from __future__ import annotations

from types import SimpleNamespace

import httpx
import pytest

from app.search.models import SearchFilters
from app.search.sources.base import SourceContext
from app.search.sources.greenhouse import GreenhouseSource
from app.search.sources.tencent import TencentCareerSource
from app.search.sources.unavailable import BossZhipinSource, LiepinSource, ZhaopinSource


def context(client: httpx.AsyncClient, **filters: object) -> SourceContext:
    values = {
        "cities": ("440300",),
        "candidate_types": ("EXPERIENCED",),
        "company_sizes": (),
        "include_unknown_size": True,
    }
    values.update(filters)
    return SourceContext(
        filters=SearchFilters(**values),
        keyword=str(filters.pop("keyword", "Java")),
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
            "Data": {
                "Posts": [
                    {
                        "RecruitPostName": "Java 后端开发工程师",
                        "LocationName": "深圳",
                        "ComName": "腾讯云智研发子公司",
                        "Responsibility": "负责 Java 后端开发，三年以上工作经验。",
                        "PostURL": "https://careers.tencent.com/jobdesc.html?postId=1",
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
        return httpx.Response(200, json={"Data": {"Posts": [{
            "RecruitPostName": "Java 后端",
            "LocationName": "北京",
            "Responsibility": "负责 Java 后端开发，三年以上经验。",
            "PostURL": "https://careers.tencent.com/jobdesc.html?postId=2",
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
async def test_unavailable_adapters_do_not_pretend_to_be_connected() -> None:
    for adapter in (BossZhipinSource(), ZhaopinSource(), LiepinSource()):
        result = await adapter.search(context(None))  # type: ignore[arg-type]
        assert result.status == "UNAVAILABLE"
        assert result.requires_credentials is True
        assert result.message
        assert result.postings == []