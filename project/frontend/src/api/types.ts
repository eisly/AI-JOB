export interface CommonResult<T> {
  code: number
  msg: string
  data: T
  traceId: string
  error?: {
    key: string
    retryable: boolean
    details?: Record<string, unknown>
  } | null
}

export interface Page<T> {
  total: number
  records: T[]
  current: number
  size: number
}

export interface PageQuery {
  current?: number
  size?: number
}

export interface UserVO {
  id: string
  email: string
  nickname: string
  role: 'USER' | 'ADMIN'
  createdAt: string
}

export interface TokenVO {
  accessToken: string
  expiresIn: number
  user: UserVO
}

export type TaskStatus = 'QUEUED' | 'RUNNING' | 'CANCEL_REQUESTED' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'EXPIRED'

export interface TaskResult {
  resourceType: string
  resourceId: string
  version: number
}

export interface TaskError {
  code: string
  message: string
  retryable: boolean
}

export interface TaskVO {
  id: string
  type: string
  status: TaskStatus
  stage: string | null
  progress: number | null
  resourceId: string | null
  result: TaskResult | null
  error: TaskError | null
  inputVersions: Record<string, string | number | null>
  lastEventId: number
  createdAt: string
  finishedAt: string | null
  retryOfTaskId?: string | null
}

export interface TaskEvent<T = Record<string, unknown>> {
  taskId: string
  seq: number
  payload: T
}

export interface SkillFact {
  skillCode: string
  claim: string
  quote: string
  location: string
  confirmed: boolean
}

export interface ResumeFacts {
  education: 'HIGH_SCHOOL' | 'ASSOCIATE' | 'BACHELOR' | 'MASTER' | 'DOCTOR' | null
  graduationYear: number | null
  experienceMonths: number | null
  major: string | null
  skills: SkillFact[]
  warnings: string[]
}

export interface ResumeVersionVO {
  id: string
  resumeId: string
  versionNo: number
  fileName: string
  mimeType: string
  sizeBytes: number
  status: 'UPLOADED' | 'PARSING' | 'PARSED' | 'CONFIRMED' | 'FAILED' | 'DELETED'
  facts: ResumeFacts | null
  createdAt: string
}

export interface ResumeVO {
  id: string
  title: string
  latestVersion: ResumeVersionVO
}

export interface DownloadVO {
  url: string
  expiresAt: string
}

export interface ProfileVO {
  userId: string
  profileVersion: number
  targetRole: string
  candidateType: 'INTERNSHIP' | 'CAMPUS' | 'EXPERIENCED'
  cities: string[]
  minMonthlySalaryCents: number | null
  maxMonthlySalaryCents: number | null
  facts: ResumeFacts
  activeResumeVersionId: string | null
  updatedAt: string
}

export interface ProfilePreferencesInput {
  expectedProfileVersion: number
  targetRole: ProfileVO['targetRole']
  candidateType: ProfileVO['candidateType']
  cities: string[]
  minMonthlySalaryCents: number | null
  maxMonthlySalaryCents: number | null
}

export interface SkillVO {
  code: string
  name: string
  parentCode: string | null
  enabled: boolean
}

export interface DiagnosisItem {
  id: string
  priority: 'IMPORTANT' | 'SUGGESTION' | 'NOTICE'
  title: string
  issue: string
  quote: string
  rationale: string
  suggestion: string
}

export interface ResumeDiagnosisVO {
  id: string
  resumeVersionId: string
  status: 'GENERATING' | 'READY' | 'FAILED'
  items: DiagnosisItem[]
  createdAt: string
}

export interface OptimizationSection {
  sectionKey: string
  title: string
  originalText: string
  suggestedText: string
  content?: string
  sourceQuotes: string[]
}

export interface ResumeOptimizationVO {
  id: string
  resumeVersionId: string
  jobId: string
  diagnosisId: string | null
  status: 'GENERATING' | 'READY' | 'ACCEPTED' | 'FAILED'
  title: string | null
  sections: OptimizationSection[]
  acceptedAt: string | null
  createdAt: string
}

export interface JobSourceVO {
  id: string
  name: string
  type: 'OFFICIAL' | 'AUTHORIZED' | 'PUBLIC_SEARCH' | 'USER_INPUT'
  baseUrl: string | null
  verificationStatus: 'VERIFIED' | 'UNVERIFIED' | 'DISABLED'
  verifiedAt: string | null
}

export interface RequirementVO {
  educationMin: ResumeFacts['education']
  graduationYearMin: number | null
  graduationYearMax: number | null
  experienceMonthsMin: number | null
  skills: string[]
  requiredSkillLevels: Record<string, number>
  quotes: Record<string, string>
}

export interface JobVO {
  id: string
  jobVersion: number
  title: string
  companyName: string
  cityCode: string | null
  candidateType: ProfileVO['candidateType']
  roleCode: ProfileVO['targetRole'] | null
  minMonthlySalaryCents: number | null
  maxMonthlySalaryCents: number | null
  jdText: string
  source: JobSourceVO
  sourceUrl: string | null
  status: 'DRAFT' | 'ANALYZING' | 'ACTIVE' | 'CLOSED' | 'FAILED'
  verifiedAt: string | null
  requirements: RequirementVO | null
  isFavorite: boolean
}

export interface EligibilityCheckVO {
  field: string
  result: 'PASS' | 'FAIL' | 'UNKNOWN'
  actual: string | null
  expected: string | null
  reason: string
  sourceQuote: string | null
}

export interface ScoreComponentVO {
  key: 'skill' | 'semantic' | 'preference'
  value: number | null
  weight: number
  provenance: string
}

export interface GapVO {
  skillCode: string
  currentScore: number | null
  targetScore: number | null
  gap: number | null
  evidenceStatus: 'MISSING' | 'LIMITED' | 'SUFFICIENT'
  evidenceIds: string[]
  suggestion: string
}

export interface MatchItemVO {
  id: string
  jobId: string
  jobVersion: number
  title: string
  eligibility: {
    status: 'PASS' | 'FAIL' | 'UNKNOWN'
    ruleVersion: string
    checks: EligibilityCheckVO[]
  }
  matchScore: number | null
  scoreVersion: string
  components: ScoreComponentVO[]
  gaps: GapVO[]
  reasons: string[]
  source: JobSourceVO
  sourceUrl: string | null
  verifiedAt: string | null
}

export interface MatchBatchVO {
  id: string
  taskId: string
  profileVersion: number
  status: 'GENERATING' | 'READY' | 'FAILED'
  isStale: boolean
  items: MatchItemVO[]
  createdAt: string
}

export type CompanySize =
  | 'SIZE_0_20'
  | 'SIZE_21_99'
  | 'SIZE_100_499'
  | 'SIZE_500_999'
  | 'SIZE_1000_9999'
  | 'SIZE_10000_PLUS'
  | 'SIZE_UNKNOWN'

export interface MatchItemOptionVO {
  matchItemId: string
  batchId: string
  jobId: string
  jobVersion: number
  title: string
  companyName: string | null
  cityCode: string | null
  matchScore: number | null
  eligibilityStatus: 'PASS' | 'UNKNOWN'
  reasons: string[]
  sourceName: string | null
  sourceUrl: string | null
  verification: string | null
  createdAt: string
}

export type SearchSourceStatus = 'AVAILABLE' | 'EMPTY' | 'PARTIAL' | 'FAILED' | 'UNAVAILABLE' | 'SKIPPED'

export interface JobSearchSourceStatusVO {
  key: string
  name: string
  status: SearchSourceStatus
  count: number
  message: string
  httpStatus: number | null
  durationMs: number
  verification: 'VERIFIED' | 'UNVERIFIED'
  requiresCredentials: boolean
  verificationCounts?: { queries?: number; discovered?: number; checked?: number; confirmedOpen?: number; closed?: number; unknown?: number }
}

export interface JobSearchSummaryVO {
  rawCount: number
  validCount: number
  uniqueCount: number
  droppedCount: number
  mergedCount: number
  multiSourceCount: number
  unknownSizeCount: number
  possiblyExpiredCount: number
  bySource: Record<string, number>
  warnings: string[]
}

export interface JobSearchVO {
  id: string
  taskId: string
  status: 'GENERATING' | 'READY' | 'FAILED'
  profileVersion: number
  resumeVersionId: string
  filters: Record<string, unknown>
  queryPlan: Record<string, unknown> | null
  sources: JobSearchSourceStatusVO[]
  summary: JobSearchSummaryVO | null
  isStale: boolean
  staleReason: string | null
  createdAt: string
  updatedAt: string
}

export interface SearchAliasVO {
  sourceKey: string
  sourceName: string | null
  sourceType: string
  sourceUrl: string
  publishedAt: string | null
  fetchedAt: string | null
  verification: 'VERIFIED' | 'UNVERIFIED'
}

export interface JobFieldSource {
  sourceUrl: string
  sourceName: string
  sourceType: string
  rawText: string
  fetchedAt: string
}

export interface JobSearchItemVO extends MatchItemVO {
  companySizeText?: string | null
  fieldSources?: Record<string, JobFieldSource> | null
  batchId: string
  rankNo: number
  companyName: string
  cityCode: string | null
  cityName?: string | null
  candidateType: ProfileVO['candidateType']
  companySize: CompanySize
  publishedAt: string | null
  updatedAt?: string | null
  fetchedAt: string | null
  recruitmentStatus?: 'OPEN' | 'PROBABLE' | 'CLOSED' | 'UNKNOWN' | null
  recruitmentCheckedAt?: string | null
  recruitmentEvidence?: 'OFFICIAL_LIST' | 'DETAIL_APPLY_LINK' | 'DETAIL_TEXT' | 'SEARCH_SNIPPET' | 'OFFICIAL_CLOSED' | 'EXPIRED' | null
  verificationStatus?: 'ACTIVE_CONFIRMED' | 'ACTIVE_PROBABLE' | 'UNKNOWN' | 'CLOSED' | null
  verificationReason?: string | null
  verificationEvidence?: 'OFFICIAL_LIST' | 'DETAIL_APPLY_LINK' | 'DETAIL_TEXT' | 'SEARCH_SNIPPET' | 'OFFICIAL_CLOSED' | 'EXPIRED' | null
  verificationCheckedAt?: string | null
  verificationHttpStatus?: number | null
  searchProviders?: string[]
  minMonthlySalaryCents: number | null
  maxMonthlySalaryCents: number | null
  salaryText: string | null
  experienceText: string | null
  experienceMonthsMin: number | null
  educationText: string | null
  educationMin: ResumeFacts['education']
  verification: 'VERIFIED' | 'UNVERIFIED'
  warnings: string[]
  aliases: SearchAliasVO[]
  fingerprint: string
  isFavorite: boolean
}

export interface JobSearchCreateInput {
  expectedProfileVersion: number
  keyword?: string
  cities?: string[]
  candidateTypes?: string[]
  companySizes?: CompanySize[]
  includeUnknownSize?: boolean
  maxResults?: number
  sources?: string[]
}

export interface ResourceRef {
  documentId: string
  documentVersion: number
  chunkId: string
  quote: string
}

export interface ResourceLinkVO {
  label: string
  url: string
}

export type PlanCategory = 'BASIC' | 'RESUME_PROJECT' | 'GITHUB'
export type PlanItemEvidenceStatus = 'ASSESSED' | 'PENDING'

export interface PlanItemVO {
  id: string
  skillCode: string
  title: string
  kind: 'READING' | 'PRACTICE' | 'INTERVIEW'
  category: PlanCategory
  objective: string
  reason: string
  method: string
  completionCriteria: string
  evidenceStatus: PlanItemEvidenceStatus
  evidenceQuote: string | null
  resourceLinks: ResourceLinkVO[]
  estimatedMinutes: number
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED'
  resourceRefs: ResourceRef[]
}

export interface PlanGithubBasisVO {
  status: 'PENDING' | 'AVAILABLE' | 'UNAVAILABLE' | 'DISABLED'
  candidateCount?: number
  fetchedAt?: string | null
  reason?: string | null
}

export interface PlanBasisVO {
  source: 'MATCH' | 'RESUME'
  summary?: string
  resumeVersionId: string | null
  resumeVersionNo?: number | null
  targetDirection: string | null
  matchItemId: string | null
  matchTitle: string | null
  matchReferenced?: boolean
  dailyMinutes: number
  days: number
  generatedAt: string
  limitations?: string[]
  github?: PlanGithubBasisVO
}

export interface MatchSummaryVO {
  id: string
  title: string | null
  companyName: string | null
  matchScore: number | null
}

export interface LearningPlanVO {
  id: string
  source: 'MATCH' | 'RESUME'
  matchItemId: string | null
  resumeVersionId: string | null
  targetDirection: string | null
  supersedesPlanId: string | null
  generationNo: number
  profileVersion: number
  dailyMinutes: number
  days: number
  status: 'GENERATING' | 'ACTIVE' | 'COMPLETED' | 'FAILED'
  title: string
  generatedAt: string
  basis: PlanBasisVO | null
  matchSummary?: MatchSummaryVO | null
  items: PlanItemVO[]
}

export interface QuestionVO {
  id: string
  skillCodes: string[]
  difficulty: 'EASY' | 'MEDIUM' | 'HARD'
  type: 'TEXT_SCENARIO' | 'TEXT_KNOWLEDGE'
  stem: string
  sourceType: 'CURATED' | 'GENERATED'
  sourceTitle: string | null
  questionVersion: number
}

export interface LearningSessionVO {
  id: string
  planId: string
  planItemId: string
  status: 'PREPARING' | 'ACTIVE' | 'EVALUATING' | 'COMPLETED' | 'FAILED'
  question: QuestionVO | null
  lastAnswerId: string | null
  version: number
}

export interface DimensionVO {
  key: string
  score: number
  weight: number
  comment: string
}

export interface EvaluationVO {
  overallScore: number | null
  dimensions: DimensionVO[]
  strengths: string[]
  improvements: string[]
  referenceAnswer: string | null
  rubricVersion: string
  modelVersion: string
  promptVersion: string
  evidenceStatus: 'VALID' | 'INSUFFICIENT' | 'INVALID'
}

export interface LearningAnswerVO {
  id: string
  sessionId: string
  content: string
  status: 'EVALUATING' | 'EVALUATED' | 'FAILED'
  taskId: string
  evaluation: EvaluationVO | null
}

export interface MistakeVO {
  id: string
  question: QuestionVO
  lastAnswerId: string
  wrongCount: number
  resolved: boolean
}

export interface ProjectRecommendationItem {
  id: string
  title: string
  summary: string
  skillCodes: string[]
  difficulty: 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED'
  estimatedWeeks: number
  reason: string
  deliverables: string[]
}

export interface ProjectRecommendationVO {
  id: string
  status: 'GENERATING' | 'READY' | 'FAILED'
  items: ProjectRecommendationItem[]
  createdAt: string
}

export interface ProjectTaskVO {
  id: string
  title: string
  description: string
  estimatedHours: number
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED'
}

export interface ProjectMilestoneVO {
  id: string
  seq: number
  title: string
  description: string
  tasks: ProjectTaskVO[]
}

export interface ProjectDeliverableVO {
  id: string
  taskId: string
  title: string
  url: string | null
  notes: string | null
  createdAt: string
}

export interface ProjectVO {
  id: string
  recommendationItemId: string
  taskId: string
  title: string | null
  summary: string | null
  status: 'GENERATING' | 'ACTIVE' | 'COMPLETED' | 'FAILED'
  progress: number
  weeklyHours: number
  skillCodes: string[]
  milestones: ProjectMilestoneVO[]
  deliverables: ProjectDeliverableVO[]
  version: number
  createdAt: string
  updatedAt: string
}

export type ProjectSummaryVO = Omit<ProjectVO, 'milestones' | 'deliverables'>

export interface MessageVO {
  id: string
  seq: number
  role: 'ASSISTANT' | 'USER'
  content: string
  questionNo: number
  followUpNo: number
  createdAt: string
}

export interface InterviewVO {
  id: string
  jobId: string
  resumeVersionId: string
  status: 'PREPARING' | 'WAITING_ANSWER' | 'GENERATING' | 'ENDING' | 'COMPLETED' | 'FAILED'
  questionLimit: number
  version: number
  pendingTaskId: string | null
  messages: MessageVO[]
  createdAt: string
}

export interface InterviewReportVO {
  interviewId: string
  reportVersion: number
  evaluation: EvaluationVO
  gaps: GapVO[]
  evidenceIds: string[]
  answeredQuestionCount: number
  completionType: 'NORMAL' | 'EARLY_END' | 'NO_VALID_ANSWER'
}

export interface EvidenceVO {
  id: string
  skillCode: string
  sourceType: 'RESUME_CLAIM' | 'LEARNING_ANSWER' | 'INTERVIEW_ANSWER'
  sourceId: string
  score: number | null
  confidence: number
  status: 'VALID' | 'REVOKED'
  rubricVersion: string | null
  createdAt: string
}

export interface SkillSnapshotVO {
  skillCode: string
  score: number | null
  sampleCount: number
  evidenceStatus: 'MISSING' | 'LIMITED' | 'SUFFICIENT'
  formulaVersion: string
  updatedAt: string
}

export interface GrowthVO {
  profileVersion: number
  skills: SkillSnapshotVO[]
  recentEvidence: EvidenceVO[]
  suggestions: string[]
  suggestionTaskId: string | null
}
export type ApplicationStatus = 'PLANNED' | 'APPLIED' | 'INTERVIEWING' | 'CLOSED'

export interface ApplicationVO {
  id: string
  jobId: string | null
  companyName: string
  jobTitle: string
  cityCode: string | null
  education: string | null
  salaryText: string | null
  description: string | null
  status: ApplicationStatus
  plannedDate: string | null
  appliedDate: string | null
  interviewDate: string | null
  closedDate: string | null
  note: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface ApplicationSummaryVO {
  total: number
  counts: Record<ApplicationStatus, number>
}

export interface ApplicationCreateInput {
  jobId?: string | null
  companyName: string
  jobTitle: string
  cityCode?: string | null
  education?: string | null
  salaryText?: string | null
  description?: string | null
  status?: ApplicationStatus
  plannedDate?: string | null
  appliedDate?: string | null
  interviewDate?: string | null
  closedDate?: string | null
  note?: string | null
}

export type ApplicationUpdateInput = Omit<ApplicationCreateInput, 'jobId'> & { expectedVersion: number }
