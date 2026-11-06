from __future__ import annotations

import copy
from datetime import datetime, timedelta, timezone
from uuid import uuid4

from tests.factories import JD_TEXT, jd_envelope, resume_envelope, wait_for_run


async def _submit(client, headers_for, body, key=None, task_id=None):
    task_id = task_id or body["taskId"]
    return await client.post(
        "/internal/ai/v1/jobs/analyze",
        json=body,
        headers={**headers_for(task_id), "Idempotency-Key": key or str(uuid4())},
    )


async def test_health_ready_and_trace_header(client):
    response = await client.get("/healthz", headers={"X-Trace-Id": "trace-abc"})
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
    assert response.headers["X-Trace-Id"] == "trace-abc"

    ready = await client.get("/readyz")
    assert ready.status_code == 200
    assert ready.json()["provider"] == "fake"


async def test_missing_auth_returns_401(client, headers_for):
    task_id = uuid4()
    response = await client.post(
        "/internal/ai/v1/jobs/analyze",
        json=jd_envelope(task_id),
        headers={"Idempotency-Key": str(uuid4())},
    )
    assert response.status_code == 401
    assert response.json()["code"] == 40101


async def test_wrong_scope_and_foreign_task_return_403(client, headers_for):
    task_id = uuid4()
    body = jd_envelope(task_id)
    response = await client.post(
        "/internal/ai/v1/jobs/analyze",
        json=body,
        headers={**headers_for(str(task_id), scope="ai:other"), "Idempotency-Key": str(uuid4())},
    )
    assert response.status_code == 403

    response = await client.post(
        "/internal/ai/v1/jobs/analyze",
        json=body,
        headers={**headers_for(str(uuid4())), "Idempotency-Key": str(uuid4())},
    )
    assert response.status_code == 403
    assert response.json()["code"] == 40301


async def test_idempotency_replay_and_conflict(client, headers_for):
    task_id = uuid4()
    body = jd_envelope(task_id)
    key = str(uuid4())

    first = await _submit(client, headers_for, body, key)
    assert first.status_code == 202
    run_id = first.json()["data"]["runId"]

    replay = await _submit(client, headers_for, body, key)
    assert replay.status_code == 202
    assert replay.json()["data"]["runId"] == run_id

    other = copy.deepcopy(body)
    other["payload"]["jdText"] = JD_TEXT + "补充：熟悉分布式系统。"
    conflict = await _submit(client, headers_for, other, key)
    assert conflict.status_code == 409
    assert conflict.json()["code"] == 40901


async def test_same_task_replay_and_conflict(client, headers_for):
    task_id = uuid4()
    body = jd_envelope(task_id)
    first = await _submit(client, headers_for, body)
    run_id = first.json()["data"]["runId"]

    replay = await _submit(client, headers_for, copy.deepcopy(body))
    assert replay.status_code == 202
    assert replay.json()["data"]["runId"] == run_id

    other = copy.deepcopy(body)
    other["payload"]["jdText"] = JD_TEXT + "补充：具备大型项目经验。"
    conflict = await _submit(client, headers_for, other)
    assert conflict.status_code == 409


async def test_deadline_and_version_and_payload_validation(client, headers_for):
    task_id = uuid4()
    expired = jd_envelope(task_id)
    expired["deadlineAt"] = (datetime.now(timezone.utc) - timedelta(seconds=120)).isoformat()
    response = await _submit(client, headers_for, expired)
    assert response.status_code == 400
    assert "已过期" in response.json()["msg"]

    far = jd_envelope(task_id)
    far["deadlineAt"] = (datetime.now(timezone.utc) + timedelta(seconds=3600)).isoformat()
    response = await _submit(client, headers_for, far)
    assert response.status_code == 400
    assert "不得超过" in response.json()["msg"]

    mismatch = jd_envelope(task_id)
    mismatch["inputVersions"]["jobVersion"] = 2
    response = await _submit(client, headers_for, mismatch)
    assert response.status_code == 400
    assert "不一致" in response.json()["msg"]

    invalid = jd_envelope(task_id)
    invalid["payload"]["jdText"] = "太短"
    response = await _submit(client, headers_for, invalid)
    assert response.status_code == 400
    assert "payload 校验失败" in response.json()["msg"]


async def test_envelope_validation_error_details(client, headers_for):
    response = await client.post(
        "/internal/ai/v1/jobs/analyze",
        json={},
        headers={**headers_for(str(uuid4())), "Idempotency-Key": str(uuid4())},
    )
    assert response.status_code == 400
    body = response.json()
    assert body["code"] == 40001
    assert body["error"]["details"]["fields"]


async def test_queue_full_returns_429(client, app, headers_for):
    app.state.settings.queue_max_size = 0
    response = await _submit(client, headers_for, jd_envelope(uuid4()))
    assert response.status_code == 429
    assert response.json()["code"] == 42901
    assert response.json()["error"]["retryable"] is True


async def test_unknown_path_and_method_not_allowed(client):
    response = await client.get("/internal/ai/v1/unknown")
    assert response.status_code == 404
    assert response.json()["code"] == 40401

    response = await client.get("/internal/ai/v1/jobs/analyze")
    assert response.status_code == 405
    assert response.json()["code"] == 40501


async def test_get_run_by_task_and_not_found(client, headers_for):
    task_id = uuid4()
    accepted = await _submit(client, headers_for, jd_envelope(task_id))
    run_id = accepted.json()["data"]["runId"]

    by_task = await client.get(f"/internal/ai/v1/runs?taskId={task_id}", headers=headers_for(str(task_id)))
    assert by_task.status_code == 200
    assert by_task.json()["data"]["runId"] == run_id

    missing_task = uuid4()
    response = await client.get(
        f"/internal/ai/v1/runs?taskId={missing_task}", headers=headers_for(str(missing_task))
    )
    assert response.status_code == 404

    response = await client.get(f"/internal/ai/v1/runs/{uuid4()}", headers=headers_for(str(task_id)))
    assert response.status_code == 404


async def test_cancel_run_reaches_cancelled_and_is_idempotent(client, fake_llm, headers_for):
    fake_llm.delay_seconds = 0.3
    task_id = uuid4()
    accepted = await _submit(client, headers_for, jd_envelope(task_id))
    run_id = accepted.json()["data"]["runId"]

    response = await client.post(f"/internal/ai/v1/runs/{run_id}/cancel", headers=headers_for(str(task_id)))
    assert response.status_code == 200
    data = await wait_for_run(client, headers_for, run_id, str(task_id))
    assert data["status"] == "CANCELLED"
    assert data["error"]["code"] == "CANCELLED"

    again = await client.post(f"/internal/ai/v1/runs/{run_id}/cancel", headers=headers_for(str(task_id)))
    assert again.json()["data"]["status"] == "CANCELLED"


async def test_provider_failure_marks_run_failed(client, fake_llm, headers_for):
    fake_llm.fail_times = 5
    task_id = uuid4()
    accepted = await _submit(client, headers_for, jd_envelope(task_id))
    data = await wait_for_run(client, headers_for, accepted.json()["data"]["runId"], str(task_id))
    assert data["status"] == "FAILED"
    assert data["error"]["code"] == "MODEL_PROVIDER_ERROR"
    assert data["error"]["retryable"] is True


async def test_invalid_output_repair_then_success(client, fake_llm, headers_for):
    fake_llm.invalid_json_times = 1
    task_id = uuid4()
    accepted = await _submit(client, headers_for, jd_envelope(task_id))
    data = await wait_for_run(client, headers_for, accepted.json()["data"]["runId"], str(task_id))
    assert data["status"] == "SUCCEEDED"
    assert len(fake_llm.calls) == 2
    assert "上一次输出未通过" in fake_llm.calls[1].user_prompt


async def test_invalid_output_twice_marks_failed(client, fake_llm, headers_for):
    fake_llm.invalid_json_times = 2
    task_id = uuid4()
    accepted = await _submit(client, headers_for, jd_envelope(task_id))
    data = await wait_for_run(client, headers_for, accepted.json()["data"]["runId"], str(task_id))
    assert data["status"] == "FAILED"
    assert data["error"]["code"] == "MODEL_OUTPUT_INVALID"


async def test_run_events_sse_stream_and_cursor_validation(client, headers_for):
    task_id = uuid4()
    accepted = await _submit(client, headers_for, jd_envelope(task_id))
    run_id = accepted.json()["data"]["runId"]
    await wait_for_run(client, headers_for, run_id, str(task_id))

    async with client.stream(
        "GET", f"/internal/ai/v1/runs/{run_id}/events", headers=headers_for(str(task_id))
    ) as response:
        assert response.status_code == 200
        assert response.headers["content-type"].startswith("text/event-stream")
        body = ""
        async for chunk in response.aiter_text():
            body += chunk

    assert "event: accepted" in body
    assert "event: stage" in body
    assert "event: result_ready" in body
    assert "id: 1" in body

    response = await client.get(
        f"/internal/ai/v1/runs/{run_id}/events?lastEventId=-1", headers=headers_for(str(task_id))
    )
    assert response.status_code == 400

    response = await client.get(
        f"/internal/ai/v1/runs/{run_id}/events?lastEventId=999", headers=headers_for(str(task_id))
    )
    assert response.status_code == 400
    assert response.json()["error"]["key"] == "INVALID_CURSOR"


async def test_resume_download_url_validation_happens_before_accept(client, headers_for):
    task_id = uuid4()
    body = resume_envelope(task_id)
    body["payload"]["document"]["text"] = None
    body["payload"]["document"]["downloadUrl"] = "http://files.example.invalid/cv.txt"
    response = await client.post(
        "/internal/ai/v1/resumes/parse",
        json=body,
        headers={**headers_for(str(task_id)), "Idempotency-Key": str(uuid4())},
    )
    assert response.status_code == 400
    assert "协议不在白名单" in response.json()["msg"]