import axios from 'axios'
import { ElMessage } from 'element-plus'
import type { AxiosError, AxiosInstance } from 'axios'
import type { ApiError } from './types'

/**
 * 统一的请求封装。
 *
 * baseURL 用相对路径 /api，原因：
 *   开发期走 Vite proxy（vite.config.ts）转发到 8080；
 *   生产期由 nginx 把 /api 反代到后端。
 * 两种环境同一份代码，不需要按环境切换 baseURL。
 *
 * 双会话（D18）：/api/admin/** 携带员工 token，其余业务接口携带客户 token；
 * 登录注册 / 常量下发 / 分享为公开接口不带 token。
 * 401 清对应会话并跳登录页；403 提示无权限。
 */
const STAFF_TOKEN_KEY = 'zj_staff_token'
const CUSTOMER_TOKEN_KEY = 'zj_customer_token'

const PUBLIC_PREFIXES = ['/auth/', '/constants', '/share/', '/demo/']

function isPublic(url: string): boolean {
  return PUBLIC_PREFIXES.some((prefix) => url.startsWith(prefix))
}

function tokenFor(url: string): string | null {
  if (url.startsWith('/admin/')) {
    return localStorage.getItem(STAFF_TOKEN_KEY)
  }
  return localStorage.getItem(CUSTOMER_TOKEN_KEY)
}

const request: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 20000,
  headers: { 'Content-Type': 'application/json' }
})

request.interceptors.request.use((config) => {
  const url = config.url ?? ''
  if (!isPublic(url)) {
    const token = tokenFor(url)
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
  }
  return config
})

request.interceptors.response.use(
  (res) => res,
  (error: AxiosError<ApiError>) => {
    const body = error.response?.data
    const apiError: ApiError = body && typeof body === 'object' && 'code' in body
      ? body
      : { status: error.response?.status ?? 0, code: 'NETWORK_ERROR', message: error.message || '网络异常' }
    const url = error.config?.url ?? ''
    if (apiError.status === 401 && !isPublic(url)) {
      if (url.startsWith('/admin/')) {
        localStorage.removeItem(STAFF_TOKEN_KEY)
        window.location.href = '/admin/login'
      } else {
        localStorage.removeItem(CUSTOMER_TOKEN_KEY)
        window.location.href = '/login'
      }
    } else if (apiError.status === 403) {
      ElMessage.error('没有权限执行该操作')
    }
    return Promise.reject(apiError)
  }
)

export default request
