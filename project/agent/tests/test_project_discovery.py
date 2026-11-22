import base64

import httpx
import pytest

from app.config import Settings
from app.project_discovery import _example, _readme_claim, discover
from app.project_sources import _github_hit


def _repository(path, description="预约管理系统", stars=1, mirror=None):
    return {
        "name": path.split("/")[-1], "full_name": path, "html_url": f"https://github.com/{path}",
        "description": description, "private": False, "default_branch": "main",
        "stargazers_count": stars, "license": {"spdx_id": "MIT"}, "mirror_url": mirror,
    }


def _file(path, text):
    return httpx.Response(200, json={
        "type": "file", "encoding": "base64", "path": path,
        "content": base64.b64encode(text.encode()).decode(),
    })


POM = """<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies><dependency>
<groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId>
</dependency><dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId>
</dependency></dependencies></project>"""


@pytest.mark.asyncio
@pytest.mark.parametrize("keyword", ["springboot", "Spring Boot", "spring-boot"])
async def test_discovery_verifies_dependencies_and_loads_next_page(keyword):
    calls = []
    config_requests = []

    def transport(request):
        path = request.url.path
        if path == "/search/repositories":
            calls.append(request.url.params)
            if request.url.params["page"] == "2":
                return httpx.Response(200, json={"items": [
                    _repository("team/appointments"), _repository("another/library", "Library system"),
                    _repository("mirror/appointments", mirror="https://github.com/team/appointments"),
                ]})
            return httpx.Response(200, headers={"Link": '<https://api.github.com/search/repositories?page=2>; rel="next"'},
                                  json={"items": [
                                      _repository("team/shop", "商城", 50_000),
                                      _repository("team/appointments"),
                                      _repository("team/comparison"),
                                      _repository("spring-projects/spring-boot"),
                                      _repository("team/minimal-demo", "Minimal demo"),
                                      _repository("team/springboot-learning-example"),
                                      _repository("team/tech-pdai-spring-demos"),
                                  ]})
        if path.endswith("/readme"):
            readmes = {
                "shop": "## 技术栈\n- Spring Boot\n- PostgreSQL",
                "comparison": "Spring Boot and PostgreSQL are alternatives compared in this article.",
            }
            name = path.split("/")[-2]
            return _file("README.md", readmes[name]) if name in readmes else httpx.Response(404)
        config_requests.append(path)
        if path in {"/repos/team/appointments/contents/pom.xml", "/repos/another/library/contents/pom.xml"}:
            return _file("pom.xml", POM)
        return httpx.Response(404)

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        settings = Settings(github_token="test")
        first, cursor, seen = await discover(
            settings, ["JAVA"], "预约", ["GITHUB"], [keyword, "postgresql"], {}, [], client,
        )
        assert len(calls) == 3
        assert any("in:readme" in call["q"] for call in calls)
        assert any(call.get("sort") == "updated" for call in calls)
        assert "预约 Spring Boot postgresql" in calls[0]["q"]
        assert [item.path for item in first.candidates] == ["team/appointments", "team/shop"]
        assert [item["status"] for item in first.candidates[0].stack_evidence] == ["VERIFIED", "VERIFIED"]
        assert first.candidates[0].stack_evidence[0]["url"] == "https://github.com/team/appointments/blob/main/pom.xml"
        assert [item["status"] for item in first.candidates[1].stack_evidence] == ["DOCUMENTED", "DOCUMENTED"]
        assert not [path for path in config_requests if any(name in path for name in (
            "minimal-demo", "spring-projects", "learning-example", "spring-demos",
        ))]
        second, cursor, added = await discover(
            settings, ["JAVA"], "预约", ["GITHUB"], [keyword, "postgresql"], cursor, seen, client,
        )
    assert [item.path for item in second.candidates] == ["another/library"]
    assert not set(seen) & set(added)
    assert not any(cursor.values())
    assert all(call["per_page"] == "8" for call in calls)


@pytest.mark.asyncio
async def test_partial_limits_and_empty_page_keep_cursor():
    def transport(request):
        if request.url.host == "api.github.com":
            return httpx.Response(429)
        return httpx.Response(200, json=[])

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        result, cursor, keys = await discover(
            Settings(gitee_token="test"), ["JAVA"], None, ["GITHUB", "GITEE"], ["springboot"], {}, [], client,
        )
    assert not keys and not result.candidates
    assert cursor["GITHUB:0"] == -1
    assert [(run["platform"], run["status"]) for run in result.source_runs] == [
        ("GITHUB", "FAILED"), ("GITEE", "SUCCEEDED"),
    ]


@pytest.mark.asyncio
async def test_anonymous_github_reads_public_files_without_using_contents_quota():
    def transport(request):
        if request.url.path == "/search/repositories":
            return httpx.Response(200, json={"items": [_repository("team/appointments")]})
        assert request.url.host == "raw.githubusercontent.com"
        assert "authorization" not in request.headers
        if request.url.path.endswith("/pom.xml"):
            return httpx.Response(200, text=POM)
        return httpx.Response(404)

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        result, _, _ = await discover(
            Settings(github_token=None), ["JAVA"], None, ["GITHUB"], ["springboot"], {}, [], client,
        )
    assert result.candidates[0].stack_evidence[0]["path"] == "pom.xml"


@pytest.mark.asyncio
async def test_file_rate_limit_keeps_page_available_for_retry():
    def transport(request):
        if request.url.path == "/search/repositories":
            return httpx.Response(200, json={"items": [_repository("team/appointments")]})
        return httpx.Response(429)

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        result, cursor, _ = await discover(
            Settings(github_token=None), ["JAVA"], None, ["GITHUB"], ["springboot"], {}, [], client,
        )
    assert not result.candidates
    assert "429" in result.source_runs[0]["reason"]
    assert cursor["GITHUB:0"] == -1


def test_readme_requires_usage_context():
    assert not _readme_claim("springboot", "Spring Boot and other alternatives")
    assert not _readme_claim("springboot", "This project does not use Spring Boot.")
    assert _readme_claim("springboot", "## 技术栈\n- Spring Boot")


@pytest.mark.parametrize(("name", "description", "excluded"), [
    ("team/springBoot", "SpringBoot系列Demo代码，每个子项目对应一个知识点", True),
    ("team/Springboot", "springboot 和一些主流框架的整合的各个基本demo", True),
    ("team/auth-spring-boot-starter", "Spring Boot Starter For Shiro", True),
    ("team/shop-demo", "可完整部署的独立小商城", False),
])
def test_excludes_tutorial_repositories_but_keeps_small_applications(name, description, excluded):
    hit = _github_hit(_repository(name, description), [])
    assert _example(hit, "") is excluded
