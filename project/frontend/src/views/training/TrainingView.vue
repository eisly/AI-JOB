<script setup lang="ts">
import LoadingSkeleton from '@/components/LoadingSkeleton.vue'
import WaitStatus from '@/components/WaitStatus.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, CircleCheck, Clock, Document, Operation } from '@element-plus/icons-vue'
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
import TrainingFlowHeader from '@/components/training/TrainingFlowHeader.vue'
import { commonStatusLabels, displayScore, errorMessage, formatDate } from '@/utils/format'

type TrainingTab = 'plans' | 'mistakes'

const route = useRoute()
const router = useRouter()
const activeTab = ref<TrainingTab>(route.query.tab === 'mistakes' ? 'mistakes' : 'plans')
const currentProfile = ref<ProfileVO | null>(null)
const view = computed(() => queryValue(route.query.view))

function returnToTraining() {
  setQuery({ view: undefined, planItemId: undefined, mistakeId: undefined, mistakePage: undefined, mistakeSize: undefined, mistakeResolved: undefined })
}

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
  return ({ TEXT_SCENARIO: '场景题', TEXT_KNOWLEDGE: '知识题', CHOICE: '选择题', FILL: '填空题', SUBJECTIVE: '主观题' } as Record<string, string>)[type] || type
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

async function loadPlan(planId: string) {
  if (!planId) return
  planDetailRequestId.value = planId
  const sequence = ++planLoadSequence
  planDetailLoading.value = true
  planDetailError.value = null
  try {
    const plan = await learningApi.getPlan(planId)
    if (sequence !== planLoadSequence) return
    selectedPlan.value = plan
  } catch (reason) {
    if (sequence === planLoadSequence) planDetailError.value = reason
  } finally {
    if (sequence === planLoadSequence) planDetailLoading.value = false
  }
}

async function selectPlan(planId: string) {
  await loadPlan(planId)
  if (!planDetailError.value && selectedPlan.value?.id === planId) {
    setQuery({ tab: 'plans', view: undefined, planId, planItemId: undefined, sessionId: undefined, answerId: undefined })
  }
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
  await router.push({ name: 'training-practice', params: { setId }, query: { legacy: allowLegacy ? '1' : undefined, from: activeTab.value === 'mistakes' ? 'mistakes' : undefined } })
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
const configValid = computed(() => [5, 10, 15, 20].includes(practiceConfig.value.totalQuestions)
  && Object.values(practiceConfig.value.typeDistribution).every(value => Number.isInteger(value) && value >= 0)
  && configuredTotal.value === practiceConfig.value.totalQuestions)

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
  if (!selectedPlan.value || item.kind !== 'PRACTICE' || groupGeneratingItemId.value) return
  if (!configValid.value) {
    ElMessage.warning('题型分配相加须等于本组题量')
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

const progress = computed(() => selectedPlan.value ? planProgress(selectedPlan.value) : { completed: 0, total: 0, percentage: 0 })
const nextUnit = computed(() => selectedPlan.value?.items.find(item => item.status !== 'COMPLETED'))
const activeUnit = computed(() => selectedPlan.value?.items.find(item => item.id === route.query.planItemId))
const activeUnitIndex = computed(() => (selectedPlan.value?.items.findIndex(item => item.id === activeUnit.value?.id) ?? -1) + 1)
const practiceDifficulty = computed(() => difficultyOptions.find(option => option.value === practiceConfig.value.difficulty)?.label)

function openUnit(item: PlanItemVO) {
  setQuery({ view: 'unit', planId: selectedPlan.value?.id, planItemId: item.id })
}

function openSetup(item?: PlanItemVO) {
  const recommended = item || selectedPlan.value?.items.find(entry => entry.kind === 'PRACTICE' && entry.status !== 'COMPLETED')
    || selectedPlan.value?.items.find(entry => entry.kind === 'PRACTICE')
  groupTaskError.value = null
  setQuery({ view: 'setup', planId: selectedPlan.value?.id, planItemId: recommended?.id })
}

function startNextUnit(item: PlanItemVO) {
  if (item.kind === 'PRACTICE') openSetup(item)
  else openUnit(item)
}

function goInterview(item: PlanItemVO) {
  void router.push({ path: '/interview', query: { planItemId: item.id } })
}

const mistakePageSizes = [6, 12, 24]
const initialMistakeSize = Number(route.query.mistakeSize)
const initialMistakePage = Number(route.query.mistakePage)
const mistakes = ref<Page<MistakeVO>>({
  ...emptyPage<MistakeVO>(mistakePageSizes.includes(initialMistakeSize) ? initialMistakeSize : 6),
  current: Number.isSafeInteger(initialMistakePage) && initialMistakePage > 0 ? initialMistakePage : 1,
})
const mistakesLoading = ref(false)
const mistakesError = ref<unknown>(null)
const mistakeResolved = ref(route.query.mistakeResolved === '1')
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

async function openMistake(mistake: MistakeVO, syncRoute = true) {
  selectedMistake.value = mistake
  mistakeAnswer.value = null
  mistakeAnswerError.value = null
  mistakeAnswerLoading.value = true
  if (syncRoute) setQuery({ tab: 'mistakes', view: 'mistake', mistakeId: mistake.id,
    mistakePage: String(mistakes.value.current), mistakeSize: String(mistakes.value.size), mistakeResolved: mistakeResolved.value ? '1' : undefined })
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

const planContextLoading = ref(false)
const planSubmitting = ref(false)
const hasResume = ref(true)
const readyResumes = ref<{ id: string; label: string }[]>([])
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
const planFormValid = computed(() => !!planForm.value.resumeVersionId && !!planForm.value.targetDirection
  && planForm.value.dailyMinutes >= 10 && planForm.value.days >= 1)

async function loadPlanContext() {
  planContextLoading.value = true
  try {
    const [resumes, profile] = await Promise.all([
      resumeApi.list({ current: 1, size: 20 }),
      profileApi.get().catch(() => null),
    ])
    readyResumes.value = resumes.records.filter(item => ['PARSED', 'CONFIRMED'].includes(item.latestVersion?.status))
      .map(item => ({ id: item.latestVersion.id, label: `${item.title} · 第 ${item.latestVersion.versionNo} 版` }))
    hasResume.value = readyResumes.value.length > 0
    const active = readyResumes.value.find(item => item.id === profile?.activeResumeVersionId)
    planForm.value.resumeVersionId = active?.id || readyResumes.value[0]?.id || ''
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
  setQuery({ view: 'new', planItemId: undefined })
}

function goResumeCenter() {
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
  if (planContextLoading.value || planSubmitting.value || planTaskActive.value || !planFormValid.value) return
  planSubmitting.value = true
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
  } finally {
    planSubmitting.value = false
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
  await loadPlan(planId)
  if (!planDetailError.value) {
    setQuery({ tab: 'plans', view: undefined, planId, planItemId: undefined })
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
    await loadPlan(plan.id)
    if (!planDetailError.value) setQuery({ tab: 'plans', view: 'unit', planId: plan.id, planItemId })
  }
}

async function loadActiveTab() {
  void refreshCurrentProfile()
  if (activeTab.value === 'mistakes') { await loadMistakes(); return }
  await loadPlans()
  if (!selectedPlan.value && !planDetailLoading.value && !route.query.planId && !route.query.planItemId) {
    const latest = plans.value.records[0]
    if (latest) await loadPlan(latest.id)
  }
}

watch(view, screen => {
  if (screen === 'new') void loadPlanContext()
}, { immediate: true })

watch(() => route.query.planId, planId => {
  const id = queryValue(planId)
  if (id && id !== selectedPlan.value?.id) void loadPlan(id)
})

watch(() => route.query.tab, tab => {
  activeTab.value = tab === 'mistakes' ? 'mistakes' : 'plans'
})

watch(activeTab, tab => {
  setQuery({ tab })
  loadActiveTab()
})

onMounted(async () => {
  const ready = loadActiveTab()
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
  if (view.value === 'mistake') {
    await ready
    const match = mistakes.value.records.find(item => item.id === route.query.mistakeId)
    if (match) await openMistake(match, false)
  }
})
</script>

<template>
  <template v-if="['library', 'unit', 'setup', 'new', 'mistake'].includes(view)">
    <TrainingFlowHeader
      :title="view === 'mistake' ? '错题本' : '专项训练'"
      :subtitle="view === 'setup' ? activeUnit?.title : undefined"
      :back-label="view === 'mistake' ? '返回错题本' : '返回训练计划'"
      @back="returnToTraining"
    >
      <el-button v-if="view === 'library'" @click="openPlanDialog">新建训练计划</el-button>
      <el-button v-if="view === 'new' || view === 'setup'" @click="returnToTraining">取消</el-button>
    </TrainingFlowHeader>

    <div class="flow-content">
      <template v-if="view === 'library'">
        <header class="flow-title"><h1>我的训练计划</h1><p>查看计划进度，选择要继续的训练安排。</p></header>
        <el-alert v-if="planDetailError" type="error" :title="errorMessage(planDetailError)" show-icon :closable="false" />
        <AsyncState :loading="plansLoading" :retain="plans.records.length > 0" :error="plansError" :empty="!plans.records.length" empty-text="暂无训练计划" @retry="loadPlans">
          <template #empty><el-button type="primary" @click="openPlanDialog">新建训练计划</el-button></template>
          <div class="plan-library">
            <article v-for="plan in plans.records" :key="plan.id" class="training-card">
              <div class="section-heading"><h2>{{ plan.title }}</h2><el-tag :type="statusTagType(plan.status)" size="small">{{ commonStatusLabels[plan.status] || plan.status }}</el-tag></div>
              <p class="muted">{{ planDirectionLabel(plan) }} · 每天 {{ plan.dailyMinutes }} 分钟 · {{ plan.days }} 天</p>
              <p class="card-separator muted">{{ planProgress(plan).completed }} / {{ planProgress(plan).total }} 单元已完成 · {{ planSourceLabel(plan) }}</p>
              <el-button type="primary" :loading="planDetailLoading && planDetailRequestId === plan.id" :disabled="planDetailLoading" @click="selectPlan(plan.id)">
                {{ selectedPlan?.id === plan.id ? '打开当前计划' : '打开训练计划' }}
              </el-button>
            </article>
          </div>
        </AsyncState>
        <div v-if="plans.total > 6" class="pagination">
          <el-pagination background layout="sizes, prev, pager, next" :total="plans.total" :current-page="plans.current" :page-size="plans.size" :page-sizes="[6, 12, 24]" @current-change="changePlanPage" @size-change="changePlanSize" />
        </div>
      </template>

      <template v-else-if="view === 'unit'">
        <AsyncState :loading="planDetailLoading || plansLoading" :error="planDetailError" :empty="!activeUnit" empty-text="未找到该训练单元" @retry="loadPlan(planDetailRequestId)">
          <template v-if="activeUnit">
            <header class="flow-title"><h1>{{ activeUnit.title }}</h1><p>{{ selectedPlan?.title }} · 第 {{ activeUnitIndex }} 个训练单元</p></header>
            <div class="training-columns flow-columns">
              <article class="training-card training-card--details">
                <section><h2>训练目标</h2><p>{{ activeUnit.objective }}</p></section>
                <section class="card-separator"><h2>练习方式</h2><p>{{ activeUnit.method }}</p></section>
                <section class="card-separator"><h2>完成要求</h2><p>{{ activeUnit.completionCriteria }}</p></section>
                <details v-if="activeUnit.reason || activeUnit.evidenceQuote || activeUnit.evidenceStatus === 'PENDING'" class="card-separator disclosure">
                  <summary>推荐依据</summary>
                  <p v-if="activeUnit.reason">{{ activeUnit.reason }}</p>
                  <p v-if="activeUnit.evidenceQuote" class="pre-wrap">{{ activeUnit.evidenceQuote }}</p>
                  <p v-if="activeUnit.evidenceStatus === 'PENDING'">简历暂无该技能的直接证据，训练后再评估。</p>
                </details>
                <section v-if="safeLinks(activeUnit).length" class="card-separator"><h2>GitHub 学习资料</h2>
                  <a v-for="link in safeLinks(activeUnit)" :key="link.url" class="resource-link" :href="link.url" target="_blank" rel="noopener noreferrer">{{ link.label }}</a>
                </section>
                <section v-if="activeUnit.resourceRefs.length" class="card-separator"><h2>授权资料</h2>
                  <blockquote v-for="reference in activeUnit.resourceRefs" :key="reference.documentId + ':' + reference.chunkId" class="pre-wrap">
                    {{ reference.quote }} <small>{{ reference.documentId }} · v{{ reference.documentVersion }} · {{ reference.chunkId }}</small>
                  </blockquote>
                </section>
              </article>
              <aside class="training-card">
                <h2>{{ kindLabel(activeUnit.kind) }}</h2>
                <p class="muted">{{ activeUnit.skillCode }} · {{ categoryLabel(activeUnit.category) }} · 预计 {{ activeUnit.estimatedMinutes }} 分钟</p>
                <div class="card-separator">
                  <el-button v-if="activeUnit.kind === 'PRACTICE'" type="primary" @click="openSetup(activeUnit)">{{ activeUnit.status === 'COMPLETED' ? '再练一组' : '配置练习并开始' }}</el-button>
                  <el-button v-else-if="activeUnit.kind === 'READING'" type="primary" :disabled="activeUnit.status === 'COMPLETED'" :loading="itemActionPending.includes(activeUnit.id)" @click="completeReading(activeUnit)">{{ activeUnit.status === 'COMPLETED' ? '已自评完成阅读' : '我已阅读（自评）' }}</el-button>
                  <el-button v-else type="primary" @click="goInterview(activeUnit)">进入模拟面试</el-button>
                </div>
                <p v-if="activeUnit.kind === 'READING'" class="muted">阅读完成为自评记录，不计入技能评分证据。<template v-if="activeUnit.completedAt"> 完成于 {{ formatDate(activeUnit.completedAt) }}。</template></p>
                <el-alert v-if="itemActionErrors[activeUnit.id]" type="error" :title="errorMessage(itemActionErrors[activeUnit.id])" show-icon :closable="false" />
              </aside>
            </div>
          </template>
        </AsyncState>
      </template>

      <template v-else-if="view === 'setup'">
        <header class="flow-title"><h1>配置本组练习</h1><p>确认题量和题型，整组生成后即可按题作答。</p></header>
        <div class="training-columns flow-columns">
          <article class="training-card">
            <div><h2>题量与题型</h2><p class="muted">题型分配相加须等于本组题量。</p></div>
            <div class="config-field">
              <span class="field-label">每组题量</span>
              <div class="actions">
                <el-button v-for="size in [5, 10, 15, 20]" :key="size" :type="practiceConfig.totalQuestions === size ? 'primary' : 'default'" :plain="practiceConfig.totalQuestions === size" :aria-pressed="practiceConfig.totalQuestions === size" @click="practiceConfig.totalQuestions = size">{{ size }}</el-button>
              </div>
            </div>
            <div class="config-field card-separator">
              <div v-for="option in typeDistributionOptions" :key="option.key" class="config-row">
                <div><strong>{{ option.label }}</strong><small>{{ option.desc }}</small></div>
                <el-input-number v-model="practiceConfig.typeDistribution[option.key]" :aria-label="option.label + '数量'" :min="0" :max="practiceConfig.totalQuestions" size="small" />
              </div>
            </div>
            <div class="fields-grid card-separator">
              <div class="config-field">
                <span class="field-label">训练难度</span>
                <el-radio-group v-model="practiceConfig.difficulty" aria-label="训练难度">
                  <el-radio-button v-for="option in difficultyOptions" :key="option.value" :value="option.value">{{ option.label }}</el-radio-button>
                </el-radio-group>
              </div>
              <label class="config-field">
                <span class="field-label">训练方向</span>
                <el-select v-model="practiceConfig.direction" aria-label="训练方向">
                  <el-option v-for="option in directionOptions" :key="option.value" :label="option.label" :value="option.value" />
                </el-select>
              </label>
            </div>
          </article>

          <aside class="training-card">
            <h2>本组练习</h2>
            <div class="count-display"><strong>{{ practiceConfig.totalQuestions }}</strong><span>道题</span></div>
            <dl class="flow-summary">
              <div><dt>选择题 · 填空题 · 主观题</dt><dd>{{ practiceConfig.typeDistribution.choice }} · {{ practiceConfig.typeDistribution.fill }} · {{ practiceConfig.typeDistribution.subjective }}</dd></div>
              <div><dt>训练难度</dt><dd>{{ practiceDifficulty }}</dd></div>
              <div v-if="activeUnit"><dt>预计时长</dt><dd>{{ activeUnit.estimatedMinutes }} 分钟</dd></div>
            </dl>
            <p class="config-status card-separator" :class="{ 'config-status--error': !configValid }" aria-live="polite">
              <el-icon v-if="configValid"><CircleCheck /></el-icon>
              {{ configValid ? '已分配' : '请调整题型分配' }} {{ configuredTotal }} / {{ practiceConfig.totalQuestions }} 道题
            </p>
            <el-alert v-if="groupTaskError || planDetailError" :title="errorMessage(groupTaskError || planDetailError)" type="error" :closable="false" show-icon />
            <div>
              <el-button v-if="activeUnit?.kind === 'PRACTICE'" type="primary" :loading="!!groupGeneratingItemId" :disabled="!configValid" @click="startGroupPractice(activeUnit)">生成并开始练习</el-button>
              <el-button v-else type="primary" :disabled="!configValid || planDetailLoading" @click="returnToTraining">应用设置</el-button>
            </div>
          </aside>
        </div>
      </template>

      <template v-else-if="view === 'new'">
        <header class="flow-title"><h1>新建训练计划</h1><p>选择已解析的简历和目标方向，再安排每天的训练量。</p></header>
        <div class="training-columns flow-columns">
          <article class="training-card">
            <div><h2>训练依据</h2><p class="muted">根据简历中的项目和技能生成训练单元。</p></div>
            <el-alert v-if="!hasResume && !planContextLoading" type="warning" :closable="false" show-icon title="请先在简历中心上传并解析简历" />
            <el-alert v-if="planError" :title="errorMessage(planError)" type="error" :closable="false" show-icon />
            <LoadingSkeleton v-if="planContextLoading && !hasResume" :rows="3" label="正在读取训练依据" />
            <el-form v-if="hasResume" label-position="top" :disabled="planContextLoading || planTaskActive || planSubmitting">
              <div class="fields-grid">
                <el-form-item label="选择简历">
                  <el-select v-model="planForm.resumeVersionId" aria-label="选择简历" placeholder="选择已解析的简历">
                    <el-option v-for="resume in readyResumes" :key="resume.id" :value="resume.id" :label="resume.label" />
                  </el-select>
                </el-form-item>
                <el-form-item label="目标方向">
                  <el-select v-model="planForm.targetDirection" aria-label="目标方向">
                    <el-option v-for="option in directionOptions" :key="option.value" :label="option.label" :value="option.value" />
                  </el-select>
                </el-form-item>
              </div>
              <div class="fields-grid card-separator">
                <el-form-item label="每天投入（分钟）"><el-input-number v-model="planForm.dailyMinutes" :min="10" :max="120" :step="5" /></el-form-item>
                <el-form-item label="计划周期（天）"><el-input-number v-model="planForm.days" :min="1" :max="14" /></el-form-item>
              </div>
              <el-form-item label="参考岗位 · 可选">
                <el-select v-model="planForm.matchItemId" clearable placeholder="暂不关联">
                  <el-option v-for="option in matchOptions" :key="option.id" :label="`${option.title || '未命名岗位'}${option.matchScore === null ? '' : ` · ${option.matchScore} 分`}`" :value="option.id" />
                </el-select>
              </el-form-item>
            </el-form>
            <TaskStatus v-if="planTaskId" :task="planTask" :loading="planTracker.loading.value" :reasoning="planTracker.reasoningText.value" :tracking-error="planTrackingError" :refreshing="planTracker.refreshing.value" @refresh="planTracker.refresh">
              <template #actions><el-button v-if="planTask && canRetryTrackedTask(planTask)" size="small" type="primary" @click="retryPlanTask">按原参数重试</el-button></template>
            </TaskStatus>
          </article>
          <aside class="training-card">
            <h2>计划预览</h2>
            <dl class="flow-summary card-separator">
              <div><dt>来源</dt><dd>{{ readyResumes.find(item => item.id === planForm.resumeVersionId)?.label || '已解析简历' }}</dd></div>
              <div><dt>目标</dt><dd>{{ getJobRoleLabel(planForm.targetDirection) }}</dd></div>
              <div><dt>时间安排</dt><dd>{{ planForm.dailyMinutes }} 分钟/天 · {{ planForm.days }} 天</dd></div>
            </dl>
            <div class="card-separator">
              <el-button v-if="!hasResume" type="primary" @click="goResumeCenter">前往简历中心</el-button>
              <el-button v-else type="primary" :loading="planTaskActive || planSubmitting" :disabled="planContextLoading || !planFormValid" @click="submitPlanFromResume">生成训练计划</el-button>
            </div>
          </aside>
        </div>
      </template>

      <template v-else>
        <header class="flow-title"><h1>错题解析</h1><p>对照原回答和参考答案，找到需要补练的知识点。</p></header>
        <div v-if="selectedMistake" class="training-columns flow-columns">
          <article class="training-card training-card--details">
            <div class="actions">
              <el-tag effect="plain">{{ questionTypeLabel(selectedMistake.question.type) }}</el-tag>
              <el-tag v-for="skill in selectedMistake.question.skillCodes" :key="skill" type="info" effect="plain">{{ skill }}</el-tag>
            </div>
            <h2 class="pre-wrap">{{ selectedMistake.question.stem }}</h2>
            <AsyncState :loading="mistakeAnswerLoading" :error="mistakeAnswerError" :empty="!mistakeAnswer" empty-text="未读取到原回答" @retry="openMistake(selectedMistake)">
              <div v-if="mistakeAnswer" class="review-content">
                <section class="card-separator"><h2>你的回答</h2><p class="pre-wrap">{{ mistakeAnswer.content }}</p></section>
                <template v-if="mistakeAnswer.evaluation">
                  <section><h2>参考答案</h2><p class="pre-wrap">{{ mistakeAnswer.evaluation.referenceAnswer || '当前评价未提供参考答案' }}</p></section>
                  <section v-if="mistakeAnswer.evaluation.strengths.length">
                    <h2>作答优点</h2><ul><li v-for="(point, i) in mistakeAnswer.evaluation.strengths" :key="i">{{ point }}</li></ul>
                  </section>
                </template>
                <el-alert v-else title="该原回答暂无评价结果" type="info" :closable="false" show-icon />
              </div>
            </AsyncState>
          </article>
          <aside class="training-card">
            <div class="section-heading"><h2>复盘要点</h2><el-tag v-if="mistakeAnswer?.evaluation" :type="statusTagType(mistakeAnswer.evaluation.evidenceStatus)" size="small">{{ evidenceLabel(mistakeAnswer.evaluation.evidenceStatus) }}</el-tag></div>
            <p v-if="mistakeAnswer?.evaluation?.overallScore != null" class="muted">本题得分 {{ displayScore(mistakeAnswer.evaluation.overallScore) }}</p>
            <ul v-if="mistakeAnswer?.evaluation?.improvements.length" class="review-tips">
              <li v-for="(point, i) in mistakeAnswer.evaluation.improvements" :key="i">{{ point }}</li>
            </ul>
            <p v-else class="muted">可以保留原题型，单独练习这道题。</p>
            <el-alert v-if="practiceActionError" :title="errorMessage(practiceActionError)" type="error" :closable="false" show-icon />
            <div class="card-separator"><el-button type="primary" :loading="retryingMistakeId === selectedMistake.id" :disabled="!!retryingMistakeId" @click="retryMistake(selectedMistake)">重新练习此题</el-button></div>
          </aside>
        </div>
        <el-empty v-else description="请从错题本打开要复盘的题目"><el-button @click="returnToTraining">返回错题本</el-button></el-empty>
      </template>
    </div>
  </template>

  <div v-else class="page training-page">
    <header class="training-heading">
      <div><h1>专项训练</h1><p>按计划完成训练，做完一组后查看反馈和错题。</p></div>
      <el-button @click="openPlanDialog">新建训练计划</el-button>
    </header>
    <el-tabs v-model="activeTab" class="training-tabs">
      <el-tab-pane label="训练计划" name="plans" />
      <el-tab-pane label="错题本" name="mistakes" />
    </el-tabs>

    <template v-if="activeTab === 'plans'">
      <AsyncState :loading="!selectedPlan && (plansLoading || planDetailLoading)" :error="planDetailError || (!selectedPlan && plansError)" :empty="!selectedPlan" empty-text="暂无训练计划" @retry="planDetailRequestId ? loadPlan(planDetailRequestId) : loadActiveTab()">
        <template #empty>
          <p class="muted">可以根据已解析的简历或岗位匹配结果生成训练计划。</p>
          <div class="actions"><el-button type="primary" @click="openPlanDialog">新建训练计划</el-button><el-button @click="router.push('/jobs?tab=matches')">前往岗位匹配</el-button></div>
        </template>
        <div v-if="selectedPlan" class="training-columns">
          <div class="unit-column">
            <section v-if="nextUnit" class="training-card next-unit">
              <div class="unit-metadata"><span class="tone-label">下一项</span><span><el-icon><Clock /></el-icon> 预计 {{ nextUnit.estimatedMinutes }} 分钟</span></div>
              <div><h2>{{ nextUnit.title }}</h2><p class="muted">{{ nextUnit.objective }}</p></div>
              <div class="actions">
                <el-button type="primary" @click="startNextUnit(nextUnit)">{{ nextUnit.kind === 'PRACTICE' ? '开始练习' : '查看训练详情' }}</el-button>
                <span v-if="nextUnit.kind === 'PRACTICE'" class="muted">{{ practiceConfig.totalQuestions }} 道题 · {{ practiceDifficulty }}</span>
              </div>
            </section>
            <WaitStatus v-else-if="selectedPlan.status === 'GENERATING'" title="正在生成训练计划">
              <template #actions><el-button size="small" :loading="planDetailLoading" @click="loadPlan(selectedPlan.id)">刷新计划</el-button></template>
            </WaitStatus>
            <section v-else class="training-card">
              <h2>{{ selectedPlan.status === 'FAILED' ? '计划生成失败' : progress.total ? '已完成当前计划' : '当前计划暂无训练单元' }}</h2>
              <div class="actions"><el-button @click="refreshCurrentProfile(); loadPlan(selectedPlan.id)">刷新计划</el-button><el-button @click="openPlanDialog">新建训练计划</el-button></div>
            </section>

            <section v-if="selectedPlan.items.length" class="unit-list" aria-labelledby="unit-list-title">
              <div class="section-heading"><h2 id="unit-list-title">训练单元</h2><span class="muted">共 {{ progress.total }} 项 · 已完成 {{ progress.completed }} 项</span></div>
              <button v-for="(item, index) in selectedPlan.items" :key="item.id" class="item-row" type="button" @click="openUnit(item)">
                <span class="unit-number">{{ String(index + 1).padStart(2, '0') }}</span>
                <span class="row-copy"><strong>{{ item.title }}</strong><small>{{ item.skillCode }} · {{ kindLabel(item.kind) }} · {{ item.estimatedMinutes }} 分钟</small></span>
                <span class="item-state" :class="{ 'item-state--done': item.status === 'COMPLETED' }">{{ item.id === nextUnit?.id ? '下一项' : commonStatusLabels[item.status] || item.status }}</span>
              </button>
            </section>
          </div>

          <aside class="training-aside">
            <section class="training-card">
              <div class="section-heading"><h2>当前计划</h2><el-tag :type="statusTagType(selectedPlan.status)" size="small">{{ commonStatusLabels[selectedPlan.status] || selectedPlan.status }}</el-tag></div>
              <div><h3>{{ selectedPlan.title }}</h3><p class="muted">{{ planDirectionLabel(selectedPlan) }} · {{ selectedPlan.dailyMinutes }} 分钟/天 · 共 {{ selectedPlan.days }} 天</p></div>
              <div class="plan-progress">
                <div class="section-heading"><span>{{ progress.completed }} / {{ progress.total }} 单元已完成</span><strong>{{ progress.percentage }}%</strong></div>
                <div v-if="progress.total && progress.total <= 20" class="progress-segments" role="progressbar" aria-label="计划完成度" :aria-valuenow="progress.percentage">
                  <span v-for="item in selectedPlan.items" :key="item.id" :class="{ finished: item.status === 'COMPLETED' }" />
                </div>
                <el-progress v-else :percentage="progress.percentage" :show-text="false" :stroke-width="7" />
              </div>
              <div class="card-separator source-caption"><el-icon><Document /></el-icon>{{ selectedPlan.source === 'RESUME' ? '根据已解析简历生成' : '根据岗位匹配生成' }}</div>
              <div><el-button @click="setQuery({ view: 'library' }); loadPlans()">查看我的计划</el-button></div>
              <el-alert v-if="planOutdatedReasons(selectedPlan).length" :title="planOutdatedReasons(selectedPlan).join('、')" type="warning" :closable="false" show-icon />
              <details v-if="selectedPlan.basis || selectedPlan.matchSummary" class="disclosure">
                <summary>查看计划依据</summary>
                <p v-if="selectedPlan.matchSummary">参考岗位 {{ selectedPlan.matchSummary.title }}</p>
                <p v-if="selectedPlan.basis?.summary">{{ selectedPlan.basis.summary }}</p>
                <ul v-if="selectedPlan.basis?.limitations?.length"><li v-for="note in selectedPlan.basis.limitations" :key="note">{{ note }}</li></ul>
                <el-alert v-if="selectedPlan.basis?.github" :title="githubNotice(selectedPlan)" :type="githubNoticeType(selectedPlan)" :closable="false" />
              </details>
            </section>

            <section class="training-card">
              <div class="config-heading"><span class="unit-number"><el-icon><Operation /></el-icon></span><div><h2>本组出题设置</h2><p class="muted">适用于下一组答题训练</p></div></div>
              <div class="actions"><span class="tone-label">{{ practiceConfig.totalQuestions }} 道题</span><span class="item-state">{{ practiceDifficulty }}</span><span class="item-state">{{ getJobRoleLabel(practiceConfig.direction) }}</span></div>
              <div class="card-separator distribution-row"><span>选择 {{ practiceConfig.typeDistribution.choice }}</span><span>填空 {{ practiceConfig.typeDistribution.fill }}</span><span>主观 {{ practiceConfig.typeDistribution.subjective }}</span></div>
              <div><el-button @click="openSetup()">调整出题设置</el-button></div>
            </section>
          </aside>
        </div>
      </AsyncState>
    </template>

    <section v-else class="training-card mistakes-panel">
      <div class="section-heading">
        <div><h2>{{ mistakeResolved ? '已解决' : '待攻克' }}</h2><p class="muted">优先复盘最近练习中的错题。</p></div>
        <el-radio-group v-model="mistakeResolved" aria-label="错题状态" @change="changeMistakeFilter">
          <el-radio-button :value="false">待攻克</el-radio-button><el-radio-button :value="true">已解决</el-radio-button>
        </el-radio-group>
      </div>
      <AsyncState :loading="mistakesLoading" :retain="mistakes.records.length > 0" :error="mistakesError" :empty="!mistakes.records.length" :empty-text="mistakeResolved ? '暂无已解决错题' : '暂无待攻克错题'" @retry="loadMistakes">
        <div class="mistake-list">
          <button v-for="mistake in mistakes.records" :key="mistake.id" class="item-row" type="button" @click="openMistake(mistake)">
            <span class="row-copy"><strong>{{ mistake.question.stem }}</strong><small>{{ [...mistake.question.skillCodes, questionTypeLabel(mistake.question.type), difficultyLabel(mistake.question.difficulty)].join(' · ') }}</small></span>
            <span class="item-state" :class="mistake.resolved ? 'item-state--done' : 'item-state--review'">{{ mistake.resolved ? '已解决' : '待攻克' }}</span>
            <span class="review-link">查看解析 <el-icon><ArrowRight /></el-icon></span>
          </button>
        </div>
      </AsyncState>
      <div v-if="mistakes.total > 6" class="pagination">
        <el-pagination background layout="sizes, prev, pager, next" :total="mistakes.total" :current-page="mistakes.current" :page-size="mistakes.size" :page-sizes="[6, 12, 24]" @current-change="changeMistakePage" @size-change="changeMistakeSize" />
      </div>
    </section>
  </div>
</template>

<style scoped lang="scss">
.training-page { padding-block:24px 36px; }
.training-heading,.section-heading { display:flex; align-items:center; justify-content:space-between; gap:12px; min-width:0; }
.training-heading { margin-bottom:12px; }
h1,h2,h3,p { margin:0; }
h1,h2,h3 { font-weight:700; }
h1 { font-size:32px; line-height:1.35; letter-spacing:-.025em; }
.training-heading p,.flow-title p { margin-top:5px; color:$ai-text-secondary; line-height:1.6; }
h2 { font-size:17px; line-height:1.4; }
h3 { font-size:20px; line-height:1.4; }
p { line-height:1.65; overflow-wrap:anywhere; }
.muted { color:$ai-text-secondary; }
.training-tabs { margin-bottom:12px; }
.training-tabs :deep(.el-tabs__header) { margin-bottom:8px; }
.training-tabs :deep(.el-tabs__nav-wrap::after) { height:1px; }
.training-columns { display:grid; grid-template-columns:minmax(0, 1.76fr) minmax(288px, 1fr); align-items:start; gap:24px; }
.flow-columns { grid-template-columns:minmax(0, 1.94fr) minmax(288px, 1fr); }
.unit-column,.training-aside,.unit-list,.mistake-list,.review-content,.plan-library { display:grid; min-width:0; gap:14px; }
.unit-list,.mistake-list { gap:8px; }
.unit-list .section-heading { margin:0 0 5px; }
.unit-list h2 { font-size:20px; }
.unit-list .section-heading span { font-size:13px; text-align:right; }
.training-card { display:grid; padding:22px; gap:14px; min-width:0; background:$ai-bg-card; border:1px solid $ai-border; border-radius:16px; }
.training-card > :is(div,section) > .muted { margin-top:5px; }
.next-unit { padding:23px; gap:14px; }
.next-unit h2 { font-size:clamp(22px, 2.15vw, 31px); overflow-wrap:anywhere; }
.next-unit .actions { gap:12px; }
.unit-metadata,.unit-metadata > span,.source-caption { display:flex; align-items:center; gap:7px; color:$ai-text-secondary; font-size:13px; }
.unit-metadata { gap:12px; }
.tone-label,.item-state { display:inline-flex; align-items:center; border-radius:7px; padding:4px 8px; font-size:12px; font-weight:500; line-height:1.35; }
.tone-label { background:$ai-primary-light; color:$ai-primary; }
.item-state { background:$ai-bg-light; color:$ai-text-secondary; }
.item-state--done { background:var(--ai-success-bg,#e5f6ee); color:var(--ai-success,#167953); }
.item-state--review { background:var(--ai-warning-bg,#fff1e2); color:var(--ai-warning,#a65318); }
.unit-number { display:grid; place-items:center; width:36px; height:36px; flex-shrink:0; border-radius:9px; background:$ai-primary-light; color:$ai-primary; font-size:13px; font-weight:650; }
.unit-number .el-icon { font-size:18px; }
.item-row { display:flex; align-items:center; gap:12px; width:100%; min-width:0; min-height:62px; padding:11px 14px; border:1px solid $ai-border; border-radius:10px; background:$ai-bg-card; color:$ai-text-primary; font:inherit; text-align:left; cursor:pointer; }
.item-row:hover,.item-row:focus-visible { border-color:$ai-primary; }
.row-copy { flex:1; min-width:0; display:grid; gap:4px; overflow-wrap:anywhere; }
.row-copy strong { font-size:14px; line-height:1.45; }
small,.row-copy small { color:$ai-text-secondary; font-size:12px; line-height:1.45; }
.config-heading { display:flex; align-items:center; gap:10px; }
.config-heading p { font-size:12px; }
.card-separator { padding-top:13px; border-top:1px solid $ai-border-light; }
.plan-progress { display:grid; gap:8px; font-size:13px; color:$ai-text-secondary; }
.plan-progress strong { color:$ai-primary; font-size:23px; font-variant-numeric:tabular-nums; }
.progress-segments { display:flex; gap:6px; }
.progress-segments span { flex:1; height:7px; border-radius:5px; background:var(--ai-primary-light,#dbe5fb); }
.progress-segments .finished { background:$ai-primary; }
.source-caption .el-icon,.review-link { color:$ai-primary; }
.distribution-row { display:flex; gap:16px; flex-wrap:wrap; color:$ai-text-secondary; font-size:13px; }
.flow-content { width:min(1152px, calc(100% - 40px)); margin:36px auto; color:$ai-text-primary; }
.flow-title { margin-bottom:24px; }
.fields-grid { display:grid; grid-template-columns:repeat(2, minmax(0,1fr)); gap:16px; }
.config-field { display:grid; gap:9px; align-content:start; }
.field-label { font-weight:650; }
.config-row { display:flex; justify-content:space-between; align-items:center; gap:10px; padding:3px 0; }
.config-row small { display:block; margin-top:3px; }
.config-status { color:var(--ai-success,#167953); }
.config-status--error,.error-text { color:$ai-danger; }
.count-display { display:flex; align-items:baseline; gap:7px; color:$ai-text-secondary; }
.count-display strong { font-size:40px; line-height:1; color:$ai-text-primary; font-variant-numeric:tabular-nums; }
.flow-summary { display:grid; gap:12px; margin:0; }
.flow-summary div { display:flex; justify-content:space-between; gap:12px; }
.flow-summary dt { color:$ai-text-secondary; }
.flow-summary dd { margin:0; font-weight:600; text-align:right; overflow-wrap:anywhere; }
.plan-library { max-width:744px; }
.disclosure summary { color:$ai-primary; cursor:pointer; }
.disclosure p,.training-card--details p { margin-top:7px; color:$ai-text-secondary; }
.resource-link { display:block; margin-top:7px; color:$ai-primary; overflow-wrap:anywhere; }
blockquote { margin:8px 0 0; padding:8px 12px; border-left:2px solid $ai-border; color:$ai-text-secondary; }
blockquote small { display:block; }
.review-link { display:inline-flex; align-items:center; gap:4px; white-space:nowrap; font-weight:600; }
.review-tips,.review-content ul { margin:0; padding-left:18px; line-height:1.65; }
@media (max-width: 1050px) { .training-columns,.flow-columns { gap:16px; } }
@media (max-width: 880px) { .training-columns,.flow-columns { grid-template-columns:minmax(0,1fr); } }
@media (max-width: 560px) {
  .fields-grid { grid-template-columns:minmax(0,1fr); gap:6px; }
  .training-heading { align-items:flex-start; flex-wrap:wrap; }
  h1 { font-size:26px; }
  .training-card { padding:15px; }
  .flow-content { width:calc(100% - 28px); margin:24px auto; }
  .item-row { flex-wrap:wrap; }
  .review-link { margin-left:auto; }
}
</style>
