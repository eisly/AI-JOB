const assert = require('node:assert')

async function verify(server) {
  const load = (path) => server.ssrLoadModule('/src/' + path + '.ts')
  const { getCurrentUser, toApiError } = await load('api/workbench')
  const { setSessionToken, clearSessionToken } = await load('api/session')
  const { buildGreeting } = await load('features/workbench/model/greeting')
  const { buildGoalSummary } = await load('features/workbench/model/goalSummary')
  const { buildNextTask } = await load('features/workbench/model/nextTask')
  const { buildTargetJobsModel } = await load('features/workbench/model/targetJobs')
  const { buildProgress } = await load('features/workbench/model/progress')
  const { buildActivities } = await load('features/workbench/model/activities')
  const { formatActivityTime } = await load('features/workbench/model/timeFormat')

  assert.strictEqual(buildGreeting('  林同学 '), '林同学')
  assert.strictEqual(buildGreeting('   '), '同学')
  assert.strictEqual(buildGreeting(undefined), '同学')

  const emptyGoal = buildGoalSummary(null)
  assert.strictEqual(emptyGoal.isEmpty, true)
  assert.strictEqual(emptyGoal.targetRoleLabel, '未设置')
  assert.strictEqual(emptyGoal.cityLabels.length, 0)

  const goal = buildGoalSummary({
    targetRole: 'JAVA_BACKEND',
    candidateType: 'CAMPUS',
    cities: ['330100', '110100', '310100', '440300', '440100', '510100'],
  })
  assert.strictEqual(goal.targetRoleLabel, 'Java 后端开发')
  assert.strictEqual(goal.candidateTypeLabel, '校招')
  assert.deepStrictEqual(goal.cityLabels, ['杭州', '北京', '上海', '深圳', '广州'])

  const profile = { targetRole: 'JAVA_BACKEND', candidateType: 'CAMPUS', profileVersion: 3 }
  const now = new Date().toISOString()
  const tasks = [
    { id: 't2', type: 'JOB_MATCH', status: 'QUEUED', progress: null, createdAt: now },
    { id: 't1', type: 'RESUME_PARSE', status: 'RUNNING', stage: 'calling_model', progress: 40, createdAt: '2026-01-01T00:00:00Z' },
  ]
  const next = buildNextTask({ tasks, plans: [], profile, profileOk: true })
  assert.strictEqual(next.suggestion.kind, 'TASK')
  assert.strictEqual(next.suggestion.title, '简历解析')
  assert.strictEqual(next.suggestion.stage, '正在调用模型')
  assert.strictEqual(next.suggestion.progress, undefined)

  const planNext = buildNextTask({
    tasks: [],
    plans: [{ id: 'p1', title: 'Redis场景专项', createdAt: now, items: [{ id: 'i1', title: '缓存穿透训练', kind: 'PRACTICE', estimatedMinutes: 20, status: 'PENDING' }] }],
    profile,
    profileOk: true,
  })
  assert.strictEqual(planNext.suggestion.kind, 'PLAN_ITEM')
  assert.strictEqual(planNext.suggestion.meta, '预计 20 分钟')

  const profileNext = buildNextTask({ tasks: [], plans: [], profile: null, profileOk: true })
  assert.strictEqual(profileNext.suggestion.kind, 'PROFILE')

  const unavailable = buildNextTask({ tasks: null, plans: null, profile, profileOk: true })
  assert.strictEqual(unavailable.unavailable, true)

  const batches = [
    { id: 'b1', status: 'READY', profileVersion: 3, isStale: false, createdAt: '2026-02-01T00:00:00Z' },
    { id: 'b2', status: 'READY', profileVersion: 3, isStale: false, createdAt: '2026-03-01T00:00:00Z' },
  ]
  const detail = {
    id: 'b2',
    profileVersion: 3,
    status: 'READY',
    createdAt: '2026-03-01T00:00:00Z',
    items: [
      { id: 'i1', title: 'A', eligibility: { status: 'UNKNOWN' }, matchScore: 95, gaps: [] },
      { id: 'i2', title: 'B', eligibility: { status: 'PASS' }, matchScore: 80, gaps: [] },
      { id: 'i3', title: 'C', eligibility: { status: 'FAIL' }, matchScore: 99, gaps: [] },
      { id: 'i4', title: 'D', eligibility: { status: 'PASS' }, matchScore: null, gaps: [] },
      { id: 'i5', title: 'E', eligibility: { status: 'PASS' }, matchScore: 70, gaps: [] },
    ],
  }
  const jobs = buildTargetJobsModel({ batches, detail, profile })
  assert.strictEqual(jobs.phase, 'READY')
  assert.deepStrictEqual(jobs.jobs.map((job) => job.id), ['i2', 'i5', 'i4'])
  assert.deepStrictEqual(jobs.jobs[0].action, { name: 'jobs', query: { tab: 'matches', matchId: 'b2' } })
  assert.strictEqual(jobs.jobs[0].companyLabel, '公司信息待补充')
  assert.strictEqual(jobs.stale, false)

  const staleJobs = buildTargetJobsModel({ batches, detail, profile: { ...profile, profileVersion: 4 } })
  assert.strictEqual(staleJobs.stale, true)

  const generating = buildTargetJobsModel({ batches: [{ id: 'b3', status: 'GENERATING' }], detail: null, profile })
  assert.strictEqual(generating.phase, 'GENERATING')

  const allFailed = buildTargetJobsModel({
    batches: [{ id: 'b4', status: 'READY', profileVersion: 3, createdAt: now }],
    detail: { id: 'b4', items: [{ id: 'x', eligibility: { status: 'FAIL' } }] },
    profile,
  })
  assert.strictEqual(allFailed.phase, 'EMPTY')
  assert.strictEqual(allFailed.emptyReason, 'ALL_INELIGIBLE')

  const progress = buildProgress({
    profile,
    resumes: [{ id: 'r1', latestVersion: { id: 'v1', status: 'CONFIRMED' } }],
    batches,
    plans: [],
    growth: { recentEvidence: [] },
    interviews: [{ id: 'iv1', status: 'COMPLETED' }],
    applicationTotal: 0,
    availability: { profile: true, resumes: true, batches: true, plans: true, growth: true, interviews: true },
  })
  assert.strictEqual(progress.completedCount, 4)
  assert.strictEqual(progress.percent, 66)
  assert.strictEqual(progress.stages.find((stage) => stage.key === 'APPLICATION').status, 'PENDING')

  const progressUnknown = buildProgress({
    profile,
    resumes: null,
    batches,
    plans: [],
    growth: { recentEvidence: [] },
    interviews: [{ id: 'iv1', status: 'COMPLETED' }],
    applicationTotal: 1,
    availability: { profile: true, resumes: false, batches: true, plans: true, growth: true, interviews: true },
  })
  assert.strictEqual(progressUnknown.percent, undefined)
  assert.strictEqual(progressUnknown.stages.find((stage) => stage.key === 'APPLICATION').status, 'DONE')

  const activities = buildActivities({
    resumes: [{ id: 'r1', latestVersion: { id: 'v1', status: 'CONFIRMED', createdAt: now } }],
    batches,
    plans: [],
    interviews: [],
    growth: { recentEvidence: [], suggestions: [], skills: [] },
  })
  assert.strictEqual(activities.length, 2)
  assert.strictEqual(activities[0].type, 'RESUME_CONFIRMED')
  assert.strictEqual(activities[1].type, 'MATCH_READY')

  const threeHoursAgo = new Date(Date.now() - 3 * 3600 * 1000).toISOString()
  assert.strictEqual(formatActivityTime(threeHoursAgo), '3 小时前')
  assert.strictEqual(formatActivityTime('not-a-date'), '时间未知')

  async function verifyRequestLayer() {
    global.window = { location: { origin: 'http://localhost' } }
    setSessionToken('test-token')

    global.fetch = async (url, init) => {
      assert.strictEqual(new URL(url).pathname, '/api/v1/users/me')
      assert.strictEqual(init.headers.get('authorization'), 'Bearer test-token')
      return Response.json({ code: 200, data: { value: 1 }, traceId: 'trace-1' })
    }
    const result = await getCurrentUser()
    assert.deepStrictEqual(result, { data: { value: 1 } })
    clearSessionToken()

    global.fetch = async () => Response.json({ code: 409, msg: '冲突', data: null, traceId: 'trace-2' })
    await assert.rejects(
      () => getCurrentUser(),
      (error) => toApiError(error).category === 'UNKNOWN' && toApiError(error).businessCode === 409,
    )

    global.fetch = async () => Response.json({ code: 401, msg: '未登录', traceId: 'trace-3' }, { status: 401 })
    await assert.rejects(
      () => getCurrentUser(),
      (error) => toApiError(error).category === 'AUTH_EXPIRED' && toApiError(error).traceId === 'trace-3',
    )

    global.fetch = (_url, init) =>
      new Promise((_resolve, reject) => {
        init.signal.addEventListener('abort', () => reject(new Error('aborted')))
      })
    await assert.rejects(
      () => getCurrentUser({ timeoutMs: 20 }),
      (error) => toApiError(error).category === 'TIMEOUT',
    )

    const controller = new AbortController()
    const pending = getCurrentUser({ signal: controller.signal })
    controller.abort()
    await assert.rejects(pending, (error) => toApiError(error).category === 'CANCELLED')

    global.fetch = async () => {
      throw new Error('network down')
    }
    await assert.rejects(
      () => getCurrentUser(),
      (error) => toApiError(error).category === 'NETWORK',
    )
  }

  await verifyRequestLayer()
}

async function main() {
  const { createServer } = await import('vite')
  const server = await createServer({
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, hmr: false, watch: null },
  })
  try {
    await verify(server)
  } finally {
    await server.close()
  }
}

main()
  .then(() => {
    console.log('workbench model verification passed')
  })
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
