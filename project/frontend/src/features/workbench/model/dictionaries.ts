import { EMPTY_TEXT } from '../constants'

export const ROLE_LABELS: Record<string, string> = {
  JAVA_BACKEND: 'Java 后端开发',
  FRONTEND: '前端开发',
  ALGORITHM: '算法工程师',
  TEST: '测试开发',
  DATA: '数据开发',
}

export const CANDIDATE_TYPE_LABELS: Record<string, string> = {
  INTERNSHIP: '实习',
  CAMPUS: '校招',
  EXPERIENCED: '社招',
}

export const PLAN_ITEM_KIND_LABELS: Record<string, string> = {
  READING: '阅读',
  PRACTICE: '练习',
  INTERVIEW: '面试演练',
}

export const TASK_TYPE_LABELS: Record<string, string> = {
  RESUME_PARSE: '简历解析',
  JOB_MATCH: '岗位匹配',
  LEARNING_PLAN: '学习计划生成',
  INTERVIEW_QUESTION: '面试提问',
  INTERVIEW_REPORT: '面试报告',
  GROWTH_SUMMARY: '成长摘要',
  PROJECT_PRACTICE: '项目实战',
}

export const TASK_STATUS_LABELS: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '执行中',
  CANCEL_REQUESTED: '取消请求中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  EXPIRED: '已过期',
}

export const ELIGIBILITY_LABELS: Record<string, string> = {
  PASS: '资格已通过',
  UNKNOWN: '资格待确认',
  FAIL: '资格不通过',
}

export const SOURCE_VERIFICATION_LABELS: Record<string, string> = {
  VERIFIED: '来源已核验',
  UNVERIFIED: '来源未核验',
  DISABLED: '来源已停用',
}

export const EVIDENCE_SOURCE_LABELS: Record<string, string> = {
  LEARNING_ANSWER: '专项训练',
  INTERVIEW_ANSWER: '模拟面试',
  RESUME_CLAIM: '简历自述',
}

export const ACTIVITY_TYPE_LABELS: Record<string, string> = {
  RESUME_CONFIRMED: '简历确认',
  MATCH_READY: '匹配完成',
  TRAINING_COMPLETED: '训练完成',
  INTERVIEW_COMPLETED: '面试完成',
  GROWTH_SUGGESTION: '成长建议',
}

export function translate(map: Record<string, string>, value?: string | null, fallback = EMPTY_TEXT): string {
  if (!value) {
    return fallback
  }
  return map[value] ?? value
}