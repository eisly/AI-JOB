from __future__ import annotations

import base64
import json
from datetime import datetime, timedelta, timezone

import pytest
import pytest_asyncio

from app.config import Settings
from app.storage import (
    JsonCodec,
    MemoryRunRepository,
    SqliteRunRepository,
    build_repository,
    canonical_json,
    from_iso,
    request_digest,
    to_iso,
    utcnow,
)

from tests.factories import make_run_record


@pytest_asyncio.fixture
async def repo(settings):
    repository = build_repository(settings)
    await repository.initialize()
    yield repository
    await repository.close()


def test_time_and_digest_helpers():
    naive = datetime(2026, 1, 1, 8, 0, 0)
    assert to_iso(naive).endswith("Z")
    aware = datetime(2026, 1, 1, tzinfo=timezone.utc)
    assert from_iso(to_iso(aware)) == aware
    assert from_iso(None) is None
    assert canonical_json({"b": 1, "a": 2}) == '{"a":2,"b":1}'
    assert request_digest({"a": 1, "b": 2}) == request_digest({"b": 2, "a": 1})


def test_json_codec_plaintext_roundtrip():
    codec = JsonCodec(None)
    assert codec.encode({"a": 1}) == '{"a":1}'
    assert codec.decode('{"a": 1}') == {"a": 1}
    assert codec.decode(None) is None


def test_json_codec_rejects_invalid_key():
    with pytest.raises(Exception):
        JsonCodec("not-base64!!!")
    with pytest.raises(ValueError, match="16/24/32 bytes"):
        JsonCodec(base64.b64encode(b"12345").decode("ascii"))


def test_json_codec_encrypted_roundtrip_and_missing_key_error():
    key = base64.b64encode(b"0" * 32).decode("ascii")
    codec = JsonCodec(key)
    sealed = codec.encode({"secret": "简历"})
    assert sealed.startswith(JsonCodec.PREFIX)
    assert "简历" not in sealed
    assert codec.decode(sealed) == {"secret": "简历"}
    with pytest.raises(RuntimeError, match="AGENT_DATA_ENCRYPTION_KEY"):
        JsonCodec(None).decode(sealed)


def test_run_record_callback_body_and_event_sse():
    from app.storage import RunEvent

    record = make_run_record(status="SUCCEEDED", result={"a": 1})
    body = record.callback_body()
    assert body["status"] == "SUCCEEDED"
    assert body["result"] == {"a": 1}
    assert body["error"] is None

    running = make_run_record(status="RUNNING")
    assert running.callback_body()["status"] == "FAILED"

    event = RunEvent(
        event_id="e1",
        run_id=record.run_id,
        seq=3,
        event_type="stage",
        payload={"name": "started"},
        created_at=utcnow(),
        expires_at=utcnow(),
    )
    sse = event.as_sse(record.task_id)
    assert sse.startswith("id: 3\nevent: stage\n")
    assert json.loads(sse.split("data: ", 1)[1])["taskId"] == record.task_id
    assert record.as_vo().operation == "JD_ANALYZE"


async def test_create_get_and_duplicate_guards(repo):
    record = make_run_record()
    await repo.create(record)
    assert (await repo.get(record.run_id)).task_id == record.task_id
    assert (await repo.get_by_task(record.task_id)).run_id == record.run_id
    assert (await repo.get_by_idempotency(record.idempotency_key)).run_id == record.run_id

    with pytest.raises(Exception):
        await repo.create(record)
    with pytest.raises(Exception):
        await repo.create(make_run_record(task_id=record.task_id, operation="JOB_MATCH"))
    with pytest.raises(Exception):
        await repo.create(make_run_record(idempotency_key=record.idempotency_key))


async def test_try_claim_requeue_and_terminal_guards(repo):
    record = make_run_record()
    await repo.create(record)

    claimed = await repo.try_claim(record.run_id, utcnow())
    assert claimed.status == "RUNNING"
    assert await repo.try_claim(record.run_id, utcnow()) is None

    requeued = await repo.requeue(record.run_id, utcnow())
    assert requeued.status == "QUEUED"
    assert requeued.attempt == 2
    assert await repo.requeue(record.run_id, utcnow()) is None

    with pytest.raises(ValueError, match="invalid terminal status"):
        await repo.finish(record.run_id, "RUNNING", None, None, None, utcnow())
    assert await repo.get("missing") is None
    assert await repo.request_cancel("missing", utcnow()) is None
    assert await repo.finish("missing", "FAILED", None, {}, None, utcnow()) is None


async def test_cancel_then_only_cancelled_finish_is_allowed(repo):
    record = make_run_record()
    await repo.create(record)
    cancelled = await repo.request_cancel(record.run_id, utcnow())
    assert cancelled.status == "CANCEL_REQUESTED"

    assert await repo.finish(record.run_id, "SUCCEEDED", {}, None, None, utcnow()) is None
    finished = await repo.finish(record.run_id, "CANCELLED", None, {"code": "CANCELLED"}, None, utcnow())
    assert finished.status == "CANCELLED"
    assert finished.finished_at is not None

    again = await repo.request_cancel(record.run_id, utcnow())
    assert again.status == "CANCELLED"
    assert (await repo.finish(record.run_id, "FAILED", None, {}, None, utcnow())).status == "CANCELLED"


async def test_events_sequence_bounds_and_missing_run(repo):
    record = make_run_record()
    await repo.create(record)
    now = utcnow()
    expires = now + timedelta(hours=1)
    await repo.add_event(record.run_id, "accepted", {}, now, expires)
    await repo.add_event(record.run_id, "stage", {"name": "started"}, now, expires)

    assert (await repo.get(record.run_id)).last_event_seq == 2
    assert len(await repo.events_after(record.run_id, 0)) == 2
    events = await repo.events_after(record.run_id, 1)
    assert [item.seq for item in events] == [2]
    assert await repo.events_after(record.run_id, 2) == []
    assert await repo.event_bounds(record.run_id, now) == (1, 2, 2)
    assert await repo.event_bounds("missing", now) == (0, 0, 0)
    with pytest.raises(KeyError):
        await repo.add_event("missing", "stage", {}, now, expires)


async def test_recoverable_and_due_callbacks(repo):
    record = make_run_record()
    await repo.create(record)
    assert [item.run_id for item in await repo.recoverable()] == [record.run_id]

    finished = await repo.finish(record.run_id, "SUCCEEDED", {"ok": 1}, None, {"m": 1}, utcnow())
    assert finished.result == {"ok": 1}
    assert finished.model_meta == {"m": 1}
    assert await repo.recoverable() == []

    due = await repo.due_callbacks(utcnow())
    assert [item.run_id for item in due] == [record.run_id]

    await repo.update_callback(record.run_id, "DELIVERED", 1, None, None)
    assert await repo.due_callbacks(utcnow()) == []
    updated = await repo.get(record.run_id)
    assert updated.callback_status == "DELIVERED"
    assert updated.callback_attempts == 1


def test_build_repository_selects_backend(settings):
    repository = build_repository(settings)
    expected = MemoryRunRepository if settings.storage_backend == "memory" else SqliteRunRepository
    assert isinstance(repository, expected)


async def test_sqlite_close_then_connection_raises(settings, tmp_path):
    if settings.storage_backend != "sqlite":
        pytest.skip("sqlite only")
    repo = SqliteRunRepository(settings)
    await repo.initialize()
    await repo.close()
    with pytest.raises(RuntimeError, match="not initialized"):
        repo._connection()


async def test_sqlite_encrypted_payload_roundtrip(tmp_path):
    key = base64.b64encode(b"1" * 32).decode("ascii")
    settings = Settings(
        app_env="test",
        storage_backend="sqlite",
        sqlite_path=str(tmp_path / "enc.db"),
        data_encryption_key=key,
        callback_enabled=False,
        llm_provider="fake",
    )
    repo = SqliteRunRepository(settings)
    await repo.initialize()
    try:
        record = make_run_record(payload={"jdText": "敏感简历内容"})
        await repo.create(record)
        loaded = await repo.get(record.run_id)
        assert loaded.payload == {"jdText": "敏感简历内容"}
        raw = (tmp_path / "enc.db").read_bytes()
        assert "敏感简历内容".encode("utf-8") not in raw
    finally:
        await repo.close()

async def test_sqlite_legacy_task_duplicates_survive_migration(tmp_path):
    settings = Settings(
        app_env="test",
        storage_backend="sqlite",
        sqlite_path=str(tmp_path / "legacy.db"),
        callback_enabled=False,
        llm_provider="fake",
    )
    legacy_repo = SqliteRunRepository(settings)
    await legacy_repo.initialize()
    first = make_run_record(operation="JD_ANALYZE")
    await legacy_repo.create(first)
    conn = legacy_repo._connection()
    conn.execute("DROP TRIGGER IF EXISTS trg_ai_run_task_unique")
    conn.execute("DROP INDEX IF EXISTS ux_ai_run_task")
    conn.execute("CREATE UNIQUE INDEX ux_ai_run_task_op ON ai_run(task_id, operation)")
    second = make_run_record(
        task_id=first.task_id,
        operation="JOB_MATCH",
        input_versions={"profileVersion": 1},
    )
    await legacy_repo.create(second)
    await legacy_repo.close()

    migrated_repo = SqliteRunRepository(settings)
    await migrated_repo.initialize()
    try:
        assert (await migrated_repo.get(first.run_id)).operation == "JD_ANALYZE"
        assert (await migrated_repo.get(second.run_id)).operation == "JOB_MATCH"
        count = migrated_repo._connection().execute(
            "SELECT COUNT(*) FROM ai_run WHERE task_id = ?", (first.task_id,)
        ).fetchone()[0]
        assert count == 2
        with pytest.raises(Exception, match="duplicate task_id"):
            await migrated_repo.create(
                make_run_record(task_id=first.task_id, operation="RESUME_PARSE")
            )
    finally:
        await migrated_repo.close()