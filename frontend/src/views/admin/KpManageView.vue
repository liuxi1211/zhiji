<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createKp, getKpMeta, listKp, retireKp } from '@/api/kb'
import { useConstantsStore } from '@/stores/constants'
import ClusterManageDialog from './kp/ClusterManageDialog.vue'
import KpEditDrawer from './kp/KpEditDrawer.vue'
import DifficultyChip from '@/components/DifficultyChip.vue'
import type { KpListItem, KpMeta, KpStatus, SubType } from '@/api/types'

const constants = useConstantsStore()

const meta = ref<KpMeta | null>(null)

const filters = reactive({
  keyword: '',
  domainId: 0,
  clusterId: 0,
  subType: '' as SubType | '',
  status: '',
  difficulty: 0,
  baseline: '' as '' | 'marked' | 'unmarked',
  reviewDue: '' as '' | 'yes' | 'no'
})

const list = ref<KpListItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

const filterClusterOptions = computed(() => {
  const all = meta.value?.clusters ?? []
  if (filters.domainId === 0) {
    return all
  }
  return all.filter((c) => c.domainId === filters.domainId)
})

function messageOf(error: unknown, fallback: string): string {
  const err = error as { message?: string }
  return err?.message ?? fallback
}

async function loadMeta() {
  try {
    meta.value = await getKpMeta()
  } catch (error) {
    ElMessage.error(messageOf(error, '加载元数据失败'))
  }
}

async function load() {
  loading.value = true
  try {
    const res = await listKp({
      keyword: filters.keyword.trim() || undefined,
      domainId: filters.domainId || undefined,
      clusterId: filters.clusterId || undefined,
      subType: (filters.subType || undefined) as SubType | undefined,
      status: filters.status || undefined,
      difficulty: filters.difficulty || undefined,
      baseline: (filters.baseline || undefined) as 'marked' | 'unmarked' | undefined,
      reviewDue: filters.reviewDue === '' ? undefined : filters.reviewDue === 'yes',
      page: page.value,
      size: size.value
    })
    list.value = res.list
    total.value = res.total
  } catch (error) {
    ElMessage.error(messageOf(error, '加载知识点列表失败'))
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  void load()
}

function onFilterDomainChange() {
  filters.clusterId = 0
  search()
}

function resetFilters() {
  filters.keyword = ''
  filters.domainId = 0
  filters.clusterId = 0
  filters.subType = ''
  filters.status = ''
  filters.difficulty = 0
  filters.baseline = ''
  filters.reviewDue = ''
  search()
}

function onSizeChange() {
  page.value = 1
  void load()
}

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({
  domainId: null as number | null,
  clusterId: null as number | null,
  subType: '' as SubType | '',
  title: '',
  difficulty: 3
})

const createClusterOptions = computed(() => {
  if (createForm.domainId == null) {
    return []
  }
  return (meta.value?.clusters ?? []).filter(
    (c) => c.domainId === createForm.domainId && c.status !== 'retired'
  )
})

function openCreate() {
  createForm.domainId = null
  createForm.clusterId = null
  createForm.subType = ''
  createForm.title = ''
  createForm.difficulty = 3
  createVisible.value = true
}

async function submitCreate() {
  if (!createForm.domainId) {
    ElMessage.warning('请选择技能域')
    return
  }
  if (!createForm.clusterId) {
    ElMessage.warning('请选择主簇')
    return
  }
  if (!createForm.subType) {
    ElMessage.warning('请选择题型')
    return
  }
  if (!createForm.title.trim()) {
    ElMessage.warning('请填写题面')
    return
  }
  creating.value = true
  try {
    const { id } = await createKp({
      clusterId: createForm.clusterId,
      subType: createForm.subType,
      title: createForm.title.trim(),
      difficulty: createForm.difficulty
    })
    ElMessage.success('已创建，请继续补全知识点结构')
    createVisible.value = false
    await load()
    openDrawer(id)
  } catch (error) {
    ElMessage.error(messageOf(error, '创建失败'))
  } finally {
    creating.value = false
  }
}

const drawerVisible = ref(false)
const editKpId = ref<number | null>(null)

function openDrawer(id: number) {
  editKpId.value = id
  drawerVisible.value = true
}

function onDrawerSaved() {
  void load()
}

const clusterDialogVisible = ref(false)

function onClusterChanged() {
  void loadMeta()
  void load()
}

const retireVisible = ref(false)
const retireTarget = ref<KpListItem | null>(null)
const retireForm = reactive<{ mode: 'discard' | 'merge'; targetId: number | null }>({
  mode: 'discard',
  targetId: null
})
const retireOptions = ref<{ id: number; title: string }[]>([])
const retireSearching = ref(false)

function openRetire(row: KpListItem) {
  retireTarget.value = row
  retireForm.mode = 'discard'
  retireForm.targetId = null
  retireOptions.value = []
  retireVisible.value = true
}

async function searchRetireTarget(keyword: string) {
  if (!keyword.trim() || !retireTarget.value) {
    return
  }
  retireSearching.value = true
  try {
    const { list: rows } = await listKp({ keyword: keyword.trim(), size: 20 })
    retireOptions.value = rows
      .filter((row) => row.id !== retireTarget.value?.id && row.status !== 'retired')
      .map((row) => ({ id: row.id, title: row.title }))
  } catch (error) {
    ElMessage.error(messageOf(error, '搜索失败'))
  } finally {
    retireSearching.value = false
  }
}

async function submitRetire() {
  const target = retireTarget.value
  if (!target) {
    return
  }
  if (retireForm.mode === 'merge' && !retireForm.targetId) {
    ElMessage.warning('请选择合并目标知识点')
    return
  }
  try {
    await ElMessageBox.confirm(
      retireForm.mode === 'discard'
        ? `确认废弃知识点「${target.title}」？废弃后不可恢复。`
        : `确认将「${target.title}」合并到所选知识点？本条将转为 retired 并保留指向。`,
      '下线知识点',
      { type: 'warning', confirmButtonText: '确认下线', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    if (retireForm.mode === 'merge' && retireForm.targetId) {
      await retireKp(target.id, { mode: 'merge', targetId: retireForm.targetId })
    } else {
      await retireKp(target.id, { mode: 'discard' })
    }
    ElMessage.success('已下线')
    retireVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(messageOf(error, '下线失败'))
  }
}

function statusTagType(status: KpStatus): 'info' | 'warning' | 'success' | 'danger' {
  if (status === 'draft') return 'info'
  if (status === 'reviewed') return 'warning'
  if (status === 'active') return 'success'
  return 'danger'
}

onMounted(() => {
  void constants.ensure()
  void loadMeta()
  void load()
})
</script>

<template>
  <section class="kp-manage">
    <header class="zj-page-head">
      <h1>知识点管理</h1>
      <p>知识点与主题簇的维护中心：题面、追问链、评分要点与内容状态机</p>
    </header>

    <el-card shadow="never">
      <template #header>
        <div class="toolbar">
          <span class="toolbar-title">
            共 <b class="zj-num">{{ total }}</b> 个知识点
          </span>
          <span class="toolbar-actions">
            <el-button size="small" @click="clusterDialogVisible = true">主题簇管理</el-button>
            <el-button type="primary" size="small" @click="openCreate">新建知识点</el-button>
          </span>
        </div>
      </template>

      <el-alert
        v-if="meta && meta.domains.length === 0"
        type="warning"
        :closable="false"
        show-icon
        title="技能域字典为空，请先通过种子数据（src/main/resources/db/sample-data.sql）或 T3 域管理建立域"
        class="domain-alert"
      />

      <div class="filters">
        <el-input
          v-model="filters.keyword"
          placeholder="题面关键词"
          clearable
          style="width: 200px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-select v-model="filters.domainId" style="width: 140px" @change="onFilterDomainChange">
          <el-option label="全部域" :value="0" />
          <el-option v-for="d in meta?.domains ?? []" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
        <el-select v-model="filters.clusterId" style="width: 160px" @change="search">
          <el-option label="全部簇" :value="0" />
          <el-option v-for="c in filterClusterOptions" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-select v-model="filters.subType" style="width: 120px" @change="search">
          <el-option label="全部题型" value="" />
          <el-option v-for="item in constants.items('sub_type')" :key="item.code" :label="item.name" :value="item.code" />
        </el-select>
        <el-select v-model="filters.status" style="width: 120px" @change="search">
          <el-option label="全部状态" value="" />
          <el-option v-for="item in constants.items('content_status')" :key="item.code" :label="item.name" :value="item.code" />
        </el-select>
        <el-select v-model="filters.difficulty" style="width: 110px" @change="search">
          <el-option label="全部难度" :value="0" />
          <el-option v-for="n in 5" :key="n" :label="`难度${n}`" :value="n" />
        </el-select>
        <el-select v-model="filters.baseline" style="width: 120px" @change="search">
          <el-option label="全部基线" value="" />
          <el-option label="已标基线" value="marked" />
          <el-option label="未标基线" value="unmarked" />
        </el-select>
        <el-select v-model="filters.reviewDue" style="width: 120px" @change="search">
          <el-option label="复核到期全部" value="" />
          <el-option label="已到期" value="yes" />
          <el-option label="未到期" value="no" />
        </el-select>
        <span class="filter-actions">
          <el-button size="small" @click="search">搜索</el-button>
          <el-button size="small" @click="resetFilters">重置</el-button>
        </span>
      </div>

      <div class="table-wrap">
        <el-table v-loading="loading" :data="list" size="small" height="100%">
        <el-table-column prop="title" label="题面" min-width="240" show-overflow-tooltip />
        <el-table-column prop="domainName" label="技能域" width="110" show-overflow-tooltip />
        <el-table-column prop="clusterName" label="主簇" width="140" show-overflow-tooltip />
        <el-table-column label="题型" width="100">
          <template #default="{ row }">{{ constants.name('sub_type', row.subType) }}</template>
        </el-table-column>
        <el-table-column label="难度" width="110">
          <template #default="{ row }">
            <DifficultyChip :level="row.difficulty" />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="92">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ constants.name('content_status', row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近校对" width="170">
          <template #default="{ row }"><span class="zj-num">{{ row.lastReviewedAt ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="复核到期" width="170">
          <template #default="{ row }"><span class="zj-num">{{ row.reviewDueAt ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openDrawer(row.id)">编辑</el-button>
            <el-button v-if="row.status !== 'retired'" size="small" text type="danger" @click="openRetire(row)">下线</el-button>
          </template>
        </el-table-column>
        </el-table>
      </div>

      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="load"
        @size-change="onSizeChange"
      />
    </el-card>

    <el-dialog v-model="createVisible" title="新建知识点" width="560px" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="技能域">
              <el-select
                v-model="createForm.domainId"
                style="width: 100%"
                placeholder="先选域"
                @change="createForm.clusterId = null"
              >
                <el-option v-for="d in meta?.domains ?? []" :key="d.id" :label="d.name" :value="d.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="主簇">
              <el-select v-model="createForm.clusterId" style="width: 100%" placeholder="按域过滤">
                <el-option v-for="c in createClusterOptions" :key="c.id" :label="c.name" :value="c.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="题型">
              <el-select v-model="createForm.subType" style="width: 100%" placeholder="请选择题型">
                <el-option v-for="item in constants.items('sub_type')" :key="item.code" :label="item.name" :value="item.code" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="难度">
              <el-select v-model="createForm.difficulty" style="width: 100%">
                <el-option v-for="n in 5" :key="n" :label="`难度${n}`" :value="n" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="题面">
          <el-input v-model="createForm.title" type="textarea" :rows="2" placeholder="面试题题面" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="retireVisible" title="下线知识点" width="480px" :close-on-click-modal="false">
      <div v-if="retireTarget" class="retire-target">
        <el-tag :type="statusTagType(retireTarget.status)" size="small">
          {{ constants.name('content_status', retireTarget.status) }}
        </el-tag>
        <span class="retire-title">{{ retireTarget.title }}</span>
      </div>
      <el-form label-position="top">
        <el-form-item label="下线方式">
          <el-radio-group v-model="retireForm.mode">
            <el-radio value="discard">废弃（无去向）</el-radio>
            <el-radio value="merge">合并到其他知识点</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="retireForm.mode === 'merge'" label="合并目标">
          <el-select
            v-model="retireForm.targetId"
            filterable
            remote
            clearable
            :remote-method="searchRetireTarget"
            :loading="retireSearching"
            :reserve-keyword="false"
            placeholder="输入题面关键词搜索"
            style="width: 100%"
          >
            <el-option v-for="opt in retireOptions" :key="opt.id" :label="opt.title" :value="opt.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="retireVisible = false">取消</el-button>
        <el-button type="danger" @click="submitRetire">确认下线</el-button>
      </template>
    </el-dialog>

    <KpEditDrawer v-model="drawerVisible" :kp-id="editKpId" :meta="meta" @saved="onDrawerSaved" />
    <ClusterManageDialog v-model="clusterDialogVisible" :meta="meta" @changed="onClusterChanged" />
  </section>
</template>

<style scoped>
/* 整页填满视口：表格区吃掉剩余高度并内部滚动（配合 el-table height="100%"），表头吸顶，
 * 筛选与分页常驻可见；窗口过矮时 table-wrap 保底 200px，页面退化为整体滚动 */
.kp-manage {
  display: flex;
  flex-direction: column;
  min-height: 100%;
}

.kp-manage > .el-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.kp-manage > .el-card :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.table-wrap {
  flex: 1;
  min-height: 200px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.toolbar-title {
  font-weight: 600;
}

.toolbar-title b {
  color: var(--zj-primary);
  font-weight: 700;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.domain-alert {
  margin-bottom: 12px;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.filter-actions {
  display: inline-flex;
  gap: 8px;
  margin-left: 4px;
}

.pager {
  margin-top: 16px;
  justify-content: flex-end;
  flex-wrap: wrap;
  row-gap: 8px;
}

.retire-target {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.retire-title {
  font-weight: 600;
}
</style>
