<script setup lang="ts">
import { RouterLink } from 'vue-router'
import BrandMark from '@/components/BrandMark.vue'
</script>

<template>
  <div class="not-found">
    <BrandMark size="lg" />
    <p class="code zj-num">404</p>
    <h1>这里没有你要找的页面</h1>
    <p class="note">地址可能拼错了，或者这个页面已经下线。</p>
    <RouterLink to="/map" class="back">回到学习地图</RouterLink>
  </div>
</template>

<style scoped>
.not-found {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 48px 20px;
  text-align: center;
  background: var(--zj-paper);
}

.code {
  margin: 16px 0 0;
  font-size: 56px;
  font-weight: 700;
  letter-spacing: 0.04em;
  line-height: 1;
  color: var(--zj-line);
}

h1 {
  margin: 0;
  font-size: var(--zj-fs-lg);
  font-weight: 650;
  color: var(--zj-ink-900);
}

.note {
  margin: 0;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-500);
}

.back {
  margin-top: 12px;
  padding: 9px 20px;
  font-size: var(--zj-fs-sm);
  font-weight: 500;
  color: #fff;
  background: var(--zj-primary);
  border-radius: var(--zj-radius-sm);
  transition: background var(--zj-dur-fast) var(--zj-ease-out);
}

.back:hover {
  color: #fff;
  background: var(--zj-primary-hover);
}
</style>
