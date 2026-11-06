import { downloadBinary, request } from './request'
import { submitTrackedTask } from './task-registry'
import type { DownloadVO, Page, PageQuery, ProfileVO, ResumeFacts, ResumeVersionVO, ResumeVO } from './types'

export const resumeApi = {
  list(query: PageQuery = {}) {
    return request<Page<ResumeVO>>('/api/v1/resumes', { query })
  },
  getVersion(versionId: string) {
    return request<ResumeVersionVO>(`/api/v1/resume-versions/${versionId}`)
  },
  versions(resumeId: string) {
    return request<ResumeVersionVO[]>(`/api/v1/resumes/${resumeId}/versions`)
  },
  upload(file: File, title?: string, resumeId?: string) {
    const body = new FormData()
    body.append('file', file)
    if (title) body.append('title', title)
    if (resumeId) body.append('resumeId', resumeId)
    return request<ResumeVO>('/api/v1/resumes/upload', { method: 'POST', body, idempotency: true })
  },
  parse(versionId: string) {
    return submitTrackedTask(`/api/v1/resume-versions/${versionId}/parse`, {}, `/resume?versionId=${versionId}`)
  },
  confirm(versionId: string, expectedProfileVersion: number, facts: ResumeFacts) {
    return request<ProfileVO>(`/api/v1/resume-versions/${versionId}/confirm`, {
      method: 'POST',
      body: { expectedProfileVersion, facts },
      idempotency: true,
    })
  },
  remove(resumeId: string) {
    return request<boolean>(`/api/v1/resumes/${resumeId}`, { method: 'DELETE' })
  },
  async download(versionId: string, fileName: string) {
    const target = await request<DownloadVO>(`/api/v1/resume-versions/${versionId}/download`)
    const signedUrl = new URL(target.url, window.location.origin)
    const path = signedUrl.pathname.startsWith('/api/v1/') ? `${signedUrl.pathname}${signedUrl.search}` : target.url
    return downloadBinary(path, { auth: path.startsWith('/api/v1/'), fallbackName: fileName })
  },
}