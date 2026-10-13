<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import type { ClaimView, KpCandidate } from '@/api/types'
import { anchorStatusName } from '@/api/profile'
import KpAnchorPicker from './KpAnchorPicker.vue'

const props = defineProps<{
  claim: ClaimView
  saving?: boolean
}>()

const emit = defineEmits<{
  (e: 'confirm'): void
  (e: 'remove'): void
  (e: 'save', payload: { title: string; wording: string; anchorIds: number[] | null }): void
}>()

const editing = ref(false)
const pickerVisible = ref(false)
const draft = reactive({ title: '', wording: '', anchorIds: [] as number[] })
/** 编辑期挑选的新锚点标题（既有锚点标题从 claim.anchors 反查） */
const pickedTitles = reactive<Record<number, string>>({})

watch(editing, (value) => {
  if (value) {
    draft.title = props.claim.title
    draft.wording = props.claim.wording
    draft.anchorIds = [...props.claim.anchorIds]
  }
})

/** 措辞渲染：占位【…】高亮，提醒用户定稿前处理（先转义再替换，防注入） */
const wordingHtml = computed(() => {
  const escaped = props.claim.wording
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  return escaped.replace(/【([^【】]*)】/g, '<mark class="wording-ph">【$1】</mark>')
})

function anchorTitle(id: number): string {
  const known = props.claim.anchors.find((anchor) => anchor.id === id)
  return known?.title ?? pickedTitles[id] ?? `知识点 #${id}`
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

function saveEdit() {
  if (!draft.wording.trim()) return
  emit('save', {
    title: draft.title.trim(),
    wording: draft.wording.trim(),
    anchorIds: draft.anchorIds
  })
  editing.value = false
}
</script>

<template>
  <article class="claim-card" :data-status="claim.status">
    <header class="claim-head">
      <span class="claim-state">
        <span class="state-dot" aria-hidden="true"></span>
        {{ claim.status === 'confirmed' ? '已拍板' : '待拍板' }}
      </span>
      <span class="claim-source">{{ claim.source === 'ai' ? '对话起草' : '手动添加' }}</span>
      <span class="anchor-flag" :data-level="claim.anchorStatus">
        {{ anchorStatusName(claim.anchorStatus) }}
      </span>
      <span v-if="!editing" class="claim-actions">
        <el-button
          v-if="claim.status !== 'confirmed'"
          type="primary"
          size="small"
          class="confirm-btn"
          @click="emit('confirm')"
        >
          拍板
        </el-button>
        <el-link @click="editing = true">编辑</el-link>
        <el-link type="danger" @click="emit('remove')">删除</el-link>
      </span>
    </header>

    <template v-if="!editing">
      <h4 v-if="claim.title" class="claim-title">{{ claim.title }}</h4>
      <!-- eslint-disable-next-line vue/no-v-html —— 内容已转义，仅注入占位高亮标记 -->
      <p class="claim-wording" v-html="wordingHtml"></p>
    </template>
    <div v-else class="edit-body">
      <el-input
        v-model="draft.title"
        maxlength="40"
        show-word-limit
        placeholder="小标题：领域 + 定位，如「存储与分库分表」"
        class="title-input"
      />
      <el-input
        v-model="draft.wording"
        type="textarea"
        :rows="3"
        maxlength="300"
        show-word-limit
        placeholder="简历措辞；未提供的事实保留【】占位，定稿前处理"
      />
      <div class="anchor-row">
        <span class="anchor-label">锚定知识点（可留空，定稿后由映射补齐）</span>
        <div class="anchor-chips">
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
        </div>
      </div>
    </div>

    <footer v-if="editing" class="edit-actions">
      <el-button size="small" @click="editing = false">取消</el-button>
      <el-button type="primary" size="small" :loading="saving" @click="saveEdit">保存</el-button>
    </footer>

    <KpAnchorPicker v-model:visible="pickerVisible" :selected="draft.anchorIds" @select="onPick" />
  </article>
</template>

<style scoped>
.claim-card {
  position: relative;
  padding: var(--zj-space-4) 0 var(--zj-space-4) var(--zj-space-4);
  border-bottom: 1px solid var(--zj-line-soft);
}

/* 拍板轨道：待拍板铜色待读、已拍板退为底色，视线自然落在未完成项上 */
.claim-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: var(--zj-space-4);
  bottom: var(--zj-space-4);
  width: 2px;
  border-radius: 1px;
  background: var(--zj-copper);
  transition: background-color var(--zj-dur) var(--zj-ease-out);
}

.claim-card[data-status='confirmed']::before {
  background: var(--zj-line-soft);
}

.claim-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--zj-space-2) var(--zj-space-3);
  margin-bottom: var(--zj-space-2);
}

.claim-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-600);
}

.state-dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--zj-copper);
}

.claim-card[data-status='confirmed'] .claim-state {
  color: var(--zj-primary);
}

.claim-card[data-status='confirmed'] .state-dot {
  background: var(--zj-primary);
}

.claim-source {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-400);
}

.anchor-flag {
  font-size: var(--zj-fs-xs);
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid var(--zj-line-soft);
  color: var(--zj-ink-500);
}

.anchor-flag[data-level='mapped'] {
  border-color: transparent;
  background: var(--zj-primary-soft);
  color: var(--zj-primary);
}

.anchor-flag[data-level='pending'] {
  border-color: var(--zj-copper);
  background: var(--zj-copper-soft);
  color: var(--zj-copper);
}

.anchor-flag[data-level='zero'] {
  border-color: var(--zj-danger, #d03050);
  color: var(--zj-danger, #d03050);
}

.claim-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: var(--zj-space-3);
}

.confirm-btn {
  min-width: 64px;
}

.claim-title {
  margin: 0 0 var(--zj-space-1);
  font-size: var(--zj-fs-sm);
  font-weight: 650;
  color: var(--zj-ink-900);
}

.claim-wording {
  margin: 0;
  font-size: var(--zj-fs-base);
  line-height: 1.75;
  color: var(--zj-ink-900);
  white-space: pre-wrap;
}

.claim-card[data-status='confirmed'] .claim-wording {
  color: var(--zj-ink-700);
}

:deep(.wording-ph) {
  background: var(--zj-copper-soft);
  color: var(--zj-copper);
  border-radius: 4px;
  padding: 0 3px;
  font-weight: 600;
}

.edit-body {
  display: flex;
  flex-direction: column;
  gap: var(--zj-space-2);
}

.title-input {
  max-width: 420px;
}

.anchor-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.anchor-label {
  font-size: var(--zj-fs-xs);
  color: var(--zj-ink-400);
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
  border: 1px solid var(--zj-line-soft);
  color: var(--zj-ink-700);
}

.anchor-chip.removable {
  cursor: pointer;
}

.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--zj-space-2);
  margin-top: var(--zj-space-2);
}

@media (pointer: coarse) {
  .claim-actions .el-link {
    padding: 10px 4px;
  }
}
</style>
