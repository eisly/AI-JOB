from __future__ import annotations

from dataclasses import dataclass

from .expansion import QuerySpec, build_gap_queries, build_query_specs, expand_keyword
from .models import SearchFilters


@dataclass
class SearchPlan:
    keyword: str
    expansions: list[str]
    alternative_keyword: str
    specs: list[QuerySpec]


class SearchPlanner:
    def __init__(self, filters: SearchFilters, keyword: str, *, limit: int = 24,
                 freshness_days: int = 30, coverage_multiplier: int = 3) -> None:
        self.filters = filters
        self.keyword = keyword
        self.limit = max(1, limit)
        self.freshness_days = freshness_days
        self.coverage_multiplier = max(1, coverage_multiplier)

    def build(self, alternative_keyword: str = "") -> SearchPlan:
        expansions = expand_keyword(self.keyword, limit=8)
        alternative = " ".join(alternative_keyword.split())[:100]
        if alternative and all(item.casefold() != alternative.casefold() for item in expansions):
            expansions.append(alternative)
        specs = build_query_specs(
            self.filters,
            self.keyword,
            limit=self.plan_limit,
            freshness_days=self.freshness_days,
        )
        if alternative:
            extra = build_query_specs(
                self.filters,
                alternative,
                limit=max(len(self.filters.cities) or 1, 1) * max(len(self.filters.candidate_types) or 1, 1),
                freshness_days=self.freshness_days,
            )
            existing = {spec.query for spec in specs}
            specs.extend(spec for spec in extra if spec.query not in existing)
        return SearchPlan(self.keyword, expansions, alternative, specs[: self.plan_limit])

    @property
    def plan_limit(self) -> int:
        dimensions = max(1, len(self.filters.cities)) * max(1, len(self.filters.candidate_types))
        return max(self.limit, dimensions * self.coverage_multiplier)

    def gaps(self, cells, *, executed: set[str], limit: int) -> list[QuerySpec]:
        return build_gap_queries(cells, self.keyword, executed=executed, limit=limit)