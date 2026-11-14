from __future__ import annotations

import json
from types import SimpleNamespace

import httpx
import pytest

from app.search.budget import BudgetConfig, SearchBudget
from app.search.models import SearchFilters
from app.search.providers import resolve_providers
from app.search.providers.brave import BraveProvider
from app.search.providers.deepseek import DeepSeekSearchProvider
from app.search.providers.tavily import TavilyProvider
from app.search.sources.base import SourceContext
from app.search.sources.web import WebSearchSource


def settings(**values):
    base = {
        "tavily_api_key": "tavily-test",
        "deepseek_api_key": "deepseek-test",
        "brave_search_api_key": "brave-test",
        "job_search_detail_verification": False,
    }
    base.update(values)
    return SimpleNamespace(**base)


def budget(queries: int = 1) -> SearchBudget:
    return SearchBudget(BudgetConfig(max_queries_per_round=queries, max_pages_per_round=5,
                                     max_detail_pages=0, max_api_calls=20))


@pytest.mark.asyncio
async def test_tavily_provider_parses_results() -> None:
    def handle(request: httpx.Request) -> httpx.Response:
        assert request.url.host == "api.tavily.com"
        assert request.headers["authorization"] == "Bearer tavily-test"
        return httpx.Response(200, json={"results": [{
            "url": "https://jobs.example.com/1",
            "title": "Java 后端",
            "raw_content": "负责 Java 服务端开发",
            "score": 0.9,
        }]})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        result = await TavilyProvider().search(
            client, "深圳 Java 后端", settings=settings(), limit=5, timeout=3,
        )
    assert result.status == "OK"
    assert result.hits[0].provider == "tavily"
    assert result.hits[0].url == "https://jobs.example.com/1"


@pytest.mark.asyncio
async def test_brave_provider_parses_results() -> None:
    def handle(request: httpx.Request) -> httpx.Response:
        assert request.url.host == "api.search.brave.com"
        assert request.headers["x-subscription-token"] == "brave-test"
        assert request.url.params["q"] == "深圳 Java 后端"
        return httpx.Response(200, json={"web": {"results": [{
            "url": "https://jobs.example.com/2",
            "title": "Java 服务端开发",
            "description": "负责后端服务",
            "extra_snippets": ["要求本科"],
            "page_age": "2026-11-01T08:00:00",
        }]}})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        result = await BraveProvider().search(
            client, "深圳 Java 后端", settings=settings(), limit=5, timeout=3,
        )
    assert result.status == "OK"
    assert result.hits[0].provider == "brave"
    assert "要求本科" in result.hits[0].content
    assert result.hits[0].published_at is not None


def test_brave_without_key_degrades_to_deepseek() -> None:
    providers = resolve_providers(settings(brave_search_api_key=None))
    assert [provider.key for provider in providers] == ["deepseek"]


def test_no_provider_key_reports_unconfigured() -> None:
    assert resolve_providers(settings(deepseek_api_key=None, brave_search_api_key=None)) == []


@pytest.mark.asyncio
async def test_web_search_runs_two_providers_in_parallel_and_dedupes_urls() -> None:
    shared = "https://jobs.example.com/shared"
    unique_brave = "https://jobs.example.com/brave"

    def handle(request: httpx.Request) -> httpx.Response:
        if request.url.host == "api.deepseek.com":
            return httpx.Response(200, json={"results": [{
                "url": shared, "title": "Java 后端 示例公司 深圳",
                "content": "示例公司 深圳 社招 Java 后端 负责服务端接口与数据存储设计开发维护，参与高并发系统性能优化，要求熟悉 Java 与 Spring Boot 开发",
            }]})
        if request.url.host == "api.search.brave.com":
            return httpx.Response(200, json={"web": {"results": [
                {"url": shared, "title": "Java 后端 示例公司 深圳",
                 "description": "示例公司 深圳 社招 Java 后端 负责服务端接口与数据存储设计开发维护，参与高并发系统性能优化，要求熟悉 Java 与 Spring Boot 开发"},
                {"url": unique_brave, "title": "Java 服务端 示例公司 深圳",
                 "description": "示例公司 深圳 社招 Java 服务端 负责服务端接口与数据存储设计开发维护，参与高并发系统性能优化，要求熟悉 Java 与 Spring Boot 开发"},
            ]}})
        raise AssertionError(request.url)

    class ExtractionLLM:
        calls = 0

        async def complete(self, request):
            from app.llm import LLMResponse

            self.calls += 1
            jd = "负责服务端接口与数据存储设计开发维护，参与高并发系统性能优化，要求熟悉 Java 与 Spring Boot 开发"
            return LLMResponse(json.dumps({"postings": [
                {"pageIndex": "0", "title": "Java 后端", "companyName": "示例公司", "cityName": "深圳",
                 "candidateTypeText": "社招", "jdQuotes": [jd]},
                {"pageIndex": "1", "title": "Java 服务端", "companyName": "示例公司", "cityName": "深圳",
                 "candidateTypeText": "社招", "jdQuotes": [jd]},
            ]}), "fake", "test")

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        context = SourceContext(
            filters=SearchFilters(cities=("440300",), candidate_types=("EXPERIENCED",)),
            keyword="Java 后端", limit=10, client=client, settings=settings(),
            extra={"llm": ExtractionLLM(), "budget": budget()},
        )
        result = await WebSearchSource().search(context)

    assert [provider for provider in result.providers] == ["deepseek", "brave"]
    assert result.verification_counts["searchResults"]["deepseek"] == 1
    assert result.verification_counts["searchResults"]["brave"] == 2
    providers = {posting.source_url: posting.search_providers for posting in result.postings}
    assert providers.get(shared) == ["deepseek", "brave"], providers
    assert len(result.postings) == 2


@pytest.mark.asyncio
async def test_brave_rate_limit_degrades_without_breaking_deepseek() -> None:
    def handle(request: httpx.Request) -> httpx.Response:
        if request.url.host == "api.search.brave.com":
            return httpx.Response(429, json={"message": "rate limited"})
        return httpx.Response(200, json={"results": [{
            "url": "https://jobs.example.com/ok", "title": "Java 后端 示例公司 深圳",
            "content": "示例公司 深圳 社招 Java 后端 负责服务端接口与数据存储设计开发维护，参与高并发系统性能优化，要求熟悉 Java 与 Spring Boot 开发",
        }]})

    class ExtractionLLM:
        async def complete(self, request):
            from app.llm import LLMResponse

            return LLMResponse(json.dumps({"postings": [{
                "pageIndex": "0", "title": "Java 后端", "companyName": "示例公司", "cityName": "深圳",
                "candidateTypeText": "社招",
                "jdQuotes": ["负责服务端接口与数据存储设计开发维护，参与高并发系统性能优化，要求熟悉 Java 与 Spring Boot 开发"],
            }]}), "fake", "test")

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        context = SourceContext(
            filters=SearchFilters(cities=("440300",), candidate_types=("EXPERIENCED",)),
            keyword="Java 后端", limit=10, client=client, settings=settings(),
            extra={"llm": ExtractionLLM(), "budget": budget()},
        )
        result = await WebSearchSource().search(context)

    assert result.status == "PARTIAL"
    assert len(result.postings) == 1
    assert result.verification_counts["providerFailures"] >= 1



@pytest.mark.asyncio
async def test_deepseek_request_and_result_mapping() -> None:
    def handle(request: httpx.Request) -> httpx.Response:
        assert str(request.url) == "https://api.deepseek.com/v1/tools/search"
        assert request.headers["authorization"] == "Bearer deepseek-test"
        assert json.loads(request.content) == {"query": "深圳 Java", "count": 50, "need_content": True}
        return httpx.Response(200, json={"results": [{"url": "https://jobs.example.com/1",
            "title": "Java 后端", "summary": "招聘后端", "date": "2026-11-01T00:00:00Z", "rerank_score": 2.1}]})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        result = await DeepSeekSearchProvider().search(client, "深圳 Java", settings=settings(deepseek_base_url="https://api.deepseek.com/v1"), limit=100, timeout=3)
    assert result.status == "OK"
    assert result.hits[0].content == "招聘后端"
    assert result.hits[0].published_at is not None
    assert result.hits[0].score == 2.1


@pytest.mark.asyncio
async def test_deepseek_errors_and_retry() -> None:
    calls = 0
    def handle(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(429 if calls == 1 else 402)
    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        provider = DeepSeekSearchProvider()
        result = await provider.search(client, "Java", settings=settings(), limit=1, timeout=3)
        invalid = await provider.search(client, "x" * 129, settings=settings(), limit=1, timeout=3)
    assert result.status == "FAILED" and result.http_status == 402 and result.attempts == 2
    assert invalid.status == "FAILED" and calls == 2
