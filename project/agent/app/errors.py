from __future__ import annotations

from typing import Any


class ApiError(Exception):
    """Error returned before a run is accepted (HTTP error contract)."""

    def __init__(
        self,
        status_code: int,
        code: int,
        message: str,
        key: str,
        *,
        retryable: bool = False,
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.key = key
        self.retryable = retryable
        self.details = details


class HandlerError(Exception):
    """Failure while executing a run; persisted as run.error and sent by callback."""

    def __init__(self, code: str, message: str, *, retryable: bool = False) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable


class HandlerValidationError(Exception):
    """A model output violated operation-specific rules and may be repaired once."""

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


def unauthorized(message: str = "内部服务凭证无效") -> ApiError:
    return ApiError(401, 40101, message, "UNAUTHORIZED", retryable=False)


def forbidden(message: str = "无权访问该任务") -> ApiError:
    return ApiError(403, 40301, message, "FORBIDDEN", retryable=False)


def not_found(message: str = "资源不存在") -> ApiError:
    return ApiError(404, 40401, message, "RESOURCE_NOT_FOUND", retryable=False)


def validation_error(message: str, *, details: dict[str, Any] | None = None) -> ApiError:
    return ApiError(400, 40001, message, "VALIDATION_ERROR", retryable=False, details=details)


def conflict(message: str) -> ApiError:
    return ApiError(409, 40901, message, "CONFLICT", retryable=False)


def too_large(message: str) -> ApiError:
    return ApiError(413, 41301, message, "PAYLOAD_TOO_LARGE", retryable=False)


def rate_limited(message: str) -> ApiError:
    return ApiError(429, 42901, message, "RATE_LIMITED", retryable=True)


def internal_error(message: str = "服务内部错误") -> ApiError:
    return ApiError(500, 50001, message, "INTERNAL_ERROR", retryable=False)