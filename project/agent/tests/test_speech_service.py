from __future__ import annotations

import pytest

from app.config import Settings
from app.errors import ApiError
from app.speech.base import AsrResult, AudioInput, AudioOutput
from app.speech.fake import FakeAsrProvider, FakeTtsProvider
from app.speech.service import SpeechService


def _settings(**overrides) -> Settings:
    base = dict(
        app_env="test",
        auth_mode="enabled",
        jwt_secret="test-internal-secret-0123456789abcdef",
        storage_backend="memory",
        speech_provider="fake",
        speech_asr_provider="fake",
        speech_tts_provider="fake",
        speech_api_key=None,
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


def test_qwen_asr_requires_injected_provider() -> None:
    with pytest.raises(RuntimeError, match="未注入"):
        SpeechService(_settings(speech_asr_provider="qwen", speech_tts_provider="fake"))


def test_qwen_asr_rejects_fake_injection() -> None:
    with pytest.raises(RuntimeError, match="不一致"):
        SpeechService(
            _settings(speech_asr_provider="qwen", speech_tts_provider="fake"),
            asr=FakeAsrProvider(),
        )


def test_prod_rejects_injected_fake() -> None:
    with pytest.raises(RuntimeError, match="Fake ASR"):
        SpeechService(
            _settings(app_env="prod", speech_asr_provider="fake", speech_tts_provider="fake"),
            asr=FakeAsrProvider(),
            tts=FakeTtsProvider(),
        )


@pytest.mark.asyncio
async def test_close_closes_provider() -> None:
    class ClosingAsr(FakeAsrProvider):
        name = "qwen"

        def __init__(self) -> None:
            self.closed = False

        async def transcribe(self, audio: AudioInput, *, language: str = "zh") -> AsrResult:
            return AsrResult(text="ok", provider="qwen", model="stub")

        async def aclose(self) -> None:
            self.closed = True

    provider = ClosingAsr()
    service = SpeechService(
        _settings(speech_asr_provider="qwen", speech_tts_provider="fake"),
        asr=provider,
        tts=FakeTtsProvider(),
    )
    await service.close()
    assert provider.closed is True


@pytest.mark.asyncio
async def test_synthesize_uses_injected_tts() -> None:
    class StubTts(FakeTtsProvider):
        async def synthesize(self, text: str, *, voice: str = "fake", fmt: str = "mp3") -> AudioOutput:
            return AudioOutput(data=b"mp3", mime_type="audio/mpeg", provider="fake", format=fmt)

    service = SpeechService(_settings(), tts=StubTts())
    result = await service.synthesize("hello")
    assert result.provider == "fake"
    assert result.audio_base64