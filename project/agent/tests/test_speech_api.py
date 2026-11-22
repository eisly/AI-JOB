from __future__ import annotations

import pytest
from httpx import AsyncClient

AUDIO = b"\x1a\x45\xdf\xa3" + b"x" * 4096


@pytest.mark.asyncio
async def test_upload_limit_prevents_provider_call(client_and_app, headers_for, monkeypatch):
    client, app = client_and_app
    app.state.settings.speech_max_bytes = 16
    async def forbidden_call(*args, **kwargs):
        pytest.fail("oversized upload must not reach provider")
    monkeypatch.setattr(app.state.speech, "transcribe", forbidden_call)
    request_id = "88888888-8888-4888-8888-888888888888"
    response = await client.post(
        "/internal/ai/v1/speech/transcribe",
        headers=headers_for(request_id, scope="ai:speech"),
        data={"requestId": request_id, "userId": "u1", "mimeType": "audio/webm", "durationMs": "8000"},
        files={"audio": ("answer.webm", AUDIO, "audio/webm")},
    )
    assert response.status_code == 413


@pytest.mark.asyncio
async def test_transcribe_requires_speech_scope(client_and_app, headers_for) -> None:
    client, _ = client_and_app
    request_id = "11111111-1111-1111-1111-111111111111"
    response = await client.post(
        "/internal/ai/v1/speech/transcribe",
        headers=headers_for(request_id, scope="ai:run"),
        data={"requestId": request_id, "userId": "u1", "mimeType": "audio/webm", "durationMs": "8000"},
        files={"audio": ("answer.webm", AUDIO, "audio/webm")},
    )
    assert response.status_code == 403


@pytest.mark.asyncio
async def test_transcribe_rejects_unknown_request_id(client_and_app, headers_for) -> None:
    client, _ = client_and_app
    response = await client.post(
        "/internal/ai/v1/speech/transcribe",
        headers=headers_for("22222222-2222-2222-2222-222222222222", scope="ai:speech"),
        data={
            "requestId": "33333333-3333-3333-3333-333333333333",
            "userId": "u1",
            "mimeType": "audio/webm",
            "durationMs": "8000",
        },
        files={"audio": ("answer.webm", AUDIO, "audio/webm")},
    )
    assert response.status_code == 403


@pytest.mark.asyncio
async def test_transcribe_success_structure(client_and_app, headers_for) -> None:
    client, _ = client_and_app
    request_id = "44444444-4444-4444-4444-444444444444"
    response = await client.post(
        "/internal/ai/v1/speech/transcribe",
        headers=headers_for(request_id, scope="ai:speech"),
        data={"requestId": request_id, "userId": "u1", "mimeType": "audio/webm", "durationMs": "8000"},
        files={"audio": ("answer.webm", AUDIO, "audio/webm")},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["code"] == 200
    assert body["traceId"]
    assert body["data"]["provider"] == "fake"
    assert body["data"]["text"]


@pytest.mark.asyncio
async def test_synthesize_success_structure(client_and_app, headers_for) -> None:
    client, _ = client_and_app
    request_id = "55555555-5555-5555-5555-555555555555"
    response = await client.post(
        "/internal/ai/v1/speech/synthesize",
        headers=headers_for(request_id, scope="ai:speech"),
        json={"requestId": request_id, "userId": "u1", "text": "请介绍一家你最熟悉的项目", "voice": "fake"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["data"]["mimeType"] == "audio/mpeg"
    assert body["data"]["audioBase64"]
    assert body["data"]["chars"] > 0


@pytest.mark.asyncio
async def test_synthesize_rejects_empty_text(client_and_app, headers_for) -> None:
    client, _ = client_and_app
    request_id = "66666666-6666-6666-6666-666666666666"
    response = await client.post(
        "/internal/ai/v1/speech/synthesize",
        headers=headers_for(request_id, scope="ai:speech"),
        json={"requestId": request_id, "userId": "u1", "text": "   "},
    )
    assert response.status_code == 400
