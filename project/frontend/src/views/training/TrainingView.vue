<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CircleCheck, Edit, Plus, Refresh, Right } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { learningApi } from '@/api/learning'
import { isEndpointMissing, practiceApi } from '@/api/training'
import { jobApi } from '@/api/jobs'
import { profileApi } from '@/api/profile'
import { resumeApi } from '@/api/resumes'
import { canRetryTrackedTask, retryTrackedTask } from '@/api/task-registry'
import { ApiError } from '@/api/request'
import type {
  LearningAnswerVO,
  LearningPlanVO,
  MatchSummaryVO,
  MistakeVO,
  Page,
  PlanItemVO,
  PracticeConfigVO,
  PracticeTypeDistributionVO,
  ProfileVO,
  TaskVO,
} from '@/api/types'
import { useTask } from '@/composables/useTask'
import { getJobRoleLabel, JOB_ROLE_LIST } from '@/config/jobRoles'
import AsyncState from '@/components/AsyncState.vue'
import TaskStatus from '@/components/TaskStatus.vue'
import { commonStatusLabels, displayScore, errorMessage, formatDate } from '@/utils/format'

type TrainingTab = 'plans' | 'mistakes'

const route = useRoute()
const router = useRouter()
const activeTab = ref<TrainingTab>(route.query.tab === 'mistakes' ? 'mistakes' : 'plans')
const currentProfile = ref<ProfileVO | null>(null)

function emptyPage<T>(size = 6): Page<T> {
  return { total: 0, records: [], current: 1, size }
}

function queryValue(value: unknown) {
  return typeof value === 'string' && value ? value : ''
}

function setQuery(values: Record<string, string | undefined>) {
  const query = { ...route.query }
  Object.entries(values).forEach(([key, value]) => {
    if (value) query[key] = value
    else delete query[key]
  })
  void router.replace({ query })
}

function taskIsActive(task?: TaskVO | null) {
  return !!task && ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(task.status)
}

function statusTagType(status: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (['ACTIVE', 'COMPLETED', 'EVALUATED'].includes(status)) return 'success'
  if (['GENERATING', 'PREPARING', 'EVALUATING', 'IN_PROGRESS'].includes(status)) return 'warning'
  if (['FAILED', 'INVALID'].includes(status)) return 'danger'
  return 'info'
}

function kindLabel(kind: PlanItemVO['kind']) {
  return ({ READING: '阅读', PRACTICE: '答题训练', INTERVIEW: '模拟面试' } as Record<string, string>)[kind]
}

function difficultyLabel(difficulty: string) {
  return ({ EASY: '简单', MEDIUM: '中等', HARD: '困难' } as Record<string, string>)[difficulty] || difficulty
}

function questionTypeLabel(type: string) {
  return ({ TEXT_SCENARIO: '场景题', TEXT_KNOWLEDGE: '知识题' } as Record<string, string>)[type] || type
}

function evidenceLabel(status: string) {
  return ({ VALID: '有效评价', INSUFFICIENT: '证据不足', INVALID: '无效回答' } as Record<string, string>)[status] || status
}

const plans = ref<Page<LearningPlanVO>>(emptyPage())
const plansLoading = ref(false)
const plansError = ref<unknown>(null)
const selectedPlan = ref<LearningPlanVO | null>(null)
const planDetailRequestId = ref('')
const planDetailLoading = ref(false)
const planDetailError = ref<unknown>(null)
let planLoadSequence = 0

async function loadPlans() {
  plansLoading.value = true
  plansError.value = null
  try {
    plans.value = await learningApi.listPlans({ current: plans.value.current, size: plans.value.size })
  } catch (reason) {
    plansError.value = reason
  } finally {
    plansLoading.value = false
  }
}

async function loadPlan(planId: string, syncRoute = false, clearCurrentSession = false) {
  if (!planId) return
  planDetailRequestId.value = planId
  const sequence = ++planLoadSequence
  planDetailLoading.value = true
  planDetailError.value = null
  if (clearCurrentSession) {
    selectedPlan.value = null
  }
  try {
    const plan = await learningApi.getPlan(planId)
    if (sequence !== planLoadSequence) return
    selectedPlan.value = plan
    if (syncRoute) setQuery({ tab: 'plans', planId, sessionId: undefined, answerId: undefined })
  } catch (reason) {
    if (sequence === planLoadSequence) planDetailError.value = reason
  } finally {
    if (sequence === planLoadSequence) planDetailLoading.value = false
  }
}

function selectPlan(planId: string) {
  void loadPlan(planId, true, true)
}

function changePlanPage(page: number) {
  plans.value.current = page
  void loadPlans()
}

function changePlanSize(size: number) {
  plans.value.size = size
  plans.value.current = 1
  void loadPlans()
}

function planProgress(plan: LearningPlanVO) {
  const completed = plan.items.filter(item => item.status === 'COMPLETED').length
  return { completed, total: plan.items.length, percentage: plan.items.length ? Math.round(completed / plan.items.length * 100) : 0 }
}

function planOutdatedReasons(plan: LearningPlanVO) {
  const profile = currentProfile.value
  if (!profile) return []
  const reasons: string[] = []
  if (plan.profileVersion < profile.profileVersion) reasons.push(`画像已从 v${plan.profileVersion} 更新到 v${profile.profileVersion}`)
  if (plan.targetDirection !== profile.targetRole) reasons.push('目标方向已变化')
  if (plan.resumeVersionId && plan.resumeVersionId !== profile.activeResumeVersionId) reasons.push('当前简历版本已变化')
  return reasons
}

async function refreshCurrentProfile() {
  currentProfile.value = await profileApi.get().catch(() => null)
  if (currentProfile.value?.targetRole) practiceConfig.value.direction = currentProfile.value.targetRole
}

const itemActionPending = ref<string[]>([])
const itemActionErrors = ref<Record<string, unknown>>({})

async function completeReading(item: PlanItemVO) {
  if (!selectedPlan.value || item.kind !== 'READING' || item.status === 'COMPLETED') return
  const planId = selectedPlan.value.id
  itemActionPending.value.push(item.id)
  itemActionErrors.value = { ...itemActionErrors.value, [item.id]: null }
  try {
    await learningApi.completeReading(planId, item.id)
    await Promise.all([loadPlan(planId), loadPlans()])
    ElMessage.success('阅读任务已完成并刷新计划状态')
  } catch (reason) {
    itemActionErrors.value = { ...itemActionErrors.value, [item.id]: reason }
  } finally {
    itemActionPending.value = itemActionPending.value.filter(id => id !== item.id)
  }
}

const practiceActionError = ref<unknown>(null)

function conflictSessionId(reason: unknown) {
  if (!(reason instanceof ApiError) || reason.status !== 409) return ''
  const value = reason.details?.sessionId
  return typeof value === 'string' ? value : ''
}

async function openPracticeSet(setId: string, allowLegacy = false) {
  await router.push({ name: 'training-practice', params: { setId }, query: allowLegacy ? { legacy: '1' } : {} })
}

async function openPreparePage(taskId: string) {
  await router.push({ name: 'training-prepare', params: { taskId } })
}

const practiceConfig = ref({
  totalQuestions: 10,
  typeDistribution: { choice: 4, fill: 3, subjective: 3 } as PracticeTypeDistributionVO,
  difficulty: 'MEDIUM' as PracticeConfigVO['difficulty'],
  direction: 'JAVA_BACKEND',
})
const difficultyOptions: { value: PracticeConfigVO['difficulty']; label: string }[] = [
  { value: 'EASY', label: '入门' },
  { value: 'MEDIUM', label: '进阶' },
  { value: 'HARD', label: '挑战' },
  { value: 'MIXED', label: '混合' },
]
const typeDistributionOptions: { key: keyof PracticeTypeDistributionVO; label: string; desc: string }[] = [
  { key: 'choice', label: '选择题', desc: '基本概念 · 知识辨析' },
  { key: 'fill', label: '填空题', desc: '关键词 · 语法 / 术语' },
  { key: 'subjective', label: '主观题', desc: '场景 / 项目 / 设计 / 代码' },
]
const configuredTotal = computed(() => {
  const dist = practiceConfig.value.typeDistribution
  return dist.choice + dist.fill + dist.subjective
})
const configValid = computed(() => configuredTotal.value === practiceConfig.value.totalQuestions && configuredTotal.value > 0)

watch(() => practiceConfig.value.totalQuestions, total => normalizeDistribution(total))

function normalizeDistribution(total: number) {
  const dist = practiceConfig.value.typeDistribution
  const current = dist.choice + dist.fill + dist.subjective
  if (!current || current === total) return
  const keys: (keyof PracticeTypeDistributionVO)[] = ['choice', 'fill', 'subjective']
  const next: PracticeTypeDistributionVO = { choice: 0, fill: 0, subjective: 0 }
  let allocated = 0
  keys.forEach((key, index) => {
    const value = index === keys.length - 1
      ? total - allocated
      : Math.min(dist[key], Math.max(0, total - allocated))
    next[key] = value
    allocated += value
  })
  practiceConfig.value.typeDistribution = next
}

function buildPracticeConfig(planId?: string, planItemId?: string): PracticeConfigVO {
  return {
    totalQuestions: practiceConfig.value.totalQuestions,
    typeDistribution: { ...practiceConfig.value.typeDistribution },
    difficulty: practiceConfig.value.difficulty,
    direction: practiceConfig.value.direction,
    planId,
    planItemId,
  }
}

const groupGeneratingItemId = ref<string | null>(null)
const groupTaskError = ref<unknown>(null)

async function startGroupPractice(item: PlanItemVO) {
  if (!selectedPlan.value || item.kind !== 'PRACTICE') return
  if (!configValid.value) {
    ElMessage.warning('题型分布之和需要等于每组题目数，请先在右侧完成配置')
    return
  }
  const planId = selectedPlan.value.id
  groupTaskError.value = null
  practiceActionError.value = null
  groupGeneratingItemId.value = item.id
  try {
    // 点击即进入主框架内的"题目生成/答题"页面，不在主页内嵌答题
    const task = await practiceApi.generateSet(buildPracticeConfig(planId, item.id))
    await openPreparePage(task.id)
  } catch (reason) {
    if (isEndpointMissing(reason)) {
      groupTaskError.value = new Error('整组出题接口暂不可用，请检查后端服务是否已更新并重启。当前配置尚未生成题目。')
    } else {
      const existingSessionId = conflictSessionId(reason)
      if (existingSessionId) {
        try {
          await ElMessageBox.confirm('该训练单元已有进行中的练习，当前配置尚未生成题目。可先继续已有练习，完成后再生成新题组。', '已有进行中的练习', {
            confirmButtonText: '继续已有练习',
            cancelButtonText: '返回',
            type: 'info',
          })
          await openPracticeSet(existingSessionId, true)
        } catch {
          // 用户选择返回时保留当前配置
        }
      } else {
        groupTaskError.value = reason
      }
    }
  } finally {
    groupGeneratingItemId.value = null
  }
}

// ===== 主页统计（顶部概览条） =====
const heroPlan = computed(() => selectedPlan.value || plans.value.records[0] || null)
const heroPlanProgress = computed(() => heroPlan.value ? planProgress(heroPlan.value) : { completed: 0, total: 0, percentage: 0 })
const pendingPracticeItems = computed(() => heroPlan.value?.items.filter(item => item.kind === 'PRACTICE' && item.status !== 'COMPLETED') || [])
const pendingPracticeMinutes = computed(() => pendingPracticeItems.value.reduce((sum, item) => sum + item.estimatedMinutes, 0))
const totalCompletedUnits = computed(() => plans.value.records.reduce((sum, plan) => sum + plan.items.filter(item => item.status === 'COMPLETED').length, 0))

function goInterview(item: PlanItemVO) {
  void router.push({ path: '/interview', query: { planItemId: item.id } })
}

const mistakes = ref<Page<MistakeVO>>(emptyPage())
const mistakesLoading = ref(false)
const mistakesError = ref<unknown>(null)
const mistakeResolved = ref(false)
const mistakeDialogVisible = ref(false)
const selectedMistake = ref<MistakeVO | null>(null)
const mistakeAnswer = ref<LearningAnswerVO | null>(null)
const mistakeAnswerLoading = ref(false)
const mistakeAnswerError = ref<unknown>(null)
const retryingMistakeId = ref<string | null>(null)

async function loadMistakes() {
  mistakesLoading.value = true
  mistakesError.value = null
  try {
    mistakes.value = await learningApi.mistakes({ current: mistakes.value.current, size: mistakes.value.size, resolved: mistakeResolved.value })
  } catch (reason) {
    mistakesError.value = reason
  } finally {
    mistakesLoading.value = false
  }
}

function changeMistakeFilter() {
  mistakes.value.current = 1
  void loadMistakes()
}

function changeMistakePage(page: number) {
  mistakes.value.current = page
  void loadMistakes()
}

function changeMistakeSize(size: number) {
  mistakes.value.size = size
  mistakes.value.current = 1
  void loadMistakes()
}

async function openMistake(mistake: MistakeVO) {
  selectedMistake.value = mistake
  mistakeAnswer.value = null
  mistakeAnswerError.value = null
  mistakeAnswerLoading.value = true
  mistakeDialogVisible.value = true
  try {
    mistakeAnswer.value = await learningApi.getAnswer(mistake.lastAnswerId)
  } catch (reason) {
    mistakeAnswerError.value = reason
  } finally {
    mistakeAnswerLoading.value = false
  }
}

async function retryMistake(mistake: MistakeVO) {
  retryingMistakeId.value = mistake.id
  practiceActionError.value = null
  try {
    // 错题重练走"单题练习集"：复用原题、保留原题型，在主框架内的答题页完成
    const set = await learningApi.retryMistakePractice(mistake.id)
    await openPracticeSet(set.id)
    ElMessage.success('已按原题创建单题练习组，正在打开答题页面')
  } catch (reason) {
    const existingSessionId = conflictSessionId(reason)
    if (existingSessionId) {
      ElMessage.info('当前计划项已有活动训练，正在打开该训练')
      await openPracticeSet(existingSessionId, true)
    } else {
      practiceActionError.value = reason
    }
  } finally {
    retryingMistakeId.value = null
  }
}

function categoryLabel(category: PlanItemVO['category']) {
  return ({ BASIC: '基础知识', RESUME_PROJECT: '简历项目深挖', GITHUB: 'GitHub 项目学习' } as Record<string, string>)[category] || category
}

function planSourceLabel(plan: LearningPlanVO) {
  return plan.source === 'RESUME' ? '简历生成' : '岗位匹配'
}

function planDirectionLabel(plan: LearningPlanVO) {
  const direction = plan.targetDirection
  if (direction) return getJobRoleLabel(direction)
  return plan.matchSummary?.title || '未设置方向'
}

function githubNotice(plan: LearningPlanVO) {
  const github = plan.basis?.github
  if (!github || github.status === 'PENDING') return 'GitHub 推荐数据尚未返回，不影响其他训练内容。'
  if (github.status === 'AVAILABLE') {
    return `GitHub 推荐来自公开检索，共 ${github.candidateCount || 0} 个候选，获取于 ${formatDate(github.fetchedAt)}。`
  }
  if (github.status === 'DISABLED') return '当前计划未启用 GitHub 推荐，可继续使用其他训练内容。'
  return `GitHub 推荐当前不可用${github.reason ? `：${github.reason}` : ''}，可继续使用其他训练内容。`
}

function githubNoticeType(plan: LearningPlanVO): 'success' | 'info' | 'warning' {
  const status = plan.basis?.github?.status
  if (status === 'AVAILABLE') return 'success'
  if (status === 'UNAVAILABLE') return 'warning'
  return 'info'
}

function safeLinks(item: PlanItemVO) {
  return item.resourceLinks.filter(link => /^https:\/\/github\.com\//i.test(link.url))
}

const planDialogVisible = ref(false)
const planContextLoading = ref(false)
const hasResume = ref(true)
const planError = ref<unknown>(null)
const planForm = ref({
  resumeVersionId: '',
  targetDirection: 'JAVA_BACKEND',
  dailyMinutes: 30,
  days: 7,
  matchItemId: '',
})
const matchOptions = ref<MatchSummaryVO[]>([])
const directionOptions = JOB_ROLE_LIST.map(({ value, label }) => ({ value, label }))
const planTaskId = ref<string | null>(null)
const planTracker = useTask(planTaskId, { onDone: handlePlanDone })
const planTask = planTracker.task
const planTrackingError = planTracker.error
const planTaskActive = computed(() => taskIsActive(planTask.value))
const planFormValid = computed(() => !!planForm.value.targetDirection && planForm.value.dailyMinutes >= 10 && planForm.value.days >= 1)

async function loadPlanContext() {
  planContextLoading.value = true
  try {
    const [resumes, profile] = await Promise.all([
      resumeApi.list({ current: 1, size: 20 }),
      profileApi.get().catch(() => null),
    ])
    const ready = resumes.records.find(item => ['PARSED', 'CONFIRMED'].includes(item.latestVersion?.status))
    hasResume.value = !!ready
    if (ready) planForm.value.resumeVersionId = ready.latestVersion.id
    if (profile?.targetRole) planForm.value.targetDirection = profile.targetRole
    const matches = await jobApi.listMatches({ current: 1, size: 5 }).catch(() => null)
    const items: MatchSummaryVO[] = []
    for (const batch of matches?.records || []) {
      if (batch.status !== 'READY' || batch.isStale) continue
      for (const item of batch.items) {
        if (item.eligibility?.status === 'FAIL') continue
        items.push({ id: item.id, title: item.title, companyName: null, matchScore: item.matchScore })
      }
    }
    matchOptions.value = items.slice(0, 10)
  } catch (reason) {
    planError.value = reason
  } finally {
    planContextLoading.value = false
  }
}

function openPlanDialog() {
  planError.value = null
  planDialogVisible.value = true
  void loadPlanContext()
}

function goResumeCenter() {
  planDialogVisible.value = false
  void router.push('/resume')
}

function resumeRequired(reason: unknown) {
  return reason instanceof ApiError && reason.status === 409 && reason.details?.action === 'GO_RESUME'
}

async function submitPlanFromResume() {
  if (!hasResume.value) {
    goResumeCenter()
    return
  }
  if (planTaskActive.value || !planFormValid.value) return
  planError.value = null
  const body: Parameters<typeof learningApi.createPlanFromResume>[0] = {
    dailyMinutes: planForm.value.dailyMinutes,
    days: planForm.value.days,
    targetDirection: planForm.value.targetDirection,
  }
  if (planForm.value.resumeVersionId) body.resumeVersionId = planForm.value.resumeVersionId
  if (planForm.value.matchItemId) body.matchItemId = planForm.value.matchItemId
  try {
    planTaskId.value = (await learningApi.createPlanFromResume(body)).id
  } catch (reason) {
    if (resumeRequired(reason)) {
      hasResume.value = false
      planError.value = new Error('当前没有可用简历，请先上传并解析简历')
    } else {
      planError.value = reason
    }
  }
}

async function retryPlanTask() {
  if (!planTask.value) return
  planError.value = null
  try {
    planTaskId.value = (await retryTrackedTask(planTask.value)).id
  } catch (reason) {
    planError.value = reason
  }
}

async function handlePlanDone(task: TaskVO) {
  const planId = task.result?.resourceId || task.resourceId
  if (!planId) {
    planError.value = new Error('任务完成但未返回训练计划ID')
    return
  }
  activeTab.value = 'plans'
  await loadPlans()
  await loadPlan(planId, true, true)
  if (!planDetailError.value) {
    planDialogVisible.value = false
    ElMessage.success('训练计划已生成，已读取计划依据与训练项')
  }
}

async function openPlanItem(planItemId: string) {
  if (!planItemId) return
  if (!plans.value.records.some(plan => plan.items.some(item => item.id === planItemId))) {
    await loadPlans()
  }
  const plan = plans.value.records.find(item => item.items.some(entry => entry.id === planItemId))
  if (plan) {
    await loadPlan(plan.id, true, true)
    setQuery({ planItemId: undefined })
  }
}

function loadActiveTab() {
  void refreshCurrentProfile()
  if (activeTab.value === 'plans') void loadPlans()
  else void loadMistakes()
}

watch(activeTab, tab => {
  setQuery({ tab })
  loadActiveTab()
})

onMounted(async () => {
  loadActiveTab()
  const planId = queryValue(route.query.planId)
  const sessionId = queryValue(route.query.sessionId)
  const planItemId = queryValue(route.query.planItemId)
  if (sessionId) {
    // 旧链接（主页内嵌答题）统一跳转到主框架内的答题页面
    await router.replace({ name: 'training-practice', params: { setId: sessionId }, query: { legacy: '1' } })
    return
  }
  if (planId) await loadPlan(planId)
  else if (planItemId) await openPlanItem(planItemId)
})
</script>

<template>
  <div class="page training-page">
    <header class="page-header">
      <div>
        <h1><el-icon><Edit /></el-icon> 专项训练 <span class="page-header__en">TRAINING CENTER</span></h1>
        <p>一次预生成整组题目，按题目列表连续完成训练，让每次练习有明确目标。</p>
      </div>
      <div class="actions page-header__actions">
        <el-button type="primary" :icon="Plus" @click="openPlanDialog">新建训练计划</el-button>
        <el-button :icon="Refresh" @click="loadActiveTab">刷新当前列表</el-button>
      </div>
    </header>

    <template v-if="activeTab === 'plans'">
      <section v-if="heroPlan" class="hero-banner">
        <div class="hero-banner__text">
          <span class="hero-banner__eyebrow">MY LEARNING PATH · {{ commonStatusLabels[heroPlan.status] || heroPlan.status }}</span>
          <h2>{{ heroPlan.title }}</h2>
          <p>{{ heroPlan.basis?.summary || `${planDirectionLabel(heroPlan)} · 共 ${heroPlan.items.length} 个训练项` }}</p>
          <div class="hero-banner__tags">
            <span class="hero-tag">{{ planDirectionLabel(heroPlan) }}</span>
            <span class="hero-tag">{{ heroPlan.dailyMinutes }} 分钟 / 天 · 周期 {{ heroPlan.days }} 天</span>
          </div>
        </div>
        <div class="hero-banner__progress">
          <strong>{{ heroPlanProgress.percentage }}%</strong>
          <span>本次完成进度</span>
          <el-progress type="circle" :percentage="heroPlanProgress.percentage" :width="72" :stroke-width="8" />
        </div>
      </section>

      <div class="stats-row">
        <div class="stat-card">
          <span class="stat-card__label">今日练题</span>
          <strong>{{ pendingPracticeItems.length * practiceConfig.totalQuestions }}</strong>
          <span class="stat-card__unit">道题</span>
        </div>
        <div class="stat-card">
          <span class="stat-card__label">预计训练时长</span>
          <strong>{{ pendingPracticeMinutes }}</strong>
          <span class="stat-card__unit">分钟</span>
        </div>
        <div class="stat-card">
          <span class="stat-card__label">累计完成</span>
          <strong>{{ totalCompletedUnits }}</strong>
          <span class="stat-card__unit">个训练单元</span>
        </div>
      </div>
    </template>

    <el-tabs v-model="activeTab" class="training-tabs">
      <el-tab-pane label="训练计划" name="plans" />
      <el-tab-pane label="错题本" name="mistakes" />
    </el-tabs>

    <template v-if="activeTab === 'plans'">
      <div class="training-layout" :class="{ 'training-layout--home': !selectedPlan }">
        <section class="panel plan-list-panel">
          <div class="panel__heading">
            <h2>我的训练计划</h2>
            <el-tag type="info">{{ plans.total }} 个计划</el-tag>
          </div>
          <AsyncState :loading="plansLoading" :error="plansError" :empty="!plans.records.length" empty-text="暂无训练计划" @retry="loadPlans">
            <template #empty>
              <p class="empty-hint">可以直接基于已解析简历生成计划，也可以从岗位匹配结果创建计划。</p>
              <div class="actions empty-actions">
                <el-button type="primary" @click="openPlanDialog">基于简历生成计划</el-button>
                <el-button @click="router.push('/jobs?tab=matches')">前往岗位匹配</el-button>
              </div>
            </template>
            <div class="list-stack">
              <button v-for="plan in plans.records" :key="plan.id" type="button" class="plan-card" :class="{ 'plan-card--active': selectedPlan?.id === plan.id }" @click="selectPlan(plan.id)">
                <span class="plan-card__heading">
                  <strong>{{ plan.title }}</strong>
                  <el-tag size="small" :type="statusTagType(plan.status)">{{ commonStatusLabels[plan.status] || plan.status }}</el-tag>
                </span>
                <span class="plan-card__source">
                  <el-tag size="small" :type="plan.source === 'RESUME' ? 'success' : 'info'">{{ planSourceLabel(plan) }}</el-tag>
                  <span class="muted">{{ planDirectionLabel(plan) }} · 第 {{ plan.generationNo }} 版</span>
                </span>
                <span class="muted">画像 v{{ plan.profileVersion }} · {{ plan.items.length }} 个训练项 · 生成于 {{ formatDate(plan.generatedAt) }}</span>
                <el-tag v-if="planOutdatedReasons(plan).length" type="warning" size="small">建议更新：{{ planOutdatedReasons(plan).join('；') }}</el-tag>
                <el-progress :percentage="planProgress(plan).percentage" :stroke-width="7" />
                <span class="plan-card__progress">{{ planProgress(plan).completed }} / {{ planProgress(plan).total }} 已完成</span>
              </button>
            </div>
          </AsyncState>
          <div class="pagination">
            <el-pagination background layout="total, sizes, prev, pager, next" :total="plans.total" :current-page="plans.current" :page-size="plans.size" :page-sizes="[6, 12, 24]" @current-change="changePlanPage" @size-change="changePlanSize" />
          </div>
        </section>

        <section v-if="selectedPlan || planDetailLoading || planDetailError" class="panel plan-detail-panel">
          <AsyncState :loading="planDetailLoading" :error="planDetailError" :empty="!selectedPlan" empty-text="请选择训练计划查看计划项" @retry="loadPlan(planDetailRequestId)">
            <template v-if="selectedPlan">
              <div class="panel__heading">
                <div>
                  <h2>{{ selectedPlan.title }}</h2>
                  <span class="muted break-all">计划ID：{{ selectedPlan.id }} · 画像 v{{ selectedPlan.profileVersion }} · 第 {{ selectedPlan.generationNo }} 版</span>
                </div>
                <div class="actions">
                  <el-tag :type="statusTagType(selectedPlan.status)">{{ commonStatusLabels[selectedPlan.status] || selectedPlan.status }}</el-tag>
                  <el-button size="small" :icon="Refresh" @click="refreshCurrentProfile(); loadPlan(selectedPlan.id)">刷新</el-button>
                </div>
              </div>
              <el-alert v-if="planOutdatedReasons(selectedPlan).length" class="inline-alert" type="warning" :closable="false" show-icon :title="`该计划依据已更新：${planOutdatedReasons(selectedPlan).join('；')}。你可以继续查看旧计划，或生成新计划。`">
                <template #default><el-button type="primary" plain size="small" @click="openPlanDialog">按当前画像生成新计划</el-button></template>
              </el-alert>
              <div class="plan-basis">
                <div class="plan-basis__row">
                  <el-tag :type="selectedPlan.source === 'RESUME' ? 'success' : 'info'" effect="plain">{{ planSourceLabel(selectedPlan) }}</el-tag>
                  <span v-if="selectedPlan.source === 'RESUME'">
                    简历 v{{ selectedPlan.basis?.resumeVersionNo || '-' }}
                    <template v-if="selectedPlan.basis?.resumeVersionId"> · {{ selectedPlan.basis.resumeVersionId }}</template>
                  </span>
                  <span>目标方向：{{ selectedPlan.targetDirection ? getJobRoleLabel(selectedPlan.targetDirection) : '未设置' }}</span>
                  <span>生成时间：{{ formatDate(selectedPlan.generatedAt) }}</span>
                </div>
                <div class="plan-basis__row">
                  <span>每日投入 {{ selectedPlan.dailyMinutes }} 分钟 · 周期 {{ selectedPlan.days }} 天</span>
                  <span v-if="selectedPlan.matchSummary">参考岗位：{{ selectedPlan.matchSummary.title }}<template v-if="selectedPlan.matchSummary.companyName"> · {{ selectedPlan.matchSummary.companyName }}</template></span>
                  <span v-else>未参考岗位匹配结果</span>
                </div>
                <p v-if="selectedPlan.basis?.summary" class="plan-basis__summary">{{ selectedPlan.basis.summary }}</p>
                <ul v-if="selectedPlan.basis?.limitations?.length" class="plan-basis__limitations">
                  <li v-for="(item, index) in selectedPlan.basis.limitations" :key="index">{{ item }}</li>
                </ul>
              </div>
              <el-alert
                v-if="selectedPlan.source === 'RESUME'"
                :title="githubNotice(selectedPlan)"
                :type="githubNoticeType(selectedPlan)"
                :closable="false"
                show-icon
              />
              <el-alert v-if="selectedPlan.status === 'GENERATING'" title="计划仍在生成，可稍后刷新读取计划项" type="warning" :closable="false" show-icon />
              <el-alert v-else-if="selectedPlan.status === 'FAILED'" title="计划生成失败，请返回匹配结果核对任务状态" type="error" :closable="false" show-icon />
              <el-empty v-else-if="!selectedPlan.items.length" description="该计划尚无训练项" />
              <div v-else class="plan-items">
                <article v-for="(item, index) in selectedPlan.items" :key="item.id" class="plan-item">
                  <div class="plan-item__index">{{ index + 1 }}</div>
                  <div class="plan-item__content">
                    <div class="plan-item__heading">
                      <div>
                        <h3>{{ item.title }}</h3>
                        <p class="plan-item__meta">
                          <el-tag size="small" effect="plain">{{ categoryLabel(item.category) }}</el-tag>
                          <span>{{ item.skillCode }} · {{ kindLabel(item.kind) }} · 预计 {{ item.estimatedMinutes }} 分钟</span>
                        </p>
                      </div>
                      <el-tag :type="statusTagType(item.status)">{{ commonStatusLabels[item.status] || item.status }}</el-tag>
                    </div>
                    <div class="plan-item__guide">
                      <p><strong>训练目标：</strong>{{ item.objective }}</p>
                      <p><strong>推荐原因：</strong>{{ item.reason }}</p>
                      <p><strong>练习方式：</strong>{{ item.method }}</p>
                      <p><strong>完成标准：</strong>{{ item.completionCriteria }}</p>
                      <p v-if="item.evidenceStatus === 'PENDING'" class="plan-item__pending">
                        <el-tag size="small" type="warning">待评估</el-tag>
                        简历未提供该技能的直接证据，训练后再评估能力。
                      </p>
                      <p v-if="item.evidenceQuote" class="muted">简历依据：{{ item.evidenceQuote }}</p>
                    </div>
                    <div v-if="safeLinks(item).length" class="resource-links">
                      <h4>GitHub 项目学习</h4>
                      <a v-for="link in safeLinks(item)" :key="link.url" :href="link.url" target="_blank" rel="noopener noreferrer">
                        {{ link.label }} <span class="muted">{{ link.url }}</span>
                      </a>
                    </div>
                    <div v-if="item.resourceRefs.length" class="resource-refs">
                      <h4>授权资料引用</h4>
                      <blockquote v-for="reference in item.resourceRefs" :key="`${reference.documentId}:${reference.chunkId}`">
                        <p class="pre-wrap">{{ reference.quote }}</p>
                        <span>{{ reference.documentId }} · v{{ reference.documentVersion }} · {{ reference.chunkId }}</span>
                      </blockquote>
                    </div>
                    <el-alert v-if="itemActionErrors[item.id]" :title="errorMessage(itemActionErrors[item.id])" type="error" :closable="false" show-icon />
                    <div class="actions plan-item__actions">
                      <el-button v-if="item.kind === 'READING'" type="primary" :disabled="item.status === 'COMPLETED'" :loading="itemActionPending.includes(item.id)" @click="completeReading(item)">{{ item.status === 'COMPLETED' ? '已自评完成阅读' : itemActionErrors[item.id] ? '重试自评完成' : '我已阅读（自评）' }}</el-button>
                      <el-button v-else-if="item.kind === 'PRACTICE'" type="primary" :disabled="groupGeneratingItemId !== null" :loading="groupGeneratingItemId === item.id" @click="startGroupPractice(item)">
                        {{ item.status === 'COMPLETED' ? '再次生成并练习本组' : '生成并开始本组练习' }}（{{ practiceConfig.totalQuestions }} 道题）
                      </el-button>
                      <el-button v-else type="success" plain @click="goInterview(item)">进入模拟面试 <el-icon><Right /></el-icon></el-button>
                    </div>
                    <p v-if="item.kind === 'PRACTICE'" class="muted practice-jump-note">
                      按右侧「自定义出题」配置预生成本组题目，完成后进入答题页面，可随时返回训练中心。
                    </p>
                    <p v-if="item.kind === 'READING'" class="muted reading-self-check-note">
                      {{ item.status === 'COMPLETED' ? '阅读完成为自评记录（完成时间已保存），不代表系统已验证掌握该技能；建议结合答题训练检验效果。' : '请先阅读上方授权资料，确认理解后自评完成；该记录为自评性质，不会计入技能评分证据。' }}
                    </p>
                    <p v-if="item.kind === 'READING' && item.completedAt" class="muted">自评完成时间：{{ formatDate(item.completedAt) }}</p>
                  </div>
                </article>
              </div>
            </template>
          </AsyncState>
        </section>

        <section class="panel config-panel">
          <div class="panel__heading">
            <div>
              <h2>自定义出题</h2>
              <p class="muted">在开始训练前，预生成整组题目与题型，做完一组再生成下一组。</p>
            </div>
          </div>
          <div class="config-block">
            <h4>每组题目数</h4>
            <div class="config-total">
              <el-input-number v-model="practiceConfig.totalQuestions" :min="5" :max="20" :step="5" />
              <div class="config-total__quick">
                <el-button v-for="size in [5, 10, 15, 20]" :key="size" size="small" :type="practiceConfig.totalQuestions === size ? 'primary' : ''" @click="practiceConfig.totalQuestions = size">{{ size }}</el-button>
              </div>
            </div>
          </div>
          <div class="config-block">
            <h4>题型分布</h4>
            <div v-for="option in typeDistributionOptions" :key="option.key" class="config-type">
              <div class="config-type__text">
                <strong>{{ option.label }}</strong>
                <span>{{ option.desc }}</span>
              </div>
              <el-input-number v-model="practiceConfig.typeDistribution[option.key]" :min="0" :max="practiceConfig.totalQuestions" size="small" />
            </div>
            <p class="config-sum" :class="{ 'config-sum--error': !configValid }">
              已配置题目总数 {{ configuredTotal }} / {{ practiceConfig.totalQuestions }}
              <el-icon v-if="configValid"><CircleCheck /></el-icon>
            </p>
          </div>
          <div class="config-block">
            <h4>训练难度</h4>
            <el-radio-group v-model="practiceConfig.difficulty">
              <el-radio-button v-for="option in difficultyOptions" :key="option.value" :value="option.value">{{ option.label }}</el-radio-button>
            </el-radio-group>
          </div>
          <div class="config-block">
            <h4>训练方向</h4>
            <el-select v-model="practiceConfig.direction" placeholder="选择训练方向">
              <el-option v-for="option in directionOptions" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
          </div>
          <el-alert type="info" :closable="false" show-icon title="整组预生成说明" description="点击左侧任意训练单元下方的「生成并开始本组练习」，将按右侧当前配置一次性预生成整组题目，并进入答题页面；客观题自动判分，主观题交卷后由 AI 统一评估。" />
          <p class="config-generate-hint">配置完成后，请到左侧训练单元卡片下方点击生成。</p>
          <el-alert v-if="groupTaskError" :title="errorMessage(groupTaskError)" type="error" :closable="false" show-icon />
        </section>
      </div>

      <el-alert v-if="practiceActionError" :title="errorMessage(practiceActionError)" type="error" :closable="false" show-icon />
    </template>

    <template v-else>
      <section class="panel">
        <div class="panel__heading">
          <div>
            <h2>错题本</h2>
            <p class="muted">有效评价低于 60 分或明确回答"不会"的题目会进入待复习；重新练习会创建新会话并保留历史回答。</p>
          </div>
          <el-radio-group v-model="mistakeResolved" @change="changeMistakeFilter">
            <el-radio-button :label="false">待攻克</el-radio-button>
            <el-radio-button :label="true">已解决</el-radio-button>
          </el-radio-group>
        </div>
        <el-alert v-if="practiceActionError" :title="errorMessage(practiceActionError)" type="error" :closable="false" show-icon />
        <AsyncState :loading="mistakesLoading" :error="mistakesError" :empty="!mistakes.records.length" :empty-text="mistakeResolved ? '暂无已解决错题' : '暂无待攻克错题'" @retry="loadMistakes">
          <div class="mistake-list">
            <article v-for="mistake in mistakes.records" :key="mistake.id" class="mistake-card">
              <div class="mistake-card__heading">
                <div class="question-card__meta">
                  <el-tag size="small" type="primary" effect="plain">{{ difficultyLabel(mistake.question.difficulty) }}</el-tag>
                  <el-tag v-for="skill in mistake.question.skillCodes" :key="skill" size="small" type="info">{{ skill }}</el-tag>
                  <el-tag size="small" :type="mistake.resolved ? 'success' : 'danger'">{{ mistake.resolved ? '已解决' : (mistake.cause === 'UNKNOWN' ? `不会 · ${mistake.wrongCount} 次` : `错误 ${mistake.wrongCount} 次`) }}</el-tag>
                </div>
                <span class="muted">题目 v{{ mistake.question.questionVersion }}</span>
              </div>
              <p class="pre-wrap mistake-stem">{{ mistake.question.stem }}</p>
              <p class="muted break-all">最近回答ID：{{ mistake.lastAnswerId }}</p>
              <div class="actions">
                <el-button type="primary" plain @click="openMistake(mistake)">查看题目与原回答</el-button>
                <el-button type="primary" :loading="retryingMistakeId === mistake.id" :disabled="groupGeneratingItemId !== null" @click="retryMistake(mistake)">重新练习</el-button>
              </div>
            </article>
          </div>
        </AsyncState>
        <div class="pagination">
          <el-pagination background layout="total, sizes, prev, pager, next" :total="mistakes.total" :current-page="mistakes.current" :page-size="mistakes.size" :page-sizes="[6, 12, 24]" @current-change="changeMistakePage" @size-change="changeMistakeSize" />
        </div>
      </section>
    </template>

    <el-dialog v-model="planDialogVisible" title="生成专项训练计划" width="620px" class="responsive-dialog">
      <div v-loading="planContextLoading" class="plan-dialog">
        <el-alert v-if="!hasResume" type="warning" :closable="false" show-icon title="没有可用简历">
          <p>请先在简历中心上传简历并完成解析，再生成专项训练计划。</p>
          <el-button type="primary" size="small" @click="goResumeCenter">前往简历中心</el-button>
        </el-alert>
        <template v-else>
          <el-form label-width="96px">
            <el-form-item label="目标方向">
              <el-select v-model="planForm.targetDirection" placeholder="请选择目标方向">
                <el-option v-for="option in directionOptions" :key="option.value" :label="option.label" :value="option.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="每天投入">
              <el-input-number v-model="planForm.dailyMinutes" :min="10" :max="120" :step="5" />
              <span class="form-hint">分钟 / 天</span>
            </el-form-item>
            <el-form-item label="计划周期">
              <el-input-number v-model="planForm.days" :min="1" :max="14" />
              <span class="form-hint">天</span>
            </el-form-item>
            <el-form-item label="参考岗位">
              <el-select v-model="planForm.matchItemId" clearable placeholder="可选：参考最近一次岗位匹配">
                <el-option
                  v-for="option in matchOptions"
                  :key="option.id"
                  :label="`${option.title}${option.matchScore === null ? '' : ` · ${option.matchScore} 分`}`"
                  :value="option.id"
                />
              </el-select>
            </el-form-item>
          </el-form>
          <p class="muted">计划将基于简历版本 {{ planForm.resumeVersionId || '当前可用版本' }} 生成，历史计划与进度会保留。</p>
          <el-alert v-if="planError" :title="errorMessage(planError)" type="error" :closable="false" show-icon />
          <TaskStatus v-if="planTask" :task="planTask" :reasoning="planTracker.reasoningText.value">
            <template #actions>
              <el-button v-if="canRetryTrackedTask(planTask)" size="small" type="primary" @click="retryPlanTask">按原参数重试</el-button>
            </template>
          </TaskStatus>
          <div v-if="planTrackingError" class="tracking-error">
            <span>{{ errorMessage(planTrackingError) }}</span>
            <el-button size="small" @click="planTracker.restart">恢复任务跟踪</el-button>
          </div>
        </template>
      </div>
      <template #footer>
        <el-button @click="planDialogVisible = false">关闭</el-button>
        <el-button v-if="!hasResume" type="primary" @click="goResumeCenter">前往简历中心</el-button>
        <el-button v-else type="primary" :loading="planTaskActive" :disabled="planTaskActive || !planFormValid" @click="submitPlanFromResume">{{ planError ? '重试生成' : '生成计划' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="mistakeDialogVisible" title="错题与原回答" width="780px" class="responsive-dialog">
      <template v-if="selectedMistake">
        <div class="question-card mistake-dialog-question">
          <div class="question-card__meta">
            <el-tag type="primary" effect="plain">{{ difficultyLabel(selectedMistake.question.difficulty) }}</el-tag>
            <el-tag effect="plain">{{ questionTypeLabel(selectedMistake.question.type) }}</el-tag>
            <el-tag v-for="skill in selectedMistake.question.skillCodes" :key="skill" type="info">{{ skill }}</el-tag>
          </div>
          <h3>题目</h3>
          <p class="pre-wrap question-stem">{{ selectedMistake.question.stem }}</p>
          <span class="muted">题目版本 v{{ selectedMistake.question.questionVersion }}</span>
        </div>
        <AsyncState :loading="mistakeAnswerLoading" :error="mistakeAnswerError" :empty="!mistakeAnswer" empty-text="未读取到原回答" @retry="openMistake(selectedMistake)">
          <template v-if="mistakeAnswer">
            <div class="original-answer">
              <h3>lastAnswerId 对应原回答</h3>
              <p class="pre-wrap">{{ mistakeAnswer.content }}</p>
              <span class="muted break-all">{{ mistakeAnswer.id }} · 会话 {{ mistakeAnswer.sessionId }}</span>
            </div>
            <template v-if="mistakeAnswer.evaluation">
              <div class="evaluation-overview">
                <div class="overall-score"><strong>{{ mistakeAnswer.evaluation.overallScore === null ? '待评估' : displayScore(mistakeAnswer.evaluation.overallScore) }}</strong><span>综合得分</span></div>
                <el-tag :type="statusTagType(mistakeAnswer.evaluation.evidenceStatus)">{{ evidenceLabel(mistakeAnswer.evaluation.evidenceStatus) }}</el-tag>
              </div>
              <div class="feedback-grid">
                <section class="feedback-card feedback-card--strength"><h3>优点</h3><ul><li v-for="(item, index) in mistakeAnswer.evaluation.strengths" :key="index">{{ item }}</li></ul></section>
                <section class="feedback-card feedback-card--improvement"><h3>改进建议</h3><ul><li v-for="(item, index) in mistakeAnswer.evaluation.improvements" :key="index">{{ item }}</li></ul></section>
              </div>
              <section class="reference-answer"><h3>参考答案</h3><p class="pre-wrap">{{ mistakeAnswer.evaluation.referenceAnswer || '当前评价未提供参考答案' }}</p></section>
            </template>
            <el-alert v-else title="该原回答暂无可展示的评价结果" type="warning" :closable="false" show-icon />
          </template>
        </AsyncState>
      </template>
      <template #footer>
        <el-button @click="mistakeDialogVisible = false">关闭</el-button>
        <el-button v-if="selectedMistake" type="primary" :loading="selectedMistake && retryingMistakeId === selectedMistake.id" @click="selectedMistake && retryMistake(selectedMistake)">重新练习此题</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.training-page { padding-bottom: 28px; }
.page-header__actions { align-items: center; }
.empty-hint { margin: 0 0 12px; color: $ai-text-secondary; }
.empty-actions { justify-content: center; }
.plan-card__source { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.plan-basis { display: grid; gap: 6px; margin: 14px 0; padding: 14px 16px; border-radius: $ai-radius; background: $ai-bg-page; color: $ai-text-secondary; font-size: 13px; }
.plan-basis__row { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 18px; }
.plan-basis__summary { margin: 4px 0 0; color: #101d4b; }
.plan-basis__limitations { margin: 4px 0 0; padding-left: 18px; }
.plan-item__meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.plan-item__guide { display: grid; gap: 4px; margin: 12px 0; color: $ai-text-secondary; font-size: 13px; }
.plan-item__guide p { margin: 0; }
.plan-item__guide strong { color: #101d4b; }
.plan-item__pending { display: flex; align-items: center; gap: 8px; }
.resource-links { display: grid; gap: 6px; margin: 12px 0; }
.resource-links h4 { margin: 0 0 4px; }
.resource-links a { word-break: break-all; }
.plan-dialog { min-height: 120px; }
.form-hint { margin-left: 8px; color: $ai-text-secondary; font-size: 12px; }
.page-header h1 { display: flex; align-items: center; gap: 10px; }
.training-tabs { margin-bottom: 16px; padding: 0 16px; border: 1px solid #e8f0fa; border-radius: $ai-radius-lg; background: #fff; }
.training-layout { display: grid; grid-template-columns: 320px minmax(0, 1fr) 300px; align-items: start; gap: 14px; }
.training-layout--home { grid-template-columns: minmax(0, 1fr) 340px; }
.plan-list-panel { min-width: 0; }
.plan-detail-panel { min-width: 0; }
.config-panel { position: sticky; top: 76px; display: grid; gap: 14px; align-self: start; }
.config-block { display: grid; gap: 8px; }
.config-block h4 { margin: 0; font-size: 13px; color: $ai-text-primary; }
.config-total { display: grid; gap: 8px; }
.config-total__quick { display: flex; gap: 8px; }
.config-type { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 8px 10px; border: 1px solid $ai-border-light; border-radius: $ai-radius; background: $ai-bg-page; }
.config-type__text { display: grid; gap: 2px; }
.config-type__text strong { font-size: 13px; color: $ai-text-primary; }
.config-type__text span { color: $ai-text-tertiary; font-size: 11px; }
.config-sum { display: flex; align-items: center; gap: 6px; margin: 0; color: $ai-text-secondary; font-size: 12px; }
.config-sum--error { color: $ai-danger; }
.config-generate-hint { margin: 0; padding: 10px 12px; border: 1px dashed #bfdbfe; border-radius: $ai-radius; color: #1e40af; font-size: 12px; line-height: 1.6; background: $ai-primary-light; }
.hero-banner { display: flex; align-items: center; justify-content: space-between; gap: 24px; margin-bottom: 14px; padding: 24px 28px; border-radius: $ai-radius-lg; background: linear-gradient(105deg, #2563eb 0%, #1d4ed8 60%, #1e40af 100%); color: #fff; }
.hero-banner__eyebrow { color: rgba(255, 255, 255, 0.72); font-size: 12px; letter-spacing: 1px; }
.hero-banner h2 { margin: 6px 0 6px; font-size: 24px; }
.hero-banner p { margin: 0 0 12px; max-width: 560px; color: rgba(255, 255, 255, 0.82); font-size: 13px; line-height: 1.6; }
.hero-banner__tags { display: flex; flex-wrap: wrap; gap: 8px; }
.hero-tag { padding: 3px 10px; border: 1px solid rgba(255, 255, 255, 0.35); border-radius: 999px; font-size: 12px; }
.hero-banner__progress { display: grid; justify-items: center; gap: 4px; flex-shrink: 0; }
.hero-banner__progress strong { font-size: 24px; }
.hero-banner__progress span { color: rgba(255, 255, 255, 0.72); font-size: 12px; }
.hero-banner__progress :deep(.el-progress__text) { color: #fff; }
.stats-row { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-bottom: 14px; }
.stat-card { display: flex; align-items: baseline; gap: 8px; padding: 16px 18px; border: 1px solid $ai-border-light; border-radius: $ai-radius-lg; background: #fff; box-shadow: $ai-shadow-card; }
.stat-card__label { color: $ai-text-secondary; font-size: 12px; }
.stat-card strong { color: #101d4b; font-size: 26px; line-height: 1; }
.stat-card__unit { color: $ai-text-tertiary; font-size: 12px; }
.page-header__en { margin-left: 8px; color: $ai-text-tertiary; font-size: 12px; font-weight: 500; letter-spacing: 1px; }
.break-all { word-break: break-all; }
.plan-card { display: grid; grid-template-columns: minmax(0, 1fr); min-width: 0; gap: 10px; width: 100%; padding: 15px; border: 1px solid $ai-border; border-radius: $ai-radius; color: inherit; text-align: left; background: #fff; cursor: pointer; overflow-wrap: anywhere; }
.plan-card :deep(.el-tag) { max-width: 100%; height: auto; min-height: 20px; white-space: normal; }
.plan-card :deep(.el-tag__content) { overflow-wrap: anywhere; }
.plan-card:hover, .plan-card--active { border-color: $ai-primary; background: $ai-primary-light; }
.plan-card__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.plan-card__heading strong { min-width: 0; color: #101d4b; line-height: 1.5; }
.plan-card__heading > .el-tag { flex-shrink: 0; }
.plan-card__progress { color: $ai-text-secondary; font-size: 12px; text-align: right; }
.plan-items { display: grid; gap: 12px; margin-top: 16px; }
.plan-item { display: flex; gap: 12px; padding: 15px; border: 1px solid $ai-border; border-radius: $ai-radius; }
.plan-item__index { display: flex; flex: 0 0 28px; align-items: center; justify-content: center; height: 28px; border-radius: 50%; color: $ai-primary; font-weight: 700; background: $ai-primary-light; }
.plan-item__content { min-width: 0; flex: 1; }
.plan-item__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.plan-item__heading h3 { margin: 0 0 6px; color: #101d4b; font-size: 16px; }
.plan-item__heading p { margin: 0; color: $ai-text-secondary; font-size: 13px; }
.plan-item__actions { margin-top: 13px; }
.plan-item__actions .el-button { min-width: 220px; }
.practice-jump-note { margin: 8px 0 0; font-size: 12px; line-height: 1.6; }
.resource-refs { margin: 14px 0; }
.resource-refs h4 { margin: 0 0 8px; }
.resource-refs blockquote { margin: 8px 0; padding: 10px 12px; border-left: 3px solid $ai-primary; background: $ai-bg-page; }
.resource-refs blockquote p { margin: 0 0 6px; }
.resource-refs blockquote span { color: $ai-text-tertiary; font-size: 11px; word-break: break-all; }
.tracking-error { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 10px; color: $ai-danger; font-size: 13px; }
.session-panel { margin-top: 14px; }
.question-card { padding: 18px; border: 1px solid #dbe7fa; border-radius: $ai-radius; background: $ai-primary-light; }
.question-card__meta { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; }
.question-card h3 { margin: 16px 0 8px; color: #101d4b; }
.question-stem { margin: 0; color: #101d4b; font-size: 17px; font-weight: 500; }
.question-code-block { margin: 10px 0; padding: 12px 14px; border: 1px solid #dbe7fa; border-radius: 8px; background: #f4f7fd; overflow-x: auto; }
.question-code-block code { font-family: 'JetBrains Mono', Consolas, Monaco, monospace; font-size: 13px; line-height: 1.6; color: #1f3576; white-space: pre; }
.question-code { min-width: 0; margin-top: 12px; }
.question-code__toolbar { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.question-source { display: flex; flex-wrap: wrap; gap: 8px 18px; margin-top: 14px; color: $ai-text-secondary; font-size: 12px; }
.answer-editor { margin-top: 18px; }
.answer-editor__heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.answer-editor__heading h3 { margin: 0; }
.answer-editor__heading span { color: $ai-text-secondary; }
.answer-editor__heading .limit-warning { color: $ai-danger; }
.answer-editor .el-alert { margin-top: 12px; }
.answer-editor .actions { margin-top: 14px; }
.draft-status { margin: 7px 0 0; color: $ai-text-secondary; font-size: 12px; }
.error-text { color: $ai-danger; }
.evaluation-task { margin-top: 18px; }
.answer-result { margin-top: 22px; padding-top: 18px; border-top: 1px solid $ai-border; }
.original-answer { margin-bottom: 12px; padding: 14px; border-radius: $ai-radius; background: $ai-bg-page; }
.original-answer h3 { margin: 0 0 9px; font-size: 15px; }
.original-answer p { margin: 0 0 8px; }
.answer-result .el-alert { margin-top: 12px; }
.evaluation-overview { display: flex; align-items: center; gap: 22px; margin: 18px 0; padding: 16px; border-radius: $ai-radius; background: $ai-bg-page; }
.overall-score { display: grid; min-width: 92px; text-align: center; }
.overall-score strong { color: $ai-primary; font-size: 32px; line-height: 1.2; }
.overall-score span { color: $ai-text-secondary; font-size: 12px; }
.evaluation-overview p { margin: 7px 0 0; }
.detail-block { overflow-x: auto; }
.detail-block h3 { margin: 0 0 10px; }
.detail-block :deep(.el-table) { min-width: 560px; }
.feedback-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 16px; }
.feedback-card { padding: 15px; border-radius: $ai-radius; }
.feedback-card h3 { margin: 0 0 10px; font-size: 16px; }
.feedback-card ul { display: grid; gap: 7px; margin: 0; padding-left: 20px; line-height: 1.65; }
.feedback-card--strength { background: #ecfdf5; }
.feedback-card--improvement { background: #fff7ed; }
.reference-answer { margin-top: 14px; padding: 16px; border-left: 4px solid $ai-primary; border-radius: $ai-radius; background: $ai-primary-light; }
.reference-answer h3 { margin: 0 0 10px; }
.reference-answer p { margin: 0; }
.mistake-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 16px; }
.mistake-card { padding: 16px; border: 1px solid $ai-border; border-radius: $ai-radius; background: #fff; }
.mistake-card__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.mistake-stem { min-height: 54px; margin: 16px 0; color: #101d4b; font-size: 16px; font-weight: 500; }
.mistake-dialog-question { margin-bottom: 16px; }
:global(.responsive-dialog) { max-width: calc(100vw - 24px); }
@media (max-width: 1100px) {
  .training-layout { grid-template-columns: 320px minmax(0, 1fr); }
  .training-layout--home { grid-template-columns: minmax(0, 1fr) 320px; }
  .config-panel { position: static; grid-column: 1 / -1; }
  .stats-row { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .hero-banner { flex-direction: column; align-items: flex-start; }
}
@media (max-width: 700px) {
  .training-layout { grid-template-columns: 1fr; }
  .training-layout--home { grid-template-columns: 1fr; }
  .stats-row { grid-template-columns: 1fr; }
  .training-tabs { padding: 0 8px; }
  .feedback-grid, .mistake-list { grid-template-columns: 1fr; }
  .plan-item { padding: 12px; }
  .plan-item__actions .el-button { width: 100%; min-width: 0; }
  .plan-item__heading, .mistake-card__heading { flex-direction: column; }
  .evaluation-overview { align-items: flex-start; flex-direction: column; }
  .pagination { justify-content: center; overflow-x: auto; }
}
</style>
