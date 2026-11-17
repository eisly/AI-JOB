from __future__ import annotations

import pytest

from app.config import Settings
from app.errors import ApiError
from app.speech.service import SpeechService


def _settings(**overrides) -> Settings:
    base = dict(
        app_env="test",
        auth_mode="enabled",
        jwt_secret="test-internal-secret-0123456789abcdef",
        storage_backend="memory",
        speech_provider="fake",
        speech_max_bytes=8 * 1024 * 1024,
        speech_min_audio_ms=2000,
        speech_max_audio_ms=180_000,
    )
    base.update(overrides)
    return Settings(**base)


@pytest.mark.asyncio
async def test_transcribe_rejects_oversized_audio() -> None:
    service = SpeechService(_settings(speech_max_bytes=10))
    with pytest.raises(ApiError) as error:
        await service.transcribe(b"\x1a\x45\xdf\xa3" + b"x" * 100, "audio/webm", 5000)
    assert error.value.status_code == 413


@pytest.mark.asyncio
async def test_transcribe_rejects_non_audio() -> None:
    service = SpeechService(_settings())
    with pytest.raises(ApiError) as error:
        await service.transcribe(b"plain text body", "text/plain", 5000)
    assert error.value.status_code == 415


@pytest.mark.asyncio
async def test_transcribe_rejects_disguised_audio() -> None:
    service = SpeechService(_settings())
    with pytest.raises(ApiError) as error:
        await service.transcribe(b"not a real audio payload", "audio/webm", 5000)
    assert error.value.status_code == 415


@pytest.mark.asyncio
async def test_transcribe_rejects_invalid_duration() -> None:
    service = SpeechService(_settings())
    with pytest.raises(ApiError) as error:
        await service.transcribe(b"\x1a\x45\xdf\xa3" + b"x" * 100, "audio/webm", 100)
    assert error.value.status_code == 400


@pytest.mark.asyncio
async def test_transcribe_success_and_log_has_no_plaintext(caplog) -> None:
    service = SpeechService(_settings())
    data = b"\x1a\x45\xdf\xa3" + b"x" * 4096
    with caplog.at_level("INFO", logger="agent.speech"):
        result = await service.transcribe(data, "audio/webm", 8000)
    assert result.text and "模拟转写" in result.text
    assert result.empty is False
    assert "x" * 100 not in caplog.text
    assert result.text not in caplog.text


@pytest.mark.asyncio
async def test_synthesize_rejects_too_long_text() -> None:
    service = SpeechService(_settings(speech_max_tts_chars=10))
    with pytest.raises(ApiError) as error:
        await service.synthesize("a" * 11)
    assert error.value.status_code == 400


@pytest.mark.asyncio
async def test_synthesize_returns_base64_audio() -> None:
    service = SpeechService(_settings())
    result = await service.synthesize("这是一道面试题")
    assert result.mime_type == "audio/mpeg"
    assert result.chars == 7
    assert len(result.audio_base64) > 100