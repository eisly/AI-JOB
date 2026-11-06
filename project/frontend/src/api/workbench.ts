import { apiGet, ApiRequestError, type QueryParams, type RequestOptions, toApiError } from './request'
import type {
  ApiResult,
  GrowthVO,
  InterviewVO,
  LearningPlanVO,
  MatchBatchVO,
  PageResult,
  ProfileVO,
  ResumeVO,
  TaskVO,
  UserVO,
} from '@/types/workbench'

type MockSourceKey =
  | 'user'
  | 'profile'
  | 'matchBatches'
  | 'matchDetail'
  | 'plans'
  | 'interviews'
  | 'growth'
  | 'tasks'
  | 'resumes'

type MockModule = typeof import('./mock/workbench.mock')

let mockModulePromise: Promise<MockModule> | null = null

function isMockEnabled(): boolean {
  return import.meta.env.DEV === true && import.meta.env.VITE_WORKBENCH_MOCK === 'true'
}

async function loadMockModule(): Promise<MockModule> {
  if (!mockModulePromise) {
    mockModulePromise = import('./mock/workbench.mock')
  }
  return mockModulePromise
}

async function requestOrMock<T>(
  key: MockSourceKey,
  path: string,
  query: QueryParams | undefined,
  options: RequestOptions | undefined,
  pick: (scenario: Awaited<ReturnType<MockModule['getMockScenario']>>) => T,
): Promise<ApiResult<T>> {
  if (isMockEnabled()) {
    const module = await loadMockModule()
    const scenario = module.getMockScenario(import.meta.env.VITE_WORKBENCH_MOCK_SCENARIO)
    const failure = scenario.failures[key]
    if (failure) {
      throw new ApiRequestError({ category: failure })
    }
    return { data: pick(scenario), traceId: 'mock-trace' }
  }
  return apiGet<T>(path, query, options)
}

export function getCurrentUser(options?: RequestOptions): Promise<ApiResult<UserVO>> {
  return requestOrMock('user', '/api/v1/users/me', undefined, options, (scenario) => scenario.user)
}

export async function getProfile(options?: RequestOptions): Promise<ApiResult<ProfileVO | null>> {
  try {
    return await requestOrMock<ProfileVO | null>('profile', '/api/v1/profile', undefined, options, (scenario) => scenario.profile)
  } catch (error) {
    if (toApiError(error).category === 'NOT_FOUND') {
      return { data: null }
    }
    throw error
  }
}

export function getMatchBatches(options?: RequestOptions): Promise<ApiResult<PageResult<MatchBatchVO>>> {
  return requestOrMock('matchBatches', '/api/v1/job-matches', { current: 1, size: 20 }, options, (scenario) => ({
    total: scenario.matchBatches.length,
    records: scenario.matchBatches,
    current: 1,
    size: 20,
  }))
}

export function getMatchBatchDetail(matchId: string, options?: RequestOptions): Promise<ApiResult<MatchBatchVO>> {
  return requestOrMock(
    'matchDetail',
    `/api/v1/job-matches/${encodeURIComponent(matchId)}`,
    undefined,
    options,
    (scenario) => scenario.matchDetail[matchId] ?? { id: matchId, items: [] },
  )
}

export function getLearningPlans(options?: RequestOptions): Promise<ApiResult<PageResult<LearningPlanVO>>> {
  return requestOrMock('plans', '/api/v1/learning/plans', { current: 1, size: 50 }, options, (scenario) => ({
    total: scenario.plans.length,
    records: scenario.plans,
    current: 1,
    size: 50,
  }))
}

export function getInterviews(options?: RequestOptions): Promise<ApiResult<PageResult<InterviewVO>>> {
  return requestOrMock('interviews', '/api/v1/interviews', { current: 1, size: 50 }, options, (scenario) => ({
    total: scenario.interviews.length,
    records: scenario.interviews,
    current: 1,
    size: 50,
  }))
}

export function getGrowthSummary(options?: RequestOptions): Promise<ApiResult<GrowthVO>> {
  return requestOrMock('growth', '/api/v1/growth', undefined, options, (scenario) => scenario.growth)
}

export function getRecentTasks(options?: RequestOptions): Promise<ApiResult<PageResult<TaskVO>>> {
  return requestOrMock('tasks', '/api/v1/tasks', { current: 1, size: 5 }, options, (scenario) => ({
    total: scenario.tasks.length,
    records: scenario.tasks,
    current: 1,
    size: 5,
  }))
}

export function getResumes(options?: RequestOptions): Promise<ApiResult<PageResult<ResumeVO>>> {
  return requestOrMock('resumes', '/api/v1/resumes', { current: 1, size: 20 }, options, (scenario) => ({
    total: scenario.resumes.length,
    records: scenario.resumes,
    current: 1,
    size: 20,
  }))
}