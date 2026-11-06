let accessToken = ''
let csrfToken = ''

export function getAccessToken() {
  return accessToken
}

export function getCsrfToken() {
  return csrfToken
}

export function setSessionToken(token: string, csrf = csrfToken) {
  accessToken = token
  csrfToken = csrf
}

export function setCsrfToken(token: string) {
  csrfToken = token
}

export function clearSessionToken() {
  accessToken = ''
  csrfToken = ''
}

export function getDevUserId() {
  return import.meta.env.DEV ? import.meta.env.VITE_DEV_USER_ID?.trim() || '' : ''
}

export function createAuthHeaders(includeLastEventId?: number) {
  const headers: Record<string, string> = {}
  const token = getAccessToken()
  const devUserId = getDevUserId()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  } else if (devUserId) {
    headers['X-User-Id'] = devUserId
  }
  if (includeLastEventId !== undefined) {
    headers['Last-Event-ID'] = String(includeLastEventId)
  }
  return headers
}