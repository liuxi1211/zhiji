<script setup lang="ts">
// 技能库：技能域字典维护（对应 skill_domain），三处消费：建档候选、知识点分域、简历授权锚点。
// 待后端 skill 模块接口就绪后接入，功能详设见 docs/prd/B端管理台PRD.md 第四节与 docs/prd/技能库PRD.md。
import ModulePlaceholder from '@/components/ModulePlaceholder.vue'
</script>

<template>
  <ModulePlaceholder
    title="技能库"
    note="技能域字典维护待后端 skill 模块接口就绪后接入。"
    :planned="[
      '技能域字典的增删改与停用',
      '三处消费：建档候选、知识点分域、简历授权锚点',
      '功能详设见 B端管理台PRD 第四节与技能库PRD'
    ]"
  />
</template>
