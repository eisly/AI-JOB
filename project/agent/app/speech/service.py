from __future__ import annotations

import asyncio
import base64
import hashlib
import logging
import time

from ..config import Settings
from ..errors import ApiError, rate_limited, too_large, validation_error
from .base import AsrProvider, AudioInput, TtsProvider
from .fake import FakeAsrProvider, FakeTtsProvider
from .schemas import SynthesizeResult, TranscribeResult

logger = logging.getLogger("agent.speech")

_ALLOWED_MIME = {
    "audio/webm": "webm",
    "audio/ogg": "ogg",
    "audio/mp4": "mp4",
    "audio/mpeg": "mp3",
    "audio/wav": "wav",
}


def _detect_format(data: bytes) -> str | None:
    if data[:4] == b"\x1a\x45\xdf\xa3":
        return "webm"
    if data[:4] == b"OggS":
        return "ogg"
    if len(data) >= 12 and data[4:8] == b"ftyp":
        return "mp4"
    if data[:3] == b"ID3" or (len(data) >= 2 and data[0] == 0xFF and (data[1] & 0xE0) == 0xE0):
        return "mp3"
    if data[:4] == b"RIFF" and data[8:12] == b"WAVE":
        return "wav"
    return None


class SpeechService:
    def __init__(self, settings: Settings, asr: AsrProvider | None = None, tts: TtsProvider | None = None) -> None:
        self.settings = settings
        asr_provider = settings.effective_asr_provider
        tts_provider = settings.effective_tts_provider
        if asr is None:
            if asr_provider != "fake":
                raise RuntimeError("speech ASR provider=%s 未注入真实实现" % asr_provider)
            asr = FakeAsrProvider()
        elif asr_provider != "fake" and getattr(asr, "name", "fake") != asr_provider:
            raise RuntimeError(
                "speech ASR provider=%s 与注入实现 %s 不一致" % (asr_provider, getattr(asr, "name", "unknown"))
            )
        if tts is None:
            if tts_provider != "fake":
                raise RuntimeError("speech TTS provider=%s 未注入真实实现" % tts_provider)
            tts = FakeTtsProvider()
        elif tts_provider != "fake" and getattr(tts, "name", "fake") != tts_provider:
            raise RuntimeError(
                "speech TTS provider=%s 与注入实现 %s 不一致" % (tts_provider, getattr(tts, "name", "unknown"))
            )
        if settings.app_env == "prod":
            if getattr(asr, "name", "fake") == "fake":
                raise RuntimeError("prod 不允许使用 Fake ASR Provider；请注入真实实现")
            if getattr(tts, "name", "fake") == "fake":
                logger.warning("prod 使用 Fake TTS：仅 ASR 为真实，TTS 入口必须保持关闭")
        self.asr = asr
        self.tts = tts
        self._semaphore = asyncio.Semaphore(settings.speech_concurrency)

    @property
    def asr_provider_name(self) -> str:
        return getattr(self.asr, "name", self.settings.effective_asr_provider)

    @property
    def tts_provider_name(self) -> str:
        return getattr(self.tts, "name", self.settings.effective_tts_provider)

    async def close(self) -> None:
        closer = getattr(self.asr, "aclose", None)
        if closer is not None:
            await closer()

    async def transcribe(self, data: bytes, mime_type: str, duration_ms: int, language: str = "zh") -> TranscribeResult:
        if len(data) == 0:
            raise validation_error("录音内容为空")
        if len(data) > self.settings.speech_max_bytes:
            raise too_large("录音文件超出大小限制")
        normalized_mime = mime_type.lower().split(";")[0].strip()
        declared_format = _ALLOWED_MIME.get(normalized_mime)
        if declared_format is None:
            raise ApiError(415, 41501, "不支持的录音格式", "UNSUPPORTED_FILE_TYPE")
        if _detect_format(data) != declared_format:
            raise ApiError(415, 41501, "录音内容与声明格式不符", "UNSUPPORTED_FILE_TYPE")
        if duration_ms < self.settings.speech_min_audio_ms or duration_ms > self.settings.speech_max_audio_ms:
            raise validation_error("录音时长超出允许范围")
        if self._semaphore.locked():
            raise rate_limited("语音服务繁忙，请稍后重试")
        started = time.monotonic()
        async with self._semaphore:
            result = await self.asr.transcribe(
                AudioInput(data=data, mime_type=normalized_mime, format=declared_format, duration_ms=duration_ms),
                language=language,
            )
        latency_ms = int((time.monotonic() - started) * 1000)
        text = " ".join(result.text.split()).strip()
        logger.info(
            "speech op=ASR outcome=%s provider=%s latency_ms=%s audio_duration_ms=%s text_chars=%s sha8=%s",
            "EMPTY" if not text else "OK",
            result.provider,
            latency_ms,
            duration_ms,
            len(text),
            hashlib.sha256(data).hexdigest()[:8],
        )
        return TranscribeResult(
            text=text or None,
            empty=not text,
            provider=result.provider,
            model=result.model,
            confidence=result.confidence,
            audioDurationMs=duration_ms,
            latencyMs=latency_ms,
        )

    async def synthesize(self, text: str, voice: str | None = None, fmt: str = "mp3") -> SynthesizeResult:
        normalized = " ".join(text.split()).strip()
        if not normalized:
            raise validation_error("待合成文本不能为空")
        if len(normalized) > self.settings.speech_max_tts_chars:
            raise validation_error(f"单次合成文本不能超过 {self.settings.speech_max_tts_chars} 字")
        started = time.monotonic()
        async with self._semaphore:
            audio = await self.tts.synthesize(normalized, voice=voice or self.settings.speech_tts_voice, fmt=fmt)
        latency_ms = int((time.monotonic() - started) * 1000)
        logger.info(
            "speech op=TTS outcome=OK provider=%s latency_ms=%s text_chars=%s voice=%s",
            audio.provider,
            latency_ms,
            len(normalized),
            voice or self.settings.speech_tts_voice,
        )
        return SynthesizeResult(
            audioBase64=base64.b64encode(audio.data).decode("ascii"),
            mimeType=audio.mime_type,
            provider=audio.provider,
            chars=len(normalized),
            latencyMs=latency_ms,
        )
