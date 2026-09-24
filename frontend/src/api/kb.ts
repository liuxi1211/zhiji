import request from './request'
import type {
  FreqLevel,
  KpClusterDetail,
  KpClusterListItem,
  KpDetail,
  KpForwardStatus,
  KpListItem,
  KpMeta,
  KpSavePayload
} from './types'

export async function listClusters(params?: {
  domainId?: number
  status?: string
}): Promise<{ list: KpClusterListItem[] }> {
  return (await request.get<{ list: KpClusterListItem[] }>('/admin/clusters', { params })).data
}

export async function getCluster(id: number): Promise<KpClusterDetail> {
  return (await request.get<KpClusterDetail>(`/admin/clusters/${id}`)).data
}

export async function createCluster(data: {
  domainId: number
  name: string
  description?: string
}): Promise<{ id: number }> {
  return (await request.post<{ id: number }>('/admin/clusters', data)).data
}

export async function updateCluster(
  id: number,
  data: {
    name?: string
    domainId?: number
    description?: string
    zeroShotMd?: string
    referenceSolutionMd?: string
  }
): Promise<void> {
  await request.put(`/admin/clusters/${id}`, data)
}

export async function changeClusterStatus(id: number, status: KpForwardStatus): Promise<void> {
  await request.post(`/admin/clusters/${id}/status`, { status })
}

export async function mergeCluster(id: number, targetId: number): Promise<void> {
  await request.post(`/admin/clusters/${id}/merge`, { targetId })
}

export async function listKp(params: {
  keyword?: string
  domainId?: number
  clusterId?: number
  subType?: string
  status?: string
  difficulty?: number
  baseline?: 'marked' | 'unmarked'
  reviewDue?: boolean
  page?: number
  size?: number
}): Promise<{ list: KpListItem[]; total: number }> {
  return (await request.get<{ list: KpListItem[]; total: number }>('/admin/kp', { params })).data
}

export async function getKp(id: number): Promise<KpDetail> {
  return (await request.get<KpDetail>(`/admin/kp/${id}`)).data
}

export async function createKp(data: KpSavePayload): Promise<{ id: number }> {
  return (await request.post<{ id: number }>('/admin/kp', data)).data
}

export async function updateKp(id: number, data: KpSavePayload): Promise<void> {
  await request.put(`/admin/kp/${id}`, data)
}

export async function addFollowupLayer(
  id: number,
  data: { dir: string; text: string; expect?: string; stopCondition?: string }
): Promise<{ layerKey: string }> {
  return (await request.post<{ layerKey: string }>(`/admin/kp/${id}/followup-layers`, data)).data
}

export async function disableFollowupLayer(id: number, layerKey: string): Promise<void> {
  await request.post(`/admin/kp/${id}/followup-layers/${layerKey}/disable`)
}

export async function changeKpStatus(id: number, status: KpForwardStatus): Promise<void> {
  await request.post(`/admin/kp/${id}/status`, { status })
}

export async function retireKp(
  id: number,
  data: { mode: 'discard' } | { mode: 'merge'; targetId: number }
): Promise<void> {
  await request.post(`/admin/kp/${id}/retire`, data)
}

export async function saveFreqSignal(
  id: number,
  data: { signalLevel: FreqLevel; note: string }
): Promise<void> {
  await request.put(`/admin/kp/${id}/freq-signal`, data)
}

export async function getKpMeta(): Promise<KpMeta> {
  return (await request.get<KpMeta>('/admin/kp/meta')).data
}
