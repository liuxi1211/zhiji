import { createApp } from 'vue'
import { createPinia } from 'pinia'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'

import App from './App.vue'
import router from './router'
import { useConstantsStore } from './stores/constants'
import { useThemeStore } from './stores/theme'
import './styles/main.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
// 中文 locale：分页器/消息框等内置文案与界面语言一致（默认 locale 为英文）
app.use(ElementPlus, { locale: zhCn })

// 主题类必须在挂载前套用，与 index.html 内联脚本保持同一来源
useThemeStore().apply()

// 常量组启动拉取一次（公开接口），失败不阻塞应用（进入需要常量的页面时仍会重试）
void useConstantsStore().ensure().catch(() => {})

app.mount('#app')
