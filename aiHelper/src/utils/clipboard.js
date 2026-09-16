/**
 * 剪贴板写入工具。
 *
 * <h3>为什么要写一个 fallback？</h3>
 * <code>navigator.clipboard</code> 只在<b>安全上下文</b>（https 或 localhost）下可用。
 * 本项目开发时是 http://localhost:5173，没问题；但一旦部署到
 * <code>http://192.168.x.x</code> 这种内网地址演示，<code>navigator.clipboard</code>
 * 就是 <code>undefined</code>，点「复制」会直接抛异常——演示现场翻车。
 * 所以降级到「隐藏 textarea + document.execCommand('copy')」这条老路。
 *
 * @param {string} text 要写入剪贴板的文本
 * @returns {Promise<boolean>} 是否复制成功
 */
export async function copyText(text) {
  if (!text) return false

  // 首选：现代异步剪贴板 API
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // 用户拒绝授权或浏览器策略限制时，继续走下面的降级方案
    }
  }

  // 降级：临时 textarea。position: fixed + opacity: 0 是为了不引起页面跳动，
  // 同时保持元素「可选中」——display:none 的元素是选不中的。
  try {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.setAttribute('readonly', '')
    textarea.style.position = 'fixed'
    textarea.style.top = '0'
    textarea.style.left = '0'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(textarea)
    return ok
  } catch {
    return false
  }
}
