import { computed, onBeforeUnmount, ref, type Ref } from 'vue'
import { pickMimeType } from '@/utils/voice/formats'

export type RecorderState = 'idle' | 'requesting' | 'recording' | 'recorded' | 'denied' | 'unavailable'

export interface RecorderOptions {
  maxDurationSeconds: Ref<number>
  onBeforeStart?: () => void | Promise<void>
}

export function useVoiceRecorder(options: RecorderOptions) {
  const state = ref<RecorderState>('idle')
  const error = ref<unknown>(null)
  const durationMs = ref(0)
  const blob = ref<Blob | null>(null)
  const mimeType = ref('')
  const audioUrl = ref('')
  const reachedLimit = ref(false)

  let stream: MediaStream | null = null
  let recorder: MediaRecorder | null = null
  let timer: number | undefined
  let startedAt = 0
  let resolveStop: ((value: Blob | null) => void) | null = null

  const recording = computed(() => state.value === 'recording')
  const recorded = computed(() => state.value === 'recorded' && !!blob.value)
  const tooShort = computed(() => recorded.value && durationMs.value < 2000)

  function cleanupTimer() {
    if (timer !== undefined) {
      window.clearInterval(timer)
      timer = undefined
    }
  }

  function releaseUrl() {
    if (audioUrl.value) {
      URL.revokeObjectURL(audioUrl.value)
      audioUrl.value = ''
    }
  }

  function releaseStream() {
    stream?.getTracks().forEach((track) => track.stop())
    stream = null
  }

  function release() {
    cleanupTimer()
    if (recorder && recorder.state !== 'inactive') {
      try {
        recorder.stop()
      } catch {
        /* recorder 已停止 */
      }
    }
    recorder = null
    releaseStream()
    resolveStop?.(null)
    resolveStop = null
  }

  function reset() {
    release()
    releaseUrl()
    blob.value = null
    durationMs.value = 0
    mimeType.value = ''
    reachedLimit.value = false
    state.value = 'idle'
    error.value = null
  }

  function classify(reason: unknown) {
    const name = reason instanceof DOMException ? reason.name : ''
    if (name === 'NotAllowedError' || name === 'SecurityError') return 'denied'
    return 'unavailable'
  }

  async function start() {
    if (state.value === 'recording' || state.value === 'requesting') return
    reset()
    await options.onBeforeStart?.()
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
      state.value = 'unavailable'
      error.value = new Error('当前浏览器不支持录音，请改用文字回答')
      return
    }
    const candidate = pickMimeType()
    if (!candidate) {
      state.value = 'unavailable'
      error.value = new Error('当前浏览器没有可用的录音格式，请改用文字回答')
      return
    }
    state.value = 'requesting'
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      recorder = new MediaRecorder(stream, {
        mimeType: candidate.mimeType,
        audioBitsPerSecond: candidate.bitsPerSecond,
      })
      mimeType.value = candidate.mimeType
      const chunks: Blob[] = []
      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) chunks.push(event.data)
      }
      recorder.onstop = () => {
        cleanupTimer()
        durationMs.value = startedAt ? Date.now() - startedAt : 0
        const value = chunks.length ? new Blob(chunks, { type: candidate.mimeType }) : null
        blob.value = value
        releaseStream()
        if (value) {
          audioUrl.value = URL.createObjectURL(value)
          state.value = 'recorded'
        } else {
          state.value = 'idle'
        }
        resolveStop?.(value)
        resolveStop = null
      }
      recorder.start()
      startedAt = Date.now()
      state.value = 'recording'
      timer = window.setInterval(() => {
        durationMs.value = Date.now() - startedAt
        if (durationMs.value >= options.maxDurationSeconds.value * 1000) {
          reachedLimit.value = true
          stop()
        }
      }, 250)
    } catch (reason) {
      state.value = classify(reason)
      error.value = reason
      releaseStream()
    }
  }

  function stop(): Promise<Blob | null> {
    if (!recorder || recorder.state === 'inactive') return Promise.resolve(blob.value)
    return new Promise((resolve) => {
      resolveStop = resolve
      recorder?.stop()
    })
  }

  function onVisibilityChange() {
    if (document.visibilityState === 'hidden') reset()
  }

  function onBeforeUnload() {
    release()
  }

  document.addEventListener('visibilitychange', onVisibilityChange)
  window.addEventListener('beforeunload', onBeforeUnload)
  onBeforeUnmount(() => {
    document.removeEventListener('visibilitychange', onVisibilityChange)
    window.removeEventListener('beforeunload', onBeforeUnload)
    reset()
  })

  return {
    state,
    error,
    durationMs,
    blob,
    mimeType,
    audioUrl,
    reachedLimit,
    tooShort,
    recording,
    recorded,
    start,
    stop,
    reset,
    release,
  }
}