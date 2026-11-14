import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import { clearSessionToken, setCsrfToken, setSessionToken } from '@/api/session'
import type { UserVO } from '@/api/types'

export const useUserStore = defineStore('user', () => {
  const userInfo = ref<UserVO | null>(null)
  const accessToken = ref('')
  const isAuthenticated = computed(() => Boolean(accessToken.value))
  let csrfPromise: Promise<void> | null = null
  let restorePromise: Promise<boolean> | null = null

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

  function restoreCsrf() {
    if (!csrfPromise) {
      csrfPromise = authApi.csrf()
        .then(result => { setCsrfToken(result.csrfToken) })
        .finally(() => { csrfPromise = null })
    }
    return csrfPromise
  }

  function restoreSession(): Promise<boolean> {
    if (isAuthenticated.value) return Promise.resolve(true)
    if (!restorePromise) {
      restorePromise = (async () => {
        try {
          await restoreCsrf()
          const result = await authApi.refresh()
          applyToken(result.accessToken, result.user)
          return true
        } catch {
          if (!isAuthenticated.value) {
            userInfo.value = null
            clearSessionToken()
          }
          return isAuthenticated.value
        }
      })().finally(() => { restorePromise = null })
    }
    return restorePromise
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

  return { userInfo, accessToken, isAuthenticated, login, logout, fetchMe, restoreCsrf, restoreSession }
})
