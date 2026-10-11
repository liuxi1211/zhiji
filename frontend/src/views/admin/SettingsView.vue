<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  checkLlmConnectivity,
  getLlmSettings,
  getLlmUsage,
  updateLlmLimits,
  updateLlmRoutes
} from '@/api/settings'
import { useConstantsStore } from '@/stores/constants'
import type {
  LlmConnectivityReport,
  LlmLimitsView,
  LlmRouteTargetView,
  LlmUsageView
} from '@/api/types'

const constants = useConstantsStore()

const loading = ref(false)
const saving = ref(false)
const checking = ref(false)
const providers = ref<string[]>([])
const providerModels = ref<Record<string, string[]>>({})
const routes = reactive<Record<string, LlmRouteTargetView>>({})
const limits = reactive<LlmLimitsView>({
  judgeDaily: 200,
  variantRewriteDaily: 20,
  perProfileDailyTotal: 300,
  siteDailyTotal: 1000
})
const usage = ref<LlmUsageView | null>(null)
const report = ref<LlmConnectivityReport | null>(null)

const purposeOrder = computed(() => constants.items('llm_purpose').map((item) => item.code))

const routeRows = computed(() => {
  const order = purposeOrder.value
  return Object.keys(routes)
    .sort((a, b) => rankOf(order, a) - rankOf(order, b) || a.localeCompare(b))
    .map((purpose) => ({ purpose, target: routes[purpose] }))
})

function rankOf(order: string[], code: string): number {
  const index = order.indexOf(code)
  return index < 0 ? order.length : index
}

function label(purpose: string): string {
  return constants.name('llm_purpose', purpose)
}

function messageOf(error: unknown, fallback: string): string {
  const err = error as { message?: string }
  return err.message ?? fallback
}

async function load() {
  loading.value = true
  try {
    const settings = await getLlmSettings()
    providers.value = settings.providers
    providerModels.value = settings.providerModels ?? {}
    Object.keys(routes).forEach((key) => delete routes[key])
    Object.entries(settings.routes ?? {}).forEach(([key, value]) => {
      routes[key] = { ...value }
    })
    Object.assign(limits, settings.limits)
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

async function saveRoutes() {
  saving.value = true
  try {
    const payload: Record<string, LlmRouteTargetView> = {}
    Object.entries(routes).forEach(([key, value]) => {
      payload[key] = { ...value }
    })
    await updateLlmRoutes(payload)
    ElMessage.success('模型路由已保存，改动即生效')
  } catch (error) {
    ElMessage.error(messageOf(error, '保存失败'))
  } finally {
    saving.value = false
  }
}

async function saveLimits() {
  saving.value = true
  try {
    await updateLlmLimits({ ...limits })
    ElMessage.success('限额已保存，改动即生效')
    usage.value = await getLlmUsage()
  } catch (error) {
    ElMessage.error(messageOf(error, '保存失败'))
  } finally {
    saving.value = false
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
      <p>模型按用途路由、限额与熔断、连通性自检与用量对照；密钥走服务端配置，不在此处管理</p>
    </header>

    <el-card shadow="never">
      <template #header>
        <div class="toolbar">
          <span>模型路由（按用途选择 provider 与模型）</span>
          <el-button type="primary" size="small" :loading="saving" @click="saveRoutes">保存路由</el-button>
        </div>
      </template>
      <el-table :data="routeRows" size="small">
        <el-table-column label="入口" min-width="150">
          <template #default="{ row }">{{ label(row.purpose) }}</template>
        </el-table-column>
        <el-table-column label="Provider" width="150">
          <template #default="{ row }">
            <el-select v-model="row.target.provider" size="small" style="width: 100%">
              <el-option v-for="p in providers" :key="p" :label="p" :value="p" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="模型名" min-width="200">
          <template #default="{ row }">
            <el-select
              v-model="row.target.model"
              size="small"
              style="width: 100%"
              filterable
              allow-create
              default-first-option
              placeholder="选择或输入模型名"
            >
              <el-option v-for="m in providerModels[row.target.provider] ?? []" :key="m" :label="m" :value="m" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="温度" width="110">
          <template #default="{ row }">
            <el-input-number
              v-model="row.target.temperature"
              size="small"
              :min="0"
              :max="2"
              :step="0.1"
              :controls="false"
              style="width: 100%"
            />
          </template>
        </el-table-column>
        <el-table-column label="最大 Token" width="130">
          <template #default="{ row }">
            <el-input-number
              v-model="row.target.maxTokens"
              size="small"
              :min="1"
              :controls="false"
              style="width: 100%"
            />
          </template>
        </el-table-column>
        <el-table-column label="向量化" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.target.embedding" type="info" size="small">embedding</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="思考模式" width="120">
          <template #default="{ row }">
            <el-select
              v-if="!row.target.embedding"
              v-model="row.target.thinking"
              size="small"
              style="width: 100%"
              placeholder="默认"
            >
              <el-option label="跟随默认" :value="null" />
              <el-option label="开启" :value="true" />
              <el-option label="关闭" :value="false" />
            </el-select>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>
        <div class="toolbar">
          <span>限额与熔断（判定为软上限，超限仅告警；其余为硬熔断）</span>
          <el-button type="primary" size="small" :loading="saving" @click="saveLimits">保存限额</el-button>
        </div>
      </template>
      <el-form label-position="top" class="limit-form">
        <el-form-item label="判定类（练习 / 抽卡，次/档案/天）">
          <el-input-number v-model="limits.judgeDaily" :min="0" />
        </el-form-item>
        <el-form-item label="变体现场改写（次/档案/天）">
          <el-input-number v-model="limits.variantRewriteDaily" :min="0" />
        </el-form-item>
        <el-form-item label="单档案每日合计（硬熔断）">
          <el-input-number v-model="limits.perProfileDailyTotal" :min="0" />
        </el-form-item>
        <el-form-item label="全站每日总熔断">
          <el-input-number v-model="limits.siteDailyTotal" :min="0" />
        </el-form-item>
      </el-form>
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

.limit-form {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 0 24px;
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
