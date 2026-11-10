import type { RegionKey } from '@/types/workbench'

export const REGION_ORDER: RegionKey[] = ['identity', 'goal', 'nextTask', 'targetJobs', 'progress', 'activities']

export const REGION_TITLES: Record<RegionKey, string> = {
  identity: '身份',
  goal: '求职目标',
  nextTask: '你的下一项任务',
  targetJobs: '近期目标岗位',
  progress: '求职进度',
  activities: '最新动态',
}

export const REQUEST_TIMEOUT_MS = 1500

export const RETRY_ESCALATION_THRESHOLD = 3

export const SNAPSHOT_KEY = 'aijob.workbench.snapshot.v1'

export const SNAPSHOT_SCHEMA_VERSION = 1

export const MAX_TARGET_JOBS = 3

export const MAX_ACTIVITIES = 5

export const MAX_GOAL_CITIES = 5

export const MAX_SUMMARY_LENGTH = 100

export const EMPTY_TEXT = '未设置'