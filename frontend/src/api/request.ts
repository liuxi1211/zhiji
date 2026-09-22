import axios from 'axios'
import type { AxiosError, AxiosInstance } from 'axios'
import type { ApiError } from './types'

/**
 * 统一的请求封装。
 *
 * baseURL 用相对路径 /api，原因：
 *   开发期走 Vite proxy（vite.config.ts）转发到 8080；
 *   生产期由 nginx 把 /api 反代到后端。
 * 两种环境同一份代码，不需要按环境切换 baseURL。
 */
const request: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 20000,
  headers: { 'Content-Type': 'application/json' }
})

/** 把后端的 { status, code, message } 错误体透出来，避免调用方拿到的只有一句 axios 文案 */
request.interceptors.response.use(
  (res) => res,
  (error: AxiosError<ApiError>) => {
    const body = error.response?.data
    if (body && typeof body === 'object' && 'code' in body) {
      return Promise.reject(body)
    }
    return Promise.reject({
      status: error.response?.status ?? 0,
      code: 'NETWORK_ERROR',
      message: error.message || '网络异常'
    } satisfies ApiError)
  }
)

export default request
