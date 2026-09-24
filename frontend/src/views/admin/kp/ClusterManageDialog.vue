<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import MarkdownEditor from '@/components/MarkdownEditor.vue'
import DifficultyChip from '@/components/DifficultyChip.vue'
import {
  changeClusterStatus,
  createCluster,
  getCluster,
  listClusters,
  mergeCluster,
  updateCluster
} from '@/api/kb'
import { useConstantsStore } from '@/stores/constants'
import type { KpClusterDetail, KpClusterListItem, KpMeta, KpStatus } from '@/api/types'

const props = defineProps<{ modelValue: boolean; meta: KpMeta | null }>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'changed'): void
}>()

const constants = useConstantsStore()

const loading = ref(false)
const clusters = ref<KpClusterListItem[]>([])
const filterDomainId = ref(0)
const selected = ref<KpClusterDetail | null>(null)
const panelMode = ref<'detail' | 'form'>('detail')

const form = reactive({
  id: null as number | null,
  domainId: null as number | null,
  name: '',
  description: '',
  zeroShotMd: '',
  referenceSolutionMd: ''
})
const saving = ref(false)

const mergeVisible = ref(false)
const mergeSource = ref<KpClusterListItem | null>(null)
const mergeTargetId = ref<number | null>(null)

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      panelMode.value = 'detail'
      selected.value = null
      void loadClusters()
    }
  }
)

function messageOf(error: unknown, fallback: string): string {
  const err = error as { message?: string }
  return err?.message ?? fallback
}

async function loadClusters() {
  loading.value = true
  try {
    const { list } = await listClusters(
      filterDomainId.value === 0 ? undefined : { domainId: filterDomainId.value }
    )
    clusters.value = list
  } catch (error) {
    ElMessage.error(messageOf(error, '加载簇列表失败'))
  } finally {
    loading.value = false
  }
}

function onFilterChange() {
  selected.value = null
  panelMode.value = 'detail'
  void loadClusters()
}

const domainOptions = computed(() => props.meta?.domains ?? [])

const formDomainOptions = computed(() => {
  const list = domainOptions.value.filter((d) => d.status === 'active')
  // 编辑态所属域若已停用不在可选项里，补回避免回显成数字
  if (form.domainId != null && !list.some((d) => d.id === form.domainId)) {
    const current = domainOptions.value.find((d) => d.id === form.domainId)
    if (current) {
      list.push(current)
    }
  }
  return list
})

const mergeTargets = computed(() =>
  clusters.value.filter(
    (c) =>
      mergeSource.value != null &&
      c.id !== mergeSource.value.id &&
      c.domainId === mergeSource.value.domainId &&
      c.status !== 'retired'
  )
)

async function openCluster(row: KpClusterListItem) {
  try {
    selected.value = await getCluster(row.id)
    panelMode.value = 'detail'
  } catch (error) {
    ElMessage.error(messageOf(error, '加载簇详情失败'))
  }
}

function openCreate() {
  form.id = null
  form.domainId = formDomainOptions.value[0]?.id ?? null
  form.name = ''
  form.description = ''
  form.zeroShotMd = ''
  form.referenceSolutionMd = ''
  panelMode.value = 'form'
}

async function openEdit(row: KpClusterListItem) {
  try {
    const detail = await getCluster(row.id)
    form.id = detail.id
    form.domainId = detail.domainId
    form.name = detail.name
    form.description = detail.description ?? ''
    form.zeroShotMd = detail.zeroShotMd ?? ''
    form.referenceSolutionMd = detail.referenceSolutionMd ?? ''
    panelMode.value = 'form'
  } catch (error) {
    ElMessage.error(messageOf(error, '加载簇详情失败'))
  }
}

async function submitForm() {
  if (form.domainId == null) {
    ElMessage.warning('请选择所属域')
    return
  }
  if (!form.name.trim()) {
    ElMessage.warning('请填写簇名')
    return
  }
  saving.value = true
  try {
    if (form.id == null) {
      await createCluster({
        domainId: form.domainId,
        name: form.name.trim(),
        description: form.description.trim() || undefined
      })
      ElMessage.success('簇已创建')
    } else {
      await updateCluster(form.id, {
        domainId: form.domainId,
        name: form.name.trim(),
        description: form.description.trim(),
        zeroShotMd: form.zeroShotMd,
        referenceSolutionMd: form.referenceSolutionMd
      })
      ElMessage.success('簇已保存')
    }
    panelMode.value = 'detail'
    await loadClusters()
    emit('changed')
  } catch (error) {
    ElMessage.error(messageOf(error, '保存失败'))
  } finally {
    saving.value = false
  }
}

async function forward(row: KpClusterListItem) {
  // 状态机只允许正向推进：draft → reviewed → active
  const next: KpStatus | null = row.status === 'draft' ? 'reviewed' : row.status === 'reviewed' ? 'active' : null
  if (!next) {
    return
  }
  try {
    await ElMessageBox.confirm(`确认将簇「${row.name}」${next === 'reviewed' ? '提交校对' : '上线'}？`, '推进状态', {
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await changeClusterStatus(row.id, next)
    ElMessage.success('已推进')
    await loadClusters()
    emit('changed')
    if (selected.value?.id === row.id) {
      await openCluster(row)
    }
  } catch (error) {
    ElMessage.error(messageOf(error, '推进失败'))
  }
}

function openMerge(row: KpClusterListItem) {
  mergeSource.value = row
  mergeTargetId.value = null
  mergeVisible.value = true
}

async function submitMerge() {
  const source = mergeSource.value
  const targetId = mergeTargetId.value
  if (!source || !targetId) {
    ElMessage.warning('请选择目标簇')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认将簇「${source.name}」合并进所选目标簇？合并后本簇转为 retired 并保留指向，知识点一并迁移。`,
      '合并簇',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await mergeCluster(source.id, targetId)
    ElMessage.success('已合并')
    mergeVisible.value = false
    selected.value = null
    await loadClusters()
    emit('changed')
  } catch (error) {
    ElMessage.error(messageOf(error, '合并失败'))
  }
}

function statusTagType(status: KpStatus): 'info' | 'warning' | 'success' | 'danger' {
  if (status === 'draft') return 'info'
  if (status === 'reviewed') return 'warning'
  if (status === 'active') return 'success'
  return 'danger'
}

</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="主题簇管理"
    width="80%"
    top="5vh"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="cluster-wrap">
      <div class="cluster-left">
        <div class="cluster-toolbar">
          <el-select v-model="filterDomainId" size="small" style="width: 160px" @change="onFilterChange">
            <el-option label="全部域" :value="0" />
            <el-option v-for="d in domainOptions" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
          <el-button type="primary" size="small" @click="openCreate">新建簇</el-button>
        </div>
        <el-table :data="clusters" size="small" highlight-current-row @row-click="openCluster">
          <el-table-column prop="name" label="簇名" min-width="140" show-overflow-tooltip />
          <el-table-column prop="domainName" label="域" width="110" show-overflow-tooltip />
          <el-table-column label="状态" width="88">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small">{{ constants.name('content_status', row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="kpCount" label="知识点数" width="88" />
          <el-table-column label="操作" width="210">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click.stop="openEdit(row)">编辑</el-button>
              <el-button v-if="row.status === 'draft'" size="small" text type="warning" @click.stop="forward(row)">提交校对</el-button>
              <el-button v-else-if="row.status === 'reviewed'" size="small" text type="success" @click.stop="forward(row)">上线</el-button>
              <el-button size="small" text type="danger" @click.stop="openMerge(row)">合并</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="cluster-right">
        <template v-if="panelMode === 'form'">
          <div class="panel-title">{{ form.id == null ? '新建簇' : `编辑簇：${form.name}` }}</div>
          <el-form label-position="top">
            <el-form-item label="所属域">
              <el-select v-model="form.domainId" style="width: 100%">
                <el-option v-for="d in formDomainOptions" :key="d.id" :label="d.name" :value="d.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="簇名">
              <el-input v-model="form.name" placeholder="主题簇名称" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="form.description" type="textarea" :rows="2" />
            </el-form-item>
            <el-form-item label="从零讲">
              <MarkdownEditor v-model="form.zeroShotMd" height="200px" placeholder="该主题从零讲起的系统性内容（Markdown）" />
            </el-form-item>
            <el-form-item label="完整参考方案">
              <MarkdownEditor v-model="form.referenceSolutionMd" height="200px" placeholder="完整参考答案 / 方案（Markdown）" />
            </el-form-item>
          </el-form>
          <div class="panel-actions">
            <el-button size="small" @click="panelMode = 'detail'">返回</el-button>
            <el-button type="primary" size="small" :loading="saving" @click="submitForm">保存</el-button>
          </div>
        </template>
        <template v-else-if="selected">
          <div class="panel-title">
            {{ selected.name }}
            <el-tag :type="statusTagType(selected.status)" size="small">
              {{ constants.name('content_status', selected.status) }}
            </el-tag>
          </div>
          <p class="cluster-desc">{{ selected.description || '暂无描述' }}</p>
          <el-table :data="selected.kpList" size="small">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="title" label="题面" min-width="200" show-overflow-tooltip />
            <el-table-column label="状态" width="88">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.status)" size="small">{{ constants.name('content_status', row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="难度" width="110">
              <template #default="{ row }">
                <DifficultyChip :level="row.difficulty" />
              </template>
            </el-table-column>
          </el-table>
        </template>
        <el-empty v-else description="点击左侧簇行，查看簇下知识点" :image-size="60" />
      </div>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>

    <el-dialog v-model="mergeVisible" title="合并簇" width="460px" append-to-body>
      <el-form label-position="top">
        <el-form-item :label="`合并到同域下其他簇（${mergeSource?.name ?? ''}）`">
          <el-select v-model="mergeTargetId" placeholder="选择目标簇" style="width: 100%">
            <el-option v-for="c in mergeTargets" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mergeVisible = false">取消</el-button>
        <el-button type="primary" @click="submitMerge">确认合并</el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<style scoped>
.cluster-wrap {
  display: flex;
  gap: 20px;
  min-height: 420px;
}

.cluster-left {
  flex: 1.4;
  min-width: 0;
}

.cluster-right {
  flex: 1;
  min-width: 0;
  border-left: 1px solid var(--zj-line);
  padding-left: 20px;
}

.cluster-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  margin-bottom: 8px;
}

.cluster-desc {
  font-size: 13px;
  color: var(--zj-ink-500);
  margin: 0 0 12px;
}

.panel-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
