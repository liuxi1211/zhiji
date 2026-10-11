<script setup lang="ts">
// 资料讲解编辑器：目录编辑抽屉与合并页产物编辑共用。
// 挂载知识点即人工发布决策（挂载动作即发布）；目标知识点不能已下线（下线全链路封死）。
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { searchContents } from '@/api/kb'
import type { KbDocDraft } from '@/api/types'
import { createMarkdown } from '@/utils/markdown'

const props = defineProps<{ initial: KbDocDraft | null; saving?: boolean }>()
const emit = defineEmits<{ (e: 'save', draft: KbDocDraft): void }>()

const md = createMarkdown()
const DIFF_OPTIONS = [
  { value: 'junior', label: '初级' },
  { value: 'mid', label: '中级' },
  { value: 'senior', label: '高级' },
  { value: 'expert', label: '资深' }
]

const blank = (): KbDocDraft => ({ title: '', summary: '', contentMd: '', difficulty: 'mid', kpId: null })

// structuredClone 无法克隆 Vue 响应式 Proxy（DataCloneError），草稿均为纯 JSON 结构，用 JSON 深拷贝
function deepClone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

const draft = reactive<KbDocDraft>(props.initial ? deepClone(props.initial) : blank())
watch(
  () => props.initial,
  (value) => {
    Object.assign(draft, value ? deepClone(value) : blank())
  }
)

const contentHtml = computed(() => md.render(draft.contentMd || '（暂无内容）'))

// 挂载目标：远程模糊搜索知识点
const kpOptions = ref<{ id: number; title: string }[]>([])
const kpLoading = ref(false)

async function searchKp(keyword: string) {
  if (!keyword.trim()) {
    kpOptions.value = []
    return
  }
  kpLoading.value = true
  try {
    const result = await searchContents(keyword.trim(), 'kp')
    kpOptions.value = result.items.map((item) => ({ id: item.id, title: item.title }))
  } catch {
    kpOptions.value = []
  } finally {
    kpLoading.value = false
  }
}

function submit() {
  if (!draft.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  if (!draft.summary.trim()) {
    ElMessage.warning('概述齐备才能进入标准库（入库门槛）')
    return
  }
  emit('save', deepClone(draft))
}
</script>

<template>
  <div class="doc-editor">
    <el-form label-width="92px" label-position="left" size="default">
      <el-form-item label="标题" required>
        <el-input v-model="draft.title" maxlength="200" />
      </el-form-item>
      <el-form-item label="概述" required>
        <el-input v-model="draft.summary" type="textarea" :rows="2" maxlength="200" show-word-limit />
      </el-form-item>
      <el-form-item label="难度">
        <el-radio-group v-model="draft.difficulty">
          <el-radio-button v-for="option in DIFF_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="讲解正文">
        <el-input v-model="draft.contentMd" type="textarea" :rows="14" placeholder="Markdown 讲解正文快照" />
        <details class="md-preview">
          <summary>预览</summary>
          <div class="md-body" v-html="contentHtml"></div>
        </details>
      </el-form-item>
      <el-form-item label="挂载知识点">
        <el-select
          v-model="draft.kpId"
          filterable
          remote
          clearable
          :remote-method="searchKp"
          :loading="kpLoading"
          placeholder="搜索知识点（挂载即发布，可不挂载）"
          style="width: 100%"
        >
          <el-option v-for="option in kpOptions" :key="option.id" :label="`#${option.id} ${option.title}`" :value="option.id" />
        </el-select>
      </el-form-item>
    </el-form>

    <div class="editor-actions">
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </div>
  </div>
</template>

<style scoped>
.doc-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.md-preview {
  width: 100%;
  margin-top: 6px;
  font-size: 12px;
  color: var(--zj-ink-600);
}

.md-preview summary {
  cursor: pointer;
}

.md-body {
  margin-top: 6px;
  padding: 8px 10px;
  border: 1px dashed var(--zj-line);
  border-radius: 6px;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-700);
}

.editor-actions {
  display: flex;
  justify-content: flex-end;
}
</style>
