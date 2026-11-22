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
  /** 业务结果摘要（S03/A05）：搜索任务区分执行完成与业务结果不可用/部分失败 */
  outcome?: string
  resultCount?: number
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

export interface ResumeEvidenceFact {
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
  internships: ResumeEvidenceFact[]
  awards: ResumeEvidenceFact[]
  warnings: string[]
}

export interface UnmappedSkillDetail {
  name: string
  quote: string
  location: string
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
  parsedFacts: ResumeFacts | null
  textLayoutVersion?: string | null
  ocrPages?: number[] | null
  parseMeta?: { unmappedDetails?: UnmappedSkillDetail[]; [key: string]: unknown } | null
  createdAt: string
}

export interface ResumeFactRevisionVO {
  revisionNo: number
  profileVersion: number | null
  facts: ResumeFacts
  reason: string
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
  normalizationStatus?: 'STANDARD' | 'PENDING'
}

export interface DiagnosisItem {
  id: string
  priority: 'IMPORTANT' | 'SUGGESTION' | 'NOTICE'
  dimensionCode?: AssessmentDimensionCode | null
  checkCode?: string | null
  title: string
  issue: string
  quote: string
  quotes?: string[]
  rationale: string
  impact?: string | null
  suggestion: string
  example?: string | null
  pendingVerification?: string[]
}

export type AssessmentDimensionCode = 'EXPERIENCE_PROJECTS' | 'SKILLS_EVIDENCE' | 'CONTENT_COMPLETENESS' | 'STRUCTURE_CLARITY' | 'EXPRESSION_PROFESSIONALISM'

export interface AssessmentDimension {
  code: AssessmentDimensionCode
  label?: string
  score: number
  maxScore?: number
  rationale: string
  quote: string | null
  quotes?: string[]
  criteria?: { code: string; label: string; score: number; maxScore: number }[]
}

export interface AssessmentStrength {
  title: string
  quote: string
  quotes?: string[]
  description: string
  approach?: string | null
  contribution?: string | null
  result?: string | null
}

export interface ResumeAssessmentVO {
  ruleVersion: string
  summary?: string | null
  totalScore: number
  dimensions: AssessmentDimension[]
  strengths: AssessmentStrength[]
  assessedAt: string
}

export interface ResumeDiagnosisVO {
  id: string
  taskId?: string | null
  resumeVersionId: string
  status: 'GENERATING' | 'READY' | 'FAILED'
  items: DiagnosisItem[]
  assessment: ResumeAssessmentVO | null
  readOrderVerified?: boolean
  textLayoutVersion?: string | null
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
  fieldEvidence?: { city?: string; confirmedQuote?: string; correctionReason?: string }
  revisions?: { versionNo: number; cityCode: string | null; createdAt: string; requirements: RequirementVO | null; reason: string; sourceQuote: string | null }[]
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

/** 搜索前来源状态（配置检查，不实际联网探测）：UNCONFIGURED=服务未配置；READY_TO_TRY=已配置、检索时验证连接；UNAVAILABLE=来源暂未接入 */
export type SourceHealthStatus = 'UNAVAILABLE' | 'UNCONFIGURED' | 'READY_TO_TRY'

export interface SourceHealthItemVO {
  key: string
  name: string
  status: SourceHealthStatus
  message: string
}

export interface SourceHealthVO {
  checkedAt: string
  sources: SourceHealthItemVO[]
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
  completedAt?: string | null
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
  generationEnv?: 'PROD' | 'FAKE' | 'LEGACY' | null
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
  /** 待复习原因：UNKNOWN=回答"不会"；WRONG_ANSWER=回答错误 */
  cause?: 'UNKNOWN' | 'WRONG_ANSWER' | null
}

// ===== 专项训练 V2：一次生成一组题目（练习集）契约 =====
// 说明：以下结构为改版后前端与后端的预留字段约定。
// 后端按此契约实现 /api/v1/learning/practice-sets 系列接口前，
// 前端会自动降级为旧版"单题会话"（TEXT_SCENARIO/TEXT_KNOWLEDGE 映射为 SUBJECTIVE）。

/** 新版题型：选择题 / 填空题 / 主观题 */
export type PracticeQuestionType = 'CHOICE' | 'FILL' | 'SUBJECTIVE'

/** 旧版题型 → 新版题型映射（降级兼容用） */
export type LegacyQuestionType = 'TEXT_SCENARIO' | 'TEXT_KNOWLEDGE'

export interface PracticeOptionVO {
  /** 选项标识，如 A / B / C / D */
  key: string
  content: string
}

export interface PracticeQuestionVO {
  id: string
  /** 题目在本组中的序号（1 开始） */
  index: number
  type: PracticeQuestionType
  skillCodes: string[]
  difficulty: 'EASY' | 'MEDIUM' | 'HARD'
  stem: string
  /** 选择题选项；填空/主观题为空数组 */
  options: PracticeOptionVO[]
  /** 填空题空位数量（默认 1） */
  blankCount: number
  /** 建议作答时长（分钟） */
  estimatedMinutes: number
  /** 分值 */
  score: number
  questionVersion: number
  sourceType?: 'CURATED' | 'GENERATED'
  sourceTitle?: string | null
}

export interface PracticeTypeDistributionVO {
  /** 选择题数量 */
  choice: number
  /** 填空题数量 */
  fill: number
  /** 主观题数量 */
  subjective: number
}

/** 用户自定义出题配置（生成练习集的入参，也是练习集快照） */
export interface PracticeConfigVO {
  /** 题目总数：5 / 10 / 15 / 20 */
  totalQuestions: number
  /** 题型分布 */
  typeDistribution: PracticeTypeDistributionVO
  /** 难度：EASY/MEDIUM/HARD/MIXED */
  difficulty: 'EASY' | 'MEDIUM' | 'HARD' | 'MIXED'
  /** 训练方向，如 JAVA_BACKEND / MYSQL / ALGORITHM */
  direction: string
  planId?: string
  planItemId?: string
}

export type PracticeSetStatus =
  | 'GENERATING' | 'READY' | 'IN_PROGRESS'
  | 'SUBMITTED' | 'EVALUATING' | 'EVALUATED' | 'FAILED'

/** 一组预生成的题目（练习集） */
export interface PracticeSetVO {
  id: string
  planId: string | null
  planItemId: string | null
  title: string
  status: PracticeSetStatus
  config: PracticeConfigVO | null
  questions: PracticeQuestionVO[]
  /** 本组预计总时长（分钟） */
  totalMinutes: number
  /** 乐观锁版本号 */
  version: number
  generatedAt?: string
  evaluationTaskId?: string | null
  lastAnswerId?: string | null
}

/** 单题作答载荷 */
export interface PracticeAnswerPayload {
  questionId: string
  type: PracticeQuestionType
  /** 选择题所选选项 key */
  choiceKey?: string
  /** 填空题各空答案（按下标对应） */
  blanks?: string[]
  /** 主观题回答内容 */
  content?: string
}

/** 交卷载荷 */
export interface PracticeSubmissionVO {
  answers: PracticeAnswerPayload[]
  /** 实际用时（秒） */
  durationSeconds: number
  /** 乐观锁版本号 */
  expectedVersion: number
}

/** 单题评估结果 */
export interface PracticeQuestionResultVO {
  questionId: string
  /** 客观题判定对错；主观题为 null（走 evaluation） */
  correct: boolean | null
  /** 得分（主观题为 0-100，客观题对=满分/错=0） */
  score: number | null
  /** 客观题正确答案 */
  correctKeys?: string[]
  /** 简要点评 */
  comment?: string
  /** 主观题完整评估（复用旧评估结构） */
  evaluation?: EvaluationVO | null
}

/** 整组训练评估结果（训练反馈报告） */
export interface PracticeSetResultVO {
  id: string
  status: 'EVALUATED'
  /** 综合得分 */
  overallScore: number | null
  /** 客观题正确数 */
  objectiveCorrectCount: number
  /** 客观题总数 */
  objectiveTotalCount: number
  /** 报告摘要 */
  summary: string
  strengths: string[]
  improvements: string[]
  questionResults: PracticeQuestionResultVO[]
}

export type RepoPlatform = 'GITHUB' | 'GITEE' | 'GITCODE'

export interface RepositorySourceVO {
  platform: RepoPlatform
  path: string
  url: string
  license: string
  readmeUrl: string | null
}

export interface RepositorySourceRunVO {
  platform: RepoPlatform
  status: 'SUCCEEDED' | 'FAILED'
  reason: string | null
}

export interface ProjectRecommendationItem {
  id: string
  title: string
  summary: string
  skillCodes: string[]
  difficulty: 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED'
  estimatedWeeks: number
  reason: string
  gettingStarted: string[]
  deliverables: string[]
  primarySource: RepositorySourceVO | null
  alternateSources: RepositorySourceVO[]
  stackEvidence?: Array<{
    keyword: string
    status: 'VERIFIED' | 'DOCUMENTED'
    path: string
    url: string
  }>
}

export interface ProjectRecommendationVO {
  id: string
  mode: 'REPOSITORY' | 'LEGACY_IDEA'
  status: 'GENERATING' | 'READY' | 'FAILED'
  sourceRuns: RepositorySourceRunVO[]
  items: ProjectRecommendationItem[]
  createdAt: string
  afterBatchId?: string
  hasMore?: boolean
  searchLimitReached?: boolean
  continuationTaskId?: string
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
  primarySource: RepositorySourceVO | null
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
  /** 语音面试：USER 消息的来源；历史数据为 null，按 TEXT 处理 */
  answerSource?: 'TEXT' | 'VOICE' | null
}

export interface InterviewVO {
  id: string
  jobId: string
  resumeVersionId: string
  status: 'PREPARING' | 'WAITING_ANSWER' | 'GENERATING' | 'ENDING' | 'COMPLETED' | 'FAILED'
  /** 语音面试：会话作答模式；历史数据为 TEXT */
  mode?: 'TEXT' | 'VOICE'
  questionLimit: number
  version: number
  pendingTaskId: string | null
  messages: MessageVO[]
  createdAt: string
  /** U09/U11：岗位名（历史列表主标题）、报告完成类型与实际有效回答数 */
  jobTitle?: string | null
  completionType?: 'NORMAL' | 'EARLY_END' | 'NO_VALID_ANSWER' | null
  answeredCount?: number | null
}

export type VoiceTranscriptionStatus = 'PENDING' | 'SUCCEEDED' | 'EMPTY' | 'FAILED' | 'DISCARDED' | 'EXPIRED'

export interface VoiceCapabilityVO {
  asrEnabled: boolean
  ttsEnabled: boolean
  consentRequired: boolean
  consentVersion: string
  consented: boolean
  maxDurationSeconds: number
  maxAudioBytes: number
  allowedMimeTypes: string[]
  transcriptionTtlSeconds: number
}

export interface VoiceTranscriptionVO {
  id: string
  status: VoiceTranscriptionStatus
  text: string | null
  questionMessageId: string
  interviewVersion: number
  durationMs: number
  attempt?: number
  expiresAt: string
  reused?: boolean
}

/** 与后端 VoiceReasons 保持一致：失效原因固定为这组值，禁止由状态拼接。 */
export type VoiceTranscriptionReason =
  | 'QUESTION_CHANGED'
  | 'TRANSCRIPTION_NOT_FOUND'
  | 'TRANSCRIPTION_CONSUMED'
  | 'TRANSCRIPTION_DISCARDED'
  | 'TRANSCRIPTION_EXPIRED'
  | 'SUPERSEDED'

export interface VoiceConsentVO {
  agreed: boolean
  policyVersion: string
  agreedAt: string | null
}

export interface InterviewReportVO {
  interviewId: string
  reportVersion: number
  evaluation: EvaluationVO
  gaps: GapVO[]
  evidenceIds: string[]
  skillEvidenceStatus?: 'AVAILABLE' | 'NO_SKILL_SCORES' | 'NO_VALID_ANSWER'
  skillEvidenceMessage?: string
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
  /** 建议依据的画像版本（G03：画像变化后旧建议仍返回并标注过期） */
  suggestionProfileVersion?: number | null
  suggestionStale?: boolean
  suggestionStaleReason?: string | null
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
