from __future__ import annotations

import contextlib
import logging
import os
import sys
from contextvars import ContextVar, Token
from typing import Any, Iterator

LOG_CONTEXT_FIELDS = ("trace", "task", "run", "op", "user")
_LOG_CONTEXT: ContextVar[dict[str, str]] = ContextVar("agent_log_context", default={})

_FORMAT = (
    "%(asctime)s %(levelname)-5s %(name)-22s "
    "trace=%(trace)s task=%(task)s run=%(run)s op=%(op)s | %(message)s"
)
_DATE_FORMAT = "%Y-%m-%d %H:%M:%S"

_LEVEL_COLORS = {
    logging.DEBUG: "\033[36m",
    logging.INFO: "\033[32m",
    logging.WARNING: "\033[33m",
    logging.ERROR: "\033[31m",
    logging.CRITICAL: "\033[1;31m",
}
_RESET = "\033[0m"


def short_id(value: Any, length: int = 12) -> str:
    if value is None or value == "":
        return "-"
    text = str(value)
    return text if len(text) <= length else text[:length]


@contextlib.contextmanager
def log_context(**fields: Any) -> Iterator[None]:
    """为当前异步上下文内的所有日志附加 trace/task/run/op/user 字段。"""
    values = {
        key: short_id(value)
        for key, value in fields.items()
        if key in LOG_CONTEXT_FIELDS and value is not None and value != ""
    }
    if not values:
        yield
        return
    token: Token[dict[str, str]] = _LOG_CONTEXT.set({**_LOG_CONTEXT.get(), **values})
    try:
        yield
    finally:
        _LOG_CONTEXT.reset(token)


def current_log_context() -> dict[str, str]:
    return dict(_LOG_CONTEXT.get())


def bind_log_context(**fields: Any) -> Token[dict[str, str]]:
    values = {
        key: short_id(value)
        for key, value in fields.items()
        if key in LOG_CONTEXT_FIELDS and value is not None and value != ""
    }
    return _LOG_CONTEXT.set({**_LOG_CONTEXT.get(), **values})


def reset_log_context(token: Token[dict[str, str]]) -> None:
    _LOG_CONTEXT.reset(token)


class _ContextFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        context = _LOG_CONTEXT.get()
        for name in LOG_CONTEXT_FIELDS:
            if not hasattr(record, name):
                setattr(record, name, context.get(name, "-"))
        return True


class _ConsoleFormatter(logging.Formatter):
    def __init__(self, use_color: bool = False) -> None:
        super().__init__(_FORMAT, datefmt=_DATE_FORMAT)
        self.use_color = use_color

    def format(self, record: logging.LogRecord) -> str:
        message = super().format(record)
        if not self.use_color:
            return message
        color = _LEVEL_COLORS.get(record.levelno)
        return f"{color}{message}{_RESET}" if color else message


def _supports_color(stream: Any) -> bool:
    if os.environ.get("NO_COLOR"):
        return False
    if os.environ.get("FORCE_COLOR"):
        return True
    return bool(getattr(stream, "isatty", lambda: False)())


def configure_logging(level: str = "INFO", *, stream: Any | None = None) -> None:
    stream = stream or sys.stdout
    reconfigure = getattr(stream, "reconfigure", None)
    if reconfigure is not None and getattr(stream, "isatty", lambda: False)():
        with contextlib.suppress(Exception):
            reconfigure(encoding="utf-8", errors="replace")

    handler = logging.StreamHandler(stream)
    handler.setFormatter(_ConsoleFormatter(use_color=_supports_color(stream)))
    handler.addFilter(_ContextFilter())

    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(level.upper())

    for noisy in ("httpx", "httpcore", "openai", "urllib3", "uvicorn.access"):
        logging.getLogger(noisy).setLevel(logging.WARNING)


def mask_secret(value: str | None) -> str:
    if not value:
        return "<unset>"
    if len(value) <= 8:
        return "***"
    return value[:4] + "***" + value[-2:]


def summarize_payload(payload: Any, *, max_keys: int = 10) -> str:
    """生成请求体摘要：只记录字段名、类型和长度，不写入业务文本或机密内容。"""
    if not isinstance(payload, dict):
        return f"<{type(payload).__name__}>"
    parts: list[str] = []
    for key in list(payload)[:max_keys]:
        value = payload[key]
        if isinstance(value, str):
            parts.append(f"{key}:str({len(value)})")
        elif isinstance(value, bool) or isinstance(value, (int, float)):
            parts.append(f"{key}:{value}")
        elif isinstance(value, (list, tuple)):
            parts.append(f"{key}:list({len(value)})")
        elif isinstance(value, dict):
            nested = ",".join(str(item) for item in list(value)[:5])
            parts.append(f"{key}:obj({nested})")
        elif value is None:
            parts.append(f"{key}:null")
        else:
            parts.append(f"{key}:{type(value).__name__}")
    if len(payload) > max_keys:
        parts.append(f"...(+{len(payload) - max_keys})")
    return "[" + ", ".join(parts) + "]"