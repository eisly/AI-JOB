import { computed, ref, type Ref } from 'vue'
import { voiceInterviewApi } from '@/api/voiceInterviews'

export function useQuestionSpeech(interviewId: Ref<string>) {
  const playing = ref(false)
  const paused = ref(false)
  const degraded = ref(false)
  const error = ref<unknown>(null)
  const progress = ref(0)
  let audio: HTMLAudioElement | null = null
  let currentUrl = ''
  let sequence = 0

  const active = computed(() => playing.value || paused.value)

  function releaseAudio() {
    if (audio) {
      audio.pause()
      audio.src = ''
      audio = null
    }
    if (currentUrl) {
      URL.revokeObjectURL(currentUrl)
      currentUrl = ''
    }
  }

  function stop() {
    sequence += 1
    playing.value = false
    paused.value = false
    progress.value = 0
    degraded.value = false
    if (typeof window !== 'undefined' && window.speechSynthesis) window.speechSynthesis.cancel()
    releaseAudio()
  }

  function pause() {
    if (audio && playing.value) {
      audio.pause()
      playing.value = false
      paused.value = true
      return
    }
    if (window.speechSynthesis?.speaking) {
      window.speechSynthesis.pause()
      playing.value = false
      paused.value = true
    }
  }

  function resume() {
    if (audio && paused.value) {
      void audio.play()
      playing.value = true
      paused.value = false
      return
    }
    if (window.speechSynthesis?.paused) {
      window.speechSynthesis.resume()
      playing.value = true
      paused.value = false
    }
  }

  function fallback(text: string) {
    degraded.value = true
    if (!window.speechSynthesis) {
      playing.value = false
      return
    }
    const utterance = new SpeechSynthesisUtterance(text)
    utterance.lang = 'zh-CN'
    utterance.onend = () => {
      playing.value = false
      progress.value = 0
    }
    utterance.onerror = () => {
      playing.value = false
    }
    window.speechSynthesis.cancel()
    window.speechSynthesis.speak(utterance)
    playing.value = true
  }

  async function play(messageId: string, text: string) {
    const run = ++sequence
    const id = interviewId.value
    if (!id || !messageId) return
    error.value = null
    playing.value = false
    paused.value = false
    degraded.value = false
    releaseAudio()
    if (window.speechSynthesis) window.speechSynthesis.cancel()
    try {
      const blob = await voiceInterviewApi.speech(id, messageId)
      if (run !== sequence) return
      currentUrl = URL.createObjectURL(blob)
      audio = new Audio(currentUrl)
      audio.onended = () => {
        playing.value = false
        progress.value = 0
      }
      audio.ontimeupdate = () => {
        progress.value = audio && audio.duration ? audio.currentTime / audio.duration : 0
      }
      await audio.play()
      playing.value = true
    } catch (reason) {
      if (run !== sequence) return
      error.value = reason
      fallback(text)
    }
  }

  function replay(messageId: string, text: string) {
    stop()
    return play(messageId, text)
  }

  return { playing, paused, degraded, error, progress, active, play, replay, pause, resume, stop }
}