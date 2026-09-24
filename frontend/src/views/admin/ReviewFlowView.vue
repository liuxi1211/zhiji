<script setup lang="ts">
// 校对流转（I-17）：draft → reviewed → active。
import ModulePlaceholder from '@/components/ModulePlaceholder.vue'
</script>

<template>
  <ModulePlaceholder
    title="校对流转"
    note="校对流转待后端 I-17 就绪后接入。"
    :planned="[
      'draft → reviewed → active 状态机流转',
      '未过 reviewed 的内容不许进用户路径',
      '下线或合并转 retired 并保留指向'
    ]"
  />
</template>
