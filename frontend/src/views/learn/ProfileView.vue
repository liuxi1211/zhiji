<script setup lang="ts">
// 建档与画像：简历/对话建档、技能清单逐条拍板、计划设置；见核心模块定义 2.1。
import ModulePlaceholder from '@/components/ModulePlaceholder.vue'
</script>

<template>
  <ModulePlaceholder
    variant="app"
    title="建档与画像"
    note="简历 / 对话建档、技能清单与计划设置待后端 profile 模块就绪后接入。"
    :planned="[
      '简历或对话两种建档方式',
      '技能清单逐条拍板',
      '目标职级与练习计划设置'
    ]"
  />
</template>
