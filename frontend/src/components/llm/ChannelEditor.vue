<script setup lang="ts">
import { computed, reactive } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import { checkLlmChannel, type LlmChannelProbe } from '@/api/settings'
import { useConstantsStore } from '@/stores/constants'

/**
 * 渠道与模型（llm.channel 域）定制编辑器：一渠道一卡，密钥脱敏展示（****abcd）。
 * 直接改写传入的草稿对象（与通用渲染器同一模式），保存由父页面统一 PUT。
 * 约定：已有渠道名不可改（渠道名是路由引用的 key）；密钥留空/保持掩码 = 不修改（后端 merge 还原）。
 * 卡片头部展示路由引用徽标（llm.channelUsage：当前哪些入口在用该渠道），并提供单渠道连通测试。
 */
interface Channel {
  protocol?: string
  baseUrl?: string
  apiKey?: string
  models: string[]
  defaultModel?: string
}

interface ProbeState extends Partial<LlmChannelProbe> {
  loading?: boolean
}

const props = defineProps<{
  modelValue: Record<string, any>
  sources?: Record<string, unknown>
  saving?: boolean
}>()

const emit = defineEmits<{ (e: 'save'): void }>()

const constants = useConstantsStore()

const channels = computed<Record<string, Channel>>(() => {
  const value = props.modelValue?.channels
  return value && typeof value === 'object' ? value : {}
})

const entries = computed(() => Object.entries(channels.value))

/** 渠道名 → 引用它的路由入口 codes（随 llm.channel 域 sources 下发） */
const usage = computed<Record<string, string[]>>(() => {
  const value = props.sources?.['llm.channelUsage']
  return value && typeof value === 'object' ? (value as Record<string, string[]>) : {}
})

const usageOf = (name: string): string[] => usage.value[name] ?? []

const usageNamesOf = (name: string): string =>
  usageOf(name).map((code) => constants.name('llm_purpose', code)).join('、')

const probes = reactive<Record<string, ProbeState>>({})

const PROTOCOLS = [
  { value: 'openai', label: 'openai（OpenAI 兼容）' },
  { value: 'anthropic', label: 'anthropic（messages）' }
]

function addChannel() {
  let name = 'new-channel'
  let suffix = 1
  while (channels.value[name]) {
    name = `new-channel-${++suffix}`
  }
  channels.value[name] = { protocol: 'openai', baseUrl: '', apiKey: '', models: [], defaultModel: '' }
}

async function removeChannel(name: string) {
  try {
    await ElMessageBox.confirm(
      `确定删除渠道「${name}」？若它仍被模型路由引用，保存将被后端拒绝。`,
      '删除渠道',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  delete channels.value[name]
  delete probes[name]
}

async function testChannel(name: string) {
  const state = probes[name] ?? (probes[name] = {})
  state.loading = true
  try {
    const result = await checkLlmChannel(name)
    state.ok = result.ok
    state.detail = result.detail
    state.latencyMs = result.latencyMs
    state.model = result.model
  } catch (error) {
    state.ok = false
    state.detail = (error as { message?: string }).message ?? '测试请求失败'
    state.model = null
    state.latencyMs = 0
  } finally {
    state.loading = false
  }
}
</script>

<template>
  <div class="channels">
    <div class="toolbar">
      <span class="hint">密钥已脱敏显示（仅留末 4 位）；留空或保持掩码表示不修改，修改后保存立即生效。「测试连通」对已保存配置发起真实请求</span>
      <div class="actions">
        <el-button :icon="Plus" size="small" @click="addChannel">新增渠道</el-button>
        <el-button type="primary" size="small" :loading="saving" @click="emit('save')">保存渠道</el-button>
      </div>
    </div>

    <el-empty v-if="!entries.length" description="暂无渠道，点击「新增渠道」开始配置" :image-size="72" />

    <div class="grid">
      <el-card v-for="[name, channel] in entries" :key="name" shadow="never" class="channel-card">
        <template #header>
          <div class="card-head">
            <div class="card-title">
              <span class="name">{{ name }}</span>
              <el-tooltip
                v-if="usageOf(name).length"
                :content="`路由入口：${usageNamesOf(name)}`"
                placement="top"
              >
                <el-tag size="small" type="success">{{ usageOf(name).length }} 个入口在用</el-tag>
              </el-tooltip>
              <el-tag v-else size="small" type="info">未被路由引用</el-tag>
            </div>
            <div class="card-actions">
              <el-button size="small" :loading="probes[name]?.loading" @click="testChannel(name)">测试连通</el-button>
              <el-button :icon="Delete" text size="small" type="danger" @click="removeChannel(name)">删除</el-button>
            </div>
          </div>
        </template>

        <div v-if="probes[name] && (probes[name]!.ok !== undefined || probes[name]!.detail)" class="probe-result">
          <el-tag :type="probes[name]!.ok ? 'success' : 'danger'" size="small">
            {{ probes[name]!.ok ? '连通正常' : '失败' }}
          </el-tag>
          <span class="probe-detail">
            {{ probes[name]!.model ?? '—' }} · {{ probes[name]!.latencyMs ?? 0 }}ms · {{ probes[name]!.detail }}
          </span>
        </div>

        <div class="form">
          <label class="field">
            <span class="label">渠道名</span>
            <el-input :model-value="name" disabled />
          </label>
          <label class="field">
            <span class="label">协议</span>
            <el-select v-model="channel.protocol">
              <el-option v-for="item in PROTOCOLS" :key="item.value" :value="item.value" :label="item.label" />
            </el-select>
          </label>
          <label class="field">
            <span class="label">Base URL</span>
            <el-input v-model="channel.baseUrl" placeholder="https://…" />
          </label>
          <label class="field">
            <span class="label">API Key</span>
            <el-input v-model="channel.apiKey" placeholder="留空保持现有密钥" autocomplete="off" />
          </label>
          <label class="field span">
            <span class="label">模型清单（可输入回车新增）</span>
            <el-select
              v-model="channel.models"
              multiple
              filterable
              allow-create
              default-first-option
              placeholder="该渠道 key 实际支持的模型"
            >
              <el-option v-for="model in channel.models" :key="model" :value="model" :label="model" />
            </el-select>
          </label>
          <label class="field">
            <span class="label">默认模型</span>
            <el-select
              v-model="channel.defaultModel"
              filterable
              allow-create
              default-first-option
              placeholder="模型下线时的归一化去向"
            >
              <el-option v-for="model in channel.models" :key="model" :value="model" :label="model" />
            </el-select>
          </label>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.channels {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.actions {
  display: flex;
  gap: 8px;
}

.hint {
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-500);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(460px, 1fr));
  gap: 12px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.name {
  font-weight: 600;
}

.card-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.probe-result {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  padding: 6px 10px;
  border-radius: 8px;
  background: var(--zj-primary-soft);
}

.probe-detail {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-600);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.form {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 12px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.field.span {
  grid-column: 1 / -1;
}

.label {
  font-size: var(--zj-fs-sm);
  font-weight: 500;
  color: var(--zj-ink-700);
}
</style>
