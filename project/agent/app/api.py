from __future__ import annotations

import asyncio
import logging
import time
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Any, AsyncIterator
from uuid import UUID, uuid4

from fastapi import Depends, FastAPI, Header, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from .callbacks import CallbackDispatcher, CallbackTransport
from .config import Settings
from .errors import ApiError, forbidden, not_found, unauthorized, validation_error
from .files import FileFetcher
from .llm import LLMClient, create_llm
from .logging_config import configure_logging, log_context, summarize_payload
from .operations import (
    GROWTH_ANALYZE,
    INTERVIEW_REPORT,
    INTERVIEW_TURN,
    JD_ANALYZE,
    JOB_MATCH,
    JOB_SEARCH,
    LEARNING_EVALUATE,
    LEARNING_PLAN,
    LEARNING_PRACTICE_EVALUATE,
    LEARNING_PRACTICE_QUESTIONS,
    LEARNING_QUESTION,
    PROJECT_PLAN,
    PROJECT_RECOMMEND,
    RESUME_DIAGNOSE,
    RESUME_OPTIMIZE,
    RESUME_OCR,
    RESUME_PARSE,
)
from .runner import RunService
from .schemas import AIRequestEnvelope, AIRunVO
from .security import Principal, verify_internal_token
from .speech.router import build_speech_router
from .speech.qwen import QwenAsrProvider
from .speech.service import SpeechService
from .storage import RunRecord, RunRepository, build_repository, utcnow

logger = logging.getLogger("agent.api")


def _trace_id(request: Request) -> str:
    return getattr(request.state, "trace_id", None) or uuid4().hex


def _result(data: Any, *, code: int = 200, msg: str = "操作成功", trace_id: str = "") -> JSONResponse:
    return JSONResponse(
        status_code=code,
        content={"code": code, "msg": msg, "data": data, "traceId": trace_id},
    )


def _error_body(exc: ApiError, trace_id: str) -> dict[str, Any]:
    return {
        "code": exc.code,
        "msg": exc.message,
        "data": None,
        "traceId": trace_id,
        "error": {
            "key": exc.key,
            "retryable": exc.retryable,
            "details": exc.details,
        },
    }


def _run_payload(record: RunRecord) -> dict[str, Any]:
    return record.as_vo().model_dump(by_alias=True, mode="json")


def _assert_task(settings: Settings, principal: Principal, task_id: str) -> None:
    if settings.auth_mode == "disabled":
        return
    principal.assert_task(task_id)


def create_app(
    settings: Settings | None = None,
    *,
    llm_client: LLMClient | None = None,
    repository: RunRepository | None = None,
    callback_transport: CallbackTransport | None = None,
) -> FastAPI:
    settings = settings or Settings()
    settings.validate_runtime_security()
    configure_logging(settings.log_level)

    repository = repository or build_repository(settings)
    llm_client = llm_client or create_llm(settings)
    callbacks = CallbackDispatcher(repository, settings, callback_transport)
    service = RunService(settings, repository, llm_client, callbacks, FileFetcher(settings))
    speech_service = None
    if settings.effective_asr_provider != "disabled":
        speech_asr = QwenAsrProvider(settings) if settings.effective_asr_provider == "qwen" else None
        speech_service = SpeechService(settings, asr=speech_asr)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        await service.start()
        logger.info("speech configuration sources=%s", settings.speech_config_origins)
        logger.info(
            "agent started provider=%s model=%s asr=%s tts=%s storage=%s callback=%s auth=%s",
            settings.effective_llm_provider,
            settings.effective_model_name,
            settings.effective_asr_provider,
            settings.effective_tts_provider,
            settings.storage_backend,
            settings.callback_enabled,
            settings.auth_mode,
        )
        try:
            yield
        finally:
            if speech_service is not None:
                await speech_service.close()
            await service.stop()
            logger.info("agent stopped")

    app = FastAPI(
        title="AI-JOB Internal Agent",
        version="0.1.0",
        docs_url="/docs" if settings.app_env != "prod" else None,
        redoc_url=None,
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.repository = repository
    app.state.llm = llm_client
    app.state.callbacks = callbacks
    app.state.service = service

    @app.middleware("http")
    async def trace_middleware(request: Request, call_next):  # type: ignore[no-untyped-def]
        trace = request.headers.get(settings.trace_header) or uuid4().hex
        request.state.trace_id = trace
        started = time.monotonic()
        quiet = request.url.path in {"/healthz", "/readyz"} or request.url.path.startswith("/docs")
        log_at = logging.DEBUG if quiet else logging.INFO
        client = request.client.host if request.client else "-"
        with log_context(trace=trace):
            logger.log(log_at, "--> %s %s client=%s", request.method, request.url.path, client)
            try:
                response = await call_next(request)
            except Exception:
                elapsed = int((time.monotonic() - started) * 1000)
                logger.exception(
                    "!! %s %s failed duration=%sms",
                    request.method,
                    request.url.path,
                    elapsed,
                )
                raise
            elapsed = int((time.monotonic() - started) * 1000)
            logger.log(
                log_at,
                "<-- %s %s status=%s duration=%sms",
                request.method,
                request.url.path,
                response.status_code,
                elapsed,
            )
        response.headers[settings.trace_header] = trace
        return response

    @app.exception_handler(ApiError)
    async def handle_api_error(request: Request, exc: ApiError) -> JSONResponse:
        logger.warning(
            "api error status=%s key=%s retryable=%s message=%s",
            exc.status_code,
            exc.key,
            exc.retryable,
            exc.message,
        )
        return JSONResponse(status_code=exc.status_code, content=_error_body(exc, _trace_id(request)))

    @app.exception_handler(RequestValidationError)
    async def handle_validation(request: Request, exc: RequestValidationError) -> JSONResponse:
        fields = []
        for error in exc.errors():
            location = ".".join(str(item) for item in error.get("loc", ()))
            fields.append({"field": location or "body", "message": str(error.get("msg", "invalid"))})
        logger.warning("request validation failed fields=%s", ",".join(item["field"] for item in fields))
        api = validation_error("参数校验失败", details={"fields": fields})
        return JSONResponse(status_code=400, content=_error_body(api, _trace_id(request)))

    @app.exception_handler(StarletteHTTPException)
    async def handle_http(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        if exc.status_code == 404:
            api = not_found("资源不存在")
        elif exc.status_code == 405:
            api = ApiError(405, 40501, "请求方法不支持", "METHOD_NOT_ALLOWED")
        elif exc.status_code == 413:
            api = ApiError(413, 41301, "请求体过大", "PAYLOAD_TOO_LARGE")
        else:
            api = ApiError(exc.status_code, exc.status_code * 100 + 1, str(exc.detail), "HTTP_ERROR")
        api.status_code = exc.status_code
        logger.warning("http error status=%s detail=%s", exc.status_code, exc.detail)
        return JSONResponse(status_code=exc.status_code, content=_error_body(api, _trace_id(request)))

    @app.exception_handler(Exception)
    async def handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
        trace = _trace_id(request)
        logger.exception("unhandled error type=%s", type(exc).__name__)
        api = ApiError(500, 50001, "服务内部错误", "INTERNAL_ERROR")
        return JSONResponse(status_code=500, content=_error_body(api, trace))

    async def principal_dependency(
        request: Request,
        authorization: str | None = Header(default=None, alias="Authorization"),
    ) -> Principal:
        if settings.auth_mode == "disabled":
            return Principal(subject="auth-disabled", scopes={settings.run_scope})
        if not authorization or not authorization.lower().startswith("bearer "):
            raise unauthorized("缺少内部服务 Bearer 凭证")
        token = authorization.split(" ", 1)[1].strip()
        return verify_internal_token(token, settings, settings.run_scope)

    async def speech_principal_dependency(
        request: Request,
        authorization: str | None = Header(default=None, alias="Authorization"),
    ) -> Principal:
        if settings.auth_mode == "disabled":
            return Principal(subject="auth-disabled", scopes={settings.speech_scope}, task_ids=set())
        if not authorization or not authorization.lower().startswith("bearer "):
            raise unauthorized("缺少内部服务 Bearer 凭证")
        token = authorization.split(" ", 1)[1].strip()
        return verify_internal_token(token, settings, settings.speech_scope)

    async def submit(
        request: Request,
        operation: str,
        body: AIRequestEnvelope,
        idempotency_key: UUID,
        principal: Principal,
    ) -> JSONResponse:
        _assert_task(settings, principal, str(body.task_id))
        with log_context(
            trace=_trace_id(request),
            task=body.task_id,
            user=body.user_id,
            op=operation,
        ):
            logger.info(
                "received idempotency=%s deadline=%s payload=%s",
                idempotency_key,
                body.deadline_at.isoformat(),
                summarize_payload(body.payload),
            )
            record, _created = await service.submit(
                operation,
                body,
                str(idempotency_key),
                _trace_id(request),
            )
            logger.info(
                "submitted run=%s created=%s status=%s",
                record.run_id,
                _created,
                record.status,
            )
        return _result(
            _run_payload(record),
            code=202,
            msg="任务已受理",
            trace_id=_trace_id(request),
        )

    @app.get("/healthz")
    async def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/readyz")
    async def readyz() -> dict[str, Any]:
        return {
            "status": "ready",
            "provider": settings.effective_llm_provider,
            "storage": settings.storage_backend,
        }

    @app.get("/internal/ai/v1/jobs/source-health")
    async def job_source_health(request: Request, principal: Principal = Depends(principal_dependency)) -> JSONResponse:
        from .search.sources.registry import configured_source_health

        return _result({"checkedAt": utcnow().isoformat(),
                        "sources": configured_source_health(settings)}, trace_id=_trace_id(request))

    @app.post("/internal/ai/v1/resumes/parse", status_code=202)
    async def resume_parse(
        request: Request,
        body: AIRequestEnvelope,
        idempotency_key: UUID = Header(alias="Idempotency-Key"),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        return await submit(request, RESUME_PARSE, body, idempotency_key, principal)

    @app.post("/internal/ai/v1/resumes/ocr", status_code=202)
    async def resume_ocr(
        request: Request,
        body: AIRequestEnvelope,
        idempotency_key: UUID = Header(alias="Idempotency-Key"),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        return await submit(request, RESUME_OCR, body, idempotency_key, principal)

    @app.post("/internal/ai/v1/resumes/diagnose", status_code=202)
    async def resume_diagnose(
        request: Request,
        body: AIRequestEnvelope,
        idempotency_key: UUID = Header(alias="Idempotency-Key"),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        return await submit(request, RESUME_DIAGNOSE, body, idempotency_key, principal)

    @app.post("/internal/ai/v1/resumes/optimize", status_code=202)
    async def resume_optimize(
        request: Request,
        body: AIRequestEnvelope,
        idempotency_key: UUID = Header(alias="Idempotency-Key"),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        return await submit(request, RESUME_OPTIMIZE, body, idempotency_key, principal)

    @app.post("/internal/ai/v1/jobs/analyze", status_code=202)
    async def jd_analyze(
        request: Request,
        body: AIRequestEnvelope,
        idempotency_key: UUID = Header(alias="Idempotency-Key"),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        return await submit(request, JD_ANALYZE, body, idempotency_key, principal)

    @app.post("/internal/ai/v1/jobs/match", status_code=202)
    async def job_match(
        request: Request,
        body: AIRequestEnvelope,
        idempotency_key: UUID = Header(alias="Idempotency-Key"),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        return await submit(request, JOB_MATCH, body, idempotency_key, principal)

    @app.post("/internal/ai/v1/jobs/search", status_code=202)
    async def job_search(
        request: Request,
        body: AIRequestEnvelope,
        idempotency_key: UUID = Header(alias="Idempotency-Key"),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        return await submit(request, JOB_SEARCH, body, idempotency_key, principal)

    @app.post("/internal/ai/v1/learning/plans", status_code=202)
    async def learning_plan(
        request: Request,
        body: AIRequestEnvelope,
        idempotency_key: UUID = Header(alias="Idempotency-Key"),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        return await submit(request, LEARNING_PLAN, body, idempotency_key, principal)

    @app.post("/internal/ai/v1/learning/questions", status_code=202)
    async def learning_question(
        request: Request,
        body: AIRequestEnvelope,
        idempotency_key: UUID = Header(alias="Idempotency-Key"),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        return await submit(request, LEARNING_QUESTION, body, idempotency_key, principal)

    @app.post("/internal/ai/v1/learning/evaluate", status_code=202)
    async def learning_evaluate(
        request: Request,
        body: AIRequestEnvelope,
        idempotency_key: UUID = Header(alias="Idempotency-Key"),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        return await submit(request, LEARNING_EVALUATE, body, idempotency_key, principal)

    @app.post("/internal/ai/v1/learning/practice/questions", status_code=202)
    async def learning_practice_questions(
        request: Request,
        body: AIRequestEnvelope,
        idempotency_key: UUID = Header(alias="Idempotency-Key"),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        return await submit(request, LEARNING_PRACTICE_QUESTIONS, body, idempotency_key, principal)

    @app.post("/internal/ai/v1/learning/practice/evaluate", status_code=202)
    async def learning_practice_evaluate(
        request: Request,
        body: AIRequestEnvelope,
        idempotency_key: UUID = Header(alias="Idempotency-Key"),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        return await submit(request, LEARNING_PRACTICE_EVALUATE, body, idempotency_key, principal)

    @app.post("/internal/ai/v1/interviews/turn", status_code=202)
    async def interview_turn(
        request: Request,
        body: AIRequestEnvelope,
        idempotency_key: UUID = Header(alias="Idempotency-Key"),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        return await submit(request, INTERVIEW_TURN, body, idempotency_key, principal)

    @app.post("/internal/ai/v1/interviews/report", status_code=202)
    async def interview_report(
        request: Request,
        body: AIRequestEnvelope,
        idempotency_key: UUID = Header(alias="Idempotency-Key"),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        return await submit(request, INTERVIEW_REPORT, body, idempotency_key, principal)

    @app.post("/internal/ai/v1/growth/analyze", status_code=202)
    async def growth_analyze(
        request: Request,
        body: AIRequestEnvelope,
        idempotency_key: UUID = Header(alias="Idempotency-Key"),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        return await submit(request, GROWTH_ANALYZE, body, idempotency_key, principal)

    @app.post("/internal/ai/v1/projects/recommend", status_code=202)
    async def project_recommend(
        request: Request,
        body: AIRequestEnvelope,
        idempotency_key: UUID = Header(alias="Idempotency-Key"),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        return await submit(request, PROJECT_RECOMMEND, body, idempotency_key, principal)

    @app.post("/internal/ai/v1/projects/plans", status_code=202)
    async def project_plan(
        request: Request,
        body: AIRequestEnvelope,
        idempotency_key: UUID = Header(alias="Idempotency-Key"),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        return await submit(request, PROJECT_PLAN, body, idempotency_key, principal)

    @app.get("/internal/ai/v1/runs/{run_id}")
    async def get_run(
        request: Request,
        run_id: UUID,
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        record = await service.get_run(str(run_id))
        _assert_task(settings, principal, record.task_id)
        with log_context(
            trace=_trace_id(request),
            task=record.task_id,
            run=record.run_id,
            op=record.operation,
        ):
            logger.info("query run status=%s attempt=%s", record.status, record.attempt)
        return _result(_run_payload(record), trace_id=_trace_id(request))

    @app.get("/internal/ai/v1/runs")
    async def get_run_by_task(
        request: Request,
        task_id: UUID = Query(alias="taskId"),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        _assert_task(settings, principal, str(task_id))
        record = await service.get_by_task(str(task_id))
        with log_context(
            trace=_trace_id(request),
            task=record.task_id,
            run=record.run_id,
            op=record.operation,
        ):
            logger.info("query by task status=%s callback=%s", record.status, record.callback_status)
        return _result(_run_payload(record), trace_id=_trace_id(request))

    @app.get("/internal/ai/v1/runs/{run_id}/events/log")
    async def run_events_log(
        request: Request,
        run_id: UUID,
        after: int = Query(default=0, ge=0),
        limit: int = Query(default=200, ge=1, le=500),
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        record = await service.get_run(str(run_id))
        _assert_task(settings, principal, record.task_id)
        events = await repository.events_after(record.run_id, after)
        fresh = [item for item in events if item.expires_at > utcnow()][:limit]
        payload = {
            "runId": record.run_id,
            "taskId": record.task_id,
            "lastSeq": fresh[-1].seq if fresh else after,
            "events": [
                {"seq": item.seq, "type": item.event_type, "payload": item.payload}
                for item in fresh
            ],
        }
        return _result(payload, trace_id=_trace_id(request))

    @app.post("/internal/ai/v1/runs/{run_id}/cancel")
    async def cancel_run(
        request: Request,
        run_id: UUID,
        principal: Principal = Depends(principal_dependency),
    ) -> JSONResponse:
        record = await service.get_run(str(run_id))
        _assert_task(settings, principal, record.task_id)
        with log_context(
            trace=_trace_id(request),
            task=record.task_id,
            run=record.run_id,
            op=record.operation,
        ):
            updated = await service.cancel(str(run_id))
            logger.info("cancel result status=%s", updated.status)
        return _result(_run_payload(updated), trace_id=_trace_id(request))

    async def event_stream(
        record: RunRecord,
        cursor: int,
        trace_id: str,
    ) -> AsyncIterator[str]:
        last = max(0, cursor)
        heartbeat_at = time.monotonic()
        with log_context(
            trace=trace_id,
            task=record.task_id,
            run=record.run_id,
            op=record.operation,
        ):
            logger.info("sse opened cursor=%s", cursor)
            while True:
                events = await repository.events_after(record.run_id, last)
                fresh = [item for item in events if item.expires_at > utcnow()]
                for event in fresh:
                    yield event.as_sse(record.task_id)
                    last = event.seq
                    heartbeat_at = time.monotonic()
                current = await repository.get(record.run_id)
                if current is None:
                    logger.info("sse closed cursor=%s reason=missing", last)
                    return
                if current.terminal and not fresh:
                    logger.info("sse closed cursor=%s reason=terminal", last)
                    return
                if time.monotonic() - heartbeat_at >= settings.heartbeat_seconds:
                    yield ": heartbeat\n\n"
                    heartbeat_at = time.monotonic()
                await asyncio.sleep(settings.event_poll_seconds)

    @app.get("/internal/ai/v1/runs/{run_id}/events")
    async def run_events(
        request: Request,
        run_id: UUID,
        last_event_id_header: int | None = Header(default=None, alias="Last-Event-ID"),
        last_event_id: int | None = Query(default=None, alias="lastEventId"),
        principal: Principal = Depends(principal_dependency),
    ) -> StreamingResponse:
        record = await service.get_run(str(run_id))
        _assert_task(settings, principal, record.task_id)

        cursor = last_event_id_header if last_event_id_header is not None else (last_event_id or 0)
        if cursor < 0:
            raise validation_error("Last-Event-ID 必须为非负整数")
        if cursor > record.last_event_seq:
            raise ApiError(400, 40001, "Last-Event-ID 大于当前最大事件号", "INVALID_CURSOR")
        min_seq, max_seq, total = await repository.event_bounds(record.run_id, utcnow())
        if cursor > 0 and cursor < record.last_event_seq and total == 0:
            raise ApiError(410, 41001, "事件游标已过期，请先查询运行终态", "EVENT_CURSOR_EXPIRED")
        if total > 0 and cursor > 0 and min_seq > cursor + 1:
            raise ApiError(410, 41001, "事件游标已过期，请先查询运行终态", "EVENT_CURSOR_EXPIRED")

        return StreamingResponse(
            event_stream(record, cursor, _trace_id(request)),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "X-Accel-Buffering": "no",
                "Connection": "keep-alive",
            },
        )

    if speech_service is not None:
        app.state.speech = speech_service
        app.include_router(build_speech_router(settings, speech_service, speech_principal_dependency))

    return app
