from __future__ import annotations

import pytest

from app.config import Settings


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


def test_auto_provider_falls_back_to_fake_without_key() -> None:
    settings = Settings(speech_provider="auto", speech_api_key=None)
    assert settings.effective_speech_provider == "fake"
    assert "speech_api_key" not in repr(settings)