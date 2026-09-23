import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import { useConstantsStore } from './stores/constants'
import './styles/main.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus)

// 常量组启动拉取一次（公开接口），失败不阻塞应用（进入需要常量的页面时仍会重试）
void useConstantsStore().ensure().catch(() => {})

app.mount('#app')
