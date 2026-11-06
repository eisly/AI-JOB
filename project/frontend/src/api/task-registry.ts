import { request } from './request'
import { taskApi } from './tasks'
import type { TaskVO } from './types'

export interface TaskSubmission {
  path: string
  method: 'POST' | 'PUT' | 'PATCH'
  body: unknown
  businessPath: string
}

const storageKey = 'ai-job-task-submissions-v1'

function readRegistry() {
  try {
    return JSON.parse(localStorage.getItem(storageKey) || '{}') as Record<string, TaskSubmission>
  } catch {
    return {} as Record<string, TaskSubmission>
  }
}

function writeRegistry(registry: Record<string, TaskSubmission>) {
  try {
    const entries = Object.entries(registry).sort((a, b) => b[0].localeCompare(a[0])).slice(-200)
    localStorage.setItem(storageKey, JSON.stringify(Object.fromEntries(entries)))
  } catch {
    return
  }
}

export function rememberTaskSubmission(taskId: string, submission: TaskSubmission) {
  const registry = readRegistry()
  registry[taskId] = submission
  writeRegistry(registry)
}

export function getTaskSubmission(taskId: string) {
  return readRegistry()[taskId]
}

export async function submitTrackedTask(path: string, body: unknown, businessPath: string, method: TaskSubmission['method'] = 'POST') {
  const task = await request<TaskVO>(path, { method, body, idempotency: true })
  rememberTaskSubmission(task.id, { path, method, body, businessPath })
  return task
}

export function canRetryTrackedTask(task: TaskVO) {
  return task.status === 'FAILED' || task.status === 'EXPIRED'
    || (task.status === 'CANCELLED' && ['INTERVIEW_TURN', 'LEARNING_EVALUATE'].includes(task.type))
}

export async function retryTrackedTask(task: TaskVO) {
  const retried = await taskApi.retry(task.id)
  const submission = getTaskSubmission(task.id)
  if (submission) rememberTaskSubmission(retried.id, submission)
  return retried
}

export function taskBusinessPath(task: TaskVO) {
  const saved = getTaskSubmission(task.id)?.businessPath
  const resourceId = task.result?.resourceId || task.resourceId
  if (task.status === 'SUCCEEDED' && task.result) {
    const routes: Record<string, string> = {
      RESUME_VERSION: `/resume?versionId=${resourceId}`,
      JOB: `/jobs?tab=trusted&jobId=${resourceId}`,
      JOB_MATCH: `/jobs?tab=matches&matchId=${resourceId}`,
      JOB_SEARCH: `/jobs?tab=search&searchId=${resourceId}`,
      LEARNING_PLAN: `/training?planId=${resourceId}`,
      LEARNING_SESSION: `/training?sessionId=${resourceId}`,
      LEARNING_ANSWER: `/training?answerId=${resourceId}`,
      INTERVIEW: `/interview?interviewId=${resourceId}`,
      INTERVIEW_REPORT: `/interview?interviewId=${resourceId}&report=1`,
      GROWTH: '/growth',
      RESUME_DIAGNOSIS: `/resume-optimization?diagnosisId=${resourceId}`,
      RESUME_OPTIMIZATION: `/resume-optimization?optimizationId=${resourceId}`,
      PROJECT_RECOMMENDATION: `/project?recommendationId=${resourceId}`,
      PROJECT: `/project?projectId=${resourceId}`,
    }
    if (routes[task.result.resourceType]) return routes[task.result.resourceType]
  }
  if (task.type === 'JOB_SEARCH') return `/jobs?tab=search&taskId=${task.id}`
  if (saved) return saved
  const fallback: Record<string, string> = {
    RESUME_PARSE: '/resume',
    RESUME_DIAGNOSE: '/resume-optimization',
    RESUME_OPTIMIZE: '/resume-optimization',
    JD_ANALYZE: '/jobs?tab=personal',
    JOB_MATCH: '/jobs?tab=matches',
    LEARNING_PLAN: '/training',
    LEARNING_QUESTION: '/training',
    LEARNING_EVALUATE: '/training',
    PROJECT_RECOMMEND: '/project',
    PROJECT_PLAN: '/project',
    INTERVIEW_TURN: '/interview',
    INTERVIEW_REPORT: '/interview',
    GROWTH_ANALYZE: '/growth',
  }
  return fallback[task.type] || '/tasks'
}