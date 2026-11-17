<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, ArrowRight, Finished, Refresh } from '@element-plus/icons-vue'
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
import EvaluationLoadingDialog from '@/components/training/EvaluationLoadingDialog.vue'
import QuestionRenderer from '@/components/training/QuestionRenderer.vue'
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
    ensureStartAt()
    loadAnswers()
    if (!currentQuestionId.value && value.questions.length) currentQuestionId.value = value.questions[0]!.id
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
const evaluationVisible = ref(false)
const evaluationStage = ref(0)
const evaluationError = ref<string | null>(null)
const evaluationTaskId = ref<string | null>(null)
const lastSubmitVersion = ref(0)
let stageTimer: number | undefined

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
  persistDraft()
  submitting.value = true
  evaluationError.value = null
  evaluationStage.value = 0
  evaluationVisible.value = true
  startStageSimulation()
  lastSubmitVersion.value = set.version
  try {
    if (isLegacyMode.value) {
      const question = questions.value[0]!
      const content = answers.value[question.id]?.content || ''
      if (!content.trim()) {
        evaluationVisible.value = false
        stopStageSimulation()
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
    evaluationVisible.value = false
    stopStageSimulation()
    ElMessage.error(errorMessage(reason))
  } finally {
    submitting.value = false
  }
}

/** 阶段提示：后端任务未提供细粒度进度时，按时间推进展示，任务完成后立即跳到最终阶段 */
function startStageSimulation() {
  stopStageSimulation()
  const submittedAt = Date.now()
  stageTimer = window.setInterval(() => {
    const elapsed = Date.now() - submittedAt
    evaluationStage.value = elapsed < 8000 ? 0 : elapsed < 22000 ? 1 : 2
  }, 1000)
}

function stopStageSimulation() {
  window.clearInterval(stageTimer)
  stageTimer = undefined
}

async function handleEvaluationDone(_task: TaskVO) {
  stopStageSimulation()
  evaluationStage.value = 2
  try {
    await loadResult()
    evaluationVisible.value = false
    clearDraft()
    try {
      localStorage.removeItem(startKey())
    } catch {
      return
    }
    ElMessage.success('评估完成，已生成本组训练反馈报告')
  } catch (reason) {
    evaluationError.value = errorMessage(reason)
  }
}

async function loadResult() {
  resultLoading.value = true
  resultError.value = null
  try {
    if (isLegacyMode.value) {
      const value = await learningApi.getSession(setId.value)
      const answerId = value.lastAnswerId
      if (!answerId) throw new Error('暂未获取到回答ID，请稍后刷新')
      const answer = await learningApi.getAnswer(answerId)
      result.value = adaptLegacyAnswerToResult(answer)
    } else {
      result.value = await practiceApi.getResult(setId.value)
    }
    window.clearTimeout(stageTimer)
  } catch (reason) {
    resultError.value = reason
  } finally {
    resultLoading.value = false
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
  evaluationError.value = null
  if (evaluationTask.value && canRetryTrackedTask(evaluationTask.value)) {
    try {
      evaluationTaskId.value = (await retryTrackedTask(evaluationTask.value)).id
      startStageSimulation()
      return
    } catch (reason) {
      evaluationError.value = errorMessage(reason)
      return
    }
  }
  submitted.value = false
  await submitPaper()
}

function closeEvaluationDialog() {
  if (!evaluationError.value) return
  evaluationVisible.value = false
}

// ===== 结果展示辅助 =====
const resultMode = computed(() => !!result.value)

function questionResultOf(questionId: string) {
  return result.value?.questionResults.find(item => item.questionId === questionId) || null
}

function navStateOf(question: PracticeQuestionVO): 'answered' | 'current' | 'correct' | 'wrong' | 'pending' {
  const questionResult = questionResultOf(question.id)
  if (resultMode.value && questionResult) {
    if (questionResult.correct === true) return 'correct'
    if (questionResult.correct === false) return 'wrong'
    return 'answered'
  }
  if (question.id === currentQuestionId.value) return 'current'
  return isAnswered(question) ? 'answered' : 'pending'
}

const objectiveSummary = computed(() => {
  if (!result.value || !result.value.objectiveTotalCount) return null
  return `${result.value.objectiveCorrectCount} / ${result.value.objectiveTotalCount}`
})

function leaveSession() {
  void router.push('/training')
}

async function exitPractice() {
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
  submitted.value = false
  answers.value = {}
  void loadSet()
})
</script>

<template>
  <div class="practice-page">
    <header class="practice-header">
      <div class="practice-header__heading">
        <el-button link :icon="ArrowLeft" class="practice-back" :disabled="submitting" @click="exitPractice">返回专项训练</el-button>
        <div class="practice-header__title">
          <h1>{{ practiceSet?.title || '专项训练题目' }}</h1>
          <p class="practice-header__sub">专项训练 / 题目 · {{ questions.length ? `本组共 ${questions.length} 题` : '题目加载中' }}</p>
        </div>
      </div>
      <div class="practice-header__actions">
        <span class="session-header__timer" aria-label="已用时">
          <span class="timer-label">已用时</span>
          <strong>{{ elapsedLabel }}</strong>
        </span>
        <span class="session-header__save">
          <span class="save-dot" :class="{ 'save-dot--warn': draftSaveFailed }" />
          {{ draftSaveFailed ? '草稿保存失败' : draftSavedAt ? '已自动保存' : '自动保存中' }}
        </span>
        <el-button
          v-if="!resultMode"
          type="primary"
          :icon="Finished"
          :loading="submitting"
          :disabled="submitted && !evaluationError"
          @click="submitPaper"
        >交卷并AI评估</el-button>
      </div>
    </header>

    <main class="session-body">
      <!-- 左侧：题号导航 -->
      <aside class="session-nav">
        <h3>答题导航</h3>
        <p class="session-nav__sub">
          本组共 {{ questions.length }} 题 · 已答 {{ answeredCount }} 题
        </p>
        <div class="session-nav__progress">
          <span>答题进度</span>
          <strong>{{ answeredPercentage }}%</strong>
        </div>
        <el-progress :percentage="answeredPercentage" :stroke-width="6" :show-text="false" />
        <div v-for="group in typeGroups" :key="group.type" class="session-nav__group">
          <h4>{{ group.label }} <span class="muted">{{ group.items.length }} 题</span></h4>
          <div class="session-nav__numbers">
            <button
              v-for="question in group.items"
              :key="question.id"
              type="button"
              class="nav-number"
              :class="`nav-number--${navStateOf(question)}`"
              :aria-label="`第 ${question.index} 题`"
              @click="goQuestion(question.id)"
            >{{ question.index }}</button>
          </div>
        </div>
        <p class="session-nav__legend">
          <span class="legend-dot legend-dot--answered" /> 蓝色为当前题，绿色为已答
        </p>
      </aside>

      <!-- 中间：题目区 / 结果区 -->
      <section class="session-main">
        <AsyncState :loading="loadLoading" :error="loadError" :empty="!practiceSet" empty-text="未找到本组训练" @retry="loadSet">
          <template v-if="practiceSet">
            <el-alert
              v-if="practiceSet.status === 'GENERATING'"
              title="题目仍在预生成中，请稍后刷新"
              type="warning"
              :closable="false"
              show-icon
            />
            <el-alert
              v-else-if="practiceSet.status === 'FAILED'"
              title="本组题目生成失败，请返回训练主页重新生成"
              type="error"
              :closable="false"
              show-icon
            />

            <!-- 结果模式 -->
            <template v-else-if="resultMode">
              <section class="result-panel">
                <div class="result-panel__overview">
                  <div class="result-score">
                    <strong>{{ result?.overallScore === null || result?.overallScore === undefined ? '-' : result.overallScore }}</strong>
                    <span>综合得分</span>
                  </div>
                  <div v-if="objectiveSummary" class="result-objective">
                    客观题正确 <strong>{{ objectiveSummary }}</strong>
                  </div>
                  <el-button size="small" :icon="Refresh" :loading="resultLoading" @click="loadResult">刷新报告</el-button>
                </div>
                <el-alert v-if="resultError" :title="errorMessage(resultError)" type="error" :closable="false" show-icon />
                <p v-if="result?.summary" class="result-panel__summary">{{ result.summary }}</p>
                <div class="result-panel__feedback">
                  <section class="feedback-card feedback-card--strength">
                    <h4>本次训练优点</h4>
                    <ul v-if="result?.strengths.length"><li v-for="(item, index) in result.strengths" :key="index">{{ item }}</li></ul>
                    <p v-else class="muted">暂无优点反馈</p>
                  </section>
                  <section class="feedback-card feedback-card--improvement">
                    <h4>改进建议</h4>
                    <ul v-if="result?.improvements.length"><li v-for="(item, index) in result.improvements" :key="index">{{ item }}</li></ul>
                    <p v-else class="muted">暂无改进建议</p>
                  </section>
                </div>
              </section>

              <section
                v-for="question in questions"
                :key="question.id"
                class="result-question"
              >
                <header class="result-question__heading">
                  <h4>第 {{ question.index }} 题 · {{ ({ CHOICE: '选择题', FILL: '填空题', SUBJECTIVE: '主观题' } as Record<string, string>)[question.type] }}</h4>
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
            </template>

            <!-- 答题模式 -->
            <template v-else-if="currentQuestion">
              <div class="question-panel">
                <div class="question-panel__heading">
                  <span class="question-panel__position">第 {{ currentQuestion.index }} / {{ questions.length }} 题</span>
                  <span class="muted">建议先完成客观题，再集中作答主观题</span>
                </div>
                <QuestionRenderer
                  :question="currentQuestion"
                  :answer="currentAnswer"
                  @update:answer="value => { answers[value.questionId] = value }"
                />
                <footer class="question-panel__footer">
                  <span class="muted footer-note">你的答案会自动保存</span>
                  <div class="footer-actions">
                    <el-button :icon="ArrowLeft" :disabled="currentIndex === 0 || submitting" @click="goPrev">上一题</el-button>
                    <el-button type="primary" :disabled="currentIndex >= questions.length - 1 || submitting" @click="goNext">
                      下一题 <el-icon><ArrowRight /></el-icon>
                    </el-button>
                  </div>
                </footer>
              </div>
            </template>
            <el-empty v-else description="本组题目尚未就绪" />
          </template>
        </AsyncState>
      </section>

      <!-- 右侧：本次训练概览 -->
      <aside class="session-side">
        <h3>本次训练</h3>
        <p class="session-side__sub">
          {{ practiceSet?.title || '专项训练' }}
        </p>
        <dl class="session-side__stats">
          <div><dt>题目总数</dt><dd>{{ questions.length }} 题</dd></div>
          <div><dt>已答 / 未答</dt><dd>{{ answeredCount }} / {{ unansweredCount }}</dd></div>
          <div v-if="practiceSet?.config"><dt>题型分布</dt><dd>选择 {{ practiceSet.config.typeDistribution.choice }} · 填空 {{ practiceSet.config.typeDistribution.fill }} · 主观 {{ practiceSet.config.typeDistribution.subjective }}</dd></div>
          <div v-if="practiceSet?.config"><dt>难度</dt><dd>{{ ({ EASY: '入门', MEDIUM: '进阶', HARD: '挑战', MIXED: '混合' } as Record<string, string>)[practiceSet.config.difficulty] || practiceSet.config.difficulty }}</dd></div>
          <div><dt>预计时长</dt><dd>{{ practiceSet?.totalMinutes || '-' }} 分钟</dd></div>
        </dl>
        <div class="session-side__tip">
          <el-icon><Finished /></el-icon>
          <span>完成全部作答后点击右上角「交卷并AI评估」，客观题自动判分，主观题由 AI 深度评估并生成训练反馈报告。</span>
        </div>
      </aside>
    </main>

    <EvaluationLoadingDialog
      :visible="evaluationVisible"
      :stage="evaluationStage"
      :has-objective="typeGroups.some(group => group.type !== 'SUBJECTIVE')"
      :error="evaluationError"
      @close="closeEvaluationDialog"
    />

    <div v-if="evaluationError" class="evaluation-retry">
      <span>{{ evaluationError }}</span>
      <el-button size="small" type="primary" :loading="submitting" @click="retryEvaluation">重试评估</el-button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.practice-page {
  display: flex;
  flex-direction: column;
  min-height: 100%;
  padding-bottom: 8px;
}

.practice-header {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
  padding: 14px 16px;
  border: 1px solid $ai-border-light;
  border-radius: $ai-radius-lg;
  background: #fff;
  box-shadow: $ai-shadow-card;
}

.practice-header__heading { display: flex; align-items: center; gap: 14px; min-width: 0; }
.practice-back { flex-shrink: 0; padding: 0; }
.practice-header__title { min-width: 0; }
.practice-header__title h1 { margin: 0; color: $ai-text-primary; font-size: 18px; line-height: 1.4; }
.practice-header__sub { margin: 2px 0 0; color: $ai-text-tertiary; font-size: 12px; }

.practice-header__actions { display: flex; align-items: center; gap: 14px; flex-shrink: 0; }
.session-header__timer { display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px; border-radius: 6px; background: $ai-bg-light; font-size: 13px; }
.timer-label { color: $ai-text-secondary; }
.session-header__save { display: inline-flex; align-items: center; gap: 6px; color: $ai-text-secondary; font-size: 12px; white-space: nowrap; }
.save-dot { width: 7px; height: 7px; border-radius: 50%; background: $ai-success; }
.save-dot--warn { background: $ai-warning; }

.session-body {
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr) 250px;
  align-items: start;
  gap: 14px;
  width: 100%;
  max-width: 1400px;
  margin: 0 auto;
  padding: 0 0 20px;
  box-sizing: border-box;
}

.session-nav, .session-side {
  position: sticky;
  top: 96px;
  padding: 16px;
  border: 1px solid $ai-border-light;
  border-radius: $ai-radius-lg;
  background: #fff;
  box-shadow: $ai-shadow-card;
}

.session-nav h3, .session-side h3 { margin: 0 0 4px; font-size: 15px; color: $ai-text-primary; }
.session-nav__sub, .session-side__sub { margin: 0 0 12px; color: $ai-text-secondary; font-size: 12px; }
.session-nav__progress { display: flex; align-items: center; justify-content: space-between; margin: 10px 0 6px; font-size: 12px; color: $ai-text-secondary; }
.session-nav__progress strong { color: $ai-primary; }
.session-nav__group { margin-top: 14px; }
.session-nav__group h4 { display: flex; align-items: center; gap: 6px; margin: 0 0 8px; font-size: 13px; color: $ai-text-primary; }
.muted { color: $ai-text-tertiary; font-weight: 400; }
.session-nav__numbers { display: flex; flex-wrap: wrap; gap: 8px; }
.nav-number {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: 1px solid $ai-border;
  border-radius: 8px;
  background: #fff;
  color: $ai-text-secondary;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;

  &:hover { border-color: $ai-primary; }
}
.nav-number--current { border-color: $ai-primary; color: #fff; background: $ai-primary; }
.nav-number--answered { border-color: #a7f3d0; color: #047857; background: #ecfdf5; }
.nav-number--answered:hover { border-color: $ai-success; }
.nav-number--correct { border-color: $ai-success; color: #fff; background: $ai-success; }
.nav-number--wrong { border-color: $ai-danger; color: #fff; background: $ai-danger; }
.session-nav__legend { display: flex; align-items: center; gap: 6px; margin: 16px 0 0; color: $ai-text-tertiary; font-size: 11px; }
.legend-dot { width: 8px; height: 8px; border-radius: 50%; background: #ecfdf5; border: 1px solid #a7f3d0; }
.legend-dot--answered { background: $ai-primary-light; border-color: $ai-primary; }

.session-main { min-width: 0; }
.question-panel, .result-panel, .result-question {
  padding: 20px 22px;
  border: 1px solid $ai-border-light;
  border-radius: $ai-radius-lg;
  background: #fff;
  box-shadow: $ai-shadow-card;
}
.question-panel__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 14px;
}
.question-panel__position { color: $ai-text-secondary; font-size: 13px; }
.question-panel__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid $ai-border-light;
}
.footer-note { font-size: 12px; }
.footer-actions { display: flex; gap: 10px; }

.result-panel { margin-bottom: 14px; }
.result-panel__overview { display: flex; align-items: center; gap: 24px; }
.result-score { display: grid; min-width: 92px; text-align: center; }
.result-score strong { color: $ai-primary; font-size: 34px; line-height: 1.2; }
.result-score span { color: $ai-text-secondary; font-size: 12px; }
.result-objective { color: $ai-text-secondary; font-size: 13px; }
.result-objective strong { color: $ai-success; }
.result-panel__summary { margin: 14px 0 0; color: $ai-text-primary; font-size: 14px; line-height: 1.7; }
.result-panel__feedback { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 14px; }
.feedback-card { padding: 14px; border-radius: $ai-radius; }
.feedback-card h4, .feedback-card h5 { margin: 0 0 8px; font-size: 14px; }
.feedback-card ul { display: grid; gap: 6px; margin: 0; padding-left: 18px; font-size: 13px; line-height: 1.6; }
.feedback-card--strength { background: #ecfdf5; }
.feedback-card--improvement { background: #fff7ed; }

.result-question { margin-bottom: 14px; }
.result-question__heading { display: flex; align-items: center; gap: 10px; margin: 0 0 12px; }
.result-question__heading h4 { margin: 0; font-size: 14px; }
.result-tag { padding: 2px 10px; border-radius: 999px; font-size: 12px; }
.result-tag--pass { color: $ai-success; background: #ecfdf5; }
.result-tag--fail { color: $ai-danger; background: #fef2f2; }
.result-tag--evaluated { color: $ai-primary; background: $ai-primary-light; }
.result-question__comment { margin-top: 14px; padding: 14px; border-radius: $ai-radius; background: $ai-bg-page; }
.result-question__comment p { margin: 0; color: $ai-text-primary; font-size: 13px; line-height: 1.7; }
.evaluation-overview { display: flex; align-items: flex-start; gap: 20px; margin: 12px 0; }
.evaluation-overview p { margin: 0; color: $ai-text-secondary; font-size: 13px; }
.feedback-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }

.session-side__stats { display: grid; gap: 10px; margin: 0 0 14px; }
.session-side__stats div { display: flex; align-items: center; justify-content: space-between; gap: 10px; font-size: 13px; }
.session-side__stats dt { color: $ai-text-secondary; }
.session-side__stats dd { margin: 0; color: $ai-text-primary; font-weight: 600; text-align: right; }
.session-side__tip {
  display: flex;
  gap: 8px;
  padding: 10px 12px;
  border-radius: $ai-radius;
  background: $ai-primary-light;
  color: #1e40af;
  font-size: 12px;
  line-height: 1.6;
}
.session-side__tip .el-icon { margin-top: 2px; color: $ai-primary; }

.evaluation-retry {
  position: fixed;
  right: 20px;
  bottom: 20px;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border: 1px solid #fecaca;
  border-radius: $ai-radius;
  background: #fff;
  color: $ai-danger;
  font-size: 13px;
  box-shadow: $ai-shadow-hover;
}

.pre-wrap { white-space: pre-wrap; }

@media (max-width: 1100px) {
  .session-body { grid-template-columns: 200px minmax(0, 1fr); }
  .session-side { display: none; }
}
@media (max-width: 760px) {
  .practice-header { flex-direction: column; align-items: stretch; }
  .practice-header__actions { flex-wrap: wrap; }
  .session-body { grid-template-columns: 1fr; }
  .session-nav { position: static; }
  .session-header__save { display: none; }
  .result-panel__feedback, .feedback-grid { grid-template-columns: 1fr; }
}
</style>
