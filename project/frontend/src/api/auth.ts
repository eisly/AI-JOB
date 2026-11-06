import { request } from './request'
import { getAccessToken, getDevUserId } from './session'
import type { TokenVO, UserVO } from './types'

export const authApi = {
  login(body: { email: string; password: string }) {
    return request<TokenVO>('/api/v1/auth/login', { method: 'POST', body, auth: false })
  },
  register(body: { email: string; password: string; nickname: string }) {
    return request<UserVO>('/api/v1/auth/register', { method: 'POST', body, auth: false })
  },
  csrf() {
    return request<{ csrfToken: string }>('/api/v1/auth/csrf', { auth: false })
  },
  logout() {
    return request<boolean>('/api/v1/auth/logout', { method: 'POST', body: {} })
  },
  me() {
    const devUserId = getDevUserId()
    if (!getAccessToken() && devUserId) return request<UserVO>(`/api/v1/users/${devUserId}`)
    return request<UserVO>('/api/v1/users/me')
  },
}