import { computed, onBeforeUnmount, ref, type Ref } from 'vue'
import { voiceInterviewApi } from '@/api/voiceInterviews'
import { ApiError } from '@/api/request'
import { fileExtension } from '@/utils/voice/formats'
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
  const notice = ref('')
  const lastAudio = ref<Blob | null>(null)
  const lastMime = ref('')
  const lastDuration = ref(0)
  // 统一次数令牌：切题、重录、重新发起识别都会递增；异步返回必须核对，防止旧结果覆盖新题。
  let requestToken = 0

  const busy = computed(() => state.value === 'TRANSCRIBING' || state.value === 'SUBMITTING')
  const canSubmit = computed(() => state.value === 'EDITING' && params.candidateText.value.trim().length > 0)

  function reset() {
    requestToken += 1
    state.value = 'ANSWERABLE'
    transcriptionId.value = ''
    error.value = null
    notice.value = ''
    lastAudio.value = null
    lastMime.value = ''
    lastDuration.value = 0
  }

  async function restore() {
    const interview = params.interviewId.value
    const question = params.question.value
    if (!interview || !question) return
    if (params.candidateText.value.trim() || transcriptionId.value) return
    error.value = null
    const token = ++requestToken
    for (let attempt = 0; attempt < 60; attempt += 1) {
      if (token !== requestToken) return
      let value
      try {
        value = await voiceInterviewApi.getTranscription(interview, question.id)
      } catch {
        if (token === requestToken) state.value = 'ANSWERABLE'
        return
      }
      if (token !== requestToken) return
      if (!value) {
        state.value = 'ANSWERABLE'
        return
      }
      transcriptionId.value = value.id
      if (value.status === 'PENDING') {
        state.value = 'TRANSCRIBING'
        await new Promise((resolve) => setTimeout(resolve, 1000))
        continue
      }
      if (value.status === 'SUCCEEDED' && value.text) {
        params.candidateText.value = value.text
        state.value = 'EDITING'
      } else {
        state.value = 'ANSWERABLE'
      }
      return
    }
    if (token === requestToken) {
      state.value = 'ANSWERABLE'
      error.value = new Error('语音识别超时，请重试')
    }
  }

  async function transcribe(blob: Blob, mimeType: string, durationMs: number) {
    const interview = params.interviewId.value
    const question = params.question.value
    if (!interview || !question) return
    const token = ++requestToken
    const questionId = question.id
    const stillCurrent = () => token === requestToken && params.question.value?.id === questionId
    lastAudio.value = blob
    lastMime.value = mimeType
    lastDuration.value = durationMs
    state.value = 'TRANSCRIBING'
    error.value = null
    notice.value = ''
    const form = new FormData()
    const extension = fileExtension(mimeType)
    form.append('audio', blob, `answer.${extension}`)
    form.append('questionMessageId', questionId)
    form.append('expectedVersion', String(params.expectedVersion.value))
    form.append('durationMs', String(Math.max(0, Math.round(durationMs))))
    form.append('mimeType', mimeType.split(';')[0])
    try {
      const value = await voiceInterviewApi.transcribe(interview, form)
      if (!stillCurrent()) {
        notice.value = '题目已变化，本次识别未生效'
        return
      }
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
      if (!stillCurrent()) {
        notice.value = '题目已变化，本次识别未生效'
        return
      }
      const detail = failureDetail(reason)
      if (detail.key === 'TASK_IN_PROGRESS') {
        // 同题已有进行中的识别：不重复上传，静默等待并轮询现有结果。
        notice.value = ''
        await restore()
        if (state.value === 'TRANSCRIBING' && params.candidateText.value.trim()) state.value = 'EDITING'
        return
      }
      if (detail.reason === 'SUPERSEDED') {
        notice.value = '已有更新的识别请求，本次结果未生效'
        return
      }
      if (detail.reason === 'QUESTION_CHANGED') {
        notice.value = '题目已变化，本次识别未生效'
        state.value = 'ANSWERABLE'
        return
      }
      state.value = 'ANSWERABLE'
      if (detail.reason === 'TRANSCRIPTION_EXPIRED') {
        error.value = new Error('识别结果已过期，请重新录音')
        return
      }
      if (detail.reason === 'TRANSCRIPTION_CONSUMED' || detail.reason === 'TRANSCRIPTION_DISCARDED'
        || detail.reason === 'TRANSCRIPTION_NOT_FOUND') {
        error.value = new Error('识别结果已失效，请重新录音或改用文字')
        return
      }
      error.value = reason
    }
  }

  function failureDetail(reason: unknown): { key: string; reason: string } {
    if (!(reason instanceof ApiError)) return { key: '', reason: '' }
    const value = reason.details?.reason
    return { key: reason.key, reason: typeof value === 'string' ? value : '' }
  }

  /** 失败后保留的录音可直接重试上传，不要求用户重新录。 */
  async function retryLastAudio() {
    if (!lastAudio.value) return
    await transcribe(lastAudio.value, lastMime.value, lastDuration.value)
  }

  function clearNotice() {
    notice.value = ''
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

  onBeforeUnmount(() => {
    requestToken += 1
  })

  return {
    state,
    transcriptionId,
    error,
    notice,
    lastAudio,
    lastMime,
    lastDuration,
    busy,
    canSubmit,
    reset,
    restore,
    transcribe,
    retryLastAudio,
    clearNotice,
    discard,
    toTextMode,
    backToVoice,
    markSubmitting,
    markWaiting,
    markSubmitFailed,
  }
}