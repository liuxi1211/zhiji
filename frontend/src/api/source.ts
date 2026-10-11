import request from './request'
import type {
  SourceAiSplitResp,
  SourceClassifyResp,
  SourceCreateReq,
  SourceDomainTopicsResp,
  SourceInferItem,
  SourceItemView,
  SourceRewriteResp,
  SourceSplitResp,
  SourceSplitSliceInput,
  SourceUpdateReq
} from './types'

export async function listSources(status?: string, parentId?: number): Promise<SourceItemView[]> {
  const params: Record<string, string | number> = {}
  if (status) params.status = status
  if (parentId != null) params.parentId = parentId
  return (await request.get<SourceItemView[]>('/admin/sources', { params })).data
}

export async function getSource(id: number): Promise<SourceItemView> {
  return (await request.get<SourceItemView>(`/admin/sources/${id}`)).data
}

export async function getSourceChildren(id: number): Promise<SourceItemView[]> {
  return (await request.get<SourceItemView[]>(`/admin/sources/${id}/children`)).data
}

export async function getSplitPreview(id: number): Promise<string[]> {
  return (await request.get<string[]>(`/admin/sources/${id}/split-preview`)).data
}

export async function createSource(req: SourceCreateReq): Promise<SourceItemView> {
  return (await request.post<SourceItemView>('/admin/sources', req)).data
}

export async function importSource(file: File): Promise<SourceItemView> {
  const form = new FormData()
  form.append('file', file)
  return (
    await request.post<SourceItemView>('/admin/sources/import', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      // zip 导入可能较大，放宽默认 20s 超时
      timeout: 120000
    })
  ).data
}

export async function fetchSource(url: string): Promise<SourceItemView> {
  return (await request.post<SourceItemView>('/admin/sources/fetch', { url })).data
}

export async function updateSource(id: number, req: SourceUpdateReq): Promise<SourceItemView> {
  return (await request.put<SourceItemView>(`/admin/sources/${id}`, req)).data
}

export async function splitSource(id: number, slices: SourceSplitSliceInput[]): Promise<SourceSplitResp> {
  return (await request.post<SourceSplitResp>(`/admin/sources/${id}/split`, { slices })).data
}

export async function submitSource(
  id: number,
  req: { domainCode: string; topicId: number; contentType: 'kp' | 'doc' }
): Promise<SourceItemView[]> {
  return (await request.post<SourceItemView[]>(`/admin/sources/${id}/submit`, req)).data
}

export async function discardSource(id: number): Promise<SourceItemView> {
  return (await request.post<SourceItemView>(`/admin/sources/${id}/discard`)).data
}

export async function deleteSource(id: number): Promise<void> {
  await request.delete(`/admin/sources/${id}`)
}

export async function getDomainTopics(domainCode: string): Promise<string[]> {
  const resp = (
    await request.get<SourceDomainTopicsResp>('/admin/sources/domain-topics', { params: { domainCode } })
  ).data
  return resp.topics
}

// —— AI 辅助（产物回人手里，不直接落库） ——

export async function aiRewrite(id: number): Promise<SourceRewriteResp> {
  return (await request.post<SourceRewriteResp>(`/admin/sources/${id}/ai/rewrite`, null, { timeout: 120000 })).data
}

export async function aiSplit(id: number): Promise<SourceAiSplitResp> {
  return (await request.post<SourceAiSplitResp>(`/admin/sources/${id}/ai/split`, null, { timeout: 120000 })).data
}

export async function aiSummary(id: number): Promise<string> {
  const resp = (await request.post<{ summary: string }>(`/admin/sources/${id}/ai/summary`, null, { timeout: 120000 })).data
  return resp.summary
}

export async function aiClassify(id: number, domainCode: string, topics: string[]): Promise<SourceClassifyResp> {
  return (
    await request.post<SourceClassifyResp>(`/admin/sources/${id}/ai/classify`, { domainCode, topics }, { timeout: 120000 })
  ).data
}

/** 难度建议（向导第 3 步：AI 建议四档，人工拍板） */
export async function aiDifficulty(id: number): Promise<{ difficulty: string; reason: string }> {
  return (
    await request.post<{ difficulty: string; reason: string }>(
      `/admin/sources/${id}/ai/difficulty`,
      null,
      { timeout: 120000 }
    )
  ).data
}

export async function aiInfer(id: number): Promise<SourceInferItem[]> {
  const resp = (
    await request.post<{ inferences: SourceInferItem[] }>(`/admin/sources/${id}/ai/infer`, null, { timeout: 120000 })
  ).data
  return resp.inferences
}

export async function saveInferred(id: number, inferences: SourceInferItem[]): Promise<number[]> {
  return (await request.post<number[]>(`/admin/sources/${id}/ai/infer/save`, { inferences })).data
}

/** 素材图片的访问地址（B 端会话 cookie 无关，走同源 /api 反代） */
export function sourceAssetUrl(itemId: number, fileName: string): string {
  return `/api/admin/sources/${itemId}/assets/${fileName}`
}
