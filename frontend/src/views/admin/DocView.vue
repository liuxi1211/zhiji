<script setup lang="ts">
// 素材库（核心模块定义 5.1 辅通道）：素材仓库，职责只有两件事——
// ① 承接素材：手写新增 / 导入（txt、md 及打包图片资源的压缩包）/ URL 抓取（失败回退空稿）；
// ② 轻量整理：编辑正文 + AI 改写（可提前完成的部分，产物永远回到人手里，6.4 AI 边界）。
// 入库动作只有一个「进标准库」按钮：点击后引导该素材进入新增向导（/admin/wizard）接续走完剩余流程。
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Delete, Edit, MagicStick, Plus, Position, Refresh, Search, Upload } from '@element-plus/icons-vue'
import {
  aiRewrite,
  createSource,
  deleteSource,
  discardSource,
  fetchSource,
  getSource,
  importSource,
  listSources,
  updateSource
} from '@/api/source'
import type { SourceItemView, SourceStatus, SourceType } from '@/api/types'
import StatusMeterBar from '@/components/StatusMeterBar.vue'
import type { MeterSegment } from '@/components/StatusMeterBar.vue'
import { createMarkdown } from '@/utils/markdown'

// asset: 引用渲染为 B 端资产接口地址（导入 zip 图片「落盘即真相」的路径约定，与向导/标准库共用同一规则）
const md = createMarkdown()

const router = useRouter()

const STATUS_LABELS: Record<SourceStatus, string> = {
  pending: '待加工',
  submitted: '已进标准库',
  discarded: '已放弃'
}
const TYPE_LABELS: Record<SourceType, string> = {
  manual: '手写',
  import: '导入',
  url: 'URL 抓取',
  inferred: '反推出题'
}

const loading = ref(false)
// 全量拉取后本地筛选：B 端素材量级小，换来状态读数恒可见 + 筛选零延迟
const items = ref<SourceItemView[]>([])
const statusFilter = ref('')
const keyword = ref('')

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return items.value.filter((item) => {
    if (statusFilter.value && item.status !== statusFilter.value) return false
    if (kw && !item.title.toLowerCase().includes(kw) && !(item.summary ?? '').toLowerCase().includes(kw)) {
      return false
    }
    return true
  })
})

const segments = computed<MeterSegment[]>(() => {
  const countOf = (status: SourceStatus) => items.value.filter((item) => item.status === status).length
  return [
    { key: 'pending', label: STATUS_LABELS.pending, count: countOf('pending'), tone: 'primary' },
    { key: 'submitted', label: STATUS_LABELS.submitted, count: countOf('submitted'), tone: 'success' },
    { key: 'discarded', label: STATUS_LABELS.discarded, count: countOf('discarded'), tone: 'neutral' }
  ]
})

function messageOf(error: unknown, fallback: string): string {
  const err = error as { message?: string }
  return err.message ?? fallback
}

async function load() {
  loading.value = true
  try {
    items.value = await listSources()
  } catch (error) {
    ElMessage.error(messageOf(error, '加载素材失败'))
  } finally {
    loading.value = false
  }
}

onMounted(() => void load())

// —— 承接素材：手写 / 导入 / URL 抓取 ——

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ title: '', contentMd: '' })

function openCreate() {
  createForm.title = ''
  createForm.contentMd = ''
  createVisible.value = true
}

async function submitCreate() {
  if (!createForm.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  creating.value = true
  try {
    const created = await createSource({ title: createForm.title, contentMd: createForm.contentMd })
    createVisible.value = false
    ElMessage.success('已入库素材库（待加工）')
    await load()
    void openEditor(created.id)
  } catch (error) {
    ElMessage.error(messageOf(error, '创建失败'))
  } finally {
    creating.value = false
  }
}

const importInput = ref<HTMLInputElement | null>(null)

function pickImport() {
  importInput.value?.click()
}

async function onImportFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  loading.value = true
  try {
    const created = await importSource(file)
    ElMessage.success(`导入成功：${created.title}`)
    await load()
    void openEditor(created.id)
  } catch (error) {
    ElMessage.error(messageOf(error, '导入失败'))
  } finally {
    loading.value = false
    input.value = ''
  }
}

const fetchVisible = ref(false)
const fetching = ref(false)
const fetchUrl = ref('')

function onImportCommand(command: string | number | object) {
  if (command === 'file') pickImport()
  else if (command === 'url') fetchVisible.value = true
}

async function submitFetch() {
  if (!fetchUrl.value.trim()) {
    ElMessage.warning('请填写 URL')
    return
  }
  fetching.value = true
  try {
    const created = await fetchSource(fetchUrl.value)
    fetchVisible.value = false
    fetchUrl.value = ''
    ElMessage.success('抓取完成（失败回退空稿，可在编辑中人工补录）')
    await load()
    void openEditor(created.id)
  } catch (error) {
    ElMessage.error(messageOf(error, '抓取失败'))
  } finally {
    fetching.value = false
  }
}

// —— 轻量整理：编辑正文 + AI 改写（素材库允许提前完成的部分） ——

const editorVisible = ref(false)
const editorLoading = ref(false)
const saving = ref(false)
const current = ref<SourceItemView | null>(null)
const form = reactive({ title: '', contentMd: '' })
const editorTab = ref<'edit' | 'preview'>('edit')
const rewriting = ref(false)

const wordCount = computed(() => form.contentMd.length.toLocaleString('zh-CN'))

function renderMarkdown(content: string): string {
  return md.render(content || '')
}

async function openEditor(id: number) {
  editorVisible.value = true
  editorLoading.value = true
  editorTab.value = 'edit'
  try {
    const item = await getSource(id)
    current.value = item
    form.title = item.title
    form.contentMd = item.contentMd
  } catch (error) {
    ElMessage.error(messageOf(error, '加载素材失败'))
    editorVisible.value = false
  } finally {
    editorLoading.value = false
  }
}

async function save() {
  if (!current.value) return
  saving.value = true
  try {
    await updateSource(current.value.id, { title: form.title, contentMd: form.contentMd })
    ElMessage.success('已保存')
    editorVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(messageOf(error, '保存失败'))
  } finally {
    saving.value = false
  }
}

async function runRewrite() {
  if (!current.value) return
  rewriting.value = true
  try {
    const resp = await aiRewrite(current.value.id)
    form.contentMd = resp.content
    ElMessage.success('改写完成，产物已回编辑器，请人工确认')
  } catch (error) {
    ElMessage.error(messageOf(error, 'AI 改写失败'))
  } finally {
    rewriting.value = false
  }
}

// —— 进标准库：素材库唯一的入库动作，点击引导进入新增向导（5.1 → 5.2 衔接） ——

function goWizard(item: SourceItemView) {
  // 新增向导（/admin/wizard）已就绪：带 sourceId 进入，已就绪的步骤（如概述与分类）自动跳过
  void router.push({ path: '/admin/wizard', query: { sourceId: String(item.id) } })
}

// —— 放弃 / 删除 ——

async function discard(item: SourceItemView) {
  try {
    await ElMessageBox.confirm(`确认放弃「${item.title}」？放弃后保留在已放弃清单。`, '放弃素材', {
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await discardSource(item.id)
    ElMessage.success('已放弃')
    await load()
  } catch (error) {
    ElMessage.error(messageOf(error, '操作失败'))
  }
}

async function remove(item: SourceItemView) {
  try {
    await ElMessageBox.confirm(
      `确认删除「${item.title}」？未入库素材可删除并连带清理其图片；已入库素材保留不删（B 端溯源资产）。`,
      '删除素材',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteSource(item.id)
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    ElMessage.error(messageOf(error, '删除失败'))
  }
}
</script>

<template>
  <div class="sources" v-loading="loading">
    <!-- 页头：标题与收料动作同区，页面级动作不上卡片 -->
    <header class="zj-page-head doc-head">
      <div class="doc-head-text">
        <h1>素材库</h1>
        <p>收料 → 轻量整理 → 进标准库：手写、导入文件、网页抓取三路收料；整理好的素材经「进标准库」进入新增向导。</p>
      </div>
      <div class="doc-head-actions">
        <el-dropdown @command="onImportCommand">
          <el-button :icon="Upload">
            导入
            <el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="file">上传文件（txt / md / zip）</el-dropdown-item>
              <el-dropdown-item command="url">网页抓取</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button type="primary" :icon="Plus" @click="openCreate">新增素材</el-button>
      </div>
    </header>

    <el-card shadow="never">
      <!-- 卡头：状态量具（读数即筛选）+ 搜索刷新 -->
      <template #header>
        <div class="meter-row">
          <StatusMeterBar v-model="statusFilter" :segments="segments" class="meter" />
          <div class="meter-tools">
            <el-input
              v-model="keyword"
              size="small"
              placeholder="搜索标题 / 概述"
              clearable
              :prefix-icon="Search"
              class="keyword"
            />
            <el-button size="small" :icon="Refresh" circle aria-label="刷新列表" @click="load" />
          </div>
        </div>
      </template>

      <input
        ref="importInput"
        type="file"
        accept=".txt,.md,.markdown,.zip"
        style="display: none"
        @change="onImportFile"
      />

      <el-table :data="filtered" size="small">
        <el-table-column label="标题" min-width="260">
          <template #default="{ row }">
            <div class="title-cell">
              <el-link :underline="false" class="title-link" @click="openEditor(row.id)">{{ row.title }}</el-link>
              <span v-if="row.childCount > 0" class="child-count zj-num">{{ row.childCount }} 子素材</span>
            </div>
            <div v-if="row.summary" class="row-summary">{{ row.summary }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="104">
          <template #default="{ row }">
            <span class="status-chip" :class="`is-${row.status}`">
              <i class="dot" aria-hidden="true"></i>{{ STATUS_LABELS[row.status as SourceStatus] }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="96">
          <template #default="{ row }">
            <span class="type-chip">{{ TYPE_LABELS[row.sourceType as SourceType] }}</span>
          </template>
        </el-table-column>
        <el-table-column label="领域 / 主题" width="160">
          <template #default="{ row }">
            <span v-if="row.domainName">{{ row.domainName }}<template v-if="row.topicName"> / {{ row.topicName }}</template></span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="150">
          <template #default="{ row }"><span class="zj-num">{{ row.updatedAt }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'pending'">
              <el-button size="small" text type="primary" :icon="Edit" @click="openEditor(row.id)">编辑</el-button>
              <el-button size="small" text type="primary" :icon="Position" @click="goWizard(row)">进标准库</el-button>
              <el-dropdown trigger="click" @command="(cmd: string) => (cmd === 'discard' ? discard(row) : remove(row))">
                <el-button size="small" text class="more-btn">···</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="discard">放弃</el-dropdown-item>
                    <el-dropdown-item command="delete">删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
            <template v-else-if="row.status === 'discarded'">
              <el-button size="small" text type="primary" @click="openEditor(row.id)">查看</el-button>
              <el-button size="small" text type="danger" :icon="Delete" @click="remove(row)">删除</el-button>
            </template>
            <template v-else>
              <span class="dim">已转入标准库维护</span>
            </template>
          </template>
        </el-table-column>

        <!-- 空状态是行动的邀请：真空引导收料，搜索空给退路 -->
        <template #empty>
          <div class="empty">
            <p class="empty-title">{{ keyword.trim() ? `没有匹配「${keyword.trim()}」的素材` : '素材架还是空的' }}</p>
            <p class="empty-note">
              {{ keyword.trim() ? '换个关键词试试，或清空搜索看全部。' : '手写、导入文件、网页抓取，三种方式都能把料收进来。' }}
            </p>
            <el-button v-if="!keyword.trim()" type="primary" size="small" :icon="Plus" @click="openCreate">写第一篇</el-button>
            <el-button v-else size="small" @click="keyword = ''">清空搜索</el-button>
          </div>
        </template>
      </el-table>
    </el-card>

    <!-- 新增（手写） -->
    <el-dialog v-model="createVisible" title="新增素材" width="640">
      <el-form label-position="top">
        <el-form-item label="标题" required>
          <el-input v-model="createForm.title" maxlength="120" placeholder="如：G1 回收流程整理" />
        </el-form-item>
        <el-form-item label="正文（Markdown）">
          <el-input v-model="createForm.contentMd" type="textarea" :rows="10" placeholder="先记个大概也行，回头再整理" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- URL 抓取 -->
    <el-dialog v-model="fetchVisible" title="URL 抓取" width="520">
      <el-form label-position="top">
        <el-form-item label="网页地址">
          <el-input v-model="fetchUrl" placeholder="https://github.com/... 或公众号文章链接" />
        </el-form-item>
      </el-form>
      <p class="hint">抓取正文转 Markdown；失败回退空稿由人工补录，URL 自动记入出处（服务版权追溯）。</p>
      <template #footer>
        <el-button @click="fetchVisible = false">取消</el-button>
        <el-button type="primary" :loading="fetching" @click="submitFetch">抓取</el-button>
      </template>
    </el-dialog>

    <!-- 轻量整理：编辑 + AI 改写；概述 / 难度 / 分类 / 拆分 / 入库走新增向导（5.2） -->
    <el-dialog v-model="editorVisible" width="760" top="6vh" destroy-on-close>
      <template #header>
        <div class="editor-head">
          <span class="editor-head-title">编辑素材</span>
          <span v-if="current" class="editor-head-meta">
            <span class="status-chip" :class="`is-${current.status}`">
              <i class="dot" aria-hidden="true"></i>{{ STATUS_LABELS[current.status] }}
            </span>
            <span class="type-chip">{{ TYPE_LABELS[current.sourceType] }}</span>
            <span class="editor-head-id zj-num">#{{ current.id }}</span>
          </span>
        </div>
      </template>
      <div v-loading="editorLoading" class="editor">
        <el-form label-position="top">
          <el-form-item label="标题" required>
            <el-input v-model="form.title" maxlength="120" />
          </el-form-item>
          <el-form-item>
            <template #label>
              <div class="label-row">
                <el-radio-group v-model="editorTab" size="small">
                  <el-radio-button value="edit">编辑</el-radio-button>
                  <el-radio-button value="preview">预览</el-radio-button>
                </el-radio-group>
                <div class="label-row-right">
                  <span class="word-count zj-num">{{ wordCount }} 字</span>
                  <el-button
                    size="small"
                    type="primary"
                    plain
                    :icon="MagicStick"
                    :loading="rewriting"
                    @click="runRewrite"
                  >
                    AI 改写
                  </el-button>
                </div>
              </div>
            </template>
            <el-input
              v-if="editorTab === 'edit'"
              v-model="form.contentMd"
              type="textarea"
              :rows="16"
              class="content-input"
            />
            <div v-else class="preview" v-html="renderMarkdown(form.contentMd)"></div>
          </el-form-item>
        </el-form>
        <p class="hint">概述、难度与分类在「进标准库」时由新增向导引导补齐；此处只做轻量整理。</p>
      </div>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sources {
  display: flex;
  flex-direction: column;
  gap: var(--zj-space-5);
}

/* —— 页头：标题说明居左，收料动作居右（zj-page-head 的纵向布局在此放宽为两栏） —— */
.doc-head {
  margin-bottom: 0; /* 间距交给 .sources 的 gap，避免页头自带 margin 叠加出双倍空隙 */
  flex-direction: row;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--zj-space-4);
  flex-wrap: wrap;
}

.doc-head-text {
  display: flex;
  flex-direction: column;
  gap: var(--zj-space-1);
  min-width: 0;
}

.doc-head-text p {
  max-width: 560px;
}

.doc-head-actions {
  display: flex;
  align-items: center;
  gap: var(--zj-space-2);
  flex: none;
}

/* —— 卡头：量具条 + 工具 —— */
.meter-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--zj-space-4);
  flex-wrap: wrap;
}

.meter {
  flex: 1 1 340px;
  max-width: 640px;
}

.meter-tools {
  display: flex;
  align-items: center;
  gap: var(--zj-space-2);
}

.keyword {
  width: 220px;
}

/* —— 表格单元 —— */
.title-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.title-link {
  font-weight: 500;
}

.title-link :deep(.el-link__inner) {
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.child-count {
  flex: none;
  font-size: 11px;
  color: var(--zj-ink-400);
}

.row-summary {
  margin-top: 2px;
  font-size: 12px;
  color: var(--zj-ink-500);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 420px;
}

/* 状态签：小圆点承色、文字保底可读，替代默认 el-tag 的彩色块 */
.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--zj-ink-600);
}

.status-chip .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--zj-ink-400);
}

.status-chip.is-pending .dot {
  background: var(--zj-primary);
}

.status-chip.is-submitted .dot {
  background: var(--el-color-success);
}

/* 来源签：线框轻签，与状态签的视觉重量分层 */
.type-chip {
  display: inline-flex;
  align-items: center;
  padding: 0 7px;
  font-size: 11px;
  line-height: 18px;
  color: var(--zj-ink-600);
  border: 1px solid var(--zj-line);
  border-radius: 5px;
}

.dim {
  color: var(--zj-ink-400);
  font-size: 12px;
}

.more-btn {
  padding: 5px 8px;
}

/* —— 空状态 —— */
.empty {
  padding: 48px 0 56px;
}

.empty-title {
  margin: 0;
  font-size: var(--zj-fs-base);
  font-weight: 600;
  color: var(--zj-ink-700);
}

.empty-note {
  margin: 6px 0 0;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-500);
}

.empty .el-button {
  margin-top: 16px;
}

.hint {
  margin: 0;
  font-size: 13px;
  color: var(--zj-ink-500);
  line-height: 1.6;
}

/* —— 编辑对话框 —— */
.editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.editor-head {
  display: flex;
  align-items: center;
  gap: var(--zj-space-3);
  flex-wrap: wrap;
}

.editor-head-title {
  font-size: var(--zj-fs-md);
  font-weight: 650;
  color: var(--zj-ink-900);
}

.editor-head-meta {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.editor-head-id {
  font-size: 12px;
  color: var(--zj-ink-400);
}

.label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--zj-space-3);
  width: 100%;
}

.label-row-right {
  display: flex;
  align-items: center;
  gap: var(--zj-space-3);
}

.word-count {
  font-size: 12px;
  color: var(--zj-ink-500);
}

.content-input :deep(textarea) {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
}

.preview {
  min-height: 320px;
  max-height: 480px;
  overflow-y: auto;
  padding: 12px 16px;
  border: 1px solid var(--zj-line-soft);
  border-radius: 8px;
  background: var(--zj-surface-sunken);
  font-size: 13px;
  line-height: 1.7;
}

.preview :deep(img) {
  max-width: 100%;
}

.preview :deep(pre) {
  padding: 10px 12px;
  overflow-x: auto;
  border-radius: 6px;
  background: var(--zj-ink-900, #1d2625);
  color: #e6edec;
  font-size: 12px;
}

/* —— 响应式 —— */
@media (max-width: 768px) {
  .keyword {
    width: 160px;
  }

  .row-summary {
    max-width: 240px;
  }
}
</style>
