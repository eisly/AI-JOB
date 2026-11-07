from __future__ import annotations

import asyncio
import contextlib
import logging
from datetime import datetime, timedelta
from typing import Any
from uuid import UUID, uuid4

from .callbacks import CallbackDispatcher
from .config import Settings
from .errors import ApiError, HandlerError, conflict, not_found, rate_limited, validation_error
from .files import FileFetcher
from .graphs import CareerOrchestrator
from .handlers import ExecutionContext, get_handler
from .llm import LLMClient
from .logging_config import bind_log_context, reset_log_context
from .operations import PAYLOAD_MODELS, validate_version_binding
from .schemas import AIRequestEnvelope
from .storage import (
    ACTIVE_STATUSES,
    RunRecord,
    RunRepository,
    request_digest,
    to_iso,
    utcnow,
)

logger = logging.getLogger("agent.runner")


class RunService:
    def __init__(
        self,
        settings: Settings,
        repository: RunRepository,
        llm: LLMClient,
        callbacks: CallbackDispatcher,
        files: FileFetcher | None = None,
    ) -> None:
        self._settings = settings
        self._repo = repository
        self._llm = llm
        self._callbacks = callbacks
        self._files = files or FileFetcher(settings)
        self._orchestrator = CareerOrchestrator()
        self._queue: asyncio.Queue[str] = asyncio.Queue()
        self._workers: list[asyncio.Task[None]] = []
        self._cancel_events: dict[str, asyncio.Event] = {}
        self._started = False
        self._stopping = False

    async def start(self) -> None:
        if self._started:
            return
        await self._repo.initialize()
        await self._recover()
        await self._callbacks.start()
        for index in range(self._settings.worker_count):
            self._workers.append(
                asyncio.create_task(self._worker_loop(index), name=f"agent-worker-{index}")
            )
        self._started = True

    async def stop(self) -> None:
        self._stopping = True
        for task in self._workers:
            task.cancel()
        for task in self._workers:
            with contextlib.suppress(asyncio.CancelledError):
                await task
        self._workers.clear()
        await self._callbacks.stop()
        await self._repo.close()
        self._started = False

    async def _recover(self) -> None:
        records = await self._repo.recoverable()
        for record in records:
            if record.status == "RUNNING":
                requeued = await self._repo.requeue(record.run_id, utcnow())
                if requeued is not None:
                    logger.info(
                        "requeued interrupted run=%s task=%s operation=%s attempt=%s",
                        record.run_id,
                        record.task_id,
                        record.operation,
                        requeued.attempt,
                    )
                    self._queue.put_nowait(record.run_id)
            elif record.status == "QUEUED":
                logger.info("requeued queued run=%s task=%s operation=%s", record.run_id, record.task_id, record.operation)
                self._queue.put_nowait(record.run_id)
            elif record.status == "CANCEL_REQUESTED":
                await self._finalize_cancelled(record.run_id)
        if records:
            logger.info("recovered %s active runs", len(records))

    # ------------------------------------------------------------------
    # Submission and queries
    # ------------------------------------------------------------------

    async def submit(
        self,
        operation: str,
        envelope: AIRequestEnvelope,
        idempotency_key: str,
        trace_id: str,
    ) -> tuple[RunRecord, bool]:
        payload_model = PAYLOAD_MODELS[operation]
        try:
            payload = payload_model.model_validate(envelope.payload)
        except Exception as exc:
            raise validation_error(f"payload 校验失败: {exc}") from exc
        validate_version_binding(operation, envelope.input_versions, payload)

        if operation in {"RESUME_PARSE", "RESUME_DIAGNOSE", "RESUME_OPTIMIZE"}:
            self._files.validate(payload.document)

        now = utcnow()
        deadline = envelope.deadline_at
        max_deadline = now + timedelta(seconds=self._settings.deadline_max_seconds)
        if deadline <= now - timedelta(seconds=self._settings.deadline_clock_skew_seconds):
            raise validation_error("deadlineAt 已过期")
        if deadline > max_deadline + timedelta(seconds=self._settings.deadline_clock_skew_seconds):
            raise validation_error(
                f"deadlineAt 不得超过接收后 {self._settings.deadline_max_seconds} 秒"
            )

        payload_json = payload.model_dump(by_alias=True, mode="json")
        digest_body = {
            "taskId": str(envelope.task_id),
            "userId": str(envelope.user_id),
            "operation": operation,
            "inputVersions": envelope.input_versions,
            "deadlineAt": to_iso(envelope.deadline_at),
            "payload": payload_json,
        }
        digest = request_digest(digest_body)

        existing = await self._repo.get_by_idempotency(idempotency_key)
        if existing is not None:
            if existing.request_digest != digest:
                raise conflict("Idempotency-Key 已被不同请求体使用")
            return existing, False

        by_task = await self._repo.get_by_task(str(envelope.task_id))
        if by_task is not None:
            if by_task.operation != operation or by_task.request_digest != digest:
                raise conflict("taskId 已绑定其他 operation 或不同请求体")
            return by_task, False

        if self._queue.qsize() >= self._settings.queue_max_size:
            raise rate_limited("Agent 运行队列已满，请稍后重试")

        record = RunRecord(
            run_id=str(uuid4()),
            task_id=str(envelope.task_id),
            user_id=str(envelope.user_id),
            operation=operation,
            idempotency_key=idempotency_key,
            request_digest=digest,
            input_versions=dict(envelope.input_versions),
            payload=payload_json,
            status="QUEUED",
            attempt=1,
            result=None,
            error=None,
            model_meta=None,
            last_event_seq=0,
            callback_status="PENDING",
            callback_attempts=0,
            callback_next_at=None,
            callback_error=None,
            deadline_at=envelope.deadline_at,
            created_at=now,
            updated_at=now,
            finished_at=None,
            purge_at=now + timedelta(days=self._settings.run_retention_days),
            trace_id=trace_id,
        )

        try:
            record = await self._repo.create(record)
        except Exception:
            # A concurrent duplicate submission may have won the unique index.
            replay = await self._repo.get_by_idempotency(idempotency_key)
            if replay is None:
                replay = await self._repo.get_by_task(str(envelope.task_id))
            if replay is None:
                raise
            if replay.request_digest != digest:
                raise conflict("并发请求的 taskId 或幂等键已绑定不同请求体")
            return replay, False

        await self._emit(record.run_id, "accepted", {}, now)
        self._queue.put_nowait(record.run_id)
        logger.info("accepted run=%s task=%s operation=%s", record.run_id, record.task_id, operation)
        return record, True

    async def get_run(self, run_id: str) -> RunRecord:
        record = await self._repo.get(run_id)
        if record is None:
            raise not_found("Agent 运行不存在")
        return record

    async def get_by_task(self, task_id: str) -> RunRecord:
        record = await self._repo.get_by_task(task_id)
        if record is None:
            raise not_found("未找到该 taskId 对应的 Agent 运行")
        return record

    async def cancel(self, run_id: str) -> RunRecord:
        record = await self._repo.get(run_id)
        if record is None:
            raise not_found("Agent 运行不存在")
        if record.terminal:
            return record
        updated = await self._repo.request_cancel(run_id, utcnow())
        if updated is None:
            raise not_found("Agent 运行不存在")
        event = self._cancel_events.setdefault(run_id, asyncio.Event())
        event.set()
        if updated.status == "CANCEL_REQUESTED":
            self._queue.put_nowait(run_id)
        logger.info("cancel requested run=%s status=%s", run_id, updated.status)
        return updated

    async def wait_for_terminal(self, run_id: str, timeout: float = 10.0) -> RunRecord:
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        while loop.time() < deadline:
            record = await self._repo.get(run_id)
            if record is None:
                raise not_found("Agent 运行不存在")
            if record.terminal:
                return record
            await asyncio.sleep(0.02)
        raise TimeoutError(f"run {run_id} did not reach a terminal state in {timeout}s")

    # ------------------------------------------------------------------
    # Worker
    # ------------------------------------------------------------------

    async def _worker_loop(self, index: int) -> None:
        while not self._stopping:
            run_id = await self._queue.get()
            try:
                await self._execute(run_id)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.error(
                    "worker %s failed run=%s error=%s",
                    index,
                    run_id,
                    type(exc).__name__,
                    exc_info=True,
                )
                await self._fail_unknown(run_id, exc)
            finally:
                self._queue.task_done()

    async def _execute(self, run_id: str) -> None:
        record = await self._repo.get(run_id)
        if record is None or record.terminal:
            return
        token = bind_log_context(
            trace=record.trace_id,
            task=record.task_id,
            run=run_id,
            op=record.operation,
            user=record.user_id,
        )
        try:
            await self._execute_record(run_id, record)
        finally:
            reset_log_context(token)

    async def _execute_record(self, run_id: str, record: RunRecord) -> None:
        if record.status == "CANCEL_REQUESTED":
            await self._finalize_cancelled(run_id)
            return
        if record.status != "QUEUED":
            logger.debug("skip run status=%s", record.status)
            return

        now = utcnow()
        if now >= record.deadline_at:
            await self._finish_failed(
                run_id,
                "DEADLINE_EXCEEDED",
                "任务在开始执行前已超过截止时间",
                retryable=False,
            )
            return

        claimed = await self._repo.try_claim(run_id, now)
        if claimed is None:
            latest = await self._repo.get(run_id)
            if latest is not None and latest.status == "CANCEL_REQUESTED":
                await self._finalize_cancelled(run_id)
            return

        logger.info("execute start attempt=%s deadline=%s", claimed.attempt, claimed.deadline_at.isoformat())
        await self._emit(run_id, "stage", {"name": "started", "progress": 5}, now)
        handler = get_handler(claimed.operation)
        ctx = ExecutionContext(
            run=claimed,
            payload=getattr(handler, "payload_model").model_validate(claimed.payload),
            settings=self._settings,
            llm=self._llm,
            files=self._files,
            emit_stage=lambda name, progress: self._emit(
                run_id, "stage", {"name": name, "progress": progress}, utcnow()
            ),
        )
        cancel_event = self._cancel_events.setdefault(run_id, asyncio.Event())
        execution = asyncio.create_task(self._orchestrator.execute(ctx), name=f"run-{run_id}")
        cancellation = asyncio.create_task(cancel_event.wait(), name=f"cancel-{run_id}")
        remaining = max(0.05, (claimed.deadline_at - utcnow()).total_seconds())

        done, _ = await asyncio.wait(
            {execution, cancellation},
            timeout=remaining,
            return_when=asyncio.FIRST_COMPLETED,
        )

        if cancellation in done and not execution.done():
            execution.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await execution
            await self._finalize_cancelled(run_id)
            return

        cancellation.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await cancellation

        if execution not in done:
            execution.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await execution
            await self._finish_failed(
                run_id,
                "DEADLINE_EXCEEDED",
                "模型或处理步骤超过任务截止时间，迟到结果已丢弃",
                retryable=False,
            )
            return

        try:
            result, meta = execution.result()
        except HandlerError as exc:
            await self._finish_failed(run_id, exc.code, exc.message, retryable=exc.retryable)
            return
        except ApiError as exc:
            await self._finish_failed(run_id, exc.key, exc.message, retryable=exc.retryable)
            return
        except asyncio.CancelledError:
            await self._finalize_cancelled(run_id)
            return
        except Exception as exc:
            logger.error("run=%s handler failed error=%s", run_id, type(exc).__name__, exc_info=True)
            await self._finish_failed(
                run_id,
                "INTERNAL_ERROR",
                "Agent 内部处理失败",
                retryable=True,
            )
            return

        if claimed.operation == "INTERVIEW_TURN":
            question = result.get("question")
            if isinstance(question, dict) and question.get("content"):
                await self._emit_delta(run_id, str(question["content"]))

        finished = await self._repo.finish(run_id, "SUCCEEDED", result, None, meta, utcnow())
        if finished is None:
            latest = await self._repo.get(run_id)
            if latest is not None and latest.status == "CANCEL_REQUESTED":
                await self._finalize_cancelled(run_id)
            return
        if finished.status != "SUCCEEDED":
            # Cancellation won the race; the successful result is intentionally dropped.
            return

        await self._emit(run_id, "result_ready", {"operation": claimed.operation}, utcnow())
        self._callbacks.notify()
        self._cancel_events.pop(run_id, None)
        logger.info(
            "succeeded attempt=%s model=%s latency_ms=%s tokens=%s/%s",
            finished.attempt,
            meta.get("model"),
            meta.get("latencyMs"),
            meta.get("inputTokens"),
            meta.get("outputTokens"),
        )

    async def _finalize_cancelled(self, run_id: str) -> None:
        record = await self._repo.get(run_id)
        if record is None or record.terminal:
            self._cancel_events.pop(run_id, None)
            return
        if record.status not in {"CANCEL_REQUESTED", "RUNNING", "QUEUED"}:
            return
        error = {"code": "CANCELLED", "message": "任务已取消，迟到结果不再接纳", "retryable": False}
        finished = await self._repo.finish(run_id, "CANCELLED", None, error, None, utcnow())
        if finished is not None:
            await self._emit(run_id, "cancelled", {}, utcnow())
            self._callbacks.notify()
            logger.info("cancelled")
        self._cancel_events.pop(run_id, None)

    async def _finish_failed(self, run_id: str, code: str, message: str, *, retryable: bool) -> None:
        error = {"code": code, "message": message[:1000], "retryable": retryable}
        finished = await self._repo.finish(run_id, "FAILED", None, error, None, utcnow())
        if finished is None:
            latest = await self._repo.get(run_id)
            if latest is not None and latest.status == "CANCEL_REQUESTED":
                await self._finalize_cancelled(run_id)
            return
        if finished.status != "FAILED":
            return
        await self._emit(run_id, "error", error, utcnow())
        self._callbacks.notify()
        self._cancel_events.pop(run_id, None)
        logger.warning("failed code=%s retryable=%s message=%s", code, retryable, message)

    async def _fail_unknown(self, run_id: str, exc: Exception) -> None:
        record = await self._repo.get(run_id)
        if record is None or record.terminal:
            return
        await self._finish_failed(run_id, "INTERNAL_ERROR", "Agent Worker 异常", retryable=True)

    async def _emit(
        self,
        run_id: str,
        event_type: str,
        payload: dict[str, Any],
        now: datetime,
    ) -> None:
        logger.debug("emit event=%s payload=%s", event_type, payload)
        expires = now + timedelta(hours=self._settings.event_retention_hours)
        await self._repo.add_event(run_id, event_type, payload, now, expires)

    async def _emit_delta(self, run_id: str, text: str) -> None:
        # P0 uses a non-streaming provider call; question text is chunked only
        # for the Java-facing delta contract and never used as business data.
        for start in range(0, len(text), 40):
            await self._emit(run_id, "delta", {"text": text[start : start + 40]}, utcnow())