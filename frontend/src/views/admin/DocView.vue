<script setup lang="ts">
// 素材库（核心模块定义 5.1 辅通道）：素材仓库，职责只有两件事——
// ① 承接素材：手写新增 / 导入（txt、md 及打包图片资源的压缩包）/ URL 抓取（失败回退空稿）；
// ② 轻量整理：编辑正文 + AI 改写（可提前完成的部分，产物永远回到人手里，6.4 AI 边界）。
// 素材阶段只归属「领域」；主题在「进标准库」时由新增向导引导、大模型推断，此处不展示不维护。
// 入库动作只有一个「进标准库」按钮：点击弹出新增向导弹窗接续走完剩余流程。
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Delete, Edit, MagicStick, Plus, Position, Refresh, Search, Upload, VideoPause } from '@element-plus/icons-vue'
import WizardDialog from '@/components/kb/WizardDialog.vue'
import ImageAttachments from '@/components/kb/ImageAttachments.vue'
import {
  createSource,
  deleteSource,
  discardSource,
  fetchSource,
  getSource,
  importSource,
  listSources,
  updateSource
} from '@/api/source'
import { streamAiRewrite } from '@/api/stream'
import type { SourceItemView, SourceStatus, SourceType } from '@/api/types'
import { useConstantsStore } from '@/stores/constants'
import { createMarkdown } from '@/utils/markdown'

// asset: 引用渲染为 B 端资产接口地址（导入 zip 图片「落盘即真相」的路径约定，与向导/标准库共用同一规则）
const md = createMarkdown()

const constants = useConstantsStore()

const STATUS_LABELS: Record<SourceStatus, string> = {
  pending: '待加工',
  submitted: '已进标准库',
  discarded: '已放弃'
}
// 状态 → el-tag 语义色：待加工用 warning 强调待办，已入库收敛为 info，已放弃 danger
const TAG_TYPES: Record<SourceStatus, 'warning' | 'info' | 'danger'> = {
  pending: 'warning',
  submitted: 'info',
  discarded: 'danger'
}
const TYPE_LABELS: Record<SourceType, string> = {
  manual: '手写',
  import: '导入',
  url: 'URL 抓取',
  inferred: '反推出题'
}

// 目标类型标记（kp 面试题线 / doc 文章线）：初始导入无法判定时留空，人工填写或采纳 AI 建议
const CONTENT_TYPE_LABELS: Record<string, string> = { kp: '知识点', doc: '资料讲解' }

const loading = ref(false)
// 服务端分页 + 服务端筛选：素材量级增长后不再全量拉取；状态分段计数与领域候选来自全库 facets
const items = ref<SourceItemView[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const statusCounts = ref<Record<string, number>>({})
const domainList = ref<string[]>([])
const filterForm = reactive({ status: '', domain: '' })
const keyword = ref('')

// 领域归属：入过库的素材带 domainCode；未入库但主题名以 OTHER· 开头的归入 OTHER
// （大模型推断的兜底领域，常量组不下发，展示名兜底为 code 本身）
function domainCodesOf(item: SourceItemView): string[] {
  const codes: string[] = []
  if (item.domainCode) codes.push(item.domainCode)
  if (item.topicName?.startsWith('OTHER·')) codes.push('OTHER')
  return codes
}

function domainName(code: string): string {
  return constants.name('domain', code)
}

// 状态分段计数与全部计数都来自 facets（全库口径，不随当前筛选变化）
const totalCount = computed(() => Object.values(statusCounts.value).reduce((sum, count) => sum + count, 0))

const domainFilterOptions = computed(() =>
  domainList.value.map((code) => ({ code, name: domainName(code) }))
)

const statusOptions = computed(() =>
  (Object.keys(STATUS_LABELS) as SourceStatus[]).map((status) => ({
    value: status,
    label: STATUS_LABELS[status],
    count: statusCounts.value[status] ?? 0
  }))
)

function messageOf(error: unknown, fallback: string): string {
  const err = error as { message?: string }
  return err.message ?? fallback
}

async function load() {
  loading.value = true
  try {
    const result = await listSources({
      page: page.value,
      pageSize: pageSize.value,
      status: filterForm.status || undefined,
      domainCode: filterForm.domain || undefined,
      keyword: keyword.value.trim() || undefined
    })
    items.value = result.items
    total.value = result.total
    statusCounts.value = result.statusCounts
    domainList.value = result.domains
    // 删除/放弃等操作后当前页可能已超出范围：回退一页重取
    if (!result.items.length && page.value > 1) {
      page.value -= 1
      await load()
    }
  } catch (error) {
    ElMessage.error(messageOf(error, '加载素材失败'))
  } finally {
    loading.value = false
  }
}

function onFilterChange() {
  page.value = 1
  void load()
}

function onPageSizeChange() {
  page.value = 1
  void load()
}

onMounted(() => void load())

// —— 承接素材：手写 / 导入 / URL 抓取 ——

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ title: '', contentMd: '', contentType: '' })

function openCreate() {
  createForm.title = ''
  createForm.contentMd = ''
  createForm.contentType = ''
  createVisible.value = true
}

async function submitCreate() {
  if (!createForm.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  creating.value = true
  try {
    const created = await createSource({
      title: createForm.title,
      contentMd: createForm.contentMd,
      contentType: createForm.contentType || undefined
    })
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
    if (!created.length) {
      ElMessage.warning('文件里没有可导入的内容')
      return
    }
    // zip 内多文档各成一条素材：逐条提示，打开第一条继续整理
    ElMessage.success(
      created.length === 1
        ? `导入成功：${created[0].title}`
        : `导入成功 ${created.length} 条素材（压缩包内每个文档各成一条）`
    )
    await load()
    void openEditor(created[0].id)
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
const form = reactive({ title: '', contentMd: '', contentType: '' })
const editorTab = ref<'edit' | 'preview'>('edit')
const rewriting = ref(false)
const preRewrite = ref('')
let rewriteAbort: (() => void) | null = null

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
    form.contentType = item.contentType ?? ''
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
    await updateSource(current.value.id, {
      title: form.title,
      contentMd: form.contentMd,
      contentType: form.contentType
    })
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
  preRewrite.value = form.contentMd
  form.contentMd = ''
  // 流式改写：逐片段追加打字机式呈现；可点「停止」中断（后端同步断开模型流）
  const { promise, abort } = streamAiRewrite(`/admin/sources/${current.value.id}/ai/rewrite/stream`, null, (delta) => {
    form.contentMd += delta
  })
  rewriteAbort = abort
  try {
    const resp = await promise
    form.contentMd = resp.content
    ElMessage.success('改写完成，产物已回编辑器，请人工确认')
  } catch (error) {
    const err = error as { status?: number; message?: string; name?: string }
    if (err?.status === undefined && err?.name === 'AbortError') {
      ElMessage.info('已停止改写，可撤销恢复原文')
    } else {
      form.contentMd = preRewrite.value
      ElMessage.error(err?.message || 'AI 改写失败')
    }
  } finally {
    rewriting.value = false
    rewriteAbort = null
  }
}

function stopRewrite() {
  rewriteAbort?.()
}

// —— 进标准库：素材库唯一的入库动作，点击弹出新增向导（5.1 → 5.2 衔接） ——

const wizardVisible = ref(false)
const wizardSourceId = ref<number | null>(null)

function goWizard(item: SourceItemView) {
  // 新增向导弹窗：带 sourceId 进入素材接续模式，已就绪的步骤（如概述与分类）自动跳过
  wizardSourceId.value = item.id
  wizardVisible.value = true
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
  <div class="sources">
    <!-- 页头：标题与收料动作同区，页面级动作不上卡片 -->
    <header class="zj-page-head doc-head">
      <div class="doc-head-text">
        <h1>素材库</h1>
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

    <el-card shadow="never" class="list-card">
      <!-- 卡头：状态分段筛选 + 领域筛选 + 搜索刷新，全部走 element-plus 标准控件 -->
      <template #header>
        <div class="table-tools">
          <el-radio-group v-model="filterForm.status" @change="onFilterChange">
            <el-radio-button value="">
              全部<span class="seg-count zj-num">{{ totalCount }}</span>
            </el-radio-button>
            <el-radio-button v-for="opt in statusOptions" :key="opt.value" :value="opt.value">
              {{ opt.label }}<span class="seg-count zj-num">{{ opt.count }}</span>
            </el-radio-button>
          </el-radio-group>
          <el-select
            v-model="filterForm.domain"
            class="domain-filter"
            placeholder="全部领域"
            clearable
            @change="onFilterChange"
          >
            <el-option v-for="opt in domainFilterOptions" :key="opt.code" :label="opt.name" :value="opt.code" />
          </el-select>
          <div class="tools-right">
            <el-input
              v-model="keyword"
              placeholder="搜索标题 / 概述"
              clearable
              :prefix-icon="Search"
              class="keyword"
              @keyup.enter="onFilterChange"
              @clear="onFilterChange"
            />
            <el-button :icon="Refresh" circle aria-label="刷新列表" @click="load" />
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

      <!-- loading 遮罩只盖表体：翻页/筛选时页头、筛选区与分页不闪动（数据是局部替换，视觉上也是局部刷新） -->
      <el-table v-loading="loading" :data="items" height="100%">
        <el-table-column label="标题" min-width="240">
          <template #default="{ row }">
            <div class="title-cell">
              <el-link :underline="false" class="title-link" @click="openEditor(row.id)">{{ row.title }}</el-link>
              <span v-if="row.childCount > 0" class="child-count zj-num">{{ row.childCount }} 子素材</span>
            </div>
            <div v-if="row.summary" class="row-summary">{{ row.summary }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="TAG_TYPES[row.status as SourceStatus]" disable-transitions>
              {{ STATUS_LABELS[row.status as SourceStatus] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" min-width="88">
          <template #default="{ row }">
            <span class="type-chip">{{ TYPE_LABELS[row.sourceType as SourceType] }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" min-width="90">
          <template #default="{ row }">
            <span v-if="row.contentType" class="type-chip">{{ CONTENT_TYPE_LABELS[row.contentType] }}</span>
            <span v-else class="dim">未定</span>
          </template>
        </el-table-column>
        <el-table-column label="领域" min-width="110">
          <template #default="{ row }">
            <span v-if="domainCodesOf(row).length" class="type-chip">
              {{ domainName(row.domainCode ?? 'OTHER') }}
            </span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="140">
          <template #default="{ row }"><span class="zj-num">{{ row.updatedAt }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
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
              <!-- 已入库素材可再引导：聚合内容拆分场景——在编辑中删减出本次要入库的部分，再走一次向导 -->
              <el-button size="small" text type="primary" :icon="Edit" @click="openEditor(row.id)">编辑</el-button>
              <el-button size="small" text type="primary" :icon="Position" @click="goWizard(row)">再次引导</el-button>
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
            <el-button v-else size="small" @click="keyword = ''; onFilterChange()">清空搜索</el-button>
          </div>
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        class="pager"
        background
        layout="total, sizes, prev, pager, next, jumper"
        :total="total"
        :page-sizes="[20, 50, 100]"
        :hide-on-single-page="false"
        @current-change="load"
        @size-change="onPageSizeChange"
      />
    </el-card>

    <!-- 新增（手写）：正文起步即要写，宽度与行数比小表单弹窗放宽一档 -->
    <el-dialog v-model="createVisible" title="新增素材" width="720">
      <el-form label-position="top">
        <el-form-item label="标题" required>
          <el-input v-model="createForm.title" maxlength="120" placeholder="如：G1 回收流程整理" />
        </el-form-item>
        <el-form-item label="类型（可选）">
          <el-radio-group v-model="createForm.contentType">
            <el-radio-button value="">先不定</el-radio-button>
            <el-radio-button value="kp">知识点（面试题线）</el-radio-button>
            <el-radio-button value="doc">资料讲解（文章线）</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="正文（Markdown）">
          <el-input v-model="createForm.contentMd" type="textarea" :rows="12" placeholder="先记个大概也行，回头再整理" />
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
      <p class="hint">抓取正文转 Markdown；失败时回退为空稿，可人工补录，原链接自动记入出处。</p>
      <template #footer>
        <el-button @click="fetchVisible = false">取消</el-button>
        <el-button type="primary" :loading="fetching" @click="submitFetch">抓取</el-button>
      </template>
    </el-dialog>

    <!-- 轻量整理：编辑 + AI 改写；左右分栏（左正文右图片），宽度对齐新增向导 960。
      * 高度固定：顶部到视口 24px、底部到屏幕下边缘 24px（见 .editor-dialog），正文区吃满剩余高度
      * （概述 / 难度 / 分类 / 入库走新增向导 5.2；多主题长文可在此复制删减成多条素材分别入库） -->
    <el-dialog
      v-model="editorVisible"
      class="editor-dialog"
      width="min(960px, 94vw)"
      destroy-on-close
    >
      <template #header>
        <div class="editor-head">
          <span class="editor-head-title">编辑素材</span>
          <span v-if="current" class="editor-head-meta">
            <el-tag size="small" :type="TAG_TYPES[current.status]" disable-transitions>
              {{ STATUS_LABELS[current.status] }}
            </el-tag>
            <span class="type-chip">{{ TYPE_LABELS[current.sourceType] }}</span>
            <span class="editor-head-id zj-num">#{{ current.id }}</span>
          </span>
        </div>
      </template>
      <div v-loading="editorLoading" class="editor">
        <el-form label-position="top" class="editor-form">
          <el-form-item label="标题" required>
            <el-input v-model="form.title" maxlength="120" />
          </el-form-item>
          <!-- 左右分栏（对齐新增向导第 1 步）：标签行只占左列，图片护栏顶边与正文区上缘对齐 -->
          <div class="editor-grid">
            <div class="label-row editor-label-row">
              <el-radio-group v-model="editorTab" size="small">
                <el-radio-button value="edit">编辑</el-radio-button>
                <el-radio-button value="preview">预览</el-radio-button>
              </el-radio-group>
              <div class="label-row-right">
                <!-- 类型即 AI 改写的成稿模式：初始导入无法判定可留空，AI 改写后可采纳建议 -->
                <el-tooltip
                  placement="top"
                  content="类型决定 AI 改写的成稿模式：知识点按参考答案式组织，资料讲解按讲解文章式组织。初始导入无法判定可留空，AI 改写后可采纳建议。"
                >
                  <el-select
                    v-model="form.contentType"
                    size="small"
                    class="type-select"
                    placeholder="类型：未定"
                    clearable
                    value-on-clear=""
                  >
                    <el-option label="知识点（面试题线）" value="kp" />
                    <el-option label="资料讲解（文章线）" value="doc" />
                  </el-select>
                </el-tooltip>
                <span class="word-count zj-num">{{ wordCount }} 字</span>
                <el-button
                  v-if="!rewriting && preRewrite"
                  size="small"
                  @click="form.contentMd = preRewrite; preRewrite = ''"
                >撤销改写</el-button>
                <el-button
                  size="small"
                  :type="rewriting ? 'danger' : 'primary'"
                  :class="{ 'btn-ai': !rewriting }"
                  plain
                  :icon="rewriting ? VideoPause : MagicStick"
                  @click="rewriting ? stopRewrite() : runRewrite()"
                >
                  {{ rewriting ? '停止' : 'AI 改写' }}
                </el-button>
              </div>
            </div>
            <el-form-item class="editor-content-item">
              <el-input
                v-if="editorTab === 'edit'"
                v-model="form.contentMd"
                type="textarea"
                :rows="8"
                class="content-input"
              />
              <div v-else class="preview" v-html="renderMarkdown(form.contentMd)"></div>
            </el-form-item>
            <!-- 右侧图片护栏：正文图片一览与处理（本地化 / 上传替换 / 删除），与正文双向联动 -->
            <ImageAttachments v-model="form.contentMd" compact class="editor-rail" />
          </div>
        </el-form>
        <p class="hint">概述、难度与分类在「进标准库」时由新增向导引导补齐；此处只做轻量整理。</p>
      </div>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新增向导弹窗：素材接续模式入口；提交入库后刷新列表（素材行状态变已进标准库） -->
    <WizardDialog v-model="wizardVisible" :source-id="wizardSourceId" @submitted="load" />
  </div>
</template>

<style scoped>
/* 整页锁高：页头/筛选/分页常驻视口，纵向滚动只发生在表体内部（配合 el-table 的 height="100%"） */
.sources {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--zj-space-5);
}

/* —— 页头：标题说明居左，收料动作居右（zj-page-head 的纵向布局在此放宽为两栏） —— */
.doc-head {
  margin-bottom: 0; /* 间距交给 .sources 的 gap，避免页头自带 margin 叠加出双倍空隙 */
  flex: none;
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

.doc-head-actions {
  display: flex;
  align-items: center;
  gap: var(--zj-space-2);
  flex: none;
}

/* —— 列表卡：卡头筛选与底部分页固定，表格区吃满剩余高度、表体内部滚动 —— */
.list-card {
  flex: 1 1 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.list-card :deep(.el-card__header) {
  flex: none;
}

.list-card :deep(.el-card__body) {
  flex: 1 1 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.list-card .el-table {
  flex: 1 1 0;
  min-height: 0;
}

/* —— 卡头工具行：状态分段筛选 / 领域下拉靠左，搜索刷新靠右 —— */
.table-tools {
  display: flex;
  align-items: center;
  gap: var(--zj-space-3);
  flex-wrap: wrap;
}

.tools-right {
  display: flex;
  align-items: center;
  gap: var(--zj-space-2);
  margin-left: auto;
}

.keyword {
  width: 220px;
}

.domain-filter {
  width: 140px;
}

/* 分段按钮上的读数：小一号、弱一档；选中态继承白色保证对比度 */
.table-tools :deep(.el-radio-button__inner) {
  display: inline-flex;
  align-items: center;
}

.seg-count {
  margin-left: 6px;
  font-size: 11px;
  color: var(--zj-ink-500);
}

.table-tools :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) .seg-count {
  color: currentColor;
  opacity: 0.8;
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

/* 来源签：线框轻签，与状态 el-tag 的彩色底形成视觉分层 */
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

/* 分页器：靠右、与表格留一拍呼吸感 */
.pager {
  flex: none;
  margin-top: 14px;
  justify-content: flex-end;
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
/* 外壳：固定高度（上距 24px、下距屏幕底 24px），内部 flex 链让正文区吃满剩余高度 */
.editor {
  flex: 1 1 0;
  min-height: 0;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.editor-form {
  flex: 1 1 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* 正文项吃掉标签行以下的全部剩余高度；底部提示行保底不挤压 */
.editor-content-item {
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  margin-bottom: 0;
}

.editor-content-item :deep(.el-form-item__content) {
  flex: 1 1 0;
  min-height: 0;
  /* Element 默认 flex-wrap: wrap 在 column 方向会按 min-content 折列，长 URL 时把预览撑出弹窗 */
  display: flex;
  flex-direction: column;
  flex-wrap: nowrap;
  align-items: stretch;
}

/* 左右分栏（对齐新增向导第 1 步）：标签行只占左列第一行，图片护栏从第二行起、
 * 顶边与正文区上缘对齐（不与标签行同排） */
.editor-grid {
  flex: 1 1 0;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 260px;
  grid-template-rows: auto minmax(0, 1fr);
  grid-template-areas:
    'label .'
    'content rail';
  gap: 10px 14px;
}

.editor-label-row {
  grid-area: label;
}

.editor-content-item {
  grid-area: content;
}

/* 右侧图片护栏：附件面板吃满高度，图少时按钮紧跟图下，图多时网格借满高度再内部滚动 */
.editor-rail {
  grid-area: rail;
  min-height: 0;
  min-width: 0;
}

.editor-grid :deep(.attach-panel--open) {
  flex: 1 1 auto;
  min-height: 0;
}

.editor-grid :deep(.attach-grid) {
  flex: 0 1 auto;
  max-height: none;
}

/* 类型下拉：嵌在编辑区标签行内，与 AI 改写同排；宽度收窄避免挤压右侧动作 */
.type-select {
  width: 168px;
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

/* 正文区：编辑与预览两态都吃满表单剩余高度，切换 tab 弹窗外框纹丝不动 */
.content-input,
.preview {
  flex: 1 1 0;
  min-height: 0;
  /* 关键：预览是 flex 子项且无固有宽度，长 URL/宽表格/代码块会把 min-content 撑出弹窗；
   * 收缩宽度归零 + max-width 兜底，让溢出走内部滚动而不是顶破外框 */
  min-width: 0;
  max-width: 100%;
}

.content-input :deep(.el-textarea) {
  height: 100%;
}

.content-input :deep(textarea) {
  height: 100%;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
}

.preview {
  /* 与编辑区同高：内容超高走内部滚动，不撑高弹窗 */
  overflow: auto;
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

/* 长外链/连续串不断行撑宽：按容器宽度截断换行，宽内容交给 overflow 滚动 */
.preview :deep(p),
.preview :deep(li),
.preview :deep(td),
.preview :deep(th) {
  overflow-wrap: anywhere;
  word-break: break-word;
}

.preview :deep(table) {
  display: block;
  max-width: 100%;
  overflow-x: auto;
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

  .domain-filter {
    width: 120px;
  }

  .row-summary {
    max-width: 240px;
  }

  /* 窄屏弹窗：分栏收不进去，改纵向堆叠——标签行、正文、图片护栏顺排 */
  .editor-grid {
    display: flex;
    flex-direction: column;
    overflow-y: auto;
  }

  .editor-label-row,
  .editor-content-item,
  .editor-rail {
    grid-area: auto;
  }

  .editor-content-item {
    flex: none;
  }

  .editor-grid .content-input,
  .editor-grid .preview {
    height: 46vh;
    flex: none;
  }

  .editor-rail {
    flex: none;
  }

  /* 类型下拉在窄屏标签行会与按钮挤压，允许换行 */
  .editor-label-row {
    flex-wrap: wrap;
  }
}

/* 宽屏：外层内容区放开到 1760 后，标题/摘要列同步放宽截断，
 * 让增量宽度变成更多信息而非更多空白（≤1600 视口维持原行宽节奏） */
@media (min-width: 1600px) {
  .title-link :deep(.el-link__inner) {
    max-width: 440px;
  }

  .row-summary {
    max-width: 640px;
  }
}
</style>

<!-- 编辑弹窗外壳是 Element 渲染、且 teleport 到 body，不受本组件 scoped 约束，故用独立全局块（类名带 editor-dialog 限定，不污染他处）。
     高度 = 视口高 - 上下各 24px 固定开销：顶部到视口 24px、底部到屏幕下边缘 24px，body 吃满剩余高度。 -->
<style>
.el-dialog.editor-dialog {
  margin: 24px auto;
  height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
}

.editor-dialog .el-dialog__body {
  flex: 1 1 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
</style>
