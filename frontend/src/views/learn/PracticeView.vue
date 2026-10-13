<script setup lang="ts">
// 练习页（核心模块定义 4.2，原型屏 03–07）：题面与目标刻度 → 双节奏（速览 / 练习）→ 小结。
// 速览：先自测后看三档样例、自评直接推进（quick/weak，进抽查范围）；
// 练习：闭卷作答经判定引擎逐层下钻（对→下钻 / 缺→继续钻 / 错→纠正后同层换角度 / 空→切题），
//       到停止点收手，close 出四档小结并回写状态（通过 → 实心）。
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  closeSession,
  getBrief,
  getDoc,
  LEARN_STATUS_LABELS,
  quickEval,
  selfEval as apiSelfEval,
  startPractice,
  submitAnswer,
  submitEmpty
} from '@/api/learn'
import { GRADE_LABELS, VERDICT_LABELS } from '@/api/judge'
import type {
  DocView,
  JudgeAnswerView,
  JudgeVerdict,
  PracticeBriefView,
  PracticeSessionView
} from '@/api/types'
import DocDisplay from '@/components/kb/DocDisplay.vue'

const route = useRoute()
const router = useRouter()

const kpId = computed(() => Number(route.params.kpId))
const loading = ref(false)

/** 阶段：brief 题面与选节奏 / quick 速览 / practice 练习 / summary 小结 */
type Phase = 'brief' | 'quick' | 'practice' | 'summary'
const phase = ref<Phase>('brief')

const brief = ref<PracticeBriefView | null>(null)

/** 速览：三档样例 tab（自测后再展开对照） */
const quickTab = ref<'pass' | 'good' | 'excellent'>('pass')
const quickRevealed = ref(false)

/** 练习：判定会话 */
const session = ref<PracticeSessionView | null>(null)
const currentFollowupId = ref<string | null>(null)
const draft = ref('')
const submitting = ref(false)
const lastAnswer = ref<JudgeAnswerView | null>(null)
const pendingSelfEval = ref<number | null>(null)
const nextAdvice = ref('')

/** 小结 */
const grade = ref<string | null>(null)
const gradeNote = ref('')
const finalState = ref<string>('')

/** "看不懂"资料展开面板 */
const openedDoc = ref<DocView | null>(null)

const DIFF_LABELS: Record<string, string> = { junior: '初级', mid: '中级', senior: '高级', expert: '资深' }

const stopLayer = computed(() => brief.value?.stopLayer ?? session.value?.stopLayer ?? 0)
const currentLayer = computed(() => {
  if (!currentFollowupId.value) return 0
  return brief.value?.followups.find((f) => f.id === currentFollowupId.value)?.layer ?? 0
})

const answeredLayers = computed(() => {
  const answers = session.value?.answers ?? []
  return new Set(answers.map((a) => a.layer)).size
})

const currentQuestion = computed(() => {
  if (!currentFollowupId.value) return brief.value?.kpTitle ?? ''
  return brief.value?.followups.find((f) => f.id === currentFollowupId.value)?.content ?? ''
})

const quickSample = computed(() =>
  (brief.value?.answerSamples ?? []).filter((s) => s.level === quickTab.value)
)

onMounted(() => void boot())

async function boot() {
  loading.value = true
  try {
    asBrief(await getBrief(kpId.value))
  } catch (error) {
    const err = error as { code?: string; message?: string }
    if (err.code === 'LEARN_KP_NOT_AVAILABLE' || err.code === 'COMMON_NOT_FOUND') {
      ElMessage.warning('该知识点已下线或不可练，返回学习路径')
      router.replace('/map')
      return
    }
    ElMessage.error(err.message ?? '加载题面失败')
  } finally {
    loading.value = false
  }
}

function asBrief(view: PracticeBriefView) {
  brief.value = view
  phase.value = 'brief'
  quickRevealed.value = false
  quickTab.value = 'pass'
  session.value = null
  lastAnswer.value = null
  pendingSelfEval.value = null
  nextAdvice.value = ''
  draft.value = ''
  openedDoc.value = null
  grade.value = null
  gradeNote.value = ''
  finalState.value = ''
}

function gradeLabel(value: string | null): string {
  switch (value) {
    case 'excellent':
      return '优'
    case 'good':
      return '良'
    case 'pass':
      return '及'
    default:
      return '未'
  }
}

// —— 速览模式 ——

function enterQuick() {
  phase.value = 'quick'
  quickRevealed.value = false
}

async function quickSelfEval(known: boolean) {
  try {
    await quickEval(kpId.value, known)
    ElMessage.success(known ? '已记速览通过 · 进度记空心格，待复验' : '已记待加强 · 弱点优先复现')
    router.push(`/unit/${brief.value?.topicId ?? ''}`)
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '操作失败')
  }
}

// —— 练习模式 ——

async function enterPractice() {
  loading.value = true
  try {
    session.value = await startPractice(kpId.value)
    phase.value = 'practice'
    currentFollowupId.value = null
    draft.value = ''
    lastAnswer.value = null
    nextAdvice.value = ''
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '开启练习失败')
  } finally {
    loading.value = false
  }
}

async function doSubmitAnswer() {
  if (!session.value || submitting.value) return
  const answer = draft.value.trim()
  if (!answer) {
    ElMessage.warning('请输入作答内容，或走「未答」')
    return
  }
  submitting.value = true
  try {
    const resp = await submitAnswer(session.value.id, currentFollowupId.value, answer)
    applyVerdict(resp.answer, resp.degraded, resp.advice)
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '判定失败')
  } finally {
    submitting.value = false
  }
}

async function doSubmitEmpty() {
  if (!session.value || submitting.value) return
  submitting.value = true
  try {
    const resp = await submitEmpty(session.value.id, currentFollowupId.value)
    applyVerdict(resp.answer, resp.degraded, resp.advice)
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '操作失败')
  } finally {
    submitting.value = false
  }
}

/** 判定结论 → 更新本地 answers + 决定是否解锁自评 */
function applyVerdict(answer: JudgeAnswerView, degraded: boolean, advice: string) {
  if (!session.value) return
  const answers = [...session.value.answers.filter((a) => a.id !== answer.id), answer]
  session.value = { ...session.value, answers, degraded: session.value.degraded || degraded }
  lastAnswer.value = answer
  pendingSelfEval.value = degraded || answer.verdict == null ? answer.id : null
  nextAdvice.value = advice
}

/** 依据最新答况决定下一步（对/缺→下钻；错→同层换角度；空→切题；到停止点→收手） */
function proceed() {
  if (!session.value || !lastAnswer.value) return
  const layer = lastAnswer.value.layer
  draft.value = ''
  // 已达停止点：收手，出小结
  if (stopLayer.value > 0 && layer >= stopLayer.value) {
    void finish()
    return
  }
  const next = nextFollowupLayer(layer)
  if (next == null) {
    void finish()
    return
  }
  currentFollowupId.value = next
  lastAnswer.value = null
  pendingSelfEval.value = null
  nextAdvice.value = ''
}

function nextFollowupLayer(currentLayerNo: number): string | null {
  const followups = (brief.value?.followups ?? []).slice().sort((a, b) => a.layer - b.layer)
  const next = followups.find((f) => f.layer > currentLayerNo)
  return next ? next.id : null
}

async function doSelfEval(verdict: JudgeVerdict) {
  if (!session.value || pendingSelfEval.value == null) return
  try {
    const view = await apiSelfEval(session.value.id, pendingSelfEval.value, verdict)
    const answers = session.value.answers.map((a) => (a.id === view.id ? view : a))
    session.value = { ...session.value, answers }
    lastAnswer.value = view
    pendingSelfEval.value = null
    nextAdvice.value = '判定已按自评结论记录'
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '自评失败')
  }
}

async function finish() {
  if (!session.value) return
  loading.value = true
  try {
    const result = await closeSession(session.value.id)
    session.value = result.session
    grade.value = result.session.grade
    gradeNote.value = result.session.gradeNote ?? ''
    finalState.value = result.state
    phase.value = 'summary'
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '结束练习失败')
  } finally {
    loading.value = false
  }
}

function backToUnit() {
  const topicId = brief.value?.topicId
  router.push(topicId ? `/unit/${topicId}` : '/map')
}

function retry() {
  void boot()
}

/** 展开挂载资料（复用 DocDisplay 的只读渲染） */
async function openDoc(docId: number) {
  try {
    openedDoc.value = await getDoc(docId)
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '资料加载失败')
  }
}
</script>

<template>
  <div class="practice-page" v-loading="loading">
    <!-- ============ 阶段一：题面与目标刻度（原型屏 03） ============ -->
    <template v-if="brief && phase === 'brief'">
      <div class="pf-top">
        <el-button text size="small" @click="backToUnit">‹ 学习单元</el-button>
        <span class="chip tiny">{{ LEARN_STATUS_LABELS[brief.state] }}</span>
      </div>

      <header class="q-title">
        <h1>{{ brief.kpTitle }}</h1>
      </header>

      <div v-if="brief.variants.length" class="variants">
        <p>面试官还可能这样问（问法变体）</p>
        <div class="vchips">
          <span v-for="(v, i) in brief.variants" :key="i" class="chip tiny">{{ v.text }}</span>
        </div>
      </div>

      <div class="meta-row">
        <span class="chip tiny">{{ DIFF_LABELS[brief.difficulty] }}</span>
        <span v-if="brief.followupLayers" class="chip tiny">追问 {{ brief.followupLayers }} 层</span>
        <span v-if="brief.docs.length" class="chip tiny">资料 {{ brief.docs.length }} 篇</span>
      </div>

      <!-- 目标刻度（铜：深度语义） -->
      <div class="stop-gauge">
        <div class="sg-t">STOP LEVEL · 目标刻度</div>
        <div class="sg-b">
          <b>
            到你目标职级（{{ DIFF_LABELS[brief.level] }}），讲到
            <em>第 {{ stopLayer }} 问</em>算够
          </b>
          <div class="depth-rail">
            <i v-for="n in brief.followupLayers" :key="n" :class="{ in: n <= stopLayer, beyond: n > stopLayer }"></i>
          </div>
        </div>
        <p class="sg-note">停止点之下的层不参与档位计算；超出停止点的追问算挑战，答了不加分、不答不扣分。</p>
      </div>

      <!-- 双节奏卡片 -->
      <p class="zj-eyebrow sect-eyebrow">选一种节奏</p>
      <div class="mode-pick">
        <div
          class="mode-card practice"
          role="button"
          tabindex="0"
          @click="enterPractice"
          @keydown.enter.prevent="enterPractice"
        >
          <span class="mc-ico" aria-hidden="true">
            <svg viewBox="0 0 24 24"><path d="M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" /></svg>
          </span>
          <div class="mc-body">
            <b>练习模式</b>
            <small>闭卷作答，判定引擎逐层下钻。判定通过才算完成，进度记<b>实心</b>。</small>
            <span class="mc-tag">SOLID · 经得起检验</span>
          </div>
        </div>
        <div
          class="mode-card quick"
          role="button"
          tabindex="0"
          @click="enterQuick"
          @keydown.enter.prevent="enterQuick"
        >
          <span class="mc-ico" aria-hidden="true">
            <svg viewBox="0 0 24 24"><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z" /><circle cx="12" cy="12" r="3" /></svg>
          </span>
          <div class="mc-body">
            <b>速览模式</b>
            <small>先自测后看三档样例，自评直接推进。想快速过一轮拿印象时用——它会进抽查范围，等着被复验。</small>
            <span class="mc-tag">HOLLOW · 显式的低保障通道</span>
          </div>
        </div>
      </div>

      <!-- 看不懂：挂载资料 -->
      <template v-if="brief.docs.length">
        <p class="zj-eyebrow sect-eyebrow">看不懂？</p>
        <div v-for="doc in brief.docs" :key="doc.id" class="card doc-link" @click="openDoc(doc.id)">
          <span class="dico" aria-hidden="true">
            <svg viewBox="0 0 24 24"><path d="M4 19V6a2 2 0 0 1 2-2h9l5 5v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2Z" /><path d="M14 4v5h5M8 13h8M8 17h5" /></svg>
          </span>
          <div class="dl-body">
            <b>{{ doc.title }}</b>
            <small>资料讲解 · {{ DIFF_LABELS[doc.difficulty] }} · 点击展开</small>
          </div>
        </div>
        <div v-if="openedDoc" class="doc-panel">
          <DocDisplay :doc="openedDoc" />
        </div>
      </template>
    </template>

    <!-- ============ 阶段二：速览模式（原型屏 04） ============ -->
    <template v-else-if="brief && phase === 'quick'">
      <div class="pf-top">
        <el-button text size="small" @click="phase = 'brief'">‹ 返回题面</el-button>
        <span class="chip tiny brass">低保障通道</span>
      </div>
      <header class="q-title"><h1>{{ brief.kpTitle }}</h1></header>

      <div class="qk-note">
        先<b>合上</b>，在心里把这道题说一遍，再展开三档样例对照——这一步的自评只决定进度<b>怎么记</b>，不判分。
      </div>

      <div v-if="!quickRevealed" class="reveal-row">
        <el-button type="primary" plain @click="quickRevealed = true">展开三档回答样例</el-button>
      </div>

      <template v-else>
        <div class="sm-tabs">
          <button :class="{ on: quickTab === 'pass' }" class="lv1" @click="quickTab = 'pass'">及格</button>
          <button :class="{ on: quickTab === 'good' }" class="lv2" @click="quickTab = 'good'">良好</button>
          <button :class="{ on: quickTab === 'excellent' }" class="lv3" @click="quickTab = 'excellent'">优秀</button>
        </div>
        <div v-for="sample in quickSample" :key="sample.level" class="card sm-pane" :class="`lv-${sample.level}`">
          <b>{{ GRADE_LABELS[sample.level] }}回答样例</b>
          <div class="sample-body">{{ sample.content }}</div>
        </div>
        <p v-if="quickSample.length === 0" class="list-empty">这一档暂无样例</p>

        <div class="self-eval">
          <el-button class="yes" type="primary" @click="quickSelfEval(true)">我会，跳过</el-button>
          <el-button class="no" plain @click="quickSelfEval(false)">我不会</el-button>
        </div>
        <div class="eval-explain">
          <b>「我会」</b> → 记学习状态 <b>速览通过</b>：进度记<b>空心格</b>，进入今日复习的<b>抽查范围</b>，之后会被低频弹出复验；虚报会被打回复测。<br />
          <b>「我不会」</b> → 记 <b>待加强</b>：进弱点加权，接下来优先复现。<b>两种都算已推进</b>，都不是失败。
        </div>
      </template>
    </template>

    <!-- ============ 阶段三：练习模式（原型屏 05） ============ -->
    <template v-else-if="brief && session && phase === 'practice'">
      <div class="drill-top">
        <span class="prog">{{ currentFollowupId ? `练习模式 · 第 ${currentLayer} 问` : '练习模式 · 题面' }}</span>
        <span class="tmr">停止点：第 {{ stopLayer }} 问</span>
      </div>

      <div class="drill-track">
        <div class="dt-l">
          <i
            v-for="n in Math.max(stopLayer, 1)"
            :key="n"
            :class="{ done: answeredLayers >= n, cur: currentLayer === n }"
          ></i>
        </div>
        <div class="dt-x"><span>追赶深度</span><span>{{ answeredLayers }} / {{ stopLayer }}</span></div>
      </div>

      <header class="drill-q"><h1>{{ currentQuestion }}</h1></header>

      <div v-if="!lastAnswer" class="answer-box">
        <div class="ab-l">你的作答（闭卷）</div>
        <el-input v-model="draft" type="textarea" :rows="5" placeholder="按你的思路把这道题说一遍……" />
      </div>

      <!-- 判定卡 -->
      <div v-if="lastAnswer && lastAnswer.verdict" class="verdict-card">
        <div class="vc-h">
          <span class="vbadge" :class="lastAnswer.verdict">{{ VERDICT_LABELS[lastAnswer.verdict] }}</span>
          <b>第 {{ lastAnswer.layer }} 问判定</b>
          <small>{{ lastAnswer.source }}</small>
        </div>
        <div class="vpoints">
          <div v-for="point in lastAnswer.points" :key="point.id" class="vpoint" :class="{ hit: point.hit }">
            <div class="vp-t"><span class="vp-mk"></span><span>{{ point.content }}</span></div>
            <div v-if="point.hit && point.quote" class="vp-q">{{ point.quote }}</div>
          </div>
          <p v-if="lastAnswer.wrongNote" class="wrong-note">{{ lastAnswer.wrongNote }}</p>
        </div>
      </div>

      <div v-if="lastAnswer && !lastAnswer.verdict" class="degrade-box">
        <p>判定暂时不可用（{{ lastAnswer.errorNote }}），请对该答况自评：</p>
        <div class="self-eval">
          <el-button size="small" @click="doSelfEval('correct')">对</el-button>
          <el-button size="small" @click="doSelfEval('missing')">缺</el-button>
          <el-button size="small" @click="doSelfEval('wrong')">错</el-button>
          <el-button size="small" @click="doSelfEval('empty')">空</el-button>
        </div>
      </div>

      <div v-if="nextAdvice && lastAnswer" class="drill-next">{{ nextAdvice }}</div>

      <div class="drill-actions">
        <template v-if="!lastAnswer">
          <el-button type="primary" class="flex1" :loading="submitting" @click="doSubmitAnswer">提交判定</el-button>
          <el-button plain :disabled="submitting" @click="doSubmitEmpty">未答</el-button>
        </template>
        <template v-else>
          <el-button type="primary" class="flex1" @click="proceed">下一步</el-button>
          <el-button plain @click="finish">结束练习</el-button>
        </template>
      </div>
      <p class="drill-foot">判定引擎给的是结构化结论（对/缺/错/空 + 命中要点 + 你的原话），档位由代码算。判定超时会重试一次，再失败自动解锁自评。</p>
    </template>

    <!-- ============ 阶段四：练习小结（原型屏 06） ============ -->
    <template v-else-if="phase === 'summary'">
      <div class="sum-head">
        <span class="sh-tag">PRACTICE · 本题目小结</span>
        <h1>{{ brief?.kpTitle }}</h1>
        <small><span>作答 {{ session?.answers.length ?? 0 }} 问</span><span>{{ finalState === 'pass' ? '判定通过' : '未通过' }}</span></small>
      </div>

      <div class="card grade-card">
        <div class="gc-t">
          <span class="grade-badge" :class="grade ?? 'fail'">{{ gradeLabel(grade) }}</span>
          <div class="gc-body">
            <b>{{ GRADE_LABELS[grade ?? ''] ?? '未评级' }}</b>
            <small>单知识点四档 · 由命中分布算出，模型不碰分</small>
          </div>
        </div>
        <p v-if="gradeNote" class="gc-note">{{ gradeNote }}</p>
      </div>

      <div class="sum-rows">
        <div v-for="answer in session?.answers ?? []" :key="answer.id" class="sum-row">
          <span class="vd" :class="answer.verdict ?? 'blank'">{{ answer.verdict ? VERDICT_LABELS[answer.verdict] : '自评' }}</span>
          <span class="st">第 {{ answer.layer }} 问</span>
          <span class="sl">{{ answer.source }}</span>
        </div>
      </div>

      <div class="writeback" :class="{ ok: finalState === 'pass' }">
        <svg viewBox="0 0 24 24"><path d="M20 6 9 17l-5-5" /></svg>
        <div class="wb-body">
          <b>{{ finalState === 'pass' ? '已回写学习状态：判定通过' : '本次未通过，保持进行中' }}</b>
          <small>
            {{ finalState === 'pass'
              ? '这道题现在记为判定通过（实心格）；练习记录只进你的个人档案，不影响题库内容。'
              : '可以再做一遍——练不住的题由判定如实指出，不是失败。' }}
          </small>
        </div>
      </div>

      <div class="sum-actions">
        <el-button plain @click="retry">再做一遍</el-button>
        <el-button type="primary" class="flex1" @click="backToUnit">回单元，看下一题</el-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.practice-page {
  padding-bottom: var(--zj-space-6);
}

.pf-top,
.drill-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--zj-space-2);
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
  margin-bottom: var(--zj-space-3);
}

.drill-top .tmr {
  color: var(--zj-brass);
  font-variant-numeric: tabular-nums;
}

.q-title h1,
.drill-q h1,
.sum-head h1 {
  margin: 0;
  font-size: var(--zj-fs-md);
  font-weight: 700;
  line-height: 1.5;
  letter-spacing: -0.01em;
  color: var(--zj-ink-900);
}

.variants {
  margin-top: var(--zj-space-4);
}

.variants p {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
  margin: 0 0 var(--zj-space-2);
}

.vchips {
  display: flex;
  gap: var(--zj-space-2);
  flex-wrap: wrap;
}

.meta-row {
  display: flex;
  gap: var(--zj-space-2);
  flex-wrap: wrap;
  margin-top: var(--zj-space-3);
}

.chip.tiny {
  font-size: 10px;
  padding: 2px 9px;
  border-radius: 999px;
  border: 1px solid var(--zj-line);
  color: var(--zj-ink-600);
}

.chip.tiny.brass {
  border-color: color-mix(in srgb, var(--zj-brass) 45%, transparent);
  color: var(--zj-brass);
  background: var(--zj-brass-soft);
}

/* —— 目标刻度（铜） —— */
.stop-gauge {
  margin-top: var(--zj-space-4);
  padding: var(--zj-space-3) var(--zj-space-4);
  border: 1px dashed color-mix(in srgb, var(--zj-brass) 50%, transparent);
  background: var(--zj-brass-soft);
  border-radius: var(--zj-radius);
}

.sg-t {
  font-size: 9.5px;
  letter-spacing: 0.14em;
  color: var(--zj-brass);
  font-weight: 600;
}

.sg-b {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--zj-space-3);
  margin-top: var(--zj-space-2);
}

.sg-b b {
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-900);
}

.sg-b b em {
  font-style: normal;
  color: var(--zj-brass);
  font-weight: 700;
}

.depth-rail {
  flex: none;
  display: flex;
  gap: 3px;
  align-items: flex-end;
  height: 26px;
}

.depth-rail i {
  width: 11px;
  height: 100%;
  border-radius: 2px;
  background: var(--zj-surface);
  border: 1.4px solid var(--zj-brass);
}

.depth-rail i.in {
  background: var(--zj-brass);
}

.depth-rail i.beyond {
  height: 60%;
  border-style: dashed;
  opacity: 0.7;
}

.sg-note {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-700);
  margin: var(--zj-space-2) 0 0;
  line-height: 1.6;
}

.sect-eyebrow {
  margin: var(--zj-space-5) 0 var(--zj-space-3);
}

/* —— 双节奏卡片 —— */
.mode-pick {
  display: flex;
  flex-direction: column;
  gap: var(--zj-space-2);
}

.mode-card {
  display: flex;
  gap: var(--zj-space-3);
  align-items: flex-start;
  padding: var(--zj-space-3) var(--zj-space-4);
  border: 1px solid var(--zj-line-soft);
  border-radius: var(--zj-radius);
  background: var(--zj-surface);
  cursor: pointer;
  transition: box-shadow var(--zj-dur-fast) var(--zj-ease-out);
}

@media (hover: hover) {
  .mode-card:hover {
    box-shadow: var(--zj-shadow);
  }
}

.mc-ico {
  flex: none;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.mc-ico svg {
  width: 19px;
  height: 19px;
  fill: none;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.mode-card.practice .mc-ico {
  background: var(--zj-acc-pass);
}

.mode-card.practice .mc-ico svg {
  stroke: var(--zj-surface);
}

.mode-card.quick .mc-ico {
  background: var(--zj-brass-soft);
  border: 1px solid color-mix(in srgb, var(--zj-brass) 35%, transparent);
}

.mode-card.quick .mc-ico svg {
  stroke: var(--zj-brass);
}

.mc-body b {
  font-size: var(--zj-fs-base);
  display: block;
  color: var(--zj-ink-900);
}

.mc-body small {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-600);
  display: block;
  margin-top: 2px;
  line-height: 1.6;
}

.mc-body small b {
  display: inline;
  font-size: inherit;
  font-weight: 700;
}

.mc-tag {
  font-size: 9.5px;
  margin-top: 5px;
  display: inline-block;
}

.mode-card.practice .mc-tag {
  color: var(--zj-acc-pass);
}

.mode-card.quick .mc-tag {
  color: var(--zj-brass);
}

/* —— 资料卡 —— */
.doc-link {
  display: flex;
  align-items: center;
  gap: var(--zj-space-3);
  padding: var(--zj-space-3) var(--zj-space-4);
  margin-bottom: var(--zj-space-2);
}

.dico {
  flex: none;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: color-mix(in srgb, var(--zj-primary) 10%, transparent);
  display: flex;
  align-items: center;
  justify-content: center;
}

.dico svg {
  width: 18px;
  height: 18px;
  stroke: var(--zj-primary);
  fill: none;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.dl-body b {
  font-size: var(--zj-fs-sm);
  display: block;
  color: var(--zj-ink-900);
}

.dl-body small {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.doc-panel {
  margin-top: var(--zj-space-3);
  padding: var(--zj-space-4);
  border: 1px solid var(--zj-line-soft);
  border-radius: var(--zj-radius);
  background: var(--zj-surface);
}

/* —— 速览 —— */
.qk-note {
  margin-top: var(--zj-space-3);
  padding: var(--zj-space-3);
  background: var(--zj-brass-soft);
  border: 1px dashed color-mix(in srgb, var(--zj-brass) 40%, transparent);
  border-radius: var(--zj-radius);
  font-size: var(--zj-fs-sm);
  color: var(--zj-brass);
  line-height: 1.65;
}

.qk-note b {
  color: var(--zj-ink-900);
}

.reveal-row {
  margin-top: var(--zj-space-4);
}

.sm-tabs {
  display: flex;
  background: var(--zj-surface-sunken);
  border-radius: var(--zj-radius);
  padding: 3px;
  gap: 3px;
  margin-top: var(--zj-space-4);
}

.sm-tabs button {
  flex: 1;
  border: none;
  background: transparent;
  border-radius: var(--zj-radius-sm);
  padding: 8px 0;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-500);
  cursor: pointer;
  font-family: inherit;
}

.sm-tabs button.on {
  background: var(--zj-surface);
  font-weight: 700;
  box-shadow: var(--zj-shadow-sm);
}

.sm-tabs button.on.lv1 {
  color: var(--zj-diff-2);
}

.sm-tabs button.on.lv2 {
  color: var(--zj-primary);
}

.sm-tabs button.on.lv3 {
  color: var(--zj-diff-4);
}

.sm-pane {
  margin-top: var(--zj-space-3);
  padding: var(--zj-space-3) var(--zj-space-4);
}

.sm-pane b {
  font-size: 11px;
  display: block;
  margin-bottom: var(--zj-space-2);
  letter-spacing: 0.04em;
}

.sm-pane.lv-pass {
  border-top: 3px solid var(--zj-diff-2);
}

.sm-pane.lv-good {
  border-top: 3px solid var(--zj-primary);
}

.sm-pane.lv-excellent {
  border-top: 3px solid var(--zj-diff-4);
}

.sample-body {
  font-size: var(--zj-fs-sm);
  line-height: 1.8;
  color: var(--zj-ink-700);
  white-space: pre-wrap;
}

.self-eval {
  margin-top: var(--zj-space-4);
  display: flex;
  gap: var(--zj-space-2);
}

.self-eval .yes,
.self-eval .no {
  flex: 1;
  padding: var(--zj-space-3) var(--zj-space-2);
  font-weight: 700;
}

.eval-explain {
  margin-top: var(--zj-space-3);
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
  line-height: 1.75;
}

.eval-explain b {
  color: var(--zj-ink-700);
}

.list-empty {
  margin: var(--zj-space-3) 0;
  text-align: center;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-500);
}

/* —— 练习下钻 —— */
.drill-track {
  margin-bottom: var(--zj-space-3);
}

.dt-l {
  display: flex;
  gap: 0;
  height: 4px;
  border-radius: 2px;
  overflow: hidden;
  background: var(--zj-surface-sunken);
}

.dt-l i {
  flex: 1;
  background: var(--zj-surface-sunken);
}

.dt-l i.done {
  background: var(--zj-acc-pass);
}

.dt-l i.cur {
  background: var(--zj-primary);
}

.dt-x {
  display: flex;
  justify-content: space-between;
  font-size: 9.5px;
  color: var(--zj-ink-500);
  margin-top: 6px;
  font-variant-numeric: tabular-nums;
}

.answer-box {
  margin-top: var(--zj-space-3);
}

.ab-l {
  font-size: 9.5px;
  letter-spacing: 0.14em;
  color: var(--zj-ink-500);
  margin-bottom: var(--zj-space-2);
}

.verdict-card {
  margin-top: var(--zj-space-3);
  border: 1px solid var(--zj-line-soft);
  border-radius: var(--zj-radius);
  background: var(--zj-surface);
  overflow: hidden;
}

.vc-h {
  display: flex;
  align-items: center;
  gap: var(--zj-space-2);
  padding: var(--zj-space-3);
  border-bottom: 1px solid var(--zj-line-soft);
}

.vc-h b {
  font-size: var(--zj-fs-sm);
  font-weight: 600;
  color: var(--zj-ink-900);
}

.vc-h small {
  margin-left: auto;
  font-size: 10px;
  color: var(--zj-ink-500);
}

.vbadge {
  flex: none;
  font-size: 11px;
  font-weight: 700;
  color: var(--zj-surface);
  border-radius: 7px;
  padding: 2.5px 9px;
}

.vbadge.correct {
  background: var(--zj-acc-pass);
}

.vbadge.missing {
  background: var(--zj-acc-weak);
}

.vbadge.wrong {
  background: var(--zj-danger, #d64545);
}

.vbadge.empty {
  background: var(--zj-ink-400);
}

.vpoints {
  padding: var(--zj-space-3);
  display: flex;
  flex-direction: column;
  gap: var(--zj-space-2);
}

.vpoint {
  font-size: var(--zj-fs-xs);
  line-height: 1.6;
  color: var(--zj-ink-600);
}

.vp-t {
  display: flex;
  gap: 7px;
  align-items: flex-start;
}

.vp-mk {
  flex: none;
  width: 13px;
  height: 13px;
  border-radius: 50%;
  margin-top: 2px;
  border: 1.6px solid var(--zj-ink-400);
}

.vpoint.hit .vp-mk {
  background: var(--zj-acc-pass);
  border-color: var(--zj-acc-pass);
}

.vp-q {
  margin: 4px 0 0 20px;
  font-size: 10.5px;
  color: var(--zj-brass);
  border-left: 2px solid color-mix(in srgb, var(--zj-brass) 45%, transparent);
  padding-left: 8px;
}

.wrong-note {
  margin: var(--zj-space-2) 0 0;
  font-size: var(--zj-fs-xs);
  color: var(--zj-danger, #d64545);
}

.degrade-box {
  margin-top: var(--zj-space-3);
  padding: var(--zj-space-3);
  border: 1px dashed color-mix(in srgb, var(--zj-danger, #d64545) 40%, transparent);
  border-radius: var(--zj-radius);
}

.degrade-box p {
  margin: 0;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-700);
}

.drill-next {
  margin-top: var(--zj-space-3);
  padding: var(--zj-space-3);
  border-radius: var(--zj-radius);
  background: var(--zj-primary-soft);
  font-size: var(--zj-fs-sm);
  color: var(--zj-primary);
  line-height: 1.6;
}

.drill-actions {
  margin-top: var(--zj-space-4);
  display: flex;
  gap: var(--zj-space-2);
}

.flex1 {
  flex: 1;
}

.drill-foot {
  text-align: center;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
  margin: var(--zj-space-3) auto 0;
  line-height: 1.7;
}

/* —— 小结 —— */
.sum-head {
  margin-bottom: var(--zj-space-4);
}

.sh-tag {
  font-size: 10.5px;
  letter-spacing: 0.16em;
  color: var(--zj-primary);
  font-weight: 600;
}

.sum-head h1 {
  margin-top: 6px;
}

.sum-head small {
  display: flex;
  gap: var(--zj-space-3);
  color: var(--zj-ink-500);
  font-size: var(--zj-fs-xs);
  margin-top: 6px;
}

.grade-card {
  padding: var(--zj-space-4);
}

.gc-t {
  display: flex;
  align-items: center;
  gap: var(--zj-space-3);
}

.grade-badge {
  flex: none;
  width: 52px;
  height: 52px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: 800;
  color: var(--zj-surface);
}

.grade-badge.excellent {
  background: var(--zj-diff-4);
}

.grade-badge.good {
  background: var(--zj-primary);
}

.grade-badge.pass {
  background: var(--zj-diff-2);
}

.grade-badge.fail {
  background: var(--zj-ink-400);
}

.gc-body b {
  font-size: var(--zj-fs-base);
  display: block;
  color: var(--zj-ink-900);
}

.gc-body small {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
  display: block;
  margin-top: 3px;
}

.gc-note {
  margin: var(--zj-space-3) 0 0;
  padding-top: var(--zj-space-3);
  border-top: 1px dashed var(--zj-line);
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-700);
  line-height: 1.75;
}

.sum-rows {
  margin-top: var(--zj-space-4);
}

.sum-row {
  display: flex;
  gap: var(--zj-space-3);
  align-items: center;
  padding: var(--zj-space-3) 0;
  border-bottom: 1px solid var(--zj-line-soft);
}

.sum-row:last-child {
  border-bottom: none;
}

.sum-row .vd {
  flex: none;
  font-size: 10px;
  color: var(--zj-surface);
  border-radius: 6px;
  padding: 2.5px 7px;
}

.sum-row .vd.correct {
  background: var(--zj-acc-pass);
}

.sum-row .vd.missing {
  background: var(--zj-acc-weak);
}

.sum-row .vd.wrong {
  background: var(--zj-danger, #d64545);
}

.sum-row .vd.empty,
.sum-row .vd.blank {
  background: var(--zj-ink-400);
}

.sum-row .st {
  flex: 1;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-800);
}

.sum-row .sl {
  flex: none;
  font-size: 10px;
  color: var(--zj-ink-500);
}

.writeback {
  margin-top: var(--zj-space-4);
  padding: var(--zj-space-3);
  border-radius: var(--zj-radius);
  background: var(--zj-brass-soft);
  border: 1px dashed color-mix(in srgb, var(--zj-brass) 45%, transparent);
  display: flex;
  gap: var(--zj-space-3);
  align-items: flex-start;
}

.writeback.ok {
  background: var(--zj-primary-soft);
  border-color: color-mix(in srgb, var(--zj-primary) 40%, transparent);
}

.writeback svg {
  flex: none;
  width: 17px;
  height: 17px;
  stroke: var(--zj-brass);
  fill: none;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
  margin-top: 1px;
}

.writeback.ok svg {
  stroke: var(--zj-primary);
}

.wb-body b {
  font-size: var(--zj-fs-sm);
  display: block;
  color: var(--zj-ink-900);
}

.wb-body small {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-700);
  display: block;
  margin-top: 2px;
  line-height: 1.6;
}

.sum-actions {
  margin-top: var(--zj-space-4);
  display: flex;
  gap: var(--zj-space-2);
}
</style>
