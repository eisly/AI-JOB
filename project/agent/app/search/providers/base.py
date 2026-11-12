from __future__ import annotations

import time
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any


@dataclass
class SearchHit:
    url: str
    title: str
    content: str
    provider: str
    query: str
    published_at: datetime | None = None
    score: float | None = None
    raw: dict[str, Any] = field(default_factory=dict)

    def as_cache(self) -> dict[str, Any]:
        return {
            "url": self.url,
            "title": self.title,
            "content": self.content,
            "provider": self.provider,
            "query": self.query,
            "publishedAt": self.published_at.isoformat() if self.published_at else None,
            "score": self.score,
        }

    @classmethod
    def from_cache(cls, value: dict[str, Any]) -> "SearchHit":
        published = value.get("publishedAt")
        return cls(
            url=str(value.get("url") or ""),
            title=str(value.get("title") or ""),
            content=str(value.get("content") or ""),
            provider=str(value.get("provider") or ""),
            query=str(value.get("query") or ""),
            published_at=datetime.fromisoformat(published) if published else None,
            score=value.get("score"),
        )


@dataclass
class ProviderResult:
    key: str
    name: str
    status: str
    hits: list[SearchHit] = field(default_factory=list)
    message: str = ""
    http_status: int | None = None
    duration_ms: int = 0
    query: str = ""
    attempts: int = 0
    page: int = 0
    next_page: int | None = None
    credits: int | None = None

    @property
    def ok(self) -> bool:
        return self.status in {"OK", "EMPTY"}

    def as_status(self) -> dict[str, Any]:
        return {
            "key": self.key,
            "name": self.name,
            "status": self.status,
            "count": len(self.hits),
            "message": self.message,
            "httpStatus": self.http_status,
            "durationMs": self.duration_ms,
            "attempts": self.attempts,
            "page": self.page,
            "nextPage": self.next_page,
            "credits": self.credits,
        }


class SearchProvider:
    key = ""
    name = ""
    requires_credentials = True
    max_results = 20
    supports_pagination = False

    def configured(self, settings: Any) -> bool:
        return True

    async def search(self, client: Any, query: str, *, settings: Any, limit: int, timeout: float,
                     freshness: str | None = None, retries: int = 1,
                     page: int = 0) -> ProviderResult:
        raise NotImplementedError

    def unconfigured(self, query: str = "") -> ProviderResult:
        return ProviderResult(
            key=self.key,
            name=self.name,
            status="UNCONFIGURED",
            message=f"未配置 {self.name} API Key",
            query=query,
        )

    def failed(self, query: str, message: str, *, http_status: int | None = None,
               started: float | None = None, attempts: int = 0) -> ProviderResult:
        return ProviderResult(
            key=self.key,
            name=self.name,
            status="RATE_LIMITED" if http_status == 429 else "FAILED",
            message=message,
            http_status=http_status,
            duration_ms=int((time.monotonic() - started) * 1000) if started is not None else 0,
            query=query,
            attempts=attempts,
        )
