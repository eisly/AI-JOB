import { request, requestBlob } from './request'
import type { VoiceCapabilityVO, VoiceConsentVO, VoiceTranscriptionVO } from './types'

export const voiceInterviewApi = {
  capability(interviewId: string) {
    return request<VoiceCapabilityVO>(`/api/v1/interviews/${interviewId}/voice-capability`)
  },
  transcribe(interviewId: string, form: FormData) {
    return request<VoiceTranscriptionVO>(`/api/v1/interviews/${interviewId}/voice-transcriptions`, {
      method: 'POST',
      body: form,
    })
  },
  getTranscription(interviewId: string, questionMessageId: string) {
    return request<VoiceTranscriptionVO | null>(`/api/v1/interviews/${interviewId}/voice-transcriptions`, {
      query: { questionMessageId },
    })
  },
  discard(interviewId: string, transcriptionId: string) {
    return request<boolean>(`/api/v1/interviews/${interviewId}/voice-transcriptions/${transcriptionId}/discard`, {
      method: 'POST',
      body: {},
    })
  },
  consent(interviewId: string, policyVersion: string, agreed: boolean) {
    return request<VoiceConsentVO>(`/api/v1/interviews/${interviewId}/voice-consent`, {
      method: 'POST',
      body: { policyVersion, agreed },
    })
  },
  speech(interviewId: string, messageId: string, voice?: string) {
    return requestBlob(`/api/v1/interviews/${interviewId}/messages/${messageId}/speech`, {
      method: 'POST',
      headers: voice ? { 'X-Voice-Id': voice } : undefined,
      body: {},
    })
  },
}