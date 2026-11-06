import { request } from './request'
import type { ProfilePreferencesInput, ProfileVO, SkillVO } from './types'
export type { ProfileVO, SkillVO } from './types'
export type CandidateType = ProfileVO['candidateType']
export type ProfilePreferencesRequest = ProfilePreferencesInput

export const profileApi = {
  get() {
    return request<ProfileVO>('/api/v1/profile')
  },
  savePreferences(body: ProfilePreferencesInput) {
    return request<ProfileVO>('/api/v1/profile/preferences', { method: 'PUT', body })
  },
  skills(q?: string) {
    return request<SkillVO[]>('/api/v1/skills', { query: { q } })
  },
}

export const getProfile = profileApi.get
export const savePreferences = profileApi.savePreferences
export const listSkills = profileApi.skills