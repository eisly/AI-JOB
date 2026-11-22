from __future__ import annotations

import base64
import io
import logging
import time
import wave
from typing import Any

import httpx

from ..config import Settings
from ..errors import ApiError, rate_limited, too_large
from .base import AsrProvider, AsrResult, AudioInput, AudioOutput, TtsProvider

logger = logging.getLogger("agent.speech.qwen")

GENERATION_PATH = "/services/aigc/multimodal-generation/generation"
TTS_LANGUAGE = "Chinese"


class QwenAsrProvider(AsrProvider):
    """百炼 qwen-audio-3.1-asr-flash 同步识别实现。

    音频以 Base64 Data URI 提交，不依赖公网 URL / OSS；每次调用一次 HTTP 请求，
    供应商侧无会话状态。连接池由本类持有并在应用关闭时释放。
    """

    name = "qwen"

    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        self.settings = settings
        self.model = settings.speech_asr_model
        self._client = client
        self._own_client = client is None
        self._endpoint = self._build_endpoint(settings.speech_base_url)

    @staticmethod
    def _build_endpoint(base_url: str) -> str:
        base = (base_url or "https://dashscope.aliyuncs.com").rstrip("/")
        if base.endswith("/api/v1"):
            return base + GENERATION_PATH
        return base + "/api/v1" + GENERATION_PATH

    def _http(self) -> httpx.AsyncClient:
        if self._client is None:
            timeout = self.settings.speech_timeout_seconds
            self._client = httpx.AsyncClient(
                timeout=httpx.Timeout(timeout, connect=min(3.0, timeout)),
                limits=httpx.Limits(
                    max_connections=max(4, self.settings.speech_concurrency * 2),
                    max_keepalive_connections=self.settings.speech_concurrency,
                ),
                trust_env=self.settings.speech_trust_env,
                follow_redirects=True,
            )
        return self._client

    async def transcribe(self, audio: AudioInput, *, language: str = "zh") -> AsrResult:
        if not self.settings.speech_api_key:
            raise ApiError(503, 50301, "语音识别未配置", "SPEECH_NOT_CONFIGURED", retryable=True)
        if not audio.data:
            raise ApiError(400, 40001, "录音内容为空", "VALIDATION_ERROR", retryable=False)

        encoded = base64.b64encode(audio.data).decode("ascii")
        payload: dict[str, Any] = {
            "model": self.model,
            "input": {
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "input_audio",
                                "input_audio": {
                                    "data": "data:%s;base64,%s" % (audio.mime_type, encoded)
                                },
                            }
                        ],
                    }
                ]
            },
            "parameters": {"format": audio.format},
        }
        headers = {
            "Authorization": "Bearer " + self.settings.speech_api_key,
            "Content-Type": "application/json",
            "X-DashScope-SSE": "disable",
        }

        started = time.monotonic()
        try:
            response = await self._http().post(self._endpoint, json=payload, headers=headers)
        except httpx.TimeoutException:
            logger.warning("qwen asr timeout endpoint=%s", self._endpoint)
            raise ApiError(504, 50401, "语音识别超时", "SPEECH_TIMEOUT", retryable=True)
        except httpx.HTTPError as exc:
            logger.warning("qwen asr transport error=%s", type(exc).__name__)
            raise ApiError(503, 50301, "语音识别服务暂不可用", "SPEECH_UNAVAILABLE", retryable=True)

        latency_ms = int((time.monotonic() - started) * 1000)
        status = response.status_code
        if status in (401, 403):
            logger.warning("qwen asr auth failed status=%s latency_ms=%s", status, latency_ms)
            raise ApiError(502, 50201, "语音识别鉴权失败", "SPEECH_AUTH_FAILED", retryable=False)
        if status == 429:
            logger.warning("qwen asr rate limited latency_ms=%s", latency_ms)
            raise rate_limited("语音识别请求过于频繁，请稍后重试")
        if status == 413:
            raise too_large("录音文件超出供应商限制")
        if status == 400:
            try:
                rejection = response.json()
            except ValueError:
                rejection = None
            if isinstance(rejection, dict) and (
                rejection.get("code") == "ASR_RESPONSE_HAVE_NO_WORDS"
                or rejection.get("message") == "ASR_RESPONSE_HAVE_NO_WORDS"
            ):
                logger.info("qwen asr no words status=%s latency_ms=%s outcome=EMPTY", status, latency_ms)
                return AsrResult(text="", provider="qwen", model=self.model)
        if status in (400, 415):
            logger.warning("qwen asr rejected status=%s latency_ms=%s", status, latency_ms)
            raise ApiError(415, 41501, "录音格式暂不支持", "UNSUPPORTED_FILE_TYPE", retryable=False)
        if status >= 500:
            logger.warning("qwen asr upstream failed status=%s latency_ms=%s", status, latency_ms)
            raise ApiError(502, 50201, "语音识别服务暂不可用", "SPEECH_UNAVAILABLE", retryable=True)
        if status != 200:
            logger.warning("qwen asr unexpected status=%s latency_ms=%s", status, latency_ms)
            raise ApiError(502, 50201, "语音识别服务返回异常", "SPEECH_BAD_RESPONSE", retryable=True)

        try:
            body = response.json()
        except ValueError:
            logger.warning("qwen asr invalid json latency_ms=%s", latency_ms)
            raise ApiError(502, 50201, "语音识别服务返回异常", "SPEECH_BAD_RESPONSE", retryable=True)
        if not isinstance(body, dict):
            raise ApiError(502, 50201, "语音识别服务返回异常", "SPEECH_BAD_RESPONSE", retryable=True)
        if body.get("code"):
            logger.warning(
                "qwen asr business error code=%s request_id=%s latency_ms=%s",
                body.get("code"),
                body.get("request_id"),
                latency_ms,
            )
            raise ApiError(502, 50201, "语音识别服务返回异常", "SPEECH_BAD_RESPONSE", retryable=True)

        text = _extract_text(body.get("output"))
        logger.info(
            "qwen asr request_id=%s status=%s latency_ms=%s text_chars=%s outcome=%s",
            body.get("request_id"),
            status,
            latency_ms,
            len(text),
            "OK" if text else "EMPTY",
        )
        return AsrResult(text=text, provider="qwen", model=self.model, confidence=None)

    async def aclose(self) -> None:
        if self._client is not None and self._own_client:
            await self._client.aclose()
            self._client = None


class QwenTtsProvider(TtsProvider):
    """百炼 Qwen3 / Qwen-Audio 同步语音合成。

    供应商可能返回临时音频 URL，也可能内联 Base64 音频；两种都接收。返回实际音频格式，
    由 Java 侧按格式合并分段并设置响应 MIME，避免把 WAV 当 MP3 返回。
    """

    name = "qwen"

    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        self.settings = settings
        self.model = settings.speech_tts_model
        self.voice = settings.speech_tts_voice
        self._client = client
        self._own_client = client is None
        self._endpoint = QwenAsrProvider._build_endpoint(settings.speech_base_url)
        if self.model.startswith("qwen-audio-"):
            self._endpoint = self._endpoint.removesuffix(GENERATION_PATH) + "/services/audio/tts/SpeechSynthesizer"

    def _http(self) -> httpx.AsyncClient:
        if self._client is None:
            timeout = self.settings.speech_tts_timeout_seconds
            self._client = httpx.AsyncClient(
                timeout=httpx.Timeout(timeout, connect=min(3.0, timeout)),
                limits=httpx.Limits(
                    max_connections=max(4, self.settings.speech_concurrency * 2),
                    max_keepalive_connections=self.settings.speech_concurrency,
                ),
                trust_env=self.settings.speech_trust_env,
                follow_redirects=True,
            )
        return self._client

    async def synthesize(self, text: str, *, voice: str = "", fmt: str = "wav") -> AudioOutput:
        if not self.settings.speech_api_key:
            raise ApiError(503, 50301, "题目语音未配置", "SPEECH_NOT_CONFIGURED", retryable=True)
        normalized = " ".join(text.split()).strip()
        if not normalized:
            raise ApiError(400, 40001, "待合成文本为空", "VALIDATION_ERROR", retryable=False)

        payload: dict[str, Any] = {
            "model": self.model,
            "input": {
                "text": normalized,
                "voice": voice or self.voice,
                "language_type": TTS_LANGUAGE,
            },
        }
        if self.model.startswith("qwen-audio-"):
            payload["input"] = {
                "text": normalized,
                "voice": voice or self.voice,
                "format": "wav",
                "sample_rate": 24000,
            }
        headers = {
            "Authorization": "Bearer " + self.settings.speech_api_key,
            "Content-Type": "application/json",
            "X-DashScope-SSE": "disable",
        }

        started = time.monotonic()
        try:
            response = await self._http().post(self._endpoint, json=payload, headers=headers)
        except httpx.TimeoutException:
            logger.warning("qwen tts timeout endpoint=%s", self._endpoint)
            raise ApiError(504, 50401, "题目语音合成超时", "SPEECH_TIMEOUT", retryable=True)
        except httpx.HTTPError as exc:
            logger.warning("qwen tts transport error=%s", type(exc).__name__)
            raise ApiError(503, 50301, "题目语音服务暂不可用", "SPEECH_UNAVAILABLE", retryable=True)

        status = response.status_code
        if status in (401, 403):
            logger.warning("qwen tts auth failed status=%s", status)
            raise ApiError(502, 50201, "题目语音鉴权失败", "SPEECH_AUTH_FAILED", retryable=False)
        if status == 429:
            logger.warning("qwen tts rate limited")
            raise rate_limited("题目语音请求过于频繁，请稍后重试")
        if status >= 500:
            logger.warning("qwen tts upstream failed status=%s", status)
            raise ApiError(502, 50201, "题目语音服务暂不可用", "SPEECH_UNAVAILABLE", retryable=True)
        if status == 400:
            try:
                rejection = response.json()
            except ValueError:
                rejection = None
            if isinstance(rejection, dict) and rejection.get("code") == "Arrearage":
                logger.warning("qwen tts account arrearage")
                raise ApiError(502, 50201, "题目语音服务账户不可用", "SPEECH_AUTH_FAILED", retryable=False)
        if status != 200:
            logger.warning("qwen tts unexpected status=%s", status)
            raise ApiError(502, 50201, "题目语音服务返回异常", "SPEECH_BAD_RESPONSE", retryable=True)

        try:
            body = response.json()
        except ValueError:
            logger.warning("qwen tts invalid json")
            raise ApiError(502, 50201, "题目语音服务返回异常", "SPEECH_BAD_RESPONSE", retryable=True)
        if not isinstance(body, dict):
            raise ApiError(502, 50201, "题目语音服务返回异常", "SPEECH_BAD_RESPONSE", retryable=True)
        if body.get("code"):
            logger.warning("qwen tts business error code=%s request_id=%s", body.get("code"), body.get("request_id"))
            raise ApiError(502, 50201, "题目语音服务返回异常", "SPEECH_BAD_RESPONSE", retryable=True)

        audio = _extract_audio(body.get("output"))
        if audio is None:
            logger.warning("qwen tts missing audio request_id=%s", body.get("request_id"))
            raise ApiError(502, 50201, "题目语音服务返回异常", "SPEECH_BAD_RESPONSE", retryable=True)

        data, source = audio
        declared_mime = source if data else None
        if not data:
            data, declared_mime = await self._download(source)
        if not data:
            raise ApiError(502, 50201, "题目语音服务返回空音频", "SPEECH_BAD_RESPONSE", retryable=True)

        detected = _detect_audio(data)
        if detected is not None and detected[1] == "wav":
            # Qwen-Audio can return a streaming WAV header with placeholder frame lengths.
            try:
                with wave.open(io.BytesIO(data), "rb") as source:
                    params = source.getparams()
                    frames = source.readframes(len(data) // (params.nchannels * params.sampwidth))
                buffer = io.BytesIO()
                with wave.open(buffer, "wb") as target:
                    target.setparams(params)
                    target.writeframes(frames)
                data = buffer.getvalue()
            except (wave.Error, EOFError, ValueError):
                raise ApiError(502, 50201, "题目语音文件格式异常", "SPEECH_BAD_RESPONSE", retryable=True) from None
        mime_type, audio_format = detected if detected is not None else (declared_mime or "audio/wav", fmt or "wav")
        latency_ms = int((time.monotonic() - started) * 1000)
        logger.info(
            "qwen tts request_id=%s status=%s latency_ms=%s format=%s voice=%s chars=%s",
            body.get("request_id"),
            status,
            latency_ms,
            audio_format,
            voice or self.voice,
            len(normalized),
        )
        return AudioOutput(data=data, mime_type=mime_type, provider="qwen", format=audio_format)

    async def _download(self, url: str | None) -> tuple[bytes, str | None]:
        if not url:
            return b"", None
        try:
            response = await self._http().get(url)
        except httpx.TimeoutException:
            logger.warning("qwen tts download timeout")
            raise ApiError(504, 50401, "题目语音下载超时", "SPEECH_TIMEOUT", retryable=True)
        except httpx.HTTPError as exc:
            logger.warning("qwen tts download transport error=%s", type(exc).__name__)
            raise ApiError(503, 50301, "题目语音服务暂不可用", "SPEECH_UNAVAILABLE", retryable=True)
        if response.status_code != 200:
            logger.warning("qwen tts download failed status=%s", response.status_code)
            raise ApiError(502, 50201, "题目语音下载失败", "SPEECH_BAD_RESPONSE", retryable=True)
        return response.content, response.headers.get("content-type")

    async def aclose(self) -> None:
        if self._client is not None and self._own_client:
            await self._client.aclose()
            self._client = None


def _extract_audio(output: Any) -> tuple[bytes, str | None] | None:
    """返回 (内联音频字节, 声明 MIME)；只有 URL 时字节为空，由调用方下载。"""
    if not isinstance(output, dict):
        return None
    audio = output.get("audio")
    if isinstance(audio, str):
        return b"", audio.strip() or None
    if not isinstance(audio, dict):
        return None
    declared_mime = audio.get("mime_type") if isinstance(audio.get("mime_type"), str) else None
    inline = audio.get("data")
    if isinstance(inline, str) and inline.strip():
        raw = inline.strip()
        if raw.startswith("data:"):
            raw = raw.split(",", 1)[1] if "," in raw else ""
        try:
            return base64.b64decode(raw), declared_mime
        except (ValueError, TypeError):
            raise ApiError(502, 50201, "题目语音服务返回异常", "SPEECH_BAD_RESPONSE", retryable=True)
    url = audio.get("url")
    return b"", url.strip() if isinstance(url, str) else None


def _detect_audio(data: bytes) -> tuple[str, str] | None:
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WAVE":
        return "audio/wav", "wav"
    if data[:3] == b"ID3" or (len(data) >= 2 and data[0] == 0xFF and (data[1] & 0xE0) == 0xE0):
        return "audio/mpeg", "mp3"
    if data[:4] == b"OggS":
        return "audio/ogg", "ogg"
    if len(data) >= 12 and data[4:8] == b"ftyp":
        return "audio/mp4", "mp4"
    return None


def _extract_text(output: Any) -> str:
    if not isinstance(output, dict):
        return ""
    text = output.get("text")
    if isinstance(text, str):
        return " ".join(text.split()).strip()
    choices = output.get("choices")
    if isinstance(choices, list) and choices:
        message = choices[0].get("message") if isinstance(choices[0], dict) else None
        content = message.get("content") if isinstance(message, dict) else None
        if isinstance(content, str):
            return " ".join(content.split()).strip()
        if isinstance(content, list):
            for item in content:
                if isinstance(item, dict) and isinstance(item.get("text"), str):
                    return " ".join(item["text"].split()).strip()
    return ""
