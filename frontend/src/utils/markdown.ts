import MarkdownIt from 'markdown-it'

/**
 * B 端共用的 Markdown 渲染器。
 *
 * 图片引用统一为公共图片库地址 /api/assets/{sha256}.{ext}（无鉴权、任何人可查），
 * 由后端导入时改写落库，前端直接按普通图片渲染即可，不再做私有标记转换——
 * 素材库、新增向导、标准库编辑/预览、C 端与分享页共用同一份地址规则（核心模块定义 5.1/5.2）。
 */
export function createMarkdown(): MarkdownIt {
  return new MarkdownIt({ html: false, linkify: true, breaks: true })
}

/** 追问层/评分要点的前端 id 生成：经分配永不复用、永不修改（6.2 红线 2） */
export function newFollowupId(): string {
  return `fu-${crypto.randomUUID()}`
}

export function newScoringPointId(): string {
  return `sp-${crypto.randomUUID()}`
}
