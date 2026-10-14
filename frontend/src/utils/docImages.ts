/**
 * 正文图片引用的前后端同口径解析（与后端 MarkdownImages 保持一致的语法）：
 * Markdown `![alt](ref)` 与 HTML `<img src="ref">` 两种形态。
 *
 * 附件面板需要"一眼看出本文有哪些图、逐张处理"，所以这里把引用解析成可定位的结构
 * （原文片段 + 地址 + 在正文中的起止位置），处理动作（本地化/替换/删除）据此精确改写。
 */

// 与后端 MarkdownImages.MD 一致
const MD_IMAGE = /!\[([^\]]*)]\(([^)]+)\)/g
// 与后端 MarkdownImages.HTML_SRC 一致
const HTML_IMG = /(<img\b[^>]*\bsrc\s*=\s*["'])([^"']+)(["'])/gi

/** 正文里的一处图片引用 */
export interface DocImage {
  /** 引用类型：md / html */
  kind: 'md' | 'html'
  /** 图片地址（原始引用，去掉 md 可选标题后的首个 token） */
  src: string
  /** 是否为外链（http/https） */
  external: boolean
  /** 是否为系统公共图片库地址 /api/assets/... */
  local: boolean
  /** 该引用在正文中的完整原文片段（用于精确替换/删除） */
  raw: string
  /** 在正文中的起止下标（[start, end)） */
  start: number
  end: number
}

/** 取引用地址的首个 token（丢弃 Markdown 的可选标题，如 `x.png "标题"`）。 */
export function firstToken(ref: string): string {
  const trimmed = (ref ?? '').trim()
  const space = trimmed.indexOf(' ')
  return space < 0 ? trimmed : trimmed.slice(0, space)
}

/** 解析正文里的全部图片引用（按出现顺序）。 */
export function parseDocImages(content: string): DocImage[] {
  const text = content ?? ''
  const images: DocImage[] = []
  const collect = (regex: RegExp, kind: 'md' | 'html', srcGroup: number) => {
    regex.lastIndex = 0
    let m: RegExpExecArray | null
    while ((m = regex.exec(text)) !== null) {
      const src = firstToken(m[srcGroup])
      const lower = src.toLowerCase()
      images.push({
        kind,
        src,
        external: lower.startsWith('http://') || lower.startsWith('https://'),
        local: lower.startsWith('/api/assets/'),
        raw: m[0],
        start: m.index,
        end: m.index + m[0].length
      })
    }
  }
  collect(MD_IMAGE, 'md', 2)
  collect(HTML_IMG, 'html', 2)
  return images.sort((a, b) => a.start - b.start)
}

/** 图片在前端的可显示地址：本地公共库地址与外链都直接可用，原样返回。 */
export function imageDisplayUrl(img: DocImage): string {
  return img.src
}

/** 把正文中指定的原始片段替换为新片段（按 raw 精确替换首个匹配）。 */
export function replaceImageRaw(content: string, raw: string, replacement: string): string {
  const idx = content.indexOf(raw)
  if (idx < 0) return content
  return content.slice(0, idx) + replacement + content.slice(idx + raw.length)
}

/** 删除正文中指定的图片引用（连同其前后多余空行一起清掉，避免留空行）。 */
export function removeImageRaw(content: string, raw: string): string {
  const idx = content.indexOf(raw)
  if (idx < 0) return content
  let start = idx
  let end = idx + raw.length
  // 吃掉紧随其后的一个换行、以及前面多余的空行（保持段落整洁）
  while (end < content.length && (content[end] === '\n' || content[end] === '\r')) end++
  const before = content.slice(0, start)
  const collapsed = before.replace(/\n{3,}$/, '\n\n')
  return collapsed + content.slice(end)
}

/** 把图片地址换成公共图片库地址（用于本地化后回填 / 人工上传后替换）。 */
export function rewriteImageSrc(img: DocImage, newUrl: string): string {
  if (img.kind === 'md') {
    const alt = /^!\[([^\]]*)]/.exec(img.raw)?.[1] ?? ''
    return `![${alt}](${newUrl})`
  }
  return `<img src="${newUrl}">`
}
