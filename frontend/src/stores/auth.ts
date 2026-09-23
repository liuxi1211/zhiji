import { defineStore } from 'pinia'
import type { StaffRole } from '@/api/types'

const STAFF_TOKEN_KEY = 'zj_staff_token'
const STAFF_ROLE_KEY = 'zj_staff_role'
const STAFF_NAME_KEY = 'zj_staff_username'
const CUSTOMER_TOKEN_KEY = 'zj_customer_token'
const CUSTOMER_NAME_KEY = 'zj_customer_username'

/**
 * 双会话状态：员工（sys_staff）与客户（sys_customer）token 分开存储，
 * request.ts 按 URL 前缀选择携带哪一个（双表双入口，决策清单 D18）。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    staffToken: localStorage.getItem(STAFF_TOKEN_KEY) ?? '',
    staffRole: (localStorage.getItem(STAFF_ROLE_KEY) ?? '') as '' | StaffRole,
    staffUsername: localStorage.getItem(STAFF_NAME_KEY) ?? '',
    customerToken: localStorage.getItem(CUSTOMER_TOKEN_KEY) ?? '',
    customerUsername: localStorage.getItem(CUSTOMER_NAME_KEY) ?? ''
  }),
  getters: {
    isStaffLoggedIn: (state) => state.staffToken !== '',
    isCustomerLoggedIn: (state) => state.customerToken !== ''
  },
  actions: {
    setStaff(token: string, username: string, role: StaffRole) {
      this.staffToken = token
      this.staffUsername = username
      this.staffRole = role
      localStorage.setItem(STAFF_TOKEN_KEY, token)
      localStorage.setItem(STAFF_ROLE_KEY, role)
      localStorage.setItem(STAFF_NAME_KEY, username)
    },
    clearStaff() {
      this.staffToken = ''
      this.staffRole = ''
      this.staffUsername = ''
      localStorage.removeItem(STAFF_TOKEN_KEY)
      localStorage.removeItem(STAFF_ROLE_KEY)
      localStorage.removeItem(STAFF_NAME_KEY)
    },
    setCustomer(token: string, username: string) {
      this.customerToken = token
      this.customerUsername = username
      localStorage.setItem(CUSTOMER_TOKEN_KEY, token)
      localStorage.setItem(CUSTOMER_NAME_KEY, username)
    },
    clearCustomer() {
      this.customerToken = ''
      this.customerUsername = ''
      localStorage.removeItem(CUSTOMER_TOKEN_KEY)
      localStorage.removeItem(CUSTOMER_NAME_KEY)
    }
  }
})
