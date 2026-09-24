<script setup lang="ts">
import { ref } from 'vue'
import MarkdownView from './MarkdownView.vue'

withDefaults(
  defineProps<{
    modelValue: string
    placeholder?: string
    height?: string
  }>(),
  {
    placeholder: '',
    height: '320px'
  }
)

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()

const active = ref('edit')
</script>

<template>
  <el-tabs v-model="active" class="md-editor">
    <el-tab-pane label="编辑" name="edit">
      <el-input
        type="textarea"
        :model-value="modelValue"
        :placeholder="placeholder"
        :input-style="{ height, resize: 'vertical' }"
        @update:model-value="emit('update:modelValue', $event)"
      />
    </el-tab-pane>
    <el-tab-pane label="预览" name="preview">
      <MarkdownView :source="modelValue" />
    </el-tab-pane>
  </el-tabs>
</template>
