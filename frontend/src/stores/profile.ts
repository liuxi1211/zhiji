import { defineStore } from 'pinia'
import { activateProfile, listProfiles } from '@/api/profile'
import type { ProfileListItem } from '@/api/types'

const ACTIVE_KEY = 'zj_active_profile'

/**
 * 当前档案状态：档案切换即切换全部上下文（核心模块定义 4.1）。
 * 学习路径 / 随机复习 / 模拟考等后续模块统一从这里取当前档案，
 * 后端以部分唯一索引保证每客户至多一个 active 档案。
 */
export const useProfileStore = defineStore('profile', {
  state: () => ({
    items: [] as ProfileListItem[],
    activeId: Number(localStorage.getItem(ACTIVE_KEY) ?? 0) || null as number | null
  }),
  getters: {
    active: (state) => state.items.find((item) => item.active) ?? null
  },
  actions: {
    async refresh() {
      this.items = await listProfiles()
      const activeItem = this.items.find((item) => item.active)
      if (activeItem) {
        this.activeId = activeItem.id
        localStorage.setItem(ACTIVE_KEY, String(activeItem.id))
      } else if (this.items.length > 0) {
        // 数据异常兜底：后端无激活档案时补激活第一份
        await this.setActive(this.items[0].id)
      } else {
        this.activeId = null
        localStorage.removeItem(ACTIVE_KEY)
      }
    },
    async setActive(id: number) {
      await activateProfile(id)
      this.activeId = id
      localStorage.setItem(ACTIVE_KEY, String(id))
      await this.refresh()
    }
  }
})
