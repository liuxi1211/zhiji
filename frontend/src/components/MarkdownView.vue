<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/common'
import 'highlight.js/styles/github.css'

const props = defineProps<{ source: string }>()

const md = new MarkdownIt({ html: false, linkify: true })

md.renderer.rules.fence = (tokens, idx) => {
  const token = tokens[idx]
  const lang = (token.info || '').trim().split(/\s+/)[0]
  const code = token.content
  let body: string
  if (lang === 'mermaid') {
    body = md.utils.escapeHtml(code)
  } else if (lang && hljs.getLanguage(lang)) {
    try {
      body = hljs.highlight(code, { language: lang }).value
    } catch {
      body = md.utils.escapeHtml(code)
    }
  } else if (lang) {
    body = md.utils.escapeHtml(code)
  } else {
    try {
      body = hljs.highlightAuto(code).value
    } catch {
      body = md.utils.escapeHtml(code)
    }
  }
  const cls = lang ? ` class="hljs language-${md.utils.escapeHtml(lang)}"` : ' class="hljs"'
  return `<pre><code${cls}>${body}\n</code></pre>`
}

type MermaidApi = (typeof import('mermaid'))['default']
let mermaidApi: MermaidApi | null = null
let mermaidSeq = 0

async function ensureMermaid(): Promise<MermaidApi> {
  if (!mermaidApi) {
    const mod = await import('mermaid')
    mod.default.initialize({ startOnLoad: false, securityLevel: 'strict' })
    mermaidApi = mod.default
  }
  return mermaidApi
}

function mermaidErrorNode(code: string): HTMLElement {
  const box = document.createElement('div')
  box.className = 'mdv-mermaid-error'
  const tip = document.createElement('div')
  tip.textContent = 'Mermaid 语法错误'
  const src = document.createElement('pre')
  src.textContent = code
  box.appendChild(tip)
  box.appendChild(src)
  return box
}

async function replaceMermaidBlocks(container: HTMLElement): Promise<void> {
  const blocks = Array.from(container.querySelectorAll<HTMLElement>('pre > code.language-mermaid'))
  if (blocks.length === 0) return
  const mermaid = await ensureMermaid()
  for (const codeEl of blocks) {
    const pre = codeEl.parentElement
    if (!pre) continue
    const code = codeEl.textContent ?? ''
    const id = `zj-mmd-${++mermaidSeq}`
    try {
      const { svg } = await mermaid.render(id, code)
      const holder = document.createElement('div')
      holder.innerHTML = svg
      const svgEl = holder.firstElementChild
      pre.replaceWith(svgEl ?? mermaidErrorNode(code))
    } catch {
      document.getElementById(`d${id}`)?.remove()
      pre.replaceWith(mermaidErrorNode(code))
    }
  }
}

const html = ref('')
const containerRef = ref<HTMLElement | null>(null)
let renderSeq = 0

async function render(): Promise<void> {
  const seq = ++renderSeq
  html.value = md.render(props.source ?? '')
  await nextTick()
  if (seq !== renderSeq || !containerRef.value) return
  try {
    await replaceMermaidBlocks(containerRef.value)
  } catch {
    // mermaid 整体加载失败也不影响正文，对应代码块保持纯文本
  }
}

watch(() => props.source, render, { immediate: true })
</script>

<template>
  <div ref="containerRef" class="md-view" v-html="html" />
</template>

<style scoped>
.md-view {
  line-height: 1.7;
  word-break: break-word;
}

.md-view :deep(h1),
.md-view :deep(h2),
.md-view :deep(h3),
.md-view :deep(h4) {
  margin: 0.8em 0 0.4em;
  line-height: 1.4;
}

.md-view :deep(p) {
  margin: 0.4em 0;
}

.md-view :deep(ul),
.md-view :deep(ol) {
  padding-left: 1.5em;
  margin: 0.4em 0;
}

.md-view :deep(code:not([class*='language-'])) {
  background: var(--el-fill-color-light);
  border-radius: 4px;
  padding: 0.15em 0.4em;
  font-size: 0.9em;
}

.md-view :deep(pre) {
  overflow: auto;
  border-radius: 6px;
  margin: 0.6em 0;
}

.md-view :deep(pre code.hljs) {
  padding: 1em;
  display: block;
}

.md-view :deep(table) {
  border-collapse: collapse;
  margin: 0.6em 0;
  width: 100%;
}

.md-view :deep(th),
.md-view :deep(td) {
  border: 1px solid var(--el-border-color-lighter);
  padding: 6px 12px;
  text-align: left;
}

.md-view :deep(th) {
  background: var(--el-fill-color-light);
}

.md-view :deep(img) {
  max-width: 100%;
}

.md-view :deep(blockquote) {
  margin: 0.6em 0;
  padding: 0.2em 1em;
  border-left: 4px solid var(--zj-line);
  color: var(--el-text-color-secondary);
}

.md-view :deep(.mdv-mermaid-error) {
  border: 1px solid var(--el-color-danger-light-7, #fde2e2);
  background: var(--el-color-danger-light-9, #fef0f0);
  border-radius: 6px;
  padding: 8px 12px;
  margin: 0.6em 0;
}

.md-view :deep(.mdv-mermaid-error > div) {
  color: var(--el-color-danger, #f56c6c);
  margin-bottom: 4px;
}

.md-view :deep(.mdv-mermaid-error pre) {
  margin: 0;
  background: var(--zj-surface);
  padding: 8px;
  white-space: pre-wrap;
}
</style>
