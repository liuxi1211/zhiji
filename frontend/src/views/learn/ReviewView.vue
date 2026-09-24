<script setup lang="ts">
// 复盘录题：真实面试被问到的题，对不上已有知识点则进候选池；见核心模块定义 2.6。
import ModulePlaceholder from '@/components/ModulePlaceholder.vue'
</script>

<template>
  <ModulePlaceholder
    variant="app"
    title="把面试里卡住的题记下来"
    note="复盘录题待后端 exam 模块就绪后接入。"
    :planned="[
      '录入真实面试被问到的题',
      '自动对齐已有知识点',
      '对不上的题进候选池，等人工归一'
    ]"
  />
</template>
