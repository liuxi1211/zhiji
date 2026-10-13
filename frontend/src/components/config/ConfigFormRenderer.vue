<script setup lang="ts">
import type { ConfigFieldDef } from '@/api/types'

const props = defineProps<{
  fields: ConfigFieldDef[]
  modelValue: Record<string, any>
}>()

function numberOrNull(value: unknown): number | undefined {
  return value === null || value === undefined || value === '' ? undefined : Number(value)
}

function textOf(value: unknown): string {
  return value === null || value === undefined ? '' : String(value)
}

function set(key: string, value: unknown) {
  props.modelValue[key] = value
}
</script>

<template>
  <el-form label-position="top" class="config-form">
    <el-form-item v-for="field in fields" :key="field.key" :label="field.label">
      <el-input-number
        v-if="field.type === 'NUMBER'"
        :model-value="numberOrNull(modelValue[field.key])"
        :min="field.min ?? -Infinity"
        :max="field.max ?? Infinity"
        :step="field.step ?? 1"
        :controls="false"
        style="width: 100%"
        @update:model-value="set(field.key, $event)"
      />
      <el-switch
        v-else-if="field.type === 'SWITCH'"
        :model-value="modelValue[field.key] === true"
        @update:model-value="set(field.key, $event)"
      />
      <el-select
        v-else-if="field.type === 'SELECT'"
        :model-value="modelValue[field.key] ?? null"
        style="width: 100%"
        @update:model-value="set(field.key, $event)"
      >
        <el-option
          v-for="opt in field.options ?? []"
          :key="String(opt.value)"
          :label="opt.label"
          :value="opt.value"
        />
      </el-select>
      <el-date-picker
        v-else-if="field.type === 'DATE'"
        :model-value="textOf(modelValue[field.key])"
        type="date"
        value-format="YYYY-MM-DD"
        style="width: 100%"
        @update:model-value="set(field.key, $event)"
      />
      <el-input
        v-else-if="field.type === 'TEXTAREA'"
        type="textarea"
        :rows="3"
        :model-value="textOf(modelValue[field.key])"
        @update:model-value="set(field.key, $event)"
      />
      <el-input
        v-else
        :model-value="textOf(modelValue[field.key])"
        @update:model-value="set(field.key, $event)"
      />
      <div v-if="field.help" class="help">{{ field.help }}</div>
    </el-form-item>
  </el-form>
</template>

<style scoped>
.config-form {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 0 24px;
}

.help {
  margin-top: 2px;
  font-size: 12px;
  line-height: 18px;
  color: var(--zj-ink-500);
}
</style>
