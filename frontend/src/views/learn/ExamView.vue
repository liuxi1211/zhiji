<script setup lang="ts">
// 模拟考：分域检验 + 终局模拟考，判定驱动追问；见核心模块定义 2.5。
// 长流程状态将来放 examStore（Pinia），当前骨架不预置空 store。
import ModulePlaceholder from '@/components/ModulePlaceholder.vue'
</script>

<template>
  <ModulePlaceholder
    variant="app"
    title="来一场完整模拟"
    note="分域检验与终局模拟考待后端 exam 模块就绪后接入。"
    :planned="[
      '分域检验与终局模拟考两种模式',
      '追问链按职级要求逐层下钻',
      '答况四分流与档位结果'
    ]"
  />
</template>
