from __future__ import annotations

import os
from pathlib import Path

import pytest

from app.config import Settings
from app.speech.base import AudioInput
from app.speech.qwen import QwenAsrProvider

RECORDING = (
    Path(__file__).resolve().parents[3]
    / ".local-dev-env"
    / "voice-samples"
    / "vue-springboot-answer.m4a"
)

pytestmark = pytest.mark.skipif(
    os.environ.get("RUN_REAL_ASR") != "1",
    reason="真实 ASR 会调用付费接口；显式设置 RUN_REAL_ASR=1 后执行",
)


@pytest.mark.asyncio
async def test_real_qwen_asr_m4a() -> None:
    if not RECORDING.is_file():
        pytest.skip("缺少本地录音样本: %s" % RECORDING)
    settings = Settings(
        app_env="dev",
        speech_asr_provider="qwen",
        speech_tts_provider="fake",
    )
    if not settings.speech_api_key:
        pytest.skip("未配置 SPEECH_API_KEY")

    provider = QwenAsrProvider(settings)
    try:
        result = await provider.transcribe(
            AudioInput(
                data=RECORDING.read_bytes(),
                mime_type="audio/mp4",
                format="mp4",
                duration_ms=9000,
            )
        )
    finally:
        await provider.aclose()

    assert result.provider == "qwen"
    assert result.model == "qwen-audio-3.1-asr-flash"
    assert result.text
    assert "Vue" in result.text or "Spring" in result.text
    assert result.confidence is None