<script setup lang="ts">
// 试判台（核心模块定义 6.1，判定引擎的 B 端质检入口）：
// 选知识点 + 目标职级 → 题面与追问层逐层作答 → 看判定明细（答况/逐要点命中/引用）→ 关闭出档位报告。
// 同时服务 5.2 审核项「评分要点可判定性」与职级停止点标定完整性的人工验证。
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CaretRight, Refresh, Search } from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'
import {
  GRADE_LABELS,
  LEVEL_LABELS,
  VERDICT_LABELS,
  closeSession,
  createSession,
  getSession,
  listSessions,
  searchAvailableKps,
  selfEval,
  submitAnswer,
  submitEmpty
} from '@/api/judge'
import { getKp } from '@/api/kb'
import type {
  JudgeSessionItemView,
  JudgeSessionView,
  JudgeVerdict,
  KbContentItemView,
  KbScoringPoint
} from '@/api/types'
import { useConstantsStore } from '@/stores/constants'

const constants = useConstantsStore()
const route = useRoute()

type Phase = 'setup' | 'session'

const phase = ref<Phase>('setup')
const loading = ref(false)
const submitting = ref(false)

function messageOf(error: unknown, fallback: string): string {
  const err = error as { message?: string }
  return err.message ?? fallback
}

// —— ① 建会话：知识点搜索 + 目标职级 ——

const keyword = ref('')
const kpOptions = ref<KbContentItemView[]>([])
const kpId = ref<number | null>(null)
const level = ref('mid')
const history = ref<JudgeSessionItemView[]>([])

const LEVEL_OPTIONS = [
  { value: 'junior', label: '初级' },
  { value: 'mid', label: '中级' },
  { value: 'senior', label: '高级' },
  { value: 'expert', label: '资深' },
  { value: 'master', label: '专家' }
]

async function searchKps() {
  if (!keyword.value.trim()) {
    ElMessage.warning('输入题面关键词搜索知识点')
    return
  }
  loading.value = true
  try {
    kpOptions.value = await searchAvailableKps(keyword.value.trim())
    if (!kpOptions.value.length) ElMessage.info('没有匹配的已上线知识点')
  } catch (error) {
    ElMessage.error(messageOf(error, '搜索失败'))
  } finally {
    loading.value = false
  }
}

async function loadHistory() {
  try {
    history.value = await listSessions()
  } catch {
    history.value = []
  }
}

async function startNew() {
  if (!kpId.value) {
    ElMessage.warning('先选定一个知识点')
    return
  }
  loading.value = true
  try {
    session.value = await createSession(kpId.value, level.value)
    phase.value = 'session'
    draft.value = ''
    await loadHistory()
  } catch (error) {
    ElMessage.error(messageOf(error, '创建会话失败'))
  } finally {
    loading.value = false
  }
}

async function openHistory(item: JudgeSessionItemView) {
  loading.value = true
  try {
    session.value = await getSession(item.id)
    phase.value = 'session'
  } catch (error) {
    ElMessage.error(messageOf(error, '加载会话失败'))
  } finally {
    loading.value = false
  }
}

function backToSetup() {
  phase.value = 'setup'
  session.value = null
  void loadHistory()
}

// —— ② 问答推进：当前问题 = 题面或第一个未答的追问层 ——

const session = ref<JudgeSessionView | null>(null)
const draft = ref('')

const answeredFollowupIds = computed(
  () => new Set(session.value?.answers.map((answer) => answer.followupId).filter(Boolean) ?? [])
)

const activeFollowups = computed(() => session.value?.followups ?? [])

const currentFollowup = computed(() => {
  if (session.value?.status === 'closed') return null
  // 题面未答（或未有效作答）→ 停在题面
  const titleAnswered = session.value?.answers.some(
    (answer) => answer.followupId === null && answer.verdict !== null && answer.verdict !== 'empty'
  )
  if (!titleAnswered) return null
  return activeFollowups.value.find((followup) => !answeredFollowupIds.value.has(followup.id)) ?? null
})

const currentQuestion = computed(() =>
  currentFollowup.value ? currentFollowup.value.content : (session.value?.kpTitle ?? '')
)

const currentQuestionLabel = computed(() =>
  currentFollowup.value
    ? `追问 · 第 ${currentFollowup.value.layer} 层 · ${currentFollowup.value.direction}`
    : '题面'
)

const inScope = (layer: number) => session.value?.stopLayer == null || layer <= session.value.stopLayer

const VERDICT_TAG_TYPES: Record<JudgeVerdict, 'success' | 'warning' | 'danger' | 'info'> = {
  correct: 'success',
  missing: 'warning',
  wrong: 'danger',
  empty: 'info'
}

const POINT_LEVEL_LABELS: Record<KbScoringPoint['level'], string> = {
  pass: '及格',
  good: '良好',
  excellent: '优秀'
}

async function doAnswer() {
  if (!session.value) return
  if (!draft.value.trim()) {
    ElMessage.warning('作答内容为空：空答请点「未答」')
    return
  }
  submitting.value = true
  try {
    const resp = await submitAnswer(
      session.value.id,
      currentFollowup.value?.id ?? null,
      draft.value.trim()
    )
    if (resp.degraded) {
      ElMessage.warning(resp.advice)
    }
    draft.value = ''
    session.value = await getSession(session.value.id)
  } catch (error) {
    ElMessage.error(messageOf(error, '判定失败'))
  } finally {
    submitting.value = false
  }
}

async function doEmpty() {
  if (!session.value) return
  submitting.value = true
  try {
    const resp = await submitEmpty(session.value.id, currentFollowup.value?.id ?? null)
    if (resp.degraded) {
      ElMessage.warning(resp.advice)
    }
    draft.value = ''
    session.value = await getSession(session.value.id)
  } catch (error) {
    ElMessage.error(messageOf(error, '记录未答失败'))
  } finally {
    submitting.value = false
  }
}

// —— ③ 降级自评 ——

const selfEvalTarget = computed(() =>
  session.value?.answers.find((answer) => answer.verdict === null) ?? null
)

async function doSelfEval(verdict: JudgeVerdict) {
  if (!session.value || !selfEvalTarget.value) return
  submitting.value = true
  try {
    await selfEval(session.value.id, selfEvalTarget.value.id, verdict)
    session.value = await getSession(session.value.id)
    ElMessage.success('已记录自评')
  } catch (error) {
    ElMessage.error(messageOf(error, '自评失败'))
  } finally {
    submitting.value = false
  }
}

async function doClose() {
  if (!session.value) return
  submitting.value = true
  try {
    session.value = await closeSession(session.value.id)
    ElMessage.success('会话已关闭，档位已生成')
  } catch (error) {
    ElMessage.error(messageOf(error, '关闭失败'))
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  await constants.ensure()
  await loadHistory()
  await presetFromQuery()
})

// 目录页「试判」直达：?kpId= 预选知识点（拉详情构造单行选项并选中），省掉重复搜索
async function presetFromQuery() {
  const raw = route.query.kpId
  const kpIdNum = Number(Array.isArray(raw) ? raw[0] : raw)
  if (!Number.isInteger(kpIdNum) || kpIdNum <= 0) return
  loading.value = true
  try {
    const kp = await getKp(kpIdNum)
    if (kp.status !== 'available') {
      ElMessage.warning('该知识点不在可用状态：判定只对上线内容进行')
      return
    }
    kpOptions.value = [{
      contentType: 'kp',
      id: kp.id,
      domainCode: kp.domainCode,
      topicId: kp.topicId,
      topicName: kp.topicName,
      title: kp.title,
      summary: kp.summary,
      difficulty: kp.difficulty,
      status: kp.status,
      kpId: null,
      mergedIntoId: null,
      mergeProduct: false,
      reviewDueAt: kp.reviewDueAt,
      createdAt: kp.createdAt,
      updatedAt: kp.updatedAt
    }]
    kpId.value = kp.id
  } catch (error) {
    ElMessage.error(messageOf(error, '加载知识点失败'))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="judge-view">
    <header class="view-head">
      <div>
        <h1>试判台</h1>
        <p class="view-sub">判定引擎质检：逐问作答对齐评分要点，验证「可判定性」与停止点标定</p>
      </div>
      <el-button v-if="phase === 'session'" :icon="Refresh" @click="backToSetup">返回选择</el-button>
    </header>

    <!-- ① 建会话 -->
    <template v-if="phase === 'setup'">
      <section class="panel">
        <h2>开始一场试判</h2>
        <div class="setup-row">
          <el-input
            v-model="keyword"
            placeholder="输入题面关键词，仅搜索已上线知识点"
            clearable
            class="keyword-input"
            @keyup.enter="searchKps"
          >
            <template #append>
              <el-button :icon="Search" @click="searchKps" />
            </template>
          </el-input>
          <el-select v-model="level" class="level-select">
            <el-option v-for="option in LEVEL_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-button type="primary" :disabled="!kpId" :loading="loading" @click="startNew">开始判定</el-button>
        </div>
        <el-table v-if="kpOptions.length" :data="kpOptions" highlight-current-row border size="default"
          class="kp-table" @current-change="(row: KbContentItemView | null) => (kpId = row?.id ?? null)">
          <el-table-column prop="title" label="题面" min-width="280" />
          <el-table-column prop="topicName" label="主题" width="140" />
          <el-table-column label="难度" width="90">
            <template #default="{ row }">{{ row.difficulty }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button size="small" type="primary" link :icon="CaretRight"
                @click.stop="kpId = row.id; startNew()">选定</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section v-if="history.length" class="panel">
        <h2>历史会话</h2>
        <el-table :data="history" border size="default">
          <el-table-column prop="kpTitle" label="知识点" min-width="240" />
          <el-table-column label="职级" width="80">
            <template #default="{ row }">{{ LEVEL_LABELS[row.level] ?? row.level }}</template>
          </el-table-column>
          <el-table-column label="停止点" width="80">
            <template #default="{ row }">{{ row.stopLayer ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">{{ row.status === 'closed' ? '已关闭' : '进行中' }}</template>
          </el-table-column>
          <el-table-column label="档位" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.grade" :type="row.grade === 'fail' ? 'danger' : 'success'" size="small">
                {{ GRADE_LABELS[row.grade] }}
              </el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column prop="answerCount" label="作答" width="70" />
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="openHistory(row)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </template>

    <!-- ② 会话进行 -->
    <template v-else-if="session">
      <section class="panel session-meta">
        <div class="meta-line">
          <span class="meta-title">{{ session.kpTitle }}</span>
          <el-tag size="small">{{ LEVEL_LABELS[session.level] ?? session.level }}</el-tag>
          <el-tag size="small" type="info">停止点：第 {{ session.stopLayer ?? '—' }} 层</el-tag>
          <el-tag v-if="session.degraded" size="small" type="warning">含降级自评</el-tag>
          <el-tag v-if="session.status === 'closed'" size="small" :type="session.grade === 'fail' ? 'danger' : 'success'">
            {{ session.grade ? GRADE_LABELS[session.grade] : '无法评级' }}
          </el-tag>
        </div>
        <p v-if="session.kpSummary" class="meta-summary">{{ session.kpSummary }}</p>
      </section>

      <!-- 报告区（关闭后） -->
      <section v-if="session.status === 'closed'" class="panel report">
        <h2>档位报告</h2>
        <div class="report-grade">
          <span class="report-grade-value" :class="{ fail: session.grade === 'fail' }">
            {{ session.grade ? GRADE_LABELS[session.grade] : '无法评级' }}
          </span>
          <span class="report-note">{{ session.gradeNote }}</span>
        </div>
      </section>

      <!-- 问答区 -->
      <section v-if="session.status === 'open'" class="panel">
        <div class="question-head">
          <span class="question-label">{{ currentQuestionLabel }}</span>
          <el-tag v-if="currentFollowup && !inScope(currentFollowup.layer)" size="small" type="warning">
            停止点之外：不计入档位
          </el-tag>
        </div>
        <p class="question-text">{{ currentQuestion }}</p>
        <el-input v-model="draft" type="textarea" :rows="5" placeholder="闭卷作答：像面试一样用文字回答" />
        <div class="answer-actions">
          <el-button type="primary" :loading="submitting" @click="doAnswer">提交判定</el-button>
          <el-button :loading="submitting" @click="doEmpty">未答</el-button>
          <el-button v-if="currentQuestionLabel !== '题面' || session.answers.length"
            type="success" plain :loading="submitting" @click="doClose">
            关闭并评级
          </el-button>
        </div>
        <el-alert v-if="selfEvalTarget" type="warning" :closable="false" class="self-eval">
          <template #title>
            判定服务暂不可用，请对该答况自评（判定失败原因：{{ selfEvalTarget.errorNote }}）
          </template>
          <div class="self-eval-actions">
            <el-button v-for="(label, value) in VERDICT_LABELS" :key="value" size="small"
              :disabled="submitting" @click="doSelfEval(value as JudgeVerdict)">{{ label }}</el-button>
          </div>
        </el-alert>
      </section>

      <!-- 判定明细时间线 -->
      <section v-if="session.answers.length" class="panel">
        <h2>逐问判定明细</h2>
        <div v-for="answer in session.answers" :key="answer.id" class="answer-row">
          <div class="answer-head">
            <el-tag size="small" type="info">{{ answer.layer === 0 ? '题面' : `第 ${answer.layer} 层` }}</el-tag>
            <el-tag v-if="answer.verdict" size="small" :type="VERDICT_TAG_TYPES[answer.verdict]">
              {{ VERDICT_LABELS[answer.verdict] }}
            </el-tag>
            <el-tag v-else size="small" type="warning">待自评</el-tag>
            <span class="answer-source">{{ answer.source === 'self' ? '自评' : answer.source === 'code' ? '短路' : '引擎' }}</span>
            <span v-if="!inScope(answer.layer)" class="answer-outscope">停止点之外</span>
          </div>
          <p class="answer-question">{{ answer.questionText }}</p>
          <p class="answer-text">{{ answer.answerText || '（未作答）' }}</p>
          <p v-if="answer.wrongNote" class="answer-wrong">错误提示：{{ answer.wrongNote }}</p>
          <ul class="point-list">
            <li v-for="point in answer.points" :key="point.id" :class="{ hit: point.hit }">
              <span class="point-level" :data-level="point.level">{{ POINT_LEVEL_LABELS[point.level] }}</span>
              <span class="point-content">{{ point.content }}</span>
              <span v-if="point.hit && point.quote" class="point-quote">「{{ point.quote }}」</span>
            </li>
          </ul>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.judge-view {
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

.panel {
  padding: 18px 20px;
  background: var(--zj-surface);
  border: 1px solid var(--zj-line-soft);
  border-radius: 12px;
}

.panel h2 {
  margin: 0 0 12px;
  font-size: 15px;
  color: var(--zj-ink-700);
}

.setup-row {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}

.keyword-input {
  flex: 1;
}

.level-select {
  width: 120px;
}

.kp-table {
  margin-top: 4px;
}

.session-meta .meta-line {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.meta-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--zj-ink-800);
}

.meta-summary {
  margin: 8px 0 0;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-600);
}

.question-head {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 8px;
}

.question-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--zj-primary);
}

.question-text {
  margin: 0 0 12px;
  font-size: 16px;
  font-weight: 600;
  color: var(--zj-ink-800);
}

.answer-actions {
  display: flex;
  gap: 10px;
  margin-top: 12px;
}

.self-eval {
  margin-top: 14px;
}

.self-eval-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.report-grade {
  display: flex;
  gap: 14px;
  align-items: baseline;
}

.report-grade-value {
  font-size: 30px;
  font-weight: 700;
  color: var(--zj-primary);
}

.report-grade-value.fail {
  color: var(--el-color-danger);
}

.report-note {
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-600);
}

.answer-row {
  padding: 12px 0 14px;
  border-bottom: 1px dashed var(--zj-line-soft);
}

.answer-row:last-child {
  border-bottom: none;
}

.answer-head {
  display: flex;
  gap: 8px;
  align-items: center;
}

.answer-source {
  font-size: 12px;
  color: var(--zj-ink-600);
}

.answer-outscope {
  font-size: 12px;
  color: var(--el-color-warning);
}

.answer-question {
  margin: 8px 0 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--zj-ink-700);
}

.answer-text {
  margin: 0 0 8px;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-600);
  white-space: pre-wrap;
}

.answer-wrong {
  margin: 0 0 8px;
  font-size: var(--zj-fs-sm);
  color: var(--el-color-danger);
}

.point-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.point-list li {
  display: flex;
  gap: 8px;
  align-items: baseline;
  padding: 3px 0;
  font-size: 13px;
  color: var(--zj-ink-600);
}

.point-list li.hit {
  color: var(--zj-ink-800);
}

.point-level {
  flex: none;
  padding: 1px 6px;
  font-size: 11px;
  border-radius: 4px;
  background: var(--zj-primary-soft);
  color: var(--zj-primary);
}

.point-quote {
  color: var(--zj-ink-600);
  font-style: italic;
}
</style>
