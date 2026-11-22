from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class AudioInput:
    """一段待识别的录音。音频只在内存中流转，不落盘。"""

    data: bytes
    mime_type: str
    format: str
    duration_ms: int


@dataclass(frozen=True)
class AsrResult:
    text: str
    provider: str
    model: str
    confidence: float | None = None


@dataclass(frozen=True)
class AudioOutput:
    data: bytes
    mime_type: str
    provider: str
    format: str


class AsrProvider(Protocol):
    async def transcribe(self, audio: AudioInput, *, language: str = "zh") -> AsrResult: ...


class TtsProvider(Protocol):
    async def synthesize(self, text: str, *, voice: str, fmt: str = "mp3") -> AudioOutput: ...