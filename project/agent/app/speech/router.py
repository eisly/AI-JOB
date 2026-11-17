from __future__ import annotations

from typing import Awaitable, Callable

from fastapi import APIRouter, Depends, File, Form, Request, UploadFile
from fastapi.responses import JSONResponse

from ..config import Settings
from ..logging_config import log_context
from ..security import Principal
from .service import SpeechService

PrincipalDependency = Callable[..., Awaitable[Principal]]


def _trace(request: Request) -> str:
    return getattr(request.state, "trace_id", None) or ""


def _result(data, *, trace_id: str) -> JSONResponse:
    return JSONResponse(status_code=200, content={"code": 200, "msg": "操作成功", "data": data, "traceId": trace_id})


def build_speech_router(
    settings: Settings,
    service: SpeechService,
    principal_dependency: PrincipalDependency,
) -> APIRouter:
    """同步语音端点：不进入 RunService 队列、不写 agent_runs、不触发回调。"""

    router = APIRouter()

    @router.post("/internal/ai/v1/speech/transcribe")
    async def transcribe(
        request: Request,
        audio: UploadFile = File(...),
        request_id: str = Form(alias="requestId"),
        user_id: str = Form(alias="userId"),
        mime_type: str = Form(default="audio/webm", alias="mimeType"),
        duration_ms: int = Form(default=0, alias="durationMs"),
        language: str = Form(default="zh"),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        principal.assert_task(request_id)
        data = await audio.read()
        with log_context(trace=_trace(request), user=user_id, op="SPEECH_ASR"):
            result = await service.transcribe(data, mime_type, duration_ms, language)
        return _result(result.model_dump(by_alias=True, mode="json"), trace_id=_trace(request))

    @router.post("/internal/ai/v1/speech/synthesize")
    async def synthesize(
        request: Request,
        body: dict,
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        request_id = str(body.get("requestId") or "")
        if not request_id:
            from ..errors import validation_error

            raise validation_error("requestId 不能为空")
        principal.assert_task(request_id)
        with log_context(trace=_trace(request), user=str(body.get("userId") or ""), op="SPEECH_TTS"):
            result = await service.synthesize(
                str(body.get("text") or ""),
                str(body.get("voice") or settings.speech_tts_voice),
                str(body.get("format") or "mp3"),
            )
        return _result(result.model_dump(by_alias=True, mode="json"), trace_id=_trace(request))

    return router