<script setup lang="ts">
// 新增向导（核心模块定义 5.1 主通道）：一次走完「编写 → 拆分 → 概述难度分类 → 入标准库」，
// 中间过程不单独保存成稿，提交即进标准库审核队列（不校不上线）。
// 两种模式：query.sourceId = 素材接续（素材库「进标准库」跳入，已就绪步骤自动跳过）；
// 无参 = 直达新建（成熟内容第 1 步直接写成品，跳过拆分，概述与难度门槛一视同仁）。
// AI 五动作只是按钮：改写回编辑器、拆分是可调预填、概述/难度/分类均为建议，用不用由人（6.4）。
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MarkdownIt from 'markdown-it'
import { ElMessage } from 'element-plus'
import { MagicStick, Plus, Delete, Right, VideoPause } from '@element-plus/icons-vue'
import {
  aiClassify,
  aiDifficulty,
  aiSplit,
  aiSummary,
  getSource,
  getSourceChildren,
  getSplitPreview,
  splitSource,
  submitSource,
  updateSource
} from '@/api/source'
import { streamAiRewrite } from '@/api/stream'
import {
  aiClassifyContent,
  aiDifficultyContent,
  aiSummaryContent,
  directSubmit,
  listTopics
} from '@/api/kb'
import type { KbContentType, SourceDifficulty, SourceItemView } from '@/api/types'
import { useConstantsStore } from '@/stores/constants'
import { createMarkdown } from '@/utils/markdown'

const route = useRoute()
const router = useRouter()
const constants = useConstantsStore()

const mdRender: MarkdownIt = createMarkdown()

// asset: 引用渲染为 B 端资产接口地址（素材图片引用在向导内同样可预览）
function renderMd(content: string): string {
  return mdRender.render(content ?? '')
}

interface WizardItem {
  sourceId: number | null
  title: string
  contentMd: string
  summary: string
  difficulty: SourceDifficulty | ''
  domainCode: string
  topicId: number | null
  /** 各类建议的理由/提示，展示用 */
  note: string
}

const STEPS = ['编写', '拆分', '概述与分类', '入标准库']

const mode = ref<'source' | 'direct'>('direct')
const parentSourceId = ref<number | null>(null)
const step = ref(0)
const loading = ref(false)
// 首帧即渲染第 1 步表单（v-show 恒渲染，activeItem 被 activeItem!.title 解引用）：
// items 必须初始就有一条空白草稿，否则 activeItem 为 null，首帧渲染直接抛 TypeError 白屏
const items = ref<WizardItem[]>([newItem({})])
// 定类：入口（知识库管理「新建知识点 / 新建资料讲解」）会以 ?type=kp|doc 预置；无预置默认面试题线，
// 第 4 步提交前可人工改选（定类是人工决策，AI 不参与建议）
function presetType(queryType: unknown): queryType is KbContentType {
  return queryType === 'kp' || queryType === 'doc'
}
const typePreset = presetType(route.query.type) ? (route.query.type as KbContentType) : null
const contentType = ref<KbContentType>(typePreset ?? 'kp')
const submitted = ref(false)

// —— AI 按钮的忙碌态（产物回到人手里，按钮各自独立） ——
const busy = reactive({ rewrite: false, split: false, summary: {} as Record<number, boolean>, difficulty: {} as Record<number, boolean>, classify: {} as Record<number, boolean> })

// —— 拆分工作区（第 2 步） ——
interface SliceDraft {
  title: string
  content: string
}
const slices = ref<SliceDraft[]>([])
const splitDone = ref(false)

const activeItem = computed(() => items.value[0] ?? null)
const topicOptions = ref<{ id: number; name: string }[]>([])

const domainOptions = computed(() => constants.items('domain'))

/** 页头副标题：直达新建带预置定类时明确提示（入口语义：面试题线 / 文章线） */
const subtitle = computed(() => {
  if (mode.value === 'source') {
    return '素材接续入库：编写 → 拆分 → 概述与分类 → 入标准库，一次走完'
  }
  const typeLabel = typePreset === 'doc' ? '资料讲解' : typePreset === 'kp' ? '知识点' : null
  return typeLabel
    ? `直达新建（${typeLabel}）：成熟内容直接写成品，跳过拆分，提交进审核队列`
    : '直达新建：成熟内容直接写成品，跳过拆分，提交进审核队列'
})

function messageOf(error: unknown, fallback: string): string {
  const err = error as { message?: string }
  return err.message ?? fallback
}

function newItem(partial: Partial<WizardItem>): WizardItem {
  return {
    sourceId: null,
    title: '',
    contentMd: '',
    summary: '',
    difficulty: '',
    domainCode: '',
    topicId: null,
    note: '',
    ...partial
  }
}

onMounted(async () => {
  await constants.ensure()
  const sourceId = Number(route.query.sourceId ?? 0)
  if (sourceId > 0) {
    mode.value = 'source'
    parentSourceId.value = sourceId
    loading.value = true
    try {
      const source: SourceItemView = await getSource(sourceId)
      items.value = [
        newItem({
          sourceId: source.id,
          title: source.title,
          contentMd: source.contentMd,
          summary: source.summary ?? '',
          difficulty: (source.difficulty ?? '') as SourceDifficulty | '',
          domainCode: source.domainCode ?? ''
        })
      ]
      // 已就绪的步骤直接跳过：概述与分类已完成则直达确认步（5.1 素材库跳转接续口径）
      step.value = items.value[0].summary && items.value[0].difficulty && items.value[0].domainCode ? 2 : 0
      await reloadTopics()
    } catch (error) {
      ElMessage.error(messageOf(error, '加载素材失败'))
    } finally {
      loading.value = false
    }
  } else {
    mode.value = 'direct'
    items.value = [newItem({})]
    step.value = 0
  }
})

// —— 第 1 步：编写（编辑 + AI 改写，产物回编辑器） ——

const preRewrite = ref('')
const rewriteAbortRef = { current: null as (() => void) | null }

async function doRewrite() {
  const item = activeItem.value
  if (!item) return
  if (!item.contentMd.trim()) {
    ElMessage.warning('正文为空，无法执行 AI 改写')
    return
  }
  busy.rewrite = true
  preRewrite.value = item.contentMd
  item.contentMd = ''
  // 流式改写：逐片段追加打字机式呈现；素材接续与直达共用同一能力（6.4）
  const streamUrl = mode.value === 'source' && item.sourceId
    ? `/admin/sources/${item.sourceId}/ai/rewrite/stream`
    : '/admin/kb/ai/rewrite-stream'
  const { promise, abort } = streamAiRewrite(streamUrl, streamUrl.includes('rewrite-stream') ? { contentMd: preRewrite.value } : null, (delta) => {
    item.contentMd += delta
  })
  rewriteAbortRef.current = abort
  try {
    const resp = await promise
    item.contentMd = resp.content
    ElMessage.success('改写产物已回到编辑器，可继续调整或撤销')
  } catch (error) {
    const err = error as { status?: number; message?: string; name?: string }
    if (err?.status === undefined && err?.name === 'AbortError') {
      ElMessage.info('已停止改写，可撤销恢复原文')
    } else {
      item.contentMd = preRewrite.value
      ElMessage.error(err?.message || 'AI 改写失败')
    }
  } finally {
    busy.rewrite = false
    rewriteAbortRef.current = null
  }
}

function stopRewrite() {
  rewriteAbortRef.current?.()
}

function undoRewrite() {
  const item = activeItem.value
  if (!item || !preRewrite.value) return
  item.contentMd = preRewrite.value
  preRewrite.value = ''
}

function nextFromStep1() {
  const item = activeItem.value
  if (!item?.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  if (!item.contentMd.trim()) {
    ElMessage.warning('正文不能为空')
    return
  }
  step.value = mode.value === 'source' && !splitDone.value ? 1 : 2
}

// —— 第 2 步：拆分（可选，1 拆多；AI 一键拆分只做预填，逐条可调） ——

/** 与后端 segmentsOf 同口径：一级/二级标题作切分锚点。 */
function segmentByAnchor(content: string): string[] {
  const lines = content.split('\n')
  const anchors: { index: number; line: string }[] = []
  lines.forEach((line, index) => {
    if (/^#{1,2}\s+/.test(line)) anchors.push({ index, line: line.replace(/^#{1,2}\s+/, '').trim() })
  })
  if (anchors.length === 0) return [content.trim()]
  const segments = anchors.map((anchor, i) => {
    const start = anchor.index
    const end = i + 1 < anchors.length ? anchors[i + 1].index : lines.length
    return lines.slice(start, end).join('\n').trim()
  })
  return segments
}

async function doAiSplit() {
  const item = activeItem.value
  if (!item?.sourceId) return
  busy.split = true
  try {
    const [suggestion, segments] = await Promise.all([aiSplit(item.sourceId), getSplitPreview(item.sourceId)])
    if (!suggestion.needSplit || suggestion.slices.length < 2) {
      ElMessage.info('AI 判断该素材主题单一，无需拆分')
      return
    }
    // anchor 匹配正文段：命中标题锚点的段作为预填内容
    slices.value = suggestion.slices.map((slice) => {
      const index = segments.findIndex((segment) => segment.replace(/^#{1,2}\s+/, '').startsWith(slice.anchor))
      return { title: slice.title, content: index >= 0 ? segments[index] : '' }
    })
    ElMessage.success('AI 拆分预填完成，请逐条核对调整')
  } catch (error) {
    ElMessage.error(messageOf(error, 'AI 拆分失败'))
  } finally {
    busy.split = false
  }
}

function prefillSlices() {
  const item = activeItem.value
  if (!item) return
  slices.value = segmentByAnchor(item.contentMd).map((segment) => {
    const heading = /^#{1,2}\s+(.+)$/.exec(segment.split('\n')[0] ?? '')
    return { title: heading?.[1]?.trim() ?? '未命名片段', content: segment }
  })
}

async function confirmSplit() {
  const item = activeItem.value
  if (!item?.sourceId) return
  if (slices.value.length < 2) {
    ElMessage.warning('拆分至少要产出两条子素材')
    return
  }
  if (slices.value.some((slice) => !slice.title.trim())) {
    ElMessage.warning('每条子素材标题不能为空')
    return
  }
  loading.value = true
  try {
    await splitSource(item.sourceId, slices.value.map((slice) => ({ title: slice.title, contentMd: slice.content })))
    const children = await getSourceChildren(item.sourceId)
    items.value = children.map((child) =>
      newItem({ sourceId: child.id, title: child.title, contentMd: child.contentMd })
    )
    splitDone.value = true
    step.value = 2
    ElMessage.success(`已拆分为 ${items.value.length} 条子素材，继续补概述与分类`)
  } catch (error) {
    ElMessage.error(messageOf(error, '拆分失败'))
  } finally {
    loading.value = false
  }
}

function skipSplit() {
  step.value = 2
}

// —— 第 3 步：概述、难度与分类（每条素材独立；AI 建议仅供采纳或无视） ——

async function reloadTopics() {
  const domainCode = items.value[0]?.domainCode ?? ''
  topicOptions.value = domainCode ? (await listTopics(domainCode)).map((topic) => ({ id: topic.id, name: topic.name })) : []
}

function onDomainChange() {
  // 领域是人工选的，不是 AI 猜的：领域变化后主题候选随领域刷新，原主题位置失效
  items.value.forEach((item) => {
    item.topicId = null
    item.note = ''
  })
  void reloadTopics()
}

async function doSummary(item: WizardItem) {
  busy.summary[itemKey(item)] = true
  try {
    const summary = item.sourceId ? await aiSummary(item.sourceId) : await aiSummaryContent(item.contentMd)
    item.summary = summary
  } catch (error) {
    ElMessage.error(messageOf(error, '概述生成失败'))
  } finally {
    busy.summary[itemKey(item)] = false
  }
}

async function doDifficulty(item: WizardItem) {
  busy.difficulty[itemKey(item)] = true
  try {
    const resp = item.sourceId ? await aiDifficulty(item.sourceId) : await aiDifficultyContent(item.contentMd)
    item.difficulty = resp.difficulty as SourceDifficulty
    item.note = `难度建议理由：${resp.reason}`
  } catch (error) {
    ElMessage.error(messageOf(error, '难度建议失败'))
  } finally {
    busy.difficulty[itemKey(item)] = false
  }
}

async function doClassify(item: WizardItem) {
  if (!item.domainCode) {
    ElMessage.warning('先点选领域，AI 只在领域内建议主题位置')
    return
  }
  busy.classify[itemKey(item)] = true
  try {
    const topics = topicOptions.value.map((topic) => topic.name)
    const resp = item.sourceId
      ? await aiClassify(item.sourceId, item.domainCode, topics)
      : await aiClassifyContent(item.contentMd, item.domainCode, topics)
    if (!resp.topicName) {
      ElMessage.info(resp.reason || '未给出建议')
      return
    }
    const matched = topicOptions.value.find((topic) => topic.name === resp.topicName)
    if (matched) item.topicId = matched.id
    item.note = `分类建议：${resp.topicName}（${resp.reason}）`
  } catch (error) {
    ElMessage.error(messageOf(error, '分类建议失败'))
  } finally {
    busy.classify[itemKey(item)] = false
  }
}

function itemKey(item: WizardItem, indexFallback = 0): number {
  const index = items.value.indexOf(item)
  return item.sourceId ?? (index >= 0 ? index : indexFallback)
}

async function persistAndNext() {
  // 概述与难度齐备才能进标准库（5.1 边界）；素材模式先把第 3 步成果写回素材行
  for (const item of items.value) {
    if (!item.summary.trim()) {
      ElMessage.warning(`「${item.title}」还差概述（入库门槛）`)
      return
    }
    if (!item.difficulty) {
      ElMessage.warning(`「${item.title}」还差难度（入库门槛）`)
      return
    }
    if (!item.domainCode) {
      ElMessage.warning(`「${item.title}」还差点选领域`)
      return
    }
    if (!item.topicId) {
      ElMessage.warning(`「${item.title}」还差确认主题位置`)
      return
    }
  }
  if (mode.value === 'source') {
    loading.value = true
    try {
      for (const item of items.value) {
        if (item.sourceId) {
          await updateSource(item.sourceId, {
            summary: item.summary,
            difficulty: item.difficulty,
            domainCode: item.domainCode
          })
        }
      }
    } catch (error) {
      ElMessage.error(messageOf(error, '保存概述与分类失败'))
      loading.value = false
      return
    } finally {
      loading.value = false
    }
  }
  // 定类缺省：入口预置（?type=）优先；无预置默认面试题线，第 4 步可人工改选
  if (!typePreset) {
    contentType.value = 'kp'
  }
  step.value = 3
}

// —— 第 4 步：入标准库（提交即进审核队列） ——

const submitting = ref(false)

async function doSubmit() {
  const first = items.value[0]
  if (!first?.domainCode || !first.topicId) {
    ElMessage.warning('领域与主题位置必须确认')
    return
  }
  submitting.value = true
  try {
    if (mode.value === 'source' && parentSourceId.value) {
      await submitSource(parentSourceId.value, {
        domainCode: first.domainCode,
        topicId: first.topicId,
        contentType: contentType.value
      })
    } else {
      const item = first
      await directSubmit({
        contentType: contentType.value,
        domainCode: item.domainCode,
        topicId: item.topicId as number,
        title: item.title,
        summary: item.summary,
        contentMd: item.contentMd,
        difficulty: item.difficulty as SourceDifficulty
      })
    }
    submitted.value = true
    ElMessage.success('已提交进标准库审核队列（不校不上线）')
  } catch (error) {
    ElMessage.error(messageOf(error, '提交失败'))
  } finally {
    submitting.value = false
  }
}

const DIFF_LABELS: Record<string, string> = { junior: '初级', mid: '中级', senior: '高级', expert: '资深' }
const domainName = (code: string) => constants.name('domain', code)
const topicName = (topicId: number | null) =>
  topicId == null ? '' : (topicOptions.value.find((topic) => topic.id === topicId)?.name ?? '')
</script>

<template>
  <div class="wizard-view">
    <header class="view-head">
      <div>
        <h1>新增向导</h1>
        <p class="view-sub">
          {{ subtitle }}
        </p>
      </div>
    </header>

    <el-steps :active="step" align-center finish-status="success" class="wizard-steps">
      <el-step v-for="(label, index) in STEPS" :key="index" :title="label" />
    </el-steps>

    <div v-if="loading" class="wizard-loading" v-loading="true"></div>

    <template v-else-if="!submitted">
      <!-- 第 1 步：编写 -->
      <section v-show="step === 0" class="wizard-step">
        <el-input v-model="activeItem!.title" placeholder="标题" maxlength="120" class="title-input" />
        <div class="editor-grid">
          <el-input v-model="activeItem!.contentMd" type="textarea" :rows="16" placeholder="Markdown 正文（或粘贴/导入文本后由 AI 改写清洗）" />
          <div class="preview-pane">
            <p class="pane-label">预览</p>
            <div class="md-body" v-html="renderMd(activeItem!.contentMd)"></div>
          </div>
        </div>
        <div class="step-actions">
          <div class="action-left">
            <el-button
              :icon="busy.rewrite ? VideoPause : MagicStick"
              :type="busy.rewrite ? 'danger' : 'default'"
              @click="busy.rewrite ? stopRewrite() : doRewrite()"
            >{{ busy.rewrite ? '停止' : 'AI 改写' }}</el-button>
            <el-button v-if="preRewrite && !busy.rewrite" @click="undoRewrite">撤销改写</el-button>
          </div>
          <el-button type="primary" @click="nextFromStep1">下一步<el-icon class="el-icon--right"><Right /></el-icon></el-button>
        </div>
      </section>

      <!-- 第 2 步：拆分（可选，仅素材模式） -->
      <section v-show="step === 1" class="wizard-step">
        <p class="step-hint">多主题长文先拆再入库：AI 一键拆分按标题锚点预填，逐条可调（增、删、改）；单主题素材直接跳过。</p>
        <div class="step-actions">
          <div class="action-left">
            <el-button :icon="MagicStick" :loading="busy.split" @click="doAiSplit">AI 一键拆分</el-button>
            <el-button @click="prefillSlices">按标题预填</el-button>
          </div>
          <el-button @click="skipSplit">不拆分，继续</el-button>
        </div>
        <div class="slice-list">
          <div v-for="(slice, index) in slices" :key="index" class="slice-card">
            <div class="slice-head">
              <span class="slice-index">{{ index + 1 }}</span>
              <el-input v-model="slice.title" placeholder="子素材标题" />
              <el-button :icon="Delete" text type="danger" @click="slices.splice(index, 1)" />
            </div>
            <el-input v-model="slice.content" type="textarea" :rows="5" placeholder="子素材正文（可按锚点预填后调整）" />
          </div>
          <el-button size="small" :icon="Plus" @click="slices.push({ title: '', content: '' })">加一条</el-button>
        </div>
        <div class="step-actions">
          <el-button type="primary" :loading="loading" :disabled="slices.length < 2" @click="confirmSplit">确认拆分并继续</el-button>
        </div>
      </section>

      <!-- 第 3 步：概述、难度与分类 -->
      <section v-show="step === 2" class="wizard-step">
        <p class="step-hint">概述与难度齐备才能进标准库；领域人工点选，AI 只在领域内建议主题位置，采纳或无视由人。</p>
        <div class="item-list">
          <el-card v-for="(item, index) in items" :key="item.sourceId ?? index" shadow="never" class="item-card">
            <template #header>
              <span class="item-title">{{ index + 1 }}. {{ item.title || '（未命名）' }}</span>
            </template>
            <el-form label-width="72px" label-position="left">
              <el-form-item label="概述">
                <div class="inline-field">
                  <el-input v-model="item.summary" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="百字内摘要" />
                  <el-button :icon="MagicStick" :loading="busy.summary[itemKey(item, index)]" @click="doSummary(item)">AI 生成</el-button>
                </div>
              </el-form-item>
              <el-form-item label="难度">
                <div class="inline-field">
                  <el-radio-group v-model="item.difficulty">
                    <el-radio-button value="junior">初级</el-radio-button>
                    <el-radio-button value="mid">中级</el-radio-button>
                    <el-radio-button value="senior">高级</el-radio-button>
                    <el-radio-button value="expert">资深</el-radio-button>
                  </el-radio-group>
                  <el-button :icon="MagicStick" :loading="busy.difficulty[itemKey(item, index)]" @click="doDifficulty(item)">AI 建议</el-button>
                </div>
              </el-form-item>
              <el-form-item label="领域">
                <el-select v-model="item.domainCode" placeholder="人工点选领域" style="width: 260px" @change="onDomainChange">
                  <el-option v-for="option in domainOptions" :key="option.code" :label="option.name" :value="option.code" />
                </el-select>
              </el-form-item>
              <el-form-item label="主题">
                <div class="inline-field">
                  <el-select v-model="item.topicId" placeholder="确认主题位置" style="width: 260px" :disabled="!item.domainCode">
                    <el-option v-for="topic in topicOptions" :key="topic.id" :label="topic.name" :value="topic.id" />
                  </el-select>
                  <el-button :icon="MagicStick" :loading="busy.classify[itemKey(item, index)]" :disabled="!item.domainCode" @click="doClassify(item)">AI 分类建议</el-button>
                </div>
              </el-form-item>
            </el-form>
            <p v-if="item.note" class="suggestion-note">{{ item.note }}</p>
          </el-card>
        </div>
        <div class="step-actions">
          <el-button type="primary" :loading="loading" @click="persistAndNext">下一步<el-icon class="el-icon--right"><Right /></el-icon></el-button>
        </div>
      </section>

      <!-- 第 4 步：入标准库 -->
      <section v-show="step === 3" class="wizard-step">
        <p class="step-hint">确认定类与位置后提交：进门先过审核门，审核通过才对用户可见（不校不上线）。</p>
        <el-form label-width="72px" label-position="left" class="confirm-form">
          <el-form-item label="定类">
            <el-radio-group v-model="contentType">
              <el-radio-button value="kp">面试题线（知识点）</el-radio-button>
              <el-radio-button value="doc">文章线（资料讲解）</el-radio-button>
            </el-radio-group>
          </el-form-item>
        </el-form>
        <el-table :data="items" size="small" border>
          <el-table-column label="标题" prop="title" min-width="200" />
          <el-table-column label="概述" min-width="220">
            <template #default="{ row }">{{ row.summary }}</template>
          </el-table-column>
          <el-table-column label="难度" width="80">
            <template #default="{ row }">{{ DIFF_LABELS[row.difficulty] ?? row.difficulty }}</template>
          </el-table-column>
          <el-table-column label="领域 / 主题" min-width="160">
            <template #default="{ row }">{{ domainName(row.domainCode) }} / {{ topicName(row.topicId) }}</template>
          </el-table-column>
        </el-table>
        <div class="step-actions">
          <el-button type="primary" :loading="submitting" @click="doSubmit">提交进审核队列</el-button>
        </div>
      </section>
    </template>

    <!-- 提交完成 -->
    <section v-else class="wizard-step done-step">
      <el-result icon="success" title="已提交进标准库审核队列" sub-title="审核通过才转为可用并对用户可见；可在内容生产台查看进度">
        <template #extra>
          <el-button type="primary" @click="router.push('/admin/production')">去审核队列</el-button>
          <el-button @click="router.push('/admin/docs')">返回素材库</el-button>
        </template>
      </el-result>
    </section>
  </div>
</template>

<style scoped>
.wizard-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
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

.wizard-steps {
  padding: 8px 0;
}

.wizard-loading {
  min-height: 240px;
}

.wizard-step {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.step-hint {
  margin: 0;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-600);
}

.title-input :deep(.el-input__inner) {
  font-weight: 600;
}

.editor-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.preview-pane {
  padding: 10px 12px;
  border: 1px solid var(--zj-line);
  border-radius: 8px;
  background: var(--zj-surface);
  overflow: auto;
  max-height: 480px;
}

.pane-label {
  margin: 0 0 6px;
  font-size: 11px;
  color: var(--zj-ink-500, #999);
}

.md-body {
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-700);
}

.step-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.action-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.slice-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: flex-start;
}

.slice-card {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px;
  border: 1px solid var(--zj-line);
  border-radius: 8px;
}

.slice-head {
  display: flex;
  gap: 8px;
  align-items: center;
}

.slice-index {
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  font-size: 12px;
  background: var(--zj-primary-soft);
  color: var(--zj-primary);
}

.item-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.item-title {
  font-weight: 600;
}

.inline-field {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  width: 100%;
}

.suggestion-note {
  margin: 0;
  font-size: 12px;
  color: var(--zj-primary);
}

.confirm-form {
  max-width: 480px;
}

.done-step {
  padding: 32px 0;
}

@media (max-width: 960px) {
  .editor-grid {
    grid-template-columns: 1fr;
  }
}
</style>
