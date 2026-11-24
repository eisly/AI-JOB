import { ApiError, request, requestBlob } from './request'
import type {
  AccountCapabilitiesVO,
  AccountProfileVO,
  BindPhoneInput,
  ChangePasswordInput,
  ChangePasswordResponse,
  CsrfVO,
  RebindPhoneInput,
  SendPhoneCodeInput,
  SendPhoneCodeResponse,
  UnbindPhoneInput,
} from './types'

let csrfToken = ''

async function ensureCsrf(force = false): Promise<string> {
  if (!csrfToken || force) {
    const result = await request<CsrfVO>('/api/v1/account/csrf')
    csrfToken = result.csrfToken
  }
  return csrfToken
}

interface WriteOptions {
  method: string
  body?: unknown
}

async function write<T>(path: string, options: WriteOptions): Promise<T> {
  const token = await ensureCsrf()
  try {
    return await request<T>(path, {
      method: options.method,
      body: options.body,
      headers: { 'X-CSRF-Token': token },
    })
  } catch (error) {
    if (error instanceof ApiError && error.key === 'CSRF_INVALID') {
      const fresh = await ensureCsrf(true)
      return request<T>(path, {
        method: options.method,
        body: options.body,
        headers: { 'X-CSRF-Token': fresh },
      })
    }
    throw error
  }
}

export const accountApi = {
  getProfile: () => request<AccountProfileVO>('/api/v1/account/profile'),

  getCapabilities: () => request<AccountCapabilitiesVO>('/api/v1/account/capabilities'),

  getCsrf: async (): Promise<CsrfVO> => ({ csrfToken: await ensureCsrf(true) }),

  updateNickname: (nickname: string) =>
    write<AccountProfileVO>('/api/v1/account/nickname', { method: 'PATCH', body: { nickname } }),

  changePassword: (input: ChangePasswordInput) =>
    write<ChangePasswordResponse>('/api/v1/account/password/change', { method: 'POST', body: input }),

  sendPhoneCode: (input: SendPhoneCodeInput) =>
    write<SendPhoneCodeResponse>('/api/v1/account/phone/code', { method: 'POST', body: input }),

  bindPhone: (input: BindPhoneInput) =>
    write<AccountProfileVO>('/api/v1/account/phone/bind', { method: 'POST', body: input }),

  rebindPhone: (input: RebindPhoneInput) =>
    write<AccountProfileVO>('/api/v1/account/phone/rebind', { method: 'POST', body: input }),

  unbindPhone: (input: UnbindPhoneInput) =>
    write<AccountProfileVO>('/api/v1/account/phone/unbind', { method: 'POST', body: input }),

  uploadAvatar: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return write<AccountProfileVO>('/api/v1/account/avatar', { method: 'POST', body: form })
  },

  resetAvatar: () => write<AccountProfileVO>('/api/v1/account/avatar', { method: 'DELETE' }),

  fetchAvatar: (version: number) =>
    requestBlob('/api/v1/account/avatars/me', { query: { v: version } }),
}