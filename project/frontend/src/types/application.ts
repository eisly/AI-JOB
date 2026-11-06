/** 投递进度模块类型定义（对齐 docs/功能资料/11-投递进度 接口契约） */

export type ApplicationStatus = 'PLANNED' | 'APPLIED' | 'INTERVIEWING' | 'CLOSED'

export const APPLICATION_STATUSES: readonly ApplicationStatus[] = [
  'PLANNED',
  'APPLIED',
  'INTERVIEWING',
  'CLOSED',
] as const

export const APPLICATION_STATUS_LABELS: Record<ApplicationStatus, string> = {
  PLANNED: '待投递',
  APPLIED: '已投递',
  INTERVIEWING: '面试中',
  CLOSED: '已结束',
}

/** ApplicationStatus 的中文标签映射（模板直接调用） */
export function statusLabelOf(status: string): string {
  return APPLICATION_STATUS_LABELS[status as ApplicationStatus] ?? status
}

/** 统一返回体 CommonResult<T>（code/msg/data/traceId） */
export interface CommonResult<T> {
  code: number
  msg: string
  data: T
  traceId: string
}

/** ApplicationVO：投递记录视图（日期为 YYYY-MM-DD 字符串直传，不做时区转换） */
export interface ApplicationVO {
  id: string
  jobId: string | null
  companyName: string
  jobTitle: string
  cityCode: string | null
  education: string | null
  salaryText: string | null
  description: string | null
  status: ApplicationStatus
  plannedDate: string | null
  appliedDate: string | null
  interviewDate: string | null
  closedDate: string | null
  note: string | null
  version: number
  createdAt: string
  updatedAt: string
}

/** 分页结构 Page<ApplicationVO> */
export interface ApplicationPage {
  total: number
  records: ApplicationVO[]
  current: number
  size: number
}

/** 看板统计 ApplicationSummaryVO */
export interface ApplicationSummary {
  total: number
  counts: Record<ApplicationStatus, number>
}

/** 新增投递记录请求体（jobId 为内部对接字段，手动录入默认不采集、不发送） */
export interface ApplicationCreatePayload {
  jobId?: string
  companyName: string
  jobTitle: string
  cityCode?: string | null
  education?: string | null
  salaryText?: string | null
  description?: string | null
  status?: ApplicationStatus
  plannedDate?: string | null
  appliedDate?: string | null
  interviewDate?: string | null
  closedDate?: string | null
  note?: string | null
}

/**
 * 修改投递记录请求体（PATCH 差量）：
 * 未修改字段不出现；清空可空字段显式传 null；expectedVersion 必填。
 * 必填字段（companyName/jobTitle/status）只发新值，永不发 null。
 */
export interface ApplicationUpdatePayload {
  expectedVersion: number
  companyName?: string
  jobTitle?: string
  cityCode?: string | null
  education?: string | null
  salaryText?: string | null
  description?: string | null
  status?: ApplicationStatus
  plannedDate?: string | null
  appliedDate?: string | null
  interviewDate?: string | null
  closedDate?: string | null
  note?: string | null
}

/** 列表查询条件：status 为 null/undefined 时整个参数不发（禁止 status= 空串/空白） */
export interface ApplicationListQuery {
  status?: ApplicationStatus | null
  current?: number
  size?: number
}

/** 表单可编辑字段（新增/编辑共用形态；空字符串表示“清空可空字段”） */
export interface ApplicationFormFieldValues {
  companyName: string
  jobTitle: string
  cityCode: string
  education: string
  salaryText: string
  description: string
  status: ApplicationStatus
  plannedDate: string
  appliedDate: string
  interviewDate: string
  closedDate: string
  note: string
}

/** 空字符串/null 统一视为“未填写” */
function isEmptyText(value: string | null | undefined): boolean {
  return value === null || value === undefined || value.trim() === ''
}

/**
 * 文本字段“是否无变化”判定（与提交口径一致：表单值按 trim 后比较）。
 * - 空态（null/undefined/纯空白）与空态 → 未变化
 * - 空态与非空 → 变化（清空或填入）
 * - 非空与非空 → trim 后相等才算未变化
 */
function sameText(a: string | null | undefined, b: string | null | undefined): boolean {
  const emptyA = isEmptyText(a)
  const emptyB = isEmptyText(b)
  if (emptyA !== emptyB) return false
  if (emptyA) return true
  return (a as string).trim() === (b as string).trim()
}

function sameDate(a: string | null | undefined, b: string | null | undefined): boolean {
  const na = isEmptyText(a) ? null : (a as string)
  const nb = isEmptyText(b) ? null : (b as string)
  return na === nb
}

/**
 * 由基线记录 + 当前表单生成 PATCH 差量：
 * - 未变化字段不出现在 payload（不发键）
 * - 可空字段从有值变为空 → 显式 null（清空）
 * - 必填字段仅在新值合法时发送，永不发送 null
 * - 无任何变化时返回 null（调用方应阻止提交）
 */
export function buildUpdatePayload(
  baseline: ApplicationVO,
  form: ApplicationFormFieldValues,
): ApplicationUpdatePayload | null {
  const payload: ApplicationUpdatePayload = { expectedVersion: baseline.version }
  let changed = false

  if (!isEmptyText(form.companyName) && form.companyName.trim() !== baseline.companyName) {
    payload.companyName = form.companyName.trim()
    changed = true
  }
  if (!isEmptyText(form.jobTitle) && form.jobTitle.trim() !== baseline.jobTitle) {
    payload.jobTitle = form.jobTitle.trim()
    changed = true
  }
  if (form.status !== baseline.status) {
    payload.status = form.status
    changed = true
  }

  const optionalText: Array<[
    key: 'cityCode' | 'education' | 'salaryText' | 'description' | 'note',
    baselineValue: string | null,
    formValue: string,
  ]> = [
    ['cityCode', baseline.cityCode, form.cityCode],
    ['education', baseline.education, form.education],
    ['salaryText', baseline.salaryText, form.salaryText],
    ['description', baseline.description, form.description],
    ['note', baseline.note, form.note],
  ]
  for (const [key, baselineValue, formValue] of optionalText) {
    if (!sameText(baselineValue, formValue)) {
      payload[key] = isEmptyText(formValue) ? null : formValue.trim()
      changed = true
    }
  }

  const optionalDates: Array<[
    key: 'plannedDate' | 'appliedDate' | 'interviewDate' | 'closedDate',
    baselineValue: string | null,
    formValue: string,
  ]> = [
    ['plannedDate', baseline.plannedDate, form.plannedDate],
    ['appliedDate', baseline.appliedDate, form.appliedDate],
    ['interviewDate', baseline.interviewDate, form.interviewDate],
    ['closedDate', baseline.closedDate, form.closedDate],
  ]
  for (const [key, baselineValue, formValue] of optionalDates) {
    if (!sameDate(baselineValue, formValue)) {
      payload[key] = isEmptyText(formValue) ? null : formValue
      changed = true
    }
  }

  return changed ? payload : null
}