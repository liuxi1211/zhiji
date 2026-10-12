<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  addCase,
  addClaim,
  confirmClaim,
  createProfile,
  deleteCase,
  deleteClaim,
  draftClaims,
  finalizeProfile,
  getLevelSuggestion,
  getProfile,
  unfinalizeProfile,
  updateCase,
  updateClaim,
  updateProfile
} from '@/api/profile'
import type { CaseView, ClaimDraft, KpCandidate, ProfileDetail } from '@/api/types'
import { useConstantsStore } from '@/stores/constants'
import { useProfileStore } from '@/stores/profile'
import CaseEditor from '@/components/profile/CaseEditor.vue'
import ClaimCard from '@/components/profile/ClaimCard.vue'
import KpAnchorPicker from '@/components/profile/KpAnchorPicker.vue'

const constants = useConstantsStore()
const profileStore = useProfileStore()

const mode = ref<'list' | 'detail'>('list')
const loading = ref(false)
const detail = ref<ProfileDetail | null>(null)

const createVisible = ref(false)
const createForm = reactive({ name: '', years: null as number | null, position: '' })
const creating = ref(false)

const planForm = reactive({
  name: '',
  resumeText: '',
  years: null as number | null,
  position: '',
  jdText: '',
  techStack: '',
  targetLevel: '',
  planWeeks: null as number | null,
  dailyMinutes: null as number | null
})
const planSaving = ref(false)

const drafts = ref<ClaimDraft[]>([])
const drafting = ref(false)
const claimSaving = ref(false)

const manualVisible = ref(false)
const manualPickerVisible = ref(false)
const manualForm = reactive({ wording: '', anchorIds: [] as number[] })
const manualTitles = reactive<Record<number, string>>({})

const caseVisible = ref(false)
const caseEditing = ref<CaseView | null>(null)
const caseSaving = ref(false)

const levelOptions = computed(() => constants.items('target_level'))
const statusName = computed(() =>
  detail.value ? constants.name('profile_status', detail.value.status) : ''
)
const canFinalize = computed(
  () => detail.value?.status === 'confirmed' && !!detail.value?.targetLevel
)
const finalizeHint = computed(() => {
  if (!detail.value) return ''
  if (detail.value.status === 'finalized') return '档案已定稿，将参与学习路径生成'
  if (detail.value.status === 'draft') return '存在未确认的技能条，全部确认后才能定稿'
  if (!detail.value.targetLevel) return '设定目标职级后才能定稿'
  return '技能条已全部确认，可以定稿'
})

onMounted(() => {
  void refreshList()
})

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

async function openDetail(id: number) {
  loading.value = true
  try {
    detail.value = await getProfile(id)
    fillPlanForm()
    drafts.value = []
    mode.value = 'detail'
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '加载档案失败')
  } finally {
    loading.value = false
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
  planForm.targetLevel = profile.targetLevel ?? ''
  planForm.planWeeks = profile.planWeeks
  planForm.dailyMinutes = profile.dailyMinutes
}

async function reloadDetail() {
  if (!detail.value) return
  detail.value = await getProfile(detail.value.id)
}

function backToList() {
  mode.value = 'list'
  detail.value = null
  void refreshList()
}

async function submitCreate() {
  if (!createForm.name.trim()) {
    ElMessage.warning('档案名不能为空')
    return
  }
  creating.value = true
  try {
    const created = await createProfile({
      name: createForm.name.trim(),
      years: createForm.years,
      position: createForm.position.trim()
    })
    createVisible.value = false
    createForm.name = ''
    createForm.years = null
    createForm.position = ''
    ElMessage.success('档案已创建')
    await refreshList()
    await openDetail(created.id)
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '创建失败')
  } finally {
    creating.value = false
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

async function savePlan() {
  if (!detail.value || !planForm.name.trim()) {
    ElMessage.warning('档案名不能为空')
    return
  }
  planSaving.value = true
  try {
    await updateProfile(detail.value.id, {
      name: planForm.name.trim(),
      resumeText: planForm.resumeText,
      years: planForm.years,
      position: planForm.position.trim(),
      jdText: planForm.jdText,
      techStack: planForm.techStack.trim(),
      targetLevel: planForm.targetLevel || null,
      planWeeks: planForm.planWeeks,
      dailyMinutes: planForm.dailyMinutes
    })
    await reloadDetail()
    fillPlanForm()
    ElMessage.success('备考计划已保存')
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '保存失败')
  } finally {
    planSaving.value = false
  }
}

// —— 技能条 ——

async function runDraft() {
  if (!detail.value) return
  drafting.value = true
  try {
    drafts.value = await draftClaims(detail.value.id)
    if (drafts.value.length === 0) {
      ElMessage.info('AI 没有给出可采纳的草稿：候选知识点对不上简历主张，先补齐题库或简历信息')
    }
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? 'AI 起草失败')
  } finally {
    drafting.value = false
  }
}

function ignoreDraft(index: number) {
  drafts.value = drafts.value.filter((_, i) => i !== index)
}

async function adoptDraft(draft: ClaimDraft) {
  if (!detail.value) return
  try {
    await addClaim(detail.value.id, {
      wording: draft.wording,
      anchorIds: draft.anchors.map((anchor) => anchor.id),
      source: 'ai'
    })
    drafts.value = drafts.value.filter((item) => item !== draft)
    await reloadDetail()
    ElMessage.success('已采纳，待确认')
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '采纳失败')
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
  try {
    await deleteClaim(detail.value.id, claimId)
    await reloadDetail()
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '操作失败')
  }
}

async function onSaveClaim(claimId: number, payload: { wording: string; anchorIds: number[] }) {
  if (!detail.value) return
  claimSaving.value = true
  try {
    await updateClaim(detail.value.id, claimId, { ...payload, source: undefined })
    await reloadDetail()
    ElMessage.success('已保存，回到待确认')
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '保存失败')
  } finally {
    claimSaving.value = false
  }
}

function openManual() {
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
      wording: manualForm.wording.trim(),
      anchorIds: manualForm.anchorIds,
      source: 'manual'
    })
    manualVisible.value = false
    await reloadDetail()
    ElMessage.success('已添加，待确认')
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '添加失败')
  } finally {
    claimSaving.value = false
  }
}

async function finalize() {
  if (!detail.value) return
  try {
    await finalizeProfile(detail.value.id)
    await reloadDetail()
    ElMessage.success('档案已定稿')
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

// —— 项目素材 ——

function openCaseEditor(item: CaseView | null) {
  caseEditing.value = item
  caseVisible.value = true
}

async function saveCase(req: {
  projectName: string
  background: string
  metrics: string
  conflict: string
  result: string
}) {
  if (!detail.value) return
  caseSaving.value = true
  try {
    if (caseEditing.value) {
      await updateCase(detail.value.id, caseEditing.value.id, req)
    } else {
      await addCase(detail.value.id, req)
    }
    caseVisible.value = false
    await reloadDetail()
    ElMessage.success('项目素材已保存')
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '保存失败')
  } finally {
    caseSaving.value = false
  }
}

async function removeCase(item: CaseView) {
  if (!detail.value) return
  try {
    await deleteCase(detail.value.id, item.id)
    await reloadDetail()
  } catch (error) {
    const err = error as { message?: string }
    ElMessage.error(err.message ?? '删除失败')
  }
}
</script>

<template>
  <div class="profile-page" v-loading="loading">
    <template v-if="mode === 'list'">
      <header class="zj-page-head">
        <h1>个人档案</h1>
        <p>简历变画像：最多 3 份档案，进度完全隔离，切换档案即切换全部上下文</p>
      </header>

      <div class="profile-list">
        <p v-if="profileStore.items.length === 0" class="list-empty">
          还没有档案，创建第一份开始建档
        </p>
        <button
          v-for="item in profileStore.items"
          :key="item.id"
          type="button"
          class="profile-row"
          @click="openDetail(item.id)"
        >
          <span class="row-main">
            <span class="row-name">
              {{ item.name }}
              <span v-if="item.active" class="row-active">当前档案</span>
            </span>
            <span class="row-meta">
              {{ constants.name('profile_status', item.status) || item.status }}
              <template v-if="item.targetLevel">· 目标{{ constants.name('target_level', item.targetLevel) }}</template>
              · 技能条 {{ item.claimCount }} 条
              <template v-if="item.unconfirmedCount > 0">（{{ item.unconfirmedCount }} 条待确认）</template>
            </span>
          </span>
          <span class="row-side" @click.stop>
            <el-button v-if="!item.active" size="small" @click="setActive(item.id)">设为当前</el-button>
            <el-button size="small" type="primary" plain @click="openDetail(item.id)">进入</el-button>
          </span>
        </button>
      </div>

      <div class="list-foot">
        <el-button type="primary" :disabled="profileStore.items.length >= 3" @click="createVisible = true">
          新建档案
        </el-button>
        <span v-if="profileStore.items.length >= 3" class="foot-hint">最多 3 份</span>
      </div>
    </template>

    <template v-else-if="detail">
      <header class="zj-page-head">
        <el-link class="back-link" @click="backToList">← 我的档案</el-link>
        <h1>{{ detail.name }}</h1>
        <p>
          {{ statusName }}
          <template v-if="detail.targetLevel">· 目标{{ constants.name('target_level', detail.targetLevel) }}</template>
          <template v-if="detail.finalizedAt"> · 定稿于 {{ detail.finalizedAt }}</template>
        </p>
        <div class="head-actions">
          <el-button v-if="!detail.active" size="small" @click="setActive(detail.id)">设为当前档案</el-button>
          <el-button v-if="detail.status !== 'finalized'" size="small" type="primary" :disabled="!canFinalize" @click="finalize">
            定稿
          </el-button>
          <el-button v-else size="small" @click="unfinalize">撤销定稿</el-button>
        </div>
        <p class="status-hint">{{ finalizeHint }}</p>
      </header>

      <section class="block">
        <h2 class="block-title">备考计划</h2>
        <el-form label-position="top" class="plan-form" @submit.prevent="savePlan">
          <el-form-item label="档案名">
            <el-input v-model="planForm.name" maxlength="40" placeholder="如：Java 后端方向" />
          </el-form-item>
          <el-form-item label="简历全文">
            <el-input
              v-model="planForm.resumeText"
              type="textarea"
              :rows="6"
              maxlength="20000"
              show-word-limit
              placeholder="粘贴简历全文，AI 据此起草技能条"
            />
          </el-form-item>
          <div class="form-grid">
            <el-form-item label="工作年限">
              <el-input-number v-model="planForm.years" :min="0" :max="50" controls-position="right" class="num-input" />
            </el-form-item>
            <el-form-item label="目标职级">
              <el-select v-model="planForm.targetLevel" placeholder="选择目标职级" clearable>
                <el-option
                  v-for="level in levelOptions"
                  :key="level.code"
                  :label="level.name"
                  :value="level.code"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="目标岗位">
              <el-input v-model="planForm.position" maxlength="120" placeholder="如：Java 后端工程师" />
            </el-form-item>
            <el-form-item label="备考周期（周）">
              <el-input-number v-model="planForm.planWeeks" :min="1" :max="104" controls-position="right" class="num-input" />
            </el-form-item>
            <el-form-item label="每日可投入（分钟）">
              <el-input-number v-model="planForm.dailyMinutes" :min="10" :max="720" :step="10" controls-position="right" class="num-input" />
            </el-form-item>
          </div>
          <el-form-item label="目标岗位 JD">
            <el-input v-model="planForm.jdText" type="textarea" :rows="3" maxlength="5000" placeholder="粘贴 JD 全文，学习路径会按它定制" />
          </el-form-item>
          <el-form-item label="技术栈">
            <el-input v-model="planForm.techStack" maxlength="2000" placeholder="逗号分隔，如：Java, Redis, MySQL, Kafka" />
          </el-form-item>
          <el-button type="primary" :loading="planSaving" @click="savePlan">保存计划</el-button>
        </el-form>
      </section>

      <section class="block">
        <div class="block-head">
          <h2 class="block-title">技能条（简历主张）</h2>
          <div class="block-actions">
            <el-button size="small" :loading="drafting" @click="runDraft">AI 起草技能条</el-button>
            <el-button size="small" @click="openManual">手动加条</el-button>
          </div>
        </div>
        <p class="block-note">
          每条技能条 = 一段简历措辞 + 锚定的题库知识点；逐条拍板：不熟的删掉、漏掉的加上、措辞不准的改掉
        </p>

        <div v-if="drafts.length > 0" class="draft-area">
          <p class="draft-title">AI 草稿（{{ drafts.length }} 条，采纳后逐条确认）</p>
          <div v-for="draft in drafts" :key="draft.wording" class="draft-item">
            <p class="draft-wording">{{ draft.wording }}</p>
            <div class="draft-anchors">
              <span v-for="anchor in draft.anchors" :key="anchor.id" class="anchor-chip">{{ anchor.title }}</span>
            </div>
            <div class="draft-actions">
              <el-button size="small" type="primary" plain @click="adoptDraft(draft)">采纳</el-button>
              <el-button size="small" @click="ignoreDraft(drafts.indexOf(draft))">忽略</el-button>
            </div>
          </div>
        </div>

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
          <p v-if="detail.claims.length === 0" class="list-empty">
            还没有技能条：粘贴简历后点「AI 起草技能条」，或手动添加
          </p>
        </div>
      </section>

      <section class="block">
        <div class="block-head">
          <h2 class="block-title">项目素材</h2>
          <el-button size="small" @click="openCaseEditor(null)">添加项目素材</el-button>
        </div>
        <p class="block-note">对话中聊到的项目细节沉淀在此，供项目叙事练习与模拟考追问使用</p>
        <div class="case-list">
          <div v-for="item in detail.cases" :key="item.id" class="case-row">
            <div class="case-main">
              <span class="case-name">{{ item.projectName }}</span>
              <span class="case-brief">{{ item.background || '（未填背景）' }}</span>
            </div>
            <div class="case-actions">
              <el-link @click="openCaseEditor(item)">编辑</el-link>
              <el-link type="danger" @click="removeCase(item)">删除</el-link>
            </div>
          </div>
          <p v-if="detail.cases.length === 0" class="list-empty">还没有项目素材</p>
        </div>
      </section>
    </template>

    <el-dialog v-model="createVisible" title="新建档案" width="min(440px, 92vw)">
      <el-form label-position="top" @submit.prevent="submitCreate">
        <el-form-item label="档案名">
          <el-input v-model="createForm.name" maxlength="40" placeholder="如：Java 后端 + ES 方向" />
        </el-form-item>
        <el-form-item label="目标岗位（可后补）">
          <el-input v-model="createForm.position" maxlength="120" placeholder="如：Java 后端工程师" />
        </el-form-item>
        <el-form-item label="工作年限（可后补）">
          <el-input-number v-model="createForm.years" :min="0" :max="50" controls-position="right" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="manualVisible" title="手动添加技能条" width="min(480px, 92vw)">
      <el-form label-position="top" @submit.prevent="submitManual">
        <el-form-item label="简历措辞">
          <el-input
            v-model="manualForm.wording"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="第一人称简历措辞，具体、可检验"
          />
        </el-form-item>
        <el-form-item label="锚定知识点">
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
        <el-button type="primary" :loading="claimSaving" :disabled="!manualForm.wording.trim() || manualForm.anchorIds.length === 0" @click="submitManual">
          添加
        </el-button>
      </template>
    </el-dialog>

    <KpAnchorPicker v-model:visible="manualPickerVisible" :selected="manualForm.anchorIds" @select="onManualPick" />

    <CaseEditor v-model:visible="caseVisible" :initial="caseEditing" :saving="caseSaving" @save="saveCase" />
  </div>
</template>

<style scoped>
.profile-page {
  padding-bottom: 24px;
}

.profile-list {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
}

.profile-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 14px 0;
  border: 0;
  border-bottom: 1px solid var(--zj-line-soft);
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.profile-row:hover .row-name {
  color: var(--zj-primary);
}

.row-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.row-name {
  font-size: var(--zj-fs-md);
  font-weight: 600;
  color: var(--zj-ink-900);
  display: flex;
  align-items: center;
  gap: 8px;
}

.row-active {
  font-size: var(--zj-fs-xs);
  font-weight: 400;
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid var(--zj-primary);
  color: var(--zj-primary);
}

.row-meta {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.row-side {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.list-empty {
  margin: 24px 0;
  text-align: center;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-500);
}

.list-foot {
  margin-top: 20px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.foot-hint {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.back-link {
  font-size: var(--zj-fs-xs);
}

.head-actions {
  margin-top: 10px;
  display: flex;
  gap: 8px;
}

.status-hint {
  margin: 6px 0 0;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.block {
  margin-top: 28px;
  padding-top: 20px;
  border-top: 1px solid var(--zj-line-soft);
}

.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.block-actions {
  display: flex;
  gap: 8px;
}

.block-title {
  margin: 0;
  font-size: var(--zj-fs-md);
  font-weight: 650;
  color: var(--zj-ink-900);
}

.block-note {
  margin: 6px 0 14px;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
  line-height: 1.6;
}

.plan-form {
  margin-top: 12px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 16px;
}

.num-input {
  width: 100%;
}

.draft-area {
  margin-bottom: 18px;
  padding: 12px 14px;
  border: 1px dashed var(--zj-line);
  border-radius: var(--zj-radius-sm);
  background: var(--zj-surface-sunken);
}

.draft-title {
  margin: 0 0 10px;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-600);
}

.draft-item {
  padding: 10px 0;
  border-bottom: 1px solid var(--zj-line-soft);
}

.draft-item:last-child {
  border-bottom: 0;
}

.draft-wording {
  margin: 0 0 6px;
  font-size: var(--zj-fs-sm);
  line-height: 1.6;
  color: var(--zj-ink-900);
}

.draft-anchors {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.draft-actions {
  display: flex;
  gap: 8px;
}

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

.case-list {
  display: flex;
  flex-direction: column;
}

.case-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--zj-line-soft);
}

.case-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.case-name {
  font-size: var(--zj-fs-sm);
  font-weight: 600;
  color: var(--zj-ink-900);
}

.case-brief {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.case-actions {
  display: flex;
  gap: 12px;
  flex-shrink: 0;
}

@media (max-width: 640px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
