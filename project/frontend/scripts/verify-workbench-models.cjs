const assert = require('node:assert')

const { ApiRequestError, apiGet } = require('../.workbench-verify/api/request.js')
const { buildGreeting } = require('../.workbench-verify/features/workbench/model/greeting.js')
const { buildGoalSummary } = require('../.workbench-verify/features/workbench/model/goalSummary.js')
const { buildNextTask } = require('../.workbench-verify/features/workbench/model/nextTask.js')
const { buildTargetJobsModel } = require('../.workbench-verify/features/workbench/model/targetJobs.js')
const { buildProgress } = require('../.workbench-verify/features/workbench/model/progress.js')
const { buildActivities } = require('../.workbench-verify/features/workbench/model/activities.js')
const { formatActivityTime } = require('../.workbench-verify/features/workbench/model/timeFormat.js')

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
  { id: 't1', type: 'RESUME_PARSE', status: 'RUNNING', progress: 40, createdAt: '2026-01-01T00:00:00Z' },
]
const next = buildNextTask({ tasks, plans: [], profile, profileOk: true })
assert.strictEqual(next.suggestion.kind, 'TASK')
assert.strictEqual(next.suggestion.title, '简历解析')
assert.strictEqual(next.suggestion.progress, 40)

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
  availability: { profile: true, resumes: true, batches: true, plans: true, growth: true, interviews: true },
})
assert.strictEqual(progress.completedCount, 4)
assert.strictEqual(progress.percent, 80)

const progressUnknown = buildProgress({
  profile,
  resumes: null,
  batches,
  plans: [],
  growth: { recentEvidence: [] },
  interviews: [{ id: 'iv1', status: 'COMPLETED' }],
  availability: { profile: true, resumes: false, batches: true, plans: true, growth: true, interviews: true },
})
assert.strictEqual(progressUnknown.percent, undefined)

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
  global.localStorage = { getItem: () => null }

  global.fetch = async () => ({
    ok: true,
    status: 200,
    json: async () => ({ code: 200, msg: '操作成功', data: { value: 1 }, traceId: 'trace-1' }),
  })
  const result = await apiGet('/api/v1/test')
  assert.deepStrictEqual(result, { data: { value: 1 }, traceId: 'trace-1' })

  global.fetch = async () => ({
    ok: true,
    status: 200,
    json: async () => ({ code: 409, msg: '冲突', data: null, traceId: 'trace-2' }),
  })
  await assert.rejects(
    () => apiGet('/api/v1/test'),
    (error) => error instanceof ApiRequestError && error.category === 'UNKNOWN' && error.businessCode === 409,
  )

  global.fetch = async () => ({
    ok: false,
    status: 401,
    json: async () => ({ code: 401, msg: '未登录', traceId: 'trace-3' }),
  })
  await assert.rejects(
    () => apiGet('/api/v1/test'),
    (error) => error instanceof ApiRequestError && error.category === 'AUTH_EXPIRED' && error.traceId === 'trace-3',
  )

  global.fetch = (_url, init) =>
    new Promise((_resolve, reject) => {
      init.signal.addEventListener('abort', () => reject(new Error('aborted')))
    })
  await assert.rejects(
    () => apiGet('/api/v1/test', undefined, { timeoutMs: 20 }),
    (error) => error instanceof ApiRequestError && error.category === 'TIMEOUT',
  )

  global.fetch = async () => {
    throw new Error('network down')
  }
  await assert.rejects(
    () => apiGet('/api/v1/test'),
    (error) => error instanceof ApiRequestError && error.category === 'NETWORK',
  )
}

verifyRequestLayer()
  .then(() => {
    console.log('workbench model verification passed')
  })
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })