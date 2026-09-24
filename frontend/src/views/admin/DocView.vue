<script setup lang="ts">
// 素材解析（I-18 / I-20）：上传 pdf/doc/docx/md，人工核对解析预览后再拆题。
// 关键：解析与拆题之间必须插一步人工核对（架构文档 7.3）。
import ModulePlaceholder from '@/components/ModulePlaceholder.vue'
</script>

<template>
  <ModulePlaceholder
    title="素材库"
    note="素材解析待后端 I-18 / I-20 就绪后接入。"
    :planned="[
      '上传 pdf / doc / docx / md 并解析',
      '解析结果人工核对预览',
      '核对通过后再拆题'
    ]"
  />
</template>
