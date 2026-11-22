from __future__ import annotations

import json
from types import SimpleNamespace
from uuid import uuid4

import httpx
import pytest

from app.config import Settings
from app.errors import HandlerValidationError
from app.handlers import ExecutionContext, ProjectRecommendHandler
from app.llm import LLMProviderError, LLMRequest
from app.project_sources import (
    OSI_SPDX_LICENSES,
    RepoHit,
    RepositorySourcesUnavailable,
    normalize_license,
    search_project_repositories,
)
from app.schemas import (
    ProjectRecommendModelOutput,
    ProjectRecommendPayload,
    ProjectRecommendSuggestion,
)
from app.skills import get_skill
from tests.factories import project_recommend_envelope

SKILLS = ["JAVA", "SPRING_BOOT", "REDIS", "MYSQL"]


def _settings(**overrides) -> Settings:
    values = dict(
        app_env="test",
        llm_provider="fake",
        github_token=None,
        gitee_token="gitee-test",
        gitcode_token="gitcode-test",
        repository_search_timeout_seconds=2.0,
        repository_search_per_page=10,
        repository_search_max_candidates=6,
        repository_search_trust_env=False,
    )
    values.update(overrides)
    return Settings(**values)


def _json(payload) -> httpx.Response:
    return httpx.Response(200, json=payload)


GITHUB_ITEMS = [
    {
        "full_name": "redis/redis",
        "name": "redis",
        "html_url": "https://github.com/redis/redis",
        "description": "Redis is an in-memory data store with a Java client ecosystem.",
        "stargazers_count": 60000,
        "default_branch": "unstable",
        "license": {"spdx_id": "BSD-3-Clause"},
        "topics": ["redis", "java"],
        "fork": False,
        "archived": False,
        "private": False,
    },
    {
        "full_name": "spring-projects/spring-boot",
        "name": "spring-boot",
        "html_url": "https://github.com/spring-projects/spring-boot",
        "description": "Spring Boot makes it easy to create production-grade Spring applications.",
        "stargazers_count": 70000,
        "default_branch": "main",
        "license": {"spdx_id": "Apache-2.0"},
        "topics": ["spring-boot"],
        "mirror_url": "https://gitee.com/spring-projects/spring-boot.git",
        "fork": False,
        "archived": False,
        "private": False,
    },
    {
        "full_name": "owner/private-repo",
        "name": "private-repo",
        "html_url": "https://github.com/owner/private-repo",
        "description": "A private Java repo",
        "stargazers_count": 90000,
        "license": {"spdx_id": "MIT"},
        "private": True,
    },
    {
        "full_name": "owner/no-description",
        "name": "no-description",
        "html_url": "https://github.com/owner/no-description",
        "description": "",
        "stargazers_count": 80000,
        "license": {"spdx_id": "MIT"},
        "private": False,
    },
    {
        "full_name": "owner/non-osi",
        "name": "non-osi",
        "html_url": "https://github.com/owner/non-osi",
        "description": "A Java sample with a non-commercial license",
        "stargazers_count": 50000,
        "license": {"spdx_id": "CC-BY-NC-4.0"},
        "private": False,
    },
    {
        "full_name": "owner/no-license",
        "name": "no-license",
        "html_url": "https://github.com/owner/no-license",
        "description": "A Java utility without any license metadata",
        "stargazers_count": 40000,
        "license": None,
        "private": False,
    },
]

GITEE_ITEMS = [
    {
        "full_name": "dromara/sa-token",
        "name": "sa-token",
        "html_url": "https://gitee.com/dromara/sa-token",
        "description": "轻量级 Java 权限认证框架，提供登录、会话管理和权限校验。",
        "stargazers_count": 20000,
        "default_branch": "dev",
        "license": "Apache License 2.0",
        "readme_url": "https://gitee.com/dromara/sa-token/blob/dev/README.md",
        "fork": False,
        "private": False,
        "public": True,
    },
    {
        "full_name": "spring-projects/spring-boot",
        "name": "spring-boot",
        "html_url": "https://gitee.com/spring-projects/spring-boot",
        "description": "Spring Boot 镜像仓库",
        "stargazers_count": 120,
        "default_branch": "main",
        "license": "Apache License 2.0",
        "fork": False,
        "private": False,
        "public": True,
    },
    {
        "full_name": "owner/gitee-fork",
        "name": "gitee-fork",
        "html_url": "https://gitee.com/owner/gitee-fork",
        "description": "A Java fork",
        "stargazers_count": 50000,
        "license": "MIT",
        "fork": True,
        "private": False,
        "public": True,
    },
]

GITCODE_ITEMS = [
    {
        "full_name": "dromara/sa-token",
        "name": "sa-token",
        "web_url": "https://gitcode.com/dromara/sa-token",
        "description": "轻量级 Java 权限认证框架（GitCode 镜像）。",
        "stargazers_count": 3000,
        "license": None,
        "readme_url": None,
        "fork": False,
        "private": False,
        "public": True,
        "mirror_url": "https://gitee.com/dromara/sa-token",
    },
    {
        "full_name": "owner/gitcode-mit",
        "name": "gitcode-mit",
        "web_url": "https://gitcode.com/owner/gitcode-mit",
        "description": "A Java command line toolkit for learning.",
        "stargazers_count": 500,
        "license": None,
        "readme_url": None,
        "fork": False,
        "private": False,
        "public": True,
    },
    {
        "full_name": "owner/gitcode-bad-license",
        "name": "gitcode-bad-license",
        "web_url": "https://gitcode.com/owner/gitcode-bad-license",
        "description": "A Java demo with a custom license.",
        "stargazers_count": 700,
        "license": None,
        "readme_url": None,
        "fork": False,
        "private": False,
        "public": True,
    },
    {
        "full_name": "owner/gitcode-no-description",
        "name": "gitcode-no-description",
        "web_url": "https://gitcode.com/owner/gitcode-no-description",
        "description": "",
        "stargazers_count": 900,
        "license": None,
        "fork": False,
        "private": False,
        "public": True,
    },
]


def _handler(request: httpx.Request) -> httpx.Response:
    path = request.url.path
    if path == "/search/repositories" and request.url.host == "api.github.com":
        return _json({"items": GITHUB_ITEMS})
    if path.startswith("/repos/") and request.url.host == "api.github.com":
        if path.endswith("/readme"):
            return _json({"html_url": "https://github.com/redis/redis/blob/unstable/README.md"})
        if path.endswith("/license"):
            return httpx.Response(404, json={"message": "Not Found"})
        return httpx.Response(404)
    if path == "/api/v5/search/repositories" and request.url.host == "gitee.com":
        return _json(GITEE_ITEMS)
    if path == "/api/v5/repos/dromara/sa-token" and request.url.host == "gitee.com":
        return _json({
            "license": "Apache-2.0",
            "readme_url": "https://gitee.com/dromara/sa-token/blob/dev/README.md",
        })
    if path == "/api/v5/search/repositories" and request.url.host == "api.gitcode.com":
        return _json(GITCODE_ITEMS)
    if path == "/api/v5/repos/owner/gitcode-mit" and request.url.host == "api.gitcode.com":
        return _json({"license": "MIT License", "readme_url": None})
    if path == "/api/v5/repos/owner/gitcode-bad-license" and request.url.host == "api.gitcode.com":
        return _json({"license": "CC-BY-NC-4.0", "readme_url": None})
    if path == "/api/v5/repos/owner/gitcode-no-description" and request.url.host == "api.gitcode.com":
        return httpx.Response(404)
    return httpx.Response(404)


async def _search(settings: Settings | None = None) -> tuple[httpx.AsyncClient, object]:
    client = httpx.AsyncClient(transport=httpx.MockTransport(_handler))
    result = await search_project_repositories(
        settings or _settings(), SKILLS, None, ["GITHUB", "GITEE", "GITCODE"], client=client,
    )
    return client, result


def test_license_normalization_only_accepts_osi_licenses() -> None:
    assert normalize_license({"spdx_id": "MIT"}) == "MIT"
    assert normalize_license("Apache_License_v2.0") == "Apache-2.0"
    assert normalize_license("GNU General Public License v3.0") == "GPL-3.0"
    assert normalize_license("Apache License 2.0") == "Apache-2.0"
    assert normalize_license("CC-BY-NC-4.0") is None
    assert normalize_license("NOASSERTION") is None
    assert "MIT" in OSI_SPDX_LICENSES


@pytest.mark.asyncio
async def test_three_platform_search_filters_licenses_and_ranks_by_skill_coverage() -> None:
    client, result = await _search()
    await client.aclose()

    names = [hit.name for hit in result.candidates]
    assert names == ["redis", "spring-boot", "sa-token", "gitcode-mit"]
    assert result.candidates[0].matched_skill_codes == ["JAVA", "REDIS"]
    assert result.candidates[0].license == "BSD-3-Clause"
    assert result.candidates[0].readme_url == "https://github.com/redis/redis/blob/unstable/README.md"
    assert all(hit.name not in {"private-repo", "no-description", "non-osi", "no-license",
                                "gitee-fork", "gitcode-no-description", "gitcode-bad-license"}
               for hit in result.candidates)
    primary = result.candidates[1]
    assert primary.platform == "GITHUB" and primary.path == "spring-projects/spring-boot"
    assert [item.platform for item in primary.alternate_sources] == ["GITEE"]
    assert primary.alternate_sources[0].license == "Apache-2.0"
    sa_token = result.candidates[2]
    assert sa_token.platform == "GITEE"
    assert sa_token.alternate_sources == []
    assert sa_token.license == "Apache-2.0"
    assert result.candidates[3].readme_url is None
    assert [run["status"] for run in result.source_runs] == ["SUCCEEDED"] * 3


@pytest.mark.asyncio
async def test_same_path_without_mirror_metadata_keeps_independent_candidates() -> None:
    def unrelated(request: httpx.Request) -> httpx.Response:
        if request.url.host == "api.github.com" and request.url.path == "/search/repositories":
            return _json({"items": [dict(GITHUB_ITEMS[1], mirror_url=None)]})
        if request.url.host == "gitee.com" and request.url.path.endswith("/search/repositories"):
            return _json([dict(GITEE_ITEMS[1], description="Unrelated Java tool.", license="MIT")])
        return _handler(request)

    async with httpx.AsyncClient(transport=httpx.MockTransport(unrelated)) as client:
        result = await search_project_repositories(_settings(), ["JAVA"], platforms=["GITHUB", "GITEE"], client=client)

    assert {(hit.platform, hit.license) for hit in result.candidates} == {("GITHUB", "Apache-2.0"), ("GITEE", "MIT")}
    assert all(not hit.alternate_sources for hit in result.candidates)


@pytest.mark.asyncio
async def test_gitcode_accepts_nested_public_repository_and_rejects_nonpublic_flags() -> None:
    public_repo = {
        "full_name": "gh_mirrors/al/allure2", "name": "allure2", "description": "Java test reporting",
        "web_url": "https://gitcode.com/gh_mirrors/al/allure2", "license": "Apache-2.0",
        "readme_url": "https://gitcode.com/gh_mirrors/al/allure2/blob/main/README.md",
        "public": 1, "private": 0, "internal": 0,
    }
    variants = [{"private": 1}, {"public": 0}, {"public": 2}, {"internal": 1}, {"public": None}]
    rejected = [dict(public_repo, full_name=f"owner/repo-{i}", web_url=f"https://gitcode.com/owner/repo-{i}", **flags)
                for i, flags in enumerate(variants)]
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: _json([public_repo, *rejected]))) as client:
        result = await search_project_repositories(_settings(), ["JAVA"], platforms=["GITCODE"], client=client)

    assert [hit.path for hit in result.candidates] == [public_repo["full_name"]]
    assert result.candidates[0].readme_url == public_repo["readme_url"]


@pytest.mark.parametrize("failure", ["unavailable", "html", "unexpected_json"])
@pytest.mark.asyncio
async def test_single_source_failure_keeps_other_platform_results(failure: str) -> None:
    def degraded(request: httpx.Request) -> httpx.Response:
        if request.url.host == "gitee.com":
            if failure == "unavailable":
                return httpx.Response(503)
            if failure == "html":
                return httpx.Response(200, text="<html>gateway error</html>")
            return _json({"message": "gateway error"})
        return _handler(request)

    client = httpx.AsyncClient(transport=httpx.MockTransport(degraded))
    try:
        result = await search_project_repositories(
            _settings(), SKILLS, None, ["GITHUB", "GITEE", "GITCODE"], client=client,
        )
    finally:
        await client.aclose()

    runs = {run["platform"]: run for run in result.source_runs}
    assert runs["GITEE"]["status"] == "FAILED"
    assert "gitee" in runs["GITEE"]["reason"].lower()
    assert result.candidates
    assert all(hit.platform != "GITEE" for hit in result.candidates)


@pytest.mark.asyncio
async def test_missing_gitee_and_gitcode_tokens_are_reported_as_unavailable() -> None:
    client = httpx.AsyncClient(transport=httpx.MockTransport(_handler))
    try:
        with pytest.raises(RepositorySourcesUnavailable) as caught:
            await search_project_repositories(
                _settings(gitee_token=None, gitcode_token=None), SKILLS, None, ["GITEE", "GITCODE"], client=client,
            )
    finally:
        await client.aclose()
    assert "Gitee 未配置服务端搜索令牌" in str(caught.value)
    assert "GitCode 未配置服务端搜索令牌" in str(caught.value)


@pytest.mark.asyncio
async def test_all_sources_failure_raises_retryable_unavailable() -> None:
    client = httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(503)))
    try:
        with pytest.raises(RepositorySourcesUnavailable):
            await search_project_repositories(
                _settings(), SKILLS, None, ["GITHUB", "GITEE", "GITCODE"], client=client,
            )
    finally:
        await client.aclose()


@pytest.mark.asyncio
async def test_zero_qualified_repositories_returns_ready_with_empty_items() -> None:
    def no_license(request: httpx.Request) -> httpx.Response:
        if "search" in request.url.path:
            items = [{"full_name": "owner/demo", "name": "demo",
                      "html_url": "https://github.com/owner/demo",
                      "web_url": "https://gitcode.com/owner/demo",
                      "description": "Java demo", "stargazers_count": 10,
                      "license": None, "public": True, "private": False, "fork": False}]
            return _json({"items": items})
        return httpx.Response(404)

    client = httpx.AsyncClient(transport=httpx.MockTransport(no_license))
    try:
        result = await search_project_repositories(
            _settings(), SKILLS, None, ["GITHUB", "GITEE", "GITCODE"], client=client,
        )
    finally:
        await client.aclose()
    assert result.candidates == []
    assert [run["status"] for run in result.source_runs] == ["SUCCEEDED"] * 3
    assert result.rejected["LICENSE_NOT_CONFIRMED"] >= 1


def _hit() -> RepoHit:
    hit = RepoHit(
        platform="GITHUB",
        path="owner/demo",
        name="demo",
        url="https://github.com/owner/demo",
        description="A real Java repository used for learning.",
        stars=120,
        default_branch="main",
    )
    hit.license = "MIT"
    hit.readme_url = "https://github.com/owner/demo/blob/main/README.md"
    hit.matched_skill_codes = ["JAVA"]
    return hit


def _prepared() -> dict:
    hit = _hit()
    return {
        "candidates": [hit],
        "repository_candidates": [ProjectRecommendHandler._candidate_input(hit)],
        "source_runs": [{"platform": "GITHUB", "status": "SUCCEEDED", "reason": None}],
    }


def _payload(**overrides) -> ProjectRecommendPayload:
    data = project_recommend_envelope(uuid4())["payload"]
    data.update(allowedSkillCodes=["JAVA", "REDIS"], difficulty="BEGINNER", maxDurationWeeks=4)
    data.update(overrides)
    return ProjectRecommendPayload.model_validate(data)


def _suggestion(**overrides) -> ProjectRecommendSuggestion:
    data = {
        "sourceUrl": "https://github.com/owner/demo",
        "skillCodes": ["JAVA"],
        "difficulty": "BEGINNER",
        "estimatedWeeks": 2,
        "reason": "仓库使用 Java，适合练习基础后端能力。",
        "gettingStarted": ["阅读 README", "运行示例"],
        "deliverables": ["运行说明", "小改造与自测记录"],
    }
    data.update(overrides)
    return ProjectRecommendSuggestion.model_validate(data)


def test_recommend_validation_rejects_fabricated_repository_and_bad_budget() -> None:
    handler = ProjectRecommendHandler()
    payload = _payload()
    ctx = SimpleNamespace(payload=payload)

    result = handler.validate_business(ctx, ProjectRecommendModelOutput(items=[_suggestion()]), _prepared(), {})
    assert result.items[0].title == "demo"
    assert result.items[0].summary == "A real Java repository used for learning."
    assert result.items[0].primary_source.url == "https://github.com/owner/demo"
    assert result.items[0].primary_source.license == "MIT"

    with pytest.raises(HandlerValidationError, match="真实仓库候选"):
        handler.validate_business(
            ctx,
            ProjectRecommendModelOutput(items=[_suggestion(sourceUrl="https://github.com/evil/fake")]),
            _prepared(), {},
        )
    with pytest.raises(HandlerValidationError, match="allowedSkillCodes"):
        handler.validate_business(
            ctx, ProjectRecommendModelOutput(items=[_suggestion(skillCodes=["PYTHON"])]), _prepared(), {},
        )
    with pytest.raises(HandlerValidationError, match="maxDurationWeeks"):
        handler.validate_business(
            ctx, ProjectRecommendModelOutput(items=[_suggestion(estimatedWeeks=5)]), _prepared(), {},
        )
    with pytest.raises(HandlerValidationError, match="difficulty"):
        handler.validate_business(
            ctx, ProjectRecommendModelOutput(items=[_suggestion(difficulty="ADVANCED")]), _prepared(), {},
        )


def test_difficulty_unlimited_allows_mixed_levels() -> None:
    handler = ProjectRecommendHandler()
    ctx = SimpleNamespace(payload=_payload(difficulty=None))
    result = handler.validate_business(
        ctx, ProjectRecommendModelOutput(items=[_suggestion(difficulty="ADVANCED")]), _prepared(), {},
    )
    assert result.items[0].difficulty == "ADVANCED"


@pytest.mark.asyncio
async def test_model_failure_falls_back_to_repository_hints_only() -> None:
    class BlockedProvider:
        name = "deepseek"
        calls = 0

        async def complete(self, request: LLMRequest):
            self.calls += 1
            raise LLMProviderError("model rejected request", retryable=False, http_status=402)

    async def emit(*_args):
        return None

    provider = BlockedProvider()
    client = httpx.AsyncClient(transport=httpx.MockTransport(_handler))
    try:
        handler = ProjectRecommendHandler()
        ctx = ExecutionContext(
            run=None,
            payload=_payload(),
            settings=_settings(),
            llm=provider,
            files=None,
            emit_stage=emit,
            repository_client=client,
        )
        result, meta = await handler.execute(ctx)
    finally:
        await client.aclose()

    assert result["mode"] == "REPOSITORY"
    assert meta["fallbackReason"] == "MODEL_HTTP_402"
    assert result["items"][0]["title"] == "redis"
    assert result["items"][0]["primarySource"]["url"] == "https://github.com/redis/redis"
    assert all(item["title"] != "Java 投递进度看板" for item in result["items"])


@pytest.mark.asyncio
@pytest.mark.parametrize("round_number", [0, 1])
async def test_discovery_keeps_all_candidates_when_model_unavailable(monkeypatch, round_number) -> None:
    class BlockedProvider:
        calls = 0

        async def complete(self, request: LLMRequest):
            assert len(request.context["repository_candidates"]) == 6
            self.calls += 1
            raise LLMProviderError("model rejected request", retryable=False, http_status=402)

    async def emit(*_args):
        pass

    prepared = _prepared()
    hits = []
    for index in range(13):
        hit = _hit()
        hit.path = f"owner/application-{index}"
        hit.url = f"https://github.com/{hit.path}"
        hit.stack_evidence = [{"keyword": "springboot", "status": "VERIFIED", "path": "pom.xml",
                               "url": f"{hit.url}/blob/main/pom.xml"}]
        hits.append(hit)
    prepared.update(candidates=hits, search_cursor={"GITHUB:0": 2},
                    repository_candidates=[ProjectRecommendHandler._candidate_input(hit) for hit in hits[:6]])
    handler = ProjectRecommendHandler()

    async def prepare(_ctx):
        return prepared

    monkeypatch.setattr(handler, "prepare", prepare)
    provider = BlockedProvider()
    ctx = ExecutionContext(
        run=None, payload=_payload(discoveryRound=round_number, techStackKeywords=["springboot"]),
        settings=_settings(), llm=provider, files=None, emit_stage=emit,
    )
    result, _ = await handler.execute(ctx)
    assert provider.calls == (1 if round_number == 0 else 0)
    assert len(result["items"]) == len(hits)
    assert result["items"][-1]["stackEvidence"][0]["status"] == "VERIFIED"
    assert result["searchCursor"] == {"GITHUB:0": 2}


@pytest.mark.asyncio
async def test_zero_candidates_skips_model_and_returns_empty_ready() -> None:
    class CountingProvider:
        calls = 0

        async def complete(self, request: LLMRequest):
            self.calls += 1
            return None

    async def emit(*_args):
        return None

    provider = CountingProvider()
    client = httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(200, json={"items": []})))
    try:
        handler = ProjectRecommendHandler()
        ctx = ExecutionContext(
            run=None,
            payload=_payload(),
            settings=_settings(),
            llm=provider,
            files=None,
            emit_stage=emit,
            repository_client=client,
        )
        result, meta = await handler.execute(ctx)
    finally:
        await client.aclose()

    assert result["items"] == []
    assert result["mode"] == "REPOSITORY"
    assert provider.calls == 0
    assert meta["candidateCount"] == 0


def test_prompt_requires_real_repository_candidates() -> None:
    skill = get_skill("PROJECT_RECOMMEND")
    assert skill.version == "project-recommend-v2"
    assert "repositoryCandidates" in skill.system_prompt
    assert "不得声称用户已经做过" in skill.system_prompt
    hint = json.loads(skill.schema_hint)
    assert hint["items"][0]["sourceUrl"].startswith("repositoryCandidates")


TECH_GITHUB_ITEMS = [
    {
        "full_name": "demo/langgraph-fastapi-agent",
        "name": "langgraph-fastapi-agent",
        "html_url": "https://github.com/demo/langgraph-fastapi-agent",
        "description": "LangGraph agent service exposed with FastAPI.",
        "stargazers_count": 900,
        "default_branch": "main",
        "license": {"spdx_id": "MIT"},
        "topics": ["langgraph", "fastapi"],
        "language": "Python",
        "fork": False,
        "archived": False,
        "private": False,
    },
    {
        "full_name": "demo/langgraph-only",
        "name": "langgraph-only",
        "html_url": "https://github.com/demo/langgraph-only",
        "description": "LangGraph multi-agent workflow samples.",
        "stargazers_count": 800,
        "default_branch": "main",
        "license": {"spdx_id": "MIT"},
        "topics": ["langgraph"],
        "language": "Python",
        "fork": False,
        "archived": False,
        "private": False,
    },
    {
        "full_name": "demo/fastapi-only",
        "name": "fastapi-only",
        "html_url": "https://github.com/demo/fastapi-only",
        "description": "FastAPI backend service template.",
        "stargazers_count": 700,
        "default_branch": "main",
        "license": {"spdx_id": "MIT"},
        "topics": ["fastapi"],
        "language": "Python",
        "fork": False,
        "archived": False,
        "private": False,
    },
    {
        "full_name": "demo/javascript-toolkit",
        "name": "javascript-toolkit",
        "html_url": "https://github.com/demo/javascript-toolkit",
        "description": "A JavaScript utility toolkit for browsers.",
        "stargazers_count": 600,
        "default_branch": "main",
        "license": {"spdx_id": "MIT"},
        "topics": ["javascript"],
        "language": "JavaScript",
        "fork": False,
        "archived": False,
        "private": False,
    },
    {
        "full_name": "demo/spring-symbols",
        "name": "spring-symbols",
        "html_url": "https://github.com/demo/spring-symbols",
        "description": "Spring Boot service with C++ and C# client bindings.",
        "stargazers_count": 500,
        "default_branch": "main",
        "license": {"spdx_id": "MIT"},
        "topics": ["spring-boot", "c++", "c#"],
        "language": "Java",
        "fork": False,
        "archived": False,
        "private": False,
    },
]


def _tech_handler(search_queries: list[str]):
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.host == "api.github.com" and request.url.path == "/search/repositories":
            search_queries.append(str(request.url.params.get("q", "")))
            return _json({"items": TECH_GITHUB_ITEMS})
        if request.url.host == "api.github.com" and request.url.path.startswith("/repos/"):
            repo = request.url.path[len("/repos/"):]
            if repo.endswith("/readme"):
                return _json({"html_url": f"https://github.com/{repo[:-len('/readme')]}/blob/main/README.md"})
            return httpx.Response(404)
        return httpx.Response(404)

    return handler


async def _tech_search(tech_stack_keywords: list[str] | None, query: str | None = None,
                       skill_codes: list[str] | None = None) -> tuple[object, list[str]]:
    search_queries: list[str] = []
    async with httpx.AsyncClient(transport=httpx.MockTransport(_tech_handler(search_queries))) as client:
        result = await search_project_repositories(
            _settings(), skill_codes or ["JAVA"], query, ["GITHUB"],
            tech_stack_keywords=tech_stack_keywords, client=client,
        )
    return result, search_queries


@pytest.mark.asyncio
async def test_tech_stack_keywords_require_every_match() -> None:
    result, queries = await _tech_search(["LangGraph", "FastAPI"], "agent")

    assert [hit.name for hit in result.candidates] == ["langgraph-fastapi-agent"]
    assert result.terms == ["agent LangGraph FastAPI", "LangGraph FastAPI", "java"]
    assert len(queries) == 3
    assert queries[0] == "agent LangGraph FastAPI is:public"


@pytest.mark.asyncio
async def test_tech_stack_keyword_java_does_not_match_javascript() -> None:
    result, _ = await _tech_search(["Java"])

    names = [hit.name for hit in result.candidates]
    assert "javascript-toolkit" not in names
    assert names == ["spring-symbols"]


@pytest.mark.asyncio
@pytest.mark.parametrize("keywords", [["Spring Boot", "C++", "C#"], ["spring-boot", "c++", "c#"]])
async def test_tech_stack_keyword_hyphen_and_symbols_match(keywords: list[str]) -> None:
    result, _ = await _tech_search(keywords)

    assert [hit.name for hit in result.candidates] == ["spring-symbols"]


@pytest.mark.asyncio
async def test_tech_stack_keywords_dedupe_and_empty_filter_keeps_existing_flow() -> None:
    deduped, _ = await _tech_search([" LangGraph ", "langgraph", "FastAPI"])
    assert [hit.name for hit in deduped.candidates] == ["langgraph-fastapi-agent"]

    unfiltered, _ = await _tech_search([])
    assert len(unfiltered.candidates) == len(TECH_GITHUB_ITEMS)
    assert "TECH_STACK_MISMATCH" not in unfiltered.rejected


@pytest.mark.asyncio
async def test_tech_stack_keywords_zero_hit_returns_empty_candidates() -> None:
    result, _ = await _tech_search(["Rust"])

    assert result.candidates == []
    assert [run["status"] for run in result.source_runs] == ["SUCCEEDED"]
    assert result.rejected["TECH_STACK_MISMATCH"] == len(TECH_GITHUB_ITEMS)


@pytest.mark.asyncio
async def test_model_failure_fallback_uses_filtered_tech_stack_candidates() -> None:
    class BlockedProvider:
        name = "deepseek"

        async def complete(self, request: LLMRequest):
            raise LLMProviderError("model rejected request", retryable=False, http_status=402)

    async def emit(*_args):
        return None

    search_queries: list[str] = []
    async with httpx.AsyncClient(transport=httpx.MockTransport(_tech_handler(search_queries))) as client:
        handler = ProjectRecommendHandler()
        ctx = ExecutionContext(
            run=None,
            payload=_payload(techStackKeywords=["LangGraph", "FastAPI"]),
            settings=_settings(),
            llm=BlockedProvider(),
            files=None,
            emit_stage=emit,
            repository_client=client,
        )
        result, meta = await handler.execute(ctx)

    assert meta["fallbackReason"] == "MODEL_HTTP_402"
    assert [item["title"] for item in result["items"]] == ["langgraph-fastapi-agent"]


def test_payload_normalizes_tech_stack_keywords() -> None:
    payload = _payload(techStackKeywords=[" LangGraph ", "langgraph", "FastAPI"])
    assert payload.tech_stack_keywords == ["LangGraph", "FastAPI"]


@pytest.mark.parametrize("value", [["   "], ["x" * 65], ["a", "b", "c", "d", "e", "f"]])
def test_payload_rejects_invalid_tech_stack_keywords(value: list[str]) -> None:
    from pydantic import ValidationError

    with pytest.raises(ValidationError):
        _payload(techStackKeywords=value)
