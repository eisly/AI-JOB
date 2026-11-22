from __future__ import annotations

import os
import uuid
from pathlib import Path

import pytest
from httpx import ASGITransport, AsyncClient

from app.api import create_app
from app.config import Settings
from app.security import create_internal_token

RECORDING = (
    Path(__file__).resolve().parents[3]
    / ".local-dev-env"
    / "voice-samples"
    / "vue-springboot-answer.m4a"
)

pytestmark = pytest.mark.skipif(
    os.environ.get("RUN_REAL_ASR") != "1",
    reason="真实 ASR 会调用付费接口；显式设置 RUN_REAL_ASR=1 后执行",
)


@pytest.mark.asyncio
async def test_real_transcribe_http_endpoint() -> None:
    if not RECORDING.is_file():
        pytest.skip("缺少本地录音样本: %s" % RECORDING)

    settings = Settings(
        app_env="dev",
        auth_mode="enabled",
        jwt_secret="test-internal-secret-0123456789abcdef",
        storage_backend="memory",
        callback_enabled=False,
        llm_provider="fake",
        speech_asr_provider="qwen",
        speech_tts_provider="fake",
    )
    if not settings.speech_api_key:
        pytest.skip("未配置 SPEECH_API_KEY")

    app = create_app(settings)
    async with app.router.lifespan_context(app):
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://agent.test") as client:
            request_id = str(uuid.uuid4())
            token = create_internal_token(settings, task_ids=[request_id], scope="ai:speech")
            response = await client.post(
                "/internal/ai/v1/speech/transcribe",
                headers={"Authorization": "Bearer " + token},
                data={
                    "requestId": request_id,
                    "userId": "u1",
                    "mimeType": "audio/mp4",
                    "durationMs": "9000",
                },
                files={"audio": ("answer.m4a", RECORDING.read_bytes(), "audio/mp4")},
            )

    assert response.status_code == 200, response.text
    data = response.json()["data"]
    assert data["provider"] == "qwen"
    assert data["text"]
    assert data["empty"] is False
    assert data["confidence"] is None
    assert "Vue" in data["text"] or "Spring" in data["text"]