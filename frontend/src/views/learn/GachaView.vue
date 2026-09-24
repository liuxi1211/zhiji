<script setup lang="ts">
// 抽卡：复测/抽查/弱点三信号混抽，每日回访的默认入口；连抽与翻牌动画为趣味性保留项。
import ModulePlaceholder from '@/components/ModulePlaceholder.vue'
</script>

<template>
  <ModulePlaceholder
    variant="app"
    title="今天抽一题"
    note="复测 / 抽查 / 弱点三信号混抽待后端复测调度就绪后接入。"
    :planned="[
      '三信号混抽：复测、抽查、弱点',
      '连抽与翻牌动画',
      '闪卡快过作为显式低保障通道，由抽卡验伪挤水'
    ]"
  />
</template>
