<script setup lang="ts">
// 资料讲解只读渲染：审核详情、合并页三栏对照共用。
import { computed } from 'vue'
import type { DocView } from '@/api/types'
import { createMarkdown } from '@/utils/markdown'

const props = defineProps<{ doc: DocView }>()

const md = createMarkdown()
const contentHtml = computed(() => md.render(props.doc.contentMd ?? ''))
const DIFF_LABELS: Record<string, string> = { junior: '初级', mid: '中级', senior: '高级', expert: '资深' }
</script>

<template>
  <div class="doc-display">
    <header class="doc-head">
      <h3 class="doc-title">{{ doc.title }}</h3>
      <div class="doc-meta">
        <span class="meta-item">{{ DIFF_LABELS[doc.difficulty] ?? doc.difficulty }}</span>
        <span class="meta-item">{{ doc.topicName }}</span>
        <span v-if="doc.kpId" class="meta-item">挂载知识点 #{{ doc.kpId }}</span>
      </div>
    </header>

    <p v-if="doc.summary" class="doc-summary">{{ doc.summary }}</p>

    <div class="md-body" v-html="contentHtml"></div>

    <p v-if="doc.sourceUrl" class="doc-source">
      来源溯源：<a :href="doc.sourceUrl" target="_blank" rel="noopener">{{ doc.sourceUrl }}</a>
    </p>
  </div>
</template>

<style scoped>
.doc-display {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.doc-head {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.doc-title {
  margin: 0;
  font-size: 16px;
  color: var(--zj-ink-800);
}

.doc-meta {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.meta-item {
  padding: 1px 8px;
  font-size: 11px;
  border: 1px solid var(--zj-line);
  border-radius: 999px;
  color: var(--zj-ink-600);
  background: var(--zj-surface);
}

.doc-summary {
  margin: 0;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-600);
}

.doc-source {
  margin: 0;
  font-size: 11px;
  color: var(--zj-ink-500, #999);
  word-break: break-all;
}
</style>
