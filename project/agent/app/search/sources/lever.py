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

LEVER_HOSTS = frozenset({"api.lever.co"})
DEFAULT_SITES: dict[str, str] = {}
MAX_SITE_QUERIES = 5


class LeverSource(SourceAdapter):
    key = "lever"
    name = "公司招聘官网（Lever ATS）"
    type = "OFFICIAL"
    verification = "VERIFIED"
    available = True
    allowed_hosts = LEVER_HOSTS

    def sites(self, settings: Any) -> dict[str, str]:
        raw = str(getattr(settings, "job_search_lever_boards", "") or "").strip()
        if not raw:
            return dict(DEFAULT_SITES)
        result: dict[str, str] = {}
        for item in raw.split(","):
            part = item.strip()
            if not part:
                continue
            token, _, company = part.partition(":")
            token = token.strip()
            if token:
                result[token] = (company.strip() or token.title())
        return result

    async def search(self, context: SourceContext) -> SourceResult:
        started = time.monotonic()
        if context.client is None:
            return self.result("FAILED", message="HTTP 客户端未初始化", started=started)
        sites = self.sites(context.settings)
        if not sites:
            return self.result("EMPTY", message="未配置 Lever 企业 board", started=started)
        limit = int(context.extra.get("lever_site_limit", MAX_SITE_QUERIES))
        failures: list[str] = ["已达到职位列表查询预算"] if len(sites) > limit else []
        successes = 0
        postings: list[SourcePosting] = []
        for token, company in list(sites.items())[:limit]:
            url = f"https://api.lever.co/v0/postings/{token}"
            try:
                await ensure_public_url(url, allowed_hosts=set(LEVER_HOSTS))
                response = await context.client.get(
                    url,
                    params={"mode": "json"},
                    timeout=context.timeout_seconds,
                    headers={"User-Agent": context.user_agent, "Accept": "application/json"},
                    follow_redirects=False,
                )
            except Exception as exc:  # noqa: BLE001 - 单个企业失败不影响其他企业
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
            if not isinstance(body, list):
                failures.append(f"{company}: 缺少当前岗位列表")
                continue
            successes += 1
            registry = context.extra.get("company_registry")
            if registry is not None:
                registry.record(f"lever:{token}", "AVAILABLE", len(body))
            for row in body:
                posting = _to_posting(row, company)
                if posting is not None:
                    postings.append(posting)
        postings = _filter_keyword(filter_postings(postings, context.filters), context.keyword)
        if not postings:
            if successes == 0:
                return self.result("FAILED", message="; ".join(failures)[:400] or "Lever 接口不可用", started=started)
            if failures:
                return self.result("PARTIAL", message="; ".join(failures)[:400], started=started)
            return self.result("EMPTY", message="Lever 接口可用，但当前条件下没有匹配岗位", started=started)
        if failures:
            return self.result("PARTIAL", postings[: context.limit], message="; ".join(failures)[:400], started=started)
        return self.result("AVAILABLE", postings[: context.limit],
                           message=f"Lever 返回 {len(postings)} 条", started=started)


def _filter_keyword(postings: list[SourcePosting], keyword: str) -> list[SourcePosting]:
    return [posting for posting in postings if keyword_relevant(posting, keyword)]


def _to_posting(job: dict[str, Any], company: str) -> SourcePosting | None:
    if not isinstance(job, dict):
        return None
    post_id = str(job.get("id") or "").strip()
    title = str(job.get("text") or "").strip()
    hosted = canonical_detail(str(job.get("hostedUrl") or ""))
    categories = job.get("categories") if isinstance(job.get("categories"), dict) else {}
    jd_parts = [
        str(job.get("descriptionPlain") or ""),
        _strip_html(str(job.get("description") or "")),
        str(job.get("additionalPlain") or ""),
        _strip_html(str(job.get("additional") or "")),
    ]
    for item in job.get("lists") or []:
        if isinstance(item, dict):
            jd_parts.append(str(item.get("text") or ""))
            jd_parts.append(_strip_html(str(item.get("content") or "")))
    jd = "\n".join(" ".join(part.split()) for part in jd_parts if part and part.strip())[:30000]
    checked_at = datetime.now(timezone.utc)
    if not post_id or not title or not jd or not hosted or closed_text(f"{title} {jd}", checked_at):
        return None
    location = str(categories.get("location") or "").strip() or None
    candidate_type, inferred = infer_candidate_type(title, str(categories.get("commitment") or ""), jd)
    salary_min, salary_max = parse_salary(jd)
    published = _parse_time(job.get("createdAt") or job.get("updatedAt"))
    warnings: list[str] = []
    if inferred:
        warnings.append("CANDIDATE_TYPE_INFERRED")
    if published is None:
        warnings.append("MISSING_PUBLISHED_AT")
    posting = SourcePosting(
        source_key=LeverSource.key,
        source_name=LeverSource.name,
        source_type=LeverSource.type,
        source_url=hosted,
        title=title,
        company_name=company,
        jd_text=jd,
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
    )
    official_list(hosted, checked_at=checked_at, detail="Lever 官方 board 当前职位列表包含该岗位").apply(posting)
    return posting


def _strip_html(value: str) -> str:
    text = html.unescape(value)
    text = re.sub(r"<(script|style)[^>]*>.*?</\1>", " ", text, flags=re.IGNORECASE | re.DOTALL)
    text = re.sub(r"<[^>]+>", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def _parse_time(value: Any) -> datetime | None:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        timestamp = float(value)
        if timestamp > 10_000_000_000:
            timestamp /= 1000
        try:
            return datetime.fromtimestamp(timestamp, tz=timezone.utc)
        except (OSError, ValueError):
            return None
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)