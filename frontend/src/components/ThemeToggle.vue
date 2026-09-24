<script setup lang="ts">
import { useThemeStore } from '@/stores/theme'

withDefaults(defineProps<{ tone?: 'ink' | 'onDark' }>(), { tone: 'ink' })

const theme = useThemeStore()
</script>

<template>
  <button
    type="button"
    class="theme-toggle"
    :class="`toggle-${tone}`"
    :title="theme.isDark ? '切换到亮色' : '切换到深色'"
    :aria-label="theme.isDark ? '切换到亮色' : '切换到深色'"
    @click="theme.toggle()"
  >
    <svg v-if="theme.isDark" key="sun" class="icon-enter" viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="12" r="4.2" />
      <g stroke-linecap="round">
        <path d="M12 2.6v2.4M12 19v2.4M2.6 12h2.4M19 12h2.4M5.4 5.4l1.7 1.7M16.9 16.9l1.7 1.7M18.6 5.4l-1.7 1.7M7.1 16.9l-1.7 1.7" />
      </g>
    </svg>
    <svg v-else key="moon" class="icon-enter" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M20 14.2A8.4 8.4 0 0 1 9.8 4a8.6 8.6 0 1 0 10.2 10.2Z" />
    </svg>
  </button>
</template>

<style scoped>
.theme-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: 1px solid var(--zj-line);
  border-radius: 9px;
  background: var(--zj-surface);
  color: var(--zj-ink-500);
  cursor: pointer;
  transition: color var(--zj-dur-fast) var(--zj-ease-out),
    border-color var(--zj-dur-fast) var(--zj-ease-out),
    background var(--zj-dur-fast) var(--zj-ease-out);
}

.theme-toggle:hover {
  color: var(--zj-primary);
  border-color: var(--zj-primary);
}

.toggle-onDark {
  background: transparent;
  border-color: rgba(255, 255, 255, 0.14);
  color: var(--zj-sidebar-ink);
}

.toggle-onDark:hover {
  color: #fff;
  border-color: rgba(255, 255, 255, 0.36);
}

.theme-toggle svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
}

/* 图标轮换：旋转 + 淡入，暗示"切换状态"而非瞬间替换 */
.icon-enter {
  animation: icon-swap var(--zj-dur) var(--zj-ease-out);
}

@keyframes icon-swap {
  from {
    opacity: 0;
    transform: rotate(-70deg) scale(0.8);
  }
  to {
    opacity: 1;
    transform: rotate(0) scale(1);
  }
}
</style>
