from __future__ import annotations

import json
from datetime import datetime, timezone
from types import SimpleNamespace

import httpx
import pytest

from app.search.budget import BudgetConfig, SearchBudget
from app.search.models import SearchFilters
from app.search.sources.base import SourceContext
from app.search.sources.detail import closed_text
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


async def _run(detail_response: httpx.Response, monkeypatch, *, max_detail_pages: int = 5) -> object:
    def handle(request: httpx.Request) -> httpx.Response:
        if request.method == "POST" and request.url.host == "api.deepseek.com":
            return httpx.Response(200, json={"results": [{
                "url": "https://careers.example.com/job/1",
                "title": "AI 应用开发实习生",
                "content": f"AI 应用开发实习生 示例公司 深圳 {JD}",
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
            settings=SimpleNamespace(deepseek_api_key="test", job_search_detail_verification=True),
            extra={"llm": ExtractionLLM(),
                   "budget": SearchBudget(BudgetConfig(max_queries_per_round=1, max_pages_per_round=5,
                                                       max_detail_pages=max_detail_pages, max_api_calls=20))},
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
        httpx.Response(200, headers={"content-type": "text/html"}, text=_html(closed=True)), monkeypatch,
        max_detail_pages=0,
    )
    posting = result.postings[0]
    assert posting.verification_status == "CLOSED"
    assert posting.verification_reason == "OFFICIAL_CLOSED"
    assert posting.is_closed is True


@pytest.mark.asyncio
@pytest.mark.parametrize("response", [httpx.Response(404), httpx.Response(410),
                                     httpx.Response(302, headers={"Location": "/careers"})])
async def test_prefetch_keeps_unavailable_link_evidence_without_extra_detail_budget(monkeypatch, response) -> None:
    result = await _run(response, monkeypatch, max_detail_pages=0)
    posting = result.postings[0]
    assert posting.is_unavailable
    assert posting.verification_http_status == response.status_code


@pytest.mark.parametrize(("body", "expected"), [
    ("薪资待遇：9000-12000元 学历要求： 硕士 工作经验： 招聘人数：", ("硕士", "MASTER", "9000-12000元")),
    ("浙江省杭州市 | 实习 | 本科及以上 | 0人 12K", ("本科及以上", "BACHELOR", "12K")),
    ("实习 300-500/天 | 学历要求：不限", ("学历不限", None, "300-500/天")),
    ("岗位职责：支持 16K上下文 发布时间：2026-11-23", (None, None, None)),
])
def test_search_parses_education_and_salary_as_separate_fields(body, expected) -> None:
    from app.search.sources.web import _to_posting

    title = "AI 应用开发实习生"
    posting, reason, _ = _to_posting({
        "title": title, "companyName": "示例公司", "candidateTypeText": "实习", "jdQuotes": [JD],
        "educationText": body,
    }, "https://careers.example.com/job/1", {"title": title, "body": f"{title} 示例公司 {body} {JD}"})
    assert posting is not None, reason
    assert (posting.education_text, posting.education_min, posting.salary_text) == expected
    if expected[2] == "300-500/天":
        assert posting.salary_min_monthly_cents is None


@pytest.mark.parametrize(("text", "is_closed"), [
    ("该职位已经结束", True),
    ("网申截止：2026-11-22", True),
    ("报名截止：2026-11-23", False),
    ("投递截止：2026-11-24", False),
])
def test_closed_text_distinguishes_closed_from_an_open_deadline(text, is_closed) -> None:
    assert closed_text(text, datetime(2026, 11, 23, 10, tzinfo=timezone.utc)) is is_closed
