<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { searchKpCandidates } from '@/api/profile'
import type { KpCandidate } from '@/api/types'

const props = defineProps<{
  visible: boolean
  /** 已选锚点 id：命中后标已选防重复 */
  selected: number[]
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'select', candidate: KpCandidate): void
}>()

const keyword = ref('')
const results = ref<KpCandidate[]>([])
const searching = ref(false)
let searchTimer: number | undefined

watch(
  () => props.visible,
  (visible) => {
    if (visible) {
      keyword.value = ''
      results.value = []
    }
  }
)

watch(keyword, (value) => {
  window.clearTimeout(searchTimer)
  const trimmed = value.trim()
  if (!trimmed) {
    results.value = []
    return
  }
  searchTimer = window.setTimeout(async () => {
    searching.value = true
    try {
      results.value = await searchKpCandidates(trimmed, 10)
    } catch (error) {
      const err = error as { message?: string }
      ElMessage.error(err.message ?? '搜索失败')
    } finally {
      searching.value = false
    }
  }, 300)
})

function pick(candidate: KpCandidate) {
  emit('select', candidate)
}

function close() {
  emit('update:visible', false)
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="锚定知识点"
    width="min(480px, 92vw)"
    @update:model-value="close"
  >
    <el-input
      v-model="keyword"
      placeholder="搜题面或概述，选择要锚定的知识点"
      clearable
      :prefix-icon="Search"
    />
    <p class="picker-hint">锚点必须是已上线的知识点</p>
    <div v-loading="searching" class="picker-list">
      <p v-if="!keyword.trim()" class="picker-empty">输入关键词开始搜索</p>
      <p v-else-if="!searching && results.length === 0" class="picker-empty">没有匹配的已上线知识点</p>
      <button
        v-for="item in results"
        :key="item.id"
        type="button"
        class="picker-item"
        :class="{ picked: selected.includes(item.id) }"
        @click="pick(item)"
      >
        <span class="item-title">{{ item.title }}</span>
        <span class="item-meta">{{ item.topicName }} · {{ item.domainCode }}</span>
        <span v-if="selected.includes(item.id)" class="item-picked">已选</span>
      </button>
    </div>
  </el-dialog>
</template>

<style scoped>
.picker-hint {
  margin: 8px 0 0;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.picker-list {
  margin-top: 12px;
  max-height: 320px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 120px;
}

.picker-empty {
  margin: 24px 0;
  text-align: center;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-500);
}

.picker-item {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  text-align: left;
  background: var(--zj-surface);
  border: 1px solid var(--zj-line-soft);
  border-radius: var(--zj-radius-sm);
  cursor: pointer;
  transition: border-color var(--zj-dur-fast) var(--zj-ease-out);
}

.picker-item:hover,
.picker-item:focus-visible {
  border-color: var(--zj-primary);
}

.picker-item.picked {
  border-color: var(--zj-primary);
  background: var(--zj-surface-sunken);
}

.item-title {
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-900);
  line-height: 1.5;
}

.item-meta {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.item-picked {
  position: absolute;
  right: 12px;
  top: 10px;
  font-size: var(--zj-fs-xs);
  color: var(--zj-primary);
}
</style>
