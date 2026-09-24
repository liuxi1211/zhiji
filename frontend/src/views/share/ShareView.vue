<script setup lang="ts">
// 知识卡分享公开页（/share/:kpId）：无需登录，接收者翻面看答案、展开追问链与从零讲。
// 红线：只含库内容（active 知识点），绝不含个人数据；不渲染目标刻度（无个人职级）。
// 接口：GET /api/share/kp/{kpId}（kb 模块公开只读，见核心模块定义 3.2）；需求见知识库PRD 7.5。
import BrandMark from '@/components/BrandMark.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
</script>

<template>
  <div class="share-page">
    <header class="share-header">
      <BrandMark size="md" />
      <ThemeToggle />
    </header>

    <section class="share-intro">
      <span class="zj-eyebrow">知识卡</span>
      <h1>来烧脑一下，这道题你能答上来吗？</h1>
      <p>翻面看答案，展开追问链与从零讲，检验一下自己的深度。</p>
    </section>

    <el-card shadow="never" class="share-card">
      <el-empty description="知识卡分享页待后端 kb 模块公开接口就绪后接入" :image-size="72" />
    </el-card>

    <footer class="share-footer">
      <el-button type="primary" size="large" disabled>用知己练这道题</el-button>
      <span class="footer-note">分享页只含公开知识内容，不含任何个人练习数据</span>
    </footer>
  </div>
</template>

<style scoped>
.share-page {
  max-width: 640px;
  margin: 0 auto;
  padding: 20px 20px 40px;
  min-height: 100vh;
}

.share-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--zj-line-soft);
}

.share-intro {
  padding: 28px 0 20px;
}

.share-intro h1 {
  margin: 10px 0 8px;
  font-size: var(--zj-fs-xl);
  font-weight: 650;
  line-height: 1.4;
  letter-spacing: -0.01em;
  color: var(--zj-ink-900);
}

.share-intro p {
  margin: 0;
  font-size: var(--zj-fs-base);
  color: var(--zj-ink-500);
}

.share-footer {
  margin-top: 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.share-footer :deep(.el-button) {
  width: 100%;
  max-width: 320px;
}

.footer-note {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-400);
}
</style>
