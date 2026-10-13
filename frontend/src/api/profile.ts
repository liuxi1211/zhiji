import request from './request'
import type {
  AnchorStatus,
  CaseView,
  ClaimView,
  IntakeClaim,
  IntakeHistoryView,
  ChatTurnView,
  KpCandidate,
  ProfileDetail,
  ProfileListItem,
  TargetLevel
} from './types'

export interface ProfileCreateReq {
  name: string
  years?: number | null
  position?: string
  /** 简历全文（选填）：创建时贴入，AI 起草时作为事实来源 */
  resumeText?: string
}

/** 备考计划整表替换语义：请求字段全量生效，null 即清空（后端 ProfileService.update） */
export interface ProfileUpdateReq {
  name: string
  resumeText: string | null
  years: number | null
  position: string | null
  jdText: string | null
  techStack: string | null
  goodAt: string | null
  targetLevel: string | null
  planWeeks: number | null
  dailyMinutes: number | null
}

export interface ClaimReq {
  title?: string
  wording: string
  /** null = 不改动锚点；[] = 清空锚点（回未映射） */
  anchorIds?: number[] | null
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

/** 删除档案：连带清理技能条、项目素材、建档对话（服务端已处理当前档案顶替） */
export async function deleteProfile(id: number): Promise<void> {
  await request.delete(`/profile/${id}`)
}

export async function finalizeProfile(id: number): Promise<ProfileListItem> {
  return (await request.post<ProfileListItem>(`/profile/${id}/finalize`)).data
}

export async function unfinalizeProfile(id: number): Promise<ProfileListItem> {
  return (await request.post<ProfileListItem>(`/profile/${id}/unfinalize`)).data
}

// —— 建档对话（PRD v2 4.4：首轮即出完整草案，之后自然语言修订） ——

export async function getChatHistory(id: number): Promise<IntakeHistoryView> {
  return (await request.get<IntakeHistoryView>(`/profile/${id}/chat`)).data
}

/** 首轮生成，分钟级任务放宽超时（对齐后端 CHAT_TIMEOUT_SECONDS） */
export async function startIntakeChat(id: number): Promise<ChatTurnView> {
  return (await request.post<ChatTurnView>(`/profile/${id}/chat/first`, null, { timeout: 120_000 })).data
}

export async function reviseIntakeChat(id: number, text: string): Promise<ChatTurnView> {
  return (await request.post<ChatTurnView>(`/profile/${id}/chat/revise`, { text }, { timeout: 120_000 })).data
}

export async function clearIntakeChat(id: number): Promise<void> {
  await request.delete(`/profile/${id}/chat`)
}

// —— 技能条 ——

/** 整版采纳（就用这版 = 拍板），替换全部对话来源技能条 */
export async function acceptClaims(
  id: number,
  claims: IntakeClaim[]
): Promise<ClaimView[]> {
  return (await request.post<ClaimView[]>(`/profile/${id}/claims/accept`, { claims })).data
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

/** 定稿后锚定映射：按语义把技能条匹配到题库知识点 */
export async function mapAnchors(
  id: number
): Promise<{ mapped: number; pending: number; zero: number }> {
  return (await request.post<{ mapped: number; pending: number; zero: number }>(
    `/profile/${id}/anchors/map`, null, { timeout: 120_000 }
  )).data
}

/** 简历技能段导出（Markdown，占位未清零会被后端拒绝） */
export async function exportMarkdown(id: number): Promise<string> {
  return (await request.get<{ markdown: string }>(`/profile/${id}/export`)).data.markdown
}

// —— 项目素材 ——

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

/** 占位符检测：wording 中是否残留【…】 */
export function hasPlaceholder(text: string): boolean {
  return /【[^【】]*】/.test(text ?? '')
}

/** 锚定状态展示文案 */
export function anchorStatusName(status: AnchorStatus): string {
  switch (status) {
    case 'mapped':
      return '已锚定'
    case 'pending':
      return '锚点待确认'
    case 'zero':
      return '未匹配到考点'
    default:
      return '未锚定'
  }
}
