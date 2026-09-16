<template>
  <div class="composer">
    <div class="composer__box" :class="{ 'is-busy': loading }">
      <textarea
        ref="textareaRef"
        class="composer__input"
        :value="message"
        :placeholder="mode.placeholder"
        :disabled="loading"
        rows="1"
        @input="onInput"
        @keydown="onKeydown"
      ></textarea>

      <!-- 生成中显示「停止」，其余时候显示「发送」。
           停止按钮只在流式过程中出现——同步接口没法中止，给了按钮就是骗人。 -->
      <button
        v-if="loading && mode.streaming"
        class="composer__btn composer__btn--stop"
        type="button"
        @click="emit('stop')"
      >
        ⏹ 停止生成
      </button>
      <button
        v-else
        class="composer__btn"
        type="button"
        :disabled="loading || !canSend"
        @click="emit('send')"
      >
        {{ loading ? '处理中…' : '发送' }}
      </button>
    </div>

    <div class="composer__bar">
      <span class="composer__hint">
        <template v-if="loading && mode.streaming">生成中，可随时停止；停止后已生成的内容会保留</template>
        <template v-else>{{ mode.hint }}</template>
      </span>
      <!-- 护栏问答有 2000 字符上限（后端 InputLengthGuardrail），本地先提示，
           让人在提交前就有预期，而不是提交后被 blocked 才知道 -->
      <span
        v-if="mode.id === 'sync'"
        class="composer__counter"
        :class="{ 'is-over': message.length > 2000 }"
      >
        {{ message.length }} / 2000
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'

/**
 * 输入区：自适应高度 + Enter 发送 + 流式时显示停止按钮。
 *
 * <h3>为什么 textarea 用手动 :value + @input，而不是 v-model？</h3>
 * 自适应高度的实现顺序是「先写入值 → 再读 scrollHeight → 再改 style.height」。
 * 用 v-model 时，值的更新是异步的（要等下一次渲染），
 * 在 input 事件里立刻读 scrollHeight 拿到的还是旧内容的高度，
 * 表现为「打字时高度总慢一拍」。手动 :value 让父组件同步写入后，
 * nextTick 里读到的已经是新内容，高度一次到位。
 */
const props = defineProps({
  /** 输入框内容（父组件持有，便于按会话保存草稿） */
  message: { type: String, default: '' },
  /** 是否有请求在进行中 */
  loading: { type: Boolean, default: false },
  /** 当前模式配置 */
  mode: { type: Object, required: true },
  /** 递增数字。父组件切换会话时 +1，用来触发重新聚焦 */
  focusTrigger: { type: Number, default: 0 }
})

const emit = defineEmits(['update:message', 'send', 'stop'])

const textareaRef = ref(null)

/** 输入非空才允许发送（防止连点发送按钮发空消息） */
const canSend = computed(() => props.message.trim().length > 0)

/**
 * 自适应高度。
 * 关键点：先把 height 归零再读 scrollHeight——
 * 否则元素已经被撑高后，scrollHeight 永远等于当前高度，
 * 删除内容时高度就再也降不回去（这是 textarea 自适应最经典的坑）。
 */
function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  // 上限 200px：粘贴长文档时不至于把输入区撑满整屏，超出后内部滚动
  el.style.height = `${Math.min(el.scrollHeight, 200)}px`
}

/** 清空后自动聚焦，方便连续提问 */
function focus() {
  nextTick(() => textareaRef.value?.focus())
}

function onInput(event) {
  emit('update:message', event.target.value)
  // 直接基于当前事件目标计算，避免依赖下一次渲染
  autoResize()
}

/**
 * 键盘处理：
 * - Enter 发送（这是聊天工具的通用约定）
 * - Shift + Enter 换行
 * - 输入法组合中的 Enter 必须放过，否则中文输入法选词按 Enter 会误发消息
 */
function onKeydown(event) {
  if (event.key !== 'Enter') return
  if (event.shiftKey) return
  // isComposing 为 true 表示正在用输入法拼字（例如拼音还没选完词）
  if (event.isComposing) return
  event.preventDefault()
  if (props.loading) return
  if (!canSend.value) return
  emit('send')
}

// 内容变化（包括发送后被清空）都要重新测量高度
watch(() => props.message, () => autoResize())

// 切换会话时把焦点交回输入框，用户可以直接开始打字
watch(
  () => props.focusTrigger,
  () => focus()
)

// 挂载后先测一次：从别的会话切进来时草稿可能是有多行的
watch(
  textareaRef,
  (el) => {
    if (el) {
      autoResize()
      el.focus()
    }
  },
  { immediate: true }
)

// 暴露给父组件：发送成功、停止生成之后都把焦点还给输入框
defineExpose({ focus, autoResize })
</script>

<style scoped>
.composer {
  padding: 10px 20px 14px;
  background: var(--ah-surface);
  border-top: 1px solid var(--ah-border);
}

.composer__box {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 8px 8px 8px 12px;
  border: 1px solid var(--ah-border-strong);
  border-radius: 14px;
  background: var(--ah-input-bg);
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

/* 聚焦时整圈高亮（:focus-within），让「输入区」这个整体被强调，
   而不是只有 textarea 自己变边框 */
.composer__box:focus-within {
  border-color: var(--ah-primary);
  box-shadow: 0 0 0 3px var(--ah-primary-soft);
}

.composer__box.is-busy {
  border-color: var(--ah-primary);
}

.composer__input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  resize: none;
  color: var(--ah-text);
  font-size: 14.5px;
  line-height: 1.6;
  max-height: 200px;
  overflow-y: auto;
  padding: 4px 0;
}

.composer__input::placeholder {
  color: var(--ah-text-faint);
}

.composer__input:disabled {
  /* 处理中禁用输入，避免用户在「正在发送」时又改内容造成困惑 */
  opacity: 0.6;
}

.composer__btn {
  flex-shrink: 0;
  border: none;
  border-radius: 10px;
  padding: 8px 16px;
  font-size: 13.5px;
  line-height: 1.4;
  background: var(--ah-primary);
  color: #fff;
  transition: background-color 0.15s ease, opacity 0.15s ease;
}

.composer__btn:hover:not(:disabled) {
  background: var(--ah-primary-strong);
}

.composer__btn:disabled {
  background: var(--ah-border-strong);
  color: var(--ah-text-faint);
  cursor: not-allowed;
}

.composer__btn--stop {
  background: var(--ah-danger);
}

.composer__btn--stop:hover {
  background: var(--ah-danger-strong);
}

.composer__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 6px;
  padding: 0 4px;
  font-size: 11.5px;
  color: var(--ah-text-faint);
}

.composer__hint {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.composer__counter {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}

.composer__counter.is-over {
  color: var(--ah-danger);
  font-weight: 600;
}

@media (max-width: 860px) {
  .composer {
    padding: 8px 12px 12px;
  }
}
</style>
