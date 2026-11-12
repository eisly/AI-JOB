from __future__ import annotations

import json
from types import SimpleNamespace

import httpx
import pytest

from app.search.budget import BudgetConfig, SearchBudget
from app.search.models import SearchFilters
from app.search.sources.base import SourceContext
from app.search.sources.web import WebSearchSource

URL = "https://www.liepin.com/job/1985680015.shtml"
HTML = (
    "<html><head><title>AI应用开发实习生（可留用）-猎聘</title></head><body>"
    "AI应用开发实习生（可留用） 100-150元/天 杭州-滨江区 实习 本科 学生可投 "
    "职位地址：杭州-滨江区中财大厦 公司信息 华创证券有限责任公司 "
    "人数规模：2000-5000人 企业行业：基金/证券/期货 "
    "岗位职责：协助团队参与金融场景下 AI Agent 应用开发与接口联调。"
    "</body></html>"
)


class ExtractionLLM:
    async def complete(self, request):
        from app.llm import LLMResponse

        return LLMResponse(json.dumps({"postings": [{
            "pageIndex": "0",
            "title": "AI应用开发实习生（可留用）",
            "companyName": "华创证券有限责任公司",
            "cityName": "杭州-滨江区",
            "candidateTypeText": "实习",
            "jdQuotes": ["岗位职责：协助团队参与金融场景下 AI Agent 应用开发与接口联调。"],
        }]}), "fake", "test")


@pytest.mark.asyncio
async def test_short_tavily_summary_fetches_detail_page_and_fills_fields(monkeypatch) -> None:
    async def resolve(_host):
        return "93.184.216.34"

    monkeypatch.setattr("app.search.sources.detail.resolve_public_ipv4", resolve)

    def handle(request: httpx.Request) -> httpx.Response:
        if request.method == "POST" and request.url.host == "api.tavily.com":
            return httpx.Response(200, json={"results": [{
                "url": URL,
                "title": "AI应用开发实习生（可留用）",
                "raw_content": "AI应用开发实习生（可留用） 华创证券 实习 杭州",
            }]})
        return httpx.Response(200, headers={"content-type": "text/html; charset=utf-8"}, text=HTML)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        context = SourceContext(
            filters=SearchFilters(cities=("330100",), candidate_types=("INTERNSHIP",)),
            keyword="AI 应用开发实习", limit=10, client=client,
            settings=SimpleNamespace(tavily_api_key="test", job_search_detail_verification=True),
            extra={
                "llm": ExtractionLLM(),
                "budget": SearchBudget(BudgetConfig(
                    max_queries_per_round=1, max_pages_per_round=5,
                    max_detail_pages=5, max_api_calls=20,
                )),
            },
        )
        result = await WebSearchSource().search(context)

    assert result.status == "AVAILABLE"
    assert len(result.postings) == 1
    posting = result.postings[0]
    assert posting.salary_text == "100-150元/天"
    assert posting.city_code == "330100"
    assert posting.company_name == "华创证券有限责任公司"
    assert posting.company_size == "SIZE_1000_9999"
    assert posting.education_min == "BACHELOR"
    assert "SALARY_FROM_TEXT" in posting.warnings or "SALARY_FROM_DETAIL" in posting.warnings
    assert result.verification_counts["pagesFetched"] == 1