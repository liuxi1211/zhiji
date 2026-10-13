<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import BrandMark from '@/components/BrandMark.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'

const route = useRoute()

/** C 端主导航：对应核心模块定义第二节的六功能（建档在"我的"内） */
const tabs = [
  { name: 'map', path: '/map', label: '地图', icon: 'map' },
  { name: 'gacha', path: '/gacha', label: '抽卡', icon: 'gacha' },
  { name: 'exam', path: '/exam', label: '模拟考', icon: 'exam' },
  { name: 'review', path: '/review', label: '复盘', icon: 'review' },
  { name: 'profile', path: '/profile', label: '我的', icon: 'profile' }
]

const activeTab = computed(() => {
  const matched = tabs.find((t) => route.path.startsWith(t.path))
  return matched?.name ?? 'map'
})

const activeIndex = computed(() => {
  const index = tabs.findIndex((t) => t.name === activeTab.value)
  return index < 0 ? 0 : index
})
</script>

<template>
  <div class="learn-layout">
    <header class="learn-header">
      <div class="learn-brand">
        <BrandMark size="md" />
        <span class="learn-title">{{ route.meta.title }}</span>
      </div>
      <ThemeToggle />
    </header>

    <main class="learn-main">
      <RouterView v-slot="{ Component }">
        <Transition name="route-fade" mode="out-in">
          <component :is="Component" />
        </Transition>
      </RouterView>
    </main>

    <nav class="learn-tabbar">
      <span
        class="tab-indicator"
        aria-hidden="true"
        :style="{ transform: `translateX(${activeIndex * 100}%)` }"
      ></span>
      <RouterLink
        v-for="tab in tabs"
        :key="tab.name"
        :to="tab.path"
        class="tab-item"
        :class="{ active: activeTab === tab.name }"
      >
        <svg class="tab-icon" viewBox="0 0 24 24" aria-hidden="true">
          <template v-if="tab.icon === 'map'">
            <path d="M4 6.5 9.5 4l5 2.5L20 4v13.5L14.5 20l-5-2.5L4 20Z" />
            <path d="M9.5 4v13.5M14.5 6.5V20" />
          </template>
          <template v-else-if="tab.icon === 'gacha'">
            <rect x="4" y="5" width="9" height="14" rx="2" />
            <path d="M16 8.5l4-1.5v10l-4-1.5Z" />
          </template>
          <template v-else-if="tab.icon === 'exam'">
            <path d="M6 3.5h9l4 4V20a.5.5 0 0 1-.5.5h-12A.5.5 0 0 1 6 20Z" />
            <path d="M15 3.5V8h4M9 12.5h6M9 16h4" />
          </template>
          <template v-else-if="tab.icon === 'review'">
            <path d="M4 12a8 8 0 1 0 2.5-5.8" />
            <path d="M4 4v4h4" />
            <path d="M12 8.5V12l2.5 1.5" />
          </template>
          <template v-else>
            <circle cx="12" cy="8.5" r="3.5" />
            <path d="M5.5 20a6.5 6.5 0 0 1 13 0" />
          </template>
        </svg>
        <span class="tab-label">{{ tab.label }}</span>
      </RouterLink>
    </nav>
  </div>
</template>

<style scoped>
.learn-layout {
  display: grid;
  grid-template:
    'header' auto
    'main' 1fr
    'tabbar' auto
    / 1fr;
  min-height: 100%;
  background: var(--zj-paper);
}

.learn-header {
  grid-area: header;
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--zj-space-3);
  padding: var(--zj-space-3) var(--zj-space-4);
  background: var(--zj-surface);
  border-bottom: 1px solid var(--zj-line-soft);
}

.learn-brand {
  display: flex;
  align-items: baseline;
  gap: var(--zj-space-3);
}

.learn-title {
  font-size: var(--zj-fs-sm);
  font-weight: 500;
  color: var(--zj-ink-600);
}

.learn-main {
  grid-area: main;
  width: 100%;
  max-width: 920px;
  margin: 0 auto;
  padding: var(--zj-space-4) var(--zj-space-4) var(--zj-space-6);
}

.learn-tabbar {
  grid-area: tabbar;
  position: sticky;
  bottom: 0;
  z-index: 10;
  display: flex;
  background: var(--zj-surface);
  border-top: 1px solid var(--zj-line-soft);
  padding-bottom: env(safe-area-inset-bottom);
}

/* 选中指示：单个元素平移，而非逐项淡入 —— 移动本身说明"从哪到哪" */
.tab-indicator {
  position: absolute;
  top: -1px;
  left: 0;
  width: 20%;
  height: 2px;
  display: flex;
  justify-content: center;
  transition: transform var(--zj-dur) var(--zj-ease-out-quint);
}

.tab-indicator::before {
  content: '';
  width: 22px;
  height: 2px;
  border-radius: 0 0 2px 2px;
  background: var(--zj-primary);
}

.tab-item {
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  padding: 9px 0 11px;
  color: var(--zj-ink-500);
  transition: color var(--zj-dur-fast) var(--zj-ease-out),
    background-color var(--zj-dur-fast) var(--zj-ease-out);
}

@media (hover: hover) {
  .tab-item:hover {
    color: var(--zj-ink-900);
  }
}

.tab-item.active {
  color: var(--zj-primary);
}

.tab-icon {
  width: 20px;
  height: 20px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linejoin: round;
  stroke-linecap: round;
}

.tab-label {
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.01em;
}

/* —— 桌面（≥1024px）：同一份 tabs 数据物化为左侧栏，应用壳模式 —— */
@media (min-width: 1024px) {
  .learn-layout {
    height: 100%;
    grid-template:
      'sidebar header' auto
      'sidebar main' 1fr
      / 236px 1fr;
  }

  .learn-header {
    position: static;
    padding: var(--zj-space-3) var(--zj-space-5);
  }

  .learn-main {
    overflow-y: auto;
    padding: var(--zj-space-5) var(--zj-space-6) var(--zj-space-7);
  }

  .learn-tabbar {
    grid-area: sidebar;
    position: static;
    flex-direction: column;
    align-items: stretch;
    gap: 2px;
    padding: var(--zj-space-4) var(--zj-space-3);
    background: var(--zj-sidebar);
    border-top: none;
  }

  .tab-indicator {
    display: none;
  }

  .tab-item {
    flex: none;
    flex-direction: row;
    justify-content: flex-start;
    gap: var(--zj-space-3);
    padding: 10px var(--zj-space-3);
    border-radius: var(--zj-radius-sm);
    color: var(--zj-sidebar-ink);
  }

  .tab-item.active {
    color: var(--zj-ink-900);
    background: var(--zj-primary-soft);
  }

  .tab-label {
    font-size: var(--zj-fs-sm);
  }
}

@media (min-width: 1024px) and (hover: hover) {
  .tab-item:hover {
    color: var(--zj-surface);
    background: rgba(255, 255, 255, 0.07);
  }
}
</style>
