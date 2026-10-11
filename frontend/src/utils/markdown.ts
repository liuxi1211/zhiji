import MarkdownIt from 'markdown-it'
import { sourceAssetUrl } from '@/api/source'

/**
 * B 端共用的 Markdown 渲染器：素材图片 asset:source/{itemId}/{file} 引用
 * 统一转成 B 端资产接口地址（导入 zip 图片「落盘即真相」的路径约定）。
 * 素材库、新增向导、标准库编辑/预览共用同一份规则（核心模块定义 5.1/5.2）。
 */
export function createMarkdown(): MarkdownIt {
  const md = new MarkdownIt({ html: false, linkify: true, breaks: true })
  const defaultImageRenderer =
    md.renderer.rules.image ??
    ((tokens: any, idx: number, options: any, _env: any, self: any) => self.renderToken(tokens, idx, options))
  md.renderer.rules.image = (tokens, idx, options, env, self) => {
    const token = tokens[idx]
    const src = token.attrGet('src') ?? ''
    const match = /^asset:source\/(\d+)\/([A-Za-z0-9._-]+)$/.exec(src)
    if (match) {
      token.attrSet('src', sourceAssetUrl(Number(match[1]), match[2]))
    }
    return defaultImageRenderer(tokens, idx, options, env, self)
  }
  return md
}

/** 追问层/评分要点的前端 id 生成：经分配永不复用、永不修改（6.2 红线 2） */
export function newFollowupId(): string {
  return `fu-${crypto.randomUUID()}`
}

export function newScoringPointId(): string {
  return `sp-${crypto.randomUUID()}`
}
