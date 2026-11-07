from __future__ import annotations

import html
import re
import time
from datetime import datetime, timezone
from typing import Any

from ..models import (
    SourcePosting,
    city_code_for,
    infer_candidate_type,
    parse_company_size,
    parse_experience_months,
    parse_salary,
)
from ..security import ensure_public_url
from ..relevance import keyword_relevant
from ..verification import official_list
from .base import SourceAdapter, SourceContext, filter_postings
from .detail import canonical_detail, closed_text

GREENHOUSE_HOSTS = frozenset({"boards-api.greenhouse.io"})
DEFAULT_BOARDS = {"stripe": "Stripe"}
MAX_BOARD_QUERIES = 3


class GreenhouseSource(SourceAdapter):
    key = "greenhouse"
    name = "公司招聘官网（Greenhouse ATS）"
    type = "OFFICIAL"
    verification = "VERIFIED"
    available = True
    allowed_hosts = GREENHOUSE_HOSTS

    def boards(self, settings: Any) -> dict[str, str]:
        raw = str(getattr(settings, "job_search_greenhouse_boards", "") or "").strip()
        if not raw:
            return dict(DEFAULT_BOARDS)
        result: dict[str, str] = {}
        for item in raw.split(","):
            part = item.strip()
            if not part:
                continue
            token, _, company = part.partition(":")
            token = token.strip()
            if token:
                result[token] = (company.strip() or token.title())
        return result or dict(DEFAULT_BOARDS)

    async def search(self, context: SourceContext) -> SourceResult:
        started = time.monotonic()
        if context.client is None:
            return self.result("FAILED", message="HTTP 客户端未初始化", started=started)
        boards = self.boards(context.settings)
        board_limit = int(context.extra.get("greenhouse_board_limit", MAX_BOARD_QUERIES))
        postings: list[SourcePosting] = []
        failures: list[str] = ["已达到职位列表查询预算"] if len(boards) > board_limit else []
        successes = 0
        per_board = max(1, context.limit)
        for token, company in list(boards.items())[:board_limit]:
            url = f"https://boards-api.greenhouse.io/v1/boards/{token}/jobs"
            try:
                await ensure_public_url(url, allowed_hosts=set(GREENHOUSE_HOSTS))
                response = await context.client.get(
                    url,
                    params={"content": "true"},
                    timeout=context.timeout_seconds,
                    headers={"User-Agent": context.user_agent, "Accept": "application/json"},
                    follow_redirects=False,
                )
            except Exception as exc:
                failures.append(f"{company}: {type(exc).__name__}")
                continue
            if response.status_code != 200:
                failures.append(f"{company}: HTTP {response.status_code}")
                continue
            try:
                body = response.json()
            except ValueError:
                failures.append(f"{company}: 非 JSON 响应")
                continue
            jobs = body.get("jobs") if isinstance(body, dict) else None
            if not isinstance(jobs, list):
                failures.append(f"{company}: 缺少当前岗位列表")
                continue
            successes += 1
            registry = context.extra.get("company_registry")
            if registry is not None:
                registry.record(f"greenhouse:{token}", "AVAILABLE", len(jobs))
            for job in jobs:
                posting = _to_posting(job, company)
                if posting is not None:
                    postings.append(posting)
            if len(_filter_keyword(filter_postings(postings, context.filters), context.keyword)) >= per_board:
                break
        postings = _filter_keyword(filter_postings(postings, context.filters), context.keyword)
        if not postings:
            if successes == 0:
                return self.result("FAILED", message="; ".join(failures)[:400] or "ATS 接口不可用", started=started)
            if failures:
                return self.result("PARTIAL", message="; ".join(failures)[:400], started=started)
            return self.result("EMPTY", message="ATS 官方接口可用，但当前条件下没有匹配岗位", started=started)
        if failures:
            return self.result("PARTIAL", postings[: context.limit], message="; ".join(failures)[:400], started=started)
        return self.result("AVAILABLE", postings[: context.limit], message=f"ATS 官方接口返回 {len(postings)} 条", started=started)


def _filter_keyword(postings: list[SourcePosting], keyword: str) -> list[SourcePosting]:
    return [posting for posting in postings if keyword_relevant(posting, keyword)]


def _to_posting(job: dict[str, Any], company: str) -> SourcePosting | None:
    if not isinstance(job, dict) or not str(job.get("id", "")).isdigit():
        return None
    title = str(job.get("title") or "").strip()
    raw_content = str(job.get("content") or "").strip()
    url = canonical_detail(str(job.get("absolute_url") or ""))
    jd = _strip_html(raw_content)
    checked_at = datetime.now(timezone.utc)
    if not title or not jd or not url or closed_text(f"{title} {jd}", checked_at):
        return None
    location = str((job.get("location") or {}).get("name") or "").strip() or None
    candidate_type, inferred = infer_candidate_type(title, jd)
    salary_min, salary_max = parse_salary(jd)
    published = _parse_time(job.get("updated_at") or job.get("first_published"))
    warnings: list[str] = []
    if inferred:
        warnings.append("CANDIDATE_TYPE_INFERRED")
    if published is None:
        warnings.append("MISSING_PUBLISHED_AT")
    return posting_with_evidence(SourcePosting(
        source_key=GreenhouseSource.key,
        source_name=GreenhouseSource.name,
        source_type=GreenhouseSource.type,
        source_url=url,
        title=title,
        company_name=company,
        jd_text=jd[:30000],
        city_name=location,
        city_code=city_code_for(location),
        candidate_type=candidate_type,
        salary_min_monthly_cents=salary_min,
        salary_max_monthly_cents=salary_max,
        salary_text=None,
        experience_text=None,
        experience_months_min=parse_experience_months(jd),
        education_text=None,
        education_min=None,
        company_size=parse_company_size(jd),
        published_at=published,
        fetched_at=checked_at,
        verification="VERIFIED",
        recruitment_evidence="OFFICIAL_LIST",
        warnings=warnings,
    ), checked_at)


def posting_with_evidence(posting: SourcePosting, checked_at: datetime) -> SourcePosting:
    official_list(posting.source_url, checked_at=checked_at).apply(posting)
    return posting


def _strip_html(value: str) -> str:
    text = html.unescape(value)
    text = re.sub(r"<(script|style)[^>]*>.*?</\1>", " ", text, flags=re.IGNORECASE | re.DOTALL)
    text = re.sub(r"<[^>]+>", " ", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def _parse_time(value: Any) -> datetime | None:
    if not value:
        return None
    text = str(value).replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)
