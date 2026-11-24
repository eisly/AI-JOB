<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AsyncState from '@/components/AsyncState.vue'
import TaskStatus from '@/components/TaskStatus.vue'
import InterviewReportPanel from './components/InterviewReportPanel.vue'
import VoiceAnswerPanel from './components/VoiceAnswerPanel.vue'
import { canRetryTrackedTask } from '@/api/task-registry'
import { useInterviewSession } from '@/composables/useInterviewSession'
import { isTerminalTask } from '@/composables/useTask'
import { commonStatusLabels, errorMessage, formatDate } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const {
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
  loadOptions,
  loadHistory,
  handleHistoryPage,
  handleHistorySize,
  loadReport,
  openInterview,
  createInterview,
  sendAnswer,
  handleVoiceSubmit,
  handleVoiceSwitchText,
  retryAnswerWithSameContent,
  endInterview,
  retryActiveTask,
  refreshCurrent,
  startNew,
  initialize,
  queryFor,
} = useInterviewSession({ basePath: '/interview' })

const planItemId = computed(() => typeof route.query.planItemId === 'string' ? route.query.planItemId : '')

function goVoiceInterview() {
  void router.push({
    path: '/interview/voice',
    query: {
      ...(createForm.value.jobId ? { jobId: createForm.value.jobId } : {}),
      ...(createForm.value.resumeVersionId ? { resumeVersionId: createForm.value.resumeVersionId } : {}),
      questionLimit: String(createForm.value.questionLimit),
      ...(planItemId.value ? { planItemId: planItemId.value } : {}),
    },
  })
}

function openHistory(item: { id: string; mode?: 'TEXT' | 'VOICE' }, showReport = false) {
  if (item.mode === 'VOICE') {
    void router.push({ path: '/interview/voice', query: queryFor(item.id, showReport) })
    return
  }
  void openInterview(item.id, showReport)
}

async function createTextInterview() {
  await startNew()
}

async function submitNewTextInterview() {
  await createInterview('TEXT')
}

watch(currentInterview, (value) => {
  if (value?.mode === 'VOICE' && route.path !== '/interview/voice') {
    void router.replace({
      path: '/interview/voice',
      query: queryFor(value.id, route.query.report === '1'),
    })
  }
})

onMounted(async () => {
  await initialize()
})
</script>

<template>
  <div class="page interview-page">
    <header class="page-header">
      <div>
        <h1>模拟面试</h1>
        <p>基于已分析岗位与已确认简历进行文字或语音面试，回答和评价结果以服务端保存内容为准。</p>
      </div>
      <div class="page-header__actions">
        <el-button @click="refreshCurrent" :disabled="!selectedInterviewId || interviewLoading">刷新当前面试</el-button>
        <el-button plain @click="createTextInterview">新建文字面试</el-button>
        <el-button type="primary" @click="goVoiceInterview">新建语音面试</el-button>
      </div>
    </header>

    <el-alert
      v-if="currentInterview && voiceCapability && !voiceCapability.asrEnabled"
      class="text-only-alert"
      title="当前会话暂未开启语音作答"
      description="可直接输入文字完成面试；语音能力恢复后会在此处展示入口。"
      type="info"
      :closable="false"
      show-icon
    />
    <el-alert
      v-else-if="currentInterview && !voiceAvailable"
      class="text-only-alert"
      title="当前浏览器环境不支持录音"
      description="需要使用 HTTPS 或本机地址，并允许麦克风权限；也可以直接输入文字作答。"
      type="warning"
      :closable="false"
      show-icon
    />
    <el-alert
      v-else
      class="text-only-alert"
      title="支持文字与语音逐题作答"
      description="本页为文字面试，题目播报请点击按钮；语音面试页会在每道新题生成后自动播报，录音时自动停止。"
      type="info"
      :closable="false"
      show-icon
    />

    <section v-if="activeTaskId" class="panel task-panel">
      <div class="panel__heading">
        <h2>当前异步任务</h2>
      </div>
      <TaskStatus :task="activeTask" :reasoning="activeReasoning" :loading="tracker.loading.value" :tracking-error="tracker.error.value" :refreshing="tracker.refreshing.value" @refresh="tracker.refresh">
        <template #actions>
          <el-button v-if="activeTask && canRetryTrackedTask(activeTask)" type="primary" :loading="retrying" @click="retryActiveTask">重试失败任务</el-button>
          <el-button v-if="activeTask?.status === 'FAILED' && activeTask.type === 'INTERVIEW_TURN' && answerAttempt" :disabled="submitting || retrying" @click="retryAnswerWithSameContent">使用原内容重新提交</el-button>
        </template>
      </TaskStatus>
      <div v-if="pendingDeltaText" class="delta-preview">
        <strong>正在生成，尚未保存</strong>
        <div class="pre-wrap">{{ pendingDeltaText }}</div>
      </div>
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
        <el-form label-position="top" class="prepare-form" @submit.prevent="submitNewTextInterview">
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
          <el-alert v-if="planItemId" title="本次面试将关联当前训练计划项" type="success" :closable="false" />
          <el-button native-type="submit" type="primary" :loading="creating" :disabled="!!activeTask && !isTerminalTask(activeTask.status)">创建文字面试并生成首题</el-button>
        </el-form>
      </AsyncState>
    </section>

    <template v-else>
      <section class="panel conversation-panel">
        <div class="panel__heading">
          <div>
            <h2>面试对话</h2>
            <p v-if="currentInterview" class="session-meta">
              <el-tag size="small" type="info">{{ currentInterview.mode === 'VOICE' ? '语音' : '文字' }}</el-tag>
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
                  <el-tag v-if="message.role === 'USER'" size="small" type="info">{{ message.answerSource === 'VOICE' ? '语音回答' : '文字回答' }}</el-tag>
                  <time>{{ formatDate(message.createdAt) }}</time>
                </div>
                <div class="message__content pre-wrap">{{ message.content }}</div>
              </article>
            </div>
            <p v-else-if="activeTaskId && !isTerminalTask(activeTask?.status)" class="muted">首题就绪后将在这里显示。</p>
            <el-empty v-else description="暂无已保存的面试消息" />

            <div v-if="currentInterview.status === 'WAITING_ANSWER'" class="answer-box">
              <div class="answer-box__heading">
                <strong>回答当前问题</strong>
                <span class="muted">使用当前会话版本 {{ currentInterview.version }}</span>
              </div>
              <VoiceAnswerPanel
                v-if="voiceAvailable && voiceCapability && currentQuestion"
                :key="`${currentInterview.id}:${currentQuestion.id}`"
                :interview="currentInterview"
                :question="currentQuestion"
                :capability="voiceCapability"
                :disabled="!!activeTask && !isTerminalTask(activeTask.status)"
                :submitting="submitting"
                @submit="handleVoiceSubmit"
                @switch-text="handleVoiceSwitchText"
              />
              <template v-else>
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
              </template>
            </div>
            <div class="session-actions">
              <el-button v-if="currentInterview.status === 'WAITING_ANSWER'" type="danger" plain :loading="ending" :disabled="!!activeTask && !isTerminalTask(activeTask.status)" @click="endInterview()">结束面试并生成报告</el-button>
              <el-button v-if="currentInterview.status === 'COMPLETED'" type="primary" plain :loading="reportLoading" @click="loadReport(currentInterview.id)">查看 / 刷新报告</el-button>
            </div>
          </template>
          <p v-else-if="activeTaskId && !isTerminalTask(activeTask?.status)" class="muted">首题就绪后将在这里显示。</p>
        </AsyncState>
      </section>

      <InterviewReportPanel
        v-if="currentInterview?.status === 'COMPLETED' || report || reportLoading || reportError"
        :report="report"
        :loading="reportLoading"
        :reevaluating="ending || (!!activeTask && !isTerminalTask(activeTask.status))"
        :error="reportError"
        @retry="loadReport(selectedInterviewId)"
        @reevaluate="endInterview(true)"
      />
    </template>

    <section class="panel history-panel">
      <div class="panel__heading">
        <h2>历史面试</h2>
        <el-button size="small" @click="loadHistory()">刷新记录</el-button>
      </div>
      <AsyncState :loading="historyLoading" :retain="history.length > 0" :error="historyError" :empty="!history.length" empty-text="暂无历史面试" @retry="loadHistory()">
        <div class="history-list">
          <article v-for="item in history" :key="item.id" class="history-item" :class="{ 'history-item--active': item.id === selectedInterviewId }">
            <button class="history-item__main" type="button" @click="openHistory(item)">
              <span class="history-item__title">{{ item.jobTitle || `面试 ${item.id.slice(0, 8)}` }}</span>
              <el-tag size="small" :type="item.mode === 'VOICE' ? 'primary' : 'info'">{{ item.mode === 'VOICE' ? '语音' : '文字' }}</el-tag>
              <el-tag size="small" :type="item.status === 'COMPLETED' ? 'success' : item.status === 'FAILED' ? 'danger' : 'primary'">{{ commonStatusLabels[item.status] || item.status }}</el-tag>
              <el-tag v-if="item.completionType === 'EARLY_END'" size="small" type="warning">提前结束</el-tag>
              <el-tag v-else-if="item.completionType === 'NO_VALID_ANSWER' && !item.answeredCount" size="small" type="info">未作答</el-tag>
              <span class="muted">已回答 {{ item.answeredCount ?? 0 }} 次（含追问） · 计划 {{ item.questionLimit }} 个主问题</span>
              <time class="muted">{{ formatDate(item.createdAt) }}</time>
            </button>
            <div class="history-item__actions">
              <el-button size="small" @click="openHistory(item)">查看对话</el-button>
              <el-button v-if="item.status === 'COMPLETED'" size="small" type="primary" plain @click="openHistory(item, true)">查看报告</el-button>
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
.task-panel { border-color: var(--ai-accent-border, #cfe0fb); }
.delta-preview { display: grid; gap: 8px; margin-top: 12px; padding: 14px; border: 1px dashed $ai-primary; border-radius: $ai-radius; color: $ai-text-secondary; background: $ai-primary-light; }
.delta-preview strong { color: $ai-primary; }
.prepare-form { max-width: 920px; }
.form-hint { margin-left: 12px; color: $ai-text-secondary; }
.option-line { display: flex; justify-content: space-between; gap: 16px; }
.session-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 6px 0 0; color: $ai-text-secondary; }
.message-list { display: grid; gap: 14px; max-height: 680px; padding: 4px; overflow-y: auto; }
.message { width: min(88%, 900px); padding: 14px 16px; border: 1px solid $ai-border; border-radius: $ai-radius-lg; background: $ai-bg-card; }
.message--user { justify-self: end; border-color: var(--ai-accent-border, #c9dcfb); background: $ai-primary-light; }
.message__meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 14px; margin-bottom: 8px; color: $ai-text-secondary; font-size: 12px; }
.message__meta strong { color: $ai-text-primary; font-size: 13px; }
.message__meta time { margin-left: auto; }
.message__content { color: $ai-text-primary; }
.answer-box { display: grid; gap: 12px; margin-top: 18px; padding-top: 18px; border-top: 1px solid $ai-border; }
.answer-box__heading, .answer-box__actions, .session-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; }
.session-actions { justify-content: flex-end; margin-top: 16px; }
.history-list { display: grid; gap: 10px; }
.history-item { display: flex; align-items: center; gap: 12px; padding: 10px 12px; border: 1px solid $ai-border; border-radius: $ai-radius; transition: border-color .2s, background .2s; }
.history-item--active { border-color: $ai-primary; background: $ai-primary-light; }
.history-item__main { display: grid; grid-template-columns: minmax(120px, 1fr) auto auto auto auto; flex: 1; align-items: center; gap: 12px; min-width: 0; padding: 0; border: 0; text-align: left; background: transparent; cursor: pointer; }
.history-item__title { overflow: hidden; color: $ai-text-primary; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.history-item__actions { display: flex; flex-shrink: 0; gap: 4px; }
.history-item__actions .el-button + .el-button { margin-left: 0; }
@media (max-width: 850px) {
  .history-item { align-items: stretch; flex-direction: column; }
  .history-item__main { grid-template-columns: 1fr auto; }
  .history-item__main time { grid-column: 1 / -1; }
  .history-item__actions { justify-content: flex-end; }
}
@media (max-width: 700px) {
  .message { width: 100%; }
  .message__meta time { width: 100%; margin-left: 0; }
  .answer-box__actions { align-items: stretch; flex-direction: column; }
  .answer-box__actions .el-button { width: 100%; }
  .form-hint { display: block; margin: 8px 0 0; }
}
</style>
