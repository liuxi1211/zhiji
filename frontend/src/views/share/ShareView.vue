<script setup lang="ts">
// 知识卡分享公开页（/share/:kpId）：无需登录，接收者翻面看答案、展开追问链与从零讲。
// 红线（知识库PRD 7.5）：只含库内容（active 知识点），绝不含个人数据；不渲染目标刻度。
// 接口：GET /api/share/kp/{kpId}（kb 模块公开只读）；卡图用 html2canvas-pro 前端渲染，零 LLM 调用。
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import html2canvas from 'html2canvas-pro'
import BrandMark from '@/components/BrandMark.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
import MarkdownView from '@/components/MarkdownView.vue'
import { getShareKp } from '@/api/kb'
import type { ShareKpDetail } from '@/api/types'

const route = useRoute()
const router = useRouter()

const kp = ref<ShareKpDetail | null>(null)
const loading = ref(true)
const notFound = ref(false)
const flipped = ref(false)
const showZeroShot = ref(false)
const showReference = ref(false)
const exporting = ref(false)

const cardRef = ref<HTMLElement | null>(null)

// 难度五色（1~5 ↔ 白绿黄橙红）：颜色只编码难度；自包含 hex 值供 html2canvas 离屏渲染取色
const DIFFICULTY_COLORS: Record<number, string> = {
  1: '#e8ecef',
  2: '#b7e4c7',
  3: '#ffe29a',
  4: '#ffb26b',
  5: '#ff7b7b'
}

const DIFFICULTY_LABELS: Record<number, string> = {
  1: '入门',
  2: '简单',
  3: '中等',
  4: '较难',
  5: '困难'
}

const difficultyColor = computed(() => DIFFICULTY_COLORS[kp.value?.difficulty ?? 1] ?? DIFFICULTY_COLORS[1])
const difficultyLabel = computed(() => DIFFICULTY_LABELS[kp.value?.difficulty ?? 1] ?? '入门')
const activeFollowups = computed(() => (kp.value?.followups ?? []).filter((l) => l.status === 'active'))
const variantsText = computed(() => (kp.value?.variants ?? []).join(' / '))
const shareLink = computed(() => `${window.location.origin}/share/${route.params.kpId}`)

async function load(): Promise<void> {
  loading.value = true
  notFound.value = false
  try {
    const kpId = Number(route.params.kpId)
    if (!Number.isInteger(kpId) || kpId <= 0) {
      notFound.value = true
      return
    }
    kp.value = await getShareKp(kpId)
  } catch (err) {
    const status = (err as { status?: number }).status
    notFound.value = status === 404 || status === undefined
    if (status !== undefined && status !== 404) {
      ElMessage.error('加载失败，请稍后重试')
    }
  } finally {
    loading.value = false
  }
}

async function saveCardImage(): Promise<void> {
  if (!cardRef.value || exporting.value) return
  exporting.value = true
  try {
    const canvas = await html2canvas(cardRef.value, {
      backgroundColor: '#ffffff',
      scale: 2,
      useCORS: true
    })
    const link = document.createElement('a')
    link.download = `zhiji-card-${route.params.kpId}.png`
    link.href = canvas.toDataURL('image/png')
    link.click()
    ElMessage.success('卡图已保存')
  } catch {
    ElMessage.error('卡图生成失败，请重试')
  } finally {
    exporting.value = false
  }
}

function copyLink(): void {
  void navigator.clipboard.writeText(shareLink.value)
  ElMessage.success('链接已复制')
}

function goPractice(): void {
  void router.push('/')
}

onMounted(async () => {
  await load()
  await nextTick()
})
</script>

<template>
  <div class="share-page">
    <header class="share-header">
      <BrandMark size="md" />
      <ThemeToggle />
    </header>

    <div v-if="loading" class="share-loading">
      <el-skeleton :rows="6" animated />
    </div>

    <el-empty
      v-else-if="notFound"
      description="内容不存在或已下线"
      :image-size="88"
    >
      <el-button type="primary" @click="goPractice">去知己看看</el-button>
    </el-empty>

    <template v-else-if="kp">
      <section class="share-intro">
        <span class="zj-eyebrow">知识卡</span>
        <h1>来烧脑一下，这道题你能答上来吗？</h1>
        <p>先自己回忆，再翻面看答案，展开追问链与从零讲检验深度。</p>
      </section>

      <!-- 卡面：自包含样式（不依赖 CSS 变量），html2canvas 离屏渲染取值稳定 -->
      <div ref="cardRef" class="share-card" :data-difficulty="kp.difficulty">
        <div class="card-top">
          <span class="card-domain" v-if="kp.domainName">{{ kp.domainName }}</span>
          <span class="card-difficulty" :style="{ background: difficultyColor }">
            难度 {{ kp.difficulty }} · {{ difficultyLabel }}
          </span>
        </div>

        <h2 class="card-title">{{ kp.title }}</h2>
        <p v-if="variantsText" class="card-variants">问法：{{ variantsText }}</p>

        <div v-if="!flipped" class="card-front-hint">
          <el-button type="primary" size="large" round @click="flipped = true">翻面看答案</el-button>
          <span class="front-meta">正面只给题面钩子 · 答案在背面</span>
        </div>

        <div v-else class="card-back">
          <div class="sample-block">
            <span class="sample-tag pass">及格档</span>
            <p class="sample-text">{{ kp.samples.pass || '暂无样例' }}</p>
          </div>
          <el-collapse v-if="kp.samples.good || kp.samples.excellent" class="sample-collapse">
            <el-collapse-item v-if="kp.samples.good" title="良好档" name="good">
              <p class="sample-text">{{ kp.samples.good }}</p>
            </el-collapse-item>
            <el-collapse-item v-if="kp.samples.excellent" title="优秀档" name="excellent">
              <p class="sample-text">{{ kp.samples.excellent }}</p>
            </el-collapse-item>
          </el-collapse>
        </div>

        <div class="card-watermark">
          <span class="watermark-brand">知己 · 面试陪练</span>
          <span class="watermark-note">在知己中练这道题，按你的目标职级标出停止点</span>
        </div>
      </div>

      <section v-if="activeFollowups.length" class="share-section">
        <h3 class="section-title">还有 {{ activeFollowups.length }} 层追问</h3>
        <p class="section-hint">真实面试官会层层下钻——每层先自己想，再展开看考察点。</p>
        <div
          v-for="layer in activeFollowups"
          :key="layer.layerKey"
          class="followup-item"
        >
          <div class="followup-head" @click="flipped || ElMessage.warning('先翻面看答案再展开追问')">
            <span class="followup-depth">第 {{ layer.layerKey.replace('L', '') }} 层</span>
            <span class="followup-dir">{{ layer.dir }}</span>
          </div>
          <p class="followup-text">{{ layer.text }}</p>
          <p v-if="layer.expect" class="followup-expect">考察点：{{ layer.expect }}</p>
          <p v-if="layer.stopCondition" class="followup-stop">讲到 {{ layer.stopCondition }} 算够</p>
        </div>
        <p class="section-note">在知己中会按你的目标职级标出停止点，这里展示全部追问层。</p>
      </section>

      <section v-if="kp.zeroShotMd" class="share-section">
        <div class="section-head" @click="showZeroShot = !showZeroShot">
          <h3 class="section-title">看不懂？从零讲一遍</h3>
          <span class="section-toggle">{{ showZeroShot ? '收起' : '展开' }}</span>
        </div>
        <div v-if="showZeroShot" class="section-body">
          <MarkdownView :source="kp.zeroShotMd" />
        </div>
      </section>

      <section v-if="kp.referenceSolutionMd" class="share-section">
        <div class="section-head" @click="showReference = !showReference">
          <h3 class="section-title">完整参考方案</h3>
          <span class="section-toggle">{{ showReference ? '收起' : '展开' }}</span>
        </div>
        <div v-if="showReference" class="section-body">
          <MarkdownView :source="kp.referenceSolutionMd" />
        </div>
      </section>

      <section class="share-actions">
        <el-button type="primary" size="large" @click="goPractice">用知己练这道题</el-button>
        <el-button size="large" :icon="Download" :loading="exporting" @click="saveCardImage">
          保存卡图
        </el-button>
        <el-button size="large" @click="copyLink">复制链接</el-button>
      </section>

      <footer class="share-footer">
        <span class="footer-note">分享页只含公开知识内容，不含任何个人练习数据</span>
        <span class="footer-link">{{ shareLink }}</span>
      </footer>
    </template>
  </div>
</template>

<style scoped>
.share-page {
  max-width: 680px;
  margin: 0 auto;
  padding: 20px 20px 48px;
  min-height: 100vh;
}

.share-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--zj-line-soft);
}

.share-loading {
  padding: 40px 0;
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
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-600);
}

/* 卡面自包含配色：不引用 CSS 变量，html2canvas-pro 离屏渲染取计算值稳定 */
.share-card {
  position: relative;
  padding: 24px 24px 20px;
  background: #ffffff;
  border: 1.5px solid #d8e0dd;
  border-radius: 16px;
  box-shadow: 0 6px 24px rgba(30, 50, 46, 0.08);
  overflow: hidden;
}

.share-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 6px;
  background: v-bind(difficultyColor);
}

.card-top {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.card-domain {
  padding: 3px 10px;
  font-size: 12px;
  font-weight: 600;
  color: #2c5a50;
  background: #e3f2ed;
  border-radius: 999px;
}

.card-difficulty {
  padding: 3px 10px;
  font-size: 12px;
  font-weight: 600;
  color: #26332f;
  border-radius: 999px;
}

.card-title {
  margin: 0 0 10px;
  font-size: 24px;
  font-weight: 700;
  line-height: 1.35;
  color: #17211e;
}

.card-variants {
  margin: 0 0 18px;
  font-size: 13px;
  color: #5c6b66;
}

.card-front-hint {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 20px 0 26px;
}

.front-meta {
  font-size: 12px;
  color: #8b9791;
}

.card-back {
  padding: 4px 0 8px;
}

.sample-block {
  padding: 14px 16px;
  background: #f2f7f4;
  border-radius: 10px;
}

.sample-tag {
  display: inline-block;
  margin-bottom: 8px;
  padding: 2px 8px;
  font-size: 11px;
  font-weight: 700;
  border-radius: 6px;
}

.sample-tag.pass {
  color: #1d6f4e;
  background: #d3ecdf;
}

.sample-text {
  margin: 0;
  font-size: 14px;
  line-height: 1.75;
  color: #26332f;
  white-space: pre-wrap;
}

.sample-collapse {
  margin-top: 10px;
  border-top: 1px dashed #d8e0dd;
  --el-collapse-header-height: 42px;
}

.sample-collapse :deep(.el-collapse-item__header) {
  font-size: 13px;
  font-weight: 600;
  color: #3d4c47;
  background: transparent;
  border-bottom: 1px dashed #e4eae7;
}

.sample-collapse :deep(.el-collapse-item__wrap) {
  background: #f7faf8;
  border-bottom: none;
}

.sample-collapse :deep(.el-collapse-item__content) {
  padding: 12px 14px;
}

.card-watermark {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid #e8eeeb;
}

.watermark-brand {
  font-size: 13px;
  font-weight: 700;
  color: #2c5a50;
}

.watermark-note {
  font-size: 11px;
  color: #93a09a;
  text-align: right;
}

/* 追问链 / 从零讲 / 参考方案 */
.share-section {
  margin-top: 22px;
  padding: 18px 20px;
  background: var(--zj-surface);
  border: 1px solid var(--zj-line-soft);
  border-radius: 12px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
}

.section-title {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
  color: var(--zj-ink-800);
}

.section-head .section-title {
  cursor: pointer;
}

.section-toggle {
  font-size: 12px;
  font-weight: 600;
  color: var(--zj-primary);
}

.section-hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--zj-ink-600);
}

.section-body {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px dashed var(--zj-line-soft);
  font-size: 14px;
  color: var(--zj-ink-800);
}

.section-note {
  margin: 12px 0 0;
  font-size: 11px;
  color: var(--zj-ink-500, #93a09a);
}

.followup-item {
  margin-top: 14px;
  padding: 12px 14px;
  background: var(--zj-paper);
  border-radius: 10px;
}

.followup-item .followup-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.followup-depth {
  padding: 1px 8px;
  font-size: 11px;
  font-weight: 700;
  color: #fff;
  background: var(--zj-primary);
  border-radius: 5px;
}

.followup-dir {
  font-size: 12px;
  color: var(--zj-ink-600);
}

.followup-text {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--zj-ink-900);
}

.followup-expect {
  margin: 0 0 4px;
  font-size: 12px;
  color: var(--zj-ink-600);
}

.followup-stop {
  margin: 0;
  font-size: 12px;
  color: #b07b2e;
}

.share-actions {
  display: flex;
  justify-content: center;
  gap: 12px;
  margin-top: 28px;
}

.share-footer {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  margin-top: 28px;
}

.footer-note {
  font-size: 12px;
  color: var(--zj-ink-600);
}

.footer-link {
  font-size: 11px;
  color: var(--zj-ink-500, #93a09a);
  word-break: break-all;
}

@media (max-width: 560px) {
  .share-card {
    padding: 18px 16px 16px;
  }

  .card-title {
    font-size: 20px;
  }

  .share-actions {
    flex-wrap: wrap;
  }
}
</style>
