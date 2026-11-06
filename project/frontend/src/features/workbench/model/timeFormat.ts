function pad(value: number): string {
  return String(value).padStart(2, '0')
}

export function formatLocalDateTime(date: Date): string {
  return [
    date.getFullYear(),
    pad(date.getMonth() + 1),
    pad(date.getDate()),
  ].join('-') + ` ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function formatActivityTime(iso?: string, now: Date = new Date()): string {
  if (!iso) {
    return '时间未知'
  }
  const date = new Date(iso)
  const time = date.getTime()
  if (Number.isNaN(time)) {
    return '时间未知'
  }
  const diff = now.getTime() - time
  if (diff < 60_000) {
    return '刚刚'
  }
  if (diff < 3_600_000) {
    return `${Math.floor(diff / 60_000)} 分钟前`
  }
  if (diff < 86_400_000) {
    return `${Math.floor(diff / 3_600_000)} 小时前`
  }
  return formatLocalDateTime(date)
}

export function formatRefreshedAt(value?: number | Date): string {
  if (value === undefined) {
    return ''
  }
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
}