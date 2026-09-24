import request from './request'
import type {
  BatchSummary,
  DocDetail,
  SourceDocStatus,
  SourceDocView,
  SourceForm,
  SplitOutcome
} from './types'

/** 素材库接口（pipeline，T6）：/api/admin/docs，员工会话由拦截器统一校验 */

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
