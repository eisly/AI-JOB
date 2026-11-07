from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass
from datetime import timedelta
from typing import Any, Protocol

import httpx

from .config import Settings
from .errors import HandlerError
from .logging_config import log_context
from .security import create_callback_token
from .storage import RunRecord, RunRepository, utcnow

logger = logging.getLogger("agent.callback")

RETRYABLE_STATUS = {408, 425, 429, 500, 502, 503, 504}
PERMANENT_STATUS = {400, 401, 403, 404, 409, 413, 422}


@dataclass
class CallbackResponse:
    status_code: int
    body: Any = None
    error: str | None = None


class CallbackTransport(Protocol):
    async def post(self, url: str, json: dict[str, Any], headers: dict[str, str]) -> CallbackResponse: ...


class HttpxCallbackTransport:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(settings.callback_timeout_seconds),
            follow_redirects=False,
            trust_env=False,
        )

    async def post(self, url: str, json: dict[str, Any], headers: dict[str, str]) -> CallbackResponse:
        try:
            response = await self._client.post(url, json=json, headers=headers)
        except httpx.HTTPError as exc:
            logger.warning("callback transport error url=%s error=%s", url, type(exc).__name__)
            return CallbackResponse(status_code=0, error=f"{type(exc).__name__}")
        try:
            body = response.json()
        except ValueError:
            body = None
        return CallbackResponse(status_code=response.status_code, body=body)

    async def close(self) -> None:
        await self._client.aclose()


class CallbackDispatcher:
    def __init__(
        self,
        repository: RunRepository,
        settings: Settings,
        transport: CallbackTransport | None = None,
    ) -> None:
        self._repo = repository
        self._settings = settings
        self._transport = transport or HttpxCallbackTransport(settings)
        self._task: asyncio.Task[None] | None = None
        self._wake = asyncio.Event()

    async def start(self) -> None:
        if not self._settings.callback_enabled:
            logger.warning("callback disabled; terminal runs stay PENDING")
            return
        logger.info(
            "callback dispatcher started base_url=%s max_attempts=%s",
            self._settings.callback_base_url,
            self._settings.callback_max_attempts,
        )
        self._task = asyncio.create_task(self._loop(), name="agent-callback-dispatcher")

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
        close = getattr(self._transport, "close", None)
        if close is not None:
            await close()

    def notify(self) -> None:
        self._wake.set()

    async def _loop(self) -> None:
        while True:
            try:
                await self._process_due()
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # keep the dispatcher alive
                logger.error("callback scan failed: %s", type(exc).__name__)
            self._wake.clear()
            try:
                await asyncio.wait_for(self._wake.wait(), timeout=self._settings.callback_scan_seconds)
            except asyncio.TimeoutError:
                pass

    async def _process_due(self) -> None:
        now = utcnow()
        due = await self._repo.due_callbacks(now)
        for run in due:
            await self._deliver(run)

    async def _deliver(self, run: RunRecord) -> None:
        now = utcnow()
        attempts = run.callback_attempts + 1
        url = self._settings.callback_base_url.rstrip("/") + self._settings.callback_path_template.format(
            task_id=run.task_id
        )
        headers = {
            "Authorization": f"Bearer {create_callback_token(self._settings, run.task_id)}",
            "Content-Type": "application/json",
            "X-Trace-Id": run.trace_id or run.run_id,
        }
        with log_context(task=run.task_id, run=run.run_id, op=run.operation):
            logger.info("callback send url=%s attempts=%s", url, attempts)
            started = time.monotonic()
            response = await self._transport.post(url, run.callback_body(), headers)
            elapsed = int((time.monotonic() - started) * 1000)

            if 200 <= response.status_code < 300:
                accepted = True
                if isinstance(response.body, dict):
                    data = response.body.get("data")
                    if isinstance(data, dict) and data.get("accepted") is False:
                        accepted = False
                note = None if accepted else "Java 返回 accepted=false（迟到结果不接纳）"
                await self._repo.update_callback(run.run_id, "DELIVERED", attempts, None, note)
                logger.info(
                    "callback delivered status=%s accepted=%s attempts=%s duration_ms=%s",
                    response.status_code,
                    accepted,
                    attempts,
                    elapsed,
                )
                return

            if response.status_code in PERMANENT_STATUS:
                error = f"HTTP {response.status_code}: 回调被 Java 拒绝，不再重试"
                await self._repo.update_callback(run.run_id, "REJECTED", attempts, None, error)
                logger.error("callback rejected status=%s duration_ms=%s", response.status_code, elapsed)
                return

            if attempts >= self._settings.callback_max_attempts:
                error = f"HTTP {response.status_code or 'network'} 回调重试耗尽"
                await self._repo.update_callback(run.run_id, "REJECTED", attempts, None, error)
                logger.error("callback exhausted attempts=%s last_status=%s", attempts, response.status_code)
                return

            delay = min(
                self._settings.callback_backoff_max_seconds,
                self._settings.callback_backoff_base_seconds * (2 ** (attempts - 1)),
            )
            next_at = now + timedelta(seconds=delay)
            error = f"HTTP {response.status_code}" if response.status_code else (response.error or "network error")
            await self._repo.update_callback(run.run_id, "PENDING", attempts, next_at, error)
            logger.warning(
                "callback retry scheduled attempts=%s delay=%ss status=%s error=%s",
                attempts,
                delay,
                response.status_code,
                error,
            )


def callback_error_from(exc: HandlerError) -> dict[str, Any]:
    return {"code": exc.code, "message": exc.message[:1000], "retryable": exc.retryable}