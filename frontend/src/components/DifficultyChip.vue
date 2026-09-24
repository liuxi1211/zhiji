<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ level: number }>()

/**
 * 难度 1~5 → 白/绿/黄/橙/红五色（核心模块定义 85 行：颜色只编码难度）。
 * 颜色只落在圆点与描边上（非文字，需 ≥3:1）；文字与数字用墨色，保证 4.5:1，
 * 且不让颜色成为唯一的信息通道（色盲用户靠数字标签区分）。
 */
const color = computed(() => `var(--zj-diff-${Math.min(Math.max(props.level, 1), 5)})`)
</script>

<template>
  <span class="diff-chip" :style="{ borderColor: color }">
    <span class="diff-dot" :style="{ background: color }"></span>
    <span class="diff-label zj-num">难度 {{ level }}</span>
  </span>
</template>

<style scoped>
.diff-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 1px 8px 1px 6px;
  font-size: var(--zj-fs-xs);
  font-weight: 600;
  line-height: 18px;
  border: 1px solid;
  border-radius: 999px;
  white-space: nowrap;
  background: var(--zj-surface);
}

.diff-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

.diff-label {
  color: var(--zj-ink-700);
}
</style>
