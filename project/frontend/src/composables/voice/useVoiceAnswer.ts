import { computed, ref, type Ref } from 'vue'
import { voiceInterviewApi } from '@/api/voiceInterviews'
import type { MessageVO } from '@/api/types'

export type VoiceAnswerState =
  | 'ANSWERABLE'
  | 'PLAYING'
  | 'RECORDING'
  | 'RECORDED'
  | 'TRANSCRIBING'
  | 'TRANSCRIBED'
  | 'EDITING'
  | 'SUBMITTING'
  | 'WAITING_NEXT'
  | 'TEXT_MODE'

export function useVoiceAnswer(params: {
  interviewId: Ref<string>
  question: Ref<MessageVO | null>
  expectedVersion: Ref<number>
  candidateText: Ref<string>
}) {
  const state = ref<VoiceAnswerState>('ANSWERABLE')
  const transcriptionId = ref('')
  const error = ref<unknown>(null)
  const lastAudio = ref<Blob | null>(null)
  const lastMime = ref('')
  const lastDuration = ref(0)

  const busy = computed(() => state.value === 'TRANSCRIBING' || state.value === 'SUBMITTING')
  const canSubmit = computed(() => state.value === 'EDITING' && params.candidateText.value.trim().length > 0)

  function reset() {
    state.value = 'ANSWERABLE'
    transcriptionId.value = ''
    error.value = null
    lastAudio.value = null
    lastMime.value = ''
    lastDuration.value = 0
  }

  async function restore() {
    const interview = params.interviewId.value
    const question = params.question.value
    if (!interview || !question) return
    if (params.candidateText.value.trim() || transcriptionId.value) return
    try {
      const value = await voiceInterviewApi.getTranscription(interview, question.id)
      if (!value) {
        state.value = 'ANSWERABLE'
        return
      }
      transcriptionId.value = value.id
      if (value.status === 'PENDING') {
        state.value = 'TRANSCRIBING'
      } else if (value.status === 'SUCCEEDED' && value.text) {
        params.candidateText.value = value.text
        state.value = 'EDITING'
      } else if (value.status === 'EMPTY') {
        state.value = 'ANSWERABLE'
      } else {
        state.value = 'ANSWERABLE'
      }
    } catch {
      state.value = 'ANSWERABLE'
    }
  }

  async function transcribe(blob: Blob, mimeType: string, durationMs: number) {
    const interview = params.interviewId.value
    const question = params.question.value
    if (!interview || !question) return
    lastAudio.value = blob
    lastMime.value = mimeType
    lastDuration.value = durationMs
    state.value = 'TRANSCRIBING'
    error.value = null
    const form = new FormData()
    const extension = mimeType.includes('mp4') ? 'm4a' : mimeType.includes('ogg') ? 'ogg' : 'webm'
    form.append('audio', blob, `answer.${extension}`)
    form.append('questionMessageId', question.id)
    form.append('expectedVersion', String(params.expectedVersion.value))
    form.append('durationMs', String(Math.max(0, Math.round(durationMs))))
    form.append('mimeType', mimeType.split(';')[0])
    try {
      const value = await voiceInterviewApi.transcribe(interview, form)
      transcriptionId.value = value.id
      if (value.status === 'SUCCEEDED' && value.text) {
        params.candidateText.value = value.text
        state.value = 'EDITING'
      } else if (value.status === 'EMPTY') {
        state.value = 'ANSWERABLE'
        error.value = new Error('没有识别到语音，请重新录音')
      } else {
        throw new Error('语音识别未完成，请重试')
      }
    } catch (reason) {
      state.value = 'ANSWERABLE'
      error.value = reason
    }
  }

  async function discard() {
    const interview = params.interviewId.value
    if (interview && transcriptionId.value) {
      try {
        await voiceInterviewApi.discard(interview, transcriptionId.value)
      } catch {
        /* 放弃失败不阻塞用户重录 */
      }
    }
    reset()
  }

  function toTextMode() {
    state.value = 'TEXT_MODE'
  }

  function backToVoice() {
    state.value = transcriptionId.value ? 'EDITING' : 'ANSWERABLE'
  }

  function markSubmitting() {
    state.value = 'SUBMITTING'
  }

  function markWaiting() {
    state.value = 'WAITING_NEXT'
  }

  function markSubmitFailed() {
    state.value = 'EDITING'
  }

  return {
    state,
    transcriptionId,
    error,
    lastAudio,
    lastMime,
    lastDuration,
    busy,
    canSubmit,
    reset,
    restore,
    transcribe,
    discard,
    toTextMode,
    backToVoice,
    markSubmitting,
    markWaiting,
    markSubmitFailed,
  }
}