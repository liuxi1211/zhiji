import request from './request'
import type { LlmConnectivityReport, LlmUsageView } from './types'

export async function checkLlmConnectivity(): Promise<LlmConnectivityReport> {
  return (await request.post<LlmConnectivityReport>('/admin/settings/connectivity-check')).data
}

export async function getLlmUsage(): Promise<LlmUsageView> {
  return (await request.get<LlmUsageView>('/admin/settings/usage')).data
}

/** 提示词「恢复默认」参照：classpath 文件版模板（code → 文本） */
export async function getLlmPromptDefaults(): Promise<Record<string, string>> {
  return (await request.get<Record<string, string>>('/admin/settings/prompt-defaults')).data
}

/** 单渠道连通测试结果 */
export interface LlmChannelProbe {
  ok: boolean
  detail: string
  latencyMs: number
  model: string | null
}

/** 对指定渠道发起一次最小真实请求（测已保存配置，非页面草稿） */
export async function checkLlmChannel(channel: string): Promise<LlmChannelProbe> {
  return (await request.post<LlmChannelProbe>('/admin/settings/channel-check', { channel })).data
}
