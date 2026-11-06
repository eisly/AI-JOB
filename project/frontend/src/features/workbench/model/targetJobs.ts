import type {
  MatchBatchVO,
  MatchItemVO,
  ProfileVO,
  TargetJobCard,
  TargetJobsModel,
} from '@/types/workbench'
import { MAX_TARGET_JOBS } from '../constants'
import { ELIGIBILITY_LABELS, SOURCE_VERIFICATION_LABELS, translate } from './dictionaries'

function timestamp(value?: string): number {
  if (!value) {
    return 0
  }
  const time = new Date(value).getTime()
  return Number.isNaN(time) ? 0 : time
}

export function selectLatestBatch(batches: MatchBatchVO[]): MatchBatchVO | null {
  if (batches.length === 0) {
    return null
  }
  return [...batches].sort((a, b) => {
    const diff = timestamp(b.createdAt) - timestamp(a.createdAt)
    if (diff !== 0) {
      return diff
    }
    return b.id.localeCompare(a.id)
  })[0]
}

function isValidVersion(value: number | undefined): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0
}

export function isBatchStale(batch: MatchBatchVO, profile: ProfileVO | null): boolean {
  if (batch.isStale === true) {
    return true
  }
  const current = profile?.profileVersion
  const batchVersion = batch.profileVersion
  if (!isValidVersion(current) || !isValidVersion(batchVersion)) {
    return false
  }
  return current !== batchVersion
}

function scoreOf(item: MatchItemVO): number | undefined {
  const score = item.matchScore
  return typeof score === 'number' && Number.isInteger(score) && score >= 0 && score <= 100 ? score : undefined
}

function eligibilityGroup(item: MatchItemVO): number {
  return item.eligibility?.status === 'PASS' ? 0 : 1
}

function sortItems(items: MatchItemVO[]): MatchItemVO[] {
  return [...items].sort((a, b) => {
    const groupDiff = eligibilityGroup(a) - eligibilityGroup(b)
    if (groupDiff !== 0) {
      return groupDiff
    }
    const scoreA = scoreOf(a)
    const scoreB = scoreOf(b)
    if (scoreA === undefined && scoreB === undefined) {
      return a.id.localeCompare(b.id)
    }
    if (scoreA === undefined) {
      return 1
    }
    if (scoreB === undefined) {
      return -1
    }
    if (scoreA !== scoreB) {
      return scoreB - scoreA
    }
    return a.id.localeCompare(b.id)
  })
}

function toCard(item: MatchItemVO, batchId: string): TargetJobCard {
  const eligibilityStatus = item.eligibility?.status ?? 'UNKNOWN'
  return {
    id: item.id,
    title: item.title?.trim() || '岗位信息待补充',
    companyLabel: item.companyName?.trim() || '公司信息待补充',
    matchScore: scoreOf(item),
    eligibilityLabel: translate(ELIGIBILITY_LABELS, eligibilityStatus, '资格待确认'),
    sourceLabel: translate(SOURCE_VERIFICATION_LABELS, item.source?.verificationStatus, ''),
    gapLabels: (item.gaps ?? [])
      .map((gap) => gap.skillCode?.trim())
      .filter((code): code is string => !!code),
    action: {
      name: 'job-match',
      query: { batchId, jobId: item.jobId ?? item.id },
    },
  }
}

export interface TargetJobsInput {
  batches: MatchBatchVO[]
  detail: MatchBatchVO | null
  profile: ProfileVO | null
}

export function buildTargetJobsModel(input: TargetJobsInput): TargetJobsModel | null {
  const latest = selectLatestBatch(input.batches)
  if (!latest) {
    return { phase: 'EMPTY', stale: false, jobs: [], emptyReason: 'NO_RECORD' }
  }

  const status = latest.status
  if (status === 'GENERATING') {
    return { phase: 'GENERATING', stale: false, jobs: [], batchId: latest.id }
  }
  if (status === 'FAILED') {
    return { phase: 'FAILED', stale: false, jobs: [], batchId: latest.id }
  }
  if (status !== 'READY') {
    return null
  }

  const batch = input.detail ?? latest
  const allItems = batch.items ?? []
  const stale = isBatchStale(batch, input.profile)
  const eligible = sortItems(allItems.filter((item) => item.eligibility?.status !== 'FAIL'))
  const jobs = eligible.slice(0, MAX_TARGET_JOBS).map((item) => toCard(item, batch.id))

  if (jobs.length === 0) {
    const allFailed = allItems.length > 0 && allItems.every((item) => item.eligibility?.status === 'FAIL')
    return {
      phase: 'EMPTY',
      stale,
      jobs: [],
      emptyReason: allFailed ? 'ALL_INELIGIBLE' : 'NO_RECORD',
      batchId: batch.id,
    }
  }

  return { phase: 'READY', stale, jobs, batchId: batch.id }
}