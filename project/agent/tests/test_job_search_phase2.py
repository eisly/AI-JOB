import json
from datetime import datetime, timezone
from types import SimpleNamespace

import httpx
import pytest

from app.fake_model import FakeLLM
from app.llm import LLMProviderError
from app.search.models import SearchFilters
from app.search.pipeline import JobSearchPipeline, SearchRuntime
from app.search.sources.base import SourceContext
from app.search.sources.detail import closed_text, read_detail_result
from app.search.sources.web import _extract_postings, _job_page_candidate
from app.skills.tools import validate_result_contract


@pytest.mark.asyncio
async def test_structured_job_survives_model_failure_for_other_page():
    url = "https://example.org/jobs/123"
    job = {
        "@type": "JobPosting", "url": url, "title": "Java 后端实习生",
        "description": "岗位职责：参与 Java 后端接口开发、持续集成和维护，配合团队测试上线并处理服务故障。",
        "hiringOrganization": {"name": "示例科技有限公司"}, "employmentType": "INTERN",
        "jobLocation": {"address": {"addressLocality": "广州"}},
    }
    page = read_detail_result(url, f'<script type="application/ld+json">{json.dumps(job)}</script>',
                              datetime.now(timezone.utc)).page
    calls = []

    class FailingLLM:
        async def complete(self, request):
            calls.append(request)
            raise ConnectionError()

    context = SourceContext(SearchFilters(keyword="Java 后端"), "Java 后端", 10, 12, "test", None,
                            SimpleNamespace(job_search_extraction_retries=1), {"llm": FailingLLM()})
    postings, stats = await _extract_postings(context, [
        (url, {**page, "pageIndex": 0}),
        ("https://example.org/jobs/124", {"pageIndex": 1, "title": "另一个开发岗位", "body": "招聘信息"}),
    ])
    assert [(p.company_name, p.city_code, p.candidate_type) for p in postings] == [
        ("示例科技有限公司", "440100", "INTERNSHIP")]
    assert len(calls) == 2 and all(request.thinking is False for request in calls)
    assert all(job["title"] not in request.user_prompt for request in calls)
    assert stats["deterministicAccepted"] == 1
    assert stats["modelFailures"] == 2 and stats["noRowPages"] == 1


@pytest.mark.asyncio
async def test_pending_urls_survive_across_rounds_with_distinct_hits_and_metrics(monkeypatch):
    async def no_address(*args, **kwargs):
        raise OSError()

    monkeypatch.setattr("app.search.sources.detail.resolve_public_ipv4", no_address)
    title = "Java 后端实习生 - 广州示例科技有限公司"
    body = "工作地点：广州。岗位职责：参与后端业务开发和测试，维护微服务并实现稳定可靠的 Java 接口。"
    body += "广州示例科技有限公司，要求熟悉 Java 和关系型数据库。"
    requests = []

    def respond(request):
        requests.append(request)
        return httpx.Response(200, json={"results": [
            {"url": f"https://example.org/jobs/{n}", "title": title, "content": body}
            for n in range(100, 104)
        ]})

    async def emit(*args):
        pass

    candidates = []

    async def emit_candidates(rows):
        candidates.extend(rows)

    settings = SimpleNamespace(deepseek_api_key="test", job_search_max_pages_per_round=1,
                               job_search_max_queries_per_round=1, job_search_max_page_fetch=0,
                               job_search_detail_verification=False, job_search_max_rounds=3)
    payload = {"sources": ["web"], "filters": {"keyword": "Java 后端", "cities": ["440100"],
                                               "candidateTypes": ["INTERNSHIP"]}}
    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        result = await JobSearchPipeline(SearchRuntime(settings, FakeLLM(), emit, client, {}, payload,
                                                       emit_candidates)).run()
    counts = result["sources"][0]["verificationCounts"]
    assert counts["pagesAnalyzed"] == 3
    assert counts["uniqueUrls"] == 4
    assert result["metrics"]["collection"]["pendingBySource"] == {"web": 1}
    assert result["metrics"]["collection"]["pendingUrlsBySource"] == {"web": ["https://example.org/jobs/103"]}
    assert result["metrics"]["funnel"]["searchResults"]["deepseek"] == len(requests) * 4
    assert len({row["sourceUrl"] for row in candidates}) == 3
    result["items"][0]["candidateType"] = None
    assert validate_result_contract("JOB_SEARCH", result)["items"][0]["candidateType"] is None


def test_salary_summary_and_search_pages_are_excluded():
    assert not _job_page_candidate("https://example.org/salary/backend", "后端开发")
    assert not _job_page_candidate("https://example.org/zhaopin/java", "Java 后端招聘")
    assert _job_page_candidate("https://example.org/jobs/123", "Java 后端实习生")


@pytest.mark.asyncio
async def test_permanent_provider_failure_skips_later_batches():
    calls = 0

    class BlockedLLM:
        async def complete(self, request):
            nonlocal calls
            calls += 1
            raise LLMProviderError("quota", retryable=False, http_status=402)

    state = {}
    context = SourceContext(SearchFilters(), "后端", 10, 12, "test", None,
                            SimpleNamespace(job_search_extraction_retries=1),
                            {"llm": BlockedLLM(), "model_availability": state})
    batch = [("https://example.org/jobs/123", {"pageIndex": 0, "title": "后端实习", "body": "招聘信息"})]
    await _extract_postings(context, batch)
    _, stats = await _extract_postings(context, batch)
    assert calls == 1 and state["blockedStatus"] == 402
    assert stats["modelAttempts"] == 0 and stats["rejected"]["MODEL_UNAVAILABLE"] == 1


def test_official_pending_pages_can_continue_after_web_failure():
    pipeline = JobSearchPipeline(SearchRuntime(SimpleNamespace(), FakeLLM(), None, None, {}, {}))
    pipeline.budget.begin_round()
    pipeline.source_extras["company_official"] = {"pending_pages": {"https://example.org/jobs/123": {}}}
    assert pipeline._route_after_search({"sources": [{"key": "web", "status": "FAILED"}]}) == "adjust"


def test_explicit_application_period_closes_the_posting_after_the_end():
    body = "后端工程师实习 投递时间：2026年10月30日-2026年10月31日 职位职责"
    assert not closed_text(body, datetime(2026, 10, 31, 10, tzinfo=timezone.utc))
    assert closed_text(body, datetime(2026, 11, 12, tzinfo=timezone.utc))
