import type {
  ApiErrorCategory,
  GrowthVO,
  InterviewVO,
  LearningPlanVO,
  MatchBatchVO,
  ProfileVO,
  ResumeVO,
  TaskVO,
  UserVO,
} from '@/types/workbench'

export type MockScenarioName = 'empty' | 'normal' | 'partial-error'

export type MockSourceKey =
  | 'user'
  | 'profile'
  | 'matchBatches'
  | 'matchDetail'
  | 'plans'
  | 'interviews'
  | 'growth'
  | 'tasks'
  | 'resumes'

export interface WorkbenchMockScenario {
  user: UserVO
  profile: ProfileVO | null
  matchBatches: MatchBatchVO[]
  matchDetail: Record<string, MatchBatchVO>
  plans: LearningPlanVO[]
  interviews: InterviewVO[]
  growth: GrowthVO
  tasks: TaskVO[]
  resumes: ResumeVO[]
  failures: Partial<Record<MockSourceKey, ApiErrorCategory>>
}

function isoHoursAgo(hours: number): string {
  return new Date(Date.now() - hours * 3600_000).toISOString()
}

const user: UserVO = {
  id: '10000000-0000-4000-8000-000000000001',
  nickname: '林同学',
  role: 'USER',
  createdAt: isoHoursAgo(720),
}

const profile: ProfileVO = {
  userId: user.id,
  profileVersion: 3,
  targetRole: 'JAVA_BACKEND',
  candidateType: 'CAMPUS',
  cities: ['330100', '310100', '440300'],
  facts: {
    education: 'BACHELOR',
    graduationYear: 2027,
    experienceMonths: 0,
    major: '计算机科学与技术',
    skills: [{ skillCode: 'redis', claim: '了解缓存设计', confirmed: true }],
    warnings: [],
  },
  activeResumeVersionId: '20000000-0000-4000-8000-000000000002',
  updatedAt: isoHoursAgo(30),
}

const batchId = '40000000-0000-4000-8000-000000000001'

const resume: ResumeVO = {
  id: '20000000-0000-4000-8000-000000000001',
  title: 'Java后端简历',
  latestVersion: {
    id: '20000000-0000-4000-8000-000000000002',
    resumeId: '20000000-0000-4000-8000-000000000001',
    versionNo: 2,
    fileName: 'Java后端简历.pdf',
    status: 'CONFIRMED',
    createdAt: isoHoursAgo(6),
    confirmedAt: isoHoursAgo(5),
  },
}

const batchDetail: MatchBatchVO = {
  id: batchId,
  profileVersion: 3,
  status: 'READY',
  isStale: false,
  createdAt: isoHoursAgo(4),
  items: [
    {
      id: '40000000-0000-4000-8000-000000000011',
      title: 'Java 后端开发工程师',
      eligibility: { status: 'PASS' },
      matchScore: 86,
      gaps: [{ skillCode: 'redis' }],
      source: { verificationStatus: 'VERIFIED', name: '企业官网（演示）' },
    },
    {
      id: '40000000-0000-4000-8000-000000000012',
      title: '后端开发工程师（云计算）',
      eligibility: { status: 'UNKNOWN' },
      matchScore: 95,
      gaps: [],
      source: { verificationStatus: 'UNVERIFIED' },
    },
    {
      id: '40000000-0000-4000-8000-000000000013',
      title: 'Java 服务端开发',
      eligibility: { status: 'PASS' },
      matchScore: 78,
      gaps: [{ skillCode: 'mysql' }],
      source: { verificationStatus: 'VERIFIED' },
    },
    {
      id: '40000000-0000-4000-8000-000000000014',
      title: '高级 Java 架构师',
      eligibility: { status: 'FAIL' },
      matchScore: 99,
      gaps: [],
      source: { verificationStatus: 'VERIFIED' },
    },
    {
      id: '40000000-0000-4000-8000-000000000015',
      title: '后端开发（分布式方向）',
      eligibility: { status: 'PASS' },
      matchScore: null,
      gaps: [],
      source: { verificationStatus: 'DISABLED' },
    },
  ],
}

const plan: LearningPlanVO = {
  id: '50000000-0000-4000-8000-000000000001',
  status: 'ACTIVE',
  title: 'Redis场景专项',
  createdAt: isoHoursAgo(8),
  items: [
    {
      id: '50000000-0000-4000-8000-000000000003',
      skillCode: 'redis',
      title: '缓存穿透训练',
      kind: 'PRACTICE',
      estimatedMinutes: 20,
      status: 'IN_PROGRESS',
    },
  ],
}

const interview: InterviewVO = {
  id: '60000000-0000-4000-8000-000000000001',
  status: 'WAITING_ANSWER',
  questionLimit: 5,
  createdAt: isoHoursAgo(3),
}

const growth: GrowthVO = {
  profileVersion: 3,
  skills: [{ skillCode: 'redis', score: 70, sampleCount: 1, evidenceStatus: 'LIMITED', updatedAt: isoHoursAgo(2) }],
  recentEvidence: [
    {
      id: '50000000-0000-4000-8000-000000000004',
      skillCode: 'redis',
      sourceType: 'LEARNING_ANSWER',
      sourceId: '50000000-0000-4000-8000-000000000003',
      score: 70,
      createdAt: isoHoursAgo(2),
    },
  ],
  suggestions: ['练习 Redis 失效与一致性场景'],
}

const task: TaskVO = {
  id: '70000000-0000-4000-8000-000000000001',
  type: 'JOB_MATCH',
  status: 'RUNNING',
  stage: '正在分析岗位',
  progress: 40,
  resourceId: batchId,
  createdAt: isoHoursAgo(1),
}

const emptyScenario: WorkbenchMockScenario = {
  user: { ...user, nickname: undefined },
  profile: null,
  matchBatches: [],
  matchDetail: {},
  plans: [],
  interviews: [],
  growth: { skills: [], recentEvidence: [], suggestions: [] },
  tasks: [],
  resumes: [],
  failures: {},
}

const normalScenario: WorkbenchMockScenario = {
  user,
  profile,
  matchBatches: [
    {
      id: batchId,
      profileVersion: 3,
      status: 'READY',
      isStale: false,
      items: [],
      createdAt: isoHoursAgo(4),
    },
    {
      id: '40000000-0000-4000-8000-000000000002',
      profileVersion: 2,
      status: 'READY',
      isStale: true,
      items: [],
      createdAt: isoHoursAgo(48),
    },
  ],
  matchDetail: { [batchId]: batchDetail },
  plans: [plan],
  interviews: [interview],
  growth,
  tasks: [task],
  resumes: [resume],
  failures: {},
}

const partialErrorScenario: WorkbenchMockScenario = {
  ...normalScenario,
  failures: {
    tasks: 'SERVER',
    matchBatches: 'SERVER',
    matchDetail: 'SERVER',
  },
}

const scenarios: Record<MockScenarioName, WorkbenchMockScenario> = {
  empty: emptyScenario,
  normal: normalScenario,
  'partial-error': partialErrorScenario,
}

export function getMockScenario(name?: string): WorkbenchMockScenario {
  const scenarioName = (name ?? 'normal') as MockScenarioName
  return scenarios[scenarioName] ?? normalScenario
}