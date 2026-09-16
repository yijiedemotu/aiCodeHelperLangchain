/**
 * 多会话本地存储层。
 *
 * <h3>为什么要有这一层，而不是在 App.vue 里直接操作 localStorage？</h3>
 * 序列化/反序列化、版本迁移、脏数据兜底这三件事每个都是「写一次容易、写对很难」的活。
 * 把它们关在一个纯函数模块里，App.vue 只面对「数组进、数组出」，
 * 也让这些逻辑可以在 Node 里单独测试（不依赖 Vue 和浏览器）。
 *
 * <h3>存储结构（v2）</h3>
 * <pre>
 * localStorage['aiHelperSessions'] = {
 *   version: 2,
 *   activeId: 's-1730000000000-ab12',
 *   sessions: [
 *     { id, memoryId: '123456', title: '新会话', createdAt, isDefault: true,
 *       messages: [ { id, role, content, status, source, ... } ] }
 *   ]
 * }
 * </pre>
 * 为什么把「所有会话」放一个 key 而不是「每个会话一个 key」？
 * 前者一次读写就是一致快照，后者要枚举 key、还要防止读到写了一半的半成品。
 */

/** 多会话存储键 */
export const SESSIONS_KEY = 'aiHelperSessions'

/**
 * 旧版单会话存储键，<b>必须保留</b>。
 * 改造前的代码只存一个 memoryId 在这里。老用户升级到新版本时，
 * 如果这个值被忽略，后端就会为新生成的 memoryId 找不到历史记忆——
 * 表现为「我的聊天记录怎么没了」。所以迁移逻辑要优先复用它。
 */
export const LEGACY_MEMORY_KEY = 'aiHelperMemoryId'

/** 当前模式的持久化键：用户关掉页面再回来，应该还是他上次用的那个能力 */
export const MODE_KEY = 'aiHelperMode'

/** 每个会话最多保留的消息条数，防止 localStorage 被长回答撑爆（约 5MB 上限） */
const MAX_MESSAGES_PER_SESSION = 60

/**
 * 生成随机 memoryId（保留旧版行为：一个随机整数）。
 * 用整数而不是 UUID，是因为后端历史接口按 number 解析。
 * @returns {string} 十进制数字字符串
 */
export function randomMemoryId() {
  return String(Math.floor(Math.random() * 1_000_000))
}

/** 生成会话 id。带随机后缀，避免同一毫秒创建两个会话时撞 id */
function randomSessionId() {
  return `s-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`
}

/**
 * 轻量 id 生成器。
 *
 * 新代码不用 <code>Date.now()</code> 当消息 id（旧代码的写法）——
 * 一次发送会在同一毫秒内 push 用户消息和 AI 占位消息，
 * 两者 id 相同，<code>v-for :key</code> 重复就会导致 Vue 复用错误的 DOM 节点，
 * 表现为「AI 的回复渲染到了用户气泡里」。这里用递增计数器彻底避开。
 */
let messageSeq = 0
export function nextMessageId() {
  messageSeq += 1
  return `m-${Date.now().toString(36)}-${messageSeq}`
}

/**
 * 创建一个空会话。
 * @param {{memoryId?: string, title?: string, isDefault?: boolean}} [options]
 * @returns {object} 会话对象
 */
export function createSession(options = {}) {
  return {
    id: options.id || randomSessionId(),
    memoryId: options.memoryId || randomMemoryId(),
    title: options.title || '新会话',
    createdAt: options.createdAt || Date.now(),
    isDefault: options.isDefault === true,
    messages: options.messages || []
  }
}

/**
 * 把任意输入规整成一个「可用的会话对象」。
 * localStorage 里的数据可能是老版本写的、可能被用户手动改过、
 * 也可能因为写一半被打断而残缺。这里做防御式归一：
 * 只要 memoryId 拿不到就补一个随机的，保证会话永远可用。
 * @param {any} raw 未知来源的会话数据
 * @returns {object|null} 规整后的会话；完全无法识别时返回 null
 */
function normalizeSession(raw) {
  if (!raw || typeof raw !== 'object') return null

  const memoryId = raw.memoryId != null && String(raw.memoryId).trim() !== ''
    ? String(raw.memoryId)
    : randomMemoryId()

  const messages = Array.isArray(raw.messages)
    ? raw.messages
        .filter((message) => message && typeof message.content === 'string')
        .map((message) => ({
          id: message.id || nextMessageId(),
          role: message.role === 'user' ? 'user' : 'assistant',
          content: message.content,
          // 上一次刷新页面时正在流式输出的消息，恢复后统一标记为 stopped：
          // 连接已经断了，显示「生成中」会让人一直等下去
          status: message.status === 'streaming' ? 'stopped' : message.status || 'done',
          source: message.source || 'stream',
          createdAt: message.createdAt || Date.now(),
          // report / plan / blocked 模式需要额外字段，逐个搬运
          report: message.report ?? null,
          plan: message.plan ?? null,
          elapsedMs: message.elapsedMs ?? null,
          blocked: message.blocked === true,
          reason: message.reason ?? null,
          errorMessage: message.errorMessage ?? null
        }))
    : []

  return {
    id: typeof raw.id === 'string' && raw.id ? raw.id : randomSessionId(),
    memoryId,
    title: typeof raw.title === 'string' && raw.title.trim() ? raw.title : '新会话',
    createdAt: typeof raw.createdAt === 'number' ? raw.createdAt : Date.now(),
    isDefault: raw.isDefault === true,
    messages
  }
}

/**
 * 从 localStorage 读取会话仓库；不存在或损坏时创建初始仓库。
 *
 * 迁移顺序（很重要）：
 * <ol>
 *   <li>已经有 v2 数据 → 直接用；</li>
 *   <li>没有 v2 但有旧版 <code>aiHelperMemoryId</code> → 把它变成默认会话的 memoryId，
 *       老用户的历史记忆得以延续；</li>
 *   <li>两者都没有（全新访客）→ 生成随机 memoryId，<b>并写回
 *       <code>aiHelperMemoryId</code></b>，保持与旧版本/后端脚本的兼容约定。</li>
 * </ol>
 *
 * @returns {{version:number, activeId:string, sessions:object[]}}
 */
export function loadStore() {
  let parsed = null
  try {
    const rawText = localStorage.getItem(SESSIONS_KEY)
    if (rawText) parsed = JSON.parse(rawText)
  } catch {
    // JSON 损坏（手动编辑 / 写入中断）不应该让整个应用崩掉，走重建分支即可
    parsed = null
  }

  const sessions = Array.isArray(parsed?.sessions)
    ? parsed.sessions.map(normalizeSession).filter(Boolean)
    : []

  if (sessions.length > 0) {
    const activeId = sessions.some((session) => session.id === parsed.activeId)
      ? parsed.activeId
      : sessions[0].id
    return { version: 2, activeId, sessions }
  }

  // 走到这里说明是新访客或数据不可用：建一个默认会话
  let legacyMemoryId = null
  try {
    legacyMemoryId = localStorage.getItem(LEGACY_MEMORY_KEY)
  } catch {
    legacyMemoryId = null
  }

  const memoryId =
    legacyMemoryId && String(legacyMemoryId).trim() !== ''
      ? String(legacyMemoryId)
      : randomMemoryId()

  // 关键：无论如何都把 memoryId 写回旧键。这样「全新访客也有可用默认会话」，
  // 而且这个键始终是有效数字字符串，不破坏既有约定。
  try {
    localStorage.setItem(LEGACY_MEMORY_KEY, memoryId)
  } catch {
    // 隐私模式下 localStorage 可能只读，忽略即可，不影响本次会话使用
  }

  const defaultSession = createSession({
    memoryId,
    title: '默认会话',
    isDefault: true
  })

  return { version: 2, activeId: defaultSession.id, sessions: [defaultSession] }
}

/**
 * 序列化一个会话用于落盘，并做两件清理：
 * ① 丢掉 <code>controller</code>（AbortController 无法序列化，且刷新后已失效）；
 * ② 只保留最近 N 条消息，长文档粘贴 + 长回答很容易把 5MB 配额写满，
 *    写满后 localStorage.setItem 会抛 QuotaExceededError，导致后面什么都不保存。
 * @param {object} session 会话对象
 * @returns {object} 可 JSON 化的会话
 */
function serializeSession(session) {
  return {
    id: session.id,
    memoryId: String(session.memoryId),
    title: session.title,
    createdAt: session.createdAt,
    isDefault: session.isDefault === true,
    messages: session.messages.slice(-MAX_MESSAGES_PER_SESSION).map((message) => ({
      id: message.id,
      role: message.role,
      content: message.content,
      status: message.status,
      source: message.source,
      createdAt: message.createdAt,
      report: message.report ?? null,
      plan: message.plan ?? null,
      elapsedMs: message.elapsedMs ?? null,
      blocked: message.blocked === true,
      reason: message.reason ?? null,
      errorMessage: message.errorMessage ?? null
    }))
  }
}

/**
 * 写入会话仓库。整体 try/catch：存储不可用（隐私模式、配额满）时
 * 应用应继续可用，只是不再持久化，而不是抛异常打断正在进行的对话。
 * @param {{activeId:string, sessions:object[]}} store 会话仓库
 * @returns {boolean} 是否写入成功
 */
export function saveStore(store) {
  try {
    localStorage.setItem(
      SESSIONS_KEY,
      JSON.stringify({
        version: 2,
        activeId: store.activeId,
        sessions: store.sessions.map(serializeSession)
      })
    )
    return true
  } catch {
    return false
  }
}

/** 读取上次使用的模式（可能是旧版本存的非法值，调用方负责校验） */
export function loadMode() {
  try {
    return localStorage.getItem(MODE_KEY)
  } catch {
    return null
  }
}

/** 记住当前模式 */
export function saveMode(modeId) {
  try {
    localStorage.setItem(MODE_KEY, modeId)
  } catch {
    // 同 saveStore，存不进去也不该影响使用
  }
}

/**
 * 读取指定会话的输入草稿。
 * 用户切到别的会话再切回来，没发出去的文字不应该丢。
 * @param {string} sessionId 会话 id
 */
export function loadDraft(sessionId) {
  try {
    return localStorage.getItem(`aiHelperDraft:${sessionId}`) || ''
  } catch {
    return ''
  }
}

/** 保存输入草稿；空字符串时删除键，避免积累大量空记录 */
export function saveDraft(sessionId, text) {
  try {
    if (!text) {
      localStorage.removeItem(`aiHelperDraft:${sessionId}`)
    } else {
      localStorage.setItem(`aiHelperDraft:${sessionId}`, text)
    }
  } catch {
    // 同上
  }
}

/**
 * 用首条用户消息生成会话标题。
 * 侧边栏宽度有限，超过 18 个字符就用省略号截断。
 * @param {string} text 用户第一句提问
 * @returns {string} 标题
 */
export function deriveTitle(text) {
  const flat = String(text || '')
    .replace(/\s+/g, ' ')
    .trim()
  if (!flat) return '新会话'
  return flat.length > 18 ? `${flat.slice(0, 18)}…` : flat
}
