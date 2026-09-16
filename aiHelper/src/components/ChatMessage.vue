<template>
  <div :class="['msg', `msg--${message.role}`, { 'msg--plain': message.role === 'user' }]">
    <div class="msg__avatar" aria-hidden="true">{{ avatar }}</div>

    <div class="msg__body">
      <!-- 助手消息显示「来自哪个后端能力」，让学习者一眼看出这次走的是哪个接口 -->
      <div v-if="message.role === 'assistant'" class="msg__meta">
        <span class="msg__source" :style="{ '--src-accent': sourceConfig.accent }">
          {{ sourceConfig.icon }} {{ sourceConfig.label }}
        </span>
        <span v-if="message.elapsedMs" class="msg__elapsed">耗时 {{ elapsedText }}</span>
      </div>

      <div class="msg__bubble" :class="{ 'is-error': message.status === 'error' }">
        <!--
          护栏拒绝：blocked 是「正常业务结果」而不是错误。
          后端约定返回 {success:false, blocked:true, reason}，
          必须以友好拒绝的样式展示 reason，不能套用红色报错样式，
          否则用户会以为系统坏了，而不是「我的输入被规则拦下了」。
        -->
        <div v-if="message.blocked" class="guardrail">
          <div class="guardrail__title">🛡️ 该提问被安全护栏拦截</div>
          <p class="guardrail__reason">{{ friendlyReason || '未提供具体原因' }}</p>
          <p class="guardrail__tip">可以精简问题后重新提问，或换一种问法再试一次。</p>
        </div>

        <!-- 学习建议：结构化数据必须渲染成真正的列表，而不是把 JSON 甩给用户 -->
        <div v-else-if="message.report" class="report">
          <div class="report__name">📋 {{ message.report.name }}</div>
          <ol class="report__list">
            <li v-for="(item, index) in message.report.suggestionList" :key="index">{{ item }}</li>
          </ol>
        </div>

        <!-- 学习方案：多 Agent 产物，主体是 Markdown 正文，附带耗时 -->
        <div v-else-if="message.plan" class="plan">
          <div class="plan__topic">🗺️ {{ message.plan.topic || '学习方案' }}</div>
          <div v-markdown="message.plan.plan || ''"></div>
        </div>

        <!-- 普通文本（用户消息、流式/同步回答、错误）统一走 Markdown 渲染 -->
        <template v-else>
          <div v-if="message.content" v-markdown="message.content"></div>
          <span v-if="message.status === 'streaming'" class="cursor" aria-label="正在生成"></span>
          <span v-if="message.status === 'streaming' && !message.content" class="thinking">
            正在思考…
          </span>
          <span v-if="message.status === 'stopped'" class="stopped-tag">⏹ 已停止生成</span>
          <span
            v-if="message.status === 'error' && !message.content"
            class="error-text"
          >{{ message.errorMessage || '请求失败' }}</span>
        </template>
      </div>

      <!-- 错误重试：只在失败且不是护栏拒绝时提供。
           护栏拒绝重试同样的内容还是会被拦，给重试按钮反而是误导。 -->
      <div v-if="message.status === 'error' && !message.blocked" class="msg__actions">
        <button class="msg__action" type="button" :disabled="disabled" @click="emit('retry', message.id)">
          ↻ 重试
        </button>
        <span v-if="message.errorMessage" class="msg__action-hint">{{ message.errorMessage }}</span>
      </div>

      <!-- 回答完成后提供整段复制；流式过程中不给（内容还在变，复制到的是半截） -->
      <div
        v-else-if="message.role === 'assistant' && message.status === 'done' && plainText"
        class="msg__actions"
      >
        <button class="msg__action" type="button" @click="copyWhole">
          {{ copied ? '✓ 已复制' : '⧉ 复制回答' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onBeforeUnmount } from 'vue'
import { modeConfigOf } from '../config/modes'
import { copyText } from '../utils/clipboard'

/**
 * 单条消息。这是「渲染四种不同产物」的地方。
 *
 * <h3>为什么四种产物共用一个组件，而不是各写一个组件？</h3>
 * 它们在结构上是同一种东西——一条带状态的消息（头像、气泡、错误重试、复制）。
 * 区别只在气泡里的内容。若拆成四个组件，头像/状态/重试这套骨架要复制四遍，
 * 复制出来的骨架迟早会走样。这里用 v-if 分支只换「内容区」。
 */
const props = defineProps({
  /** 消息对象，见 utils/sessions.js 的字段说明 */
  message: { type: Object, required: true },
  /** 当前是否正在流式输出（流式期间禁用重试，避免并发请求） */
  disabled: { type: Boolean, default: false }
})

const emit = defineEmits(['retry'])

const copied = ref(false)
let copiedTimer = null

/** 头像：用户与助手用不同图形，比纯文字更快区分 */
const avatar = computed(() => (props.message.role === 'user' ? '👤' : '🤖'))

/** 该条助手消息对应的能力配置（流式/护栏/建议/方案） */
const sourceConfig = computed(() => modeConfigOf(props.message.source))

/**
 * 护栏拒绝原因的「人话版」。
 *
 * <h3>这层清洗还需要吗？—— 现在基本是空操作，但保留它有价值</h3>
 * 早期后端把 LangChain4j 的护栏异常原样透传，形如：
 * <pre>
 *   The guardrail com.yupi.aicodehelper.ai.guardrail.InputLengthGuardrail
 *   failed with this message: 输入内容过长（当前 3942 字符，上限 2000 字符）。
 * </pre>
 * 前半段是 Java 全限定类名 + 英文调试前缀，对用户毫无意义。
 *
 * <p><b>后端已经修好了</b>：现在输入校验在 HTTP 入口完成，
 * 抛出的是自有异常，携带的就是干净文案，不再带框架前缀
 * （原因见后端 InputRejectedException 的注释——
 *  在源头产出干净数据，比让每个消费方各自打补丁更可靠）。
 *
 * <p>那这里的清洗为什么不删掉？因为它对<b>旧版本后端</b>仍然有效：
 * 用户可能开着缓存的旧前端配新后端，或反之。
 * 标记不存在时原样返回，所以对新后端它就是不执行任何操作的空转，
 * 零成本地保留了向前兼容。这类「无害的兼容层」值得留，
 * 但必须写清它为什么还在——否则下一个人会以为是没清理干净的废代码。
 */
const friendlyReason = computed(() => {
  const reason = String(props.message.reason || '')
  const marker = 'failed with this message:'
  const index = reason.indexOf(marker)
  // 没有该标记说明后端已经给了干净文案（当前版本就是如此），原样展示
  return index === -1 ? reason : reason.slice(index + marker.length).trim()
})

/** 耗时格式化：超过 1 秒显示秒，否则显示毫秒 */
const elapsedText = computed(() => {
  const ms = Number(props.message.elapsedMs)
  if (!Number.isFinite(ms) || ms <= 0) return ''
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)} 秒` : `${ms} 毫秒`
})

/**
 * 「复制回答」的原文。
 * 结构化产物（report / plan）拼成人可读的文本，而不是 JSON——
 * 复制出去是要粘到笔记里的，JSON 对读者毫无意义。
 */
const plainText = computed(() => {
  const message = props.message
  if (message.blocked) return ''
  if (message.report) {
    const list = (message.report.suggestionList || [])
      .map((item, index) => `${index + 1}. ${item}`)
      .join('\n')
    return `${message.report.name}\n\n${list}`
  }
  if (message.plan) {
    return `${message.plan.topic || '学习方案'}\n\n${message.plan.plan || ''}`
  }
  return message.content || ''
})

/** 复制整段回答，带 1.4 秒的「已复制」反馈 */
async function copyWhole() {
  const ok = await copyText(plainText.value)
  copied.value = ok
  if (copiedTimer) clearTimeout(copiedTimer)
  copiedTimer = setTimeout(() => {
    copied.value = false
  }, 1400)
}

// 组件销毁时清掉定时器：否则回调会在已卸载的组件上写 ref
onBeforeUnmount(() => {
  if (copiedTimer) clearTimeout(copiedTimer)
})
</script>

<style scoped>
.msg {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
  /* 用户消息靠右 */
  justify-content: flex-start;
}

.msg--user {
  flex-direction: row-reverse;
}

.msg__avatar {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  background: var(--ah-avatar-bg);
  border: 1px solid var(--ah-border);
}

.msg__body {
  min-width: 0;
  /* 气泡不铺满整行，留出「这是对话」的呼吸感 */
  max-width: min(760px, 84%);
}

.msg--user .msg__body {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.msg__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
  font-size: 11px;
  color: var(--ah-text-faint);
}

.msg__source {
  /* 用该能力的强调色做浅底标签，和标签栏形成呼应 */
  color: var(--src-accent);
  background: color-mix(in srgb, var(--src-accent) 12%, transparent);
  border-radius: 999px;
  padding: 1px 8px;
}

.msg__bubble {
  padding: 10px 14px;
  border-radius: 14px;
  background: var(--ah-bubble-ai);
  border: 1px solid var(--ah-border);
  color: var(--ah-text);
  word-break: break-word;
  overflow-wrap: anywhere;
}

.msg--user .msg__bubble {
  background: var(--ah-bubble-user);
  border-color: transparent;
  color: #fff;
}

.msg__bubble.is-error {
  border-color: var(--ah-danger);
  background: var(--ah-danger-soft);
}

/* 流式光标：用块状字符而不是动画图片，零依赖且不会引起布局跳动 */
.cursor {
  display: inline-block;
  width: 7px;
  height: 1em;
  margin-left: 2px;
  background: var(--ah-primary);
  vertical-align: -0.15em;
  animation: ah-blink 1s steps(2, start) infinite;
}

@keyframes ah-blink {
  to {
    visibility: hidden;
  }
}

.thinking {
  color: var(--ah-text-faint);
  font-size: 13px;
}

.stopped-tag {
  display: block;
  margin-top: 6px;
  font-size: 11px;
  color: var(--ah-text-faint);
}

.error-text {
  color: var(--ah-danger);
}

/* 护栏拒绝卡片 */
.guardrail {
  border-left: 3px solid var(--ah-warn);
  padding-left: 10px;
}

.guardrail__title {
  font-weight: 600;
  color: var(--ah-warn);
  margin-bottom: 4px;
}

.guardrail__reason {
  margin: 0 0 6px;
  font-size: 13.5px;
  line-height: 1.6;
}

.guardrail__tip {
  margin: 0;
  font-size: 12px;
  color: var(--ah-text-faint);
}

/* 学习建议卡片 */
.report__name {
  font-weight: 650;
  margin-bottom: 8px;
  color: var(--ah-text);
}

.report__list {
  margin: 0;
  padding-left: 20px;
  font-size: 14px;
  line-height: 1.75;
}

.report__list li {
  margin-bottom: 4px;
}

.plan__topic {
  font-weight: 650;
  margin-bottom: 8px;
  padding-bottom: 8px;
  border-bottom: 1px dashed var(--ah-border);
}

.msg__actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
}

.msg--user .msg__actions {
  flex-direction: row-reverse;
}

.msg__action {
  border: 1px solid var(--ah-border);
  background: var(--ah-surface);
  color: var(--ah-text-muted);
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 999px;
  transition: all 0.15s ease;
}

.msg__action:hover:not(:disabled) {
  color: var(--ah-primary);
  border-color: var(--ah-primary);
  background: var(--ah-primary-soft);
}

.msg__action:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.msg__action-hint {
  font-size: 11.5px;
  color: var(--ah-text-faint);
}

@media (max-width: 560px) {
  .msg__body {
    max-width: 92%;
  }
}
</style>
