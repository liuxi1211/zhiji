import request from './request'
import type {
  CaseView,
  ClaimDraft,
  ClaimView,
  KpCandidate,
  ProfileDetail,
  ProfileListItem,
  TargetLevel
} from './types'

export interface ProfileCreateReq {
  name: string
  years?: number | null
  position?: string
}

/** 备考计划整表替换语义：请求字段全量生效，null 即清空（后端 ProfileService.update） */
export interface ProfileUpdateReq {
  name: string
  resumeText: string | null
  years: number | null
  position: string | null
  jdText: string | null
  techStack: string | null
  targetLevel: string | null
  planWeeks: number | null
  dailyMinutes: number | null
}

export interface ClaimReq {
  wording: string
  anchorIds: number[]
  source?: 'ai' | 'manual'
}

export interface CaseReq {
  projectName: string
  background: string
  metrics: string
  conflict: string
  result: string
}

export async function listProfiles(): Promise<ProfileListItem[]> {
  return (await request.get<ProfileListItem[]>('/profile')).data
}

export async function createProfile(req: ProfileCreateReq): Promise<ProfileListItem> {
  return (await request.post<ProfileListItem>('/profile', req)).data
}

export async function getProfile(id: number): Promise<ProfileDetail> {
  return (await request.get<ProfileDetail>(`/profile/${id}`)).data
}

export async function updateProfile(id: number, req: ProfileUpdateReq): Promise<ProfileListItem> {
  return (await request.put<ProfileListItem>(`/profile/${id}`, req)).data
}

export async function activateProfile(id: number): Promise<void> {
  await request.post(`/profile/${id}/activate`)
}

export async function finalizeProfile(id: number): Promise<ProfileListItem> {
  return (await request.post<ProfileListItem>(`/profile/${id}/finalize`)).data
}

export async function unfinalizeProfile(id: number): Promise<ProfileListItem> {
  return (await request.post<ProfileListItem>(`/profile/${id}/unfinalize`)).data
}

/** AI 起草技能条：产物不落库，分钟级任务放宽超时（对齐后端 DRAFT_TIMEOUT_SECONDS） */
export async function draftClaims(id: number): Promise<ClaimDraft[]> {
  return (await request.post<ClaimDraft[]>(`/profile/${id}/ai/claims`, null, { timeout: 120_000 })).data
}

export async function addClaim(profileId: number, req: ClaimReq): Promise<ClaimView> {
  return (await request.post<ClaimView>(`/profile/${profileId}/claims`, req)).data
}

export async function updateClaim(profileId: number, claimId: number, req: ClaimReq): Promise<ClaimView> {
  return (await request.put<ClaimView>(`/profile/${profileId}/claims/${claimId}`, req)).data
}

export async function confirmClaim(profileId: number, claimId: number): Promise<ClaimView> {
  return (await request.post<ClaimView>(`/profile/${profileId}/claims/${claimId}/confirm`)).data
}

export async function deleteClaim(profileId: number, claimId: number): Promise<void> {
  await request.delete(`/profile/${profileId}/claims/${claimId}`)
}

export async function addCase(profileId: number, req: CaseReq): Promise<CaseView> {
  return (await request.post<CaseView>(`/profile/${profileId}/cases`, req)).data
}

export async function updateCase(profileId: number, caseId: number, req: CaseReq): Promise<CaseView> {
  return (await request.put<CaseView>(`/profile/${profileId}/cases/${caseId}`, req)).data
}

export async function deleteCase(profileId: number, caseId: number): Promise<void> {
  await request.delete(`/profile/${profileId}/cases/${caseId}`)
}

/** 年限→目标职级缺省推测（表单预填，用户可改） */
export async function getLevelSuggestion(years: number): Promise<{ level: TargetLevel; name: string }> {
  return (
    await request.get<{ level: TargetLevel; name: string }>('/profile/level-suggestion', {
      params: { years }
    })
  ).data
}

/** 知识点候选搜索（锚点挑选；同一能力后续服务复盘录题） */
export async function searchKpCandidates(q: string, limit = 10): Promise<KpCandidate[]> {
  return (await request.get<KpCandidate[]>('/kp/search', { params: { q, limit } })).data
}
