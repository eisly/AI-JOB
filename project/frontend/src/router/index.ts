import { createRouter, createWebHistory } from 'vue-router'
import { routes } from './routes'
import './types'

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((_to, _from, next) => {
  // TODO: 预留登录态判断钩子
  // const userStore = useUserStore()
  // if (!userStore.token && to.path !== '/login') {
  //   next('/login')
  //   return
  // }
  next()
})

export default router