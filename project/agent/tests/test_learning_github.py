from __future__ import annotations

import pytest

from app.config import Settings
from datetime import datetime, timezone

from app.learning_github import (
    _cache, _is_practice_repository, _matched_skills,
    search_github_repositories, search_project_repositories,
)
from app.schemas import GitHubCandidate


def _repo(full_name: str, topic: str, language: str | None) -> dict:
    return {
        "full_name": full_name,
        "html_url": f"https://github.com/{full_name}",
        "description": f"A {topic} project",
        "stargazers_count": 1000,
        "language": language,
        "pushed_at": "2026-01-01T00:00:00Z",
        "topics": [topic],
        "archived": False,
        "disabled": False,
        "fork": False,
    }


@pytest.fixture
def mock_search(monkeypatch):
    _cache.clear()
    queries: list[str] = []
    responses = {}

    class Response:
        def __init__(self, items, status_code=200):
            self.items = items
            self.status_code = status_code

        def json(self):
            return {"items": self.items}

    class Client:
        def __init__(self, **_kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_):
            return None

        async def get(self, _url, *, params, headers):
            query = params["q"]
            queries.append(query)
            items = responses.get(query, [])
            return Response([], items) if isinstance(items, int) else Response(items)

    monkeypatch.setattr("app.learning_github.httpx.AsyncClient", Client)
    yield queries, responses
    _cache.clear()


@pytest.mark.asyncio
async def test_project_search_ranks_single_repository_skill_overlap_above_stars(mock_search):
    queries, responses = mock_search
    combined = _repo("example/service", "docker", "Python")
    combined["topics"] = ["docker", "fastapi"]
    combined["stargazers_count"] = 100
    docker = _repo("example/docker", "docker", None)
    docker["stargazers_count"] = 50000
    suffix = "stars:>=100 pushed:>2024-01-01"
    responses[f"topic:docker {suffix}"] = [docker, combined]
    responses[f"topic:fastapi {suffix}"] = [combined]
    responses[f"language:Go {suffix}"] = [_repo("example/go", "go", "Go")]
    responses[f"language:Python {suffix}"] = [_repo("example/cache", "redis", "Python"), combined]
    responses[f"topic:redis {suffix}"] = [_repo("example/cache", "redis", "Python")]
    settings = Settings(github_search_enabled=True)
    result = await search_project_repositories(
        settings,
        None,
        ["DOCKER", "FASTAPI", "GO", "PYTHON", "REDIS"],
    )

    assert result.status == "AVAILABLE"
    assert [item.full_name for item in result.candidates] == [
        "example/service", "example/cache", "example/docker", "example/go",
    ]
    assert result.candidates[0].matched_skill_codes == ["DOCKER", "FASTAPI", "PYTHON"]
    assert result.candidates[1].matched_skill_codes == ["PYTHON", "REDIS"]
    assert queries[0] == f"topic:docker topic:fastapi topic:go topic:python topic:redis {suffix}"
    assert len(queries) == 6
    assert all(" OR " not in query for query in queries)


@pytest.mark.asyncio
async def test_full_intersection_skips_broader_queries(mock_search):
    queries, responses = mock_search
    candidate = _repo("example/api", "fastapi", "Python")
    candidate["topics"] = ["fastapi", "docker"]
    query = "topic:docker topic:fastapi topic:python stars:>=100 pushed:>2024-01-01"
    responses[query] = [dict(candidate, full_name=f"example/api{i}", html_url=f"https://github.com/example/api{i}")
                        for i in range(3)]
    result = await search_project_repositories(Settings(), None, ["docker", "FASTAPI", "python", "DOCKER"])
    assert queries == [query]
    assert result.candidates[0].matched_skill_codes == ["DOCKER", "FASTAPI", "PYTHON"]


@pytest.mark.asyncio
async def test_one_full_intersection_still_searches_for_more_options(mock_search):
    queries, responses = mock_search
    candidate = _repo("example/api", "fastapi", "Python")
    responses["topic:fastapi topic:python stars:>=100 pushed:>2024-01-01"] = [candidate]
    responses["language:Python stars:>=100 pushed:>2024-01-01"] = [_repo("example/python", "python", "Python")]
    result = await search_project_repositories(Settings(), None, ["FASTAPI", "PYTHON"])
    assert len(queries) == 3
    assert len(result.candidates) == 2


@pytest.mark.parametrize("status", [403, 429, 500])
@pytest.mark.asyncio
async def test_project_search_unavailable_stops_and_can_degrade(mock_search, status):
    queries, responses = mock_search
    query = "topic:docker topic:redis stars:>=100 pushed:>2024-01-01"
    responses[query] = status
    result = await search_project_repositories(Settings(), None, ["DOCKER", "REDIS"])
    assert result.status == "UNAVAILABLE"
    assert result.candidates == []
    assert queries == [query]


@pytest.mark.asyncio
async def test_project_search_does_not_fabricate_overlap_when_empty_or_disabled(mock_search):
    queries, _ = mock_search
    result = await search_project_repositories(Settings(github_search_enabled=False), None, ["DOCKER", "REDIS"])
    assert result.status == "DISABLED"
    assert queries == []
    result = await search_project_repositories(Settings(), None, ["DOCKER", "REDIS"])
    assert result.status == "UNAVAILABLE"
    assert result.candidates == []


@pytest.mark.parametrize(("name", "description", "language", "topics", "skills", "expected"), [
    ("example/api", "A web service", "Python", [], ["PYTHON", "FASTAPI"], ["PYTHON"]),
    ("example/api", "A web service", "Java", [], ["JAVA", "SPRING_BOOT"], ["JAVA"]),
    ("example/ui", "A web page", "JavaScript", [], ["JAVASCRIPT", "REACT", "JAVA"], ["JAVASCRIPT"]),
    ("example/django", "A Django backend", "Python", [], ["GO", "PYTHON"], ["PYTHON"]),
    ("docker/service", "A backend", "Python", [], ["DOCKER", "PYTHON"], ["PYTHON"]),
    ("example/api", "FastAPI service with Docker and Redis", "Python", [],
     ["DOCKER", "FASTAPI", "GO", "PYTHON", "REDIS"], ["DOCKER", "FASTAPI", "PYTHON", "REDIS"]),
])
def test_skill_coverage_requires_actual_metadata_evidence(name, description, language, topics, skills, expected):
    candidate = GitHubCandidate(fullName=name, htmlUrl=f"https://github.com/{name}",
                                description=description, language=language, topics=topics,
                                verifiedAt=datetime.now(timezone.utc))
    assert _matched_skills(candidate, skills) == expected


@pytest.mark.parametrize(("name", "description", "topics", "expected"), [
    ("Snailclimb/JavaGuide", "Java 面试 & 后端通用面试指南，覆盖数据库、分布式", ["java", "mysql"], False),
    ("example/backend-notes", "后端面试指南", ["redis"], False),
    ("example/awesome-fastapi", "FastAPI projects", ["fastapi"], False),
    ("example/resources", "A curated list of Redis resources", ["redis"], False),
    ("example/redis-cheatsheet", "Redis commands", ["redis"], False),
    ("example/backend", "Java resources", ["awesome-list", "java"], False),
    ("fastapi/full-stack-fastapi-template", "Full stack FastAPI template", ["fastapi", "docker"], True),
    ("example/interview-platform", "Runnable mock interview application", ["interview", "java"], True),
    ("example/tutorial-app", "A tutorial application with Java and Redis", ["java", "redis"], True),
    ("macrozheng/mall", "基于 Spring Boot 的电商系统", ["java", "mysql"], True),
])
def test_practice_repository_excludes_resources_and_preserves_apps(name, description, topics, expected):
    candidate = GitHubCandidate(fullName=name, htmlUrl=f"https://github.com/{name}",
                                description=description, topics=topics, verifiedAt=datetime.now(timezone.utc))
    assert _is_practice_repository(candidate) is expected


@pytest.mark.asyncio
async def test_resource_only_intersection_expands_search_and_keeps_training_resources(mock_search):
    queries, responses = mock_search
    guide = _repo("Snailclimb/JavaGuide", "java", "JavaScript")
    guide.update(description="Java 面试 & 后端通用面试指南", topics=["java", "mysql", "redis"])
    suffix = "stars:>=100 pushed:>2024-01-01"
    responses[f"topic:java topic:mysql topic:redis {suffix}"] = [
        dict(guide, full_name=f"example/interview-guide{i}", html_url=f"https://github.com/example/interview-guide{i}")
        for i in range(3)
    ]
    app = _repo("example/store", "mysql", "Java")
    app["topics"] = ["java", "mysql", "redis"]
    responses[f"language:Java {suffix}"] = [guide, app]
    responses[f"topic:redis {suffix}"] = [guide]
    result = await search_project_repositories(Settings(), None, ["JAVA", "MYSQL", "REDIS"])
    assert len(queries) == 4
    assert [item.full_name for item in result.candidates] == ["example/store"]
    responses[f"language:Java topic:java {suffix}"] = [guide]
    training = await search_github_repositories(Settings(), "JAVA_BACKEND", ["JAVA"])
    assert [item.full_name for item in training.candidates] == ["Snailclimb/JavaGuide"]


@pytest.mark.asyncio
async def test_only_resource_repositories_returns_unavailable_for_ai_fallback(mock_search):
    _, responses = mock_search
    guide = _repo("Snailclimb/JavaGuide", "java", "Java")
    responses["language:Java stars:>=100 pushed:>2024-01-01"] = [guide]
    result = await search_project_repositories(Settings(), None, ["JAVA"])
    assert result.status == "UNAVAILABLE"
    assert result.candidates == []
