import request from './request'
import type { DrawResp, ReviewMode, ReviewResultView, ReviewTodayView } from './types'

// —— 今日复习（核心模块定义 4.3，T12） ——
// 今日清单（四路信号混合推送）+ 随机抽取（碎片时间动态权重）；复测只更新复习参数不回写学习五态。

/** 今日清单视图（items + stats + 日期 + 档案名/职级；未定稿 400 LEARN_PROFILE_NOT_FINALIZED） */
export async function getToday(): Promise<ReviewTodayView> {
  return (await request.get<ReviewTodayView>('/review/today')).data
}

/** 复习自评：known=true 打后移排期 / false 打回复测队列并累积 fail_count */
export async function submitResult(kpId: number, known: boolean, mode: ReviewMode): Promise<ReviewResultView> {
  return (await request.post<ReviewResultView>(`/review/kp/${kpId}/result`, { known, mode })).data
}

/** 随机抽取一题（domainCode 按域集中复习；exclude 会话内已抽 kpId，池空 400 REVIEW_POOL_EMPTY） */
export async function draw(domainCode?: string, excludeIds?: number[]): Promise<DrawResp> {
  const params: Record<string, string> = {}
  if (domainCode) params.domainCode = domainCode
  if (excludeIds && excludeIds.length > 0) params.exclude = excludeIds.join(',')
  return (await request.get<DrawResp>('/review/draw', { params })).data
}

/** 复习卡片 tag 文案 */
export const REVIEW_TAG_LABELS: Record<string, string> = {
  due: '到期复测',
  weak: '弱点',
  spot: '抽查',
  new: '新题',
  draw: '抽取'
}
