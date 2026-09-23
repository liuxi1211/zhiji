import request from './request'
import type { StaffAccountView, StaffRole } from './types'

export async function listStaffAccounts(): Promise<StaffAccountView[]> {
  return (await request.get<StaffAccountView[]>('/admin/accounts')).data
}

export interface CreateStaffReq {
  username: string
  password: string
  role: StaffRole
}

export async function createStaffAccount(req: CreateStaffReq): Promise<StaffAccountView> {
  return (await request.post<StaffAccountView>('/admin/accounts', req)).data
}

export async function updateStaffStatus(id: number, status: 'active' | 'disabled'): Promise<StaffAccountView> {
  return (await request.post<StaffAccountView>(`/admin/accounts/${id}/status`, { status })).data
}

export async function resetStaffPassword(id: number, password: string): Promise<StaffAccountView> {
  return (await request.post<StaffAccountView>(`/admin/accounts/${id}/reset-password`, { password })).data
}
