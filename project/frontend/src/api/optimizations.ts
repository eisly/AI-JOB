import { downloadBinary, request } from './request'
import { submitTrackedTask } from './task-registry'
import type { Page, PageQuery, ResumeDiagnosisVO, ResumeOptimizationVO } from './types'

export const optimizationApi = {
  diagnose(resumeVersionId: string) {
    return submitTrackedTask('/api/v1/resume-optimizations/diagnoses', { resumeVersionId }, `/resume-optimization?resumeVersionId=${resumeVersionId}`)
  },
  getDiagnosis(diagnosisId: string) {
    return request<ResumeDiagnosisVO>(`/api/v1/resume-optimizations/diagnoses/${diagnosisId}`)
  },
  listDiagnoses(query: PageQuery & { resumeVersionId?: string }) {
    return request<Page<ResumeDiagnosisVO>>('/api/v1/resume-optimizations/diagnoses', { query })
  },
  create(body: { resumeVersionId: string; jobId: string; diagnosisId?: string }) {
    return submitTrackedTask('/api/v1/resume-optimizations', body, `/resume-optimization?resumeVersionId=${body.resumeVersionId}&jobId=${body.jobId}`)
  },
  get(optimizationId: string) {
    return request<ResumeOptimizationVO>(`/api/v1/resume-optimizations/${optimizationId}`)
  },
  list(query: PageQuery & { resumeVersionId?: string; jobId?: string }) {
    return request<Page<ResumeOptimizationVO>>('/api/v1/resume-optimizations', { query })
  },
  accept(optimizationId: string, sections: { sectionKey: string; title: string; content: string }[]) {
    return request<ResumeOptimizationVO>(`/api/v1/resume-optimizations/${optimizationId}/accept`, {
      method: 'POST',
      body: { sections },
      idempotency: true,
    })
  },
  export(optimizationId: string, format: 'PDF' | 'DOCX') {
    return downloadBinary(`/api/v1/resume-optimizations/${optimizationId}/export`, {
      query: { format },
      fallbackName: `resume-optimization.${format.toLowerCase()}`,
    })
  },
}