<script setup lang="ts">
import { computed, ref, watchEffect } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getLlmPromptDefaults } from '@/api/settings'

/**
 * 提示词模板（llm.prompt 域）定制编辑器：左侧模板列表、右侧编辑器。
 * 直接改写传入的草稿对象（templates map），保存由父页面统一 PUT；恢复默认拉 classpath 文件版参照。
 */
const props = defineProps<{
  modelValue: Record<string, any>
  sources: Record<string, unknown>
  saving?: boolean
}>()

const emit = defineEmits<{ (e: 'save'): void }>()

interface TemplateItem {
  code: string
  name: string
  text: string
}

const selectedCode = ref<string>('')

const templates = computed<Record<string, string>>(() => {
  const value = props.modelValue?.templates
  return value && typeof value === 'object' ? value : {}
})

const names = computed<Record<string, string>>(() => {
  const value = props.sources?.['llm.promptNames']
  return value && typeof value === 'object' ? (value as Record<string, string>) : {}
})

const items = computed<TemplateItem[]>(() =>
  Object.entries(templates.value).map(([code, text]) => ({
    code,
    name: names.value[code] ?? code,
    text: String(text ?? '')
  }))
)

const current = computed(() => items.value.find((item) => item.code === selectedCode.value) ?? items.value[0])

const currentText = computed({
  get: () => current.value?.text ?? '',
  set: (value: string) => {
    if (current.value) {
      templates.value[current.value.code] = value
    }
  }
})

const dirty = computed(() => {
  if (!current.value) {
    return false
  }
  return templates.value[current.value.code] !== originalTexts.value[current.value.code]
})

/** 进入页面时的文本快照：标记「已编辑」用（不拦截保存）；草稿异步到位后逐 code 首次登记 */
const originalTexts = ref<Record<string, string>>({})

watchEffect(() => {
  for (const [code, text] of Object.entries(templates.value)) {
    if (!(code in originalTexts.value)) {
      originalTexts.value[code] = text
    }
  }
})

const variables = computed(() => {
  const found = new Set<string>()
  for (const match of currentText.value.matchAll(/\{\{\s*([^{}\s]+)\s*\}\}/g)) {
    found.add(match[1])
  }
  return [...found]
})

/** 模板里不能写字面 {{（会干扰 Vue 插值解析），展示用标签在此拼接 */
function varTag(name: string): string {
  return '{{' + name + '}}'
}

function select(code: string) {
  selectedCode.value = code
}

async function restoreDefault() {
  if (!current.value) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `将「${current.value.name}」重置为文件默认版本？重置后仍需点「保存提示词」才落库生效。`,
      '恢复默认',
      { type: 'warning', confirmButtonText: '重置', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const defaults = await getLlmPromptDefaults()
    const text = defaults[current.value.code]
    if (text == null) {
      ElMessage.error('该模板没有文件默认版本')
      return
    }
    templates.value[current.value.code] = text
    ElMessage.success('已重置为默认文本，保存后生效')
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '恢复默认失败')
  }
}
</script>

<template>
  <div class="prompts">
    <aside class="list">
      <button
        v-for="item in items"
        :key="item.code"
        type="button"
        class="item"
        :class="{ active: current?.code === item.code, edited: templates[item.code] !== originalTexts[item.code] }"
        @click="select(item.code)"
      >
        <span class="item-name">{{ item.name }}</span>
        <span class="item-code">{{ item.code }}</span>
      </button>
    </aside>

    <section v-if="current" class="editor">
      <div class="editor-head">
        <div class="title">
          <span class="name">{{ current.name }}</span>
          <code class="code">{{ current.code }}</code>
          <el-tag v-if="dirty" type="warning" size="small">已编辑</el-tag>
        </div>
        <div class="actions">
          <el-button size="small" @click="restoreDefault">恢复默认</el-button>
          <el-button type="primary" size="small" :loading="saving" @click="emit('save')">保存提示词</el-button>
        </div>
      </div>

      <div v-if="variables.length" class="vars">
        <span class="vars-label">模板变量</span>
        <el-tag v-for="variable in variables" :key="variable" size="small" class="var-tag">{{ varTag(variable) }}</el-tag>
      </div>

      <el-input
        v-model="currentText"
        type="textarea"
        :autosize="{ minRows: 18, maxRows: 32 }"
        class="textarea"
        spellcheck="false"
      />
      <p class="note">保存后立即生效，所有走该入口的调用即时切换；「恢复默认」取 classpath 文件版本。</p>
    </section>
  </div>
</template>

<style scoped>
.prompts {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 14px;
  align-items: start;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 6px;
  background: var(--zj-surface);
  border: 1px solid var(--zj-line-soft);
  border-radius: 10px;
}

.item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  padding: 7px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.item:hover {
  background: var(--zj-primary-soft);
}

.item.active {
  background: var(--zj-primary-soft);
  box-shadow: inset 2px 0 0 var(--zj-primary);
}

.item-name {
  font-size: var(--zj-fs-sm);
  font-weight: 600;
  color: var(--zj-ink-700);
}

.item-code {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.item.edited .item-name::after {
  content: ' *';
  color: var(--zj-warning, #d97706);
}

.editor {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}

.editor-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.name {
  font-size: 15px;
  font-weight: 600;
}

.code {
  font-size: 12px;
  color: var(--zj-ink-500);
}

.actions {
  display: flex;
  gap: 8px;
}

.vars {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.vars-label {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.var-tag {
  font-family: var(--zj-mono, ui-monospace, monospace);
}

.textarea :deep(textarea) {
  font-family: var(--zj-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: var(--zj-fs-sm);
  line-height: 1.6;
}

.note {
  margin: 0;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

@media (max-width: 900px) {
  .prompts {
    grid-template-columns: 1fr;
  }

  .list {
    flex-direction: row;
    flex-wrap: wrap;
  }
}
</style>
