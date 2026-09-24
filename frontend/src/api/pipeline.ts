import request from './request'
import type {
  BatchSummary,
  CanonicalDetail,
  CanonicalItem,
  CanonicalStatus,
  CoverageView,
  DocDetail,
  NameSuggestion,
  RunSummary,
  SourceDocStatus,
  SourceDocView,
  SourceForm,
  SplitOutcome,
  TreeView
} from './types'

/** 素材库与知识地图接口（pipeline，T6/T7）：/api/admin 前缀，员工会话由拦截器统一校验 */

export async function importDocs(files: File[], sourceRef?: string): Promise<{ batchId: string; docs: { id: number; fileName: string }[] }> {
  const form = new FormData()
  files.forEach((file) => form.append('files', file))
  if (sourceRef) {
    form.append('sourceRef', sourceRef)
  }
  return (
    await request.post('/admin/docs/import', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000
    })
  ).data
}

export async function listBatches(): Promise<BatchSummary[]> {
  return (await request.get<BatchSummary[]>('/admin/docs/batches')).data
}

export async function listBatchDocs(batchId: string): Promise<SourceDocView[]> {
  return (await request.get<SourceDocView[]>(`/admin/docs/batches/${batchId}`)).data
}

export async function listDocs(params?: { batchId?: string; status?: SourceDocStatus; form?: SourceForm }): Promise<SourceDocView[]> {
  return (await request.get<SourceDocView[]>('/admin/docs', { params })).data
}

export async function getDoc(id: number): Promise<DocDetail> {
  return (await request.get<DocDetail>(`/admin/docs/${id}`)).data
}

export async function parseDoc(id: number): Promise<void> {
  await request.post(`/admin/docs/${id}/parse`)
}

export async function retryDoc(id: number): Promise<void> {
  await request.post(`/admin/docs/${id}/retry`)
}

export async function changeDocForm(id: number, form: SourceForm): Promise<void> {
  await request.post(`/admin/docs/${id}/form`, { form })
}

export async function updateSourceRef(id: number, sourceRef: string): Promise<void> {
  await request.put(`/admin/docs/${id}/source-ref`, { sourceRef })
}

export async function splitDoc(id: number): Promise<SplitOutcome> {
  return (await request.post<SplitOutcome>(`/admin/docs/${id}/split`)).data
}

export async function inverseGen(id: number, sectionIndexes: number[]): Promise<SplitOutcome> {
  return (await request.post<SplitOutcome>(`/admin/docs/${id}/inverse-gen`, sectionIndexes)).data
}

export async function updateCandidate(
  id: number,
  data: { title?: string; answerEvidence?: string; form?: SourceForm; note?: string }
): Promise<void> {
  await request.put(`/admin/docs/candidates/${id}`, data)
}

export async function discardCandidate(id: number): Promise<void> {
  await request.post(`/admin/docs/candidates/${id}/discard`)
}

export async function deleteDoc(id: number): Promise<void> {
  await request.delete(`/admin/docs/${id}`)
}

/* ================= 知识地图（T7）：/api/admin/knowledge-map ================= */

/** 运行增量归一（结构投票 → embedding 召回 → LLM 精判 → 语料簇聚类与命名） */
export async function runNormalization(): Promise<RunSummary> {
  return (await request.post<RunSummary>('/admin/knowledge-map/run', {})).data
}

/** 覆盖率统计条 + 按簇建设排行（覆盖源合计倒序） */
export async function getCoverage(): Promise<CoverageView> {
  return (await request.get<CoverageView>('/admin/knowledge-map/coverage')).data
}

/** 三层视图的域 → 簇层级（含未归簇散题计数） */
export async function getKnowledgeTree(): Promise<TreeView> {
  return (await request.get<TreeView>('/admin/knowledge-map/tree')).data
}

/** 归一题列表：clusterId 过滤簇内；unclustered=true 查未归簇桶；覆盖源数倒序 */
export async function listCanonicals(params?: {
  clusterId?: number
  unclustered?: boolean
  status?: CanonicalStatus
}): Promise<{ list: CanonicalItem[] }> {
  return (await request.get<{ list: CanonicalItem[] }>('/admin/knowledge-map/canonicals', { params })).data
}

/** 归一题详情 + 源候选溯源 */
export async function getCanonical(id: number): Promise<CanonicalDetail> {
  return (await request.get<CanonicalDetail>(`/admin/knowledge-map/canonicals/${id}`)).data
}

/** 确认同题（pending → confirmed），可顺带调整簇归属 */
export async function confirmCanonical(id: number, clusterId?: number): Promise<void> {
  await request.post(`/admin/knowledge-map/canonicals/${id}/confirm`, { clusterId })
}

/** 解除知识点映射（自动映射纠错口）：built → confirmed */
export async function unbuildCanonical(id: number): Promise<void> {
  await request.post(`/admin/knowledge-map/canonicals/${id}/unbuild`, {})
}

/** 拆分回退：all=整题解散；partial=拆出指定候选（退回候选池散题） */
export async function splitCanonical(id: number, mode: 'all' | 'partial', candidateIds?: number[]): Promise<{ dissolved: boolean; returned: number }> {
  return (await request.post(`/admin/knowledge-map/canonicals/${id}/split`, { mode, candidateIds })).data
}

/** 调整簇归属（clusterId 传 undefined 移出簇） */
export async function moveCanonicalCluster(id: number, clusterId?: number | null): Promise<void> {
  await request.post(`/admin/knowledge-map/canonicals/${id}/cluster`, { clusterId: clusterId ?? null })
}

/** 语料簇合并编排：先移归一题归属再 retire 源簇 */
export async function mergeKnowledgeClusters(id: number, targetId: number): Promise<void> {
  await request.post(`/admin/knowledge-map/clusters/${id}/merge`, { targetId })
}

/** AI 簇命名建议（只建议不落库，采纳走 kb.updateCluster） */
export async function suggestClusterName(id: number): Promise<NameSuggestion> {
  return (await request.post<NameSuggestion>(`/admin/knowledge-map/clusters/${id}/suggest-name`, {})).data
}
