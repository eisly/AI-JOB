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
    CITY_CODES,
    SourcePosting,
    SourceResult,
    city_code_for,
    education_from_text,
    infer_candidate_type,
    parse_company_size,
    parse_experience_months,
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
PAGES_PER_BATCH = 3
MAX_PAGE_CHARS = 6500
MAX_EXTRACT_PAGE_CHARS = 3500
MAX_EXTRACT_BATCH_CHARS = 12000
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


def _page_batches(page_list: list[tuple[str, dict]], *, max_pages: int, max_chars: int):
    batches: list[list[tuple[str, dict]]] = []
    current: list[tuple[str, dict]] = []
    current_chars = 0
    for item in page_list:
        page = item[1]
        size = len(str(page.get("title") or "")) + min(len(str(page.get("body") or "")), MAX_EXTRACT_PAGE_CHARS)
        if current and (len(current) >= max_pages or current_chars + size > max_chars):
            batches.append(current)
            current = []
            current_chars = 0
        current.append(item)
        current_chars += size
    if current:
        batches.append(current)
    return batches


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
        pending_state = context.extra.setdefault("pending_pages", {})
        completed = context.extra.setdefault("completed_urls", set())

        candidates = [spec for spec in specs if spec.query not in executed]
        query_limit = max(0, min(budget.queries_remaining(), budget.config.max_queries_per_round))
        selected_specs = [] if self.key == "company_official" and context.extra.get("supplemental") and pending_state else candidates[:query_limit]
        if not selected_specs and not pending_state:
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
            "fetchAttempted": 0,
            "fetchSucceeded": 0,
            "fetchFailed": 0,
            "fetchFailureReasons": {},
            "fetchBodyChars": 0,
            "renderUsed": 0,
        }
        context.extra["round_counts"] = counts
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
                all_hits: list[SearchHit] = []
                page = 0
                last_result: ProviderResult | None = None
                pages_allowed = max(1, int(getattr(settings, "job_search_provider_pages", 1))) if provider.supports_pagination else 1
                per_query_limit = min(
                    provider.max_results,
                    max(10, int(getattr(settings, "job_search_provider_results", context.limit))),
                )
                failed_attempts = 0
                while page < min(10, pages_allowed):
                    if not budget.can_call():
                        if last_result is None:
                            return ProviderResult(key=provider.key, name=provider.name, status="FAILED",
                                                  message="已达到 API 调用预算", query=spec.query), []
                        break
                    budget.record_api_call()
                    budget.start_stage("search")
                    try:
                        current = await provider.search(
                            context.client,
                            spec.query,
                            settings=settings,
                            limit=per_query_limit,
                            timeout=context.timeout_seconds,
                            freshness=spec.freshness,
                            retries=0,
                            page=page,
                        )
                    except asyncio.TimeoutError:
                        current = ProviderResult(key=provider.key, name=provider.name, status="FAILED",
                                                 message="搜索请求超时", query=spec.query, page=page)
                    except Exception as exc:  # noqa: BLE001 - provider 必须降级而不是中断整条链路
                        current = ProviderResult(key=provider.key, name=provider.name, status="FAILED",
                                                 message=f"搜索请求失败（{type(exc).__name__}）", query=spec.query, page=page)
                    finally:
                        budget.end_stage("search")
                    last_result = current
                    if not current.ok:
                        if (failed_attempts < retries and
                                (current.http_status is None or current.http_status in {429, 500, 502, 503, 504})):
                            failed_attempts += 1
                            continue
                        break
                    failed_attempts = 0
                    all_hits.extend(current.hits)
                    page = current.next_page if current.next_page is not None else pages_allowed
                result = last_result or ProviderResult(
                    key=provider.key, name=provider.name, status="FAILED", query=spec.query,
                    message="搜索请求未执行",
                )
                result.hits = all_hits
                result.page = 0
                result.next_page = None
            if result.ok:
                budget.remember(provider.key, spec.query, result.hits)
            if result.ok or result.hits:
                coverage.mark_query(spec, provider.key)
                coverage.mark_result(spec, provider.key, len(result.hits))
            return result, result.hits

        results = await asyncio.gather(
            *(retrieve(spec, provider) for spec in selected_specs for provider in providers)
        ) if selected_specs else []

        provider_results = [item[0] for item in results]
        for result in provider_results:
            provider_counts[result.key] = provider_counts.get(result.key, 0) + len(result.hits)
            if result.status in {"FAILED", "RATE_LIMITED"}:
                counts.setdefault("providerFailures", 0)
                counts["providerFailures"] += 1
        counts["cacheHits"] = budget.cache_hits
        counts["searchResults"] = provider_counts
        counts["providerStatus"] = [item.as_status() for item in provider_results]
        if not pending_state and not any(item.hits for item in provider_results) and selected_specs and provider_results and all(
            item.status in {"FAILED", "RATE_LIMITED", "UNCONFIGURED"} for item in provider_results
        ):
            first = next((item for item in provider_results if item.http_status), provider_results[0])
            message = first.message or "搜索 API 请求失败"
            result = self.result("FAILED", message=message, http_status=first.http_status, started=started)
            result.verification_counts = counts
            result.providers = [provider.key for provider in providers]
            return result


        lists = [item[1] for item in results]
        hits_by_url = _merge_hits(lists)
        discovered_urls: set[str] = context.extra.setdefault("discovered_urls", set())
        discovered_urls.update(hits_by_url)
        rejected_urls = context.extra.setdefault("rejected_urls", set())
        rejected_urls.update(url for url, page in hits_by_url.items() if not _job_page_candidate(url, page["title"]))
        counts["rejectedNonJobUrls"] = len(rejected_urls)
        links = {url: page for url, page in hits_by_url.items() if url not in completed and url not in rejected_urls}
        for url, page in pending_state.items():
            if url in links:
                page["queries"] = list(dict.fromkeys([*page["queries"], *links[url]["queries"]]))
            links[url] = page
        counts["discovered"] = len(discovered_urls)
        counts["uniqueUrls"] = len(discovered_urls)

        by_domain: dict[str, list[tuple[str, dict]]] = {}
        for url, page in links.items():
            by_domain.setdefault(urlsplit(url).netloc, []).append((url, page))
        pending = [group[index] for index in range(max(map(len, by_domain.values()), default=0))
                   for group in by_domain.values() if index < len(group)]
        remaining = budget.pages_remaining()
        hard_cap = 6 if self.key == "company_official" else budget.config.max_pages_per_round
        selected = pending[:min(remaining, hard_cap)]
        pending_state.update(links)
        budget.record_page(len(selected))
        counts["pagesSelected"] = len(selected)
        # 详情页正文是薪资/规模/学历/公司名的主要来源；预抓正文与核验预算分开。
        fetched_so_far = int(getattr(budget, "page_fetches", 0))
        fetch_remaining = (budget.fetches_remaining() if hasattr(budget, "fetches_remaining")
                           else max(0, budget.config.max_page_fetch - fetched_so_far))
        fetch_targets = selected[:fetch_remaining]
        actual_fetches = [0]
        page_timeout = min(12.0, max(4.0, context.timeout_seconds))

        async def prefetch(item: tuple[str, dict]) -> None:
            url, page = item
            async with semaphore:
                if not budget.can_call():
                    return

                cached = url in context.extra.get("detail_cache", {})
                if not cached:
                    if hasattr(budget, "fetches_remaining") and budget.fetches_remaining() <= 0:
                        return
                    budget.record_api_call()
                    actual_fetches[0] += 1
                    budget.page_fetches = int(getattr(budget, "page_fetches", 0)) + 1
                    counts["fetchAttempted"] += 1
                budget.start_stage("detail")
                try:
                    detail = await _fetch_detail_cached(context, url, page_timeout)
                except Exception as exc:  # noqa: BLE001 - 单页失败不影响其他候选
                    if not cached:
                        counts["fetchFailed"] += 1
                        reason = type(exc).__name__
                        counts["fetchFailureReasons"][reason] = counts["fetchFailureReasons"].get(reason, 0) + 1
                    return
                finally:
                    budget.end_stage("detail")
                if detail.page and detail.page.get("body"):
                    text = " ".join(str(detail.page["body"]).split())
                    if detail.page.get("title"):
                        page["title"] = detail.page["title"]
                    if len(text) > len(page.get("body") or ""):
                        page["body"] = text[:MAX_PAGE_CHARS]
                        page["title"] = detail.page.get("title") or page.get("title")
                    if len(text) >= 100:
                        page["body"] = text[:MAX_PAGE_CHARS]
                    if detail.page.get("fields"):
                        page["fields"] = detail.page["fields"]
                    if detail.page.get("job"):
                        page["job"] = detail.page["job"]
                    if not cached:
                        counts["fetchSucceeded"] += 1
                        counts["fetchBodyChars"] += len(text)
                    if detail.page.get("renderUsed"):
                        counts["renderUsed"] += 1
                elif not cached:
                    counts["fetchFailed"] += 1
                    reason = detail.reason or "EMPTY_BODY"
                    counts["fetchFailureReasons"][reason] = counts["fetchFailureReasons"].get(reason, 0) + 1

        await asyncio.gather(*(prefetch(item) for item in fetch_targets))
        counts["pagesFetched"] = actual_fetches[0]
        page_list = [(url, {**page, "pageIndex": index}) for index, (url, page) in enumerate(selected)]
        batch_pages = max(1, int(getattr(settings, "job_search_web_batch_pages", PAGES_PER_BATCH)))
        batch_chars = max(1000, int(getattr(settings, "job_search_web_batch_chars", MAX_EXTRACT_BATCH_CHARS)))
        batches = _page_batches(page_list, max_pages=batch_pages, max_chars=batch_chars)

        async def extract(batch):
            async with semaphore:
                deterministic = _deterministic_postings(context, batch)
                partial, _ = partition_postings(deterministic[0], context.filters)
                checkpoint = context.extra.setdefault("partial_postings", [])
                checkpoint.extend(partial)
                result = await _extract_postings(context, batch, deterministic=deterministic)
                allowed, _ = partition_postings(result[0], context.filters)
                pending_state_before = {p.source_url for p in partial}
                checkpoint.extend(p for p in allowed if p.source_url not in pending_state_before)
                for url, _ in batch:
                    pending_state.pop(url, None)
                    completed.add(url)
                counts["pagesAnalyzed"] = counts.get("pagesAnalyzed", 0) + len(batch)
                counts["pagesWithPosting"] = counts.get("pagesWithPosting", 0) + len(result[0])
                return result

        parsed = await asyncio.gather(*(extract(batch) for batch in batches), return_exceptions=True) if batches else []
        extraction_failures = 0
        diagnostics: dict[str, object] = {
            "modelRows": 0,
            "modelAccepted": 0,
            "deterministicAccepted": 0,
            "accepted": 0,
            "rejected": {},
            "noRowPages": 0,
            "modelErrors": 0,
            "modelFailures": 0,
            "modelLatencyMs": 0,
            "modelInputTokens": 0,
            "modelOutputTokens": 0,
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
                fallback, fallback_rejected = _deterministic_postings(context, batch)
                if not fallback:
                    extraction_failures += 1
                    diagnostics["modelErrors"] = int(diagnostics["modelErrors"]) + len(batch)
                    diagnostics["noRowPages"] = int(diagnostics["noRowPages"]) + len(batch)
                    diagnostics["modelFailures"] = int(diagnostics["modelFailures"]) + 1
                    for reason, count in fallback_rejected.items():
                        rejected[reason] = rejected.get(reason, 0) + int(count)
                    continue
                diag = {
                    "modelRows": 0,
                    "modelAccepted": 0,
                    "deterministicAccepted": len(fallback),
                    "rejected": fallback_rejected,
                    "noRowPages": max(0, len(batch) - len(fallback)),
                    "modelErrors": len(batch),
                    "modelFailures": 1,
                    "perQuery": {
                        query: {
                            "analyzed": sum(1 for _, page in batch if query in _page_queries(page)),
                            "extracted": sum(1 for posting in fallback if query in _page_queries(_page_for_url(batch, posting.source_url))),
                            "rejected": 0,
                        }
                        for query in {query for _, page in batch for query in _page_queries(page)}
                    },
                }
                batch_postings = fallback
            else:
                batch_postings, diag = item
            for reason, count in (diag.get("rejected") or {}).items():
                rejected[reason] = rejected.get(reason, 0) + int(count)
            for field in ("modelRows", "modelAccepted", "deterministicAccepted", "noRowPages",
                          "modelErrors", "modelFailures", "modelAttempts", "modelLatencyMs", "modelInputTokens",
                          "modelOutputTokens", "missingCompany", "missingType", "fuzzyJd"):
                diagnostics[field] = int(diagnostics.get(field, 0)) + int(diag.get(field, 0) or 0)
            diagnostics["accepted"] = int(diagnostics["accepted"]) + len(batch_postings)
            valid_batch, _ = partition_postings(batch_postings, context.filters)
            for query, entry in (diag.get("perQuery") or {}).items():
                spec = spec_by_query.get(query)
                if spec is not None:
                    verified_yield = sum(
                        query in _page_queries(_page_for_url(batch, posting.source_url))
                        and (not spec.city_code or posting.city_code == spec.city_code)
                        and (not spec.candidate_type or posting.candidate_type == spec.candidate_type)
                        and not posting.is_closed
                        for posting in valid_batch
                    )
                    coverage.mark_analysis(
                        spec,
                        analyzed=int(entry.get("analyzed", 0)),
                        extracted=verified_yield,
                        rejected=int(entry.get("rejected", 0)),
                    )
            for posting in batch_postings:
                raw_postings.append(posting)
                pages_with_posting.add(posting.source_url)
                counts["extractionSuccess"] = counts.get("extractionSuccess", 0) + 1
        diagnostics["rejected"] = rejected
        counts["pagesAnalyzed"] = sum(len(batch) for batch, outcome in zip(batches, parsed)
                                      if not isinstance(outcome, Exception))
        counts["pagesExtracted"] = len(pages_with_posting)
        counts["modelPagesAccepted"] = int(diagnostics.get("modelAccepted", 0) or 0)
        counts["deterministicPagesAccepted"] = int(diagnostics.get("deterministicAccepted", 0) or 0)
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
        model_failures = int(diagnostics.get("modelFailures", 0) or 0)
        counts["pendingUrls"] = len(pending_state)
        partial = bool(failures or extraction_failures or query_limit_reached or model_failures
                       or diagnostics["modelErrors"] or pending_state)
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
            message += f"；{extraction_failures} 个解析批次异常"
        if model_failures:
            message += f"；模型批次失败 {model_failures} 次，确定性兜底 {int(diagnostics.get('deterministicAccepted', 0) or 0)} 条"
        if diagnostics["noRowPages"]:
            top_reason = max(rejected.items(), key=lambda item: item[1])[0] if rejected else "模型未提取"
            message += (f"；{diagnostics['noRowPages']} 页未提取到岗位"
                        f"（规则拒绝 {sum(rejected.values())} 条，主要原因 {top_reason}）")
        if query_limit_reached:
            message += "；已达到本轮查询预算"
        if pending_state:
            message += f"；仍有 {len(pending_state)} 个候选页面待处理"
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


def _job_page_candidate(url: str, title: str) -> bool:
    parsed = urlsplit(url)
    path = parsed.path.casefold()
    if re.search(r"/(?:salary|salaries|zhaopin|search|job-search|discuss)(?:/|$)", path):
        return False
    return not re.search(r"工资待遇|薪酬报告|职位汇总|招聘信息汇总|岗位合集", title)


def _page_queries(page: dict) -> list[str]:
    values = page.get("queries")
    if isinstance(values, list) and values:
        return [str(item) for item in values if item]
    query = str(page.get("query") or "")
    return [query] if query else []


def _clean_page_title(title: str) -> str:
    parts = [" ".join(part.split()) for part in re.split(r"\s*[-|｜]\s*", str(title or "")) if part.strip()]
    if not parts:
        return ""
    job_like = [part for part in parts if re.search(r"(开发|工程师|实习|校招|测试|算法|产品|设计|运营|数据|架构|研究|分析)", part)]
    return (job_like[0] if job_like else parts[0])[:200]


def _deterministic_row(url: str, page: dict) -> dict | None:
    body = " ".join(str(page.get("body") or "").split())
    html = str(page.get("html") or "")
    fields = dict(page.get("fields") or {})
    if html:
        from ..page_body import listing_fields, structured_job
        try:
            fields = {**listing_fields(html, url), **fields}
            job = structured_job(html, url) or {}
        except (ValueError, RecursionError):
            job = {}
    else:
        job = page.get("job") or {}
    title = _clean_page_title(str(job.get("title") or page.get("title") or ""))
    if not title or (not job and not re.search(r"(开发|工程师|实习|校招|测试|算法|产品|设计|运营|数据|架构|研究|分析)", title)):
        return None
    if not job and not re.search(r"岗位职责|工作职责|职位描述|岗位描述|工作内容|任职要求|职位要求|岗位要求", body):
        return None
    company = str(fields.get("company_name") or "").strip()
    if not company:
        organization = job.get("hiringOrganization") or {}
        if isinstance(organization, dict):
            company = str(organization.get("name") or "").strip()
    if not company:
        company = _company_from_body(body) or ""
    if not company and body:
        for part in re.split(r"\s*[-|｜]\s*", str(page.get("title") or "")):
            if 4 <= len(part) <= 40 and re.search(r"(公司|集团|科技|银行|研究院|大学|中心|工作室|事务所)", part):
                company = part.strip()
                break
    if not company:
        return None
    text = f"{title} {body}"
    city, _ = _extract_city(text)
    description = job.get("description")
    jd = ""
    if isinstance(description, str) and description.strip():
        from ..text_extract import _html_text
        description_text = _html_text(description)
        jd = " ".join(description_text.split())
    if not jd:
        jd = body
    if len(jd) < 35:
        return None
    row = {
        "title": title,
        "companyName": company,
        "cityName": city,
        "candidateTypeText": text[:200],
        "jdQuotes": [jd[i:i + 400] for i in range(0, min(len(jd), 1600), 400)],
        "_structuredJd": jd if job else None,
    }
    for source_key, target_key in (
        ("salary_text", "salaryText"),
        ("company_size_text", "companySizeText"),
        ("experience_text", "experienceText"),
        ("education_text", "educationText"),
    ):
        if fields.get(source_key):
            row[target_key] = fields[source_key]
    published = fields.get("published_at")
    if isinstance(published, datetime):
        row["publishedDate"] = published.isoformat()
    return row


def _deterministic_postings(context: SourceContext, batch: list[tuple[str, dict]]) -> tuple[list[SourcePosting], dict[str, int]]:
    postings: list[SourcePosting] = []
    rejected: dict[str, int] = {}
    for url, page in batch:
        row = _deterministic_row(url, page)
        if row is None:
            rejected["DETERMINISTIC_INSUFFICIENT"] = rejected.get("DETERMINISTIC_INSUFFICIENT", 0) + 1
            continue
        structured = row.pop("_structuredJd")
        evidence_page = {**page, "title": row["title"], "body": structured} if structured else page
        posting, reason, _ = _to_posting(row, url, evidence_page)
        if posting is None:
            key = f"DETERMINISTIC_{reason or 'UNKNOWN_REJECT'}"
            rejected[key] = rejected.get(key, 0) + 1
            continue
        posting.warnings.append("DETERMINISTIC_EXTRACTION")
        if structured:
            posting.company_name = row["companyName"]
            posting.warnings = [w for w in posting.warnings if w != "COMPANY_FROM_DOMAIN"]
            employment = (page.get("job") or {}).get("employmentType")
            values = employment if isinstance(employment, list) else [employment]
            if "INTERN" in values or "INTERNSHIP" in values:
                posting.candidate_type = "INTERNSHIP"
        postings.append(posting)
    return postings, rejected


async def _extract_postings(context: SourceContext, batch: list[tuple[str, dict]], *, deterministic=None) -> tuple[list[SourcePosting], dict]:
    settings = context.settings
    fallback, fallback_rejected = deterministic if deterministic is not None else _deterministic_postings(context, batch)
    fallback_urls = {p.source_url for p in fallback}
    model_batch = [(url, page) for url, page in batch if url not in fallback_urls]
    page_chars = max(800, int(getattr(settings, "job_search_web_page_chars", MAX_EXTRACT_PAGE_CHARS)))
    request = LLMRequest(
        operation="JOB_SEARCH",
        system_prompt=extraction_system_prompt(),
        user_prompt=json.dumps({
            "keyword": context.filters.keyword or context.keyword,
            "searchKeyword": context.keyword,
            "candidateTypes": [CANDIDATE_LABELS[item] for item in context.filters.candidate_types],
            "cities": [CITY_NAMES.get(code, code) for code in context.filters.cities],
            "pages": [
                {
                    "pageIndex": page["pageIndex"],
                    "title": str(page.get("title") or "")[:350],
                    "body": str(page.get("body") or "")[:page_chars],
                    "url": url,
                }
                for url, page in model_batch
            ],
        }, ensure_ascii=False),
        schema_hint="postings",
        reasoning_effort=str(getattr(settings, "job_search_extraction_reasoning_effort", "low")),
        max_output_tokens=int(getattr(settings, "job_search_extraction_max_output_tokens", 4096)),
        timeout_seconds=float(getattr(settings, "job_search_extraction_timeout_seconds", 18.0)),
        thinking=False,
    )
    pages = {str(page["pageIndex"]): (url, page) for url, page in model_batch}
    diagnostics: dict[str, object] = {
        "modelRows": 0,
        "modelAccepted": 0,
        "deterministicAccepted": 0,
        "accepted": 0,
        "rejected": {},
        "noRowPages": 0,
        "modelErrors": 0,
        "modelFailures": 0,
        "modelErrorType": None,
        "modelLatencyMs": 0,
        "modelInputTokens": None,
        "modelOutputTokens": None,
        "missingCompany": 0,
        "missingType": 0,
        "fuzzyJd": 0,
        "perQuery": {},
    }
    per_query: dict[str, dict[str, int]] = {}
    for url, page in batch:
        for query in _page_queries(page):
            entry = per_query.setdefault(query, {"analyzed": 0, "extracted": 0, "rejected": 0})
            entry["analyzed"] += 1
    rejected: dict[str, int] = dict(fallback_rejected)

    def record_reject(page: dict, reason: str) -> None:
        rejected[reason] = rejected.get(reason, 0) + 1
        for query in _page_queries(page):
            entry = per_query.setdefault(query, {"analyzed": 0, "extracted": 0, "rejected": 0})
            entry["rejected"] += 1

    rows: list[dict] = []
    retries = min(1, max(0, int(getattr(settings, "job_search_extraction_retries", 0))))
    last_error: Exception | None = None
    deadline = time.monotonic() + min(26.0, context.extra["budget"].remaining_seconds() if context.extra.get("budget") else 26.0)
    diagnostics["modelAttempts"] = 0
    availability = context.extra.get("model_availability", {})
    for attempt in range(retries + 1 if model_batch else 0):
        started = time.monotonic()
        remaining = deadline - started
        if remaining <= 0.1 or availability.get("blockedStatus"):
            break
        diagnostics["modelAttempts"] += 1
        try:
            response = await asyncio.wait_for(
                context.extra["llm"].complete(request),
                timeout=min(remaining, request.timeout_seconds),
            )
            diagnostics["modelLatencyMs"] = int((time.monotonic() - started) * 1000)
            diagnostics["modelInputTokens"] = response.input_tokens
            diagnostics["modelOutputTokens"] = response.output_tokens
            parsed = parse_json_object(response.text).get("postings")
            if not isinstance(parsed, list):
                raise ValueError("缺少岗位提取结果")
            rows = [item for item in parsed if isinstance(item, dict)]
            diagnostics["modelRows"] = len(rows)
            break
        except Exception as exc:
            last_error = exc
            if getattr(exc, "http_status", None) in {401, 402, 403}:
                availability["blockedStatus"] = exc.http_status
            diagnostics["modelFailures"] = int(diagnostics["modelFailures"]) + 1
            if isinstance(exc, TimeoutError) or getattr(exc, "retryable", True) is False:
                break
    blocked = availability.get("blockedStatus")
    if model_batch and not rows and (last_error or blocked):
        diagnostics["modelErrors"] = len(model_batch)
        diagnostics["modelErrorType"] = f"HTTP_{blocked}" if blocked else type(last_error).__name__
    diagnostics["deterministicAccepted"] = len(fallback)
    postings = list(fallback)
    for posting in fallback:
        for query in _page_queries(_page_for_url(batch, posting.source_url)):
            per_query[query]["extracted"] += 1
    seen_pages: set[str] = set()
    seen_rows: set[str] = set()
    for row in rows:
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
        diagnostics["modelAccepted"] = int(diagnostics["modelAccepted"]) + 1
        for query in _page_queries(page):
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
            record_reject(page, "MODEL_UNAVAILABLE" if blocked else "MODEL_FAILED" if last_error and not rows else "MODEL_NO_ROW")
    diagnostics["noRowPages"] = len(batch) - len(postings)
    diagnostics["accepted"] = len(postings)
    diagnostics["rejected"] = rejected
    diagnostics["perQuery"] = per_query
    return postings, diagnostics


def _page_for_url(batch: list[tuple[str, dict]], url: str) -> dict:
    for page_url, page in batch:
        if page_url == url:
            return page
    return {}


async def _fetch_detail_cached(context: SourceContext, url: str, timeout: float) -> DetailResult:
    cache: dict[str, DetailResult] = context.extra.setdefault("detail_cache", {})
    if url in cache:
        return cache[url]
    budget = context.extra.get("budget")
    if budget:
        timeout = min(timeout, budget.remaining_seconds())
    if timeout <= 0:
        return DetailResult(STATUS_UNKNOWN, "TIMEOUT", url, detail="搜索取数预算已用完")
    result = await fetch_detail(context.client, url, timeout, budget=budget) if budget else await fetch_detail(context.client, url, timeout)
    page = result.page or {}
    html = page.get("html", "")
    needs_render = result.http_status == 200 and (len(page.get("body", "")) < 100 or
                   (not page.get("fields") and "<script" in html))
    if needs_render and context.extra.setdefault("render_state", {"count": 0})["count"] < 2 and (not budget or budget.can_call()):
        from .render import render_detail
        context.extra["render_state"]["count"] += 1
        semaphore = context.extra.setdefault("render_semaphore", asyncio.Semaphore(2))
        async with semaphore:
            rendered = await render_detail(context.client, url, min(6, budget.remaining_seconds() if budget else 6), budget=budget)
        if rendered.page or not result.page:
            if rendered.page:
                rendered.page["renderUsed"] = True
            result = rendered
    cache[url] = result
    return result


async def _verify_details(context: SourceContext, postings: list[SourcePosting],
                           budget: SearchBudget, counts: dict) -> None:

    config = budget.config
    if not config.detail_verification:
        for posting in postings:
            if posting.verification_status != STATUS_CLOSED:
                snippet_only(posting.source_url).apply(posting)
        counts["detailChecks"] = 0
        return
    targets = list(postings)
    remaining = budget.details_remaining()
    selected = targets[:remaining]

    counts["detailChecks"] = len(selected)
    if not selected:
        return
    semaphore = asyncio.Semaphore(max(1, min(config.detail_concurrency, context.extra.get("concurrency", 4))))
    page_timeout = min(12.0, max(4.0, context.timeout_seconds))

    async def check(posting: SourcePosting) -> None:
        async with semaphore:
            cache: dict[str, DetailResult] = context.extra.setdefault("detail_cache", {})
            if posting.source_url in cache:
                result = cache[posting.source_url]
                _apply_detail(posting, result)
                if result.page and result.page.get("body"):
                    _enrich_from_page(posting, result.page)
                return
            if not budget.can_call():
                snippet_only(posting.source_url).apply(posting)
                return
            budget.record_detail()
            budget.record_api_call()
            budget.start_stage("detail")
            try:
                result = await _fetch_detail_cached(context, posting.source_url, page_timeout)
            except Exception:  # noqa: BLE001 - 单页异常不得中断整条搜索链路
                result = DetailResult(status=STATUS_UNKNOWN, reason="PAGE_ACCESS_FAILED",
                                      url=posting.source_url, detail="详情页核验异常")
            finally:
                budget.end_stage("detail")
            _apply_detail(posting, result)
            if result.page and result.page.get("body"):
                _enrich_from_page(posting, result.page)

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
        # Keep only the actual source span, never the model's extra requirements.
        offsets = [i for i, char in enumerate(body) for _ in compact_text(char)]
        if match.b + match.size <= len(offsets):
            return body[offsets[match.b]:offsets[match.b + match.size - 1] + 1], True
    return None, False


CITY_LABEL = re.compile(
    r"(?:工作地点|工作城市|办公地点|职位地点|上班地点|职位地址|工作地址|上班地址|地点|base)\s*[:：]?\s*([\u4e00-\u9fff]{2,16})",
    re.IGNORECASE,
)
SALARY_PATTERN = re.compile(r"(\d{2,6}(?:\.\d+)?\s*[-~至]\s*\d{2,6}(?:\.\d+)?\s*(?:[kK]|千|万|元)?(?:\s*/\s*(?:月|年|天))?)")
SIZE_PATTERN = re.compile(r"(\d{2,5}\s*[-~至]\s*\d{2,5}\s*人|\d{2,5}\s*人以上|少于\s*\d{2,5}\s*人)")
EXPERIENCE_PATTERN = re.compile(
    r"((?:\d{1,2}\s*[-~至]\s*\d{1,2}\s*年(?:经验)?)|(?:\d{1,2}\s*年(?:以上|及以上))|应届(?:毕业生)?|经验不限|无经验)"
)
EDUCATION_PATTERN = re.compile(r"([^\n。；;]{0,12}(?:本科|硕士|博士|大专|统招|学历不限)[^\n。；;]{0,12})")
DATE_PATTERN = re.compile(r"(20\d{2}\s*[-/年]\s*\d{1,2}\s*[-/月]\s*\d{1,2}\s*日?)")


def _extract_city(text: str) -> tuple[str | None, str | None]:
    """只从位置字段或职位摘要的城市位置读取，避免公司名被当作工作地点。"""
    for match in CITY_LABEL.finditer(text):
        candidate = match.group(1).strip()
        code = city_code_for(candidate)
        if code:
            return candidate[:100], code
    for match in re.finditer(r"(?<![\w\u4e00-\u9fff])([\u4e00-\u9fff]{2,4})(?:市)?(?=\s*(?:[-—·]\s*[\u4e00-\u9fff]{2,6}区|\s+本科|\s+大专|\s+硕士|\s+\d[-–]\d年))", text[:300]):
        code = city_code_for(match[1])
        if code:
            return match[1], code
    return None, None


def _resolve_company(row_company: str | None, page_title: str, body: str, url: str,
                     flags: dict) -> str | None:
    text = f"{page_title} {body}"
    compact_body = compact_text(text)
    if row_company:
        if compact_text(row_company) and compact_text(row_company) in compact_body:
            return row_company
        base = re.sub(r"(有限公司|股份有限公司|科技有限公司|集团|公司)$", "", row_company).strip()
        if base and compact_text(base) in compact_body:
            return row_company
    # 标题中带公司后缀的片段，且正文可确认，才作为公司名。
    for part in re.split(r"\s*[-|｜]\s*", page_title):
        part = part.strip()
        if 4 <= len(part) <= 40 and re.search(r"(公司|集团|科技|银行|研究院|大学|中心|工作室|事务所)", part):
            if compact_text(part) in compact_body:
                return part[:200]
    host = urlsplit(url).hostname or ""
    company = (host[4:] if host.startswith("www.") else host)[:200]
    if not company:
        return None
    flags["missingCompany"] = True
    return company


def _in_body(value: str | None, body: str) -> bool:
    return bool(value and compact_text(value) and compact_text(value) in compact_text(body))


def _find_salary_text(body: str) -> str | None:
    for match in SALARY_PATTERN.finditer(body):
        snippet = " ".join(match.group(1).split())
        if re.match(r"20\d{2}", snippet):
            continue
        if not re.search(r"[kK千万元]|/\s*(?:月|年|天)", snippet):
            continue
        return snippet[:200]
    return None


def _find_size_text(body: str) -> str | None:
    match = re.search(r"(?:公司规模|企业规模|人数规模|团队规模)\s*[:：]?\s*" + SIZE_PATTERN.pattern, body)
    return " ".join(match.group(1).split())[:80] if match else None


def _find_experience_text(body: str) -> str | None:
    match = EXPERIENCE_PATTERN.search(body)
    return " ".join(match.group(1).split())[:120] if match else None


def _find_education_text(body: str) -> str | None:
    match = EDUCATION_PATTERN.search(body)
    return " ".join(match.group(1).split())[:120] if match else None


def _find_published_text(body: str) -> str | None:
    match = re.search(r"(?:发布时间|发布日期|发布于|更新于)\s*[:：]?\s*" + DATE_PATTERN.pattern, body)
    return " ".join(match.group(1).split())[:40] if match else None


def _parse_published(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        date_text = value.replace("年", "-").replace("月", "-").replace("日", "").replace("/", "-")
        parsed = datetime.fromisoformat(date_text)
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _company_from_body(body: str) -> str | None:
    """从公司信息卡片/正文中识别带企业后缀的公司名。"""
    header = re.match(r"^([\u4e00-\u9fffA-Za-z0-9]{2,24}(?:科技|集团|银行|证券))\s+([^\s]{2,8})\s", body)
    if header and city_code_for(header[2]):
        return header[1]
    for match in re.finditer(
        r"([\u4e00-\u9fffA-Za-z0-9（）()·]{2,40}(?:有限责任公司|股份有限公司|有限公司))",
        body,
    ):
        candidate = re.sub(r"^(?:我们是|欢迎加入|关于|公司介绍)", "", match.group(1)).strip()
        if 4 <= len(candidate) <= 200:
            return candidate
    return None


def _enrich_from_page(posting: SourcePosting, page: dict) -> None:
    """详情页正文可确认时补齐缺失字段；只填空缺，不覆盖已有值。"""
    body = " ".join(str(page.get("body") or "").split())
    page_title = " ".join(str(page.get("title") or "").split())
    if not body:
        return
    if page_title and not _title_matches(posting.title, page_title):
        return
    text = f"{page_title} {body}"
    warnings = list(posting.warnings)
    if not posting.city_code:
        city, code = _extract_city(text)
        if city:
            posting.city_name = city
            posting.city_code = code
            warnings.append("CITY_FROM_DETAIL")
    host_like = bool(re.search(r"[A-Za-z]", posting.company_name or "")) and not re.search(
        r"[\u4e00-\u9fff]", posting.company_name or ""
    )
    if host_like:
        company = _resolve_company(None, page_title, body, posting.source_url, {})
        if not company or not re.search(r"[\u4e00-\u9fff]", company):
            company = _company_from_body(body)
        if company and re.search(r"[\u4e00-\u9fff]", company):
            posting.company_name = company
            warnings.append("COMPANY_FROM_DETAIL")
    if not posting.salary_text:
        salary = _find_salary_text(body)
        if salary:
            posting.salary_text = salary
            posting.salary_min_monthly_cents, posting.salary_max_monthly_cents = parse_salary(salary)
            warnings.append("SALARY_FROM_DETAIL")
    if not posting.company_size or posting.company_size == "SIZE_UNKNOWN":
        size_text = _find_size_text(body)
        size = parse_company_size(size_text)
        if size_text:
            posting.company_size_text = size_text
        if size != "SIZE_UNKNOWN":
            posting.company_size = size
            warnings.append("COMPANY_SIZE_FROM_DETAIL")
    if not posting.experience_text:
        experience = _find_experience_text(body)
        if experience:
            posting.experience_text = experience
            posting.experience_months_min = parse_experience_months(experience)
            warnings.append("EXPERIENCE_FROM_DETAIL")
    if not posting.education_text:
        education = _find_education_text(body)
        if education:
            posting.education_text = education
            posting.education_min = education_from_text(education)
            warnings.append("EDUCATION_FROM_DETAIL")
    if posting.published_at is None:
        published = _find_published_text(body)
        parsed = _parse_published(published)
        if parsed:
            posting.published_at = parsed
            warnings.append("PUBLISHED_AT_FROM_DETAIL")
    from ..evidence import GROUPS, record_fields
    for key, names in GROUPS.items():
        fields = page.get("fields") or {}
        if any(fields.get(n) not in (None, "", "SIZE_UNKNOWN") for n in names):
            for name in names:
                setattr(posting, name, fields.get(name))
            posting.field_sources.pop(key, None)
    posting.warnings = list(dict.fromkeys(warnings))
    record_fields(posting)


def _to_posting(row: dict, url: str, page: dict) -> tuple[SourcePosting | None, str | None, dict]:
    body = " ".join(page["body"].split())
    page_title = " ".join(str(page.get("title") or "").split())
    text = f"{page_title} {body}"
    compact_text_body = compact_text(text)
    flags = {
        "missingCompany": False, "missingType": False, "fuzzyJd": False,
        "cityFromText": False, "salaryFromText": False, "sizeFromText": False,
        "experienceFromText": False, "educationFromText": False, "publishedFromText": False,
    }

    title = _normalize_quote(row.get("title"), 200)
    if not title:
        return None, "TITLE_MISSING", flags
    if not _title_matches(title, page_title):
        return None, "TITLE_MISMATCH", flags

    company = _resolve_company(_normalize_quote(row.get("companyName"), 200), page_title, body, url, flags)
    if not company:
        return None, "COMPANY_MISSING", flags

    city = _normalize_quote(row.get("cityName"), 100)
    if city and not _in_body(city, text):
        city = None
    city_code = city_code_for(city) if city else None
    if not city_code:
        fallback_city, fallback_code = _extract_city(text)
        if fallback_city:
            city, city_code = fallback_city, fallback_code
            flags["cityFromText"] = True

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
    if not known and _in_body(type_text, body):
        candidate_type, known = infer_candidate_type(type_text)
    if not known:
        label = re.search(r"(?:职位类型|招聘类型|岗位类型|工作性质)\s*[:：]\s*(实习|全职|社招|校招|校园招聘)", body[:900])
        path = urlsplit(url).path
        if label:
            candidate_type, known = ("EXPERIENCED", True) if label[1] == "全职" else infer_candidate_type(label[1])
        elif "/society/" in path or "/social/" in path:
            candidate_type, known = "EXPERIENCED", True
    salary = _normalize_quote(row.get("salaryText"), 200)
    if not _in_body(salary, text):
        salary = _find_salary_text(body)
        flags["salaryFromText"] = bool(salary)
    salary_min, salary_max = parse_salary(salary)

    size_text = _normalize_quote(row.get("companySizeText"), 80)
    if not _in_body(size_text, text):
        size_text = _find_size_text(body)
        flags["sizeFromText"] = bool(size_text)
    size = parse_company_size(size_text)

    experience = _normalize_quote(row.get("experienceText"), 120)
    if not _in_body(experience, text):
        experience = _find_experience_text(body)
        flags["experienceFromText"] = bool(experience)
    experience_months = parse_experience_months(experience)
    if not known and experience_months is not None and experience_months >= 24:
        candidate_type, known = "EXPERIENCED", True
    if not known:
        candidate_type = None
        flags["missingType"] = True

    education_text = _normalize_quote(row.get("educationText"), 120)
    if not _in_body(education_text, text):
        education_text = _find_education_text(body)
        flags["educationFromText"] = bool(education_text)
    education_min = education_from_text(education_text)

    published = _normalize_quote(row.get("publishedDate"), 40)
    if not _in_body(published, text):
        published = _find_published_text(body)
        flags["publishedFromText"] = bool(published)
    published_at = _parse_published(published)

    fetched_at = datetime.now(timezone.utc)
    warnings: list[str] = []
    if flags["missingCompany"]:
        warnings.append("COMPANY_FROM_DOMAIN")
    if flags["missingType"]:
        warnings.append("CANDIDATE_TYPE_UNKNOWN")
    if flags["fuzzyJd"]:
        warnings.append("JD_QUOTE_FUZZY")
    for flag, label in (
        ("cityFromText", "CITY_FROM_TEXT"),
        ("salaryFromText", "SALARY_FROM_TEXT"),
        ("sizeFromText", "COMPANY_SIZE_FROM_TEXT"),
        ("experienceFromText", "EXPERIENCE_FROM_TEXT"),
        ("educationFromText", "EDUCATION_FROM_TEXT"),
        ("publishedFromText", "PUBLISHED_AT_FROM_TEXT"),
    ):
        if flags[flag]:
            warnings.append(label)
    posting = SourcePosting(
        source_key=WebSearchSource.key, source_name=urlsplit(url).hostname or WebSearchSource.name,
        source_type=WebSearchSource.type, source_url=url,
        title=title, company_name=company, jd_text=jd,
        city_name=city, city_code=city_code, candidate_type=candidate_type,
        company_size=size, salary_text=salary, salary_min_monthly_cents=salary_min,
        salary_max_monthly_cents=salary_max, company_size_text=size_text,
        experience_text=experience, experience_months_min=experience_months,
        education_text=education_text, education_min=education_min,
        published_at=published_at,
        fetched_at=fetched_at,
        search_providers=list(page.get("providers") or []),
        query=page.get("query"),
        recruitment_status="UNKNOWN",
        warnings=warnings,
    )
    _enrich_from_page(posting, page)
    snippet_closed = closed_text(text.casefold(), fetched_at)
    if snippet_closed:
        closed(url, "OFFICIAL_CLOSED", checked_at=fetched_at,
               detail="搜索结果摘要明确声明岗位已下线或截止").apply(posting)
    else:
        snippet_only(url, checked_at=fetched_at).apply(posting)
    return posting, None, flags
