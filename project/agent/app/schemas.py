from __future__ import annotations

from datetime import datetime
from typing import Annotated, Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator, model_validator
from pydantic.alias_generators import to_camel

Education = Literal["HIGH_SCHOOL", "ASSOCIATE", "BACHELOR", "MASTER", "DOCTOR"]
EDUCATION_ORDER: dict[str, int] = {
    "HIGH_SCHOOL": 0,
    "ASSOCIATE": 1,
    "BACHELOR": 2,
    "MASTER": 3,
    "DOCTOR": 4,
}
Difficulty = Literal["EASY", "MEDIUM", "HARD"]
PlanKind = Literal["READING", "PRACTICE", "INTERVIEW"]
PlanCategory = Literal["BASIC", "RESUME_PROJECT", "GITHUB"]
PlanEvidenceStatus = Literal["ASSESSED", "PENDING"]
GitHubLookupStatus = Literal["AVAILABLE", "UNAVAILABLE", "DISABLED"]
EvidenceStatus = Literal["MISSING", "LIMITED", "SUFFICIENT"]
ROLE_VALUES = {"USER", "ASSISTANT", "SYSTEM"}


class APIModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="ignore",
        str_strip_whitespace=True,
    )


# ---------------------------------------------------------------------------
# Shared result fragments
# ---------------------------------------------------------------------------


class SkillFact(APIModel):
    skill_code: str = Field(min_length=1, max_length=64)
    claim: str = Field(min_length=1, max_length=500)
    quote: str = Field(min_length=1, max_length=1000)
    location: str = Field(min_length=1, max_length=200)
    confirmed: bool = False


class ResumeEvidenceFact(APIModel):
    claim: str = Field(min_length=1, max_length=500)
    quote: str = Field(min_length=1, max_length=1000)
    location: str = Field(min_length=1, max_length=200)
    confirmed: bool = False


class ResumeFacts(APIModel):
    education: Education | None = None
    graduation_year: int | None = None
    experience_months: int | None = None
    major: str | None = None
    skills: list[SkillFact] = Field(default_factory=list, max_length=50)
    internships: list[ResumeEvidenceFact] = Field(default_factory=list, max_length=20)
    awards: list[ResumeEvidenceFact] = Field(default_factory=list, max_length=20)
    warnings: list[str] = Field(default_factory=list, max_length=20)


class UnmappedSkillDetail(APIModel):
    name: str = Field(min_length=1, max_length=100)
    quote: str = Field(min_length=1, max_length=1000)
    location: str = Field(min_length=1, max_length=200)


class AIParseResult(APIModel):
    facts: ResumeFacts
    text_hash: str
    unmapped_skills: list[str] = Field(default_factory=list, max_length=50)
    unmapped_details: list[UnmappedSkillDetail] = Field(default_factory=list, max_length=50)


class AIResumeOcrResult(APIModel):
    text: str = Field(min_length=1, max_length=200_000)
    text_hash: str = Field(min_length=64, max_length=64)
    text_layout_version: str = Field(default="pdf-ocr-v1", min_length=1, max_length=32)
    text_source: str = Field(default="OCR", min_length=1, max_length=16)
    native_pages: list[int] = Field(default_factory=list, max_length=100)
    ocr_pages: list[int] = Field(default_factory=list, max_length=100)
    empty_pages: list[int] = Field(default_factory=list, max_length=100)
    duration_ms: int = Field(default=0, ge=0)


class Requirement(APIModel):
    education_min: Education | None = None
    graduation_year_min: int | None = None
    graduation_year_max: int | None = None
    experience_months_min: int | None = None
    skills: list[str] = Field(default_factory=list, max_length=50)
    required_skill_levels: dict[str, int] = Field(default_factory=dict)
    quotes: dict[str, str] = Field(default_factory=dict)


class Salary(APIModel):
    min_monthly_salary_cents: int = Field(ge=0, le=100_000_000)
    max_monthly_salary_cents: int = Field(ge=0, le=100_000_000)
    currency: str = Field(default="CNY", min_length=3, max_length=3)


class AIJDResult(APIModel):
    requirements: Requirement
    salary: Salary | None = None
    warnings: list[str] = Field(default_factory=list, max_length=20)


class Citation(APIModel):
    document_id: UUID
    document_version: int = Field(ge=1)
    chunk_id: str = Field(min_length=1, max_length=128)
    quote: str = Field(min_length=1, max_length=1000)


class Gap(APIModel):
    skill_code: str = Field(min_length=1, max_length=64)
    current_score: float | None = Field(default=None, ge=0, le=100)
    target_score: float | None = Field(default=None, ge=0, le=100)
    gap: float | None = Field(default=None, ge=0, le=100)
    evidence_status: str = "MISSING"
    evidence_ids: list[UUID] = Field(default_factory=list, max_length=50)
    suggestion: str | None = Field(default=None, max_length=500)


SkillSupportLevel = Literal["DIRECT", "PARTIAL", "MISSING"]


class RequirementEvidenceItem(APIModel):
    requirement_quote: str = Field(min_length=1, max_length=2000)
    requirement_title: str | None = Field(default=None, max_length=60)
    skill_code: str | None = Field(default=None, max_length=64)
    support_level: SkillSupportLevel = "MISSING"
    resume_quote: str | None = Field(default=None, max_length=2000)
    gap_title: str | None = Field(default=None, max_length=60)
    gap_summary: str | None = Field(default=None, max_length=400)


class AIMatchItem(APIModel):
    job_id: UUID
    job_version: int = Field(ge=1)
    semantic_score: float | None = Field(default=None, ge=0, le=1)
    semantic_version: str = "semantic-v1"
    reasons: list[str] = Field(default_factory=list, max_length=5)
    gaps: list[Gap] = Field(default_factory=list, max_length=50)
    requirement_evidence: list[RequirementEvidenceItem] = Field(default_factory=list, max_length=12)


class AIMatchResult(APIModel):
    items: list[AIMatchItem] = Field(default_factory=list, max_length=50)


class GitHubCandidate(APIModel):
    full_name: str = Field(min_length=1, max_length=200)
    html_url: str = Field(min_length=1, max_length=2048)
    description: str = Field(default="", max_length=1000)
    stargazers_count: int = Field(default=0, ge=0)
    language: str | None = Field(default=None, max_length=64)
    pushed_at: datetime | None = None
    topics: list[str] = Field(default_factory=list, max_length=20)
    source: Literal["GITHUB_API"] = "GITHUB_API"
    verified_at: datetime


class GitHubLookup(APIModel):
    status: GitHubLookupStatus = "UNAVAILABLE"
    fetched_at: datetime | None = None
    reason: str | None = Field(default=None, max_length=500)
    candidates: list[GitHubCandidate] = Field(default_factory=list, max_length=10)


class ResourceLink(APIModel):
    label: str = Field(min_length=1, max_length=120)
    url: str = Field(min_length=1, max_length=2048)


class PlanItem(APIModel):
    skill_code: str = Field(min_length=1, max_length=64)
    title: str = Field(min_length=1, max_length=200)
    kind: PlanKind
    category: PlanCategory = "BASIC"
    objective: str = Field(min_length=1, max_length=500)
    reason: str = Field(min_length=1, max_length=500)
    method: str = Field(min_length=1, max_length=500)
    completion_criteria: str = Field(min_length=1, max_length=500)
    evidence_status: PlanEvidenceStatus = "ASSESSED"
    evidence_quote: str | None = Field(default=None, max_length=500)
    resource_links: list[ResourceLink] = Field(default_factory=list, max_length=5)
    estimated_minutes: int = Field(ge=5, le=120)
    resource_refs: list[Citation] = Field(default_factory=list, max_length=10)


class AIPlanResult(APIModel):
    title: str = Field(min_length=1, max_length=200)
    summary: str = Field(default="", max_length=500)
    items: list[PlanItem] = Field(min_length=1, max_length=20)
    github: GitHubLookup = Field(default_factory=GitHubLookup)


class RubricDimension(APIModel):
    key: str = Field(min_length=1, max_length=64)
    weight: float = Field(ge=0, le=1)
    description: str | None = Field(default=None, max_length=500)


class Rubric(APIModel):
    version: str = Field(min_length=1, max_length=64)
    dimensions: list[RubricDimension] = Field(min_length=1, max_length=10)


class AIQuestionResult(APIModel):
    stem: str = Field(min_length=1, max_length=3000)
    skill_codes: list[str] = Field(min_length=1, max_length=10)
    difficulty: Difficulty
    type: str = Field(default="TEXT_SCENARIO", min_length=1, max_length=48)
    source_type: Literal["CURATED", "GENERATED"] = "GENERATED"
    source_question_id: UUID | None = None
    reference_answer: str = Field(min_length=1, max_length=5000)
    rubric: Rubric
    citations: list[Citation] = Field(default_factory=list, max_length=10)
    # 生成/复用该题的模型提供方（如 fake-llm / deepseek），Java 落库时据此标记 generation_env 隔离 fake 题
    model_provider: str = Field(default="", max_length=64)


class EvaluationDimension(APIModel):
    key: str = Field(min_length=1, max_length=64)
    score: float = Field(ge=0, le=100)
    weight: float = Field(ge=0, le=1)
    comment: str = Field(default="", max_length=500)


class EvaluationVO(APIModel):
    overall_score: float | None = Field(default=None, ge=0, le=100)
    dimensions: list[EvaluationDimension] = Field(default_factory=list, max_length=10)
    strengths: list[str] = Field(default_factory=list, max_length=10)
    improvements: list[str] = Field(default_factory=list, max_length=10)
    reference_answer: str = Field(default="", max_length=5000)
    rubric_version: str = Field(min_length=1, max_length=64)
    model_version: str = Field(min_length=1, max_length=128)
    prompt_version: str = Field(min_length=1, max_length=64)
    evidence_status: str = "VALID"
    # 回答分类：UNKNOWN=用户明确回答"不会"；INVALID=回答无效（过短/乱输入）；None=正常作答。
    # "不会"不伪造分数，但 Java 会写入错题本（cause=UNKNOWN）进入待复习路径。
    answer_class: str | None = Field(default=None, max_length=16)


class SkillScore(APIModel):
    skill_code: str = Field(min_length=1, max_length=64)
    score: float = Field(ge=0, le=100)
    confidence: float = Field(ge=0, le=1)
    source_id: UUID


class AIEvaluateResult(APIModel):
    evaluation: EvaluationVO
    skill_scores: list[SkillScore] = Field(default_factory=list, max_length=20)


class AIGrowthResult(APIModel):
    suggestions: list[str] = Field(min_length=1, max_length=5)
    cited_evidence_ids: list[UUID] = Field(default_factory=list, max_length=20)


class TurnQuestion(APIModel):
    content: str = Field(min_length=1, max_length=3000)
    question_no: int = Field(ge=1, le=50)
    follow_up_no: int = Field(ge=0, le=2)
    skill_codes: list[str] = Field(default_factory=list, max_length=10)


class AIInterviewTurnResult(APIModel):
    action: Literal["ASK", "FOLLOW_UP", "FINISH"]
    question: TurnQuestion | None = None
    answer_evaluation: AIEvaluateResult | None = None
    stop_reason: str | None = None


class AIInterviewReportResult(APIModel):
    evaluation: EvaluationVO
    gaps: list[Gap] = Field(default_factory=list, max_length=20)
    answered_question_count: int = Field(ge=0)
    reviewed_answer_count: int = Field(ge=0)
    completion_type: str


ResumeDiagnosisPriority = Literal["IMPORTANT", "SUGGESTION", "NOTICE"]
ProjectDifficulty = Literal["BEGINNER", "INTERMEDIATE", "ADVANCED"]
ResumeAssessmentDimensionCode = Literal[
    "EXPERIENCE_PROJECTS",
    "SKILLS_EVIDENCE",
    "CONTENT_COMPLETENESS",
    "STRUCTURE_CLARITY",
    "EXPRESSION_PROFESSIONALISM",
]


class ResumeDiagnosisItem(APIModel):
    priority: ResumeDiagnosisPriority
    dimension_code: ResumeAssessmentDimensionCode | None = None
    check_code: str | None = Field(default=None, min_length=1, max_length=64)
    title: str = Field(min_length=1, max_length=200)
    issue: str = Field(min_length=1, max_length=2000)
    quote: str = Field(min_length=1, max_length=1000)
    quotes: list[str] = Field(default_factory=list, max_length=2)
    rationale: str = Field(min_length=1, max_length=2000)
    impact: str | None = Field(default=None, max_length=1000)
    suggestion: str = Field(min_length=1, max_length=2000)
    example: str | None = Field(default=None, max_length=2000)
    pending_verification: list[str] = Field(default_factory=list, max_length=5)


RESUME_ASSESSMENT_RULE_VERSION = "resume-quality-v2"
RESUME_ASSESSMENT_RUBRIC_VERSION = "resume-quality-v3"
RESUME_ASSESSMENT_SUPPORTED_RULE_VERSIONS = frozenset({
    "resume-quality-v1", "resume-quality-v2", RESUME_ASSESSMENT_RUBRIC_VERSION,
})


class ResumeAssessmentDimension(APIModel):
    code: ResumeAssessmentDimensionCode
    score: int | None = Field(default=None, ge=0)
    levels: dict[str, Annotated[int, Field(strict=True, ge=0, le=5)]] = Field(default_factory=dict)
    rationale: str = Field(min_length=1, max_length=2000)
    quote: str | None = Field(default=None, max_length=1000)
    quotes: list[str] = Field(default_factory=list, max_length=2)


class ResumeAssessmentStrength(APIModel):
    title: str = Field(min_length=1, max_length=200)
    quote: str = Field(min_length=1, max_length=1000)
    quotes: list[str] = Field(default_factory=list, max_length=2)
    description: str = Field(min_length=1, max_length=2000)
    approach: str | None = Field(default=None, max_length=1000)
    contribution: str | None = Field(default=None, max_length=1000)
    result: str | None = Field(default=None, max_length=1000)


class ResumeAssessment(APIModel):
    rule_version: str = Field(min_length=1, max_length=64)
    summary: str | None = Field(default=None, max_length=600)
    dimensions: list[ResumeAssessmentDimension] = Field(min_length=5, max_length=5)
    strengths: list[ResumeAssessmentStrength] = Field(default_factory=list, max_length=10)


class ResumeDiagnosisInput(APIModel):
    items: list[ResumeDiagnosisItem] = Field(max_length=20)
    assessment: ResumeAssessment | None = None


class AIResumeDiagnoseResult(ResumeDiagnosisInput):
    @model_validator(mode="after")
    def _requires_legacy_items(self) -> "AIResumeDiagnoseResult":
        if not self.items and (
            self.assessment is None or self.assessment.rule_version != RESUME_ASSESSMENT_RUBRIC_VERSION
        ):
            raise ValueError("旧版诊断必须包含至少一条建议")
        return self


ResumeOptimizeMode = Literal["SECTIONS", "FULL_DOCUMENT"]


class ResumeOptimizeSection(APIModel):
    section_key: str = Field(min_length=1, max_length=64)
    title: str = Field(min_length=1, max_length=200)
    original_text: str = Field(min_length=1, max_length=10000)
    suggested_text: str = Field(min_length=1, max_length=10000)
    source_quotes: list[str] = Field(default_factory=list, max_length=10)
    jd_quote: str | None = Field(default=None, max_length=2000)
    reason: str | None = Field(default=None, max_length=2000)


class ResumeOptimizeRequirementGap(APIModel):
    requirement: str = Field(min_length=1, max_length=2000)
    note: str | None = Field(default=None, max_length=2000)


class AIResumeOptimizeResult(APIModel):
    title: str = Field(min_length=1, max_length=200)
    sections: list[ResumeOptimizeSection] = Field(max_length=20)
    missing_requirements: list[ResumeOptimizeRequirementGap] = Field(default_factory=list, max_length=5)


RepoPlatform = Literal["GITHUB", "GITEE", "GITCODE"]
RepoSourceStatus = Literal["SUCCEEDED", "FAILED"]


class RepositorySource(APIModel):
    platform: RepoPlatform
    path: str = Field(min_length=1, max_length=300)
    url: str = Field(min_length=1, max_length=2048)
    license: str = Field(min_length=1, max_length=64)
    readme_url: str | None = Field(default=None, max_length=2048)


class RepositorySourceRun(APIModel):
    platform: RepoPlatform
    status: RepoSourceStatus
    reason: str | None = Field(default=None, max_length=500)


class ProjectStackEvidence(APIModel):
    keyword: str = Field(min_length=1, max_length=64)
    status: Literal["VERIFIED", "DOCUMENTED"]
    path: str = Field(min_length=1, max_length=240)
    url: str = Field(min_length=1, max_length=2048)


class ProjectRecommendationItem(APIModel):
    title: str = Field(min_length=1, max_length=200)
    summary: str = Field(min_length=1, max_length=2000)
    skill_codes: list[str] = Field(min_length=1, max_length=10)
    difficulty: ProjectDifficulty
    estimated_weeks: int = Field(ge=1, le=12)
    reason: str = Field(min_length=1, max_length=2000)
    getting_started: list[str] = Field(default_factory=list, max_length=5)
    deliverables: list[str] = Field(min_length=1, max_length=10)
    primary_source: RepositorySource | None = None
    alternate_sources: list[RepositorySource] = Field(default_factory=list, max_length=5)
    stack_evidence: list[ProjectStackEvidence] = Field(default_factory=list, max_length=5)


class ProjectRecommendSuggestion(APIModel):
    """模型只对检索层返回的真实仓库补充学习建议，不得自行生成仓库或链接。"""

    source_url: str = Field(min_length=1, max_length=2048)
    skill_codes: list[str] = Field(min_length=1, max_length=10)
    difficulty: ProjectDifficulty
    estimated_weeks: int = Field(ge=1, le=12)
    reason: str = Field(min_length=1, max_length=2000)
    getting_started: list[str] = Field(min_length=1, max_length=5)
    deliverables: list[str] = Field(min_length=1, max_length=10)


class ProjectRecommendModelOutput(APIModel):
    items: list[ProjectRecommendSuggestion] = Field(default_factory=list, max_length=6)


class AIProjectRecommendResult(APIModel):
    mode: Literal["REPOSITORY", "LEGACY_IDEA"] = "REPOSITORY"
    source_runs: list[RepositorySourceRun] = Field(default_factory=list, max_length=3)
    items: list[ProjectRecommendationItem] = Field(default_factory=list, max_length=24)
    search_cursor: dict[str, int] | None = None
    discovered_keys: list[str] = Field(default_factory=list, max_length=72)


class ProjectPlanTask(APIModel):
    title: str = Field(min_length=1, max_length=200)
    description: str = Field(min_length=1, max_length=2000)
    estimated_hours: int = Field(ge=1, le=40)


class ProjectPlanMilestone(APIModel):
    seq: int = Field(ge=1, le=8)
    title: str = Field(min_length=1, max_length=200)
    description: str = Field(min_length=1, max_length=2000)
    tasks: list[ProjectPlanTask] = Field(min_length=1, max_length=10)


class AIProjectPlanResult(APIModel):
    title: str = Field(min_length=1, max_length=200)
    summary: str = Field(min_length=1, max_length=2000)
    milestones: list[ProjectPlanMilestone] = Field(min_length=1, max_length=8)


# ---------------------------------------------------------------------------
# LLM output models (subset of the public result, assembled server-side)
# ---------------------------------------------------------------------------


class ParseModelOutput(APIModel):
    facts: ResumeFacts
    unmapped_skills: list[str] = Field(default_factory=list, max_length=50)

    @field_validator("facts", mode="before")
    @classmethod
    def discard_malformed_evidence(cls, value: Any) -> Any:
        if not isinstance(value, dict):
            return value
        facts = dict(value)
        for key, model in (("skills", SkillFact), ("internships", ResumeEvidenceFact), ("awards", ResumeEvidenceFact)):
            rows = facts.get(key)
            if isinstance(rows, list):
                valid = []
                for row in rows:
                    try:
                        valid.append(model.model_validate(row))
                    except ValidationError:
                        continue
                facts[key] = valid
        return facts


class MatchItemModelOutput(APIModel):
    job_id: UUID
    job_version: int = Field(ge=1)
    semantic_score: float | None = Field(default=None, ge=0, le=1)
    reasons: list[str] = Field(default_factory=list, max_length=5)
    gap_suggestions: dict[str, str] = Field(default_factory=dict)
    requirement_evidence: list[RequirementEvidenceItem] = Field(default_factory=list, max_length=12)


class MatchModelOutput(APIModel):
    items: list[MatchItemModelOutput] = Field(min_length=0, max_length=50)


class EvaluationModelDimension(EvaluationDimension):
    score: float | None = Field(default=None, ge=0, le=100)


class EvaluationModelOutput(APIModel):
    overall_score: float | None = Field(default=None, ge=0, le=100)
    dimensions: list[EvaluationDimension | EvaluationModelDimension] = Field(default_factory=list, max_length=10)
    strengths: list[str] = Field(default_factory=list, max_length=10)
    improvements: list[str] = Field(default_factory=list, max_length=10)
    reference_answer: str = Field(default="", max_length=5000)
    evidence_status: str = "VALID"

    @field_validator("reference_answer", mode="before")
    @classmethod
    def normalize_missing_reference_answer(cls, value: Any) -> Any:
        return "" if value is None else value


class SkillScoreDraft(APIModel):
    skill_code: str = Field(min_length=1, max_length=64)
    score: float = Field(ge=0, le=100)
    confidence: float = Field(ge=0, le=1)


class EvaluateModelOutput(APIModel):
    evaluation: EvaluationModelOutput
    skill_scores: list[SkillScoreDraft] = Field(default_factory=list, max_length=20)


class TurnQuestionModelOutput(APIModel):
    content: str = Field(min_length=1, max_length=3000)
    question_no: int = Field(default=0, ge=0, le=50)
    follow_up_no: int = Field(default=0, ge=0, le=2)
    skill_codes: list[str] = Field(default_factory=list, max_length=10)


class TurnModelOutput(APIModel):
    answer_evaluation: EvaluateModelOutput | None = None
    needs_follow_up: bool = False
    follow_up_question: TurnQuestionModelOutput | None = None
    next_question: TurnQuestionModelOutput | None = None


class ReportAnswerScores(APIModel):
    correctness: float = Field(ge=0, le=100)
    completeness: float = Field(ge=0, le=100)
    expression: float = Field(ge=0, le=100)


class ReportModelOutput(APIModel):
    unscored_answer_scores: dict[UUID, ReportAnswerScores] = Field(default_factory=dict)
    strengths: list[str] = Field(default_factory=list, max_length=10)
    improvements: list[str] = Field(default_factory=list, max_length=10)
    reference_answer: str = Field(default="", max_length=5000)
    gap_suggestions: dict[str, str] = Field(default_factory=dict)


# ---------------------------------------------------------------------------
# Request envelope and payloads
# ---------------------------------------------------------------------------


class AIRequestEnvelope(APIModel):
    task_id: UUID
    user_id: UUID
    input_versions: dict[str, Any]
    deadline_at: datetime
    payload: dict[str, Any]

    @field_validator("deadline_at")
    @classmethod
    def _deadline_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.tzinfo.utcoffset(value) is None:
            raise ValueError("deadlineAt must be an ISO-8601 UTC datetime with timezone")
        return value

    @field_validator("input_versions")
    @classmethod
    def _versions(cls, value: dict[str, Any]) -> dict[str, Any]:
        if not value:
            raise ValueError("inputVersions must not be empty")
        for key, item in value.items():
            if not isinstance(key, str) or not key:
                raise ValueError("inputVersions keys must be non-empty strings")
            if isinstance(item, bool) or not isinstance(item, (int, str)):
                raise ValueError(f"inputVersions.{key} must be an integer or string")
            if isinstance(item, int) and item < 1:
                raise ValueError(f"inputVersions.{key} must be >= 1")
        return value


class SkillCatalogItem(APIModel):
    code: str = Field(min_length=1, max_length=64)
    name: str = Field(min_length=1, max_length=128)
    aliases: list[str] = Field(default_factory=list, max_length=20)


class DocumentRef(APIModel):
    version_id: UUID
    file_name: str = Field(min_length=1, max_length=255)
    mime_type: str = Field(min_length=1, max_length=128)
    size_bytes: int = Field(ge=0, le=100 * 1024 * 1024)
    download_url: str | None = Field(default=None, max_length=2048)
    text: str | None = Field(default=None, max_length=200_000)
    text_layout_version: str | None = Field(default=None, max_length=32)
    read_order_verified: bool | None = None

    @model_validator(mode="after")
    def _has_source(self) -> "DocumentRef":
        if not self.download_url and self.text is None:
            raise ValueError("document must provide downloadUrl or text")
        return self


class ResumeParsePayload(APIModel):
    document: DocumentRef
    skill_catalog: list[SkillCatalogItem] = Field(default_factory=list, max_length=500)


class ResumeOcrPayload(APIModel):
    document: DocumentRef


class JDIdPayload(APIModel):
    job_id: UUID


class JDParsePayload(APIModel):
    job_id: UUID
    job_version: int = Field(ge=1)
    jd_text: str = Field(min_length=1, max_length=30000)
    skill_catalog: list[SkillCatalogItem] = Field(default_factory=list, max_length=500)

    @field_validator("jd_text")
    @classmethod
    def _jd_length(cls, value: str) -> str:
        stripped = value.strip()
        if len(stripped) < 50 or len(stripped) > 30000:
            raise ValueError("jdText must contain 50..30000 characters")
        return stripped


class SkillSnapshotItem(APIModel):
    skill_code: str = Field(min_length=1, max_length=64)
    score: float | None = Field(default=None, ge=0, le=100)
    sample_count: int = Field(default=0, ge=0, le=10000)


class ProfileFacts(ResumeFacts):
    pass


class ProfileSnapshot(APIModel):
    user_id: UUID
    profile_version: int = Field(ge=1)
    target_role: str | None = Field(default=None, max_length=64)
    candidate_type: str | None = Field(default=None, max_length=32)
    cities: list[str] = Field(default_factory=list, max_length=20)
    min_monthly_salary_cents: int | None = Field(default=None, ge=0)
    max_monthly_salary_cents: int | None = Field(default=None, ge=0)
    facts: ProfileFacts = Field(default_factory=ProfileFacts)
    active_resume_version_id: UUID | None = None
    updated_at: datetime | None = None
    warnings: list[str] = Field(default_factory=list, max_length=20)


class JobMatchCandidate(APIModel):
    job_id: UUID
    job_version: int = Field(ge=1)
    title: str = Field(min_length=1, max_length=200)
    jd_text: str = Field(default="", max_length=30000)
    requirements: Requirement = Field(default_factory=Requirement)
    personal_owned: bool = False


class JobMatchPayload(APIModel):
    profile: ProfileSnapshot
    skill_snapshot: list[SkillSnapshotItem] = Field(default_factory=list, max_length=100)
    candidates: list[JobMatchCandidate] = Field(min_length=1, max_length=50)
    resume_quotes: list[str] = Field(default_factory=list, max_length=90)
    resume_text: str | None = Field(default=None, min_length=1, max_length=30000)


class MatchItemInput(APIModel):
    id: UUID
    job_id: UUID
    job_version: int = Field(ge=1)
    title: str = Field(min_length=1, max_length=200)
    eligibility: dict[str, Any] | None = None
    match_score: int | None = Field(default=None, ge=0, le=100)
    score_version: str | None = None
    components: list[dict[str, Any]] = Field(default_factory=list)
    gaps: list[Gap] = Field(default_factory=list, max_length=50)
    reasons: list[str] = Field(default_factory=list, max_length=20)
    source: dict[str, Any] | None = None
    source_url: str | None = Field(default=None, max_length=2048)
    verified_at: datetime | None = None


class ResumeSkillInput(APIModel):
    skill_code: str = Field(min_length=1, max_length=64)
    claim: str = Field(default="", max_length=500)
    quote: str = Field(default="", max_length=1000)
    location: str = Field(default="", max_length=200)


class ResumeSnapshotInput(APIModel):
    version_id: UUID
    version_no: int = Field(ge=1)
    title: str = Field(min_length=1, max_length=200)
    target_direction: str | None = Field(default=None, max_length=32)
    education: Education | None = None
    experience_months: int | None = Field(default=None, ge=0, le=600)
    major: str | None = Field(default=None, max_length=200)
    skills: list[ResumeSkillInput] = Field(default_factory=list, max_length=50)
    projects_text: str = Field(default="", max_length=12000)


class TrainingHistoryInput(APIModel):
    completed_items: int = Field(default=0, ge=0)
    evaluated_answers: int = Field(default=0, ge=0)
    average_score: float | None = Field(default=None, ge=0, le=100)
    open_mistakes: int = Field(default=0, ge=0)
    weak_skill_codes: list[str] = Field(default_factory=list, max_length=20)



class PlanItemSpec(APIModel):
    skill_code: str = Field(min_length=1, max_length=64)
    difficulty: Difficulty = "MEDIUM"
    title: str | None = Field(default=None, max_length=200)
    objective: str | None = Field(default=None, max_length=2000)
    reason: str | None = Field(default=None, max_length=2000)
    completion_criteria: str | None = Field(default=None, max_length=2000)


class LearningPlanPayload(APIModel):
    plan_source: Literal["MATCH", "RESUME"] = "MATCH"
    match_item: MatchItemInput | None = None
    resume: ResumeSnapshotInput | None = None
    target_direction: str | None = Field(default=None, max_length=32)
    training_history: TrainingHistoryInput | None = None
    limitations: list[str] = Field(default_factory=list, max_length=10)
    profile_version: int = Field(ge=1)
    daily_minutes: int = Field(ge=5, le=480)
    days: int = Field(ge=1, le=30)
    allowed_skill_codes: list[str] = Field(min_length=1, max_length=20)
    knowledge_refs: list[Citation] = Field(default_factory=list, max_length=50)

    @field_validator("allowed_skill_codes")
    @classmethod
    def _unique_skills(cls, value: list[str]) -> list[str]:
        cleaned = []
        for item in value:
            if item not in cleaned:
                cleaned.append(item)
        return cleaned

    @model_validator(mode="after")
    def _required_source(self) -> "LearningPlanPayload":
        if self.plan_source == "MATCH" and self.match_item is None:
            raise ValueError("MATCH 来源必须提供 matchItem")
        if self.plan_source == "RESUME" and self.resume is None:
            raise ValueError("RESUME 来源必须提供 resume")
        return self


class QuestionCandidate(APIModel):
    id: UUID
    version: int = Field(ge=1)
    stem: str = Field(min_length=1, max_length=3000)
    skill_codes: list[str] = Field(min_length=1, max_length=10)
    reference_answer: str = Field(min_length=1, max_length=5000)
    rubric: Rubric
    difficulty: Difficulty = "MEDIUM"
    type: str = Field(default="TEXT_SCENARIO", min_length=1, max_length=48)


class LearningQuestionPayload(APIModel):
    session_id: UUID
    plan_item: PlanItemSpec
    candidate_questions: list[QuestionCandidate] = Field(default_factory=list, max_length=50)
    excluded_question_ids: list[UUID] = Field(default_factory=list, max_length=100)
    # 已用题的题干列表（与 excluded_question_ids 对应）：选题与生成新题时按归一化题干排除等价重复
    excluded_question_stems: list[str] = Field(default_factory=list, max_length=100)
    knowledge_refs: list[Citation] = Field(default_factory=list, max_length=50)
    mode: Literal["SELECT_OR_GENERATE", "REUSE_ONLY"] = "SELECT_OR_GENERATE"

    @model_validator(mode="after")
    def _reuse_only_single_candidate(self) -> "LearningQuestionPayload":
        if self.mode == "REUSE_ONLY" and len(self.candidate_questions) != 1:
            raise ValueError("REUSE_ONLY 必须提供且只提供一个原题候选")
        return self


class EvaluateQuestion(APIModel):
    id: UUID
    question_version: int = Field(ge=1)
    stem: str = Field(min_length=1, max_length=3000)
    skill_codes: list[str] = Field(min_length=1, max_length=10)
    reference_answer: str = Field(min_length=1, max_length=5000)
    rubric: Rubric


class LearningEvaluatePayload(APIModel):
    answer_id: UUID
    question: EvaluateQuestion
    answer: str = Field(min_length=1, max_length=10000)

    @field_validator("answer")
    @classmethod
    def _answer(cls, value: str) -> str:
        stripped = value.strip()
        if len(stripped) < 1 or len(stripped) > 10000:
            raise ValueError("answer must contain 1..10000 characters")
        return stripped

    @model_validator(mode="after")
    def _fixed_rubric(self) -> "LearningEvaluatePayload":
        expected = {"correctness": 0.5, "completeness": 0.3, "expression": 0.2}
        keys = [item.key for item in self.question.rubric.dimensions]
        if len(keys) != 3 or set(keys) != set(expected):
            raise ValueError("rubric 必须包含 correctness/completeness/expression")
        for item in self.question.rubric.dimensions:
            if abs(item.weight - expected[item.key]) > 1e-6:
                raise ValueError(f"rubric.{item.key} 权重必须为 {expected[item.key]}")
        if abs(sum(item.weight for item in self.question.rubric.dimensions) - 1.0) > 1e-6:
            raise ValueError("rubric 权重之和必须为 1")
        return self


# ---------------------------------------------------------------------------
# 批量出题（一次生成一组题目）与整组评估
# ---------------------------------------------------------------------------

PracticeQuestionType = Literal["CHOICE", "FILL", "SUBJECTIVE"]


class PracticeTypeDistribution(APIModel):
    choice: int = Field(default=0, ge=0, le=20)
    fill: int = Field(default=0, ge=0, le=20)
    subjective: int = Field(default=0, ge=0, le=20)


class PracticeConfig(APIModel):
    total_questions: int = Field(ge=1, le=20)
    type_distribution: PracticeTypeDistribution
    difficulty: Literal["EASY", "MEDIUM", "HARD", "MIXED"] = "MEDIUM"
    direction: str = Field(default="", max_length=32)

    @model_validator(mode="after")
    def _distribution_matches_total(self) -> "PracticeConfig":
        dist = self.type_distribution
        if dist.choice + dist.fill + dist.subjective != self.total_questions:
            raise ValueError("题型分布之和必须等于题目总数")
        return self


class PracticeOption(APIModel):
    key: str = Field(min_length=1, max_length=2)
    content: str = Field(min_length=1, max_length=500)


class AIPracticeQuestion(APIModel):
    stem: str = Field(min_length=10, max_length=3000)
    type: PracticeQuestionType
    skill_codes: list[str] = Field(min_length=1, max_length=10)
    difficulty: Difficulty = "MEDIUM"
    options: list[PracticeOption] = Field(default_factory=list, max_length=8)
    answer_key: list[str] = Field(default_factory=list, max_length=10)
    blank_count: int = Field(default=0, ge=0, le=10)
    score: int = Field(default=10, ge=1, le=100)
    reference_answer: str = Field(min_length=1, max_length=5000)
    rubric: Rubric
    citations: list[Citation] = Field(default_factory=list, max_length=10)


class AIPracticeQuestionSet(APIModel):
    questions: list[AIPracticeQuestion] = Field(min_length=1, max_length=20)


class LearningPracticeQuestionsPayload(APIModel):
    """一次生成一组题目的输入。"""

    session_id: UUID
    plan_item: PlanItemSpec
    config: PracticeConfig
    # 复用旧题库时提供的单选候选（按 objective 相关性筛选），批量生成允许为空
    candidate_questions: list[QuestionCandidate] = Field(default_factory=list, max_length=50)
    excluded_question_ids: list[UUID] = Field(default_factory=list, max_length=100)
    excluded_question_stems: list[str] = Field(default_factory=list, max_length=100)
    knowledge_refs: list[Citation] = Field(default_factory=list, max_length=50)


class PracticeSubjectiveAnswer(APIModel):
    answer_id: UUID
    question_id: UUID
    question_version: int = Field(ge=1)
    stem: str = Field(min_length=1, max_length=3000)
    skill_codes: list[str] = Field(min_length=1, max_length=10)
    reference_answer: str = Field(min_length=1, max_length=5000)
    rubric: Rubric
    answer: str = Field(min_length=1, max_length=10000)

    @model_validator(mode="after")
    def _fixed_rubric(self) -> "PracticeSubjectiveAnswer":
        expected = {"correctness": 0.5, "completeness": 0.3, "expression": 0.2}
        keys = [item.key for item in self.rubric.dimensions]
        if len(keys) != 3 or set(keys) != set(expected):
            raise ValueError("rubric 必须包含 correctness/completeness/expression")
        for item in self.rubric.dimensions:
            if abs(item.weight - expected[item.key]) > 1e-6:
                raise ValueError(f"rubric.{item.key} 权重必须为 {expected[item.key]}")
        return self


class LearningPracticeEvaluatePayload(APIModel):
    """整组交卷：仅主观题需要 LLM 评估，客观题由 Java 自动判分。

    全客观题训练组会传入空 answers，此时只需返回空 evaluations 与整组报告。
    """

    session_id: UUID
    answers: list[PracticeSubjectiveAnswer] = Field(default_factory=list, max_length=20)


class PracticeEvaluationItem(APIModel):
    answer_id: UUID
    evaluation: EvaluationVO
    skill_scores: list[SkillScoreDraft] = Field(default_factory=list, max_length=20)


class PracticeReport(APIModel):
    summary: str = Field(default="", max_length=2000)
    strengths: list[str] = Field(default_factory=list, max_length=10)
    improvements: list[str] = Field(default_factory=list, max_length=10)


class PracticeEvaluationModelItem(APIModel):
    """模型输出：仅评分内容，rubric/model/prompt 版本由服务端补齐。"""

    answer_id: UUID
    evaluation: EvaluationModelOutput
    skill_scores: list[SkillScoreDraft] = Field(default_factory=list, max_length=20)


class PracticeEvaluateModelOutput(APIModel):
    evaluations: list[PracticeEvaluationModelItem] = Field(default_factory=list, max_length=20)
    report: PracticeReport = Field(default_factory=PracticeReport)


class AIPracticeEvaluateResult(APIModel):
    evaluations: list[PracticeEvaluationItem] = Field(default_factory=list, max_length=20)
    report: PracticeReport = Field(default_factory=PracticeReport)


class GrowthSkillInput(APIModel):
    skill_code: str = Field(min_length=1, max_length=64)
    score: float | None = Field(default=None, ge=0, le=100)
    sample_count: int = Field(default=0, ge=0)
    evidence_status: str = "MISSING"
    formula_version: str | None = None
    updated_at: datetime | None = None


class GrowthEvidenceInput(APIModel):
    id: UUID
    skill_code: str = Field(min_length=1, max_length=64)
    source_type: str = Field(min_length=1, max_length=32)
    source_id: UUID
    score: float | None = Field(default=None, ge=0, le=100)
    confidence: float | None = Field(default=None, ge=0, le=1)
    status: str = "VALID"
    rubric_version: str | None = None
    created_at: datetime | None = None


class GrowthPayload(APIModel):
    profile_version: int = Field(ge=1)
    skills: list[GrowthSkillInput] = Field(default_factory=list, max_length=100)
    evidence: list[GrowthEvidenceInput] = Field(default_factory=list, max_length=100)
    target_job_requirements: Requirement | None = None


class InterviewMessage(APIModel):
    id: UUID
    seq: int = Field(ge=1)
    role: Literal["USER", "ASSISTANT", "SYSTEM"]
    content: str = Field(min_length=1, max_length=12000)
    question_no: int | None = Field(default=None, ge=1, le=50)
    follow_up_no: int | None = Field(default=None, ge=0, le=2)
    skill_codes: list[str] = Field(default_factory=list, max_length=10)
    created_at: datetime | None = None


class LatestAnswer(APIModel):
    message_id: UUID
    question_message_id: UUID
    content: str = Field(min_length=1, max_length=10000)


class InterviewJobInput(APIModel):
    id: UUID
    job_version: int = Field(ge=1)
    jd_text: str = Field(default="", max_length=30000)


class InterviewTurnPayload(APIModel):
    interview_id: UUID
    expected_version: int = Field(ge=1)
    question_limit: int = Field(ge=1, le=10)
    max_follow_ups: int = Field(ge=0, le=2)
    profile: ProfileSnapshot
    job: InterviewJobInput | None = None
    messages: list[InterviewMessage] = Field(default_factory=list, max_length=62)
    latest_answer: LatestAnswer | None = None
    question_rubric_version: str = Field(default="technical-answer-v1", min_length=1, max_length=64)

    @model_validator(mode="after")
    def _latest_answer_matches_messages(self) -> "InterviewTurnPayload":
        if self.latest_answer is None:
            return self
        if not self.messages:
            raise ValueError("latestAnswer requires messages")
        question = next(
            (m for m in self.messages if str(m.id) == str(self.latest_answer.question_message_id)),
            None,
        )
        if question is None or question.role != "ASSISTANT":
            raise ValueError("latestAnswer.questionMessageId must reference an ASSISTANT message")
        if not any(str(m.id) == str(self.latest_answer.message_id) and m.role == "USER" for m in self.messages):
            raise ValueError("latestAnswer.messageId must reference a USER message")
        return self


class TurnEvaluationInput(APIModel):
    message_id: UUID | None = None
    overall_score: float | None = Field(default=None, ge=0, le=100)
    dimensions: list[EvaluationDimension] = Field(default_factory=list, max_length=10)
    evidence_status: str = "VALID"


class InterviewReportPayload(APIModel):
    interview_id: UUID
    completion_type: Literal["NORMAL", "EARLY_END"] = "NORMAL"
    messages: list[InterviewMessage] = Field(min_length=1, max_length=62)
    turn_evaluations: list[TurnEvaluationInput] = Field(default_factory=list, max_length=62)
    skill_snapshot: list[SkillSnapshotItem] = Field(default_factory=list, max_length=100)


class ResumeAssessmentCheck(APIModel):
    code: str = Field(min_length=1, max_length=64)
    label: str = Field(min_length=1, max_length=120)
    max_score: Literal[5, 10]


class ResumeAssessmentRuleDimension(APIModel):
    code: ResumeAssessmentDimensionCode
    max_score: int = Field(ge=1, le=100)
    criteria: str = Field(min_length=1, max_length=1000)
    checks: list[ResumeAssessmentCheck] = Field(default_factory=list, max_length=4)


class ResumeAssessmentRules(APIModel):
    rule_version: str = Field(min_length=1, max_length=64)
    dimensions: list[ResumeAssessmentRuleDimension] = Field(min_length=5, max_length=5)

    @model_validator(mode="after")
    def _validate_rubric(self) -> "ResumeAssessmentRules":
        if self.rule_version == RESUME_ASSESSMENT_RUBRIC_VERSION:
            if sum(d.max_score for d in self.dimensions) != 100:
                raise ValueError("评分维度满分之和必须为 100")
            for dimension in self.dimensions:
                checks = dimension.checks
                if len({check.code for check in checks}) != len(checks) or sum(
                    check.max_score for check in checks
                ) != dimension.max_score:
                    raise ValueError(f"评分点配置有误: {dimension.code}")
        return self


RESUME_ASSESSMENT_DEFAULT_RULES = ResumeAssessmentRules(
    rule_version=RESUME_ASSESSMENT_RULE_VERSION,
    dimensions=[
        ResumeAssessmentRuleDimension(
            code="EXPERIENCE_PROJECTS",
            max_score=30,
            criteria="按阅读顺序复核后的文本判断职责、个人贡献、解决的问题及成果是否说清楚",
        ),
        ResumeAssessmentRuleDimension(
            code="SKILLS_EVIDENCE",
            max_score=20,
            criteria="技能描述是否具体，能否在经历或项目中找到支撑",
        ),
        ResumeAssessmentRuleDimension(
            code="CONTENT_COMPLETENESS",
            max_score=20,
            criteria="教育、经历、技能等信息是否足以理解候选人的背景",
        ),
        ResumeAssessmentRuleDimension(
            code="STRUCTURE_CLARITY",
            max_score=15,
            criteria="章节、层级和时间线是否清楚，跨页内容是否归入正确章节",
        ),
        ResumeAssessmentRuleDimension(
            code="EXPRESSION_PROFESSIONALISM",
            max_score=15,
            criteria="表述是否具体、简洁，术语是否一致",
        ),
    ],
)


class ResumeDiagnosePayload(APIModel):
    document: DocumentRef
    profile_version: int | None = Field(default=None, ge=1)
    assessment_rules: ResumeAssessmentRules | None = None

    @model_validator(mode="after")
    def _requires_authorized_text(self) -> "ResumeDiagnosePayload":
        if self.document.text is None or not self.document.text.strip():
            raise ValueError("document.text 必须提供非空简历授权原文")
        if self.assessment_rules is not None and len(
            {dimension.code for dimension in self.assessment_rules.dimensions}
        ) != len(self.assessment_rules.dimensions):
            raise ValueError("assessmentRules.dimensions 维度编码不得重复")
        return self


class ResumeOptimizePayload(APIModel):
    document: DocumentRef
    job_id: UUID
    job_version: int = Field(ge=1)
    jd_text: str = Field(min_length=1, max_length=30000)
    company_name: str | None = Field(default=None, max_length=100)
    job_title: str | None = Field(default=None, max_length=100)
    diagnosis: ResumeDiagnosisInput | None = None
    mode: ResumeOptimizeMode = "SECTIONS"

    @model_validator(mode="after")
    def _requires_authorized_text(self) -> "ResumeOptimizePayload":
        if self.document.text is None or not self.document.text.strip():
            raise ValueError("document.text 必须提供非空简历授权原文")
        return self


class ProjectRecommendPayload(APIModel):
    profile: ProfileSnapshot
    allowed_skill_codes: list[str] = Field(min_length=1, max_length=20)
    match_item: MatchItemInput | None = None
    query: str | None = Field(default=None, max_length=120)
    sources: list[RepoPlatform] = Field(default_factory=list, max_length=3)
    tech_stack_keywords: list[str] = Field(default_factory=list, max_length=5)
    discovery_round: int | None = Field(default=None, ge=0, le=10)
    search_cursor: dict[str, int] = Field(default_factory=dict, max_length=9)
    seen_repository_keys: list[str] = Field(default_factory=list, max_length=720)
    difficulty: ProjectDifficulty | None = None
    max_duration_weeks: int = Field(default=12, ge=1, le=12)

    @field_validator("allowed_skill_codes")
    @classmethod
    def _unique_skills(cls, value: list[str]) -> list[str]:
        cleaned = []
        for item in value:
            if item not in cleaned:
                cleaned.append(item)
        return cleaned

    @field_validator("query")
    @classmethod
    def _clean_query(cls, value: str | None) -> str | None:
        if value is None:
            return None
        cleaned = value.strip()
        return cleaned or None

    @field_validator("sources")
    @classmethod
    def _unique_sources(cls, value: list[str]) -> list[str]:
        cleaned = []
        for item in value:
            if item not in cleaned:
                cleaned.append(item)
        return cleaned

    @field_validator("tech_stack_keywords")
    @classmethod
    def _clean_tech_stack_keywords(cls, value: list[str]) -> list[str]:
        cleaned: list[str] = []
        for item in value:
            keyword = item.strip()
            if not keyword:
                raise ValueError("技术栈关键词不能为空白")
            if len(keyword) > 64:
                raise ValueError("单个技术栈关键词不能超过 64 个字符")
            if all(keyword.lower() != existing.lower() for existing in cleaned):
                cleaned.append(keyword)
        return cleaned


class ProjectPlanPayload(APIModel):
    recommendation: ProjectRecommendationItem
    weekly_hours: int = Field(ge=1, le=40)


CompanySize = Literal[
    "SIZE_0_20",
    "SIZE_21_99",
    "SIZE_100_499",
    "SIZE_500_999",
    "SIZE_1000_9999",
    "SIZE_10000_PLUS",
    "SIZE_UNKNOWN",
]
SearchSourceType = Literal["OFFICIAL", "AUTHORIZED", "PUBLIC_SEARCH", "USER_INPUT"]
SearchVerification = Literal["VERIFIED", "UNVERIFIED"]
SearchVerificationStatus = Literal["ACTIVE_CONFIRMED", "ACTIVE_PROBABLE", "UNKNOWN", "CLOSED"]
SearchVerificationEvidence = Literal[
    "OFFICIAL_LIST",
    "DETAIL_APPLY_LINK",
    "DETAIL_TEXT",
    "SEARCH_SNIPPET",
    "OFFICIAL_CLOSED",
    "EXPIRED",
]


class JobSearchFilters(APIModel):
    keyword: str | None = Field(default=None, max_length=100)
    cities: list[str] = Field(default_factory=list, max_length=20)
    candidate_types: list[str] = Field(default_factory=list, max_length=3)
    company_sizes: list[CompanySize] = Field(default_factory=list, max_length=7)
    include_unknown_size: bool = True
    max_results: int = Field(default=30, ge=1, le=60)

    @field_validator("candidate_types")
    @classmethod
    def _candidate_types(cls, value: list[str]) -> list[str]:
        allowed = {"INTERNSHIP", "CAMPUS", "EXPERIENCED"}
        cleaned = []
        for item in value:
            if item not in allowed:
                raise ValueError(f"未知求职类型: {item}")
            if item not in cleaned:
                cleaned.append(item)
        return cleaned

    @field_validator("company_sizes")
    @classmethod
    def _company_sizes(cls, value: list[str]) -> list[str]:
        cleaned = []
        for item in value:
            if item not in cleaned:
                cleaned.append(item)
        return cleaned


class JobSearchLimits(APIModel):
    per_source: int = Field(default=20, ge=1, le=30)
    max_total: int = Field(default=60, ge=1, le=100)
    concurrency: int = Field(default=4, ge=1, le=8)
    source_timeout_seconds: float = Field(default=12.0, ge=1, le=30)


class JobSearchPayload(APIModel):
    profile: ProfileSnapshot
    filters: JobSearchFilters = Field(default_factory=JobSearchFilters)
    sources: list[str] = Field(default_factory=list, max_length=10)
    skill_catalog: list[SkillCatalogItem] = Field(default_factory=list, max_length=500)
    limits: JobSearchLimits = Field(default_factory=JobSearchLimits)


class SearchAlias(APIModel):
    source_key: str = Field(min_length=1, max_length=32)
    source_name: str = Field(min_length=1, max_length=100)
    source_type: SearchSourceType
    source_url: str = Field(min_length=1, max_length=2000)
    published_at: datetime | None = None
    fetched_at: datetime | None = None
    verification: SearchVerification
    verification_status: SearchVerificationStatus | None = None
    search_providers: list[str] = Field(default_factory=list, max_length=5)


class SearchGap(APIModel):
    skill_code: str = Field(min_length=1, max_length=64)
    suggestion: str = Field(min_length=1, max_length=500)


class SearchSourceStatus(APIModel):
    key: str = Field(min_length=1, max_length=32)
    name: str = Field(min_length=1, max_length=100)
    status: Literal["AVAILABLE", "EMPTY", "PARTIAL", "FAILED", "UNAVAILABLE", "SKIPPED"]
    count: int = Field(default=0, ge=0, le=1000)
    message: str = Field(default="", max_length=1000)
    http_status: int | None = Field(default=None, ge=100, le=599)
    duration_ms: int = Field(default=0, ge=0)
    verification: SearchVerification = "UNVERIFIED"
    requires_credentials: bool = False
    verification_counts: dict[str, Any] = Field(default_factory=dict)
    providers: list[str] = Field(default_factory=list, max_length=5)


class AIJobSearchItem(APIModel):
    source_key: str = Field(min_length=1, max_length=32)
    source_name: str = Field(min_length=1, max_length=100)
    source_type: SearchSourceType
    source_url: str = Field(min_length=1, max_length=2000)
    title: str = Field(min_length=1, max_length=200)
    company_name: str = Field(min_length=1, max_length=200)
    city_name: str | None = Field(default=None, max_length=100)
    city_code: str | None = Field(default=None, max_length=12)
    candidate_type: Literal["INTERNSHIP", "CAMPUS", "EXPERIENCED"] | None = None
    salary_min_monthly_cents: int | None = Field(default=None, ge=0, le=100_000_000)
    salary_max_monthly_cents: int | None = Field(default=None, ge=0, le=100_000_000)
    salary_text: str | None = Field(default=None, max_length=200)
    experience_text: str | None = Field(default=None, max_length=200)
    experience_months_min: int | None = Field(default=None, ge=0, le=600)
    education_text: str | None = Field(default=None, max_length=200)
    education_min: Education | None = None
    company_size: CompanySize
    company_size_label: str | None = Field(default=None, max_length=50)
    company_size_text: str | None = Field(default=None, max_length=200)
    field_sources: dict[str, Any] = Field(default_factory=dict)
    published_at: datetime | None = None
    updated_at: datetime | None = None
    fetched_at: datetime
    recruitment_status: Literal["OPEN", "PROBABLE", "CLOSED", "UNKNOWN"] | None = None
    recruitment_checked_at: datetime | None = None
    recruitment_evidence: SearchVerificationEvidence | None = None
    verification_status: SearchVerificationStatus | None = None
    verification_reason: str | None = Field(default=None, max_length=64)
    verification_evidence: SearchVerificationEvidence | None = None
    verification_checked_at: datetime | None = None
    verification_http_status: int | None = Field(default=None, ge=100, le=599)
    verification_history: list[dict[str, Any]] = Field(default_factory=list, max_length=10)
    search_providers: list[str] = Field(default_factory=list, max_length=5)
    query: str | None = Field(default=None, max_length=300)
    jd_text: str = Field(min_length=1, max_length=30000)
    requirements: Requirement | None = None
    semantic_score: float | None = Field(default=None, ge=0, le=1)
    reasons: list[str] = Field(default_factory=list, max_length=5)
    gaps: list[SearchGap] = Field(default_factory=list, max_length=20)
    verification: SearchVerification = "UNVERIFIED"
    warnings: list[str] = Field(default_factory=list, max_length=20)
    fingerprint: str = Field(min_length=1, max_length=64)
    aliases: list[SearchAlias] = Field(default_factory=list, max_length=20)

    @field_validator("source_url")
    @classmethod
    def _http_url(cls, value: str) -> str:
        if not value.lower().startswith(("http://", "https://")):
            raise ValueError("sourceUrl 仅允许 http/https")
        return value

    @model_validator(mode="after")
    def _salary_order(self) -> "AIJobSearchItem":
        if (
            self.salary_min_monthly_cents is not None
            and self.salary_max_monthly_cents is not None
            and self.salary_min_monthly_cents > self.salary_max_monthly_cents
        ):
            raise ValueError("salary min 不得大于 max")
        return self


class AIJobSearchResult(APIModel):
    items: list[AIJobSearchItem] = Field(default_factory=list, max_length=100)
    sources: list[SearchSourceStatus] = Field(default_factory=list, max_length=10)
    query_plan: dict[str, Any] = Field(default_factory=dict)
    warnings: list[str] = Field(default_factory=list, max_length=60)
    dedupe: dict[str, Any] = Field(default_factory=dict)
    metrics: dict[str, Any] = Field(default_factory=dict)


# ---------------------------------------------------------------------------
# Run API
# ---------------------------------------------------------------------------


RunStatus = Literal[
    "QUEUED",
    "RUNNING",
    "SUCCEEDED",
    "FAILED",
    "CANCEL_REQUESTED",
    "CANCELLED",
]


class AIRunVO(APIModel):
    run_id: UUID
    task_id: UUID
    operation: str
    status: RunStatus
    attempt: int
    result: dict[str, Any] | None = None
    error: dict[str, Any] | None = None
    last_event_id: int = 0
    created_at: datetime
