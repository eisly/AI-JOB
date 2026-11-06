from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any

from ..models import SearchFilters, SourcePosting, SourceResult

DEFAULT_TIMEOUT_SECONDS = 12.0
DEFAULT_USER_AGENT = "AI-JOB-Agent/1.0 (+job-search; contact: local-agent)"


@dataclass
class SourceContext:
    filters: SearchFilters
    keyword: str
    limit: int
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS
    user_agent: str = DEFAULT_USER_AGENT
    client: Any = None
    settings: Any = None
    extra: dict[str, Any] = field(default_factory=dict)


class SourceAdapter:
    key = ""
    name = ""
    type = "PUBLIC_SEARCH"
    verification = "UNVERIFIED"
    requires_credentials = False
    available = True
    allowed_hosts: frozenset[str] = frozenset()
    unavailable_reason = ""

    async def search(self, context: SourceContext) -> SourceResult:
        raise NotImplementedError

    def unavailable(self, message: str | None = None) -> SourceResult:
        return SourceResult(
            key=self.key,
            name=self.name,
            type=self.type,
            status="UNAVAILABLE",
            message=message or self.unavailable_reason,
            verification=self.verification,
            requires_credentials=self.requires_credentials,
        )

    def result(
        self,
        status: str,
        postings: list[SourcePosting] | None = None,
        *,
        message: str = "",
        http_status: int | None = None,
        started: float | None = None,
    ) -> SourceResult:
        return SourceResult(
            key=self.key,
            name=self.name,
            type=self.type,
            status=status,
            postings=postings or [],
            message=message,
            http_status=http_status,
            duration_ms=int((time.monotonic() - started) * 1000) if started is not None else 0,
            verification=self.verification,
            requires_credentials=self.requires_credentials,
        )


def filter_postings(postings: list[SourcePosting], filters: SearchFilters) -> list[SourcePosting]:
    from ..models import company_size_matches

    selected_cities = set(filters.cities)
    selected_types = set(filters.candidate_types)
    selected_sizes = tuple(filters.company_sizes)
    result: list[SourcePosting] = []
    for posting in postings:
        if selected_cities and posting.city_code not in selected_cities:
            continue
        if selected_types and posting.candidate_type not in selected_types:
            continue
        if not company_size_matches(posting.company_size or "SIZE_UNKNOWN", selected_sizes, filters.include_unknown_size):
            continue
        result.append(posting)
    return result


def query_terms(keyword: str) -> list[str]:
    text = (keyword or "").strip()
    if not text:
        return []
    terms = [text]
    for token in text.replace("工程师", " ").replace("开发", " ").replace("岗位", " ").split():
        token = token.strip()
        if token and token not in terms and len(token) >= 2:
            terms.append(token)
    return terms[:4]