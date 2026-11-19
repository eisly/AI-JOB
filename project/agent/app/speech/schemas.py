from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


class TranscribeResult(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    text: str | None = None
    empty: bool = False
    provider: str
    model: str
    confidence: float | None = None
    audio_duration_ms: int = Field(alias="audioDurationMs")
    latency_ms: int = Field(alias="latencyMs")


class SynthesizeResult(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    audio_base64: str = Field(alias="audioBase64")
    mime_type: str = Field(alias="mimeType")
    provider: str
    chars: int
    latency_ms: int = Field(alias="latencyMs")