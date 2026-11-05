import { request } from './request'

export type TargetRole = string
export type CandidateType = 'INTERNSHIP' | 'CAMPUS' | 'EXPERIENCED'
export type Education = 'HIGH_SCHOOL' | 'ASSOCIATE' | 'BACHELOR' | 'MASTER' | 'DOCTOR'

export interface SkillFact {
  skillCode: string
  claim: string
  quote: string
  location: string
  confirmed: boolean | null
}

export interface ResumeFacts {
  education: Education | null
  graduationYear: number | null
  experienceMonths: number | null
  major: string | null
  skills: SkillFact[]
  warnings: string[]
}

export interface ProfileVO {
  userId: string
  profileVersion: number
  targetRole: TargetRole | null
  candidateType: CandidateType | null
  cities: string[]
  minMonthlySalaryCents: number | null
  maxMonthlySalaryCents: number | null
  facts: ResumeFacts
  activeResumeVersionId: string | null
  updatedAt: string
}

export interface SkillVO {
  code: string
  name: string
  parentCode: string | null
  enabled: boolean
}

export interface ProfilePreferencesRequest {
  expectedProfileVersion: number
  targetRole: TargetRole
  candidateType: CandidateType
  cities: string[]
  minMonthlySalaryCents: number | null
  maxMonthlySalaryCents: number | null
}

export function getProfile(): Promise<ProfileVO> {
  return request<ProfileVO>('/v1/profile')
}

export function savePreferences(payload: ProfilePreferencesRequest): Promise<ProfileVO> {
  return request<ProfileVO>('/v1/profile/preferences', {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function listSkills(q?: string): Promise<SkillVO[]> {
  const query = q ? `?q=${encodeURIComponent(q)}` : ''
  return request<SkillVO[]>(`/v1/skills${query}`)
}