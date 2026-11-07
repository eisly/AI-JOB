from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, Iterable

from .models import CITY_CODES, ROLE_CATEGORIES, ROLE_KEYWORDS, SearchFilters

CITY_NAMES: dict[str, str] = {}
for _name, _code in CITY_CODES.items():
    CITY_NAMES.setdefault(_code, _name)

CANDIDATE_LABELS = {"INTERNSHIP": "实习", "CAMPUS": "校招", "EXPERIENCED": "社招"}
CANDIDATE_SUFFIXES = ("实习", "校招", "社招", "校园招聘")

# 只在命中技术族时扩展，避免把无关岗位词硬塞进查询。
KEYWORD_EXPANSIONS: tuple[tuple[tuple[str, ...], tuple[str, ...]], ...] = (
    (
        ("agent", "智能体", "ai agent"),
        (
            "AI Agent 开发",
            "智能体应用开发",
            "AI 应用开发",
            "大模型应用开发",
            "LLM 应用开发",
            "RAG 开发",
            "LangChain 开发",
            "LangGraph 开发",
        ),
    ),
    (
        ("rag", "检索增强"),
        ("RAG 开发", "检索增强生成", "大模型应用开发", "AI 应用开发", "知识库问答开发"),
    ),
    (
        ("大模型", "llm", "ai 应用", "ai应用"),
        ("大模型应用开发", "AI 应用开发", "LLM 应用开发", "智能体应用开发", "RAG 开发"),
    ),
    (
        ("java",),
        ("Java 后端", "Java 服务端", "Java 开发", "后端开发", "Java 研发"),
    ),
    (
        ("后端", "服务端", "backend", "back end", "后台开发"),
        ("后端开发", "服务端开发", "后端研发", "Java 后端", "Python 后端", "后端工程师"),
    ),
    (
        ("前端", "vue", "react"),
        ("前端开发", "Web 前端开发", "前端工程师", "Vue 前端开发"),
    ),
    (
        ("测试", "qa"),
        ("软件测试", "测试工程师", "自动化测试", "测试开发"),
    ),
    (
        ("算法", "机器学习", "深度学习"),
        ("算法工程师", "机器学习工程师", "AI 算法工程师"),
    ),
    (
        ("数据", "etl"),
        ("数据开发", "数据工程师", "大数据开发", "数据分析"),
    ),
)
FULL_ROLE_KEYWORDS = tuple(ROLE_KEYWORDS.values())


def _normalize(value: str) -> str:
    return "".join(str(value or "").casefold().split())


def strip_candidate_suffix(keyword: str) -> tuple[str, str]:
    text = str(keyword or "").strip()
    for suffix in CANDIDATE_SUFFIXES:
        if text.endswith(suffix) and len(text) > len(suffix):
            return text[: -len(suffix)].strip(), suffix
    return text, ""


def keyword_category(keyword: str) -> str:
    text = _normalize(keyword)
    for role, label in ROLE_CATEGORIES.items():
        if _normalize(label) in text or _normalize(role) in text:
            return label
    for triggers, labels in KEYWORD_EXPANSIONS:
        if any(_normalize(trigger) in text for trigger in triggers):
            return labels[0]
    return str(keyword or "").strip() or "未分类"


def expand_keyword(keyword: str, *, limit: int = 8) -> list[str]:
    original = " ".join(str(keyword or "").split())
    if not original:
        return []
    base, suffix = strip_candidate_suffix(original)
    normalized = _normalize(base)
    result: list[str] = [original]
    for triggers, labels in KEYWORD_EXPANSIONS:
        if not any(_normalize(trigger) in normalized for trigger in triggers):
            continue
        for label in labels:
            candidate = f"{label}{suffix}" if suffix else label
            if _normalize(candidate) not in {_normalize(item) for item in result}:
                result.append(candidate)
            if len(result) >= limit:
                return result
    # 角色编码（如 JAVA_BACKEND）回退到中文岗位名。
    if len(result) == 1 and _normalize(base).upper() in ROLE_KEYWORDS:
        label = ROLE_KEYWORDS[_normalize(base).upper()]
        result.append(f"{label}{suffix}" if suffix else label)
    return result[:limit]


@dataclass(frozen=True)
class QuerySpec:
    query: str
    city_code: str
    city_name: str
    keyword: str
    category: str
    candidate_type: str
    freshness: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return {
            "query": self.query,
            "cityCode": self.city_code,
            "cityName": self.city_name,
            "keyword": self.keyword,
            "category": self.category,
            "candidateType": self.candidate_type,
            "freshness": self.freshness,
        }


def build_query_specs(
    filters: SearchFilters,
    keyword: str,
    *,
    limit: int = 24,
    freshness: str | None = None,
    freshness_days: int = 30,
) -> list[QuerySpec]:
    expansions = expand_keyword(keyword, limit=8)
    cities = list(filters.cities) or [""]
    types = list(filters.candidate_types) or [""]
    kind_suffix = {"INTERNSHIP": "实习", "CAMPUS": "校招", "EXPERIENCED": "社招"}
    specs: list[QuerySpec] = []
    # 先按城市轮转，保证每个城市首轮都有覆盖，而不是被一个城市占满。
    for candidate_type in types:
        suffix = kind_suffix.get(candidate_type, "")
        for index in range(max(len(expansions), len(cities))):
            city_code = cities[index % len(cities)]
            city_name = CITY_NAMES.get(city_code, city_code)
            variant = expansions[index % len(expansions)]
            # 扩展词可能已带“实习/校招/社招”，避免生成“实习 实习”。
            label = "" if suffix and variant.endswith(suffix) else suffix
            parts = [part for part in (city_name, variant, label, "招聘 职位") if part]
            query = " ".join(parts)
            spec = QuerySpec(
                query=query,
                city_code=city_code,
                city_name=city_name,
                keyword=variant,
                category=keyword_category(variant),
                candidate_type=candidate_type,
                freshness=freshness if freshness_days <= 7 else None,
            )
            if spec.query not in {item.query for item in specs}:
                specs.append(spec)
            if len(specs) >= limit:
                return specs
    return specs


@dataclass
class CoverageCell:
    city_code: str
    city_name: str
    category: str
    queries: set[str] = field(default_factory=set)
    providers: set[str] = field(default_factory=set)
    keywords: set[str] = field(default_factory=set)
    candidate_types: set[str] = field(default_factory=set)
    found: int = 0
    analyzed: int = 0
    extracted: int = 0
    rejected: int = 0
    completed: bool = False

    @property
    def attempt_count(self) -> int:
        return len(self.queries)


class CoverageMatrix:
    def __init__(self, specs: Iterable[QuerySpec], providers: Iterable[str],
                 attempt_limit: int = 3) -> None:
        self.providers = tuple(providers)
        self.attempt_limit = max(1, attempt_limit)
        self._exhausted = False
        self.cells: dict[tuple[str, str], CoverageCell] = {}
        for spec in specs:
            key = (spec.city_code, spec.category)
            self.cells.setdefault(key, CoverageCell(spec.city_code, spec.city_name, spec.category))

    def _cell(self, spec: QuerySpec) -> CoverageCell:
        return self.cells.setdefault(
            (spec.city_code, spec.category),
            CoverageCell(spec.city_code, spec.city_name, spec.category),
        )

    def mark_query(self, spec: QuerySpec, provider: str) -> None:
        cell = self._cell(spec)
        cell.queries.add(spec.query)
        cell.providers.add(provider)
        cell.keywords.add(spec.keyword)
        if spec.candidate_type:
            cell.candidate_types.add(spec.candidate_type)

    def mark_result(self, spec: QuerySpec, provider: str, count: int) -> None:
        cell = self._cell(spec)
        cell.found += max(0, count)
        cell.providers.add(provider)

    def mark_analysis(self, spec: QuerySpec, *, analyzed: int = 0, extracted: int = 0,
                      rejected: int = 0) -> None:
        """记录该维度实际进入提取的页面数与产出，区分“有链接”与“有关键岗位”。"""
        cell = self._cell(spec)
        cell.analyzed += max(0, analyzed)
        cell.extracted += max(0, extracted)
        cell.rejected += max(0, rejected)

    def complete(self) -> None:
        if self._exhausted:
            for cell in self.cells.values():
                cell.completed = True
            return
        expected = set(self.providers)
        for cell in self.cells.values():
            providers_ok = not expected or expected <= cell.providers
            # 只有“分析过且产出岗位”或“已用尽同义词尝试”才算完成；
            # 有链接但 0 提取仍视为覆盖缺口。
            produced = cell.analyzed > 0 and cell.extracted > 0
            exhausted = cell.attempt_count >= self.attempt_limit
            cell.completed = bool(cell.queries) and providers_ok and (produced or exhausted)

    def uncovered(self) -> list[CoverageCell]:
        self.complete()
        return [cell for cell in self.cells.values() if not cell.completed]

    def all_covered(self) -> bool:
        self.complete()
        return all(cell.completed for cell in self.cells.values())

    def mark_exhausted(self) -> None:
        """同义词与剩余候选都已用尽，标记完成以避免无意义轮次。"""
        self._exhausted = True
        for cell in self.cells.values():
            cell.completed = True

    def city_coverage(self) -> dict[str, int]:
        result: dict[str, int] = {}
        for cell in self.cells.values():
            result[cell.city_name or cell.city_code] = result.get(cell.city_name or cell.city_code, 0) + cell.extracted
        return result

    def category_coverage(self) -> dict[str, int]:
        result: dict[str, int] = {}
        for cell in self.cells.values():
            result[cell.category] = result.get(cell.category, 0) + cell.extracted
        return result

    def report(self) -> dict[str, Any]:
        self.complete()
        return {
            "cities": self.city_coverage(),
            "categories": self.category_coverage(),
            "cells": [
                {
                    "cityCode": cell.city_code,
                    "cityName": cell.city_name,
                    "category": cell.category,
                    "queries": len(cell.queries),
                    "providers": sorted(cell.providers),
                    "found": cell.found,
                    "analyzed": cell.analyzed,
                    "extracted": cell.extracted,
                    "rejected": cell.rejected,
                    "completed": cell.completed,
                }
                for cell in self.cells.values()
            ],
            "completedCells": sum(1 for cell in self.cells.values() if cell.completed),
            "totalCells": len(self.cells),
        }


def match_gap_queries(cells: Iterable[CoverageCell], specs: Iterable[QuerySpec], *, limit: int) -> list[QuerySpec]:
    """优先补齐未完成维度，而不是重复已执行的查询。"""
    pending = {(cell.city_code, cell.category) for cell in cells}
    result: list[QuerySpec] = []
    for spec in specs:
        if (spec.city_code, spec.category) in pending and spec.query not in {item.query for item in result}:
            result.append(spec)
        if len(result) >= limit:
            break
    return result

def build_gap_queries(
    cells: Iterable[CoverageCell],
    keyword: str,
    *,
    executed: set[str],
    limit: int = 12,
) -> list[QuerySpec]:
    """对未完成维度换同义词补搜；已执行查询绝不重复。"""
    kind_suffix = {"INTERNSHIP": "实习", "CAMPUS": "校招", "EXPERIENCED": "社招"}
    seed_terms: list[str] = []
    for cell in cells:
        seed_terms.extend(sorted(cell.keywords) or [cell.category])
    if keyword:
        seed_terms.append(keyword)
    pool: list[str] = []
    for seed in seed_terms:
        for variant in expand_keyword(seed, limit=8):
            if variant and variant not in pool:
                pool.append(variant)
    if len(pool) <= len(set(seed_terms)):
        for variant in ("后端研发", "服务端开发", "Java 后端", "后端工程师"):
            if variant not in pool:
                pool.append(variant)
    result: list[QuerySpec] = []
    planned: set[str] = set()
    for cell in cells:
        city_name = cell.city_name or CITY_NAMES.get(cell.city_code, cell.city_code)
        kinds = sorted(cell.candidate_types) or [""]
        for variant in pool:
            for candidate_type in kinds:
                suffix = kind_suffix.get(candidate_type, "")
                label = "" if suffix and variant.endswith(suffix) else suffix
                query = " ".join(part for part in (city_name, variant, label, "招聘 职位") if part)
                if not query or query in executed or query in planned:
                    continue
                planned.add(query)
                result.append(QuerySpec(
                    query=query,
                    city_code=cell.city_code,
                    city_name=city_name,
                    keyword=variant,
                    category=keyword_category(variant),
                    candidate_type=candidate_type,
                ))
                if len(result) >= limit:
                    return result
    return result