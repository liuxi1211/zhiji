<script setup lang="ts">
// 素材库（T6，B端管理台PRD 第六节）：批量导入（多文件 + zip）→ 解析（LLM 形态识别）
// → 预览修正（形态 / 片段反推 / 拆题结果编辑）→ 拆题入池。候选条目溯源回素材文档；
// 版权红线：解析文本只在预览可见，出处提示固定展示（6.4）。
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Plus, Refresh, Search, Upload } from '@element-plus/icons-vue'
import MarkdownView from '@/components/MarkdownView.vue'
import {
  changeDocForm,
  deleteDoc,
  discardCandidate,
  getDoc,
  importDocs,
  inverseGen,
  listBatches,
  listDocs,
  parseDoc,
  retryDoc,
  splitDoc,
  updateCandidate
} from '@/api/pipeline'
import type {
  BatchSummary,
  CandidateItem,
  SourceDocStatus,
  SourceDocView,
  SourceForm
} from '@/api/types'

const FORM_LABELS: Record<SourceForm, string> = {
  question_bank: '题库型',
  reading: '资料型',
  interview: '面经型'
}

const STATUS_LABELS: Record<SourceDocStatus, string> = {
  pending: '待解析',
  parsed: '解析成功',
  parse_failed: '解析失败',
  split: '已拆题入池'
}

const STATUS_TAG_TYPES: Record<SourceDocStatus, 'info' | 'success' | 'danger' | 'primary'> = {
  pending: 'info',
  parsed: 'primary',
  parse_failed: 'danger',
  split: 'success'
}

// 上传出处提示（B端管理台PRD 6.4 固定文案）
const SOURCE_NOTICE =
  '各来源协议与授权各异，素材只作参考输入，成品一律走「AI 参考重写 + 人工审核」管道；' +
  'docs/题库源/ 下的仓库素材仅限本地使用，随开源或共享分发前必须移出。'

const batches = ref<BatchSummary[]>([])
const currentBatchId = ref<string | null>(null)
const docs = ref<SourceDocView[]>([])
const loading = ref(false)
const uploading = ref(false)
const uploadVisible = ref(false)
const uploadFiles = ref<File[]>([])
const uploadSourceRef = ref('')

const previewVisible = ref(false)
const previewLoading = ref(false)
const previewDoc = ref<SourceDocView | null>(null)
const previewCandidates = ref<CandidateItem[]>([])
const inverseRunning = ref(false)
const selectedSections = ref<number[]>([])

const headingTree = computed(() => {
  const raw = previewDoc.value?.headingTree
  if (!raw) return []
  try {
    return JSON.parse(raw) as { level: number; title: string; line: number }[]
  } catch {
    return []
  }
})

const canInverse = computed(
  () => previewDoc.value?.form === 'reading' && (previewDoc.value?.status === 'parsed' || previewDoc.value?.status === 'split')
)

async function messageOf(error: unknown): Promise<string> {
  return (error as { message?: string }).message ?? '操作失败'
}

async function loadAll(): Promise<void> {
  loading.value = true
  try {
    batches.value = await listBatches()
    docs.value = await listDocs(currentBatchId.value ? { batchId: currentBatchId.value } : undefined)
  } catch (error) {
    ElMessage.error(await messageOf(error))
  } finally {
    loading.value = false
  }
}

function selectBatch(batchId: string | null): void {
  currentBatchId.value = batchId
  void loadAll()
}

function handleFileChange(files: File[]): void {
  uploadFiles.value = files
}

async function doUpload(): Promise<void> {
  if (uploadFiles.value.length === 0) {
    ElMessage.warning('请选择文件（支持多选与 zip）')
    return
  }
  uploading.value = true
  try {
    const result = await importDocs(uploadFiles.value, uploadSourceRef.value || undefined)
    ElMessage.success(`已导入 ${result.docs.length} 个文件（批次 ${result.batchId}）`)
    uploadVisible.value = false
    uploadFiles.value = []
    uploadSourceRef.value = ''
    await loadAll()
  } catch (error) {
    ElMessage.error(await messageOf(error))
  } finally {
    uploading.value = false
  }
}

async function openPreview(doc: SourceDocView): Promise<void> {
  previewVisible.value = true
  previewLoading.value = true
  previewDoc.value = doc
  previewCandidates.value = []
  try {
    const detail = await getDoc(doc.id)
    previewDoc.value = detail.doc
    previewCandidates.value = detail.candidates
    selectedSections.value = []
  } catch (error) {
    ElMessage.error(await messageOf(error))
  } finally {
    previewLoading.value = false
  }
}

async function runParse(doc: SourceDocView): Promise<void> {
  loading.value = true
  try {
    await parseDoc(doc.id)
    ElMessage.success('解析完成')
    await loadAll()
  } catch (error) {
    ElMessage.error(await messageOf(error))
    await loadAll()
  } finally {
    loading.value = false
  }
}

async function runRetry(doc: SourceDocView): Promise<void> {
  loading.value = true
  try {
    await retryDoc(doc.id)
    ElMessage.success('已重试解析')
    await loadAll()
  } catch (error) {
    ElMessage.error(await messageOf(error))
    await loadAll()
  } finally {
    loading.value = false
  }
}

async function changeForm(form: SourceForm): Promise<void> {
  if (!previewDoc.value) return
  try {
    await changeDocForm(previewDoc.value.id, form)
    previewDoc.value.form = form
    ElMessage.success(`形态已修正为「${FORM_LABELS[form]}」`)
    await loadAll()
  } catch (error) {
    ElMessage.error(await messageOf(error))
  }
}

async function runSplit(): Promise<void> {
  if (!previewDoc.value) return
  try {
    const result = await splitDoc(previewDoc.value.id)
    ElMessage.success(`拆题完成，入池 ${result.inserted} 条候选`)
    const detail = await getDoc(previewDoc.value.id)
    previewDoc.value = detail.doc
    previewCandidates.value = detail.candidates
    await loadAll()
  } catch (error) {
    ElMessage.error(await messageOf(error))
  }
}

async function runInverse(): Promise<void> {
  if (!previewDoc.value) return
  if (selectedSections.value.length === 0) {
    ElMessage.warning('请先在片段列表勾选要反推出题的章节')
    return
  }
  inverseRunning.value = true
  try {
    const result = await inverseGen(previewDoc.value.id, selectedSections.value)
    ElMessage.success(`反推出题完成，入池 ${result.inserted} 条候选`)
    const detail = await getDoc(previewDoc.value.id)
    previewDoc.value = detail.doc
    previewCandidates.value = detail.candidates
    await loadAll()
  } catch (error) {
    ElMessage.error(await messageOf(error))
  } finally {
    inverseRunning.value = false
  }
}

async function saveCandidate(candidate: CandidateItem): Promise<void> {
  try {
    await updateCandidate(candidate.id, {
      title: candidate.title,
      answerEvidence: candidate.answerEvidence ?? '',
      form: candidate.form
    })
    ElMessage.success('候选条目已修正')
  } catch (error) {
    ElMessage.error(await messageOf(error))
  }
}

async function discardOne(candidate: CandidateItem): Promise<void> {
  try {
    await ElMessageBox.confirm(`放弃候选「${candidate.title}」？放弃后保留记录。`, '放弃候选', {
      confirmButtonText: '放弃',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await discardCandidate(candidate.id)
    candidate.status = 'discarded'
    ElMessage.success('已放弃')
    await loadAll()
  } catch (error) {
    ElMessage.error(await messageOf(error))
  }
}

async function removeDoc(doc: SourceDocView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除素材「${doc.fileName}」？解析文本一并删除；名下候选需全部处于待研判状态。`,
      '删除素材',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteDoc(doc.id)
    ElMessage.success('已删除')
    await loadAll()
  } catch (error) {
    ElMessage.error(await messageOf(error))
  }
}

onMounted(() => {
  void loadAll()
})
</script>

<template>
  <div class="doc-page">
    <header class="page-head">
      <div>
        <p class="zj-eyebrow">内容生产</p>
        <h1>素材库</h1>
        <p class="page-note">批量导入多源素材（v1 仅 md / txt，docx 用 pandoc 预转 md，pdf 文字版用专用工具转 md），解析后按形态路由拆题。</p>
      </div>
      <el-button type="primary" :icon="Upload" @click="uploadVisible = true">批量导入</el-button>
    </header>

    <el-alert class="source-alert" type="info" :closable="false" show-icon :title="SOURCE_NOTICE" />

    <!-- 批次面板 -->
    <el-card shadow="never" class="block">
      <template #header>
        <span>导入批次（批次是进度与失败重试的单位）</span>
      </template>
      <el-empty v-if="batches.length === 0" description="还没有导入批次" :image-size="60" />
      <div v-else class="batch-list">
        <div
          class="batch-card"
          :class="{ active: currentBatchId === null }"
          @click="selectBatch(null)"
        >
          <span class="batch-name">全部批次</span>
        </div>
        <div
          v-for="batch in batches"
          :key="batch.batchId"
          class="batch-card"
          :class="{ active: currentBatchId === batch.batchId }"
          @click="selectBatch(batch.batchId)"
        >
          <span class="batch-name">{{ batch.batchId }}</span>
          <span class="batch-meta">{{ batch.fileCount }} 文件 · {{ batch.createdAt }}</span>
          <div class="batch-counts">
            <el-tag size="small" type="info">待 {{ batch.pendingCount }}</el-tag>
            <el-tag size="small">解析 {{ batch.parsedCount }}</el-tag>
            <el-tag size="small" type="danger">败 {{ batch.failedCount }}</el-tag>
            <el-tag size="small" type="success">拆 {{ batch.splitCount }}</el-tag>
          </div>
          <ul v-if="batch.failures.length" class="batch-failures">
            <li v-for="f in batch.failures" :key="f.id">{{ f.fileName }}：{{ f.error }}</li>
          </ul>
        </div>
      </div>
    </el-card>

    <!-- 文件表格 -->
    <el-card shadow="never" class="block">
      <template #header>
        <span>素材文件{{ currentBatchId ? `（批次 ${currentBatchId}）` : '' }}</span>
      </template>
      <el-table v-loading="loading" :data="docs" size="small">
        <el-table-column prop="fileName" label="文件名" min-width="220" show-overflow-tooltip />
        <el-table-column label="形态" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.form" size="small">{{ FORM_LABELS[row.form as SourceForm] }}</el-tag>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="STATUS_TAG_TYPES[row.status as SourceDocStatus]">
              {{ STATUS_LABELS[row.status as SourceDocStatus] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="字符数" width="90">
          <template #default="{ row }">{{ row.charCount || '—' }}</template>
        </el-table-column>
        <el-table-column prop="parseError" label="失败原因" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.parseError" class="error-text">{{ row.parseError }}</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'pending'"
              size="small"
              type="primary"
              :icon="Search"
              @click="runParse(row)"
            >解析</el-button>
            <el-button
              v-if="row.status === 'parse_failed'"
              size="small"
              type="warning"
              :icon="Refresh"
              @click="runRetry(row)"
            >重试</el-button>
            <el-button size="small" @click="openPreview(row)">预览</el-button>
            <el-button size="small" type="danger" :icon="Delete" plain @click="removeDoc(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 上传对话框 -->
    <el-dialog v-model="uploadVisible" title="批量导入素材" width="560">
      <el-upload
        drag
        multiple
        :auto-upload="false"
        accept=".md,.markdown,.txt,.zip"
        :on-change="(_file: any, files: any) => handleFileChange(files)"
      >
        <el-icon :size="36"><Plus /></el-icon>
        <div class="upload-tip">拖入文件或点击选择（多选 / 整文件夹 / zip 包）</div>
      </el-upload>
      <el-input
        v-model="uploadSourceRef"
        placeholder="来源出处：GitHub 仓库 / CSDN 帖子链接，或书名·手册章节"
        class="source-input"
      />
      <el-alert type="info" :closable="false" show-icon :title="SOURCE_NOTICE" class="source-alert" />
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="doUpload">导入</el-button>
      </template>
    </el-dialog>

    <!-- 预览抽屉：左解析文本 / 右拆题结果（B端管理台PRD 6.3） -->
    <el-drawer v-model="previewVisible" size="78%" :title="previewDoc?.fileName ?? '预览'">
      <div v-loading="previewLoading" class="preview-body">
        <template v-if="previewDoc">
          <div class="preview-toolbar">
            <el-tag :type="STATUS_TAG_TYPES[previewDoc.status]">{{ STATUS_LABELS[previewDoc.status] }}</el-tag>
            <el-select
              :model-value="previewDoc.form"
              placeholder="内容形态"
              size="small"
              style="width: 140px"
              @change="(value: SourceForm) => changeForm(value)"
            >
              <el-option
                v-for="(label, code) in FORM_LABELS"
                :key="code"
                :label="label"
                :value="code"
              />
            </el-select>
            <span v-if="previewDoc.sourceRef" class="dim">出处：{{ previewDoc.sourceRef }}</span>
            <div class="toolbar-spacer" />
            <el-button
              v-if="previewDoc.status === 'parsed' || previewDoc.status === 'split'"
              type="primary"
              size="small"
              @click="runSplit"
            >拆题入池</el-button>
          </div>

          <div class="preview-grid">
            <div class="preview-left">
              <h4>解析文本与标题树</h4>
              <ul v-if="headingTree.length" class="heading-tree">
                <li
                  v-for="(h, index) in headingTree"
                  :key="index"
                  :style="{ paddingLeft: (h.level - 1) * 14 + 'px' }"
                  :class="{ clickable: canInverse }"
                >
                  <el-checkbox
                    v-if="canInverse && h.level <= 2"
                    v-model="selectedSections"
                    :value="index"
                    size="small"
                  >{{ h.title }}</el-checkbox>
                  <span v-else>{{ h.title }}</span>
                </li>
              </ul>
              <p v-else class="dim">无标题树（txt 启发式或纯文本）</p>
              <div class="raw-text">
                <MarkdownView v-if="previewDoc.rawText" :source="previewDoc.rawText" />
                <p v-else class="dim">（待解析）</p>
              </div>
              <div v-if="canInverse" class="inverse-bar">
                <el-button
                  size="small"
                  type="primary"
                  plain
                  :loading="inverseRunning"
                  @click="runInverse"
                >对勾选片段反推出题（LLM 成本高，按需触发）</el-button>
              </div>
            </div>

            <div class="preview-right">
              <h4>拆题结果（{{ previewCandidates.filter((c) => c.status === 'pending').length }} 条待研判）</h4>
              <el-empty
                v-if="previewCandidates.filter((c) => c.status === 'pending').length === 0"
                description="尚未拆题或无可编辑条目"
                :image-size="56"
              />
              <div v-for="candidate in previewCandidates" :key="candidate.id" class="candidate-item">
                <template v-if="candidate.status !== 'discarded'">
                  <div class="candidate-head">
                    <el-tag size="small" :type="candidate.confidence === 'high' ? 'success' : 'warning'">
                      {{ candidate.confidence === 'high' ? '结构命中' : 'LLM 兜底' }}
                    </el-tag>
                    <el-tag size="small" type="info">{{ FORM_LABELS[candidate.form] }}</el-tag>
                    <span v-if="candidate.sectionPath" class="dim">{{ candidate.sectionPath }}</span>
                    <div class="toolbar-spacer" />
                    <el-button size="small" text type="danger" @click="discardOne(candidate)">放弃</el-button>
                  </div>
                  <el-input
                    v-model="candidate.title"
                    type="textarea"
                    :rows="2"
                    autosize
                    placeholder="题面"
                  />
                  <el-input
                    v-if="candidate.answerEvidence"
                    v-model="candidate.answerEvidence"
                    type="textarea"
                    :rows="3"
                    autosize
                    placeholder="答案证据"
                  />
                  <el-button size="small" class="candidate-save" @click="saveCandidate(candidate)">保存修正</el-button>
                </template>
              </div>
            </div>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.doc-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.page-head h1 {
  margin: 4px 0 6px;
  font-size: 22px;
  font-weight: 700;
  color: var(--zj-ink-900);
}

.page-note {
  margin: 0;
  font-size: 13px;
  color: var(--zj-ink-600);
}

.source-alert {
  margin: 0;
}

.batch-list {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.batch-card {
  width: 260px;
  padding: 12px 14px;
  border: 1px solid var(--zj-line-soft);
  border-radius: 10px;
  cursor: pointer;
  transition: border-color 0.15s ease-out;
}

.batch-card:hover {
  border-color: var(--zj-primary);
}

.batch-card.active {
  border-color: var(--zj-primary);
  background: var(--zj-primary-soft);
}

.batch-name {
  display: block;
  font-size: 13px;
  font-weight: 650;
  color: var(--zj-ink-900);
}

.batch-meta {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--zj-ink-600);
}

.batch-counts {
  display: flex;
  gap: 4px;
  margin-top: 8px;
  flex-wrap: wrap;
}

.batch-failures {
  margin: 8px 0 0;
  padding: 8px 10px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--zj-danger, #c0392b);
  background: var(--zj-danger-soft, #fdf0ee);
  border-radius: 6px;
  list-style: none;
}

.error-text {
  color: var(--zj-danger, #c0392b);
  font-size: 12px;
}

.dim {
  color: var(--zj-ink-500, #93a09a);
  font-size: 12px;
}

.upload-tip {
  font-size: 13px;
  color: var(--zj-ink-600);
}

.source-input {
  margin-top: 12px;
}

.preview-body {
  min-height: 300px;
}

.preview-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

.toolbar-spacer {
  flex: 1;
}

.preview-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  gap: 20px;
}

.preview-left h4,
.preview-right h4 {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 650;
  color: var(--zj-ink-800);
}

.heading-tree {
  margin: 0 0 12px;
  padding: 8px 12px;
  font-size: 12px;
  line-height: 2;
  list-style: none;
  background: var(--zj-paper);
  border-radius: 8px;
}

.heading-tree.clickable {
  cursor: pointer;
}

.raw-text {
  max-height: 46vh;
  padding: 14px;
  overflow-y: auto;
  font-size: 13px;
  background: var(--zj-paper);
  border-radius: 8px;
}

.inverse-bar {
  margin-top: 12px;
}

.candidate-item {
  margin-bottom: 14px;
  padding: 12px 14px;
  border: 1px solid var(--zj-line-soft);
  border-radius: 10px;
}

.candidate-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.candidate-item .el-textarea {
  margin-bottom: 8px;
}

.candidate-save {
  margin-top: 4px;
}

@media (max-width: 1080px) {
  .preview-grid {
    grid-template-columns: 1fr;
  }
}
</style>
