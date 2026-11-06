import { request } from './request'
import { submitTrackedTask } from './task-registry'
import type {
  CompanySize,
  JobSearchCreateInput,
  JobSearchItemVO,
  JobSearchVO,
  MatchItemOptionVO,
  Page,
  PageQuery,
} from './types'

export interface JobSearchItemsQuery extends PageQuery {
  keyword?: string
  cityCode?: string
  candidateType?: string
  companySizes?: CompanySize[]
  includeUnknownSize?: boolean
}

export const jobSearchApi = {
  create(body: JobSearchCreateInput) {
    return submitTrackedTask('/api/v1/job-searches', body, '/jobs?tab=search')
  },
  list(query: PageQuery = {}) {
    return request<Page<JobSearchVO>>('/api/v1/job-searches', { query })
  },
  get(searchId: string) {
    return request<JobSearchVO>(`/api/v1/job-searches/${searchId}`)
  },
  items(searchId: string, query: JobSearchItemsQuery = {}) {
    return request<Page<JobSearchItemVO>>(`/api/v1/job-searches/${searchId}/items`, {
      query: {
        ...query,
        companySizes: query.companySizes?.length ? query.companySizes.join(',') : undefined,
      },
    })
  },
  matchItemOptions() {
    return request<MatchItemOptionVO[]>('/api/v1/job-searches/match-item-options')
  },
}