<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { checkLlmConnectivity, getLlmUsage } from '@/api/settings'
import { getConfigSections, updateConfigSection } from '@/api/configs'
import { useConstantsStore } from '@/stores/constants'
import ConfigFormRenderer from '@/components/config/ConfigFormRenderer.vue'
import ConfigTableRenderer from '@/components/config/ConfigTableRenderer.vue'
import ChannelEditor from '@/components/llm/ChannelEditor.vue'
import PromptEditor from '@/components/llm/PromptEditor.vue'
import type {
  ConfigFieldDef,
  ConfigSectionView,
  LlmConnectivityReport,
  LlmUsageView
} from '@/api/types'

/**
 * LLM 管理（独立模块页）：渠道与模型 / 模型路由 / 限额熔断 / 提示词模板 / 连通性与用量。
 * 渠道与提示词为定制渲染（components/llm/*），路由与限额复用通用配置渲染器（元数据驱动）。
 * 全部经通用配置接口 GET/PUT /api/admin/configs/{key}，保存后改动即生效。
 */
const constants = useConstantsStore()

const loading = ref(false)
const savingKey = ref<string | null>(null)
const activeTab = ref('channels')
const sections = ref<ConfigSectionView[]>([])
const drafts = reactive<Record<string, Record<string, any>>>({})
const promptEditorKey = ref(0)

const checking = ref(false)
const usage = ref<LlmUsageView | null>(null)
const report = ref<LlmConnectivityReport | null>(null)

const sectionOf = (key: string) => sections.value.find((section) => section.key === key)

const channelSection = computed(() => sectionOf('llm.channel'))
const routeSection = computed(() => sectionOf('llm.route'))
const limitsSection = computed(() => sectionOf('llm.limits'))
const promptSection = computed(() => sectionOf('llm.prompt'))

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
    const all = await getConfigSections()
    sections.value = all.filter((section) => section.key.startsWith('llm.'))
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

async function save(key: string) {
  savingKey.value = key
  try {
    const saved = await updateConfigSection(key, drafts[key])
    const index = sections.value.findIndex((section) => section.key === saved.key)
    if (index >= 0) {
      sections.value[index] = saved
    }
    drafts[saved.key] = cloneDraft(saved.value)
    if (saved.key === 'llm.prompt') {
      promptEditorKey.value += 1
    }
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
  <div class="llm-admin" v-loading="loading">
    <header class="zj-page-head">
      <h1>LLM 管理</h1>
      <p>模型渠道、路由、限额与提示词集中管理，保存后改动即生效；密钥脱敏显示，真实值不出后端</p>
    </header>

    <el-tabs v-model="activeTab" class="tabs">
      <el-tab-pane label="渠道与模型" name="channels">
        <el-card shadow="never">
          <ChannelEditor
            v-if="channelSection"
            :model-value="drafts['llm.channel']"
            :sources="channelSection.sources ?? {}"
            :saving="savingKey === 'llm.channel'"
            @save="save('llm.channel')"
          />
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="模型路由" name="route">
        <el-card v-if="routeSection" shadow="never">
          <template #header>
            <div class="toolbar">
              <span>
                {{ routeSection.title }}
                <span class="section-desc">{{ routeSection.description }}</span>
              </span>
              <el-button type="primary" size="small" :loading="savingKey === 'llm.route'" @click="save('llm.route')">
                保存
              </el-button>
            </div>
          </template>
          <ConfigTableRenderer
            :field="tableFieldOf(routeSection)!"
            :model-value="drafts['llm.route']"
            :sources="routeSection.sources ?? {}"
          />
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="限额熔断" name="limits">
        <el-card v-if="limitsSection" shadow="never">
          <template #header>
            <div class="toolbar">
              <span>
                {{ limitsSection.title }}
                <span class="section-desc">{{ limitsSection.description }}</span>
              </span>
              <el-button type="primary" size="small" :loading="savingKey === 'llm.limits'" @click="save('llm.limits')">
                保存
              </el-button>
            </div>
          </template>
          <ConfigFormRenderer :fields="formFieldsOf(limitsSection)" :model-value="drafts['llm.limits']" />
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="提示词模板" name="prompts">
        <el-card v-if="promptSection" shadow="never">
          <PromptEditor
            :key="promptEditorKey"
            :model-value="drafts['llm.prompt']"
            :sources="promptSection.sources ?? {}"
            :saving="savingKey === 'llm.prompt'"
            @save="save('llm.prompt')"
          />
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="连通性与用量" name="monitor">
        <div class="monitor">
          <el-card shadow="never">
            <template #header>
              <div class="toolbar">
                <span>全站状态（{{ usage?.date ?? '—' }}）</span>
                <el-tag v-if="usage?.circuitOpen" type="danger" size="small">全站熔断中</el-tag>
              </div>
            </template>
            <div v-if="usage" class="totals">
              <span>今日全部调用：{{ usage.todayTotal }} / 全站日熔断：{{ usage.siteDailyTotal }}</span>
              <span>
                近 7 天：
                <el-tag v-for="point in usage.recent7d" :key="point.date" size="small" class="day-tag">
                  {{ point.date }} · {{ point.callCount }}
                </el-tag>
              </span>
            </div>
          </el-card>

          <el-card shadow="never">
            <template #header>
              <div class="toolbar">
                <span>C 端用量（按账号）</span>
                <span class="quota-hint">
                  日 {{ usage?.customerDailyLimit ?? '—' }} · 周 {{ usage?.customerWeeklyLimit ?? '—' }} ·
                  月 {{ usage?.customerMonthlyLimit ?? '—' }}
                </span>
              </div>
            </template>
            <el-empty
              v-if="usage && !usage.customers.length"
              description="本月暂无 C 端调用"
              :image-size="60"
            />
            <el-table v-else-if="usage" :data="usage.customers">
              <el-table-column label="账号" min-width="180">
                <template #default="{ row }">{{ row.username }}</template>
              </el-table-column>
              <el-table-column label="今日" min-width="120">
                <template #default="{ row }">{{ row.daily }} / {{ usage.customerDailyLimit }}</template>
              </el-table-column>
              <el-table-column label="本周" min-width="120">
                <template #default="{ row }">{{ row.weekly }} / {{ usage.customerWeeklyLimit }}</template>
              </el-table-column>
              <el-table-column label="本月" min-width="120">
                <template #default="{ row }">{{ row.monthly }} / {{ usage.customerMonthlyLimit }}</template>
              </el-table-column>
              <el-table-column label="状态" width="130">
                <template #default="{ row }">
                  <el-tag v-if="row.dailyExceeded" type="danger" size="small">日超限</el-tag>
                  <el-tag v-else-if="row.weeklyExceeded" type="danger" size="small">周超限</el-tag>
                  <el-tag v-else-if="row.monthlyExceeded" type="danger" size="small">月超限</el-tag>
                  <span v-else>正常</span>
                </template>
              </el-table-column>
            </el-table>
          </el-card>

          <el-card shadow="never">
            <template #header>
              <div class="toolbar">
                <span>B 端用量（全站总额度）</span>
                <span class="quota-hint">
                  今日 {{ usage?.staffTodayTotal ?? 0 }} / {{ usage?.staffDaily ?? '—' }} · 本周
                  {{ usage?.staffWeekly ?? '—' }} · 本月 {{ usage?.staffMonthly ?? '—' }}
                </span>
              </div>
            </template>
            <el-empty v-if="usage && !usage.staff.length" description="无 B 端入口" :image-size="60" />
            <el-table v-else-if="usage" :data="usage.staff">
              <el-table-column label="入口" min-width="200">
                <template #default="{ row }">{{ row.purposeName }}</template>
              </el-table-column>
              <el-table-column prop="purpose" label="code" min-width="180" />
              <el-table-column label="今日计数" width="120">
                <template #default="{ row }">{{ row.today }}</template>
              </el-table-column>
            </el-table>
          </el-card>

          <el-card shadow="never">
            <template #header>
              <div class="toolbar">
                <span>模型连通性自检</span>
                <el-button size="small" :loading="checking" @click="runConnectivityCheck">开始自检</el-button>
              </div>
            </template>
            <el-empty v-if="!report" description="点击「开始自检」按当前路由验证渠道密钥与网络" :image-size="60" />
            <el-table v-else :data="report.items">
              <el-table-column label="入口" min-width="150">
                <template #default="{ row }">{{ label(row.purpose) }}</template>
              </el-table-column>
              <el-table-column prop="provider" label="渠道" width="120" />
              <el-table-column prop="model" label="模型" min-width="180" />
              <el-table-column label="结果" width="90">
                <template #default="{ row }">
                  <el-tag :type="row.ok ? 'success' : 'danger'" size="small">{{ row.ok ? '正常' : '失败' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="detail" label="说明" min-width="220" />
            </el-table>
          </el-card>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.llm-admin {
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

.monitor {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.quota-hint {
  font-size: var(--zj-fs-sm);
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
