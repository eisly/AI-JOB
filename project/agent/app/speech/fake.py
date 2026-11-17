from __future__ import annotations

import hashlib

from .base import AsrProvider, AsrResult, AudioInput, AudioOutput, TtsProvider

# 128kbps / 44.1kHz 的单帧 MP3 头（FF FB 90 64）+ 静音负载；重复 38 帧约 1 秒。
_MP3_SILENCE_FRAME = b"\xff\xfb\x90\x64" + b"\x00" * 413
_MP3_SILENCE = _MP3_SILENCE_FRAME * 38


class FakeAsrProvider(AsrProvider):
    """离线可控的识别实现：同字节输入得到同一文本，便于断言与联调。"""

    async def transcribe(self, audio: AudioInput, *, language: str = "zh") -> AsrResult:
        if len(audio.data) < 2048 or audio.duration_ms < 1000:
            return AsrResult(text="", provider="fake", model="fake-asr")
        digest = hashlib.sha256(audio.data).hexdigest()[:8]
        return AsrResult(
            text=f"（模拟转写）这是一段语音回答，编号 {digest}",
            provider="fake",
            model="fake-asr",
            confidence=0.99,
        )


class FakeTtsProvider(TtsProvider):
    async def synthesize(self, text: str, *, voice: str = "fake", fmt: str = "mp3") -> AudioOutput:
        return AudioOutput(data=_MP3_SILENCE, mime_type="audio/mpeg", provider="fake", format=fmt)