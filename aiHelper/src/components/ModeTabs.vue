<template>
  <div class="tabs" role="tablist" aria-label="能力切换">
    <button
      v-for="mode in modes"
      :key="mode.id"
      class="tab"
      type="button"
      role="tab"
      :aria-selected="mode.id === modelValue"
      :class="{ 'is-active': mode.id === modelValue }"
      :style="mode.id === modelValue ? { '--tab-accent': mode.accent } : null"
      :title="`${mode.hint}（${mode.apiPath}）`"
      @click="emit('update:modelValue', mode.id)"
    >
      <span class="tab__icon" aria-hidden="true">{{ mode.icon }}</span>
      <span class="tab__label">{{ mode.label }}</span>
    </button>
  </div>
</template>

<script setup>
/**
 * 四种后端能力的切换标签栏。
 *
 * 数据来源是 config/modes.js 的 MODES，本组件只是把它渲染出来——
 * 加一种能力不需要动这里，这正是把元数据抽出去的意义。
 *
 * 用 v-model（modelValue + update:modelValue）而不是自定义事件名，
 * 是为了让 App.vue 直接写 <ModeTabs v-model="mode" />，减少一处心智负担。
 */
defineProps({
  /** 全部模式配置 */
  modes: { type: Array, required: true },
  /** 当前选中的模式 id */
  modelValue: { type: String, required: true }
})

const emit = defineEmits(['update:modelValue'])
</script>

<style scoped>
.tabs {
  display: flex;
  gap: 6px;
  padding: 12px 20px 0;
  overflow-x: auto;
  /* 移动端标签横排会超宽，允许横向滚动但隐藏滚动条，避免挤压布局 */
  scrollbar-width: none;
}

.tabs::-webkit-scrollbar {
  display: none;
}

.tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  padding: 7px 13px;
  border: 1px solid var(--ah-border);
  border-radius: 999px;
  background: var(--ah-surface);
  color: var(--ah-text-muted);
  font-size: 13px;
  line-height: 1.2;
  transition: all 0.15s ease;
}

.tab:hover {
  background: var(--ah-hover);
  color: var(--ah-text);
}

.tab.is-active {
  /* 每个模式有自己的强调色，激活时用该色描边+上色，视觉上能立刻区分当前能力 */
  border-color: var(--tab-accent);
  color: var(--tab-accent);
  background: color-mix(in srgb, var(--tab-accent) 12%, transparent);
  font-weight: 600;
}

.tab__icon {
  font-size: 14px;
}

@media (max-width: 560px) {
  .tabs {
    padding: 10px 12px 0;
  }

  .tab {
    padding: 6px 10px;
    font-size: 12px;
  }
}
</style>
