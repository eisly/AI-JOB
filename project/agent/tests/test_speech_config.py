from __future__ import annotations

import pytest

from app.config import Settings


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