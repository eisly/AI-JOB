import { request } from './request'
import { submitTrackedTask } from './task-registry'
import type { InterviewReportVO, InterviewVO, Page, PageQuery } from './types'

export const interviewApi = {
  create(body: { jobId: string; resumeVersionId: string; questionLimit: number; planItemId?: string }) {
    return submitTrackedTask('/api/v1/interviews', body, '/interview')
  },
  list(query: PageQuery = {}) {
    return request<Page<InterviewVO>>('/api/v1/interviews', { query })
  },
  get(interviewId: string) {
    return request<InterviewVO>(`/api/v1/interviews/${interviewId}`)
  },
  answer(interviewId: string, body: {
    questionMessageId: string
    content: string
    expectedVersion: number
    answerSource?: 'TEXT' | 'VOICE'
    voiceTranscriptionId?: string
  }) {
    return submitTrackedTask(`/api/v1/interviews/${interviewId}/answers`, body, `/interview?interviewId=${interviewId}`)
  },
  end(interviewId: string, expectedVersion: number) {
    return submitTrackedTask(`/api/v1/interviews/${interviewId}/end`, { expectedVersion }, `/interview?interviewId=${interviewId}&report=1`)
  },
  report(interviewId: string) {
    return request<InterviewReportVO>(`/api/v1/interviews/${interviewId}/report`)
  },
}