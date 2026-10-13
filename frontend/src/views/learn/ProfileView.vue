<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  addClaim,
  confirmClaim,
  createProfile,
  deleteClaim,
  deleteProfile,
  exportMarkdown,
  finalizeProfile,
  getLevelSuggestion,
  getProfile,
  hasPlaceholder,
  mapAnchors,
  unfinalizeProfile,
  updateClaim,
  updateProfile
} from '@/api/profile'
import type { KpCandidate, ProfileDetail } from '@/api/types'
import { useConstantsStore } from '@/stores/constants'
import { useProfileStore } from '@/stores/profile'
import ChatPanel from '@/components/profile/ChatPanel.vue'
import ClaimCard from '@/components/profile/ClaimCard.vue'
import KpAnchorPicker from '@/components/profile/KpAnchorPicker.vue'

const constants = useConstantsStore()
const profileStore = useProfileStore()

const loading = ref(false)
/** 当前工作台正在编辑的档案；null 表示用户还没有任何档案 */
const detail = ref<ProfileDetail | null>(null)
const creatingNew = ref(false)

/** 标题处就地重命名（取代「新建档案」弹窗里的档案名字段） */
const titleEditing = ref(false)
const titleDraft = ref('')
const titleInputRef = ref<HTMLInputElement | null>(null)

const planForm = reactive({
  name: '',
  resumeText: '',
  years: null as number | null,
  position: '',
  jdText: '',
  techStack: '',
  goodAt: '',
  targetLevel: '',
  planWeeks: null as number | null,
  dailyMinutes: null as number | null
})
const planSaving = ref(false)
const chatPanelRef = ref<InstanceType<typeof ChatPanel> | null>(null)

/** 引导式建档：脊上四个节点，一次只显示一个分区 */
interface StepMeta {
  key: string
  label: string
  optional?: boolean
}

const STEP_META: StepMeta[] = [
  { key: 'plan', label: '基础信息' },
  { key: 'chat', label: '对话起草' },
  { key: 'claims', label: '技能条' },
  { key: 'export', label: '定稿导出' }
]
const activeStep = ref(1)

function goStep(step: number) {
  if (step < 1 || step > STEP_META.length) return
  activeStep.value = step
  if (step === 2) chatPanelRef.value?.scrollToBottom()
}

const claimSaving = ref(false)
const levelSaving = ref(false)
const mapping = ref(false)
const exportBusy = ref(false)

const manualVisible = ref(false)
const manualPickerVisible = ref(false)
const manualForm = reactive({ title: '', wording: '', anchorIds: [] as number[] })
const manualTitles = reactive<Record<number, string>>({})

const isMobile = ref(false)
let mobileMql: MediaQueryList | null = null
const updateIsMobile = () => {
  isMobile.value = mobileMql?.matches ?? false
}

const levelOptions = computed(() => constants.items('target_level'))
const statusName = computed(() =>
  detail.value ? constants.name('profile_status', detail.value.status) : ''
)
const canCreate = computed(() => profileStore.items.length < 3)

/** 占位清单：wording 含【…】的技能条，定稿前必须逐条处理 */
const placeholderClaims = computed(
  () => detail.value?.claims.filter((claim) => hasPlaceholder(claim.wording)) ?? []
)
const placeholderCount = computed(() => placeholderClaims.value.length)

/** 流程第 1 步是否完成：年限与技术栈填了才允许进入对话（PRD v2 必填两项） */
const intakeComplete = computed(
  () => detail.value != null && detail.value.years != null && !!detail.value.techStack
)

/** 技术栈 chip 编辑（PRD v2：预置点选 + 自由输入，防预置清单遗漏） */
const STACK_PRESETS = [
  'Java',
  'Spring Boot',
  'MySQL',
  'Redis',
  'RocketMQ',
  'Kafka',
  'Elasticsearch',
  'Kubernetes',
  'Go'
]
const stackList = computed(() =>
  planForm.techStack
    .split(/[,，、;；]+/)
    .map((item) => item.trim())
    .filter(Boolean)
)
const stackDraft = ref('')
const missingPresets = computed(() =>
  STACK_PRESETS.filter((preset) => !stackList.value.includes(preset))
)

function addStack(tag: string) {
  const value = tag.trim()
  if (!value) return
  if (!stackList.value.includes(value)) {
    planForm.techStack = [...stackList.value, value].join(', ')
  }
  stackDraft.value = ''
}

function removeStack(tag: string) {
  planForm.techStack = stackList.value.filter((item) => item !== tag).join(', ')
}

const canFinalize = computed(
  () =>
    detail.value?.status === 'confirmed' &&
    !!detail.value?.targetLevel &&
    detail.value.claims.length > 0 &&
    placeholderCount.value === 0
)
const finalizeHint = computed(() => {
  if (!detail.value) return ''
  if (detail.value.status === 'finalized') return '档案已定稿，将参与学习路径生成'
  if (detail.value.claims.length === 0) return '先在对话里起草并采纳一版技能条'
  if (detail.value.status === 'draft') return '存在待拍板的技能条（手动添加的需拍板）'
  if (!detail.value.targetLevel) return '设定目标职级后才能定稿'
  if (placeholderCount.value > 0) return `还有 ${placeholderCount.value} 条技能条带【】占位，处理完才能定稿`
  return '技能条就绪，可以定稿'
})

const pendingAnchors = computed(
  () => detail.value?.claims.filter((claim) => claim.anchorStatus !== 'mapped').length ?? 0
)

/** 各节点完成状态：驱动脊上的 ✓ */
const stepDone = computed<boolean[]>(() => {
  const profile = detail.value
  if (!profile) return STEP_META.map(() => false)
  return [
    profile.years != null && !!profile.techStack,
    profile.claims.length > 0,
    profile.claims.length > 0 && placeholderCount.value === 0,
    profile.status === 'finalized'
  ]
})

onMounted(() => {
  mobileMql = window.matchMedia('(max-width: 767px)')
  updateIsMobile()
  mobileMql.addEventListener('change', updateIsMobile)
  void boot()
})

onUnmounted(() => {
  mobileMql?.removeEventListener('change', updateIsMobile)
})

/** 进入页面即直接落到某份档案的工作台，不存在"先看列表再进入"的一层 */
async function boot() {
  await refreshList()
  const target = profileStore.items.find((item) => item.active) ?? profileStore.items[0]
  if (target) {
    await openProfile(target.id)
  } else {
    detail.value = null
  }
}

async function refreshList() {
  loading.value = true
  try {
    await profileStore.refresh()
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '加载档案失败')
  } finally {
    loading.value = false
  }
}

async function openProfile(id: number) {
  loading.value = true
  try {
    const profile = await getProfile(id)
    detail.value = profile
    titleEditing.value = false
    fillPlanForm()
    // 引导式流程：默认落在第一个未完成的节点
    const intakeOk = profile.years != null && !!profile.techStack
    activeStep.value = !intakeOk ? 1 : profile.claims.length === 0 ? 2 : profile.status === 'finalized' ? 4 : 3
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '加载档案失败')
  } finally {
    loading.value = false
  }
}

/** 新建即进工作台：只建一个空壳，档案名在标题处就地改，其余字段全在第 1 步 */
async function createNewProfile() {
  if (creatingNew.value) return
  creatingNew.value = true
  try {
    const created = await createProfile({ name: '未命名档案' })
    await refreshList()
    await openProfile(created.id)
    ElMessage.success('已新建档案，先给它起个名字')
    startTitleEdit(true)
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '新建失败')
  } finally {
    creatingNew.value = false
  }
}

function fillPlanForm() {
  const profile = detail.value
  if (!profile) return
  planForm.name = profile.name
  planForm.resumeText = profile.resumeText
  planForm.years = profile.years
  planForm.position = profile.position
  planForm.jdText = profile.jdText
  planForm.techStack = profile.techStack
  planForm.goodAt = profile.goodAt
  planForm.targetLevel = profile.targetLevel ?? ''
  planForm.planWeeks = profile.planWeeks
  planForm.dailyMinutes = profile.dailyMinutes
}

/** 统一的整表更新载荷（updateProfile 为整表替换语义） */
function payload(nameOverride?: string) {
  return {
    name: (nameOverride ?? planForm.name).trim(),
    resumeText: planForm.resumeText,
    years: planForm.years,
    position: planForm.position.trim(),
    jdText: planForm.jdText,
    techStack: planForm.techStack.trim(),
    goodAt: planForm.goodAt.trim(),
    targetLevel: planForm.targetLevel || null,
    planWeeks: planForm.planWeeks,
    dailyMinutes: planForm.dailyMinutes
  }
}

async function reloadDetail() {
  if (!detail.value) return
  detail.value = await getProfile(detail.value.id)
}

// —— 标题就地重命名 ——

function startTitleEdit(auto = false) {
  if (!detail.value) return
  titleDraft.value = detail.value.name
  titleEditing.value = true
  if (auto) void nextTick(() => titleInputRef.value?.focus())
}

async function saveTitle() {
  if (!detail.value || !titleEditing.value) return
  titleEditing.value = false
  const name = titleDraft.value.trim()
  if (!name || name === detail.value.name) {
    titleDraft.value = detail.value.name
    return
  }
  try {
    planForm.name = name
    await updateProfile(detail.value.id, payload(name))
    await reloadDetail()
    fillPlanForm()
    await refreshList()
    ElMessage.success('档案名已更新')
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '重命名失败')
    await reloadDetail()
    fillPlanForm()
  }
}

async function setActive(id: number) {
  try {
    await profileStore.setActive(id)
    if (detail.value?.id === id) {
      await reloadDetail()
    }
    ElMessage.success('已切换当前档案')
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '切换失败')
  }
}

async function onDeleteProfile(item: { id: number; name: string }) {
  try {
    await ElMessageBox.confirm(
      `「${item.name}」的技能条、项目素材、建档对话与学习记录将一并删除，不可恢复。`,
      '删除档案',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch {
    return
  }
  try {
    await deleteProfile(item.id)
    ElMessage.success('档案已删除')
    await refreshList()
    if (detail.value?.id === item.id) {
      const next = profileStore.items.find((it) => it.active) ?? profileStore.items[0]
      if (next) {
        await openProfile(next.id)
      } else {
        detail.value = null
      }
    }
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '删除失败')
  }
}

// 年限变化时预填职级缺省推测（用户未手选时）
watch(
  () => planForm.years,
  async (years) => {
    if (years == null || planForm.targetLevel) return
    try {
      const suggestion = await getLevelSuggestion(years)
      planForm.targetLevel = suggestion.level
    } catch {
      // 推测失败静默跳过，用户手选
    }
  }
)

/** 只保存，不跳转（跳转交给脊上的节点或第 1 步的「下一步」） */
async function savePlan(): Promise<boolean> {
  if (!detail.value || !planForm.name.trim()) {
    ElMessage.warning('档案名不能为空')
    return false
  }
  planSaving.value = true
  try {
    await updateProfile(detail.value.id, payload())
    await reloadDetail()
    fillPlanForm()
    await refreshList()
    ElMessage.success('基础信息已保存')
    return true
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '保存失败')
    return false
  } finally {
    planSaving.value = false
  }
}

async function savePlanAndNext() {
  if (await savePlan()) goStep(2)
}

/** 目标职级只服务定稿门槛（LLM 起草不用），放在第 4 步，选完即存 */
async function saveLevel(level: string) {
  if (!detail.value) return
  // 以已保存数据为基准提交，避免把第 1 步未保存的草稿一并带入
  fillPlanForm()
  planForm.targetLevel = level
  levelSaving.value = true
  try {
    await updateProfile(detail.value.id, payload())
    await reloadDetail()
    ElMessage.success('目标职级已保存')
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '保存失败')
    await reloadDetail()
    fillPlanForm()
  } finally {
    levelSaving.value = false
  }
}

// —— 技能条 ——

async function onAccepted() {
  await reloadDetail()
  goStep(3)
}

async function runMapping() {
  if (!detail.value || mapping.value) return
  mapping.value = true
  try {
    const result = await mapAnchors(detail.value.id)
    await reloadDetail()
    if (result.mapped + result.pending + result.zero === 0) {
      ElMessage.info('所有技能条都已锚定')
    } else {
      ElMessage.success(`锚定完成：${result.mapped} 条已锚定，${result.pending} 条待确认，${result.zero} 条未匹配`)
    }
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '锚定映射失败')
  } finally {
    mapping.value = false
  }
}

async function onConfirmClaim(claimId: number) {
  if (!detail.value) return
  try {
    await confirmClaim(detail.value.id, claimId)
    await reloadDetail()
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '操作失败')
  }
}

async function onDeleteClaim(claimId: number) {
  if (!detail.value) return
  const claim = detail.value.claims.find((item) => item.id === claimId)
  if (!claim) return
  const preview = claim.wording.length > 24 ? `${claim.wording.slice(0, 24)}…` : claim.wording
  try {
    await ElMessageBox.confirm(`「${preview}」删除后不可恢复。`, '删除技能条', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
  } catch {
    return
  }
  try {
    await deleteClaim(detail.value.id, claimId)
    await reloadDetail()
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '操作失败')
  }
}

async function onSaveClaim(
  claimId: number,
  payload: { title: string; wording: string; anchorIds: number[] | null }
) {
  if (!detail.value) return
  claimSaving.value = true
  try {
    await updateClaim(detail.value.id, claimId, payload)
    await reloadDetail()
    ElMessage.success('已保存')
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '保存失败')
  } finally {
    claimSaving.value = false
  }
}

function openManual() {
  manualForm.title = ''
  manualForm.wording = ''
  manualForm.anchorIds = []
  Object.keys(manualTitles).forEach((key) => delete manualTitles[Number(key)])
  manualVisible.value = true
}

function onManualPick(candidate: KpCandidate) {
  if (!manualForm.anchorIds.includes(candidate.id)) {
    manualForm.anchorIds = [...manualForm.anchorIds, candidate.id]
    manualTitles[candidate.id] = candidate.title
  }
  manualPickerVisible.value = false
}

function removeManualAnchor(id: number) {
  manualForm.anchorIds = manualForm.anchorIds.filter((anchorId) => anchorId !== id)
}

async function submitManual() {
  if (!detail.value || !manualForm.wording.trim()) return
  claimSaving.value = true
  try {
    await addClaim(detail.value.id, {
      title: manualForm.title.trim(),
      wording: manualForm.wording.trim(),
      anchorIds: manualForm.anchorIds,
      source: 'manual'
    })
    manualVisible.value = false
    await reloadDetail()
    ElMessage.success('已添加，待拍板')
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '添加失败')
  } finally {
    claimSaving.value = false
  }
}

// —— 定稿与导出 ——

async function finalize() {
  if (!detail.value) return
  try {
    await finalizeProfile(detail.value.id)
    await reloadDetail()
    ElMessage.success('档案已定稿，别忘了执行锚定映射')
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '定稿失败')
  }
}

async function unfinalize() {
  if (!detail.value) return
  try {
    await unfinalizeProfile(detail.value.id)
    await reloadDetail()
    ElMessage.success('已撤销定稿，可继续编辑')
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '操作失败')
  }
}

async function copyMarkdown() {
  if (!detail.value) return
  exportBusy.value = true
  try {
    const markdown = await exportMarkdown(detail.value.id)
    await navigator.clipboard.writeText(markdown)
    ElMessage.success('已复制，可直接粘贴进简历')
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '导出失败')
  } finally {
    exportBusy.value = false
  }
}
</script>

<template>
  <div class="profile-page" v-loading="loading">
    <header class="zj-page-head">
      <h1>个人档案</h1>
      <p>每份档案是一条独立的求职赛道：简历、技能条与学习进度互相隔离，最多同时 3 份</p>
    </header>

    <!-- 档案切换器：替代原来独立的"档案列表页" -->
    <div class="profile-switcher" role="tablist" aria-label="我的档案">
      <button
        v-for="item in profileStore.items"
        :key="item.id"
        type="button"
        role="tab"
        class="pswitch"
        :class="{ active: item.id === detail?.id }"
        :aria-selected="item.id === detail?.id"
        @click="openProfile(item.id)"
      >
        <span class="pswitch-top">
          <span class="pswitch-name">{{ item.name }}</span>
          <span v-if="item.active" class="chip-current">当前</span>
        </span>
        <span class="pswitch-meta">
          {{ constants.name('profile_status', item.status) || item.status }} · 技能条 {{ item.claimCount }}
        </span>
      </button>

      <button
        v-if="canCreate"
        type="button"
        class="pswitch is-new"
        :disabled="creatingNew"
        @click="createNewProfile"
      >
        <span class="pswitch-plus" aria-hidden="true">＋</span>
        <span class="pswitch-new-text">新建档案</span>
      </button>
    </div>

    <!-- 工作台 -->
    <template v-if="detail">
      <header class="detail-head">
        <div class="head-row">
          <div class="head-title">
            <h1
              v-if="!titleEditing"
              class="title-text"
              role="button"
              tabindex="0"
              title="点击重命名"
              @click="startTitleEdit()"
              @keydown.enter.prevent="startTitleEdit()"
            >
              {{ detail.name }}
              <span class="edit-hint" aria-hidden="true">✎</span>
            </h1>
            <input
              v-else
              ref="titleInputRef"
              v-model="titleDraft"
              class="title-input"
              maxlength="40"
              placeholder="档案名，如：Java 后端 + ES 方向"
              @keydown.enter.prevent="saveTitle"
              @blur="saveTitle"
            />
            <p class="head-meta">
              <span class="status-chip" :data-finalized="detail.status === 'finalized'">
                {{ statusName }}
              </span>
              <template v-if="detail.targetLevel">
                目标{{ constants.name('target_level', detail.targetLevel) }}
              </template>
              <template v-if="detail.finalizedAt">定稿于 {{ detail.finalizedAt }}</template>
            </p>
          </div>
          <div class="head-actions">
            <el-button v-if="!detail.active" size="small" @click="setActive(detail.id)">
              设为当前档案
            </el-button>
            <el-button size="small" type="danger" plain @click="onDeleteProfile(detail)">删除</el-button>
          </div>
        </div>
        <p class="status-hint">{{ finalizeHint }}</p>
      </header>

      <div class="workspace">
        <!-- 脊：四个节点的竖向进度（桌面），移动端塌成横向条 -->
        <nav class="spine" aria-label="建档步骤">
          <button
            v-for="(step, index) in STEP_META"
            :key="step.key"
            type="button"
            class="spine-node"
            :class="{ done: stepDone[index], active: activeStep === index + 1, optional: step.optional }"
            :aria-current="activeStep === index + 1 ? 'step' : undefined"
            @click="goStep(index + 1)"
          >
            <span class="spine-dot" aria-hidden="true">{{ stepDone[index] ? '✓' : index + 1 }}</span>
            <span class="spine-label">{{ step.label }}</span>
          </button>
        </nav>

        <div class="workspace-body">
          <!-- ① 基础信息 -->
          <section v-show="activeStep === 1" class="block">
            <div class="block-head">
              <h2 class="block-title">基础信息</h2>
            </div>
            <p class="block-note">
              这几项是 AI 起草的依据，填得越具体、写出来越贴合你。档案名在上面标题处直接改。
            </p>

            <el-form label-position="top" class="plan-form" @submit.prevent="savePlanAndNext">
              <div class="form-grid">
                <el-form-item>
                  <template #label>
                    工作年限
                    <span class="form-label-hint">*必填 · 决定措辞强度基线</span>
                  </template>
                  <el-input-number
                    v-model="planForm.years"
                    :min="0"
                    :max="50"
                    controls-position="right"
                    class="num-input"
                  />
                </el-form-item>
                <el-form-item>
                  <template #label>当前岗位（选填）</template>
                  <el-input
                    v-model="planForm.position"
                    maxlength="120"
                    placeholder="如：负责电商订单系统，日订单 50 万"
                  />
                </el-form-item>
              </div>
              <el-form-item>
                <template #label>
                  技术栈
                  <span class="form-label-hint">*必填 · AI 只会使用这里列出的技术，不会编造</span>
                </template>
                <div class="stack-editor">
                  <div class="stack-chips">
                    <el-tag
                      v-for="tag in stackList"
                      :key="tag"
                      closable
                      :disable-transitions="true"
                      @close="removeStack(tag)"
                    >
                      {{ tag }}
                    </el-tag>
                    <input
                      v-model="stackDraft"
                      class="stack-input"
                      placeholder="列表里没有？输入后回车"
                      @keydown.enter.prevent="addStack(stackDraft)"
                    />
                  </div>
                  <div v-if="missingPresets.length > 0" class="stack-presets">
                    <el-link
                      v-for="preset in missingPresets"
                      :key="preset"
                      class="stack-preset"
                      @click="addStack(preset)"
                    >
                      + {{ preset }}
                    </el-link>
                  </div>
                </div>
              </el-form-item>
              <el-form-item label="目标岗位 / JD（选填，填了会向目标岗位倾斜）">
                <el-input
                  v-model="planForm.jdText"
                  type="textarea"
                  :rows="3"
                  maxlength="5000"
                  placeholder="粘贴 JD 原文或自己写"
                />
              </el-form-item>
              <el-form-item label="想突出的 / 擅长的（选填）">
                <el-input
                  v-model="planForm.goodAt"
                  type="textarea"
                  :rows="2"
                  maxlength="2000"
                  placeholder="如：搜索性能调优、带过 3 人小组、主导过分库分表"
                />
              </el-form-item>
              <el-form-item>
                <template #label>
                  简历全文（选填，可随时补）
                  <span class="form-label-hint">贴了 AI 起草更准，作为事实来源</span>
                </template>
                <el-input
                  v-model="planForm.resumeText"
                  type="textarea"
                  :rows="6"
                  maxlength="20000"
                  show-word-limit
                  placeholder="粘贴简历全文，AI 起草时作为事实来源"
                />
              </el-form-item>

              <div class="step-actions">
                <el-button :loading="planSaving" @click="savePlan">保存</el-button>
                <el-button type="primary" :loading="planSaving" @click="savePlanAndNext">
                  下一步：对话起草
                </el-button>
              </div>
            </el-form>
          </section>

          <!-- ② 对话头脑风暴 -->
          <section v-show="activeStep === 2" class="block">
            <div class="block-head">
              <h2 class="block-title">对话头脑风暴</h2>
            </div>
            <p class="block-note">
              教练按你的基础信息直接出完整草案；不满意直接说人话，你补充的经历下一版就会写进去。轮次上限 5 轮。
            </p>
            <ChatPanel
              ref="chatPanelRef"
              :profile-id="detail.id"
              :profile-status="detail.status"
              :intake-ready="intakeComplete"
              @accepted="onAccepted"
            />
          </section>

          <!-- ③ 技能条 -->
          <section v-show="activeStep === 3" class="block">
            <div class="block-head">
              <h2 class="block-title">技能条</h2>
              <div class="block-actions">
                <el-button
                  size="small"
                  :loading="mapping"
                  :disabled="detail.claims.length === 0"
                  @click="runMapping"
                >
                  锚定映射{{ pendingAnchors > 0 ? `（${pendingAnchors} 条待处理）` : '' }}
                </el-button>
                <el-button size="small" @click="openManual">手动加条</el-button>
              </div>
            </div>
            <p class="block-note">
              小标题 + 一句简历措辞。锚点在定稿后由映射补齐：待确认的可在编辑里挑选考点确认；
              未匹配到的回对话改写，或手动挂锚点。
            </p>

            <div class="claim-list">
              <ClaimCard
                v-for="claim in detail.claims"
                :key="claim.id"
                :claim="claim"
                :saving="claimSaving"
                @confirm="onConfirmClaim(claim.id)"
                @remove="onDeleteClaim(claim.id)"
                @save="(payload) => onSaveClaim(claim.id, payload)"
              />
              <ol v-if="detail.claims.length === 0" class="start-steps">
                <li>
                  <span class="step-body">
                    <span class="step-title">填基础信息</span>
                    年限与技术栈是起草的依据，30 秒填完
                    <el-link type="primary" class="step-link" @click="goStep(1)">去填写</el-link>
                  </span>
                </li>
                <li>
                  <span class="step-body">
                    <span class="step-title">对话起草</span>
                    教练直接给出完整草案，来回磨到满意
                    <el-link type="primary" class="step-link" @click="goStep(2)">去对话</el-link>
                  </span>
                </li>
                <li>
                  <span class="step-body">
                    <span class="step-title">就用这版</span>
                    采纳后逐条微调，占位符在定稿前处理
                  </span>
                </li>
              </ol>
            </div>
          </section>

          <!-- ④ 定稿与导出 -->
          <section v-show="activeStep === 4" class="block">
            <div class="block-head">
              <h2 class="block-title">定稿与导出</h2>
              <el-button
                type="primary"
                size="small"
                :loading="exportBusy"
                :disabled="detail.claims.length === 0 || placeholderCount > 0"
                @click="copyMarkdown"
              >
                复制简历技能段
              </el-button>
            </div>
            <p class="block-note">
              导出为可直接粘贴进简历的 Markdown（自动带上小标题）。带【】占位的条目不会导出。
            </p>

            <div v-if="placeholderCount > 0" class="placeholder-list">
              <p class="zj-eyebrow placeholder-title">待处理占位 · {{ placeholderCount }} 条</p>
              <div v-for="claim in placeholderClaims" :key="claim.id" class="placeholder-row">
                <span class="placeholder-claim">{{ claim.title || '未命名条目' }}</span>
                <span class="placeholder-text">{{ claim.wording }}</span>
                <el-link type="primary" @click="goStep(3)">去编辑</el-link>
              </div>
            </div>
            <p v-else-if="detail.claims.length > 0" class="list-empty">占位已清零，可以定稿并导出</p>

            <div class="level-row">
              <span class="level-label">目标职级</span>
              <el-select
                v-model="planForm.targetLevel"
                class="level-select"
                placeholder="选择目标职级"
                clearable
                :loading="levelSaving"
                @change="saveLevel"
              >
                <el-option
                  v-for="level in levelOptions"
                  :key="level.code"
                  :label="level.name"
                  :value="level.code"
                />
              </el-select>
              <span class="level-note">定稿必填 · 已按工作年限预填，可改</span>
            </div>

            <div class="finalize-row">
              <p class="finalize-hint">{{ finalizeHint }}</p>
              <el-button
                v-if="detail.status !== 'finalized'"
                type="primary"
                :disabled="!canFinalize"
                @click="finalize"
              >
                定稿
              </el-button>
              <el-button v-else @click="unfinalize">撤销定稿</el-button>
            </div>
          </section>
        </div>
      </div>
    </template>

    <!-- 空状态：没有任何档案 -->
    <div v-else class="profile-empty">
      <p class="empty-title">还没有档案</p>
      <p class="empty-note">档案是简历技能条、学习路径与复盘记录的共同归属，先建一份开始。</p>
      <el-button type="primary" :loading="creatingNew" @click="createNewProfile">
        ＋ 新建档案
      </el-button>
    </div>

    <el-dialog
      v-model="manualVisible"
      title="手动添加技能条"
      :fullscreen="isMobile"
      width="min(480px, 92vw)"
    >
      <el-form label-position="top" @submit.prevent="submitManual">
        <el-form-item label="小标题">
          <el-input v-model="manualForm.title" maxlength="40" placeholder="领域 + 定位，如「存储与分库分表」" />
        </el-form-item>
        <el-form-item label="简历措辞">
          <el-input
            v-model="manualForm.wording"
            type="textarea"
            :rows="2"
            maxlength="300"
            show-word-limit
            placeholder="第一人称简历措辞，具体、可检验"
          />
        </el-form-item>
        <el-form-item label="锚定知识点（可后补，定稿后映射也会补齐）">
          <div class="anchor-chips">
            <span
              v-for="anchorId in manualForm.anchorIds"
              :key="anchorId"
              class="anchor-chip removable"
              role="button"
              tabindex="0"
              @click="removeManualAnchor(anchorId)"
              @keydown.enter.prevent="removeManualAnchor(anchorId)"
            >
              {{ manualTitles[anchorId] ?? `知识点 #${anchorId}` }} ✕
            </span>
            <el-link type="primary" @click="manualPickerVisible = true">+ 选择知识点</el-link>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="claimSaving"
          :disabled="!manualForm.wording.trim()"
          @click="submitManual"
        >
          添加
        </el-button>
      </template>
    </el-dialog>

    <KpAnchorPicker v-model:visible="manualPickerVisible" :selected="manualForm.anchorIds" @select="onManualPick" />
  </div>
</template>

<style scoped>
.profile-page {
  padding-bottom: var(--zj-space-6);
}

/* —— 档案切换器 —— */
.profile-switcher {
  margin-top: var(--zj-space-5);
  display: flex;
  flex-wrap: wrap;
  gap: var(--zj-space-2);
}

.pswitch {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 150px;
  padding: var(--zj-space-3);
  text-align: left;
  background: var(--zj-surface);
  border: 1px solid var(--zj-line-soft);
  border-radius: var(--zj-radius);
  cursor: pointer;
  transition: border-color var(--zj-dur-fast) var(--zj-ease-out),
    box-shadow var(--zj-dur-fast) var(--zj-ease-out);
}

.pswitch:focus-visible {
  outline: 2px solid var(--zj-primary);
  outline-offset: 2px;
}

.pswitch.active {
  border-color: var(--zj-primary);
  box-shadow: var(--zj-shadow-sm);
}

.pswitch-top {
  display: flex;
  align-items: center;
  gap: var(--zj-space-2);
  min-width: 0;
}

.pswitch-name {
  font-size: var(--zj-fs-sm);
  font-weight: 650;
  color: var(--zj-ink-900);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pswitch-meta {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.chip-current {
  flex-shrink: 0;
  font-size: var(--zj-fs-xs);
  font-weight: 500;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--zj-primary-soft);
  color: var(--zj-primary);
}

.pswitch.is-new {
  flex-direction: row;
  align-items: center;
  justify-content: center;
  gap: var(--zj-space-2);
  min-width: 120px;
  border-style: dashed;
  border-color: var(--zj-line);
  background: transparent;
  color: var(--zj-ink-600);
}

.pswitch-plus {
  font-size: var(--zj-fs-md);
  font-weight: 300;
  color: var(--zj-ink-400);
}

.pswitch-new-text {
  font-size: var(--zj-fs-sm);
  font-weight: 600;
}

@media (hover: hover) {
  .pswitch:not(.active):hover {
    border-color: var(--zj-line);
    box-shadow: var(--zj-shadow);
  }

  .pswitch.is-new:hover {
    border-color: var(--zj-primary);
    color: var(--zj-primary);
  }
}

/* —— 详情头部 —— */
.detail-head {
  margin-top: var(--zj-space-5);
  padding-top: var(--zj-space-4);
  border-top: 1px solid var(--zj-line-soft);
}

.head-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--zj-space-3) var(--zj-space-4);
}

.head-title {
  min-width: 0;
}

.title-text {
  margin: 0;
  display: inline-flex;
  align-items: center;
  gap: var(--zj-space-2);
  font-size: var(--zj-fs-lg);
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--zj-ink-900);
  cursor: text;
}

.title-text:focus-visible {
  outline: 2px solid var(--zj-primary);
  outline-offset: 4px;
  border-radius: var(--zj-radius-sm);
}

.edit-hint {
  font-size: var(--zj-fs-sm);
  font-weight: 400;
  color: var(--zj-ink-400);
  opacity: 0;
  transition: opacity var(--zj-dur-fast) var(--zj-ease-out);
}

.title-text:focus-visible .edit-hint {
  opacity: 1;
}

@media (hover: hover) {
  .title-text:hover .edit-hint {
    opacity: 1;
  }
}

.title-input {
  width: min(360px, 80vw);
  padding: 2px 0;
  font-size: var(--zj-fs-lg);
  font-weight: 700;
  letter-spacing: -0.01em;
  font-family: inherit;
  color: var(--zj-ink-900);
  background: transparent;
  border: none;
  border-bottom: 2px solid var(--zj-primary);
}

.title-input:focus {
  outline: none;
}

.head-meta {
  margin: var(--zj-space-2) 0 0;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--zj-space-2) var(--zj-space-3);
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.status-chip {
  padding: 1px 10px;
  border-radius: 999px;
  border: 1px solid var(--zj-line);
  color: var(--zj-ink-600);
}

.status-chip[data-finalized='true'] {
  border-color: var(--zj-primary);
  background: var(--zj-primary-soft);
  color: var(--zj-primary);
}

.head-actions {
  display: flex;
  align-items: center;
  gap: var(--zj-space-2);
  flex-wrap: wrap;
}

.status-hint {
  margin: var(--zj-space-2) 0 0;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

/* —— 工作台：脊 + 内容区 —— */
.workspace {
  margin-top: var(--zj-space-4);
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--zj-space-4);
}

/* 脊（移动端：横向可滑动的节点头） */
.spine {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 2px;
}

.spine-node {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  padding: 5px 12px 5px 5px;
  border-radius: 999px;
  border: 1px dashed var(--zj-copper);
  background: var(--zj-copper-soft);
  color: var(--zj-copper);
  font-size: var(--zj-fs-xs);
  line-height: 1.5;
  cursor: pointer;
  transition: border-color var(--zj-dur-fast) var(--zj-ease-out),
    background var(--zj-dur-fast) var(--zj-ease-out);
}

.spine-dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 999px;
  border: 1px solid currentColor;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.spine-node.done {
  border: 1px solid var(--zj-line-soft);
  background: var(--zj-surface-sunken);
  color: var(--zj-ink-500);
}

.spine-node.done .spine-dot {
  border-color: var(--zj-primary);
  background: var(--zj-primary-soft);
  color: var(--zj-primary);
}

.spine-node.active {
  border: 1px solid var(--zj-copper);
  border-style: solid;
  background: var(--zj-surface);
  color: var(--zj-copper);
  font-weight: 600;
  box-shadow: var(--zj-shadow-sm);
}

.spine-node.optional {
  border-color: var(--zj-line);
  background: transparent;
  color: var(--zj-ink-400);
}

@media (hover: hover) {
  .spine-node:not(.active):hover {
    border-style: solid;
  }
}

@media (pointer: coarse) {
  .spine-node {
    padding: 6px 14px 6px 6px;
  }

  .spine-dot {
    width: 22px;
    height: 22px;
  }
}

/* —— 分区 —— */
.block {
  padding-top: var(--zj-space-2);
}

.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--zj-space-2) var(--zj-space-3);
}

.block-title {
  margin: 0;
  font-size: var(--zj-fs-md);
  font-weight: 650;
  color: var(--zj-ink-900);
}

.block-note {
  margin: var(--zj-space-1) 0 var(--zj-space-4);
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
  line-height: 1.7;
}

.block-actions {
  display: flex;
  gap: var(--zj-space-2);
}

.list-empty {
  margin: var(--zj-space-5) 0;
  text-align: center;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-500);
}

/* —— 空状态 —— */
.profile-empty {
  margin-top: var(--zj-space-6);
  padding: var(--zj-space-7) var(--zj-space-5);
  text-align: center;
  border: 1px dashed var(--zj-line);
  border-radius: var(--zj-radius-lg);
}

.empty-title {
  margin: 0;
  font-size: var(--zj-fs-md);
  font-weight: 650;
  color: var(--zj-ink-900);
}

.empty-note {
  margin: var(--zj-space-2) auto var(--zj-space-5);
  max-width: 32em;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-500);
  line-height: 1.7;
}

/* —— 首用三步 —— */
.start-steps {
  margin: var(--zj-space-4) 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--zj-space-3);
  counter-reset: step;
}

.start-steps li {
  display: flex;
  align-items: center;
  gap: var(--zj-space-3);
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-600);
}

.start-steps li::before {
  counter-increment: step;
  content: counter(step);
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--zj-line);
  border-radius: 999px;
  font-size: var(--zj-fs-xs);
  color: var(--zj-copper);
  font-variant-numeric: tabular-nums;
}

.step-body {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--zj-space-1) var(--zj-space-2);
  line-height: 1.6;
}

.step-title {
  font-weight: 600;
  color: var(--zj-ink-900);
}

.step-link {
  font-size: var(--zj-fs-xs);
}

/* —— 占位清单 —— */
.placeholder-list {
  padding: var(--zj-space-3) var(--zj-space-4);
  border: 1px dashed var(--zj-copper);
  border-radius: var(--zj-radius);
  background: var(--zj-copper-soft);
}

.placeholder-title {
  margin: 0 0 var(--zj-space-2);
}

.placeholder-row {
  display: flex;
  align-items: center;
  gap: var(--zj-space-3);
  padding: var(--zj-space-2) 0;
  border-bottom: 1px solid var(--zj-line-soft);
  min-width: 0;
}

.placeholder-row:last-child {
  border-bottom: 0;
}

.placeholder-claim {
  flex-shrink: 0;
  font-size: var(--zj-fs-xs);
  font-weight: 600;
  color: var(--zj-ink-900);
}

.placeholder-text {
  flex: 1;
  min-width: 0;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* —— 目标职级（定稿门槛） —— */
.level-row {
  margin-top: var(--zj-space-4);
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--zj-space-2) var(--zj-space-3);
}

.level-label {
  font-size: var(--zj-fs-sm);
  font-weight: 600;
  color: var(--zj-ink-900);
}

.level-select {
  width: 200px;
}

.level-note {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-400);
}

.finalize-row {
  margin-top: var(--zj-space-4);
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--zj-space-4);
  flex-wrap: wrap;
}

.finalize-hint {
  flex: 1;
  min-width: 200px;
  margin: 0;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

/* —— 基础信息表单 —— */
.plan-form {
  margin-top: var(--zj-space-4);
  display: flex;
  flex-direction: column;
  gap: var(--zj-space-5);
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr;
  column-gap: var(--zj-space-4);
}

.num-input {
  width: 100%;
}

.form-label-hint {
  margin-left: var(--zj-space-2);
  font-size: var(--zj-fs-xs);
  font-weight: 400;
  color: var(--zj-ink-400);
}

/* —— 技术栈 chip 编辑 —— */
.stack-editor {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--zj-space-2);
}

.stack-chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 6px;
  border: 1px solid var(--zj-line);
  border-radius: var(--zj-radius);
  background: var(--zj-surface);
}

.stack-input {
  flex: 1;
  min-width: 160px;
  border: 1px dashed var(--zj-line);
  border-radius: 999px;
  padding: 4px 12px;
  font-size: var(--zj-fs-xs);
  font-family: inherit;
  color: var(--zj-ink-900);
  background: transparent;
}

.stack-input:focus {
  outline: none;
  border-color: var(--zj-primary);
}

.stack-presets {
  display: flex;
  flex-wrap: wrap;
  gap: var(--zj-space-2) var(--zj-space-3);
  font-size: var(--zj-fs-xs);
}

.step-actions {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: var(--zj-space-2);
}

/* —— 手动加条的锚点 chip —— */
.anchor-chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.anchor-chip {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--zj-fs-xs);
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--zj-surface-sunken);
  color: var(--zj-ink-700);
}

.anchor-chip.removable {
  cursor: pointer;
}

/* —— 桌面增强（≥1024px）：脊变竖向，内容在右 —— */
@media (min-width: 1024px) {
  .workspace {
    grid-template-columns: 168px minmax(0, 1fr);
    gap: var(--zj-space-6);
  }

  .spine {
    position: sticky;
    top: var(--zj-space-4);
    align-self: start;
    flex-direction: column;
    gap: 0;
    overflow: visible;
    padding-bottom: 0;
  }

  .spine::before {
    content: '';
    position: absolute;
    left: 10.5px;
    top: 16px;
    bottom: 22px;
    width: 1px;
    background: var(--zj-line);
  }

  .spine-node {
    position: relative;
    z-index: 1;
    width: 100%;
    justify-content: flex-start;
    padding: 5px 0 18px;
    border: none;
    border-radius: 0;
    background: transparent;
    color: var(--zj-ink-500);
    font-weight: 500;
    text-align: left;
  }

  .spine-node:last-child {
    padding-bottom: 0;
  }

  .spine-dot {
    width: 22px;
    height: 22px;
    background: var(--zj-paper);
    color: var(--zj-ink-400);
    border-color: var(--zj-line);
  }

  .spine-node.done {
    background: transparent;
    border: none;
  }

  .spine-node.done .spine-dot {
    border-color: var(--zj-primary);
    background: var(--zj-primary-soft);
    color: var(--zj-primary);
  }

  .spine-node.active {
    background: transparent;
    border: none;
    box-shadow: none;
  }

  .spine-node.active .spine-dot {
    border-color: var(--zj-copper);
    background: var(--zj-surface);
    color: var(--zj-copper);
  }

  .spine-node.active .spine-label {
    font-weight: 650;
    color: var(--zj-ink-900);
  }

  .spine-label {
    font-size: var(--zj-fs-sm);
  }

  @media (hover: hover) {
    .spine-node:not(.active):hover {
      color: var(--zj-ink-900);
    }
  }

  .form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
