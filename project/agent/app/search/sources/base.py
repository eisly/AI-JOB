from __future__ import annotations

import re
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


FOREIGN_LOCATION = re.compile(r"[A-Za-z]")
HAN = re.compile(r"[\u4e00-\u9fff]")
CHINA_CITY_SUFFIX = re.compile(r"(市|地区|自治州|特别行政区)$")


def is_foreign_location(city_name: str | None) -> bool:
    """在选择中国城市时，纯英文且无法映射为城市码的地点视为境外岗位。"""
    text = str(city_name or "").strip()
    return bool(text) and bool(FOREIGN_LOCATION.search(text)) and not bool(HAN.search(text))


def is_other_china_city(city_name: str | None) -> bool:
    """中文城市名明确指向其他中国城市时，视为“城市明确不符合”。"""
    text = str(city_name or "").strip()
    return bool(text) and bool(HAN.search(text)) and bool(CHINA_CITY_SUFFIX.search(text))


def partition_postings(postings: list[SourcePosting], filters: SearchFilters) -> tuple[list[SourcePosting], dict[str, int]]:
    """按用户硬条件分流，并记录每一类淘汰原因，便于定位漏斗丢失点。"""
    from ..models import company_size_matches

    selected_cities = set(filters.cities)
    selected_types = set(filters.candidate_types)
    selected_sizes = tuple(filters.company_sizes)
    stats = {
        "input": len(postings),
        "kept": 0,
        "rejectedCity": 0,
        "rejectedType": 0,
        "rejectedSize": 0,
        "unknownCity": 0,
        "unknownType": 0,
    }
    result: list[SourcePosting] = []
    for posting in postings:
        # 只有“明确不符合”才淘汰；字段缺失属于未知，保留给 Java 端标记与排序。
        if selected_cities and posting.city_code and posting.city_code not in selected_cities:
            stats["rejectedCity"] += 1
            continue
        if selected_cities and not posting.city_code and (
            is_foreign_location(posting.city_name) or is_other_china_city(posting.city_name)
        ):
            stats["rejectedCity"] += 1
            continue
        if selected_types and posting.candidate_type and posting.candidate_type not in selected_types:
            stats["rejectedType"] += 1
            continue
        if not company_size_matches(posting.company_size or "SIZE_UNKNOWN", selected_sizes, filters.include_unknown_size):
            stats["rejectedSize"] += 1
            continue
        if selected_cities and not posting.city_code:
            posting.warnings.append("CITY_UNKNOWN")
            stats["unknownCity"] += 1
        if selected_types and not posting.candidate_type:
            posting.warnings.append("CANDIDATE_TYPE_UNKNOWN")
            stats["unknownType"] += 1
        result.append(posting)
    stats["kept"] = len(result)
    return result, stats


def filter_postings(postings: list[SourcePosting], filters: SearchFilters) -> list[SourcePosting]:
    return partition_postings(postings, filters)[0]


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