import request from './request'
import type {
  DocView,
  KbContentItemView,
  KbContentType,
  KbDirectSubmitReq,
  KbMergeProduct,
  KbReviewChecks,
  KbReviewDetail,
  KbStatus,
  KbTopicView,
  KbUpdateDocReq,
  KbUpdateKpReq,
  KpView,
  SourceClassifyResp,
  SourceRewriteResp
} from './types'

// —— 主题（组织结构） ——

export async function listTopics(domainCode?: string): Promise<KbTopicView[]> {
  const params: Record<string, string> = {}
  if (domainCode) params.domainCode = domainCode
  return (await request.get<KbTopicView[]>('/admin/kb/topics', { params })).data
}

export async function createTopic(domainCode: string, name: string): Promise<KbTopicView> {
  return (await request.post<KbTopicView>('/admin/kb/topics', { domainCode, name })).data
}

export async function renameTopic(id: number, name: string): Promise<KbTopicView> {
  return (await request.put<KbTopicView>(`/admin/kb/topics/${id}`, { name })).data
}

export async function mergeTopic(id: number, targetId: number): Promise<KbTopicView> {
  return (await request.post<KbTopicView>(`/admin/kb/topics/${id}/merge`, { targetId })).data
}

export async function moveTopic(id: number, domainCode: string): Promise<KbTopicView> {
  return (await request.post<KbTopicView>(`/admin/kb/topics/${id}/move`, { domainCode })).data
}

// —— 目录与内容 ——

export async function listContents(topicId: number, type?: string, status?: string): Promise<KbContentItemView[]> {
  const params: Record<string, string | number> = { topicId }
  if (type) params.type = type
  if (status) params.status = status
  return (await request.get<KbContentItemView[]>('/admin/kb/contents', { params })).data
}

export async function listPeers(topicId: number, type?: string): Promise<KbContentItemView[]> {
  const params: Record<string, string | number> = { topicId }
  if (type) params.type = type
  return (await request.get<KbContentItemView[]>('/admin/kb/peers', { params })).data
}

export async function searchContents(keyword: string, type?: string): Promise<KbContentItemView[]> {
  const params: Record<string, string> = { keyword }
  if (type) params.type = type
  return (await request.get<KbContentItemView[]>('/admin/kb/search', { params })).data
}

export async function getKp(id: number): Promise<KpView> {
  return (await request.get<KpView>(`/admin/kb/kp/${id}`)).data
}

export async function updateKp(id: number, req: KbUpdateKpReq): Promise<KpView> {
  return (await request.put<KpView>(`/admin/kb/kp/${id}`, req)).data
}

export async function getDoc(id: number): Promise<DocView> {
  return (await request.get<DocView>(`/admin/kb/doc/${id}`)).data
}

export async function updateDoc(id: number, req: KbUpdateDocReq): Promise<DocView> {
  return (await request.put<DocView>(`/admin/kb/doc/${id}`, req)).data
}

export async function offlineContent(contentType: KbContentType, id: number): Promise<KbContentItemView> {
  return (await request.post<KbContentItemView>(`/admin/kb/${contentType}/${id}/offline`)).data
}

export async function restoreContent(contentType: KbContentType, id: number): Promise<KbContentItemView> {
  return (await request.post<KbContentItemView>(`/admin/kb/${contentType}/${id}/restore`)).data
}

// —— 直达新建（向导无素材模式） ——

export async function directSubmit(req: KbDirectSubmitReq): Promise<{ contentType: KbContentType; id: number }> {
  return (await request.post<{ contentType: KbContentType; id: number }>('/admin/kb/direct-submit', req)).data
}

// —— 同位合并 ——

export async function validateKpMerge(ids: number[]): Promise<KpView[]> {
  return (await request.post<KpView[]>('/admin/kb/merge/kp/validate', { ids })).data
}

export async function validateDocMerge(ids: number[]): Promise<DocView[]> {
  return (await request.post<DocView[]>('/admin/kb/merge/doc/validate', { ids })).data
}

export async function aiMerge(contentType: KbContentType, ids: number[]): Promise<KbMergeProduct> {
  return (await request.post<KbMergeProduct>(`/admin/kb/merge/ai?type=${contentType}`, { ids }, { timeout: 120000 }))
    .data
}

export async function submitKpMerge(ids: number[], product: KbMergeProduct): Promise<KbContentItemView> {
  return (await request.post<KbContentItemView>('/admin/kb/merge/kp/submit', { ids, product })).data
}

export async function submitDocMerge(ids: number[], product: KbMergeProduct): Promise<KbContentItemView> {
  return (await request.post<KbContentItemView>('/admin/kb/merge/doc/submit', { ids, product })).data
}

// —— 通用 AI 辅助（直达模式：内容不绑定素材行，与素材库共用同一套能力） ——

export async function aiRewriteContent(contentMd: string): Promise<SourceRewriteResp> {
  return (await request.post<SourceRewriteResp>('/admin/kb/ai/rewrite', { contentMd }, { timeout: 120000 })).data
}

export async function aiSummaryContent(contentMd: string): Promise<string> {
  const resp = (await request.post<{ summary: string }>('/admin/kb/ai/summary', { contentMd }, { timeout: 120000 }))
    .data
  return resp.summary
}

export async function aiClassifyContent(
  contentMd: string,
  domainCode: string,
  topics: string[]
): Promise<SourceClassifyResp> {
  return (
    await request.post<SourceClassifyResp>('/admin/kb/ai/classify', { contentMd, domainCode, topics }, { timeout: 120000 })
  ).data
}

export async function aiDifficultyContent(
  contentMd: string
): Promise<{ difficulty: string; reason: string }> {
  return (
    await request.post<{ difficulty: string; reason: string }>('/admin/kb/ai/difficulty', { contentMd }, { timeout: 120000 })
  ).data
}

// —— 审核队列（内容生产台） ——

export async function reviewQueue(contentType?: string): Promise<KbContentItemView[]> {
  const params: Record<string, string> = {}
  if (contentType) params.contentType = contentType
  return (await request.get<KbContentItemView[]>('/admin/kb/reviews/queue', { params })).data
}

export async function reviewDue(): Promise<KbContentItemView[]> {
  return (await request.get<KbContentItemView[]>('/admin/kb/reviews/due')).data
}

export async function reviewDetail(contentType: KbContentType, id: number): Promise<KbReviewDetail> {
  return (await request.get<KbReviewDetail>(`/admin/kb/reviews/${contentType}/${id}/detail`)).data
}

export async function reviewPass(
  contentType: KbContentType,
  id: number,
  checks: KbReviewChecks,
  comment: string,
  dueDays?: number
): Promise<KbContentItemView> {
  return (
    await request.post<KbContentItemView>(`/admin/kb/reviews/${contentType}/${id}/pass`, {
      checks,
      comment,
      dueDays
    })
  ).data
}

export async function reviewReject(contentType: KbContentType, id: number, comment: string): Promise<KbContentItemView> {
  return (await request.post<KbContentItemView>(`/admin/kb/reviews/${contentType}/${id}/reject`, { comment })).data
}

export async function reviewDiscard(contentType: KbContentType, id: number, comment: string): Promise<KbContentItemView> {
  return (await request.post<KbContentItemView>(`/admin/kb/reviews/${contentType}/${id}/discard`, { comment })).data
}

export async function reviewResubmit(contentType: KbContentType, id: number): Promise<KbContentItemView> {
  return (await request.post<KbContentItemView>(`/admin/kb/reviews/${contentType}/${id}/resubmit`)).data
}

export async function reviewRecheck(contentType: KbContentType, id: number): Promise<KbContentItemView> {
  return (await request.post<KbContentItemView>(`/admin/kb/reviews/${contentType}/${id}/recheck`)).data
}

/** 标准内容状态中文标签（与后端 kb_status 常量组同口径） */
export const KB_STATUS_LABELS: Record<KbStatus, string> = {
  draft: '草稿',
  pending: '待审核',
  available: '已上线',
  deprecated: '已弃用',
  merged: '合并下线'
}
