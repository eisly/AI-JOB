from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal
from typing import Any, Awaitable, Callable
from uuid import UUID

from pydantic import BaseModel, ValidationError

from .config import Settings
from .errors import HandlerError, HandlerValidationError
from .files import FileFetcher
from .llm import LLMClient, LLMProviderError, LLMRequest
from .project_fallback import plan_project, recommend_project
from .learning_github import search_github_repositories
from .operations import (
    GROWTH_ANALYZE,
    INTERVIEW_REPORT,
    INTERVIEW_TURN,
    JD_ANALYZE,
    JOB_MATCH,
    JOB_SEARCH,
    LEARNING_EVALUATE,
    LEARNING_PLAN,
    LEARNING_QUESTION,
    PROJECT_PLAN,
    PROJECT_RECOMMEND,
    PROMPT_VERSIONS,
    RESUME_DIAGNOSE,
    RESUME_OPTIMIZE,
    RESUME_PARSE,
)
from .prompts import build_prompt, build_repair_prompt
from .skills import get_skill
from .schemas import (
    AIEvaluateResult,
    AIInterviewReportResult,
    AIInterviewTurnResult,
    AIJDResult,
    AIJobSearchResult,
    AIMatchItem,
    AIMatchResult,
    AIParseResult,
    AIPlanResult,
    AIProjectPlanResult,
    AIProjectRecommendResult,
    AIQuestionResult,
    AIGrowthResult,
    AIResumeDiagnoseResult,
    AIResumeOptimizeResult,
    Citation,
    GitHubLookup,
    Education,
    EvaluationDimension,
    EvaluationModelOutput,
    EvaluationVO,
    EvaluateModelOutput,
    Gap,
    GrowthPayload,
    InterviewReportPayload,
    InterviewTurnPayload,
    JDParsePayload,
    JobMatchPayload,
    JobSearchPayload,
    LearningEvaluatePayload,
    LearningPlanPayload,
    LearningQuestionPayload,
    MatchItemModelOutput,
    MatchModelOutput,
    ParseModelOutput,
    PlanItem,
    ProjectPlanPayload,
    ProjectRecommendPayload,
    ReportModelOutput,
    Requirement,
    ResumeDiagnosePayload,
    ResumeOptimizePayload,
    ResumeParsePayload,
    Rubric,
    RubricDimension,
    SkillScore,
    SkillScoreDraft,
    TurnModelOutput,
    TurnQuestion,
)

logger = logging.getLogger("agent.handler")

FIXED_RUBRIC_WEIGHTS: dict[str, float] = {
    "correctness": 0.5,
    "completeness": 0.3,
    "expression": 0.2,
}
UNUSABLE_ANSWERS = {"不知道", "不清楚", "不会", "无", "没有", "不知道。", "不会。", "asdf", "test"}


def utcnow_iso() -> str:
    from .storage import to_iso, utcnow

    return to_iso(utcnow())


def half_up(value: float, digits: int = 1) -> float:
    quant = Decimal("1").scaleb(-digits)
    return float(Decimal(str(value)).quantize(quant, rounding=ROUND_HALF_UP))


def norm_text(value: str) -> str:
    return re.sub(r"\s+", "", value).lower()


def quote_in_text(quote: str, text: str) -> bool:
    q = norm_text(quote)
    return bool(q) and q in norm_text(text)


def _normalize_url(value: str) -> str:
    normalized = value.strip().lower()
    if normalized.startswith("https://"):
        normalized = normalized[8:]
    elif normalized.startswith("http://"):
        normalized = normalized[7:]
    return normalized.rstrip("/")


def dedupe(values: list[str], *, limit: int | None = None) -> list[str]:
    seen: set[str] = set()
    result = []
    for value in values:
        item = value.strip()
        if not item or item in seen:
            continue
        seen.add(item)
        result.append(item)
        if limit is not None and len(result) >= limit:
            break
    return result


def fixed_rubric(version: str) -> Rubric:
    return Rubric(
        version=version,
        dimensions=[
            RubricDimension(key=key, weight=weight)
            for key, weight in FIXED_RUBRIC_WEIGHTS.items()
        ],
    )


def check_fixed_rubric(rubric: Rubric) -> None:
    keys = [item.key for item in rubric.dimensions]
    if set(keys) != set(FIXED_RUBRIC_WEIGHTS) or len(keys) != 3:
        raise HandlerValidationError("rubric 必须包含 correctness/completeness/expression 三个维度")
    for item in rubric.dimensions:
        if abs(item.weight - FIXED_RUBRIC_WEIGHTS[item.key]) > 1e-6:
            raise HandlerValidationError(f"rubric 维度 {item.key} 的权重必须为 {FIXED_RUBRIC_WEIGHTS[item.key]}")
    total = sum(item.weight for item in rubric.dimensions)
    if abs(total - 1.0) > 1e-6:
        raise HandlerValidationError("rubric 权重之和必须为 1")


def _format_validation_error(exc: Exception) -> str:
    if isinstance(exc, ValidationError):
        parts = []
        for error in exc.errors():
            location = ".".join(str(item) for item in error.get("loc", ()))
            parts.append(f"{location}: {error.get('msg', '')}")
        return "; ".join(parts)[:1500]
    return str(exc)[:1500]


@dataclass
class ExecutionContext:
    run: Any
    payload: BaseModel
    settings: Settings
    llm: LLMClient
    files: FileFetcher
    emit_stage: Callable[[str, int | None], Awaitable[None]]
    emit_reasoning: Callable[[str], Awaitable[None]] | None = None
    emit_candidates: Callable[[list[dict[str, Any]]], Awaitable[None]] | None = None


class BaseHandler:
    operation: str
    payload_model: type[BaseModel]
    output_model: type[BaseModel]
    prompt_version: str

    async def prepare(self, ctx: ExecutionContext) -> dict[str, Any]:
        return {}

    def validate_business(
        self,
        ctx: ExecutionContext,
        output: BaseModel,
        prepared: dict[str, Any],
        meta: dict[str, Any],
    ) -> BaseModel:
        raise NotImplementedError

    async def execute(self, ctx: ExecutionContext) -> tuple[dict[str, Any], dict[str, Any]]:
        skill = get_skill(self.operation)
        prepared = await self.prepare(ctx)
        await ctx.emit_stage("input_ready", 15)
        payload_dict = ctx.payload.model_dump(by_alias=True, mode="json")
        system, user_prompt, schema_hint = build_prompt(self.operation, payload_dict, prepared)
        logger.info(
            "prompt ready prompt_version=%s system_chars=%s user_chars=%s schema_chars=%s prepared=%s",
            skill.version,
            len(system),
            len(user_prompt),
            len(schema_hint),
            ",".join(sorted(prepared)) or "-",
        )
        context = {"payload": payload_dict, **prepared}
        repair_note = ""
        last_error = ""
        for attempt in range(2):
            await ctx.emit_stage("calling_model", 40)
            request = LLMRequest(
                operation=self.operation,
                system_prompt=system,
                user_prompt=user_prompt + repair_note,
                schema_hint=schema_hint,
                context=context,
                thinking=False if self.operation in {PROJECT_RECOMMEND, PROJECT_PLAN} else None,
            )
            logger.info("model call attempt=%s user_chars=%s", attempt + 1, len(request.user_prompt))
            try:
                response = await ctx.llm.complete(request)
            except LLMProviderError as exc:
                logger.error(
                    "model provider error attempt=%s retryable=%s message=%s",
                    attempt + 1,
                    exc.retryable,
                    exc.message,
                )
                reasoning_text = getattr(exc, "reasoning", "") or ""
                if (
                    reasoning_text
                    and ctx.emit_reasoning is not None
                    and ctx.settings.reasoning_display_enabled
                ):
                    await ctx.emit_reasoning(reasoning_text[: ctx.settings.reasoning_max_chars])
                raise HandlerError("MODEL_PROVIDER_ERROR", exc.message, retryable=exc.retryable) from exc
            meta = {
                "provider": getattr(ctx.llm, "name", "unknown"),
                "model": response.model or getattr(ctx.llm, "name", "unknown"),
                "promptVersion": skill.version,
                "latencyMs": response.latency_ms,
                "inputTokens": response.input_tokens,
                "outputTokens": response.output_tokens,
                "attempts": attempt + 1,
                "operation": self.operation,
            }
            logger.info(
                "model response attempt=%s model=%s latency_ms=%s tokens=%s/%s chars=%s",
                attempt + 1,
                meta["model"],
                response.latency_ms,
                response.input_tokens,
                response.output_tokens,
                len(response.text),
            )
            reasoning_text = getattr(response, "reasoning", "") or ""
            if (
                reasoning_text
                and ctx.emit_reasoning is not None
                and ctx.settings.reasoning_display_enabled
            ):
                await ctx.emit_reasoning(reasoning_text[: ctx.settings.reasoning_max_chars])
            try:
                data = skill.parse_output(response.text)
                output = self.output_model.model_validate(data)
            except (ValueError, ValidationError) as exc:
                last_error = _format_validation_error(exc)
                logger.warning("model output invalid attempt=%s error=%s", attempt + 1, last_error)
                repair_note = "\n\n" + build_repair_prompt(
                    "结构与 JSON",
                    last_error,
                    response.text,
                )
                await ctx.emit_stage("repairing_output", 60)
                continue

            try:
                result = self.validate_business(ctx, output, prepared, meta)
            except HandlerValidationError as exc:
                last_error = exc.message
                logger.warning("business validation failed attempt=%s error=%s", attempt + 1, last_error)
                repair_note = "\n\n" + build_repair_prompt(
                    "业务规则",
                    last_error,
                    response.text,
                )
                await ctx.emit_stage("repairing_output", 60)
                continue

            await ctx.emit_stage("validating_result", 90)
            logger.info("result ready attempts=%s", attempt + 1)
            return result.model_dump(by_alias=True, mode="json"), meta

        raise HandlerError("MODEL_OUTPUT_INVALID", f"模型输出未通过校验: {last_error}", retryable=True)


class ResumeParseHandler(BaseHandler):
    operation = RESUME_PARSE
    payload_model = ResumeParsePayload
    output_model = ParseModelOutput
    prompt_version = PROMPT_VERSIONS[RESUME_PARSE]

    async def prepare(self, ctx: ExecutionContext) -> dict[str, Any]:
        document = await ctx.files.extract(ctx.payload.document)
        return {"document_text": document.text, "text_hash": document.text_hash}

    def validate_business(
        self,
        ctx: ExecutionContext,
        output: ParseModelOutput,
        prepared: dict[str, Any],
        meta: dict[str, Any],
    ) -> AIParseResult:
        payload: ResumeParsePayload = ctx.payload  # type: ignore[assignment]
        text = prepared["document_text"]
        catalog = {item.code for item in payload.skill_catalog}
        facts = output.facts.model_copy(deep=True)

        if facts.education is not None and facts.education not in {
            "HIGH_SCHOOL",
            "ASSOCIATE",
            "BACHELOR",
            "MASTER",
            "DOCTOR",
        }:
            raise HandlerValidationError("education 不在允许枚举内")
        if facts.graduation_year is not None and not 1900 <= facts.graduation_year <= 2100:
            raise HandlerValidationError("graduationYear 超出合理范围")
        if facts.experience_months is not None and not 0 <= facts.experience_months <= 600:
            raise HandlerValidationError("experienceMonths 超出合理范围")

        seen: set[str] = set()
        skills = []
        for skill in facts.skills:
            if skill.skill_code not in catalog:
                raise HandlerValidationError(f"skillCode 不在 skillCatalog 中: {skill.skill_code}")
            if skill.skill_code in seen:
                continue
            if not quote_in_text(skill.quote, text):
                raise HandlerValidationError(f"技能 {skill.skill_code} 的 quote 不在简历原文中")
            seen.add(skill.skill_code)
            skills.append(
                skill.model_copy(update={"quote": skill.quote.strip(), "confirmed": False})
            )
        facts.skills = skills
        facts.warnings = dedupe(facts.warnings, limit=20)

        unmapped = dedupe([item for item in output.unmapped_skills if len(item) <= 100], limit=50)
        return AIParseResult(facts=facts, text_hash=prepared["text_hash"], unmapped_skills=unmapped)


class ResumeDiagnoseHandler(BaseHandler):
    operation = RESUME_DIAGNOSE
    payload_model = ResumeDiagnosePayload
    output_model = AIResumeDiagnoseResult
    prompt_version = PROMPT_VERSIONS[RESUME_DIAGNOSE]

    async def prepare(self, ctx: ExecutionContext) -> dict[str, Any]:
        document = await ctx.files.extract(ctx.payload.document)
        return {"document_text": document.text}

    def validate_business(
        self,
        ctx: ExecutionContext,
        output: AIResumeDiagnoseResult,
        prepared: dict[str, Any],
        meta: dict[str, Any],
    ) -> AIResumeDiagnoseResult:
        text = prepared["document_text"]
        for item in output.items:
            if not quote_in_text(item.quote, text):
                raise HandlerValidationError("诊断项 quote 必须在简历授权原文中")
        return AIResumeDiagnoseResult(items=output.items)


class ResumeOptimizeHandler(BaseHandler):
    operation = RESUME_OPTIMIZE
    payload_model = ResumeOptimizePayload
    output_model = AIResumeOptimizeResult
    prompt_version = PROMPT_VERSIONS[RESUME_OPTIMIZE]

    async def prepare(self, ctx: ExecutionContext) -> dict[str, Any]:
        document = await ctx.files.extract(ctx.payload.document)
        return {"document_text": document.text}

    def validate_business(
        self,
        ctx: ExecutionContext,
        output: AIResumeOptimizeResult,
        prepared: dict[str, Any],
        meta: dict[str, Any],
    ) -> AIResumeOptimizeResult:
        text = prepared["document_text"]
        section_keys: set[str] = set()
        for section in output.sections:
            if section.section_key in section_keys:
                raise HandlerValidationError(f"sectionKey 必须唯一: {section.section_key}")
            section_keys.add(section.section_key)
            if not quote_in_text(section.original_text, text):
                raise HandlerValidationError(f"段落 {section.section_key} 的 originalText 必须来自简历授权原文")
            for quote in section.source_quotes:
                if not quote_in_text(quote, text):
                    raise HandlerValidationError(f"段落 {section.section_key} 的 sourceQuotes 必须来自简历授权原文")
        return AIResumeOptimizeResult(title=output.title, sections=output.sections)


class JDAnalyzeHandler(BaseHandler):
    operation = JD_ANALYZE
    payload_model = JDParsePayload
    output_model = AIJDResult
    prompt_version = PROMPT_VERSIONS[JD_ANALYZE]

    def validate_business(
        self,
        ctx: ExecutionContext,
        output: AIJDResult,
        prepared: dict[str, Any],
        meta: dict[str, Any],
    ) -> AIJDResult:
        payload: JDParsePayload = ctx.payload  # type: ignore[assignment]
        text = payload.jd_text
        catalog = {item.code for item in payload.skill_catalog}
        requirements: Requirement = output.requirements

        allowed_quote_keys = {
            "educationMin",
            "graduationYearMin",
            "graduationYearMax",
            "experienceMonthsMin",
        }
        unknown_keys = set(requirements.quotes) - allowed_quote_keys
        if unknown_keys:
            raise HandlerValidationError(f"quotes 含未知字段: {', '.join(sorted(unknown_keys))}")
        for key, quote in requirements.quotes.items():
            if not quote or not quote_in_text(quote, text):
                raise HandlerValidationError(f"quotes.{key} 必须是 JD 原文片段")

        hard_fields = {
            "educationMin": requirements.education_min,
            "graduationYearMin": requirements.graduation_year_min,
            "graduationYearMax": requirements.graduation_year_max,
            "experienceMonthsMin": requirements.experience_months_min,
        }
        for key, value in hard_fields.items():
            if value is not None and key not in requirements.quotes:
                raise HandlerValidationError(f"硬性字段 {key} 缺少 JD 原文引用")

        for code in requirements.skills:
            if code not in catalog:
                raise HandlerValidationError(f"技能 {code} 不在 skillCatalog 中")
        requirements.skills = dedupe(requirements.skills, limit=50)
        if set(requirements.required_skill_levels) - set(requirements.skills):
            raise HandlerValidationError("requiredSkillLevels 只能包含 requirements.skills 中的技能")
        for code, level in requirements.required_skill_levels.items():
            if not isinstance(level, int) or not 0 <= level <= 100:
                raise HandlerValidationError(f"技能 {code} 的 requiredSkillLevels 必须在 0..100")

        years = [value for value in (requirements.graduation_year_min, requirements.graduation_year_max) if value is not None]
        if any(not 1900 <= value <= 2100 for value in years):
            raise HandlerValidationError("毕业年份超出合理范围")
        if (
            requirements.graduation_year_min is not None
            and requirements.graduation_year_max is not None
            and requirements.graduation_year_min > requirements.graduation_year_max
        ):
            raise HandlerValidationError("graduationYearMin 不得大于 graduationYearMax")
        if requirements.experience_months_min is not None and not 0 <= requirements.experience_months_min <= 600:
            raise HandlerValidationError("experienceMonthsMin 超出合理范围")
        if output.salary is not None and output.salary.min_monthly_salary_cents > output.salary.max_monthly_salary_cents:
            raise HandlerValidationError("salary 下限不得大于上限")

        return AIJDResult(
            requirements=requirements,
            salary=output.salary,
            warnings=dedupe(output.warnings, limit=20),
        )


def _evidence_status(snapshot: Any) -> str:
    if snapshot is None or snapshot.score is None:
        return "MISSING"
    if snapshot.sample_count >= 3:
        return "SUFFICIENT"
    if snapshot.sample_count >= 1:
        return "LIMITED"
    return "MISSING"


def _build_gaps(
    requirements: Requirement,
    snapshots: dict[str, Any],
    suggestions: dict[str, str],
) -> list[Gap]:
    gaps: list[Gap] = []
    seen: set[str] = set()
    for code in requirements.skills:
        if code in seen:
            continue
        seen.add(code)
        snapshot = snapshots.get(code)
        current = snapshot.score if snapshot is not None else None
        target = requirements.required_skill_levels.get(code)
        if target is None and current is not None:
            continue
        if target is not None and current is not None and current >= target:
            continue
        gap = None
        if target is not None and current is not None:
            gap = max(0.0, target - current)
        gaps.append(
            Gap(
                skill_code=code,
                current_score=current,
                target_score=target,
                gap=gap,
                evidence_status=_evidence_status(snapshot),
                evidence_ids=[],
                suggestion=(suggestions.get(code) or f"补充 {code} 的场景训练并提交可追溯回答")[:500],
            )
        )
    return gaps


class JobMatchHandler(BaseHandler):
    operation = JOB_MATCH
    payload_model = JobMatchPayload
    output_model = MatchModelOutput
    prompt_version = PROMPT_VERSIONS[JOB_MATCH]

    def validate_business(
        self,
        ctx: ExecutionContext,
        output: MatchModelOutput,
        prepared: dict[str, Any],
        meta: dict[str, Any],
    ) -> AIMatchResult:
        payload: JobMatchPayload = ctx.payload  # type: ignore[assignment]
        expected = {(str(item.job_id), item.job_version) for item in payload.candidates}
        actual: dict[tuple[str, int], MatchItemModelOutput] = {}
        for item in output.items:
            key = (str(item.job_id), item.job_version)
            if key in actual:
                raise HandlerValidationError(f"候选重复: {item.job_id}")
            actual[key] = item
        if set(actual) != expected:
            raise HandlerValidationError("items 必须与输入 candidates 一一对应，不得增加、遗漏或变更版本")

        snapshots = {item.skill_code: item for item in payload.skill_snapshot}
        result_items = []
        for candidate in payload.candidates:
            item = actual[(str(candidate.job_id), candidate.job_version)]
            allowed_skills = set(candidate.requirements.skills)
            unknown = set(item.gap_suggestions) - allowed_skills
            if unknown:
                raise HandlerValidationError(
                    f"gapSuggestions 含候选 {candidate.job_id} 未要求的技能: {', '.join(sorted(unknown))}"
                )
            suggestions = {
                code: text.strip()
                for code, text in item.gap_suggestions.items()
                if text and text.strip()
            }
            result_items.append(
                AIMatchItem(
                    job_id=candidate.job_id,
                    job_version=candidate.job_version,
                    semantic_score=item.semantic_score,
                    semantic_version="semantic-v1",
                    reasons=dedupe(item.reasons, limit=5),
                    gaps=_build_gaps(candidate.requirements, snapshots, suggestions),
                )
            )
        return AIMatchResult(items=result_items)


class JobSearchHandler(BaseHandler):
    operation = JOB_SEARCH
    payload_model = JobSearchPayload
    output_model = AIJobSearchResult
    prompt_version = PROMPT_VERSIONS[JOB_SEARCH]

    async def execute(self, ctx: ExecutionContext) -> tuple[dict[str, Any], dict[str, Any]]:
        import httpx

        from .search.pipeline import JobSearchPipeline, SearchRuntime

        settings = ctx.settings
        payload = ctx.payload.model_dump(by_alias=True, mode="json")
        timeout_seconds = float(getattr(settings, "job_search_timeout_seconds", 12.0))
        concurrency = int(getattr(settings, "job_search_concurrency", 4))
        await ctx.emit_stage("input_ready", 15)
        async with httpx.AsyncClient(
            timeout=httpx.Timeout(timeout_seconds, connect=min(5.0, timeout_seconds)),
            limits=httpx.Limits(max_connections=max(4, concurrency * 2), max_keepalive_connections=4),
            trust_env=bool(getattr(settings, "job_search_trust_env", False)),
            follow_redirects=False,
        ) as client:
            runtime = SearchRuntime(
                settings=settings,
                llm=ctx.llm,
                emit_stage=ctx.emit_stage,
                client=client,
                profile=payload.get("profile") or {},
                payload=payload,
                emit_candidates=ctx.emit_candidates,
            )
            try:
                result = await JobSearchPipeline(runtime).run()
            except ValueError as exc:
                raise HandlerError("INVALID_SEARCH_QUERY", str(exc)[:300], retryable=False) from exc
        meta = {
            "provider": getattr(ctx.llm, "name", "unknown"),
            "model": getattr(ctx.llm, "name", "unknown"),
            "promptVersion": self.prompt_version,
            "operation": self.operation,
            **result.get("meta", {}),
        }
        return result, meta


class LearningPlanHandler(BaseHandler):
    operation = LEARNING_PLAN

    payload_model = LearningPlanPayload
    output_model = AIPlanResult
    prompt_version = PROMPT_VERSIONS[LEARNING_PLAN]

    async def prepare(self, ctx: ExecutionContext) -> dict[str, Any]:
        payload: LearningPlanPayload = ctx.payload  # type: ignore[assignment]
        if payload.plan_source != "RESUME":
            return {"github": {"status": "DISABLED", "reason": "岗位匹配来源不检索 GitHub"}}
        lookup = await search_github_repositories(
            ctx.settings,
            payload.target_direction,
            payload.allowed_skill_codes,
        )
        return {"github": lookup.model_dump(by_alias=True, mode="json")}

    def validate_business(
        self,
        ctx: ExecutionContext,
        output: AIPlanResult,
        prepared: dict[str, Any],
        meta: dict[str, Any],
    ) -> AIPlanResult:
        payload: LearningPlanPayload = ctx.payload  # type: ignore[assignment]
        allowed = set(payload.allowed_skill_codes)
        budget = payload.daily_minutes * payload.days
        refs = {
            (
                str(item.document_id).lower(),
                item.document_version,
                item.chunk_id,
            ): item
            for item in payload.knowledge_refs
        }
        github = GitHubLookup.model_validate(prepared.get("github") or {"status": "UNAVAILABLE"})
        github_urls = {_normalize_url(item.html_url) for item in github.candidates}
        resume_text = norm_text(payload.resume.projects_text) if payload.resume else ""

        items: list[PlanItem] = []
        total = 0
        for item in output.items:
            if item.skill_code not in allowed:
                raise HandlerValidationError(f"计划项技能 {item.skill_code} 不在 allowedSkillCodes 中")
            if item.kind not in {"READING", "PRACTICE", "INTERVIEW"}:
                raise HandlerValidationError(f"非法计划项类型: {item.kind}")
            total += item.estimated_minutes
            refs_out = []
            for ref in item.resource_refs:
                key = (str(ref.document_id).lower(), ref.document_version, ref.chunk_id)
                source = refs.get(key)
                if source is None:
                    raise HandlerValidationError("resourceRefs 含未授权的知识引用")
                refs_out.append(source.model_copy(deep=True))
            if item.category == "RESUME_PROJECT":
                if payload.plan_source != "RESUME":
                    raise HandlerValidationError("岗位匹配来源不能生成简历项目深挖训练")
                quote = norm_text(item.evidence_quote or "")
                if len(quote) < 6 or quote not in resume_text:
                    raise HandlerValidationError("简历项目训练必须引用简历原文中的真实项目描述")
                if item.evidence_status == "PENDING":
                    raise HandlerValidationError("简历项目训练必须给出已评估证据")
            if item.category == "GITHUB":
                if github.status != "AVAILABLE":
                    raise HandlerValidationError("GitHub 数据不可用，不能生成 GitHub 学习项")
                if not item.resource_links:
                    raise HandlerValidationError("GitHub 学习项必须包含仓库链接")
                for link in item.resource_links:
                    if _normalize_url(link.url) not in github_urls:
                        raise HandlerValidationError("GitHub 学习项包含输入候选之外的仓库链接")
                if item.evidence_status != "ASSESSED":
                    raise HandlerValidationError("GitHub 学习项必须为已评估证据")
            if item.evidence_status == "PENDING" and payload.plan_source != "RESUME":
                raise HandlerValidationError("只有在基于简历的计划中允许待评估项")
            items.append(
                item.model_copy(
                    update={
                        "title": item.title.strip()[:200],
                        "evidence_quote": (item.evidence_quote or "").strip() or None,
                        "resource_refs": refs_out,
                    }
                )
            )
        if len(items) < 1 or len(items) > 20:
            raise HandlerValidationError("计划项数量必须为 1..20")
        if total > budget:
            raise HandlerValidationError(
                f"计划总时长 {total} 分钟超过 dailyMinutes×days={budget} 分钟"
            )
        return AIPlanResult(
            title=output.title.strip()[:200],
            summary=output.summary.strip()[:500],
            items=items,
            github=github,
        )


class LearningQuestionHandler(BaseHandler):
    operation = LEARNING_QUESTION
    payload_model = LearningQuestionPayload
    output_model = AIQuestionResult
    prompt_version = PROMPT_VERSIONS[LEARNING_QUESTION]

    @staticmethod
    def _from_candidate(candidate: Any) -> AIQuestionResult:
        return AIQuestionResult(
            stem=candidate.stem,
            skill_codes=list(candidate.skill_codes),
            difficulty=candidate.difficulty,
            type=candidate.type,
            source_type="CURATED",
            source_question_id=candidate.id,
            reference_answer=candidate.reference_answer,
            rubric=candidate.rubric.model_copy(deep=True),
            citations=[],
        )

    @staticmethod
    def _meta(deterministic: bool, ctx: ExecutionContext) -> dict[str, Any]:
        return {
            "provider": "deterministic" if deterministic else getattr(ctx.llm, "name", "unknown"),
            "model": "none" if deterministic else getattr(ctx.llm, "name", "unknown"),
            "promptVersion": PROMPT_VERSIONS[LEARNING_QUESTION],
            "latencyMs": 0,
            "attempts": 0,
            "operation": LEARNING_QUESTION,
        }

    async def execute(self, ctx: ExecutionContext) -> tuple[dict[str, Any], dict[str, Any]]:
        payload: LearningQuestionPayload = ctx.payload  # type: ignore[assignment]
        if payload.mode == "REUSE_ONLY":
            if len(payload.candidate_questions) != 1:
                raise HandlerError(
                    "INVALID_CANDIDATES",
                    "REUSE_ONLY 必须提供且只提供一个原题候选",
                    retryable=False,
                )
            result = self._from_candidate(payload.candidate_questions[0])
            return result.model_dump(by_alias=True, mode="json"), self._meta(True, ctx)

        excluded = {str(item) for item in payload.excluded_question_ids}
        available = [item for item in payload.candidate_questions if str(item.id) not in excluded]
        if available:
            result = self._from_candidate(available[0])
            return result.model_dump(by_alias=True, mode="json"), self._meta(True, ctx)

        return await super().execute(ctx)

    def validate_business(
        self,
        ctx: ExecutionContext,
        output: AIQuestionResult,
        prepared: dict[str, Any],
        meta: dict[str, Any],
    ) -> AIQuestionResult:
        payload: LearningQuestionPayload = ctx.payload  # type: ignore[assignment]
        planned = payload.plan_item.skill_code
        if planned not in output.skill_codes:
            raise HandlerValidationError(f"skillCodes 必须包含 planItem.skillCode: {planned}")
        check_fixed_rubric(output.rubric)
        if len(output.stem.strip()) < 10:
            raise HandlerValidationError("题干过短")
        if not output.reference_answer.strip():
            raise HandlerValidationError("缺少参考答案")

        refs = {
            (str(item.document_id).lower(), item.document_version, item.chunk_id): item
            for item in payload.knowledge_refs
        }
        citations = []
        for citation in output.citations:
            key = (str(citation.document_id).lower(), citation.document_version, citation.chunk_id)
            source = refs.get(key)
            if source is None:
                raise HandlerValidationError("citations 含未授权的知识引用")
            citations.append(source.model_copy(deep=True))

        return output.model_copy(
            update={
                "skill_codes": dedupe(output.skill_codes, limit=10),
                "difficulty": payload.plan_item.difficulty,
                "source_type": "GENERATED",
                "source_question_id": None,
                "citations": citations,
            }
        )


def _build_evaluation(
    *,
    model_output: EvaluationModelOutput,
    skill_score_drafts: list[SkillScoreDraft] | None = None,
    rubric: Rubric,
    meta: dict[str, Any],
    answer: str,
    question_skills: list[str],
    source_id: UUID,
    reference_fallback: str,
) -> tuple[EvaluationVO, list[SkillScore]]:
    check_fixed_rubric(rubric)
    status = str(model_output.evidence_status).upper()
    if status not in {"VALID", "INSUFFICIENT", "INVALID"}:
        raise HandlerValidationError(f"evidenceStatus 非法: {model_output.evidence_status}")
    if len(answer.strip()) < 2 or answer.strip().lower() in UNUSABLE_ANSWERS:
        status = "INVALID"

    dimensions_by_key = {item.key: item for item in model_output.dimensions}
    if status == "VALID" and (set(dimensions_by_key) != set(FIXED_RUBRIC_WEIGHTS) or len(model_output.dimensions) != 3):
        missing = [key for key in FIXED_RUBRIC_WEIGHTS if key not in dimensions_by_key]
        raise HandlerValidationError(
            "评价维度必须与输入量表的三个维度完全一致："
            "correctness、completeness、expression 各且仅出现一次；"
            f"缺少：{', '.join(missing) if missing else '无'}；请删除重复或多余维度"
        )

    dimensions = []
    for rubric_dim in rubric.dimensions if status == "VALID" else []:
        item = dimensions_by_key[rubric_dim.key]
        if item.score is None:
            raise HandlerValidationError("有效评价的维度 score 必须为 0..100 数值")
        dimensions.append(
            EvaluationDimension(
                key=rubric_dim.key,
                score=item.score,
                weight=rubric_dim.weight,
                comment=item.comment.strip()[:500],
            )
        )

    if status == "VALID":
        overall = half_up(sum(item.score * item.weight for item in dimensions), 1)
        allowed = set(question_skills)
        skill_scores = []
        seen: set[str] = set()
        for draft in skill_score_drafts or []:
            if draft.skill_code not in allowed:
                raise HandlerValidationError(f"skillScores 含题目未包含的技能: {draft.skill_code}")
            if draft.skill_code in seen:
                continue
            seen.add(draft.skill_code)
            skill_scores.append(
                SkillScore(
                    skill_code=draft.skill_code,
                    score=draft.score,
                    confidence=draft.confidence,
                    source_id=source_id,
                )
            )
    else:
        overall = None
        skill_scores = []

    evaluation = EvaluationVO(
        overall_score=overall,
        dimensions=dimensions,
        strengths=dedupe(model_output.strengths, limit=10),
        improvements=dedupe(model_output.improvements, limit=10),
        reference_answer=(model_output.reference_answer.strip() or reference_fallback)[:5000],
        rubric_version=rubric.version,
        model_version=str(meta.get("model") or "unknown"),
        prompt_version=str(meta.get("promptVersion") or PROMPT_VERSIONS[LEARNING_EVALUATE]),
        evidence_status=status,
    )
    return evaluation, skill_scores


class LearningEvaluateHandler(BaseHandler):
    operation = LEARNING_EVALUATE
    payload_model = LearningEvaluatePayload
    output_model = EvaluateModelOutput
    prompt_version = PROMPT_VERSIONS[LEARNING_EVALUATE]

    def validate_business(
        self,
        ctx: ExecutionContext,
        output: EvaluateModelOutput,
        prepared: dict[str, Any],
        meta: dict[str, Any],
    ) -> AIEvaluateResult:
        payload: LearningEvaluatePayload = ctx.payload  # type: ignore[assignment]
        check_fixed_rubric(payload.question.rubric)
        evaluation, skill_scores = _build_evaluation(
            model_output=output.evaluation,
            skill_score_drafts=output.skill_scores,
            rubric=payload.question.rubric,
            meta=meta,
            answer=payload.answer,
            question_skills=payload.question.skill_codes,
            source_id=payload.answer_id,
            reference_fallback=payload.question.reference_answer,
        )
        return AIEvaluateResult(evaluation=evaluation, skill_scores=skill_scores)


class InterviewTurnHandler(BaseHandler):
    operation = INTERVIEW_TURN
    payload_model = InterviewTurnPayload
    output_model = TurnModelOutput
    prompt_version = PROMPT_VERSIONS[INTERVIEW_TURN]

    async def prepare(self, ctx: ExecutionContext) -> dict[str, Any]:
        payload: InterviewTurnPayload = ctx.payload  # type: ignore[assignment]
        profile_skills = [item.skill_code for item in payload.profile.facts.skills]
        current = None
        is_first = payload.latest_answer is None
        if not is_first:
            current = next(
                item
                for item in payload.messages
                if str(item.id) == str(payload.latest_answer.question_message_id)
            )
        main_questions = len(
            [item for item in payload.messages if item.role == "ASSISTANT" and (item.follow_up_no or 0) == 0]
        )
        return {
            "profile_skills": profile_skills,
            "current_question": current,
            "is_first": is_first,
            "main_questions": main_questions,
        }

    def _turn_question(
        self,
        candidate: Any,
        *,
        question_no: int,
        follow_up_no: int,
        allowed: set[str],
    ) -> TurnQuestion:
        if candidate is None:
            raise HandlerValidationError("缺少题目内容")
        content = candidate.content.strip()
        if len(content) < 5 or len(content) > 3000:
            raise HandlerValidationError("题目内容长度必须为 5..3000")
        skills = dedupe(candidate.skill_codes, limit=10)
        if allowed and (set(skills) - allowed):
            raise HandlerValidationError("题目包含输入资料中不存在的技能编码")
        return TurnQuestion(
            content=content,
            question_no=question_no,
            follow_up_no=follow_up_no,
            skill_codes=skills,
        )

    def validate_business(
        self,
        ctx: ExecutionContext,
        output: TurnModelOutput,
        prepared: dict[str, Any],
        meta: dict[str, Any],
    ) -> AIInterviewTurnResult:
        payload: InterviewTurnPayload = ctx.payload  # type: ignore[assignment]
        allowed = set(prepared["profile_skills"])

        if prepared["is_first"]:
            candidate = output.next_question or output.follow_up_question
            question = self._turn_question(
                candidate, question_no=1, follow_up_no=0, allowed=allowed
            )
            return AIInterviewTurnResult(
                action="ASK",
                question=question,
                answer_evaluation=None,
                stop_reason=None,
            )

        if output.answer_evaluation is None:
            raise HandlerValidationError("后续轮次必须提供 answerEvaluation")
        current = prepared["current_question"]
        question_no = current.question_no or max(1, prepared["main_questions"])
        follow_up_no = current.follow_up_no or 0
        evaluation, skill_scores = _build_evaluation(
            model_output=output.answer_evaluation.evaluation,
            skill_score_drafts=output.answer_evaluation.skill_scores,
            rubric=fixed_rubric(payload.question_rubric_version),
            meta=meta,
            answer=payload.latest_answer.content,
            question_skills=prepared["current_question"].skill_codes,
            source_id=payload.latest_answer.message_id,
            reference_fallback="",
        )
        answer_evaluation = AIEvaluateResult(evaluation=evaluation, skill_scores=skill_scores)

        if output.needs_follow_up and follow_up_no < payload.max_follow_ups:
            question = self._turn_question(
                output.follow_up_question,
                question_no=question_no,
                follow_up_no=follow_up_no + 1,
                allowed=allowed,
            )
            return AIInterviewTurnResult(
                action="FOLLOW_UP",
                question=question,
                answer_evaluation=answer_evaluation,
                stop_reason=None,
            )

        if question_no < payload.question_limit:
            question = self._turn_question(
                output.next_question,
                question_no=question_no + 1,
                follow_up_no=0,
                allowed=allowed,
            )
            return AIInterviewTurnResult(
                action="ASK",
                question=question,
                answer_evaluation=answer_evaluation,
                stop_reason=None,
            )

        return AIInterviewTurnResult(
            action="FINISH",
            question=None,
            answer_evaluation=answer_evaluation,
            stop_reason="LIMIT_REACHED",
        )


class InterviewReportHandler(BaseHandler):
    operation = INTERVIEW_REPORT
    payload_model = InterviewReportPayload
    output_model = ReportModelOutput
    prompt_version = PROMPT_VERSIONS[INTERVIEW_REPORT]

    async def prepare(self, ctx: ExecutionContext) -> dict[str, Any]:
        payload: InterviewReportPayload = ctx.payload  # type: ignore[assignment]
        valid = [
            item
            for item in payload.turn_evaluations
            if item.evidence_status.upper() == "VALID" and item.overall_score is not None
        ]
        average = half_up(sum(item.overall_score for item in valid) / len(valid), 1) if valid else None
        dimensions: list[EvaluationDimension] = []
        for key, weight in FIXED_RUBRIC_WEIGHTS.items():
            scores = []
            for item in valid:
                match = next((dim for dim in item.dimensions if dim.key == key), None)
                if match is not None:
                    scores.append(match.score)
            if scores:
                dimensions.append(
                    EvaluationDimension(
                        key=key,
                        score=half_up(sum(scores) / len(scores), 1),
                        weight=weight,
                        comment="综合逐轮评价",
                    )
                )
        return {
            "valid_count": len(valid),
            "average_score": average,
            "aggregated_dimensions": dimensions,
        }

    async def execute(self, ctx: ExecutionContext) -> tuple[dict[str, Any], dict[str, Any]]:
        payload: InterviewReportPayload = ctx.payload  # type: ignore[assignment]
        prepared = await self.prepare(ctx)
        if prepared["valid_count"] == 0:
            result = AIInterviewReportResult(
                evaluation=EvaluationVO(
                    overall_score=None,
                    dimensions=[],
                    strengths=[],
                    improvements=["本次面试没有形成有效回答，建议先完成至少一道主问题"],
                    reference_answer="",
                    rubric_version="technical-answer-v1",
                    model_version="none",
                    prompt_version=PROMPT_VERSIONS[INTERVIEW_REPORT],
                    evidence_status="INSUFFICIENT",
                ),
                gaps=self._build_report_gaps(payload, {}),
                answered_question_count=0,
                completion_type="NO_VALID_ANSWER",
            )
            meta = {
                "provider": "deterministic",
                "model": "none",
                "promptVersion": PROMPT_VERSIONS[INTERVIEW_REPORT],
                "latencyMs": 0,
                "attempts": 0,
                "operation": INTERVIEW_REPORT,
            }
            return result.model_dump(by_alias=True, mode="json"), meta
        return await super().execute(ctx)

    @staticmethod
    def _build_report_gaps(payload: InterviewReportPayload, suggestions: dict[str, str]) -> list[Gap]:
        snapshot = {item.skill_code: item for item in payload.skill_snapshot}
        gaps = []
        for code, item in snapshot.items():
            if code not in suggestions:
                continue
            if item.score is not None and item.score >= 70:
                continue
            gaps.append(
                Gap(
                    skill_code=code,
                    current_score=item.score,
                    target_score=None,
                    gap=None,
                    evidence_status=_evidence_status(item),
                    evidence_ids=[],
                    suggestion=suggestions[code].strip()[:500],
                )
            )
        return gaps

    def validate_business(
        self,
        ctx: ExecutionContext,
        output: ReportModelOutput,
        prepared: dict[str, Any],
        meta: dict[str, Any],
    ) -> AIInterviewReportResult:
        payload: InterviewReportPayload = ctx.payload  # type: ignore[assignment]
        snapshot_skills = {item.skill_code for item in payload.skill_snapshot}
        unknown = set(output.gap_suggestions) - snapshot_skills
        if unknown:
            raise HandlerValidationError(
                f"gapSuggestions 含 skillSnapshot 之外的技能: {', '.join(sorted(unknown))}"
            )
        suggestions = {
            code: text for code, text in output.gap_suggestions.items() if text and text.strip()
        }
        result = AIInterviewReportResult(
            evaluation=EvaluationVO(
                overall_score=prepared["average_score"],
                dimensions=prepared["aggregated_dimensions"],
                strengths=dedupe(output.strengths, limit=10),
                improvements=dedupe(output.improvements, limit=10),
                reference_answer=output.reference_answer.strip()[:5000],
                rubric_version="technical-answer-v1",
                model_version=str(meta.get("model") or "unknown"),
                prompt_version=PROMPT_VERSIONS[INTERVIEW_REPORT],
                evidence_status="VALID",
            ),
            gaps=self._build_report_gaps(payload, suggestions),
            answered_question_count=prepared["valid_count"],
            completion_type=payload.completion_type,
        )
        return result


class GrowthAnalyzeHandler(BaseHandler):
    operation = GROWTH_ANALYZE
    payload_model = GrowthPayload
    output_model = AIGrowthResult
    prompt_version = PROMPT_VERSIONS[GROWTH_ANALYZE]

    def validate_business(
        self,
        ctx: ExecutionContext,
        output: AIGrowthResult,
        prepared: dict[str, Any],
        meta: dict[str, Any],
    ) -> AIGrowthResult:
        payload: GrowthPayload = ctx.payload  # type: ignore[assignment]
        suggestions = dedupe([item[:500] for item in output.suggestions], limit=5)
        if not suggestions:
            raise HandlerValidationError("suggestions 不能为空")
        evidence_ids = {str(item.id) for item in payload.evidence}
        cited: list[UUID] = []
        seen: set[str] = set()
        for item in output.cited_evidence_ids:
            value = str(item)
            if value not in evidence_ids:
                raise HandlerValidationError(f"citedEvidenceIds 含输入中不存在的证据: {value}")
            if value in seen:
                continue
            seen.add(value)
            cited.append(item)
        return AIGrowthResult(suggestions=suggestions, cited_evidence_ids=cited)


class ProjectFallbackHandler(BaseHandler):
    model_deadline_seconds = 35.0

    async def execute(self, ctx: ExecutionContext) -> tuple[dict[str, Any], dict[str, Any]]:
        import asyncio

        try:
            return await asyncio.wait_for(super().execute(ctx), timeout=self.model_deadline_seconds)
        except TimeoutError:
            reason = "MODEL_TIMEOUT"
        except HandlerError as exc:
            if exc.code not in {"MODEL_PROVIDER_ERROR", "MODEL_OUTPUT_INVALID"}:
                raise
            reason = exc.code
            status = getattr(exc.__cause__, "http_status", None)
            if status is not None:
                reason = f"MODEL_HTTP_{status}"
        await ctx.emit_stage("using_local_project_rules", 85)
        result = self.fallback(ctx.payload)
        meta = {"provider": "local_rules", "promptVersion": self.prompt_version,
                "operation": self.operation, "modelDegraded": True, "fallbackReason": reason}
        logger.warning("project fallback operation=%s reason=%s", self.operation, reason)
        await ctx.emit_stage("validating_result", 90)
        return self.validate_business(ctx, result, {}, meta).model_dump(by_alias=True, mode="json"), meta


class ProjectRecommendHandler(ProjectFallbackHandler):
    operation = PROJECT_RECOMMEND
    payload_model = ProjectRecommendPayload
    output_model = AIProjectRecommendResult
    prompt_version = PROMPT_VERSIONS[PROJECT_RECOMMEND]
    fallback = staticmethod(recommend_project)

    def validate_business(
        self,
        ctx: ExecutionContext,
        output: AIProjectRecommendResult,
        prepared: dict[str, Any],
        meta: dict[str, Any],
    ) -> AIProjectRecommendResult:
        payload = ctx.payload
        allowed = set(payload.allowed_skill_codes)
        for item in output.items:
            unknown = set(item.skill_codes) - allowed
            if unknown:
                raise HandlerValidationError(
                    f"推荐技能不在 allowedSkillCodes 中: {', '.join(sorted(unknown))}"
                )
            if item.difficulty != payload.difficulty:
                raise HandlerValidationError("推荐项目 difficulty 必须与输入难度一致")
            if item.estimated_weeks > payload.max_duration_weeks:
                raise HandlerValidationError("推荐项目 estimatedWeeks 超出 maxDurationWeeks")
        return AIProjectRecommendResult(items=output.items)


class ProjectPlanHandler(ProjectFallbackHandler):
    operation = PROJECT_PLAN
    payload_model = ProjectPlanPayload
    output_model = AIProjectPlanResult
    prompt_version = PROMPT_VERSIONS[PROJECT_PLAN]
    fallback = staticmethod(plan_project)

    def validate_business(
        self,
        ctx: ExecutionContext,
        output: AIProjectPlanResult,
        prepared: dict[str, Any],
        meta: dict[str, Any],
    ) -> AIProjectPlanResult:
        payload = ctx.payload
        expected_seq = list(range(1, len(output.milestones) + 1))
        if [item.seq for item in output.milestones] != expected_seq:
            raise HandlerValidationError("milestones.seq 必须从 1 开始连续编号")
        total_hours = sum(
            task.estimated_hours
            for milestone in output.milestones
            for task in milestone.tasks
        )
        budget = payload.weekly_hours * payload.recommendation.estimated_weeks
        if total_hours > budget:
            raise HandlerValidationError(
                f"计划总工时 {total_hours} 超过 weeklyHours×estimatedWeeks={budget}"
            )
        return AIProjectPlanResult(
            title=output.title,
            summary=output.summary,
            milestones=output.milestones,
        )


HANDLERS: dict[str, BaseHandler] = {
    RESUME_PARSE: ResumeParseHandler(),
    RESUME_DIAGNOSE: ResumeDiagnoseHandler(),
    RESUME_OPTIMIZE: ResumeOptimizeHandler(),
    JD_ANALYZE: JDAnalyzeHandler(),
    JOB_MATCH: JobMatchHandler(),
    JOB_SEARCH: JobSearchHandler(),
    LEARNING_PLAN: LearningPlanHandler(),
    LEARNING_QUESTION: LearningQuestionHandler(),
    LEARNING_EVALUATE: LearningEvaluateHandler(),
    INTERVIEW_TURN: InterviewTurnHandler(),
    INTERVIEW_REPORT: InterviewReportHandler(),
    GROWTH_ANALYZE: GrowthAnalyzeHandler(),
    PROJECT_RECOMMEND: ProjectRecommendHandler(),
    PROJECT_PLAN: ProjectPlanHandler(),
}


def get_handler(operation: str) -> BaseHandler:
    handler = HANDLERS.get(operation)
    if handler is None:
        raise HandlerError("UNSUPPORTED_OPERATION", f"不支持的 operation: {operation}", retryable=False)
    return handler
