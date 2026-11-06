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
    return http.post<ApiEnvelope<boolean>>('/auth/password/forgot', { email })
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
