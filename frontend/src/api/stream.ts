import type { SourceRewriteResp } from './types'

/**
 * SSE 流式 AI 改写客户端（fetch + ReadableStream，不走 axios——axios 1.x 不支持流式响应拦截）。
 *
 * 协议对齐后端 RewriteStreamSupport：
 *   event: delta  data: {"text":"片段"}
 *   event: done   data: {}
 *   event: error  data: {"message":"..."}
 *
 * onDelta 逐片段回调（打字机式追加到编辑器）；返回 Promise<SourceRewriteResp>（完整正文）。
 * 返回的 abort 函数可中途停止（后端检测到断开会中断模型流，不白烧 token）。
 */
export function streamAiRewrite(
  url: string,
  body: unknown,
  onDelta: (text: string) => void
): { promise: Promise<SourceRewriteResp>; abort: () => void } {
  const controller = new AbortController()

  const promise = (async (): Promise<SourceRewriteResp> => {
    const token = localStorage.getItem('zj_staff_token')
    const resp = await fetch(`/api${url}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      body: JSON.stringify(body),
      signal: controller.signal
    })
    if (!resp.ok || !resp.body) {
      // 非 200：尝试读 JSON 错误体（BizException 统一格式）
      let message = `请求失败（${resp.status}）`
      try {
        const err = await resp.json()
        if (err?.message) message = err.message
      } catch {
        // 非 JSON 响应体，保持默认文案
      }
      throw Object.assign(new Error(message), { status: resp.status })
    }

    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let full = ''

    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      // SSE 帧以空行分隔；逐帧解析 event/data 两行
      const frames = buffer.split('\n\n')
      buffer = frames.pop() ?? ''
      for (const frame of frames) {
        const lines = frame.split('\n')
        let event = ''
        const dataLines: string[] = []
        for (const line of lines) {
          if (line.startsWith('event:')) event = line.slice(6).trim()
          else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
        }
        if (!dataLines.length) continue
        let payload: { text?: string; message?: string }
        try {
          payload = JSON.parse(dataLines.join('\n'))
        } catch {
          continue
        }
        if (event === 'delta' && payload.text) {
          full += payload.text
          onDelta(payload.text)
        } else if (event === 'error') {
          throw new Error(payload.message || 'AI 改写失败')
        }
        // done 帧：只表示流正常收尾，无载荷
      }
    }
    return { content: full }
  })()

  return { promise, abort: () => controller.abort() }
}
