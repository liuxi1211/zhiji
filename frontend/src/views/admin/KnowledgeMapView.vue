<script setup lang="ts">
// 知识地图（T7，B端管理台PRD 第九节）：语料归一与按簇建设——三层视图（域 → 簇 → 归一题）
// + 归一确认 / 拆分回退 / 簇操作 + 覆盖率统计条。归一题与语料簇是工作草稿层（知识库PRD 九-3）：
// 高置信自动归并默认就绪、低置信逐条确认，误归并可拆回候选池重新研判。
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, MagicStick, VideoPlay } from '@element-plus/icons-vue'
import {
  confirmCanonical,
  getCanonical,
  getCoverage,
  getKnowledgeTree,
  listCanonicals,
  mergeKnowledgeClusters,
  moveCanonicalCluster,
  runNormalization,
  splitCanonical,
  suggestClusterName,
  unbuildCanonical
} from '@/api/pipeline'
import { updateCluster } from '@/api/kb'
import type { CanonicalDetail, CanonicalItem, CanonicalStatus, CoverageView, TreeView } from '@/api/types'

const STATUS_LABELS: Record<CanonicalStatus, string> = {
  pending: '待确认',
  confirmed: '已确认',
  built: '已建设'
}

const STATUS_TAG_TYPES: Record<CanonicalStatus, 'warning' | 'success' | 'info'> = {
  pending: 'warning',
  confirmed: 'success',
  built: 'info'
}

const BASIS_LABELS: Record<string, string> = {
  structural: '结构命中',
  exact: '极高相似',
  llm: 'LLM 精判'
}

const router = useRouter()

const loading = ref(false)
const running = ref(false)
const coverage = ref<CoverageView | null>(null)
const tree = ref<TreeView | null>(null)

const selectedDomainId = ref<number | null>(null)
const unclusteredMode = ref(false)
const selectedClusterId = ref<number | null>(null)
const canonicals = ref<CanonicalItem[]>([])

const drawerVisible = ref(false)
const drawerLoading = ref(false)
const detail = ref<CanonicalDetail | null>(null)

const splitVisible = ref(false)
const splitMode = ref<'all' | 'partial'>('all')
const splitSourceIds = ref<number[]>([])
const splitSaving = ref(false)

const moveVisible = ref(false)
const moveTarget = ref<number | null>(null)
const moveSaving = ref(false)

const mergeVisible = ref(false)
const mergeTarget = ref<number | null>(null)
const mergeSaving = ref(false)

const namingVisible = ref(false)
const namingLoading = ref(false)
const namingClusterId = ref<number | null>(null)
const namingName = ref('')
const namingDomainId = ref<number | null>(null)
const namingSaving = ref(false)

const clusters = computed(() => {
  if (!coverage.value) return []
  if (unclusteredMode.value) return []
  if (selectedDomainId.value === null) return coverage.value.clusters
  return coverage.value.clusters.filter((c) => c.domainId === selectedDomainId.value)
})

const mergeTargets = computed(
  () => coverage.value?.clusters.filter((c) => c.clusterId !== selectedClusterId.value) ?? []
)

const moveTargets = computed(
  () => coverage.value?.clusters.filter((c) => c.clusterId !== selectedClusterId.value) ?? []
)

async function messageOf(error: unknown): Promise<string> {
  return (error as { message?: string }).message ?? '操作失败'
}

async function loadOverview(): Promise<void> {
  coverage.value = await getCoverage()
  tree.value = await getKnowledgeTree()
}

async function loadCanonicals(): Promise<void> {
  if (unclusteredMode.value) {
    canonicals.value = (await listCanonicals({ unclustered: true })).list
    return
  }
  if (selectedClusterId.value === null) {
    canonicals.value = []
    return
  }
  canonicals.value = (await listCanonicals({ clusterId: selectedClusterId.value })).list
}

async function refresh(): Promise<void> {
  loading.value = true
  try {
    await loadOverview()
    await loadCanonicals()
  } catch (error) {
    ElMessage.error(await messageOf(error))
  } finally {
    loading.value = false
  }
}

function selectDomain(domainId: number | null): void {
  selectedDomainId.value = domainId
  unclusteredMode.value = false
  selectedClusterId.value = null
  canonicals.value = []
}

async function selectUnclustered(): Promise<void> {
  unclusteredMode.value = true
  selectedClusterId.value = null
  loading.value = true
  try {
    canonicals.value = (await listCanonicals({ unclustered: true })).list
  } catch (error) {
    ElMessage.error(await messageOf(error))
  } finally {
    loading.value = false
  }
}

async function selectCluster(clusterId: number): Promise<void> {
  unclusteredMode.value = false
  selectedClusterId.value = clusterId
  loading.value = true
  try {
    canonicals.value = (await listCanonicals({ clusterId })).list
  } catch (error) {
    ElMessage.error(await messageOf(error))
  } finally {
    loading.value = false
  }
}

async function runMap(): Promise<void> {
  running.value = true
  try {
    const summary = await runNormalization()
    const parts = [`散题 ${summary.looseCount}`, `新建归一题 ${summary.newCanonicals}`, `吸收 ${summary.absorbedItems}`, `建簇 ${summary.newClusters}`, `映射已建设 ${summary.mappedKp}`]
    ElMessage.success(`归一完成：${parts.join(' · ')}`)
    if (!summary.embeddingOk) {
      ElMessage.warning('embedding 调用失败，本轮仅用结构信号归并，可稍后重跑')
    }
    if (summary.llmJudgeTruncated > 0) {
      ElMessage.warning(`中间带待精判对数超出上限，${summary.llmJudgeTruncated} 对本轮跳过，建议分批重跑`)
    }
    await refresh()
  } catch (error) {
    ElMessage.error(await messageOf(error))
  } finally {
    running.value = false
  }
}

async function openDetail(canonical: CanonicalItem): Promise<void> {
  drawerVisible.value = true
  drawerLoading.value = true
  try {
    detail.value = await getCanonical(canonical.id)
  } catch (error) {
    ElMessage.error(await messageOf(error))
  } finally {
    drawerLoading.value = false
  }
}

function gotoDoc(docId: number): void {
  router.push({ path: '/admin/docs', query: { docId: String(docId) } })
}

async function doConfirm(canonical: CanonicalItem): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认「${canonical.title}」为同一题并入图？`, '归一确认', { type: 'info' })
  } catch {
    return
  }
  try {
    await confirmCanonical(canonical.id)
    ElMessage.success('已确认入图')
    await refresh()
    if (detail.value?.canonical.id === canonical.id) {
      detail.value = await getCanonical(canonical.id)
    }
  } catch (error) {
    ElMessage.error(await messageOf(error))
  }
}

async function doUnbuild(canonical: CanonicalItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '解除与已有知识点的映射后，该归一题退回「已确认」，重新进入建设视野。确认解除？',
      '解除映射',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await unbuildCanonical(canonical.id)
    ElMessage.success('已解除映射')
    await refresh()
  } catch (error) {
    ElMessage.error(await messageOf(error))
  }
}

function openSplit(canonical: CanonicalItem): void {
  splitMode.value = 'all'
  splitSourceIds.value = []
  void getCanonical(canonical.id).then((d) => {
    detail.value = d
    splitVisible.value = true
  })
}

async function doSplit(): Promise<void> {
  if (!detail.value) return
  if (splitMode.value === 'partial' && splitSourceIds.value.length === 0) {
    ElMessage.warning('请勾选要拆出的源候选')
    return
  }
  splitSaving.value = true
  try {
    const result = await splitCanonical(
      detail.value.canonical.id,
      splitMode.value,
      splitMode.value === 'partial' ? splitSourceIds.value : undefined
    )
    ElMessage.success(result.dissolved ? '已整题解散，候选退回候选池' : `已拆出 ${result.returned} 条候选`)
    splitVisible.value = false
    drawerVisible.value = false
    await refresh()
  } catch (error) {
    ElMessage.error(await messageOf(error))
  } finally {
    splitSaving.value = false
  }
}

function openMove(canonical: CanonicalItem): void {
  moveTarget.value = canonical.clusterId
  void getCanonical(canonical.id).then((d) => {
    detail.value = d
    moveVisible.value = true
  })
}

async function doMove(): Promise<void> {
  if (!detail.value) return
  moveSaving.value = true
  try {
    await moveCanonicalCluster(detail.value.canonical.id, moveTarget.value)
    ElMessage.success(moveTarget.value === null ? '已移出簇' : '已调整簇归属')
    moveVisible.value = false
    drawerVisible.value = false
    await refresh()
  } catch (error) {
    ElMessage.error(await messageOf(error))
  } finally {
    moveSaving.value = false
  }
}

function openMerge(clusterId: number): void {
  selectedClusterId.value = clusterId
  mergeTarget.value = null
  mergeVisible.value = true
}

async function doMerge(): Promise<void> {
  if (selectedClusterId.value === null || mergeTarget.value === null) {
    ElMessage.warning('请选择合并目标簇')
    return
  }
  mergeSaving.value = true
  try {
    await mergeKnowledgeClusters(selectedClusterId.value, mergeTarget.value)
    ElMessage.success('簇已合并（源簇下线保留指向）')
    mergeVisible.value = false
    selectedClusterId.value = null
    canonicals.value = []
    await refresh()
  } catch (error) {
    ElMessage.error(await messageOf(error))
  } finally {
    mergeSaving.value = false
  }
}

async function openNaming(clusterId: number): Promise<void> {
  namingClusterId.value = clusterId
  namingVisible.value = true
  namingLoading.value = true
  namingName.value = ''
  namingDomainId.value = null
  try {
    const suggestion = await suggestClusterName(clusterId)
    namingName.value = suggestion.name
    namingDomainId.value = suggestion.domainId
  } catch (error) {
    ElMessage.error(await messageOf(error))
  } finally {
    namingLoading.value = false
  }
}

async function adoptNaming(): Promise<void> {
  if (namingClusterId.value === null || !namingName.value.trim()) {
    ElMessage.warning('簇名不能为空')
    return
  }
  namingSaving.value = true
  try {
    await updateCluster(namingClusterId.value, {
      name: namingName.value.trim(),
      domainId: namingDomainId.value ?? undefined
    })
    ElMessage.success('已采纳命名与归属域')
    namingVisible.value = false
    await refresh()
  } catch (error) {
    ElMessage.error(await messageOf(error))
  } finally {
    namingSaving.value = false
  }
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <div class="map-page">
    <header class="page-head">
      <div>
        <p class="zj-eyebrow">内容生产</p>
        <h1>知识地图</h1>
        <p class="page-note">全量语料的三层归一视图：域 → 语料簇 → 归一题。覆盖源数排行就是「按簇建设」的排队依据。</p>
      </div>
      <el-button type="primary" :icon="VideoPlay" :loading="running" @click="runMap">运行归一</el-button>
    </header>

    <!-- 覆盖率统计条（B端管理台PRD 九） -->
    <el-card v-if="coverage" shadow="never" class="block">
      <div class="stats-bar">
        <div class="stat">
          <span class="stat-num">{{ coverage.domainCount }}</span>
          <span class="stat-label">技能域</span>
        </div>
        <div class="stat">
          <span class="stat-num">{{ coverage.clusterCount }}</span>
          <span class="stat-label">语料簇</span>
        </div>
        <div class="stat">
          <span class="stat-num">{{ coverage.canonicalCount }}</span>
          <span class="stat-label">归一题</span>
        </div>
        <div class="stat">
          <span class="stat-num">{{ coverage.builtCount }}</span>
          <span class="stat-label">已建设</span>
        </div>
        <div class="stat">
          <span class="stat-num">{{ coverage.pendingCount }}</span>
          <span class="stat-label">待确认</span>
        </div>
        <div class="stat">
          <span class="stat-num">{{ coverage.unclusteredCount }}</span>
          <span class="stat-label">待聚簇</span>
        </div>
      </div>
    </el-card>

    <div class="three-pane">
      <!-- 第一层：技能域 -->
      <el-card shadow="never" class="pane">
        <template #header><span>技能域</span></template>
        <div class="domain-list">
          <div
            class="domain-item"
            :class="{ active: !unclusteredMode && selectedDomainId === null }"
            @click="selectDomain(null)"
          >
            <span>全部域</span>
          </div>
          <div
            v-for="domain in tree?.domains ?? []"
            :key="domain.domainId"
            class="domain-item"
            :class="{ active: !unclusteredMode && selectedDomainId === domain.domainId }"
            @click="selectDomain(domain.domainId)"
          >
            <span class="domain-name">{{ domain.domainName }}</span>
            <span class="dim">{{ domain.canonicalCount }} 题</span>
          </div>
          <div class="domain-item unclustered" :class="{ active: unclusteredMode }" @click="selectUnclustered">
            <span class="domain-name">待聚簇</span>
            <el-tag size="small" type="warning">{{ tree?.unclusteredCount ?? 0 }}</el-tag>
          </div>
        </div>
      </el-card>

      <!-- 第二层：语料簇 -->
      <el-card shadow="never" class="pane">
        <template #header>
          <span>{{ unclusteredMode ? '待聚簇归一题' : '语料簇（按覆盖源合计排行）' }}</span>
        </template>
        <el-empty
          v-if="clusters.length === 0 && !unclusteredMode"
          description="运行归一后自动聚簇，或在「待聚簇」里人工归簇"
          :image-size="60"
        />
        <el-empty v-else-if="unclusteredMode" description="右侧列表展示未归簇的归一题" :image-size="60" />
        <div v-else class="cluster-list">
          <div
            v-for="cluster in clusters"
            :key="cluster.clusterId"
            class="cluster-card"
            :class="{ active: selectedClusterId === cluster.clusterId }"
            @click="selectCluster(cluster.clusterId)"
          >
            <div class="cluster-head">
              <span class="cluster-name">{{ cluster.name }}</span>
              <el-tag size="small" type="info">{{ cluster.domainName ?? '未归属域' }}</el-tag>
            </div>
            <div class="cluster-meta">
              {{ cluster.canonicalCount }} 归一题 · 覆盖源 {{ cluster.coverageSum }} · 已建设 {{ cluster.builtCount }}
            </div>
            <div class="cluster-ops" @click.stop>
              <el-button size="small" text type="primary" :icon="MagicStick" @click="openNaming(cluster.clusterId)">
                AI 命名
              </el-button>
              <el-button size="small" text :icon="Connection" @click="openMerge(cluster.clusterId)">合并</el-button>
            </div>
          </div>
        </div>
      </el-card>

      <!-- 第三层：归一题 -->
      <el-card shadow="never" class="pane">
        <template #header>
          <span>
            归一题{{ selectedClusterId ? `（${clusters.find((c) => c.clusterId === selectedClusterId)?.name ?? ''}）` : '' }}
          </span>
        </template>
        <el-empty v-if="canonicals.length === 0" description="选择左侧语料簇查看归一题" :image-size="60" />
        <el-table v-else v-loading="loading" :data="canonicals" size="small">
          <el-table-column prop="title" label="归一题面" min-width="200" show-overflow-tooltip />
          <el-table-column label="覆盖源" width="80" sortable prop="coverageCount" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="STATUS_TAG_TYPES[row.status as CanonicalStatus]">
                {{ STATUS_LABELS[row.status as CanonicalStatus] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="依据" width="90">
            <template #default="{ row }">
              <span v-if="row.judgeBasis" class="dim">{{ BASIS_LABELS[row.judgeBasis] ?? row.judgeBasis }}</span>
              <span v-else class="dim">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="210" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="openDetail(row)">源候选</el-button>
              <el-button
                v-if="row.status === 'pending'"
                size="small"
                text
                type="success"
                @click="doConfirm(row)"
              >确认</el-button>
              <el-button
                v-if="row.status === 'built'"
                size="small"
                text
                type="warning"
                @click="doUnbuild(row)"
              >解除映射</el-button>
              <el-button
                v-if="row.status !== 'built'"
                size="small"
                text
                @click="openSplit(row)"
              >拆分</el-button>
              <el-button size="small" text @click="openMove(row)">调簇</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <!-- 归一题详情抽屉：源候选溯源 -->
    <el-drawer v-model="drawerVisible" size="62%" :title="detail?.canonical.title ?? '归一题详情'">
      <div v-if="detail" v-loading="drawerLoading" class="detail-body">
        <div class="detail-meta">
          <el-tag size="small" :type="STATUS_TAG_TYPES[detail.canonical.status]">
            {{ STATUS_LABELS[detail.canonical.status] }}
          </el-tag>
          <el-tag v-if="detail.canonical.judgeBasis" size="small" type="info">
            {{ BASIS_LABELS[detail.canonical.judgeBasis] ?? detail.canonical.judgeBasis }}
          </el-tag>
          <span class="dim">覆盖源数 {{ detail.canonical.coverageCount }}</span>
          <span v-if="detail.canonical.builtKpId" class="dim">已建设 → 知识点 #{{ detail.canonical.builtKpId }}</span>
        </div>
        <p class="drawer-note">
          以下 N={{ detail.sources.length }} 条源候选被判定为同一题：各源问法将成问法变体候选，答案用于交叉验证成稿。
        </p>
        <el-table :data="detail.sources" size="small">
          <el-table-column prop="title" label="源问法" min-width="180" show-overflow-tooltip />
          <el-table-column prop="answerEvidence" label="答案证据" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.answerEvidence">{{ row.answerEvidence }}</span>
              <span v-else class="dim">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="sectionPath" label="章节路径" width="140" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.sectionPath">{{ row.sectionPath }}</span>
              <span v-else class="dim">—</span>
            </template>
          </el-table-column>
          <el-table-column label="出处" width="170">
            <template #default="{ row }">
              <span class="dim">{{ row.docFileName ?? `文档 #${row.docId}` }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="gotoDoc(row.docId)">回跳原文</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>

    <!-- 拆分回退 -->
    <el-dialog v-model="splitVisible" title="拆分回退" width="560">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="拆出的候选退回候选池重新研判；整题解散会删除这条归一题（语料草稿层可激进操作）。"
        class="split-alert"
      />
      <el-radio-group v-model="splitMode" class="split-mode">
        <el-radio value="all">整题解散（全部候选退回散题）</el-radio>
        <el-radio value="partial">拆出部分候选</el-radio>
      </el-radio-group>
      <el-checkbox-group v-if="splitMode === 'partial' && detail" v-model="splitSourceIds">
        <el-checkbox v-for="source in detail.sources" :key="source.candidateId" :value="source.candidateId">
          {{ source.title }}
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="splitVisible = false">取消</el-button>
        <el-button type="warning" :loading="splitSaving" @click="doSplit">执行拆分</el-button>
      </template>
    </el-dialog>

    <!-- 调整簇归属 -->
    <el-dialog v-model="moveVisible" title="调整簇归属" width="460">
      <el-select v-model="moveTarget" placeholder="选择目标簇（留空 = 移出簇）" clearable style="width: 100%">
        <el-option
          v-for="cluster in moveTargets"
          :key="cluster.clusterId"
          :label="`${cluster.name}（${cluster.domainName ?? '未归属域'}）`"
          :value="cluster.clusterId"
        />
      </el-select>
      <template #footer>
        <el-button @click="moveVisible = false">取消</el-button>
        <el-button type="primary" :loading="moveSaving" @click="doMove">确定</el-button>
      </template>
    </el-dialog>

    <!-- 簇合并 -->
    <el-dialog v-model="mergeVisible" title="合并语料簇" width="460">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="簇下归一题整体迁移到目标簇，源簇下线并保留指向。"
        class="split-alert"
      />
      <el-select v-model="mergeTarget" placeholder="选择合并目标簇" style="width: 100%">
        <el-option
          v-for="cluster in mergeTargets"
          :key="cluster.clusterId"
          :label="`${cluster.name}（${cluster.domainName ?? '未归属域'}）`"
          :value="cluster.clusterId"
        />
      </el-select>
      <template #footer>
        <el-button @click="mergeVisible = false">取消</el-button>
        <el-button type="primary" :loading="mergeSaving" @click="doMerge">合并</el-button>
      </template>
    </el-dialog>

    <!-- AI 命名建议 -->
    <el-dialog v-model="namingVisible" title="AI 簇命名建议" width="480">
      <div v-loading="namingLoading" class="naming-body">
        <el-input v-model="namingName" placeholder="簇名" maxlength="12" show-word-limit />
        <el-select v-model="namingDomainId" placeholder="归属技能域" style="width: 100%; margin-top: 12px">
          <el-option
            v-for="domain in tree?.domains.filter((d) => d.domainId > 0) ?? []"
            :key="domain.domainId"
            :label="domain.domainName"
            :value="domain.domainId"
          />
        </el-select>
        <p class="drawer-note">建议由模型按簇内题面与章节路径生成，采纳后才落库。</p>
      </div>
      <template #footer>
        <el-button @click="namingVisible = false">取消</el-button>
        <el-button type="primary" :loading="namingSaving" @click="adoptNaming">采纳</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.map-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.stats-bar {
  display: flex;
  gap: 32px;
  flex-wrap: wrap;
}

.stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 72px;
}

.stat-num {
  font-size: 24px;
  font-weight: 700;
  line-height: 1.2;
}

.stat-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.three-pane {
  display: grid;
  grid-template-columns: 220px 1fr 1.4fr;
  gap: 16px;
  align-items: start;
}

.domain-list,
.cluster-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.domain-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  border: 1px solid transparent;
}

.domain-item:hover {
  background: var(--el-fill-color-light);
}

.domain-item.active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.domain-name {
  font-weight: 500;
}

.cluster-card {
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  cursor: pointer;
}

.cluster-card:hover {
  border-color: var(--el-color-primary-light-5);
}

.cluster-card.active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.cluster-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.cluster-name {
  font-weight: 600;
}

.cluster-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.cluster-ops {
  margin-top: 6px;
  display: flex;
  gap: 4px;
}

.detail-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.detail-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.drawer-note,
.dim {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.split-alert {
  margin-bottom: 12px;
}

.split-mode {
  margin-bottom: 12px;
}

.naming-body {
  min-height: 96px;
}

@media (max-width: 1200px) {
  .three-pane {
    grid-template-columns: 1fr;
  }
}
</style>
