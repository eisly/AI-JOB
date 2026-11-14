import { request } from './request'
import { submitTrackedTask } from './task-registry'
import type { JobVO, MatchBatchVO, Page, PageQuery } from './types'

export const jobApi = {
  list(query: PageQuery & { q?: string; cityCode?: string; candidateType?: string }) {
    return request<Page<JobVO>>('/api/v1/jobs', { query })
  },
  get(jobId: string) {
    return request<JobVO>(`/api/v1/jobs/${jobId}`)
  },
  imports(query: PageQuery = {}) {
    return request<Page<JobVO>>('/api/v1/jobs/imports', { query })
  },
  importJd(body: { title: string; companyName: string; jdText: string; sourceUrl?: string }) {
    return submitTrackedTask('/api/v1/jobs/imports', body, '/jobs?tab=personal')
  },
  correctFields(jobId: string, body: { expectedJobVersion: number; cityCode: string | null; skillCodes: string[]; sourceQuote: string; reason: string }) {
    return request<JobVO>(`/api/v1/jobs/${jobId}/extracted-fields`, { method: 'PUT', body })
  },
  favorites(query: PageQuery = {}) {
    return request<Page<JobVO>>('/api/v1/favorites', { query })
  },
  setFavorite(jobId: string, favorite: boolean) {
    return request<boolean>(`/api/v1/favorites/${jobId}`, { method: favorite ? 'PUT' : 'DELETE', ...(favorite ? { body: {} } : {}) })
  },
  createMatch(body: { expectedProfileVersion: number; jobIds?: string[]; includeUnknown: boolean }) {
    return submitTrackedTask('/api/v1/job-matches', body, '/jobs?tab=matches')
  },
  getMatch(matchId: string) {
    return request<MatchBatchVO>(`/api/v1/job-matches/${matchId}`)
  },
  listMatches(query: PageQuery = {}) {
    return request<Page<MatchBatchVO>>('/api/v1/job-matches', { query })
  },
}
