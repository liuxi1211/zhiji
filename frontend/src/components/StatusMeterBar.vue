<script setup lang="ts">
import { computed } from 'vue'

/**
 * 状态量具条：B 端列表页的状态总览控件（素材库 / 候选池 / 账号管理可复用）。
 * 上半是分段条——段宽按计数占比铺开，选中段加高为"主刻度"（呼应 ModulePlaceholder 的刻度带签名）；
 * 下半是读数行——读数即筛选器：点读数切换筛选，再点一次回到「全部」。
 * 分段条纯展示（aria-hidden），交互语义全在读数行，避免读屏重复。
 */
export interface MeterSegment {
  key: string
  label: string
  count: number
  /** 段的语义色：primary 主队列 / success 完成态 / neutral 归档态 */
  tone: 'primary' | 'success' | 'neutral'
}

const props = defineProps<{
  segments: MeterSegment[]
  /** 当前选中段的 key；空串表示「全部」 */
  modelValue: string
}>()

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()

const total = computed(() => props.segments.reduce((sum, seg) => sum + seg.count, 0))

function toggle(key: string) {
  emit('update:modelValue', props.modelValue === key ? '' : key)
}
</script>

<template>
  <div class="meter">
    <div class="meter-bar" :class="{ 'is-muted': !modelValue }" aria-hidden="true">
      <span
        v-for="seg in segments"
        :key="seg.key"
        class="meter-seg"
        :class="[`tone-${seg.tone}`, { 'is-active': modelValue === seg.key }]"
        :style="{ flexGrow: seg.count }"
      ></span>
    </div>

    <div class="meter-legend" role="group" aria-label="按状态筛选">
      <button
        type="button"
        class="meter-item"
        :class="{ 'is-active': !modelValue }"
        :aria-pressed="!modelValue"
        @click="emit('update:modelValue', '')"
      >
        <span class="label">全部</span>
        <span class="count zj-num">{{ total }}</span>
      </button>
      <button
        v-for="seg in segments"
        :key="seg.key"
        type="button"
        class="meter-item"
        :class="[`tone-${seg.tone}`, { 'is-active': modelValue === seg.key }]"
        :aria-pressed="modelValue === seg.key"
        @click="toggle(seg.key)"
      >
        <i class="dot" aria-hidden="true"></i>
        <span class="label">{{ seg.label }}</span>
        <span class="count zj-num">{{ seg.count }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.meter {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/* —— 分段条：flex-grow 承载占比，计数为 0 的段自然塌缩成一道缝 —— */
.meter-bar {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 18px;
}

.meter-seg {
  flex-basis: 0;
  min-width: 2px;
  height: 8px;
  border-radius: 2px;
  background: var(--meter-color, var(--zj-ink-400));
  opacity: 0.38;
  transition: height var(--zj-dur) var(--zj-ease-out), opacity var(--zj-dur) var(--zj-ease-out);
}

/* 「全部」态：整条压暗一档，让分布而非选中成为焦点 */
.meter-bar.is-muted .meter-seg {
  opacity: 0.55;
}

.meter-seg.is-active {
  height: 18px;
  opacity: 1;
}

.tone-primary {
  --meter-color: var(--zj-primary);
  --meter-dot: var(--zj-primary);
}

.tone-success {
  --meter-color: var(--el-color-success);
  --meter-dot: var(--el-color-success);
}

.tone-neutral {
  --meter-color: var(--zj-ink-400);
  --meter-dot: var(--zj-ink-400);
}

/* —— 读数行：读数即筛选器 —— */
.meter-legend {
  display: flex;
  align-items: baseline;
  gap: 20px;
  flex-wrap: wrap;
}

.meter-item {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 0 4px;
  border: none;
  background: none;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-600);
  cursor: pointer;
  transition: color var(--zj-dur-fast) var(--zj-ease-out);
}

.meter-item .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--meter-dot, var(--zj-ink-400));
}

.meter-item:hover {
  color: var(--zj-ink-900);
}

.meter-item .count {
  font-size: var(--zj-fs-md);
  font-weight: 600;
  color: var(--zj-ink-900);
}

.meter-item.is-active {
  color: var(--zj-ink-900);
}

.meter-item.is-active .label {
  font-weight: 600;
}

/* 选中刻度线：铜色，"深度/刻度"语义在交互层的落点 */
.meter-item.is-active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 2px;
  border-radius: 1px;
  background: var(--zj-copper);
}
</style>
