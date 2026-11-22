import { computed, ref, type Ref } from 'vue'
import { voiceInterviewApi } from '@/api/voiceInterviews'

/**
 * 题目播报。
 *
 * - 手动播放/重播由用户点击触发；
 * - 自动播报按 messageId 去重，同一题只自动播一次，重复刷新不打断当前播放；
 * - 浏览器拦截自动播放时设置 blocked，由页面显示「点击播放题目」；
 * - 合成失败保留 error，由页面提供重试；同时降级为浏览器本地朗读，题目原文始终可读。
 */
export function useQuestionSpeech(interviewId: Ref<string>) {
  const playing = ref(false)
  const paused = ref(false)
  const degraded = ref(false)
  const blocked = ref(false)
  const error = ref<unknown>(null)
  const progress = ref(0)
  const currentTime = ref(0)
  const duration = ref(0)
  let audio: HTMLAudioElement | null = null
  let currentUrl = ''
  let sequence = 0
  const autoPlayed = new Set<string>()

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
    currentTime.value = 0
    duration.value = 0
    degraded.value = false
    blocked.value = false
    error.value = null
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

  function wasBlocked(reason: unknown) {
    const name = (reason as { name?: string } | null)?.name
    return name === 'NotAllowedError' || name === 'SecurityError'
  }

  async function play(messageId: string, text: string) {
    const run = ++sequence
    const id = interviewId.value
    if (!id || !messageId) return
    error.value = null
    blocked.value = false
    playing.value = false
    paused.value = false
    degraded.value = false
    progress.value = 0
    currentTime.value = 0
    duration.value = 0
    releaseAudio()
    if (window.speechSynthesis) window.speechSynthesis.cancel()
    try {
      const blob = await voiceInterviewApi.speech(id, messageId)
      if (run !== sequence) return
      currentUrl = URL.createObjectURL(blob)
      audio = new Audio(currentUrl)
      duration.value = Number.isFinite(audio.duration) ? audio.duration : 0
      audio.onloadedmetadata = () => {
        if (audio && Number.isFinite(audio.duration)) duration.value = audio.duration
      }
      audio.onended = () => {
        playing.value = false
        progress.value = 0
        currentTime.value = 0
      }
      audio.ontimeupdate = () => {
        if (!audio) return
        currentTime.value = audio.currentTime
        if (audio.duration) duration.value = audio.duration
        progress.value = audio.duration ? audio.currentTime / audio.duration : 0
      }
      await audio.play()
      if (run !== sequence) {
        stop()
        return
      }
      playing.value = true
    } catch (reason) {
      if (run !== sequence) return
      if (wasBlocked(reason)) {
        blocked.value = true
        playing.value = false
        // 音频已取到但被浏览器拦截时保留为暂停态，用户点击即可直接播放，无需重新合成。
        paused.value = !!audio
        return
      }
      error.value = reason
      fallback(text)
    }
  }

  function replay(messageId: string, text: string) {
    stop()
    return play(messageId, text)
  }

  /** 逐题自动播报：同一 messageId 只尝试一次，重复刷新同一题保持当前播放状态。 */
  function autoPlay(messageId: string, text: string) {
    if (!messageId || autoPlayed.has(messageId)) return
    autoPlayed.add(messageId)
    return play(messageId, text)
  }

  function forgetAutoPlay(messageId: string) {
    autoPlayed.delete(messageId)
  }

  return {
    playing,
    paused,
    degraded,
    blocked,
    error,
    progress,
    currentTime,
    duration,
    active,
    play,
    autoPlay,
    replay,
    forgetAutoPlay,
    pause,
    resume,
    stop,
  }
}