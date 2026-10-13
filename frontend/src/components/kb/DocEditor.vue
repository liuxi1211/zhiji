<script setup lang="ts">
// 资料讲解编辑器：目录编辑抽屉与合并页产物编辑共用。
// 挂载知识点即人工发布决策（挂载动作即发布）；目标知识点不能已下线（下线全链路封死）。
import { computed, reactive, ref, watch } from 'vue'
import { View } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { searchContents } from '@/api/kb'
import DocDisplay from '@/components/kb/DocDisplay.vue'
import type { KbDocDraft, DocView } from '@/api/types'

const props = defineProps<{ initial: KbDocDraft | null; saving?: boolean }>()
const emit = defineEmits<{ (e: 'save', draft: KbDocDraft): void }>()

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

// —— 渲染预览（所见即所得）：草稿实时组装成用户侧渲染形态，弹窗内用 DocDisplay 展示 ——

const previewVisible = ref(false)

const previewDoc = computed<DocView>(() => ({
  id: 0,
  domainCode: '',
  topicId: 0,
  topicName: '',
  title: draft.title,
  summary: draft.summary,
  contentMd: draft.contentMd,
  difficulty: draft.difficulty,
  sourceUrl: null,
  kpId: draft.kpId,
  status: 'draft',
  mergedIntoId: null,
  mergeSources: [],
  reviewDueAt: null,
  createdBy: 0,
  createdAt: '',
  updatedAt: ''
}))

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
        <p class="hint">点右下角「预览效果」查看 Markdown 渲染后的样子</p>
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
      <el-button :icon="View" @click="previewVisible = true">预览效果</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </div>

    <!-- 渲染预览：与用户侧详情完全同构（DocDisplay），草稿实时反映 -->
    <el-dialog v-model="previewVisible" title="渲染预览（用户侧所见）" width="min(860px, 92vw)" append-to-body>
      <div class="preview-scroll">
        <DocDisplay :doc="previewDoc" />
      </div>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.doc-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.hint {
  margin: 0;
  font-size: 11px;
  color: var(--zj-ink-500, #999);
}

.editor-actions {
  display: flex;
  justify-content: flex-end;
}

/* 预览弹窗内容限高内滚 */
.preview-scroll {
  max-height: 65vh;
  overflow: auto;
}
</style>
