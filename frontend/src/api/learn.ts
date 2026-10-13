import request from './request'
import type {
  DocView,
  JudgeAnswerResp,
  JudgeAnswerView,
  JudgeVerdict,
  LearnPathView,
  LearnStateView,
  PracticeBriefView,
  PracticeCloseView,
  PracticeSessionView
} from './types'

// —— 学习路径与练习（核心模块定义 4.2，T11） ——
// 学习路径实时推导（不落表）；练习复用判定引擎（/api/learn 下 customer 归属）。

/** 当前档案的学习路径全量视图（未定稿 400 LEARN_PROFILE_NOT_FINALIZED） */
export async function getPath(): Promise<LearnPathView> {
  return (await request.get<LearnPathView>('/learn/path')).data
}

/** 速览自评：known=true → quick（抽查范围）/ false → weak（弱点加权） */
export async function quickEval(kpId: number, known: boolean): Promise<LearnStateView> {
  return (await request.post<LearnStateView>(`/learn/kp/${kpId}/quick`, { known })).data
}

/** 放弃一道题（移出单元、进放弃清单，可选填原因） */
export async function abandonKp(kpId: number, reason?: string): Promise<LearnStateView> {
  return (await request.post<LearnStateView>(`/learn/kp/${kpId}/abandon`, { reason: reason ?? '' })).data
}

/** 恢复放弃的题（回单元 learning） */
export async function restoreKp(kpId: number): Promise<LearnStateView> {
  return (await request.delete<LearnStateView>(`/learn/kp/${kpId}/abandon`)).data
}

/** 题面预览（选题后、选节奏前；不开判定会话） */
export async function getBrief(kpId: number): Promise<PracticeBriefView> {
  return (await request.get<PracticeBriefView>(`/learn/kp/${kpId}/brief`)).data
}

/** 挂载资料详情（"看不懂"展开层，只回 available） */
export async function getDoc(docId: number): Promise<DocView> {
  return (await request.get<DocView>(`/learn/docs/${docId}`)).data
}

/** 开练习判定会话（任意 available kp；返回会话 + 展示结构 + 目标刻度） */
export async function startPractice(kpId: number): Promise<PracticeSessionView> {
  return (await request.post<PracticeSessionView>(`/learn/kp/${kpId}/practice`, null, { timeout: 60000 })).data
}

export async function getLearnSession(id: number): Promise<PracticeSessionView> {
  return (await request.get<PracticeSessionView>(`/learn/sessions/${id}`)).data
}

/** 提交作答判定（模型较慢，放宽超时） */
export async function submitAnswer(
  sessionId: number,
  followupId: string | null,
  answer: string
): Promise<JudgeAnswerResp> {
  return (
    await request.post<JudgeAnswerResp>(
      `/learn/sessions/${sessionId}/answers`,
      { followupId, answer },
      { timeout: 120000 }
    )
  ).data
}

/** 未答短路（不调模型） */
export async function submitEmpty(sessionId: number, followupId: string | null): Promise<JudgeAnswerResp> {
  return (await request.post<JudgeAnswerResp>(`/learn/sessions/${sessionId}/empty`, { followupId })).data
}

/** 降级自评（判定失败后解锁） */
export async function selfEval(
  sessionId: number,
  answerId: number,
  verdict: JudgeVerdict
): Promise<JudgeAnswerView> {
  return (await request.post<JudgeAnswerView>(`/learn/sessions/${sessionId}/self-eval`, { answerId, verdict })).data
}

/** 结束练习：出四档小结并回写学习状态（通过 → pass 实心） */
export async function closeSession(sessionId: number): Promise<PracticeCloseView> {
  return (await request.post<PracticeCloseView>(`/learn/sessions/${sessionId}/close`, null, { timeout: 120000 })).data
}

/** 学习状态标签（learn_status 常量组兜底文案） */
export const LEARN_STATUS_LABELS: Record<string, string> = {
  learning: '进行中',
  quick: '速览通过',
  pass: '判定通过',
  weak: '待加强',
  abandoned: '已放弃'
}

/** 路径信号标签 */
export const SIGNAL_LABELS: Record<string, string> = {
  claim: '主攻',
  baseline: '基线',
  jd: 'JD'
}
