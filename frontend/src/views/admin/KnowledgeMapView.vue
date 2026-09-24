<script setup lang="ts">
// 知识地图：语料归一与按簇建设（决策清单 D21），对应 kp_canonical / 语料簇（draft 态 kp_cluster）。
// 三层视图（域 → 簇 → 归一题）+ 归一确认 + 簇操作 + 按簇批量成稿。
// 待后端 pipeline 模块接口就绪后接入，功能详设见 docs/prd/B端管理台PRD.md 第九节。
import ModulePlaceholder from '@/components/ModulePlaceholder.vue'
</script>

<template>
  <ModulePlaceholder
    title="知识地图"
    note="语料归一与按簇建设待后端 pipeline 模块接口就绪后接入。"
    :planned="[
      '三层视图：域 → 簇 → 归一题',
      '归一确认与簇操作（合并 / 拆分 / 改名 / 归属调整）',
      '按簇批量成稿'
    ]"
  />
</template>
