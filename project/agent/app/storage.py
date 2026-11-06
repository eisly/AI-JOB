from __future__ import annotations

import asyncio
import base64
import copy
import hashlib
import json
import sqlite3
import threading
from dataclasses import dataclass, field, replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Protocol
from uuid import UUID, uuid4

from .config import Settings
from .schemas import AIRunVO

TERMINAL_STATUSES = {"SUCCEEDED", "FAILED", "CANCELLED"}
ACTIVE_STATUSES = {"QUEUED", "RUNNING", "CANCEL_REQUESTED"}


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def to_iso(value: datetime) -> str:
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def from_iso(value: str | None) -> datetime | None:
    if value is None:
        return None
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def request_digest(body: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_json(body).encode("utf-8")).hexdigest()


class JsonCodec:
    """Optional AES-GCM protection for sensitive run payloads/results at rest."""

    PREFIX = "enc:v1:"

    def __init__(self, key: str | None) -> None:
        self._aes = None
        if key:
            raw = base64.b64decode(key, validate=True)
            if len(raw) not in (16, 24, 32):
                raise ValueError("AGENT_DATA_ENCRYPTION_KEY must be base64 for 16/24/32 bytes")
            from cryptography.hazmat.primitives.ciphers.aead import AESGCM

            self._aes = AESGCM(raw)

    def encode(self, value: Any) -> str:
        text = canonical_json(value)
        if self._aes is None:
            return text
        import os

        nonce = os.urandom(12)
        sealed = self._aes.encrypt(nonce, text.encode("utf-8"), None)
        return self.PREFIX + base64.b64encode(nonce + sealed).decode("ascii")

    def decode(self, text: str | None) -> Any:
        if text is None:
            return None
        if not text.startswith(self.PREFIX):
            return json.loads(text)
        if self._aes is None:
            raise RuntimeError("encrypted payload found but AGENT_DATA_ENCRYPTION_KEY is not configured")
        raw = base64.b64decode(text[len(self.PREFIX) :])
        plain = self._aes.decrypt(raw[:12], raw[12:], None)
        return json.loads(plain.decode("utf-8"))


@dataclass
class RunRecord:
    run_id: str
    task_id: str
    user_id: str
    operation: str
    idempotency_key: str
    request_digest: str
    input_versions: dict[str, Any]
    payload: dict[str, Any]
    status: str
    attempt: int
    result: dict[str, Any] | None
    error: dict[str, Any] | None
    model_meta: dict[str, Any] | None
    last_event_seq: int
    callback_status: str
    callback_attempts: int
    callback_next_at: datetime | None
    callback_error: str | None
    deadline_at: datetime
    created_at: datetime
    updated_at: datetime
    finished_at: datetime | None = None
    purge_at: datetime | None = None
    trace_id: str | None = None

    @property
    def terminal(self) -> bool:
        return self.status in TERMINAL_STATUSES

    def as_vo(self) -> AIRunVO:
        return AIRunVO(
            run_id=UUID(self.run_id),
            task_id=UUID(self.task_id),
            operation=self.operation,
            status=self.status,  # type: ignore[arg-type]
            attempt=self.attempt,
            result=self.result,
            error=self.error,
            last_event_id=self.last_event_seq,
            created_at=self.created_at,
        )

    def callback_body(self) -> dict[str, Any]:
        status = self.status if self.status in TERMINAL_STATUSES else "FAILED"
        return {
            "runId": self.run_id,
            "operation": self.operation,
            "status": status,
            "inputVersions": self.input_versions,
            "result": self.result if status == "SUCCEEDED" else None,
            "error": self.error if status != "SUCCEEDED" else None,
        }


@dataclass
class RunEvent:
    event_id: str
    run_id: str
    seq: int
    event_type: str
    payload: dict[str, Any]
    created_at: datetime
    expires_at: datetime

    def as_sse(self, task_id: str) -> str:
        body = {
            "runId": self.run_id,
            "taskId": task_id,
            "seq": self.seq,
            "payload": self.payload,
        }
        return (
            f"id: {self.seq}\n"
            f"event: {self.event_type}\n"
            f"data: {json.dumps(body, ensure_ascii=False, separators=(',', ':'))}\n\n"
        )


class RunRepository(Protocol):
    async def initialize(self) -> None: ...

    async def close(self) -> None: ...

    async def create(self, record: RunRecord) -> RunRecord: ...

    async def get(self, run_id: str) -> RunRecord | None: ...

    async def get_by_task(self, task_id: str) -> RunRecord | None: ...

    async def get_by_idempotency(self, key: str) -> RunRecord | None: ...

    async def try_claim(self, run_id: str, now: datetime) -> RunRecord | None: ...

    async def request_cancel(self, run_id: str, now: datetime) -> RunRecord | None: ...

    async def finish(
        self,
        run_id: str,
        status: str,
        result: dict[str, Any] | None,
        error: dict[str, Any] | None,
        model_meta: dict[str, Any] | None,
        now: datetime,
    ) -> RunRecord | None: ...

    async def requeue(self, run_id: str, now: datetime) -> RunRecord | None: ...

    async def add_event(
        self,
        run_id: str,
        event_type: str,
        payload: dict[str, Any],
        now: datetime,
        expires_at: datetime,
    ) -> RunEvent: ...

    async def events_after(self, run_id: str, seq: int) -> list[RunEvent]: ...

    async def event_bounds(self, run_id: str, now: datetime) -> tuple[int, int, int]: ...

    async def recoverable(self) -> list[RunRecord]: ...

    async def update_callback(
        self,
        run_id: str,
        status: str,
        attempts: int,
        next_at: datetime | None,
        error: str | None,
    ) -> None: ...

    async def due_callbacks(self, now: datetime, limit: int = 20) -> list[RunRecord]: ...



class MemoryRunRepository:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._runs: dict[str, RunRecord] = {}
        self._by_task: dict[str, str] = {}
        self._by_key: dict[str, str] = {}
        self._events: dict[str, list[RunEvent]] = {}
        self._lock = asyncio.Lock()

    async def initialize(self) -> None:
        return None

    async def close(self) -> None:
        return None

    async def create(self, record: RunRecord) -> RunRecord:
        async with self._lock:
            if record.run_id in self._runs:
                raise KeyError("duplicate run_id")
            existing_task = self._by_task.get(record.task_id)
            if existing_task is not None:
                raise ValueError("duplicate task_id")
            if record.idempotency_key in self._by_key:
                raise ValueError("duplicate idempotency_key")
            self._runs[record.run_id] = copy.deepcopy(record)
            self._by_task[record.task_id] = record.run_id
            self._by_key[record.idempotency_key] = record.run_id
            return copy.deepcopy(record)

    async def get(self, run_id: str) -> RunRecord | None:
        async with self._lock:
            record = self._runs.get(run_id)
            return copy.deepcopy(record) if record else None

    async def get_by_task(self, task_id: str) -> RunRecord | None:
        async with self._lock:
            run_id = self._by_task.get(task_id)
            return copy.deepcopy(self._runs[run_id]) if run_id else None

    async def get_by_idempotency(self, key: str) -> RunRecord | None:
        async with self._lock:
            run_id = self._by_key.get(key)
            return copy.deepcopy(self._runs[run_id]) if run_id else None

    async def try_claim(self, run_id: str, now: datetime) -> RunRecord | None:
        async with self._lock:
            record = self._runs.get(run_id)
            if record is None or record.status != "QUEUED":
                return None
            updated = replace(record, status="RUNNING", updated_at=now)
            self._runs[run_id] = updated
            return copy.deepcopy(updated)

    async def request_cancel(self, run_id: str, now: datetime) -> RunRecord | None:
        async with self._lock:
            record = self._runs.get(run_id)
            if record is None:
                return None
            if record.status in TERMINAL_STATUSES or record.status == "CANCEL_REQUESTED":
                return copy.deepcopy(record)
            updated = replace(record, status="CANCEL_REQUESTED", updated_at=now)
            self._runs[run_id] = updated
            return copy.deepcopy(updated)

    async def finish(
        self,
        run_id: str,
        status: str,
        result: dict[str, Any] | None,
        error: dict[str, Any] | None,
        model_meta: dict[str, Any] | None,
        now: datetime,
    ) -> RunRecord | None:
        if status not in TERMINAL_STATUSES:
            raise ValueError(f"invalid terminal status {status}")
        async with self._lock:
            record = self._runs.get(run_id)
            if record is None:
                return None
            if record.status in TERMINAL_STATUSES:
                return copy.deepcopy(record)
            if record.status == "CANCEL_REQUESTED" and status != "CANCELLED":
                return None
            callback_status = record.callback_status
            updated = replace(
                record,
                status=status,
                result=result,
                error=error,
                model_meta=model_meta,
                updated_at=now,
                finished_at=now,
                callback_status=callback_status,
                callback_next_at=now if callback_status == "PENDING" else record.callback_next_at,
            )
            self._runs[run_id] = updated
            return copy.deepcopy(updated)

    async def requeue(self, run_id: str, now: datetime) -> RunRecord | None:
        async with self._lock:
            record = self._runs.get(run_id)
            if record is None or record.status != "RUNNING":
                return None
            updated = replace(record, status="QUEUED", attempt=record.attempt + 1, updated_at=now)
            self._runs[run_id] = updated
            return copy.deepcopy(updated)

    async def add_event(
        self,
        run_id: str,
        event_type: str,
        payload: dict[str, Any],
        now: datetime,
        expires_at: datetime,
    ) -> RunEvent:
        async with self._lock:
            record = self._runs.get(run_id)
            if record is None:
                raise KeyError(run_id)
            seq = record.last_event_seq + 1
            event = RunEvent(
                event_id=str(uuid4()),
                run_id=run_id,
                seq=seq,
                event_type=event_type,
                payload=payload,
                created_at=now,
                expires_at=expires_at,
            )
            self._events.setdefault(run_id, []).append(event)
            self._runs[run_id] = replace(record, last_event_seq=seq, updated_at=now)
            return copy.deepcopy(event)

    async def events_after(self, run_id: str, seq: int) -> list[RunEvent]:
        async with self._lock:
            return [copy.deepcopy(item) for item in self._events.get(run_id, []) if item.seq > seq]

    async def event_bounds(self, run_id: str, now: datetime) -> tuple[int, int, int]:
        async with self._lock:
            record = self._runs.get(run_id)
            if record is None:
                return 0, 0, 0
            events = self._events.get(run_id, [])
            active = [item.seq for item in events if item.expires_at > now]
            min_seq = min(active) if active else 0
            return min_seq, record.last_event_seq, len(active)

    async def recoverable(self) -> list[RunRecord]:
        async with self._lock:
            return [
                copy.deepcopy(item)
                for item in self._runs.values()
                if item.status in ACTIVE_STATUSES
            ]

    async def update_callback(
        self,
        run_id: str,
        status: str,
        attempts: int,
        next_at: datetime | None,
        error: str | None,
    ) -> None:
        async with self._lock:
            record = self._runs.get(run_id)
            if record is None:
                return
            self._runs[run_id] = replace(
                record,
                callback_status=status,
                callback_attempts=attempts,
                callback_next_at=next_at,
                callback_error=error,
                updated_at=utcnow(),
            )

    async def due_callbacks(self, now: datetime, limit: int = 20) -> list[RunRecord]:
        async with self._lock:
            due = [
                copy.deepcopy(item)
                for item in self._runs.values()
                if item.terminal
                and item.callback_status == "PENDING"
                and (item.callback_next_at is None or item.callback_next_at <= now)
            ]
            due.sort(key=lambda item: item.finished_at or item.created_at)
            return due[:limit]


class SqliteRunRepository:
    """Single-process persistent store using the standard library sqlite3 driver.

    All statements are serialized through one lock and executed in a worker
    thread. This keeps the async API without adding a native async driver.
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._codec = JsonCodec(settings.data_encryption_key)
        self._path = Path(settings.sqlite_path)
        self._lock = threading.RLock()
        self._conn: sqlite3.Connection | None = None

    def _connection(self) -> sqlite3.Connection:
        if self._conn is None:
            raise RuntimeError("repository is not initialized")
        return self._conn

    async def initialize(self) -> None:
        await asyncio.to_thread(self._initialize_sync)

    def _initialize_sync(self) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(str(self._path), check_same_thread=False, isolation_level=None)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA synchronous=NORMAL")
        conn.execute("PRAGMA busy_timeout=5000")
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS ai_run (
                run_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                operation TEXT NOT NULL,
                idempotency_key TEXT NOT NULL,
                request_digest TEXT NOT NULL,
                input_versions_json TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                status TEXT NOT NULL,
                attempt INTEGER NOT NULL,
                result_json TEXT,
                error_json TEXT,
                model_meta_json TEXT,
                last_event_seq INTEGER NOT NULL DEFAULT 0,
                callback_status TEXT NOT NULL DEFAULT 'PENDING',
                callback_attempts INTEGER NOT NULL DEFAULT 0,
                callback_next_at TEXT,
                callback_error TEXT,
                deadline_at TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                finished_at TEXT,
                purge_at TEXT,
                trace_id TEXT
            );

            CREATE UNIQUE INDEX IF NOT EXISTS ux_ai_run_idem ON ai_run(idempotency_key);
            CREATE INDEX IF NOT EXISTS ix_ai_run_status ON ai_run(status, deadline_at);
            CREATE INDEX IF NOT EXISTS ix_ai_run_callback ON ai_run(callback_status, callback_next_at);
            CREATE TABLE IF NOT EXISTS ai_run_event (
                id TEXT PRIMARY KEY,
                run_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                UNIQUE(run_id, seq)
            );
            CREATE INDEX IF NOT EXISTS ix_ai_run_event_expiry ON ai_run_event(expires_at);
            """
        )
        conn.execute("DROP INDEX IF EXISTS ux_ai_run_task_op")
        duplicate = conn.execute(
            "SELECT task_id FROM ai_run GROUP BY task_id HAVING COUNT(*) > 1 LIMIT 1"
        ).fetchone()
        if duplicate is None:
            conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_ai_run_task ON ai_run(task_id)")
        else:
            conn.execute("CREATE INDEX IF NOT EXISTS ix_ai_run_task ON ai_run(task_id)")
        conn.executescript(
            """
            CREATE TRIGGER IF NOT EXISTS trg_ai_run_task_unique
            BEFORE INSERT ON ai_run
            FOR EACH ROW
            WHEN EXISTS (SELECT 1 FROM ai_run WHERE task_id = NEW.task_id)
            BEGIN
                SELECT RAISE(ABORT, 'duplicate task_id');
            END;
            """
        )
        self._conn = conn

    async def close(self) -> None:
        def _close() -> None:
            with self._lock:
                if self._conn is not None:
                    self._conn.close()
                    self._conn = None

        await asyncio.to_thread(_close)

    def _row(self, row: sqlite3.Row) -> RunRecord:
        return RunRecord(
            run_id=row["run_id"],
            task_id=row["task_id"],
            user_id=row["user_id"],
            operation=row["operation"],
            idempotency_key=row["idempotency_key"],
            request_digest=row["request_digest"],
            input_versions=self._codec.decode(row["input_versions_json"]),
            payload=self._codec.decode(row["payload_json"]),
            status=row["status"],
            attempt=row["attempt"],
            result=self._codec.decode(row["result_json"]) if row["result_json"] else None,
            error=json.loads(row["error_json"]) if row["error_json"] else None,
            model_meta=json.loads(row["model_meta_json"]) if row["model_meta_json"] else None,
            last_event_seq=row["last_event_seq"],
            callback_status=row["callback_status"],
            callback_attempts=row["callback_attempts"],
            callback_next_at=from_iso(row["callback_next_at"]),
            callback_error=row["callback_error"],
            deadline_at=from_iso(row["deadline_at"]),  # type: ignore[arg-type]
            created_at=from_iso(row["created_at"]),  # type: ignore[arg-type]
            updated_at=from_iso(row["updated_at"]),  # type: ignore[arg-type]
            finished_at=from_iso(row["finished_at"]),
            purge_at=from_iso(row["purge_at"]),
            trace_id=row["trace_id"],
        )

    def _event(self, row: sqlite3.Row) -> RunEvent:
        return RunEvent(
            event_id=row["id"],
            run_id=row["run_id"],
            seq=row["seq"],
            event_type=row["event_type"],
            payload=json.loads(row["payload_json"]),
            created_at=from_iso(row["created_at"]),  # type: ignore[arg-type]
            expires_at=from_iso(row["expires_at"]),  # type: ignore[arg-type]
        )

    async def create(self, record: RunRecord) -> RunRecord:
        return await asyncio.to_thread(self._create_sync, record)

    def _create_sync(self, record: RunRecord) -> RunRecord:
        with self._lock:
            conn = self._connection()
            try:
                conn.execute("BEGIN IMMEDIATE")
                conn.execute(
                    """
                    INSERT INTO ai_run (
                        run_id, task_id, user_id, operation, idempotency_key, request_digest,
                        input_versions_json, payload_json, status, attempt, last_event_seq,
                        callback_status, callback_attempts, deadline_at, created_at, updated_at,
                        purge_at, trace_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        record.run_id,
                        record.task_id,
                        record.user_id,
                        record.operation,
                        record.idempotency_key,
                        record.request_digest,
                        self._codec.encode(record.input_versions),
                        self._codec.encode(record.payload),
                        record.status,
                        record.attempt,
                        record.last_event_seq,
                        record.callback_status,
                        record.callback_attempts,
                        to_iso(record.deadline_at),
                        to_iso(record.created_at),
                        to_iso(record.updated_at),
                        to_iso(record.purge_at) if record.purge_at else None,
                        record.trace_id,
                    ),
                )
                conn.execute("COMMIT")
            except Exception:
                conn.execute("ROLLBACK")
                raise
            return record

    async def get(self, run_id: str) -> RunRecord | None:
        return await asyncio.to_thread(self._get_sync, "run_id", run_id)

    async def get_by_task(self, task_id: str) -> RunRecord | None:
        return await asyncio.to_thread(self._get_sync, "task_id", task_id)

    async def get_by_idempotency(self, key: str) -> RunRecord | None:
        return await asyncio.to_thread(self._get_sync, "idempotency_key", key)

    def _get_sync(self, column: str, value: str) -> RunRecord | None:
        with self._lock:
            conn = self._connection()
            row = conn.execute(f"SELECT * FROM ai_run WHERE {column} = ?", (value,)).fetchone()
            return self._row(row) if row else None

    async def try_claim(self, run_id: str, now: datetime) -> RunRecord | None:
        return await asyncio.to_thread(self._update_status_sync, run_id, "QUEUED", "RUNNING", now)

    async def request_cancel(self, run_id: str, now: datetime) -> RunRecord | None:
        return await asyncio.to_thread(self._cancel_sync, run_id, now)

    def _cancel_sync(self, run_id: str, now: datetime) -> RunRecord | None:
        with self._lock:
            conn = self._connection()
            row = conn.execute("SELECT * FROM ai_run WHERE run_id = ?", (run_id,)).fetchone()
            if row is None:
                return None
            record = self._row(row)
            if record.terminal or record.status == "CANCEL_REQUESTED":
                return record
            return self._update_status_sync(run_id, record.status, "CANCEL_REQUESTED", now)

    def _update_status_sync(
        self,
        run_id: str,
        expected: str,
        new_status: str,
        now: datetime,
    ) -> RunRecord | None:
        with self._lock:
            conn = self._connection()
            cursor = conn.execute(
                "UPDATE ai_run SET status = ?, updated_at = ? WHERE run_id = ? AND status = ?",
                (new_status, to_iso(now), run_id, expected),
            )
            if cursor.rowcount == 0:
                return None
            row = conn.execute("SELECT * FROM ai_run WHERE run_id = ?", (run_id,)).fetchone()
            return self._row(row)

    async def finish(
        self,
        run_id: str,
        status: str,
        result: dict[str, Any] | None,
        error: dict[str, Any] | None,
        model_meta: dict[str, Any] | None,
        now: datetime,
    ) -> RunRecord | None:
        if status not in TERMINAL_STATUSES:
            raise ValueError(f"invalid terminal status {status}")
        return await asyncio.to_thread(
            self._finish_sync, run_id, status, result, error, model_meta, now
        )

    def _finish_sync(
        self,
        run_id: str,
        status: str,
        result: dict[str, Any] | None,
        error: dict[str, Any] | None,
        model_meta: dict[str, Any] | None,
        now: datetime,
    ) -> RunRecord | None:
        with self._lock:
            conn = self._connection()
            row = conn.execute("SELECT * FROM ai_run WHERE run_id = ?", (run_id,)).fetchone()
            if row is None:
                return None
            record = self._row(row)
            if record.terminal:
                return record
            if record.status == "CANCEL_REQUESTED" and status != "CANCELLED":
                return None
            try:
                conn.execute("BEGIN IMMEDIATE")
                conn.execute(
                    """
                    UPDATE ai_run
                    SET status = ?, result_json = ?, error_json = ?, model_meta_json = ?,
                        updated_at = ?, finished_at = ?,
                        callback_next_at = CASE WHEN callback_status = 'PENDING' THEN ? ELSE callback_next_at END
                    WHERE run_id = ?
                    """,
                    (
                        status,
                        self._codec.encode(result) if result is not None else None,
                        canonical_json(error) if error is not None else None,
                        canonical_json(model_meta) if model_meta is not None else None,
                        to_iso(now),
                        to_iso(now),
                        to_iso(now),
                        run_id,
                    ),
                )
                conn.execute("COMMIT")
            except Exception:
                conn.execute("ROLLBACK")
                raise
            row = conn.execute("SELECT * FROM ai_run WHERE run_id = ?", (run_id,)).fetchone()
            return self._row(row)

    async def requeue(self, run_id: str, now: datetime) -> RunRecord | None:
        return await asyncio.to_thread(self._requeue_sync, run_id, now)

    def _requeue_sync(self, run_id: str, now: datetime) -> RunRecord | None:
        with self._lock:
            conn = self._connection()
            cursor = conn.execute(
                "UPDATE ai_run SET status = 'QUEUED', attempt = attempt + 1, updated_at = ? "
                "WHERE run_id = ? AND status = 'RUNNING'",
                (to_iso(now), run_id),
            )
            if cursor.rowcount == 0:
                return None
            row = conn.execute("SELECT * FROM ai_run WHERE run_id = ?", (run_id,)).fetchone()
            return self._row(row)

    async def add_event(
        self,
        run_id: str,
        event_type: str,
        payload: dict[str, Any],
        now: datetime,
        expires_at: datetime,
    ) -> RunEvent:
        return await asyncio.to_thread(
            self._add_event_sync, run_id, event_type, payload, now, expires_at
        )

    def _add_event_sync(
        self,
        run_id: str,
        event_type: str,
        payload: dict[str, Any],
        now: datetime,
        expires_at: datetime,
    ) -> RunEvent:
        with self._lock:
            conn = self._connection()
            try:
                conn.execute("BEGIN IMMEDIATE")
                row = conn.execute("SELECT last_event_seq FROM ai_run WHERE run_id = ?", (run_id,)).fetchone()
                if row is None:
                    # 统一由下方 except 分支回滚，避免重复 ROLLBACK。
                    raise KeyError(run_id)
                seq = int(row["last_event_seq"]) + 1
                event_id = str(uuid4())
                conn.execute(
                    """
                    INSERT INTO ai_run_event (id, run_id, seq, event_type, payload_json, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        event_id,
                        run_id,
                        seq,
                        event_type,
                        canonical_json(payload),
                        to_iso(now),
                        to_iso(expires_at),
                    ),
                )
                conn.execute(
                    "UPDATE ai_run SET last_event_seq = ?, updated_at = ? WHERE run_id = ?",
                    (seq, to_iso(now), run_id),
                )
                conn.execute("COMMIT")
            except Exception:
                conn.execute("ROLLBACK")
                raise
            return RunEvent(
                event_id=event_id,
                run_id=run_id,
                seq=seq,
                event_type=event_type,
                payload=payload,
                created_at=now,
                expires_at=expires_at,
            )

    async def events_after(self, run_id: str, seq: int) -> list[RunEvent]:
        return await asyncio.to_thread(self._events_after_sync, run_id, seq)

    def _events_after_sync(self, run_id: str, seq: int) -> list[RunEvent]:
        with self._lock:
            conn = self._connection()
            rows = conn.execute(
                "SELECT * FROM ai_run_event WHERE run_id = ? AND seq > ? ORDER BY seq ASC",
                (run_id, seq),
            ).fetchall()
            return [self._event(row) for row in rows]

    async def event_bounds(self, run_id: str, now: datetime) -> tuple[int, int, int]:
        return await asyncio.to_thread(self._event_bounds_sync, run_id, now)

    def _event_bounds_sync(self, run_id: str, now: datetime) -> tuple[int, int, int]:
        with self._lock:
            conn = self._connection()
            record = conn.execute("SELECT last_event_seq FROM ai_run WHERE run_id = ?", (run_id,)).fetchone()
            if record is None:
                return 0, 0, 0
            row = conn.execute(
                "SELECT MIN(seq) AS min_seq, COUNT(*) AS total "
                "FROM ai_run_event WHERE run_id = ? AND expires_at > ?",
                (run_id, to_iso(now)),
            ).fetchone()
            min_seq = int(row["min_seq"]) if row["min_seq"] is not None else 0
            return min_seq, int(record["last_event_seq"]), int(row["total"] or 0)

    async def recoverable(self) -> list[RunRecord]:
        return await asyncio.to_thread(self._recoverable_sync)

    def _recoverable_sync(self) -> list[RunRecord]:
        with self._lock:
            conn = self._connection()
            rows = conn.execute(
                "SELECT * FROM ai_run WHERE status IN ('QUEUED','RUNNING','CANCEL_REQUESTED') "
                "ORDER BY created_at ASC"
            ).fetchall()
            return [self._row(row) for row in rows]

    async def update_callback(
        self,
        run_id: str,
        status: str,
        attempts: int,
        next_at: datetime | None,
        error: str | None,
    ) -> None:
        await asyncio.to_thread(
            self._update_callback_sync, run_id, status, attempts, next_at, error
        )

    def _update_callback_sync(
        self,
        run_id: str,
        status: str,
        attempts: int,
        next_at: datetime | None,
        error: str | None,
    ) -> None:
        with self._lock:
            conn = self._connection()
            conn.execute(
                "UPDATE ai_run SET callback_status = ?, callback_attempts = ?, "
                "callback_next_at = ?, callback_error = ?, updated_at = ? WHERE run_id = ?",
                (status, attempts, to_iso(next_at) if next_at else None, error, to_iso(utcnow()), run_id),
            )

    async def due_callbacks(self, now: datetime, limit: int = 20) -> list[RunRecord]:
        return await asyncio.to_thread(self._due_callbacks_sync, now, limit)

    def _due_callbacks_sync(self, now: datetime, limit: int) -> list[RunRecord]:
        with self._lock:
            conn = self._connection()
            rows = conn.execute(
                "SELECT * FROM ai_run WHERE status IN ('SUCCEEDED','FAILED','CANCELLED') "
                "AND callback_status = 'PENDING' "
                "AND (callback_next_at IS NULL OR callback_next_at <= ?) "
                "ORDER BY finished_at ASC LIMIT ?",
                (to_iso(now), limit),
            ).fetchall()
            return [self._row(row) for row in rows]


def build_repository(settings: Settings) -> RunRepository:
    if settings.storage_backend == "memory":
        return MemoryRunRepository(settings)
    return SqliteRunRepository(settings)