from __future__ import annotations

import hashlib

import pytest

from app.speech.base import AudioInput
from app.speech.fake import FakeAsrProvider, FakeTtsProvider


@pytest.mark.asyncio
async def test_fake_asr_is_deterministic() -> None:
    data = b"\x1a\x45\xdf\xa3" + b"x" * 4096
    audio = AudioInput(data=data, mime_type="audio/webm", format="webm", duration_ms=10_000)
    provider = FakeAsrProvider()
    first = await provider.transcribe(audio)
    second = await provider.transcribe(audio)
    assert first.text == second.text
    assert hashlib.sha256(data).hexdigest()[:8] in first.text
    assert first.provider == "fake"


@pytest.mark.asyncio
async def test_fake_asr_silence_returns_empty() -> None:
    provider = FakeAsrProvider()
    tiny = await provider.transcribe(AudioInput(b"\x1a\x45\xdf\xa3", "audio/webm", "webm", 300))
    short = await provider.transcribe(AudioInput(b"\x1a\x45\xdf\xa3" + b"x" * 4096, "audio/webm", "webm", 500))
    assert tiny.text == ""
    assert short.text == ""


@pytest.mark.asyncio
async def test_fake_tts_returns_playable_mp3_bytes() -> None:
    output = await FakeTtsProvider().synthesize("这是一道题目", voice="fake", fmt="mp3")
    assert output.mime_type == "audio/mpeg"
    assert output.data[:4] == b"\xff\xfb\x90\x64"
    assert len(output.data) > 10_000