<script setup lang="ts">
// 今日复习（核心模块定义 4.3，T12）：每天打开知道刷什么 + 碎片时间随机抽取。
// 今日清单（默认）：到期复测 / 弱点 / 抽查验伪 / 技能条新题 四路信号混合，3 分钟量级；
// 随机抽取：不设上限连续抽题，自测自评为主，支持按域集中复习（考前突击）；
// 高难度题"进入判定模式"复用练习流程（/practice/:kpId）。
// 路由与组件名保留 /gacha 与 GachaView（T11 R12 先例），页内文案全按 6.5 命名表。
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { draw, getToday, REVIEW_TAG_LABELS, submitResult } from '@/api/review'
import type {
  DrawResp,
  ReviewItemView,
  ReviewMode,
  ReviewTag,
  ReviewTodayView
} from '@/api/types'
import { useConstantsStore } from '@/stores/constants'

const router = useRouter()
const constants = useConstantsStore()

/** 页形态：today=今日清单 / draw=随机抽取 */
type PageForm = 'today' | 'draw'
const form = ref<PageForm>('today')

const loading = ref(false)
const today = ref<ReviewTodayView | null>(null)
/** 未定稿空态（后端 400 LEARN_PROFILE_NOT_FINALIZED）：引导去建档定稿 */
const notFinalized = ref(false)

const DIFF_LABELS: Record<string, string> = { junior: '初级', mid: '中级', senior: '高级', expert: '资深' }

/** 今日清单已自评的题（kpId → 下次复测提示），当日不再出现 */
const doneToday = ref<Map<number, string>>(new Map())

// —— 随机抽取状态 ——
const drawing = ref(false)
const current = ref<ReviewItemView | null>(null)
const poolSize = ref(0)
const drawnIds = ref<number[]>([])
/** 抽题卡的轻翻牌动画 key（重放用） */
const flipKey = ref(0)

const targetLevelName = computed(() =>
  today.value?.targetLevel ? constants.name('target_level', today.value.targetLevel) : ''
)

const progressDone = computed(() => doneToday.value.size)
const progressTotal = computed(() => (today.value?.items.length ?? 0) + progressDone.value)

const domainChips = computed(() => constants.items('domain') ?? [])

onMounted(() => void boot())

async function boot() {
  loading.value = true
  notFinalized.value = false
  try {
    today.value = await getToday()
  } catch (error) {
    const err = error as { code?: string; message?: string }
    if (err.code === 'LEARN_PROFILE_NOT_FINALIZED') {
      notFinalized.value = true
      today.value = null
    } else {
      ElMessage.error(err.message ?? '加载今日复习失败')
    }
  } finally {
    loading.value = false
  }
}

function switchForm(next: PageForm) {
  form.value = next
  if (next === 'today' && today.value === null && !notFinalized.value) {
    void boot()
  }
}

// —— 今日清单：自评 ——

const evaluating = ref<number | null>(null)

async function onEval(item: ReviewItemView, known: boolean, mode: ReviewMode) {
  evaluating.value = item.kpId
  try {
    const result = await submitResult(item.kpId, known, mode)
    const hint = known && result.intervalDays > 0
      ? `答对了，${result.intervalDays} 天后复测`
      : known
        ? '记下了，今天到此为止'
        : '打回复测队列，过会儿再来看看'
    doneToday.value.set(item.kpId, hint)
    evaluating.value = null
  } catch (error) {
    evaluating.value = null
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '提交失败')
  }
}

// —— 随机抽取 ——

async function onDraw(domainCode?: string) {
  drawing.value = true
  try {
    const resp: DrawResp = await draw(domainCode, drawnIds.value)
    current.value = resp.item
    poolSize.value = resp.poolSize
    drawnIds.value.push(resp.item.kpId)
    flipKey.value++
  } catch (error) {
    const err = error as { code?: string; message?: string }
    if (err.code === 'REVIEW_POOL_EMPTY') {
      current.value = null
      poolSize.value = 0
      drawnIds.value = []
      ElMessage.info('这一轮的题都过完了：重新开始一轮')
    } else {
      ElMessage.error(err.message ?? '抽取失败')
    }
  } finally {
    drawing.value = false
  }
}

const drawEval = ref<number | null>(null)
const drawEvalKnown = ref<boolean | null>(null)

async function onDrawEval(known: boolean) {
  const item = current.value
  if (!item) return
  drawEval.value = item.kpId
  drawEvalKnown.value = known
  try {
    await submitResult(item.kpId, known, 'draw')
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '提交失败')
  } finally {
    drawEval.value = null
  }
}

function nextDraw() {
  current.value = null
  drawEvalKnown.value = null
  void onDraw(pickedDomain.value || undefined)
}

/** 域过滤（chips 单选，再点取消） */
const pickedDomain = ref('')

function pickDomain(code: string) {
  pickedDomain.value = pickedDomain.value === code ? '' : code
  current.value = null
  drawEvalKnown.value = null
  drawnIds.value = []
}

/** 高难度卡：进入判定模式（复用练习流程，R9） */
function goPractice(kpId: number) {
  router.push(`/practice/${kpId}`)
}

function goMap() {
  router.push('/map')
}

function goProfile() {
  router.push('/profile')
}

/** tag → 样式类（映射 T11 已备的验收五态令牌，不新增颜色） */
function tagClass(tag: ReviewTag): string {
  return `tag-${tag}`
}

/** 展开的三档样例 tab（自评后才可见） */
const sampleTab = ref<Record<number, 'pass' | 'good' | 'excellent'>>({})

function tabOf(kpId: number): 'pass' | 'good' | 'excellent' {
  return sampleTab.value[kpId] ?? 'pass'
}

function setTab(kpId: number, tab: 'pass' | 'good' | 'excellent') {
  sampleTab.value = { ...sampleTab.value, [kpId]: tab }
}

function sampleOf(item: ReviewItemView, level: 'pass' | 'good' | 'excellent') {
  return item.answerSamples.find((s) => s.level === level)?.content ?? ''
}
</script>

<template>
  <div class="review-page" v-loading="loading">
    <!-- 未定稿 / 无档案 空态 -->
    <template v-if="notFinalized">
      <div class="gate">
        <div class="gate-ico" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="M4 19V6a2 2 0 0 1 2-2h9l5 5v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2Z" /><path d="M14 4v5h5M9 12.5l2 2 4-4" /></svg>
        </div>
        <h3>档案还没定稿</h3>
        <p>今日复习按你的技能条与掌握情况出题。档案定稿后，每天打开就知道该刷什么。</p>
        <el-button type="primary" class="gate-cta" @click="goProfile">去完成建档</el-button>
      </div>
    </template>

    <template v-else-if="today">
      <header class="zj-page-head">
        <h1>今日复习</h1>
        <p>{{ today.date }} · {{ today.profileName }}<template v-if="targetLevelName"> · 目标{{ targetLevelName }}</template></p>
      </header>

      <!-- 双形态切换 -->
      <div class="form-switch" role="tablist">
        <button
          type="button"
          class="fs-btn"
          :class="{ on: form === 'today' }"
          role="tab"
          :aria-selected="form === 'today'"
          @click="switchForm('today')"
        >
          今日清单
        </button>
        <button
          type="button"
          class="fs-btn"
          :class="{ on: form === 'draw' }"
          role="tab"
          :aria-selected="form === 'draw'"
          @click="switchForm('draw')"
        >
          随机抽取
        </button>
      </div>

      <!-- ============ 形态一：今日清单 ============ -->
      <template v-if="form === 'today'">
        <div class="card today-brief">
          <span class="tb-num">{{ progressDone }}<i>/{{ progressTotal }}</i></span>
          <div class="tb-body">
            <b>3 分钟量级 · 刷完今天就算完成</b>
            <small>到期复测 {{ today.stats.due }} · 弱点 {{ today.stats.weak }} · 抽查 {{ today.stats.quick }} · 新题 {{ today.stats.newCount }}</small>
          </div>
        </div>

        <!-- 空清单 -->
        <div v-if="today.items.length === 0 && doneToday.size === 0" class="gate">
          <div class="gate-ico" aria-hidden="true">
            <svg viewBox="0 0 24 24"><path d="M20 6 9 17l-5-5" /></svg>
          </div>
          <h3>今天没有到期的复习内容</h3>
          <p>复习排期都在正轨上。想学新东西可以去学习路径推进单元，或抽几题练练手感。</p>
          <div class="gate-cta-row">
            <el-button plain @click="goMap">去学习路径</el-button>
            <el-button plain type="primary" @click="switchForm('draw')">随机抽一题</el-button>
          </div>
        </div>

        <template v-else>
          <!-- 今日卡片 -->
          <article
            v-for="item in today.items"
            :key="item.kpId"
            class="card rev-card"
            :class="{ done: doneToday.has(item.kpId) }"
          >
            <div class="rc-top">
              <span v-for="tag in item.tags" :key="tag" class="rev-tag" :class="tagClass(tag)">
                {{ REVIEW_TAG_LABELS[tag] ?? tag }}
              </span>
              <span class="rc-diff" :data-diff="item.difficulty">{{ DIFF_LABELS[item.difficulty] ?? item.difficulty }}</span>
            </div>

            <!-- 已自评：折叠为结果行 -->
            <template v-if="doneToday.has(item.kpId)">
              <b class="rc-title">{{ item.title }}</b>
              <p class="rc-done-hint">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 6 9 17l-5-5" /></svg>
                {{ doneToday.get(item.kpId) }}
              </p>
            </template>

            <!-- 未自评：题面自测 → 展开样例 → 自评 -->
            <template v-else>
              <b class="rc-title">{{ item.title }}</b>
              <ul v-if="item.variants.length > 0" class="rc-variants">
                <li v-for="(v, vi) in item.variants" :key="vi">{{ v.text }}</li>
              </ul>
              <p class="rc-hint">先自己回忆一遍，再对照答案自评</p>

              <el-collapse class="rc-fold" @click.stop>
                <el-collapse-item name="samples">
                  <template #title>对照三档回答样例</template>
                  <div class="sample-tabs">
                    <button
                      v-for="lv in ['pass', 'good', 'excellent'] as const"
                      :key="lv"
                      type="button"
                      class="st-btn"
                      :class="{ on: tabOf(item.kpId) === lv, [`lv-${lv}`]: true }"
                      @click="setTab(item.kpId, lv)"
                    >
                      {{ lv === 'pass' ? '及格' : lv === 'good' ? '良好' : '优秀' }}
                    </button>
                  </div>
                  <p class="sample-body">{{ sampleOf(item, tabOf(item.kpId)) || '暂无该档样例' }}</p>
                </el-collapse-item>
              </el-collapse>

              <!-- 元信息与判定入口 -->
              <div class="rc-meta">
                <span v-if="item.followupLayers > 0">追问 {{ item.followupLayers }} 层</span>
                <span v-if="item.docCount > 0">资料 {{ item.docCount }} 篇</span>
                <button
                  v-if="item.followupLayers > 0 || item.difficulty === 'senior' || item.difficulty === 'expert'"
                  type="button"
                  class="rc-judge"
                  @click="goPractice(item.kpId)"
                >
                  进入判定模式 ›
                </button>
              </div>

              <div class="rc-actions">
                <el-button
                  plain
                  type="primary"
                  :loading="evaluating === item.kpId"
                  @click="onEval(item, true, 'today')"
                >
                  我会
                </el-button>
                <el-button
                  plain
                  :loading="evaluating === item.kpId"
                  @click="onEval(item, false, 'today')"
                >
                  不会
                </el-button>
              </div>
            </template>
          </article>

          <!-- 全部刷完 -->
          <div v-if="today.items.length === 0 && doneToday.size > 0" class="card alldone">
            <b>今天的复习完成了</b>
            <p>明天打开会按新的排期出题。想继续可以去学习路径或随机抽取。</p>
            <div class="gate-cta-row">
              <el-button plain @click="goMap">去学习路径</el-button>
              <el-button plain type="primary" @click="switchForm('draw')">随机抽一题</el-button>
            </div>
          </div>
        </template>
      </template>

      <!-- ============ 形态二：随机抽取 ============ -->
      <template v-else>
        <!-- 域过滤 chips（按域集中复习 / 考前突击） -->
        <div class="domain-chips">
          <button
            v-for="d in domainChips"
            :key="d.code"
            type="button"
            class="dc-chip"
            :class="{ on: pickedDomain === d.code }"
            @click="pickDomain(d.code)"
          >
            {{ d.name }}
          </button>
        </div>

        <!-- 未抽：大按钮 -->
        <div v-if="!current" class="draw-stage">
          <button type="button" class="draw-btn" :disabled="drawing" @click="nextDraw">
            <span class="db-ico" aria-hidden="true">
              <svg viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="2" /><path d="M3 10h18M8 15h3" /></svg>
            </span>
            <b>{{ drawing ? '抽取中…' : '抽一题' }}</b>
            <small>自测自评为主 · 不设上限 · 考前按域突击</small>
          </button>
        </div>

        <!-- 抽中的卡 -->
        <article v-else :key="flipKey" class="card rev-card draw-card">
          <div class="rc-top">
            <span class="rev-tag tag-draw">{{ REVIEW_TAG_LABELS.draw }}</span>
            <span class="rc-diff" :data-diff="current.difficulty">{{ DIFF_LABELS[current.difficulty] ?? current.difficulty }}</span>
            <span class="rc-pool">池中 {{ poolSize }} 题</span>
          </div>
          <b class="rc-title">{{ current.title }}</b>
          <ul v-if="current.variants.length > 0" class="rc-variants">
            <li v-for="(v, vi) in current.variants" :key="vi">{{ v.text }}</li>
          </ul>

          <!-- 自评后给样例对照与结论 -->
          <template v-if="drawEvalKnown !== null">
            <el-collapse class="rc-fold" @click.stop>
              <el-collapse-item name="samples">
                <template #title>对照三档回答样例</template>
                <div class="sample-tabs">
                  <button
                    v-for="lv in ['pass', 'good', 'excellent'] as const"
                    :key="lv"
                    type="button"
                    class="st-btn"
                    :class="{ on: tabOf(current.kpId) === lv, [`lv-${lv}`]: true }"
                    @click="setTab(current.kpId, lv)"
                  >
                    {{ lv === 'pass' ? '及格' : lv === 'good' ? '良好' : '优秀' }}
                  </button>
                </div>
                <p class="sample-body">{{ sampleOf(current, tabOf(current.kpId)) || '暂无该档样例' }}</p>
              </el-collapse-item>
            </el-collapse>
            <p class="dc-verdict" :class="drawEvalKnown ? 'ok' : 'no'">
              {{ drawEvalKnown ? '记下了 · 排期已后移' : '已打回复测队列 · fail_count +1' }}
            </p>
            <div class="rc-actions">
              <el-button type="primary" plain @click="nextDraw">再来一题</el-button>
              <el-button
                v-if="current.state === 'learning'"
                plain
                @click="goPractice(current.kpId)"
              >
                想正式拿下？去练习 ›
              </el-button>
            </div>
          </template>

          <!-- 未自评：自评按钮 -->
          <template v-else>
            <div class="rc-meta">
              <span v-if="current.followupLayers > 0">追问 {{ current.followupLayers }} 层</span>
              <button
                v-if="current.followupLayers > 0 || current.difficulty === 'senior' || current.difficulty === 'expert'"
                type="button"
                class="rc-judge"
                @click="goPractice(current.kpId)"
              >
                进入判定模式 ›
              </button>
            </div>
            <div class="rc-actions">
              <el-button
                plain
                type="primary"
                :loading="drawEval === current.kpId"
                @click="onDrawEval(true)"
              >
                我会
              </el-button>
              <el-button
                plain
                :loading="drawEval === current.kpId"
                @click="onDrawEval(false)"
              >
                不会
              </el-button>
            </div>
          </template>
        </article>
      </template>
    </template>
  </div>
</template>

<style scoped>
.review-page {
  padding-bottom: calc(var(--zj-space-6) + env(safe-area-inset-bottom));
}

/* —— 形态切换（分段控件） —— */
.form-switch {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  background: var(--zj-surface-sunken);
  border: 1px solid var(--zj-line-soft);
  border-radius: 10px;
  padding: 4px;
  margin-bottom: var(--zj-space-4);
}

.fs-btn {
  border: none;
  background: transparent;
  font-size: var(--zj-fs-sm);
  font-weight: 600;
  color: var(--zj-ink-600);
  padding: 7px 0;
  border-radius: 7px;
  cursor: pointer;
  transition: background var(--zj-dur-fast) var(--zj-ease-out), color var(--zj-dur-fast) var(--zj-ease-out);
}

.fs-btn.on {
  background: var(--zj-surface);
  color: var(--zj-ink-900);
  box-shadow: 0 1px 3px rgb(0 0 0 / 8%);
}

/* —— 今日概要条 —— */
.today-brief {
  display: flex;
  align-items: center;
  gap: var(--zj-space-3);
  padding: var(--zj-space-3) var(--zj-space-4);
  margin-bottom: var(--zj-space-3);
}

.tb-num {
  flex: none;
  font-size: 22px;
  font-weight: 700;
  color: var(--zj-ink-900);
  font-variant-numeric: tabular-nums;
}

.tb-num i {
  font-style: normal;
  font-size: 12px;
  color: var(--zj-ink-500);
  font-weight: 400;
}

.tb-body b {
  display: block;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-900);
}

.tb-body small {
  display: block;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
  margin-top: 2px;
}

/* —— 复习卡片 —— */
.rev-card {
  padding: var(--zj-space-4);
  margin-bottom: var(--zj-space-3);
  transition: opacity var(--zj-dur-fast) var(--zj-ease-out);
}

.rev-card.done {
  opacity: 0.62;
}

.rc-top {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.rev-tag {
  font-size: 9.5px;
  font-weight: 600;
  border-radius: 5px;
  padding: 1.5px 7px;
  border: 1px solid var(--zj-line);
  color: var(--zj-ink-600);
}

/* tag 色映射 T11 验收五态令牌：到期=铜 / 弱点=橙 / 抽查=墨青描边 / 新题=朱 */
.rev-tag.tag-due {
  border-color: color-mix(in srgb, var(--zj-brass) 45%, transparent);
  color: var(--zj-brass);
  background: var(--zj-brass-soft);
}

.rev-tag.tag-weak {
  border-color: color-mix(in srgb, var(--zj-acc-weak) 45%, transparent);
  color: var(--zj-acc-weak);
}

.rev-tag.tag-spot {
  border-color: color-mix(in srgb, var(--zj-acc-pass) 45%, transparent);
  color: var(--zj-acc-pass);
}

.rev-tag.tag-new {
  border-color: color-mix(in srgb, var(--zj-primary) 40%, transparent);
  color: var(--zj-primary);
  background: var(--zj-primary-soft);
}

.rev-tag.tag-draw {
  border-color: color-mix(in srgb, var(--zj-brass) 45%, transparent);
  color: var(--zj-brass);
  background: var(--zj-brass-soft);
}

.rc-diff {
  margin-left: auto;
  font-size: 10px;
  color: var(--zj-ink-500);
}

.rc-diff[data-diff='senior'],
.rc-diff[data-diff='expert'] {
  color: var(--zj-copper);
}

.rc-pool {
  font-size: 10px;
  color: var(--zj-ink-500);
  font-variant-numeric: tabular-nums;
}

.rc-title {
  display: block;
  font-size: var(--zj-fs-md);
  font-weight: 650;
  color: var(--zj-ink-900);
  margin-top: var(--zj-space-2);
  line-height: 1.5;
}

.rc-variants {
  margin: var(--zj-space-2) 0 0;
  padding: 0 0 0 var(--zj-space-4);
  color: var(--zj-ink-600);
  font-size: var(--zj-fs-sm);
}

.rc-variants li {
  margin-top: 3px;
  line-height: 1.6;
}

.rc-hint {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
  margin-top: var(--zj-space-2);
}

.rc-done-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-600);
  margin: var(--zj-space-2) 0 0;
}

.rc-done-hint svg {
  width: 14px;
  height: 14px;
  stroke: var(--zj-acc-pass);
  fill: none;
  stroke-width: 2.2;
  stroke-linecap: round;
  stroke-linejoin: round;
  flex: none;
}

/* —— 样例折叠 —— */
.rc-fold {
  margin-top: var(--zj-space-3);
  border-top: 1px dashed var(--zj-line);
  border-bottom: none;
}

.rc-fold :deep(.el-collapse-item__header) {
  font-size: var(--zj-fs-sm);
  color: var(--zj-brass);
  font-weight: 600;
  border-bottom: none;
  background: transparent;
}

.rc-fold :deep(.el-collapse-item__wrap) {
  background: transparent;
  border-bottom: none;
}

.rc-fold :deep(.el-collapse-item__content) {
  padding-bottom: var(--zj-space-2);
}

.sample-tabs {
  display: flex;
  gap: 6px;
  margin-bottom: var(--zj-space-2);
}

.st-btn {
  border: 1px solid var(--zj-line);
  background: var(--zj-surface);
  color: var(--zj-ink-600);
  font-size: var(--zj-fs-xs);
  border-radius: 6px;
  padding: 3px 10px;
  cursor: pointer;
}

.st-btn.on.lv-pass {
  border-color: color-mix(in srgb, var(--zj-acc-pass) 55%, transparent);
  color: var(--zj-acc-pass);
  font-weight: 700;
}

.st-btn.on.lv-good {
  border-color: color-mix(in srgb, var(--zj-brass) 55%, transparent);
  color: var(--zj-brass);
  font-weight: 700;
}

.st-btn.on.lv-excellent {
  border-color: color-mix(in srgb, var(--zj-primary) 55%, transparent);
  color: var(--zj-primary);
  font-weight: 700;
}

.sample-body {
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-700);
  line-height: 1.8;
  margin: 0;
  white-space: pre-wrap;
}

/* —— 元信息与判定入口 —— */
.rc-meta {
  display: flex;
  align-items: center;
  gap: var(--zj-space-3);
  margin-top: var(--zj-space-3);
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.rc-judge {
  border: none;
  background: none;
  padding: 0;
  font-size: var(--zj-fs-xs);
  font-weight: 700;
  color: var(--zj-brass);
  cursor: pointer;
}

@media (hover: hover) {
  .rc-judge:hover {
    color: var(--zj-copper);
  }
}

.rc-actions {
  display: flex;
  gap: var(--zj-space-3);
  margin-top: var(--zj-space-3);
}

.rc-actions .el-button {
  flex: 1;
}

/* —— 随机抽取 —— */
.domain-chips {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: var(--zj-space-4);
}

.dc-chip {
  border: 1px solid var(--zj-line);
  background: var(--zj-surface);
  color: var(--zj-ink-600);
  font-size: var(--zj-fs-xs);
  border-radius: 999px;
  padding: 4px 12px;
  cursor: pointer;
  transition: border-color var(--zj-dur-fast) var(--zj-ease-out), color var(--zj-dur-fast) var(--zj-ease-out);
}

@media (hover: hover) {
  .dc-chip:hover {
    border-color: var(--zj-ink-500);
    color: var(--zj-ink-900);
  }
}

.dc-chip.on {
  border-color: var(--zj-brass);
  color: var(--zj-brass);
  background: var(--zj-brass-soft);
  font-weight: 600;
}

.draw-stage {
  display: flex;
  justify-content: center;
  padding: var(--zj-space-7) 0;
}

.draw-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--zj-space-2);
  border: 1.5px dashed var(--zj-brass);
  background: var(--zj-brass-soft);
  border-radius: 20px;
  padding: var(--zj-space-6) var(--zj-space-7);
  cursor: pointer;
  transition: transform var(--zj-dur-fast) var(--zj-ease-out), box-shadow var(--zj-dur-fast) var(--zj-ease-out);
}

@media (hover: hover) {
  .draw-btn:hover:not(:disabled) {
    transform: translateY(-2px);
    box-shadow: var(--zj-shadow);
  }
}

.draw-btn:disabled {
  opacity: 0.6;
  cursor: default;
}

.db-ico svg {
  width: 30px;
  height: 30px;
  stroke: var(--zj-brass);
  fill: none;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.draw-btn b {
  font-size: var(--zj-fs-md);
  color: var(--zj-brass);
}

.draw-btn small {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

/* 抽中卡入场（轻量翻入，prefers-reduced-motion 时禁用） */
.draw-card {
  animation: flip-in var(--zj-dur-base, 240ms) var(--zj-ease-out);
}

@keyframes flip-in {
  from {
    transform: perspective(600px) rotateX(8deg);
    opacity: 0;
  }
  to {
    transform: none;
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .draw-card {
    animation: none;
  }
}

.dc-verdict {
  margin: var(--zj-space-3) 0 0;
  font-size: var(--zj-fs-sm);
  font-weight: 600;
}

.dc-verdict.ok {
  color: var(--zj-acc-pass);
}

.dc-verdict.no {
  color: var(--zj-acc-weak);
}

/* —— 空态 / 全部完成 —— */
.gate {
  padding: var(--zj-space-7) var(--zj-space-5);
  text-align: center;
}

.gate-ico {
  width: 62px;
  height: 62px;
  border-radius: 18px;
  background: var(--zj-surface-sunken);
  border: 1px solid var(--zj-line);
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto var(--zj-space-4);
}

.gate-ico svg {
  width: 28px;
  height: 28px;
  stroke: var(--zj-ink-600);
  fill: none;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.gate h3 {
  margin: 0;
  font-size: var(--zj-fs-md);
  font-weight: 650;
  color: var(--zj-ink-900);
}

.gate p {
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-600);
  line-height: 1.8;
  margin: var(--zj-space-2) auto 0;
  max-width: 300px;
}

.gate-cta {
  margin-top: var(--zj-space-5);
  min-width: 200px;
}

.gate-cta-row {
  margin-top: var(--zj-space-4);
  display: flex;
  justify-content: center;
  gap: var(--zj-space-3);
}

.alldone {
  padding: var(--zj-space-5);
  text-align: center;
}

.alldone b {
  font-size: var(--zj-fs-md);
  color: var(--zj-ink-900);
}

.alldone p {
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-600);
  margin: var(--zj-space-2) 0 0;
}

/* —— PC（≥1024px）：清单限宽、抽取居中限宽 —— */
@media (min-width: 1024px) {
  .review-page :deep(.zj-page-head) {
    margin-bottom: var(--zj-space-4);
  }

  .rev-card {
    max-width: 720px;
  }

  .form-switch {
    max-width: 320px;
  }

  .draw-stage,
  .draw-card {
    max-width: 640px;
  }
}
</style>
