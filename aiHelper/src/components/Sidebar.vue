<template>
  <!-- 移动端遮罩：抽屉打开时点它关闭，符合移动端「点空白处收起」的直觉 -->
  <div
    v-if="mobileOpen"
    class="sidebar-scrim"
    @click="emit('close')"
    aria-hidden="true"
  ></div>

  <aside :class="['sidebar', { 'is-open': mobileOpen }]">
    <header class="sidebar__brand">
      <span class="sidebar__logo">🤖</span>
      <div class="sidebar__title-group">
        <h1>AI 编程小助手</h1>
        <p>流式对话 · 护栏 · 学习方案</p>
      </div>
      <!-- 移动端关闭按钮；桌面端由 CSS 隐藏 -->
      <button class="sidebar__close" type="button" title="收起侧栏" @click="emit('close')">✕</button>
    </header>

    <button class="sidebar__new" type="button" @click="emit('create')">
      <span aria-hidden="true">＋</span> 新建对话
    </button>

    <nav class="sidebar__list" aria-label="会话列表">
      <div
        v-for="session in sessions"
        :key="session.id"
        :class="['session', { 'is-active': session.id === activeId }]"
        role="button"
        tabindex="0"
        @click="emit('select', session.id)"
        @keydown.enter="emit('select', session.id)"
      >
        <div class="session__body">
          <div class="session__title">
            <span v-if="session.isDefault" class="session__badge" title="默认会话">默认</span>
            {{ session.title }}
          </div>
          <!-- memoryId 直接展示出来：这是教学项目，让学习者看得见「记忆隔离」的载体 -->
          <div class="session__meta">memoryId {{ session.memoryId }} · {{ session.messages.length }} 条</div>
        </div>
        <button
          class="session__delete"
          type="button"
          title="删除该会话"
          :disabled="sessions.length <= 1"
          @click.stop="emit('remove', session.id)"
        >
          🗑
        </button>
      </div>
    </nav>

    <footer class="sidebar__footer">
      <div class="sidebar__footer-label">会话保存在浏览器本地（localStorage）</div>
      <button class="sidebar__footer-reset" type="button" @click="emit('reset')">重置本地数据</button>
    </footer>
  </aside>
</template>

<script setup>
/**
 * 侧边栏：会话列表 + 新建 / 切换 / 删除。
 *
 * <h3>为什么全部用 emit 上报，不在组件内直接改数据？</h3>
 * 会话数据是「当前会话」这个全局状态的一部分。如果侧边栏直接改数组，
 * 就得把「切换会话要中止正在进行的流」「删除当前会话要选下一个」这些规则
 * 复制到组件里，规则一多必然和 App.vue 打架。
 * 保持单向数据流：侧边栏只负责「用户点了什么」，App.vue 负责「该发生什么」。
 */
defineProps({
  /** 全部会话（App.vue 传入的只读视图） */
  sessions: { type: Array, required: true },
  /** 当前选中会话 id */
  activeId: { type: String, default: '' },
  /** 移动端抽屉是否展开 */
  mobileOpen: { type: Boolean, default: false }
})

const emit = defineEmits(['select', 'create', 'remove', 'close', 'reset'])
</script>

<style scoped>
.sidebar {
  width: 256px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--ah-sidebar-bg);
  border-right: 1px solid var(--ah-border);
  /* 侧栏自身高度由外层 flex 拉伸，这里只约束内容区滚动 */
  min-height: 0;
}

.sidebar__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 16px 12px;
}

.sidebar__logo {
  font-size: 22px;
  line-height: 1;
}

.sidebar__title-group {
  min-width: 0;
  flex: 1;
}

.sidebar__title-group h1 {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
  color: var(--ah-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sidebar__title-group p {
  margin: 2px 0 0;
  font-size: 11px;
  color: var(--ah-text-faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sidebar__close {
  display: none;
  border: none;
  background: transparent;
  font-size: 16px;
  color: var(--ah-text-muted);
  padding: 4px 6px;
  border-radius: 6px;
}

.sidebar__new {
  margin: 0 12px 12px;
  padding: 10px 12px;
  border: 1px dashed var(--ah-border-strong);
  border-radius: 10px;
  background: transparent;
  color: var(--ah-text);
  font-size: 14px;
  text-align: center;
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.sidebar__new:hover {
  background: var(--ah-hover);
  border-color: var(--ah-primary);
  color: var(--ah-primary);
}

.sidebar__list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 8px 8px;
}

.session {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 9px 10px;
  margin-bottom: 4px;
  border-radius: 10px;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.session:hover {
  background: var(--ah-hover);
}

.session.is-active {
  background: var(--ah-primary-soft);
}

.session__body {
  flex: 1;
  min-width: 0;
}

.session__title {
  font-size: 13.5px;
  color: var(--ah-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.session.is-active .session__title {
  color: var(--ah-primary-strong);
  font-weight: 600;
}

.session__badge {
  display: inline-block;
  margin-right: 4px;
  padding: 0 5px;
  border-radius: 4px;
  font-size: 10px;
  line-height: 15px;
  background: var(--ah-primary);
  color: #fff;
  vertical-align: 1px;
}

.session__meta {
  margin-top: 2px;
  font-size: 11px;
  color: var(--ah-text-faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.session__delete {
  flex-shrink: 0;
  border: none;
  background: transparent;
  font-size: 13px;
  padding: 4px 6px;
  border-radius: 6px;
  /* 默认半透明，hover 整行时才明显——避免列表视觉噪声 */
  opacity: 0.35;
  transition: opacity 0.15s ease, background-color 0.15s ease;
}

.session:hover .session__delete {
  opacity: 1;
}

.session__delete:hover:not(:disabled) {
  background: var(--ah-danger-soft);
}

.session__delete:disabled {
  opacity: 0.15;
  cursor: not-allowed;
}

.sidebar__footer {
  padding: 10px 14px 14px;
  border-top: 1px solid var(--ah-border);
}

.sidebar__footer-label {
  font-size: 11px;
  color: var(--ah-text-faint);
  margin-bottom: 6px;
}

.sidebar__footer-reset {
  border: none;
  background: transparent;
  padding: 0;
  font-size: 12px;
  color: var(--ah-text-muted);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.sidebar__footer-reset:hover {
  color: var(--ah-danger);
}

/* 移动端：侧栏变成从左侧滑出的抽屉，默认移出视口 */
.sidebar-scrim {
  display: none;
}

@media (max-width: 860px) {
  .sidebar {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    z-index: 40;
    transform: translateX(-100%);
    transition: transform 0.22s ease;
    box-shadow: var(--ah-shadow-lg);
  }

  .sidebar.is-open {
    transform: translateX(0);
  }

  .sidebar__close {
    display: block;
  }

  .sidebar-scrim {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 30;
    background: rgba(0, 0, 0, 0.35);
  }
}
</style>
