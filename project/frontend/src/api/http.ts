import axios, { type AxiosError, type AxiosRequestConfig } from 'axios'

export interface ApiErrorPayload {
  key: string
  retryable: boolean
  details?: Record<string, unknown>
}

export interface ApiEnvelope<T> {
  code: number
  msg: string
  data: T
  traceId: string
  error?: ApiErrorPayload
}

export interface UserPayload {
  id: string
  email: string
  nickname: string
  role: string
  createdAt: string
}

export interface TokenPayload {
  accessToken: string
  expiresIn: number
  user: UserPayload
}

interface RetriableConfig extends AxiosRequestConfig {
  _retry?: boolean
}

const baseURL = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'

export const http = axios.create({
  baseURL,
  withCredentials: true,
  timeout: 15000
})

let accessToken: string | null = null
let csrfToken: string | null = null
let unauthorizedHandler: (() => void) | null = null
let refreshing: Promise<boolean> | null = null

export function setAccessToken(token: string | null): void {
  accessToken = token
}

export function setCsrfToken(token: string | null): void {
  csrfToken = token
}

export function getAccessToken(): string | null {
  return accessToken
}

export function getCsrfToken(): string | null {
  return csrfToken
}

export function setUnauthorizedHandler(handler: () => void): void {
  unauthorizedHandler = handler
}

export async function refreshSession(): Promise<boolean> {
  if (!refreshing) {
    refreshing = axios
      .post<ApiEnvelope<TokenPayload>>(`${baseURL}/auth/refresh`, {}, {
        withCredentials: true,
        headers: csrfToken ? { 'X-CSRF-Token': csrfToken } : {}
      })
      .then((response) => {
        const payload = response.data.data
        accessToken = payload.accessToken
        const headerToken = response.headers['x-csrf-token']
        if (typeof headerToken === 'string') {
          csrfToken = headerToken
        }
        return true
      })
      .catch(() => false)
      .finally(() => {
        refreshing = null
      })
  }
  return refreshing
}

http.interceptors.request.use((config) => {
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<ApiEnvelope<unknown>>) => {
    const original = error.config as RetriableConfig | undefined
    const status = error.response?.status
    const url = String(original?.url ?? '')
    if (status === 401 && original && !original._retry && !url.includes('/auth/')) {
      original._retry = true
      const refreshed = await refreshSession()
      if (refreshed && accessToken) {
        original.headers = { ...(original.headers ?? {}), Authorization: `Bearer ${accessToken}` }
        return http.request(original)
      }
      unauthorizedHandler?.()
    }
    return Promise.reject(error)
  }
)

export function errorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const payload = (error.response?.data as ApiEnvelope<unknown> | undefined)?.msg
    if (payload) {
      return payload
    }
    // 超时与"后端未启动"必须区分：超时时服务端可能仍在处理（如同步发邮件），
    // 误报为服务未启动会诱导用户立即重复操作
    if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
      return '请求超时，服务可能仍在处理中，请稍候查看结果，请勿立即重复操作'
    }
    if (!error.response) {
      return '网络连接中断，请检查网络后重试；刚提交的操作可能仍在处理中，请先查看结果'
    }
  }
  return '操作失败，请稍后重试'
}
