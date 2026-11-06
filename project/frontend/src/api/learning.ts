import { request } from './request'
import { submitTrackedTask } from './task-registry'
import type { LearningAnswerVO, LearningPlanVO, LearningSessionVO, MistakeVO, Page, PageQuery } from './types'

export const learningApi = {
  createPlan(body: { matchItemId: string; dailyMinutes: number; days: number }) {
    return submitTrackedTask('/api/v1/learning/plans', body, '/training')
  },
  listPlans(query: PageQuery = {}) {
    return request<Page<LearningPlanVO>>('/api/v1/learning/plans', { query })
  },
  getPlan(planId: string) {
    return request<LearningPlanVO>(`/api/v1/learning/plans/${planId}`)
  },
  completeReading(planId: string, itemId: string) {
    return request<boolean>(`/api/v1/learning/plans/${planId}/items/${itemId}/completion`, { method: 'PUT', body: {} })
  },
  startSession(planId: string, planItemId: string) {
    return submitTrackedTask('/api/v1/learning/sessions', { planId, planItemId }, `/training?planId=${planId}`)
  },
  getSession(sessionId: string) {
    return request<LearningSessionVO>(`/api/v1/learning/sessions/${sessionId}`)
  },
  submitAnswer(sessionId: string, body: { content: string; expectedVersion: number }) {
    return submitTrackedTask(`/api/v1/learning/sessions/${sessionId}/answers`, body, `/training?sessionId=${sessionId}`)
  },
  getAnswer(answerId: string) {
    return request<LearningAnswerVO>(`/api/v1/learning/answers/${answerId}`)
  },
  mistakes(query: PageQuery & { resolved: boolean }) {
    return request<Page<MistakeVO>>('/api/v1/learning/mistakes', { query })
  },
  retryMistake(mistakeId: string) {
    return submitTrackedTask(`/api/v1/learning/mistakes/${mistakeId}/retry`, {}, '/training?tab=mistakes')
  },
}