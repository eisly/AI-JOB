import type { VoiceCapabilityVO } from '@/api/types'

export const VOICE_LIMITS = {
  maxAudioBytes: 8 * 1024 * 1024,
  maxDurationSeconds: 180,
  minDurationMs: 2000,
  transcriptionTtlSeconds: 1800,
}

export function applyCapability(capability: VoiceCapabilityVO | null | undefined) {
  if (!capability) return { ...VOICE_LIMITS }
  return {
    maxAudioBytes: capability.maxAudioBytes || VOICE_LIMITS.maxAudioBytes,
    maxDurationSeconds: capability.maxDurationSeconds || VOICE_LIMITS.maxDurationSeconds,
    minDurationMs: VOICE_LIMITS.minDurationMs,
    transcriptionTtlSeconds: capability.transcriptionTtlSeconds || VOICE_LIMITS.transcriptionTtlSeconds,
  }
}

export function secondsLabel(totalSeconds: number) {
  const seconds = Math.max(0, Math.floor(totalSeconds))
  const minutes = Math.floor(seconds / 60)
  return `${String(minutes).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}