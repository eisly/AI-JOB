import { translate, CANDIDATE_TYPE_LABELS } from './dictionaries'

export function buildGreeting(nickname?: string): string {
  const name = nickname?.trim()
  return name ? name : '同学'
}

export function buildStageDescription(candidateType?: string, hasProfile = false): string {
  if (!hasProfile) {
    return '完善求职画像后，我们会为你规划训练与岗位方向。'
  }
  const stage = translate(CANDIDATE_TYPE_LABELS, candidateType, '')
  if (stage === '实习') {
    return '围绕实习目标积累项目与基础能力，稳步推进求职准备。'
  }
  if (stage === '校招') {
    return '围绕校招节奏补齐能力短板，一步步完成求职准备。'
  }
  if (stage === '社招') {
    return '结合目标岗位梳理经验亮点，持续提升面试竞争力。'
  }
  return '基于你的求职目标，规划训练与实战任务，稳步提升竞争力。'
}