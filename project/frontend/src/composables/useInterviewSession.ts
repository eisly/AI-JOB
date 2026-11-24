import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { interviewApi } from '@/api/interviews'
import { voiceInterviewApi } from '@/api/voiceInterviews'
import { jobApi } from '@/api/jobs'
import { resumeApi } from '@/api/resumes'
import { ApiError } from '@/api/request'
import { canRetryTrackedTask, retryTrackedTask } from '@/api/task-registry'
import type { InterviewReportVO, InterviewVO, JobVO, ResumeVO, TaskVO, VoiceCapabilityVO } from '@/api/types'
import { isTerminalTask, useTask } from '@/composables/useTask'

export interface AnswerAttempt {
  taskId: string
  questionMessageId: string
  content: string
  expectedVersion: number
  answerSource: 'TEXT' | 'VOICE'
  voiceTranscriptionId?: string
}

export interface InterviewSessionOptions {
  /** 当前页面路径，用于同步 interviewId / report 查询参数。 */
  basePath: string
}

function queryValue(value: unknown) {
  return typeof value === 'string' ? value : ''
}

/**
 * 文字面试与语音面试共用的会话业务流程。
 *
 * 负责选项、历史、会话、任务、提交、结束与报告的加载和状态机；页面只决定
 * 当前使用哪种作答界面，以及按会话类型跳转到哪个页面。
 */
export function useInterviewSession(options: InterviewSessionOptions) {
  const route = useRoute()
  const router = useRouter()
  const jobs = ref<JobVO[]>([])
  const resumes = ref<ResumeVO[]>([])
  const optionsLoading = ref(false)
  const optionsError = ref<unknown>(null)
  const creating = ref(false)
  const history = ref<InterviewVO[]>([])
  const historyPage = ref(1)
  const historySize = ref(10)
  const historyTotal = ref(0)
  const historyLoading = ref(false)
  const historyError = ref<unknown>(null)
  const selectedInterviewId = ref('')
  const currentInterview = ref<InterviewVO | null>(null)
  const interviewLoading = ref(false)
  const interviewError = ref<unknown>(null)
  const report = ref<InterviewReportVO | null>(null)
  const reportLoading = ref(false)
  const reportError = ref<unknown>(null)
  const activeTaskId = ref('')
  const taskSnapshot = ref<TaskVO | null>(null)
  const answerContent = ref('')
  const answerAttempt = ref<AnswerAttempt | null>(null)
  const submitting = ref(false)
  const ending = ref(false)
  const retrying = ref(false)
  const actionError = ref<unknown>(null)
  const voiceCapability = ref<VoiceCapabilityVO | null>(null)
  const requestedLimit = Number(queryValue(route.query.questionLimit))
  const createForm = ref({
    jobId: queryValue(route.query.jobId),
    resumeVersionId: queryValue(route.query.resumeVersionId),
    questionLimit: Number.isFinite(requestedLimit) && requestedLimit >= 3 && requestedLimit <= 10 ? requestedLimit : 5,
  })
  let interviewRequestSeq = 0
  let reportRequestSeq = 0
  let voiceCapabilitySeq = 0

  const voiceAvailable = computed(() => {
    if (!voiceCapability.value?.asrEnabled) return false
    if (typeof MediaRecorder === 'undefined' || !navigator.mediaDevices?.getUserMedia) return false
    return typeof window === 'undefined' || window.isSecureContext || ['localhost', '127.0.0.1'].includes(window.location.hostname)
  })

  const tracker = useTask(activeTaskId, {
    onDone: handleTaskDone,
    onTerminal: handleTaskTerminal,
  })

  const activeTask = computed(() => {
    if (tracker.task.value?.id === activeTaskId.value) return tracker.task.value
    return taskSnapshot.value?.id === activeTaskId.value ? taskSnapshot.value : null
  })
  const pendingDeltaText = computed(() => {
    if (!activeTaskId.value || tracker.task.value?.id !== activeTaskId.value) return ''
    if (isTerminalTask(tracker.task.value.status)) return ''
    return tracker.deltaText.value
  })
  const activeReasoning = computed(() => {
    if (!activeTaskId.value || tracker.task.value?.id !== activeTaskId.value) return ''
    return tracker.reasoningText.value
  })
  const sortedMessages = computed(() => [...(currentInterview.value?.messages || [])].sort((a, b) => a.seq - b.seq))
  const currentQuestion = computed(() => {
    const messages = sortedMessages.value
    for (let index = messages.length - 1; index >= 0; index -= 1) {
      if (messages[index].role === 'ASSISTANT') return messages[index]
    }
    return null
  })
  const canAnswer = computed(() => {
    const contentLength = answerContent.value.trim().length
    return currentInterview.value?.status === 'WAITING_ANSWER'
      && !!currentQuestion.value
      && contentLength > 0
      && answerContent.value.length <= 10000
      && !submitting.value
      && (!activeTask.value || isTerminalTask(activeTask.value.status))
  })

  function taskInterviewId(task: TaskVO) {
    return task.result?.resourceId || task.resourceId || selectedInterviewId.value
  }

  function queryFor(interviewId: string, showReport = false) {
    return {
      interviewId,
      report: showReport ? '1' : undefined,
    }
  }

  async function loadOptions() {
    optionsLoading.value = true
    optionsError.value = null
    try {
      const [jobPage, personalJobPage, resumePage] = await Promise.all([
        jobApi.list({ current: 1, size: 100 }),
        jobApi.imports({ current: 1, size: 100 }),
        resumeApi.list({ current: 1, size: 100 }),
      ])
      const jobMap = new Map<string, JobVO>()
      ;[...jobPage.records, ...personalJobPage.records].forEach(job => jobMap.set(job.id, job))
      jobs.value = [...jobMap.values()].filter((job) => job.status === 'ACTIVE' && !!job.requirements)
      resumes.value = resumePage.records.filter((resume) => resume.latestVersion.status === 'CONFIRMED')
      const preferredJobId = queryValue(route.query.jobId) || createForm.value.jobId
      if (preferredJobId && !jobs.value.some((job) => job.id === preferredJobId)) {
        try {
          const preferredJob = await jobApi.get(preferredJobId)
          if (preferredJob.status === 'ACTIVE' && preferredJob.requirements) jobs.value.unshift(preferredJob)
        } catch {
          ElMessage.warning('链接中的岗位暂不可用于面试，请重新选择')
        }
      }
      createForm.value.jobId = jobs.value.some((job) => job.id === preferredJobId) ? preferredJobId : jobs.value[0]?.id || ''
      const preferredResumeId = queryValue(route.query.resumeVersionId) || createForm.value.resumeVersionId
      createForm.value.resumeVersionId = resumes.value.some((resume) => resume.latestVersion.id === preferredResumeId)
        ? preferredResumeId
        : resumes.value[0]?.latestVersion.id || ''
    } catch (reason) {
      optionsError.value = reason
    } finally {
      optionsLoading.value = false
    }
  }

  async function loadHistory(showLoading = true) {
    if (showLoading) historyLoading.value = true
    if (showLoading) historyError.value = null
    try {
      const page = await interviewApi.list({ current: historyPage.value, size: historySize.value })
      history.value = page.records
      historyTotal.value = page.total
      historyPage.value = page.current
      historySize.value = page.size
      historyError.value = null
    } catch (reason) {
      if (showLoading) historyError.value = reason
    } finally {
      if (showLoading) historyLoading.value = false
    }
  }

  function handleHistoryPage(page: number) {
    historyPage.value = page
    void loadHistory()
  }

  function handleHistorySize(size: number) {
    historySize.value = size
    historyPage.value = 1
    void loadHistory()
  }

  async function loadReport(interviewId: string) {
    if (!interviewId) return
    const run = ++reportRequestSeq
    reportLoading.value = true
    reportError.value = null
    try {
      const value = await interviewApi.report(interviewId)
      if (run !== reportRequestSeq || selectedInterviewId.value !== interviewId) return
      report.value = value
    } catch (reason) {
      if (run !== reportRequestSeq) return
      reportError.value = reason
      if (reason instanceof ApiError && reason.key === 'REPORT_NOT_READY') {
        const taskId = reason.details?.taskId
        if (typeof taskId === 'string') activeTaskId.value = taskId
      }
    } finally {
      if (run === reportRequestSeq) reportLoading.value = false
    }
  }

  async function loadCapability(interviewId: string) {
    const run = ++voiceCapabilitySeq
    voiceCapability.value = null
    if (!interviewId) return
    try {
      const value = await voiceInterviewApi.capability(interviewId)
      if (run === voiceCapabilitySeq) voiceCapability.value = value
    } catch {
      if (run === voiceCapabilitySeq) voiceCapability.value = null
    }
  }

  async function loadInterview(interviewId: string, showReport = false) {
    if (!interviewId) return null
    const run = ++interviewRequestSeq
    if (selectedInterviewId.value !== interviewId) {
      selectedInterviewId.value = interviewId
      currentInterview.value = null
      report.value = null
      reportError.value = null
      answerContent.value = ''
      answerAttempt.value = null
      activeTaskId.value = ''
      taskSnapshot.value = null
    }
    interviewLoading.value = true
    interviewError.value = null
    actionError.value = null
    try {
      const value = await interviewApi.get(interviewId)
      if (run !== interviewRequestSeq) return null
      currentInterview.value = value
      selectedInterviewId.value = value.id
      activeTaskId.value = value.pendingTaskId || ''
      taskSnapshot.value = null
      void loadCapability(value.id)
      const historyIndex = history.value.findIndex((item) => item.id === value.id)
      if (historyIndex >= 0) history.value[historyIndex] = { ...history.value[historyIndex], ...value, messages: history.value[historyIndex].messages }
      if (value.status === 'COMPLETED') await loadReport(value.id)
      else if (!showReport) reportError.value = null
      await loadHistory(false)
      return value
    } catch (reason) {
      if (run === interviewRequestSeq) interviewError.value = reason
      return null
    } finally {
      if (run === interviewRequestSeq) interviewLoading.value = false
    }
  }

  async function openInterview(interviewId: string, showReport = false) {
    const value = await loadInterview(interviewId, showReport)
    if (!value) return
    await router.replace({ path: options.basePath, query: queryFor(interviewId, showReport) })
  }

  async function handleTaskDone(task: TaskVO) {
    const interviewId = taskInterviewId(task)
    if (!interviewId) return
    const isFirstQuestion = !currentInterview.value || currentInterview.value.id !== interviewId
    const value = await loadInterview(interviewId, task.type === 'INTERVIEW_REPORT')
    if (!value) return
    if (task.type === 'INTERVIEW_TURN') {
      answerContent.value = ''
      answerAttempt.value = null
      ElMessage.success(isFirstQuestion ? '首题已生成，可以开始回答' : '回答已保存，面试状态已更新')
    } else if (task.type === 'INTERVIEW_REPORT' && value.status === 'COMPLETED') {
      ElMessage.success('面试报告已生成')
    }
  }

  async function handleTaskTerminal(task: TaskVO) {
    if (task.status === 'SUCCEEDED') return
    if (task.status === 'FAILED' && task.type === 'INTERVIEW_TURN' && answerAttempt.value) {
      answerContent.value = answerAttempt.value.content
    }
    const interviewId = taskInterviewId(task)
    if (interviewId) await loadInterview(interviewId, task.type === 'INTERVIEW_REPORT')
  }

  async function createInterview(mode: 'TEXT' | 'VOICE' = 'TEXT') {
    if (!createForm.value.jobId || !createForm.value.resumeVersionId) {
      ElMessage.warning('请选择已分析岗位和已确认简历')
      return null
    }
    if (createForm.value.questionLimit < 3 || createForm.value.questionLimit > 10) {
      ElMessage.warning('主问题数量须为 3 到 10')
      return null
    }
    creating.value = true
    actionError.value = null
    try {
      const planItemId = queryValue(route.query.planItemId)
      const task = await interviewApi.create({
        jobId: createForm.value.jobId,
        resumeVersionId: createForm.value.resumeVersionId,
        questionLimit: createForm.value.questionLimit,
        mode,
        ...(planItemId ? { planItemId } : {}),
      })
      taskSnapshot.value = task
      activeTaskId.value = task.id
      currentInterview.value = null
      report.value = null
      reportError.value = null
      answerAttempt.value = null
      answerContent.value = ''
      selectedInterviewId.value = task.resourceId || ''
      if (task.resourceId) {
        await router.replace({ path: options.basePath, query: queryFor(task.resourceId) })
      }
      ElMessage.success('面试已创建，正在生成首题')
      return task
    } catch (reason) {
      actionError.value = reason
      return null
    } finally {
      creating.value = false
    }
  }

  async function submitAnswer(content: string, answerSource: 'TEXT' | 'VOICE' = 'TEXT', voiceTranscriptionId = '') {
    const interview = currentInterview.value
    const question = currentQuestion.value
    if (!interview || !question || interview.status !== 'WAITING_ANSWER') {
      ElMessage.warning('当前没有可回答的问题')
      return false
    }
    if (!content.trim() || content.length > 10000) {
      ElMessage.warning('回答长度须为 1 到 10000 个字符')
      return false
    }
    submitting.value = true
    actionError.value = null
    const attempt: AnswerAttempt = {
      taskId: '',
      questionMessageId: question.id,
      content,
      expectedVersion: interview.version,
      answerSource,
      ...(answerSource === 'VOICE' ? { voiceTranscriptionId } : {}),
    }
    answerAttempt.value = attempt
    try {
      const task = await interviewApi.answer(interview.id, {
        questionMessageId: question.id,
        content,
        expectedVersion: interview.version,
        ...(answerSource === 'VOICE' ? { answerSource, voiceTranscriptionId } : {}),
      })
      answerAttempt.value = { ...attempt, taskId: task.id }
      taskSnapshot.value = task
      activeTaskId.value = task.id
      ElMessage.success('回答已提交，正在生成下一轮')
      return true
    } catch (reason) {
      answerContent.value = content
      if (reason instanceof ApiError && reason.status === 409) {
        await loadInterview(interview.id)
        ElMessage.warning('面试版本或状态已变化，已刷新最新内容，请确认后再次提交')
      } else {
        actionError.value = reason
      }
      return false
    } finally {
      submitting.value = false
    }
  }

  async function sendAnswer() {
    return submitAnswer(answerContent.value)
  }

  async function handleVoiceSubmit(payload: { content: string; answerSource: 'TEXT' | 'VOICE'; transcriptionId: string }) {
    answerContent.value = payload.content
    return submitAnswer(payload.content, payload.answerSource, payload.transcriptionId)
  }

  function handleVoiceSwitchText() {
    ElMessage.info('已切换为文字作答，已识别的文本会保留在当前编辑区')
  }

  async function retryAnswerWithSameContent() {
    if (!answerAttempt.value) return
    answerContent.value = answerAttempt.value.content
    const attempt = answerAttempt.value
    await submitAnswer(attempt.content, attempt.answerSource, attempt.voiceTranscriptionId)
  }

  async function endInterview(reevaluate = false) {
    const interview = currentInterview.value
    if (!interview || ending.value || (activeTask.value && !isTerminalTask(activeTask.value.status))) return false
    const expectedStatus = reevaluate ? 'COMPLETED' : 'WAITING_ANSWER'
    if (interview.status !== expectedStatus || (reevaluate && !report.value?.canReevaluate)) return false
    if (!reevaluate) {
      try {
        await ElMessageBox.confirm('结束后将生成面试报告，不能继续回答。确认结束本次面试吗？', '结束面试', {
          confirmButtonText: '确认结束',
          cancelButtonText: '继续面试',
          type: 'warning',
        })
      } catch {
        return false
      }
    }
    ending.value = true
    actionError.value = null
    try {
      const task = await interviewApi.end(interview.id, interview.version, reevaluate, options.basePath)
      taskSnapshot.value = task
      activeTaskId.value = task.id
      if (!reevaluate) report.value = null
      reportError.value = null
      await router.replace({ path: options.basePath, query: queryFor(interview.id, true) })
      ElMessage.success(reevaluate ? '正在重新评估历史回答' : '已结束面试，正在生成报告')
      return true
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 409) {
        await loadInterview(interview.id, true)
        ElMessage.warning('面试版本或状态已变化，已刷新最新状态，请确认后再操作')
      } else {
        actionError.value = reason
      }
      return false
    } finally {
      ending.value = false
    }
  }

  async function retryActiveTask() {
    const task = activeTask.value
    if (!task || !canRetryTrackedTask(task)) return
    retrying.value = true
    actionError.value = null
    try {
      if (task.type === 'INTERVIEW_REPORT') {
        const interviewId = taskInterviewId(task)
        const refreshed = await loadInterview(interviewId, true)
        if (!refreshed) throw new Error('无法刷新面试状态，请稍后重试')
        if (refreshed.status === 'COMPLETED' && !report.value?.canReevaluate) {
          ElMessage.success('报告已经生成，无需重试')
          return
        }
      }
      const retried = await retryTrackedTask(task)
      taskSnapshot.value = retried
      activeTaskId.value = retried.id
      if (retried.type === 'INTERVIEW_TURN' && answerAttempt.value) {
        answerAttempt.value = { ...answerAttempt.value, taskId: retried.id }
      }
      if (retried.type === 'INTERVIEW_REPORT') {
        reportError.value = null
      }
      ElMessage.success('已按原提交参数创建重试任务')
      await loadHistory(false)
    } catch (reason) {
      actionError.value = reason
    } finally {
      retrying.value = false
    }
  }

  async function refreshCurrent() {
    if (!selectedInterviewId.value) return
    await loadInterview(selectedInterviewId.value, queryValue(route.query.report) === '1')
  }

  async function startNew() {
    interviewRequestSeq += 1
    reportRequestSeq += 1
    selectedInterviewId.value = ''
    currentInterview.value = null
    report.value = null
    reportError.value = null
    interviewError.value = null
    actionError.value = null
    answerContent.value = ''
    answerAttempt.value = null
    activeTaskId.value = ''
    taskSnapshot.value = null
    await router.replace({
      path: options.basePath,
      query: {
        ...route.query,
        interviewId: undefined,
        report: undefined,
      },
    })
    await loadOptions()
    if (!optionsError.value && (!jobs.value.length || !resumes.value.length)) {
      ElMessage.warning('请先准备面试所需的岗位和简历')
    }
  }

  async function initialize() {
    const interviewId = queryValue(route.query.interviewId)
    await Promise.all([
      loadOptions(),
      loadHistory(),
      interviewId ? loadInterview(interviewId, queryValue(route.query.report) === '1') : Promise.resolve(),
    ])
  }

  watch(
    () => [queryValue(route.query.interviewId), queryValue(route.query.report)],
    ([interviewId, reportFlag]) => {
      if (!interviewId) return
      if (interviewId !== selectedInterviewId.value) {
        void loadInterview(interviewId, reportFlag === '1')
      } else if (reportFlag === '1' && currentInterview.value?.status === 'COMPLETED' && !report.value && !reportLoading.value) {
        void loadReport(interviewId)
      }
    },
  )

  return {
    jobs,
    resumes,
    optionsLoading,
    optionsError,
    creating,
    history,
    historyPage,
    historySize,
    historyTotal,
    historyLoading,
    historyError,
    selectedInterviewId,
    currentInterview,
    interviewLoading,
    interviewError,
    report,
    reportLoading,
    reportError,
    activeTaskId,
    taskSnapshot,
    answerContent,
    answerAttempt,
    submitting,
    ending,
    retrying,
    actionError,
    voiceCapability,
    voiceAvailable,
    createForm,
    tracker,
    activeTask,
    pendingDeltaText,
    activeReasoning,
    sortedMessages,
    currentQuestion,
    canAnswer,
    queryFor,
    loadOptions,
    loadHistory,
    handleHistoryPage,
    handleHistorySize,
    loadReport,
    loadCapability,
    loadInterview,
    openInterview,
    createInterview,
    submitAnswer,
    sendAnswer,
    handleVoiceSubmit,
    handleVoiceSwitchText,
    retryAnswerWithSameContent,
    endInterview,
    retryActiveTask,
    refreshCurrent,
    startNew,
    initialize,
  }
}
