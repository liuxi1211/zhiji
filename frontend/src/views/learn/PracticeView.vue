<script setup lang="ts">
import { useRoute } from 'vue-router'

// 合卷练（I-08 详情 + I-09 作答判定）。
// 机制级闭卷：资料收起、先作答后自评，前端实现时必须保证
// "未作答前不请求答案"，否则答案会出现在 Network/DOM 中。
const route = useRoute()
</script>

<template>
  <el-card shadow="never">
    <el-empty :description="`合卷练（kpId=${String(route.params.kpId)}）待后端 I-08 / I-09 就绪后接入`" />
  </el-card>
</template>
