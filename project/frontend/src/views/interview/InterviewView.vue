<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { interviewApi } from '@/api/interviews'
import { jobApi } from '@/api/jobs'
import { resumeApi } from '@/api/resumes'
import { ApiError } from '@/api/request'
import { canRetryTrackedTask, retryTrackedTask } from '@/api/task-registry'
import type { InterviewReportVO, InterviewVO, JobVO, ResumeVO, TaskVO } from '@/api/types'
import AsyncState from '@/components/AsyncState.vue'
import TaskStatus from '@/components/TaskStatus.vue'
import { isTerminalTask, useTask } from '@/composables/useTask'
import { commonStatusLabels, errorMessage, formatDate } from '@/utils/format'

interface AnswerAttempt {
  taskId: string
  questionMessageId: string
  content: string
  expectedVersion: number
}

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
const createForm = ref({
  jobId: typeof route.query.jobId === 'string' ? route.query.jobId : '',
  resumeVersionId: '',
  questionLimit: 5,
})
let interviewRequestSeq = 0
let reportRequestSeq = 0

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
const completionTypeLabels: Record<InterviewReportVO['completionType'], string> = {
  NORMAL: '正常完成',
  EARLY_END: '提前结束',
  NO_VALID_ANSWER: '无有效回答',
}
const dimensionLabels: Record<string, string> = {
  correctness: '正确性',
  completeness: '完整性',
  expression: '表达能力',
}
const evidenceStatusLabels: Record<string, string> = {
  VALID: '有效',
  INSUFFICIENT: '证据不足',
  INVALID: '无效',
  MISSING: '缺失',
  LIMITED: '有限',
  SUFFICIENT: '充分',
}

function queryValue(value: unknown) {
  return typeof value === 'string' ? value : ''
}

function taskInterviewId(task: TaskVO) {
  return task.result?.resourceId || task.resourceId || selectedInterviewId.value
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
    if (!resumes.value.some((resume) => resume.latestVersion.id === createForm.value.resumeVersionId)) {
      createForm.value.resumeVersionId = resumes.value[0]?.latestVersion.id || ''
    }
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
  await router.replace({
    query: {
      ...route.query,
      interviewId,
      report: showReport ? '1' : undefined,
    },
  })
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

async function createInterview() {
  if (!createForm.value.jobId || !createForm.value.resumeVersionId) {
    ElMessage.warning('请选择已分析岗位和已确认简历')
    return
  }
  if (createForm.value.questionLimit < 3 || createForm.value.questionLimit > 10) {
    ElMessage.warning('主问题数量须为 3 到 10')
    return
  }
  creating.value = true
  actionError.value = null
  try {
    const planItemId = queryValue(route.query.planItemId)
    const task = await interviewApi.create({
      jobId: createForm.value.jobId,
      resumeVersionId: createForm.value.resumeVersionId,
      questionLimit: createForm.value.questionLimit,
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
      await router.replace({ query: { ...route.query, interviewId: task.resourceId, report: undefined } })
    }
    ElMessage.success('面试已创建，正在生成首题')
  } catch (reason) {
    actionError.value = reason
  } finally {
    creating.value = false
  }
}

async function submitAnswer(content: string) {
  const interview = currentInterview.value
  const question = currentQuestion.value
  if (!interview || !question || interview.status !== 'WAITING_ANSWER') {
    ElMessage.warning('当前没有可回答的问题')
    return
  }
  if (!content.trim() || content.length > 10000) {
    ElMessage.warning('回答长度须为 1 到 10000 个字符')
    return
  }
  submitting.value = true
  actionError.value = null
  const attempt: AnswerAttempt = {
    taskId: '',
    questionMessageId: question.id,
    content,
    expectedVersion: interview.version,
  }
  answerAttempt.value = attempt
  try {
    const task = await interviewApi.answer(interview.id, {
      questionMessageId: question.id,
      content,
      expectedVersion: interview.version,
    })
    answerAttempt.value = { ...attempt, taskId: task.id }
    taskSnapshot.value = task
    activeTaskId.value = task.id
    ElMessage.success('回答已提交，正在生成下一轮')
  } catch (reason) {
    answerContent.value = content
    if (reason instanceof ApiError && reason.status === 409) {
      await loadInterview(interview.id)
      ElMessage.warning('面试版本或状态已变化，已刷新最新内容，请确认后再次提交')
    } else {
      actionError.value = reason
    }
  } finally {
    submitting.value = false
  }
}

async function sendAnswer() {
  await submitAnswer(answerContent.value)
}

async function retryAnswerWithSameContent() {
  if (!answerAttempt.value) return
  answerContent.value = answerAttempt.value.content
  await submitAnswer(answerAttempt.value.content)
}

async function endInterview() {
  const interview = currentInterview.value
  if (!interview || interview.status !== 'WAITING_ANSWER') return
  try {
    await ElMessageBox.confirm('结束后将生成面试报告，不能继续回答。确认结束本次面试吗？', '结束面试', {
      confirmButtonText: '确认结束',
      cancelButtonText: '继续面试',
      type: 'warning',
    })
  } catch {
    return
  }
  ending.value = true
  actionError.value = null
  try {
    const task = await interviewApi.end(interview.id, interview.version)
    taskSnapshot.value = task
    activeTaskId.value = task.id
    report.value = null
    reportError.value = null
    await router.replace({ query: { ...route.query, interviewId: interview.id, report: '1' } })
    ElMessage.success('已结束面试，正在生成报告')
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 409) {
      await loadInterview(interview.id, true)
      ElMessage.warning('面试版本或状态已变化，已刷新最新状态，请确认后再操作')
    } else {
      actionError.value = reason
    }
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
      if (refreshed.status === 'COMPLETED') {
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
      report.value = null
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

function nullableScore(value: number | null | undefined) {
  return value === null || value === undefined ? '暂无' : String(value)
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

onMounted(async () => {
  const interviewId = queryValue(route.query.interviewId)
  await Promise.all([
    loadOptions(),
    loadHistory(),
    interviewId ? loadInterview(interviewId, queryValue(route.query.report) === '1') : Promise.resolve(),
  ])
})
</script>

<template>
  <div class="page interview-page">
    <header class="page-header">
      <div>
        <h1>模拟面试</h1>
        <p>基于已分析岗位与已确认简历进行文字面试，回答和评价结果以服务端保存内容为准。</p>
      </div>
      <div class="page-header__actions">
        <el-button @click="refreshCurrent" :disabled="!selectedInterviewId || interviewLoading">刷新当前面试</el-button>
        <el-button type="primary" @click="startNew">新建文字面试</el-button>
      </div>
    </header>

    <el-alert class="text-only-alert" title="当前仅支持文字面试" description="请直接输入文字回答；流式题目仅作生成过程预览，最终消息以保存后的面试记录为准。" type="info" :closable="false" show-icon />

    <section v-if="activeTask" class="panel task-panel">
      <div class="panel__heading">
        <h2>当前异步任务</h2>
        <el-tag v-if="isTerminalTask(activeTask.status)" type="info" size="small">已结束</el-tag>
        <el-tag v-else :type="tracker.connected.value ? 'success' : 'info'" size="small">
          {{ tracker.connected.value ? '实时连接中' : '轮询恢复中' }}
        </el-tag>
      </div>
      <TaskStatus :task="activeTask" :reasoning="activeReasoning">
        <template #actions>
          <el-button v-if="canRetryTrackedTask(activeTask)" type="primary" :loading="retrying" @click="retryActiveTask">重试失败任务</el-button>
          <el-button v-if="activeTask.status === 'FAILED' && activeTask.type === 'INTERVIEW_TURN' && answerAttempt" :disabled="submitting || retrying" @click="retryAnswerWithSameContent">使用原内容重新提交</el-button>
        </template>
      </TaskStatus>
      <div v-if="pendingDeltaText" class="delta-preview">
        <strong>正在生成，尚未保存</strong>
        <div class="pre-wrap">{{ pendingDeltaText }}</div>
      </div>
      <el-alert v-if="tracker.error.value" class="task-connection-error" :title="errorMessage(tracker.error.value)" type="warning" :closable="false" show-icon>
        <el-button size="small" @click="tracker.restart">重新连接并刷新</el-button>
      </el-alert>
    </section>

    <el-alert v-if="actionError" class="action-error" :title="errorMessage(actionError)" type="error" show-icon @close="actionError = null" />

    <section v-if="!selectedInterviewId" class="panel">
      <div class="panel__heading">
        <h2>面试准备</h2>
        <el-button size="small" @click="loadOptions">刷新岗位与简历</el-button>
      </div>
      <AsyncState
        :loading="optionsLoading"
        :error="optionsError"
        :empty="!jobs.length || !resumes.length"
        empty-text="暂无可用于面试的岗位或简历"
        empty-description="需要至少一个已分析且进行中的岗位，以及一份最新版本为已确认状态的简历。"
        @retry="loadOptions"
      >
        <template #empty>
          <p v-if="!jobs.length">当前没有可用于面试的已分析岗位，请先导入并确认岗位信息。</p>
          <p v-if="!resumes.length">当前没有已确认的最新简历，请先在简历中心完成确认。</p>
          <div class="actions">
            <el-button v-if="!jobs.length" type="primary" @click="router.push('/jobs?tab=personal')">前往导入岗位</el-button>
            <el-button v-if="!resumes.length" type="primary" @click="router.push('/resume')">前往确认简历</el-button>
          </div>
        </template>
        <el-form label-position="top" class="prepare-form" @submit.prevent="createInterview">
          <div class="form-grid">
            <el-form-item label="已分析岗位" required>
              <el-select v-model="createForm.jobId" class="full-width" filterable placeholder="请选择岗位">
                <el-option v-for="job in jobs" :key="job.id" :value="job.id" :label="`${job.title} · ${job.companyName}`">
                  <div class="option-line">
                    <span>{{ job.title }} · {{ job.companyName }}</span>
                    <span class="muted">JD v{{ job.jobVersion }}</span>
                  </div>
                </el-option>
              </el-select>
            </el-form-item>
            <el-form-item label="已确认简历" required>
              <el-select v-model="createForm.resumeVersionId" class="full-width" filterable placeholder="请选择简历">
                <el-option v-for="resume in resumes" :key="resume.latestVersion.id" :value="resume.latestVersion.id" :label="`${resume.title} · v${resume.latestVersion.versionNo}`" />
              </el-select>
            </el-form-item>
          </div>
          <el-form-item label="主问题数量（3–10）" required>
            <el-input-number v-model="createForm.questionLimit" :min="3" :max="10" :step="1" step-strictly />
            <span class="form-hint">每个主问题最多追问 2 次</span>
          </el-form-item>
          <el-alert v-if="queryValue(route.query.planItemId)" title="本次面试将关联当前训练计划项" type="success" :closable="false" />
          <el-button native-type="submit" type="primary" :loading="creating" :disabled="!!activeTask && !isTerminalTask(activeTask.status)">创建面试并生成首题</el-button>
        </el-form>
      </AsyncState>
    </section>

    <template v-else>
      <section class="panel conversation-panel">
        <div class="panel__heading">
          <div>
            <h2>文字面试对话</h2>
            <p v-if="currentInterview" class="session-meta">
              {{ commonStatusLabels[currentInterview.status] || currentInterview.status }} · {{ currentInterview.questionLimit }} 个主问题 · 版本 {{ currentInterview.version }}
            </p>
          </div>
          <el-button size="small" :loading="interviewLoading" @click="refreshCurrent">刷新会话</el-button>
        </div>
        <AsyncState :loading="interviewLoading" :error="interviewError" @retry="refreshCurrent">
          <template v-if="currentInterview">
            <div v-if="sortedMessages.length" class="message-list">
              <article v-for="message in sortedMessages" :key="message.id" class="message" :class="`message--${message.role.toLowerCase()}`">
                <div class="message__meta">
                  <strong>{{ message.role === 'ASSISTANT' ? '面试官' : '我的回答' }}</strong>
                  <span>第 {{ message.questionNo }} 题<template v-if="message.followUpNo"> · 追问 {{ message.followUpNo }}</template></span>
                  <time>{{ formatDate(message.createdAt) }}</time>
                </div>
                <div class="message__content pre-wrap">{{ message.content }}</div>
              </article>
            </div>
            <el-empty v-else-if="activeTask && !isTerminalTask(activeTask.status)" description="首题正在生成，保存后将显示在这里" />
            <el-empty v-else description="暂无已保存的面试消息" />

            <div v-if="currentInterview.status === 'WAITING_ANSWER'" class="answer-box">
              <div class="answer-box__heading">
                <strong>回答当前问题</strong>
                <span class="muted">使用当前会话版本 {{ currentInterview.version }}</span>
              </div>
              <el-input
                v-model="answerContent"
                type="textarea"
                :rows="7"
                maxlength="10000"
                show-word-limit
                resize="vertical"
                placeholder="请输入 1–10000 个字符的回答"
                :disabled="submitting || (!!activeTask && !isTerminalTask(activeTask.status))"
                @keydown.ctrl.enter.prevent="canAnswer && sendAnswer()"
              />
              <div class="answer-box__actions">
                <span class="muted">发送失败时保留原内容；遇到版本冲突会先刷新，不会自动重发。</span>
                <el-button type="primary" :loading="submitting" :disabled="!canAnswer" @click="sendAnswer">提交回答</el-button>
              </div>
            </div>
            <div class="session-actions">
              <el-button v-if="currentInterview.status === 'WAITING_ANSWER'" type="danger" plain :loading="ending" :disabled="!!activeTask && !isTerminalTask(activeTask.status)" @click="endInterview">结束面试并生成报告</el-button>
              <el-button v-if="currentInterview.status === 'COMPLETED'" type="primary" plain :loading="reportLoading" @click="loadReport(currentInterview.id)">查看 / 刷新报告</el-button>
            </div>
          </template>
          <el-empty v-else-if="activeTask && !isTerminalTask(activeTask.status)" description="面试已创建，正在等待首题生成" />
        </AsyncState>
      </section>

      <section v-if="currentInterview?.status === 'COMPLETED' || report || reportLoading || reportError" class="panel report-panel">
        <div class="panel__heading">
          <h2>面试复盘报告</h2>
          <el-tag v-if="report" type="info">报告 v{{ report.reportVersion }}</el-tag>
        </div>
        <AsyncState :loading="reportLoading" :error="reportError" @retry="loadReport(selectedInterviewId)">
          <template v-if="report">
            <div class="report-summary">
              <div class="score-card" :class="{ 'score-card--empty': report.evaluation.overallScore === null }">
                <span>综合评分</span>
                <strong>{{ report.evaluation.overallScore === null ? '无有效评分' : report.evaluation.overallScore }}</strong>
                <small v-if="report.evaluation.overallScore !== null">满分 100</small>
              </div>
              <div class="report-facts">
                <div><span>完成方式</span><strong>{{ completionTypeLabels[report.completionType] }}</strong></div>
                <div><span>有效回答主问题数</span><strong>{{ report.answeredQuestionCount }}</strong></div>
                <div><span>证据状态</span><strong>{{ evidenceStatusLabels[report.evaluation.evidenceStatus] || report.evaluation.evidenceStatus }}</strong></div>
              </div>
            </div>
            <el-alert v-if="report.completionType === 'NO_VALID_ANSWER'" class="report-notice" title="本次没有有效回答，因此不生成有效评分，也不新增能力证据。" type="info" :closable="false" show-icon />
            <el-alert v-else-if="!report.evidenceIds.length" class="report-notice" :title="report.skillEvidenceMessage || '本次有有效总评，但没有有效的逐技能评分，因此不新增技能证据或有效样本；总分不会复制为技能分。'" type="info" :closable="false" show-icon />

            <div class="report-section">
              <h3>能力维度</h3>
              <div v-if="report.evaluation.dimensions.length" class="dimension-list">
                <div v-for="dimension in report.evaluation.dimensions" :key="dimension.key" class="dimension-item">
                  <div class="dimension-item__heading">
                    <strong>{{ dimensionLabels[dimension.key] || dimension.key }}</strong>
                    <span>{{ dimension.score }} 分 · 权重 {{ Math.round(dimension.weight * 100) }}%</span>
                  </div>
                  <el-progress :percentage="Math.max(0, Math.min(100, dimension.score))" />
                  <p class="pre-wrap">{{ dimension.comment }}</p>
                </div>
              </div>
              <el-empty v-else :image-size="72" description="暂无有效维度评分" />
            </div>

            <div class="responsive-grid report-columns">
              <div class="report-section">
                <h3>表现优点</h3>
                <ul v-if="report.evaluation.strengths.length" class="report-list strengths">
                  <li v-for="(item, index) in report.evaluation.strengths" :key="index">{{ item }}</li>
                </ul>
                <el-empty v-else :image-size="64" description="暂无优点记录" />
              </div>
              <div class="report-section">
                <h3>改进建议</h3>
                <ul v-if="report.evaluation.improvements.length" class="report-list improvements">
                  <li v-for="(item, index) in report.evaluation.improvements" :key="index">{{ item }}</li>
                </ul>
                <el-empty v-else :image-size="64" description="暂无改进建议" />
              </div>
            </div>

            <div class="report-section">
              <h3>能力差距</h3>
              <div v-if="report.gaps.length" class="gap-list">
                <article v-for="gap in report.gaps" :key="gap.skillCode" class="gap-item">
                  <div class="gap-item__heading">
                    <strong>{{ gap.skillCode }}</strong>
                    <el-tag size="small" :type="gap.evidenceStatus === 'SUFFICIENT' ? 'success' : 'warning'">{{ evidenceStatusLabels[gap.evidenceStatus] || gap.evidenceStatus }}</el-tag>
                  </div>
                  <div class="gap-scores">
                    <span>当前：{{ nullableScore(gap.currentScore) }}</span>
                    <span>目标：{{ nullableScore(gap.targetScore) }}</span>
                    <span>差距：{{ nullableScore(gap.gap) }}</span>
                  </div>
                  <p class="pre-wrap">{{ gap.suggestion }}</p>
                </article>
              </div>
              <el-empty v-else :image-size="72" description="暂无能力差距记录" />
            </div>
          </template>
        </AsyncState>
      </section>
    </template>

    <section class="panel history-panel">
      <div class="panel__heading">
        <h2>历史面试</h2>
        <el-button size="small" @click="loadHistory()">刷新记录</el-button>
      </div>
      <AsyncState :loading="historyLoading" :error="historyError" :empty="!history.length" empty-text="暂无历史面试" @retry="loadHistory()">
        <div class="history-list">
          <article v-for="item in history" :key="item.id" class="history-item" :class="{ 'history-item--active': item.id === selectedInterviewId }">
            <button class="history-item__main" type="button" @click="openInterview(item.id)">
              <span class="history-item__title">{{ item.jobTitle || `面试 ${item.id.slice(0, 8)}` }}</span>
              <el-tag size="small" :type="item.status === 'COMPLETED' ? 'success' : item.status === 'FAILED' ? 'danger' : 'primary'">{{ commonStatusLabels[item.status] || item.status }}</el-tag>
              <el-tag v-if="item.completionType === 'EARLY_END'" size="small" type="warning">提前结束</el-tag>
              <el-tag v-else-if="item.completionType === 'NO_VALID_ANSWER'" size="small" type="info">无有效回答</el-tag>
              <span class="muted">有效回答 {{ item.answeredCount ?? 0 }} 次（含追问） · 计划 {{ item.questionLimit }} 个主问题</span>
              <time class="muted">{{ formatDate(item.createdAt) }}</time>
            </button>
            <div class="history-item__actions">
              <el-button size="small" @click="openInterview(item.id)">查看对话</el-button>
              <el-button v-if="item.status === 'COMPLETED'" size="small" type="primary" plain @click="openInterview(item.id, true)">查看报告</el-button>
            </div>
          </article>
        </div>
        <el-pagination
          v-if="historyTotal > 0"
          v-model:current-page="historyPage"
          v-model:page-size="historySize"
          class="pagination"
          background
          layout="total, sizes, prev, pager, next"
          :total="historyTotal"
          :page-sizes="[5, 10, 20]"
          @current-change="handleHistoryPage"
          @size-change="handleHistorySize"
        />
      </AsyncState>
    </section>
  </div>
</template>

<style scoped lang="scss">
.interview-page { padding-bottom: 24px; }
.text-only-alert, .action-error { margin-bottom: 14px; }
.task-panel { border-color: #cfe0fb; }
.task-connection-error { margin-top: 12px; }
.task-connection-error .el-button { margin-top: 8px; }
.delta-preview { display: grid; gap: 8px; margin-top: 12px; padding: 14px; border: 1px dashed $ai-primary; border-radius: $ai-radius; color: $ai-text-secondary; background: $ai-primary-light; }
.delta-preview strong { color: $ai-primary; }
.prepare-form { max-width: 920px; }
.form-hint { margin-left: 12px; color: $ai-text-secondary; }
.option-line { display: flex; justify-content: space-between; gap: 16px; }
.session-meta { margin: 6px 0 0; color: $ai-text-secondary; }
.message-list { display: grid; gap: 14px; max-height: 680px; padding: 4px; overflow-y: auto; }
.message { width: min(88%, 900px); padding: 14px 16px; border: 1px solid $ai-border; border-radius: $ai-radius-lg; background: $ai-bg-card; }
.message--user { justify-self: end; border-color: #c9dcfb; background: $ai-primary-light; }
.message__meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 14px; margin-bottom: 8px; color: $ai-text-secondary; font-size: 12px; }
.message__meta strong { color: $ai-text-primary; font-size: 13px; }
.message__meta time { margin-left: auto; }
.message__content { color: $ai-text-primary; }
.answer-box { display: grid; gap: 12px; margin-top: 18px; padding-top: 18px; border-top: 1px solid $ai-border; }
.answer-box__heading, .answer-box__actions, .session-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; }
.session-actions { justify-content: flex-end; margin-top: 16px; }
.report-summary { display: grid; grid-template-columns: 220px 1fr; gap: 16px; }
.score-card { display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 150px; padding: 18px; border-radius: $ai-radius-lg; color: #fff; background: linear-gradient(145deg, $ai-primary, #668fec); }
.score-card strong { margin: 8px 0; font-size: 44px; line-height: 1.1; }
.score-card--empty { color: $ai-text-primary; background: $ai-bg-light; }
.score-card--empty strong { font-size: 25px; }
.report-facts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.report-facts > div { display: flex; flex-direction: column; justify-content: center; gap: 10px; padding: 18px; border: 1px solid $ai-border; border-radius: $ai-radius; }
.report-facts span { color: $ai-text-secondary; }
.report-facts strong { font-size: 18px; }
.report-notice { margin-top: 16px; }
.report-section { margin-top: 20px; }
.report-section h3 { margin: 0 0 12px; color: #101d4b; font-size: 16px; }
.dimension-list, .gap-list { display: grid; gap: 12px; }
.dimension-item, .gap-item { padding: 14px; border: 1px solid $ai-border; border-radius: $ai-radius; }
.dimension-item__heading, .gap-item__heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.dimension-item__heading span { color: $ai-text-secondary; }
.dimension-item p, .gap-item p { margin: 10px 0 0; color: $ai-text-secondary; }
.report-columns { gap: 20px; }
.report-list { display: grid; gap: 10px; margin: 0; padding: 0; list-style: none; }
.report-list li { position: relative; padding: 12px 12px 12px 32px; border-radius: $ai-radius; line-height: 1.6; }
.report-list li::before { position: absolute; left: 14px; font-weight: 700; }
.strengths li { background: #ecfdf5; }
.strengths li::before { content: '✓'; color: $ai-success; }
.improvements li { background: #fff7ed; }
.improvements li::before { content: '↑'; color: $ai-warning; }
.gap-scores { display: flex; flex-wrap: wrap; gap: 8px 20px; color: $ai-text-secondary; }
.history-list { display: grid; gap: 10px; }
.history-item { display: flex; align-items: center; gap: 12px; padding: 10px 12px; border: 1px solid $ai-border; border-radius: $ai-radius; transition: border-color .2s, background .2s; }
.history-item--active { border-color: $ai-primary; background: $ai-primary-light; }
.history-item__main { display: grid; grid-template-columns: minmax(120px, 1fr) auto auto auto; flex: 1; align-items: center; gap: 12px; min-width: 0; padding: 0; border: 0; text-align: left; background: transparent; cursor: pointer; }
.history-item__title { overflow: hidden; color: $ai-text-primary; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.history-item__actions { display: flex; flex-shrink: 0; gap: 4px; }
.history-item__actions .el-button + .el-button { margin-left: 0; }
@media (max-width: 850px) {
  .report-summary { grid-template-columns: 1fr; }
  .history-item { align-items: stretch; flex-direction: column; }
  .history-item__main { grid-template-columns: 1fr auto; }
  .history-item__main time { grid-column: 1 / -1; }
  .history-item__actions { justify-content: flex-end; }
}
@media (max-width: 700px) {
  .message { width: 100%; }
  .message__meta time { width: 100%; margin-left: 0; }
  .report-facts { grid-template-columns: 1fr; }
  .answer-box__actions { align-items: stretch; flex-direction: column; }
  .answer-box__actions .el-button { width: 100%; }
  .form-hint { display: block; margin: 8px 0 0; }
}
</style>
