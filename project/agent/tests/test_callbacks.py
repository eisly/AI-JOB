from __future__ import annotations

import asyncio

import httpx
import pytest

from app.callbacks import (
    CallbackDispatcher,
    CallbackResponse,
    HttpxCallbackTransport,
    callback_error_from,
)
from app.config import Settings
from app.errors import HandlerError
from app.storage import MemoryRunRepository

from tests.factories import make_run_record


def _settings(**overrides) -> Settings:
    base = dict(
        app_env="test",
        jwt_secret="test-internal-secret-0123456789abcdef",
        storage_backend="memory",
        callback_enabled=True,
        callback_base_url="http://business.test",
        callback_scan_seconds=0.01,
        callback_backoff_base_seconds=0.01,
        callback_backoff_max_seconds=0.02,
        callback_max_attempts=3,
        llm_provider="fake",
    )
    base.update(overrides)
    return Settings(**base)


class FakeTransport:
    def __init__(self, response: CallbackResponse):
        self.response = response
        self.calls: list[dict] = []
        self.closed = False

    async def post(self, url, json, headers):
        self.calls.append({"url": url, "json": json, "headers": headers})
        return self.response

    async def close(self):
        self.closed = True


def _dispatcher(settings, repo, transport):
    return CallbackDispatcher(repo, settings, transport)


async def _prepare(repo, record):
    await repo.create(record)
    return record


async def test_deliver_success_marks_delivered_and_signs_request():
    settings = _settings()
    repo = MemoryRunRepository(settings)
    record = await _prepare(repo, make_run_record(status="SUCCEEDED", result={"ok": 1}))
    transport = FakeTransport(CallbackResponse(status_code=200, body={"data": {"accepted": True}}))
    dispatcher = _dispatcher(settings, repo, transport)

    await dispatcher._deliver(record)

    updated = await repo.get(record.run_id)
    assert updated.callback_status == "DELIVERED"
    assert updated.callback_attempts == 1
    assert updated.callback_error is None
    call = transport.calls[0]
    assert call["url"] == f"http://business.test/internal/business/v1/ai-tasks/{record.task_id}/result"
    assert call["headers"]["Authorization"].startswith("Bearer ")
    assert call["headers"]["X-Trace-Id"] == "trace-test"
    assert call["json"]["status"] == "SUCCEEDED"
    assert call["json"]["result"] == {"ok": 1}


async def test_deliver_records_note_when_java_rejects_late_result():
    settings = _settings()
    repo = MemoryRunRepository(settings)
    record = await _prepare(repo, make_run_record(status="FAILED", error={"code": "X"}))
    transport = FakeTransport(CallbackResponse(status_code=200, body={"data": {"accepted": False}}))
    await _dispatcher(settings, repo, transport)._deliver(record)

    updated = await repo.get(record.run_id)
    assert updated.callback_status == "DELIVERED"
    assert "accepted=false" in updated.callback_error
    assert transport.calls[0]["json"]["result"] is None
    assert transport.calls[0]["json"]["error"] == {"code": "X"}


async def test_deliver_permanent_error_marks_rejected_without_retry():
    settings = _settings()
    repo = MemoryRunRepository(settings)
    record = await _prepare(repo, make_run_record(status="SUCCEEDED"))
    transport = FakeTransport(CallbackResponse(status_code=400))
    await _dispatcher(settings, repo, transport)._deliver(record)

    updated = await repo.get(record.run_id)
    assert updated.callback_status == "REJECTED"
    assert updated.callback_next_at is None
    assert "HTTP 400" in updated.callback_error


async def test_deliver_retryable_error_schedules_backoff():
    settings = _settings()
    repo = MemoryRunRepository(settings)
    record = await _prepare(repo, make_run_record(status="SUCCEEDED"))
    transport = FakeTransport(CallbackResponse(status_code=500))
    await _dispatcher(settings, repo, transport)._deliver(record)

    updated = await repo.get(record.run_id)
    assert updated.callback_status == "PENDING"
    assert updated.callback_attempts == 1
    assert updated.callback_next_at is not None
    assert "HTTP 500" in updated.callback_error


async def test_deliver_network_error_uses_error_text():
    settings = _settings()
    repo = MemoryRunRepository(settings)
    record = await _prepare(repo, make_run_record(status="SUCCEEDED"))
    transport = FakeTransport(CallbackResponse(status_code=0, error="ConnectError"))
    await _dispatcher(settings, repo, transport)._deliver(record)

    updated = await repo.get(record.run_id)
    assert updated.callback_status == "PENDING"
    assert updated.callback_error == "ConnectError"


async def test_deliver_exhausts_attempts():
    settings = _settings()
    repo = MemoryRunRepository(settings)
    record = await _prepare(
        repo,
        make_run_record(status="SUCCEEDED", callback_attempts=settings.callback_max_attempts - 1),
    )
    transport = FakeTransport(CallbackResponse(status_code=503))
    await _dispatcher(settings, repo, transport)._deliver(record)

    updated = await repo.get(record.run_id)
    assert updated.callback_status == "REJECTED"
    assert updated.callback_attempts == settings.callback_max_attempts
    assert "重试耗尽" in updated.callback_error


async def test_start_is_noop_when_disabled_and_stop_closes_transport():
    settings = _settings(callback_enabled=False)
    repo = MemoryRunRepository(settings)
    transport = FakeTransport(CallbackResponse(status_code=200))
    dispatcher = _dispatcher(settings, repo, transport)
    await dispatcher.start()
    assert dispatcher._task is None
    await dispatcher.stop()
    assert transport.closed is True


async def test_loop_delivers_due_callbacks_and_stops_cleanly():
    settings = _settings()
    repo = MemoryRunRepository(settings)
    record = await _prepare(repo, make_run_record(status="SUCCEEDED"))
    transport = FakeTransport(CallbackResponse(status_code=200, body={}))
    dispatcher = _dispatcher(settings, repo, transport)

    await dispatcher.start()
    dispatcher.notify()
    for _ in range(100):
        if transport.calls:
            break
        await asyncio.sleep(0.01)
    await dispatcher.stop()

    assert transport.calls, "dispatcher loop should deliver the due callback"
    updated = await repo.get(record.run_id)
    assert updated.callback_status == "DELIVERED"


def test_callback_error_from_truncates_message():
    error = HandlerError("MODEL_OUTPUT_INVALID", "x" * 1200, retryable=True)
    body = callback_error_from(error)
    assert body["code"] == "MODEL_OUTPUT_INVALID"
    assert body["retryable"] is True
    assert len(body["message"]) == 1000


async def test_httpx_transport_success_json_and_network_error(monkeypatch):
    transport = HttpxCallbackTransport(_settings())

    class _Response:
        status_code = 200

        def json(self):
            return {"code": 200}

    async def fake_post(url, json, headers):
        return _Response()

    monkeypatch.setattr(transport._client, "post", fake_post)
    response = await transport.post("http://business.test/x", {}, {})
    assert response.status_code == 200
    assert response.body == {"code": 200}

    async def failing_post(url, json, headers):
        raise httpx.ConnectError("down")

    monkeypatch.setattr(transport._client, "post", failing_post)
    response = await transport.post("http://business.test/x", {}, {})
    assert response.status_code == 0
    assert response.error == "ConnectError"
    await transport.close()


async def test_httpx_transport_tolerates_non_json_body(monkeypatch):
    transport = HttpxCallbackTransport(_settings())

    class _Response:
        status_code = 204

        def json(self):
            raise ValueError("no json")

    async def fake_post(url, json, headers):
        return _Response()

    monkeypatch.setattr(transport._client, "post", fake_post)
    response = await transport.post("http://business.test/x", {}, {})
    assert response.status_code == 204
    assert response.body is None
    await transport.close()