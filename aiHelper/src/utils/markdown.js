import { marked } from 'marked'
import DOMPurify from 'dompurify'

/**
 * Markdown 渲染 + XSS 消毒。
 *
 * <h3>为什么要引这两个库？</h3>
 * 旧代码用一段正则 <code>formatMessage()</code> 拼 HTML 再 <code>v-html</code> 插进页面：
 * <pre>
 *   content.replace(/\n/g, '&lt;br&gt;').replace(/```([\s\S]*?)```/g, '&lt;pre&gt;&lt;code&gt;$1&lt;/code&gt;&lt;/pre&gt;')
 * </pre>
 * 它有两个致命问题：
 * <ol>
 *   <li><b>XSS</b>——模型输出（以及用户输入被回显时）里的
 *       <code>&lt;img src=x onerror=alert(1)&gt;</code> 会被原样插入 DOM 并执行。
 *       大模型的输出<b>不可信</b>：它可能被提示词注入诱导，也可能只是原样复述了
 *       网页/代码里的恶意片段。</li>
 *   <li><b>渲染能力约等于零</b>——列表、表格、引用、代码语言标注全都不支持，
 *       而编程助手的回答里恰恰全是这些东西。</li>
 * </ol>
 *
 * <h3>安全模型：marked 负责「解析」，DOMPurify 负责「信任裁决」</h3>
 * 关键认识是——<b>marked 不是安全组件</b>，它默认允许原始 HTML 直通（为了兼容
 * CommonMark）。所以流程必须是「先 parse 成 HTML，再整体 sanitize」，
 * 而不是「先 sanitize 输入再 parse」（后者会被各种编码绕过）。
 * <code>DOMPurify.sanitize()</code> 基于浏览器真实 DOM 解析树做白名单过滤，
 * 会剥掉 <code>on*</code> 事件属性、<code>javascript:</code> 协议、<code>&lt;script&gt;</code> 等。
 *
 * <h3>为什么自定义 renderer 而不是写完再正则替换？</h3>
 * 解析阶段我们就已经知道「哪段是代码块、语言是什么」，直接产出带
 * <code>code-block</code> 包裹层的结构最准确。若改成事后正则匹配
 * <code>&lt;pre&gt;</code>，代码本身含有反引号或 <code>&lt;/pre&gt;</code> 时就会错乱。
 */

// marked 的全局配置。GFM（表格、任务列表、删除线）是现代 Markdown 的通行标准；
// breaks: true 是因为中文技术问答里单换行常被当作「换行」而非「新段落」。
marked.setOptions({
  gfm: true,
  breaks: true
})

/**
 * HTML 转义：把可能破坏标签结构的字符变成实体。
 *
 * <h3>为什么这里不用 DOMPurify？</h3>
 * 一开始我把这段写成 <code>DOMPurify.sanitize(text, {ALLOWED_TAGS: []})</code>，
 * 那是<b>用错了工具</b>：DOMPurify 是「按白名单删标签」，不是「把文本转义成实体」。
 * 它会把 <code>&amp;</code> 原样留下、把 <code>"</code> 原样留下，
 * 放进 <code>data-code="..."</code> 里照样会被引号截断，属性注入依旧成立。
 * 转义是纯字符串操作，五条 replace 就够，且不依赖 DOM——
 * 这也让渲染逻辑能在 Node 里跑测试。
 *
 * @param {string} value 任意文本
 * @returns {string} 可安全放进标签体或双引号属性值的文本
 */
function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;') // 必须第一个替换，否则会把下面产生的实体的 & 再转义一次
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/**
 * 语言名只保留「字母/数字/加号/井号/点/短横线」。
 *
 * <h3>为什么要白名单裁剪而不是转义？</h3>
 * 语言名要进 <code>class="language-xxx"</code>。class 属性里出现空格会
 * 拆出多余的类名，出现引号/尖括号则可能撑破属性。既然合法语言名本来就只由
 * 这几种字符组成，直接「裁掉非法字符」比「转义后塞进去」更简单也更安全：
 * 输出集合被收敛成一个不可能有害的字符集。
 *
 * @param {string} lang marked 给出的语言标识（如 "js" / "cpp" / ""）
 * @returns {string} 裁剪后的语言名，可能为空串
 */
function sanitizeLanguage(lang) {
  return String(lang || '')
    .toLowerCase()
    .replace(/[^a-z0-9+#.-]/g, '')
    .slice(0, 24)
}

const renderer = new marked.Renderer()

/**
 * 自定义代码块渲染：包一层 <code>.code-block</code>，含语言标签栏 + pre/code。
 *
 * <h3>复制按钮为什么不在这个字符串里生成？</h3>
 * 按钮要绑定 click 事件，而 <code>v-html</code> 插进来的 HTML 无法绑定 Vue 事件，
 * 只能写内联 <code>onclick</code>——那恰恰是我们要消灭的 XSS 通道。
 * 所以渲染阶段只产出「语义正确的骨架」，按钮由 <code>v-markdown</code> 指令在
 * DOM 就绪后补上，按钮直接读 <code>code.textContent</code> 取原文，
 * 这样<b>完全不需要把代码原文再存一份到属性里</b>，省掉一整类转义问题。
 */
renderer.code = function ({ text, lang }) {
  const language = sanitizeLanguage(lang)
  // 没有语言标注时显示 text，让标签栏不留空白
  const label = language || 'text'
  return (
    `<div class="code-block">` +
    `<div class="code-block__header">` +
    `<span class="code-block__lang" data-language="${language}">${escapeHtml(label)}</span>` +
    `</div>` +
    `<pre><code class="language-${language}">${escapeHtml(text)}</code></pre>` +
    `</div>`
  )
}

/**
 * 渲染 Markdown 为「已消毒」的 HTML 字符串。
 *
 * @param {string} raw 模型/用户提供的原始文本
 * @returns {string} 可以安全交给 v-html 的 HTML
 */
export function renderMarkdown(raw) {
  if (!raw) return ''

  const html = marked.parse(raw, { renderer })

  // marked 仅在启用 async 扩展时才返回 Promise；本项目未启用。
  // 这里做一次防御：万一拿到 Promise，宁可退回纯文本展示，也不要把 Promise 字符串化。
  if (typeof html !== 'string') {
    return DOMPurify.sanitize(escapeHtml(raw))
  }

  return DOMPurify.sanitize(html, {
    // 允许 class：代码块的 language-xxx、表格对齐等都依赖它
    ADD_ATTR: ['class', 'target', 'rel'],
    ALLOW_DATA_ATTR: true,
    // 聊天场景完全用不到表单/嵌入类标签，直接禁掉以缩小攻击面
    FORBID_TAGS: ['form', 'input', 'button', 'iframe', 'object', 'embed', 'style'],
    FORBID_ATTR: ['style']
  })
}

export { escapeHtml }
