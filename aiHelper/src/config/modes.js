/**
 * 四种后端能力的元数据（单一数据源）。
 *
 * <h3>为什么单独抽一个文件？</h3>
 * 标签栏要显示它们，输入框的 placeholder 要跟着变，发送时分发到哪个 API 也取决于它们。
 * 如果这三处各写一份 if/else，以后加一种能力就得改三个地方，漏一个就出 bug。
 * 集中成数组后，UI 用 v-for 渲染，分发用 modeConfigOf()，加能力只改这一个文件。
 */

/**
 * @typedef {Object} ModeConfig
 * @property {string} id           模式标识，同时作为消息上的 source 字段
 * @property {string} label        标签栏文字
 * @property {string} icon         标签栏图标（emoji，零依赖）
 * @property {string} apiPath      对应的后端接口，便于对照文档排查
 * @property {string} placeholder  输入框占位提示
 * @property {string} hint         标签栏下方的说明文字
 * @property {string} accent       主题色，用于标签高亮
 * @property {boolean} streaming   是否走 SSE 流式（决定要不要「停止生成」按钮）
 */

/** @type {ModeConfig[]} */
export const MODES = [
  {
    id: 'stream',
    label: '流式对话',
    icon: '⚡',
    apiPath: 'POST /api/ai/chat',
    placeholder: '问点什么吧…  Enter 发送，Shift + Enter 换行',
    hint: '逐字返回，可随时「停止生成」',
    accent: '#4c6ef5',
    streaming: true
  },
  {
    id: 'sync',
    label: '护栏问答',
    icon: '🛡️',
    apiPath: 'POST /api/ai/chat-sync',
    placeholder: '输入问题（最长 2000 字符）…',
    hint: '一次性返回，经过输入长度 / 提示词注入 / 敏感词三重护栏',
    accent: '#0ca678',
    streaming: false
  },
  {
    id: 'report',
    label: '学习建议',
    icon: '📋',
    apiPath: 'POST /api/ai/report',
    placeholder: '想学什么？例如：Java 并发编程',
    hint: '返回结构化建议清单，以列表卡片展示',
    accent: '#f59f00',
    streaming: false
  },
  {
    id: 'plan',
    label: '学习方案',
    icon: '🗺️',
    apiPath: 'POST /api/ai/study-plan',
    placeholder: '想制定哪个方向的学习方案？例如：Spring Boot',
    hint: '多 Agent 串行编排，约需 40~50 秒，请耐心等待',
    accent: '#ae3ec9',
    streaming: false
  }
]

/** 默认模式：流式对话，因为它是用户预期中的「正常聊天」 */
export const DEFAULT_MODE = 'stream'

/**
 * 按 id 取模式配置。
 * 传入未知 id（例如 localStorage 里存了旧版本的值）时回落到默认模式，
 * 避免标签栏渲染出 undefined 导致整页白屏。
 * @param {string} id 模式标识
 * @returns {ModeConfig}
 */
export function modeConfigOf(id) {
  return MODES.find((mode) => mode.id === id) || MODES[0]
}
