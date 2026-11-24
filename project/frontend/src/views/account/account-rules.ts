export const NICKNAME_MAX_LENGTH = 30
export const PASSWORD_MIN_LENGTH = 10
export const PASSWORD_MAX_LENGTH = 72
export const AVATAR_MAX_BYTES = 2 * 1024 * 1024
export const AVATAR_ACCEPT_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const

const CONTROL_CHARS = /[\u0000-\u001F\u007F]/
const PHONE_PATTERN = /^1[3-9]\d{9}$/
const CODE_PATTERN = /^\d{6}$/

export function normalizeNickname(raw: string | null | undefined): string {
  return (raw ?? '').trim()
}

export function nicknameLength(raw: string | null | undefined): number {
  return [...normalizeNickname(raw)].length
}

export function validateNickname(raw: string | null | undefined): string | null {
  const value = normalizeNickname(raw)
  if (!value) return '昵称不能为空'
  if ([...value].length > NICKNAME_MAX_LENGTH) return '昵称不能超过 30 个字符'
  if (CONTROL_CHARS.test(value)) return '昵称包含不支持的字符'
  return null
}

export function nicknameUnchanged(raw: string | null | undefined, current: string | null | undefined): boolean {
  return normalizeNickname(raw) === (current ?? '')
}

export function passwordLength(value: string | null | undefined): number {
  return [...(value ?? '')].length
}

export function passwordStrengthHint(value: string | null | undefined): string {
  const text = value ?? ''
  if (!text) return '建议包含字母和数字'
  const hasLetter = /[A-Za-z]/.test(text)
  const hasDigit = /\d/.test(text)
  return hasLetter && hasDigit ? '已包含字母和数字' : '建议包含字母和数字'
}

export function validatePassword(
  currentPassword: string | null | undefined,
  newPassword: string | null | undefined,
  confirmPassword: string | null | undefined,
): string | null {
  const length = passwordLength(newPassword)
  if (length < PASSWORD_MIN_LENGTH || length > PASSWORD_MAX_LENGTH) {
    return '密码长度需为 10-72 位'
  }
  if (!newPassword || newPassword !== confirmPassword) {
    return '两次输入的密码不一致'
  }
  if (currentPassword && currentPassword === newPassword) {
    return '新密码不能与当前密码相同'
  }
  return null
}

export function isValidPhone(phone: string | null | undefined): boolean {
  return PHONE_PATTERN.test(phone ?? '')
}

export function isValidCode(code: string | null | undefined): boolean {
  return CODE_PATTERN.test(code ?? '')
}

export function maskPhone(phone: string | null | undefined): string | null {
  if (!isValidPhone(phone)) return null
  return `${phone!.substring(0, 3)}****${phone!.substring(7)}`
}

export function avatarValidation(file: { size: number; type: string } | null | undefined): string | null {
  if (!file) return '请选择要上传的图片'
  if (file.size <= 0) return '图片内容为空，请更换图片'
  if (file.size > AVATAR_MAX_BYTES) return '图片大小不能超过 2 MB'
  if (!AVATAR_ACCEPT_TYPES.includes(file.type as (typeof AVATAR_ACCEPT_TYPES)[number])) {
    return '仅支持 JPG、PNG、WebP 格式'
  }
  return null
}

export function countdownText(seconds: number): string {
  return `${Math.max(0, Math.ceil(seconds))}s 后重新获取`
}