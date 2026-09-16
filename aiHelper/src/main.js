import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import { vMarkdown } from './directives/markdown'

const app = createApp(App)

/**
 * 全局注册 Markdown 渲染指令。
 *
 * <h3>为什么在这里注册，而不是在用到它的组件里局部导入？</h3>
 * 最初写成「在 ChatMessage.vue 里 <code>import { vMarkdown }</code>」，
 * 但那个 import 被误写进了 App.vue——App.vue 的模板里并没有用到 v-markdown，
 * 于是这个绑定成了「已声明未使用」的代码，被构建流程直接丢弃；
 * 而真正使用它的 ChatMessage.vue 里没有任何绑定，Vue 运行时只会在控制台打一句
 * <code>[Vue warn]: Failed to resolve directive: markdown</code>，
 * 然后<b>什么都不渲染</b>——消息气泡全空。
 *
 * 这个教训值得记下来：<b>构建成功不等于功能正确</b>。
 * 指令、组件这类「按名字解析」的绑定漏注册时，打包器和类型检查都不会报错，
 * 只有运行时才暴露。所以 UI 冒烟测试不是可选项，而是必需品。
 *
 * 选择全局注册的另一个理由：Markdown 渲染是本项目的通用能力
 * （聊天回答、学习方案、护栏原因都要用），全局注册后任何组件写
 * v-markdown 就能用，不必每个组件都记得导入——少一次「忘记导入」的机会。
 */
app.directive('markdown', vMarkdown)

app.mount('#app')
