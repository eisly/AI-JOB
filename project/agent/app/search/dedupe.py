from __future__ import annotations

import re
from typing import Any
from urllib.parse import parse_qsl, urlsplit

from .evidence import fill_groups

from .models import (
    SourcePosting,
    normalize_company,
    normalize_key,
    normalize_title,
    posting_fingerprint,
)

SOURCE_PRIORITY = {"OFFICIAL": 0, "AUTHORIZED": 1, "PUBLIC_SEARCH": 2, "USER_INPUT": 3}
JOB_ID_KEYS = {"id", "jobid", "positionid", "postid", "requisitionid", "gh_jid", "job_id"}
JD_SIMILARITY_THRESHOLD = 0.9


def canonical_url(url: str) -> str:
    from .sources.detail import canonical_detail

    return canonical_detail(url)


def job_identity(posting: SourcePosting) -> str | None:
    try:
        parsed = urlsplit(posting.source_url)
    except ValueError:
        return None
    ids = {value for key, value in parse_qsl(parsed.query) if key.casefold() in JOB_ID_KEYS and value}
    segments = [segment for segment in parsed.path.split("/") if segment]
    if segments and re.fullmatch(r"[0-9]{3,}", segments[-1]):
        ids.add(segments[-1])
    if not ids:
        return None
    return f"{posting.source_key}|{parsed.hostname or ''}|{sorted(ids)[0]}"


def jd_similarity(left: str, right: str) -> float:
    a = _shingles(left)
    b = _shingles(right)
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def _shingles(text: str) -> set[str]:
    compact = normalize_key(text or "")
    if not compact:
        return set()
    if len(compact) < 4:
        return {compact}
    return {compact[index:index + 3] for index in range(len(compact) - 2)}


class _UnionFind:
    def __init__(self, size: int) -> None:
        self.parent = list(range(size))

    def find(self, value: int) -> int:
        while self.parent[value] != value:
            self.parent[value] = self.parent[self.parent[value]]
            value = self.parent[value]
        return value

    def union(self, left: int, right: int) -> bool:
        root_left, root_right = self.find(left), self.find(right)
        if root_left == root_right:
            return False
        self.parent[root_right] = root_left
        return True


def dedupe_postings(postings: list[SourcePosting]) -> tuple[list[SourcePosting], dict[str, Any]]:
    ordered = sorted(
        postings,
        key=lambda item: (
            SOURCE_PRIORITY.get(item.source_type, 9),
            -_verification_rank(item),
            -len(item.jd_text),
        ),
    )
    finder = _UnionFind(len(ordered))
    by_url: dict[str, int] = {}
    by_identity: dict[str, int] = {}
    by_fingerprint: dict[str, list[int]] = {}
    merged_by_method = {"url": 0, "jobId": 0, "fingerprint": 0, "jdSimilarity": 0}
    for index, posting in enumerate(ordered):
        url = canonical_url(posting.source_url)
        if url in by_url and finder.union(index, by_url[url]):
            merged_by_method["url"] += 1
        else:
            by_url.setdefault(url, index)
        identity = job_identity(posting)
        if identity and identity in by_identity and finder.union(index, by_identity[identity]):
            merged_by_method["jobId"] += 1
        elif identity:
            by_identity.setdefault(identity, index)
        host = _host(posting.source_url)
        for other in by_fingerprint.get(posting.fingerprint, []):
            if _compatible(posting, ordered[other]) and _host(ordered[other].source_url) != host and finder.union(index, other):
                merged_by_method["fingerprint"] += 1
                break
        by_fingerprint.setdefault(posting.fingerprint, []).append(index)

    # 语义相似度只在同城市且不同域名的候选之间比较，避免把同公司的多个岗位合并。
    by_city: dict[str, list[int]] = {}
    for index, posting in enumerate(ordered):
        by_city.setdefault(posting.city_code or posting.city_name or "", []).append(index)
    for indexes in by_city.values():
        for position, left_index in enumerate(indexes):
            left = ordered[left_index]
            for right_index in indexes[position + 1:]:
                right = ordered[right_index]
                if _host(left.source_url) == _host(right.source_url):
                    continue
                if finder.find(left_index) == finder.find(right_index):
                    continue
                if _compatible(left, right) and normalize_title(left.title) == normalize_title(right.title) and jd_similarity(left.jd_text, right.jd_text) >= JD_SIMILARITY_THRESHOLD:
                    finder.union(left_index, right_index)
                    merged_by_method["jdSimilarity"] += 1

    groups: dict[int, list[SourcePosting]] = {}
    for index, posting in enumerate(ordered):
        groups.setdefault(finder.find(index), []).append(posting)

    result: list[SourcePosting] = []
    merged_count = 0
    for group in groups.values():
        group.sort(key=lambda item: (SOURCE_PRIORITY.get(item.source_type, 9), -_verification_rank(item),
                                     -len(item.jd_text)))
        primary = group[0]
        aliases: list[dict[str, Any]] = []
        for other in group:
            aliases.append(_alias(other))
            _fill_missing(primary, other)
            primary.warnings.extend(other.warnings)
            for provider in other.search_providers:
                if provider not in primary.search_providers:
                    primary.search_providers.append(provider)
            if not primary.verification_history and other.verification_history:
                primary.verification_history = list(other.verification_history)
        primary.aliases = _dedupe_aliases(aliases)
        primary.warnings = _dedupe_values(primary.warnings, 20)
        if len(group) > 1:
            merged_count += len(group) - 1
        result.append(primary)

    # Size belongs to the verified employer; salary remains specific to a job.
    for primary in result:
        if primary.company_size in (None, "SIZE_UNKNOWN"):
            employer = next((p for p in result if normalize_company(p.company_name) == normalize_company(primary.company_name)
                             and p.company_size not in (None, "SIZE_UNKNOWN") and p.company_size_text), None)
            if employer:
                from .evidence import record_fields
                record_fields(employer)
                primary.company_size, primary.company_size_text = employer.company_size, employer.company_size_text
                primary.field_sources["companySize"] = dict(employer.field_sources["companySize"])

    result.sort(key=lambda item: (
        SOURCE_PRIORITY.get(item.source_type, 9),
        -_verification_rank(item),
        item.fingerprint,
    ))
    stats = {
        "rawCount": len(postings),
        "uniqueCount": len(result),
        "mergedCount": merged_count,
        "methods": {key: value for key, value in merged_by_method.items() if value},
        "duplicateRate": round(merged_count / len(postings), 4) if postings else 0.0,
    }
    return result, stats


def _compatible(left, right):
    return (normalize_company(left.company_name) == normalize_company(right.company_name)
            and bool(left.city_code or left.city_name)
            and (left.city_code or left.city_name) == (right.city_code or right.city_name)
            and bool(left.candidate_type) and left.candidate_type == right.candidate_type)


def _host(url: str) -> str:
    try:
        return (urlsplit(url).hostname or "").lower()
    except ValueError:
        return ""


def _verification_rank(posting: SourcePosting) -> int:
    return {"ACTIVE_CONFIRMED": 4, "ACTIVE_PROBABLE": 3, "UNKNOWN": 2, "CLOSED": 1}.get(
        posting.verification_status, 0
    )


def _alias(posting: SourcePosting) -> dict[str, Any]:
    return {
        "sourceKey": posting.source_key,
        "sourceName": posting.source_name,
        "sourceType": posting.source_type,
        "sourceUrl": posting.source_url,
        "publishedAt": posting.published_at.isoformat() if posting.published_at else None,
        "updatedAt": posting.updated_at.isoformat() if posting.updated_at else None,
        "fetchedAt": posting.fetched_at.isoformat(),
        "verification": posting.verification,
        "verificationStatus": posting.verification_status,
        "searchProviders": list(posting.search_providers),
    }


def _fill_missing(primary: SourcePosting, other: SourcePosting) -> None:
    fill_groups(primary, other)
    if primary.source_type != "OFFICIAL" and _verification_rank(other) > _verification_rank(primary):
        primary.verification_status = other.verification_status
        primary.verification_reason = other.verification_reason
        primary.verification_evidence = other.verification_evidence
        primary.verification_checked_at = other.verification_checked_at
        primary.verification_source_url = other.verification_source_url
        primary.verification_http_status = other.verification_http_status
        primary.recruitment_status = other.recruitment_status
        primary.recruitment_evidence = other.recruitment_evidence


def _dedupe_aliases(values: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[tuple[str, str]] = set()
    result: list[dict[str, Any]] = []
    for item in values:
        key = (str(item.get("sourceKey")), str(item.get("sourceUrl")))
        if key in seen:
            continue
        seen.add(key)
        result.append(item)
    return result


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


def relevance_score(posting: SourcePosting, keyword: str) -> float:
    if not keyword:
        return 0.0
    terms = [term for term in normalize_key(keyword).split() if term] or [normalize_key(keyword)]
    haystack = normalize_key(f"{posting.title} {posting.jd_text}")
    if not terms or not haystack:
        return 0.0
    return min(1.0, sum(1 for term in terms if term and term in haystack) / len(terms))


def completeness_score(posting: SourcePosting) -> float:
    fields = (
        posting.city_code,
        posting.candidate_type,
        posting.salary_min_monthly_cents,
        posting.salary_max_monthly_cents,
        posting.experience_text or posting.experience_months_min,
        posting.education_min,
        posting.published_at,
        posting.requirements,
    )
    return sum(1 for value in fields if value not in (None, "")) / len(fields)


def freshness_score(posting: SourcePosting, *, now=None) -> float:
    from datetime import datetime, timezone

    if posting.published_at is None:
        return 0.3
    published = posting.published_at
    if published.tzinfo is None:
        published = published.replace(tzinfo=timezone.utc)
    days = max(0.0, ((now or datetime.now(timezone.utc)) - published).total_seconds() / 86400)
    if days <= 3:
        return 1.0
    if days <= 7:
        return 0.85
    if days <= 30:
        return 0.6
    if days <= 90:
        return 0.35
    return 0.1


def ranking_score(posting: SourcePosting, keyword: str) -> float:
    verification = {"ACTIVE_CONFIRMED": 1.0, "ACTIVE_PROBABLE": 0.8, "UNKNOWN": 0.5, "CLOSED": 0.0}.get(
        posting.verification_status, 0.5
    )
    semantic = posting.semantic_score if posting.semantic_score is not None else 0.5
    source = {"OFFICIAL": 1.0, "AUTHORIZED": 0.8, "PUBLIC_SEARCH": 0.6, "USER_INPUT": 0.5}.get(
        posting.source_type, 0.5
    )
    score = (
        0.25 * relevance_score(posting, keyword)
        + 0.20 * freshness_score(posting)
        + 0.15 * completeness_score(posting)
        + 0.20 * verification
        + 0.10 * semantic
        + 0.10 * source
    )
    if posting.verification_status == "CLOSED":
        score -= 0.5
    return round(score, 4)