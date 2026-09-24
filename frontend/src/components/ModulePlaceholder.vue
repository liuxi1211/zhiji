<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string
    note: string
    planned?: string[]
    /**
     * admin：B 端形态，卡片 + 刻度带，信息密度偏高；
     * app：C 端形态，不上卡片，用留白与一条标尺线分组，更像 App 而非后台。
     */
    variant?: 'admin' | 'app'
  }>(),
  {
    planned: () => [],
    variant: 'admin'
  }
)

const TICKS = Array.from({ length: 28 }, (_, index) => index)
</script>

<template>
  <section class="placeholder" :class="`variant-${variant}`">
    <header class="zj-page-head">
      <h1>{{ title }}</h1>
      <p>{{ note }}</p>
    </header>

    <!-- C 端：一条标尺线代替卡片边框，用留白分组 -->
    <template v-if="variant === 'app'">
      <div class="ruler" aria-hidden="true">
        <span
          v-for="index in TICKS"
          :key="index"
          class="ruler-tick"
          :class="{ 'is-major': index % 4 === 0 }"
          :style="{ animationDelay: `${index * 18}ms` }"
        ></span>
      </div>

      <div class="body">
        <p class="body-title">该模块接口尚未就绪</p>
        <ul v-if="planned.length" class="planned">
          <li v-for="item in planned" :key="item">{{ item }}</li>
        </ul>
      </div>
    </template>

    <!-- B 端：卡片承载，刻度带顶到卡边 -->
    <el-card v-else shadow="never" class="placeholder-card">
      <div class="gauge" aria-hidden="true">
        <span
          v-for="index in TICKS"
          :key="index"
          class="gauge-tick"
          :class="{ 'is-major': index % 4 === 0 }"
          :style="{ animationDelay: `${index * 18}ms` }"
        ></span>
      </div>

      <div class="body">
        <p class="body-title">该模块接口尚未就绪</p>
        <ul v-if="planned.length" class="planned">
          <li v-for="item in planned" :key="item">{{ item }}</li>
        </ul>
      </div>
    </el-card>
  </section>
</template>

<style scoped>
.placeholder {
  padding-top: var(--zj-space-1);
}

/* —— 刻度带 / 标尺线：精密量具的读数条，作为"未就绪"页面的品牌签名 —— */
.gauge,
.ruler {
  display: flex;
  align-items: flex-end;
  gap: 4px;
  background: var(--zj-surface-sunken);
}

.gauge {
  height: 34px;
  padding: 0 var(--zj-space-5);
  margin: calc(-1 * var(--zj-space-5)) calc(-1 * var(--zj-space-5)) var(--zj-space-5);
  border-bottom: 1px solid var(--zj-line-soft);
}

.ruler {
  height: 22px;
  margin-bottom: var(--zj-space-5);
  background: transparent;
  border-bottom: 1px solid var(--zj-line);
}

.gauge-tick,
.ruler-tick {
  flex: 1;
  height: 8px;
  border-radius: 1px;
  background: var(--zj-line);
  /* 一次性入场：纯 CSS，reduced-motion 下由全局规则压到 0.01ms 直接落到终态 */
  animation: tick-rise var(--zj-dur-slow) var(--zj-ease-out) both;
}

.ruler-tick {
  height: 6px;
}

.gauge-tick.is-major,
.ruler-tick.is-major {
  height: 15px;
  background: var(--zj-ink-400);
}

@keyframes tick-rise {
  from {
    opacity: 0;
    transform: scaleY(0.2);
  }
  to {
    opacity: 1;
    transform: scaleY(1);
  }
}

.placeholder-card {
  overflow: hidden;
}

.body {
  display: flex;
  flex-direction: column;
  gap: var(--zj-space-2);
  padding: var(--zj-space-2) 0 var(--zj-space-1);
}

.body-title {
  margin: 0;
  font-size: var(--zj-fs-base);
  font-weight: 600;
  color: var(--zj-ink-700);
}

.planned {
  margin: var(--zj-space-1) 0 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: var(--zj-space-1);
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-600);
}

.planned li::marker {
  color: var(--zj-copper);
}
</style>
