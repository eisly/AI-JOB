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


@pytest_asyncio.fixture
async def client_and_app(settings: Settings, fake_llm: FakeLLM):
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