import request from './request'
import type { CaptchaResp, CustomerAuthResp, CustomerMeResp, StaffAuthResp, StaffMeResp } from './types'

export interface CustomerRegisterReq {
  username: string
  password: string
  captchaId: string
  captchaCode: string
}

export interface CustomerLoginReq {
  username: string
  password: string
  captchaId: string
  captchaCode: string
}

export interface StaffLoginReq {
  username: string
  password: string
}

export async function customerCaptcha(): Promise<CaptchaResp> {
  return (await request.get<CaptchaResp>('/auth/customer/captcha')).data
}

export async function customerRegister(req: CustomerRegisterReq): Promise<CustomerAuthResp> {
  return (await request.post<CustomerAuthResp>('/auth/customer/register', req)).data
}

export async function customerLogin(req: CustomerLoginReq): Promise<CustomerAuthResp> {
  return (await request.post<CustomerAuthResp>('/auth/customer/login', req)).data
}

export async function customerMe(): Promise<CustomerMeResp> {
  return (await request.get<CustomerMeResp>('/auth/customer/me')).data
}

export async function staffLogin(req: StaffLoginReq): Promise<StaffAuthResp> {
  return (await request.post<StaffAuthResp>('/auth/staff/login', req)).data
}

export async function staffMe(): Promise<StaffMeResp> {
  return (await request.get<StaffMeResp>('/auth/staff/me')).data
}
