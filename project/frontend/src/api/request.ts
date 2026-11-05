// 轻量请求封装：使用 fetch，避免当前项目额外安装 axios。
// 认证未实现前，统一注入演示用户 X-User-Id；认证落地后改为 Bearer accessToken。

export interface ApiErrorBody {
  key?: string
  retryable?: boolean
  details?: Record<string, unknown>
}

export class ApiError extends Error {
  readonly code: number
  readonly error?: ApiErrorBody

  constructor(message: string, code: number, error?: ApiErrorBody) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.error = error
  }
}

export const DEMO_USER_ID = '11111111-1111-4111-8111-111111111111'

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  headers.set('Content-Type', 'application/json')
  headers.set('X-User-Id', DEMO_USER_ID)

  const response = await fetch(`/api${path}`, {
    ...options,
    headers,
  })

  const text = await response.text()
  let body: any = null
  if (text) {
    try {
      body = JSON.parse(text)
    } catch {
      throw new ApiError(`请求失败（HTTP ${response.status}）`, response.status)
    }
  }

  if (!response.ok || body?.code !== 200) {
    throw new ApiError(
      body?.msg ?? `请求失败（HTTP ${response.status}）`,
      body?.code ?? response.status,
      body?.error,
    )
  }

  return body.data as T
}