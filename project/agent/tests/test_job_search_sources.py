from __future__ import annotations

import json
from datetime import datetime, timezone
from types import SimpleNamespace

import httpx
import pytest

from app.search.models import SearchFilters
from app.search.sources.baidu import BaiduCareerSource
from app.search.sources.base import SourceContext
from app.search.sources.greenhouse import GreenhouseSource
from app.search.sources.tencent import TencentCareerSource
from app.search.sources.unavailable import BossZhipinSource, LiepinSource, ZhaopinSource


def context(client: httpx.AsyncClient, **filters: object) -> SourceContext:
    keyword = str(filters.pop("keyword", "Java"))
    values = {
        "cities": ("440300",),
        "candidate_types": ("EXPERIENCED",),
        "company_sizes": (),
        "include_unknown_size": True,
    }
    values.update(filters)
    return SourceContext(
        filters=SearchFilters(**values),
        keyword=keyword,
        limit=10,
        client=client,
        settings=SimpleNamespace(job_search_greenhouse_boards="stripe:Stripe"),
    )


@pytest.mark.asyncio
async def test_tencent_official_payload_is_parsed(monkeypatch: pytest.MonkeyPatch) -> None:
    async def allow(*args, **kwargs):
        return args[0] if args else ""

    monkeypatch.setattr("app.search.sources.tencent.ensure_public_url", allow)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={
            "Code": 200,
            "Data": {
                "Posts": [
                    {
                        "RecruitPostName": "Java 后端开发工程师",
                        "LocationName": "深圳",
                        "ComName": "腾讯云智研发子公司",
                        "Responsibility": "负责 Java 后端开发，三年以上工作经验。",
                        "PostURL": "https://careers.tencent.com/jobdesc.html?postId=1",
                        "PostId": "1",
                        "LastUpdateTime": "2026年11月02日",
                        "RequireWorkYearsName": "三年以上工作经验",
                    }
                ]
            }
        })

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        result = await TencentCareerSource().search(context(client, keyword="Java"))

    assert result.status == "AVAILABLE"
    assert result.http_status == 200
    assert len(result.postings) == 1
    posting = result.postings[0]
    assert posting.title == "Java 后端开发工程师"
    assert posting.company_name == "腾讯云智研发子公司"
    assert posting.city_code == "440300"
    assert posting.experience_months_min == 36
    assert posting.candidate_type == "EXPERIENCED"
    assert posting.verification == "VERIFIED"
    assert posting.company_size is None or posting.company_size == "SIZE_UNKNOWN"
    assert posting.published_at is not None


@pytest.mark.asyncio
async def test_tencent_city_filter_keeps_empty_result(monkeypatch: pytest.MonkeyPatch) -> None:
    async def allow(*args, **kwargs):
        return args[0] if args else ""

    monkeypatch.setattr("app.search.sources.tencent.ensure_public_url", allow)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"Code": 200, "Data": {"Posts": [{
            "RecruitPostName": "Java 后端",
            "LocationName": "北京",
            "Responsibility": "负责 Java 后端开发，三年以上经验。",
            "PostURL": "https://careers.tencent.com/jobdesc.html?postId=2",
            "PostId": "2",
            "LastUpdateTime": "2026年11月02日",
            "RequireWorkYearsName": "三年以上工作经验",
        }]}})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        result = await TencentCareerSource().search(context(client))
    assert result.status == "EMPTY"
    assert result.postings == []


@pytest.mark.asyncio
async def test_greenhouse_payload_is_parsed(monkeypatch: pytest.MonkeyPatch) -> None:
    async def allow(*args, **kwargs):
        return args[0] if args else ""

    monkeypatch.setattr("app.search.sources.greenhouse.ensure_public_url", allow)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"jobs": [{
            "id": 1,
            "title": "Backend Engineer",
            "absolute_url": "https://boards-api.greenhouse.io/v1/boards/stripe/jobs/1",
            "content": "<p>Java and Spring Boot. 3+ years of experience.</p>",
            "location": {"name": "Shenzhen"},
            "updated_at": "2026-11-01T00:00:00Z",
        }]})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        result = await GreenhouseSource().search(context(client, keyword="Java"))
    assert result.status == "AVAILABLE"
    assert len(result.postings) == 1
    posting = result.postings[0]
    assert posting.company_name == "Stripe"
    assert posting.city_code == "440300"
    assert posting.verification == "VERIFIED"
    assert posting.title == "Backend Engineer"


@pytest.mark.asyncio
async def test_baidu_checks_requested_list_and_dedupes_multi_city_posting(monkeypatch) -> None:
    async def allow(url, **kwargs):
        return url

    monkeypatch.setattr("app.search.sources.baidu.ensure_public_url", allow)
    job = {"postId": "e3cec5b8-b7a3-4946-99fc-b292b749cd53", "name": "智能体应用开发实习生",
           "workPlace": "北京市,上海市", "workContent": "负责智能体应用开发",
           "publishDate": "2026-04-28", "updateDate": "2026-07-21"}

    def handle(request):
        keyword = request.url.params["search"]
        data = {"listData": {"keyWord": keyword, "recruitType": "INTERN" if keyword == "AI 应用开发" else "SOCIAL",
                             "listDetailData": [job], "total": 1}}
        return httpx.Response(200, text=f"<script>window.__INITIAL_DATA__ = {json.dumps(data)}</script>")

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        result = await BaiduCareerSource().search(context(client, keyword="AI 应用开发", cities=("110100", "310100"),
                                                          candidate_types=("INTERNSHIP",)))
    assert result.status == "PARTIAL"
    assert len(result.postings) == 1
    assert result.postings[0].recruitment_evidence == "OFFICIAL_LIST"
    assert result.postings[0].candidate_type == "INTERNSHIP"
    assert result.postings[0].published_at is not None
    assert result.postings[0].published_at.date().isoformat() == "2026-04-28"
    assert result.postings[0].updated_at is not None
    assert result.postings[0].updated_at.date().isoformat() == "2026-07-21"


@pytest.mark.asyncio
async def test_unavailable_adapters_do_not_pretend_to_be_connected() -> None:
    for adapter in (BossZhipinSource(), ZhaopinSource(), LiepinSource()):
        result = await adapter.search(context(None))  # type: ignore[arg-type]
        assert result.status == "UNAVAILABLE"
        assert result.requires_credentials is True
        assert result.message
        assert result.postings == []

@pytest.mark.asyncio
async def test_web_search_keeps_relevant_results_independent_of_recruitment_status() -> None:
    from app.llm import LLMResponse
    from app.search.sources.web import WebSearchSource

    jd = "岗位职责：开发企业知识库问答与检索增强生成服务，维护模型接入、评测和数据处理流程。"
    cities = ["杭州", "上海", "深圳", "北京", "上海"]
    urls = [f"https://careers.example.com/company/{index}" for index in range(len(cities))]

    def handle(request: httpx.Request) -> httpx.Response:
        assert request.url.host == "api.deepseek.com"
        payload = json.loads(request.content)
        assert "include_domains" not in payload
        query = payload["query"]
        assert "实习" in query
        assert ("杭州" in query) != ("上海" in query)
        return httpx.Response(200, json={"results": [
            {
                "url": url, "title": "AI 应用开发实习生",
                "content": f"示例公司 工作地点：{city} 实习 公司规模：100-499人 {jd}" + (" 当前职位已下线" if index == 4 else ""),
            }
            for index, (url, city) in enumerate(zip(urls, cities))
        ]})

    class ExtractionLLM:
        async def complete(self, request):
            return LLMResponse(json.dumps({"postings": [
                {"pageIndex": str(index), "title": "AI 应用开发实习生", "companyName": "示例公司",
                 "cityName": "杭州" if index == 3 else city, "candidateTypeText": "实习", "jdQuotes": [jd],
                 "companySizeText": "100-499人" if index else None}
                for index, city in enumerate(cities)
            ]}), "fake", "test")

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        context = SourceContext(
            filters=SearchFilters(cities=("330100", "310100"), candidate_types=("INTERNSHIP",),
                                  company_sizes=("SIZE_100_499",), include_unknown_size=False),
            keyword="AI 应用开发", limit=10, client=client,
            settings=SimpleNamespace(deepseek_api_key="test", job_search_detail_verification=False),
            extra={"llm": ExtractionLLM()},
        )
        result = await WebSearchSource().search(context)
        assert result.status == "AVAILABLE"
        # 页面正文可确认城市：索引 0 杭州、索引 1 上海保留；深圳、北京明确不符被排除。
        assert [posting.source_url for posting in result.postings] == [urls[0], urls[1], urls[4]]
        assert result.postings[0].verification == "UNVERIFIED"
        assert [posting.recruitment_status for posting in result.postings] == [
            "UNKNOWN", "UNKNOWN", "CLOSED"
        ]
        assert [posting.verification_status for posting in result.postings] == [
            "UNKNOWN", "UNKNOWN", "CLOSED"
        ]
        assert (result.postings[0].city_name, result.postings[0].city_code) == ("杭州", "330100")
        assert (result.postings[1].city_name, result.postings[1].city_code) == ("上海", "310100")
        assert result.postings[0].company_size == "SIZE_100_499"
        assert result.postings[1].company_size == "SIZE_100_499"
        assert result.postings[0].recruitment_evidence is None
        assert result.postings[1].recruitment_evidence is None
        assert result.postings[2].recruitment_evidence == "OFFICIAL_CLOSED"
        assert result.verification_counts["closed"] == 1
        assert result.verification_counts["discovered"] == 5


def _detail_html(url: str, *, extra: str = "", deadline: str = "", link: str = "") -> str:
    job = {"@type": "JobPosting", "title": "AI 应用开发实习生", "url": url, "validThrough": deadline}
    return (f'<script type="application/ld+json">{json.dumps(job)}</script>'
            f'<h1>AI 应用开发实习生</h1><p>{extra}</p><a href="{link or url + "/apply"}">立即申请</a>')


@pytest.mark.parametrize(("extra", "deadline", "link", "expected_status", "expected_reason"), [
    ("", "", "", "ACTIVE_PROBABLE", "ACTIVE_EVIDENCE_FOUND"),
    ("该岗位已经下线", "", "", "CLOSED", "OFFICIAL_CLOSED"),
    ("", "2025-03-01", "", "CLOSED", "EXPIRED"),
    ("", "", "/login", "ACTIVE_PROBABLE", "APPLY_LINK_MISSING"),
    ("", "", "/company/999/apply", "ACTIVE_PROBABLE", "APPLY_LINK_MISSING"),
])
def test_detail_status_requires_job_specific_evidence(extra, deadline, link, expected_status, expected_reason) -> None:
    from app.search.sources.detail import read_detail_result

    url = "https://jobs.lever.co/company/123"
    html = _detail_html(url, extra=extra, deadline=deadline, link=link)
    result = read_detail_result(url, html, datetime(2026, 11, 6, tzinfo=timezone.utc))
    assert (result.status, result.reason) == (expected_status, expected_reason)


def test_detail_accepts_job_specific_application_routes_on_other_sites() -> None:
    from app.search.sources.detail import read_detail_result

    url = "https://careers.example.com/detail?jobId=123"
    now = datetime(2026, 11, 6, tzinfo=timezone.utc)
    valid = read_detail_result(url, _detail_html(url, link="/apply?jobId=123"), now)
    assert (valid.status, valid.reason, valid.apply_link) == ("ACTIVE_PROBABLE", "ACTIVE_EVIDENCE_FOUND", True)
    mismatched = read_detail_result(url, _detail_html(url, link="/apply?jobId=999"), now)
    assert (mismatched.status, mismatched.reason) == ("ACTIVE_PROBABLE", "APPLY_LINK_MISSING")


@pytest.mark.asyncio
@pytest.mark.parametrize(("response", "expected", "reason"), [
    (httpx.Response(404), "UNKNOWN", "HTTP_404"),
    (httpx.Response(410), "UNKNOWN", "HTTP_404"),
    (httpx.Response(302, headers={"Location": "/company"}), "UNKNOWN", "PAGE_ACCESS_FAILED"),
    (httpx.Response(403), "UNKNOWN", "HTTP_403"),
    (httpx.Response(401), "UNKNOWN", "HTTP_403"),
    (httpx.ReadTimeout("timeout"), "UNKNOWN", "TIMEOUT"),
])
async def test_detail_network_failures_are_not_closed(monkeypatch, response, expected, reason) -> None:
    from app.search.sources.detail import fetch_detail

    async def resolve(_host):
        return "93.184.216.34"

    def handle(_):
        if isinstance(response, Exception):
            raise response
        return response

    monkeypatch.setattr("app.search.sources.detail.resolve_public_ipv4", resolve)
    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        result = await fetch_detail(client, "https://jobs.lever.co/company/123", 1)
        assert (result.status, result.reason) == (expected, reason)


@pytest.mark.asyncio
async def test_detail_blocks_redirect_to_internal_ip_before_connecting(monkeypatch) -> None:
    from app.search.sources.detail import fetch_detail

    monkeypatch.setattr("app.search.security._default_resolver", lambda host: [
        "93.184.216.34" if host == "careers.example.com" else "127.0.0.1",
    ])
    seen = []

    def handle(request):
        seen.append(request.headers["host"])
        return httpx.Response(302, headers={"Location": "https://redirect.example.com/company/123"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        result = await fetch_detail(client, "https://careers.example.com/company/123", 1)
        assert (result.status, result.reason) == ("UNKNOWN", "PAGE_ACCESS_FAILED")
    assert seen == ["careers.example.com"]


@pytest.mark.asyncio
async def test_web_search_distinguishes_no_config_and_provider_failure() -> None:
    from types import SimpleNamespace

    from app.search.sources.web import WebSearchSource

    source = WebSearchSource()
    context = SourceContext(filters=SearchFilters(), keyword="Java 后端", limit=8)
    assert (await source.search(context)).status == "UNAVAILABLE"
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(429))) as client:
        context.client = client
        context.settings = SimpleNamespace(deepseek_api_key="test")
        context.extra["llm"] = object()
        result = await source.search(context)
        assert (result.status, result.http_status) == ("FAILED", 429)
