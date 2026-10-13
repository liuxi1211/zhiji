<script setup lang="ts">
// 新增向导弹窗（核心模块定义 5.1 主通道）：一次走完「编写 → 概述与分类 → 入标准库」，
// 中间过程不单独保存成稿，提交即进标准库审核队列（不校不上线）。
// 以弹窗承载（入口触达，不占路由页面）：素材库「进标准库」带 sourceId 进入素材接续模式；
// 知识库管理「新建知识点 / 新建资料讲解」带 presetType 进入直达模式；已就绪的步骤自动跳过。
// 步骤可回退：返回上一步时数据保留在前端，再次「下一步」重新校验并把成果写回素材行（素材模式）；
// 多主题长文改为人工处理——在素材层复制删减成多条素材，分别走向导入库。
// AI 只是按钮（6.4）：改写（可带人工指令）回编辑器，标题/追问链/概述/难度/分类均为建议，用不用由人。
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import MarkdownIt from 'markdown-it'
import { ElMessage } from 'element-plus'
import { Check, Delete, MagicStick, Plus, Right, VideoPause } from '@element-plus/icons-vue'
import { aiClassify, aiDifficulty, aiSummary, getSource, submitSource, updateSource } from '@/api/source'
import { streamAiRewrite } from '@/api/stream'
import {
  aiClassifyContent,
  aiDifficultyContent,
  aiFollowupsContent,
  aiSummaryContent,
  aiTitleContent,
  directSubmit,
  listTopics
} from '@/api/kb'
import type { KbContentType, KbFollowupDraft, SourceDifficulty, SourceItemView } from '@/api/types'
import { useConstantsStore } from '@/stores/constants'
import { createMarkdown, newFollowupId } from '@/utils/markdown'
import ImageAttachments from './ImageAttachments.vue'

const props = defineProps<{
  modelValue: boolean
  /** 素材接续模式：素材库「进标准库」带入 */
  sourceId?: number | null
  /** 直达模式定类预置：知识库管理「新建知识点 / 新建资料讲解」带入 */
  presetType?: KbContentType | null
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'submitted'): void
}>()

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

const STEPS = ['编写', '概述与分类', '入标准库']

const mode = ref<'source' | 'direct'>('direct')
const activeSourceId = ref<number | null>(null)
const step = ref(0)
/** 步骤切换方向：决定过渡动画的滑动方向，让三步读起来是同一个界面前后翻页 */
const stepDirection = ref<'fwd' | 'back'>('fwd')

/** 统一的步骤切换入口：记录方向供过渡动画使用（init 时的跳步不经此，不播动画） */
function goStep(target: number) {
  stepDirection.value = target >= step.value ? 'fwd' : 'back'
  step.value = target
}

const loading = ref(false)
// 定类：入口预置优先（?type=kp|doc / 素材行类型标记），无预置默认面试题线，提交前可人工改选
const contentType = ref<KbContentType>('kp')
const submitted = ref(false)

// —— AI 按钮的忙碌态（产物回到人手里，按钮各自独立） ——
const busy = reactive({ rewrite: false, title: false, summary: false, difficulty: false, classify: false, followups: false })

const item = ref<WizardItem>(newItem({}))
const topicOptions = ref<{ id: number; name: string }[]>([])

const domainOptions = computed(() => constants.items('domain'))

/** 弹窗头副标题：只说明模式来源，步骤序列由下方步骤条承载，不在此重复 */
const subtitle = computed(() => {
  if (mode.value === 'source') {
    return '素材接续入库'
  }
  const typeLabel = props.presetType === 'doc' ? '资料讲解' : props.presetType === 'kp' ? '知识点' : null
  return typeLabel ? `直达新建（${typeLabel}）` : '直达新建'
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

/** 每次打开弹窗重新初始化（destroy-on-close 销毁 DOM，状态在此重置） */
async function init() {
  step.value = 0
  submitted.value = false
  editorTab.value = 'edit'
  rewriteInstruction.value = ''
  preRewrite.value = ''
  followupsDraft.value = []
  topicOptions.value = []
  const sourceId = Number(props.sourceId ?? 0)
  if (sourceId > 0) {
    mode.value = 'source'
    activeSourceId.value = sourceId
    loading.value = true
    try {
      await constants.ensure()
      const source: SourceItemView = await getSource(sourceId)
      item.value = newItem({
        sourceId: source.id,
        title: source.title,
        contentMd: source.contentMd,
        summary: source.summary ?? '',
        difficulty: (source.difficulty ?? '') as SourceDifficulty | '',
        domainCode: source.domainCode ?? ''
      })
      // 定类预置：素材行的类型标记优先，未标记默认面试题线
      contentType.value = (source.contentType ?? 'kp') as KbContentType
      // 已就绪的步骤直接跳过：概述与分类已完成则直达确认步
      step.value = item.value.summary && item.value.difficulty && item.value.domainCode ? 1 : 0
      await reloadTopics()
    } catch (error) {
      ElMessage.error(messageOf(error, '加载素材失败'))
      emit('update:modelValue', false)
    } finally {
      loading.value = false
    }
  } else {
    mode.value = 'direct'
    activeSourceId.value = null
    item.value = newItem({})
    contentType.value = props.presetType ?? 'kp'
    step.value = 0
    await constants.ensure()
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) void init()
  }
)

function close() {
  emit('update:modelValue', false)
}

// —— 第 1 步：编写（标题 + 正文 + AI 改写，产物回编辑器） ——

/** 编辑/预览单区域切换（与素材库编辑弹窗同交互），不左右分栏 */
const editorTab = ref<'edit' | 'preview'>('edit')
const wordCount = computed(() => item.value.contentMd.length.toLocaleString('zh-CN'))

const rewriteInstruction = ref('')
const preRewrite = ref('')
const rewriteAbortRef = { current: null as (() => void) | null }

/** AI 起标题：内容明确（正文非空）后才可用；生成后落回标题框，仍可继续改。 */
async function doTitle() {
  if (!item.value.contentMd.trim()) {
    ElMessage.warning('先写正文，内容明确后 AI 才能起标题')
    return
  }
  busy.title = true
  try {
    const title = await aiTitleContent(item.value.contentMd, contentType.value)
    if (title) {
      item.value.title = title
      ElMessage.success('标题已生成，可继续修改')
    } else {
      ElMessage.info('AI 没有给出标题，请手动填写')
    }
  } catch (error) {
    ElMessage.error(messageOf(error, '标题生成失败'))
  } finally {
    busy.title = false
  }
}

async function doRewrite() {
  if (!item.value.contentMd.trim()) {
    ElMessage.warning('正文为空，无法执行 AI 改写')
    return
  }
  busy.rewrite = true
  preRewrite.value = item.value.contentMd
  item.value.contentMd = ''
  // 流式改写：逐片段追加打字机式呈现；统一走内容式端点（改写编辑器当前正文，
  // 与素材行解耦——人工删减后的正文即时生效），mode 带当前定类，instruction 带人工改写要求。
  const { promise, abort } = streamAiRewrite(
    '/admin/kb/ai/rewrite-stream',
    { contentMd: preRewrite.value, mode: contentType.value, instruction: rewriteInstruction.value || null },
    (delta) => {
      item.value.contentMd += delta
    }
  )
  rewriteAbortRef.current = abort
  try {
    const resp = await promise
    item.value.contentMd = resp.content
    ElMessage.success('改写产物已回到编辑器，可继续调整或撤销')
  } catch (error) {
    const err = error as { status?: number; message?: string; name?: string }
    if (err?.status === undefined && err?.name === 'AbortError') {
      ElMessage.info('已停止改写，可撤销恢复原文')
    } else {
      item.value.contentMd = preRewrite.value
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
  if (!preRewrite.value) return
  item.value.contentMd = preRewrite.value
  preRewrite.value = ''
}

async function nextFromStep1() {
  if (!item.value.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  if (!item.value.contentMd.trim()) {
    ElMessage.warning('正文不能为空')
    return
  }
  loading.value = true
  try {
    // 跨步即把编写成果写回素材行（人工删减即时持久化）
    if (mode.value === 'source' && item.value.sourceId) {
      await updateSource(item.value.sourceId, { title: item.value.title, contentMd: item.value.contentMd })
    }
    goStep(1)
  } catch (error) {
    ElMessage.error(messageOf(error, '保存编写成果失败'))
  } finally {
    loading.value = false
  }
}

// —— 第 2 步：概述、难度与分类（AI 建议仅供采纳或无视；领域人工点选） ——

async function reloadTopics() {
  const domainCode = item.value.domainCode ?? ''
  topicOptions.value = domainCode ? (await listTopics(domainCode)).map((topic) => ({ id: topic.id, name: topic.name })) : []
}

function onDomainChange() {
  // 领域是人工选的，不是 AI 猜的：领域变化后主题候选随领域刷新，原主题位置失效
  item.value.topicId = null
  item.value.note = ''
  void reloadTopics()
}

async function doSummary() {
  busy.summary = true
  try {
    const summary = item.value.sourceId ? await aiSummary(item.value.sourceId) : await aiSummaryContent(item.value.contentMd)
    item.value.summary = summary
  } catch (error) {
    ElMessage.error(messageOf(error, '概述生成失败'))
  } finally {
    busy.summary = false
  }
}

async function doDifficulty() {
  busy.difficulty = true
  try {
    const resp = item.value.sourceId
      ? await aiDifficulty(item.value.sourceId)
      : await aiDifficultyContent(item.value.contentMd)
    item.value.difficulty = resp.difficulty as SourceDifficulty
    item.value.note = `难度建议理由：${resp.reason}`
  } catch (error) {
    ElMessage.error(messageOf(error, '难度建议失败'))
  } finally {
    busy.difficulty = false
  }
}

async function doClassify() {
  if (!item.value.domainCode) {
    ElMessage.warning('先点选领域，AI 只在领域内建议主题位置')
    return
  }
  busy.classify = true
  try {
    const topics = topicOptions.value.map((topic) => topic.name)
    const resp = item.value.sourceId
      ? await aiClassify(item.value.sourceId, item.value.domainCode, topics)
      : await aiClassifyContent(item.value.contentMd, item.value.domainCode, topics)
    if (!resp.topicName) {
      ElMessage.info(resp.reason || '未给出建议')
      return
    }
    const matched = topicOptions.value.find((topic) => topic.name === resp.topicName)
    if (matched) item.value.topicId = matched.id
    item.value.note = `分类建议：${resp.topicName}（${resp.reason}）`
  } catch (error) {
    ElMessage.error(messageOf(error, '分类建议失败'))
  } finally {
    busy.classify = false
  }
}

async function nextFromStep2() {
  if (!item.value.summary.trim()) {
    ElMessage.warning('还差概述（入库门槛）')
    return
  }
  if (!item.value.difficulty) {
    ElMessage.warning('还差难度（入库门槛）')
    return
  }
  if (!item.value.domainCode) {
    ElMessage.warning('还差点选领域')
    return
  }
  if (!item.value.topicId) {
    ElMessage.warning('还差确认主题位置')
    return
  }
  loading.value = true
  try {
    // 素材模式先把概述与分类成果写回素材行
    if (mode.value === 'source' && item.value.sourceId) {
      await updateSource(item.value.sourceId, {
        summary: item.value.summary,
        difficulty: item.value.difficulty,
        domainCode: item.value.domainCode
      })
    }
    goStep(2)
  } catch (error) {
    ElMessage.error(messageOf(error, '保存概述与分类失败'))
  } finally {
    loading.value = false
  }
}

// —— 第 3 步：入标准库（定类 + 追问链 + 提交进审核队列） ——

/** 追问链草稿（仅面试题线）：AI 识别或手动整理，行内可改可删；提交时补 id，入库即受红线 2 约束 */
const followupsDraft = ref<KbFollowupDraft[]>([])

async function doFollowups() {
  if (!item.value.contentMd.trim()) {
    ElMessage.warning('正文为空，无法识别追问链')
    return
  }
  busy.followups = true
  try {
    const drafts = await aiFollowupsContent(item.value.contentMd)
    if (drafts.length === 0) {
      ElMessage.info('正文里没有识别到追问雏形（内容只回答了主问题）')
      return
    }
    followupsDraft.value = drafts
    ElMessage.success(`识别到 ${drafts.length} 层追问，请逐条核对调整`)
  } catch (error) {
    ElMessage.error(messageOf(error, '追问链识别失败'))
  } finally {
    busy.followups = false
  }
}

function addFollowup() {
  const nextLayer = followupsDraft.value.reduce((max, f) => Math.max(max, f.layer), 0) + 1
  followupsDraft.value.push({ layer: nextLayer, direction: '', content: '' })
}

const submitting = ref(false)

async function doSubmit() {
  if (!item.value.domainCode || !item.value.topicId) {
    ElMessage.warning('领域与主题位置必须确认')
    return
  }
  // 问答题线才带追问链；内容为空的层不提交（提交后层身份受红线 2 约束）
  const followups =
    contentType.value === 'kp'
      ? followupsDraft.value
          .filter((f) => f.content.trim())
          .map((f) => ({ id: newFollowupId(), layer: f.layer, direction: f.direction.trim(), content: f.content.trim(), status: 'active' as const }))
      : undefined
  submitting.value = true
  try {
    if (mode.value === 'source' && activeSourceId.value) {
      await submitSource(activeSourceId.value, {
        domainCode: item.value.domainCode,
        topicId: item.value.topicId,
        contentType: contentType.value,
        followups
      })
    } else {
      await directSubmit({
        contentType: contentType.value,
        domainCode: item.value.domainCode,
        topicId: item.value.topicId,
        title: item.value.title,
        summary: item.value.summary,
        contentMd: item.value.contentMd,
        difficulty: item.value.difficulty as SourceDifficulty,
        followups
      })
    }
    submitted.value = true
    emit('submitted')
    ElMessage.success('已提交，进入审核队列')
  } catch (error) {
    ElMessage.error(messageOf(error, '提交失败'))
  } finally {
    submitting.value = false
  }
}

function goProduction() {
  close()
  void router.push('/admin/production')
}

const DIFF_LABELS: Record<string, string> = { junior: '初级', mid: '中级', senior: '高级', expert: '资深' }
const domainName = (code: string) => constants.name('domain', code)
const topicName = (topicId: number | null) =>
  topicId == null ? '' : (topicOptions.value.find((topic) => topic.id === topicId)?.name ?? '')
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    class="wizard-dialog"
    width="min(960px, 94vw)"
    destroy-on-close
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <template #header>
      <div class="wizard-head">
        <span class="wizard-title">新增向导</span>
        <span class="wizard-sub">{{ subtitle }}</span>
      </div>
    </template>

    <div v-if="loading" class="wizard-loading" v-loading="true"></div>

    <!-- 固定高度舞台：三步共用同一块画布，切换只有内容平移，弹窗外框纹丝不动 -->
    <div v-else-if="!submitted" class="wizard-stage">
      <!-- 步骤条：编号与描述同行（① 编写 —— ② 概述与分类 —— ③ 入标准库），居中紧凑不拉伸 -->
      <nav class="wizard-steps" aria-label="向导步骤">
        <template v-for="(label, index) in STEPS" :key="label">
          <div
            class="wizard-step"
            :class="{ 'is-active': step === index, 'is-done': step > index }"
            :aria-current="step === index ? 'step' : undefined"
          >
            <span class="step-badge">
              <el-icon v-if="step > index"><Check /></el-icon>
              <template v-else>{{ index + 1 }}</template>
            </span>
            <span class="step-label">{{ label }}</span>
          </div>
          <span
            v-if="index < STEPS.length - 1"
            class="step-connector"
            :class="{ 'is-done': step > index }"
            aria-hidden="true"
          ></span>
        </template>
      </nav>

      <div class="step-viewport">
        <Transition :name="stepDirection === 'fwd' ? 'step-fwd' : 'step-back'" mode="out-in">
          <!-- 第 1 步：编写 -->
          <section v-if="step === 0" key="step-0" class="step-body step-body--write">
        <!-- 工具行横跨两列：左列「标题…[编辑|预览]」（按钮贴编辑区右缘），右列字数（落在图片区上方） -->
        <div class="editor-toolbar">
          <div class="toolbar-main">
            <!-- 左上：标题 + AI 命名 -->
            <div class="title-row">
              <span class="title-label">标题：</span>
              <el-input v-model="item.title" placeholder="面试题线写完整题面" maxlength="120" class="title-input" />
              <el-button class="btn-ai" size="small" :icon="MagicStick" :loading="busy.title" :disabled="!item.contentMd.trim()" @click="doTitle">
                AI 命名
              </el-button>
            </div>
            <!-- 左列右缘：编辑/预览切换（最右侧对齐编辑区最右侧） -->
            <el-radio-group v-model="editorTab" size="small" class="editor-toolbar-tabs">
              <el-radio-button value="edit">编辑</el-radio-button>
              <el-radio-button value="preview">预览</el-radio-button>
            </el-radio-group>
          </div>
          <!-- 右列：字数跟在切换按钮后，位置在右侧图片区上方 -->
          <span class="word-count zj-num">{{ wordCount }} 字</span>
        </div>
        <div class="editor-block">
          <el-input
            v-if="editorTab === 'edit'"
            v-model="item.contentMd"
            type="textarea"
            :rows="12"
            class="content-input"
            placeholder="Markdown 正文（可粘贴/导入文本后由 AI 改写清洗）"
          />
          <div v-else class="preview-pane md-body" v-html="renderMd(item.contentMd)"></div>
        </div>
        <!-- 右侧辅助栏：附件处理与 AI 改写收在护栏里，正文独占左栏整高，不再被辅助块挤压 -->
        <aside class="write-rail">
          <!-- 附件面板（紧凑）：正文图片一览与处理，进标准库前可在此本地化/替换/删除 -->
          <ImageAttachments v-model="item.contentMd" compact />
          <div class="ai-panel">
            <p class="ai-panel-title">AI 改写</p>
            <div class="ai-panel-actions">
              <el-input
                v-model="rewriteInstruction"
                type="textarea"
                :rows="3"
                class="ai-panel-input"
                :disabled="busy.rewrite"
                placeholder="改进要求（可留空，留空按通用规则整理）"
              />
              <el-button
                :icon="busy.rewrite ? VideoPause : MagicStick"
                :type="busy.rewrite ? 'danger' : 'primary'"
                :class="{ 'btn-ai': !busy.rewrite }"
                plain
                @click="busy.rewrite ? stopRewrite() : doRewrite()"
              >{{ busy.rewrite ? '停止' : '开始改写' }}</el-button>
              <el-button v-if="preRewrite && !busy.rewrite" @click="undoRewrite">撤销改写</el-button>
            </div>
          </div>
        </aside>
      </section>

      <!-- 第 2 步：概述、难度与分类（左右两栏：概述占左栏整高，难度与位置收右栏） -->
          <section v-else-if="step === 1" key="step-1" class="step-body step-body--meta">
        <p class="step-hint">概述与难度齐备才能进标准库；领域人工点选，AI 只在领域内建议主题位置，采纳或无视由人。</p>
        <div class="meta-grid">
          <div class="meta-card">
            <div class="meta-card-head">
              <span class="meta-card-title">概述</span>
              <el-button class="btn-ai" :icon="MagicStick" :loading="busy.summary" @click="doSummary">AI 生成</el-button>
            </div>
            <el-input
              v-model="item.summary"
              type="textarea"
              :rows="8"
              maxlength="200"
              show-word-limit
              class="summary-input"
              placeholder="百字内摘要——目录、同位管理与搜索都靠它"
            />
          </div>
          <div class="meta-card">
            <div class="meta-card-head">
              <span class="meta-card-title">难度</span>
              <el-button class="btn-ai" :icon="MagicStick" :loading="busy.difficulty" @click="doDifficulty">AI 建议</el-button>
            </div>
            <el-radio-group v-model="item.difficulty">
              <el-radio-button value="junior">初级</el-radio-button>
              <el-radio-button value="mid">中级</el-radio-button>
              <el-radio-button value="senior">高级</el-radio-button>
              <el-radio-button value="expert">资深</el-radio-button>
            </el-radio-group>
            <div class="meta-card-head head-gap">
              <span class="meta-card-title">领域</span>
            </div>
            <el-select v-model="item.domainCode" placeholder="人工点选领域" @change="onDomainChange">
              <el-option v-for="option in domainOptions" :key="option.code" :label="option.name" :value="option.code" />
            </el-select>
            <div class="meta-card-head head-gap">
              <span class="meta-card-title">主题</span>
              <el-button class="btn-ai" :icon="MagicStick" :loading="busy.classify" :disabled="!item.domainCode" @click="doClassify">AI 分类建议</el-button>
            </div>
            <el-select v-model="item.topicId" placeholder="确认主题位置" :disabled="!item.domainCode">
              <el-option v-for="topic in topicOptions" :key="topic.id" :label="topic.name" :value="topic.id" />
            </el-select>
            <p v-if="item.note" class="suggestion-note">{{ item.note }}</p>
          </div>
        </div>
      </section>

      <!-- 第 3 步：入标准库（定类卡 + 左确认/右追问链，两栏填满画布） -->
          <section v-else key="step-2" class="step-body step-body--meta">
        <p class="step-hint">确认定类与位置后提交；提交后进入审核，审核通过才对用户可见。</p>
        <div class="type-cards">
          <button type="button" class="type-card" :class="{ active: contentType === 'kp' }" @click="contentType = 'kp'">
            <span class="type-card-title">面试题线（知识点）</span>
            <span class="type-card-desc">题面 + 标准作答，可带追问链与评分要点</span>
          </button>
          <button type="button" class="type-card" :class="{ active: contentType === 'doc' }" @click="contentType = 'doc'">
            <span class="type-card-title">文章线（资料讲解）</span>
            <span class="type-card-desc">讲解文章，无追问链，挂载知识点后发布</span>
          </button>
        </div>
        <div class="meta-grid">
          <div class="meta-card">
            <div class="meta-card-head">
              <span class="meta-card-title">内容确认</span>
            </div>
            <div class="confirm-rows">
              <div class="confirm-row">
                <span class="confirm-label">标题</span>
                <span class="confirm-value strong">{{ item.title }}</span>
              </div>
              <div class="confirm-row">
                <span class="confirm-label">概述</span>
                <span class="confirm-value">{{ item.summary }}</span>
              </div>
              <div class="confirm-row">
                <span class="confirm-label">难度</span>
                <span class="confirm-value">{{ DIFF_LABELS[item.difficulty] ?? item.difficulty }}</span>
              </div>
              <div class="confirm-row">
                <span class="confirm-label">位置</span>
                <span class="confirm-value">{{ domainName(item.domainCode) }} / {{ topicName(item.topicId) }}</span>
              </div>
            </div>
          </div>
          <div class="meta-card">
            <template v-if="contentType === 'kp'">
              <div class="meta-card-head">
                <span class="meta-card-title">追问链（可选）</span>
                <div class="followup-head-actions">
                  <el-button class="btn-ai" size="small" :icon="MagicStick" :loading="busy.followups" @click="doFollowups">AI 识别追问链</el-button>
                  <el-button size="small" :icon="Plus" @click="addFollowup">加一层</el-button>
                </div>
              </div>
              <p v-if="followupsDraft.length === 0" class="followup-empty">
                暂无追问层：正文若自带多问/下钻内容，AI 可识别成追问层草稿；每行可改可删，也可手动加一层。提交后追问层只能停用，不能删除。
              </p>
              <div v-else class="followup-list followup-scroll">
                <div v-for="(f, index) in followupsDraft" :key="index" class="followup-line">
                  <el-input-number v-model="f.layer" :min="1" :max="20" controls-position="right" class="layer-input" />
                  <el-input v-model="f.direction" placeholder="方向（原理/对比/场景）" class="direction-input" />
                  <el-input v-model="f.content" placeholder="追问内容（面试官口吻的完整问句）" />
                  <el-button :icon="Delete" text type="danger" @click="followupsDraft.splice(index, 1)" />
                </div>
              </div>
            </template>
            <template v-else>
              <div class="meta-card-head">
                <span class="meta-card-title">提交说明</span>
              </div>
              <ul class="doc-notes">
                <li>文章线不携带追问链与评分要点，挂载知识点后即可发布学习；</li>
                <li>提交后进入审核队列，审核通过才对用户可见；</li>
                <li>素材接续模式下，标题、正文、概述与难度已逐步保存回素材，提交后素材标记「已进标准库」。</li>
              </ul>
            </template>
          </div>
        </div>
      </section>
        </Transition>
      </div>
    </div>

    <!-- 提交完成 -->
    <section v-else class="done-step">
      <el-result icon="success" title="已提交进标准库审核队列" sub-title="审核通过才转为可用并对用户可见；可在内容生产台查看进度">
        <template #extra>
          <el-button type="primary" @click="goProduction">去审核队列</el-button>
          <el-button @click="close">完成</el-button>
        </template>
      </el-result>
    </section>

    <template #footer>
      <template v-if="!loading && !submitted">
        <div v-show="step === 0" class="footer-row footer-row--end">
          <el-button type="primary" :loading="loading" @click="nextFromStep1">下一步<el-icon class="el-icon--right"><Right /></el-icon></el-button>
        </div>
        <div v-show="step === 1" class="footer-row">
          <el-button class="back-btn" :disabled="loading" @click="goStep(0)">上一步</el-button>
          <el-button type="primary" :loading="loading" @click="nextFromStep2">下一步<el-icon class="el-icon--right"><Right /></el-icon></el-button>
        </div>
        <div v-show="step === 2" class="footer-row">
          <el-button class="back-btn" @click="goStep(1)">上一步</el-button>
          <el-button type="primary" :loading="submitting" @click="doSubmit">提交进审核队列</el-button>
        </div>
      </template>
      <div v-else class="footer-row">
        <el-button @click="close">关闭</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.wizard-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}

.wizard-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--zj-ink-800);
}

.wizard-sub {
  font-size: 12px;
  color: var(--zj-ink-600);
}

.wizard-loading {
  /* 与舞台同高（由弹窗外壳 flex 分配），加载骨架不引起弹窗尺寸跳动 */
  flex: 1 1 0;
  min-height: 0;
}

/* 固定高度舞台：三步共用同一块画布，body 高度不再随步骤内容伸缩；
 * 高度由弹窗外壳（.wizard-dialog）算好，这里只负责吃满 */
.wizard-stage {
  flex: 1 1 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* 步骤条：编号与描述同行，居中紧凑；下方留白加大，与内容拉开呼吸 */
.wizard-steps {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 2px 0 24px;
}

.wizard-step {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  flex: none;
}

.step-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--zj-ink-500);
  background: var(--zj-surface-sunken);
  border: 1px solid var(--zj-line);
  transition: color var(--zj-dur-fast) var(--zj-ease-out),
    background var(--zj-dur-fast) var(--zj-ease-out),
    border-color var(--zj-dur-fast) var(--zj-ease-out);
}

.wizard-step .step-badge :deep(svg) {
  width: 13px;
  height: 13px;
}

.step-label {
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-500);
  transition: color var(--zj-dur-fast) var(--zj-ease-out);
}

/* 当前步：实底主色，视觉焦点 */
.wizard-step.is-active .step-badge {
  color: #fff;
  background: var(--zj-primary);
  border-color: var(--zj-primary);
}

.wizard-step.is-active .step-label {
  color: var(--zj-ink-900);
  font-weight: 600;
}

/* 已过步：线框主色 + 勾，弱于当前步但明确"已完成" */
.wizard-step.is-done .step-badge {
  color: var(--zj-primary);
  background: var(--zj-primary-soft);
  border-color: var(--zj-primary);
}

.wizard-step.is-done .step-label {
  color: var(--zj-ink-600);
}

/* 连接线：等宽短横，已走过的变主色 */
.step-connector {
  flex: none;
  width: 40px;
  height: 1px;
  background: var(--zj-line);
}

.step-connector.is-done {
  background: var(--zj-primary);
}

/* 步骤画布：占满舞台剩余高度，步骤切换的过渡在此发生 */
.step-viewport {
  flex: 1 1 0;
  min-height: 0;
}

/* 单步内容：占满画布，超高时步内滚动（外框不动） */
.step-body {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 14px;
  overflow-y: auto;
  padding-right: 2px;
}

/* 第 1 步不滚：编辑区吃满画布剩余高度，编辑器多高弹窗就多高。
 * 栅格：工具行通栏（左列标题/切换、右列字数），第二行＝编辑器（左）+ 辅助护栏（右）。
 * 右列首行只放字数，辅助护栏从第 2 行起，顶部与编辑器对齐 */
.step-body--write {
  overflow: hidden;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  grid-template-rows: auto minmax(0, 1fr);
  grid-template-areas:
    'toolbar toolbar'
    'editor rail';
  gap: 10px 14px;
}

.step-body--write .editor-toolbar {
  grid-area: toolbar;
}

.step-body--write .editor-block {
  grid-area: editor;
  min-height: 0;
  min-width: 0;
}

/* 编辑器块：纵向排（正文框吃满其高度） */
.step-body--write .editor-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.step-body--write .content-input {
  flex: 1 1 0;
  min-height: 0;
}

.step-body--write .content-input :deep(.el-textarea) {
  height: 100%;
}

.step-body--write .preview-pane {
  flex: 1 1 0;
  min-height: 0;
  max-height: none;
}

/* 右侧辅助栏：附件面板吃满剩余高度（图多时向下方借空间，而非卡在固定 max-height），
 * AI 改写固定贴底；附件网格自身内部滚动，不撑破画布 */
.write-rail {
  grid-area: rail;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow: hidden;
  padding-right: 2px;
}

/* 展开时允许收缩（不强制撑满）：图少时按内容高排布，本地化按钮紧跟其下；
 * 图多时向下借满护栏高度，仍不够才内部滚动 */
.write-rail .attach-panel--open {
  flex: 1 1 auto;
  min-height: 0;
}

/* 网格按内容自适应：flex-grow=0（不撑高，按钮紧跟图下），flex-shrink=1（空间不够才收缩滚动）；
 * 覆盖面板默认的固定 max-height，让高度真正受护栏可用空间约束 */
.write-rail :deep(.attach-grid) {
  flex: 0 1 auto;
  min-height: 0;
  max-height: none;
}

.write-rail .ai-panel {
  flex: none;
}

/* 步骤切换过渡：前进向左推出、后退向右推回，配合外框不动营造"同一界面翻页"感 */
.step-fwd-enter-active,
.step-fwd-leave-active,
.step-back-enter-active,
.step-back-leave-active {
  transition: opacity 0.16s ease, transform 0.16s ease;
}

.step-fwd-enter-from {
  opacity: 0;
  transform: translateX(24px);
}

.step-fwd-leave-to {
  opacity: 0;
  transform: translateX(-24px);
}

.step-back-enter-from {
  opacity: 0;
  transform: translateX(-24px);
}

.step-back-leave-to {
  opacity: 0;
  transform: translateX(24px);
}

.step-hint {
  margin: 0;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-600);
}

/* 工具行：与正文栅格同列宽——左列放标题与切换，右列放字数（右列即图片区上方） */
.editor-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  align-items: center;
  gap: 14px;
}

/* 左列：标题 + AI 命名在左、编辑/预览贴右缘（＝编辑区右缘） */
.toolbar-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

/* 标题区：标签 + 输入自适应 + AI 命名按钮 */
.title-row {
  display: flex;
  gap: 8px;
  align-items: center;
  flex: 1 1 auto;
  min-width: 0;
}

.title-label {
  flex: none;
  font-size: var(--zj-fs-sm);
  font-weight: 600;
  color: var(--zj-ink-700);
}

.title-input {
  flex: 0 1 320px;
  min-width: 160px;
}

.title-input :deep(.el-input__inner) {
  font-weight: 600;
}

.editor-toolbar-tabs {
  flex: none;
}

/* 右列：字数，落在右侧图片区上方 */
.editor-toolbar .word-count {
  justify-self: start;
}

/* 编辑区：工具行 + 单区域正文，切换不换布局 */
.editor-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.word-count {
  font-size: 12px;
  color: var(--zj-ink-500);
}

.content-input :deep(textarea) {
  /* 完全跟随 editor-block 的分配高度（flex 剩余空间）：AI 面板恒在画布内，正文框让位不溢出 */
  height: 100%;
  min-height: 0;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
}

.preview-pane {
  overflow-y: auto;
  padding: 12px 16px;
  border: 1px solid var(--zj-line-soft);
  border-radius: 8px;
  background: var(--zj-surface-sunken);
  font-size: 13px;
  line-height: 1.7;
}

.md-body {
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-700);
}

.md-body :deep(img) {
  max-width: 100%;
}

/* AI 改写面板：标题在上，输入与动作在下，与编辑区明确分区 */
.ai-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  border: 1px dashed var(--zj-line);
  border-radius: 8px;
  background: var(--zj-primary-soft);
}

.ai-panel-title {
  margin: 0;
  font-size: var(--zj-fs-sm);
  font-weight: 600;
  color: var(--zj-ink-800);
}

.ai-panel-actions {
  display: flex;
  gap: 8px;
  /* 多行输入框与按钮底对齐：动线是"写完要求 → 点开始改写" */
  align-items: flex-end;
  flex-wrap: wrap;
}

.ai-panel-input {
  flex: 1 1 260px;
  min-width: 200px;
}

.ai-panel-input :deep(textarea) {
  resize: none;
}

/* 辅助栏内 AI 面板：栏窄，输入与按钮改纵向堆叠、按钮铺满宽，避免横向挤压 */
.write-rail .ai-panel-actions {
  flex-direction: column;
  align-items: stretch;
}

.write-rail .ai-panel-input {
  flex: none;
  min-width: 0;
}

/* 第 2 步：左右两栏填满画布——概述占左栏整高，难度/领域/主题收在右栏 */
.step-body--meta {
  overflow: hidden;
}

.meta-grid {
  flex: 1 1 0;
  min-height: 0;
  display: grid;
  grid-template-columns: 1.15fr 1fr;
  gap: 14px;
}

.meta-card {
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--zj-line);
  border-radius: 8px;
  background: var(--zj-surface-sunken);
}

.meta-card-head {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.meta-card-head.head-gap {
  margin-top: 6px;
}

.meta-card-title {
  font-size: var(--zj-fs-sm);
  font-weight: 600;
  color: var(--zj-ink-800);
}

.meta-card :deep(.el-select) {
  width: 100%;
}

.summary-input {
  flex: 1 1 0;
  min-height: 0;
}

.summary-input :deep(textarea) {
  height: 100%;
  resize: none;
}

.meta-card .suggestion-note {
  margin: auto 0 0;
}

.field-with-action {
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

/* 定类卡：两张大卡点选，替代挤在一起的 radio */
.type-cards {
  flex: none;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.type-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid var(--zj-line);
  border-radius: 8px;
  background: var(--zj-surface);
  cursor: pointer;
  font: inherit;
  text-align: left;
  transition: border-color 0.12s ease, background 0.12s ease, box-shadow 0.12s ease;
}

.type-card:hover {
  border-color: var(--zj-primary);
}

.type-card.active {
  border-color: var(--zj-primary);
  background: var(--zj-primary-soft);
  box-shadow: 0 0 0 1px var(--zj-primary);
}

.type-card-title {
  font-size: var(--zj-fs-sm);
  font-weight: 600;
  color: var(--zj-ink-900);
}

.type-card.active .type-card-title {
  color: var(--zj-primary);
}

.type-card-desc {
  font-size: 12px;
  color: var(--zj-ink-600);
}

/* 追问链行内动作（卡片标题行右侧） */
.followup-head-actions {
  display: flex;
  gap: 8px;
  flex: none;
}

.followup-empty {
  margin: 0;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-500);
  line-height: 1.7;
}

.followup-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* 追问层多时在卡片内滚动，不撑破画布 */
.followup-scroll {
  flex: 1 1 0;
  min-height: 0;
  overflow-y: auto;
  align-content: start;
  padding-right: 2px;
}

.followup-line {
  display: flex;
  gap: 8px;
  align-items: center;
}

.layer-input {
  width: 88px;
  flex: none;
}

.direction-input {
  width: 150px;
  flex: none;
}

/* 内容确认行式列表：正常字号，替代此前过小的 descriptions */
.confirm-rows {
  flex: 1 1 0;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-right: 2px;
}

.confirm-row {
  display: flex;
  gap: 10px;
  font-size: var(--zj-fs-sm);
  line-height: 1.7;
}

.confirm-label {
  flex: none;
  width: 48px;
  color: var(--zj-ink-500);
}

.confirm-value {
  flex: 1;
  min-width: 0;
  color: var(--zj-ink-900);
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.confirm-value.strong {
  font-weight: 600;
}

/* 文章线的提交说明 */
.doc-notes {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-700);
  line-height: 1.7;
}

.done-step {
  padding: 8px 0 16px;
}

/* 弹窗底栏：上一步固定左侧，主按钮靠右 */
.footer-row {
  display: flex;
  align-items: center;
  gap: 12px;
  /* 三步底栏行高一致：切换时 footer 不跳动 */
  min-height: 32px;
}

.footer-hint {
  margin: 0 auto 0 0;
  font-size: 12px;
  color: var(--zj-ink-500);
}

.back-btn {
  margin-right: auto;
}

/* 步骤一的底栏只有「下一步」，靠右收尾；其它步底栏含「上一步」自动占左 */
.footer-row--end {
  justify-content: flex-end;
}

@media (max-width: 900px) {
  /* 窄屏：侧栏收不进去，栅格改纵向堆叠——工具行、正文、辅助栏依次顺排，整体可滚 */
  .step-body--write {
    display: flex;
    flex-direction: column;
    overflow-y: auto;
  }

  .step-body--write .editor-toolbar,
  .step-body--write .editor-block,
  .write-rail {
    grid-area: auto;
  }

  /* 工具行窄屏改单列：标题/切换、字数上下顺排 */
  .editor-toolbar {
    grid-template-columns: minmax(0, 1fr);
    gap: 8px;
  }

  .step-body--write .editor-block {
    flex: none;
    height: 46vh;
  }

  .write-rail {
    flex: none;
    overflow: visible;
    padding-right: 0;
  }

  .write-rail .ai-panel {
    margin-top: 0;
  }
}

@media (max-width: 720px) {
  /* 工具行太窄时换行：标题一行、切换一行，避免互相挤压 */
  .toolbar-main {
    flex-wrap: wrap;
  }

  .title-input {
    flex: 1 1 auto;
  }

  .step-body--meta {
    overflow-y: auto;
  }

  .meta-grid {
    grid-template-columns: 1fr;
    flex: none;
  }

  .type-cards {
    grid-template-columns: 1fr;
  }

  .summary-input :deep(textarea) {
    height: 160px;
  }

  .followup-line {
    flex-wrap: wrap;
  }

  .direction-input {
    width: 130px;
  }
}
</style>

<!-- 弹窗外壳：Element 渲染且 teleport 到 body，不受本组件 scoped 约束，故用独立全局块（类名限定不污染他处）。
     高度 = 视口高 - 上下各 24px 固定开销：顶部到视口 24px、底部到屏幕下边缘 24px，
     固定项不随屏漂移，1K/2K/4K 都得到"撑满且留边"的一致效果；body 吃满剩余高度。 -->
<style>
.el-dialog.wizard-dialog {
  margin: 24px auto;
  height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
}

.wizard-dialog .el-dialog__body {
  flex: 1 1 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
</style>
