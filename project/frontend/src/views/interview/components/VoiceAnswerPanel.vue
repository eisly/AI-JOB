<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { voiceInterviewApi } from '@/api/voiceInterviews'
import type { InterviewVO, MessageVO, VoiceCapabilityVO } from '@/api/types'
import { useVoiceAnswer } from '@/composables/voice/useVoiceAnswer'
import { useVoiceRecorder } from '@/composables/voice/useVoiceRecorder'
import { applyCapability } from '@/utils/voice/limits'
import { looksLikeAudio } from '@/utils/voice/formats'
import QuestionAudioPlayer from './QuestionAudioPlayer.vue'
import RecordingControls from './RecordingControls.vue'
import TranscriptionEditor from './TranscriptionEditor.vue'
import VoicePrivacyDialog from './VoicePrivacyDialog.vue'

const props = defineProps<{
  interview: InterviewVO
  question: MessageVO
  capability: VoiceCapabilityVO
  disabled?: boolean
  submitting?: boolean
}>()
const emit = defineEmits<{
  submit: [payload: { content: string; transcriptionId: string }]
  'switch-text': []
}>()

const limits = computed(() => applyCapability(props.capability))
const consented = ref(props.capability.consented)
const privacyVisible = ref(false)
const consentLoading = ref(false)
const text = ref('')
const player = ref<InstanceType<typeof QuestionAudioPlayer> | null>(null)
const voiceMode = ref(true)

const answer = useVoiceAnswer({
  interviewId: computed(() => props.interview.id),
  question: computed(() => props.question),
  expectedVersion: computed(() => props.interview.version),
  candidateText: text,
})
const recorder = useVoiceRecorder({
  maxDurationSeconds: computed(() => limits.value.maxDurationSeconds),
  onBeforeStart: () => {
    player.value?.stop()
  },
})

watch(
  () => props.question.id,
  async () => {
    recorder.reset()
    answer.reset()
    text.value = ''
    if (voiceMode.value) await answer.restore()
  },
  { immediate: true },
)

const transcribing = computed(() => answer.state.value === 'TRANSCRIBING')
const showEditor = computed(() => ['EDITING', 'SUBMITTING'].includes(answer.state.value) || text.value.length > 0)
const canSubmit = computed(() => text.value.trim().length > 0 && text.value.length <= 10000 && !props.disabled)

async function agree() {
  consentLoading.value = true
  try {
    await voiceInterviewApi.consent(props.interview.id, props.capability.consentVersion, true)
    consented.value = true
    privacyVisible.value = false
    await startRecording()
  } catch (reason) {
    ElMessage.error(reason instanceof Error ? reason.message : '隐私同意保存失败')
  } finally {
    consentLoading.value = false
  }
}

function reject() {
  privacyVisible.value = false
  voiceMode.value = false
  answer.toTextMode()
  emit('switch-text')
}

async function startRecording() {
  if (props.capability.consentRequired && !consented.value) {
    privacyVisible.value = true
    return
  }
  player.value?.stop()
  await recorder.start()
}

async function stopRecording() {
  await recorder.stop()
}

async function transcribe() {
  const blob = recorder.blob.value
  if (!blob) return
  if (!(await looksLikeAudio(blob))) {
    ElMessage.error('录音格式无法识别，请重新录音')
    recorder.reset()
    return
  }
  if (recorder.durationMs.value < limits.value.minDurationMs) {
    ElMessage.warning('录音时间过短，请至少录制 2 秒')
    return
  }
  await answer.transcribe(blob, recorder.mimeType.value, recorder.durationMs.value)
  if (answer.state.value === 'ANSWERABLE' && answer.error.value) {
    ElMessage.error(answer.error.value instanceof Error ? answer.error.value.message : '语音识别失败，请重试或改用文字')
  }
}

async function retryRecording() {
  await answer.discard()
  text.value = ''
  recorder.reset()
  await startRecording()
}

function submit() {
  if (!canSubmit.value) return

  emit('submit', {
    content: text.value.trim(),
    transcriptionId: voiceMode.value ? answer.transcriptionId.value : '',
  })
}

function switchToText() {
  recorder.reset()
  player.value?.stop()
  voiceMode.value = false
  answer.toTextMode()
  emit('switch-text')
}

function switchToVoice() {
  voiceMode.value = true
  answer.backToVoice()
  void answer.restore()
}

watch(
  () => answer.state.value,
  (value) => {
    if (value === 'WAITING_NEXT') {
      recorder.reset()
      text.value = ''
    }
  },
)

defineExpose({ reset: () => { recorder.reset(); answer.reset(); text.value = '' } })
</script>

<template>
  <section class="voice-panel">
    <div class="voice-panel__heading">
      <strong>语音作答</strong>
      <div class="voice-panel__modes">
        <el-button v-if="voiceMode" size="small" text @click="switchToText">切换为文字</el-button>
        <el-button v-else size="small" text @click="switchToVoice">切换回语音</el-button>
        <el-tag size="small" type="info">第 {{ question.questionNo }} 题<template v-if="question.followUpNo"> · 追问 {{ question.followUpNo }}</template></el-tag>
      </div>
    </div>

    <QuestionAudioPlayer
      v-if="voiceMode && capability.ttsEnabled"
      ref="player"
      :interview-id="interview.id"
      :message-id="question.id"
      :text="question.content"
      :disabled="disabled || recorder.recording.value"
    />

    <template v-if="voiceMode">
      <RecordingControls
        :state="recorder.state.value"
        :duration-ms="recorder.durationMs.value"
        :max-duration-seconds="limits.maxDurationSeconds"
        :audio-url="recorder.audioUrl.value"
        :reached-limit="recorder.reachedLimit.value"
        :too-short="recorder.tooShort.value"
        :transcribing="transcribing"
        :disabled="disabled || submitting"
        @start="startRecording"
        @stop="stopRecording"
        @retry="retryRecording"
        @transcribe="transcribe"
      />
      <el-alert v-if="recorder.state.value === 'denied'" class="voice-panel__error" title="麦克风权限被拒绝" description="请在浏览器地址栏的权限设置中允许麦克风后重试，或直接改用文字作答。" type="error" :closable="false" show-icon />
      <el-alert v-else-if="recorder.state.value === 'unavailable'" class="voice-panel__error" title="当前设备无法录音" description="请检查麦克风是否被其他程序占用，或改用文字作答。" type="error" :closable="false" show-icon />
      <el-alert v-if="recorder.error.value && recorder.state.value === 'idle'" class="voice-panel__error" :title="recorder.error.value instanceof Error ? recorder.error.value.message : '录音失败'" type="error" :closable="false" show-icon />
      <TranscriptionEditor
        v-if="showEditor"
        v-model="text"
        :disabled="disabled || submitting"
        :loading="submitting"
        :can-submit="canSubmit"
        @submit="submit"
        @discard="retryRecording"
      />
    </template>

    <template v-else>
      <el-input
        v-model="text"
        type="textarea"
        :rows="7"
        maxlength="10000"
        show-word-limit
        resize="vertical"
        placeholder="请输入 1–10000 个字符的回答"
        :disabled="disabled || submitting"
      />
      <div class="voice-panel__actions">
        <span class="muted">切换文字后，已识别的文本会保留在同一编辑区。</span>
        <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">提交回答</el-button>
      </div>
    </template>

    <VoicePrivacyDialog
      v-model="privacyVisible"
      :policy-version="capability.consentVersion"
      :loading="consentLoading"
      @agree="agree"
      @reject="reject"
    />
  </section>
</template>

<style scoped>
.voice-panel {
  border: 1px solid var(--ai-border-light, #ebeef5);
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
  background: var(--ai-bg-card, #fafcff);
}
.voice-panel__heading {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.voice-panel__modes {
  display: flex;
  gap: 8px;
  align-items: center;
}
.voice-panel__error {
  margin-top: 10px;
}
.voice-panel__actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}
.muted {
  color: var(--ai-text-tertiary, #909399);
  font-size: 12px;
}
@media (max-width: 700px) {
  .voice-panel {
    padding: 12px;
  }
}
</style>