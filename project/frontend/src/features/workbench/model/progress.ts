import type {
  GrowthVO,
  InterviewVO,
  LearningPlanVO,
  MatchBatchVO,
  NavigationTarget,
  ProfileVO,
  ProgressModel,
  ProgressStage,
  ResumeVO,
} from '@/types/workbench'
import { isBatchStale } from './targetJobs'

const STAGE_ACTIONS: Record<string, NavigationTarget> = {
  PROFILE: { name: 'profile', query: { section: 'goal' }, label: '完善求职画像' },
  RESUME: { name: 'resume', label: '前往简历中心' },
  MATCH: { name: 'job-match', label: '前往岗位匹配' },
  TRAINING: { name: 'training', label: '前往专项训练' },
  INTERVIEW: { name: 'interview', label: '前往模拟面试' },
  APPLICATION: { name: 'application', label: '查看投递进度（方案预览）', preview: true },
}

export interface ProgressSourceAvailability {
  profile: boolean
  resumes: boolean
  batches: boolean
  plans: boolean
  growth: boolean
  interviews: boolean
}

export interface ProgressInput {
  profile: ProfileVO | null
  resumes: ResumeVO[] | null
  batches: MatchBatchVO[] | null
  plans: LearningPlanVO[] | null
  growth: GrowthVO | null
  interviews: InterviewVO[] | null
  availability: ProgressSourceAvailability
}

function isProfileDone(profile: ProfileVO | null): boolean {
  return !!profile?.targetRole && !!profile?.candidateType
}

function isResumeDone(resumes: ResumeVO[]): boolean {
  return resumes.some((resume) => resume.latestVersion?.status === 'CONFIRMED')
}

function isMatchDone(batches: MatchBatchVO[], profile: ProfileVO | null): boolean {
  return batches.some((batch) => batch.status === 'READY' && !isBatchStale(batch, profile))
}

function trainingDone(plans: LearningPlanVO[] | null, growth: GrowthVO | null): 'DONE' | 'PENDING' | 'UNKNOWN' {
  const planHasCompleted = (plans ?? []).some((plan) => (plan.items ?? []).some((item) => item.status === 'COMPLETED'))
  if (planHasCompleted) {
    return 'DONE'
  }
  const growthHasEvidence = (growth?.recentEvidence ?? []).some((evidence) => evidence.sourceType === 'LEARNING_ANSWER')
  if (growthHasEvidence) {
    return 'DONE'
  }
  if (plans !== null || growth !== null) {
    return 'PENDING'
  }
  return 'UNKNOWN'
}

export function buildProgress(input: ProgressInput): ProgressModel {
  const { profile, resumes, batches, plans, growth, interviews, availability } = input

  const stages: ProgressStage[] = [
    {
      key: 'PROFILE',
      label: '完善求职画像',
      status: availability.profile ? (isProfileDone(profile) ? 'DONE' : 'PENDING') : 'UNKNOWN',
      note: availability.profile ? undefined : '数据加载失败，状态待确认',
      action: STAGE_ACTIONS.PROFILE,
    },
    {
      key: 'RESUME',
      label: '确认简历',
      status: availability.resumes ? (isResumeDone(resumes ?? []) ? 'DONE' : 'PENDING') : 'UNKNOWN',
      note: availability.resumes ? undefined : '数据加载失败，状态待确认',
      action: STAGE_ACTIONS.RESUME,
    },
    {
      key: 'MATCH',
      label: '完成一次岗位匹配',
      status: availability.batches ? (isMatchDone(batches ?? [], profile) ? 'DONE' : 'PENDING') : 'UNKNOWN',
      note: availability.batches ? undefined : '数据加载失败，状态待确认',
      action: STAGE_ACTIONS.MATCH,
    },
    {
      key: 'TRAINING',
      label: '完成一次专项训练',
      status: trainingDone(plans, growth),
      note: availability.plans || availability.growth ? undefined : '数据加载失败，状态待确认',
      action: STAGE_ACTIONS.TRAINING,
    },
    {
      key: 'INTERVIEW',
      label: '完成一次模拟面试',
      status: availability.interviews
        ? (interviews ?? []).some((interview) => interview.status === 'COMPLETED')
          ? 'DONE'
          : 'PENDING'
        : 'UNKNOWN',
      note: availability.interviews ? undefined : '数据加载失败，状态待确认',
      action: STAGE_ACTIONS.INTERVIEW,
    },
    {
      key: 'APPLICATION',
      label: '产生一条投递记录',
      status: 'UNKNOWN',
      note: '投递进度功能未开放',
      action: STAGE_ACTIONS.APPLICATION,
    },
  ]

  const priorityStage = stages.find((stage) => stage.status === 'PENDING')
  if (priorityStage) {
    priorityStage.priority = true
  }

  const completedCount = stages.filter((stage) => stage.status === 'DONE').length
  const blockingUnknown = stages.some((stage) => stage.key !== 'APPLICATION' && stage.status === 'UNKNOWN')
  const applicationUnknown = stages.some((stage) => stage.key === 'APPLICATION' && stage.status === 'UNKNOWN')
  const denominator = applicationUnknown ? 5 : 6

  const percent = blockingUnknown ? undefined : Math.floor((completedCount / denominator) * 100)

  return {
    percent,
    computable: percent !== undefined,
    completedCount,
    totalCount: 6,
    stages,
  }
}