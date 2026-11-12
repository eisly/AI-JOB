from __future__ import annotations

from datetime import datetime, timezone

from app.search.budget import BudgetConfig, SearchBudget
from app.search.expansion import (
    CoverageMatrix,
    build_gap_queries,
    build_query_specs,
    expand_keyword,
    keyword_category,
    match_gap_queries,
)
from app.search.models import SearchFilters


def test_ai_agent_keyword_expansion() -> None:
    values = expand_keyword("AI Agent 开发实习", limit=8)
    assert values[0] == "AI Agent 开发实习"
    assert "智能体应用开发实习" in values
    assert "大模型应用开发实习" in values
    assert "RAG 开发实习" in values
    assert "LangChain 开发实习" in values
    assert all("产品" not in value for value in values)


def test_java_backend_expansion_stays_relevant() -> None:
    values = expand_keyword("Java 后端", limit=8)
    assert values == [value for value in values if "Java" in value or "后端" in value or "服务端" in value]
    assert "Java 服务端" in values
    assert all("算法" not in value and "产品" not in value for value in values)


def test_unknown_keyword_is_not_over_expanded() -> None:
    assert expand_keyword("财务分析", limit=8) == ["财务分析"]


def test_keyword_category() -> None:
    assert keyword_category("AI Agent 开发实习") == "AI Agent 开发"
    assert keyword_category("Java 后端") == "Java 后端"


def test_query_specs_cover_each_city_first() -> None:
    filters = SearchFilters(cities=("440300", "440100", "441900"), candidate_types=("INTERNSHIP",))
    specs = build_query_specs(filters, "AI Agent 开发实习", limit=8)
    first_three = {spec.city_code for spec in specs[:3]}
    assert first_three == {"440300", "440100", "441900"}
    assert all(spec.query.count("实习") == 1 for spec in specs)
    assert any(spec.city_name == "东莞" for spec in specs)


def test_query_specs_do_not_duplicate_candidate_suffix() -> None:
    filters = SearchFilters(cities=("440300",), candidate_types=("INTERNSHIP",))
    specs = build_query_specs(filters, "AI Agent 开发实习", limit=4)
    assert all("实习 实习" not in spec.query for spec in specs)


def test_coverage_matrix_tracks_missing_dimensions() -> None:
    filters = SearchFilters(cities=("440300", "440100"), candidate_types=("INTERNSHIP",))
    specs = build_query_specs(filters, "AI Agent 开发实习", limit=8)
    matrix = CoverageMatrix(specs, ["tavily", "brave"])
    assert len(matrix.uncovered()) > 0
    for spec in specs:
        matrix.mark_query(spec, "tavily")
        matrix.mark_result(spec, "tavily", 1)
        matrix.mark_analysis(spec, analyzed=1, extracted=1)
    matrix.mark_query(specs[0], "brave")
    matrix.mark_result(specs[0], "brave", 2)
    assert len(matrix.uncovered()) > 0
    for spec in specs:
        matrix.mark_query(spec, "brave")
    assert matrix.all_covered()
    gaps = matrix.report()
    assert gaps["totalCells"] == len({(s.city_code, s.category, s.candidate_type) for s in specs})
    assert gaps["completedCells"] == gaps["totalCells"]
    assert gaps["cities"].get("深圳", 0) > 0


def test_coverage_treats_links_without_extraction_as_gap() -> None:
    filters = SearchFilters(cities=("440300",), candidate_types=("INTERNSHIP",))
    specs = build_query_specs(filters, "后端开发", limit=2)
    matrix = CoverageMatrix(specs, ["tavily"], attempt_limit=3)
    spec = specs[0]
    matrix.mark_query(spec, "tavily")
    matrix.mark_result(spec, "tavily", 20)
    matrix.mark_analysis(spec, analyzed=5, extracted=0, rejected=5)
    assert matrix.uncovered(), "有链接但 0 提取仍应视为覆盖缺口"
    matrix.mark_exhausted()
    assert matrix.all_covered()


def test_gap_queries_switch_to_backend_synonyms_and_skip_executed() -> None:
    filters = SearchFilters(cities=("330100",), candidate_types=("INTERNSHIP",))
    specs = build_query_specs(filters, "后端开发", limit=2)
    matrix = CoverageMatrix(specs, ["tavily"], attempt_limit=3)
    spec = specs[0]
    matrix.mark_query(spec, "tavily")
    matrix.mark_result(spec, "tavily", 20)
    matrix.mark_analysis(spec, analyzed=5, extracted=0, rejected=5)
    gaps = matrix.uncovered()
    executed = {spec.query}
    queries = build_gap_queries(gaps, "后端开发", executed=executed, limit=4)
    assert queries
    assert all(item.query not in executed for item in queries)
    assert any(item.keyword in {"后端研发", "服务端开发", "Java 后端"} for item in queries)


def test_budget_cache_avoids_duplicate_calls() -> None:
    budget = SearchBudget(BudgetConfig(max_api_calls=2, cache_ttl_seconds=600))
    budget.begin_round()
    assert budget.cached("tavily", "Java 后端") is None
    from app.search.providers.base import SearchHit

    hit = SearchHit(url="https://x/1", title="t", content="c", provider="tavily", query="Java 后端")
    budget.remember("tavily", "Java 后端", [hit])
    assert budget.cached("tavily", "Java 后端")[0].url == "https://x/1"
    assert budget.cache_hits == 1


def test_budget_dynamic_stop_rules() -> None:
    config = BudgetConfig(max_rounds=2, target_results=5, min_results=3, max_api_calls=10)
    budget = SearchBudget(config)
    budget.begin_round()
    # 数量达到目标且无覆盖缺口 -> 停止
    assert budget.should_continue(5, uncovered=0) is False
    # 数量不足 -> 继续
    assert budget.should_continue(1, uncovered=2) is True
    # 已有一定数量但仍有覆盖缺口 -> 继续
    assert budget.should_continue(3, uncovered=1) is True
    budget.begin_round()
    # 达到最大轮数 -> 停止
    assert budget.should_continue(0, uncovered=3) is False


def test_budget_enforces_api_call_limit() -> None:
    budget = SearchBudget(BudgetConfig(max_api_calls=2))
    budget.begin_round()
    assert budget.can_call() is True
    budget.record_api_call()
    budget.record_api_call()
    assert budget.can_call() is False
    assert budget.queries_remaining() == 12

def test_budget_stops_when_no_new_results_and_coverage_complete() -> None:
    budget = SearchBudget(BudgetConfig(max_rounds=3, target_results=10, min_results=5))
    budget.begin_round()
    assert budget.should_continue(2, uncovered=0, last_round_new=2) is True
    budget.begin_round()
    assert budget.should_continue(2, uncovered=0, last_round_new=0) is False
