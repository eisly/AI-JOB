from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any
from uuid import UUID

import jwt

from .config import Settings
from .errors import forbidden, unauthorized


@dataclass
class Principal:
    subject: str
    scopes: set[str] = field(default_factory=set)
    task_ids: set[str] = field(default_factory=set)
    raw: dict[str, Any] = field(default_factory=dict)

    def assert_task(self, task_id: str | UUID) -> None:
        value = str(task_id)
        if not self.task_ids:
            raise forbidden("内部凭证未限定任务范围")
        if value not in self.task_ids:
            raise forbidden("内部凭证不包含该任务")


def _decode(token: str, settings: Settings) -> dict[str, Any]:
    try:
        return jwt.decode(
            token,
            settings.jwt_secret,
            algorithms=["HS256"],
            audience=settings.jwt_audience,
            issuer=settings.jwt_issuer,
            leeway=settings.jwt_leeway_seconds,
            options={"require": ["exp", "iat", "iss", "aud"]},
        )
    except jwt.ExpiredSignatureError as exc:
        raise unauthorized("内部服务凭证已过期") from exc
    except jwt.InvalidTokenError as exc:
        raise unauthorized() from exc


def verify_internal_token(token: str, settings: Settings, required_scope: str) -> Principal:
    if settings.auth_mode == "disabled":
        return Principal(subject="auth-disabled", scopes={required_scope}, task_ids=set())

    raw = _decode(token, settings)
    scopes = {part for part in str(raw.get("scope", "")).split() if part}
    if required_scope not in scopes:
        raise forbidden(f"缺少内部 scope: {required_scope}")

    task_ids: set[str] = set()
    single = raw.get("taskId")
    if single:
        task_ids.add(str(single))
    many = raw.get("taskIds") or raw.get("task_ids") or []
    if isinstance(many, str):
        many = [part for part in many.split(",") if part]
    if isinstance(many, (list, tuple, set)):
        task_ids.update(str(item) for item in many)

    return Principal(
        subject=str(raw.get("sub", "")),
        scopes=scopes,
        task_ids=task_ids,
        raw=raw,
    )


def create_internal_token(
    settings: Settings,
    *,
    task_ids: list[str] | None = None,
    scope: str | None = None,
    subject: str = "career-business",
    ttl_seconds: int = 300,
    audience: str | None = None,
    issuer: str | None = None,
    secret: str | None = None,
) -> str:
    now = datetime.now(timezone.utc)
    payload: dict[str, Any] = {
        "iss": issuer or settings.jwt_issuer,
        "aud": audience or settings.jwt_audience,
        "sub": subject,
        "iat": int(now.timestamp()),
        "exp": int((now + timedelta(seconds=ttl_seconds)).timestamp()),
        "scope": scope or settings.run_scope,
    }
    if task_ids:
        payload["taskIds"] = list(task_ids)
    return jwt.encode(payload, secret or settings.jwt_secret, algorithm="HS256")


def create_callback_token(settings: Settings, task_id: str | UUID) -> str:
    secret = settings.callback_secret or settings.jwt_secret
    return create_internal_token(
        settings,
        task_ids=[str(task_id)],
        scope=settings.callback_scope,
        subject=settings.callback_issuer,
        audience=settings.callback_audience,
        issuer=settings.callback_issuer,
        secret=secret,
        ttl_seconds=120,
    )