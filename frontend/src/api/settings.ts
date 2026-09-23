import request from './request'
import type {
  LlmConnectivityReport,
  LlmLimitsView,
  LlmRouteTargetView,
  LlmSettingsView,
  LlmUsageView
} from './types'

export async function getLlmSettings(): Promise<LlmSettingsView> {
  return (await request.get<LlmSettingsView>('/admin/settings')).data
}

export async function updateLlmRoutes(
  routes: Record<string, LlmRouteTargetView>
): Promise<LlmSettingsView> {
  return (await request.put<LlmSettingsView>('/admin/settings/routes', { routes })).data
}

export async function updateLlmLimits(limits: LlmLimitsView): Promise<LlmSettingsView> {
  return (await request.put<LlmSettingsView>('/admin/settings/limits', limits)).data
}

export async function checkLlmConnectivity(): Promise<LlmConnectivityReport> {
  return (await request.post<LlmConnectivityReport>('/admin/settings/connectivity-check')).data
}

export async function getLlmUsage(): Promise<LlmUsageView> {
  return (await request.get<LlmUsageView>('/admin/settings/usage')).data
}
