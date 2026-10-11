<script setup lang="ts">
// 知识点只读渲染（用户侧预览所见即所得）：审核详情、合并页三栏对照共用。
// 展示结构全产品统一：题面 → 概述 → 叙述框架 → 问法变体 → 追问链 → 分级评分要点 → 三档回答样例。
import { computed } from 'vue'
import type { KpView } from '@/api/types'
import { createMarkdown } from '@/utils/markdown'

const props = defineProps<{ kp: KpView }>()

const md = createMarkdown()
const frameworkHtml = computed(() => md.render(props.kp.frameworkMd ?? ''))

const DIFF_LABELS: Record<string, string> = { junior: '初级', mid: '中级', senior: '高级', expert: '资深' }
const LEVEL_LABELS: Record<string, string> = { junior: '初级', mid: '中级', senior: '高级', expert: '资深', master: '专家' }
const GRADE_LABELS: Record<string, string> = { pass: '及格', good: '良好', excellent: '优秀' }

const activeFollowups = computed(() => props.kp.followups.filter((f) => f.status === 'active'))
const disabledFollowups = computed(() => props.kp.followups.filter((f) => f.status === 'disabled'))
const groupedSamples = computed(() =>
  (['pass', 'good', 'excellent'] as const)
    .map((level) => ({ level, label: GRADE_LABELS[level], items: props.kp.answerSamples.filter((s) => s.level === level) }))
    .filter((group) => group.items.length > 0)
)
</script>

<template>
  <div class="kp-display">
    <header class="kp-head">
      <h3 class="kp-title">{{ kp.title }}</h3>
      <div class="kp-meta">
        <span class="meta-item">{{ DIFF_LABELS[kp.difficulty] ?? kp.difficulty }}</span>
        <span class="meta-item">{{ kp.topicName }}</span>
        <span v-if="kp.freqManual + kp.freqSignal + kp.freqCoverage > 0" class="meta-item">
          频率 {{ kp.freqManual + kp.freqSignal + kp.freqCoverage }}
        </span>
      </div>
    </header>

    <p v-if="kp.summary" class="kp-summary">{{ kp.summary }}</p>

    <section v-if="kp.frameworkMd" class="kp-section">
      <h4>叙述框架</h4>
      <div class="md-body" v-html="frameworkHtml"></div>
    </section>

    <section v-if="kp.variants.length" class="kp-section">
      <h4>问法变体</h4>
      <ul class="plain-list">
        <li v-for="(variant, index) in kp.variants" :key="index">{{ variant.text }}</li>
      </ul>
    </section>

    <section v-if="activeFollowups.length" class="kp-section">
      <h4>追问链</h4>
      <ol class="followup-list">
        <li v-for="f in activeFollowups" :key="f.id">
          <span class="followup-layer">第 {{ f.layer }} 层</span>
          <span v-if="f.direction" class="followup-dir">{{ f.direction }}</span>
          <span>{{ f.content }}</span>
        </li>
      </ol>
      <p v-if="disabledFollowups.length" class="muted-note">（{{ disabledFollowups.length }} 层已停用，不在判定范围）</p>
    </section>

    <section v-if="kp.scoringPoints.length" class="kp-section">
      <h4>分级评分要点</h4>
      <ul class="scoring-list">
        <li v-for="point in kp.scoringPoints" :key="point.id">
          <span class="grade-tag" :class="`grade-${point.level}`">{{ GRADE_LABELS[point.level] }}</span>
          <span>{{ point.content }}</span>
        </li>
      </ul>
    </section>

    <section v-for="group in groupedSamples" :key="group.level" class="kp-section">
      <h4>{{ group.label }}回答样例</h4>
      <div v-for="(sample, index) in group.items" :key="index" class="md-body sample-body">{{ sample.content }}</div>
    </section>

    <section v-if="kp.levelRules.length" class="kp-section">
      <h4>职级要求与停止点</h4>
      <ul class="plain-list">
        <li v-for="(rule, index) in kp.levelRules" :key="index">
          {{ LEVEL_LABELS[rule.level] ?? rule.level }}：{{ rule.required ? '必问' : '选问' }}<template v-if="rule.stopLayer">，讲到第 {{ rule.stopLayer }} 层算够</template>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.kp-display {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.kp-head {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.kp-title {
  margin: 0;
  font-size: 16px;
  color: var(--zj-ink-800);
}

.kp-meta {
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

.kp-summary {
  margin: 0;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-600);
}

.kp-section h4 {
  margin: 0 0 6px;
  font-size: 12px;
  letter-spacing: 0.04em;
  color: var(--zj-ink-600);
}

.plain-list {
  margin: 0;
  padding-left: 18px;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-700);
}

.followup-list {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-700);
}

.followup-layer {
  margin-right: 6px;
  padding: 0 6px;
  font-size: 11px;
  border-radius: 4px;
  background: var(--zj-primary-soft);
  color: var(--zj-primary);
}

.followup-dir {
  margin-right: 6px;
  font-size: 11px;
  color: var(--zj-ink-600);
}

.scoring-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: var(--zj-fs-sm);
  color: var(--zj-ink-700);
}

.grade-tag {
  display: inline-block;
  margin-right: 6px;
  padding: 0 6px;
  font-size: 11px;
  border-radius: 4px;
}

.grade-pass {
  background: var(--zj-primary-soft);
  color: var(--zj-primary);
}

.grade-good {
  background: rgba(64, 158, 255, 0.12);
  color: #409eff;
}

.grade-excellent {
  background: rgba(103, 194, 58, 0.14);
  color: #67c23a;
}

.muted-note {
  margin: 4px 0 0;
  font-size: 11px;
  color: var(--zj-ink-500, #999);
}

.sample-body {
  padding: 8px 10px;
  border: 1px dashed var(--zj-line);
  border-radius: 6px;
  font-size: var(--zj-fs-sm);
  white-space: pre-wrap;
}
</style>
