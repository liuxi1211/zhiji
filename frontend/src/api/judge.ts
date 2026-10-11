import request from './request'
import type {
  JudgeAnswerResp,
  JudgeAnswerView,
  JudgeSessionItemView,
  JudgeSessionView,
  JudgeVerdict,
  KbContentItemView
} from './types'

// —— 试判台（判定引擎的 B 端质检入口，核心模块定义 6.1） ——

export async function searchAvailableKps(keyword: string): Promise<KbContentItemView[]> {
  return (await request.get<KbContentItemView[]>('/admin/judge/kps', { params: { keyword } })).data
}

export async function createSession(kpId: number, level: string): Promise<JudgeSessionView> {
  return (await request.post<JudgeSessionView>('/admin/judge/sessions', { kpId, level })).data
}

export async function listSessions(status?: string): Promise<JudgeSessionItemView[]> {
  const params: Record<string, string> = {}
  if (status) params.status = status
  return (await request.get<JudgeSessionItemView[]>('/admin/judge/sessions', { params })).data
}

export async function getSession(id: number): Promise<JudgeSessionView> {
  return (await request.get<JudgeSessionView>(`/admin/judge/sessions/${id}`)).data
}

export async function submitAnswer(
  sessionId: number,
  followupId: string | null,
  answer: string
): Promise<JudgeAnswerResp> {
  return (
    await request.post<JudgeAnswerResp>(`/admin/judge/sessions/${sessionId}/answers`, { followupId, answer }, {
      timeout: 120000
    })
  ).data
}

export async function submitEmpty(sessionId: number, followupId: string | null): Promise<JudgeAnswerResp> {
  return (await request.post<JudgeAnswerResp>(`/admin/judge/sessions/${sessionId}/empty`, { followupId })).data
}

export async function selfEval(
  sessionId: number,
  answerId: number,
  verdict: JudgeVerdict
): Promise<JudgeAnswerView> {
  return (
    await request.post<JudgeAnswerView>(`/admin/judge/sessions/${sessionId}/self-eval`, { answerId, verdict })
  ).data
}

export async function closeSession(sessionId: number): Promise<JudgeSessionView> {
  return (await request.post<JudgeSessionView>(`/admin/judge/sessions/${sessionId}/close`)).data
}

/** 答况中文标签（与 judge_verdict 常量组同口径） */
export const VERDICT_LABELS: Record<JudgeVerdict, string> = {
  correct: '答对',
  missing: '答缺',
  wrong: '答错',
  empty: '未答'
}

/** 档位中文标签（公式四档） */
export const GRADE_LABELS: Record<string, string> = {
  excellent: '优秀',
  good: '良好',
  pass: '及格',
  fail: '不及格'
}

/** 职级中文标签（五档） */
export const LEVEL_LABELS: Record<string, string> = {
  junior: '初级',
  mid: '中级',
  senior: '高级',
  expert: '资深',
  master: '专家'
}
