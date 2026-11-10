import type {
  ActivityItem,
  ActivityType,
  EvidenceVO,
  GrowthVO,
  InterviewVO,
  LearningPlanVO,
  MatchBatchVO,
  NavigationTarget,
  ResumeVO,
} from '@/types/workbench'
import { MAX_ACTIVITIES, MAX_SUMMARY_LENGTH } from '../constants'
import { ACTIVITY_TYPE_LABELS } from './dictionaries'
import { selectLatestBatch } from './targetJobs'

const SOURCE_ORDER: Record<ActivityType, number> = {
  RESUME_CONFIRMED: 0,
  MATCH_READY: 1,
  TRAINING_COMPLETED: 2,
  INTERVIEW_COMPLETED: 3,
  GROWTH_SUGGESTION: 4,
}

export interface ActivitiesInput {
  resumes: ResumeVO[] | null
  batches: MatchBatchVO[] | null
  plans: LearningPlanVO[] | null
  interviews: InterviewVO[] | null
  growth: GrowthVO | null
}

function sanitizeSummary(text: string): string {
  const cleaned = text
    .replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, '[已隐藏]')
    .replace(/https?:\/\/\S+/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  return cleaned.slice(0, MAX_SUMMARY_LENGTH)
}

function parseTime(value?: string): number | undefined {
  if (!value) {
    return undefined
  }
  const time = new Date(value).getTime()
  return Number.isNaN(time) ? undefined : time
}

function latestTime(values: Array<string | undefined>): string | undefined {
  let result: string | undefined
  let max = -Infinity
  for (const value of values) {
    const time = parseTime(value)
    if (time !== undefined && time > max) {
      max = time
      result = value
    }
  }
  return result
}

function push(
  items: ActivityItem[],
  type: ActivityType,
  id: string,
  summary: string,
  occurredAt: string | undefined,
  target: NavigationTarget | undefined,
): void {
  items.push({
    id,
    type,
    typeLabel: ACTIVITY_TYPE_LABELS[type],
    summary: sanitizeSummary(summary) || '记录已更新',
    occurredAt,
    target,
  })
}

export function buildActivities(input: ActivitiesInput): ActivityItem[] {
  const items: ActivityItem[] = []
  const planItemTitles = new Map<string, string>()
  for (const plan of input.plans ?? []) {
    for (const item of plan.items ?? []) {
      if (item.title) {
        planItemTitles.set(item.id, item.title)
      }
    }
  }

  for (const resume of input.resumes ?? []) {
    const version = resume.latestVersion
    if (version?.status !== 'CONFIRMED') {
      continue
    }
    push(items, 'RESUME_CONFIRMED', `resume-${version.id}`, '简历版本已确认', version.createdAt ?? version.confirmedAt, {
      name: 'resume',
      query: { resumeId: resume.id },
    })
  }

  const latestBatch = input.batches ? selectLatestBatch(input.batches) : null
  if (latestBatch?.status === 'READY') {
    push(items, 'MATCH_READY', `match-${latestBatch.id}`, '岗位匹配结果已更新', latestBatch.createdAt, {
      name: 'job-match',
      query: { batchId: latestBatch.id },
    })
  }

  const seenEvidence = new Set<string>()
  for (const evidence of input.growth?.recentEvidence ?? []) {
    if (evidence.sourceType !== 'LEARNING_ANSWER') {
      continue
    }
    const key = evidence.sourceId ?? evidence.id
    if (seenEvidence.has(key)) {
      continue
    }
    seenEvidence.add(key)
    const title = evidence.sourceId ? planItemTitles.get(evidence.sourceId) : undefined
    push(
      items,
      'TRAINING_COMPLETED',
      `training-${key}`,
      title ? `完成专项训练：${title}` : '完成专项训练',
      evidence.createdAt,
      { name: 'training', query: evidence.sourceId ? { planItemId: evidence.sourceId } : undefined },
    )
  }

  for (const interview of input.interviews ?? []) {
    if (interview.status !== 'COMPLETED') {
      continue
    }
    push(items, 'INTERVIEW_COMPLETED', `interview-${interview.id}`, '完成模拟面试', interview.completedAt ?? interview.endedAt ?? interview.createdAt, {
      name: 'interview',
      query: { interviewId: interview.id },
    })
  }

  if ((input.growth?.suggestions ?? []).length > 0) {
    push(
      items,
      'GROWTH_SUGGESTION',
      `growth-${input.growth?.profileVersion ?? 0}-${input.growth?.suggestions?.length ?? 0}`,
      '已生成新的成长建议',
      latestTime((input.growth?.skills ?? []).map((skill) => skill.updatedAt)),
      { name: 'growth' },
    )
  }

  return items
    .sort((a, b) => {
      const timeA = parseTime(a.occurredAt)
      const timeB = parseTime(b.occurredAt)
      if (timeA === undefined && timeB === undefined) {
        return SOURCE_ORDER[a.type] - SOURCE_ORDER[b.type]
      }
      if (timeA === undefined) {
        return 1
      }
      if (timeB === undefined) {
        return -1
      }
      if (timeA !== timeB) {
        return timeB - timeA
      }
      return SOURCE_ORDER[a.type] - SOURCE_ORDER[b.type]
    })
    .slice(0, MAX_ACTIVITIES)
}

export function collectEvidence(items: EvidenceVO[] | undefined): EvidenceVO[] {
  return (items ?? []).filter((item) => item.sourceType === 'LEARNING_ANSWER')
}