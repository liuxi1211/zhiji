import request from './request'
import type { LlmConnectivityReport, LlmUsageView } from './types'

export async function checkLlmConnectivity(): Promise<LlmConnectivityReport> {
  return (await request.post<LlmConnectivityReport>('/admin/settings/connectivity-check')).data
}

export async function getLlmUsage(): Promise<LlmUsageView> {
  return (await request.get<LlmUsageView>('/admin/settings/usage')).data
}
