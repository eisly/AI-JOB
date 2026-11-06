import { request } from './request'
import { submitTrackedTask } from './task-registry'
import type { EvidenceVO, GrowthVO, Page, PageQuery } from './types'

export const growthApi = {
  get() {
    return request<GrowthVO>('/api/v1/growth')
  },
  evidence(query: PageQuery & { skillCode?: string; sourceType?: string }) {
    return request<Page<EvidenceVO>>('/api/v1/growth/evidence', { query })
  },
  suggest(expectedProfileVersion: number) {
    return submitTrackedTask('/api/v1/growth/suggestions', { expectedProfileVersion }, '/growth')
  },
}