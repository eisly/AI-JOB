from __future__ import annotations

import asyncio
import json
import logging
import re
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable, TypedDict

from langgraph.graph import END, START, StateGraph

from ..llm import LLMRequest, LLMResponse, parse_json_object
from ..schemas import Requirement
from ..skills import get_skill
from .models import (
    COMPANY_SIZE_LABELS,
    ROLE_KEYWORDS,
    SearchFilters,
    SourcePosting,
    company_size_matches,
    normalize_company_size,
)
from .sources import SourceAdapter, SourceContext, resolve_sources
from .sources.base import DEFAULT_USER_AGENT, DEFAULT_TIMEOUT_SECONDS

logger = logging.getLogger("agent.search")
SEARCH_GRAPH_VERSION = "job-search-graph-v1"
UNDERSTAND_BATCH = 8
MODEL_JD_CHARS = 3000


@dataclass
class SearchRuntime:
    settings: Any
    llm: Any
    emit_stage: Callable[[str, int | None], Awaitable[None]]
    client: Any
    profile: dict[str, Any]
    payload: dict[str, Any]


class SearchState(TypedDict, total=False):
    runtime: SearchRuntime
    filters: SearchFilters
    keyword: str
    cities: tuple[str, ...]
    candidate_types: tuple[str, ...]
    adapters: list[SourceAdapter]
    raw: list[SourcePosting]
    deduped: list[SourcePosting]
    dedupe: dict[str, Any]
    sources: list[dict[str, Any]]
    query_plan: dict[str, Any]
    warnings: list[str]
    adjustments: list[str]
    was_adjusted: bool
    model_calls: int
    model_degraded: bool


def build_filters(payload: dict[str, Any], profile: dict[str, Any]) -> tuple[SearchFilters, str]:
    raw = payload.get("filters") or {}
    keyword = str(raw.get("keyword") or "").strip()
    if not keyword:
        keyword = ROLE_KEYWORDS.get(str(profile.get("targetRole") or ""), "")
    cities = tuple(str(item) for item in (raw.get("cities") or profile.get("cities") or []) if str(item))
    candidate_types = tuple(str(item) for item in (raw.get("candidateTypes") or []) if str(item))
    if not candidate_types and profile.get("candidateType"):
        candidate_types = (str(profile["candidateType"]),)
    company_sizes = tuple(str(item) for item in (raw.get("companySizes") or []) if str(item))
    max_results = int(raw.get("maxResults") or 30)
    max_results = max(1, min(max_results, 60))
    filters = SearchFilters(
        keyword=keyword or None,
        cities=cities,
        candidate_types=candidate_types,
        company_sizes=company_sizes,
        include_unknown_size=bool(raw.get("includeUnknownSize", True)),
        max_results=max_results,
    )
    return filters, keyword


class JobSearchPipeline:
    def __init__(self, runtime: SearchRuntime) -> None:
        self.runtime = runtime
        self.settings = runtime.settings
        self.filters, self.keyword = build_filters(runtime.payload, runtime.profile)
        limits = runtime.payload.get("limits") or {}
        self.per_source = max(1, min(int(limits.get("perSource") or getattr(self.settings, "job_search_per_source", 20)), 30))
        self.max_total = max(1, min(int(limits.get("maxTotal") or getattr(self.settings, "job_search_max_total", 60)), 100))
        self.concurrency = max(1, min(int(limits.get("concurrency") or getattr(self.settings, "job_search_concurrency", 4)), 8))
        self.source_timeout = max(1.0, min(float(limits.get("sourceTimeoutSeconds") or getattr(self.settings, "job_search_timeout_seconds", DEFAULT_TIMEOUT_SECONDS)), 30.0))
        self.max_jd_chars = max(500, int(getattr(self.settings, "job_search_max_jd_chars", 30000)))
        self.adapters = resolve_sources(runtime.payload.get("sources") or [], self.settings)
        self.graph = self._build_graph()

    def _build_graph(self):
        builder = StateGraph(SearchState)
        builder.add_node("plan", self._plan)
        builder.add_node("search", self._search)
        builder.add_node("adjust", self._adjust)
        builder.add_node("normalize", self._normalize)
        builder.add_node("understand", self._understand)
        builder.add_node("finalize", self._finalize)
        builder.add_edge(START, "plan")
        builder.add_edge("plan", "search")
        builder.add_conditional_edges("search", self._route_after_search, {"adjust": "adjust", "normalize": "normalize"})
        builder.add_edge("adjust", "search")
        builder.add_edge("normalize", "understand")
        builder.add_edge("understand", "finalize")
        builder.add_edge("finalize", END)
        return builder.compile()

    async def run(self) -> dict[str, Any]:
        state: SearchState = {
            "runtime": self.runtime,
            "filters": self.filters,
            "keyword": self.keyword,
            "cities": self.filters.cities,
            "candidate_types": self.filters.candidate_types,
            "adapters": self.adapters,
            "raw": [],
            "warnings": [],
            "adjustments": [],
            "was_adjusted": False,
            "model_calls": 0,
            "model_degraded": False,
        }
        final = await self.graph.ainvoke(state)
        postings = final.get("deduped") or []
        return {
            "items": _items_to_result(postings),
            "sources": final.get("sources") or [],
            "queryPlan": final.get("query_plan") or {},
            "warnings": _dedupe_values(final.get("warnings") or [], 50),
            "dedupe": final.get("dedupe") or {},
            "meta": {
                "graph": SEARCH_GRAPH_VERSION,
                "modelCalls": final.get("model_calls", 0),
                "modelDegraded": bool(final.get("model_degraded")),
            },
        }

    async def _plan(self, state: SearchState) -> SearchState:
        await self.runtime.emit_stage("search_planning", 10)
        adjustments: list[str] = []
        keyword = state["keyword"]
        if not keyword:
            keyword = ROLE_KEYWORDS.get(str(self.runtime.profile.get("targetRole") or ""), "")
        if not keyword:
            raise ValueError("缺少岗位关键词：请在求职画像中设置目标岗位，或填写岗位关键词")
        plan = {
            "keyword": keyword,
            "cities": list(state["cities"]),
            "candidateTypes": list(state["candidate_types"]),
            "companySizes": list(state["filters"].company_sizes),
            "includeUnknownSize": state["filters"].include_unknown_size,
            "adjustments": adjustments,
            "version": "job-search-query-v1",
        }
        warnings: list[str] = []
        filters = state["filters"]
        if not filters.company_sizes and not filters.include_unknown_size:
            warnings.append("公司规模筛选为空且排除了未知规模：已按不受规模限制处理")
        return {"query_plan": plan, "warnings": warnings, "keyword": keyword}

    async def _search(self, state: SearchState) -> SearchState:
        await self.runtime.emit_stage("searching_sources", 30)
        filters = state["filters"]
        cities = state["cities"]
        keyword = state["keyword"]
        if cities:
            city_filters = SearchFilters(
                keyword=filters.keyword,
                cities=cities,
                candidate_types=state["candidate_types"],
                company_sizes=filters.company_sizes,
                include_unknown_size=filters.include_unknown_size,
                max_results=filters.max_results,
            )
        else:
            city_filters = filters
        context = SourceContext(
            filters=city_filters,
            keyword=keyword,
            limit=self.per_source,
            timeout_seconds=self.source_timeout,
            user_agent=str(getattr(self.settings, "job_search_user_agent", DEFAULT_USER_AGENT)),
            client=self.runtime.client,
            settings=self.settings,
        )
        semaphore = asyncio.Semaphore(self.concurrency)
        adapters = state["adapters"]

        async def run_one(adapter: SourceAdapter):
            if not adapter.available:
                return adapter.unavailable().as_status(), []
            async with semaphore:
                started = time.monotonic()
                try:
                    result = await asyncio.wait_for(adapter.search(context), timeout=self.source_timeout + 3)
                except asyncio.TimeoutError:
                    result = adapter.result("FAILED", message="来源检索超时", started=started)
                except Exception as exc:
                    logger.warning("job search source failed source=%s error=%s", adapter.key, type(exc).__name__)
                    result = adapter.result("FAILED", message=f"来源检索失败: {type(exc).__name__}", started=started)
                if result.status not in {"AVAILABLE", "EMPTY", "PARTIAL"}:
                    logger.info("job search source status source=%s status=%s count=%s", adapter.key, result.status, len(result.postings))
                return result.as_status(), result.postings

        started_all = time.monotonic()
        results = await asyncio.gather(*(run_one(adapter) for adapter in adapters))
        sources = [item[0] for item in results]
        fresh = [posting for item in results for posting in item[1]]
        elapsed = int((time.monotonic() - started_all) * 1000)
        raw = list(state.get("raw") or []) + fresh
        warnings = list(state.get("warnings") or [])
        if elapsed > self.source_timeout * 2:
            warnings.append("SEARCH_SLOW")
        logger.info(
            "job search completed sources=%s raw=%s duration_ms=%s",
            [item["key"] for item in sources if item["status"] in {"AVAILABLE", "EMPTY", "PARTIAL"}],
            len(raw),
            elapsed,
        )
        return {"sources": sources, "raw": raw, "warnings": warnings}

    def _route_after_search(self, state: SearchState) -> str:
        if state.get("was_adjusted"):
            return "normalize"
        raw = state.get("raw") or []
        if raw:
            return "normalize"
        cities = state.get("cities") or ()
        keyword_terms = [item for item in (state.get("keyword") or "").split() if len(item) >= 2]
        if cities or len(keyword_terms) > 1:
            return "adjust"
        return "normalize"

    async def _adjust(self, state: SearchState) -> SearchState:
        await self.runtime.emit_stage("search_adjusting", 45)
        adjustments = list(state.get("adjustments") or [])
        warnings = list(state.get("warnings") or [])
        cities = state.get("cities") or ()
        keyword = state.get("keyword") or ""
        next_cities = ()
        next_keyword = keyword
        if cities:
            next_cities = ()
            adjustments.append(f"首次检索结果不足，自动放宽城市限制（原城市：{', '.join(cities)}），按全国范围重试一次")
        else:
            terms = [item for item in keyword.split() if len(item) >= 2]
            if len(terms) > 1:
                next_keyword = terms[0]
                adjustments.append(f"首次检索结果不足，自动收窄关键词为「{next_keyword}」重试一次")
            else:
                adjustments.append("首次检索结果不足，已保持原条件；未发现可安全放宽的检索维度")
        warnings.append("QUERY_ADJUSTED")
        plan = dict(state.get("query_plan") or {})
        plan["adjustments"] = adjustments
        plan["cities"] = list(next_cities)
        plan["keyword"] = next_keyword
        return {
            "cities": next_cities,
            "keyword": next_keyword,
            "adjustments": adjustments,
            "warnings": warnings,
            "query_plan": plan,
            "was_adjusted": True,
        }

    async def _normalize(self, state: SearchState) -> SearchState:
        await self.runtime.emit_stage("search_normalizing", 55)
        raw = state.get("raw") or []
        valid = [posting for posting in raw if _valid_posting(posting)]
        deduped, merged = _dedupe(valid)
        deduped = deduped[: self.max_total]
        warnings = list(state.get("warnings") or [])
        if len(deduped) >= self.max_total:
            warnings.append(f"RESULT_TRUNCATED_TO_LIMIT:{self.max_total}")
        if merged:
            warnings.append(f"MULTI_SOURCE_MERGED:{merged}")
        dedupe_info = {"rawCount": len(raw), "validCount": len(valid), "uniqueCount": len(deduped), "mergedCount": merged}
        return {"deduped": deduped, "warnings": warnings, "dedupe": dedupe_info}

    async def _understand(self, state: SearchState) -> SearchState:
        await self.runtime.emit_stage("understanding_jd", 70)
        postings = list(state.get("deduped") or [])
        warnings = list(state.get("warnings") or [])
        calls = int(state.get("model_calls") or 0)
        degraded = bool(state.get("model_degraded"))
        if not postings:
            return {"deduped": postings, "warnings": warnings, "model_calls": calls, "model_degraded": degraded}
        catalog = {
            str(item.get("code")): str(item.get("name") or item.get("code"))
            for item in (self.runtime.payload.get("skillCatalog") or [])
            if item.get("code")
        }
        skill = get_skill("JOB_SEARCH")
        profile_for_model = {
            "targetRole": self.runtime.profile.get("targetRole"),
            "candidateType": self.runtime.profile.get("candidateType"),
            "cities": self.runtime.profile.get("cities") or [],
            "education": (self.runtime.profile.get("facts") or {}).get("education"),
            "experienceMonths": (self.runtime.profile.get("facts") or {}).get("experienceMonths"),
            "skills": [
                item.get("skillCode")
                for item in ((self.runtime.profile.get("facts") or {}).get("skills") or [])
                if item.get("skillCode")
            ],
        }
        understood: list[SourcePosting] = []
        for start in range(0, len(postings), UNDERSTAND_BATCH):
            batch = postings[start : start + UNDERSTAND_BATCH]
            body = {
                "profile": profile_for_model,
                "skillCatalog": [{"code": code, "name": name} for code, name in catalog.items()],
                "postings": [
                    {
                        "sourceKey": item.source_key,
                        "sourceUrl": item.source_url,
                        "title": item.title,
                        "companyName": item.company_name,
                        "jdText": item.jd_text[:MODEL_JD_CHARS],
                    }
                    for item in batch
                ],
            }
            try:
                response = await self.runtime.llm.complete(
                    LLMRequest(
                        operation="JOB_SEARCH",
                        system_prompt=skill.system_prompt,
                        user_prompt=skill.render_user_prompt(body),
                        schema_hint=skill.schema_hint,
                        context={"payload": body},
                    )
                )
                calls += 1
                parsed = parse_json_object(response.text)
                by_key = {
                    (str(item.get("sourceKey")), str(item.get("sourceUrl"))): item
                    for item in parsed.get("items") or []
                }
                understood.extend(_apply_understanding(batch, by_key, catalog, warnings))
            except Exception as exc:
                degraded = True
                logger.warning("job search understanding degraded batch=%s error=%s", start, type(exc).__name__)
                understood.extend(_rule_understanding(item, catalog) for item in batch)
                warnings.append(f"MODEL_UNDERSTANDING_DEGRADED:{start // UNDERSTAND_BATCH + 1}")
        for posting in understood:
            if posting.semantic_score is None and "SEMANTIC_SCORE_UNAVAILABLE" not in warnings:
                warnings.append("SEMANTIC_SCORE_UNAVAILABLE")
        return {
            "deduped": understood,
            "warnings": _dedupe_values(warnings, 50),
            "model_calls": calls,
            "model_degraded": degraded,
        }

    async def _finalize(self, state: SearchState) -> SearchState:
        await self.runtime.emit_stage("finalizing_result", 90)
        postings = state.get("deduped") or []
        postings.sort(key=lambda item: (item.fingerprint, item.source_key, item.source_url))
        warnings = list(state.get("warnings") or [])
        sources = state.get("sources") or []
        if any(item.get("status") == "UNAVAILABLE" for item in sources):
            warnings.append("SOME_SOURCES_UNAVAILABLE")
        if any(item.get("status") in {"FAILED", "PARTIAL"} for item in sources):
            warnings.append("SOME_SOURCES_PARTIAL_FAILURE")
        if not postings:
            warnings.append("EMPTY_RESULT")
        for posting in postings:
            _annotate_posting(posting, warnings)
        return {"deduped": postings, "warnings": _dedupe_values(warnings, 60), "sources": sources}


def _valid_posting(posting: SourcePosting) -> bool:
    return bool(
        posting.title.strip()
        and posting.company_name.strip()
        and posting.jd_text.strip()
        and posting.source_url.startswith(("http://", "https://"))
    )


def _dedupe(postings: list[SourcePosting]) -> tuple[list[SourcePosting], int]:
    order = {"OFFICIAL": 0, "AUTHORIZED": 1, "PUBLIC_SEARCH": 2, "USER_INPUT": 3}
    by_fingerprint: dict[str, list[SourcePosting]] = {}
    for posting in postings:
        by_fingerprint.setdefault(posting.fingerprint, []).append(posting)
    merged_count = 0
    result: list[SourcePosting] = []
    for group in by_fingerprint.values():
        group.sort(key=lambda item: (order.get(item.source_type, 9), -len(item.jd_text)))
        primary = group[0]
        aliases: list[dict[str, Any]] = []
        for other in group:
            aliases.append(
                {
                    "sourceKey": other.source_key,
                    "sourceName": other.source_name,
                    "sourceType": other.source_type,
                    "sourceUrl": other.source_url,
                    "publishedAt": other.published_at.isoformat() if other.published_at else None,
                    "fetchedAt": other.fetched_at.isoformat(),
                    "verification": other.verification,
                }
            )
            _fill_missing(primary, other)
            primary.warnings.extend(other.warnings)
        primary.aliases = _dedupe_dicts(aliases)
        primary.warnings = _dedupe_values(primary.warnings, 20)
        if len(group) > 1:
            merged_count += len(group) - 1
        result.append(primary)
    result.sort(key=lambda item: (item.fingerprint, item.source_key, item.source_url))
    return result, merged_count


def _fill_missing(primary: SourcePosting, other: SourcePosting) -> None:
    for field in (
        "city_name",
        "city_code",
        "candidate_type",
        "salary_min_monthly_cents",
        "salary_max_monthly_cents",
        "salary_text",
        "experience_text",
        "experience_months_min",
        "education_text",
        "education_min",
        "published_at",
    ):
        if getattr(primary, field) in (None, "") and getattr(other, field) not in (None, ""):
            setattr(primary, field, getattr(other, field))
    if len(other.jd_text) > len(primary.jd_text):
        primary.jd_text = other.jd_text


def _dedupe_values(values: list[str], limit: int) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        text = str(value).strip()
        if not text or text in seen:
            continue
        seen.add(text)
        result.append(text)
        if len(result) >= limit:
            break
    return result


def _dedupe_dicts(values: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[tuple[str, str]] = set()
    result: list[dict[str, Any]] = []
    for item in values:
        key = (str(item.get("sourceKey")), str(item.get("sourceUrl")))
        if key in seen:
            continue
        seen.add(key)
        result.append(item)
    return result


def _apply_understanding(
    batch: list[SourcePosting],
    by_key: dict[tuple[str, str], dict[str, Any]],
    catalog: dict[str, str],
    warnings: list[str],
) -> list[SourcePosting]:
    result: list[SourcePosting] = []
    for posting in batch:
        data = by_key.get((posting.source_key, posting.source_url))
        if data is None:
            warnings.append("MODEL_ITEM_MISSING")
            result.append(_rule_understanding(posting, catalog))
            continue
        requirements = _validated_requirements(data.get("requirements"), posting.jd_text, catalog, warnings)
        posting.requirements = requirements.model_dump(by_alias=True, mode="json") if requirements else None
        score = data.get("semanticScore")
        if isinstance(score, bool) or not isinstance(score, (int, float)):
            posting.semantic_score = None
        else:
            posting.semantic_score = max(0.0, min(float(score), 1.0))
        posting.reasons = _dedupe_values([str(item)[:500] for item in (data.get("reasons") or [])], 5)
        allowed = set(requirements.skills) if requirements else set()
        gaps = []
        for gap in data.get("gaps") or []:
            code = str(gap.get("skillCode") or "")
            suggestion = str(gap.get("suggestion") or "").strip()
            if code in allowed and suggestion:
                gaps.append({"skillCode": code, "suggestion": suggestion[:500]})
        posting.gaps = gaps[:20]
        result.append(posting)
    return result


def _validated_requirements(
    raw: Any,
    jd_text: str,
    catalog: dict[str, str],
    warnings: list[str],
) -> Requirement | None:
    if not isinstance(raw, dict):
        return None
    try:
        requirement = Requirement.model_validate(raw)
    except Exception:
        warnings.append("REQUIREMENTS_DROPPED:SCHEMA")
        return None
    quotes = dict(requirement.quotes or {})
    for key, quote in list(quotes.items()):
        if not quote or quote not in jd_text:
            quotes.pop(key, None)
    allowed_quote_keys = {"educationMin", "graduationYearMin", "graduationYearMax", "experienceMonthsMin"}
    quotes = {key: value for key, value in quotes.items() if key in allowed_quote_keys}
    for field in ("education_min", "graduation_year_min", "graduation_year_max", "experience_months_min"):
        value = getattr(requirement, field)
        if value is None:
            continue
        quote_key = {
            "education_min": "educationMin",
            "graduation_year_min": "graduationYearMin",
            "graduation_year_max": "graduationYearMax",
            "experience_months_min": "experienceMonthsMin",
        }[field]
        if quote_key not in quotes:
            setattr(requirement, field, None)
            warnings.append(f"REQUIREMENT_DROPPED_NO_QUOTE:{quote_key}")
    skills = [code for code in requirement.skills if code in catalog]
    if len(skills) != len(requirement.skills):
        warnings.append("UNKNOWN_SKILLS_DROPPED")
    requirement.skills = _dedupe_values(skills, 50)
    requirement.quotes = quotes
    requirement.required_skill_levels = {
        code: value
        for code, value in requirement.required_skill_levels.items()
        if code in requirement.skills and isinstance(value, int) and 0 <= value <= 100
    }
    return requirement


def _rule_understanding(posting: SourcePosting, catalog: dict[str, str]) -> SourcePosting:
    jd = posting.jd_text
    skills = []
    for code, name in catalog.items():
        pattern = re.compile(rf"(?<![A-Za-z0-9_]){re.escape(code)}(?![A-Za-z0-9_])", re.IGNORECASE)
        if pattern.search(jd) or (name and name.lower() in jd.lower()):
            skills.append(code)
    skills = _dedupe_values(skills, 50)
    experience_months = posting.experience_months_min
    quotes: dict[str, str] = {}
    exp_match = re.search(r"[^\n。；;]{0,40}(\d{1,2}\s*年(?:以上|及以上|\+)[^\n。；;]{0,20})", jd)
    if exp_match and experience_months is not None:
        quotes["experienceMonthsMin"] = exp_match.group(0)[:200]
    education = posting.education_min
    if education:
        label = next((item for item in ("本科", "硕士", "博士", "大专", "高中") if item in jd), None)
        if label:
            edu_match = re.search(rf"[^\n。；;]{{0,40}}{label}[^\n。；;]{{0,40}}", jd)
            if edu_match:
                quotes["educationMin"] = edu_match.group(0)[:200]
    posting.requirements = {
        "educationMin": education,
        "graduationYearMin": None,
        "graduationYearMax": None,
        "experienceMonthsMin": experience_months,
        "skills": skills,
        "requiredSkillLevels": {},
        "quotes": quotes,
    }
    if not posting.reasons:
        posting.reasons = ["确定性规则降级：硬性条件仅在 JD 原文可核验时保留，未生成模型语义评分"]
    posting.gaps = []
    return posting


def _annotate_posting(posting: SourcePosting, warnings: list[str]) -> None:
    if posting.verification != "VERIFIED":
        posting.warnings.append("UNVERIFIED_SOURCE")
    if not posting.salary_min_monthly_cents or not posting.salary_max_monthly_cents:
        posting.warnings.append("MISSING_SALARY")
    if posting.published_at is None:
        posting.warnings.append("MISSING_PUBLISHED_AT")
    elif (datetime.now(timezone.utc) - posting.published_at).days > 90:
        posting.warnings.append("POSSIBLY_EXPIRED")
    if not posting.education_min:
        posting.warnings.append("MISSING_EDUCATION")
    if not posting.experience_text and posting.experience_months_min is None:
        posting.warnings.append("MISSING_EXPERIENCE")
    if normalize_company_size(posting.company_size) == "SIZE_UNKNOWN":
        posting.warnings.append("COMPANY_SIZE_UNKNOWN")
    posting.warnings = _dedupe_values(posting.warnings, 20)


def _items_to_result(postings: list[SourcePosting]) -> list[dict[str, Any]]:
    items = []
    for posting in postings:
        items.append(
            {
                "sourceKey": posting.source_key,
                "sourceName": posting.source_name,
                "sourceType": posting.source_type,
                "sourceUrl": posting.source_url,
                "title": posting.title,
                "companyName": posting.company_name,
                "cityName": posting.city_name,
                "cityCode": posting.city_code,
                "candidateType": posting.candidate_type,
                "salaryMinMonthlyCents": posting.salary_min_monthly_cents,
                "salaryMaxMonthlyCents": posting.salary_max_monthly_cents,
                "salaryText": posting.salary_text,
                "experienceText": posting.experience_text,
                "experienceMonthsMin": posting.experience_months_min,
                "educationText": posting.education_text,
                "educationMin": posting.education_min,
                "companySize": normalize_company_size(posting.company_size),
                "companySizeLabel": COMPANY_SIZE_LABELS.get(normalize_company_size(posting.company_size)),
                "publishedAt": posting.published_at.isoformat() if posting.published_at else None,
                "fetchedAt": posting.fetched_at.isoformat(),
                "jdText": posting.jd_text[:30000],
                "requirements": posting.requirements,
                "semanticScore": posting.semantic_score,
                "reasons": posting.reasons,
                "gaps": posting.gaps,
                "verification": posting.verification,
                "warnings": posting.warnings,
                "fingerprint": posting.fingerprint,
                "aliases": posting.aliases,
            }
        )
    return items