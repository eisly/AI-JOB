from __future__ import annotations

from datetime import datetime
from typing import Any

from .errors import validation_error
from .schemas import (
    AIEvaluateResult,
    AIGrowthResult,
    AIInterviewReportResult,
    AIInterviewTurnResult,
    AIJDResult,
    AIJobSearchResult,
    AIMatchResult,
    AIParseResult,
    AIPlanResult,
    AIProjectPlanResult,
    AIProjectRecommendResult,
    AIQuestionResult,
    AIResumeDiagnoseResult,
    AIResumeOptimizeResult,
    GrowthPayload,
    InterviewReportPayload,
    InterviewTurnPayload,
    JDParsePayload,
    JobMatchPayload,
    JobSearchPayload,
    LearningEvaluatePayload,
    LearningPlanPayload,
    LearningQuestionPayload,
    ProjectPlanPayload,
    ProjectRecommendPayload,
    ResumeDiagnosePayload,
    ResumeOptimizePayload,
    ResumeParsePayload,
)

RESUME_PARSE = "RESUME_PARSE"
RESUME_DIAGNOSE = "RESUME_DIAGNOSE"
RESUME_OPTIMIZE = "RESUME_OPTIMIZE"
JD_ANALYZE = "JD_ANALYZE"
JOB_MATCH = "JOB_MATCH"
JOB_SEARCH = "JOB_SEARCH"
LEARNING_PLAN = "LEARNING_PLAN"
LEARNING_QUESTION = "LEARNING_QUESTION"
LEARNING_EVALUATE = "LEARNING_EVALUATE"
INTERVIEW_TURN = "INTERVIEW_TURN"
INTERVIEW_REPORT = "INTERVIEW_REPORT"
GROWTH_ANALYZE = "GROWTH_ANALYZE"
PROJECT_RECOMMEND = "PROJECT_RECOMMEND"
PROJECT_PLAN = "PROJECT_PLAN"

OPERATIONS: tuple[str, ...] = (
    RESUME_PARSE,
    RESUME_DIAGNOSE,
    RESUME_OPTIMIZE,
    JD_ANALYZE,
    JOB_MATCH,
    JOB_SEARCH,
    LEARNING_PLAN,
    LEARNING_QUESTION,
    LEARNING_EVALUATE,
    INTERVIEW_TURN,
    INTERVIEW_REPORT,
    GROWTH_ANALYZE,
    PROJECT_RECOMMEND,
    PROJECT_PLAN,
)

PAYLOAD_MODELS: dict[str, type] = {
    RESUME_PARSE: ResumeParsePayload,
    RESUME_DIAGNOSE: ResumeDiagnosePayload,
    RESUME_OPTIMIZE: ResumeOptimizePayload,
    JD_ANALYZE: JDParsePayload,
    JOB_MATCH: JobMatchPayload,
    JOB_SEARCH: JobSearchPayload,
    LEARNING_PLAN: LearningPlanPayload,
    LEARNING_QUESTION: LearningQuestionPayload,
    LEARNING_EVALUATE: LearningEvaluatePayload,
    INTERVIEW_TURN: InterviewTurnPayload,
    INTERVIEW_REPORT: InterviewReportPayload,
    GROWTH_ANALYZE: GrowthPayload,
    PROJECT_RECOMMEND: ProjectRecommendPayload,
    PROJECT_PLAN: ProjectPlanPayload,
}

RESULT_MODELS: dict[str, type] = {
    RESUME_PARSE: AIParseResult,
    RESUME_DIAGNOSE: AIResumeDiagnoseResult,
    RESUME_OPTIMIZE: AIResumeOptimizeResult,
    JD_ANALYZE: AIJDResult,
    JOB_MATCH: AIMatchResult,
    JOB_SEARCH: AIJobSearchResult,
    LEARNING_PLAN: AIPlanResult,
    LEARNING_QUESTION: AIQuestionResult,
    LEARNING_EVALUATE: AIEvaluateResult,
    INTERVIEW_TURN: AIInterviewTurnResult,
    INTERVIEW_REPORT: AIInterviewReportResult,
    GROWTH_ANALYZE: AIGrowthResult,
    PROJECT_RECOMMEND: AIProjectRecommendResult,
    PROJECT_PLAN: AIProjectPlanResult,
}

PROMPT_VERSIONS: dict[str, str] = {
    RESUME_PARSE: "resume-parse-v3",
    RESUME_DIAGNOSE: "resume-diagnose-v1",
    RESUME_OPTIMIZE: "resume-optimize-v1",
    JD_ANALYZE: "jd-analyze-v3",
    JOB_MATCH: "semantic-v2",
    JOB_SEARCH: "job-search-v1",
    LEARNING_PLAN: "learning-plan-v3",
    LEARNING_QUESTION: "learning-question-v2",
    LEARNING_EVALUATE: "answer-evaluate-v3",
    INTERVIEW_TURN: "interview-turn-v5",
    INTERVIEW_REPORT: "interview-report-v2",
    GROWTH_ANALYZE: "growth-analyze-v2",
    PROJECT_RECOMMEND: "project-recommend-v1",
    PROJECT_PLAN: "project-plan-v1",
}

# Minimum inputVersions keys the Java side must freeze for each operation.
REQUIRED_VERSION_KEYS: dict[str, tuple[str, ...]] = {
    RESUME_PARSE: ("resumeVersionId",),
    RESUME_DIAGNOSE: ("resumeVersionId",),
    RESUME_OPTIMIZE: ("resumeVersionId", "jobVersion"),
    JD_ANALYZE: ("jobVersion",),
    JOB_MATCH: ("profileVersion",),
    JOB_SEARCH: ("profileVersion", "resumeVersionId"),
    LEARNING_PLAN: ("profileVersion",),
    LEARNING_QUESTION: ("profileVersion",),
    LEARNING_EVALUATE: ("questionVersion",),
    INTERVIEW_TURN: ("profileVersion", "interviewVersion"),
    INTERVIEW_REPORT: ("interviewVersion",),
    GROWTH_ANALYZE: ("profileVersion",),
    PROJECT_RECOMMEND: ("profileVersion",),
    PROJECT_PLAN: ("profileVersion",),
}


def _require_versions(operation: str, versions: dict[str, Any]) -> None:
    missing = [key for key in REQUIRED_VERSION_KEYS[operation] if key not in versions]
    if missing:
        raise validation_error(
            f"{operation} 缺少固定版本字段: {', '.join(missing)}",
            details={"fields": [{"field": "inputVersions", "message": f"missing {key}"} for key in missing]},
        )


def validate_version_binding(operation: str, versions: dict[str, Any], payload: Any) -> None:
    _require_versions(operation, versions)

    if operation in (RESUME_PARSE, RESUME_DIAGNOSE, RESUME_OPTIMIZE) and "resumeVersionId" in versions:
        if str(versions["resumeVersionId"]).lower() != str(payload.document.version_id).lower():
            raise validation_error("inputVersions.resumeVersionId 与 payload.document.versionId 不一致")
    if operation == JD_ANALYZE and "jobVersion" in versions:
        if int(versions["jobVersion"]) != payload.job_version:
            raise validation_error("inputVersions.jobVersion 与 payload.jobVersion 不一致")
    if operation == JOB_MATCH and "profileVersion" in versions:
        if int(versions["profileVersion"]) != int(payload.profile.profile_version):
            raise validation_error("inputVersions.profileVersion 与 payload.profile.profileVersion 不一致")
    if operation == JOB_SEARCH:
        if int(versions["profileVersion"]) != int(payload.profile.profile_version):
            raise validation_error("inputVersions.profileVersion 与 payload.profile.profileVersion 不一致")
        active_resume = payload.profile.active_resume_version_id
        if active_resume is not None and str(versions["resumeVersionId"]).lower() != str(active_resume).lower():
            raise validation_error("inputVersions.resumeVersionId 与 payload.profile.activeResumeVersionId 不一致")
    if operation in (LEARNING_PLAN, GROWTH_ANALYZE):
        if "profileVersion" in versions and int(versions["profileVersion"]) != int(payload.profile_version):
            raise validation_error("inputVersions.profileVersion 与 payload.profileVersion 不一致")
    # LEARNING_QUESTION 的 payload 不含 profile 快照，仅要求版本键存在（见 REQUIRED_VERSION_KEYS）。
    if operation == LEARNING_EVALUATE and "questionVersion" in versions:
        if int(versions["questionVersion"]) != payload.question.question_version:
            raise validation_error("inputVersions.questionVersion 与 payload.question.questionVersion 不一致")
    if operation == RESUME_DIAGNOSE and payload.profile_version is not None:
        if "profileVersion" not in versions:
            raise validation_error("RESUME_DIAGNOSE 缺少固定版本字段: profileVersion")
        if int(versions["profileVersion"]) != payload.profile_version:
            raise validation_error("inputVersions.profileVersion 与 payload.profileVersion 不一致")
    if operation == RESUME_OPTIMIZE and "jobVersion" in versions:
        if int(versions["jobVersion"]) != payload.job_version:
            raise validation_error("inputVersions.jobVersion 与 payload.jobVersion 不一致")
    if operation == PROJECT_RECOMMEND:
        if int(versions["profileVersion"]) != payload.profile.profile_version:
            raise validation_error("inputVersions.profileVersion 与 payload.profile.profileVersion 不一致")
        if payload.match_item is not None and "jobVersion" in versions:
            if int(versions["jobVersion"]) != payload.match_item.job_version:
                raise validation_error("inputVersions.jobVersion 与 payload.matchItem.jobVersion 不一致")
    if operation == INTERVIEW_TURN:
        if int(versions["profileVersion"]) != payload.profile.profile_version:
            raise validation_error("inputVersions.profileVersion 与 payload.profile.profileVersion 不一致")
        if int(versions["interviewVersion"]) != payload.expected_version:
            raise validation_error("inputVersions.interviewVersion 与 payload.expectedVersion 不一致")
        if payload.job is not None and "jobVersion" in versions:
            if int(versions["jobVersion"]) != payload.job.job_version:
                raise validation_error("inputVersions.jobVersion 与 payload.job.jobVersion 不一致")
