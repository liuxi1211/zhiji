<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'

const route = useRoute()

/** C 端主导航：对应核心模块定义第二节的六功能（建档在"我的"内） */
const tabs = [
  { name: 'map', path: '/map', label: '地图' },
  { name: 'gacha', path: '/gacha', label: '抽卡' },
  { name: 'exam', path: '/exam', label: '模拟考' },
  { name: 'review', path: '/review', label: '复盘' },
  { name: 'profile', path: '/profile', label: '我的' }
]

const activeTab = computed(() => {
  const matched = tabs.find((t) => route.path.startsWith(t.path))
  return matched?.name ?? 'map'
})
</script>

<template>
  <div class="learn-layout">
    <header class="learn-header">
      <span class="brand">知己</span>
      <span class="subtitle">{{ route.meta.title }}</span>
    </header>

    <main class="learn-main">
      <RouterView />
    </main>

    <nav class="learn-tabbar">
      <RouterLink
        v-for="tab in tabs"
        :key="tab.name"
        :to="tab.path"
        class="tab-item"
        :class="{ active: activeTab === tab.name }"
      >
        {{ tab.label }}
      </RouterLink>
    </nav>
  </div>
</template>

<style scoped>
.learn-layout {
  display: flex;
  flex-direction: column;
  min-height: 100%;
}

.learn-header {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  background: #fff;
  border-bottom: 1px solid var(--zj-border);
}

.brand {
  font-size: 17px;
  font-weight: 600;
}

.subtitle {
  font-size: 13px;
  color: var(--zj-text-weak);
}

.learn-main {
  flex: 1;
  padding: 16px;
}

.learn-tabbar {
  position: sticky;
  bottom: 0;
  display: flex;
  background: #fff;
  border-top: 1px solid var(--zj-border);
}

.tab-item {
  flex: 1;
  padding: 12px 0;
  text-align: center;
  font-size: 13px;
  color: var(--zj-text-weak);
  text-decoration: none;
}

.tab-item.active {
  color: #2b6cf6;
  font-weight: 600;
}
</style>
