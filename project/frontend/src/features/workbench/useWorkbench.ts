import { onBeforeUnmount, reactive } from 'vue'
import { useRouter } from 'vue-router'
import * as workbenchApi from '@/api/workbench'
import { toApiError, type RequestOptions } from '@/api/request'
import type {
  ActivityItem,
  ApiError,
  ApiResult,
  GoalSummary,
  GrowthVO,
  InterviewVO,
  LearningPlanVO,
  MatchBatchVO,
  NextTaskModel,
  PageResult,
  ProfileVO,
  ProgressModel,
  RegionKey,
  RegionState,
  RegionStatus,
  ResumeVO,
  TaskVO,
  TargetJobsModel,
  UserVO,
  WorkbenchState,
} from '@/types/workbench'
import { REQUEST_TIMEOUT_MS, SNAPSHOT_KEY, SNAPSHOT_SCHEMA_VERSION } from './constants'
import { buildActivities } from './model/activities'
import { buildGoalSummary } from './model/goalSummary'
import { buildNextTask } from './model/nextTask'
import { buildProgress } from './model/progress'
import { buildTargetJobsModel, selectLatestBatch } from './model/targetJobs'

type SourceKey = 'user' | 'profile' | 'matchBatches' | 'plans' | 'interviews' | 'growth' | 'tasks' | 'resumes'

type SourceOutcome<T> = { ok: true; data: T; traceId?: string } | { ok: false; error: ApiError }

interface SourcePool {
  get: <T>(key: SourceKey, load: (options: RequestOptions) => Promise<ApiResult<T>>) => Promise<SourceOutcome<T>>
}

interface LoadContext {
  success: boolean
}

interface SnapshotRegion<T> {
  status: 'READY' | 'EMPTY'
  data?: T
  updatedAt?: number
}

interface Snapshot {
  userId: string
  savedAt: number
  schemaVersion: number
  state: {
    goal?: SnapshotRegion<GoalSummary>
    nextTask?: SnapshotRegion<NextTaskModel>
    targetJobs?: SnapshotRegion<TargetJobsModel>
    progress?: SnapshotRegion<ProgressModel>
    activities?: SnapshotRegion<ActivityItem[]>
    refreshedAt?: number
  }
}

function emptyRegion<T>(): RegionState<T> {
  return { status: 'IDLE', retryCount: 0 }
}

function createPool(controller: AbortController): SourcePool {
  const cache = new Map<SourceKey, Promise<SourceOutcome<unknown>>>()

  function get<T>(key: SourceKey, load: (options: RequestOptions) => Promise<ApiResult<T>>): Promise<SourceOutcome<T>> {
    const cached = cache.get(key)
    if (cached) {
      return cached as Promise<SourceOutcome<T>>
    }
    const task = load({ signal: controller.signal, timeoutMs: REQUEST_TIMEOUT_MS })
      .then((result) => ({ ok: true as const, data: result.data, traceId: result.traceId }))
      .catch((error: unknown) => ({ ok: false as const, error: toApiError(error) }))
    cache.set(key, task)
    return task
  }

  return { get }
}

function isAuthError(error: ApiError): boolean {
  return error.category === 'AUTH_EXPIRED'
}

function decorateError(error: ApiError): ApiError {
  if (error.traceId || error.requestId) {
    return error
  }
  return {
    ...error,
    requestId: `wb-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
  }
}

export interface WorkbenchController {
  state: WorkbenchState
  refresh: () => Promise<void>
  retryRegion: (key: RegionKey) => Promise<void>
}

export function useWorkbench(): WorkbenchController {
  const router = useRouter()

  const state = reactive<WorkbenchState>({
    identity: emptyRegion<UserVO>(),
    goal: emptyRegion<GoalSummary>(),
    nextTask: emptyRegion<NextTaskModel>(),
    targetJobs: emptyRegion<TargetJobsModel>(),
    progress: emptyRegion<ProgressModel>(),
    activities: emptyRegion<ActivityItem[]>(),
    refreshing: false,
    generation: 0,
  })

  let activeController: AbortController | null = null
  let loadPromise: Promise<void> | null = null
  let identityBlocked = false
  let authHandling = false
  let snapshotRestored = false
  let currentUserId: string | undefined
  let unmounted = false

  const businessKeys: Array<Exclude<RegionKey, 'identity'>> = [
    'goal',
    'nextTask',
    'targetJobs',
    'progress',
    'activities',
  ]

  function regionOf(key: RegionKey): RegionState<unknown> {
    return state[key] as unknown as RegionState<unknown>
  }

  function beginRegion(key: RegionKey): void {
    const region = regionOf(key)
    region.status = region.data !== undefined ? 'REFRESHING' : 'LOADING'
    region.error = undefined
  }

  function setRegion<K extends RegionKey>(
    key: K,
    status: RegionStatus,
    data?: RegionState<unknown>['data'],
    error?: ApiError,
  ): void {
    const region = regionOf(key)
    region.status = status
    region.error = status === 'ERROR' ? error : undefined
    if (status === 'ERROR') {
      region.data = undefined
      return
    }
    if (data !== undefined) {
      region.data = data
    }
    region.fromCache = false
    region.updatedAt = Date.now()
  }

  function setEmptyRegion<K extends RegionKey>(key: K, data?: RegionState<unknown>['data']): void {
    setRegion(key, 'EMPTY', data)
  }

  function clearRegions(): void {
    state.identity = emptyRegion<UserVO>()
    state.goal = emptyRegion<GoalSummary>()
    state.nextTask = emptyRegion<NextTaskModel>()
    state.targetJobs = emptyRegion<TargetJobsModel>()
    state.progress = emptyRegion<ProgressModel>()
    state.activities = emptyRegion<ActivityItem[]>()
    state.refreshing = false
    state.refreshedAt = undefined
  }

  function clearSnapshot(): void {
    try {
      sessionStorage.removeItem(SNAPSHOT_KEY)
    } catch {
      // 忽略隐私模式下不可用的会话存储
    }
  }

  function handleAuthExpired(): void {
    if (authHandling) {
      return
    }
    authHandling = true
    state.generation += 1
    activeController?.abort()
    clearRegions()
    clearSnapshot()
    currentUserId = undefined
    identityBlocked = false
    if (router.hasRoute('login')) {
      void router.push({ name: 'login', query: { reason: 'expired' } })
    }
  }

  function handleIdentityError(error: ApiError): void {
    if (isAuthError(error)) {
      handleAuthExpired()
      return
    }
    if (identityBlocked) {
      return
    }
    identityBlocked = true
    state.generation += 1
    activeController?.abort()
    setRegion('identity', 'ERROR', undefined, decorateError(error))
    for (const key of businessKeys) {
      setRegion(key, 'ERROR', undefined, decorateError(error))
    }
    state.refreshing = false
  }

  function failRegion(key: RegionKey, error: ApiError): void {
    if (isAuthError(error)) {
      handleAuthExpired()
      return
    }
    if (identityBlocked) {
      return
    }
    setRegion(key, 'ERROR', undefined, decorateError(error))
  }

  async function requireIdentity(pool: SourcePool, generation: number): Promise<UserVO | null> {
    const outcome = await pool.get('user', workbenchApi.getCurrentUser)
    if (generation !== state.generation) {
      return null
    }
    if (!outcome.ok) {
      handleIdentityError(outcome.error)
      return null
    }
    currentUserId = outcome.data.id
    return outcome.data
  }

  function maybeRestoreSnapshot(userId: string): void {
    if (snapshotRestored) {
      return
    }
    snapshotRestored = true
    let raw: string | null = null
    try {
      raw = sessionStorage.getItem(SNAPSHOT_KEY)
    } catch {
      return
    }
    if (!raw) {
      return
    }
    let snapshot: Snapshot
    try {
      snapshot = JSON.parse(raw) as Snapshot
    } catch {
      clearSnapshot()
      return
    }
    if (snapshot.schemaVersion !== SNAPSHOT_SCHEMA_VERSION || snapshot.userId !== userId) {
      clearSnapshot()
      return
    }

    const applyRegion = <K extends Exclude<RegionKey, 'identity'>>(
      key: K,
      saved?: SnapshotRegion<RegionState<unknown>['data']>,
    ): void => {
      if (!saved || (saved.status !== 'READY' && saved.status !== 'EMPTY')) {
        return
      }
      const region = regionOf(key)
      region.status = saved.status
      region.data = saved.data
      region.updatedAt = saved.updatedAt
      region.fromCache = true
    }

    applyRegion('goal', snapshot.state.goal)
    applyRegion('nextTask', snapshot.state.nextTask)
    applyRegion('targetJobs', snapshot.state.targetJobs)
    applyRegion('progress', snapshot.state.progress)
    applyRegion('activities', snapshot.state.activities)
    if (snapshot.state.refreshedAt !== undefined) {
      state.refreshedAt = snapshot.state.refreshedAt
    }
  }

  function saveSnapshot(): void {
    if (!currentUserId) {
      return
    }
    const snapshotRegion = <T>(region: RegionState<T>): SnapshotRegion<T> | undefined => {
      if ((region.status !== 'READY' && region.status !== 'EMPTY') || region.data === undefined) {
        return undefined
      }
      return { status: region.status, data: region.data, updatedAt: region.updatedAt }
    }

    const snapshot: Snapshot = {
      userId: currentUserId,
      savedAt: Date.now(),
      schemaVersion: SNAPSHOT_SCHEMA_VERSION,
      state: {
        goal: snapshotRegion(state.goal),
        nextTask: snapshotRegion(state.nextTask),
        targetJobs: snapshotRegion(state.targetJobs),
        progress: snapshotRegion(state.progress),
        activities: snapshotRegion(state.activities),
        refreshedAt: state.refreshedAt,
      },
    }

    try {
      sessionStorage.setItem(SNAPSHOT_KEY, JSON.stringify(snapshot))
    } catch {
      // 忽略存储配额或隐私模式导致的写入失败
    }
  }

  async function loadGoal(pool: SourcePool, generation: number, ctx: LoadContext): Promise<void> {
    beginRegion('goal')
    const user = await requireIdentity(pool, generation)
    if (!user || generation !== state.generation || identityBlocked) {
      return
    }
    maybeRestoreSnapshot(user.id)
    const outcome = await pool.get('profile', workbenchApi.getProfile)
    if (generation !== state.generation || identityBlocked) {
      return
    }
    if (!outcome.ok) {
      failRegion('goal', outcome.error)
      return
    }
    const summary = buildGoalSummary(outcome.data)
    if (summary.isEmpty) {
      setEmptyRegion('goal', summary)
    } else {
      setRegion('goal', 'READY', summary)
    }
    ctx.success = true
  }

  async function loadNextTask(pool: SourcePool, generation: number, ctx: LoadContext): Promise<void> {
    beginRegion('nextTask')
    const user = await requireIdentity(pool, generation)
    if (!user || generation !== state.generation || identityBlocked) {
      return
    }

    const [tasksOutcome, plansOutcome, profileOutcome] = await Promise.all([
      pool.get<TaskVO[]>('tasks', (options) => workbenchApi.getRecentTasks(options).then((result) => ({
        data: result.data.records,
        traceId: result.traceId,
      }))),
      pool.get<LearningPlanVO[]>('plans', (options) => workbenchApi.getLearningPlans(options).then((result) => ({
        data: result.data.records,
        traceId: result.traceId,
      }))),
      pool.get<ProfileVO | null>('profile', workbenchApi.getProfile),
    ])

    if (generation !== state.generation || identityBlocked) {
      return
    }
    for (const outcome of [tasksOutcome, plansOutcome, profileOutcome]) {
      if (!outcome.ok && isAuthError(outcome.error)) {
        handleAuthExpired()
        return
      }
    }

    const model = buildNextTask({
      tasks: tasksOutcome.ok ? tasksOutcome.data : null,
      plans: plansOutcome.ok ? plansOutcome.data : null,
      profile: profileOutcome.ok ? profileOutcome.data : null,
      profileOk: profileOutcome.ok,
    })

    if (model.unavailable) {
      const error = !tasksOutcome.ok
        ? tasksOutcome.error
        : !plansOutcome.ok
          ? plansOutcome.error
          : { category: 'UNKNOWN' as const }
      failRegion('nextTask', error)
      return
    }
    if (!model.suggestion) {
      setEmptyRegion('nextTask', model)
    } else {
      setRegion('nextTask', 'READY', model)
    }
    ctx.success = true
  }

  async function loadTargetJobs(pool: SourcePool, generation: number, ctx: LoadContext): Promise<void> {
    beginRegion('targetJobs')
    const user = await requireIdentity(pool, generation)
    if (!user || generation !== state.generation || identityBlocked) {
      return
    }
    maybeRestoreSnapshot(user.id)

    const startedAt = Date.now()
    const [batchesOutcome, profileOutcome] = await Promise.all([
      pool.get<PageResult<MatchBatchVO>>('matchBatches', workbenchApi.getMatchBatches),
      pool.get<ProfileVO | null>('profile', workbenchApi.getProfile),
    ])
    if (generation !== state.generation || identityBlocked) {
      return
    }
    if (!batchesOutcome.ok) {
      failRegion('targetJobs', batchesOutcome.error)
      return
    }
    if (!profileOutcome.ok && isAuthError(profileOutcome.error)) {
      handleAuthExpired()
      return
    }

    const batches = batchesOutcome.data.records
    const profile = profileOutcome.ok ? profileOutcome.data : null
    const latest = selectLatestBatch(batches)
    if (!latest) {
      setEmptyRegion('targetJobs', buildTargetJobsModel({ batches, detail: null, profile }))
      ctx.success = true
      return
    }

    let detail: MatchBatchVO | null = null
    if (latest.status === 'READY') {
      const remaining = REQUEST_TIMEOUT_MS - (Date.now() - startedAt)
      if (remaining <= 0) {
        failRegion('targetJobs', { category: 'TIMEOUT' })
        return
      }
      try {
        const result = await workbenchApi.getMatchBatchDetail(latest.id, {
          signal: activeController?.signal,
          timeoutMs: Math.min(remaining, REQUEST_TIMEOUT_MS),
        })
        detail = result.data
      } catch (error) {
        if (generation !== state.generation || identityBlocked) {
          return
        }
        failRegion('targetJobs', toApiError(error))
        return
      }
    }

    const model = buildTargetJobsModel({ batches, detail, profile })
    if (!model) {
      failRegion('targetJobs', { category: 'UNKNOWN' })
      return
    }
    if (model.phase === 'EMPTY') {
      setEmptyRegion('targetJobs', model)
    } else {
      setRegion('targetJobs', 'READY', model)
    }
    ctx.success = true
  }

  async function loadProgress(pool: SourcePool, generation: number, ctx: LoadContext): Promise<void> {
    beginRegion('progress')
    const user = await requireIdentity(pool, generation)
    if (!user || generation !== state.generation || identityBlocked) {
      return
    }

    const [profileOutcome, resumesOutcome, batchesOutcome, plansOutcome, growthOutcome, interviewsOutcome] =
      await Promise.all([
        pool.get<ProfileVO | null>('profile', workbenchApi.getProfile),
        pool.get<ResumeVO[]>('resumes', (options) => workbenchApi.getResumes(options).then((result) => ({
          data: result.data.records,
          traceId: result.traceId,
        }))),
        pool.get<MatchBatchVO[]>('matchBatches', (options) => workbenchApi.getMatchBatches(options).then((result) => ({
          data: result.data.records,
          traceId: result.traceId,
        }))),
        pool.get<LearningPlanVO[]>('plans', (options) => workbenchApi.getLearningPlans(options).then((result) => ({
          data: result.data.records,
          traceId: result.traceId,
        }))),
        pool.get<GrowthVO>('growth', workbenchApi.getGrowthSummary),
        pool.get<InterviewVO[]>('interviews', (options) => workbenchApi.getInterviews(options).then((result) => ({
          data: result.data.records,
          traceId: result.traceId,
        }))),
      ])

    if (generation !== state.generation || identityBlocked) {
      return
    }

    const outcomes = [profileOutcome, resumesOutcome, batchesOutcome, plansOutcome, growthOutcome, interviewsOutcome]
    for (const outcome of outcomes) {
      if (!outcome.ok && isAuthError(outcome.error)) {
        handleAuthExpired()
        return
      }
    }

    if (outcomes.every((outcome) => !outcome.ok)) {
      failRegion('progress', outcomes[0]?.ok === false ? outcomes[0].error : { category: 'UNKNOWN' })
      return
    }

    const model = buildProgress({
      profile: profileOutcome.ok ? profileOutcome.data : null,
      resumes: resumesOutcome.ok ? resumesOutcome.data : null,
      batches: batchesOutcome.ok ? batchesOutcome.data : null,
      plans: plansOutcome.ok ? plansOutcome.data : null,
      growth: growthOutcome.ok ? growthOutcome.data : null,
      interviews: interviewsOutcome.ok ? interviewsOutcome.data : null,
      availability: {
        profile: profileOutcome.ok,
        resumes: resumesOutcome.ok,
        batches: batchesOutcome.ok,
        plans: plansOutcome.ok,
        growth: growthOutcome.ok,
        interviews: interviewsOutcome.ok,
      },
    })

    setRegion('progress', 'READY', model)
    ctx.success = true
  }

  async function loadActivities(pool: SourcePool, generation: number, ctx: LoadContext): Promise<void> {
    beginRegion('activities')
    const user = await requireIdentity(pool, generation)
    if (!user || generation !== state.generation || identityBlocked) {
      return
    }

    const [resumesOutcome, batchesOutcome, plansOutcome, interviewsOutcome, growthOutcome] = await Promise.all([
      pool.get<ResumeVO[]>('resumes', (options) => workbenchApi.getResumes(options).then((result) => ({
        data: result.data.records,
        traceId: result.traceId,
      }))),
      pool.get<MatchBatchVO[]>('matchBatches', (options) => workbenchApi.getMatchBatches(options).then((result) => ({
        data: result.data.records,
        traceId: result.traceId,
      }))),
      pool.get<LearningPlanVO[]>('plans', (options) => workbenchApi.getLearningPlans(options).then((result) => ({
        data: result.data.records,
        traceId: result.traceId,
      }))),
      pool.get<InterviewVO[]>('interviews', (options) => workbenchApi.getInterviews(options).then((result) => ({
        data: result.data.records,
        traceId: result.traceId,
      }))),
      pool.get<GrowthVO>('growth', workbenchApi.getGrowthSummary),
    ])

    if (generation !== state.generation || identityBlocked) {
      return
    }

    const outcomes = [resumesOutcome, batchesOutcome, plansOutcome, interviewsOutcome, growthOutcome]
    for (const outcome of outcomes) {
      if (!outcome.ok && isAuthError(outcome.error)) {
        handleAuthExpired()
        return
      }
    }
    if (outcomes.every((outcome) => !outcome.ok)) {
      failRegion('activities', outcomes[0]?.ok === false ? outcomes[0].error : { category: 'UNKNOWN' })
      return
    }

    const items = buildActivities({
      resumes: resumesOutcome.ok ? resumesOutcome.data : null,
      batches: batchesOutcome.ok ? batchesOutcome.data : null,
      plans: plansOutcome.ok ? plansOutcome.data : null,
      interviews: interviewsOutcome.ok ? interviewsOutcome.data : null,
      growth: growthOutcome.ok ? growthOutcome.data : null,
    })
    if (items.length === 0) {
      setEmptyRegion('activities', items)
    } else {
      setRegion('activities', 'READY', items)
    }
    ctx.success = true
  }

  const loaders: Record<Exclude<RegionKey, 'identity'>, (pool: SourcePool, generation: number, ctx: LoadContext) => Promise<void>> = {
    goal: loadGoal,
    nextTask: loadNextTask,
    targetJobs: loadTargetJobs,
    progress: loadProgress,
    activities: loadActivities,
  }

  async function executeLoad(): Promise<void> {
    identityBlocked = false
    authHandling = false
    state.generation += 1
    const generation = state.generation
    state.refreshing = true
    beginRegion('identity')
    for (const key of businessKeys) {
      beginRegion(key)
    }

    const controller = new AbortController()
    activeController = controller
    const pool = createPool(controller)
    const ctx: LoadContext = { success: false }

    try {
      await Promise.all([
        (async () => {
          beginRegion('identity')
          const outcome = await pool.get('user', workbenchApi.getCurrentUser)
          if (generation !== state.generation) {
            return
          }
          if (!outcome.ok) {
            handleIdentityError(outcome.error)
            return
          }
          currentUserId = outcome.data.id
          setRegion('identity', 'READY', outcome.data)
          maybeRestoreSnapshot(outcome.data.id)
          ctx.success = true
        })(),
        ...businessKeys.map((key) => loaders[key](pool, generation, ctx)),
      ])
    } finally {
      if (generation === state.generation) {
        state.refreshing = false
        if (ctx.success && !identityBlocked) {
          state.refreshedAt = Date.now()
          saveSnapshot()
        }
        activeController = null
      }
    }
  }

  function refresh(): Promise<void> {
    if (loadPromise) {
      return loadPromise
    }
    loadPromise = executeLoad().finally(() => {
      loadPromise = null
    })
    return loadPromise
  }

  async function retryRegion(key: RegionKey): Promise<void> {
    if (key === 'identity') {
      await refresh()
      return
    }
    if (state.refreshing || loadPromise) {
      return
    }
    const region = regionOf(key)
    region.retryCount += 1
    identityBlocked = false
    state.generation += 1
    const generation = state.generation
    const controller = new AbortController()
    activeController = controller
    const pool = createPool(controller)
    const ctx: LoadContext = { success: false }

    try {
      await loaders[key](pool, generation, ctx)
    } finally {
      if (generation === state.generation && ctx.success) {
        state.refreshedAt = Date.now()
        saveSnapshot()
      }
    }
  }

  onBeforeUnmount(() => {
    unmounted = true
    activeController?.abort()
    activeController = null
  })

  return {
    state,
    refresh,
    retryRegion,
  }
}