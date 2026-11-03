import { useUserStore } from '@/stores/user'

/**
 * 统一请求封装（原生 fetch，零新增依赖；对外接口稳定，登录模块合入后可无痛替换内部实现）。
 *
 * - 统一解包 CommonResult：code === 200 返回 data，否则抛 ApiError（提示字段为 msg）
 * - Authorization 从 user store 内存令牌读取（setToken 为登录模块预留入口）
 * - 开发身份请求头（X-User-Id）仅在前端双条件同时满足时发送：
 *   ① import.meta.env.DEV === true（正式构建恒为 false，永不携带）
 *   ② VITE_DEV_USER_ID 显式配置非空（.env.local）
 *   后端另需 local profile + app.auth.dev-user-header-enabled=true 双开关，四层条件缺一不可
 */

/** 业务/HTTP 错误统一载体：msg 用于提示，errorKey/details 供分支处理，traceId 用于排障 */
export class ApiError extends Error {
  readonly httpStatus: number
  readonly code: number
  readonly errorKey: string | null
  readonly retryable: boolean | null
  readonly details: Record<string, unknown> | null
  readonly traceId: string | null

  constructor(init: {
    message: string
    httpStatus: number
    code: number
    errorKey?: string | null
    retryable?: boolean | null
    details?: Record<string, unknown> | null
    traceId?: string | null
  }) {
    super(init.message)
    this.name = 'ApiError'
    this.httpStatus = init.httpStatus
    this.code = init.code
    this.errorKey = init.errorKey ?? null
    this.retryable = init.retryable ?? null
    this.details = init.details ?? null
    this.traceId = init.traceId ?? null
  }

  /** 409 VERSION_CONFLICT：details.currentVersion 仅为冲突提示，不可用于直接重提交 */
  get isVersionConflict(): boolean {
    return this.httpStatus === 409 && this.errorKey === 'VERSION_CONFLICT'
  }

  get isUnauthenticated(): boolean {
    return this.httpStatus === 401
  }
}

interface RequestConfig {
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE'
  /** 请求体（POST/PATCH JSON） */
  body?: unknown
  /** query 参数（undefined/null 值的键完全不发送） */
  query?: Record<string, string | number | undefined | null>
  /** 超时毫秒 */
  timeoutMs?: number
}

/** 开发身份请求头的发送条件（前端双条件，正式构建恒不发送） */
function devUserId(): string | null {
  if (import.meta.env.DEV !== true) return null
  const id = import.meta.env.VITE_DEV_USER_ID
  return typeof id === 'string' && id.trim() !== '' ? id.trim() : null
}

function buildUrl(path: string, query?: RequestConfig['query']): string {
  const url = new URL(path, 'http://placeholder.local')
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      // 值为 undefined/null 时整个参数省略（status 筛选“查全部”依赖此行为）
      if (value !== undefined && value !== null) {
        url.searchParams.set(key, String(value))
      }
    }
  }
  const search = url.search
  return path + (search.startsWith('?') ? search : '')
}

async function parseErrorBody(response: Response): Promise<{
  code: number
  msg: string
  errorKey: string | null
  retryable: boolean | null
  details: Record<string, unknown> | null
  traceId: string | null
}> {
  try {
    const raw = await response.json()
    const error = (raw && typeof raw === 'object' && 'error' in raw
      ? (raw as { error?: { key?: string; retryable?: boolean; details?: Record<string, unknown> } }).error
      : undefined) ?? undefined
    return {
      code: typeof raw?.code === 'number' ? raw.code : response.status,
      msg: typeof raw?.msg === 'string' && raw.msg !== '' ? raw.msg : `请求失败（HTTP ${response.status}）`,
      errorKey: typeof error?.key === 'string' ? error.key : null,
      retryable: typeof error?.retryable === 'boolean' ? error.retryable : null,
      details: (error?.details && typeof error.details === 'object'
        ? (error.details as Record<string, unknown>)
        : null),
      traceId: typeof raw?.traceId === 'string' ? raw.traceId : null,
    }
  } catch {
    return {
      code: response.status,
      msg: `请求失败（HTTP ${response.status}）`,
      errorKey: null,
      retryable: null,
      details: null,
      traceId: null,
    }
  }
}

/** 核心请求函数：解包 CommonResult，非 200 抛 ApiError */
export async function request<T>(path: string, config: RequestConfig = {}): Promise<T> {
  const method = config.method ?? 'GET'
  const headers: Record<string, string> = { Accept: 'application/json' }

  const userStore = useUserStore()
  if (userStore.hasToken) {
    headers.Authorization = `Bearer ${userStore.token}`
  } else {
    const devId = devUserId()
    if (devId) {
      headers['X-User-Id'] = devId
    }
  }

  let bodyText: string | undefined
  if (config.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    bodyText = JSON.stringify(config.body)
  }

  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), config.timeoutMs ?? 15000)

  let response: Response
  try {
    response = await fetch(buildUrl(path, config.query), {
      method,
      headers,
      body: bodyText,
      signal: controller.signal,
    })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw new ApiError({
        message: '请求超时，请稍后重试',
        httpStatus: 0,
        code: 0,
      })
    }
    throw new ApiError({
      message: '网络异常，请检查连接后重试',
      httpStatus: 0,
      code: 0,
    })
  } finally {
    clearTimeout(timeout)
  }

  if (!response.ok) {
    const parsed = await parseErrorBody(response)
    throw new ApiError({
      message: parsed.msg,
      httpStatus: response.status,
      code: parsed.code,
      errorKey: parsed.errorKey,
      retryable: parsed.retryable,
      details: parsed.details,
      traceId: parsed.traceId,
    })
  }

  const raw = await response.json()
  if (raw && typeof raw === 'object' && 'code' in raw) {
    const result = raw as { code: number; msg: string; data: T; traceId?: string }
    if (result.code === 200) {
      return result.data
    }
    throw new ApiError({
      message: typeof result.msg === 'string' && result.msg !== '' ? result.msg : `请求失败（code ${result.code}）`,
      httpStatus: response.status,
      code: result.code,
      traceId: typeof result.traceId === 'string' ? result.traceId : null,
    })
  }
  throw new ApiError({
    message: '响应格式异常',
    httpStatus: response.status,
    code: response.status,
  })
}