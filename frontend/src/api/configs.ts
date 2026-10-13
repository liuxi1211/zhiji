import request from './request'
import type { ConfigSectionView } from './types'

export async function getConfigSections(): Promise<ConfigSectionView[]> {
  return (await request.get<ConfigSectionView[]>('/admin/configs')).data
}

export async function updateConfigSection(key: string, value: unknown): Promise<ConfigSectionView> {
  return (await request.put<ConfigSectionView>(`/admin/configs/${key}`, value)).data
}
