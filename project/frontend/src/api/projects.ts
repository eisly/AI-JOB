import { request } from './request'
import { submitTrackedTask } from './task-registry'
import type { MatchItemOptionVO, Page, PageQuery, ProjectRecommendationVO, ProjectSummaryVO, ProjectVO, RepoPlatform } from './types'

export const projectApi = {
  recommend(body: {
    query?: string
    sources?: RepoPlatform[]
    techStackKeywords?: string[]
    matchItemId?: string
    skillCodes?: string[]
    difficulty?: string
    maxDurationWeeks?: number
  }) {
    return submitTrackedTask('/api/v1/projects/recommendations', body, '/project?tab=recommendations')
  },
  matchItemOptions() {
    return request<MatchItemOptionVO[]>('/api/v1/job-searches/match-item-options')
  },
  getRecommendation(recommendationId: string) {
    return request<ProjectRecommendationVO>(`/api/v1/projects/recommendations/${recommendationId}`)
  },
  moreRecommendations(recommendationId: string, afterBatchId: string) {
    return submitTrackedTask(`/api/v1/projects/recommendations/${recommendationId}/more`, { afterBatchId },
      `/project?tab=recommendations&recommendationId=${recommendationId}`)
  },
  createPlan(body: { recommendationItemId: string; weeklyHours: number }) {
    return submitTrackedTask('/api/v1/projects/plans', body, '/project?tab=projects')
  },
  list(query: PageQuery = {}) {
    return request<Page<ProjectSummaryVO>>('/api/v1/projects', { query })
  },
  get(projectId: string) {
    return request<ProjectVO>(`/api/v1/projects/${projectId}`)
  },
  updateTask(projectId: string, taskId: string, body: { status: string; expectedVersion: number }) {
    return request<ProjectVO>(`/api/v1/projects/${projectId}/tasks/${taskId}`, { method: 'PATCH', body })
  },
  addDeliverable(projectId: string, body: { taskId: string; title: string; url?: string; notes?: string }) {
    return request<ProjectVO>(`/api/v1/projects/${projectId}/deliverables`, { method: 'POST', body, idempotency: true })
  },
}
