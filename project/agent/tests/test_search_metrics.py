from __future__ import annotations

from datetime import datetime, timezone

from app.search.evaluation import evaluate_ranked
from app.search.metrics import SearchFunnel, build_metrics
from app.search.models import SourcePosting


def posting(url: str, status: str = "UNKNOWN") -> SourcePosting:
    return SourcePosting(
        source_key="web", source_name="jobs.example.com", source_type="PUBLIC_SEARCH",
        source_url=url, title="AI 应用开发", company_name="示例公司", jd_text="负责 AI 应用开发与检索增强",
        city_name="深圳", city_code="440300", candidate_type="INTERNSHIP",
        verification_status=status, published_at=datetime(2026, 11, 5, tzinfo=timezone.utc),
    )


def test_search_funnel_and_rates() -> None:
    funnel = SearchFunnel(search_results={"tavily": 5, "brave": 3}, unique_urls=7, unique_postings=4,
                          detail_pages=4, detail_pages_success=2, extraction_attempted=4,
                          extraction_success=2, duplicate_count=2, stale_count=1)
    items = [posting("https://a.example.com/1", "ACTIVE_CONFIRMED"),
             posting("https://b.example.com/2", "ACTIVE_PROBABLE"),
             posting("https://c.example.com/3", "UNKNOWN"),
             posting("https://d.example.com/4", "CLOSED")]
    metrics = build_metrics(funnel=funnel, postings=items)
    assert metrics["funnel"]["searchResults"] == {"tavily": 5, "brave": 3}
    assert metrics["counts"]["ACTIVE_CONFIRMED"] == 1
    assert metrics["counts"]["CLOSED"] == 1
    assert metrics["rates"]["extractionSuccessRate"] == 0.5
    assert metrics["rates"]["duplicateRate"] == round(2 / 6, 4)
    assert metrics["sourceDiversity"]["uniqueDomains"] == 4
    assert metrics["evaluation"]["status"] == "NOT_MEASURED"


def test_evaluation_requires_labels() -> None:
    items = [posting("https://a.example.com/1")]
    assert evaluate_ranked(items, None)["status"] == "NOT_MEASURED"


def test_evaluation_with_manual_labels() -> None:
    items = [posting("https://a.example.com/1"), posting("https://b.example.com/2")]
    labels = {"relevantUrls": ["https://a.example.com/1", "https://c.example.com/3"],
              "judgedUrls": ["https://a.example.com/1", "https://b.example.com/2"]}
    result = evaluate_ranked(items, labels, k=10)
    assert result["status"] == "MEASURED"
    assert result["recallAtK"] == 0.5
    assert result["precisionAtK"] == 0.5
    assert 0 < result["ndcgAtK"] <= 1
