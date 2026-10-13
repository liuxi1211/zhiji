<script setup lang="ts">
// 学习单元（核心模块定义 4.2，原型屏 02）：单元内知识点列表 + 单元大验收尺 + 放弃动作。
// 状态点五态与单元尺同源（learn_state）；豁免卡演示 level_rules.required 分范围（超出目标职级仍可选学）。
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { abandonKp, getPath, restoreKp, SIGNAL_LABELS } from '@/api/learn'
import type { LearnPathView, PathKpView, PathUnitView } from '@/api/types'
import AcceptanceRuler from '@/components/learn/AcceptanceRuler.vue'
import { useConstantsStore } from '@/stores/constants'

const route = useRoute()
const router = useRouter()
const constants = useConstantsStore()

const topicId = computed(() => Number(route.params.topicId))
const loading = ref(false)
const path = ref<LearnPathView | null>(null)
const unit = ref<PathUnitView | null>(null)
const stageTitle = ref('')

const DIFF_LABELS: Record<string, string> = {
  junior: '初级',
  mid: '中级',
  senior: '高级',
  expert: '资深'
}
const DIFF_CLASS: Record<string, string> = {
  junior: 'd-junior',
  mid: 'd-mid',
  senior: 'd-senior',
  expert: 'd-expert'
}

/** 单元刻度尺：按 kp 顺序逐格给状态 */
const ticks = computed<('lock' | PathKpView['state'])[]>(() => {
  const u = unit.value
  if (!u) return []
  if (u.locked) return u.kps.map(() => 'lock' as const)
  return u.kps.map((kp) => kp.state)
})

const counts = computed(() => {
  const u = unit.value
  const base = { pass: 0, quick: 0, weak: 0, none: 0 }
  if (!u) return base
  u.kps.forEach((kp) => {
    if (kp.state === 'pass') base.pass++
    else if (kp.state === 'quick') base.quick++
    else if (kp.state === 'weak') base.weak++
    else base.none++
  })
  return base
})

const targetLevelName = computed(() =>
  path.value?.targetLevel ? constants.name('target_level', path.value.targetLevel) : ''
)

/** 单元内每题：是否需要"超出目标职级"角标（难度高于目标职级即提示可选学） */
const TARGET_ORDER: Record<string, number> = { junior: 1, mid: 2, senior: 3, expert: 4 }
function isBeyondTarget(difficulty: string): boolean {
  const target = path.value?.targetLevel
  if (!target) return false
  return (TARGET_ORDER[difficulty] ?? 0) > (TARGET_ORDER[target] ?? 0)
}

onMounted(() => void boot())

async function boot() {
  loading.value = true
  try {
    const view = await getPath()
    path.value = view
    const found = locateUnit(view)
    if (!found) {
      ElMessage.warning('该学习单元已不在当前路径中')
      router.replace('/map')
      return
    }
    unit.value = found.unit
    stageTitle.value = found.stageTitle
  } catch (error) {
    const err = error as { code?: string; message?: string }
    if (err.code === 'LEARN_PROFILE_NOT_FINALIZED') {
      router.replace('/map')
      return
    }
    ElMessage.error(err.message ?? '加载学习单元失败')
  } finally {
    loading.value = false
  }
}

function locateUnit(view: LearnPathView) {
  for (const stage of view.stages) {
    const unit = stage.units.find((u) => u.topicId === topicId.value)
    if (unit) return { unit, stageTitle: stage.title }
  }
  return null
}

function goPractice(kpId: number) {
  router.push(`/practice/${kpId}`)
}

async function onAbandon(kpId: number) {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(
      '放弃后该题移出学习单元、进入放弃清单，可随时恢复。原因选填。',
      '放弃这道题',
      {
        confirmButtonText: '放弃',
        cancelButtonText: '取消',
        inputPlaceholder: '如：当前岗位用不到',
        inputValidator: () => true
      }
    )
    reason = value ?? ''
  } catch {
    return
  }
  try {
    await abandonKp(kpId, reason)
    ElMessage.success('已移入放弃清单')
    await boot()
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '操作失败')
  }
}

async function onRestore(kpId: number) {
  try {
    await restoreKp(kpId)
    ElMessage.success('已恢复到学习单元')
    await boot()
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '恢复失败')
  }
}
</script>

<template>
  <div class="unit-page" v-loading="loading">
    <template v-if="unit">
      <div class="uu-top">
        <el-button text size="small" @click="router.push('/map')">‹ 学习路径</el-button>
        <span>{{ stageTitle }}</span>
      </div>

      <header class="uu-head">
        <h1>{{ unit.topicName }}</h1>
        <div class="uu-sub">
          <span>{{ unit.total }} 道题</span>
          <span v-for="sig in unit.signals" :key="sig" class="chip tiny" :class="sig">{{ SIGNAL_LABELS[sig] }}</span>
          <span v-if="unit.locked" class="chip tiny lock">前置未达成 · 锁定</span>
        </div>
      </header>

      <!-- 单元大验收尺 -->
      <div class="card uu-gauge">
        <div class="ug-top">
          <span>验收刻度 · 这个单元学到哪了</span>
          <b>{{ unit.done }} / {{ unit.total }}</b>
        </div>
        <AcceptanceRuler :ticks="ticks" size="lg" />
        <div class="ug-legend">
          <span><i class="dot pass"></i>验收 {{ counts.pass }}</span>
          <span><i class="dot quick"></i>待复验 {{ counts.quick }}</span>
          <span><i class="dot weak"></i>弱点 {{ counts.weak }}</span>
          <span><i class="dot none"></i>未学 {{ counts.none }}</span>
        </div>
      </div>

      <p class="zj-eyebrow sect-eyebrow">知识点 · 按难度与频率排序</p>

      <div class="kp-list">
        <div
          v-for="kp in unit.kps"
          :key="kp.id"
          class="kp-row"
          :class="{ locked: kp.locked, exempt: isBeyondTarget(kp.difficulty) }"
          @click="!kp.locked && goPractice(kp.id)"
        >
          <span class="mt" :class="kp.state" aria-hidden="true"></span>
          <div class="kt">
            <b>{{ kp.title }}</b>
            <small>
              <span class="mono" :class="{ 'freq-hi': kp.freqTotal >= 60 }">频率 {{ kp.freqTotal }}</span>
              <span v-if="kp.followupLayers > 0">追问 {{ kp.followupLayers }} 层</span>
              <span v-if="kp.docCount > 0">资料 {{ kp.docCount }} 篇</span>
              <span v-if="kp.state === 'quick'">速览通过 · 已进抽查范围</span>
              <span v-else-if="kp.state === 'weak'">自评「我不会」· 弱点加权</span>
              <span v-else-if="kp.state === 'pass'">判定通过</span>
            </small>
            <div v-if="kp.locked" class="lock-note">
              <svg viewBox="0 0 24 24"><rect x="5" y="11" width="14" height="9" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></svg>
              前置知识点未完成，先推进前置再练这道
            </div>
            <span v-if="isBeyondTarget(kp.difficulty) && !kp.locked" class="exempt-badge">
              <b>{{ DIFF_LABELS[kp.difficulty] }} · 超出你的目标（{{ targetLevelName }}）</b> · 不要求 · 选学不计档
            </span>
          </div>
          <span class="dtag" :class="DIFF_CLASS[kp.difficulty]">{{ DIFF_LABELS[kp.difficulty] }}</span>
          <el-button
            v-if="!kp.locked"
            class="ab-btn"
            text
            size="small"
            @click.stop="onAbandon(kp.id)"
          >
            放弃
          </el-button>
        </div>
      </div>

      <!-- 放弃清单（本单元内已放弃的题可恢复） -->
      <template v-if="path && path.abandoned.length > 0">
        <p class="zj-eyebrow sect-eyebrow">放弃清单 · {{ path.abandoned.length }} 条</p>
        <div v-for="item in path.abandoned" :key="item.kpId" class="card abandon-row">
          <div class="ab-body">
            <b>{{ item.title }}</b>
            <small>{{ item.reason ? `放弃原因：${item.reason}` : '未填原因' }}</small>
          </div>
          <el-button size="small" plain @click="onRestore(item.kpId)">恢复</el-button>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.unit-page {
  padding-bottom: var(--zj-space-6);
}

.uu-top {
  display: flex;
  align-items: center;
  gap: var(--zj-space-2);
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
  margin-bottom: var(--zj-space-3);
}

.uu-head h1 {
  margin: 0;
  font-family: var(--serif, inherit);
  font-size: var(--zj-fs-lg);
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--zj-ink-900);
}

.uu-sub {
  display: flex;
  gap: var(--zj-space-2);
  align-items: center;
  margin-top: var(--zj-space-2);
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
  flex-wrap: wrap;
}

.chip.tiny {
  font-size: 9.5px;
  padding: 1.5px 8px;
  border-radius: 999px;
  border: 1px solid var(--zj-line);
  color: var(--zj-ink-600);
}

.chip.tiny.claim {
  border-color: color-mix(in srgb, var(--zj-primary) 40%, transparent);
  color: var(--zj-primary);
  background: var(--zj-primary-soft);
}

.chip.tiny.baseline {
  border-color: color-mix(in srgb, var(--zj-brass) 40%, transparent);
  color: var(--zj-brass);
  background: var(--zj-brass-soft);
}

.chip.tiny.jd {
  border-color: color-mix(in srgb, var(--zj-copper) 45%, transparent);
  color: var(--zj-copper);
}

.chip.tiny.lock {
  border-color: color-mix(in srgb, var(--zj-brass) 45%, transparent);
  color: var(--zj-brass);
}

.uu-gauge {
  margin-top: var(--zj-space-4);
  padding: var(--zj-space-3) var(--zj-space-4);
}

.ug-top {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.ug-top b {
  color: var(--zj-ink-900);
  font-size: var(--zj-fs-sm);
  font-variant-numeric: tabular-nums;
}

.uu-gauge :deep(.ruler-lg) {
  margin: var(--zj-space-3) 0;
}

.ug-legend {
  display: flex;
  gap: var(--zj-space-4);
  flex-wrap: wrap;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-700);
}

.ug-legend span {
  display: flex;
  align-items: center;
  gap: 6px;
}

.dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  display: inline-block;
}

.dot.pass {
  background: var(--zj-acc-pass);
}

.dot.quick {
  background: transparent;
  border: 1.6px solid var(--zj-acc-quick);
}

.dot.weak {
  background: var(--zj-acc-weak);
}

.dot.none {
  background: var(--zj-acc-none);
}

.sect-eyebrow {
  margin: var(--zj-space-5) 0 var(--zj-space-3);
}

.kp-list {
  background: var(--zj-surface);
  border: 1px solid var(--zj-line-soft);
  border-radius: var(--zj-radius-lg);
  padding: 0 var(--zj-space-2);
}

.kp-row {
  display: flex;
  gap: var(--zj-space-3);
  align-items: flex-start;
  padding: var(--zj-space-3) var(--zj-space-2);
  border-bottom: 1px solid var(--zj-line-soft);
  cursor: pointer;
}

.kp-row:last-child {
  border-bottom: none;
}

.kp-row.locked {
  opacity: 0.55;
  cursor: not-allowed;
}

.kp-row.locked .kt b {
  color: var(--zj-ink-500);
}

.mt {
  flex: none;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-top: 5px;
  background: var(--zj-acc-none);
}

.mt.pass {
  background: var(--zj-acc-pass);
}

.mt.quick {
  background: transparent;
  border: 1.6px solid var(--zj-acc-quick);
}

.mt.weak {
  background: var(--zj-acc-weak);
}

.mt.learning {
  background: transparent;
  border: 1.6px solid var(--zj-ink-400);
}

.kt {
  flex: 1;
  min-width: 0;
}

.kt b {
  font-weight: 600;
  font-size: var(--zj-fs-base);
  line-height: 1.5;
  display: block;
  color: var(--zj-ink-900);
}

.kt small {
  display: flex;
  gap: var(--zj-space-2);
  flex-wrap: wrap;
  margin-top: 4px;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
  align-items: center;
}

.kt small .freq-hi {
  color: var(--zj-primary);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.lock-note {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 10px;
  color: var(--zj-brass);
  margin-top: 5px;
}

.lock-note svg {
  width: 12px;
  height: 12px;
  stroke: currentColor;
  fill: none;
  stroke-width: 1.9;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.exempt-badge {
  display: inline-block;
  font-size: 10px;
  color: var(--zj-ink-500);
  border: 1px dashed var(--zj-line);
  border-radius: 7px;
  padding: 2px 8px;
  margin-top: 5px;
}

.exempt-badge b {
  color: var(--zj-ink-700);
  font-weight: 600;
  font-size: 10px;
  display: inline;
}

.dtag {
  flex: none;
  font-size: 10px;
  border-radius: 6px;
  padding: 2px 7px;
  margin-top: 2px;
  border: 1px solid;
  background: var(--zj-surface);
  white-space: nowrap;
}

.d-junior {
  color: var(--zj-diff-2);
  border-color: var(--zj-diff-2);
}

.d-mid {
  color: var(--zj-diff-3);
  border-color: var(--zj-diff-3);
}

.d-senior {
  color: var(--zj-diff-4);
  border-color: var(--zj-diff-4);
}

.d-expert {
  color: var(--zj-diff-5);
  border-color: var(--zj-diff-5);
}

.abandon-row {
  display: flex;
  align-items: center;
  gap: var(--zj-space-3);
  padding: var(--zj-space-3) var(--zj-space-4);
  margin-bottom: var(--zj-space-2);
}

.ab-body {
  flex: 1;
  min-width: 0;
}

.ab-body b {
  font-size: var(--zj-fs-sm);
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--zj-ink-900);
}

.ab-body small {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.danger-row {
  margin-top: var(--zj-space-4);
  display: flex;
  justify-content: center;
}
</style>
