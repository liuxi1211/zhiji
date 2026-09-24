<script setup lang="ts">
// 学习地图：关卡组总览与入口，日常主入口；信息密度控制方案见核心模块定义第七节待办。
import ModulePlaceholder from '@/components/ModulePlaceholder.vue'
</script>

<template>
  <ModulePlaceholder
    variant="app"
    title="今天从哪一关开始"
    note="关卡组总览与入口待后端关卡生成接口就绪后接入。"
    :planned="[
      '技能域为关卡组、主题簇为关卡的两级结构',
      '默认按关卡组折叠，首屏只给「下一步闯哪」的建议',
      '前置依赖未解锁的关卡置灰并说明原因'
    ]"
  />
</template>
