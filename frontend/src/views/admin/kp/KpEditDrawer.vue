<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import MarkdownEditor from '@/components/MarkdownEditor.vue'
import {
  addFollowupLayer,
  disableFollowupLayer,
  getKp,
  listKp,
  saveFreqSignal,
  updateKp
} from '@/api/kb'
import { useConstantsStore } from '@/stores/constants'
import type {
  ApiError,
  FreqLevel,
  KpFreqSignalView,
  KpLevelReq,
  KpMeta,
  KpRubricGroup,
  KpRubricPoint,
  KpSampleSet,
  KpSavePayload,
  KpStatus,
  LevelTarget,
  SubType
} from '@/api/types'

const props = defineProps<{ modelValue: boolean; kpId: number | null; meta: KpMeta | null }>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved'): void
}>()

const constants = useConstantsStore()

const LEVEL_TARGETS: LevelTarget[] = ['junior', 'mid', 'senior', 'staff', 'expert']
const MAIN_LAYER = 'main'

interface EditableFollowup {
  layerKey: string
  dir: string
  text: string
  expect: string
  stopCondition: string
  status: 'active' | 'disabled'
}

const loading = ref(false)
const saving = ref(false)
const kpStatus = ref<KpStatus>('draft')

const state = reactive({
  domainId: null as number | null,
  clusterId: null as number | null,
  subType: '' as SubType | '',
  title: '',
  difficulty: 3,
  variants: [] as string[],
  narrativeFramework: '',
  followups: [] as EditableFollowup[],
  rubric: [] as KpRubricGroup[],
  samples: { pass: '', good: '', excellent: '' } as KpSampleSet,
  levelReq: {
    junior: { required: false, stopLayer: null },
    mid: { required: false, stopLayer: null },
    senior: { required: false, stopLayer: null },
    staff: { required: false, stopLayer: null },
    expert: { required: false, stopLayer: null }
  } as KpLevelReq,
  prereqIds: [] as number[],
  freqSignals: [] as KpFreqSignalView[]
})

watch(
  () => props.modelValue,
  (open) => {
    if (open && props.kpId != null) {
      void loadDetail(props.kpId)
    }
  }
)

async function loadDetail(id: number) {
  loading.value = true
  try {
    const detail = await getKp(id)
    kpStatus.value = detail.status
    state.domainId = detail.domainId
    state.clusterId = detail.clusterId
    state.subType = detail.subType
    state.title = detail.title
    state.difficulty = detail.difficulty
    state.variants = [...detail.variants]
    state.narrativeFramework = detail.narrativeFramework ?? ''
    state.followups = detail.followups.map((f) => ({
      layerKey: f.layerKey,
      dir: f.dir,
      text: f.text,
      expect: f.expect ?? '',
      stopCondition: f.stopCondition ?? '',
      status: f.status
    }))
    state.rubric = detail.rubric.map((g) => ({
      layerKey: g.layerKey,
      points: g.points.map((p) => ({ ...p }))
    }))
    state.samples = {
      pass: detail.samples?.pass ?? '',
      good: detail.samples?.good ?? '',
      excellent: detail.samples?.excellent ?? ''
    }
    for (const t of LEVEL_TARGETS) {
      state.levelReq[t] = {
        required: detail.levelReq?.[t]?.required ?? false,
        stopLayer: detail.levelReq?.[t]?.stopLayer ?? null
      }
    }
    state.prereqIds = [...detail.prereqIds]
    state.freqSignals = [...detail.freqSignals]
    prereqOptions.value = detail.prereqList.map((p) => ({ id: p.id, title: p.title }))
    freqSignalForm.level = null
    freqSignalForm.note = ''
  } catch (error) {
    ElMessage.error(messageOf(error, '加载知识点详情失败'))
    emit('update:modelValue', false)
  } finally {
    loading.value = false
  }
}

function messageOf(error: unknown, fallback: string): string {
  const err = error as { message?: string }
  return err?.message ?? fallback
}

const domainOptions = computed(() => props.meta?.domains ?? [])

const clusterOptions = computed(() => {
  const all = props.meta?.clusters ?? []
  const list = all.filter((c) => c.domainId === state.domainId && c.status !== 'retired')
  // 当前簇若已 retired 不在可选项里，补回避免回显成数字
  if (state.clusterId != null && !list.some((c) => c.id === state.clusterId)) {
    const current = all.find((c) => c.id === state.clusterId)
    if (current) {
      list.push(current)
    }
  }
  return list
})

function onDomainChange() {
  state.clusterId = null
}

function addVariant() {
  if (state.variants.length >= 5) {
    ElMessage.warning('题面变体最多 5 条')
    return
  }
  state.variants.push('')
}

function removeVariant(index: number) {
  state.variants.splice(index, 1)
}

function moveLayer(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= state.followups.length) {
    return
  }
  const [item] = state.followups.splice(index, 1)
  state.followups.splice(target, 0, item)
}

async function disableLayer(f: EditableFollowup) {
  if (props.kpId == null) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认停用追问层「${f.layerKey}」？停用即对后续判定生效，且不可恢复。`,
      '停用追问层',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await disableFollowupLayer(props.kpId, f.layerKey)
    f.status = 'disabled'
    ElMessage.success('已停用')
  } catch (error) {
    ElMessage.error(messageOf(error, '停用失败'))
  }
}

const newLayer = reactive({ dir: '', text: '', expect: '', stopCondition: '' })
const addingLayer = ref(false)

async function submitAddLayer() {
  if (props.kpId == null) {
    return
  }
  if (!newLayer.dir.trim() || !newLayer.text.trim()) {
    ElMessage.warning('追问方向与追问内容必填')
    return
  }
  addingLayer.value = true
  try {
    const { layerKey } = await addFollowupLayer(props.kpId, {
      dir: newLayer.dir.trim(),
      text: newLayer.text.trim(),
      expect: newLayer.expect.trim() || undefined,
      stopCondition: newLayer.stopCondition.trim() || undefined
    })
    state.followups.push({
      layerKey,
      dir: newLayer.dir.trim(),
      text: newLayer.text.trim(),
      expect: newLayer.expect.trim(),
      stopCondition: newLayer.stopCondition.trim(),
      status: 'active'
    })
    ElMessage.success(`已新增层 ${layerKey}，可在评分要点区继续补要点`)
    newLayer.dir = ''
    newLayer.text = ''
    newLayer.expect = ''
    newLayer.stopCondition = ''
  } catch (error) {
    ElMessage.error(messageOf(error, '新增层失败'))
  } finally {
    addingLayer.value = false
  }
}

const rubricSections = computed(() => {
  const sections: { layerKey: string; label: string; disabled: boolean; points: KpRubricPoint[] }[] = [
    {
      layerKey: MAIN_LAYER,
      label: '主问题层',
      disabled: false,
      points: state.rubric.find((g) => g.layerKey === MAIN_LAYER)?.points ?? []
    }
  ]
  state.followups.forEach((f, index) => {
    sections.push({
      layerKey: f.layerKey,
      label: `追问层 ${index + 1}`,
      disabled: f.status === 'disabled',
      points: state.rubric.find((g) => g.layerKey === f.layerKey)?.points ?? []
    })
  })
  return sections
})

function addPoint(layerKey: string) {
  let group = state.rubric.find((g) => g.layerKey === layerKey)
  if (!group) {
    group = { layerKey, points: [] }
    state.rubric.push(group)
  }
  // 新要点 pointId 置空，由后端分配
  group.points.push({ pointId: '', level: 'basic', text: '' })
}

function removePoint(layerKey: string, index: number) {
  const group = state.rubric.find((g) => g.layerKey === layerKey)
  group?.points.splice(index, 1)
}

const stopLayerOptions = computed(() => {
  const opts: { value: string; label: string }[] = [
    { value: MAIN_LAYER, label: '主问题层（main）' }
  ]
  state.followups.forEach((f, index) => {
    const suffix = f.status === 'disabled' ? ' · 已停用' : ''
    opts.push({ value: f.layerKey, label: `追问层 ${index + 1}（${f.layerKey}）${suffix}` })
  })
  return opts
})

const prereqOptions = ref<{ id: number; title: string }[]>([])
const prereqSearching = ref(false)

async function searchPrereq(keyword: string) {
  if (props.kpId == null || !keyword.trim()) {
    return
  }
  prereqSearching.value = true
  try {
    const { list } = await listKp({ keyword: keyword.trim(), size: 20 })
    const selected = new Set(state.prereqIds)
    const merged = prereqOptions.value.filter((o) => selected.has(o.id))
    for (const row of list) {
      if (row.id === props.kpId || row.status === 'retired') {
        continue
      }
      if (!merged.some((o) => o.id === row.id)) {
        merged.push({ id: row.id, title: row.title })
      }
    }
    prereqOptions.value = merged
  } catch (error) {
    ElMessage.error(messageOf(error, '搜索失败'))
  } finally {
    prereqSearching.value = false
  }
}

const freqSignalForm = reactive<{ level: FreqLevel | null; note: string }>({ level: null, note: '' })
const savingSignal = ref(false)

async function submitFreqSignal() {
  if (props.kpId == null) {
    return
  }
  if (!freqSignalForm.level) {
    ElMessage.warning('请选择信号档位')
    return
  }
  if (!freqSignalForm.note.trim()) {
    ElMessage.warning('请填写标定说明')
    return
  }
  savingSignal.value = true
  try {
    await saveFreqSignal(props.kpId, { signalLevel: freqSignalForm.level, note: freqSignalForm.note.trim() })
    ElMessage.success('标定已保存')
    // 只刷新信号区，避免覆盖其他区块未保存的编辑
    const fresh = await getKp(props.kpId)
    state.freqSignals = [...fresh.freqSignals]
    freqSignalForm.level = null
    freqSignalForm.note = ''
  } catch (error) {
    ElMessage.error(messageOf(error, '标定保存失败'))
  } finally {
    savingSignal.value = false
  }
}

function saveErrorMessage(error: unknown): string {
  const err = error as ApiError
  let msg = err?.message ?? '保存失败'
  if (err?.fields) {
    const detail = Object.values(err.fields).join('；')
    if (detail) {
      msg += `：${detail}`
    }
  }
  return msg
}

async function save() {
  if (props.kpId == null) {
    return
  }
  const clusterId = state.clusterId
  const subType = state.subType
  if (!state.title.trim()) {
    ElMessage.warning('请填写题面')
    return
  }
  if (!clusterId) {
    ElMessage.warning('请选择主簇')
    return
  }
  if (!subType) {
    ElMessage.warning('请选择题型')
    return
  }
  for (const t of LEVEL_TARGETS) {
    const req = state.levelReq[t]
    if (req.required && !req.stopLayer) {
      ElMessage.warning(`职级「${constants.name('level', t)}」已设为必答，请选择停止追问层`)
      return
    }
  }
  if (kpStatus.value === 'active') {
    try {
      await ElMessageBox.confirm('本次改动影响未来判定，历史判定已快照。确认保存？', '保存确认', {
        type: 'warning',
        confirmButtonText: '确认保存',
        cancelButtonText: '取消'
      })
    } catch {
      return
    }
  }
  saving.value = true
  try {
    const payload: KpSavePayload = {
      clusterId,
      subType,
      title: state.title.trim(),
      difficulty: state.difficulty,
      variants: state.variants.map((v) => v.trim()).filter((v) => v !== ''),
      narrativeFramework: state.narrativeFramework,
      followups: state.followups.map((f) => ({
        layerKey: f.layerKey,
        dir: f.dir.trim(),
        text: f.text.trim(),
        expect: f.expect.trim() || null,
        stopCondition: f.stopCondition.trim() || null,
        status: f.status
      })),
      rubric: state.rubric
        .map((g) => ({ layerKey: g.layerKey, points: g.points.map((p) => ({ ...p })) }))
        .filter((g) => g.points.length > 0),
      samples: { ...state.samples },
      levelReq: JSON.parse(JSON.stringify(state.levelReq)) as KpLevelReq,
      prereqIds: [...state.prereqIds]
    }
    await updateKp(props.kpId, payload)
    ElMessage.success('已保存')
    emit('saved')
    emit('update:modelValue', false)
  } catch (error) {
    ElMessage.error(saveErrorMessage(error))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    size="90%"
    :title="`编辑知识点${state.title ? `：${state.title}` : ''}`"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="drawer-body">
      <el-divider content-position="left">基础信息</el-divider>
      <el-form label-position="top">
        <el-row :gutter="16">
          <el-col :span="6">
            <el-form-item label="技能域">
              <el-select v-model="state.domainId" style="width: 100%" @change="onDomainChange">
                <el-option v-for="d in domainOptions" :key="d.id" :label="d.name" :value="d.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="主簇">
              <el-select v-model="state.clusterId" style="width: 100%" placeholder="先选域再选簇">
                <el-option v-for="c in clusterOptions" :key="c.id" :label="c.name" :value="c.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="题型">
              <el-select v-model="state.subType" style="width: 100%">
                <el-option v-for="item in constants.items('sub_type')" :key="item.code" :label="item.name" :value="item.code" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="难度">
              <el-select v-model="state.difficulty" style="width: 100%">
                <el-option v-for="n in 5" :key="n" :label="`难度${n}`" :value="n" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="题面">
          <el-input v-model="state.title" type="textarea" :rows="2" placeholder="面试题题面" />
        </el-form-item>
      </el-form>

      <el-divider content-position="left">题面变体</el-divider>
      <div class="section-hint">同一题面的不同问法，最多 5 条；用于命中识别。</div>
      <div v-for="(_, index) in state.variants" :key="index" class="variant-row">
        <el-input v-model="state.variants[index]" placeholder="变体问法" />
        <el-button size="small" text type="danger" @click="removeVariant(index)">删除</el-button>
      </div>
      <el-button size="small" :disabled="state.variants.length >= 5" @click="addVariant">添加变体</el-button>
      <span v-if="state.variants.length >= 5" class="section-hint inline">已达上限 5 条</span>

      <el-divider content-position="left">追问链</el-divider>
      <div class="section-hint">主问题层固定为 main，不在此列；数组顺序即展示序。新增层与停用层即时生效，不走底部整体保存。</div>
      <div
        v-for="(f, index) in state.followups"
        :key="f.layerKey"
        class="layer-box"
        :class="{ 'layer-disabled': f.status === 'disabled' }"
      >
        <div class="layer-head">
          <el-tag size="small" effect="plain">{{ f.layerKey }}</el-tag>
          <el-tag v-if="f.status === 'disabled'" size="small" type="info">已停用</el-tag>
          <span class="layer-ops">
            <el-button size="small" text :disabled="index === 0" @click="moveLayer(index, -1)">上移</el-button>
            <el-button size="small" text :disabled="index === state.followups.length - 1" @click="moveLayer(index, 1)">下移</el-button>
            <el-button v-if="f.status === 'active'" size="small" text type="danger" @click="disableLayer(f)">停用</el-button>
          </span>
        </div>
        <el-form label-position="top">
          <el-row :gutter="16">
            <el-col :span="8">
              <el-form-item label="追问方向">
                <el-input v-model="f.dir" :disabled="f.status === 'disabled'" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item label="追问内容">
            <el-input v-model="f.text" type="textarea" :rows="2" :disabled="f.status === 'disabled'" />
          </el-form-item>
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="期望回答要点">
                <el-input v-model="f.expect" type="textarea" :rows="2" :disabled="f.status === 'disabled'" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="停止条件">
                <el-input v-model="f.stopCondition" type="textarea" :rows="2" :disabled="f.status === 'disabled'" />
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>
      </div>
      <div class="layer-box new-layer">
        <div class="layer-head">
          <span class="box-title">新增追问层</span>
        </div>
        <el-form label-position="top">
          <el-row :gutter="16">
            <el-col :span="8">
              <el-form-item label="追问方向">
                <el-input v-model="newLayer.dir" placeholder="必填" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item label="追问内容">
            <el-input v-model="newLayer.text" type="textarea" :rows="2" placeholder="必填" />
          </el-form-item>
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="期望回答要点">
                <el-input v-model="newLayer.expect" type="textarea" :rows="2" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="停止条件">
                <el-input v-model="newLayer.stopCondition" type="textarea" :rows="2" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-button type="primary" size="small" :loading="addingLayer" @click="submitAddLayer">新增层</el-button>
        </el-form>
      </div>

      <el-divider content-position="left">评分要点</el-divider>
      <div class="section-hint">预制评分要点是判定尺子，LLM 只做对齐判断；新加要点保存后由后端分配编号。</div>
      <div v-for="section in rubricSections" :key="section.layerKey" class="rubric-group">
        <div class="rubric-head">
          <span class="box-title">{{ section.label }}</span>
          <el-tag size="small" effect="plain">{{ section.layerKey }}</el-tag>
          <el-tag v-if="section.disabled" size="small" type="info">已停用层</el-tag>
        </div>
        <div v-for="(point, pi) in section.points" :key="pi" class="point-row">
          <el-select v-model="point.level" size="small" style="width: 110px">
            <el-option v-for="item in constants.items('point_level')" :key="item.code" :label="item.name" :value="item.code" />
          </el-select>
          <el-input v-model="point.text" size="small" placeholder="要点描述" />
          <el-button size="small" text type="danger" @click="removePoint(section.layerKey, pi)">删除</el-button>
        </div>
        <el-button size="small" text type="primary" @click="addPoint(section.layerKey)">+ 添加要点</el-button>
      </div>

      <el-divider content-position="left">三档标准回答样例</el-divider>
      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="及格样例">
            <MarkdownEditor v-model="state.samples.pass" height="180px" placeholder="及格线水平的标准回答" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="良好样例">
            <MarkdownEditor v-model="state.samples.good" height="180px" placeholder="良好水平的标准回答" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="优秀样例">
            <MarkdownEditor v-model="state.samples.excellent" height="180px" placeholder="优秀水平的标准回答" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-divider content-position="left">叙述框架</el-divider>
      <MarkdownEditor v-model="state.narrativeFramework" height="220px" placeholder="作答应遵循的叙述框架（Markdown）" />

      <el-divider content-position="left">职级要求</el-divider>
      <div class="level-grid">
        <div v-for="t in LEVEL_TARGETS" :key="t" class="level-cell">
          <div class="level-head">
            <span>{{ constants.name('level', t) }}</span>
            <el-switch v-model="state.levelReq[t].required" active-text="必答" />
          </div>
          <el-select v-model="state.levelReq[t].stopLayer" clearable placeholder="停止追问层" style="width: 100%">
            <el-option v-for="opt in stopLayerOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </div>
      </div>

      <el-divider content-position="left">前置依赖</el-divider>
      <el-select
        v-model="state.prereqIds"
        multiple
        filterable
        remote
        clearable
        :remote-method="searchPrereq"
        :loading="prereqSearching"
        :reserve-keyword="false"
        placeholder="输入题面关键词搜索知识点"
        style="width: 100%"
      >
        <el-option v-for="opt in prereqOptions" :key="opt.id" :label="opt.title" :value="opt.id" />
      </el-select>

      <el-divider content-position="left">频率信号</el-divider>
      <el-table :data="state.freqSignals" size="small">
        <el-table-column label="来源" width="110">
          <template #default="{ row }">{{ constants.name('freq_source', row.source) }}</template>
        </el-table-column>
        <el-table-column label="档位" width="100">
          <template #default="{ row }">{{ row.signalLevel ? constants.name('freq_level', row.signalLevel) : '—' }}</template>
        </el-table-column>
        <el-table-column prop="signalCount" label="次数" width="80" />
        <el-table-column prop="note" label="说明" min-width="200" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="记录时间" width="170" />
      </el-table>
      <div class="signal-form">
        <el-select v-model="freqSignalForm.level" placeholder="信号档位" style="width: 140px">
          <el-option v-for="item in constants.items('freq_level')" :key="item.code" :label="item.name" :value="item.code" />
        </el-select>
        <el-input v-model="freqSignalForm.note" placeholder="标定说明" style="width: 320px" />
        <el-button type="primary" size="small" :loading="savingSignal" @click="submitFreqSignal">保存标定</el-button>
      </div>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.drawer-body {
  padding: 0 8px;
}

.section-hint {
  font-size: 12px;
  color: var(--zj-text-secondary, #909399);
  margin-bottom: 8px;
}

.section-hint.inline {
  margin-left: 8px;
}

.variant-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.layer-box {
  border: 1px solid var(--zj-border, #dcdfe6);
  border-radius: 6px;
  padding: 12px 16px;
  margin-bottom: 12px;
}

.layer-disabled {
  background: #f5f7fa;
}

.layer-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.layer-ops {
  margin-left: auto;
}

.box-title {
  font-weight: 600;
}

.new-layer {
  border-style: dashed;
}

.rubric-group {
  border: 1px solid var(--zj-border, #dcdfe6);
  border-radius: 6px;
  padding: 12px 16px;
  margin-bottom: 12px;
}

.rubric-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.point-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.level-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px 24px;
}

.level-cell {
  border: 1px solid var(--zj-border, #dcdfe6);
  border-radius: 6px;
  padding: 10px 14px;
}

.level-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-weight: 600;
}

.signal-form {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}
</style>
