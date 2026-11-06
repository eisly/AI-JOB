import { request } from './request'
import type { ApplicationCreateInput, ApplicationSummaryVO, ApplicationUpdateInput, ApplicationVO, Page, PageQuery } from './types'

export const applicationApi = {
  list(query: PageQuery & { status?: string }) {
    return request<Page<ApplicationVO>>('/api/v1/applications', { query })
  },
  summary() {
    return request<ApplicationSummaryVO>('/api/v1/applications/summary')
  },
  get(id: string) {
    return request<ApplicationVO>(`/api/v1/applications/${id}`)
  },
  create(body: ApplicationCreateInput) {
    return request<ApplicationVO>('/api/v1/applications', { method: 'POST', body })
  },
  update(id: string, body: ApplicationUpdateInput) {
    return request<ApplicationVO>(`/api/v1/applications/${id}`, { method: 'PATCH', body })
  },
  remove(id: string, expectedVersion: number) {
    return request<boolean>(`/api/v1/applications/${id}`, { method: 'DELETE', query: { expectedVersion } })
  },
}