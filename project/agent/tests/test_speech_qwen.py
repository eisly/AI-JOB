from __future__ import annotations

import base64
import json

import httpx
import pytest

from app.config import Settings
from app.errors import ApiError
from app.speech.base import AudioInput
from app.speech.qwen import QwenAsrProvider, QwenTtsProvider

AUDIO = b"\x1a\x45\xdf\xa3" + b"a" * 4096
# 16kHz / 单声道 / 16bit 的极短 WAV，仅用于断言格式识别与下载链路。
WAV = (
    b"RIFF"
    + (36 + 320).to_bytes(4, "little")
    + b"WAVEfmt "
    + (16).to_bytes(4, "little")
    + (1).to_bytes(2, "little")
    + (1).to_bytes(2, "little")
    + (16000).to_bytes(4, "little")
    + (32000).to_bytes(4, "little")
    + (2).to_bytes(2, "little")
    + (16).to_bytes(2, "little")
    + b"data"
    + (320).to_bytes(4, "little")
    + b"\x00" * 320
)


def _settings(**overrides) -> Settings:
    values = dict(
        app_env="test",
        auth_mode="enabled",
        jwt_secret="test-internal-secret-0123456789abcdef",
        speech_asr_provider="qwen",
        speech_tts_provider="fake",
        speech_api_key="sk-test",
        speech_base_url="https://ws-test.cn-beijing.maas.aliyuncs.com",
        speech_asr_model="qwen-audio-3.1-asr-flash",
        speech_timeout_seconds=3.0,
        speech_concurrency=2,
    )
    values.update(overrides)
    return Settings(**values)


def _audio() -> AudioInput:
    return AudioInput(data=AUDIO, mime_type="audio/webm", format="webm", duration_ms=9000)


def _provider(handler) -> tuple[QwenAsrProvider, httpx.AsyncClient]:
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return QwenAsrProvider(_settings(), client=client), client


def _tts_settings(**overrides) -> Settings:
    return _settings(speech_tts_model="qwen3-tts-flash", speech_tts_voice="Cherry", **overrides)


def _tts_provider(handler) -> tuple[QwenTtsProvider, httpx.AsyncClient]:
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return QwenTtsProvider(_tts_settings(), client=client), client


@pytest.mark.asyncio
async def test_payload_uses_base64_data_uri_and_parses_text() -> None:
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["auth"] = request.headers.get("authorization")
        captured["payload"] = json.loads(request.content.decode("utf-8"))
        return httpx.Response(
            200,
            json={"request_id": "req-1", "output": {"text": "  我负责 Vue 项目。  "}},
        )

    provider, client = _provider(handler)
    try:
        result = await provider.transcribe(_audio())
    finally:
        await client.aclose()

    assert captured["url"].endswith("/api/v1/services/aigc/multimodal-generation/generation")
    assert captured["auth"] == "Bearer sk-test"
    payload = captured["payload"]
    assert payload["model"] == "qwen-audio-3.1-asr-flash"
    assert payload["parameters"]["format"] == "webm"
    data_uri = payload["input"]["messages"][0]["content"][0]["input_audio"]["data"]
    assert data_uri.startswith("data:audio/webm;base64,")
    assert base64.b64decode(data_uri.split(",", 1)[1]) == AUDIO
    assert result.text == "我负责 Vue 项目。"
    assert result.provider == "qwen"
    assert result.model == "qwen-audio-3.1-asr-flash"
    assert result.confidence is None


@pytest.mark.asyncio
async def test_empty_text_is_returned_as_empty() -> None:
    provider, client = _provider(lambda request: httpx.Response(200, json={"output": {"text": "  "}}))
    try:
        result = await provider.transcribe(_audio())
    finally:
        await client.aclose()
    assert result.text == ""


@pytest.mark.asyncio
async def test_no_words_rejection_is_returned_as_empty() -> None:
    provider, client = _provider(lambda request: httpx.Response(
        400, json={"code": "CLIENT_ERROR", "message": "ASR_RESPONSE_HAVE_NO_WORDS"},
    ))
    async with client:
        result = await provider.transcribe(_audio())
    assert result.text == ""
    assert result.provider == "qwen"


@pytest.mark.asyncio
async def test_auth_failure_maps_to_speech_auth_failed() -> None:
    provider, client = _provider(lambda request: httpx.Response(403, json={"code": "AccessDenied"}))
    try:
        with pytest.raises(ApiError) as error:
            await provider.transcribe(_audio())
    finally:
        await client.aclose()
    assert error.value.status_code == 502
    assert error.value.key == "SPEECH_AUTH_FAILED"


@pytest.mark.asyncio
async def test_rate_limit_maps_to_429() -> None:
    provider, client = _provider(lambda request: httpx.Response(429, json={}))
    try:
        with pytest.raises(ApiError) as error:
            await provider.transcribe(_audio())
    finally:
        await client.aclose()
    assert error.value.status_code == 429
    assert error.value.key == "RATE_LIMITED"


@pytest.mark.asyncio
async def test_bad_request_maps_to_unsupported_audio() -> None:
    provider, client = _provider(lambda request: httpx.Response(400, json={"code": "InvalidParameter"}))
    try:
        with pytest.raises(ApiError) as error:
            await provider.transcribe(_audio())
    finally:
        await client.aclose()
    assert error.value.status_code == 415
    assert error.value.key == "UNSUPPORTED_FILE_TYPE"


@pytest.mark.asyncio
async def test_upstream_5xx_maps_to_unavailable() -> None:
    provider, client = _provider(lambda request: httpx.Response(503, json={}))
    try:
        with pytest.raises(ApiError) as error:
            await provider.transcribe(_audio())
    finally:
        await client.aclose()
    assert error.value.status_code == 502
    assert error.value.key == "SPEECH_UNAVAILABLE"


@pytest.mark.asyncio
async def test_timeout_maps_to_speech_timeout() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("timeout", request=request)

    provider, client = _provider(handler)
    try:
        with pytest.raises(ApiError) as error:
            await provider.transcribe(_audio())
    finally:
        await client.aclose()
    assert error.value.status_code == 504
    assert error.value.key == "SPEECH_TIMEOUT"


@pytest.mark.asyncio
async def test_invalid_json_maps_to_bad_response() -> None:
    provider, client = _provider(lambda request: httpx.Response(200, content=b"not-json"))
    try:
        with pytest.raises(ApiError) as error:
            await provider.transcribe(_audio())
    finally:
        await client.aclose()
    assert error.value.status_code == 502
    assert error.value.key == "SPEECH_BAD_RESPONSE"


@pytest.mark.asyncio
async def test_business_error_code_maps_to_bad_response() -> None:
    provider, client = _provider(
        lambda request: httpx.Response(200, json={"code": "InvalidApiKey", "message": "do not log me"})
    )
    try:
        with pytest.raises(ApiError) as error:
            await provider.transcribe(_audio())
    finally:
        await client.aclose()
    assert error.value.status_code == 502
    assert error.value.key == "SPEECH_BAD_RESPONSE"


@pytest.mark.asyncio
async def test_log_has_no_audio_or_text(caplog) -> None:
    provider, client = _provider(
        lambda request: httpx.Response(200, json={"request_id": "r1", "output": {"text": "敏感转写全文"}})
    )
    try:
        with caplog.at_level("INFO", logger="agent.speech.qwen"):
            await provider.transcribe(_audio())
    finally:
        await client.aclose()
    assert "敏感转写全文" not in caplog.text
    assert base64.b64encode(AUDIO).decode()[:32] not in caplog.text
    assert "sk-test" not in caplog.text


@pytest.mark.asyncio
async def test_injected_client_is_not_closed_by_provider() -> None:
    provider, client = _provider(lambda request: httpx.Response(200, json={"output": {"text": "ok"}}))
    await provider.transcribe(_audio())
    await provider.aclose()
    assert client.is_closed is False
    await client.aclose()


@pytest.mark.asyncio
async def test_tts_inline_data_returns_actual_wav_format() -> None:
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["payload"] = json.loads(request.content.decode("utf-8"))
        return httpx.Response(
            200,
            json={
                "request_id": "tts-1",
                "output": {"audio": {"data": base64.b64encode(WAV).decode("ascii"), "mime_type": "audio/wav"}},
            },
        )

    provider, client = _tts_provider(handler)
    try:
        output = await provider.synthesize("请介绍你的项目。", voice="Cherry", fmt="wav")
    finally:
        await client.aclose()

    assert captured["url"].endswith("/api/v1/services/aigc/multimodal-generation/generation")
    payload = captured["payload"]
    assert payload["model"] == "qwen3-tts-flash"
    assert payload["input"]["text"] == "请介绍你的项目。"
    assert payload["input"]["voice"] == "Cherry"
    assert payload["input"]["language_type"] == "Chinese"
    assert output.data == WAV
    assert output.mime_type == "audio/wav"
    assert output.format == "wav"
    assert output.provider == "qwen"


@pytest.mark.asyncio
@pytest.mark.parametrize("base", ["https://workspace.example", "https://workspace.example/api/v1"])
async def test_qwen_audio_tts_uses_speech_synthesizer_endpoint(base: str) -> None:
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["payload"] = json.loads(request.content)
        return httpx.Response(200, json={"output": {"audio": {"data": base64.b64encode(WAV).decode()}}})

    settings = _settings(speech_base_url=base, speech_tts_model="qwen-audio-3.1-tts-flash",
                         speech_tts_voice="longanhuan_v3.1")
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        output = await QwenTtsProvider(settings, client).synthesize("接口回归测试。")

    assert captured["url"] == "https://workspace.example/api/v1/services/audio/tts/SpeechSynthesizer"
    assert captured["payload"] == {
        "model": "qwen-audio-3.1-tts-flash",
        "input": {"text": "接口回归测试。", "voice": "longanhuan_v3.1", "format": "wav", "sample_rate": 24000},
    }
    assert output.data == WAV
    assert output.mime_type == "audio/wav"


@pytest.mark.asyncio
async def test_tts_rebuilds_placeholder_wav_header_from_actual_frames() -> None:
    streaming_wav = bytearray(WAV)
    streaming_wav[4:8] = (0x7FFFFFFF).to_bytes(4, "little")
    streaming_wav[40:44] = (0x7FFFFFFF - 36).to_bytes(4, "little")
    provider, client = _tts_provider(lambda request: httpx.Response(
        200, json={"output": {"audio": {"data": base64.b64encode(streaming_wav).decode()}}}))
    try:
        output = await provider.synthesize("实际音频长度测试。")
    finally:
        await client.aclose()
    assert output.data == WAV


@pytest.mark.asyncio
async def test_tts_downloads_url_without_authorization_header() -> None:
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        if request.method == "POST":
            return httpx.Response(
                200,
                json={"request_id": "tts-2", "output": {"audio": {"url": "https://oss.example/tts.wav"}}},
            )
        return httpx.Response(200, content=WAV, headers={"content-type": "audio/wav"})

    provider, client = _tts_provider(handler)
    try:
        output = await provider.synthesize("第二题", fmt="wav")
    finally:
        await client.aclose()

    assert [item.method for item in calls] == ["POST", "GET"]
    assert calls[1].headers.get("authorization") is None
    assert output.data == WAV
    assert output.format == "wav"


@pytest.mark.asyncio
async def test_tts_uses_settings_voice_when_voice_is_blank() -> None:
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["payload"] = json.loads(request.content.decode("utf-8"))
        return httpx.Response(200, json={"output": {"audio": {"data": base64.b64encode(WAV).decode()}}})

    provider, client = _tts_provider(handler)
    try:
        await provider.synthesize("题目", voice="")
    finally:
        await client.aclose()
    assert captured["payload"]["input"]["voice"] == "Cherry"


@pytest.mark.asyncio
async def test_tts_missing_audio_is_bad_response() -> None:
    provider, client = _tts_provider(lambda request: httpx.Response(200, json={"output": {}}))
    try:
        with pytest.raises(ApiError) as error:
            await provider.synthesize("题目")
    finally:
        await client.aclose()
    assert error.value.status_code == 502
    assert error.value.key == "SPEECH_BAD_RESPONSE"


@pytest.mark.asyncio
async def test_tts_auth_failure_and_rate_limit_are_mapped() -> None:
    provider, client = _tts_provider(lambda request: httpx.Response(403, json={"code": "AccessDenied"}))
    try:
        with pytest.raises(ApiError) as error:
            await provider.synthesize("题目")
    finally:
        await client.aclose()
    assert error.value.status_code == 502
    assert error.value.key == "SPEECH_AUTH_FAILED"

    provider, client = _tts_provider(lambda request: httpx.Response(429, json={}))
    try:
        with pytest.raises(ApiError) as error:
            await provider.synthesize("题目")
    finally:
        await client.aclose()
    assert error.value.status_code == 429
    assert error.value.key == "RATE_LIMITED"


@pytest.mark.asyncio
async def test_tts_arrearage_is_reported_as_auth_failure() -> None:
    provider, client = _tts_provider(
        lambda request: httpx.Response(400, json={"code": "Arrearage", "message": "Access denied"})
    )
    try:
        with pytest.raises(ApiError) as error:
            await provider.synthesize("题目")
    finally:
        await client.aclose()
    assert error.value.status_code == 502
    assert error.value.key == "SPEECH_AUTH_FAILED"


@pytest.mark.asyncio
async def test_tts_download_failure_is_reported() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "POST":
            return httpx.Response(200, json={"output": {"audio": {"url": "https://oss.example/expired.wav"}}})
        return httpx.Response(403, json={})

    provider, client = _tts_provider(handler)
    try:
        with pytest.raises(ApiError) as error:
            await provider.synthesize("题目")
    finally:
        await client.aclose()
    assert error.value.status_code == 502
    assert error.value.key == "SPEECH_BAD_RESPONSE"


@pytest.mark.asyncio
async def test_tts_log_has_no_text_url_or_key(caplog) -> None:
    provider, client = _tts_provider(
        lambda request: httpx.Response(
            200,
            json={"request_id": "tts-3", "output": {"audio": {"data": base64.b64encode(WAV).decode()}}},
        )
    )
    try:
        with caplog.at_level("INFO", logger="agent.speech.qwen"):
            await provider.synthesize("这是一道不该出现在日志里的题目")
    finally:
        await client.aclose()
    assert "这是一道不该出现在日志里的题目" not in caplog.text
    assert "sk-test" not in caplog.text
    assert base64.b64encode(WAV).decode()[:32] not in caplog.text
