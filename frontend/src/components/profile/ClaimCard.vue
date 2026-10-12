<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import type { ClaimView, KpCandidate } from '@/api/types'
import KpAnchorPicker from './KpAnchorPicker.vue'

const props = defineProps<{
  claim: ClaimView
  saving?: boolean
}>()

const emit = defineEmits<{
  (e: 'confirm'): void
  (e: 'remove'): void
  (e: 'save', payload: { wording: string; anchorIds: number[] }): void
}>()

const editing = ref(false)
const pickerVisible = ref(false)
const draft = reactive({ wording: '', anchorIds: [] as number[] })
/** 编辑期挑选的新锚点标题（既有锚点标题从 claim.anchors 反查） */
const pickedTitles = reactive<Record<number, string>>({})

watch(editing, (value) => {
  if (value) {
    draft.wording = props.claim.wording
    draft.anchorIds = [...props.claim.anchorIds]
  }
})

function anchorTitle(id: number): string {
  const known = props.claim.anchors.find((anchor) => anchor.id === id)
  return known?.title ?? pickedTitles[id] ?? `知识点 #${id}`
}

function startEdit() {
  editing.value = true
}

function cancelEdit() {
  editing.value = false
}

function saveEdit() {
  if (!draft.wording.trim()) return
  emit('save', { wording: draft.wording.trim(), anchorIds: draft.anchorIds })
  editing.value = false
}

function removeAnchor(id: number) {
  draft.anchorIds = draft.anchorIds.filter((anchorId) => anchorId !== id)
}

function onPick(candidate: KpCandidate) {
  if (!draft.anchorIds.includes(candidate.id)) {
    draft.anchorIds = [...draft.anchorIds, candidate.id]
    pickedTitles[candidate.id] = candidate.title
  }
  pickerVisible.value = false
}
</script>

<template>
  <article class="claim-card" :class="{ confirmed: claim.status === 'confirmed' }">
    <header class="claim-head">
      <span class="claim-status" :data-status="claim.status">
        {{ claim.status === 'confirmed' ? '已确认' : '待确认' }}
      </span>
      <span class="claim-source">{{ claim.source === 'ai' ? 'AI 起草' : '手动添加' }}</span>
      <span v-if="!editing" class="claim-actions">
        <el-link v-if="claim.status !== 'confirmed'" type="primary" @click="emit('confirm')">确认</el-link>
        <el-link @click="startEdit">编辑</el-link>
        <el-link type="danger" @click="emit('remove')">删除</el-link>
      </span>
    </header>

    <p v-if="!editing" class="claim-wording">{{ claim.wording }}</p>
    <el-input
      v-else
      v-model="draft.wording"
      type="textarea"
      :rows="2"
      maxlength="200"
      show-word-limit
      placeholder="第一人称简历措辞，具体、可检验"
    />

    <div class="anchor-row">
      <span class="anchor-label">锚定知识点</span>
      <div class="anchor-chips">
        <template v-if="!editing">
          <span v-for="anchor in claim.anchors" :key="anchor.id" class="anchor-chip" :title="anchor.title">
            {{ anchor.title }}
          </span>
          <span v-if="claim.anchors.length === 0" class="anchor-none">未锚定</span>
        </template>
        <template v-else>
          <span
            v-for="anchorId in draft.anchorIds"
            :key="anchorId"
            class="anchor-chip removable"
            :title="anchorTitle(anchorId)"
            role="button"
            tabindex="0"
            @click="removeAnchor(anchorId)"
            @keydown.enter.prevent="removeAnchor(anchorId)"
          >
            {{ anchorTitle(anchorId) }} ✕
          </span>
          <el-link type="primary" @click="pickerVisible = true">+ 选择知识点</el-link>
        </template>
      </div>
    </div>

    <footer v-if="editing" class="edit-actions">
      <el-button size="small" @click="cancelEdit">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="saveEdit">保存</el-button>
    </footer>

    <KpAnchorPicker v-model:visible="pickerVisible" :selected="draft.anchorIds" @select="onPick" />
  </article>
</template>

<style scoped>
.claim-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 0;
  border-bottom: 1px solid var(--zj-line-soft);
}

.claim-card.confirmed .claim-wording {
  color: var(--zj-ink-700);
}

.claim-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.claim-status {
  font-size: var(--zj-fs-xs);
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px solid var(--zj-line);
  color: var(--zj-ink-600);
}

.claim-status[data-status='confirmed'] {
  border-color: var(--zj-primary);
  color: var(--zj-primary);
}

.claim-source {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.claim-actions {
  margin-left: auto;
  display: flex;
  gap: 12px;
}

.claim-wording {
  margin: 0;
  font-size: var(--zj-fs-sm);
  line-height: 1.7;
  color: var(--zj-ink-900);
}

.anchor-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.anchor-label {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.anchor-chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.anchor-chip {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--zj-fs-xs);
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--zj-surface-sunken);
  color: var(--zj-ink-700);
}

.anchor-chip.removable {
  cursor: pointer;
}

.anchor-none {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-500);
}

.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
