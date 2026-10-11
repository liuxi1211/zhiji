<script setup lang="ts">
// 内容生产台（核心模块定义 5.2，待办队列形态）：打开它的理由是"有事要做"，按待办量组织——
// ① 审核队列（进门质量门：四项强制校验人工确认后放行，退回/废弃留痕可回溯）；
// ② 同位管理（同主题内容勾选 ≤3 份进合并页）；
// ③ 复核到期（available 且复核到期的人工巡检列表，送复审回审核队列）。
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Right } from '@element-plus/icons-vue'
import {
  KB_STATUS_LABELS,
  listPeers,
  listTopics,
  reviewDetail,
  reviewDue,
  reviewPass,
  reviewQueue,
  reviewRecheck,
  reviewReject,
  reviewDiscard
} from '@/api/kb'
import type { KbContentItemView, KbContentType, KbReviewChecks, KbReviewDetail } from '@/api/types'
import KpDisplay from '@/components/kb/KpDisplay.vue'
import DocDisplay from '@/components/kb/DocDisplay.vue'
import { useConstantsStore } from '@/stores/constants'

const router = useRouter()
const constants = useConstantsStore()

const activeTab = ref('review')
const loading = ref(false)

const DIFF_LABELS: Record<string, string> = { junior: '初级', mid: '中级', senior: '高级', expert: '资深' }
const ACTION_LABELS: Record<string, string> = {
  submit: '提交审核',
  resubmit: '重新提交',
  recheck: '送复审',
  pass: '放行',
  reject: '退回',
  discard: '废弃'
}
const SOURCE_LABELS: Record<string, string> = { wizard: '向导', direct: '直达新建', merge: '同位合并', recheck: '复核' }

function messageOf(error: unknown, fallback: string): string {
  const err = error as { message?: string }
  return err.message ?? fallback
}

// —— Tab1 审核队列 ——

const queueItems = ref<KbContentItemView[]>([])
const reviewVisible = ref(false)
const reviewing = ref(false)
const detail = ref<KbReviewDetail | null>(null)
const checks = reactive<KbReviewChecks>({ correctness: 'pass', judgeable: 'pass', infringement: 'pass', quality: 'pass' })
const reviewComment = ref('')
const dueDays = ref(90)

const currentType = computed<KbContentType>(() => (detail.value?.contentType ?? 'kp') as KbContentType)
const contentTitle = computed(() => detail.value?.kp?.title ?? detail.value?.doc?.title ?? '')

async function loadQueue() {
  loading.value = true
  try {
    queueItems.value = await reviewQueue()
  } catch (error) {
    ElMessage.error(messageOf(error, '加载审核队列失败'))
  } finally {
    loading.value = false
  }
}

async function openReview(item: KbContentItemView) {
  reviewing.value = true
  try {
    detail.value = await reviewDetail(item.contentType, item.id)
    const isKp = item.contentType === 'kp'
    checks.correctness = 'pass'
    checks.judgeable = isKp ? 'pass' : 'na'
    checks.infringement = 'pass'
    checks.quality = 'pass'
    reviewComment.value = ''
    dueDays.value = 90
    reviewVisible.value = true
  } catch (error) {
    ElMessage.error(messageOf(error, '加载审核详情失败'))
  } finally {
    reviewing.value = false
  }
}

const allChecksPass = computed(() =>
  checks.correctness === 'pass' &&
  (currentType.value === 'doc' || checks.judgeable === 'pass') &&
  checks.infringement === 'pass' &&
  checks.quality === 'pass'
)

async function doPass() {
  if (!detail.value) return
  if (!allChecksPass.value) {
    ElMessage.warning('四项强制校验全部通过才能放行（不校不上线）')
    return
  }
  try {
    await reviewPass(currentType.value, contentId(), { ...checks }, reviewComment.value, dueDays.value)
    ElMessage.success('已放行，内容上线并设置复核到期')
    reviewVisible.value = false
    await loadQueue()
  } catch (error) {
    ElMessage.error(messageOf(error, '放行失败'))
  }
}

function contentId(): number {
  return detail.value?.kp?.id ?? detail.value?.doc?.id ?? 0
}

async function doReject() {
  if (!reviewComment.value.trim()) {
    ElMessage.warning('退回必须给出审核意见')
    return
  }
  try {
    await reviewReject(currentType.value, contentId(), reviewComment.value)
    ElMessage.success('已退回草稿态，可补正后重新提交')
    reviewVisible.value = false
    await loadQueue()
  } catch (error) {
    ElMessage.error(messageOf(error, '退回失败'))
  }
}

async function doDiscard() {
  try {
    await ElMessageBox.confirm('废弃后内容标记弃用下线（数据保留，可在知识库目录恢复），确认废弃？', '废弃确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await reviewDiscard(currentType.value, contentId(), reviewComment.value)
    ElMessage.success('已废弃')
    reviewVisible.value = false
    await loadQueue()
  } catch (error) {
    ElMessage.error(messageOf(error, '废弃失败'))
  }
}

// —— Tab2 同位管理 ——

const peerDomain = ref('')
const peerTopicId = ref<number | null>(null)
const peerItems = ref<KbContentItemView[]>([])
const peerSelection = ref<KbContentItemView[]>([])
const topicOptions = ref<{ id: number; name: string }[]>([])

const domainOptions = computed(() => constants.items('domain'))

async function loadTopics() {
  peerTopicId.value = null
  peerItems.value = []
  peerSelection.value = []
  topicOptions.value = peerDomain.value
    ? (await listTopics(peerDomain.value)).map((topic) => ({ id: topic.id, name: topic.name }))
    : []
}

async function loadPeers() {
  if (!peerTopicId.value) return
  loading.value = true
  try {
    peerItems.value = await listPeers(peerTopicId.value)
  } catch (error) {
    ElMessage.error(messageOf(error, '加载同位内容失败'))
  } finally {
    loading.value = false
  }
}

function onPeerSelectionChange(selection: KbContentItemView[]) {
  // 最多同时勾选三份（可视化合并页的硬约束）
  if (selection.length > 3) {
    ElMessage.warning('合并页最多同时勾选三份内容')
    peerSelection.value = selection.slice(0, 3)
    return
  }
  peerSelection.value = selection
}

function goMerge() {
  if (peerSelection.value.length < 2) {
    ElMessage.warning('至少勾选两份内容才能合并')
    return
  }
  const types = new Set(peerSelection.value.map((item) => item.contentType))
  if (types.size > 1) {
    ElMessage.warning('面试题线与文章线分开合并（两套实体数据分开存放）')
    return
  }
  const type = peerSelection.value[0].contentType
  const ids = peerSelection.value.map((item) => item.id).join(',')
  void router.push({ path: '/admin/production/merge', query: { type, ids } })
}

// —— Tab3 复核到期 ——

const dueItems = ref<KbContentItemView[]>([])

async function loadDue() {
  loading.value = true
  try {
    dueItems.value = await reviewDue()
  } catch (error) {
    ElMessage.error(messageOf(error, '加载复核到期列表失败'))
  } finally {
    loading.value = false
  }
}

async function doRecheck(item: KbContentItemView) {
  try {
    await reviewRecheck(item.contentType, item.id)
    ElMessage.success('已送复审，重新回审核队列')
    await loadDue()
  } catch (error) {
    ElMessage.error(messageOf(error, '送复审失败'))
  }
}

watch(activeTab, (tab) => {
  if (tab === 'review') void loadQueue()
  if (tab === 'due') void loadDue()
})

onMounted(async () => {
  await constants.ensure()
  await loadQueue()
})
</script>

<template>
  <div class="production-view">
    <header class="view-head">
      <div>
        <h1>内容生产台</h1>
        <p class="view-sub">审核队列 / 同位管理 / 复核到期：有事要做才打开的待办队列</p>
      </div>
      <el-button :icon="Refresh" @click="activeTab === 'review' ? loadQueue() : activeTab === 'due' ? loadDue() : loadPeers()">刷新</el-button>
    </header>

    <el-tabs v-model="activeTab">
      <!-- Tab1 审核队列 -->
      <el-tab-pane label="审核队列" name="review">
        <el-table :data="queueItems" v-loading="loading" size="default" border>
          <el-table-column label="类型" width="70">
            <template #default="{ row }">
              <el-tag :type="row.contentType === 'kp' ? 'primary' : 'success'" size="small">
                {{ row.contentType === 'kp' ? '知识点' : '资料讲解' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="标题" min-width="220" prop="title" />
          <el-table-column label="领域 / 主题" min-width="160">
            <template #default="{ row }">
              {{ constants.name('domain', row.domainCode) }} / {{ row.topicName }}
            </template>
          </el-table-column>
          <el-table-column label="难度" width="70">
            <template #default="{ row }">{{ DIFF_LABELS[row.difficulty] ?? row.difficulty }}</template>
          </el-table-column>
          <el-table-column label="来源" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.mergeProduct" type="warning" size="small">合并产物</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="提交时间" width="160" prop="createdAt" />
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" size="small" text :loading="reviewing" @click="openReview(row)">审核</el-button>
            </template>
          </el-table-column>
          <template #empty>队列已清空——没有待审内容</template>
        </el-table>
      </el-tab-pane>

      <!-- Tab2 同位管理 -->
      <el-tab-pane label="同位管理" name="peers">
        <div class="peer-filter">
          <el-select v-model="peerDomain" placeholder="选择领域" style="width: 220px" @change="loadTopics">
            <el-option v-for="option in domainOptions" :key="option.code" :label="option.name" :value="option.code" />
          </el-select>
          <el-select v-model="peerTopicId" placeholder="选择主题" style="width: 220px" :disabled="!peerDomain" @change="loadPeers">
            <el-option v-for="topic in topicOptions" :key="topic.id" :label="topic.name" :value="topic.id" />
          </el-select>
          <span class="peer-count">同主题内容 {{ peerItems.length }} 条（重复一眼可见，无需算法）</span>
        </div>
        <el-table
          :data="peerItems"
          v-loading="loading"
          size="default"
          border
          @selection-change="onPeerSelectionChange"
        >
          <el-table-column type="selection" width="44" />
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ row.contentType === 'kp' ? '知识点' : '资料讲解' }}</template>
          </el-table-column>
          <el-table-column label="标题" min-width="220" prop="title" />
          <el-table-column label="摘要" min-width="200" prop="summary" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">{{ KB_STATUS_LABELS[row.status as keyof typeof KB_STATUS_LABELS] }}</template>
          </el-table-column>
        </el-table>
        <div class="peer-actions">
          <el-button type="primary" :icon="Right" :disabled="peerSelection.length < 2" @click="goMerge">
            进合并页（已选 {{ peerSelection.length }}/3）
          </el-button>
        </div>
      </el-tab-pane>

      <!-- Tab3 复核到期 -->
      <el-tab-pane label="复核到期" name="due">
        <p class="tab-hint">上线后的内容有复核到期机制：到期回审核队列复检（一期列表人工巡检，不自动扫描）。</p>
        <el-table :data="dueItems" v-loading="loading" size="default" border>
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ row.contentType === 'kp' ? '知识点' : '资料讲解' }}</template>
          </el-table-column>
          <el-table-column label="标题" min-width="220" prop="title" />
          <el-table-column label="领域 / 主题" min-width="160">
            <template #default="{ row }">{{ constants.name('domain', row.domainCode) }} / {{ row.topicName }}</template>
          </el-table-column>
          <el-table-column label="复核到期日" width="120" prop="reviewDueAt" />
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button type="warning" size="small" text @click="doRecheck(row)">送复审</el-button>
            </template>
          </el-table-column>
          <template #empty>没有到期待复检的内容</template>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 审核抽屉：内容预览（所见即用户所得）+ 四项校验 + 放行/退回/废弃 -->
    <el-drawer v-model="reviewVisible" size="640px" :title="`审核 · ${contentTitle}`">
      <div v-if="detail" v-loading="reviewing" class="review-drawer">
        <div class="preview-block">
          <KpDisplay v-if="detail.contentType === 'kp' && detail.kp" :kp="detail.kp" />
          <DocDisplay v-else-if="detail.doc" :doc="detail.doc" />
        </div>

        <el-divider />

        <el-form label-width="120px" label-position="left" size="default">
          <el-form-item label="技术正确性">
            <el-radio-group v-model="checks.correctness">
              <el-radio-button value="pass">通过</el-radio-button>
              <el-radio-button value="fail">不通过</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="评分要点可判定">
            <el-radio-group v-model="checks.judgeable" :disabled="currentType === 'doc'">
              <el-radio-button value="pass">通过</el-radio-button>
              <el-radio-button value="fail">不通过</el-radio-button>
              <el-radio-button v-if="currentType === 'doc'" value="na">不适用</el-radio-button>
            </el-radio-group>
            <span v-if="currentType === 'doc'" class="check-hint">文章线无评分要求</span>
          </el-form-item>
          <el-form-item label="侵权检查">
            <el-radio-group v-model="checks.infringement">
              <el-radio-button value="pass">通过</el-radio-button>
              <el-radio-button value="fail">不通过</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="内容质量">
            <el-radio-group v-model="checks.quality">
              <el-radio-button value="pass">通过</el-radio-button>
              <el-radio-button value="fail">不通过</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="复核周期(天)">
            <el-input-number v-model="dueDays" :min="7" :max="365" controls-position="right" />
          </el-form-item>
          <el-form-item label="审核意见">
            <el-input v-model="reviewComment" type="textarea" :rows="2" placeholder="退回时必填；放行/废弃可留痕备注" />
          </el-form-item>
        </el-form>

        <div class="review-actions">
          <el-button type="primary" :disabled="!allChecksPass" @click="doPass">放行上线</el-button>
          <el-button type="warning" @click="doReject">退回</el-button>
          <el-button type="danger" @click="doDiscard">废弃</el-button>
        </div>

        <el-divider />
        <h4 class="history-title">审核留痕</h4>
        <el-timeline>
          <el-timeline-item v-for="record in detail.history" :key="record.id" :timestamp="record.createdAt">
            <b>{{ ACTION_LABELS[record.action] ?? record.action }}</b>
            <span class="history-source">（{{ SOURCE_LABELS[record.source] ?? record.source }}）</span>
            <span v-if="record.comment" class="history-comment">{{ record.comment }}</span>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.production-view {
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

.peer-filter {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 12px;
}

.peer-count {
  font-size: 12px;
  color: var(--zj-ink-600);
}

.peer-actions {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}

.tab-hint {
  margin: 0 0 10px;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-600);
}

.review-drawer {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.preview-block {
  max-height: 42vh;
  overflow: auto;
  padding-right: 4px;
}

.check-hint {
  margin-left: 8px;
  font-size: 11px;
  color: var(--zj-ink-500, #999);
}

.review-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.history-title {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--zj-ink-700);
}

.history-source {
  margin-left: 6px;
  font-size: 11px;
  color: var(--zj-ink-500, #999);
}

.history-comment {
  display: block;
  font-size: 12px;
  color: var(--zj-ink-600);
}
</style>
