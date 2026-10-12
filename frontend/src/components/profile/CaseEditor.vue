<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { CaseView } from '@/api/types'

const props = defineProps<{
  visible: boolean
  /** null = 新增，否则编辑该条 */
  initial: CaseView | null
  saving?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'save', req: { projectName: string; background: string; metrics: string; conflict: string; result: string }): void
}>()

const form = reactive({
  projectName: '',
  background: '',
  metrics: '',
  conflict: '',
  result: ''
})

watch(
  () => props.visible,
  (visible) => {
    if (visible) {
      form.projectName = props.initial?.projectName ?? ''
      form.background = props.initial?.background ?? ''
      form.metrics = props.initial?.metrics ?? ''
      form.conflict = props.initial?.conflict ?? ''
      form.result = props.initial?.result ?? ''
    }
  }
)

function save() {
  if (!form.projectName.trim()) return
  emit('save', {
    projectName: form.projectName.trim(),
    background: form.background.trim(),
    metrics: form.metrics.trim(),
    conflict: form.conflict.trim(),
    result: form.result.trim()
  })
}

function close() {
  emit('update:visible', false)
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="initial ? '编辑项目素材' : '添加项目素材'"
    width="min(520px, 92vw)"
    @update:model-value="close"
  >
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="项目名">
        <el-input v-model="form.projectName" maxlength="80" placeholder="如：交易网关重构" />
      </el-form-item>
      <el-form-item label="背景">
        <el-input v-model="form.background" type="textarea" :rows="2" maxlength="2000" placeholder="项目要解决什么问题" />
      </el-form-item>
      <el-form-item label="数字">
        <el-input v-model="form.metrics" type="textarea" :rows="2" maxlength="2000" placeholder="规模与量化指标，如 QPS、P99、节省成本" />
      </el-form-item>
      <el-form-item label="冲突/难点">
        <el-input v-model="form.conflict" type="textarea" :rows="2" maxlength="2000" placeholder="最难的地方是什么，怎么权衡的" />
      </el-form-item>
      <el-form-item label="结果">
        <el-input v-model="form.result" type="textarea" :rows="2" maxlength="2000" placeholder="最终成果与业务价值" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>
