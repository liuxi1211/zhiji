<script setup lang="ts">
/**
 * 验收刻度尺（核心模块定义 4.2）——本模块的标志性元素。
 * 每道题一格，把"双节奏的保障差"做成可读的几何：
 *   pass  实心墨青：练习判定通过，经得起检验
 *   quick 空心铜描边：速览通过 / 自评「我会」，进抽查范围待复验（低保障通道）
 *   weak  斜纹橙：自评「我不会」，弱点加权
 *   none  空白：未学
 *   lock  灰虚线：前置未达成，单元锁定
 * 颜色只落在格子上（非文字），不靠颜色单独承载信息——配合 units 页图例与 title 提示。
 */
import { computed } from 'vue'
import type { LearnStatus } from '@/api/types'

const props = withDefaults(
  defineProps<{
    /** 每格一个状态；也可直接传 LearnStatus / 'lock' */
    ticks: (LearnStatus | 'lock' | 'none')[]
    size?: 'sm' | 'lg'
  }>(),
  { size: 'sm' }
)

/** 归一化：learning → none（未学空白格），其余直落对应样式类 */
const normalized = computed(() => props.ticks.map((tick) => (tick === 'learning' ? 'none' : tick)))

const LABELS: Record<string, string> = {
  pass: '判定通过',
  quick: '速览通过 · 待复验',
  weak: '自评不会 · 弱点加权',
  none: '未学',
  lock: '前置未达成 · 锁定'
}
</script>

<template>
  <div class="ruler" :class="`ruler-${size}`" role="img" :aria-label="`验收刻度：共 ${ticks.length} 题`">
    <span
      v-for="(tick, index) in normalized"
      :key="index"
      class="tk"
      :class="`tk-${tick}`"
      :title="LABELS[tick]"
    ></span>
  </div>
</template>

<style scoped>
.ruler {
  display: flex;
  align-items: flex-end;
  gap: 3px;
  width: 100%;
}

.ruler-lg {
  height: 32px;
  gap: 4px;
}

.tk {
  flex: 1;
  min-width: 4px;
  align-self: stretch;
  border-radius: 2px;
  background: var(--zj-acc-none);
  transition: background var(--zj-dur-fast) var(--zj-ease-out);
}

.ruler-lg .tk {
  border-radius: 3px;
}

.tk-pass {
  background: var(--zj-acc-pass);
}

.tk-quick {
  /* 空心铜描边：低保障通道的视觉签名 */
  background: transparent;
  border: 1.5px solid var(--zj-acc-quick);
}

.tk-weak {
  background: repeating-linear-gradient(
    45deg,
    var(--zj-acc-weak) 0 3px,
    transparent 3px 6px
  );
  border: 1px solid var(--zj-acc-weak);
}

.tk-none {
  background: var(--zj-acc-none);
}

.tk-lock {
  background: transparent;
  border: 1.5px dashed var(--zj-ink-400);
}
</style>
