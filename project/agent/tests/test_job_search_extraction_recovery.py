from __future__ import annotations

from types import SimpleNamespace

import pytest

from app.search.budget import BudgetConfig, SearchBudget
from app.search.expansion import CoverageMatrix, build_query_specs
from app.search.models import SearchFilters, SourcePosting
from app.search.pipeline import JobSearchPipeline, SearchRuntime
from app.search.sources.base import partition_postings
from app.search.sources.web import _to_posting

JD = "负责后端服务开发，参与接口设计与性能优化，保障系统稳定性和高并发场景可用性"


def page(title: str = "后端开发实习生 - 某招聘", body: str | None = None,
         query: str = "杭州 后端开发 实习 招聘 职位") -> dict:
    return {
        "title": title,
        "body": body or f"示例科技 杭州 实习 {JD} 要求本科及以上学历",
        "providers": ["tavily"],
        "queries": [query],
        "query": query,
    }


def row(**overrides) -> dict:
    values = {
        "title": "后端开发实习生",
        "companyName": "示例科技",
        "cityName": "杭州",
        "candidateTypeText": "实习",
        "jdQuotes": [JD],
    }
    values.update(overrides)
    return values


def test_title_truncated_by_search_result_is_accepted() -> None:
    posting, reason, flags = _to_posting(row(), "https://careers.example.com/job/1", page())
    assert reason is None and posting is not None
    assert posting.title == "后端开发实习生"
    assert posting.verification_status == "UNKNOWN"
    assert flags["missingCompany"] is False


def test_company_missing_falls_back_to_domain_without_fabrication() -> None:
    posting, reason, flags = _to_posting(
        row(companyName="页面未出现的公司"),
        "https://careers.example.com/job/1",
        page(),
    )
    assert reason is None and posting is not None
    assert posting.company_name == "示例科技"  # 详情正文与标题提供了可核对公司名
    assert "COMPANY_FROM_DOMAIN" in posting.warnings
    assert flags["missingCompany"] is True


def test_short_source_span_is_rejected_even_when_model_quote_is_long() -> None:
    body = "示例科技 杭州 实习 负责后端服务接口开发，参与接口设计与性能优化，保障系统稳定性和高并发场景可用性"
    posting, reason, flags = _to_posting(
        row(jdQuotes=["负责后端服务开发 参与接口设计与性能优化，保障系统稳定性和高并发场景可用性"]),
        "https://careers.example.com/job/1",
        page(body=body),
    )
    assert posting is None
    assert reason == "JD_TOO_SHORT"


def test_jd_quote_without_page_evidence_is_rejected() -> None:
    posting, reason, _ = _to_posting(
        row(jdQuotes=["完全虚构且页面不存在的岗位职责描述，用于验证防幻觉规则不会放行"]),
        "https://careers.example.com/job/1",
        page(),
    )
    assert posting is None
    assert reason == "JD_QUOTE_MISMATCH"


def test_candidate_type_unknown_is_kept_not_dropped() -> None:
    posting, reason, flags = _to_posting(
        row(title="后端开发工程师", candidateTypeText=None),
        "https://careers.example.com/job/1",
        page(title="后端开发工程师 - 某招聘", body=f"示例科技 北京 {JD}"),
    )
    assert reason is None and posting is not None
    assert posting.candidate_type is None
    assert flags["missingType"] is True
    assert "CANDIDATE_TYPE_UNKNOWN" in posting.warnings


def test_city_or_type_mismatch_is_still_excluded() -> None:
    filters = SearchFilters(cities=("440300",), candidate_types=("INTERNSHIP",), include_unknown_size=True)
    other_city = SourcePosting(
        source_key="web", source_name="e", source_type="PUBLIC_SEARCH",
        source_url="https://e.example.com/1", title="后端开发", company_name="E",
        jd_text=JD, city_name="北京", city_code="110100", candidate_type="INTERNSHIP",
    )
    other_type = SourcePosting(
        source_key="web", source_name="e", source_type="PUBLIC_SEARCH",
        source_url="https://e.example.com/2", title="后端开发", company_name="E",
        jd_text=JD, city_name="深圳", city_code="440300", candidate_type="EXPERIENCED",
    )
    unknown_city = SourcePosting(
        source_key="web", source_name="e", source_type="PUBLIC_SEARCH",
        source_url="https://e.example.com/3", title="后端开发", company_name="E",
        jd_text=JD, city_name=None, city_code=None,
    )
    kept, stats = partition_postings([other_city, other_type, unknown_city], filters)
    assert [item.source_url for item in kept] == ["https://e.example.com/3"]
    assert stats["rejectedCity"] == 1
    assert stats["rejectedType"] == 1
    assert stats["unknownCity"] == 1
    assert "CITY_UNKNOWN" in kept[0].warnings


def test_size_unknown_is_excluded_only_when_user_requires_known_size() -> None:
    posting = SourcePosting(
        source_key="web", source_name="e", source_type="PUBLIC_SEARCH",
        source_url="https://e.example.com/4", title="后端开发", company_name="E",
        jd_text=JD, city_name="深圳", city_code="440300", candidate_type="INTERNSHIP",
        company_size="SIZE_UNKNOWN",
    )
    excluded, stats = partition_postings(
        [posting], SearchFilters(cities=("440300",), include_unknown_size=False)
    )
    assert excluded == [] and stats["rejectedSize"] == 1
    kept, _ = partition_postings(
        [posting], SearchFilters(cities=("440300",), include_unknown_size=True)
    )
    assert len(kept) == 1


async def _emit(_stage: str, _percent: int | None = None) -> None:
    return None


def pipeline() -> JobSearchPipeline:
    runtime = SearchRuntime(
        settings=SimpleNamespace(tavily_api_key="test"),
        llm=None,
        emit_stage=_emit,
        client=None,
        profile={"targetRole": "JAVA_BACKEND", "candidateType": "INTERNSHIP"},
        payload={
            "filters": {"keyword": "后端开发", "cities": ["330100"], "candidateTypes": ["INTERNSHIP"]},
            "sources": ["web"],
            "limits": {"perSource": 10, "maxTotal": 30},
        },
    )
    return JobSearchPipeline(runtime)


def coverage_with_zero_extraction() -> CoverageMatrix:
    specs = build_query_specs(
        SearchFilters(cities=("330100",), candidate_types=("INTERNSHIP",)),
        "后端开发",
        limit=1,
    )
    matrix = CoverageMatrix(specs, ["tavily"], attempt_limit=3)
    spec = specs[0]
    matrix.mark_query(spec, "tavily")
    matrix.mark_result(spec, "tavily", 20)
    matrix.mark_analysis(spec, analyzed=5, extracted=0, rejected=5)
    return matrix


def test_links_without_extraction_triggers_adjust() -> None:
    engine = pipeline()
    engine.budget.begin_round()
    state = {
        "sources": [{"key": "web", "status": "PARTIAL"}],
        "raw": [],
        "coverage": coverage_with_zero_extraction(),
        "last_round_new": 0,
    }
    assert engine._route_after_search(state) == "adjust"


def test_budget_exhaustion_stops_adjust_even_with_gap() -> None:
    engine = pipeline()
    engine.budget.begin_round()
    engine.budget.api_calls = engine.budget.config.max_api_calls
    state = {
        "sources": [{"key": "web", "status": "PARTIAL"}],
        "raw": [],
        "coverage": coverage_with_zero_extraction(),
        "last_round_new": 0,
    }
    assert engine._route_after_search(state) == "normalize"


def test_round_limit_stops_adjust_even_with_gap() -> None:
    engine = pipeline()
    for _ in range(engine.budget_config.max_rounds):
        engine.budget.begin_round()
    state = {
        "sources": [{"key": "web", "status": "PARTIAL"}],
        "raw": [],
        "coverage": coverage_with_zero_extraction(),
        "last_round_new": 0,
    }
    assert engine._route_after_search(state) == "normalize"