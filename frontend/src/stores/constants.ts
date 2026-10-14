import { defineStore } from 'pinia'
import request from '@/api/request'

/**
 * 常量组 store：启动时从 GET /api/constants 拉全量，
 * 之后按常量组名称取 code/name 展示（label 由后端下发，前端免维护）。
 */
interface ConstantItemView {
  code: string
  name: string
  /** 可选说明（领域组的覆盖范围等），枚举组为空 */
  desc?: string | null
}

type GroupMap = Record<string, ConstantItemView[]>

export const useConstantsStore = defineStore('constants', {
  state: () => ({
    groups: {} as GroupMap,
    loaded: false
  }),
  actions: {
    async ensure() {
      if (this.loaded) {
        return
      }
      const res = await request.get<GroupMap>('/constants')
      this.groups = res.data
      this.loaded = true
    },
    /** 强制重拉（领域等配置化常量组在系统设置页保存后调用，保持下拉/展示即时更新） */
    async reload() {
      const res = await request.get<GroupMap>('/constants')
      this.groups = res.data
      this.loaded = true
    },
    items(group: string): ConstantItemView[] {
      return this.groups[group] ?? []
    },
    name(group: string, code: string): string {
      const hit = this.items(group).find((item) => item.code === code)
      return hit ? hit.name : code
    }
  }
})
