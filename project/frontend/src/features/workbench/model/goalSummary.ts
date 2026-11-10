import type { GoalSummary, ProfileVO } from '@/types/workbench'
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

  const targetRoleLabel = translate(ROLE_LABELS, profile.targetRole)
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