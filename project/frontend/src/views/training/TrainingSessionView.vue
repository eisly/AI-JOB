<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import { learningApi } from '@/api/learning'
import { adaptLegacySession, isEndpointMissing, legacySubmitAnswer, practiceApi } from '@/api/training'
import { canRetryTrackedTask, retryTrackedTask } from '@/api/task-registry'
import type {
  LearningAnswerVO,
  PracticeAnswerPayload,
  PracticeQuestionType,
  PracticeQuestionVO,
  PracticeSetResultVO,
  PracticeSetVO,
  TaskVO,
} from '@/api/types'
import { useTask } from '@/composables/useTask'
import AsyncState from '@/components/AsyncState.vue'
import TaskStatus from '@/components/TaskStatus.vue'
import WaitStatus from '@/components/WaitStatus.vue'
import QuestionRenderer from '@/components/training/QuestionRenderer.vue'
import TrainingFlowHeader from '@/components/training/TrainingFlowHeader.vue'
import { errorMessage } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const draftPrefix = 'ai-job-practice-draft-v2'
const startPrefix = 'ai-job-practice-start-v1'

const setId = computed(() => String(route.params.setId || ''))

// ===== 练习集加载；历史链接与显式继续旧会话允许兼容读取 =====
const practiceSet = ref<PracticeSetVO | null>(null)
const isLegacyMode = ref(false)
const loadRequestId = ref('')
const loadLoading = ref(false)
const loadError = ref<unknown>(null)
let loadSequence = 0

async function loadSet() {
  const id = setId.value
  if (!id) return
  loadRequestId.value = id
  const sequence = ++loadSequence
  loadLoading.value = true
  loadError.value = null
  try {
    let value: PracticeSetVO
    try {
      value = await practiceApi.getSet(id)
      isLegacyMode.value = false
    } catch (reason) {
      if (route.query.legacy !== '1' || !isEndpointMissing(reason)) throw reason
      // 降级：旧版单题会话
      value = adaptLegacySession(await learningApi.getSession(id))
      isLegacyMode.value = true
    }
    if (sequence !== loadSequence) return
    practiceSet.value = value
    submitted.value = ['SUBMITTED', 'EVALUATING', 'EVALUATED'].includes(value.status)
    ensureStartAt()
    loadAnswers()
    if (!currentQuestionId.value && value.questions.length) currentQuestionId.value = value.questions[0]!.id
    if (value.status === 'EVALUATED') {
      if (!result.value) await loadResult()
    } else if (submitted.value && value.evaluationTaskId) {
      evaluationTaskId.value = value.evaluationTaskId
    } else if (submitted.value && isLegacyMode.value && value.lastAnswerId) {
      const answer = await learningApi.getAnswer(value.lastAnswerId)
      if (sequence === loadSequence) evaluationTaskId.value = answer.taskId
    }
  } catch (reason) {
    if (sequence === loadSequence) loadError.value = reason
  } finally {
    if (sequence === loadSequence) loadLoading.value = false
  }
}

// ===== 作答状态与草稿 =====
const answers = ref<Record<string, PracticeAnswerPayload>>({})
const currentQuestionId = ref('')
const draftSaveFailed = ref(false)
const draftSavedAt = ref<number | null>(null)
let draftTimer: number | undefined

const questions = computed<PracticeQuestionVO[]>(() => practiceSet.value?.questions || [])
const currentIndex = computed(() => Math.max(0, questions.value.findIndex(item => item.id === currentQuestionId.value)))
const currentQuestion = computed(() => questions.value[currentIndex.value] || null)
const currentAnswer = computed<PracticeAnswerPayload>(() => {
  const question = currentQuestion.value
  if (!question) return { questionId: '', type: 'SUBJECTIVE' }
  return answers.value[question.id] || { questionId: question.id, type: question.type }
})

const typeGroups = computed<{ type: PracticeQuestionType; label: string; items: PracticeQuestionVO[] }[]>(() => {
  const groups: { type: PracticeQuestionType; label: string; items: PracticeQuestionVO[] }[] = [
    { type: 'CHOICE', label: '选择题', items: [] },
    { type: 'FILL', label: '填空题', items: [] },
    { type: 'SUBJECTIVE', label: '主观题', items: [] },
  ]
  for (const question of questions.value) groups.find(group => group.type === question.type)?.items.push(question)
  return groups.filter(group => group.items.length)
})

function isAnswered(question: PracticeQuestionVO) {
  const answer = answers.value[question.id]
  if (!answer) return false
  if (question.type === 'CHOICE') return !!answer.choiceKey
  if (question.type === 'FILL') return !!answer.blanks?.some(value => value.trim())
  return !!answer.content?.trim()
}

const answeredCount = computed(() => questions.value.filter(isAnswered).length)
const unansweredCount = computed(() => questions.value.length - answeredCount.value)
const answeredPercentage = computed(() => questions.value.length ? Math.round(answeredCount.value / questions.value.length * 100) : 0)

const submitting = ref(false)
const submitted = ref(false)

function canNavigate() {
  return !submitting.value && !submitted.value
}

function goQuestion(questionId: string) {
  if (!canNavigate()) return
  currentQuestionId.value = questionId
}

function goPrev() {
  if (currentIndex.value > 0) goQuestion(questions.value[currentIndex.value - 1]!.id)
}

function goNext() {
  if (currentIndex.value < questions.value.length - 1) goQuestion(questions.value[currentIndex.value + 1]!.id)
}

function draftKey() {
  return `${draftPrefix}:${setId.value}`
}

function loadAnswers() {
  try {
    const raw = localStorage.getItem(draftKey())
    if (!raw) return
    const parsed = JSON.parse(raw) as Record<string, PracticeAnswerPayload>
    for (const question of questions.value) {
      const saved = parsed[question.id]
      if (saved) answers.value[question.id] = { ...saved, questionId: question.id, type: question.type }
    }
  } catch {
    return
  }
}

function persistDraft() {
  try {
    localStorage.setItem(draftKey(), JSON.stringify(answers.value))
    draftSaveFailed.value = false
    draftSavedAt.value = Date.now()
  } catch {
    draftSaveFailed.value = true
  }
}

watch(answers, () => {
  if (submitted.value) return
  window.clearTimeout(draftTimer)
  draftTimer = window.setTimeout(persistDraft, 600)
}, { deep: true })

function clearDraft() {
  try {
    localStorage.removeItem(draftKey())
  } catch {
    return
  }
}

// ===== 计时 =====
const elapsedSeconds = ref(0)
let clockTimer: number | undefined

function startKey() {
  return `${startPrefix}:${setId.value}`
}

function ensureStartAt() {
  try {
    if (!localStorage.getItem(startKey())) {
      localStorage.setItem(startKey(), String(Date.now()))
    }
  } catch {
    return
  }
}

function readStartAt(): number {
  try {
    return Number(localStorage.getItem(startKey())) || Date.now()
  } catch {
    return Date.now()
  }
}

function startClock() {
  window.clearInterval(clockTimer)
  const startedAt = readStartAt()
  const tick = () => {
    elapsedSeconds.value = Math.max(0, Math.floor((Date.now() - startedAt) / 1000))
  }
  tick()
  clockTimer = window.setInterval(tick, 1000)
}

const elapsedLabel = computed(() => {
  const total = elapsedSeconds.value
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  const pad = (value: number) => String(value).padStart(2, '0')
  return hours > 0 ? `${pad(hours)}:${pad(minutes)}:${pad(seconds)}` : `${pad(minutes)}:${pad(seconds)}`
})

onBeforeUnmount(() => {
  window.clearInterval(clockTimer)
  window.clearTimeout(draftTimer)
})

// ===== 交卷与评估 =====
const evaluationError = ref<string | null>(null)
const evaluationTaskId = ref<string | null>(null)
const retryingEvaluation = ref(false)

const evaluationTracker = useTask(evaluationTaskId, { onDone: handleEvaluationDone })
const evaluationTask = evaluationTracker.task

const result = ref<PracticeSetResultVO | null>(null)
const resultLoading = ref(false)
const resultError = ref<unknown>(null)

function questionById(id: string) {
  return questions.value.find(item => item.id === id)
}

async function submitPaper() {
  const set = practiceSet.value
  if (!set || submitting.value || submitted.value) return
  if (!questions.value.length) {
    ElMessage.warning('本组暂无题目，无法交卷')
    return
  }
  if (unansweredCount.value > 0) {
    try {
      await ElMessageBox.confirm(
        `还有 ${unansweredCount.value} 道题未作答，未作答的题目将按空答案评估。确认交卷吗？`,
        '确认交卷',
        { confirmButtonText: '确认交卷', cancelButtonText: '继续作答', type: 'warning' },
      )
    } catch {
      return
    }
  }
  if (submitting.value || submitted.value || set.id !== setId.value) return
  persistDraft()
  submitting.value = true
  evaluationError.value = null
  try {
    if (isLegacyMode.value) {
      const question = questions.value[0]!
      const content = answers.value[question.id]?.content || ''
      if (!content.trim()) {
        ElMessage.warning('请先填写回答再提交')
        return
      }
      const task = await legacySubmitAnswer(set.id, content, set.version)
      submitted.value = true
      evaluationTaskId.value = task.id
      // 旧版没有整组结果接口：等评价任务完成后直接读取单题回答
    } else {
      const payload = {
        answers: questions.value.map(question => answers.value[question.id] || { questionId: question.id, type: question.type }),
        durationSeconds: elapsedSeconds.value,
        expectedVersion: set.version,
      }
      const task = await practiceApi.submitSet(set.id, payload)
      submitted.value = true
      evaluationTaskId.value = task.id
    }
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  } finally {
    submitting.value = false
  }
}

async function handleEvaluationDone(_task: TaskVO) {
  const loaded = await loadResult()
  if (!loaded) return
  clearDraft()
  try {
    localStorage.removeItem(startKey())
  } catch { /* The report remains available when browser storage is blocked. */ }
  ElMessage.success('评估完成，已生成本组训练反馈报告')
}

async function loadResult() {
  const id = setId.value
  const sequence = loadSequence
  resultLoading.value = true
  resultError.value = null
  try {
    let value: PracticeSetResultVO
    if (isLegacyMode.value) {
      const session = await learningApi.getSession(id)
      const answerId = session.lastAnswerId
      if (!answerId) throw new Error('暂未获取到回答ID，请稍后刷新')
      const answer = await learningApi.getAnswer(answerId)
      value = adaptLegacyAnswerToResult(answer)
    } else {
      value = await practiceApi.getResult(id)
    }
    if (sequence !== loadSequence) return false
    result.value = value
    return true
  } catch (reason) {
    if (sequence === loadSequence) resultError.value = reason
    return false
  } finally {
    if (sequence === loadSequence) resultLoading.value = false
  }
}

function adaptLegacyAnswerToResult(answer: LearningAnswerVO): PracticeSetResultVO {
  const question = questions.value[0]
  return {
    id: setId.value,
    status: 'EVALUATED',
    overallScore: answer.evaluation?.overallScore ?? null,
    objectiveCorrectCount: 0,
    objectiveTotalCount: 0,
    summary: '本次为旧版单题训练，AI 已完成对该题回答的评估。',
    strengths: answer.evaluation?.strengths || [],
    improvements: answer.evaluation?.improvements || [],
    questionResults: [
      {
        questionId: question?.id || '',
        correct: null,
        score: answer.evaluation?.overallScore ?? null,
        comment: answer.evaluation?.dimensions.map(item => `${item.key}: ${item.comment}`).join('；') || '',
        evaluation: answer.evaluation,
      },
    ],
  }
}

async function retryEvaluation() {
  const task = evaluationTask.value
  if (!task || !canRetryTrackedTask(task) || retryingEvaluation.value) return
  const session = setId.value
  retryingEvaluation.value = true
  evaluationError.value = null
  try {
    const retried = await retryTrackedTask(task)
    if (session === setId.value) evaluationTaskId.value = retried.id
  } catch (reason) {
    if (session === setId.value) evaluationError.value = errorMessage(reason)
  } finally {
    retryingEvaluation.value = false
  }
}

// ===== 结果展示辅助 =====
const resultMode = computed(() => !!result.value)

function questionResultOf(questionId: string) {
  return result.value?.questionResults.find(item => item.questionId === questionId) || null
}

function navStateOf(question: PracticeQuestionVO): 'answered' | 'current' | 'pending' {
  if (question.id === currentQuestionId.value) return 'current'
  return isAnswered(question) ? 'answered' : 'pending'
}

const objectiveSummary = computed(() => {
  if (!result.value || !result.value.objectiveTotalCount) return null
  return `${result.value.objectiveCorrectCount} / ${result.value.objectiveTotalCount}`
})

const needsReview = computed(() => result.value?.questionResults.filter(({ correct, evaluation }) => correct === false
  || (evaluation?.evidenceStatus === 'VALID' && evaluation.overallScore != null && evaluation.overallScore < 60)
  || (evaluation && 'answerClass' in evaluation && evaluation.answerClass === 'UNKNOWN')) || [])
const reviewingMistakes = ref(false)
const visibleReviewQuestions = computed(() => reviewingMistakes.value
  ? questions.value.filter(question => needsReview.value.some(item => item.questionId === question.id)) : questions.value)
const allQuestionTypes = { CHOICE: '选择题', FILL: '填空题', SUBJECTIVE: '主观题' }

function reviewMistakes() {
  reviewingMistakes.value = !reviewingMistakes.value
}

function leaveSession() {
  void router.push({ name: 'training', query: { planId: practiceSet.value?.planId || undefined, tab: route.query.from === 'mistakes' ? 'mistakes' : undefined } })
}

async function exitPractice() {
  if (submitted.value || resultMode.value || !answeredCount.value) { leaveSession(); return }
  try {
    await ElMessageBox.confirm(
      '返回后作答草稿会保留在当前浏览器，可稍后从训练单元重新进入继续作答。确认返回专项训练吗？',
      '返回专项训练',
      { confirmButtonText: '返回专项训练', cancelButtonText: '继续作答', type: 'warning' },
    )
  } catch {
    return
  }
  leaveSession()
}

onMounted(() => {
  void loadSet().then(() => {
    if (resultMode.value) return
    startClock()
  })
})

watch(setId, () => {
  result.value = null
  resultLoading.value = false
  resultError.value = null
  evaluationTaskId.value = null
  evaluationError.value = null
  reviewingMistakes.value = false
  submitted.value = false
  answers.value = {}
  currentQuestionId.value = ''
  void loadSet()
})
</script>

<template>
  <div class="practice-page">
    <TrainingFlowHeader
      class="practice-top"
      :title="practiceSet?.title || '专项训练'"
      :subtitle="resultMode ? '整组练习 · 评估完成' : '整组练习'"
      :back-label="route.query.from === 'mistakes' ? '返回错题本' : '返回训练计划'"
      :disabled="submitting"
      @back="exitPractice"
    >
      <el-button v-if="resultMode" @click="router.push({ name: 'training', query: { tab: 'mistakes', planId: practiceSet?.planId || undefined } })">查看错题本</el-button>
      <el-tag v-else-if="!submitted && currentQuestion" effect="plain">第 {{ currentQuestion.index }} / {{ questions.length }} 题</el-tag>
    </TrainingFlowHeader>

    <div class="practice-content">
      <header class="practice-heading">
        <h1>{{ submitted || resultMode ? '练习结果' : route.query.from === 'mistakes' ? '错题重练' : '整组练习' }}</h1>
        <p v-if="resultMode">查看本组表现，从错题开始复盘。</p>
        <p v-else-if="!submitted">作答后统一交卷，可在题号区查看完成情况。</p>
      </header>

      <AsyncState :loading="loadLoading" :retain="practiceSet?.id === setId" :error="loadError" :empty="!practiceSet || practiceSet.id !== setId" empty-text="未找到本组训练" @retry="loadSet">
        <template v-if="practiceSet">
          <WaitStatus v-if="practiceSet.status === 'GENERATING'" title="正在准备本组题目">
            <template #actions><el-button size="small" :loading="loadLoading" @click="loadSet">刷新题组</el-button></template>
          </WaitStatus>
          <el-alert v-else-if="practiceSet.status === 'FAILED'" title="本组题目生成失败，请返回训练计划重新生成" type="error" show-icon :closable="false" />

          <template v-else-if="resultMode && result">
            <div class="session-grid">
              <div class="review-column">
                <section class="practice-card">
                  <div class="practice-row"><h2>本组得分</h2><el-tag type="success" size="small">评估完成</el-tag></div>
                  <div class="result-score"><strong>{{ result.overallScore ?? '—' }}</strong><span>/ 100</span></div>
                  <p v-if="result.summary" class="result-summary">{{ result.summary }}</p>
                  <div class="result-metrics">
                    <div><span>客观题正确</span><strong>{{ objectiveSummary || '—' }}</strong></div>
                    <div><span>主观题已评估</span><strong>{{ result.questionResults.filter(item => item.evaluation).length }}</strong></div>
                    <div><span>待复盘题目</span><strong>{{ needsReview.length }}</strong></div>
                  </div>
                </section>
                <section v-if="result.improvements.length || needsReview.length" class="practice-card">
                  <h2>优先复盘</h2>
                  <p v-if="result.improvements[0]" class="result-summary">{{ result.improvements[0] }}</p>
                  <div v-if="needsReview.length">
                    <el-button type="primary" :aria-pressed="reviewingMistakes" @click="reviewMistakes">{{ reviewingMistakes ? '查看全部题目' : '查看本组错题' }}</el-button>
                  </div>
                </section>
              </div>
              <aside class="practice-card">
                <h2>本组反馈</h2>
                <section class="review-section"><h3>表现稳定</h3>
                  <ul v-if="result.strengths.length"><li v-for="(item, i) in result.strengths" :key="i">{{ item }}</li></ul>
                  <p v-else class="muted">暂无优点反馈</p>
                </section>
                <section class="review-section"><h3>需要加强</h3>
                  <ul v-if="result.improvements.length"><li v-for="(item, i) in result.improvements" :key="i">{{ item }}</li></ul>
                  <p v-else class="muted">暂无改进建议</p>
                </section>
                <div class="practice-row"><el-button @click="leaveSession">返回训练计划</el-button><el-button link :loading="resultLoading" @click="loadResult">刷新结果</el-button></div>
                <el-alert v-if="resultError" :title="errorMessage(resultError)" type="error" :closable="false" show-icon />
              </aside>
            </div>

            <section class="review-list" aria-label="逐题复盘">
              <div class="practice-row"><h2>{{ reviewingMistakes ? '本组错题' : '逐题复盘' }}</h2><span class="muted">{{ visibleReviewQuestions.length }} 题</span></div>
              <section
                v-for="question in visibleReviewQuestions"
                :key="question.id" :id="'review-' + question.id"
                class="result-question"
              >
                <header class="result-question__heading">
                  <h4>第 {{ question.index }} 题 · {{ allQuestionTypes[question.type] }}</h4>
                  <span v-if="questionResultOf(question.id)?.correct === true" class="result-tag result-tag--pass">回答正确</span>
                  <span v-else-if="questionResultOf(question.id)?.correct === false" class="result-tag result-tag--fail">回答错误</span>
                  <span v-else class="result-tag result-tag--evaluated">AI 已评估</span>
                </header>
                <QuestionRenderer
                  :question="question"
                  :answer="answers[question.id] || { questionId: question.id, type: question.type }"
                  :correct-keys="questionResultOf(question.id)?.correctKeys"
                  readonly
                />
                <div v-if="questionResultOf(question.id)" class="result-question__comment">
                  <p v-if="questionResultOf(question.id)!.comment">{{ questionResultOf(question.id)!.comment }}</p>
                  <template v-if="questionResultOf(question.id)!.evaluation">
                    <div class="evaluation-overview">
                      <div class="overall-score">
                        <strong>{{ questionResultOf(question.id)!.evaluation!.overallScore ?? '待评估' }}</strong>
                        <span>本题得分</span>
                      </div>
                      <div>
                        <p v-if="questionResultOf(question.id)!.evaluation!.referenceAnswer" class="pre-wrap">参考答案：{{ questionResultOf(question.id)!.evaluation!.referenceAnswer }}</p>
                      </div>
                    </div>
                    <div class="feedback-grid">
                      <section class="feedback-card feedback-card--strength">
                        <h5>优点</h5>
                        <ul><li v-for="(item, index) in questionResultOf(question.id)!.evaluation!.strengths" :key="index">{{ item }}</li></ul>
                      </section>
                      <section class="feedback-card feedback-card--improvement">
                        <h5>改进建议</h5>
                        <ul><li v-for="(item, index) in questionResultOf(question.id)!.evaluation!.improvements" :key="index">{{ item }}</li></ul>
                      </section>
                    </div>
                  </template>
                </div>
              </section>
            </section>
          </template>

          <section v-else-if="submitted" class="practice-card evaluation-status">
            <WaitStatus v-if="resultLoading" title="正在读取练习结果" />
            <WaitStatus v-else-if="resultError" title="读取练习结果失败" :description="errorMessage(resultError)" state="error">
              <template #actions><el-button size="small" @click="loadResult">重新读取结果</el-button></template>
            </WaitStatus>
            <TaskStatus
              v-else-if="evaluationTaskId"
              :task="evaluationTask"
              :loading="evaluationTracker.loading.value || !evaluationTask"
              :tracking-error="evaluationTracker.error.value"
              :refreshing="evaluationTracker.refreshing.value"
              :reasoning="evaluationTracker.reasoningText.value"
              running-description="评估完成后会自动显示练习结果。"
              @refresh="evaluationTracker.refresh"
            >
              <template #actions>
                <el-button v-if="evaluationTask && canRetryTrackedTask(evaluationTask)" size="small" type="primary" :loading="retryingEvaluation" @click="retryEvaluation">重试评估</el-button>
              </template>
            </TaskStatus>
            <WaitStatus v-else title="作答已提交" description="刷新题组后可查看当前状态和结果。" state="idle">
              <template #actions><el-button size="small" :loading="loadLoading" @click="loadSet">刷新题组</el-button></template>
            </WaitStatus>
            <el-alert v-if="evaluationError" :title="evaluationError" type="error" show-icon :closable="false" />
          </section>
          <template v-else-if="currentQuestion">
            <div class="session-grid">
              <section class="practice-card question-panel">
                <QuestionRenderer
                  :question="currentQuestion"
                  :answer="currentAnswer"
                  :readonly="submitting || submitted"
                  @update:answer="value => { answers[value.questionId] = value }"
                />
                <footer class="question-panel__footer">
                  <span class="muted" aria-live="polite">
                    {{ draftSaveFailed ? '草稿保存失败' : draftSavedAt ? '已自动保存' : '答案会自动保存' }}
                  </span>
                  <div class="footer-actions">
                    <el-button :icon="ArrowLeft" :disabled="currentIndex === 0 || submitting" @click="goPrev">上一题</el-button>
                    <el-button v-if="currentIndex < questions.length - 1" :disabled="submitting" @click="goNext">下一题 <el-icon><ArrowRight /></el-icon></el-button>
                    <el-button type="primary" :loading="submitting" @click="submitPaper">{{ submitting ? '正在交卷…' : '交卷并评估' }}</el-button>
                  </div>
                </footer>
              </section>
              <aside class="practice-card session-nav">
                <h2>答题进度</h2>
                <div class="practice-row"><strong class="nav-count">{{ answeredCount }} / {{ questions.length }}</strong><el-tag :type="unansweredCount ? 'info' : 'success'" size="small">{{ unansweredCount ? '还有 ' + unansweredCount + ' 题待作答' : '全部完成' }}</el-tag></div>
                <div class="question-numbers">
                  <button
                    v-for="question in questions"
                    :key="question.id"
                    type="button"
                    class="nav-number"
                    :class="'nav-number--' + navStateOf(question)"
                    :aria-label="'第 ' + question.index + ' 题' + (isAnswered(question) ? '，已答' : '，未答')"
                    :aria-current="question.id === currentQuestionId ? 'true' : undefined"
                    @click="goQuestion(question.id)"
                  >{{ question.index }}</button>
                </div>
                <div class="nav-foot"><span>已用时 {{ elapsedLabel }}</span><span>客观题自动判分，主观题交卷后统一评估。</span></div>
              </aside>
            </div>
          </template>
          <el-empty v-else description="本组题目尚未就绪" />
        </template>
      </AsyncState>

    </div>
  </div>
</template>

<style scoped lang="scss">
.practice-page { min-height:100%; color:$ai-text-primary; }
.practice-top { position:sticky; top:0; z-index:10; }
.practice-content { width:min(1152px, calc(100% - 40px)); margin:36px auto; }
.practice-heading { margin-bottom:24px; }
h1,h2,h3,p { margin:0; overflow-wrap:anywhere; }
h1,h2,h3 { font-weight:700; }
h1 { font-size:32px; line-height:1.35; letter-spacing:-.025em; }
h2 { font-size:18px; }
h3 { font-size:14px; }
.practice-heading p,.muted { color:$ai-text-secondary; }
.practice-heading p { margin-top:5px; line-height:1.65; }
.session-grid { display:grid; align-items:start; grid-template-columns:minmax(0, 1.94fr) minmax(270px, 1fr); gap:24px; }
.practice-card,.result-question { display:grid; gap:16px; min-width:0; padding:22px; border:1px solid $ai-border; border-radius:16px; background:$ai-bg-card; }
.practice-row { display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:10px; }
.question-panel__footer { display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:12px; padding-top:14px; border-top:1px solid $ai-border-light; }
.question-panel__footer .muted { font-size:12px; }
.footer-actions { display:flex; flex-wrap:wrap; gap:8px; }
.footer-actions :deep(.el-button) { margin-left:0; }
.session-nav { position:sticky; top:88px; }
.nav-count { font-size:26px; font-variant-numeric:tabular-nums; }
.question-numbers { display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); gap:8px; }
.nav-number { min-height:39px; border:1px solid $ai-border-light; border-radius:8px; background:$ai-bg-light; color:$ai-text-secondary; font:inherit; font-weight:600; cursor:pointer; }
.nav-number--answered { border-color:transparent; background:var(--ai-success-bg,#e5f6ee); color:var(--ai-success,#167953); }
.nav-number--current { border-color:$ai-primary; color:$ai-primary; background:$ai-primary-light; }
.nav-number:hover,.nav-number:focus-visible { border-color:$ai-primary; }
.nav-foot { display:grid; gap:8px; padding-top:12px; border-top:1px solid $ai-border-light; color:$ai-text-secondary; font-size:12px; line-height:1.55; }
.review-column,.review-list { display:grid; gap:16px; }
.review-list { margin-top:24px; }
.result-score { display:flex; align-items:baseline; gap:8px; color:$ai-text-secondary; }
.result-score strong { color:$ai-primary; font-size:52px; line-height:1.05; font-variant-numeric:tabular-nums; }
.result-metrics { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; padding-top:14px; border-top:1px solid $ai-border-light; }
.result-metrics div { display:grid; gap:5px; border:1px solid $ai-border-light; padding:12px; border-radius:10px; }
.result-metrics span { color:$ai-text-secondary; font-size:12px; }
.result-metrics strong { font-size:20px; }
.result-summary { color:$ai-text-secondary; line-height:1.7; }
.review-section { padding-block:12px 0; border-top:1px solid $ai-border-light; }
.review-section ul { padding-left:18px; margin-bottom:0; }
.review-section li,.review-section p { margin-top:6px; color:$ai-text-secondary; line-height:1.65; }
.result-question { scroll-margin-top:92px; }
.result-question__heading { display:flex; flex-wrap:wrap; align-items:center; gap:10px; }
.result-question__heading h4 { margin:0; }
.result-tag { padding:4px 8px; border-radius:6px; font-size:12px; }
.result-tag--pass { color:var(--ai-success,#167953); background:var(--ai-success-bg,#e5f6ee); }
.result-tag--fail { color:$ai-danger; background:var(--ai-danger-bg,#fef2f2); }
.result-tag--evaluated { color:$ai-primary; background:$ai-primary-light; }
.result-question__comment { padding:14px; border-radius:8px; background:$ai-bg-page; }
.evaluation-overview { display:flex; align-items:flex-start; gap:20px; margin:12px 0; }
.overall-score { display:grid; flex-shrink:0; gap:4px; }
.feedback-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px; }
.feedback-card { padding:12px; border:1px solid $ai-border-light; border-radius:8px; }
.feedback-card h5 { margin:0 0 5px; }
.feedback-card ul { margin:0; padding-left:18px; line-height:1.6; }
.evaluation-status { display:grid; gap:12px; }
@media(max-width:800px) { .session-grid { grid-template-columns:minmax(0,1fr); gap:16px; } .session-nav { position:static; } }
@media(max-width:520px) { .practice-content { width:calc(100% - 28px); margin:24px auto; } h1 { font-size:26px; } .practice-card,.result-question { padding:15px; } .feedback-grid { grid-template-columns:1fr; } }
</style>
