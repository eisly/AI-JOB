from __future__ import annotations

import asyncio
from datetime import datetime, timezone
from types import SimpleNamespace
from typing import Any

import pytest

from app.fake_model import FakeLLM
from app.llm import LLMResponse
from app.search.models import (
    SearchFilters,
    SourcePosting,
    SourceResult,
    company_size_matches,
    normalize_company_size,
    parse_company_size,
    parse_salary,
    parse_experience_months,
    posting_fingerprint,
    size_for_headcount,
)
from app.search.pipeline import JobSearchPipeline, SearchRuntime, _keyword_relevant
from app.search.security import UnsafeUrlError, ensure_public_url
from app.search.sources.base import SourceAdapter


def posting(**overrides: Any) -> SourcePosting:
    values = {
        "source_key": "tencent",
        "source_name": "腾讯招聘官网",
        "source_type": "OFFICIAL",
        "source_url": "https://careers.tencent.com/jobdesc.html?postId=1",
        "title": "Java 后端开发工程师",
        "company_name": "腾讯",
        "jd_text": "负责 Java 后端开发，要求本科及以上学历，三年以上工作经验，熟悉 Java、Spring Boot、MySQL、Redis。",
        "city_name": "深圳",
        "city_code": "440300",
        "candidate_type": "EXPERIENCED",
        "experience_text": "三年以上工作经验",
        "experience_months_min": 36,
        "education_min": "BACHELOR",
        "company_size": "SIZE_UNKNOWN",
        "published_at": datetime(2026, 11, 2, tzinfo=timezone.utc),
        "fetched_at": datetime(2026, 11, 6, tzinfo=timezone.utc),
        "verification": "VERIFIED",
        "recruitment_evidence": "OFFICIAL_LIST",
    }
    values.update(overrides)
    return SourcePosting(**values)


def payload(**filters: Any) -> dict[str, Any]:
    filter_values = {
        "keyword": "Java 后端",
        "cities": ["440300"],
        "candidateTypes": ["EXPERIENCED"],
        "companySizes": [],
        "includeUnknownSize": True,
        "maxResults": 30,
    }
    filter_values.update(filters)
    return {
        "profile": {
            "profileVersion": 3,
            "targetRole": "JAVA_BACKEND",
            "candidateType": "EXPERIENCED",
            "cities": ["440300"],
            "facts": {
                "education": "BACHELOR",
                "experienceMonths": 48,
                "skills": [{"skillCode": "JAVA"}, {"skillCode": "SPRING_BOOT"}],
            },
        },
        "filters": filter_values,
        "sources": ["tencent"],
        "skillCatalog": [
            {"code": "JAVA", "name": "Java"},
            {"code": "SPRING_BOOT", "name": "Spring Boot"},
            {"code": "MYSQL", "name": "MySQL"},
            {"code": "REDIS", "name": "Redis"},
        ],
        "limits": {"perSource": 20, "maxTotal": 60, "concurrency": 2, "sourceTimeoutSeconds": 5},
    }


async def noop_stage(_name: str, _progress: int | None) -> None:
    return None


class FixedAdapter(SourceAdapter):
    key = "tencent"
    name = "腾讯招聘官网"
    type = "OFFICIAL"
    verification = "VERIFIED"
    available = True

    def __init__(self, postings: list[SourcePosting] | None = None, *, fail: bool = False) -> None:
        self.postings = postings or []
        self.fail = fail
        self.calls = 0

    async def search(self, context) -> SourceResult:
        self.calls += 1
        if self.fail:
            raise RuntimeError("boom")
        return self.result("AVAILABLE" if self.postings else "EMPTY", self.postings)


class EmptyThenFilledAdapter(FixedAdapter):
    key = "web"

    def __init__(self, postings: list[SourcePosting]) -> None:
        super().__init__(postings)
        self.contexts = []

    async def search(self, context) -> SourceResult:
        self.contexts.append(context)
        self.calls += 1
        if self.calls == 1:
            return self.result("EMPTY", [])
        return self.result("AVAILABLE", self.postings)


def runtime(llm: Any, overrides: dict[str, Any] | None = None) -> SearchRuntime:
    data = payload(**(overrides or {}))
    settings = SimpleNamespace(
        job_search_max_jd_chars=30000,
        job_search_per_source=20,
        job_search_max_total=60,
        job_search_concurrency=2,
        job_search_timeout_seconds=5,
        job_search_sources="tencent",
        job_search_greenhouse_boards="stripe:Stripe",
        job_search_user_agent="AI-JOB-Agent/test",
    )
    return SearchRuntime(
        settings=settings,
        llm=llm,
        emit_stage=noop_stage,
        client=object(),
        profile=data["profile"],
        payload=data,
    )


@pytest.mark.parametrize(
    ("count", "expected"),
    [
        (0, "SIZE_0_20"),
        (20, "SIZE_0_20"),
        (21, "SIZE_21_99"),
        (99, "SIZE_21_99"),
        (100, "SIZE_100_499"),
        (499, "SIZE_100_499"),
        (500, "SIZE_500_999"),
        (999, "SIZE_500_999"),
        (1000, "SIZE_1000_9999"),
        (9999, "SIZE_1000_9999"),
        (10000, "SIZE_10000_PLUS"),
        (None, "SIZE_UNKNOWN"),
    ],
)
def test_company_size_boundaries(count: int | None, expected: str) -> None:
    assert size_for_headcount(count) == expected


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("0-20人", "SIZE_0_20"),
        ("21-99人", "SIZE_21_99"),
        ("100-499人", "SIZE_100_499"),
        ("500-999人", "SIZE_500_999"),
        ("1000-9999人", "SIZE_1000_9999"),
        ("10000人以上", "SIZE_10000_PLUS"),
        ("2000-5000人", "SIZE_1000_9999"),
        ("50-150人", "SIZE_UNKNOWN"),
        ("20人以下", "SIZE_0_20"),
        ("规模未知", "SIZE_UNKNOWN"),
        ("保密", "SIZE_UNKNOWN"),
        ("", "SIZE_UNKNOWN"),
    ],
)
def test_company_size_text_parsing(text: str, expected: str) -> None:
    assert parse_company_size(text) == normalize_company_size(expected)


def test_company_size_filter_unknown() -> None:
    assert company_size_matches("SIZE_UNKNOWN", (), True) is True
    assert company_size_matches("SIZE_UNKNOWN", (), False) is False
    assert company_size_matches("SIZE_UNKNOWN", ("SIZE_UNKNOWN",), True) is True
    assert company_size_matches("SIZE_UNKNOWN", ("SIZE_UNKNOWN",), False) is True
    assert company_size_matches("SIZE_UNKNOWN", ("SIZE_0_20",), True) is True
    assert company_size_matches("SIZE_UNKNOWN", ("SIZE_0_20",), False) is False
    assert company_size_matches("SIZE_0_20", ("SIZE_21_99",), True) is False
    assert company_size_matches("SIZE_10000_PLUS", ("SIZE_10000_PLUS",), False) is True


@pytest.mark.parametrize(
    ("text", "low", "high"),
    [
        ("15-25K", 1_500_000, 2_500_000),
        ("1.5-2.5万", 1_500_000, 2_500_000),
        ("8000-12000元", 800_000, 1_200_000),
        ("20-30万/年", 1_666_667, 2_500_000),
        ("面议", None, None),
        ("300元/天", None, None),
    ],
)
def test_salary_parsing(text: str, low: int | None, high: int | None) -> None:
    assert parse_salary(text) == (low, high)


def test_experience_parsing() -> None:
    assert parse_experience_months("三年以上工作经验") == 36
    assert parse_experience_months("3-5年") == 36
    assert parse_experience_months("经验不限") is None


def test_fingerprint_stable() -> None:
    assert posting_fingerprint("腾讯科技有限公司", "Java 后端（急聘）", "深圳") == posting_fingerprint("腾讯科技", "Java后端", "深圳市")


@pytest.mark.asyncio
async def test_pipeline_dedupe_and_explain(monkeypatch: pytest.MonkeyPatch) -> None:
    primary = posting()
    alias = posting(
        source_key="greenhouse",
        source_name="公司招聘官网（Greenhouse ATS）",
        source_type="OFFICIAL",
        source_url="https://boards-api.greenhouse.io/v1/boards/stripe/jobs/1",
        company_name="腾讯科技有限公司",
        title="Java 后端开发工程师（急聘）",
        city_name="深圳",
    )
    adapter = FixedAdapter([primary, alias])
    monkeypatch.setattr("app.search.pipeline.resolve_sources", lambda requested, settings: [adapter])
    llm = FakeLLM()
    result = await JobSearchPipeline(runtime(llm)).run()

    assert len(result["items"]) == 1
    item = result["items"][0]
    assert item["companySize"] == "SIZE_UNKNOWN"
    assert len(item["aliases"]) == 2
    assert "COMPANY_SIZE_UNKNOWN" in item["warnings"]
    assert result["dedupe"]["mergedCount"] == 1
    assert result["sources"][0]["status"] == "AVAILABLE"
    assert llm.calls
    prompt = llm.calls[0].user_prompt
    assert "10000000-0000-4000-8000" not in prompt
    assert "13800138000" not in prompt


@pytest.mark.asyncio
async def test_pipeline_adjusts_query_when_empty(monkeypatch: pytest.MonkeyPatch) -> None:
    adapter = EmptyThenFilledAdapter([posting()])
    monkeypatch.setattr("app.search.pipeline.resolve_sources", lambda requested, settings: [adapter])
    class SynonymLLM(FakeLLM):
        async def complete(self, request):
            if request.schema_hint == "keyword":
                return LLMResponse('{"keyword":"Java 服务端"}', "fake", "test")
            return await super().complete(request)

    run = runtime(SynonymLLM(), {"cities": ["440300", "310100"], "includeUnknownSize": False})
    run.settings.deepseek_api_key = "test"
    result = await JobSearchPipeline(run).run()
    assert adapter.calls >= 2
    assert adapter.contexts[0].filters == adapter.contexts[1].filters
    assert adapter.contexts[1].keyword == "Java 服务端"
    assert result["queryPlan"]["cities"] == ["440300", "310100"]
    assert result["queryPlan"]["expansions"][0] == "Java 后端"
    assert result["queryPlan"]["adjustments"]
    assert "QUERY_ADJUSTED" in result["warnings"]
    assert len(result["items"]) == 1


@pytest.mark.asyncio
async def test_pipeline_source_failure_is_partial(monkeypatch: pytest.MonkeyPatch) -> None:
    good = FixedAdapter([posting()])
    bad = FixedAdapter(fail=True)
    bad.key = "boss"
    bad.name = "BOSS直聘"
    monkeypatch.setattr("app.search.pipeline.resolve_sources", lambda requested, settings: [good, bad])
    result = await JobSearchPipeline(runtime(FakeLLM())).run()
    statuses = {item["key"]: item["status"] for item in result["sources"]}
    assert statuses["tencent"] == "AVAILABLE"
    assert statuses["boss"] == "FAILED"
    assert "SOME_SOURCES_PARTIAL_FAILURE" in result["warnings"]
    assert len(result["items"]) == 1


@pytest.mark.asyncio
async def test_pipeline_empty_result(monkeypatch: pytest.MonkeyPatch) -> None:
    adapter = FixedAdapter([])
    monkeypatch.setattr("app.search.pipeline.resolve_sources", lambda requested, settings: [adapter])
    result = await JobSearchPipeline(runtime(FakeLLM(), {"cities": []})).run()
    assert result["items"] == []
    assert "EMPTY_RESULT" in result["warnings"]
    assert result["sources"][0]["status"] == "EMPTY"


@pytest.mark.asyncio
async def test_unavailable_sources_are_reported(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.search.sources.unavailable import BossZhipinSource, LiepinSource, ZhaopinSource

    monkeypatch.setattr(
        "app.search.pipeline.resolve_sources",
        lambda requested, settings: [BossZhipinSource(), ZhaopinSource(), LiepinSource()],
    )
    result = await JobSearchPipeline(runtime(FakeLLM())).run()
    assert result["items"] == []
    assert {item["key"]: item["status"] for item in result["sources"]} == {
        "boss": "UNAVAILABLE",
        "zhaopin": "UNAVAILABLE",
        "liepin": "UNAVAILABLE",
    }
    assert all(item["requiresCredentials"] for item in result["sources"])
    assert "SOME_SOURCES_UNAVAILABLE" in result["warnings"]


@pytest.mark.asyncio
async def test_pipeline_stable_order(monkeypatch: pytest.MonkeyPatch) -> None:
    postings = [
        posting(title="Java 后端开发", source_url="https://careers.tencent.com/jobdesc.html?postId=1"),
        posting(title="数据开发", source_url="https://careers.tencent.com/jobdesc.html?postId=2"),
    ]
    adapter = FixedAdapter(postings)
    monkeypatch.setattr("app.search.pipeline.resolve_sources", lambda requested, settings: [adapter])
    first = await JobSearchPipeline(runtime(FakeLLM())).run()
    second = await JobSearchPipeline(runtime(FakeLLM())).run()
    assert [item["fingerprint"] for item in first["items"]] == [item["fingerprint"] for item in second["items"]]


@pytest.mark.asyncio
async def test_ssrf_guard(monkeypatch: pytest.MonkeyPatch) -> None:
    async def public(host: str) -> list[str]:
        return ["93.184.216.34"]

    async def private(host: str) -> list[str]:
        return ["127.0.0.1"]

    def fake_resolver(host: str) -> list[str]:
        return ["93.184.216.34"]

    assert await ensure_public_url(
        "https://careers.tencent.com/tencentcareer/api/post/Query",
        allowed_hosts={"careers.tencent.com"},
        resolver=fake_resolver,
    )
    with pytest.raises(UnsafeUrlError):
        await ensure_public_url("http://127.0.0.1/a", allowed_hosts={"127.0.0.1"}, resolver=fake_resolver)
    with pytest.raises(UnsafeUrlError):
        await ensure_public_url("https://example.com/a", allowed_hosts={"careers.tencent.com"}, resolver=fake_resolver)
    with pytest.raises(UnsafeUrlError):
        await ensure_public_url(
            "https://careers.tencent.com/a",
            allowed_hosts={"careers.tencent.com"},
            resolver=lambda host: ["10.0.0.8"],
        )

def test_official_supplement_requires_relevant_job_title_for_java_backend() -> None:
    assert _keyword_relevant(posting(title="Java 开发工程师"), "Java 后端")
    assert _keyword_relevant(posting(title="服务端研发工程师", jd_text="负责 Java 服务研发"), "Java 后端")
    assert not _keyword_relevant(posting(title="前端开发工程师", jd_text="对接 Java 后端团队"), "Java 后端")
    assert _keyword_relevant(posting(title="后端开发工程师", jd_text="熟练掌握Java、Go等至少一门语言"), "Java后端")
    assert not _keyword_relevant(posting(title="后端开发工程师", jd_text="使用JavaScript开发接口"), "Java后端")

def test_official_keyword_relevance_accepts_expanded_variants() -> None:
    assert _keyword_relevant(
        posting(title="智能体应用开发实习生", jd_text="负责智能体应用开发与评测"), "AI Agent 开发实习"
    )
    assert _keyword_relevant(
        posting(title="AI 应用开发实习生", jd_text="负责大模型应用开发"), "AI Agent 开发实习"
    )
    assert not _keyword_relevant(
        posting(title="研究助理", jd_text="参与 AI Agent 应用开发课题"), "AI Agent 开发实习"
    )
    assert not _keyword_relevant(
        posting(title="产品经理（AI 方向）", jd_text="负责 AI Agent 产品规划"), "AI Agent 开发实习"
    )


def test_extraction_prompt_is_markdown_backed() -> None:
    from app.search.sources.web import extraction_system_prompt

    prompt = extraction_system_prompt()
    assert "研究助理" in prompt
    assert "pageIndex" in prompt
    assert "软件研发岗位" in prompt
    assert "JSON" in prompt


def test_non_development_official_postings_are_rejected() -> None:
    assert not _keyword_relevant(
        posting(title="Account Executive, AI Sales", jd_text="Sell AI products to enterprise customers"),
        "AI Agent 开发实习",
    )
    assert not _keyword_relevant(
        posting(title="Abuse Investigator", jd_text="Investigate abuse cases with machine learning tooling"),
        "AI Agent 开发实习",
    )
    assert not _keyword_relevant(
        posting(title="法务专员", jd_text="负责合同审核，了解 AI Agent 产品"), "AI Agent 开发实习"
    )


def test_intern_keyword_does_not_match_internal() -> None:
    from app.search.models import infer_candidate_type

    assert infer_candidate_type("Backend Engineer", "work on internal systems")[0] == "EXPERIENCED"
    assert infer_candidate_type("Software Engineer Intern")[0] == "INTERNSHIP"
    assert infer_candidate_type("Backend Engineer", "international team")[0] == "EXPERIENCED"


def test_foreign_city_is_filtered_when_china_city_selected() -> None:
    from app.search.sources.base import filter_postings

    foreign = posting(city_name="San Francisco, CA", city_code=None)
    other_city = posting(city_name="大连市", city_code=None)
    missing = posting(city_name=None, city_code=None)
    filters = SearchFilters(cities=("440300",))
    assert filter_postings([foreign], filters) == []
    assert filter_postings([other_city], filters) == []
    kept = filter_postings([missing], filters)
    assert len(kept) == 1
    assert "CITY_UNKNOWN" in kept[0].warnings
