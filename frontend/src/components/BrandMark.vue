<script setup lang="ts">
withDefaults(
  defineProps<{
    size?: 'sm' | 'md' | 'lg'
    tone?: 'ink' | 'onDark'
  }>(),
  {
    size: 'md',
    tone: 'ink'
  }
)

const TICKS = [1, 0.78, 0.56, 0.36]
</script>

<template>
  <span class="brand" :class="[`brand-${size}`, `brand-${tone}`]">
    <span class="brand-glyph" aria-hidden="true">
      <span
        v-for="(scale, index) in TICKS"
        :key="index"
        class="brand-tick"
        :style="{ height: `${scale * 100}%`, opacity: 1 - index * 0.16 }"
      ></span>
    </span>
    <span class="brand-text">知己</span>
  </span>
</template>

<style scoped>
.brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  font-weight: 700;
  letter-spacing: 0.14em;
  line-height: 1;
  color: var(--zj-ink-900);
}

.brand-onDark {
  color: #fff;
}

.brand-md {
  font-size: var(--zj-fs-md);
}

.brand-lg {
  font-size: var(--zj-fs-lg);
}

.brand-sm {
  font-size: var(--zj-fs-base);
  letter-spacing: 0.1em;
}

/* 刻度脊柱微缩图形：四道递减竖档，主色由深到浅 —— 产品的追问深度隐喻 */
.brand-glyph {
  display: inline-flex;
  align-items: flex-end;
  gap: 2px;
  height: 1.05em;
}

.brand-tick {
  width: 2px;
  border-radius: 1px;
  background: var(--zj-primary);
}

.brand-lg .brand-tick {
  width: 3px;
}

.brand-onDark .brand-tick {
  background: #63c6be;
}
</style>
