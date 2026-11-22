from __future__ import annotations

import asyncio
import hashlib
import logging
import re
import time
from dataclasses import dataclass
from datetime import date
from decimal import ROUND_HALF_UP, Decimal
from typing import Any, Awaitable, Callable
from uuid import UUID

from pydantic import BaseModel, ValidationError

from .config import Settings
from .errors import HandlerError, HandlerValidationError
from .files import FileFetcher
from .llm import LLMClient, LLMProviderError, LLMRequest
from .project_fallback import plan_project
from .project_sources import RepositorySourcesUnavailable, RepoHit, search_project_repositories
from .project_discovery import discover
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
    LEARNING_PRACTICE_EVALUATE,
    LEARNING_PRACTICE_QUESTIONS,
    LEARNING_QUESTION,
    PROJECT_PLAN,
    PROJECT_RECOMMEND,
    PROMPT_VERSIONS,
    RESUME_DIAGNOSE,
    RESUME_OPTIMIZE,
    RESUME_OCR,
    RESUME_PARSE,
)
from .prompts import build_prompt, build_repair_prompt
from .resume_skills import locate_quote, recover_catalog_skills
from .resume_timeline import has_unsupported_timeline_claim, quotes_reversed_range, timeline_level
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
    AIResumeOcrResult,
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
    AIPracticeEvaluateResult,
    AIPracticeQuestion,
    AIPracticeQuestionSet,
    LearningEvaluatePayload,
    LearningPlanPayload,
    LearningPracticeEvaluatePayload,
    LearningPracticeQuestionsPayload,
    LearningQuestionPayload,
    PracticeEvaluateModelOutput,
    PracticeEvaluationItem,
    MatchItemModelOutput,
    MatchModelOutput,
    ParseModelOutput,
    PlanItem,
    ProjectPlanPayload,
    ProjectRecommendModelOutput,
    ProjectRecommendPayload,
    ProjectRecommendationItem,
    RepositorySource,
    RepositorySourceRun,
    ReportModelOutput,
    Requirement,
    RESUME_ASSESSMENT_DEFAULT_RULES,
    RESUME_ASSESSMENT_RUBRIC_VERSION,
    RESUME_ASSESSMENT_SUPPORTED_RULE_VERSIONS,
    ResumeAssessment,
    ResumeAssessmentRules,
    ResumeEvidenceFact,
    ResumeDiagnosePayload,
    ResumeOptimizePayload,
    ResumeOcrPayload,
    ResumeParsePayload,
    Rubric,
    RubricDimension,
    SkillCatalogItem,
    SkillFact,
    SkillScore,
    SkillScoreDraft,
    TurnModelOutput,
    TurnQuestion,
    UnmappedSkillDetail,
)

logger = logging.getLogger("agent.handler")

FIXED_RUBRIC_WEIGHTS: dict[str, float] = {
    "correctness": 0.5,
    "completeness": 0.3,
    "expression": 0.2,
}
UNUSABLE_ANSWERS = {"不知道", "不清楚", "不会", "无", "没有"}


def utcnow_iso() -> str:
    from .storage import to_iso, utcnow

    return to_iso(utcnow())


def half_up(value: float, digits: int = 1) -> float:
    quant = Decimal("1").scaleb(-digits)
    return float(Decimal(str(value)).quantize(quant, rounding=ROUND_HALF_UP))


def norm_text(value: str) -> str:
    return re.sub(r"\s+", "", value).lower()


_PAGE_LINE_MARKER = re.compile(r"^\[page:(\d+)\]$")


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
    repository_client: Any = None


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

    async def execute(self, ctx: ExecutionContext,
                      prepared: dict[str, Any] | None = None) -> tuple[dict[str, Any], dict[str, Any]]:
        skill = get_skill(self.operation)
        prepared = prepared if prepared is not None else await self.prepare(ctx)
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
                thinking=False if self.operation in {
                    RESUME_PARSE, RESUME_DIAGNOSE, PROJECT_RECOMMEND, PROJECT_PLAN, INTERVIEW_TURN,
                } else None,
            )
            logger.info("model call attempt=%s user_chars=%s thinking=%s",
                        attempt + 1, len(request.user_prompt), request.thinking)
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
    timeout_seconds = 60.0

    async def execute(self, ctx: ExecutionContext,
                      prepared: dict[str, Any] | None = None) -> tuple[dict[str, Any], dict[str, Any]]:
        try:
            async with asyncio.timeout(self.timeout_seconds):
                return await super().execute(ctx, prepared)
        except TimeoutError as exc:
            raise HandlerError("MODEL_PROVIDER_ERROR", "简历解析响应超时，请重试", retryable=True) from exc

    @staticmethod
    def evidence_facts(field: str, items: list[ResumeEvidenceFact], text: str,
                       quote_cache: dict[str, Any]) -> list[ResumeEvidenceFact]:
        result: list[ResumeEvidenceFact] = []
        for item in items:
            try:
                quote = _restore_quote(text, item.quote, quote_cache)
            except HandlerValidationError:
                continue
            located = locate_quote(text, quote)
            if located and not any(norm_text(quote) == norm_text(existing.quote) for existing in result):
                result.append(item.model_copy(
                    update={"quote": quote, "confirmed": False, "location": located}))

        marker = (r"实习|\bintern(?:ship)?\b" if field == "internships"
                  else r"获奖|奖学金|一等奖|二等奖|三等奖|金奖|银奖|铜奖")
        headings = {"实习经历", "实习经验", "获奖经历", "奖项荣誉", "荣誉奖项"}
        page = 1
        for number, raw_line in enumerate(text.splitlines(), 1):
            line = raw_line.strip()
            marker_match = _PAGE_LINE_MARKER.match(line)
            if marker_match:
                page = int(marker_match.group(1))
                continue
            if not line or line in headings or not re.search(marker, line, re.IGNORECASE):
                continue
            quote = line[:1000]
            if any(norm_text(quote) in norm_text(item.quote) or norm_text(item.quote) in norm_text(quote)
                   for item in result):
                continue
            result.append(ResumeEvidenceFact(claim=line[:500], quote=quote,
                                             location=f"page:{page};line:{number}", confirmed=False))
            if len(result) >= 20:
                break
        return result

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

        quote_cache: dict[str, Any] = {}
        seen: set[str] = set()
        skills = []
        for skill in facts.skills:
            if skill.skill_code not in catalog or skill.skill_code in seen:
                continue
            try:
                quote = _restore_quote(text, skill.quote, quote_cache)
            except HandlerValidationError:
                # Leave the code unseen so catalog matching can recover it from the source.
                continue
            located = locate_quote(text, quote)
            if located is None:
                continue
            seen.add(skill.skill_code)
            skills.append(
                skill.model_copy(update={"quote": quote, "confirmed": False, "location": located})
            )
        # 目录补漏：技能目录（含别名）词边界匹配，补回模型遗漏的明确技能。
        for recovered in recover_catalog_skills(text, payload.skill_catalog, seen):
            if len(skills) >= 50:
                break
            skills.append(SkillFact(
                skill_code=recovered.skill_code,
                claim=recovered.claim,
                quote=recovered.quote,
                location=recovered.location,
                confirmed=False,
            ))
            seen.add(recovered.skill_code)
        facts.skills = skills
        facts.internships = self.evidence_facts("internships", facts.internships, text, quote_cache)
        facts.awards = self.evidence_facts("awards", facts.awards, text, quote_cache)
        if not (facts.skills or facts.internships or facts.awards):
            raise HandlerValidationError("解析结果没有任何有效事实（技能、实习、获奖均为空）")
        facts.warnings = dedupe(facts.warnings, limit=20)

        unmapped = dedupe([item for item in output.unmapped_skills if len(item) <= 100], limit=50)
        # 目录外候选：必须能定位原文依据，否则不作为候选展示。
        unmapped_details: list[UnmappedSkillDetail] = []
        for name in unmapped:
            detail = self._locate_unmapped(text, name)
            if detail is not None:
                unmapped_details.append(detail)
        return AIParseResult(
            facts=facts,
            text_hash=prepared["text_hash"],
            unmapped_skills=[item.name for item in unmapped_details],
            unmapped_details=unmapped_details,
        )

    @staticmethod
    def _locate_unmapped(text: str, name: str) -> UnmappedSkillDetail | None:
        from .resume_skills import _catalog_needles, _codes_for_match, _index_lines, _needle_pattern, _needle_usable
        from .schemas import SkillCatalogItem

        mapping = _catalog_needles([SkillCatalogItem(code="_UNMAPPED_", name=name)])
        for needle, entries in mapping.items():
            if not _needle_usable(needle):
                continue
            for line in _index_lines(text):
                match = _needle_pattern(needle).search(line.text)
                if match is None:
                    continue
                if not _codes_for_match(entries, match.group(0)) and len(entries) > 1:
                    continue
                return UnmappedSkillDetail(
                    name=name[:100],
                    quote=line.text[:1000],
                    location=f"page:{line.page};line:{line.line_no}",
                )
        return None


class ResumeOcrHandler(BaseHandler):
    """图片 PDF 的 OCR 识别：纯程序化执行，不调用模型。"""

    operation = RESUME_OCR
    payload_model = ResumeOcrPayload
    output_model = AIResumeOcrResult
    prompt_version = PROMPT_VERSIONS[RESUME_OCR]

    async def execute(self, ctx: ExecutionContext,
                      prepared: dict[str, Any] | None = None) -> tuple[dict[str, Any], dict[str, Any]]:
        import asyncio

        from .ocr import merge_pdf_text, strip_page_markers

        payload: ResumeOcrPayload = ctx.payload  # type: ignore[assignment]
        await ctx.emit_stage("input_ready", 10)
        data = await ctx.files.download_bytes(payload.document)
        await ctx.emit_stage("calling_model", 30)

        total_pages_holder = {"total": 0}

        def report_progress(done: int, total: int) -> None:
            total_pages_holder["total"] = total
            # 进度只写日志；阶段推进由主协程统一发出，避免子线程触发异步 IO。

        started = time.monotonic()
        try:
            outcome = await asyncio.to_thread(merge_pdf_text, data, ctx.settings, report_progress)
        except HandlerError:
            raise
        except Exception as exc:  # Tesseract/PyMuPDF 等未预期错误
            raise HandlerError("OCR_ENGINE_FAILED", f"OCR 处理失败: {type(exc).__name__}", retryable=True) from exc
        duration_ms = int((time.monotonic() - started) * 1000)

        body = strip_page_markers(outcome.text)
        if not body:
            raise HandlerError(
                "FILE_NO_TEXT",
                "OCR 与文字层提取均未得到有效文本，文件可能为空白或纯图形",
                retryable=False,
            )
        normalized = outcome.text.replace("\r\n", "\n").replace("\r", "\n").strip()
        if len(normalized) > ctx.settings.max_extracted_chars:
            raise HandlerError(
                "FILE_TEXT_TOO_LONG",
                f"OCR 文本超过 {ctx.settings.max_extracted_chars} 字符限制",
                retryable=False,
            )
        await ctx.emit_stage("validating_result", 90)
        result = AIResumeOcrResult(
            text=normalized,
            text_hash=hashlib.sha256(normalized.encode("utf-8")).hexdigest(),
            text_layout_version="pdf-ocr-v1",
            text_source="OCR" if outcome.ocr_pages else "NATIVE",
            native_pages=outcome.native_pages,
            ocr_pages=outcome.ocr_pages,
            empty_pages=outcome.empty_pages,
            duration_ms=duration_ms,
        )
        meta = {
            "provider": "tesseract",
            "model": "none",
            "promptVersion": self.prompt_version,
            "latencyMs": duration_ms,
            "attempts": 0,
            "operation": self.operation,
            "nativePages": len(outcome.native_pages),
            "ocrPages": len(outcome.ocr_pages),
            "emptyPages": len(outcome.empty_pages),
        }
        logger.info(
            "ocr done native=%s ocr=%s empty=%s duration_ms=%s",
            len(outcome.native_pages), len(outcome.ocr_pages), len(outcome.empty_pages), duration_ms,
        )
        return result.model_dump(by_alias=True, mode="json"), meta

    def validate_business(
        self,
        ctx: ExecutionContext,
        output: BaseModel,
        prepared: dict[str, Any],
        meta: dict[str, Any],
    ) -> BaseModel:
        return output


def _restore_quote(text: str, quote: str, source_chars_cache: dict[str, Any]) -> str:
    """Return a quote that appears verbatim in the task snapshot text.

    FileFetcher normalizes line endings; quotes copied from the normalized text
    may differ by whitespace only. Match ignoring all whitespace and return the
    exact snapshot slice so Java's strict ``contains`` check passes.
    """
    quote = quote.strip()
    if quote and quote in text:
        return quote
    if source_chars_cache.get("chars") is None:
        source_chars_cache["chars"] = list(re.finditer(r"\S", text))
        source_chars_cache["compact"] = "".join(char.group() for char in source_chars_cache["chars"])
    compact_quote = re.sub(r"\s+", "", quote)
    start = source_chars_cache["compact"].find(compact_quote) if compact_quote else -1
    if start < 0:
        raise HandlerValidationError("引用 quote 必须在简历授权原文中")
    restored = text[
        source_chars_cache["chars"][start].start():
        source_chars_cache["chars"][start + len(compact_quote) - 1].end()
    ]
    if len(restored) > 1000:
        raise HandlerValidationError("引用 quote 原文片段过长，请使用更短的引用")
    return restored


class ResumeDiagnoseHandler(BaseHandler):
    operation = RESUME_DIAGNOSE
    payload_model = ResumeDiagnosePayload
    output_model = AIResumeDiagnoseResult
    prompt_version = PROMPT_VERSIONS[RESUME_DIAGNOSE]

    async def prepare(self, ctx: ExecutionContext) -> dict[str, Any]:
        document = await ctx.files.extract(ctx.payload.document)
        return {
            "document_text": document.text,
            "diagnosis_date": date.today().isoformat(),
            "assessment_rules": RESUME_ASSESSMENT_DEFAULT_RULES.model_dump(by_alias=True),
            "timeline_level": timeline_level(ctx.payload.document.text or document.text),
        }

    def validate_business(
        self,
        ctx: ExecutionContext,
        output: AIResumeDiagnoseResult,
        prepared: dict[str, Any],
        meta: dict[str, Any],
    ) -> AIResumeDiagnoseResult:
        # FileFetcher normalizes line endings. Return quotes from the original task snapshot.
        text = ctx.payload.document.text
        if text is None:
            raise HandlerValidationError("诊断缺少简历授权原文")
        payload: ResumeDiagnosePayload = ctx.payload  # type: ignore[assignment]
        rules = payload.assessment_rules
        if rules is None or rules.rule_version not in RESUME_ASSESSMENT_SUPPORTED_RULE_VERSIONS:
            rules = RESUME_ASSESSMENT_DEFAULT_RULES
        uses_rubric = rules.rule_version == RESUME_ASSESSMENT_RUBRIC_VERSION
        if not uses_rubric and not output.items:
            raise HandlerValidationError("诊断结果缺少有效建议")
        cache: dict[str, Any] = {}
        assessment = self._validated_assessment(output.assessment, rules, text, cache)
        levels = {dimension.code: dimension.levels for dimension in assessment.dimensions}
        for item in output.items:
            if uses_rubric:
                item_levels = levels.get(item.dimension_code, {})
                if item.check_code not in item_levels:
                    raise HandlerValidationError("诊断建议的 dimensionCode、checkCode 必须对应实际评分点")
                if item_levels[item.check_code] == 5:
                    raise HandlerValidationError("诊断建议不能关联满分项")
                if has_unsupported_timeline_claim(item.title, item.issue, item.rationale, item.impact,
                                                  item.suggestion, item.example, *item.pending_verification):
                    raise HandlerValidationError("跨条目同期、跨章节顺序或实习性质不能作为简历问题")
            item.quote = _restore_quote(text, item.quote, cache)
            item.quotes = [_restore_quote(text, quote, cache) for quote in item.quotes]
            if uses_rubric and item.check_code == "timeline" and not quotes_reversed_range(item.quote):
                raise HandlerValidationError("日期建议必须引用同一条目内确实倒置的起止日期")
        return AIResumeDiagnoseResult(items=output.items, assessment=assessment)

    def _validated_assessment(
        self,
        assessment: Any,
        rules: ResumeAssessmentRules,
        text: str,
        cache: dict[str, Any],
    ):
        if assessment is None:
            raise HandlerValidationError("诊断结果缺少评分 assessment，必须返回五个维度的评分")
        uses_rubric = rules.rule_version == RESUME_ASSESSMENT_RUBRIC_VERSION
        if uses_rubric and (assessment.rule_version != rules.rule_version or not assessment.summary):
            raise HandlerValidationError("诊断结果须使用任务评分规则并提供综合评价 summary")
        if uses_rubric and has_unsupported_timeline_claim(
            assessment.summary, *(dimension.rationale for dimension in assessment.dimensions)
        ):
            raise HandlerValidationError("评分与综合评价不能因跨条目同期、跨章节顺序或实习性质扣分")
        dimensions = {dimension.code: dimension for dimension in rules.dimensions}
        seen: set[str] = set()
        for dimension in assessment.dimensions:
            if dimension.code in seen:
                raise HandlerValidationError(f"评分维度重复: {dimension.code}")
            seen.add(dimension.code)
            rule = dimensions[dimension.code]
            if uses_rubric:
                checks = {check.code: check.max_score for check in rule.checks}
                if dimension.levels.keys() != checks.keys():
                    raise HandlerValidationError(f"评分维度 {dimension.code} 的评分点缺失或未知")
                if dimension.code == "STRUCTURE_CLARITY" and "timeline" in checks:
                    verified_level = timeline_level(text)
                    if verified_level is not None and dimension.levels["timeline"] != verified_level:
                        raise HandlerValidationError(
                            f"timeline 档位须等于已核验的 timelineLevel={verified_level}"
                        )
                dimension.score = sum(
                    dimension.levels[code] * max_score // 5 for code, max_score in checks.items()
                )
            if dimension.score is None:
                raise HandlerValidationError(f"评分维度 {dimension.code} 缺少 score")
            if dimension.score > rule.max_score:
                raise HandlerValidationError(
                    f"评分维度 {dimension.code} 超过满分 {rule.max_score}"
                )
            if dimension.score > 0:
                if not dimension.quote or not dimension.quote.strip():
                    raise HandlerValidationError(
                        f"评分维度 {dimension.code} 正分必须提供简历原文依据 quote"
                    )
                dimension.quote = _restore_quote(text, dimension.quote, cache)
            else:
                dimension.quote = None
            dimension.quotes = [_restore_quote(text, quote, cache) for quote in dimension.quotes]
        missing = sorted(dimensions.keys() - seen)
        if missing:
            raise HandlerValidationError(f"评分维度缺失: {','.join(missing)}")
        for strength in assessment.strengths:
            strength.quote = _restore_quote(text, strength.quote, cache)
            strength.quotes = [_restore_quote(text, quote, cache) for quote in strength.quotes]
        return ResumeAssessment(
            rule_version=rules.rule_version,
            summary=assessment.summary if uses_rubric else None,
            dimensions=assessment.dimensions,
            strengths=assessment.strengths,
        )


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
    def _from_candidate(candidate: Any, provider: str = "") -> AIQuestionResult:
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
            model_provider=provider,
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
        used_stems = {norm_text(item) for item in payload.excluded_question_stems}
        available = [
            item
            for item in payload.candidate_questions
            if str(item.id) not in excluded and norm_text(item.stem) not in used_stems
        ]
        # A shared skill tag alone does not prove relevance to a specific plan goal.
        if available and not payload.plan_item.objective:
            result = self._from_candidate(available[0], provider=str(getattr(ctx.llm, "name", "")))
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
        # 生成的新题不得与已用题干等价重复（归一化比较），避免连续练习收到重复题
        used_stems = {norm_text(item) for item in payload.excluded_question_stems}
        if norm_text(output.stem) in used_stems:
            raise HandlerValidationError("生成题干与已用题目重复，请换一个考查角度")

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
                "model_provider": str(getattr(getattr(ctx, "llm", None), "name", "") or ""),
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
    # 回答分类：明确回答"不会"（UNKNOWN）与无效回答（INVALID，过短/乱输入）分开标记。
    # 两者都不伪造分数，但"不会"在 Java 侧会进入错题本待复习路径。
    answer_class: str | None = None
    admission = re.sub(r"[\s，。！？,.!?]", "", answer).lower()
    explicitly_unknown = admission in UNUSABLE_ANSWERS or re.fullmatch(
        r"(?:我)?(?:暂时|目前|还)?(?:不会|不知道|不清楚|不懂)(?:这(?:道)?题)?"
        r"(?:暂时无法给出(?:有效的)?(?:分析方案|答案|解答))?", admission
    ) is not None
    if explicitly_unknown:
        status = "INVALID"
        answer_class = "UNKNOWN"
    elif len(answer.strip()) < 2 or admission in {"asdf", "test"}:
        status = "INVALID"
        answer_class = "INVALID"

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
        answer_class=answer_class,
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


class LearningPracticeQuestionsHandler(BaseHandler):
    """一次生成一组题目：按配置混合选择题、填空题与主观题。"""

    operation = LEARNING_PRACTICE_QUESTIONS
    payload_model = LearningPracticeQuestionsPayload
    output_model = AIPracticeQuestionSet
    prompt_version = PROMPT_VERSIONS[LEARNING_PRACTICE_QUESTIONS]

    def validate_business(
        self,
        ctx: ExecutionContext,
        output: AIPracticeQuestionSet,
        prepared: dict[str, Any],
        meta: dict[str, Any],
    ) -> AIPracticeQuestionSet:
        payload: LearningPracticeQuestionsPayload = ctx.payload  # type: ignore[assignment]
        config = payload.config
        planned = payload.plan_item.skill_code
        questions = output.questions

        if len(questions) != config.total_questions:
            raise HandlerValidationError(
                f"题目数量必须为 {config.total_questions}，实际 {len(questions)}"
            )
        expected = {
            "CHOICE": config.type_distribution.choice,
            "FILL": config.type_distribution.fill,
            "SUBJECTIVE": config.type_distribution.subjective,
        }
        actual = {
            "CHOICE": sum(1 for item in questions if item.type == "CHOICE"),
            "FILL": sum(1 for item in questions if item.type == "FILL"),
            "SUBJECTIVE": sum(1 for item in questions if item.type == "SUBJECTIVE"),
        }
        if actual != expected:
            raise HandlerValidationError(
                "题型分布不符合配置要求：" + "，".join(
                    f"{key} 需 {expected[key]} 道，实际 {actual[key]} 道" for key in expected
                )
            )

        used_stems = {
            norm_text(str(item)) for item in payload.excluded_question_stems if str(item).strip()
        }
        for index, question in enumerate(questions, start=1):
            if planned not in question.skill_codes:
                raise HandlerValidationError(
                    f"第 {index} 题 skillCodes 必须包含 planItem.skillCode: {planned}"
                )
            check_fixed_rubric(question.rubric)
            if not question.reference_answer.strip():
                raise HandlerValidationError(f"第 {index} 题缺少参考答案")
            if question.citations and not payload.knowledge_refs:
                raise HandlerValidationError(f"第 {index} 题包含未授权引用")
            normalized = norm_text(question.stem)
            if normalized in used_stems:
                raise HandlerValidationError(f"第 {index} 题题干与已用题目重复，请更换考查角度")
            used_stems.add(normalized)

            if question.type == "CHOICE":
                keys = [option.key.upper() for option in question.options]
                if len(keys) < 2 or len(set(keys)) != len(keys):
                    raise HandlerValidationError(f"第 {index} 题选项必须至少 2 个且 key 不重复")
                if len(question.answer_key) != 1:
                    raise HandlerValidationError(f"第 {index} 题选择题必须且只能有 1 个正确答案")
                if question.answer_key[0].upper() not in keys:
                    raise HandlerValidationError(f"第 {index} 题正确答案不在选项中")
                if question.blank_count:
                    raise HandlerValidationError(f"第 {index} 题选择题不得设置空位数")
            elif question.type == "FILL":
                if question.blank_count < 1:
                    raise HandlerValidationError(f"第 {index} 题填空题必须设置 blankCount")
                if len(question.answer_key) != question.blank_count:
                    raise HandlerValidationError(
                        f"第 {index} 题填空题标准答案数量必须等于空位数 {question.blank_count}"
                    )
                if question.options:
                    raise HandlerValidationError(f"第 {index} 题填空题不得包含选项")
                if not all(str(item).strip() for item in question.answer_key):
                    raise HandlerValidationError(f"第 {index} 题填空题标准答案不能为空")
            else:
                if question.options or question.answer_key or question.blank_count:
                    raise HandlerValidationError(f"第 {index} 题主观题不得包含选项/答案键/空位")

        return AIPracticeQuestionSet(questions=questions)


class LearningPracticeEvaluateHandler(BaseHandler):
    """整组交卷：逐题评估主观题作答，并汇总整组反馈报告。"""

    operation = LEARNING_PRACTICE_EVALUATE
    payload_model = LearningPracticeEvaluatePayload
    output_model = PracticeEvaluateModelOutput
    prompt_version = PROMPT_VERSIONS[LEARNING_PRACTICE_EVALUATE]

    def validate_business(
        self,
        ctx: ExecutionContext,
        output: PracticeEvaluateModelOutput,
        prepared: dict[str, Any],
        meta: dict[str, Any],
    ) -> AIPracticeEvaluateResult:
        payload: LearningPracticeEvaluatePayload = ctx.payload  # type: ignore[assignment]
        by_id = {str(item.answer_id): item for item in payload.answers}
        seen: set[str] = set()
        results: list[PracticeEvaluationItem] = []
        for item in output.evaluations:
            key = str(item.answer_id)
            if key not in by_id:
                raise HandlerValidationError(f"评估结果包含未知 answerId: {key}")
            if key in seen:
                raise HandlerValidationError(f"answerId 重复评估: {key}")
            seen.add(key)
            answer = by_id[key]
            evaluation, _ = _build_evaluation(
                model_output=item.evaluation,
                skill_score_drafts=item.skill_scores,
                rubric=answer.rubric,
                meta=meta,
                answer=answer.answer,
                question_skills=answer.skill_codes,
                source_id=answer.answer_id,
                reference_fallback=answer.reference_answer,
            )
            results.append(
                PracticeEvaluationItem(
                    answer_id=answer.answer_id,
                    evaluation=evaluation,
                    skill_scores=item.skill_scores,
                )
            )
        missing = [key for key in by_id if key not in seen]
        if missing:
            raise HandlerValidationError(f"缺少以下作答的评估结果: {', '.join(missing[:5])}")
        return AIPracticeEvaluateResult(
            evaluations=results,
            report=output.report,
        )


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


class ProjectRecommendHandler(BaseHandler):
    operation = PROJECT_RECOMMEND
    payload_model = ProjectRecommendPayload
    output_model = ProjectRecommendModelOutput
    prompt_version = PROMPT_VERSIONS[PROJECT_RECOMMEND]
    model_deadline_seconds = 35.0

    async def prepare(self, ctx: ExecutionContext) -> dict[str, Any]:
        payload: ProjectRecommendPayload = ctx.payload  # type: ignore[assignment]
        cursor, keys = None, []
        try:
            client = getattr(ctx, "repository_client", None)
            if payload.discovery_round is not None and payload.tech_stack_keywords:
                result, cursor, keys = await discover(
                    ctx.settings, payload.allowed_skill_codes, payload.query, payload.sources,
                    payload.tech_stack_keywords, payload.search_cursor, payload.seen_repository_keys, client,
                )
            else:
                result = await search_project_repositories(
                    ctx.settings, payload.allowed_skill_codes, payload.query, payload.sources,
                    tech_stack_keywords=payload.tech_stack_keywords, client=client,
                )
        except RepositorySourcesUnavailable as exc:
            raise HandlerError(
                "PROJECT_SOURCES_UNAVAILABLE",
                f"所选平台本次均不可用：{exc}",
                retryable=True,
            ) from exc
        return {
            "source_runs": result.source_runs,
            "repository_candidates": [self._candidate_input(hit) for hit in result.candidates[:6]],
            "candidates": result.candidates,
            "search_cursor": cursor,
            "discovered_keys": keys,
            "terms": result.terms,
            "rejected": result.rejected,
        }

    async def execute(self, ctx: ExecutionContext) -> tuple[dict[str, Any], dict[str, Any]]:
        import asyncio

        prepared = await self.prepare(ctx)
        discovery = prepared.get("search_cursor") is not None
        if discovery and (ctx.payload.discovery_round > 0 or not prepared["candidates"]):
            await ctx.emit_stage("validating_result", 90)
            result = self._repository_hints(ctx.payload, prepared)
            return result.model_dump(by_alias=True, mode="json"), {
                "provider": "repository_rules", "promptVersion": self.prompt_version,
                "operation": self.operation, "candidateCount": len(result.items),
            }
        if not prepared["candidates"]:
            await ctx.emit_stage("validating_result", 90)
            empty = AIProjectRecommendResult(
                mode="REPOSITORY",
                source_runs=self._source_runs(prepared),
                items=[],
            )
            return empty.model_dump(by_alias=True, mode="json"), {
                "provider": "none",
                "promptVersion": self.prompt_version,
                "operation": self.operation,
                "candidateCount": 0,
            }
        try:
            model_prepared = {**prepared, "candidates": prepared["candidates"][:6]} if discovery else prepared
            output, meta = await asyncio.wait_for(
                super().execute(ctx, model_prepared),
                timeout=self.model_deadline_seconds,
            )
            if discovery:
                suggestions = {item["primarySource"]["url"]: item for item in output["items"]}
                complete = self._repository_hints(ctx.payload, prepared).model_dump(by_alias=True, mode="json")
                complete["items"] = [suggestions.get(item["primarySource"]["url"], item) for item in complete["items"]]
                return complete, meta
            return output, meta
        except TimeoutError:
            reason = "MODEL_TIMEOUT"
        except HandlerError as exc:
            if exc.code not in {"MODEL_PROVIDER_ERROR", "MODEL_OUTPUT_INVALID"}:
                raise
            reason = exc.code
            status = getattr(exc.__cause__, "http_status", None)
            if status is not None:
                reason = f"MODEL_HTTP_{status}"
        await ctx.emit_stage("using_repository_hints", 85)
        result = self._repository_hints(ctx.payload, prepared)
        meta = {
            "provider": "repository_rules",
            "promptVersion": self.prompt_version,
            "operation": self.operation,
            "modelDegraded": True,
            "fallbackReason": reason,
        }
        logger.warning("project recommend hints operation=%s reason=%s candidates=%s",
                       self.operation, reason, len(result.items))
        await ctx.emit_stage("validating_result", 90)
        return result.model_dump(by_alias=True, mode="json"), meta

    def validate_business(
        self,
        ctx: ExecutionContext,
        output: ProjectRecommendModelOutput,
        prepared: dict[str, Any],
        meta: dict[str, Any],
    ) -> AIProjectRecommendResult:
        payload: ProjectRecommendPayload = ctx.payload  # type: ignore[assignment]
        allowed = set(payload.allowed_skill_codes)
        candidates = {hit.url.strip().lower(): hit for hit in prepared.get("candidates") or []}
        if not candidates:
            raise HandlerValidationError("本次没有可用的真实仓库候选")
        if not output.items:
            raise HandlerValidationError("存在真实仓库候选时必须至少输出一个推荐项")
        order = {hit.url.strip().lower(): index for index, hit in enumerate(prepared.get("candidates") or [])}
        items: list[ProjectRecommendationItem] = []
        seen: set[str] = set()
        for suggestion in output.items:
            key = suggestion.source_url.strip().lower()
            hit = candidates.get(key)
            if hit is None:
                raise HandlerValidationError("推荐来源必须来自本次检索到的真实仓库候选")
            if key in seen:
                raise HandlerValidationError("推荐结果不能重复包含同一个仓库")
            seen.add(key)
            unknown = set(suggestion.skill_codes) - allowed
            if unknown:
                raise HandlerValidationError(
                    f"推荐技能不在 allowedSkillCodes 中: {', '.join(sorted(unknown))}"
                )
            if payload.difficulty is not None and suggestion.difficulty != payload.difficulty:
                raise HandlerValidationError("推荐项目 difficulty 必须与输入难度一致")
            if suggestion.estimated_weeks > payload.max_duration_weeks:
                raise HandlerValidationError("推荐项目 estimatedWeeks 超出 maxDurationWeeks")
            items.append(self._item(hit, suggestion))
        items.sort(key=lambda item: order.get(
            (item.primary_source.url if item.primary_source else "").strip().lower(), len(order)))
        return AIProjectRecommendResult(
            mode="REPOSITORY",
            source_runs=self._source_runs(prepared),
            items=items,
        )

    def _repository_hints(self, payload: ProjectRecommendPayload,
                          prepared: dict[str, Any]) -> AIProjectRecommendResult:
        items: list[ProjectRecommendationItem] = []
        for hit in prepared.get("candidates") or []:
            skills = [code for code in hit.matched_skill_codes
                      if code in payload.allowed_skill_codes][:3]
            if not skills:
                skills = payload.allowed_skill_codes[:1]
            items.append(ProjectRecommendationItem(
                title=hit.name,
                summary=hit.description,
                skill_codes=skills,
                difficulty=payload.difficulty or "INTERMEDIATE",
                estimated_weeks=min(payload.max_duration_weeks, 2),
                reason=("仓库包含请求技术栈的使用证据，可从文档和相关配置开始实战。"
                        if hit.stack_evidence else "根据仓库公开简介、许可证与你的目标技能匹配推荐；建议先阅读 README，再完成一个小改造。"),
                getting_started=[
                    "阅读仓库 README 和目录结构，确认运行方式",
                    "运行官方示例或最小 demo，记录依赖与启动步骤",
                    "围绕目标技能完成一个小改造并补充自测记录",
                ],
                deliverables=[
                    "仓库运行说明与关键模块笔记",
                    "基于仓库完成的小改造及自测记录",
                ],
                primary_source=self._source(hit),
                alternate_sources=[self._source(item) for item in hit.alternate_sources],
                stack_evidence=hit.stack_evidence,
            ))
        return AIProjectRecommendResult(
            mode="REPOSITORY",
            source_runs=self._source_runs(prepared),
            items=items if prepared.get("search_cursor") is not None else items[:6],
            search_cursor=prepared.get("search_cursor"),
            discovered_keys=prepared.get("discovered_keys", []),
        )

    def _item(self, hit: RepoHit, suggestion: Any) -> ProjectRecommendationItem:
        return ProjectRecommendationItem(
            title=hit.name,
            summary=hit.description,
            skill_codes=suggestion.skill_codes,
            difficulty=suggestion.difficulty,
            estimated_weeks=suggestion.estimated_weeks,
            reason=suggestion.reason,
            getting_started=suggestion.getting_started,
            deliverables=suggestion.deliverables,
            primary_source=self._source(hit),
            alternate_sources=[self._source(item) for item in hit.alternate_sources],
            stack_evidence=hit.stack_evidence,
        )

    @staticmethod
    def _candidate_input(hit: RepoHit) -> dict[str, Any]:
        return {
            "sourceUrl": hit.url,
            "platform": hit.platform,
            "path": hit.path,
            "title": hit.name,
            "summary": hit.description,
            "skillCodes": hit.matched_skill_codes,
            "license": hit.license,
            "stars": hit.stars,
            "readmeUrl": hit.readme_url,
            "stackEvidence": hit.stack_evidence,
        }

    @staticmethod
    def _source(hit: RepoHit) -> RepositorySource:
        return RepositorySource.model_validate(hit.source_dict())

    @staticmethod
    def _source_runs(prepared: dict[str, Any]) -> list[RepositorySourceRun]:
        return [RepositorySourceRun.model_validate(item) for item in prepared.get("source_runs") or []]


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
    RESUME_OCR: ResumeOcrHandler(),
    RESUME_DIAGNOSE: ResumeDiagnoseHandler(),
    RESUME_OPTIMIZE: ResumeOptimizeHandler(),
    JD_ANALYZE: JDAnalyzeHandler(),
    JOB_MATCH: JobMatchHandler(),
    JOB_SEARCH: JobSearchHandler(),
    LEARNING_PLAN: LearningPlanHandler(),
    LEARNING_QUESTION: LearningQuestionHandler(),
    LEARNING_EVALUATE: LearningEvaluateHandler(),
    LEARNING_PRACTICE_QUESTIONS: LearningPracticeQuestionsHandler(),
    LEARNING_PRACTICE_EVALUATE: LearningPracticeEvaluateHandler(),
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
