from __future__ import annotations

from app.compat import ensure_asyncio_self_pipe

# 必须在创建任何 asyncio 事件循环之前完成环境适配（沙箱禁用 IPv4 回环时兜底）。
ensure_asyncio_self_pipe()

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.api import create_app
from app.config import Settings
from app.fake_model import FakeLLM
from app.security import create_internal_token


@pytest.fixture(params=["memory", "sqlite"])
def settings(request: pytest.FixtureRequest, tmp_path) -> Settings:
    backend = request.param
    kwargs = dict(
        app_env="test",
        auth_mode="enabled",
        jwt_secret="test-internal-secret-0123456789abcdef",
        storage_backend=backend,
        callback_enabled=False,
        worker_count=2,
        event_poll_seconds=0.02,
        heartbeat_seconds=0.2,
        llm_provider="fake",
        speech_asr_provider="fake",
        speech_tts_provider="fake",
        # 显式隔离本地 .env / 环境变量中的语音凭据，避免不同机器配置导致冲突或真实调用。
        speech_api_key=None,
        log_level="WARNING",
    )
    if backend == "sqlite":
        kwargs["sqlite_path"] = str(tmp_path / "agent_runs.db")
    return Settings(**kwargs)


@pytest.fixture
def fake_llm() -> FakeLLM:
    return FakeLLM()


@pytest.fixture
def headers_for(settings: Settings):
    def build(task_id: str, scope: str = "ai:run", audience: str | None = None) -> dict[str, str]:
        token = create_internal_token(
            settings,
            task_ids=[task_id],
            scope=scope,
            audience=audience,
        )
        return {"Authorization": f"Bearer {token}"}

    return build


@pytest.fixture
def repository_search_stub(monkeypatch):
    from app.project_sources import RepoHit, RepositorySearchResult

    async def stub(_settings, _skills, _query=None, _platforms=None, tech_stack_keywords=None, client=None):
        hit = RepoHit(
            platform="GITHUB",
            path="spring-projects/spring-boot",
            name="Spring Boot",
            url="https://github.com/spring-projects/spring-boot",
            description="Spring Boot makes it easy to create stand-alone, production-grade Spring applications.",
            stars=70000,
            default_branch="main",
            license_hint="Apache-2.0",
            readme_hint="https://github.com/spring-projects/spring-boot/blob/main/README.adoc",
        )
        hit.license = "Apache-2.0"
        hit.readme_url = hit.readme_hint
        return RepositorySearchResult(
            source_runs=[{"platform": "GITHUB", "status": "SUCCEEDED", "reason": None}],
            candidates=[hit],
            terms=["java"],
        )

    monkeypatch.setattr("app.handlers.search_project_repositories", stub)
    return stub


@pytest_asyncio.fixture
async def client_and_app(settings: Settings, fake_llm: FakeLLM, repository_search_stub):
    app = create_app(settings, llm_client=fake_llm)
    async with app.router.lifespan_context(app):
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://agent.test") as client:
            yield client, app


@pytest.fixture
def client(client_and_app):
    return client_and_app[0]


@pytest.fixture
def app(client_and_app):
    return client_and_app[1]
