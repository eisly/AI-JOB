import type {
  LearningPlanItemVO,
  LearningPlanVO,
  NavigationTarget,
  NextTaskModel,
  NextTaskSuggestion,
  ProfileVO,
  TaskVO,
} from '@/types/workbench'
import { taskStageText } from '@/utils/format'
import { MAX_GOAL_CITIES } from '../constants'
import { PLAN_ITEM_KIND_LABELS, TASK_TYPE_LABELS, translate } from './dictionaries'

const RECOMMEND_STATUS_ORDER = ['RUNNING', 'QUEUED', 'CANCEL_REQUESTED']

export function isProfileComplete(profile: ProfileVO | null | undefined): boolean {
  return !!profile?.targetRole && !!profile?.candidateType
}

function timestamp(value?: string): number {
  if (!value) {
    return 0
  }
  const time = new Date(value).getTime()
  return Number.isNaN(time) ? 0 : time
}

function sortTasks(tasks: TaskVO[]): TaskVO[] {
  return [...tasks].sort((a, b) => {
    const diff = timestamp(b.createdAt) - timestamp(a.createdAt)
    if (diff !== 0) {
      return diff
    }
    return b.id.localeCompare(a.id)
  })
}

function sortPlans(plans: LearningPlanVO[]): LearningPlanVO[] {
  return [...plans].sort((a, b) => {
    const diff = timestamp(b.createdAt) - timestamp(a.createdAt)
    if (diff !== 0) {
      return diff
    }
    return b.id.localeCompare(a.id)
  })
}

function pendingPlanItems(plans: LearningPlanVO[]): Array<{ plan: LearningPlanVO; item: LearningPlanItemVO }> {
  const result: Array<{ plan: LearningPlanVO; item: LearningPlanItemVO }> = []
  for (const plan of sortPlans(plans)) {
    const items = (plan.items ?? [])
      .filter((item) => item.status === 'IN_PROGRESS' || item.status === 'PENDING')
      .sort((a, b) => {
        if (a.status !== b.status) {
          return a.status === 'IN_PROGRESS' ? -1 : 1
        }
        return (a.id ?? '').localeCompare(b.id ?? '')
      })
    for (const item of items) {
      result.push({ plan, item })
    }
  }
  return result
}

function taskAction(task: TaskVO): NavigationTarget {
  const resource = task.resourceId ?? undefined
  switch (task.type) {
    case 'RESUME_PARSE':
      return { name: 'resume', query: resource ? { resumeId: resource } : undefined }
    case 'JOB_MATCH':
      return { name: 'jobs', query: { tab: 'matches', ...(resource ? { matchId: resource } : {}) } }
    case 'LEARNING_PLAN':
      return { name: 'training', query: resource ? { planId: resource } : undefined }
    case 'INTERVIEW_QUESTION':
      return { name: 'interview', query: resource ? { interviewId: resource } : undefined }
    case 'INTERVIEW_REPORT':
      return { name: 'interview', query: resource ? { interviewId: resource } : undefined }
    case 'GROWTH_SUMMARY':
      return { name: 'growth' }
    default:
      return { name: 'tasks', query: { taskId: task.id } }
  }
}

function buildTaskSuggestion(task: TaskVO): NextTaskSuggestion {
  const typeLabel = translate(TASK_TYPE_LABELS, task.type, '求职任务')
  return {
    kind: 'TASK',
    title: typeLabel,
    typeLabel,
    stage: taskStageText(task),
    action: taskAction(task),
  }
}

function buildPlanSuggestion(plan: LearningPlanVO, item: LearningPlanItemVO): NextTaskSuggestion {
  const typeLabel = translate(PLAN_ITEM_KIND_LABELS, item.kind, '学习计划')
  const minutes = item.estimatedMinutes
  return {
    kind: 'PLAN_ITEM',
    title: item.title?.trim() || plan.title?.trim() || '继续学习计划',
    typeLabel,
    meta: typeof minutes === 'number' && minutes >= 5 && minutes <= 120 ? `预计 ${minutes} 分钟` : undefined,
    action: {
      name: 'training',
      query: { planId: plan.id, planItemId: item.id },
      label: '继续训练',
    },
  }
}

function buildProfileSuggestion(): NextTaskSuggestion {
  return {
    kind: 'PROFILE',
    title: '完善求职画像',
    description: '完善画像后可获得个性化任务与岗位推荐。',
    action: { name: 'profile', query: { section: 'goal' }, label: '完善求职画像' },
  }
}

export interface NextTaskInput {
  tasks: TaskVO[] | null
  plans: LearningPlanVO[] | null
  profile: ProfileVO | null
  profileOk: boolean
}

export function buildNextTask(input: NextTaskInput): NextTaskModel {
  const { tasks, plans, profile, profileOk } = input
  const recentTaskFailed = tasks?.[0]?.status === 'FAILED'

  if (tasks) {
    for (const status of RECOMMEND_STATUS_ORDER) {
      const matched = sortTasks(tasks.filter((task) => task.status === status))
      if (matched.length > 0) {
        return { suggestion: buildTaskSuggestion(matched[0]), recentTaskFailed, unavailable: false }
      }
    }
  }

  if (plans) {
    const pending = pendingPlanItems(plans)
    if (pending.length > 0) {
      const first = pending[0]
      return { suggestion: buildPlanSuggestion(first.plan, first.item), recentTaskFailed, unavailable: false }
    }
  }

  if (profileOk && !isProfileComplete(profile)) {
    return { suggestion: buildProfileSuggestion(), recentTaskFailed, unavailable: false }
  }

  if (!profileOk || !tasks || !plans) {
    return { suggestion: null, recentTaskFailed, unavailable: true }
  }

  return { suggestion: null, recentTaskFailed, unavailable: false }
}

export { MAX_GOAL_CITIES }
