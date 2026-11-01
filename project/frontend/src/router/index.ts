import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { routes } from './routes'
import './types'

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }
  const auth = useAuthStore()
  if (!auth.user) {
    await auth.bootstrap()
  }
  if (!auth.user) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router