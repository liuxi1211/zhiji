<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <div class="not-found">
    <h2>页面不存在</h2>
    <RouterLink to="/today">回到首页</RouterLink>
  </div>
</template>

<style scoped>
.not-found {
  padding: 48px 16px;
  text-align: center;
}
</style>
