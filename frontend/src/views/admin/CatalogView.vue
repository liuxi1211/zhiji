<script setup lang="ts">
// 知识库管理（核心模块定义 5.2，资产家底形态）：领域 → 主题 → 知识点/资料讲解的目录树浏览与检索。
// 内容编辑、组织结构调整（主题改名/合并/调领域）、直达新建、下线与恢复都在这里。
// 打开它的理由是"要看家底"，按目录结构组织（与内容生产台的待办形态互补）。
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CirclePlus, Document, Edit, MagicStick, Refresh, Search, VideoPlay } from '@element-plus/icons-vue'
import {
  KB_STATUS_LABELS,
  createTopic,
  listContents,
  listTopics,
  moveTopic,
  mergeTopic,
  offlineContent,
  renameTopic,
  restoreContent,
  reviewDue,
  reviewResubmit,
  searchContents,
  updateDoc,
  updateKp,
  getDoc,
  getKp
} from '@/api/kb'
import type {
  KbContentItemView,
  KbDocDraft,
  KbKpDraft,
  KbStatus,
  KbTopicView,
  KpView,
  SourceDifficulty
} from '@/api/types'
import KpEditor from '@/components/kb/KpEditor.vue'
import DocEditor from '@/components/kb/DocEditor.vue'
import { useConstantsStore } from '@/stores/constants'

const router = useRouter()
const constants = useConstantsStore()

const loading = ref(false)
const topics = ref<KbTopicView[]>([])
const currentDomain = ref('')
const currentTopicId = ref<number | null>(null)
const contents = ref<KbContentItemView[]>([])
const typeFilter = ref('')
const statusFilter = ref('')
const keyword = ref('')
const dueCount = ref(0)
// 目录清单与模糊搜索共用一套分页状态：searchActive 决定取数来源（主题清单 or 搜索结果）
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const searchActive = ref(false)

const DIFF_LABELS: Record<string, string> = { junior: '初级', mid: '中级', senior: '高级', expert: '资深' }
const domainOptions = computed(() => constants.items('domain'))
const visibleTopics = computed(() => topics.value.filter((topic) => topic.status === 'active'))
const currentTopic = computed(() => topics.value.find((topic) => topic.id === currentTopicId.value) ?? null)

function messageOf(error: unknown, fallback: string): string {
  const err = error as { message?: string }
  return err.message ?? fallback
}

// 试判直达：带上 kpId 跳试判台自动预选，免掉重复搜索（职级仍需人工选择，它决定停止点）
function goTrial(row: KbContentItemView) {
  void router.push({ path: '/admin/judge', query: { kpId: String(row.id) } })
}

async function refreshTopics() {
  topics.value = await listTopics(currentDomain.value || undefined)
}

async function refreshDue() {
  try {
    // 只要到期总数，取一页一条即可（total 是全量计数）
    dueCount.value = (await reviewDue(1, 1)).total
  } catch {
    dueCount.value = 0
  }
}

// —— 目录清单 / 模糊搜索（服务复盘录题的同一能力），共用分页 ——

const searching = ref(false)

async function fetchContents() {
  if (searchActive.value) {
    const result = await searchContents(keyword.value.trim(), typeFilter.value || undefined, page.value, pageSize.value)
    contents.value = result.items
    total.value = result.total
    return
  }
  if (!currentTopicId.value) {
    contents.value = []
    total.value = 0
    return
  }
  const result = await listContents(
    currentTopicId.value,
    typeFilter.value || undefined,
    statusFilter.value || undefined,
    page.value,
    pageSize.value
  )
  contents.value = result.items
  total.value = result.total
}

/** 按当前筛选与页码重新取数：保存/下线/恢复后调用，保持页面位置与搜索态 */
async function refresh() {
  loading.value = true
  try {
    await fetchContents()
  } catch (error) {
    ElMessage.error(messageOf(error, '加载内容失败'))
  } finally {
    loading.value = false
  }
}

/** 切回目录清单模式：重置页码并重新取数 */
async function loadContents() {
  searchActive.value = false
  page.value = 1
  await refresh()
}

async function onDomainChange() {
  currentTopicId.value = null
  contents.value = []
  total.value = 0
  await refreshTopics()
}

async function doSearch() {
  if (!keyword.value.trim()) {
    ElMessage.warning('输入搜索关键词')
    return
  }
  searching.value = true
  searchActive.value = true
  page.value = 1
  loading.value = true
  try {
    await fetchContents()
  } catch (error) {
    ElMessage.error(messageOf(error, '搜索失败'))
  } finally {
    loading.value = false
    searching.value = false
  }
}

function clearSearch() {
  keyword.value = ''
  void loadContents()
}

function onPageSizeChange() {
  page.value = 1
  void refresh()
}

// —— 组织结构调整（只动位置，不动内容本身） ——

const topicDialog = reactive({ visible: false, mode: 'create' as 'create' | 'rename' | 'merge' | 'move', name: '', targetId: null as number | null, domainCode: '' })

function openTopicDialog(mode: 'create' | 'rename' | 'merge' | 'move') {
  if (mode !== 'create' && !currentTopic.value) {
    ElMessage.warning('先选择一个主题')
    return
  }
  topicDialog.mode = mode
  topicDialog.name = mode === 'rename' ? (currentTopic.value?.name ?? '') : ''
  topicDialog.targetId = null
  topicDialog.domainCode = mode === 'move' ? (currentTopic.value?.domainCode ?? '') : currentDomain.value || 'java'
  topicDialog.visible = true
}

const topicDialogTitle = computed(
  () => ({ create: '新建主题', rename: '重命名主题', merge: '合并主题（内容迁入目标）', move: '调整主题领域归属' })[topicDialog.mode]
)

async function confirmTopicDialog() {
  try {
    if (topicDialog.mode === 'create') {
      await createTopic(topicDialog.domainCode, topicDialog.name)
      currentDomain.value = topicDialog.domainCode
      ElMessage.success('主题已创建')
    } else if (topicDialog.mode === 'rename') {
      await renameTopic(currentTopicId.value!, topicDialog.name)
      ElMessage.success('主题已改名（调整即时生效于目录视图）')
    } else if (topicDialog.mode === 'merge') {
      if (!topicDialog.targetId) {
        ElMessage.warning('选择合并目标主题')
        return
      }
      await mergeTopic(currentTopicId.value!, topicDialog.targetId)
      ElMessage.success('主题已合并，内容已迁入目标主题')
      currentTopicId.value = topicDialog.targetId
    } else {
      await moveTopic(currentTopicId.value!, topicDialog.domainCode)
      currentDomain.value = topicDialog.domainCode
      ElMessage.success('领域归属已调整（内容跟主题走）')
    }
    topicDialog.visible = false
    await refreshTopics()
    await loadContents()
  } catch (error) {
    ElMessage.error(messageOf(error, '操作失败'))
  }
}

// —— 内容编辑（知识库本体维护线：available 直接生效不重审） ——

const editorVisible = ref(false)
const editing = ref<KbContentItemView | null>(null)
const kpDraft = ref<KbKpDraft | null>(null)
const docDraft = ref<KbDocDraft | null>(null)
const saving = ref(false)

async function openEditor(item: KbContentItemView) {
  loading.value = true
  try {
    editing.value = item
    if (item.contentType === 'kp') {
      const kp: KpView = await getKp(item.id)
      kpDraft.value = {
        title: kp.title,
        summary: kp.summary ?? '',
        frameworkMd: kp.frameworkMd,
        variants: kp.variants,
        followups: kp.followups,
        scoringPoints: kp.scoringPoints,
        answerSamples: kp.answerSamples,
        difficulty: kp.difficulty as SourceDifficulty,
        levelRules: kp.levelRules,
        prerequisites: kp.prerequisites,
        freqManual: kp.freqManual
      }
      docDraft.value = null
    } else {
      const doc = await getDoc(item.id)
      docDraft.value = {
        title: doc.title,
        summary: doc.summary ?? '',
        contentMd: doc.contentMd,
        difficulty: doc.difficulty as SourceDifficulty,
        kpId: doc.kpId
      }
      kpDraft.value = null
    }
    editorVisible.value = true
  } catch (error) {
    ElMessage.error(messageOf(error, '加载内容详情失败'))
  } finally {
    loading.value = false
  }
}

async function saveKp(draft: KbKpDraft) {
  if (!editing.value) return
  saving.value = true
  try {
    await updateKp(editing.value.id, {
      title: draft.title,
      summary: draft.summary,
      frameworkMd: draft.frameworkMd,
      variants: draft.variants,
      followups: draft.followups,
      scoringPoints: draft.scoringPoints,
      answerSamples: draft.answerSamples,
      difficulty: draft.difficulty,
      levelRules: draft.levelRules,
      prerequisites: draft.prerequisites,
      freqManual: draft.freqManual
    })
    ElMessage.success('已保存（编辑即时生效，不重走审核）')
    editorVisible.value = false
    await refresh()
  } catch (error) {
    ElMessage.error(messageOf(error, '保存失败'))
  } finally {
    saving.value = false
  }
}

async function saveDoc(draft: KbDocDraft) {
  if (!editing.value) return
  saving.value = true
  try {
    await updateDoc(editing.value.id, {
      title: draft.title,
      summary: draft.summary,
      contentMd: draft.contentMd,
      difficulty: draft.difficulty,
      kpId: draft.kpId ?? undefined
    })
    ElMessage.success('已保存（挂载动作即人工发布决策）')
    editorVisible.value = false
    await refresh()
  } catch (error) {
    ElMessage.error(messageOf(error, '保存失败'))
  } finally {
    saving.value = false
  }
}

// —— 下线与恢复 / 重新提交审核 ——

async function doOffline(item: KbContentItemView) {
  try {
    await ElMessageBox.confirm('弃用后内容下线（数据保留，可在"已下线"筛选中恢复），确认弃用？', '弃用下线', { type: 'warning' })
  } catch {
    return
  }
  try {
    await offlineContent(item.contentType, item.id)
    ElMessage.success('已弃用下线（全链路封死：不再作合并目标/前置依赖/挂载对象）')
    await refresh()
  } catch (error) {
    ElMessage.error(messageOf(error, '下线失败'))
  }
}

async function doRestore(item: KbContentItemView) {
  try {
    await restoreContent(item.contentType, item.id)
    ElMessage.success('已恢复为草稿，需重新提交审核')
    await refresh()
  } catch (error) {
    ElMessage.error(messageOf(error, '恢复失败'))
  }
}

async function doResubmit(item: KbContentItemView) {
  try {
    await reviewResubmit(item.contentType, item.id)
    ElMessage.success('已重新提交审核队列')
    await refresh()
  } catch (error) {
    ElMessage.error(messageOf(error, '提交失败'))
  }
}

onMounted(async () => {
  await constants.ensure()
  try {
    await refreshTopics()
    await refreshDue()
  } catch (error) {
    ElMessage.error(messageOf(error, '加载目录失败'))
  }
})

function statusLabel(status: KbStatus): string {
  return KB_STATUS_LABELS[status]
}

function statusTagType(status: KbStatus): 'primary' | 'success' | 'info' | 'warning' | 'danger' {
  switch (status) {
    case 'available':
      return 'success'
    case 'pending':
      return 'primary'
    case 'draft':
      return 'info'
    case 'merged':
      return 'warning'
    default:
      return 'danger'
  }
}
</script>

<template>
  <div class="catalog-view">
    <header class="view-head">
      <div>
        <h1>知识库管理</h1>
        <p class="view-sub">领域 → 主题 → 知识点/资料讲解：资产家底，编辑与组织维护、试判入口都在这里</p>
      </div>
      <div class="head-actions">
        <el-badge :value="dueCount" :hidden="dueCount === 0">
          <el-button @click="router.push('/admin/production')">复核到期巡检</el-button>
        </el-badge>
        <!-- 直达新建的两条线入口（5.2）：定类由入口预置，向导内不被改写建议覆盖；跳过加工不豁免审核 -->
        <el-button type="primary" :icon="CirclePlus" @click="router.push('/admin/wizard?type=kp')">新建知识点</el-button>
        <el-button :icon="Document" @click="router.push('/admin/wizard?type=doc')">新建资料讲解</el-button>
      </div>
    </header>

    <div class="catalog-body">
      <!-- 左侧：领域 → 主题树 -->
      <aside class="catalog-side">
        <el-select v-model="currentDomain" placeholder="全部领域" clearable style="width: 100%" @change="onDomainChange">
          <el-option v-for="option in domainOptions" :key="option.code" :label="option.name" :value="option.code" />
        </el-select>
        <div class="topic-list">
          <div
            v-for="topic in visibleTopics"
            :key="topic.id"
            class="topic-row"
            :class="{ active: topic.id === currentTopicId }"
            @click="currentTopicId = topic.id; loadContents()"
          >
            <span class="topic-name">{{ topic.name }}</span>
            <span class="topic-count">{{ topic.kpCount + topic.docCount }}</span>
          </div>
          <p v-if="!visibleTopics.length" class="topic-empty">暂无主题</p>
        </div>
        <div class="topic-ops">
          <el-button size="small" :icon="CirclePlus" @click="openTopicDialog('create')">新建主题</el-button>
          <el-button size="small" :icon="Edit" :disabled="!currentTopicId" @click="openTopicDialog('rename')">改名</el-button>
          <el-button size="small" :icon="MagicStick" :disabled="!currentTopicId" @click="openTopicDialog('merge')">合并主题</el-button>
          <el-button size="small" :disabled="!currentTopicId" @click="openTopicDialog('move')">调整领域</el-button>
        </div>
      </aside>

      <!-- 右侧：内容清单 + 检索 -->
      <main class="catalog-main">
        <div class="content-filter">
          <el-input
            v-model="keyword"
            placeholder="模糊搜索题面/摘要/正文（服务复盘录题同一能力）"
            style="width: 320px"
            clearable
            @keyup.enter="doSearch"
            @clear="clearSearch"
          >
            <template #append>
              <el-button :icon="Search" :loading="searching" @click="doSearch" />
            </template>
          </el-input>
          <el-select v-model="typeFilter" placeholder="类型" clearable style="width: 130px" @change="loadContents">
            <el-option label="知识点" value="kp" />
            <el-option label="资料讲解" value="doc" />
          </el-select>
          <el-select v-model="statusFilter" placeholder="状态（含已下线）" clearable style="width: 150px" @change="loadContents">
            <el-option v-for="(label, status) in KB_STATUS_LABELS" :key="status" :label="label" :value="status" />
          </el-select>
          <el-button :icon="Refresh" circle @click="loadContents" />
        </div>

        <el-table :data="contents" v-loading="loading" border>
          <el-table-column label="类型" width="90">
            <template #default="{ row }">
              <el-tag :type="row.contentType === 'kp' ? 'primary' : 'success'" size="small">
                {{ row.contentType === 'kp' ? '知识点' : '资料讲解' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="标题" min-width="220">
            <template #default="{ row }">
              <span class="content-title">{{ row.title }}</span>
              <el-tag v-if="row.mergeProduct" type="warning" size="small" class="row-tag">合并产物</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="摘要" min-width="200" prop="summary" />
          <el-table-column label="难度" width="70">
            <template #default="{ row }">{{ DIFF_LABELS[row.difficulty] ?? row.difficulty }}</template>
          </el-table-column>
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="270" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="openEditor(row)">编辑</el-button>
              <!-- 试判只对上线知识点开放（后端 D10 校验 kp 必须 available），doc 无可判定性 -->
              <el-button
                v-if="row.contentType === 'kp' && row.status === 'available'"
                size="small" text type="success" :icon="VideoPlay" @click="goTrial(row)"
              >试判</el-button>
              <el-button v-if="row.status === 'available'" size="small" text type="danger" @click="doOffline(row)">弃用</el-button>
              <el-button v-if="row.status === 'draft'" size="small" text type="warning" @click="doResubmit(row)">重新提交审核</el-button>
              <el-button v-if="row.status === 'deprecated' || row.status === 'merged'" size="small" text type="warning" @click="doRestore(row)">恢复</el-button>
            </template>
          </el-table-column>
          <template #empty>{{ searchActive ? '没有匹配的搜索结果——换个关键词试试' : '该主题下暂无内容——点右上角「新建知识点 / 新建资料讲解」走新增向导' }}</template>
        </el-table>

        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          background
          layout="total, sizes, prev, pager, next"
          :total="total"
          :page-sizes="[20, 50, 100]"
          @current-change="refresh"
          @size-change="onPageSizeChange"
        />
      </main>
    </div>

    <!-- 主题操作弹窗 -->
    <el-dialog v-model="topicDialog.visible" :title="topicDialogTitle" width="420px">
      <el-form label-width="88px" label-position="left">
        <el-form-item v-if="topicDialog.mode === 'create' || topicDialog.mode === 'move'" label="领域">
          <el-select v-model="topicDialog.domainCode" style="width: 100%">
            <el-option v-for="option in domainOptions" :key="option.code" :label="option.name" :value="option.code" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="topicDialog.mode === 'create' || topicDialog.mode === 'rename'" label="主题名">
          <el-input v-model="topicDialog.name" maxlength="60" placeholder="按「给用户看」的标准命名" />
        </el-form-item>
        <el-form-item v-if="topicDialog.mode === 'merge'" label="目标主题">
          <el-select v-model="topicDialog.targetId" style="width: 100%" placeholder="选择同领域目标主题">
            <el-option
              v-for="topic in visibleTopics.filter((t) => t.id !== currentTopicId && t.domainCode === currentTopic?.domainCode)"
              :key="topic.id"
              :label="topic.name"
              :value="topic.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="topicDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmTopicDialog">确定</el-button>
      </template>
    </el-dialog>

    <!-- 内容编辑抽屉 -->
    <!-- 内容编辑抽屉：KpEditor 是十几字段的长表单（叙述框架/追问链/样例×3），窄抽屉放不下 -->
    <el-drawer v-model="editorVisible" size="min(960px, 92vw)" :title="`编辑 · ${editing?.title ?? ''}`">
      <KpEditor
        v-if="editing?.contentType === 'kp' && kpDraft"
        :initial="kpDraft"
        :saving="saving"
        @save="saveKp"
      />
      <DocEditor
        v-else-if="editing?.contentType === 'doc' && docDraft"
        :initial="docDraft"
        :saving="saving"
        @save="saveDoc"
      />
    </el-drawer>
  </div>
</template>

<style scoped>
.catalog-view {
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

.head-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.catalog-body {
  display: grid;
  grid-template-columns: 260px 1fr;
  gap: 14px;
  align-items: start;
}

.catalog-side {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--zj-line);
  border-radius: 10px;
  background: var(--zj-surface);
}

.topic-list {
  max-height: 52vh;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.topic-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 7px 10px;
  border-radius: 8px;
  cursor: pointer;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-700);
}

.topic-row:hover {
  background: var(--zj-primary-soft);
}

.topic-row.active {
  background: var(--zj-primary-soft);
  color: var(--zj-primary);
  font-weight: 600;
}

.topic-count {
  font-size: 11px;
  color: var(--zj-ink-500, #999);
}

.topic-empty {
  margin: 8px 0;
  font-size: 12px;
  color: var(--zj-ink-500, #999);
  text-align: center;
}

.topic-ops {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.catalog-main {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

/* 分页器靠右，与表格右缘对齐 */
.catalog-main :deep(.el-pagination) {
  justify-content: flex-end;
}

.content-filter {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.content-title {
  font-weight: 500;
}

.row-tag {
  margin-left: 6px;
}

@media (max-width: 1080px) {
  .catalog-body {
    grid-template-columns: 1fr;
  }
}
</style>
