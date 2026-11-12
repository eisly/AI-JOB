import type { GoalSummary, ProfileVO } from '@/types/workbench'
import { findJobRoleByValue } from '@/config/jobRoles'
import { EMPTY_TEXT, MAX_GOAL_CITIES } from '../constants'
import { cityLabel } from './cities'
import { CANDIDATE_TYPE_LABELS, ROLE_LABELS, translate } from './dictionaries'

export function buildGoalSummary(profile: ProfileVO | null): GoalSummary {
  if (!profile) {
    return {
      targetRoleLabel: EMPTY_TEXT,
      cityLabels: [],
      candidateTypeLabel: EMPTY_TEXT,
      isEmpty: true,
    }
  }

  const role = profile.targetRole
  const targetRoleLabel = role ? (ROLE_LABELS[role] ?? findJobRoleByValue(role)?.label ?? role) : EMPTY_TEXT
  const candidateTypeLabel = translate(CANDIDATE_TYPE_LABELS, profile.candidateType)
  const cityLabels = (profile.cities ?? [])
    .slice(0, MAX_GOAL_CITIES)
    .map((code) => cityLabel(code))

  return {
    targetRoleLabel,
    cityLabels,
    candidateTypeLabel,
    isEmpty: targetRoleLabel === EMPTY_TEXT && candidateTypeLabel === EMPTY_TEXT && cityLabels.length === 0,
  }
}
