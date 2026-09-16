import { copyText } from '../utils/clipboard'
import { renderMarkdown } from '../utils/markdown'

/**
 * <code>v-markdown</code> 指令：把 Markdown 原文安全地渲染进元素，并给代码块补上复制按钮。
 *
 * <h3>为什么用「指令」而不是「组件 + v-html」？</h3>
 * 只做渲染的话，<code>&lt;div v-html="renderMarkdown(x)"&gt;</code> 就够了。
 * 但复制按钮必须在 DOM 插入之后才能补——按钮要绑 click 事件，
 * 而 v-html 生成的 HTML 无法绑定 Vue 事件（只能写内联 onclick，那正是 XSS 温床）。
 * 指令恰好提供了 <code>mounted</code> / <code>updated</code> 两个「DOM 已经是最新」的时机，
 * 于是渲染与增强可以收敛到一个地方，调用方只写 <code>v-markdown="text"</code>。
 *
 * <h3>为什么按钮读 code.textContent，而不是从属性里取原文？</h3>
 * 如果把原始代码存进 <code>data-code</code> 属性，就要处理「属性值里的引号/换行」
 * 这一整套转义问题，任何一点疏漏都是属性注入。
 * 而 <code>&lt;code&gt;</code> 的 textContent 本身就是浏览器解析后的真实文本——
 * 转义早由 HTML 解析器完成了，读取时也自动还原成原文。
 * 「让专业的人干专业的事」，这里只负责搬运。
 */

/** 每个按钮的「已复制」复位定时器。用 WeakMap 是为了不阻止按钮元素被回收 */
const resetTimers = new WeakMap()

/**
 * 「已复制」提示保持多久。小于 1 秒容易被忽略，大于 2 秒又显得像卡住了
 */
const COPIED_FEEDBACK_MS = 1400

/**
 * 浏览器是否具备复制能力。
 * 两者都没有（极老的浏览器 / 被策略禁用）时干脆不注入按钮——
 * 给用户一个点了没反应的按钮，比没有按钮更糟。
 */
function canCopy() {
  return Boolean(navigator.clipboard || document.queryCommandSupported?.('copy'))
}

/**
 * 给尚未增强的代码块插入复制按钮。
 *
 * <h3>为什么要检查 data-enhanced？</h3>
 * 每次 updated 都会调用本函数。如果不做标记，按钮会被无限追加；
 * 更隐蔽的是：流式输出时每来一个分块都会重建整个 innerHTML，
 * 于是按钮会在每次刷新时被重建一次——用标记跳过已处理的块，
 * 把开销控制在「真正新增的代码块」上。
 *
 * @param {HTMLElement} root 使用本指令的元素
 */
function enhanceCodeBlocks(root) {
  const blocks = root.querySelectorAll('.code-block')
  blocks.forEach((block) => {
    const code = block.querySelector('code')
    // 代码块内部可能没有 pre（理论上不会，但 marked 的自定义渲染器改过就别信），跳过即可
    if (!code) return

    // 语言标签为空时补一句「text」，避免标签栏空着显得像渲染坏了
    const langLabel = block.querySelector('.code-block__lang')
    if (langLabel && !langLabel.textContent.trim()) {
      langLabel.textContent = 'text'
    }

    const header = block.querySelector('.code-block__header')
    if (!header || header.dataset.copyReady === 'true') return
    header.dataset.copyReady = 'true'

    // 无复制能力的浏览器直接不注入，功能降级但不出现坏按钮
    if (!canCopy()) return

    const button = document.createElement('button')
    button.type = 'button'
    button.className = 'code-block__copy'
    button.textContent = '复制'
    button.title = '复制此代码块'
    button.dataset.copyButton = 'true'

    // 用事件委托（监听器挂在根元素上）而非给每个按钮单独绑定：
    // 这样每次 innerHTML 重建都不需要重新绑监听，也不会泄漏监听器。
    header.appendChild(button)
  })
}

/**
 * 处理一次复制点击。
 * @param {MouseEvent} event 点击事件
 * @param {HTMLElement} root 指令根元素
 */
async function handleCopyClick(event, root) {
  const button = event.target.closest?.('[data-copy-button]')
  if (!button || !root.contains(button)) return

  const code = button.closest('.code-block')?.querySelector('code')
  if (!code) return

  const ok = await copyText(code.textContent)

  // 清理上一个未到期的定时器，避免连点两次后第一次的定时器把提示提前抹掉
  const existing = resetTimers.get(button)
  if (existing) clearTimeout(existing)

  button.textContent = ok ? '已复制' : '复制失败'
  button.classList.toggle('is-copied', ok)

  const timer = setTimeout(() => {
    button.textContent = '复制'
    button.classList.remove('is-copied')
    resetTimers.delete(button)
  }, COPIED_FEEDBACK_MS)
  resetTimers.set(button, timer)
}

/**
 * 写入内容并执行增强。
 * @param {HTMLElement} el 指令根元素
 * @param {string} value Markdown 原文
 */
function render(el, value) {
  const raw = typeof value === 'string' ? value : ''
  const html = renderMarkdown(raw)

  // 内容完全相同时不碰 DOM：流式输出时 value 每次都变，但例如
  // 父组件因为别的原因重渲染时 value 可能没变，这时重建 innerHTML
  // 会让代码块里的文本选区丢失（用户正在选代码复制时会很恼火）。
  if (el.dataset.renderedRaw === raw) {
    enhanceCodeBlocks(el)
    return
  }
  el.dataset.renderedRaw = raw
  el.innerHTML = html
  enhanceCodeBlocks(el)
}

export const vMarkdown = {
  mounted(el, binding) {
    // 挂载时绑一次委托监听；el 在整个生命周期内是同一个元素，不需要重复绑定
    el.addEventListener('click', (event) => handleCopyClick(event, el))
    render(el, binding.value)
  },

  updated(el, binding) {
    // 只有内容变化才需要重渲染，否则连 el.innerHTML 都不该碰
    if (binding.value !== binding.oldValue) {
      render(el, binding.value)
    } else {
      enhanceCodeBlocks(el)
    }
  },

  unmounted(el) {
    // 元素销毁时清掉所有「已复制」定时器。不清理的话，
    // 定时器回调会持有已脱离文档的按钮元素的引用；
    // 更重要的是，若按钮所在的 DOM 被浏览器节流复用，延迟设文字会造成串台。
    el.querySelectorAll('[data-copy-button]').forEach((button) => {
      const timer = resetTimers.get(button)
      if (timer) {
        clearTimeout(timer)
        resetTimers.delete(button)
      }
    })
  }
}
