import { createRouter, createWebHistory } from 'vue-router'
import { routes } from './routes'
import './types'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async to => {
  if (['login', 'register', 'forgot-password', 'reset-password'].includes(String(to.name))) return true
  const user = useUserStore()
  if (!user.isAuthenticated) await user.restoreSession()
  if (!user.isAuthenticated) return { name: 'login', query: { redirect: to.fullPath } }
  return true
})

router.afterEach(to => {
  document.title = to.meta.title ? `${to.meta.title} - AI-JOB` : 'AI-JOB'
})

export default router
