from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Iterable

from .verification import summarize


@dataclass
class SearchFunnel:
    search_results: dict[str, int] = field(default_factory=dict)
    unique_urls: int = 0
    unique_postings: int = 0
    detail_pages: int = 0
    detail_pages_success: int = 0
    extraction_attempted: int = 0
    extraction_success: int = 0
    hard_filter_pass: int = 0
    final_recommendations: int = 0
    duplicate_count: int = 0
    stale_count: int = 0
    failures: dict[str, int] = field(default_factory=dict)
    stage_ms: dict[str, int] = field(default_factory=dict)
    source_counts: dict[str, int] = field(default_factory=dict)
    city_coverage: dict[str, int] = field(default_factory=dict)
    job_category_coverage: dict[str, int] = field(default_factory=dict)
    freshness_lags: list[float] = field(default_factory=list)
    posting_ids: list[str] = field(default_factory=list)

    def fail(self, stage: str, reason: str, count: int = 1) -> None:
        key = f"{stage}:{reason}"
        self.failures[key] = self.failures.get(key, 0) + count


def _ratio(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 4) if denominator else 0.0


def build_metrics(
    *,
    funnel: SearchFunnel,
    postings: Iterable[Any],
    now: datetime | None = None,
    coverage: dict[str, Any] | None = None,
    evaluation: dict[str, Any] | None = None,
) -> dict[str, Any]:
    items = list(postings)
    verification = summarize(items)
    reference = now or datetime.now(timezone.utc)
    lags = []
    for posting in items:
        published = getattr(posting, "published_at", None)
        if not isinstance(published, datetime):
            continue
        if published.tzinfo is None:
            published = published.replace(tzinfo=timezone.utc)
        lags.append(max(0.0, (reference - published).total_seconds() / 86400))
    funnel.freshness_lags = lags
    freshness_lag = round(sum(lags) / len(lags), 2) if lags else None
    catalog = funnel.city_coverage or ((coverage or {}).get("cities") or {})
    categories = funnel.job_category_coverage or ((coverage or {}).get("categories") or {})
    sources = {getattr(item, "source_key", "") for item in items if getattr(item, "source_key", None)}
    providers = sorted({provider for item in items for provider in (getattr(item, "search_providers", None) or [])})
    return {
        "funnel": {
            "searchResults": dict(funnel.search_results),
            "uniqueUrls": funnel.unique_urls,
            "uniquePostings": funnel.unique_postings,
            "jobDetailPages": funnel.detail_pages,
            "extractionAttempted": funnel.extraction_attempted,
            "extractionSuccess": funnel.extraction_success,
            "verification": verification.as_dict(),
            "hardFilterPass": funnel.hard_filter_pass,
            "finalRecommendations": funnel.final_recommendations,
        },
        "counts": dict(verification.counts),
        "reasons": dict(verification.reasons),
        "failures": dict(funnel.failures),
        "stageMs": dict(funnel.stage_ms),
        "sourceCounts": dict(funnel.source_counts),
        "cityCoverage": dict(catalog),
        "jobCategoryCoverage": dict(categories),
        "queryCoverage": coverage or {},
        "sourceDiversity": {
            "sources": len(sources),
            "providers": providers,
            "uniqueDomains": len({_domain(item) for item in items if _domain(item)}),
        },
        "rates": {
            **verification.rates(),
            "extractionSuccessRate": _ratio(funnel.extraction_success, funnel.extraction_attempted),
            "duplicateRate": _ratio(funnel.duplicate_count, funnel.unique_urls + funnel.duplicate_count),
            "staleRate": _ratio(funnel.stale_count, len(items)),
            "unknownRate": verification.rates().get("unknownRate", 0.0),
        },
        "freshnessLagDays": freshness_lag,
        "evaluation": evaluation or {
            "status": "NOT_MEASURED",
            "reason": "缺少人工标注的相关岗位全集，未计算 Recall@K / NDCG@10",
        },
        "postingIds": list(funnel.posting_ids),
    }


def _domain(posting: Any) -> str:
    from urllib.parse import urlsplit

    try:
        return urlsplit(getattr(posting, "source_url", "") or "").hostname or ""
    except ValueError:
        return ""