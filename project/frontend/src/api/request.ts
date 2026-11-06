import type { ApiError, ApiErrorCategory, ApiResult, CommonResult } from '@/types/workbench'

export type QueryValue = string | number | boolean | null | undefined

export type QueryParams = Record<string, QueryValue>

export interface RequestOptions {
  timeoutMs?: number
  signal?: AbortSignal
}

const DEFAULT_TIMEOUT_MS = 1500

export class ApiRequestError extends Error {
  readonly category: ApiErrorCategory
  readonly httpStatus?: number
  readonly businessCode?: number
  readonly traceId?: string
  readonly requestId?: string

  constructor(error: ApiError, message?: string) {
    super(message ?? error.category)
    this.name = 'ApiRequestError'
    this.category = error.category
    this.httpStatus = error.httpStatus
    this.businessCode = error.businessCode
    this.traceId = error.traceId
    this.requestId = error.requestId
  }

  toApiError(): ApiError {
    return {
      category: this.category,
      httpStatus: this.httpStatus,
      businessCode: this.businessCode,
      traceId: this.traceId,
      requestId: this.requestId,
    }
  }
}

export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiRequestError) {
    return error.toApiError()
  }
  return { category: 'UNKNOWN' }
}

function buildQueryString(query?: QueryParams): string {
  if (!query) {
    return ''
  }
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null) {
      continue
    }
    params.append(key, String(value))
  }
  const text = params.toString()
  return text ? `?${text}` : ''
}

async function readBody(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    return undefined
  }
}

function pickTraceId(body: unknown): string | undefined {
  if (body && typeof body === 'object' && 'traceId' in body) {
    const traceId = (body as { traceId?: unknown }).traceId
    return typeof traceId === 'string' && traceId ? traceId : undefined
  }
  return undefined
}

function statusToCategory(status: number): ApiErrorCategory {
  if (status === 401) {
    return 'AUTH_EXPIRED'
  }
  if (status === 429) {
    return 'RATE_LIMIT'
  }
  if (status === 404) {
    return 'NOT_FOUND'
  }
  if (status >= 500) {
    return 'SERVER'
  }
  return 'UNKNOWN'
}

function isCommonResult(body: unknown): body is CommonResult<unknown> {
  return !!body && typeof body === 'object' && 'code' in body
}

export async function apiGet<T>(path: string, query?: QueryParams, options?: RequestOptions): Promise<ApiResult<T>> {
  const timeoutMs = options?.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const externalSignal = options?.signal
  const controller = new AbortController()
  let timedOut = false

  const abortByExternal = (): void => controller.abort()
  if (externalSignal) {
    if (externalSignal.aborted) {
      controller.abort()
    } else {
      externalSignal.addEventListener('abort', abortByExternal, { once: true })
    }
  }

  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)

  const headers: Record<string, string> = { Accept: 'application/json' }
  const token = typeof localStorage !== 'undefined' ? localStorage.getItem('token') : null
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  let response: Response
  try {
    response = await fetch(`${path}${buildQueryString(query)}`, {
      method: 'GET',
      headers,
      signal: controller.signal,
    })
  } catch (error) {
    if (timedOut) {
      throw new ApiRequestError({ category: 'TIMEOUT' }, '请求超时')
    }
    if (externalSignal?.aborted) {
      throw new ApiRequestError({ category: 'CANCELLED' }, '请求已取消')
    }
    throw new ApiRequestError({ category: 'NETWORK' }, '网络连接异常')
  } finally {
    clearTimeout(timer)
    if (externalSignal) {
      externalSignal.removeEventListener('abort', abortByExternal)
    }
  }

  const body = await readBody(response)
  const traceId = pickTraceId(body)

  if (!response.ok) {
    throw new ApiRequestError({
      category: statusToCategory(response.status),
      httpStatus: response.status,
      traceId,
    })
  }

  if (isCommonResult(body)) {
    const code = Number(body.code)
    if (code !== 200 && code !== 202) {
      throw new ApiRequestError({
        category: code === 401 ? 'AUTH_EXPIRED' : 'UNKNOWN',
        httpStatus: response.status,
        businessCode: code,
        traceId: body.traceId,
      })
    }
    return { data: body.data as T, traceId: body.traceId }
  }

  return { data: body as T, traceId }
}

export default apiGet