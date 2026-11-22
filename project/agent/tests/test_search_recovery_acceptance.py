"""S04: deterministic upstream faults, bounded retries and cache lifecycle."""
import asyncio
import time
from types import SimpleNamespace

import httpx
import pytest

from app.fake_model import FakeLLM
from app.search.budget import BudgetConfig, SearchBudget
from app.search.models import SourcePosting
from app.search.pipeline import JobSearchPipeline, SearchRuntime
from app.search.providers.brave import BraveProvider
from app.search.providers.tavily import TavilyProvider
from app.search.sources.base import SourceAdapter


@pytest.mark.asyncio
@pytest.mark.parametrize("provider", [BraveProvider(), TavilyProvider()])
@pytest.mark.parametrize("fault", ["timeout", 503, 429])
async def test_same_provider_recovers_with_bounded_attempts(provider, fault):
    calls = []
    healthy = False

    def handle(request):
        calls.append(request)
        if not healthy:
            if fault == "timeout":
                raise httpx.ReadTimeout("injected timeout", request=request)
            return httpx.Response(fault)
        row = {"url": "https://example.invalid/job/1", "title": "Java", "content": "Java"}
        return httpx.Response(200, json={"web": {"results": [row]}, "results": [row]})

    settings = SimpleNamespace(brave_search_api_key="test-only", tavily_api_key="test-only")
    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        failed = await provider.search(client, "Java", settings=settings, limit=1, timeout=0.1, retries=1)
        assert failed.status == ("RATE_LIMITED" if fault == 429 else "FAILED")
        assert failed.attempts == len(calls) == 2
        healthy = True
        recovered = await provider.search(client, "Java", settings=settings, limit=1, timeout=0.1, retries=1)
        assert recovered.status == "OK"
        assert recovered.attempts == 1
        assert len(calls) == 3


@pytest.mark.asyncio
@pytest.mark.parametrize("provider", [BraveProvider(), TavilyProvider()])
async def test_transient_503_recovers_within_same_request(provider):
    count = 0

    def handle(request):
        nonlocal count
        count += 1
        return httpx.Response(503 if count == 1 else 200, json={"web": {"results": []}, "results": []})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        result = await provider.search(client, "Java", settings=SimpleNamespace(
            brave_search_api_key="test-only", tavily_api_key="test-only"), limit=1, timeout=0.1, retries=1)
    assert result.status == "EMPTY"
    assert result.attempts == count == 2


def test_query_cache_expires_and_is_not_shared_between_searches(monkeypatch):
    clock = [100.0]
    monkeypatch.setattr("app.search.budget.time.monotonic", lambda: clock[0])
    budget = SearchBudget(BudgetConfig(cache_ttl_seconds=3))
    hits = [object()]
    budget.remember("brave", "Java", hits)
    assert budget.cached("brave", "Java") is hits
    clock[0] += 4
    assert budget.cached("brave", "Java") is None
    budget.remember("brave", "Java", hits)
    assert SearchBudget(BudgetConfig()).cached("brave", "Java") is None


@pytest.mark.asyncio
async def test_timed_out_source_does_not_hide_healthy_source_and_new_search_recovers():
    healthy = False
    calls = []

    class Source(SourceAdapter):
        def __init__(self, key):
            self.key = key

        async def search(self, context):
            calls.append(self.key)
            if self.key == "web" and not healthy:
                await asyncio.sleep(2)
            return self.result("AVAILABLE", [SourcePosting(
                source_key=self.key, source_name=self.key, source_type="PUBLIC_SEARCH",
                source_url=f"https://example.invalid/{self.key}/job/1", title="Java 后端开发",
                company_name=self.key, jd_text="负责 Java 服务端开发和维护接口。")])

    async def emit(*args):
        pass

    async def search():
        runtime = SearchRuntime(SimpleNamespace(job_search_max_elapsed_seconds=30.1,
            job_search_max_rounds=1), FakeLLM(), emit, None, {},
            {"filters": {"keyword": "Java"}, "sources": ["web", "tencent"]})
        pipeline = JobSearchPipeline(runtime)
        pipeline.adapters = [Source("web"), Source("tencent")]
        return await pipeline.run()

    started = time.monotonic()
    first = await search()
    assert time.monotonic() - started < 1
    assert any(row["companyName"] == "tencent" for row in first["items"])
    assert any(row["status"] == "FAILED" for row in first["sources"])
    healthy = True
    second = await search()
    assert len(second["items"]) == 2
    assert all(row["status"] == "AVAILABLE" for row in second["sources"])
    assert calls.count("web") == 2
