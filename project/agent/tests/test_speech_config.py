from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.api import create_app
from app.config import Settings
from app.fake_model import FakeLLM


def _base(**overrides) -> Settings:
    values = dict(
        app_env="dev",
        auth_mode="enabled",
        jwt_secret="test-internal-secret-0123456789abcdef",
        speech_api_key=None,
        speech_asr_provider="auto",
        speech_tts_provider="auto",
    )
    values.update(overrides)
    return Settings(**values)


def test_fake_asr_is_rejected_in_prod() -> None:
    settings = _base(
        app_env="prod",
        llm_provider="deepseek",
        deepseek_api_key="test-key",
        speech_asr_provider="fake",
        speech_tts_provider="qwen",
    )
    with pytest.raises(RuntimeError, match="ASR"):
        settings.validate_runtime_security()


def test_prod_allows_fake_tts_with_real_asr() -> None:
    settings = _base(
        app_env="prod",
        llm_provider="deepseek",
        deepseek_api_key="test-key",
        speech_asr_provider="qwen",
        speech_tts_provider="fake",
        speech_api_key="sk-test",
    )
    settings.validate_runtime_security()
    assert settings.effective_asr_provider == "qwen"
    assert settings.effective_tts_provider == "fake"


def test_qwen_asr_requires_api_key_in_prod() -> None:
    settings = _base(
        app_env="prod",
        llm_provider="deepseek",
        deepseek_api_key="test-key",
        speech_asr_provider="qwen",
        speech_tts_provider="qwen",
        speech_api_key=None,
    )
    with pytest.raises(RuntimeError, match="SPEECH_API_KEY"):
        settings.validate_runtime_security()


def test_specific_provider_overrides_legacy_switch() -> None:
    settings = _base(
        speech_provider="fake",
        speech_asr_provider="qwen",
        speech_tts_provider="fake",
    )
    assert settings.effective_asr_provider == "qwen"
    assert settings.effective_tts_provider == "fake"


def test_legacy_switch_still_applies_when_specific_is_auto() -> None:
    settings = _base(speech_provider="fake", speech_asr_provider="auto", speech_tts_provider="auto")
    assert settings.effective_asr_provider == "fake"
    assert settings.effective_tts_provider == "fake"


def test_auto_provider_falls_back_to_fake_without_key() -> None:
    settings = _base(speech_provider="auto", speech_api_key=None)
    assert settings.effective_asr_provider == "fake"
    assert settings.effective_tts_provider == "fake"
    assert "speech_api_key" not in repr(settings)


def test_dev_allows_real_asr_with_fake_tts() -> None:
    settings = _base(
        app_env="dev",
        speech_asr_provider="qwen",
        speech_tts_provider="fake",
        speech_api_key="sk-test",
    )
    settings.validate_runtime_security()
    assert settings.effective_asr_provider == "qwen"
    assert settings.effective_tts_provider == "fake"


def test_prod_explicit_qwen_exposes_asr_without_mock_tts() -> None:
    settings = _base(app_env="prod", speech_asr_provider="qwen", speech_api_key="sk-test",
                     storage_backend="memory", llm_provider="fake")
    client = TestClient(create_app(settings, llm_client=FakeLLM()))
    assert client.post("/internal/ai/v1/speech/transcribe").status_code != 404
    assert client.post("/internal/ai/v1/speech/synthesize").status_code == 404


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
