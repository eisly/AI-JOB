import { request } from './request'
import type {
  ApplicationCreatePayload,
  ApplicationListQuery,
  ApplicationPage,
  ApplicationStatus,
  ApplicationSummary,
  ApplicationUpdatePayload,
  ApplicationVO,
} from '@/types/application'

/**
 * 投递进度六接口封装（对接 docs/功能资料/11-投递进度 契约）：
 * - 列表 status 省略时整个参数不发送（后端对空串/空白严格 400）
 * - 修改携带 expectedVersion（差量 body 由 buildUpdatePayload 生成）
 * - 删除通过 query 参数携带 expectedVersion
 */

const BASE = '/api/v1/applications'

/** 新增投递记录 */
export function createApplication(payload: ApplicationCreatePayload): Promise<ApplicationVO> {
  return request<ApplicationVO>(BASE, { method: 'POST', body: payload })
}

/**
 * 分页查询投递列表。
 * query.status 为 null/undefined 时完全省略 status 参数（禁止 status= 空串/空白）。
 */
export function listApplications(query: ApplicationListQuery = {}): Promise<ApplicationPage> {
  return request<ApplicationPage>(BASE, {
    method: 'GET',
    query: {
      status: query.status ?? undefined,
      current: query.current ?? undefined,
      size: query.size ?? undefined,
    },
  })
}

/** 查询投递详情（409 冲突“重新加载”也走此接口取最新完整记录） */
export function getApplicationDetail(id: string): Promise<ApplicationVO> {
  return request<ApplicationVO>(`${BASE}/${encodeURIComponent(id)}`, { method: 'GET' })
}

/** 修改投递记录（PATCH 差量：未修改字段不发送，清空可空字段发送 null） */
export function updateApplication(id: string, payload: ApplicationUpdatePayload): Promise<ApplicationVO> {
  return request<ApplicationVO>(`${BASE}/${encodeURIComponent(id)}`, { method: 'PATCH', body: payload })
}

/** 删除投递记录（expectedVersion 经 query 参数传递） */
export function deleteApplication(id: string, expectedVersion: number): Promise<boolean> {
  return request<boolean>(`${BASE}/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    query: { expectedVersion },
  })
}

/** 看板统计（独立请求，不用列表数据推算） */
export function getApplicationSummary(): Promise<ApplicationSummary> {
  return request<ApplicationSummary>(`${BASE}/summary`, { method: 'GET' })
}

/** 按状态查询列表的便捷封装（明确指定状态时才发送参数） */
export function listApplicationsByStatus(status: ApplicationStatus, current = 1, size = 20): Promise<ApplicationPage> {
  return listApplications({ status, current, size })
}