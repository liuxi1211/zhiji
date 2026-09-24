<script setup lang="ts">
// 管理端概览：题库规模、各状态（draft/reviewed/active/retired）分布、待复核数。
import ModulePlaceholder from '@/components/ModulePlaceholder.vue'
</script>

<template>
  <ModulePlaceholder
    title="概览"
    note="题库规模、内容状态分布与待复核数待后端 kb / pipeline 接口就绪后接入。"
    :planned="[
      '知识点总数与 draft / reviewed / active / retired 状态分布',
      '主题簇数、技能域数、难度 1~5 分布',
      '待复核数量与到期提醒'
    ]"
  />
</template>
