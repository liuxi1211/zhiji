<script setup lang="ts">
// 知识点全结构编辑器：知识库管理编辑抽屉与合并页产物编辑共用（核心模块定义 5.2）。
// 追问层身份永久（红线 2）：新增层生成 fu-<uuid>，既有层只能停用不能删除；
// 评分要点可增删（无身份约束）；产物永远回到人手里，保存动作由父级决定去向。
import { computed, reactive, ref, watch } from 'vue'
import { Delete, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { searchContents } from '@/api/kb'
import type { KbAnswerSample, KbKpDraft } from '@/api/types'
import { createMarkdown, newFollowupId, newScoringPointId } from '@/utils/markdown'

const props = defineProps<{ initial: KbKpDraft | null; saving?: boolean }>()
const emit = defineEmits<{ (e: 'save', draft: KbKpDraft): void }>()

const md = createMarkdown()
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
  { value: 'expert', label: '资深' },
  { value: 'master', label: '专家' }
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

const draft = reactive<KbKpDraft>(props.initial ? structuredClone(props.initial) : blank())
watch(
  () => props.initial,
  (value) => {
    Object.assign(draft, value ? structuredClone(value) : blank())
  }
)

const frameworkHtml = computed(() => md.render(draft.frameworkMd || '（暂无内容）'))

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
    const hits = await searchContents(keyword.trim(), 'kp')
    prereqOptions.value = hits
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
  emit('save', structuredClone(draft))
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
        <el-input v-model="draft.frameworkMd" type="textarea" :rows="8" placeholder="Markdown 正文（叙述框架）" />
        <details class="md-preview">
          <summary>预览</summary>
          <div class="md-body" v-html="frameworkHtml"></div>
        </details>
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
          <p class="hint">层身份一经分配永不复用、永不修改（红线 2）：只能新增或停用，不能删除</p>
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
        <span class="hint inline-hint">人工标定的热度信号（面经回流与覆盖度由系统写入）</span>
      </el-form-item>
    </el-form>

    <div class="editor-actions">
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </div>
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

.md-preview {
  width: 100%;
  margin-top: 6px;
  font-size: 12px;
  color: var(--zj-ink-600);
}

.md-preview summary {
  cursor: pointer;
}

.md-body {
  margin-top: 6px;
  padding: 8px 10px;
  border: 1px dashed var(--zj-line);
  border-radius: 6px;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-700);
}

.editor-actions {
  display: flex;
  justify-content: flex-end;
}
</style>
