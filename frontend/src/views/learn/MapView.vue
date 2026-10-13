<script setup lang="ts">
// 学习路径（核心模块定义 4.2，原型屏 01）：后端实时推导的阶段/单元视图（不落表）。
// 三态：无当前档案 / 未定稿 → 引导去建档；正常 → 基础阶段置顶 + 领域阶段 + 下一步 + 放弃清单。
// 文案全按 6.5 命名表（学习路径/学习阶段/学习单元），路由与组件名保留 /map 与 MapView。
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getPath, restoreKp, SIGNAL_LABELS } from '@/api/learn'
import type { LearnPathView, PathKpView, PathUnitView } from '@/api/types'
import AcceptanceRuler from '@/components/learn/AcceptanceRuler.vue'
import { useConstantsStore } from '@/stores/constants'

const router = useRouter()
const constants = useConstantsStore()

const loading = ref(false)
const path = ref<LearnPathView | null>(null)
/** 未定稿空态（后端 400 LEARN_PROFILE_NOT_FINALIZED）：区分"没档案"与"没内容"两种引导 */
const notFinalized = ref(false)

/** 展开的阶段 key 集合（默认收起非首阶段；基础阶段与首个领域阶段默认展开） */
const expanded = ref<Set<string>>(new Set())

const targetLevelName = computed(() =>
  path.value?.targetLevel ? constants.name('target_level', path.value.targetLevel) : ''
)

const summary = computed(() => path.value?.summary ?? null)

/** 三色拼接条占比（验收/待复验/弱点/未学） */
const bars = computed(() => {
  const s = summary.value
  if (!s || s.total === 0) return { pass: 0, quick: 0, weak: 0 }
  return {
    pass: (s.passCount / s.total) * 100,
    quick: (s.quickCount / s.total) * 100,
    weak: (s.weakCount / s.total) * 100
  }
})

onMounted(() => void boot())

async function boot() {
  loading.value = true
  notFinalized.value = false
  try {
    const view = await getPath()
    path.value = view
    // 默认展开：基础阶段 + 首个领域阶段（让"下一步"所在阶段可见）
    const first = view.stages.slice(0, 2).map((stage) => stage.key)
    expanded.value = new Set(first)
  } catch (error) {
    const err = error as { code?: string; message?: string }
    if (err.code === 'LEARN_PROFILE_NOT_FINALIZED') {
      notFinalized.value = true
      path.value = null
    } else {
      ElMessage.error(err.message ?? '加载学习路径失败')
    }
  } finally {
    loading.value = false
  }
}

function toggleStage(key: string) {
  const next = new Set(expanded.value)
  if (next.has(key)) {
    next.delete(key)
  } else {
    next.add(key)
  }
  expanded.value = next
}

function isExpanded(key: string) {
  return expanded.value.has(key)
}

/** 单元行的刻度格：锁定单元整排灰虚；其余按每题状态（abandoned 已移出单元不会出现） */
function unitTicks(unit: PathUnitView): ('lock' | PathKpView['state'])[] {
  if (unit.locked) {
    return unit.kps.map(() => 'lock' as const)
  }
  return unit.kps.map((kp) => kp.state)
}

function goUnit(topicId: number) {
  router.push(`/unit/${topicId}`)
}

function goNext() {
  const nextId = summary.value?.nextTopicId
  if (nextId != null) {
    router.push(`/unit/${nextId}`)
  }
}

function goProfile() {
  router.push('/profile')
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
  <div class="map-page" v-loading="loading">
    <!-- 未定稿 / 无档案 空态（原型屏 08 上半） -->
    <template v-if="notFinalized">
      <div class="gate">
        <div class="gate-ico" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="M4 19V6a2 2 0 0 1 2-2h9l5 5v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2Z" /><path d="M14 4v5h5M9 12.5l2 2 4-4" /></svg>
        </div>
        <h3>档案还没定稿</h3>
        <p>学习路径要按你的技能条和目标职级来组织。档案定稿后才能生成——这一步只做一次，之后每天打开就有该学的东西。</p>
        <ol class="gate-steps">
          <li><span class="gs-no">1</span>补全基础信息（年限与技术栈，30 秒）</li>
          <li><span class="gs-no">2</span>对话起草技能条并逐条拍板</li>
          <li><span class="gs-no">3</span>设目标职级 → 定稿</li>
        </ol>
        <el-button type="primary" class="gate-cta" @click="goProfile">去完成建档</el-button>
      </div>
    </template>

    <!-- 正常路径视图 -->
    <template v-else-if="path">
      <header class="zj-page-head">
        <h1>学习路径</h1>
        <p>按你说的（技能条）和你要的（目标职级）组织的</p>
      </header>

      <!-- 当前档案锚点 -->
      <div class="card profile-anchor">
        <span class="pa-flag" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="M4 19V6a2 2 0 0 1 2-2h9l5 5v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2Z" /><path d="M14 4v5h5" /></svg>
        </span>
        <div class="pa-body">
          <b>{{ path.profileName }}</b>
          <small>目标{{ targetLevelName || '—' }} · {{ path.stages.length }} 个阶段 · 共 {{ summary?.total ?? 0 }} 题</small>
          <div class="split-bar">
            <i class="pass" :style="{ width: `${bars.pass}%` }"></i>
            <i class="quick" :style="{ width: `${bars.quick}%` }"></i>
            <i class="weak" :style="{ width: `${bars.weak}%` }"></i>
          </div>
          <div class="split-l">
            <span>验收 {{ summary?.passCount ?? 0 }}</span>
            <span>待复验 {{ summary?.quickCount ?? 0 }}</span>
            <span>共 {{ summary?.total ?? 0 }}</span>
          </div>
        </div>
      </div>

      <!-- 下一步学哪 -->
      <template v-if="summary?.nextTopicId != null">
        <p class="zj-eyebrow sect-eyebrow">下一步学哪</p>
        <div class="card nextup" @click="goNext">
          <span class="nu-flag">NEXT · 建议先推进这个单元</span>
          <b>{{ summary.nextTopicName }}</b>
          <small>{{ summary.nextStageTitle }}</small>
          <div class="ruler-track"><i></i></div>
          <span class="nu-go">进入单元 ›</span>
        </div>
      </template>

      <!-- 学习阶段 -->
      <p class="zj-eyebrow sect-eyebrow">学习阶段</p>

      <div
        v-for="stage in path.stages"
        :key="stage.key"
        class="card stage-band"
        :class="{ open: isExpanded(stage.key) }"
      >
        <div class="sb-top" role="button" tabindex="0" @click="toggleStage(stage.key)" @keydown.enter.prevent="toggleStage(stage.key)">
          <b>
            <span class="sno" :class="{ base: stage.kind === 'baseline' }">
              {{ stage.kind === 'baseline' ? '基础' : stage.title.split(' ')[0] }}
            </span>
            {{ stage.kind === 'baseline' ? stage.title : stage.title.replace(/^\d+\s/, '') }}
          </b>
          <span class="sb-num">{{ stage.done }}<i>/{{ stage.total }}</i></span>
        </div>
        <div class="sb-sub">
          <span>{{ stage.kind === 'baseline' ? '打底 · 快速过一遍建立节奏' : `${stage.units.length} 个学习单元` }}</span>
          <em v-if="stage.kind === 'baseline'">职级必问集</em>
          <em v-else-if="stage.units.some((u) => u.signals.includes('claim'))">主攻领域</em>
          <em v-else-if="stage.units.some((u) => u.signals.includes('jd'))">JD 命中</em>
        </div>
        <div class="split-bar sb-bar">
          <i class="pass" :style="{ width: stage.total ? `${(stage.done / stage.total) * 100}%` : '0%' }"></i>
        </div>

        <div v-show="isExpanded(stage.key)" class="unit-list">
          <div v-for="unit in stage.units" :key="unit.topicId" class="unit-row" @click="goUnit(unit.topicId)">
            <div class="ur-body">
              <b>{{ unit.topicName }}</b>
              <div class="ur-tags">
                <span v-for="sig in unit.signals" :key="sig" class="tag-src" :class="sig">{{ SIGNAL_LABELS[sig] }}</span>
              </div>
            </div>
            <AcceptanceRuler class="ur-ruler" :ticks="unitTicks(unit)" size="sm" />
            <span class="ur-num">{{ unit.done }}/{{ unit.total }}</span>
          </div>
        </div>
      </div>

      <!-- 放弃清单 -->
      <template v-if="path.abandoned.length > 0">
        <p class="zj-eyebrow sect-eyebrow">放弃清单 · {{ path.abandoned.length }} 条</p>
        <div v-for="item in path.abandoned" :key="item.kpId" class="card abandon-row">
          <div class="ab-body">
            <b>{{ item.title }}</b>
            <small>{{ item.reason ? `放弃原因：${item.reason}` : '未填原因' }}</small>
          </div>
          <el-button size="small" plain @click="onRestore(item.kpId)">恢复</el-button>
        </div>
      </template>

      <div class="regen-row">
        <el-button plain @click="goProfile">技能条有变？重新生成路径</el-button>
      </div>
    </template>

    <!-- 并集为空（有档案但路径无内容，原型屏 08 下半） -->
    <template v-else-if="!loading">
      <header class="zj-page-head">
        <h1>学习路径</h1>
        <p>按你说的（技能条）和你要的（目标职级）组织的</p>
      </header>
      <div class="gate">
        <div class="gate-ico" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="M3 6.5 9 4l6 2.5L21 4v13.5L15 20l-6-2.5L3 20Z" /><path d="M9 4v13.5M15 6.5V20" /></svg>
        </div>
        <h3>路径里暂时没有内容</h3>
        <p>你的技能条、目标职级基线与 JD 都没有命中已上线的题目。先补一条技能条，或把 JD 写具体一点，路径就有了。</p>
        <el-button plain class="gate-cta" @click="goProfile">回档案补充技能条</el-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.map-page {
  padding-bottom: var(--zj-space-6);
}

/* —— 档案锚点 —— */
.profile-anchor {
  margin-top: var(--zj-space-2);
  padding: var(--zj-space-3) var(--zj-space-4);
  display: flex;
  align-items: center;
  gap: var(--zj-space-3);
}

.pa-flag {
  flex: none;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: color-mix(in srgb, var(--zj-ink-900) 8%, transparent);
  display: flex;
  align-items: center;
  justify-content: center;
}

.pa-flag svg {
  width: 17px;
  height: 17px;
  stroke: var(--zj-ink-900);
  fill: none;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.pa-body {
  flex: 1;
  min-width: 0;
}

.pa-body b {
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-900);
}

.pa-body small {
  display: block;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
  margin-top: 1px;
}

/* —— 三色拼接条 —— */
.split-bar {
  display: flex;
  height: 7px;
  border-radius: 4px;
  overflow: hidden;
  background: var(--zj-acc-none);
  margin-top: var(--zj-space-3);
}

.split-bar i {
  display: block;
  height: 100%;
}

.split-bar i.pass {
  background: var(--zj-acc-pass);
}

.split-bar i.quick {
  background: var(--zj-acc-quick);
}

.split-bar i.weak {
  background: var(--zj-acc-weak);
}

.split-l {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: var(--zj-ink-500);
  margin-top: 6px;
  font-variant-numeric: tabular-nums;
}

.sect-eyebrow {
  margin: var(--zj-space-5) 0 var(--zj-space-3);
}

/* —— 下一步卡（朱色左边线） —— */
.nextup {
  padding: var(--zj-space-3) var(--zj-space-4);
  border-left: 3px solid var(--zj-primary);
  cursor: pointer;
  position: relative;
  transition: box-shadow var(--zj-dur-fast) var(--zj-ease-out);
}

.nextup .nu-flag {
  font-size: 9.5px;
  letter-spacing: 0.14em;
  color: var(--zj-primary);
  font-weight: 600;
}

.nextup b {
  display: block;
  font-size: var(--zj-fs-md);
  font-weight: 650;
  margin: 5px 0 3px;
  color: var(--zj-ink-900);
}

.nextup small {
  display: block;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.ruler-track {
  margin-top: var(--zj-space-3);
  height: 6px;
  border-radius: 3px;
  background: var(--zj-acc-none);
  overflow: hidden;
}

.nu-go {
  position: absolute;
  right: var(--zj-space-4);
  bottom: var(--zj-space-3);
  font-size: var(--zj-fs-xs);
  font-weight: 700;
  color: var(--zj-primary);
}

@media (hover: hover) {
  .nextup:hover {
    box-shadow: var(--zj-shadow);
  }
}

/* —— 阶段带 —— */
.stage-band {
  padding: var(--zj-space-3) var(--zj-space-4);
  margin-bottom: var(--zj-space-3);
}

.sb-top {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  cursor: pointer;
}

.sb-top b {
  font-size: var(--zj-fs-base);
  font-weight: 650;
  display: flex;
  align-items: center;
  gap: var(--zj-space-2);
  color: var(--zj-ink-900);
}

.sno {
  font-size: 10px;
  font-weight: 500;
  color: var(--zj-surface);
  background: var(--zj-ink-900);
  border-radius: 5px;
  padding: 1.5px 6px;
  font-variant-numeric: tabular-nums;
}

.sno.base {
  background: var(--zj-brass);
}

.sb-num {
  font-size: var(--zj-fs-sm);
  font-weight: 700;
  color: var(--zj-ink-900);
  font-variant-numeric: tabular-nums;
}

.sb-num i {
  font-style: normal;
  font-size: 10px;
  color: var(--zj-ink-500);
  font-weight: 400;
}

.sb-sub {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 4px;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.sb-sub em {
  font-style: normal;
  color: var(--zj-brass);
}

.sb-bar {
  margin-top: var(--zj-space-2);
}

.unit-list {
  margin-top: var(--zj-space-2);
}

.unit-row {
  display: flex;
  align-items: center;
  gap: var(--zj-space-3);
  padding: var(--zj-space-2) 0;
  border-top: 1px dashed var(--zj-line);
  cursor: pointer;
}

.unit-row:first-child {
  border-top: 1px solid var(--zj-line-soft);
  margin-top: var(--zj-space-2);
}

.ur-body {
  flex: 1;
  min-width: 0;
}

.ur-body b {
  font-size: var(--zj-fs-sm);
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--zj-ink-900);
}

.ur-tags {
  display: flex;
  gap: 5px;
  margin-top: 4px;
  flex-wrap: wrap;
}

.tag-src {
  font-size: 9.5px;
  border-radius: 5px;
  padding: 1px 6px;
  border: 1px solid var(--zj-line);
  color: var(--zj-ink-600);
}

.tag-src.claim {
  border-color: color-mix(in srgb, var(--zj-primary) 40%, transparent);
  color: var(--zj-primary);
  background: var(--zj-primary-soft);
}

.tag-src.baseline {
  border-color: color-mix(in srgb, var(--zj-brass) 40%, transparent);
  color: var(--zj-brass);
  background: var(--zj-brass-soft);
}

.tag-src.jd {
  border-color: color-mix(in srgb, var(--zj-copper) 45%, transparent);
  color: var(--zj-copper);
}

.ur-ruler {
  flex: none;
  width: 64px;
  height: 14px;
}

.ur-num {
  flex: none;
  font-size: 10.5px;
  color: var(--zj-ink-500);
  width: 34px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

/* —— 放弃清单 —— */
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

.regen-row {
  margin-top: var(--zj-space-5);
  display: flex;
  justify-content: center;
}

/* —— 空态（原型屏 08） —— */
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

.gate-steps {
  margin: var(--zj-space-5) 0 0;
  padding: 0;
  list-style: none;
  text-align: left;
  display: flex;
  flex-direction: column;
  gap: var(--zj-space-2);
  max-width: 360px;
  margin-left: auto;
  margin-right: auto;
}

.gate-steps li {
  display: flex;
  gap: var(--zj-space-3);
  align-items: center;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-700);
  background: var(--zj-surface);
  border: 1px solid var(--zj-line-soft);
  border-radius: var(--zj-radius);
  padding: var(--zj-space-3);
}

.gs-no {
  flex: none;
  width: 22px;
  height: 22px;
  border-radius: 999px;
  border: 1.5px solid var(--zj-brass);
  color: var(--zj-brass);
  font-size: var(--zj-fs-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  font-variant-numeric: tabular-nums;
}

.gate-cta {
  margin-top: var(--zj-space-5);
  min-width: 200px;
}

/* —— 桌面（≥1024px）：路径与当前单元两栏（原型屏 09） —— */
@media (min-width: 1024px) {
  .map-page {
    padding-bottom: var(--zj-space-7);
  }
}
</style>
