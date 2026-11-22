import type { CommonResult } from './types'
import { createAuthHeaders, getAccessToken, getCsrfToken, setCsrfToken, setSessionToken } from './session'

export class ApiError extends Error {
  status: number
  code: number | string
  key: string
  traceId: string
  retryable: boolean
  details?: Record<string, unknown>

  get httpStatus() { return this.status }
  get isVersionConflict() { return this.status === 409 && this.key === 'VERSION_CONFLICT' }

  constructor(message: string, options: { status?: number; code?: number | string; key?: string; traceId?: string; retryable?: boolean; details?: Record<string, unknown> } = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = options.status ?? 0
    this.code = options.code ?? 'NETWORK_ERROR'
    this.key = options.key ?? 'NETWORK_ERROR'
    this.traceId = options.traceId ?? ''
    this.retryable = options.retryable ?? true
    this.details = options.details
  }
}

export interface RequestOptions extends Omit<RequestInit, 'body'> {
  body?: unknown
  query?: object
  auth?: boolean
  idempotency?: boolean | string
  retryAuth?: boolean
}

export interface BinaryDownload {
  blob: Blob
  fileName: string
}

const apiOrigin = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
let refreshPromise: Promise<boolean> | null = null

export function newIdempotencyKey() {
  return crypto.randomUUID()
}

export function apiUrl(path: string, query?: RequestOptions['query']) {
  const url = new URL(`${apiOrigin}${path}`, window.location.origin)
    ; (Object.entries(query || {}) as [string, string | number | boolean | null | undefined][]).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        url.searchParams.set(key, String(value))
      }
    })
  return url.toString()
}

function responseFileName(response: Response, fallback: string) {
  const disposition = response.headers.get('content-disposition') || ''
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  const plain = disposition.match(/filename="?([^";]+)"?/i)?.[1]
  if (encoded) {
    try {
      return decodeURIComponent(encoded)
    } catch {
      return fallback
    }
  }
  return plain || fallback
}

async function parsePayload(response: Response) {
  const text = await response.text()
  if (!text) return null
  try {
    return JSON.parse(text) as CommonResult<unknown>
  } catch {
    return null
  }
}

async function toApiError(response: Response) {
  const payload = await parsePayload(response)
  return new ApiError(payload?.msg || `请求失败（HTTP ${response.status}）`, {
    status: response.status,
    code: payload?.code ?? response.status,
    key: payload?.error?.key || 'HTTP_ERROR',
    traceId: payload?.traceId || response.headers.get('x-trace-id') || '',
    retryable: payload?.error?.retryable ?? (response.status >= 500 || response.status === 429),
    details: payload?.error?.details,
  })
}

async function refreshAccessToken() {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      try {
        const response = await fetch(apiUrl('/api/v1/auth/refresh'), {
          method: 'POST',
          credentials: 'include',
          headers: {
            'Content-Type': 'application/json',
            ...(getCsrfToken() ? { 'X-CSRF-Token': getCsrfToken() } : {}),
          },
          body: '{}',
        })
        if (!response.ok) return false
        const payload = await parsePayload(response)
        if (!payload || (payload.code !== 200 && payload.code !== 202)) return false
        const data = payload.data as { accessToken: string }
        setSessionToken(data.accessToken, response.headers.get('x-csrf-token') || getCsrfToken())
        return true
      } catch {
        return false
      } finally {
        setTimeout(() => {
          refreshPromise = null
        }, 0)
      }
    })()
  }
  return refreshPromise
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { query, auth = true, idempotency = false, retryAuth = true, body, headers: inputHeaders, ...init } = options
  const headers = new Headers(inputHeaders)
  if (auth) {
    Object.entries(createAuthHeaders()).forEach(([key, value]) => headers.set(key, value))
  }
  if (getCsrfToken() && init.method && init.method !== 'GET') {
    headers.set('X-CSRF-Token', getCsrfToken())
  }
  if (idempotency) {
    headers.set('Idempotency-Key', typeof idempotency === 'string' ? idempotency : newIdempotencyKey())
  }
  let requestBody: BodyInit | undefined
  if (body instanceof FormData || body instanceof Blob || typeof body === 'string' || body instanceof URLSearchParams) {
    requestBody = body
  } else if (body !== undefined) {
    headers.set('Content-Type', 'application/json')
    requestBody = JSON.stringify(body)
  }
  let response: Response
  try {
    response = await fetch(apiUrl(path, query), {
      ...init,
      body: requestBody,
      headers,
      credentials: 'include',
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError('网络连接失败，请检查业务后端是否可用', { key: 'NETWORK_ERROR', retryable: true })
  }
  if (response.status === 401 && auth && retryAuth && getAccessToken()) {
    const refreshed = await refreshAccessToken()
    if (refreshed) return request<T>(path, { ...options, retryAuth: false })
  }
  if (!response.ok) throw await toApiError(response)
  const payload = await parsePayload(response)
  if (!payload) {
    if (response.status === 204) return true as T
    throw new ApiError('业务后端返回了无法解析的响应', { status: response.status, key: 'INVALID_RESPONSE', retryable: false })
  }
  const responseCsrf = response.headers.get('x-csrf-token')
  if (responseCsrf) setCsrfToken(responseCsrf)
  if (payload.code !== 200 && payload.code !== 202) {
    throw new ApiError(payload.msg || '业务处理失败', {
      status: response.status,
      code: payload.code,
      key: payload.error?.key || 'BUSINESS_ERROR',
      traceId: payload.traceId,
      retryable: payload.error?.retryable ?? false,
      details: payload.error?.details,
    })
  }
  return payload.data as T
}

export async function requestBlob(path: string, options: RequestOptions = {}): Promise<Blob> {
  const { query, auth = true, headers: inputHeaders, body, idempotency = false, retryAuth = true, ...init } = options
  const headers = new Headers(inputHeaders)
  if (auth) Object.entries(createAuthHeaders()).forEach(([key, value]) => headers.set(key, value))
  if (idempotency) {
    headers.set('Idempotency-Key', typeof idempotency === 'string' ? idempotency : newIdempotencyKey())
  }
  if (getCsrfToken() && init.method && init.method !== 'GET') {
    headers.set('X-CSRF-Token', getCsrfToken())
  }
  let requestBody: BodyInit | undefined
  if (body instanceof FormData || body instanceof Blob || typeof body === 'string' || body instanceof URLSearchParams) {
    requestBody = body
  } else if (body !== undefined) {
    headers.set('Content-Type', 'application/json')
    requestBody = JSON.stringify(body)
  }
  let response: Response
  try {
    response = await fetch(apiUrl(path, query), { ...init, body: requestBody, headers, credentials: 'include' })
  } catch {
    throw new ApiError('音频获取失败，请检查网络后重试', { key: 'AUDIO_NETWORK_ERROR', retryable: true })
  }
  if (response.status === 401 && auth && retryAuth && getAccessToken()) {
    const refreshed = await refreshAccessToken()
    if (refreshed) return requestBlob(path, { ...options, retryAuth: false })
  }
  if (!response.ok) throw await toApiError(response)
  const contentType = response.headers.get('content-type') || ''
  if (contentType.includes('application/json')) throw await toApiError(response)
  const blob = await response.blob()
  if (!blob.size) throw new ApiError('题目语音为空，请改用文字阅读', { key: 'EMPTY_AUDIO', retryable: true })
  return blob
}

export async function downloadBinary(path: string, options: RequestOptions & { fallbackName?: string } = {}): Promise<BinaryDownload> {
  const { query, auth = true, fallbackName = 'download', headers: inputHeaders, body: _body, idempotency: _idempotency, retryAuth = true, ...init } = options
  const headers = new Headers(inputHeaders)
  if (auth) Object.entries(createAuthHeaders()).forEach(([key, value]) => headers.set(key, value))
  let response: Response
  try {
    response = await fetch(path.startsWith('http') ? path : apiUrl(path, query), {
      ...init,
      headers,
      credentials: 'include',
    })
  } catch {
    throw new ApiError('下载连接失败，请稍后重试', { key: 'DOWNLOAD_NETWORK_ERROR', retryable: true })
  }
  if (response.status === 401 && auth && retryAuth && getAccessToken()) {
    const refreshed = await refreshAccessToken()
    if (refreshed) return downloadBinary(path, { ...options, retryAuth: false })
  }
  if (!response.ok) throw await toApiError(response)
  const contentType = response.headers.get('content-type') || ''
  if (contentType.includes('application/json')) throw await toApiError(response)
  const blob = await response.blob()
  if (!blob.size) throw new ApiError('后端返回了空文件，未执行下载', { key: 'EMPTY_DOWNLOAD', retryable: true })
  return { blob, fileName: responseFileName(response, fallbackName) }
}

export function saveBinary({ blob, fileName }: BinaryDownload) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fileName
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}