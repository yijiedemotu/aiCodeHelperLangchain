<template>
  <div class="app">
    <Sidebar
      :sessions="store.sessions"
      :active-id="store.activeId"
      :mobile-open="sidebarOpen"
      @select="selectSession"
      @create="createNewSession"
      @remove="removeSession"
      @reset="resetAll"
      @close="sidebarOpen = false"
    />

    <section class="main">
      <header class="topbar">
        <!-- 移动端汉堡按钮；桌面端由 CSS 隐藏 -->
        <button class="topbar__menu" type="button" title="会话列表" @click="sidebarOpen = true">
          ☰
        </button>
        <div class="topbar__info">
          <div class="topbar__title">{{ activeSession?.title || '新会话' }}</div>
          <div class="topbar__sub">
            {{ modeConfig.apiPath }} · memoryId {{ activeSession?.memoryId || '-' }}
          </div>
        </div>
        <span v-if="isStreaming" class="topbar__live">● 生成中</span>
      </header>

      <ModeTabs :modes="MODES" v-model="mode" />

      <!--
        ref 放在滚动容器上（而不是消息列表上）：
        真正需要读写的 scrollTop / scrollHeight / clientHeight 都属于这个可滚动元素。
      -->
      <div class="transcript" ref="scrollerRef" @scroll.passive="onUserScroll">
        <div class="transcript__inner">
          <div v-if="visibleMessages.length === 0" class="welcome">
            <div class="welcome__icon">🤖</div>
            <h2 class="welcome__title">你好，我是 AI 编程小助手</h2>
            <p class="welcome__desc">{{ modeConfig.hint }}</p>
            <div class="welcome__chips">
              <!-- 点击示例即填入输入框：降低「不知道能问什么」的门槛 -->
              <button
                v-for="sample in currentModeSamples"
                :key="sample"
                class="chip"
                type="button"
                @click="useSample(sample)"
              >
                {{ sample }}
              </button>
            </div>
          </div>

          <ChatMessage
            v-for="message in visibleMessages"
            :key="message.id"
            :message="message"
            :disabled="isStreaming"
            @retry="retryMessage"
          />

          <!-- 「回到底部」：只在用户往上翻看历史且当前正在生成时出现，
               避免打扰阅读，又能在需要时一键追上新内容 -->
          <button
            v-if="!stickToBottom"
            class="jump-bottom"
            type="button"
            @click="stickToBottom = true"
          >
            ↓ 回到底部
          </button>
        </div>
      </div>

      <!-- 持久化失败提示：localStorage 写不进去（隐私模式/配额满）时必须让用户知道，
           否则他会以为「我的会话怎么又没了」是 bug -->
      <div v-if="storageBanner" class="banner">⚠️ {{ storageBanner }}</div>

      <ChatComposer
        ref="composerRef"
        v-model:message="input"
        :loading="isStreaming"
        :mode="modeConfig"
        :focus-trigger="focusTrigger"
        @send="send"
        @stop="stopGenerating"
      />
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import Sidebar from './components/Sidebar.vue'
import ModeTabs from './components/ModeTabs.vue'
import ChatMessage from './components/ChatMessage.vue'
import ChatComposer from './components/ChatComposer.vue'
import { MODES, DEFAULT_MODE, modeConfigOf } from './config/modes'
import { chatSync, getReport, getStudyPlan, streamChat } from './api/chat'
import {
  SESSIONS_KEY,
  LEGACY_MEMORY_KEY,
  MODE_KEY,
  createSession,
  deriveTitle,
  loadDraft,
  loadMode,
  loadStore,
  nextMessageId,
  saveDraft,
  saveMode,
  saveStore
} from './utils/sessions'

/**
 * 应用主组件：会话状态 + 四种能力的请求分发。
 *
 * <h3>为什么状态都集中在这里，子组件只做展示？</h3>
 * 「切换会话要中止正在流式输出的请求」「删除会话后要选中另一个」这类规则跨越
 * 侧栏、消息区、输入区三个组件。状态一旦分散，这些规则就得靠事件来回传，
 * 很容易出现「侧栏删了会话，消息区还显示旧内容」这种不一致。
 * 收敛到顶层后，每个规则只需要在一个函数里写一遍。
 */

// ===== 常量 =====

/** 各模式的示例提问，点击即填入输入框 */
const SAMPLES = {
  stream: ['用一段话讲清楚 Java 的 HashMap 扩容机制', '帮我写一个 LRU 缓存（带注释）', '解释一下 HTTP 和 HTTPS 的区别'],
  sync: ['Java'],
  report: ['Java 并发编程', '前端工程化', 'MySQL 索引优化'],
  plan: ['Spring Boot', '算法与数据结构', 'Kubernetes 入门']
}

// ===== 核心状态 =====

// loadStore() 内部完成了三件事：读缓存 / 迁移旧版 aiHelperMemoryId / 为新访客建默认会话
const store = ref(loadStore())
const mode = ref(pickInitialMode())
const input = ref(loadDraft(store.value.activeId))
const sidebarOpen = ref(false)
/** 递增数字，用来通知输入框重新聚焦（切换会话时用） */
const focusTrigger = ref(0)
/** 是否跟随最新内容自动滚动 */
const stickToBottom = ref(true)
/** localStorage 写入失败时的提示文字 */
const storageBanner = ref('')

const scrollerRef = ref(null)
const composerRef = ref(null)

/**
 * 当前流式请求的中止控制器。
 *
 * 用 <code>shallowRef</code> 而不是普通 ref：AbortController 内部结构复杂，
 * 深层响应式代理会给它的每个属性都套 Proxy，既浪费性能，
 * 也可能让 fetch 收到的 signal 不是它认识的那个原生对象。
 */
const streamController = shallowRef(null)

// ===== 派生状态 =====

const modeConfig = computed(() => modeConfigOf(mode.value))

const activeSession = computed(
  () => store.value.sessions.find((session) => session.id === store.value.activeId) || store.value.sessions[0]
)

const visibleMessages = computed(() => activeSession.value?.messages ?? [])

/**
 * 「是否正在生成」由消息状态推导，而不是单独维护一个 isLoading 布尔量。
 * 好处：切换到别的会话时，状态天然跟着会话走，不会出现
 * 「A 会话还在请求，切到 B 会话却发现按钮也变成停止」的错乱。
 */
const isStreaming = computed(() =>
  (activeSession.value?.messages ?? []).some((message) => message.status === 'streaming')
)

const currentModeSamples = computed(() => SAMPLES[mode.value] || [])

// ===== 模式选择 =====

/**
 * 读取上次使用的模式。
 * 必须校验：localStorage 里可能是旧版本写入的已废弃取值，
 * 直接信任会让标签栏全部未激活、发送时分发到不存在的分支。
 */
function pickInitialMode() {
  const saved = loadMode()
  return MODES.some((item) => item.id === saved) ? saved : DEFAULT_MODE
}

// 切换模式时：记住选择，并把焦点还给输入框，方便直接开问
watch(mode, (value) => {
  saveMode(value)
  focusTrigger.value += 1
})

// ===== 持久化 =====

/**
 * 任何消息变化都落盘。
 * deep: true 是必须的——流式输出是在<b>同一条消息对象</b>上累加 content，
 * 消息数组本身的引用没变，浅层 watch 收不到通知，刷新后回答就丢了。
 *
 * flush 用默认的 'pre'（批量到下一帧）而<b>不是</b> 'sync'：
 * 'sync' 会让每个 SSE 分块都触发一次「整包 JSON.stringify + localStorage 写入」，
 * 一次长回答有 100+ 个分块，就是 100+ 次同步磁盘写，输入和滚动都会发卡。
 * 批量写入的代价是「刷新页面可能丢掉最后一帧的内容」，
 * 这一点由 beforeunload 里的显式 saveStore 兜住，不会真的丢。
 */
watch(
  store,
  () => {
    const ok = saveStore(store.value)
    storageBanner.value = ok ? '' : '本地存储写入失败，本次会话无法保存（可能处于隐私模式或存储空间已满）'
  },
  { deep: true }
)

// 输入草稿按会话保存，切回来还能接着写
watch(input, (value) => {
  saveDraft(store.value.activeId, value)
})

// ===== 自动滚动 =====

/**
 * 判断用户是否「还贴在底部」。
 * 阈值 80px 而不是 0：字体渲染、行高取整会让 scrollHeight 与
 * scrollTop + clientHeight 差上几个像素，用 0 会导致永远判定为「离开底部」。
 */
function onUserScroll() {
  const el = scrollerRef.value
  if (!el) return
  stickToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

/**
 * 自动滚到底。
 * 只在 stickToBottom 为真时执行——这就是「不要和用户抢滚动条」：
 * 用户往上翻看历史时，新内容不再强行把他拽回底部。
 */
function scrollToBottomIfSticky() {
  if (!stickToBottom.value) return
  const el = scrollerRef.value
  if (!el) return
  el.scrollTop = el.scrollHeight
}

// ===== 会话操作 =====

function createNewSession() {
  stopGenerating()
  const session = createSession()
  store.value.sessions.unshift(session)
  store.value.activeId = session.id
  input.value = ''
  stickToBottom.value = true
  sidebarOpen.value = false
  focusTrigger.value += 1
}

function selectSession(id) {
  if (id === store.value.activeId) {
    sidebarOpen.value = false
    return
  }
  // 切走之前先停掉当前会话的流：否则那个请求会继续往后台会话里写内容，
  // 而用户已经看不到它了，白白消耗 token。
  stopGenerating()
  store.value.activeId = id
  input.value = loadDraft(id)
  stickToBottom.value = true
  sidebarOpen.value = false
  focusTrigger.value += 1
}

function removeSession(id) {
  const target = store.value.sessions.find((session) => session.id === id)
  if (!target) return

  if (id === store.value.activeId) stopGenerating()

  // 本地 UI 删除不需要二次确认弹窗（教学项目，弹窗反而增加打断感），
  // 但被删会话如果是当前会话，必须把 activeId 迁到别的会话上，
  // 否则 activeSession 计算属性会拿到 undefined，整页白屏。
  store.value.sessions = store.value.sessions.filter((session) => session.id !== id)

  if (store.value.sessions.length === 0) {
    // 永远保留至少一个会话，「零会话」状态没有意义且会让所有派生状态失效
    store.value.sessions = [createSession({ isDefault: true, title: '默认会话' })]
  }

  if (id === store.value.activeId) {
    store.value.activeId = store.value.sessions[0].id
    input.value = loadDraft(store.value.activeId)
  }
}

/** 清空本地所有数据，回到「全新访客」状态（用于演示与排障） */
function resetAll() {
  stopGenerating()
  try {
    localStorage.removeItem(SESSIONS_KEY)
    localStorage.removeItem(LEGACY_MEMORY_KEY)
    localStorage.removeItem(MODE_KEY)
  } catch {
    // 存储不可用时忽略，下面的 loadStore 会自动重建默认会话
  }
  store.value = loadStore()
  mode.value = DEFAULT_MODE
  input.value = ''
  storageBanner.value = ''
  stickToBottom.value = true
  focusTrigger.value += 1
}

// ===== 发送与请求分发 =====

function useSample(sample) {
  input.value = sample
  focusTrigger.value += 1
}

function send() {
  const text = input.value.trim()
  if (!text || isStreaming.value) return

  const session = activeSession.value
  if (!session) return

  session.messages.push({
    id: nextMessageId(),
    role: 'user',
    content: text,
    status: 'done',
    source: mode.value,
    createdAt: Date.now()
  })

  // 首条用户消息顺便当会话标题，侧栏才有辨识度
  if (session.title === '新会话' || session.messages.length === 1) {
    session.title = deriveTitle(text)
  }

  // 清空输入框与草稿
  input.value = ''
  saveDraft(session.id, '')

  // 立刻回到跟随状态：用户刚发完消息，最想看到的就是回答
  stickToBottom.value = true
  scrollToBottomIfSticky()

  runRequest({ role: 'assistant', content: text, source: mode.value }, { focusAfter: true })
}

/**
 * 重试一条失败的消息。
 * 把同一条 assistant 消息重置后重跑，而不是再 push 一对新消息——
 * 这样对话记录里不会留下一堆失败气泡。
 */
function retryMessage(messageId) {
  const session = activeSession.value
  if (!session || isStreaming.value) return

  const target = session.messages.find((message) => message.id === messageId)
  if (!target) return

  // 重试时沿用原始提问；requestMessage 在消息创建时就写好了，
  // 这样即使之后用户又发了别的消息也不影响重试内容
  const originalText =
    target.requestMessage ||
    [...session.messages].reverse().find((message) => message.role === 'user')?.content

  if (!originalText) return

  target.content = ''
  target.status = 'streaming'
  target.errorMessage = null
  target.blocked = false
  target.reason = null
  target.report = null
  target.plan = null
  target.elapsedMs = null

  stickToBottom.value = true
  runRequest({ role: 'assistant', content: originalText, source: target.source || mode.value })
}

/**
 * 统一的请求入口：按 source 分发到四个后端能力之一。
 *
 * @param {{role:string, content:string, source:string}} request 请求描述
 * @param {{focusAfter?:boolean}} [options] focusAfter 为真时在完成后重新聚焦输入框
 */
function runRequest(request, options = {}) {
  if (request.source === 'stream') {
    runStream(request, options)
  } else {
    runNonStream(request, options)
  }
}

/**
 * 把「刚 push 进响应式数组的消息」取回为<b>响应式代理</b>，后续只改这个代理。
 *
 * <h3>为什么必须这样做？（这里踩过一个很隐蔽的坑）</h3>
 * Vue 3 的 <code>reactive()</code> 是「惰性包装」：<code>arr.push(obj)</code> 存进去的是
 * 原始对象，只有当你<b>从数组里读出来</b>时才会得到它的 Proxy。
 * 因此如果闭包里一直拿着 push 时的那个原始对象去改：
 * <pre>
 *   const assistant = { content: '' }
 *   session.messages.push(assistant)
 *   assistant.content += chunk   // ✗ 改的是原始对象
 * </pre>
 * 组件模板读到的却是数组里那个 Proxy。实测中这种写法<b>不会触发重新渲染</b>——
 * 表现为「数据明明在涨（onChunk 被调了 84 次、content 已 1154 字），
 * 气泡里却一直停在『正在思考…』」，而且控制台毫无报错。
 * 这类 bug 打包、类型检查都发现不了，只有真正把界面跑起来才能看到。
 *
 * 正确写法是从数组读回代理再改：<code>messages[length - 1].content += chunk</code>。
 *
 * @param {Array} messages 消息数组（响应式）
 * @returns {object} 该数组最后一条消息的响应式代理；数组为空时返回一个兜底对象
 */
function lastMessage(messages) {
  return messages[messages.length - 1] || {}
}

/**
 * 流式对话：POST + SSE，边收边渲染。
 * @param {{content:string, source:string}} request 请求描述
 * @param {{focusAfter?:boolean}} options 选项
 */
async function runStream(request, options) {
  const session = activeSession.value
  if (!session) return

  const controller = new AbortController()
  streamController.value = controller

  session.messages.push({
    id: nextMessageId(),
    role: 'assistant',
    content: '',
    status: 'streaming',
    source: 'stream',
    createdAt: Date.now(),
    requestMessage: request.content,
    errorMessage: null,
    blocked: false,
    reason: null,
    report: null,
    plan: null,
    elapsedMs: null
  })

  // ★ 关键：从数组读回「响应式代理」再改，而不是持有 push 时的原始对象。
  // 详见 lastMessage() 的注释——这正是之前流式内容不显示的根因。
  const assistant = () => lastMessage(session.messages)

  try {
    await streamChat({
      memoryId: Number(session.memoryId),
      message: request.content,
      signal: controller.signal,
      onChunk(chunk) {
        // 每个分块到达就追加到代理上，Vue 才能侦测到并重渲染
        assistant().content += chunk
        scrollToBottomIfSticky()
      }
    })

    assistant().status = assistant().content ? 'done' : 'error'
    if (!assistant().content) {
      assistant().errorMessage = '后端没有返回任何内容，请重试'
    }
  } catch (error) {
    // ★ 关键：用户主动「停止生成」时 fetch 会抛 AbortError。
    // 这是预期内的正常操作，不是错误——必须静默处理，
    // 并保留已经收到的内容（不能清空，用户的目的是「够了，停下」而不是「丢弃」）。
    if (error?.name === 'AbortError') {
      assistant().status = 'stopped'
    } else {
      assistant().status = 'error'
      // 优先用 axios 拦截器归一过的 friendlyMessage；fetch 抛出的错误没有这层包装，
      // 于是回落到「HTTP 500」这类原始信息，最后再兜一句通用话术。
      assistant().errorMessage = error?.friendlyMessage || error?.message || '请求失败，请稍后重试'
    }
  } finally {
    streamController.value = null
    scrollToBottomIfSticky()
    if (options.focusAfter) composerRef.value?.focus()
  }
}

/**
 * 非流式请求：护栏问答 / 学习建议 / 学习方案。
 * 三个接口的响应结构不同，所以在这里分派，而不是塞进 api/chat.js——
 * 那个文件只负责「发请求拿数据」，把 UI 语义（怎么显示 blocked、怎么摆列表）
 * 混进去会让它不再是一个干净的数据层。
 *
 * @param {{content:string, source:string}} request 请求描述
 * @param {{focusAfter?:boolean}} options 选项
 */
async function runNonStream(request, options) {
  const session = activeSession.value
  if (!session) return

  const startedAt = Date.now()
  session.messages.push({
    id: nextMessageId(),
    role: 'assistant',
    content: '',
    // 统一先置为 streaming，非流式接口借这个状态复用「生成中」的交互
    // （禁用重试、显示处理中）。用户看到的是「正在思考」，语义一致。
    status: 'streaming',
    source: request.source,
    createdAt: Date.now(),
    requestMessage: request.content,
    errorMessage: null,
    blocked: false,
    reason: null,
    report: null,
    plan: null,
    elapsedMs: null
  })
  // 同样从数组读回代理再改，理由见 lastMessage() 的注释
  const assistant = () => lastMessage(session.messages)
  scrollToBottomIfSticky()

  try {
    if (request.source === 'sync') {
      const data = await chatSync(request.content)
      if (data?.blocked) {
        // 护栏拦截是正常业务结果：走 blocked 分支而不是 error 分支
        assistant().blocked = true
        assistant().reason = data.reason || ''
        assistant().content = ''
      } else if (data?.success === false) {
        assistant().status = 'error'
        assistant().errorMessage = data.reason || '后端拒绝了这次请求'
      } else {
        assistant().content = data?.answer || ''
        if (!assistant().content) {
          assistant().status = 'error'
          assistant().errorMessage = '后端返回了空答案，请重试'
        }
      }
    } else if (request.source === 'report') {
      const data = await getReport(request.content)
      const suggestionList = Array.isArray(data?.suggestionList) ? data.suggestionList : []
      assistant().report = {
        name: data?.name || '学习建议',
        suggestionList
      }
      if (suggestionList.length === 0) {
        assistant().status = 'error'
        assistant().errorMessage = '后端返回了空建议列表，请重试'
      }
    } else if (request.source === 'plan') {
      const data = await getStudyPlan(request.content)
      if (data?.success === false) {
        assistant().status = 'error'
        assistant().errorMessage = data.reason || '学习方案生成失败'
      } else {
        assistant().plan = {
          topic: data?.topic || request.content,
          plan: data?.plan || ''
        }
        if (!assistant().plan.plan) {
          assistant().status = 'error'
          assistant().errorMessage = '后端返回了空方案，请重试'
        }
      }
    }

    if (assistant().status === 'streaming') assistant().status = 'done'
  } catch (error) {
    assistant().status = 'error'
    assistant().errorMessage = error?.friendlyMessage || error?.message || '请求失败，请稍后重试'
  } finally {
    // 记录真实耗时（学习方案约 40~50 秒，让用户直观看到多 Agent 编排的代价）
    assistant().elapsedMs = Date.now() - startedAt
    scrollToBottomIfSticky()
    if (options.focusAfter) composerRef.value?.focus()
  }
}

/** 停止生成：中止流并保留已收到的内容 */
function stopGenerating() {
  if (streamController.value) {
    streamController.value.abort()
    streamController.value = null
  }
}

// ===== 生命周期 =====

function handleBeforeUnload() {
  saveStore(store.value)
  // 页面关闭时中止请求，避免留下悬空的连接
  stopGenerating()
}

onMounted(() => {
  window.addEventListener('beforeunload', handleBeforeUnload)
  // 首屏直接滚到底（恢复的会话可能很长）
  scrollToBottomIfSticky()
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload)
  stopGenerating()
})
</script>

<style scoped>
/**
 * 主题变量。<b>这里是全项目唯一定义颜色的地方。</b>
 * 组件里只用 var(--ah-*) 取值，因此深色模式只需覆盖这一处，
 * 不必去每个组件里写 @media (prefers-color-scheme: dark)。
 *
 * 为什么不用 color-scheme: light dark 直接交给浏览器？
 * 因为那样原生控件会跟随系统变暗，但我们的自定义背景/文字仍是写死的浅色，
 * 两者拼在一起就是「白底白字」。显式定义两套变量才能保证对比度可控。
 */
.app {
  --ah-bg: #f6f7f9;
  --ah-surface: #ffffff;
  --ah-sidebar-bg: #fbfbfd;
  --ah-input-bg: #ffffff;
  --ah-border: #e6e8eb;
  --ah-border-strong: #d3d7dd;
  --ah-text: #1f2430;
  --ah-text-muted: #5b6472;
  --ah-text-faint: #8b939f;
  --ah-hover: #f0f1f4;
  --ah-primary: #4c6ef5;
  --ah-primary-strong: #3b5bdb;
  --ah-primary-soft: rgba(76, 110, 245, 0.14);
  --ah-bubble-ai: #ffffff;
  --ah-bubble-user: #4c6ef5;
  --ah-avatar-bg: #eef0f4;
  --ah-danger: #e03131;
  --ah-danger-strong: #c92a2a;
  --ah-danger-soft: #fff5f5;
  --ah-warn: #e8890c;
  --ah-code-bg: #f5f6f8;
  --ah-code-header: #eceef1;
  --ah-shadow-lg: 0 10px 30px rgba(15, 20, 30, 0.16);

  display: flex;
  height: 100%;
  background: var(--ah-bg);
  color: var(--ah-text);
}

/* 深色模式：只覆盖变量。注意深色下用户气泡用较亮的蓝，
   保证白字有足够对比度；AI 气泡则用接近背景的深灰而不是纯黑，
   避免「黑色卡片浮在灰色背景上」的割裂感。 */
@media (prefers-color-scheme: dark) {
  .app {
    --ah-bg: #16181d;
    --ah-surface: #1d2027;
    --ah-sidebar-bg: #1a1d23;
    --ah-input-bg: #23262e;
    --ah-border: #2c3038;
    --ah-border-strong: #3a3f49;
    --ah-text: #e7e9ee;
    --ah-text-muted: #a8b0bd;
    --ah-text-faint: #7d8794;
    --ah-hover: #252932;
    --ah-primary: #5c7cfa;
    --ah-primary-strong: #4c6ef5;
    --ah-primary-soft: rgba(92, 124, 250, 0.2);
    --ah-bubble-ai: #23262e;
    --ah-bubble-user: #3b5bdb;
    --ah-avatar-bg: #262a33;
    --ah-danger: #ff6b6b;
    --ah-danger-strong: #ff8787;
    --ah-danger-soft: rgba(255, 107, 107, 0.12);
    --ah-warn: #ffc078;
    --ah-code-bg: #15171c;
    --ah-code-header: #1f2229;
    --ah-shadow-lg: 0 10px 30px rgba(0, 0, 0, 0.5);
  }
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--ah-bg);
}

.topbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 20px 0;
}

.topbar__menu {
  display: none;
  border: 1px solid var(--ah-border);
  background: var(--ah-surface);
  color: var(--ah-text);
  border-radius: 8px;
  padding: 4px 9px;
  font-size: 15px;
}

.topbar__info {
  min-width: 0;
  flex: 1;
}

.topbar__title {
  font-size: 14.5px;
  font-weight: 600;
  color: var(--ah-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.topbar__sub {
  margin-top: 1px;
  font-size: 11px;
  color: var(--ah-text-faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.topbar__live {
  flex-shrink: 0;
  font-size: 11.5px;
  color: var(--ah-primary);
}

/* 滚动容器：min-height: 0 是 flex 子项能正确滚动的关键，
   否则内容会把容器撑高，overflow 永远不生效（flex 经典坑） */
.transcript {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 20px 8px;
  /* 滚动时贴着底部的判定依赖原生滚动事件，交给浏览器平滑即可 */
  overscroll-behavior: contain;
}

.transcript__inner {
  max-width: 1080px;
  margin: 0 auto;
  position: relative;
}

.welcome {
  text-align: center;
  padding: 48px 16px 32px;
}

.welcome__icon {
  font-size: 44px;
  line-height: 1;
}

.welcome__title {
  margin: 14px 0 6px;
  font-size: 19px;
  font-weight: 650;
  color: var(--ah-text);
}

.welcome__desc {
  margin: 0 auto 20px;
  max-width: 460px;
  font-size: 13.5px;
  color: var(--ah-text-muted);
}

.welcome__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}

.chip {
  border: 1px solid var(--ah-border);
  background: var(--ah-surface);
  color: var(--ah-text-muted);
  border-radius: 999px;
  padding: 6px 14px;
  font-size: 13px;
  transition: all 0.15s ease;
}

.chip:hover {
  border-color: var(--ah-primary);
  color: var(--ah-primary);
  background: var(--ah-primary-soft);
}

.jump-bottom {
  position: sticky;
  bottom: 8px;
  left: 50%;
  transform: translateX(-50%);
  display: block;
  margin: 0 auto;
  border: 1px solid var(--ah-border);
  background: var(--ah-surface);
  color: var(--ah-primary);
  border-radius: 999px;
  padding: 6px 16px;
  font-size: 12.5px;
  box-shadow: 0 2px 10px rgba(15, 20, 30, 0.12);
}

.banner {
  margin: 0 20px 8px;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--ah-danger-soft);
  color: var(--ah-danger);
  font-size: 12.5px;
}

/**
 * Markdown 内容样式。
 * 用 :deep() 是因为这些元素由 v-html 生成，不在本组件的 scoped 属性作用域内——
 * 不加深选择器，样式对它们完全不生效（这是 scoped 样式最常见的「为什么不生效」）。
 */
.transcript :deep(h1),
.transcript :deep(h2),
.transcript :deep(h3),
.transcript :deep(h4) {
  margin: 14px 0 8px;
  line-height: 1.3;
  font-weight: 650;
}

.transcript :deep(h1) {
  font-size: 19px;
}

.transcript :deep(h2) {
  font-size: 17px;
}

.transcript :deep(h3) {
  font-size: 15.5px;
}

.transcript :deep(p) {
  margin: 8px 0;
  line-height: 1.75;
}

/* 首尾元素去掉外边距，否则气泡上下会多出一截空白 */
.transcript :deep(> div > .msg:first-child p:first-child) {
  margin-top: 0;
}

.transcript :deep(ul),
.transcript :deep(ol) {
  margin: 8px 0;
  padding-left: 22px;
  line-height: 1.75;
}

.transcript :deep(li) {
  margin: 3px 0;
}

.transcript :deep(blockquote) {
  margin: 10px 0;
  padding: 4px 14px;
  border-left: 3px solid var(--ah-border-strong);
  color: var(--ah-text-muted);
  background: var(--ah-hover);
  border-radius: 0 6px 6px 0;
}

.transcript :deep(a) {
  color: var(--ah-primary);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.transcript :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 10px 0;
  font-size: 13.5px;
  /* 宽表格内部横向滚动，不撑破气泡 */
  display: block;
  overflow-x: auto;
}

.transcript :deep(th),
.transcript :deep(td) {
  border: 1px solid var(--ah-border);
  padding: 6px 10px;
  text-align: left;
}

.transcript :deep(th) {
  background: var(--ah-hover);
  font-weight: 600;
}

.transcript :deep(hr) {
  border: none;
  border-top: 1px solid var(--ah-border);
  margin: 14px 0;
}

/* 行内代码 */
.transcript :deep(code) {
  font-family: 'JetBrains Mono', 'Cascadia Code', Consolas, 'Courier New', monospace;
  font-size: 12.8px;
  background: var(--ah-code-bg);
  padding: 1px 5px;
  border-radius: 4px;
}

.transcript :deep(.code-block) {
  margin: 10px 0;
  border: 1px solid var(--ah-border);
  border-radius: 10px;
  overflow: hidden;
  background: var(--ah-code-bg);
}

.transcript :deep(.code-block__header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 10px;
  background: var(--ah-code-header);
  border-bottom: 1px solid var(--ah-border);
}

.transcript :deep(.code-block__lang) {
  font-size: 11px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--ah-text-faint);
}

.transcript :deep(.code-block__copy) {
  border: 1px solid var(--ah-border-strong);
  background: var(--ah-surface);
  color: var(--ah-text-muted);
  border-radius: 6px;
  padding: 1px 9px;
  font-size: 11.5px;
  line-height: 1.6;
  transition: all 0.15s ease;
}

.transcript :deep(.code-block__copy:hover) {
  color: var(--ah-primary);
  border-color: var(--ah-primary);
}

.transcript :deep(.code-block__copy.is-copied) {
  color: #fff;
  background: var(--ah-primary);
  border-color: var(--ah-primary);
}

.transcript :deep(pre) {
  margin: 0;
  padding: 12px 14px;
  overflow-x: auto;
}

/* 代码块里的 code 不再套一层底色，否则会出现「双层背景」 */
.transcript :deep(pre code) {
  background: transparent;
  padding: 0;
  font-size: 12.8px;
  line-height: 1.65;
}

.transcript :deep(img) {
  max-width: 100%;
  border-radius: 8px;
}

@media (max-width: 860px) {
  .topbar {
    padding: 10px 12px 0;
  }

  .topbar__menu {
    display: inline-block;
  }

  .transcript {
    padding: 12px 12px 6px;
  }

  .banner {
    margin: 0 12px 8px;
  }
}
</style>
