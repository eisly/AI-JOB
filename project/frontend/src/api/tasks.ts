import { request } from './request'
import type { Page, PageQuery, TaskVO } from './types'

export const taskApi = {
  list(query: PageQuery & { status?: string }) {
    return request<Page<TaskVO>>('/api/v1/tasks', { query })
  },
  get(taskId: string) {
    return request<TaskVO>(`/api/v1/tasks/${taskId}`)
  },
  cancel(taskId: string) {
    return request<TaskVO>(`/api/v1/tasks/${taskId}/cancel`, { method: 'POST', body: {} })
  },
  retry(taskId: string) {
    return request<TaskVO>(`/api/v1/tasks/${taskId}/retry`, { method: 'POST', body: {}, idempotency: true })
  },
}