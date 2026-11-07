from __future__ import annotations

from types import SimpleNamespace

import httpx
import pytest

from app.search.models import SearchFilters
from app.search.sources.base import SourceContext
from app.search.sources.company_registry import CompanyRegistry
from app.search.sources.lever import LeverSource


def context(client: httpx.AsyncClient, **filters: object) -> SourceContext:
    keyword = str(filters.pop("keyword", "Java"))
    values = {"cities": ("440300",), "candidate_types": ("EXPERIENCED",),
              "company_sizes": (), "include_unknown_size": True}
    values.update(filters)
    return SourceContext(
        filters=SearchFilters(**values), keyword=keyword, limit=10, client=client,
        settings=SimpleNamespace(job_search_lever_boards="acme:Acme"),
        extra={"company_registry": CompanyRegistry([])},
    )


@pytest.mark.asyncio
async def test_lever_payload_is_parsed(monkeypatch) -> None:
    async def allow(url, **kwargs):
        return url

    monkeypatch.setattr("app.search.sources.lever.ensure_public_url", allow)

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.host == "api.lever.co"
        return httpx.Response(200, json=[{
            "id": "abc-123",
            "text": "Java Backend Engineer",
            "hostedUrl": "https://jobs.lever.co/acme/abc-123",
            "categories": {"location": "Shenzhen, China", "commitment": "Full-time"},
            "descriptionPlain": "负责 Java 后端开发，三年以上工作经验，熟悉 Spring Boot。",
            "createdAt": 1761955200000,
        }])

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        result = await LeverSource().search(context(client))

    assert result.status == "AVAILABLE"
    assert len(result.postings) == 1
    posting = result.postings[0]
    assert posting.company_name == "Acme"
    assert posting.city_code == "440300"
    assert posting.verification_status == "ACTIVE_CONFIRMED"
    assert posting.recruitment_evidence == "OFFICIAL_LIST"
    assert posting.published_at is not None


@pytest.mark.asyncio
async def test_lever_without_boards_is_empty() -> None:
    source = LeverSource()
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(200, json=[]))) as client:
        ctx = context(client)
        ctx.settings = SimpleNamespace(job_search_lever_boards="")
        result = await source.search(ctx)
    assert result.status == "EMPTY"
    assert result.postings == []


def test_company_registry_reads_configured_boards_and_records_collection() -> None:
    registry = CompanyRegistry.from_settings(SimpleNamespace(
        job_search_greenhouse_boards="stripe:Stripe,acme:Acme",
        job_search_lever_boards="lever-co:Lever Co",
    ))
    assert len(registry.for_platform("greenhouse")) == 2
    assert len(registry.for_platform("lever")) == 1
    registry.record("lever:lever-co", "AVAILABLE", 3)
    report = registry.report()
    assert report["companies"][0]["key"] == "lever:lever-co"
    assert report["companies"][0]["lastCollectedAt"] is not None
    assert report["companies"][0]["lastCount"] == 3