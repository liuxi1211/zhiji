<script setup lang="ts">
// 候选池（I-16）：手工录入候选题 + 聚类去重映射。
import ModulePlaceholder from '@/components/ModulePlaceholder.vue'
</script>

<template>
  <ModulePlaceholder
    title="候选池"
    note="候选题录入与聚类去重待后端 I-16 就绪后接入。"
    :planned="[
      '手工录入候选题',
      '聚类去重映射',
      '与知识地图的语料归一衔接'
    ]"
  />
</template>
