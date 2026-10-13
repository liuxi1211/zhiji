import request from './request'
import type {
  SourceClassifyResp,
  SourceCreateReq,
  SourceDomainTopicsResp,
  SourceInferItem,
  SourceItemView,
  SourceListParams,
  SourcePageView,
  SourceSubmitReq,
  SourceUpdateReq
} from './types'

export async function listSources(params: SourceListParams): Promise<SourcePageView> {
  const query: Record<string, string | number> = { page: params.page, pageSize: params.pageSize }
  if (params.status) query.status = params.status
  if (params.domainCode) query.domainCode = params.domainCode
  if (params.keyword) query.keyword = params.keyword
  return (await request.get<SourcePageView>('/admin/sources', { params: query })).data
}

export async function getSource(id: number): Promise<SourceItemView> {
  return (await request.get<SourceItemView>(`/admin/sources/${id}`)).data
}

export async function createSource(req: SourceCreateReq): Promise<SourceItemView> {
  return (await request.post<SourceItemView>('/admin/sources', req)).data
}

/** 导入文件：单文件返回一条，zip 内多文档各成一条，返回本次导入的素材列表 */
export async function importSource(file: File): Promise<SourceItemView[]> {
  const form = new FormData()
  form.append('file', file)
  return (
    await request.post<SourceItemView[]>('/admin/sources/import', form, {
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

export async function submitSource(id: number, req: SourceSubmitReq): Promise<SourceItemView[]> {
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
