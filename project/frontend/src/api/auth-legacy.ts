import { http, getCsrfToken, type ApiEnvelope, type TokenPayload, type UserPayload } from './http'

export interface RegisterBody {
  email: string
  password: string
  nickname: string
}

export interface LoginBody {
  email: string
  password: string
}

export interface ResetPasswordBody {
  email: string
  code: string
  newPassword: string
}

export const authApi = {
  register(body: RegisterBody) {
    return http.post<ApiEnvelope<UserPayload>>('/auth/register', body)
  },
  login(body: LoginBody) {
    return http.post<ApiEnvelope<TokenPayload>>('/auth/login', body)
  },
  csrf() {
    return http.get<ApiEnvelope<{ csrfToken: string }>>('/auth/csrf')
  },
  logout() {
    return http.post<ApiEnvelope<boolean>>('/auth/logout', {}, {
      headers: { 'X-CSRF-Token': getCsrfToken() ?? '' }
    })
  },
  forgotPassword(email: string) {
    // 邮件为同步发送：SMTP 慢时可能超过全局 15 秒超时，此处单独放宽到 45 秒，
    // 避免把"发送慢"误报为"后端未启动"并诱导重复发送（后端另有 60 秒发送冷却兜底）
    return http.post<ApiEnvelope<boolean>>('/auth/password/forgot', { email }, { timeout: 45000 })
  },
  resetPassword(body: ResetPasswordBody) {
    return http.post<ApiEnvelope<boolean>>('/auth/password/reset', body)
  },
  me() {
    return http.get<ApiEnvelope<UserPayload>>('/users/me')
  },
  updateNickname(nickname: string) {
    return http.patch<ApiEnvelope<UserPayload>>('/users/me', { nickname })
  }
}
