from __future__ import annotations

import json
from types import SimpleNamespace

import httpx
import pytest

from app.search.budget import BudgetConfig, SearchBudget
from app.search.models import SearchFilters
from app.search.sources.base import SourceContext
from app.search.sources.web import WebSearchSource

JD = "岗位职责：开发企业知识库问答与检索增强生成服务，维护模型接入、评测和数据处理流程。"


class ExtractionLLM:
    async def complete(self, request):
        from app.llm import LLMResponse

        return LLMResponse(json.dumps({"postings": [{
            "pageIndex": "0", "title": "AI 应用开发实习生", "companyName": "示例公司",
            "cityName": "深圳", "candidateTypeText": "实习", "jdQuotes": [JD],
        }]}), "fake", "test")


def _html(*, closed: bool = False, apply: bool = True) -> str:
    url = "https://careers.example.com/job/1"
    job = {"@type": "JobPosting", "title": "AI 应用开发实习生", "url": url}
    body = "AI 应用开发实习生 示例公司 深圳 当前职位已下线" if closed else "AI 应用开发实习生 示例公司 深圳"
    link = f'<a href="{url}/apply">立即申请</a>' if apply else ""
    return f'<script type="application/ld+json">{json.dumps(job)}</script><p>{body}</p>{link}'


async def _run(detail_response: httpx.Response, monkeypatch) -> object:
    def handle(request: httpx.Request) -> httpx.Response:
        if request.method == "POST" and request.url.host == "api.tavily.com":
            return httpx.Response(200, json={"results": [{
                "url": "https://careers.example.com/job/1",
                "title": "AI 应用开发实习生",
                "raw_content": f"AI 应用开发实习生 示例公司 深圳 {JD}",
            }]})
        if isinstance(detail_response, Exception):
            raise detail_response
        return detail_response

    async def resolve(_host):
        return "93.184.216.34"

    monkeypatch.setattr("app.search.sources.detail.resolve_public_ipv4", resolve)
    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        context = SourceContext(
            filters=SearchFilters(cities=("440300",), candidate_types=("INTERNSHIP",)),
            keyword="AI 应用开发实习", limit=10, client=client,
            settings=SimpleNamespace(tavily_api_key="test", job_search_detail_verification=True),
            extra={"llm": ExtractionLLM(),
                   "budget": SearchBudget(BudgetConfig(max_queries_per_round=1, max_pages_per_round=5,
                                                       max_detail_pages=5, max_api_calls=20))},
        )
        return await WebSearchSource().search(context)


@pytest.mark.asyncio
async def test_detail_403_keeps_posting_as_unknown(monkeypatch) -> None:
    result = await _run(httpx.Response(403, text="forbidden"), monkeypatch)
    assert len(result.postings) == 1
    posting = result.postings[0]
    assert (posting.verification_status, posting.verification_reason) == ("UNKNOWN", "HTTP_403")
    assert posting.is_closed is False


@pytest.mark.asyncio
async def test_detail_timeout_keeps_posting_as_unknown(monkeypatch) -> None:
    result = await _run(httpx.ReadTimeout("timeout"), monkeypatch)
    assert len(result.postings) == 1
    assert (result.postings[0].verification_status, result.postings[0].verification_reason) == ("UNKNOWN", "TIMEOUT")


@pytest.mark.asyncio
async def test_detail_page_with_apply_link_is_probable(monkeypatch) -> None:
    result = await _run(httpx.Response(200, headers={"content-type": "text/html"}, text=_html()), monkeypatch)
    posting = result.postings[0]
    assert posting.verification_status == "ACTIVE_PROBABLE"
    assert posting.verification_evidence == "DETAIL_APPLY_LINK"
    assert posting.recruitment_status == "PROBABLE"


@pytest.mark.asyncio
async def test_explicit_closed_text_is_closed(monkeypatch) -> None:
    result = await _run(
        httpx.Response(200, headers={"content-type": "text/html"}, text=_html(closed=True)), monkeypatch
    )
    posting = result.postings[0]
    assert posting.verification_status == "CLOSED"
    assert posting.verification_reason == "OFFICIAL_CLOSED"
    assert posting.is_closed is True