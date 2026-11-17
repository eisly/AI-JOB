import { request } from './request'
import { submitTrackedTask } from './task-registry'
import { learningApi } from './learning'
import { ApiError } from './request'
import type {
  EvaluationVO,
  LearningSessionVO,
  PracticeAnswerPayload,
  PracticeConfigVO,
  PracticeOptionVO,
  PracticeQuestionType,
  PracticeQuestionVO,
  PracticeSetResultVO,
  PracticeSetVO,
  PracticeSubmissionVO,
  QuestionVO,
} from './types'

/** 判断是否为"后端尚未实现新接口"的 404（用于降级到旧版单题会话） */
export function isEndpointMissing(reason: unknown) {
  if (!(reason instanceof ApiError)) return false
  return reason.status === 404 || reason.status === 405
}

export const practiceApi = {
  /**
   * 一次性预生成一组题目（练习集）。
   * 新接口：POST /api/v1/learning/practice-sets（异步任务，result.resourceType=PRACTICE_SET）
   */
  generateSet(config: PracticeConfigVO) {
    return submitTrackedTask('/api/v1/learning/practice-sets', config, '/training')
  },

  /** 读取练习集详情（含全部预生成题目） */
  getSet(setId: string) {
    return request<PracticeSetVO>(`/api/v1/learning/practice-sets/${setId}`)
  },

  /** 交卷：提交整组答案并触发 AI 评估（异步任务，result.resourceType=PRACTICE_EVALUATION） */
  submitSet(setId: string, body: PracticeSubmissionVO) {
    return submitTrackedTask(`/api/v1/learning/practice-sets/${setId}/submission`, body, `/training/practice/${setId}`)
  },

  /** 读取整组训练反馈报告 */
  getResult(setId: string) {
    return request<PracticeSetResultVO>(`/api/v1/learning/practice-sets/${setId}/result`)
  },
}

// ===== 降级适配：旧版单题会话 → 新版练习集结构 =====

function legacyTypeToPracticeType(type: QuestionVO['type']): PracticeQuestionType {
  return 'SUBJECTIVE'
}

function adaptLegacyQuestion(question: QuestionVO): PracticeQuestionVO {
  const stem = question.stem
  const options: PracticeOptionVO[] = parseLegacyOptions(stem)
  return {
    id: question.id,
    index: 1,
    type: options.length ? 'CHOICE' : legacyTypeToPracticeType(question.type),
    skillCodes: question.skillCodes,
    difficulty: question.difficulty,
    stem: stripLegacyOptions(stem),
    options,
    blankCount: 0,
    estimatedMinutes: question.type === 'TEXT_SCENARIO' ? 10 : 5,
    score: 10,
    questionVersion: question.questionVersion,
    sourceType: question.sourceType,
    sourceTitle: question.sourceTitle,
  }
}

/** 旧题干若以 "A. xxx" / "A、xxx" 形式带选项，则解析为结构化选项 */
function parseLegacyOptions(stem: string): PracticeOptionVO[] {
  const lines = stem.split(/\r?\n/).map(line => line.trim()).filter(Boolean)
  const options: PracticeOptionVO[] = []
  for (const line of lines) {
    const match = line.match(/^([A-Da-d])[.、．:：]\s*(.+)$/)
    if (!match) continue
    options.push({ key: match[1]!.toUpperCase(), content: match[2]!.trim() })
  }
  return options.length >= 2 ? options : []
}

function stripLegacyOptions(stem: string): string {
  const lines = stem.split(/\r?\n/)
  const kept = lines.filter(line => {
    const trimmed = line.trim()
    if (!trimmed) return true
    return !/^[A-Da-d][.、．:：]\s*.+$/.test(trimmed)
  })
  return kept.join('\n').trim()
}

/** 把旧版会话包装为"单题练习集"，保证答题页 UI 一致 */
export function adaptLegacySession(session: LearningSessionVO): PracticeSetVO {
  const question = session.question ? adaptLegacyQuestion(session.question) : null
  return {
    id: session.id,
    planId: session.planId,
    planItemId: session.planItemId,
    title: '专项训练 · 单题会话',
    status: session.status === 'PREPARING'
      ? 'GENERATING'
      : session.status === 'FAILED'
        ? 'FAILED'
        : session.status === 'ACTIVE' ? 'IN_PROGRESS' : 'EVALUATING',
    config: null,
    questions: question ? [question] : [],
    totalMinutes: question?.estimatedMinutes ?? 0,
    version: session.version,
  }
}

/** 把旧版主观题回答包装为新版作答载荷 */
export function legacyAnswerPayload(questionId: string, content: string): PracticeAnswerPayload {
  return { questionId, type: 'SUBJECTIVE', content }
}

/** 旧版提交答案（返回评估任务） */
export function legacySubmitAnswer(sessionId: string, content: string, expectedVersion: number) {
  return learningApi.submitAnswer(sessionId, { content, expectedVersion })
}

export type { PracticeSetResultVO, PracticeSetVO, PracticeSubmissionVO, PracticeConfigVO, EvaluationVO }
