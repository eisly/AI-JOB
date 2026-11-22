<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ChatDotSquare, Headset, Microphone, RefreshRight, VideoPause, VideoPlay } from '@element-plus/icons-vue'
import AsyncState from '@/components/AsyncState.vue'
import TaskStatus from '@/components/TaskStatus.vue'
import InterviewReportPanel from './components/InterviewReportPanel.vue'
import VoiceAnswerPanel from './components/VoiceAnswerPanel.vue'
import { canRetryTrackedTask } from '@/api/task-registry'
import { useInterviewSession } from '@/composables/useInterviewSession'
import { useQuestionSpeech } from '@/composables/voice/useQuestionSpeech'
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
  initialize,
  queryFor,
} = useInterviewSession({ basePath: '/interview/voice' })

const {
  playing,
  paused,
  blocked,
  degraded,
  error: speechError,
  progress,
  currentTime,
  duration,
  play,
  autoPlay,
  replay,
  pause,
  resume,
  stop: stopSpeech,
} = useQuestionSpeech(selectedInterviewId)

const voiceAutoPlay = ref(false)
const recording = ref(false)
let lastQuestionId = ''
const planItemId = computed(() => typeof route.query.planItemId === 'string' ? route.query.planItemId : '')

const activeSessions = computed(() => history.value.filter((item) => !['COMPLETED', 'FAILED'].includes(item.status)))
const activeVoiceSession = computed(() => activeSessions.value.find((item) => item.mode === 'VOICE') || null)
const activeTextSession = computed(() => activeSessions.value.find((item) => item.mode !== 'VOICE') || null)
const currentJob = computed(() => jobs.value.find((job) => job.id === createForm.value.jobId))
const sessionTitle = computed(() => currentInterview.value?.jobTitle || currentJob.value?.title || '语音模拟面试')
const playbackEnabled = computed(() => voiceCapability.value?.ttsEnabled !== false)
const canContinue = computed(() => playbackEnabled.value
  && !voiceAutoPlay.value
  && currentInterview.value?.status === 'WAITING_ANSWER'
  && !!currentQuestion.value)

const assistantByQuestion = computed(() => {
  const map = new Map<number, { content: string; followUpNo: number }>()
  sortedMessages.value.filter((message) => message.role === 'ASSISTANT').forEach((message) => {
    map.set(message.questionNo, { content: message.content, followUpNo: message.followUpNo })
  })
  return map
})
const questionSteps = computed(() => {
  const limit = currentInterview.value?.questionLimit || 0
  return Array.from({ length: limit }, (_, index) => {
    const no = index + 1
    return {
      no,
      asked: assistantByQuestion.value.has(no),
      current: currentQuestion.value?.questionNo === no,
    }
  })
})
const currentQuestionNo = computed(() => currentQuestion.value?.questionNo || 0)
const questionPercent = computed(() => {
  const limit = currentInterview.value?.questionLimit || 0
  return limit ? Math.round((currentQuestionNo.value / limit) * 100) : 0
})

function formatClock(seconds: number) {
  const total = Math.max(0, Math.round(seconds || 0))
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`
}

function togglePlayback() {
  if (recording.value) return
  const question = currentQuestion.value
  if (!question) return
  if (playing.value) {
    pause()
    return
  }
  if (paused.value) {
    resume()
    return
  }
  void play(question.id, question.content)
}

function retrySpeech() {
  if (recording.value) return
  const question = currentQuestion.value
  if (question) void replay(question.id, question.content)
}

function playbackLabel() {
  if (playing.value) return '正在播报'
  if (blocked.value) return '点击播放题目'
  if (paused.value) return '已暂停'
  if (speechError.value) return '播报失败，可重试'
  if (degraded.value) return '使用浏览器语音播报'
  return '等待播报'
}

async function beginVoiceInterview() {
  const task = await createInterview('VOICE')
  if (task) voiceAutoPlay.value = true
}

function continueVoice() {
  if (recording.value) return
  voiceAutoPlay.value = true
  const question = currentQuestion.value
  if (!question) return
  stopSpeech()
  void play(question.id, question.content)
}

function handleRecordingChange(active: boolean) {
  recording.value = active
  if (active) stopSpeech()
}

function resumeActiveSession() {
  if (!activeVoiceSession.value) return
  void openInterview(activeVoiceSession.value.id)
}

function goTextInterview() {
  if (!activeTextSession.value) return
  void router.push({ path: '/interview', query: queryFor(activeTextSession.value.id) })
}

function goBack() {
  stopSpeech()
  void router.push('/interview')
}

async function endCurrentInterview() {
  const ended = await endInterview()
  if (ended) {
    stopSpeech()
    voiceAutoPlay.value = false
  }
}

watch(
  () => currentQuestion.value?.id,
  (id) => {
    if (!id) {
      stopSpeech()
      lastQuestionId = ''
      return
    }
    if (lastQuestionId && lastQuestionId !== id) stopSpeech()
    lastQuestionId = id
    if (!voiceAutoPlay.value || !playbackEnabled.value || recording.value) return
    const question = currentQuestion.value
    if (question) void autoPlay(question.id, question.content)
  },
)

watch(currentInterview, (value) => {
  if (value?.mode === 'TEXT' && route.path !== '/interview') {
    void router.replace({
      path: '/interview',
      query: queryFor(value.id, route.query.report === '1'),
    })
  }
})

watch(
  () => currentInterview.value?.status,
  (status) => {
    if (status === 'COMPLETED' || status === 'FAILED') {
      stopSpeech()
      voiceAutoPlay.value = false
    }
  },
)

onMounted(async () => {
  await initialize()
  if (currentInterview.value?.status === 'WAITING_ANSWER') voiceAutoPlay.value = false
})

onBeforeUnmount(() => {
  stopSpeech()
})
</script>

<template>
  <div class="page voice-page">
    <header class="page-header">
      <div>
        <el-button text :icon="ArrowLeft" class="back-button" @click="goBack">返回模拟面试</el-button>
        <h1>语音模拟面试</h1>
        <p>AI 播报题目，你可以语音或文字作答。语音回答会先转写为文字，经你确认后提交。</p>
      </div>
      <div class="page-header__actions">
        <el-button v-if="selectedInterviewId && currentInterview?.status === 'WAITING_ANSWER'" :loading="ending" :disabled="!!activeTask && !isTerminalTask(activeTask.status)" @click="endCurrentInterview">结束面试</el-button>
        <el-button v-else-if="selectedInterviewId && currentInterview?.status === 'COMPLETED'" type="primary" plain :loading="reportLoading" @click="loadReport(selectedInterviewId)">查看报告</el-button>
      </div>
    </header>

    <el-alert v-if="actionError" class="action-error" :title="errorMessage(actionError)" type="error" show-icon @close="actionError = null" />

    <section v-if="activeTaskId" class="panel task-panel">
      <div class="panel__heading">
        <h2>当前异步任务</h2>
      </div>
      <TaskStatus :task="activeTask" :reasoning="activeReasoning" :loading="tracker.loading.value" :tracking-error="tracker.error.value" :refreshing="tracker.refreshing.value" @refresh="tracker.refresh">
        <template #actions>
          <el-button v-if="activeTask && canRetryTrackedTask(activeTask)" type="primary" :loading="retrying" @click="retryActiveTask">重试失败任务</el-button>
          <el-button v-if="activeTask?.status === 'FAILED' && activeTask.type === 'INTERVIEW_TURN'" :disabled="submitting || retrying" @click="retryAnswerWithSameContent">使用原内容重新提交</el-button>
        </template>
      </TaskStatus>
      <div v-if="pendingDeltaText" class="delta-preview">
        <strong>正在生成，尚未保存</strong>
        <div class="pre-wrap">{{ pendingDeltaText }}</div>
      </div>
    </section>

    <section v-if="!selectedInterviewId" class="prepare-layout">
      <section class="panel prepare-card">
        <div class="panel__heading">
          <h2>面试准备</h2>
          <el-button size="small" @click="loadOptions">刷新岗位与简历</el-button>
        </div>
        <div v-if="activeVoiceSession" class="voice-notice voice-notice--active">
          <div>
            <strong>你有正在进行的语音面试</strong>
            <p>{{ activeVoiceSession.jobTitle || '上一场语音面试' }} · 主问题 {{ activeVoiceSession.questionLimit }} 个</p>
          </div>
          <el-button type="primary" @click="resumeActiveSession">继续已有语音面试</el-button>
        </div>
        <div v-else-if="activeTextSession" class="voice-notice">
          <div>
            <strong>你有正在进行的文字面试</strong>
            <p>同一时间只能有一场活动面试，请先继续或结束文字面试。</p>
          </div>
          <el-button @click="goTextInterview">继续文字面试</el-button>
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
          <el-form label-position="top" class="prepare-form" @submit.prevent="beginVoiceInterview">
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
            <el-form-item label="主问题数量（3–10）" required>
              <el-input-number v-model="createForm.questionLimit" :min="3" :max="10" :step="1" step-strictly />
              <span class="form-hint">每个主问题最多追问 2 次</span>
            </el-form-item>
            <el-alert v-if="planItemId" title="本次面试将关联当前训练计划项" type="success" :closable="false" />
            <el-button native-type="submit" type="primary" size="large" :loading="creating" :disabled="!!activeTask && !isTerminalTask(activeTask.status)">开始语音面试</el-button>
          </el-form>
        </AsyncState>
      </section>

      <section class="panel mode-card">
        <div class="panel__heading">
          <h2>作答方式</h2>
        </div>
        <div class="mode-list">
          <article class="mode-item">
            <el-icon class="mode-item__icon"><Headset /></el-icon>
            <div>
              <strong>听题</strong>
              <p>题目自动播报，支持暂停和重播。</p>
            </div>
          </article>
          <article class="mode-item">
            <el-icon class="mode-item__icon"><Microphone /></el-icon>
            <div>
              <strong>录音</strong>
              <p>开始录音会停止播报，转写后可编辑确认。</p>
            </div>
          </article>
          <article class="mode-item">
            <el-icon class="mode-item__icon"><ChatDotSquare /></el-icon>
            <div>
              <strong>文字</strong>
              <p>随时切换为文字作答，已识别内容会保留。</p>
            </div>
          </article>
        </div>
      </section>
    </section>

    <template v-else>
      <div class="session-heading">
        <div>
          <h2>{{ sessionTitle }}</h2>
          <div class="session-meta">
            <el-tag size="small" type="primary">语音面试</el-tag>
            <el-tag size="small" :type="currentInterview?.status === 'COMPLETED' ? 'success' : 'info'">
              {{ currentInterview ? (commonStatusLabels[currentInterview.status] || currentInterview.status) : '加载中' }}
            </el-tag>
            <span v-if="currentQuestion" class="muted">第 {{ currentQuestion.questionNo }} 题<template v-if="currentQuestion.followUpNo"> · 追问 {{ currentQuestion.followUpNo }}</template></span>
            <span class="muted">版本 {{ currentInterview?.version ?? '-' }}</span>
          </div>
        </div>
        <el-button size="small" :loading="interviewLoading" @click="refreshCurrent">刷新会话</el-button>
      </div>

      <AsyncState :loading="interviewLoading" :error="interviewError" @retry="refreshCurrent">
        <template v-if="currentInterview">
          <div class="voice-workspace">
            <div class="voice-main">
              <section class="panel question-card">
                <div class="question-card__heading">
                  <div class="question-card__title">
                    <el-icon><Headset /></el-icon>
                    <strong>AI 面试官</strong>
                  </div>
                  <el-tag v-if="currentQuestion" size="small" type="info">
                    第 {{ currentQuestion.questionNo }} 题<template v-if="currentQuestion.followUpNo"> · 追问 {{ currentQuestion.followUpNo }}</template>
                  </el-tag>
                </div>
                <p v-if="currentQuestion" class="question-card__content">{{ currentQuestion.content }}</p>
                <p v-else-if="activeTaskId && !isTerminalTask(activeTask?.status)" class="muted">题目生成中，完成后会自动播报。</p>
                <el-empty v-else description="暂无题目" :image-size="64" />

                <div v-if="currentQuestion && playbackEnabled" class="playback-bar">
                  <el-button circle :icon="playing ? VideoPause : VideoPlay" :disabled="recording" :aria-label="playing ? '暂停播报' : '播放题目'" @click="togglePlayback" />
                  <span class="playback-bar__state">{{ playbackLabel() }}</span>
                  <el-progress class="playback-bar__progress" :percentage="Math.round(progress * 100)" :show-text="false" />
                  <span class="playback-bar__time">{{ formatClock(currentTime) }} / {{ formatClock(duration) }}</span>
                  <el-button size="small" :icon="RefreshRight" :disabled="recording" @click="retrySpeech">重播</el-button>
                </div>
                <p v-else-if="currentQuestion && !playbackEnabled" class="muted">当前会话未开启题目播报，请直接阅读题目原文作答。</p>

                <div v-if="canContinue" class="continue-bar">
                  <span>已打开一场语音面试，继续后将自动播报当前题目。</span>
                  <el-button type="primary" :disabled="recording" @click="continueVoice">继续语音面试</el-button>
                </div>
                <el-alert v-if="blocked" class="speech-alert" title="浏览器拦截了自动播报" description="请点击上方播放按钮开始听题；题目原文始终可以阅读。" type="warning" :closable="false" show-icon />
                <el-alert v-else-if="speechError" class="speech-alert" title="题目语音合成失败" description="可以重试播报，或直接阅读题目原文作答。" type="error" :closable="false" show-icon>
                  <template #default>
                    <el-button size="small" type="primary" plain :disabled="recording" @click="retrySpeech">重试播报</el-button>
                  </template>
                </el-alert>
              </section>

              <section v-if="currentInterview.status === 'WAITING_ANSWER' && currentQuestion" class="panel answer-card">
                <div class="answer-card__heading">
                  <h2>你的回答</h2>
                  <p class="muted">你可以听完题目再录音，也可以直接打字。录音转写后，可编辑确认再提交。</p>
                </div>
                <VoiceAnswerPanel
                  v-if="voiceAvailable && voiceCapability"
                  :key="`${currentInterview.id}:${currentQuestion.id}`"
                  :interview="currentInterview"
                  :question="currentQuestion"
                  :capability="voiceCapability"
                  :disabled="!!activeTask && !isTerminalTask(activeTask.status)"
                  :submitting="submitting"
                  :show-player="false"
                  flat
                  title=""
                  @submit="handleVoiceSubmit"
                  @switch-text="handleVoiceSwitchText"
                  @recording-start="stopSpeech"
                  @recording-change="handleRecordingChange"
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
                  <div class="answer-card__actions">
                    <span class="muted">当前环境不可用麦克风，可直接输入文字作答。</span>
                    <el-button type="primary" :loading="submitting" :disabled="!canAnswer" @click="sendAnswer">提交回答</el-button>
                  </div>
                </template>
              </section>

              <section v-else-if="currentInterview.status === 'COMPLETED'" class="panel session-done">
                <el-icon><Headset /></el-icon>
                <div>
                  <strong>本次语音面试已结束</strong>
                  <p class="muted">报告已保存在服务端，可在下方查看评分、优点与改进建议。</p>
                </div>
              </section>
            </div>

            <aside class="voice-side">
              <section class="panel progress-card">
                <div class="panel__heading">
                  <h2>面试进度</h2>
                </div>
                <div class="progress-summary">
                  <span>主问题 {{ currentQuestionNo || 0 }} / {{ currentInterview.questionLimit }}</span>
                  <el-progress :percentage="questionPercent" :show-text="false" />
                </div>
                <ul class="progress-list">
                  <li v-for="step in questionSteps" :key="step.no" :class="{ 'is-current': step.current, 'is-done': step.asked && !step.current }">
                    <span class="progress-dot" />
                    <div>
                      <strong>第 {{ step.no }} 题</strong>
                      <small v-if="step.current" class="muted">
                        {{ currentQuestion?.followUpNo ? `当前追问 ${currentQuestion.followUpNo}` : '当前题目' }}
                      </small>
                      <small v-else-if="step.asked" class="muted">已完成</small>
                      <small v-else class="muted">未开始</small>
                    </div>
                  </li>
                </ul>
              </section>
              <section class="panel meta-card">
                <div class="panel__heading">
                  <h2>会话信息</h2>
                </div>
                <dl class="meta-list">
                  <div><dt>岗位</dt><dd>{{ currentInterview.jobTitle || '当前岗位' }}</dd></div>
                  <div><dt>已保存消息</dt><dd>{{ sortedMessages.length }}</dd></div>
                  <div><dt>创建时间</dt><dd>{{ formatDate(currentInterview.createdAt) }}</dd></div>
                </dl>
                <el-button size="small" class="full-width" @click="loadHistory()">刷新历史</el-button>
              </section>
            </aside>
          </div>
        </template>
      </AsyncState>

      <InterviewReportPanel
        v-if="currentInterview?.status === 'COMPLETED' || report || reportLoading || reportError"
        :report="report"
        :loading="reportLoading"
        :error="reportError"
        @retry="loadReport(selectedInterviewId)"
      />

      <section class="panel history-panel">
        <div class="panel__heading">
          <h2>历史面试</h2>
          <el-button size="small" @click="loadHistory()">刷新记录</el-button>
        </div>
        <AsyncState :loading="historyLoading" :retain="history.length > 0" :error="historyError" :empty="!history.length" empty-text="暂无历史面试" @retry="loadHistory()">
          <div class="history-list">
            <article v-for="item in history" :key="item.id" class="history-item">
              <button class="history-item__main" type="button" @click="router.push({ path: '/interview/voice', query: queryFor(item.id) })">
                <span class="history-item__title">{{ item.jobTitle || `面试 ${item.id.slice(0, 8)}` }}</span>
                <el-tag size="small" :type="item.mode === 'VOICE' ? 'primary' : 'info'">{{ item.mode === 'VOICE' ? '语音' : '文字' }}</el-tag>
                <el-tag size="small" :type="item.status === 'COMPLETED' ? 'success' : item.status === 'FAILED' ? 'danger' : 'primary'">{{ commonStatusLabels[item.status] || item.status }}</el-tag>
                <time class="muted">{{ formatDate(item.createdAt) }}</time>
              </button>
              <div class="history-item__actions">
                <el-button size="small" @click="router.push({ path: '/interview/voice', query: queryFor(item.id) })">查看对话</el-button>
                <el-button v-if="item.status === 'COMPLETED'" size="small" type="primary" plain @click="router.push({ path: '/interview/voice', query: queryFor(item.id, true) })">查看报告</el-button>
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
    </template>
  </div>
</template>

<style scoped lang="scss">
.voice-page { padding-bottom: 24px; }
.back-button { margin-bottom: 6px; }
.action-error, .task-panel, .speech-alert { margin-bottom: 14px; }
.task-panel { border-color: var(--ai-accent-border, #cfe0fb); }
.delta-preview { display: grid; gap: 8px; margin-top: 12px; padding: 14px; border: 1px dashed $ai-primary; border-radius: $ai-radius; color: $ai-text-secondary; background: $ai-primary-light; }
.delta-preview strong { color: $ai-primary; }
.prepare-layout { display: grid; grid-template-columns: minmax(0, 1.4fr) minmax(260px, .8fr); gap: 18px; align-items: start; }
.prepare-form { display: grid; gap: 4px; }
.prepare-form .el-button { justify-self: start; margin-top: 6px; }
.form-hint { margin-left: 12px; color: $ai-text-secondary; }
.option-line { display: flex; justify-content: space-between; gap: 16px; }
.voice-notice { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-bottom: 16px; padding: 14px 16px; border: 1px solid $ai-border; border-radius: $ai-radius; background: $ai-bg-light; }
.voice-notice--active { border-color: var(--ai-accent-border, #cfe0fb); background: $ai-primary-light; }
.voice-notice strong { color: $ai-text-primary; }
.voice-notice p { margin: 4px 0 0; color: $ai-text-secondary; font-size: 13px; }
.mode-list { display: grid; gap: 12px; }
.mode-item { display: flex; align-items: flex-start; gap: 12px; padding: 16px; border: 1px solid $ai-border; border-radius: $ai-radius; background: $ai-bg-card; }
.mode-item__icon { flex-shrink: 0; margin-top: 2px; color: $ai-primary; font-size: 22px; }
.mode-item strong { color: $ai-text-primary; }
.mode-item p { margin: 6px 0 0; color: $ai-text-secondary; font-size: 13px; line-height: 1.6; }
.session-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin: 6px 0 14px; }
.session-heading h2 { margin: 0; color: $ai-text-primary; font-size: 22px; }
.session-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; margin-top: 8px; }
.voice-workspace { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: 18px; align-items: start; }
.voice-main { display: grid; gap: 16px; min-width: 0; }
.voice-side { display: grid; gap: 16px; }
.question-card__heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.question-card__title { display: flex; align-items: center; gap: 8px; color: $ai-primary; }
.question-card__title strong { color: $ai-text-primary; }
.question-card__content { margin: 18px 0; color: $ai-text-primary; font-size: clamp(18px, 2vw, 24px); font-weight: 600; line-height: 1.6; }
.playback-bar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 12px 14px; border-radius: $ai-radius; background: $ai-primary-light; }
.playback-bar__state { color: $ai-primary; font-size: 13px; font-weight: 600; }
.playback-bar__progress { flex: 1; min-width: 140px; }
.playback-bar__time { color: $ai-text-secondary; font-size: 12px; font-variant-numeric: tabular-nums; }
.continue-bar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; margin-top: 14px; padding: 14px; border: 1px solid $ai-border; border-radius: $ai-radius; background: $ai-bg-light; }
.continue-bar span { color: $ai-text-secondary; font-size: 13px; }
.answer-card__heading { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 8px 16px; margin-bottom: 12px; }
.answer-card__heading h2 { margin: 0; color: $ai-text-primary; font-size: 16px; }
.answer-card__heading p { margin: 0; }
.answer-card__actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; margin-top: 12px; }
.session-done { display: flex; align-items: center; gap: 14px; color: $ai-success; }
.session-done p { margin: 4px 0 0; }
.progress-summary { display: grid; gap: 10px; margin-bottom: 14px; color: $ai-text-secondary; font-size: 13px; }
.progress-list { display: grid; gap: 10px; margin: 0; padding: 0; list-style: none; }
.progress-list li { display: flex; align-items: flex-start; gap: 10px; padding: 10px 12px; border: 1px solid transparent; border-radius: $ai-radius; color: $ai-text-secondary; }
.progress-list li.is-current { border-color: var(--ai-accent-border, #cfe0fb); background: $ai-primary-light; color: $ai-primary; }
.progress-list li strong { display: block; color: $ai-text-primary; font-size: 13px; }
.progress-list li.is-current strong { color: $ai-primary; }
.progress-list li small { display: block; margin-top: 2px; font-size: 12px; }
.progress-dot { width: 8px; height: 8px; margin-top: 6px; border-radius: 50%; background: var(--ai-border-strong, #c7d4ea); }
.is-current .progress-dot { background: $ai-primary; }
.is-done .progress-dot { background: $ai-success; }
.meta-list { display: grid; gap: 10px; margin: 0 0 14px; }
.meta-list div { display: flex; justify-content: space-between; gap: 12px; }
.meta-list dt { color: $ai-text-secondary; }
.meta-list dd { margin: 0; color: $ai-text-primary; text-align: right; }
.full-width { width: 100%; }
.history-list { display: grid; gap: 10px; }
.history-item { display: flex; align-items: center; gap: 12px; padding: 10px 12px; border: 1px solid $ai-border; border-radius: $ai-radius; }
.history-item__main { display: grid; grid-template-columns: minmax(120px, 1fr) auto auto auto; flex: 1; align-items: center; gap: 12px; min-width: 0; padding: 0; border: 0; text-align: left; background: transparent; cursor: pointer; }
.history-item__title { overflow: hidden; color: $ai-text-primary; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.history-item__actions { display: flex; flex-shrink: 0; gap: 4px; }
.history-item__actions .el-button + .el-button { margin-left: 0; }
@media (max-width: 1100px) {
  .voice-workspace, .prepare-layout { grid-template-columns: 1fr; }
  .voice-side { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 850px) {
  .history-item { align-items: stretch; flex-direction: column; }
  .history-item__main { grid-template-columns: 1fr auto; }
  .history-item__main time { grid-column: 1 / -1; }
  .history-item__actions { justify-content: flex-end; }
}
@media (max-width: 700px) {
  .voice-side { grid-template-columns: 1fr; }
  .session-heading { flex-direction: column; }
  .voice-notice, .continue-bar { align-items: stretch; flex-direction: column; }
  .playback-bar { align-items: stretch; }
  .playback-bar__progress { min-width: 0; }
  .form-hint { display: block; margin: 8px 0 0; }
}
</style>
