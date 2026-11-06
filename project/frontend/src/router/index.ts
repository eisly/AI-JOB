import { createRouter, createWebHistory } from 'vue-router'
import { routes } from './routes'
import './types'

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.afterEach(to => {
  document.title = to.meta.title ? `${to.meta.title} - AI-JOB` : 'AI-JOB'
})

export default router