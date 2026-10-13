<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { checkLlmConnectivity, getLlmUsage } from '@/api/settings'
import { getConfigSections, updateConfigSection } from '@/api/configs'
import { useConstantsStore } from '@/stores/constants'
import ConfigFormRenderer from '@/components/config/ConfigFormRenderer.vue'
import ConfigTableRenderer from '@/components/config/ConfigTableRenderer.vue'
import type {
  ConfigFieldDef,
  ConfigSectionView,
  LlmConnectivityReport,
  LlmUsageView
} from '@/api/types'

const constants = useConstantsStore()

const loading = ref(false)
const savingKey = ref<string | null>(null)
const checking = ref(false)
const sections = ref<ConfigSectionView[]>([])
const drafts = reactive<Record<string, Record<string, any>>>({})
const usage = ref<LlmUsageView | null>(null)
const report = ref<LlmConnectivityReport | null>(null)

const sortedSections = computed(() => [...sections.value].sort((a, b) => a.order - b.order))

function formFieldsOf(section: ConfigSectionView): ConfigFieldDef[] {
  return section.fields.filter((field) => field.type !== 'TABLE')
}

function tableFieldOf(section: ConfigSectionView): ConfigFieldDef | undefined {
  return section.fields.find((field) => field.type === 'TABLE')
}

function label(purpose: string): string {
  return constants.name('llm_purpose', purpose)
}

function messageOf(error: unknown, fallback: string): string {
  const err = error as { message?: string }
  return err.message ?? fallback
}

function cloneDraft(value: Record<string, unknown>): Record<string, any> {
  return JSON.parse(JSON.stringify(value ?? {}))
}

async function load() {
  loading.value = true
  try {
    sections.value = await getConfigSections()
    sections.value.forEach((section) => {
      drafts[section.key] = cloneDraft(section.value)
    })
    usage.value = await getLlmUsage()
  } catch (error) {
    ElMessage.error(messageOf(error, '加载失败'))
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void constants.ensure()
  void load()
})

async function save(section: ConfigSectionView) {
  savingKey.value = section.key
  try {
    const saved = await updateConfigSection(section.key, drafts[section.key])
    const index = sections.value.findIndex((item) => item.key === saved.key)
    if (index >= 0) {
      sections.value[index] = saved
    }
    drafts[saved.key] = cloneDraft(saved.value)
    ElMessage.success(`${saved.title}已保存，改动即生效`)
  } catch (error) {
    ElMessage.error(messageOf(error, '保存失败'))
  } finally {
    savingKey.value = null
  }
}

async function runConnectivityCheck() {
  checking.value = true
  try {
    report.value = await checkLlmConnectivity()
  } catch (error) {
    ElMessage.error(messageOf(error, '自检失败'))
  } finally {
    checking.value = false
  }
}
</script>

<template>
  <div class="settings" v-loading="loading">
    <header class="zj-page-head">
      <h1>系统配置</h1>
      <p>系统运行参数集中配置，保存后改动即生效；密钥走服务端配置，不在此处管理</p>
    </header>

    <el-card v-for="section in sortedSections" :key="section.key" shadow="never">
      <template #header>
        <div class="toolbar">
          <span>
            {{ section.title }}
            <span v-if="section.description" class="section-desc">{{ section.description }}</span>
          </span>
          <el-button type="primary" size="small" :loading="savingKey === section.key" @click="save(section)">
            保存
          </el-button>
        </div>
      </template>
      <ConfigTableRenderer
        v-if="tableFieldOf(section)"
        :field="tableFieldOf(section)!"
        :model-value="drafts[section.key]"
        :sources="section.sources ?? {}"
      />
      <ConfigFormRenderer v-else :fields="formFieldsOf(section)" :model-value="drafts[section.key]" />
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>
        <div class="toolbar">
          <span>模型连通性自检</span>
          <el-button size="small" :loading="checking" @click="runConnectivityCheck">开始自检</el-button>
        </div>
      </template>
      <el-empty v-if="!report" description="点击「开始自检」验证 key 与网络" :image-size="60" />
      <el-table v-else :data="report.items" size="small">
        <el-table-column label="入口" min-width="150">
          <template #default="{ row }">{{ label(row.purpose) }}</template>
        </el-table-column>
        <el-table-column prop="provider" label="Provider" width="120" />
        <el-table-column prop="model" label="模型" min-width="180" />
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.ok ? 'success' : 'danger'" size="small">{{ row.ok ? '正常' : '失败' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="说明" min-width="220" />
      </el-table>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>
        <div class="toolbar">
          <span>LLM 用量对照（{{ usage?.date ?? '—' }}）</span>
          <el-tag v-if="usage?.circuitOpen" type="danger" size="small">全站熔断中</el-tag>
        </div>
      </template>
      <el-table v-if="usage" :data="usage.purposes" size="small">
        <el-table-column label="入口" min-width="150">
          <template #default="{ row }">{{ row.purposeName }}</template>
        </el-table-column>
        <el-table-column label="侧别" width="70">
          <template #default="{ row }">
            <el-tag :type="row.side === 'B' ? 'warning' : 'primary'" size="small" disable-transitions>
              {{ row.side === 'B' ? 'B 端' : 'C 端' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="limitKey" label="限额档" width="100" />
        <el-table-column prop="callCount" label="全站计数" width="100" />
        <el-table-column label="单档案峰值" width="120">
          <template #default="{ row }">{{ row.peakProfileCount }}</template>
        </el-table-column>
        <el-table-column label="单档案限额" width="120">
          <template #default="{ row }">{{ row.limit ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.exceeded && row.soft" type="warning" size="small">超软限（不阻断）</el-tag>
            <el-tag v-else-if="row.exceeded" type="danger" size="small">超硬限</el-tag>
            <span v-else>正常</span>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="usage" class="totals">
        <span>全站合计：{{ usage.todayTotal }} / 熔断阈值：{{ usage.siteDailyTotal }}</span>
        <span>
          单档案合计峰值：{{ usage.peakProfileTotal }} / 兜底阈值：{{ usage.perProfileDailyTotal }}
          <el-tag v-if="usage.perProfileExceeded" type="danger" size="small">触达单档案兜底</el-tag>
        </span>
        <span class="recent">
          近 7 天：
          <el-tag v-for="point in usage.recent7d" :key="point.date" size="small" class="day-tag">
            {{ point.date }} · {{ point.callCount }}
          </el-tag>
        </span>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.settings {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.section-desc {
  margin-left: 8px;
  font-size: 13px;
  color: var(--zj-ink-500);
}

.totals {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
  font-size: 13px;
  color: var(--zj-ink-500);
}

.recent {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.day-tag {
  font-weight: 400;
}
</style>
