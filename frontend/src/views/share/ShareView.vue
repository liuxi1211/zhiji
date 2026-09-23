<script setup lang="ts">
// 知识卡分享公开页（/share/:kpId）：无需登录，接收者翻面看答案、展开追问链与从零讲。
// 红线：只含库内容（active 知识点），绝不含个人数据；不渲染目标刻度（无个人职级）。
// 接口：GET /api/share/kp/{kpId}（kb 模块公开只读，见核心模块定义 3.2）；需求见知识库PRD 7.5。
</script>

<template>
  <div class="share-page">
    <header class="share-header">
      <span class="brand">知己 · 面试陪练</span>
      <span class="hint">来烧脑一下，这道题你能答上来吗？</span>
    </header>
    <el-card shadow="never">
      <el-empty description="知识卡分享页待后端 kb 模块公开接口就绪后接入" />
    </el-card>
    <footer class="share-footer">
      <el-button type="primary" disabled>用知己练这道题</el-button>
    </footer>
  </div>
</template>

<style scoped>
.share-page {
  max-width: 640px;
  margin: 0 auto;
  padding: 24px 16px;
}

.share-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 16px;
}

.brand {
  font-size: 17px;
  font-weight: 600;
}

.hint {
  font-size: 13px;
  color: var(--zj-text-weak);
}

.share-footer {
  margin-top: 16px;
  text-align: center;
}
</style>
