<script setup lang="ts">
// 知识点全结构编辑器：知识库管理编辑抽屉与合并页产物编辑共用（核心模块定义 5.2）。
// 追问层身份永久（红线 2）：新增层生成 fu-<uuid>，既有层只能停用不能删除；
// 评分要点可增删（无身份约束）；产物永远回到人手里，保存动作由父级决定去向。
import { computed, reactive, ref, watch } from 'vue'
import { Delete, Plus, View } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { searchContents } from '@/api/kb'
import KpDisplay from '@/components/kb/KpDisplay.vue'
import type { KbAnswerSample, KbKpDraft, KpView } from '@/api/types'
import { newFollowupId, newScoringPointId } from '@/utils/markdown'

const props = defineProps<{ initial: KbKpDraft | null; saving?: boolean }>()
const emit = defineEmits<{ (e: 'save', draft: KbKpDraft): void }>()

const DIFF_OPTIONS = [
  { value: 'junior', label: '初级' },
  { value: 'mid', label: '中级' },
  { value: 'senior', label: '高级' },
  { value: 'expert', label: '资深' }
]
const GRADE_OPTIONS: { value: KbAnswerSample['level']; label: string }[] = [
  { value: 'pass', label: '及格' },
  { value: 'good', label: '良好' },
  { value: 'excellent', label: '优秀' }
]
const LEVEL_OPTIONS = [
  { value: 'junior', label: '初级' },
  { value: 'mid', label: '中级' },
  { value: 'senior', label: '高级' },
  { value: 'expert', label: '资深' }
]

const blank = (): KbKpDraft => ({
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
})

// structuredClone 无法克隆 Vue 响应式 Proxy（DataCloneError），草稿均为纯 JSON 结构，用 JSON 深拷贝
function deepClone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

const draft = reactive<KbKpDraft>(props.initial ? deepClone(props.initial) : blank())
watch(
  () => props.initial,
  (value) => {
    Object.assign(draft, value ? deepClone(value) : blank())
  }
)

// —— 渲染预览（所见即所得）：草稿实时组装成用户侧渲染形态，弹窗内用 KpDisplay 展示 ——
// 元数据字段（id/主题/频率信号等）编辑态拿不到，用占位值补齐 KpView 结构；KpDisplay 对空值已做隐藏。

const previewVisible = ref(false)

const previewKp = computed<KpView>(() => ({
  id: 0,
  domainCode: '',
  topicId: 0,
  topicName: '',
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
  freqManual: draft.freqManual,
  freqSignal: 0,
  freqCoverage: 0,
  status: 'draft',
  mergedIntoId: null,
  mergeSources: [],
  reviewDueAt: null,
  createdBy: 0,
  createdAt: '',
  updatedAt: ''
}))

function addVariant() {
  draft.variants.push({ text: '' })
}

function addFollowup() {
  draft.followups.push({ id: newFollowupId(), layer: draft.followups.length + 1, direction: '', content: '', status: 'active' })
}

function addScoringPoint() {
  draft.scoringPoints.push({ id: newScoringPointId(), level: 'pass', content: '' })
}

function addLevelRule() {
  draft.levelRules.push({ level: 'mid', required: true, stopLayer: 1 })
}

function sampleOf(level: KbAnswerSample['level']): string {
  return draft.answerSamples.find((s) => s.level === level)?.content ?? ''
}

function setSample(level: KbAnswerSample['level'], content: string) {
  const existing = draft.answerSamples.find((s) => s.level === level)
  if (existing) {
    existing.content = content
  } else {
    draft.answerSamples.push({ level, content })
  }
}

// 前置依赖：远程模糊搜索知识点（搜得到才选得中，服务复盘同一能力）
const prereqOptions = ref<{ id: number; title: string }[]>([])
const prereqLoading = ref(false)

async function searchPrereq(keyword: string) {
  if (!keyword.trim()) {
    prereqOptions.value = []
    return
  }
  prereqLoading.value = true
  try {
    const result = await searchContents(keyword.trim(), 'kp')
    prereqOptions.value = result.items
      .filter((item) => item.id !== (props.initial as unknown as { id?: number })?.id)
      .map((item) => ({ id: item.id, title: item.title }))
  } catch {
    prereqOptions.value = []
  } finally {
    prereqLoading.value = false
  }
}

function submit() {
  if (!draft.title.trim()) {
    ElMessage.warning('题面不能为空')
    return
  }
  if (!draft.summary.trim()) {
    ElMessage.warning('概述齐备才能进入标准库（入库门槛）')
    return
  }
  emit('save', deepClone(draft))
}
</script>

<template>
  <div class="kp-editor">
    <el-form label-width="92px" label-position="left" size="default">
      <el-form-item label="题面" required>
        <el-input v-model="draft.title" placeholder="一句最完整的问法" maxlength="200" />
      </el-form-item>
      <el-form-item label="概述" required>
        <el-input v-model="draft.summary" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="百字内摘要（目录与同位管理的可读摘要）" />
      </el-form-item>
      <el-form-item label="难度">
        <el-radio-group v-model="draft.difficulty">
          <el-radio-button v-for="option in DIFF_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item label="叙述框架">
        <el-input v-model="draft.frameworkMd" type="textarea" :rows="8" placeholder="Markdown 正文（即参考答案的叙述主体）" />
        <p class="hint">叙述框架就是答案正文：不要复述题面，直接从作答内容开始；点右下角「预览效果」查看渲染后的样子</p>
      </el-form-item>

      <el-form-item label="问法变体">
        <div class="row-list">
          <div v-for="(variant, index) in draft.variants" :key="index" class="row-line">
            <el-input v-model="variant.text" placeholder="同题不同问法" />
            <el-button :icon="Delete" text type="danger" @click="draft.variants.splice(index, 1)" />
          </div>
          <el-button size="small" :icon="Plus" @click="addVariant">加变体</el-button>
        </div>
      </el-form-item>

      <el-form-item label="追问链">
        <div class="row-list">
          <div v-for="f in draft.followups" :key="f.id" class="row-line followup-line" :class="{ disabled: f.status === 'disabled' }">
            <el-input-number v-model="f.layer" :min="1" :max="20" controls-position="right" style="width: 88px" />
            <el-input v-model="f.direction" placeholder="方向（原理/对比/场景）" style="width: 140px" />
            <el-input v-model="f.content" placeholder="追问内容" />
            <el-switch v-model="f.status" active-value="active" inactive-value="disabled" active-text="启用" inline-prompt />
          </div>
          <el-button size="small" :icon="Plus" @click="addFollowup">加一层</el-button>
          <p class="hint">追问层一旦提交就只能停用、不能删除，请确认后再加。</p>
        </div>
      </el-form-item>

      <el-form-item label="评分要点">
        <div class="row-list">
          <div v-for="(point, index) in draft.scoringPoints" :key="point.id" class="row-line">
            <el-select v-model="point.level" style="width: 96px">
              <el-option v-for="option in GRADE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
            <el-input v-model="point.content" placeholder="可判定的得分要点（判定引擎的尺子）" />
            <el-button :icon="Delete" text type="danger" @click="draft.scoringPoints.splice(index, 1)" />
          </div>
          <el-button size="small" :icon="Plus" @click="addScoringPoint">加要点</el-button>
        </div>
      </el-form-item>

      <el-form-item v-for="grade in GRADE_OPTIONS" :key="grade.value" :label="`${grade.label}样例`">
        <el-input type="textarea" :rows="3" :model-value="sampleOf(grade.value)" @update:model-value="(v: string) => setSample(grade.value, v)" />
      </el-form-item>

      <el-form-item label="职级规则">
        <div class="row-list">
          <div v-for="(rule, index) in draft.levelRules" :key="index" class="row-line">
            <el-select v-model="rule.level" style="width: 96px">
              <el-option v-for="option in LEVEL_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
            <el-switch v-model="rule.required" active-text="必问" inactive-text="选问" inline-prompt />
            <span class="stop-label">停止点</span>
            <el-input-number v-model="rule.stopLayer" :min="0" :max="20" controls-position="right" style="width: 88px" />
            <el-button :icon="Delete" text type="danger" @click="draft.levelRules.splice(index, 1)" />
          </div>
          <el-button size="small" :icon="Plus" @click="addLevelRule">加职级</el-button>
        </div>
      </el-form-item>

      <el-form-item label="前置依赖">
        <el-select
          v-model="draft.prerequisites"
          multiple
          filterable
          remote
          clearable
          :remote-method="searchPrereq"
          :loading="prereqLoading"
          placeholder="搜索知识点标题作为先修项"
          style="width: 100%"
        >
          <el-option v-for="option in prereqOptions" :key="option.id" :label="`#${option.id} ${option.title}`" :value="option.id" />
        </el-select>
      </el-form-item>

      <el-form-item label="频率标定">
        <el-input-number v-model="draft.freqManual" :min="0" controls-position="right" />
        <span class="hint inline-hint">人工标定的热度；面经回流与覆盖度由系统自动统计</span>
      </el-form-item>
    </el-form>

    <div class="editor-actions">
      <el-button :icon="View" @click="previewVisible = true">预览效果</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </div>

    <!-- 渲染预览：与用户侧详情完全同构（KpDisplay），草稿实时反映 -->
    <el-dialog v-model="previewVisible" title="渲染预览（用户侧所见）" width="min(860px, 92vw)" append-to-body>
      <div class="preview-scroll">
        <KpDisplay :kp="previewKp" />
      </div>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.kp-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.row-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.row-line {
  display: flex;
  gap: 6px;
  align-items: center;
}

.row-line > .el-input:first-child {
  flex: 1;
}

.followup-line.disabled {
  opacity: 0.55;
}

.stop-label {
  font-size: 12px;
  color: var(--zj-ink-600);
}

.hint {
  margin: 0;
  font-size: 11px;
  color: var(--zj-ink-500, #999);
}

.inline-hint {
  margin-left: 8px;
}

.editor-actions {
  display: flex;
  justify-content: flex-end;
}

/* 预览弹窗内容限高内滚（append-to-body 弹窗，scoped 样式够不到，用内联高度+此类） */
.preview-scroll {
  max-height: 65vh;
  overflow: auto;
}
</style>
