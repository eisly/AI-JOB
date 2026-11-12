from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any

from .providers.base import SearchHit


@dataclass(frozen=True)
class BudgetConfig:
    max_queries_per_round: int = 12
    max_pages_per_round: int = 20
    max_detail_pages: int = 20
    max_page_fetch: int = 12
    max_api_calls: int = 60
    max_elapsed_seconds: float = 150.0
    max_rounds: int = 3
    target_results: int = 10
    min_results: int = 5
    min_new_urls: int = 3
    cache_ttl_seconds: int = 900
    provider_retries: int = 1
    freshness_days: int = 30
    detail_concurrency: int = 4
    detail_verification: bool = True

    @classmethod
    def from_settings(cls, settings: Any) -> "BudgetConfig":
        def value(name: str, default):
            return getattr(settings, name, default)

        return cls(
            max_queries_per_round=int(value("job_search_max_queries_per_round", 12)),
            max_pages_per_round=int(value("job_search_max_pages_per_round", 20)),
            max_detail_pages=int(value("job_search_max_detail_pages", 20)),
            max_page_fetch=int(value("job_search_max_page_fetch", 12)),
            max_api_calls=int(value("job_search_max_api_calls", 60)),
            max_elapsed_seconds=float(value("job_search_max_elapsed_seconds", 150.0)),
            max_rounds=int(value("job_search_max_rounds", 3)),
            target_results=int(value("job_search_target_results", 10)),
            min_results=int(value("job_search_min_results", 5)),
            min_new_urls=int(value("job_search_min_new_urls", 3)),
            cache_ttl_seconds=int(value("job_search_cache_ttl_seconds", 900)),
            provider_retries=int(value("job_search_provider_retries", 1)),
            freshness_days=int(value("job_search_freshness_days", 30)),
            detail_concurrency=int(value("job_search_detail_concurrency", 4)),
            detail_verification=bool(value("job_search_detail_verification", True)),
        )


@dataclass
class _CacheEntry:
    created_at: float
    hits: list[SearchHit]


@dataclass
class SearchBudget:
    config: BudgetConfig
    started_at: float = field(default_factory=time.monotonic)
    round_no: int = 0
    api_calls: int = 0
    queries: int = 0
    pages: int = 0
    details: int = 0
    cache_hits: int = 0
    _cache: dict[str, _CacheEntry] = field(default_factory=dict)
    _stage_started: dict[str, float] = field(default_factory=dict)
    _stage_durations: dict[str, int] = field(default_factory=dict)

    def begin_round(self) -> int:
        self.round_no += 1
        self._round_started = time.monotonic()
        self._round_queries = 0
        self._round_pages = 0
        return self.round_no

    def elapsed(self) -> float:
        return time.monotonic() - self.started_at

    def queries_remaining(self) -> int:
        if self.round_no <= 0:
            return 0
        return max(0, self.config.max_queries_per_round - getattr(self, "_round_queries", 0))

    def pages_remaining(self) -> int:
        if self.round_no <= 0:
            return 0
        return max(0, self.config.max_pages_per_round - getattr(self, "_round_pages", 0))

    def details_remaining(self) -> int:
        return max(0, self.config.max_detail_pages - self.details)

    def remaining_seconds(self):
        return max(0.0, self.config.max_elapsed_seconds - 30 - self.elapsed())

    def can_call(self, count: int = 1) -> bool:
        return self.api_calls + count <= self.config.max_api_calls and self.remaining_seconds() > 0

    def record_query(self, count: int = 1) -> None:
        self.queries += count
        self._round_queries = getattr(self, "_round_queries", 0) + count


    def record_page(self, count: int = 1) -> None:
        self.pages += count
        self._round_pages = getattr(self, "_round_pages", 0) + count

    def record_detail(self, count: int = 1) -> None:
        self.details += count

    def record_api_call(self, count: int = 1) -> None:
        self.api_calls += count

    def start_stage(self, name: str) -> None:
        self._stage_started[name] = time.monotonic()

    def end_stage(self, name: str) -> int:
        started = self._stage_started.pop(name, None)
        duration = int((time.monotonic() - started) * 1000) if started is not None else 0
        self._stage_durations[name] = self._stage_durations.get(name, 0) + duration
        return duration

    def cached(self, provider: str, query: str) -> list[SearchHit] | None:
        entry = self._cache.get(f"{provider}|{query}")
        if entry is None:
            return None
        if time.monotonic() - entry.created_at > self.config.cache_ttl_seconds:
            self._cache.pop(f"{provider}|{query}", None)
            return None
        self.cache_hits += 1
        return entry.hits

    def remember(self, provider: str, query: str, hits: list[SearchHit]) -> None:
        self._cache[f"{provider}|{query}"] = _CacheEntry(time.monotonic(), hits)

    def should_continue(self, valid_count: int, *, uncovered: int = 0, last_round_new: int = 0,
                        pending: int = 0) -> bool:
        """根据有效岗位数量、覆盖缺口、待处理页面和剩余预算决定是否继续补搜。"""
        if self.round_no >= self.config.max_rounds:
            return False
        if not self.can_call():
            return False
        if pending > 0:
            return True
        if valid_count >= self.config.target_results and uncovered == 0:
            return False
        if valid_count >= self.config.target_results and last_round_new == 0:
            return False
        if valid_count >= self.config.min_results and uncovered == 0:
            return False
        # 已覆盖全部维度且本轮没有新增岗位时，继续补搜只会重复请求。
        if uncovered == 0 and last_round_new == 0:
            return False
        return True

    def snapshot(self) -> dict[str, Any]:
        return {
            "rounds": self.round_no,
            "queries": self.queries,
            "pages": self.pages,
            "detailChecks": self.details,
            "pagesFetched": getattr(self, "page_fetches", 0),
            "apiCalls": self.api_calls,
            "cacheHits": self.cache_hits,
            "elapsedMs": int(self.elapsed() * 1000),
            "limits": {
                "maxQueriesPerRound": self.config.max_queries_per_round,
                "maxPagesPerRound": self.config.max_pages_per_round,
                "maxDetailPages": self.config.max_detail_pages,
                "maxPageFetch": self.config.max_page_fetch,
                "maxApiCalls": self.config.max_api_calls,
                "maxElapsedSeconds": self.config.max_elapsed_seconds,
                "maxRounds": self.config.max_rounds,
                "targetResults": self.config.target_results,
                "minResults": self.config.min_results,
            },
            "stages": dict(self._stage_durations),
        }

@dataclass
class SourceBudget:
    """单个来源的预算视图；所有消耗仍计入全局预算，避免来源互相挤占配额。"""

    global_budget: SearchBudget
    key: str
    query_limit: int | None = None
    page_limit: int | None = None
    page_fetch_limit: int | None = None
    api_limit: int | None = None
    _round_queries: int = 0
    _round_pages: int = 0
    _page_fetches: int = 0
    _api_calls: int = 0

    @property
    def config(self) -> BudgetConfig:
        return self.global_budget.config

    @property
    def round_no(self) -> int:
        return self.global_budget.round_no

    @property
    def cache_hits(self) -> int:
        return self.global_budget.cache_hits

    def begin_round(self) -> int:
        self._round_queries = 0
        self._round_pages = 0
        return self.global_budget.round_no

    def elapsed(self) -> float:
        return self.global_budget.elapsed()

    def remaining_seconds(self) -> float:
        return self.global_budget.remaining_seconds()

    def queries_remaining(self) -> int:
        global_remaining = self.global_budget.queries_remaining()
        own_remaining = self.query_limit - self._round_queries if self.query_limit is not None else global_remaining
        return max(0, min(global_remaining, own_remaining))

    def pages_remaining(self) -> int:
        global_remaining = self.global_budget.pages_remaining()
        own_remaining = self.page_limit - self._round_pages if self.page_limit is not None else global_remaining
        return max(0, min(global_remaining, own_remaining))

    def details_remaining(self) -> int:
        return self.global_budget.details_remaining()

    def can_call(self, count: int = 1) -> bool:
        if not self.global_budget.can_call(count):
            return False
        if self.api_limit is not None and self._api_calls + count > self.api_limit:
            return False
        return True

    def record_query(self, count: int = 1) -> None:
        self._round_queries += count
        self.global_budget.record_query(count)

    def record_page(self, count: int = 1) -> None:
        self._round_pages += count
        self.global_budget.record_page(count)

    def record_detail(self, count: int = 1) -> None:
        self.global_budget.record_detail(count)

    def record_api_call(self, count: int = 1) -> None:
        self._api_calls += count
        self.global_budget.record_api_call(count)

    def start_stage(self, name: str) -> None:
        self.global_budget.start_stage(name)

    def end_stage(self, name: str) -> int:
        return self.global_budget.end_stage(name)

    def cached(self, provider: str, query: str):
        return self.global_budget.cached(provider, query)

    def remember(self, provider: str, query: str, hits) -> None:
        self.global_budget.remember(provider, query, hits)

    @property
    def page_fetches(self) -> int:
        return self._page_fetches

    @page_fetches.setter
    def page_fetches(self, value: int) -> None:
        previous = self._page_fetches
        self._page_fetches = max(0, int(value))
        delta = self._page_fetches - previous
        if delta:
            self.global_budget.page_fetches = int(getattr(self.global_budget, "page_fetches", 0)) + delta

    def fetches_remaining(self) -> int:
        own = self.page_fetch_limit - self.page_fetches if self.page_fetch_limit is not None else self.config.max_page_fetch
        return max(0, min(own, self.config.max_page_fetch - getattr(self.global_budget, "page_fetches", 0)))

    def snapshot(self) -> dict[str, Any]:
        return self.global_budget.snapshot()
