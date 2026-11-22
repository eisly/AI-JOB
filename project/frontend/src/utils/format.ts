import type { ResumeFacts } from '@/api/types'

export const taskStatusLabels: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '处理中',
  CANCEL_REQUESTED: '取消中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  EXPIRED: '已过期',
}

export const taskTypeLabels: Record<string, string> = {
  RESUME_PARSE: '简历解析',
  RESUME_DIAGNOSE: '简历诊断',
  RESUME_OPTIMIZE: '简历定向优化',
  JD_ANALYZE: 'JD 解析',
  JOB_MATCH: '岗位匹配',
  JOB_SEARCH: '联网岗位搜索',
  LEARNING_PLAN: '生成训练计划',
  LEARNING_QUESTION: '准备训练题',
  LEARNING_EVALUATE: '训练评价',
  LEARNING_PRACTICE_QUESTIONS: '准备整组练习',
  LEARNING_PRACTICE_EVALUATE: '整组练习评估',
  PROJECT_RECOMMEND: '项目推荐',
  PROJECT_PLAN: '生成项目计划',
  INTERVIEW_TURN: '面试对话生成',
  INTERVIEW_REPORT: '面试报告',
  GROWTH_ANALYZE: '成长建议生成',
}

export const taskStageLabels: Record<string, string> = {
  QUEUED: '排队等待执行',
  AI_RUNNING: 'AI 处理中',
  DONE: '已完成',
  FINISHED: '已结束',
  input_ready: '输入就绪',
  calling_model: '正在调用模型',
  repairing_output: '正在修正模型输出',
  validating_result: '正在校验结果',
  using_local_project_rules: '正在基于已选仓库生成规则化计划',
  using_repository_hints: '正在整理候选仓库',
  search_planning: '正在规划搜索',
  searching_sources: '正在检索岗位来源',
  search_candidates_available: '已获得候选岗位',
  search_adjusting: '正在补充搜索',
  search_normalizing: '正在归一化岗位数据',
  understanding_jd: '正在理解 JD',
  finalizing_result: '正在汇总结果',
}

export function taskStageText(task: { status?: string; stage?: string | null }): string {
  if (task.status === 'RUNNING') return taskStageLabels[task.stage || ''] || '处理中'
  return taskStatusLabels[task.status || ''] || '等待任务状态'
}

export const commonStatusLabels: Record<string, string> = {
  UPLOADED: '待解析',
  PARSING: '解析中',
  PARSED: '待确认',
  CONFIRMED: '已确认',
  FAILED: '失败',
  GENERATING: '生成中',
  READY: '已生成',
  ACTIVE: '进行中',
  COMPLETED: '已完成',
  ACCEPTED: '已采纳',
  DRAFT: '草稿',
  ANALYZING: '分析中',
  CLOSED: '已关闭',
  PREPARING: '准备中',
  WAITING_ANSWER: '等待回答',
  ENDING: '正在结束',
  EVALUATING: '评价中',
  EVALUATED: '已评价',
  PENDING: '待开始',
  IN_PROGRESS: '进行中',
}

export const roleLabels: Record<string, string> = {
  JAVA_BACKEND: 'Java 后端',
  FRONTEND: '前端开发',
  ALGORITHM: '算法工程师',
  TEST: '测试工程师',
  DATA: '数据工程师',
}

export const candidateTypeLabels: Record<string, string> = {
  INTERNSHIP: '实习',
  CAMPUS: '校招',
  EXPERIENCED: '社招',
}

export const educationLabels: Record<string, string> = {
  HIGH_SCHOOL: '高中',
  ASSOCIATE: '大专',
  BACHELOR: '本科',
  MASTER: '硕士',
  DOCTOR: '博士',
}

export const cityLabels: Record<string, string> = {
  '110100': '北京',
  '310100': '上海',
  '330100': '杭州',
  '440100': '广州',
  '440300': '深圳',
  '320100': '南京',
  '510100': '成都',
  '420100': '武汉',
}

export const companySizeLabels: Record<string, string> = {
  SIZE_0_20: '0-20 人',
  SIZE_21_99: '21-99 人',
  SIZE_100_499: '100-499 人',
  SIZE_500_999: '500-999 人',
  SIZE_1000_9999: '1000-9999 人',
  SIZE_10000_PLUS: '10000 人及以上',
  SIZE_UNKNOWN: '规模未知',
}

export const searchSourceStatusLabels: Record<string, string> = {
  AVAILABLE: '可用',
  EMPTY: '可用但无结果',
  PARTIAL: '部分可用',
  FAILED: '失败',
  UNAVAILABLE: '不可用',
  SKIPPED: '未选择',
}

/** 搜索前来源配置检查状态标签：均为静态配置检查结论，不代表已实际联网验证连接 */
export const sourceHealthLabels: Record<string, string> = {
  UNCONFIGURED: '未配置',
  READY_TO_TRY: '已配置（检索时验证）',
  UNAVAILABLE: '暂未接入',
}

/** 任务业务结果标签（S03/A05）：任务列表区分"执行完成"与"业务结果不可用/部分失败" */
export const taskOutcomeLabels: Record<string, string> = {
  OK: '正常完成',
  PARTIAL: '部分来源失败，已保留可用结果',
  EMPTY: '搜索完成，本次无匹配结果',
  NO_RESULTS_SOURCE_FAILED: '来源失败，无可用结果',
}

export function taskErrorSummary(error: { code: string; message: string }) {
  if (/PROMPT_VERSIONS|\.md.*version|Agent 内部/.test(error.message) || error.code === 'INTERNAL_ERROR') {
    return '服务处理异常，请稍后重试；若仍失败，请联系维护人员。'
  }
  return error.message
}

export const searchWarningLabels: Record<string, string> = {
  MISSING_SALARY: '薪资未提供',
  MISSING_EDUCATION: '学历要求未提供',
  MISSING_EXPERIENCE: '经验要求未提供',
  MISSING_PUBLISHED_AT: '发布时间未知',
  POSSIBLY_EXPIRED: '发布时间超过 90 天，可能已过期',
  COMPANY_SIZE_UNKNOWN: '公司规模未知',
  UNVERIFIED_SOURCE: '来源未经核验',
  CANDIDATE_TYPE_INFERRED: '求职类型由规则推断',
  UNKNOWN_SKILLS_DROPPED: '部分技能编码不在目录中已忽略',
  SOURCE_PARTIAL: '部分来源失败',
  EMPTY_RESULT: '本次搜索无结果',
}

export function errorDetailText(reason: unknown) {
  const details = (reason as { details?: Record<string, unknown> } | null)?.details
  if (!details) return ''
  const fields = details.fields
  if (Array.isArray(fields) && fields.length) {
    return fields
      .map(item => {
        if (!item || typeof item !== 'object') return ''
        const field = (item as { field?: unknown }).field
        const message = (item as { message?: unknown }).message
        return `${typeof field === 'string' ? field : '字段'}：${typeof message === 'string' ? message : '值不合法'}`
      })
      .filter(Boolean)
      .join('；')
  }
  if (typeof details.field === 'string') {
    const hint = details.hint ?? details.reason ?? details.message
    return `${details.field}：${typeof hint === 'string' ? hint : '请检查该字段'}`
  }
  return ''
}

export function formatDate(value?: string | null) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

export function formatSalary(min?: number | null, max?: number | null) {
  if (min === null || min === undefined || max === null || max === undefined) return '薪资未明确'
  return `¥${(min / 100).toLocaleString('zh-CN')}–${(max / 100).toLocaleString('zh-CN')}/月`
}

export function formatFileSize(size: number) {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KiB`
  return `${(size / 1024 / 1024).toFixed(1)} MiB`
}

export function displayScore(score?: number | null) {
  return score === null || score === undefined ? '待评估' : String(score)
}

export function emptyFacts(): ResumeFacts {
  return { education: null, graduationYear: null, experienceMonths: null, major: null, skills: [], internships: [], awards: [], warnings: [] }
}

export function cloneFacts(facts?: ResumeFacts | null): ResumeFacts {
  if (!facts) return emptyFacts()
  const copied = JSON.parse(JSON.stringify(facts)) as ResumeFacts
  return { ...emptyFacts(), ...copied, internships: copied.internships || [], awards: copied.awards || [] }
}

export function errorMessage(reason: unknown) {
  const base = reason instanceof Error
    ? reason.message
    : reason && typeof reason === 'object'
      ? objectMessage(reason)
      : String(reason ?? '未知错误')
  const detail = errorDetailText(reason)
  return detail && !base.includes(detail) ? `${base}（${detail}）` : base
}

function objectMessage(reason: object) {
  const message = (reason as { message?: unknown }).message
  if (typeof message === 'string' && message) return message
  const code = (reason as { code?: unknown }).code
  if (typeof code === 'string' && code) return `请求失败（${code}）`
  try {
    const text = JSON.stringify(reason)
    return text && text !== '{}' ? text : '请求失败，请稍后重试'
  } catch {
    return '请求失败，请稍后重试'
  }
}
