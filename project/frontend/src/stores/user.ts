import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import { accountApi } from '@/api/account'
import { clearSessionToken, setCsrfToken, setSessionToken } from '@/api/session'
import type { AccountProfileVO, UserVO } from '@/api/types'

export const useUserStore = defineStore('user', () => {
  const userInfo = ref<UserVO | null>(null)
  const accessToken = ref('')
  const isAuthenticated = computed(() => Boolean(accessToken.value))
  const profile = ref<AccountProfileVO | null>(null)
  const avatarObjectUrl = ref('')
  const avatarFailed = ref(false)
  const accountCsrfToken = ref('')
  let csrfPromise: Promise<void> | null = null
  let restorePromise: Promise<boolean> | null = null
  let avatarPromise: Promise<void> | null = null

  function applyToken(token: string, user: UserVO) {
    accessToken.value = token
    userInfo.value = user
    setSessionToken(token)
    void refreshAccountProfile()
  }

  async function login(email: string, password: string) {
    const result = await authApi.login({ email, password })
    applyToken(result.accessToken, result.user)
    return result.user
  }

  async function fetchMe() {
    userInfo.value = await authApi.me()
    void refreshAccountProfile()
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

  function revokeAvatarUrl() {
    if (avatarObjectUrl.value) {
      URL.revokeObjectURL(avatarObjectUrl.value)
      avatarObjectUrl.value = ''
    }
  }

  function applyProfile(next: AccountProfileVO) {
    profile.value = next
    if (userInfo.value) {
      userInfo.value.nickname = next.nickname
      userInfo.value.email = next.email
      userInfo.value.phoneMasked = next.phoneMasked
      userInfo.value.avatarUrl = next.avatarUrl
      userInfo.value.avatarVersion = next.avatarVersion
      userInfo.value.profileUpdatedAt = next.profileUpdatedAt
    }
  }

  function markAvatarFailed() {
    avatarFailed.value = true
  }

  function resetAvatar() {
    revokeAvatarUrl()
    avatarFailed.value = false
  }

  async function loadAvatar(version?: number): Promise<void> {
    const current = profile.value
    if (!current || !current.avatarUrl) {
      resetAvatar()
      return
    }
    if (avatarPromise) return avatarPromise
    avatarPromise = (async () => {
      try {
        const blob = await accountApi.fetchAvatar(version ?? current.avatarVersion)
        revokeAvatarUrl()
        avatarObjectUrl.value = URL.createObjectURL(blob)
        avatarFailed.value = false
      } catch {
        revokeAvatarUrl()
        avatarFailed.value = true
      } finally {
        avatarPromise = null
      }
    })()
    return avatarPromise
  }

  async function ensureAvatarLoaded() {
    if (!profile.value || !profile.value.avatarUrl) return
    if (avatarObjectUrl.value || avatarFailed.value) return
    await loadAvatar()
  }

  async function refreshAccountProfile() {
    try {
      const next = await accountApi.getProfile()
      applyProfile(next)
      if (next.avatarUrl) {
        await loadAvatar(next.avatarVersion)
      } else {
        resetAvatar()
      }
    } catch {
      // 账号资料加载失败不阻塞登录/恢复会话，账号页会给出可重试提示
    }
  }

  async function ensureAccountCsrf(): Promise<string> {
    if (!accountCsrfToken.value) {
      const result = await accountApi.getCsrf()
      accountCsrfToken.value = result.csrfToken
    }
    return accountCsrfToken.value
  }

  function clearAccountState() {
    revokeAvatarUrl()
    avatarFailed.value = false
    profile.value = null
    accountCsrfToken.value = ''
  }

  function clearSession() {
    accessToken.value = ''
    userInfo.value = null
    clearSessionToken()
    clearAccountState()
  }

  async function logout() {
    await authApi.logout()
    clearSession()
  }

  return {
    userInfo,
    accessToken,
    isAuthenticated,
    profile,
    avatarObjectUrl,
    avatarFailed,
    accountCsrfToken,
    login,
    logout,
    clearSession,
    fetchMe,
    restoreCsrf,
    restoreSession,
    applyProfile,
    loadAvatar,
    ensureAvatarLoaded,
    markAvatarFailed,
    resetAvatar,
    refreshAccountProfile,
    ensureAccountCsrf,
    clearAccountState,
  }
})
