export type RegionKey = 'identity' | 'goal' | 'nextTask' | 'targetJobs' | 'progress' | 'activities'

export type RegionStatus = 'IDLE' | 'LOADING' | 'READY' | 'EMPTY' | 'ERROR' | 'REFRESHING'

export type ApiErrorCategory =
  | 'TIMEOUT'
  | 'NETWORK'
  | 'RATE_LIMIT'
  | 'AUTH_EXPIRED'
  | 'NOT_FOUND'
  | 'SERVER'
  | 'UNKNOWN'
  | 'CANCELLED'

export interface ApiError {
  category: ApiErrorCategory
  httpStatus?: number
  businessCode?: number
  traceId?: string
  requestId?: string
}

export type WorkbenchError = ApiError

export interface ApiResult<T> {
  data: T
  traceId?: string
}

export interface CommonResult<T> {
  code: number
  msg?: string
  data: T
  traceId?: string
}

export interface PageResult<T> {
  total: number
  records: T[]
  current: number
  size: number
}

export interface RegionState<T> {
  status: RegionStatus
  data?: T
  error?: ApiError
  updatedAt?: number
  retryCount: number
  fromCache?: boolean
}

export interface RegionDataMap {
  identity: UserVO
  goal: GoalSummary
  nextTask: NextTaskModel
  targetJobs: TargetJobsModel
  progress: ProgressModel
  activities: ActivityItem[]
}

export type RegionData<K extends RegionKey> = RegionDataMap[K]

export interface WorkbenchState {
  identity: RegionState<UserVO>
  goal: RegionState<GoalSummary>
  nextTask: RegionState<NextTaskModel>
  targetJobs: RegionState<TargetJobsModel>
  progress: RegionState<ProgressModel>
  activities: RegionState<ActivityItem[]>
  refreshing: boolean
  refreshedAt?: number
  generation: number
}

/** 用户 */
export interface UserVO {
  id: string
  email?: string
  nickname?: string
  role?: string
  createdAt?: string
}

/** 求职画像 */
export interface ResumeSkillVO {
  skillCode?: string
  claim?: string
  quote?: string
  location?: string
  confirmed?: boolean
}

export interface ResumeFactsVO {
  education?: string | null
  graduationYear?: number | null
  experienceMonths?: number | null
  major?: string | null
  skills?: ResumeSkillVO[]
  warnings?: string[]
}

export interface ProfileVO {
  userId?: string
  profileVersion?: number
  targetRole?: string
  candidateType?: string
  cities?: string[]
  minMonthlySalaryCents?: number | null
  maxMonthlySalaryCents?: number | null
  facts?: ResumeFactsVO
  activeResumeVersionId?: string | null
  updatedAt?: string
}

/** 简历 */
export interface ResumeVersionVO {
  id: string
  resumeId?: string
  versionNo?: number
  fileName?: string
  mimeType?: string
  sizeBytes?: number
  status?: string
  facts?: ResumeFactsVO
  createdAt?: string
  confirmedAt?: string
}

export interface ResumeVO {
  id: string
  title?: string
  latestVersion?: ResumeVersionVO | null
}

/** 岗位匹配 */
export interface EligibilityCheckVO {
  field?: string
  result?: string
  actual?: string
  expected?: string
  reason?: string
  sourceQuote?: string
}

export interface EligibilityVO {
  status?: string
  ruleVersion?: string
  checks?: EligibilityCheckVO[]
}

export interface MatchComponentVO {
  key?: string
  value?: number
  weight?: number
  provenance?: string
}

export interface MatchGapVO {
  skillCode?: string
  currentScore?: number | null
  targetScore?: number | null
  gap?: number | null
  evidenceStatus?: string
  evidenceIds?: string[]
  suggestion?: string
}

export interface MatchSourceVO {
  id?: string
  name?: string
  type?: string
  baseUrl?: string
  verificationStatus?: string
  verifiedAt?: string
}

export interface MatchItemVO {
  id: string
  jobId?: string
  jobVersion?: number
  title?: string
  companyName?: string
  eligibility?: EligibilityVO
  matchScore?: number | null
  scoreVersion?: string
  components?: MatchComponentVO[]
  gaps?: MatchGapVO[]
  reasons?: string[]
  source?: MatchSourceVO
  sourceUrl?: string
  verifiedAt?: string
}

export interface MatchBatchVO {
  id: string
  taskId?: string
  profileVersion?: number
  status?: string
  isStale?: boolean
  items?: MatchItemVO[]
  createdAt?: string
}

/** 学习计划 */
export interface LearningPlanItemVO {
  id: string
  skillCode?: string
  title?: string
  kind?: string
  estimatedMinutes?: number
  status?: string
  resourceRefs?: string[]
}

export interface LearningPlanVO {
  id: string
  matchItemId?: string
  profileVersion?: number
  status?: string
  title?: string
  items?: LearningPlanItemVO[]
  createdAt?: string
}

/** 模拟面试 */
export interface InterviewMessageVO {
  id?: string
  seq?: number
  role?: string
  content?: string
  questionNo?: number
  followUpNo?: number
  createdAt?: string
}

export interface InterviewVO {
  id: string
  jobId?: string
  resumeVersionId?: string
  status?: string
  questionLimit?: number
  questionCountRule?: 'MAIN_ONLY' | 'TOTAL_ASKED' | null
  askedQuestionCount?: number | null
  remainingQuestionCount?: number | null
  version?: number
  pendingTaskId?: string | null
  messages?: InterviewMessageVO[]
  createdAt?: string
  endedAt?: string
  completedAt?: string
}

/** 成长中心 */
export interface SkillSnapshotVO {
  skillCode?: string
  score?: number
  sampleCount?: number
  evidenceStatus?: string
  formulaVersion?: string
  updatedAt?: string
}

export interface EvidenceVO {
  id: string
  skillCode?: string
  sourceType?: string
  sourceId?: string
  score?: number
  confidence?: number
  status?: string
  rubricVersion?: string
  createdAt?: string
}

export interface GrowthVO {
  profileVersion?: number
  skills?: SkillSnapshotVO[]
  recentEvidence?: EvidenceVO[]
  suggestions?: string[]
  suggestionTaskId?: string | null
}

/** 任务中心 */
export interface TaskErrorVO {
  code?: string
  message?: string
  retryable?: boolean
}

export interface TaskVO {
  id: string
  type?: string
  status?: string
  stage?: string | null
  progress?: number | null
  resourceId?: string | null
  result?: unknown
  error?: TaskErrorVO | null
  inputVersions?: Record<string, string>
  lastEventId?: number
  createdAt?: string
  finishedAt?: string | null
}

/** 派生展示模型 */
export interface GoalSummary {
  targetRoleLabel: string
  cityLabels: string[]
  candidateTypeLabel: string
  isEmpty: boolean
}

export type NextTaskKind = 'TASK' | 'PLAN_ITEM' | 'PROFILE'

export interface NavigationTarget {
  name: string
  query?: Record<string, string>
  label?: string
  preview?: boolean
  fallback?: NavigationTarget
}

export interface NextTaskSuggestion {
  kind: NextTaskKind
  title: string
  typeLabel?: string
  stage?: string
  progress?: number
  meta?: string
  description?: string
  action: NavigationTarget
}

export interface NextTaskModel {
  suggestion: NextTaskSuggestion | null
  recentTaskFailed: boolean
  unavailable: boolean
}

export type TargetJobsPhase = 'READY' | 'GENERATING' | 'FAILED' | 'EMPTY'

export type TargetJobsEmptyReason = 'NO_RECORD' | 'ALL_INELIGIBLE'

export interface TargetJobCard {
  id: string
  title: string
  companyLabel: string
  matchScore?: number
  eligibilityLabel: string
  sourceLabel?: string
  gapLabels: string[]
  action: NavigationTarget
}

export interface TargetJobsModel {
  phase: TargetJobsPhase
  stale: boolean
  jobs: TargetJobCard[]
  emptyReason?: TargetJobsEmptyReason
  batchId?: string
}

export type ProgressStageKey = 'PROFILE' | 'RESUME' | 'MATCH' | 'TRAINING' | 'INTERVIEW' | 'APPLICATION'

export type StageStatus = 'DONE' | 'PENDING' | 'UNKNOWN'

export interface ProgressStage {
  key: ProgressStageKey
  label: string
  status: StageStatus
  note?: string
  priority?: boolean
  action?: NavigationTarget
}

export interface ProgressModel {
  percent?: number
  computable: boolean
  completedCount: number
  totalCount: number
  stages: ProgressStage[]
}

export type ActivityType = 'RESUME_CONFIRMED' | 'MATCH_READY' | 'TRAINING_COMPLETED' | 'INTERVIEW_COMPLETED' | 'GROWTH_SUGGESTION'

export interface ActivityItem {
  id: string
  type: ActivityType
  typeLabel: string
  summary: string
  occurredAt?: string
  target?: NavigationTarget
}