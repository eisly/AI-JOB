import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './api/http'
import './styles/index.scss'
import './styles/main.scss'

const app = createApp(App)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component as any)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

setUnauthorizedHandler(() => {
  const current = router.currentRoute.value
  if (current.path !== '/login') {
    // L02：会话中途失效跳转登录时保留原地址并标记过期，登录页显示"登录已过期"提示
    router.push({ name: 'login', query: { redirect: current.fullPath, expired: '1' } })
  }
})

app.mount('#app')
