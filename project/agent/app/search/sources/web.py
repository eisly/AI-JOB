from __future__ import annotations

import asyncio
import difflib
import json
import re
import time
from datetime import datetime, timezone
from functools import lru_cache
from pathlib import Path
from urllib.parse import urlsplit

from ...llm import LLMRequest, parse_json_object
from ..budget import BudgetConfig, SearchBudget
from ..expansion import CANDIDATE_LABELS, CITY_NAMES, CoverageMatrix, QuerySpec, build_query_specs
from ..models import (
    SourcePosting,
    SourceResult,
    city_code_for,
    infer_candidate_type,
    parse_company_size,
    parse_salary,
)
from ..providers import ProviderResult, SearchHit, resolve_providers
from ..relevance import compact as compact_text
from ..verification import (
    STATUS_CLOSED,
    STATUS_PROBABLE,
    STATUS_UNKNOWN,
    closed,
    detail_page,
    failure,
    snippet_only,
)
from .base import SourceAdapter, SourceContext, partition_postings
from .detail import DetailResult, canonical_detail, closed_text, fetch_detail


# 兼容旧常量（外部仍可能引用）；实际执行由动态预算控制。
MAX_QUERIES = 12
PAGES_PER_BATCH = 5
MAX_PAGE_CHARS = 6500
MAX_WEB_PAGES = 60
DETAIL_CONCURRENCY = 4
EXTRACT_PROMPT_PATH = (
    Path(__file__).resolve().parents[2] / "skills" / "prompts" / "job_search_extract.md"
)


@lru_cache(maxsize=1)
def extraction_system_prompt() -> str:
    text = EXTRACT_PROMPT_PATH.read_text(encoding="utf-8").replace("\r\n", "\n").lstrip("\ufeff")
    if text.startswith("---\n"):
        end = text.find("\n---\n", 4)
        if end >= 0:
            text = text[end + 5 :]
    marker = "## 任务规则"
    start = text.find(marker)
    if start < 0:
        raise ValueError("岗位提取提示词缺少任务规则章节")
    body = text[start + len(marker) :]
    next_section = body.find("\n## ")
    return (body[:next_section] if next_section >= 0 else body).strip()


def role_variants(keyword: str) -> list[str]:
    from ..expansion import expand_keyword

    return expand_keyword(keyword, limit=8)


def build_queries(context: SourceContext) -> list[str]:
    specs = build_query_specs(
        context.filters,
        context.filters.keyword or context.keyword,
        limit=max(MAX_QUERIES, len(context.filters.cities) or 1),
    )
    result = [spec.query for spec in specs]
    alternative = context.extra.get("alternative_keyword")
    if alternative and alternative not in result:
        result.append(alternative)
    return list(dict.fromkeys(result))


class WebSearchSource(SourceAdapter):
    key = "web"
    name = "AI 全网搜索"
    requires_credentials = True

    async def search(self, context: SourceContext) -> SourceResult:
        started = time.monotonic()
        settings = context.settings
        providers = resolve_providers(settings)
        if not providers:
            return self.unavailable("Agent 未配置 TAVILY_API_KEY 或 BRAVE_SEARCH_API_KEY")
        if context.client is None or context.extra.get("llm") is None:
            return self.result("FAILED", message="搜索客户端未初始化", started=started)

        budget: SearchBudget = context.extra.get("budget") or SearchBudget(BudgetConfig.from_settings(settings))
        if budget.round_no <= 0:
            # 直接调用来源（脚本/单测）时也要有本轮预算；pipeline 调用时已初始化。
            budget.begin_round()
        specs: list[QuerySpec] = context.extra.get("query_specs") or build_query_specs(
            context.filters,
            context.filters.keyword or context.keyword,
            limit=max(budget.config.max_queries_per_round, len(context.filters.cities) or 1),
            freshness_days=budget.config.freshness_days,
        )
        coverage: CoverageMatrix = context.extra.get("coverage") or CoverageMatrix(
            specs, [provider.key for provider in providers]
        )
        executed: set[str] = context.extra.setdefault("executed_queries", set())

        candidates = [spec for spec in specs if spec.query not in executed]
        query_limit = max(0, min(budget.queries_remaining(), budget.config.max_queries_per_round))
        selected_specs = candidates[:query_limit]
        if not selected_specs and not context.extra.get("pending_pages"):
            return self.result("EMPTY", message="没有未执行的补搜查询", started=started)
        executed.update(spec.query for spec in selected_specs)
        budget.record_query(len(selected_specs))

        counts = {
            "queries": len(selected_specs),
            "providerCount": len(providers),
            "discovered": 0,
            "checked": 0,
            "confirmedOpen": 0,
            "probable": 0,
            "closed": 0,
            "unknown": 0,
            "cacheHits": 0,
        }
        provider_counts: dict[str, int] = {}
        semaphore = asyncio.Semaphore(context.extra.get("concurrency", 4))
        retries = budget.config.provider_retries

        async def retrieve(spec: QuerySpec, provider) -> tuple[ProviderResult, list[SearchHit]]:
            cached = budget.cached(provider.key, spec.query)
            if cached is not None:
                coverage.mark_query(spec, provider.key)
                coverage.mark_result(spec, provider.key, len(cached))
                return ProviderResult(
                    key=provider.key, name=provider.name, status="OK" if cached else "EMPTY",
                    hits=cached, message="命中查询缓存", query=spec.query,
                ), cached
            async with semaphore:
                if not budget.can_call():
                    return ProviderResult(key=provider.key, name=provider.name, status="FAILED",
                                          message="已达到 API 调用预算", query=spec.query), []
                budget.record_api_call()
                budget.start_stage("search")
                try:
                    result = await provider.search(
                        context.client,
                        spec.query,
                        settings=settings,
                        limit=min(20, max(10, context.limit // max(1, len(selected_specs)))),
                        timeout=context.timeout_seconds,
                        freshness=spec.freshness,
                        retries=retries,
                    )
                except asyncio.TimeoutError:
                    result = ProviderResult(key=provider.key, name=provider.name, status="FAILED",
                                            message="搜索请求超时", query=spec.query)
                except Exception as exc:  # noqa: BLE001 - provider 必须降级而不是中断整条链路
                    result = ProviderResult(key=provider.key, name=provider.name, status="FAILED",
                                            message=f"搜索请求失败（{type(exc).__name__}）", query=spec.query)
                finally:
                    budget.end_stage("search")
            if result.ok:
                budget.remember(provider.key, spec.query, result.hits)
                coverage.mark_query(spec, provider.key)
                coverage.mark_result(spec, provider.key, len(result.hits))
            return result, result.hits

        results = await asyncio.gather(
            *(retrieve(spec, provider) for spec in selected_specs for provider in providers)
        ) if selected_specs else []

        provider_results = [item[0] for item in results]
        for result in provider_results:
            if result.status in {"OK", "EMPTY", "RATE_LIMITED"}:
                provider_counts[result.key] = provider_counts.get(result.key, 0) + len(result.hits)
            if result.status in {"FAILED", "RATE_LIMITED"}:
                counts.setdefault("providerFailures", 0)
                counts["providerFailures"] += 1
        counts["cacheHits"] = budget.cache_hits
        counts["searchResults"] = provider_counts
        if selected_specs and provider_results and all(
            item.status in {"FAILED", "RATE_LIMITED", "UNCONFIGURED"} for item in provider_results
        ):
            first = next((item for item in provider_results if item.http_status), provider_results[0])
            message = first.message or "搜索 API 请求失败"
            result = self.result("FAILED", message=message, http_status=first.http_status, started=started)
            result.verification_counts = counts
            result.providers = [provider.key for provider in providers]
            return result


        lists = [item[1] for item in results]
        links = _merge_hits(lists)
        if state_pages := context.extra.get("pending_pages"):
            links.update(state_pages)
        discovered_urls: set[str] = context.extra.setdefault("discovered_urls", set())
        discovered_urls.update(links)
        counts["discovered"] = len(discovered_urls)
        counts["uniqueUrls"] = len(discovered_urls)

        by_domain: dict[str, list[tuple[str, dict]]] = {}
        for url, page in links.items():
            by_domain.setdefault(urlsplit(url).netloc, []).append((url, page))
        pending = [group[index] for index in range(max(map(len, by_domain.values()), default=0))
                   for group in by_domain.values() if index < len(group)]
        remaining = budget.pages_remaining()
        selected = pending[:remaining]
        context.extra["pending_pages"] = {
            url: page for url, page in pending[len(selected):]
        }
        budget.record_page(len(selected))
        counts["pagesSelected"] = len(selected)
        page_list = [(url, {**page, "pageIndex": index}) for index, (url, page) in enumerate(selected)]
        batches = [page_list[index:index + PAGES_PER_BATCH] for index in range(0, len(page_list), PAGES_PER_BATCH)]

        async def extract(batch):
            async with semaphore:
                return await asyncio.wait_for(
                    _extract_postings(context, batch),
                    timeout=min(20, max(5.0, context.timeout_seconds + 8)),
                )

        parsed = await asyncio.gather(*(extract(batch) for batch in batches), return_exceptions=True) if batches else []
        extraction_failures = 0
        diagnostics: dict[str, object] = {
            "modelRows": 0,
            "accepted": 0,
            "rejected": {},
            "noRowPages": 0,
            "modelErrors": 0,
            "missingCompany": 0,
            "missingType": 0,
            "fuzzyJd": 0,
        }
        rejected: dict[str, int] = {}
        pages_with_posting: set[str] = set()
        raw_postings: list[SourcePosting] = []
        spec_by_query = {spec.query: spec for spec in (context.extra.get("query_specs") or selected_specs)}
        for batch, item in zip(batches, parsed):
            if isinstance(item, Exception):
                extraction_failures += 1
                diagnostics["modelErrors"] = int(diagnostics["modelErrors"]) + len(batch)
                diagnostics["noRowPages"] = int(diagnostics["noRowPages"]) + len(batch)
                continue
            batch_postings, diag = item
            for reason, count in (diag.get("rejected") or {}).items():
                rejected[reason] = rejected.get(reason, 0) + int(count)
            for field in ("modelRows", "noRowPages", "missingCompany", "missingType", "fuzzyJd"):
                diagnostics[field] = int(diagnostics.get(field, 0)) + int(diag.get(field, 0))
            diagnostics["accepted"] = int(diagnostics["accepted"]) + len(batch_postings)
            for query, entry in (diag.get("perQuery") or {}).items():
                spec = spec_by_query.get(query)
                if spec is not None:
                    coverage.mark_analysis(
                        spec,
                        analyzed=int(entry.get("analyzed", 0)),
                        extracted=int(entry.get("extracted", 0)),
                        rejected=int(entry.get("rejected", 0)),
                    )
            for posting in batch_postings:
                raw_postings.append(posting)
                pages_with_posting.add(posting.source_url)
                counts["extractionSuccess"] = counts.get("extractionSuccess", 0) + 1
        diagnostics["rejected"] = rejected
        counts["pagesExtracted"] = len(selected)
        counts["pagesWithPosting"] = len(pages_with_posting)
        postings, filter_stats = partition_postings(raw_postings, context.filters)
        counts["hardFilterPass"] = len(postings)
        counts["filterStats"] = filter_stats
        counts["extractionDiagnostics"] = diagnostics

        await _verify_details(context, postings, budget, counts)
        counts["confirmedOpen"] = sum(item.verification_status == "ACTIVE_CONFIRMED" for item in postings)
        counts["probable"] = sum(item.verification_status == STATUS_PROBABLE for item in postings)
        counts["closed"] = sum(item.verification_status == STATUS_CLOSED for item in postings)
        counts["unknown"] = sum(item.verification_status == STATUS_UNKNOWN for item in postings)
        counts["extractionFailures"] = extraction_failures
        counts["providerStatus"] = [item.as_status() for item in provider_results]

        failures = [item for item in provider_results if item.status in {"FAILED", "RATE_LIMITED"}]
        query_limit_reached = bool(candidates[query_limit:])
        partial = bool(failures or extraction_failures or query_limit_reached)
        status = "PARTIAL" if partial else "AVAILABLE" if postings else "EMPTY"
        domains = len({urlsplit(url).netloc for url in links})
        message = (
            f"引擎 {','.join(provider.key for provider in providers)} 发现 {len(links)} 个链接"
            f"（{domains} 个域名），分析 {len(selected)} 页，筛选后 {len(postings)} 个岗位"
        )
        if counts["probable"] or counts["confirmedOpen"]:
            message += f"；已确认 {counts['confirmedOpen']}，待确认 {counts['probable']}"
        if failures:
            message += f"；{len(failures)} 个检索请求失败"
        if extraction_failures:
            message += f"；{extraction_failures} 个解析批次失败"
        if diagnostics["noRowPages"]:
            top_reason = max(rejected.items(), key=lambda item: item[1])[0] if rejected else "模型未提取"
            message += (f"；{diagnostics['noRowPages']} 页未提取到岗位"
                        f"（规则拒绝 {sum(rejected.values())} 条，主要原因 {top_reason}）")
        if query_limit_reached:
            message += "；已达到本轮查询预算，可在下一轮补搜"
        result = self.result(status, postings, message=message, started=started)
        result.verification_counts = counts
        result.providers = [provider.key for provider in providers]
        return result


def _merge_hits(lists: list[list[SearchHit]]) -> dict[str, dict]:
    links: dict[str, dict] = {}
    for hits in lists:
        for hit in hits:
            url = canonical_detail(hit.url)
            title = " ".join(str(hit.title or "").split())[:350]
            body = " ".join(str(hit.content or "").replace("**", "").split())[:MAX_PAGE_CHARS]
            if not url or not title or not body:
                continue
            current = links.get(url)
            if current is None:
                links[url] = {
                    "title": title,
                    "body": body,
                    "providers": [hit.provider],
                    "queries": [hit.query],
                    "query": hit.query,
                    "publishedAt": hit.published_at.isoformat() if hit.published_at else None,
                }
                continue
            if hit.provider not in current["providers"]:
                current["providers"].append(hit.provider)
            if hit.query and hit.query not in current["queries"]:
                current["queries"].append(hit.query)
            if len(body) > len(current["body"]):
                current["body"] = body
    return links


async def _extract_postings(context: SourceContext, batch: list[tuple[str, dict]]) -> tuple[list[SourcePosting], dict]:
    response = await context.extra["llm"].complete(LLMRequest(
        operation="JOB_SEARCH",
        system_prompt=extraction_system_prompt(),
        user_prompt=json.dumps({
            "keyword": context.filters.keyword or context.keyword,
            "searchKeyword": context.keyword,
            "candidateTypes": [CANDIDATE_LABELS[item] for item in context.filters.candidate_types],
            "cities": [CITY_NAMES.get(code, code) for code in context.filters.cities],
            "pages": [page for _, page in batch],
        }, ensure_ascii=False),
        schema_hint="postings",
    ))
    rows = parse_json_object(response.text).get("postings")
    if not isinstance(rows, list):
        raise ValueError("缺少岗位提取结果")
    pages = {str(page["pageIndex"]): (url, page) for url, page in batch}
    diagnostics: dict[str, object] = {
        "modelRows": len(rows),
        "accepted": 0,
        "rejected": {},
        "noRowPages": 0,
        "missingCompany": 0,
        "missingType": 0,
        "fuzzyJd": 0,
        "perQuery": {},
    }
    per_query: dict[str, dict[str, int]] = {}
    def page_queries(page: dict) -> list[str]:
        values = page.get("queries")
        if isinstance(values, list) and values:
            return [str(item) for item in values if item]
        query = str(page.get("query") or "")
        return [query] if query else []

    for url, page in batch:
        for query in page_queries(page):
            entry = per_query.setdefault(query, {"analyzed": 0, "extracted": 0, "rejected": 0})
            entry["analyzed"] += 1
    rejected: dict[str, int] = {}

    def record_reject(page: dict, reason: str) -> None:
        rejected[reason] = rejected.get(reason, 0) + 1
        for query in page_queries(page):
            entry = per_query.setdefault(query, {"analyzed": 0, "extracted": 0, "rejected": 0})
            entry["rejected"] += 1

    postings: list[SourcePosting] = []
    seen_pages: set[str] = set()
    seen_rows: set[str] = set()
    for row in rows:
        if not isinstance(row, dict):
            continue
        page_index = str(row.get("pageIndex"))
        if page_index not in pages:
            continue
        seen_pages.add(page_index)
        if page_index in seen_rows:
            record_reject(pages[page_index][1], "DUPLICATE_PAGE_ROW")
            continue
        url, page = pages[page_index]
        posting, reason, flags = _to_posting(row, url, page)
        if posting is None:
            record_reject(page, reason or "UNKNOWN_REJECT")
            continue
        postings.append(posting)
        seen_rows.add(page_index)
        for query in page_queries(page):
            entry = per_query.setdefault(query, {"analyzed": 0, "extracted": 0, "rejected": 0})
            entry["extracted"] += 1
        if flags.get("missingCompany"):
            diagnostics["missingCompany"] = int(diagnostics["missingCompany"]) + 1
        if flags.get("missingType"):
            diagnostics["missingType"] = int(diagnostics["missingType"]) + 1
        if flags.get("fuzzyJd"):
            diagnostics["fuzzyJd"] = int(diagnostics["fuzzyJd"]) + 1
    for page_index, (_, page) in pages.items():
        if page_index not in seen_pages:
            diagnostics["noRowPages"] = int(diagnostics["noRowPages"]) + 1
            record_reject(page, "MODEL_NO_ROW")
    diagnostics["accepted"] = len(postings)
    diagnostics["rejected"] = rejected
    diagnostics["perQuery"] = per_query
    return postings, diagnostics


async def _verify_details(context: SourceContext, postings: list[SourcePosting],
                           budget: SearchBudget, counts: dict) -> None:

    config = budget.config
    if not config.detail_verification:
        for posting in postings:
            if posting.verification_status != STATUS_CLOSED:
                snippet_only(posting.source_url).apply(posting)
        counts["detailChecks"] = 0
        return
    targets = [posting for posting in postings if posting.verification_status != STATUS_CLOSED]
    remaining = budget.details_remaining()
    selected = targets[:remaining]

    counts["detailChecks"] = len(selected)
    if not selected:
        return
    semaphore = asyncio.Semaphore(max(1, min(config.detail_concurrency, context.extra.get("concurrency", 4))))
    page_timeout = min(12.0, max(4.0, context.timeout_seconds))

    async def check(posting: SourcePosting) -> None:
        async with semaphore:
            if not budget.can_call():
                snippet_only(posting.source_url).apply(posting)
                return
            budget.record_detail()
            budget.record_api_call()
            budget.start_stage("detail")
            try:
                result = await fetch_detail(context.client, posting.source_url, page_timeout)
            except Exception:  # noqa: BLE001 - 单页异常不得中断整条搜索链路
                result = DetailResult(status=STATUS_UNKNOWN, reason="PAGE_ACCESS_FAILED",
                                      url=posting.source_url, detail="详情页核验异常")
            finally:
                budget.end_stage("detail")
            _apply_detail(posting, result)

    await asyncio.gather(*(check(posting) for posting in selected), return_exceptions=True)


def _apply_detail(posting: SourcePosting, result: DetailResult) -> None:
    if result.status == STATUS_CLOSED:
        closed(posting.source_url, result.reason, http_status=result.http_status,
               detail=result.detail).apply(posting)
    elif result.status == STATUS_PROBABLE:
        detail_page(posting.source_url, apply_link=result.apply_link,
                    http_status=result.http_status or 200, detail=result.detail).apply(posting)
    else:
        failure(posting.source_url, result.reason, http_status=result.http_status,
                detail=result.detail).apply(posting)


def _normalize_quote(value: object, limit: int) -> str | None:
    if not isinstance(value, str):
        return None
    text = " ".join(value.split())
    return text[:limit] if text else None


def _title_matches(job_title: str, page_title: str) -> bool:
    """允许搜索标题被截断或带站点后缀，但要求核心字符高度重合。"""
    left = compact_text(job_title)
    right = compact_text(page_title)
    if not left or not right:
        return False
    if left in right or right in left:
        return True
    left_grams = {left[index:index + 2] for index in range(max(0, len(left) - 1))}
    right_grams = {right[index:index + 2] for index in range(max(0, len(right) - 1))}
    if not left_grams:
        return False
    return len(left_grams & right_grams) / len(left_grams) >= 0.6


def _source_quote(body: str, value: object, limit: int) -> tuple[str | None, bool]:
    """返回页面可确认的引文；排版差异用最长公共片段确认，并标记为模糊命中。"""
    quote = _normalize_quote(value, limit)
    if not quote:
        return None, False
    compact_quote = compact_text(quote)
    compact_body = compact_text(body)
    if compact_quote and compact_quote in compact_body:
        return quote, False
    if not compact_quote or not compact_body:
        return None, False
    match = difflib.SequenceMatcher(None, compact_quote, compact_body, autojunk=False).find_longest_match(
        0, len(compact_quote), 0, len(compact_body)
    )
    if match.size >= max(12, int(len(compact_quote) * 0.65)):
        return quote, True
    return None, False


def _to_posting(row: dict, url: str, page: dict) -> tuple[SourcePosting | None, str | None, dict]:
    body = " ".join(page["body"].split())
    page_title = " ".join(str(page.get("title") or "").split())
    text = f"{page_title} {body}".casefold()
    compact_text_body = compact_text(text)
    flags = {"missingCompany": False, "missingType": False, "fuzzyJd": False}

    title = _normalize_quote(row.get("title"), 200)
    if not title:
        return None, "TITLE_MISSING", flags
    if not _title_matches(title, page_title):
        return None, "TITLE_MISMATCH", flags

    company = _normalize_quote(row.get("companyName"), 200)
    if not company or not compact_text(company) or compact_text(company) not in compact_text_body:
        host = urlsplit(url).hostname or ""
        company = (host[4:] if host.startswith("www.") else host)[:200]
        if not company:
            return None, "COMPANY_MISSING", flags
        flags["missingCompany"] = True

    city = _normalize_quote(row.get("cityName"), 100)
    if city and compact_text(city) not in compact_text_body:
        city = None

    passages = row.get("jdQuotes")
    jd_parts: list[str] = []
    if isinstance(passages, list):
        for value in passages[:4]:
            snippet, fuzzy = _source_quote(body, value, 500)
            if snippet:
                jd_parts.append(snippet)
                flags["fuzzyJd"] = flags["fuzzyJd"] or fuzzy
    jd = "\n".join(dict.fromkeys(jd_parts))
    if not jd:
        return None, "JD_QUOTE_MISMATCH", flags
    if len(jd) < 35:
        return None, "JD_TOO_SHORT", flags

    type_text = _normalize_quote(row.get("candidateTypeText"), 200)
    candidate_type, known = infer_candidate_type(title)
    if not known:
        candidate_type, known = infer_candidate_type(type_text)
    if not known:
        # 不编造招聘类型；缺失交给 Java 端标记 UNKNOWN，而不是直接淘汰。
        candidate_type = None
        flags["missingType"] = True

    city_code = city_code_for(city)
    if city and not city_code:
        city_code = city if len(city) == 6 and city.isascii() and city.isdigit() else None
    size = parse_company_size(_normalize_quote(row.get("companySizeText"), 80))
    salary = _normalize_quote(row.get("salaryText"), 200)
    salary_min, salary_max = parse_salary(salary)
    published_at = None
    published = _normalize_quote(row.get("publishedDate"), 40)
    if published:
        try:
            date_text = published.replace("年", "-").replace("月", "-").replace("日", "").replace("/", "-")
            parsed = datetime.fromisoformat(date_text)
            published_at = parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)
        except ValueError:
            pass
    fetched_at = datetime.now(timezone.utc)
    warnings: list[str] = []
    if flags["missingCompany"]:
        warnings.append("COMPANY_FROM_DOMAIN")
    if flags["missingType"]:
        warnings.append("CANDIDATE_TYPE_UNKNOWN")
    if flags["fuzzyJd"]:
        warnings.append("JD_QUOTE_FUZZY")
    posting = SourcePosting(
        source_key=WebSearchSource.key, source_name=urlsplit(url).hostname or WebSearchSource.name,
        source_type=WebSearchSource.type, source_url=url,
        title=title, company_name=company, jd_text=jd,
        city_name=city, city_code=city_code, candidate_type=candidate_type,
        company_size=size, salary_text=salary, salary_min_monthly_cents=salary_min,
        salary_max_monthly_cents=salary_max, published_at=published_at,
        fetched_at=fetched_at,
        search_providers=list(page.get("providers") or []),
        query=page.get("query"),
        recruitment_status="UNKNOWN",
        warnings=warnings,
    )
    snippet_closed = closed_text(text, fetched_at)
    if snippet_closed:
        closed(url, "OFFICIAL_CLOSED", checked_at=fetched_at,
               detail="搜索结果摘要明确声明岗位已下线或截止").apply(posting)
    else:
        snippet_only(url, checked_at=fetched_at).apply(posting)
    return posting, None, flags