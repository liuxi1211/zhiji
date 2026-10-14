<script setup lang="ts">
import { computed, watchEffect } from 'vue'
import { useConstantsStore } from '@/stores/constants'
import type { ConfigFieldDef, ConfigFieldOption } from '@/api/types'

interface TableRow {
  key: string
  label: string
  data: Record<string, any>
  index: number
}

const props = defineProps<{
  field: ConfigFieldDef
  modelValue: Record<string, any>
  sources: Record<string, unknown>
}>()

const constants = useConstantsStore()

const columns = computed(() => props.field.columns ?? [])
const fixedRows = computed(() => Boolean(props.field.rowKeySource))
const group = computed(() => (props.field.rowKeySource ?? '').slice('constants:'.length))

const rows = computed<TableRow[]>(() => {
  const collection = props.modelValue?.[props.field.key]
  if (fixedRows.value) {
    return constants.items(group.value).map((item, index) => ({
      key: item.code,
      label: constants.name(group.value, item.code),
      data: (collection?.[item.code] ?? {}) as Record<string, any>,
      index
    }))
  }
  return (Array.isArray(collection) ? collection : []).map((data, index) => ({
    key: String(index),
    label: '',
    data: data as Record<string, any>,
    index
  }))
})

// 固定行回填：老库缺行或常量组新增入口时补空行对象，保证单元格编辑写进草稿而非游离对象
watchEffect(() => {
  if (!fixedRows.value) {
    return
  }
  const collection = props.modelValue?.[props.field.key]
  if (!collection || typeof collection !== 'object') {
    return
  }
  for (const item of constants.items(group.value)) {
    if (!collection[item.code]) {
      collection[item.code] = {}
    }
  }
})

function cell(row: TableRow, col: ConfigFieldDef): unknown {
  return row.data[col.key]
}

function hidden(row: TableRow, col: ConfigFieldDef): boolean {
  const cond = col.visibleWhen
  if (!cond) {
    return false
  }
  const actual = row.data[cond.field]
  return cond.op === 'eq' ? actual === cond.value : actual !== cond.value
}

function setCell(row: TableRow, col: ConfigFieldDef, value: unknown) {
  row.data[col.key] = value
}

function addRow() {
  const collection = props.modelValue[props.field.key]
  if (Array.isArray(collection)) {
    collection.push({})
  }
}

function removeRow(row: TableRow) {
  const collection = props.modelValue[props.field.key]
  if (Array.isArray(collection)) {
    collection.splice(row.index, 1)
  }
}

function numberOrNull(value: unknown): number | undefined {
  return value === null || value === undefined || value === '' ? undefined : Number(value)
}

function textOf(value: unknown): string {
  return value === null || value === undefined ? '' : String(value)
}

function cellOptions(row: TableRow, col: ConfigFieldDef): ConfigFieldOption[] {
  if (col.options) {
    return col.options
  }
  if (!col.optionsSource) {
    return []
  }
  const source = props.sources?.[col.optionsSource]
  if (Array.isArray(source)) {
    return source.map((item) =>
      item !== null && typeof item === 'object'
        ? (item as ConfigFieldOption)
        : { value: item, label: String(item) }
    )
  }
  if (source && typeof source === 'object' && col.optionsDependsOn) {
    const map = source as Record<string, string[]>
    return (map[row.data[col.optionsDependsOn]] ?? []).map((item) => ({ value: item, label: item }))
  }
  return []
}
</script>

<template>
  <el-table :data="rows" size="small">
    <el-table-column v-if="fixedRows" label="入口" min-width="150">
      <template #default="{ row }">{{ row.label }}</template>
    </el-table-column>
    <el-table-column
      v-for="col in columns"
      :key="col.key"
      :label="col.label"
      :width="col.width"
      :min-width="col.minWidth"
    >
      <template #default="{ row }">
        <template v-if="!hidden(row, col)">
          <el-select
            v-if="col.type === 'SELECT'"
            :model-value="cell(row, col) ?? null"
            size="small"
            style="width: 100%"
            :filterable="Boolean(col.creatable)"
            :allow-create="Boolean(col.creatable)"
            default-first-option
            placeholder="选择或输入"
            @update:model-value="setCell(row, col, $event)"
          >
            <el-option v-if="col.triState" :label="col.nullLabel ?? '默认'" :value="null" />
            <el-option
              v-for="opt in cellOptions(row, col)"
              :key="String(opt.value)"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <el-input-number
            v-else-if="col.type === 'NUMBER'"
            :model-value="numberOrNull(cell(row, col))"
            :min="col.min ?? -Infinity"
            :max="col.max ?? Infinity"
            :step="col.step ?? 1"
            :controls="false"
            size="small"
            style="width: 100%"
            @update:model-value="setCell(row, col, $event)"
          />
          <el-switch
            v-else-if="col.type === 'SWITCH'"
            :model-value="cell(row, col) === true"
            @update:model-value="setCell(row, col, $event)"
          />
          <el-tag v-else-if="col.type === 'TAG' && cell(row, col)" type="info" size="small">
            {{ col.tagText ?? col.label }}
          </el-tag>
          <span v-else-if="col.type === 'TAG'">—</span>
          <el-input
            v-else-if="col.type === 'TEXTAREA'"
            size="small"
            type="textarea"
            :rows="3"
            :model-value="textOf(cell(row, col))"
            @update:model-value="setCell(row, col, $event)"
          />
          <el-input
            v-else
            size="small"
            :model-value="textOf(cell(row, col))"
            @update:model-value="setCell(row, col, $event)"
          />
        </template>
        <span v-else>—</span>
      </template>
    </el-table-column>
    <el-table-column v-if="!fixedRows" label="操作" width="80">
      <template #default="{ row }">
        <el-button size="small" link type="danger" @click="removeRow(row)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>
  <div v-if="!fixedRows" class="row-ops">
    <el-button size="small" @click="addRow">添加一行</el-button>
  </div>
</template>

<style scoped>
.row-ops {
  margin-top: 12px;
}
</style>
