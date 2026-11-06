import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import { clearSessionToken, getDevUserId, setCsrfToken, setSessionToken } from '@/api/session'
import type { UserVO } from '@/api/types'

export const useUserStore = defineStore('user', () => {
  const userInfo = ref<UserVO | null>(null)
  const accessToken = ref('')
  const restoring = ref(false)
  const isAuthenticated = computed(() => Boolean(accessToken.value || getDevUserId()))

  function applyToken(token: string, user: UserVO) {
    accessToken.value = token
    userInfo.value = user
    setSessionToken(token)
  }

  async function login(email: string, password: string) {
    const result = await authApi.login({ email, password })
    applyToken(result.accessToken, result.user)
    return result.user
  }

  async function fetchMe() {
    userInfo.value = await authApi.me()
    return userInfo.value
  }

  async function restoreCsrf() {
    if (restoring.value) return
    restoring.value = true
    try {
      const result = await authApi.csrf()
      setCsrfToken(result.csrfToken)
    } finally {
      restoring.value = false
    }
  }

  async function logout() {
    try {
      if (accessToken.value) await authApi.logout()
    } finally {
      accessToken.value = ''
      userInfo.value = null
      clearSessionToken()
    }
  }

  return { userInfo, accessToken, isAuthenticated, login, logout, fetchMe, restoreCsrf }
})