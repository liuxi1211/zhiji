<script setup lang="ts">
// 可视化合并页（核心模块定义 5.2「同位去重与合并」）：最多三份并排对照，逐项对照；
// 两种合并方式——AI 一键合并（产物回编辑器由人调整，多源矛盾点单列不阻断）或人工参照手写
// （页面上方即可编辑）。保存后产物进审核队列；审核通过后源标 merged、覆盖度转入。
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { MagicStick } from '@element-plus/icons-vue'
import {
  aiMerge,
  submitDocMerge,
  submitKpMerge,
  validateDocMerge,
  validateKpMerge
} from '@/api/kb'
import type {
  DocView,
  KbContentType,
  KbDocDraft,
  KbKpDraft,
  KbMergeProduct,
  KpView,
  SourceDifficulty
} from '@/api/types'
import KpDisplay from '@/components/kb/KpDisplay.vue'
import DocDisplay from '@/components/kb/DocDisplay.vue'
import KpEditor from '@/components/kb/KpEditor.vue'
import DocEditor from '@/components/kb/DocEditor.vue'
import { newFollowupId, newScoringPointId } from '@/utils/markdown'

const route = useRoute()
const router = useRouter()

const contentType = ref<KbContentType>('kp')
const sources = ref<(KpView | DocView)[]>([])
const conflicts = ref<string[]>([])
const aiBusy = ref(false)
const saving = ref(false)

// 产物编辑草稿：AI 产物或人工手写共用同一编辑器
const kpDraft = ref<KbKpDraft | null>(null)
const docDraft = ref<KbDocDraft | null>(null)

function messageOf(error: unknown, fallback: string): string {
  const err = error as { message?: string }
  return err.message ?? fallback
}

onMounted(async () => {
  contentType.value = (route.query.type as KbContentType) === 'doc' ? 'doc' : 'kp'
  const ids = String(route.query.ids ?? '')
    .split(',')
    .map((raw) => Number(raw))
    .filter((id) => id > 0)
  if (ids.length < 2) {
    ElMessage.error('请从内容生产台的同位管理勾选 2–3 份内容进入合并页')
    void router.replace('/admin/production')
    return
  }
  try {
    if (contentType.value === 'kp') {
      sources.value = await validateKpMerge(ids)
    } else {
      sources.value = await validateDocMerge(ids)
    }
  } catch (error) {
    ElMessage.error(messageOf(error, '加载合并语料失败'))
    void router.replace('/admin/production')
  }
})

// —— AI 一键合并：产物回编辑器，矛盾点单列不阻断 ——

async function doAiMerge() {
  const ids = sources.value.map((item) => item.id)
  aiBusy.value = true
  try {
    const product = await aiMerge(contentType.value, ids)
    conflicts.value = product.conflicts ?? []
    if (contentType.value === 'kp') {
      kpDraft.value = {
        title: product.title ?? '',
        summary: product.summary ?? '',
        frameworkMd: product.frameworkMd ?? '',
        variants: product.variants ?? [],
        followups: (product.followups ?? []).map((f) => ({
          id: newFollowupId(),
          layer: f.layer,
          direction: f.direction,
          content: f.content,
          status: 'active' as const
        })),
        scoringPoints: (product.scoringPoints ?? []).map((point) => ({
          id: newScoringPointId(),
          level: point.level,
          content: point.content
        })),
        answerSamples: product.answerSamples ?? [],
        difficulty: ((product.difficulty || 'mid') as SourceDifficulty),
        levelRules: product.levelRules ?? [],
        prerequisites: [],
        freqManual: 0
      }
    } else {
      docDraft.value = {
        title: product.title ?? '',
        summary: product.summary ?? '',
        contentMd: product.contentMd ?? '',
        difficulty: ((product.difficulty || 'mid') as SourceDifficulty),
        kpId: null
      }
    }
    ElMessage.success('AI 合并产物已回编辑器，请逐项核对调整（多源矛盾点见下方清单）')
  } catch (error) {
    ElMessage.error(messageOf(error, 'AI 合并失败'))
  } finally {
    aiBusy.value = false
  }
}

function blankKpDraft(): KbKpDraft {
  return {
    title: '',
    summary: '',
    frameworkMd: '',
    variants: [],
    followups: [],
    scoringPoints: [],
    answerSamples: [],
    difficulty: 'mid',
    levelRules: [],
    prerequisites: [],
    freqManual: 0
  }
}

function blankDocDraft(): KbDocDraft {
  return { title: '', summary: '', contentMd: '', difficulty: 'mid', kpId: null }
}

// —— 保存：产物落库进审核队列（pending 新行 + merge_sources） ——

async function onSaveKp(draft: KbKpDraft) {
  saving.value = true
  try {
    const product: KbMergeProduct = {
      title: draft.title,
      summary: draft.summary,
      frameworkMd: draft.frameworkMd,
      variants: draft.variants,
      followups: draft.followups.map((f) => ({ layer: f.layer, direction: f.direction, content: f.content })),
      scoringPoints: draft.scoringPoints.map((point) => ({ level: point.level, content: point.content })),
      answerSamples: draft.answerSamples,
      difficulty: draft.difficulty,
      levelRules: draft.levelRules,
      conflicts: conflicts.value
    }
    await submitKpMerge(
      sources.value.map((item) => item.id),
      product
    )
    ElMessage.success('合并产物已进审核队列；审核通过后源将标记合并下线')
    void router.push('/admin/production')
  } catch (error) {
    ElMessage.error(messageOf(error, '保存合并产物失败'))
  } finally {
    saving.value = false
  }
}

async function onSaveDoc(draft: KbDocDraft) {
  saving.value = true
  try {
    const product: KbMergeProduct = {
      title: draft.title,
      summary: draft.summary,
      contentMd: draft.contentMd,
      difficulty: draft.difficulty,
      conflicts: conflicts.value
    }
    await submitDocMerge(
      sources.value.map((item) => item.id),
      product
    )
    ElMessage.success('合并产物已进审核队列；审核通过后源将标记合并下线')
    void router.push('/admin/production')
  } catch (error) {
    ElMessage.error(messageOf(error, '保存合并产物失败'))
  } finally {
    saving.value = false
  }
}

const gridClass = computed(() => `source-grid cols-${Math.min(sources.value.length, 3)}`)
</script>

<template>
  <div class="merge-view">
    <header class="view-head">
      <div>
        <h1>同位合并</h1>
        <p class="view-sub">去重即成稿，成稿即去重：勾选内容并排对照，产出一份标准产物进审核队列</p>
      </div>
      <el-button class="btn-ai" :icon="MagicStick" type="primary" :loading="aiBusy" @click="doAiMerge">AI 一键合并</el-button>
    </header>

    <!-- 产物编辑区（人工参照手写或 AI 产物调整） -->
    <el-card shadow="never" class="product-card">
      <template #header>
        <span>合并产物（人工参照手写，或点右上角 AI 一键合并后逐项调整）</span>
      </template>
      <KpEditor
        v-if="contentType === 'kp'"
        :key="kpDraft ? 'kp-draft' : 'kp-blank'"
        :initial="kpDraft"
        :saving="saving"
        @save="onSaveKp"
      />
      <DocEditor
        v-else
        :key="docDraft ? 'doc-draft' : 'doc-blank'"
        :initial="docDraft"
        :saving="saving"
        @save="onSaveDoc"
      />
      <el-button v-if="contentType === 'kp' && !kpDraft" size="small" @click="kpDraft = blankKpDraft()">开始手写产物</el-button>
      <el-button v-if="contentType === 'doc' && !docDraft" size="small" @click="docDraft = blankDocDraft()">开始手写产物</el-button>
    </el-card>

    <!-- 多源矛盾点（单列不阻断，事后逐条裁决） -->
    <el-alert v-if="conflicts.length" type="warning" :closable="false" class="conflicts">
      <template #title>多源矛盾点 {{ conflicts.length }} 条（不阻断合并，事后逐条裁决）</template>
      <ul class="conflict-list">
        <li v-for="(conflict, index) in conflicts" :key="index">{{ conflict }}</li>
      </ul>
    </el-alert>

    <!-- 三栏并排对照（最多三份） -->
    <div :class="gridClass" class="source-grid">
      <el-card v-for="item in sources" :key="item.id" shadow="never" class="source-card">
        <template #header>
          <span class="source-tag">#{{ item.id }}</span>
          <span v-if="item.status === 'available'" class="status-tag">已上线</span>
          <span v-else-if="item.status === 'pending'" class="status-tag pending">待审核</span>
          <span v-else-if="item.status === 'draft'" class="status-tag">草稿</span>
        </template>
        <KpDisplay v-if="contentType === 'kp'" :kp="item as KpView" />
        <DocDisplay v-else :doc="item as DocView" />
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.merge-view {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.view-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.view-head h1 {
  margin: 0 0 4px;
  font-size: 20px;
  color: var(--zj-ink-800);
}

.view-sub {
  margin: 0;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-600);
}

.conflicts {
  border-radius: 8px;
}

.conflict-list {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: var(--zj-fs-sm);
}

.source-grid {
  display: grid;
  gap: 12px;
}

.source-grid.cols-2 {
  grid-template-columns: repeat(2, 1fr);
}

.source-grid.cols-3 {
  grid-template-columns: repeat(3, 1fr);
}

.source-card {
  min-width: 0;
}

.source-card :deep(.el-card__body) {
  max-height: 520px;
  overflow: auto;
}

.source-tag {
  font-weight: 600;
  color: var(--zj-primary);
  margin-right: 8px;
}

.status-tag {
  font-size: 11px;
  color: var(--zj-ink-500, #999);
}

.status-tag.pending {
  color: var(--zj-primary);
}

@media (max-width: 1080px) {
  .source-grid.cols-2,
  .source-grid.cols-3 {
    grid-template-columns: 1fr;
  }
}
</style>
