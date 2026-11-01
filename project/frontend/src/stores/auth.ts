import { defineStore } from 'pinia'
import { ref } from 'vue'
import { authApi } from '@/api/auth'
import {
  refreshSession,
  setAccessToken,
  setCsrfToken,
  type TokenPayload,
  type UserPayload
} from '@/api/http'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserPayload | null>(null)
  const ready = ref(false)

  function applyTokenPayload(payload: TokenPayload, csrfToken: string | null): void {
    setAccessToken(payload.accessToken)
    if (csrfToken) {
      setCsrfToken(csrfToken)
    }
    user.value = payload.user
  }

  async function login(email: string, password: string): Promise<void> {
    const response = await authApi.login({ email, password })
    const csrfHeader = response.headers['x-csrf-token']
    applyTokenPayload(response.data.data, typeof csrfHeader === 'string' ? csrfHeader : null)
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      clear()
    }
  }

  async function bootstrap(): Promise<void> {
    if (ready.value) {
      return
    }
    ready.value = true
    try {
      const csrfResponse = await authApi.csrf()
      setCsrfToken(csrfResponse.data.data.csrfToken)
      const refreshed = await refreshSession()
      if (refreshed) {
        const meResponse = await authApi.me()
        user.value = meResponse.data.data
      }
    } catch {
      user.value = null
    }
  }

  function clear(): void {
    user.value = null
    setAccessToken(null)
    setCsrfToken(null)
    ready.value = false
  }

  return { user, ready, login, logout, bootstrap, clear }
})