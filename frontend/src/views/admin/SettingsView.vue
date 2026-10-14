<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getConfigSections, updateConfigSection } from '@/api/configs'
import { useConstantsStore } from '@/stores/constants'
import ConfigFormRenderer from '@/components/config/ConfigFormRenderer.vue'
import ConfigTableRenderer from '@/components/config/ConfigTableRenderer.vue'
import type { ConfigFieldDef, ConfigSectionView } from '@/api/types'

/**
 * 系统配置（通用配置域卡片流）：元数据驱动自渲染，新增配置域前端零改动。
 * LLM 相关配置（llm.*）已独立为「LLM 管理」页，本页不再展示。
 */
const constants = useConstantsStore()

const loading = ref(false)
const savingKey = ref<string | null>(null)
const sections = ref<ConfigSectionView[]>([])
const drafts = reactive<Record<string, Record<string, any>>>({})

const sortedSections = computed(() => [...sections.value].sort((a, b) => a.order - b.order))

function formFieldsOf(section: ConfigSectionView): ConfigFieldDef[] {
  return section.fields.filter((field) => field.type !== 'TABLE')
}

function tableFieldOf(section: ConfigSectionView): ConfigFieldDef | undefined {
  return section.fields.find((field) => field.type === 'TABLE')
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
    sections.value = all.filter((section) => !section.key.startsWith('llm.'))
    sections.value.forEach((section) => {
      drafts[section.key] = cloneDraft(section.value)
    })
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
</script>

<template>
  <div class="settings" v-loading="loading">
    <header class="zj-page-head">
      <h1>系统配置</h1>
      <p>系统运行参数集中配置，保存后改动即生效；LLM 相关配置已迁移至「LLM 管理」</p>
    </header>

    <el-empty
      v-if="!loading && !sortedSections.length"
      description="暂无通用配置域；模型渠道、路由、限额与提示词请前往「LLM 管理」"
      :image-size="88"
    />

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
</style>
