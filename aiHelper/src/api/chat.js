import axios from 'axios'

/**
 * 前端唯一的后端访问层。
 *
 * <h2>设计取舍（每一条都对应旧代码的一个坑）</h2>
 *
 * <h3>① 流式为什么用 fetch 而不是 EventSource？</h3>
 * 旧代码用 <code>new EventSource(url)</code>。EventSource 有两个致命短板：
 * <ul>
 *   <li><b>只支持 GET</b>——长文本（贴代码/日志）走 GET 会撞 Tomcat 8KB 请求头上限，
 *       直接 400 <code>Request header is too large</code>，连后端都到不了；</li>
 *   <li><b>无法主动中止</b>——它只有 <code>close()</code>，没有标准的中断信号，
 *       「停止生成」这个 AI 产品几乎必备的交互做不出来。</li>
 * </ul>
 * 用 <code>fetch + ReadableStream</code> 手动解析 SSE 报文，一举解决这两个问题，
 * 并且语义上更诚实——SSE 本来就和请求方法无关，只要求响应是
 * <code>Content-Type: text/event-stream</code> 且按 <code>data: ...\n\n</code> 组织。
 *
 * <h3>② 同步接口为什么用 axios 而不是 fetch？</h3>
 * axios 自带请求/响应拦截器、超时配置、错误规范化，
 * 同步场景用它代码更短。而且旧代码把 axios 列进了 package.json 却<b>从未使用</b>，
 * 这里把它真正用起来，避免「装了个死依赖」。
 *
 * <h3>③ baseURL 为什么是 /api/ai？</h3>
 * 开发环境由 vite.config.js 把 <code>/api</code> 代理到
 * <code>http://localhost:8081</code>；生产环境应由部署方把 <code>/api</code>
 * 反向代理到后端。前端<b>不写死任何主机与端口</b>，这是它能随处部署的前提。
 */

const http = axios.create({
  baseURL: '/api/ai',
  // 普通问答模型几秒就能返回；学习方案是多 Agent 串行（约 40~50 秒），单独放宽
  timeout: 120_000
})

// 统一错误处理：把 axios 五花八门的错误归一成「一句人能看懂的话」。
// 没有这一层，每个调用处都要重复写「是超时？还是断网？还是后端 500？」
//
// ⚠ 关键一步：优先采用后端返回的 message。
// 后端的全局异常处理器已经产出了「面向用户、可直接展示」的文案，例如
//   「您的提问包含疑似『提示词注入』的内容，已被安全策略拦截。」
// 如果这里只按状态码拼一句「后端返回错误（HTTP 400）」，
// 那后端精心写好的提示就被丢掉了，用户完全不知道自己做错了什么。
// 只在后端确实没给 message 时，才退回通用文案。
http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.code === 'ECONNABORTED') {
      error.friendlyMessage = '请求超时，后端可能正在生成较长内容，请稍后重试'
    } else if (!error.response) {
      error.friendlyMessage = '无法连接后端服务，请确认后端已启动（http://localhost:8081）'
    } else {
      // 统一错误体形如 { success:false, error:'BAD_REQUEST', message:'...', path:'...' }
      const backendMessage = error.response.data?.message
      error.friendlyMessage = backendMessage || `后端返回错误（HTTP ${error.response.status}）`
      // 顺带带出机器可读的错误码，让调用方可以按码做分支
      // （例如 INPUT_TOO_LONG 时提示「请缩短问题」，INPUT_REJECTED 时提示「换个问法」）
      error.errorCode = error.response.data?.error
    }
    return Promise.reject(error)
  }
)

/**
 * 流式对话（POST + SSE），逐块回调。
 *
 * @param {Object}   options
 * @param {number}   options.memoryId 会话标识（隔离不同用户的记忆）
 * @param {string}   options.message  用户问题
 * @param {AbortSignal} options.signal 中止信号；abort 时 reader.read() 会抛 AbortError
 * @param {(chunk: string) => void} options.onChunk 每收到一个文本分块就回调一次
 */
export async function streamChat({ memoryId, message, signal, onChunk }) {
  const response = await fetch('/api/ai/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream'
    },
    body: JSON.stringify({ memoryId, message }),
    signal
  })

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }
  if (!response.body) {
    throw new Error('响应没有可读流')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  // SSE 协议：事件之间用空行（\n\n）分隔；每个事件由若干 "字段:值" 行组成，
  // 其中 data: 行承载内容。一个事件可能有多行 data:，按规范要用 \n 拼回。
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let boundary
    while ((boundary = buffer.indexOf('\n\n')) >= 0) {
      const rawEvent = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)

      const dataLines = []
      for (const line of rawEvent.split('\n')) {
        if (line.startsWith('data:')) {
          dataLines.push(line.slice(5))
        }
      }
      if (dataLines.length > 0) {
        onChunk(dataLines.join('\n'))
      }
    }
  }
}

/**
 * 带护栏的同步问答。
 * @returns {Promise<{success:boolean, answer?:string, blocked?:boolean, reason?:string}>}
 */
export async function chatSync(message) {
  const { data } = await http.post('/chat-sync', { message })
  return data
}

/**
 * 结构化学习建议。
 * @returns {Promise<{name:string, suggestionList:string[]}>}
 */
export async function getReport(message) {
  const { data } = await http.post('/report', { message })
  return data
}

/**
 * 多 Agent 学习方案（耗时长，单独放宽超时）。
 * @returns {Promise<{success:boolean, topic?:string, plan?:string, elapsedMs?:number, reason?:string}>}
 */
export async function getStudyPlan(topic) {
  const { data } = await http.post('/study-plan', { topic }, { timeout: 300_000 })
  return data
}
