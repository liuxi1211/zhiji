import axios from 'axios'

/**
 * 环境验证接口（后端 DemoController，路径 /demo 非 /api）。
 *
 * 单独用一个 axios 实例是因为 demo 接口的路径前缀与正式 API 不同，
 * 且它只是"跑通链路"用的探针，将来随 demo 包一起删除。
 * 保留它的意义：前端骨架必须有一条真能打到数据库的链路，
 * 否则"骨架跑通"只是页面能渲染，证明不了全链路。
 */
const demoRequest = axios.create({
  baseURL: '/demo',
  timeout: 10000
})

export interface DemoKp {
  id: number
  bizId: string
  title: string
  subType: string
  difficulty: number
  followups: string
}

export interface CacheStat {
  dbHitCount: number
  note: string
}

export function listDemoKp() {
  return demoRequest.get<DemoKp[]>('/kp').then((r) => r.data)
}

export function getCacheStat() {
  return demoRequest.get<CacheStat>('/cache-stat').then((r) => r.data)
}
