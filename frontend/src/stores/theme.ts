import { defineStore } from 'pinia'

export type ThemeMode = 'light' | 'dark'

const STORAGE_KEY = 'zj-theme'

function initialMode(): ThemeMode {
  const saved = localStorage.getItem(STORAGE_KEY)
  if (saved === 'light' || saved === 'dark') {
    return saved
  }
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

/**
 * 主题 store：亮/暗切换，写 html.dark 类（Element Plus 深色变量挂在此类下）。
 * 偏好落 localStorage，index.html 内联脚本在首屏前抢先套用，避免闪烁。
 */
export const useThemeStore = defineStore('theme', {
  state: () => ({
    mode: initialMode() as ThemeMode
  }),
  getters: {
    isDark: (state) => state.mode === 'dark'
  },
  actions: {
    apply() {
      document.documentElement.classList.toggle('dark', this.mode === 'dark')
    },
    set(mode: ThemeMode) {
      this.mode = mode
      localStorage.setItem(STORAGE_KEY, mode)
      this.apply()
    },
    toggle() {
      this.set(this.mode === 'dark' ? 'light' : 'dark')
    }
  }
})
