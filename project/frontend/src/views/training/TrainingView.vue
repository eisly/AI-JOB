<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Edit, Refresh, Right } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { learningApi } from '@/api/learning'
import { canRetryTrackedTask, retryTrackedTask } from '@/api/task-registry'
import { ApiError } from '@/api/request'
import type { LearningAnswerVO, LearningPlanVO, LearningSessionVO, MistakeVO, Page, PlanItemVO, TaskVO } from '@/api/types'
import { useTask } from '@/composables/useTask'
import AsyncState from '@/components/AsyncState.vue'
import TaskStatus from '@/components/TaskStatus.vue'
import { commonStatusLabels, displayScore, errorMessage } from '@/utils/format'

type TrainingTab = 'plans' | 'mistakes'
type TaskContext = 'plans' | 'mistakes'
type AnswerBody = { content: string; expectedVersion: number }

const route = useRoute()
const router = useRouter()
const activeTab = ref<TrainingTab>(route.query.tab === 'mistakes' ? 'mistakes' : 'plans')
const draftPrefix = 'ai-job-training-draft-v1'

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

function dimensionLabel(key: string) {
  return ({ correctness: '正确性', completeness: '完整性', expression: '表达' } as Record<string, string>)[key] || key
}

function draftKey(sessionId: string) {
  return `${draftPrefix}:${sessionId}`
}

function readDraft(sessionId: string) {
  try {
    return localStorage.getItem(draftKey(sessionId)) || ''
  } catch {
    return ''
  }
}

function clearDraft(sessionId: string) {
  try {
    localStorage.removeItem(draftKey(sessionId))
  } catch {
    return
  }
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
    session.value = null
    answer.value = null
    sessionTaskId.value = null
    evaluationTaskId.value = null
    answerContent.value = ''
    answerSubmitted.value = false
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

const session = ref<LearningSessionVO | null>(null)
const sessionRequestId = ref('')
const sessionLoading = ref(false)
const sessionError = ref<unknown>(null)
const sessionStartingItemId = ref<string | null>(null)
const sessionActionError = ref<unknown>(null)
const sessionTaskId = ref<string | null>(null)
const sessionTaskContext = ref<TaskContext>('plans')
const sessionRetrying = ref(false)
const sessionTracker = useTask(sessionTaskId, { onDone: handleSessionDone })
const sessionTask = sessionTracker.task
const sessionTrackingError = sessionTracker.error
const sessionTaskActive = computed(() => taskIsActive(sessionTask.value))

const answer = ref<LearningAnswerVO | null>(null)
const answerRequestId = ref('')
const answerLoading = ref(false)
const answerError = ref<unknown>(null)
const answerContent = ref('')
const answerSubmitting = ref(false)
const answerRetrying = ref(false)
const answerSubmitted = ref(false)
const answerConflict = ref(false)
const answerActionError = ref<unknown>(null)
const evaluationTaskId = ref<string | null>(null)
const lastAnswerBody = ref<AnswerBody | null>(null)
const draftSaveFailed = ref(false)
const evaluationTracker = useTask(evaluationTaskId, { onDone: handleEvaluationDone })
const evaluationTask = evaluationTracker.task
const evaluationTrackingError = evaluationTracker.error
const evaluationTaskActive = computed(() => taskIsActive(evaluationTask.value) || answerSubmitting.value)

watch(answerContent, content => {
  if (!session.value || answerSubmitted.value) return
  try {
    localStorage.setItem(draftKey(session.value.id), content)
    draftSaveFailed.value = false
  } catch {
    draftSaveFailed.value = true
  }
})

async function loadSession(sessionId: string, options: { preserveContent?: boolean; loadLastAnswer?: boolean; syncRoute?: boolean } = {}) {
  if (!sessionId) return
  sessionRequestId.value = sessionId
  sessionLoading.value = true
  sessionError.value = null
  try {
    const value = await learningApi.getSession(sessionId)
    session.value = value
    answerConflict.value = false
    draftSaveFailed.value = false
    if (!options.preserveContent) {
      answerContent.value = readDraft(sessionId)
      answerSubmitted.value = false
      lastAnswerBody.value = null
    }
    if (options.syncRoute !== false) setQuery({ tab: 'plans', planId: value.planId, sessionId, answerId: value.lastAnswerId || undefined })
    await loadPlan(value.planId)
    if (options.loadLastAnswer !== false && value.lastAnswerId) {
      await loadAnswer(value.lastAnswerId, { loadSession: false, syncRoute: options.syncRoute !== false })
    } else if (!value.lastAnswerId && !options.preserveContent) {
      answer.value = null
      evaluationTaskId.value = null
      answerSubmitted.value = false
    }
  } catch (reason) {
    sessionError.value = reason
  } finally {
    sessionLoading.value = false
  }
}

async function handleSessionDone(task: TaskVO) {
  const sessionId = task.result?.resourceId || task.resourceId
  if (!sessionId) {
    sessionActionError.value = new Error('任务完成但未返回训练会话ID')
    return
  }
  activeTab.value = 'plans'
  await loadSession(sessionId)
  if (!sessionError.value) {
    await loadPlans()
    ElMessage.success('训练题已准备完成，已读取权威会话')
  }
}

function conflictSessionId(reason: unknown) {
  if (!(reason instanceof ApiError) || reason.status !== 409) return ''
  const value = reason.details?.sessionId
  return typeof value === 'string' ? value : ''
}

async function startPractice(item: PlanItemVO) {
  if (!selectedPlan.value || item.kind !== 'PRACTICE') return
  const planId = selectedPlan.value.id
  sessionStartingItemId.value = item.id
  sessionTaskContext.value = 'plans'
  sessionTaskId.value = null
  sessionActionError.value = null
  try {
    sessionTaskId.value = (await learningApi.startSession(planId, item.id)).id
  } catch (reason) {
    const existingSessionId = conflictSessionId(reason)
    if (existingSessionId) {
      ElMessage.info('已存在活动训练会话，正在打开该会话')
      await loadSession(existingSessionId)
    } else {
      sessionActionError.value = reason
    }
  } finally {
    sessionStartingItemId.value = null
  }
}

async function retrySessionTask() {
  sessionRetrying.value = true
  sessionActionError.value = null
  try {
    if (sessionTask.value) sessionTaskId.value = (await retryTrackedTask(sessionTask.value)).id
  } catch (reason) {
    sessionActionError.value = reason
  } finally {
    sessionRetrying.value = false
  }
}

function goInterview(item: PlanItemVO) {
  void router.push({ path: '/interview', query: { planItemId: item.id } })
}

async function loadAnswer(answerId: string, options: { loadSession?: boolean; syncRoute?: boolean; preserveContent?: boolean } = {}) {
  if (!answerId) return
  answerRequestId.value = answerId
  answerLoading.value = true
  answerError.value = null
  try {
    const value = await learningApi.getAnswer(answerId)
    answer.value = value
    if (!options.preserveContent) answerContent.value = value.content
    answerSubmitted.value = true
    answerActionError.value = null
    if (value.status === 'EVALUATING' || value.status === 'FAILED') evaluationTaskId.value = value.taskId
    if (options.loadSession !== false) await loadSession(value.sessionId, { preserveContent: true, loadLastAnswer: false, syncRoute: false })
    if (options.syncRoute !== false) setQuery({ tab: 'plans', planId: session.value?.planId || selectedPlan.value?.id, sessionId: value.sessionId, answerId })
    if (value.status === 'EVALUATED') clearDraft(value.sessionId)
  } catch (reason) {
    answerError.value = reason
  } finally {
    answerLoading.value = false
  }
}

async function handleEvaluationDone(task: TaskVO) {
  const answerId = task.result?.resourceId || task.resourceId
  if (!answerId) {
    answerActionError.value = new Error('评价任务完成但未返回回答ID')
    return
  }
  await loadAnswer(answerId, { preserveContent: true })
  if (!answerError.value) {
    if (session.value) {
      clearDraft(session.value.id)
      await loadSession(session.value.id, { preserveContent: true, loadLastAnswer: false })
    }
    await Promise.all([loadPlans(), loadMistakes()])
    ElMessage.success('回答评价已完成并读取完整结果')
  }
}

async function submitAnswer() {
  if (!session.value || session.value.status !== 'ACTIVE' || !session.value.question) return
  if (!answerContent.value.trim()) {
    ElMessage.warning('请先填写回答')
    return
  }
  if (answerContent.value.length > 10000) {
    ElMessage.warning('回答不能超过10000个字符')
    return
  }
  const body: AnswerBody = { content: answerContent.value, expectedVersion: session.value.version }
  lastAnswerBody.value = body
  answerSubmitting.value = true
  answerActionError.value = null
  answerConflict.value = false
  try {
    evaluationTaskId.value = (await learningApi.submitAnswer(session.value.id, body)).id
    answerSubmitted.value = true
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 409) {
      await loadSession(session.value.id, { preserveContent: true, loadLastAnswer: false })
      if (sessionError.value) {
        answerActionError.value = sessionError.value
      } else {
        answerConflict.value = true
        ElMessage.warning('会话版本已刷新，请核对题目和原回答后再次点击提交；答案未被自动更改')
      }
    } else {
      answerActionError.value = reason
    }
  } finally {
    answerSubmitting.value = false
  }
}

async function retryEvaluation() {
  if (!session.value) return
  answerRetrying.value = true
  answerActionError.value = null
  try {
    if (evaluationTask.value) {
      evaluationTaskId.value = (await retryTrackedTask(evaluationTask.value)).id
    } else {
      const body = lastAnswerBody.value || { content: answer.value?.content ?? answerContent.value, expectedVersion: session.value.version }
      lastAnswerBody.value = body
      answerContent.value = body.content
      evaluationTaskId.value = (await learningApi.submitAnswer(session.value.id, body)).id
    }
    answerSubmitted.value = true
  } catch (reason) {
    answerActionError.value = reason
  } finally {
    answerRetrying.value = false
  }
}

const canSubmitAnswer = computed(() => !!session.value && session.value.status === 'ACTIVE' && !!session.value.question && !answerSubmitted.value && !evaluationTaskActive.value && !!answerContent.value.trim() && answerContent.value.length <= 10000)

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
  sessionTaskContext.value = 'mistakes'
  sessionTaskId.value = null
  sessionActionError.value = null
  try {
    sessionTaskId.value = (await learningApi.retryMistake(mistake.id)).id
  } catch (reason) {
    const existingSessionId = conflictSessionId(reason)
    if (existingSessionId) {
      activeTab.value = 'plans'
      ElMessage.info('已存在活动训练会话，正在打开该会话')
      await loadSession(existingSessionId)
    } else {
      sessionActionError.value = reason
    }
  } finally {
    retryingMistakeId.value = null
  }
}

function loadActiveTab() {
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
  const answerId = queryValue(route.query.answerId)
  if (planId) await loadPlan(planId)
  if (sessionId) {
    await loadSession(sessionId, { loadLastAnswer: !answerId, syncRoute: false })
    if (answerId) await loadAnswer(answerId, { loadSession: false, syncRoute: false })
  } else if (answerId) {
    await loadAnswer(answerId, { syncRoute: false })
  }
})
</script>

<template>
  <div class="page training-page">
    <header class="page-header">
      <div>
        <h1><el-icon><Edit /></el-icon> 专项训练</h1>
        <p>按计划完成阅读、答题和面试训练，保留真实评价与错题记录。</p>
      </div>
      <el-button :icon="Refresh" @click="loadActiveTab">刷新当前列表</el-button>
    </header>

    <el-tabs v-model="activeTab" class="training-tabs">
      <el-tab-pane label="训练计划" name="plans" />
      <el-tab-pane label="错题本" name="mistakes" />
    </el-tabs>

    <template v-if="activeTab === 'plans'">
      <div class="training-layout">
        <section class="panel plan-list-panel">
          <div class="panel__heading">
            <h2>我的训练计划</h2>
            <el-tag type="info">{{ plans.total }} 个计划</el-tag>
          </div>
          <AsyncState :loading="plansLoading" :error="plansError" :empty="!plans.records.length" empty-text="暂无训练计划，请先从有效匹配结果创建" @retry="loadPlans">
            <div class="list-stack">
              <button v-for="plan in plans.records" :key="plan.id" type="button" class="plan-card" :class="{ 'plan-card--active': selectedPlan?.id === plan.id }" @click="selectPlan(plan.id)">
                <span class="plan-card__heading">
                  <strong>{{ plan.title }}</strong>
                  <el-tag size="small" :type="statusTagType(plan.status)">{{ commonStatusLabels[plan.status] || plan.status }}</el-tag>
                </span>
                <span class="muted">画像 v{{ plan.profileVersion }} · {{ plan.items.length }} 个训练项</span>
                <el-progress :percentage="planProgress(plan).percentage" :stroke-width="7" />
                <span class="plan-card__progress">{{ planProgress(plan).completed }} / {{ planProgress(plan).total }} 已完成</span>
              </button>
            </div>
          </AsyncState>
          <div class="pagination">
            <el-pagination background layout="total, sizes, prev, pager, next" :total="plans.total" :current-page="plans.current" :page-size="plans.size" :page-sizes="[6, 12, 24]" @current-change="changePlanPage" @size-change="changePlanSize" />
          </div>
        </section>

        <section class="panel plan-detail-panel">
          <AsyncState :loading="planDetailLoading" :error="planDetailError" :empty="!selectedPlan" empty-text="请选择训练计划查看计划项" @retry="loadPlan(planDetailRequestId)">
            <template v-if="selectedPlan">
              <div class="panel__heading">
                <div>
                  <h2>{{ selectedPlan.title }}</h2>
                  <span class="muted break-all">计划ID：{{ selectedPlan.id }} · 画像 v{{ selectedPlan.profileVersion }}</span>
                </div>
                <div class="actions">
                  <el-tag :type="statusTagType(selectedPlan.status)">{{ commonStatusLabels[selectedPlan.status] || selectedPlan.status }}</el-tag>
                  <el-button size="small" :icon="Refresh" @click="loadPlan(selectedPlan.id)">刷新</el-button>
                </div>
              </div>
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
                        <p>{{ item.skillCode }} · {{ kindLabel(item.kind) }} · 预计 {{ item.estimatedMinutes }} 分钟</p>
                      </div>
                      <el-tag :type="statusTagType(item.status)">{{ commonStatusLabels[item.status] || item.status }}</el-tag>
                    </div>
                    <div v-if="item.resourceRefs.length" class="resource-refs">
                      <h4>授权资料引用</h4>
                      <blockquote v-for="reference in item.resourceRefs" :key="`${reference.documentId}:${reference.chunkId}`">
                        <p class="pre-wrap">{{ reference.quote }}</p>
                        <span>{{ reference.documentId }} · v{{ reference.documentVersion }} · {{ reference.chunkId }}</span>
                      </blockquote>
                    </div>
                    <el-alert v-if="itemActionErrors[item.id]" :title="errorMessage(itemActionErrors[item.id])" type="error" :closable="false" show-icon />
                    <div class="actions">
                      <el-button v-if="item.kind === 'READING'" type="primary" :disabled="item.status === 'COMPLETED'" :loading="itemActionPending.includes(item.id)" @click="completeReading(item)">{{ item.status === 'COMPLETED' ? '阅读已完成' : itemActionErrors[item.id] ? '重试完成阅读' : '完成阅读' }}</el-button>
                      <el-button v-else-if="item.kind === 'PRACTICE'" type="primary" :disabled="sessionTaskActive" :loading="sessionStartingItemId === item.id" @click="startPractice(item)">{{ item.status === 'COMPLETED' ? '再次练习' : '开始答题' }}</el-button>
                      <el-button v-else type="success" plain @click="goInterview(item)">进入模拟面试 <el-icon><Right /></el-icon></el-button>
                    </div>
                  </div>
                </article>
              </div>
            </template>
          </AsyncState>
        </section>
      </div>

      <section v-if="sessionActionError || sessionTask" class="panel">
        <div class="panel__heading"><h2>训练题准备任务</h2></div>
        <el-alert v-if="sessionActionError" :title="errorMessage(sessionActionError)" type="error" :closable="false" show-icon />
        <TaskStatus v-if="sessionTask" :task="sessionTask">
          <template #actions>
            <el-button v-if="canRetryTrackedTask(sessionTask)" size="small" type="primary" :loading="sessionRetrying" @click="retrySessionTask">按原参数重试</el-button>
          </template>
        </TaskStatus>
        <div v-if="sessionTrackingError" class="tracking-error">
          <span>{{ errorMessage(sessionTrackingError) }}</span>
          <el-button size="small" @click="sessionTracker.restart">恢复任务跟踪</el-button>
        </div>
      </section>

      <section v-if="session || sessionLoading || sessionError || route.query.sessionId" class="panel session-panel">
        <div class="panel__heading">
          <div>
            <h2>当前答题会话</h2>
            <span v-if="session" class="muted break-all">{{ session.id }} · 版本 {{ session.version }}</span>
          </div>
          <div class="actions">
            <el-tag v-if="session" :type="statusTagType(session.status)">{{ commonStatusLabels[session.status] || session.status }}</el-tag>
            <el-button v-if="session" size="small" :icon="Refresh" @click="loadSession(session.id, { preserveContent: true })">刷新会话</el-button>
          </div>
        </div>
        <AsyncState :loading="sessionLoading" :error="sessionError" :empty="!session" empty-text="尚未选择训练会话" @retry="loadSession(sessionRequestId, { preserveContent: true })">
          <template v-if="session">
            <el-alert v-if="session.status === 'PREPARING'" title="训练题仍在准备，请稍后刷新会话" type="warning" :closable="false" show-icon />
            <el-alert v-else-if="session.status === 'FAILED'" title="训练题准备失败，请通过计划项重新发起或重试原任务" type="error" :closable="false" show-icon />
            <template v-else-if="session.question">
              <div class="question-card">
                <div class="question-card__meta">
                  <el-tag type="primary" effect="plain">{{ difficultyLabel(session.question.difficulty) }}</el-tag>
                  <el-tag effect="plain">{{ questionTypeLabel(session.question.type) }}</el-tag>
                  <el-tag v-for="skill in session.question.skillCodes" :key="skill" type="info">{{ skill }}</el-tag>
                </div>
                <h3>题目</h3>
                <p class="pre-wrap question-stem">{{ session.question.stem }}</p>
                <div class="question-source">
                  <span>题目版本 v{{ session.question.questionVersion }}</span>
                  <span>{{ session.question.sourceType === 'CURATED' ? '审核题库' : 'AI生成并完成结构校验' }}</span>
                  <span v-if="session.question.sourceTitle">来源：{{ session.question.sourceTitle }}</span>
                </div>
              </div>

              <div class="answer-editor">
                <div class="answer-editor__heading">
                  <h3>我的回答</h3>
                  <span :class="{ 'limit-warning': answerContent.length >= 10000 }">{{ answerContent.length }} / 10000</span>
                </div>
                <el-input v-model="answerContent" type="textarea" :rows="9" maxlength="10000" show-word-limit resize="vertical" placeholder="请输入完整分析与解决方案，草稿会按会话自动保存在当前浏览器" :disabled="session.status !== 'ACTIVE' || evaluationTaskActive || answerSubmitted" />
                <div class="draft-status">
                  <span v-if="answerSubmitted">回答已提交；失败重试会继续使用相同内容</span>
                  <span v-else-if="draftSaveFailed" class="error-text">浏览器草稿保存失败，请勿在提交前关闭页面</span>
                  <span v-else>输入自动保存为当前会话草稿</span>
                </div>
                <el-alert v-if="answerConflict" title="会话版本已更新" description="请再次核对题目与未更改的回答，确认后重新点击提交评价。系统没有自动重交或修改答案。" type="warning" :closable="false" show-icon />
                <el-alert v-if="answerActionError" :title="errorMessage(answerActionError)" type="error" :closable="false" show-icon />
                <div class="actions">
                  <el-button v-if="!answerSubmitted" type="primary" :disabled="!canSubmitAnswer" :loading="answerSubmitting" @click="submitAnswer">{{ answerConflict ? '确认并再次提交评价' : '提交评价' }}</el-button>
                  <el-button v-if="answerSubmitted && (answer?.status === 'FAILED' || evaluationTask && ['FAILED', 'CANCELLED', 'EXPIRED'].includes(evaluationTask.status))" type="primary" :loading="answerRetrying" :disabled="evaluationTaskActive" @click="retryEvaluation">使用相同回答重试评价</el-button>
                </div>
              </div>
            </template>
            <el-empty v-else description="会话尚未返回题目" />

            <div v-if="evaluationTask" class="evaluation-task">
              <TaskStatus :task="evaluationTask">
                <template #actions>
                  <el-button v-if="canRetryTrackedTask(evaluationTask)" size="small" type="primary" :loading="answerRetrying" @click="retryEvaluation">使用相同回答重试</el-button>
                </template>
              </TaskStatus>
              <div v-if="evaluationTrackingError" class="tracking-error">
                <span>{{ errorMessage(evaluationTrackingError) }}</span>
                <el-button size="small" @click="evaluationTracker.restart">恢复任务跟踪</el-button>
              </div>
            </div>

            <section v-if="answer || answerLoading || answerError" class="answer-result">
              <div class="panel__heading"><h2>回答与评价</h2><el-button v-if="answer" size="small" :icon="Refresh" @click="loadAnswer(answer.id, { preserveContent: true })">刷新评价</el-button></div>
              <AsyncState :loading="answerLoading" :error="answerError" :empty="!answer" empty-text="暂无回答评价" @retry="loadAnswer(answerRequestId, { preserveContent: true })">
                <template v-if="answer">
                  <div class="original-answer">
                    <h3>已提交原回答</h3>
                    <p class="pre-wrap">{{ answer.content }}</p>
                    <span class="muted break-all">回答ID：{{ answer.id }}</span>
                  </div>
                  <el-tag :type="statusTagType(answer.status)">{{ commonStatusLabels[answer.status] || answer.status }}</el-tag>
                  <el-alert v-if="answer.status === 'EVALUATING'" title="回答已保存，正在等待权威评价结果" type="warning" :closable="false" show-icon />
                  <el-alert v-else-if="answer.status === 'FAILED'" title="评价失败，回答内容已保留，可按相同内容重试" type="error" :closable="false" show-icon />
                  <template v-if="answer.evaluation">
                    <div class="evaluation-overview">
                      <div class="overall-score"><strong>{{ answer.evaluation.overallScore === null ? '待评估' : displayScore(answer.evaluation.overallScore) }}</strong><span>综合得分</span></div>
                      <div>
                        <el-tag :type="statusTagType(answer.evaluation.evidenceStatus)">{{ evidenceLabel(answer.evaluation.evidenceStatus) }}</el-tag>
                        <p class="muted">量表 {{ answer.evaluation.rubricVersion }} · 提示词 {{ answer.evaluation.promptVersion }}</p>
                        <p class="muted break-all">模型 {{ answer.evaluation.modelVersion }}</p>
                      </div>
                    </div>
                    <div class="detail-block">
                      <h3>评价维度</h3>
                      <el-table :data="answer.evaluation.dimensions" border size="small">
                        <el-table-column label="维度" min-width="110"><template #default="scope">{{ dimensionLabel(scope.row.key) }}</template></el-table-column>
                        <el-table-column prop="score" label="得分" width="80" />
                        <el-table-column prop="weight" label="权重" width="80" />
                        <el-table-column prop="comment" label="评价说明" min-width="240" />
                      </el-table>
                    </div>
                    <div class="feedback-grid">
                      <section class="feedback-card feedback-card--strength">
                        <h3>优点</h3>
                        <ul v-if="answer.evaluation.strengths.length"><li v-for="(item, index) in answer.evaluation.strengths" :key="index">{{ item }}</li></ul>
                        <p v-else class="muted">暂无优点反馈</p>
                      </section>
                      <section class="feedback-card feedback-card--improvement">
                        <h3>改进建议</h3>
                        <ul v-if="answer.evaluation.improvements.length"><li v-for="(item, index) in answer.evaluation.improvements" :key="index">{{ item }}</li></ul>
                        <p v-else class="muted">暂无改进建议</p>
                      </section>
                    </div>
                    <section class="reference-answer">
                      <h3>参考答案</h3>
                      <p v-if="answer.evaluation.referenceAnswer" class="pre-wrap">{{ answer.evaluation.referenceAnswer }}</p>
                      <p v-else class="muted">当前评价未提供参考答案</p>
                    </section>
                  </template>
                  <el-empty v-else-if="answer.status === 'EVALUATED'" :image-size="60" description="评价结果待评估，暂无可展示维度" />
                </template>
              </AsyncState>
            </section>
          </template>
        </AsyncState>
      </section>
    </template>

    <template v-else>
      <section class="panel">
        <div class="panel__heading">
          <div>
            <h2>错题本</h2>
            <p class="muted">有效评价低于60分记录为错题；重新练习会创建新会话并保留历史回答。</p>
          </div>
          <el-radio-group v-model="mistakeResolved" @change="changeMistakeFilter">
            <el-radio-button :label="false">待攻克</el-radio-button>
            <el-radio-button :label="true">已解决</el-radio-button>
          </el-radio-group>
        </div>
        <el-alert v-if="sessionActionError" :title="errorMessage(sessionActionError)" type="error" :closable="false" show-icon />
        <TaskStatus v-if="sessionTask && sessionTaskContext === 'mistakes'" :task="sessionTask">
          <template #actions>
            <el-button v-if="canRetryTrackedTask(sessionTask)" size="small" type="primary" :loading="sessionRetrying" @click="retrySessionTask">按原参数重试错题重练</el-button>
          </template>
        </TaskStatus>
        <AsyncState :loading="mistakesLoading" :error="mistakesError" :empty="!mistakes.records.length" :empty-text="mistakeResolved ? '暂无已解决错题' : '暂无待攻克错题'" @retry="loadMistakes">
          <div class="mistake-list">
            <article v-for="mistake in mistakes.records" :key="mistake.id" class="mistake-card">
              <div class="mistake-card__heading">
                <div class="question-card__meta">
                  <el-tag size="small" type="primary" effect="plain">{{ difficultyLabel(mistake.question.difficulty) }}</el-tag>
                  <el-tag v-for="skill in mistake.question.skillCodes" :key="skill" size="small" type="info">{{ skill }}</el-tag>
                  <el-tag size="small" :type="mistake.resolved ? 'success' : 'danger'">{{ mistake.resolved ? '已解决' : `错误 ${mistake.wrongCount} 次` }}</el-tag>
                </div>
                <span class="muted">题目 v{{ mistake.question.questionVersion }}</span>
              </div>
              <p class="pre-wrap mistake-stem">{{ mistake.question.stem }}</p>
              <p class="muted break-all">最近回答ID：{{ mistake.lastAnswerId }}</p>
              <div class="actions">
                <el-button type="primary" plain @click="openMistake(mistake)">查看题目与原回答</el-button>
                <el-button type="primary" :loading="retryingMistakeId === mistake.id" :disabled="sessionTaskActive" @click="retryMistake(mistake)">重新练习</el-button>
              </div>
            </article>
          </div>
        </AsyncState>
        <div class="pagination">
          <el-pagination background layout="total, sizes, prev, pager, next" :total="mistakes.total" :current-page="mistakes.current" :page-size="mistakes.size" :page-sizes="[6, 12, 24]" @current-change="changeMistakePage" @size-change="changeMistakeSize" />
        </div>
      </section>
    </template>

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
.page-header h1 { display: flex; align-items: center; gap: 10px; }
.training-tabs { margin-bottom: 16px; padding: 0 16px; border: 1px solid #e8f0fa; border-radius: $ai-radius-lg; background: #fff; }
.training-layout { display: grid; grid-template-columns: 330px minmax(0, 1fr); align-items: start; gap: 14px; }
.plan-list-panel { min-width: 0; }
.plan-detail-panel { min-width: 0; }
.break-all { word-break: break-all; }
.plan-card { display: grid; gap: 10px; width: 100%; padding: 15px; border: 1px solid $ai-border; border-radius: $ai-radius; color: inherit; text-align: left; background: #fff; cursor: pointer; }
.plan-card:hover, .plan-card--active { border-color: $ai-primary; background: $ai-primary-light; }
.plan-card__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.plan-card__heading strong { color: #101d4b; line-height: 1.5; }
.plan-card__progress { color: $ai-text-secondary; font-size: 12px; text-align: right; }
.plan-items { display: grid; gap: 12px; margin-top: 16px; }
.plan-item { display: flex; gap: 12px; padding: 15px; border: 1px solid $ai-border; border-radius: $ai-radius; }
.plan-item__index { display: flex; flex: 0 0 28px; align-items: center; justify-content: center; height: 28px; border-radius: 50%; color: $ai-primary; font-weight: 700; background: $ai-primary-light; }
.plan-item__content { min-width: 0; flex: 1; }
.plan-item__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.plan-item__heading h3 { margin: 0 0 6px; color: #101d4b; font-size: 16px; }
.plan-item__heading p { margin: 0; color: $ai-text-secondary; font-size: 13px; }
.plan-item .actions { margin-top: 13px; }
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
  .training-layout { grid-template-columns: 1fr; }
}
@media (max-width: 700px) {
  .training-tabs { padding: 0 8px; }
  .feedback-grid, .mistake-list { grid-template-columns: 1fr; }
  .plan-item { padding: 12px; }
  .plan-item__heading, .mistake-card__heading { flex-direction: column; }
  .evaluation-overview { align-items: flex-start; flex-direction: column; }
  .pagination { justify-content: center; overflow-x: auto; }
}
</style>