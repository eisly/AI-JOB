from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.api import create_app
from app.config import Settings
from app.fake_model import FakeLLM


def test_fake_provider_is_rejected_in_prod() -> None:
    settings = Settings(
        app_env="prod",
        auth_mode="enabled",
        jwt_secret="prod-internal-secret-0123456789abcdef",
        llm_provider="deepseek",
        deepseek_api_key="test-key",
        speech_provider="fake",
    )
    with pytest.raises(RuntimeError, match="speech"):
        settings.validate_runtime_security()


def test_qwen_provider_requires_api_key_in_prod() -> None:
    settings = Settings(
        app_env="prod",
        auth_mode="enabled",
        jwt_secret="prod-internal-secret-0123456789abcdef",
        llm_provider="deepseek",
        deepseek_api_key="test-key",
        speech_provider="qwen",
    )
    settings.validate_runtime_security()
    assert settings.effective_speech_provider == "qwen"


@pytest.mark.parametrize("speech_api_key", [None, "test-speech-key"])
def test_dev_auto_uses_fake_until_real_provider_is_available(speech_api_key: str | None) -> None:
    settings = Settings(app_env="dev", speech_provider="auto", speech_api_key=speech_api_key)
    assert settings.effective_speech_provider == "fake"
    assert "speech_api_key" not in repr(settings)


@pytest.mark.parametrize("speech_api_key", [None, "test-speech-key"])
def test_prod_auto_starts_core_agent_without_mock_routes(speech_api_key: str | None) -> None:
    settings = Settings(
        app_env="prod",
        auth_mode="enabled",
        jwt_secret="prod-internal-secret-0123456789abcdef",
        llm_provider="deepseek",
        deepseek_api_key="test-key",
        speech_provider="auto",
        speech_api_key=speech_api_key,
        storage_backend="memory",
    )
    client = TestClient(create_app(settings, llm_client=FakeLLM()))
    assert client.get("/healthz").status_code == 200
    assert client.post("/internal/ai/v1/speech/synthesize").status_code == 404
