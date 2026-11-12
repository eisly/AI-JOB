from __future__ import annotations

import time
from typing import Any

import httpx

from .base import ProviderResult, SearchHit, SearchProvider

TAVILY_URL = "https://api.tavily.com/search"


class TavilyProvider(SearchProvider):
    key = "tavily"
    name = "Tavily"
    requires_credentials = True

    def configured(self, settings: Any) -> bool:
        return bool(getattr(settings, "tavily_api_key", None))

    async def search(self, client: Any, query: str, *, settings: Any, limit: int, timeout: float,
                     freshness: str | None = None, retries: int = 1,
                     page: int = 0) -> ProviderResult:
        api_key = getattr(settings, "tavily_api_key", None)
        if not api_key:
            return self.unconfigured(query)
        started = time.monotonic()
        payload = {
            "query": query,
            "topic": "general",
            "country": "china",
            "language": "zh-cn",
            "search_depth": str(getattr(settings, "job_search_tavily_search_depth", "basic")),
            "include_raw_content": "markdown",
            "include_answer": False,
            "include_published_date": True,
            "include_usage": True,
            "max_results": max(1, min(limit, self.max_results)),
        }
        if freshness:
            payload["time_range"] = freshness
        attempts = 0
        last_error: Exception | None = None
        for attempt in range(retries + 1):
            attempts = attempt + 1
            try:
                response = await client.post(
                    TAVILY_URL,
                    headers={"Authorization": f"Bearer {api_key}"},
                    json=payload,
                    timeout=timeout,
                    follow_redirects=False,
                )
                if response.status_code in {429, 500, 502, 503, 504} and attempt < retries:
                    last_error = httpx.HTTPStatusError("retryable", request=response.request, response=response)
                    continue
                if response.status_code >= 400:
                    return self.failed(query, f"Tavily 返回 HTTP {response.status_code}",
                                       http_status=response.status_code, started=started, attempts=attempts)
                body = response.json()
                results = body.get("results") if isinstance(body, dict) else None
                if not isinstance(results, list):
                    return self.failed(query, "Tavily 响应缺少结果列表", http_status=response.status_code,
                                       started=started, attempts=attempts)
                hits = []
                for item in results:
                    if not isinstance(item, dict) or not item.get("url"):
                        continue
                    hits.append(SearchHit(
                        url=str(item["url"]),
                        title=" ".join(str(item.get("title") or "").split()),
                        content=str(item.get("raw_content") or item.get("content") or ""),
                        provider=self.key,
                        query=query,
                        published_at=_parse_date(item.get("published_date")),
                        score=item.get("score") if isinstance(item.get("score"), (int, float)) else None,
                    ))
                usage = body.get("usage")
                credits = usage.get("credits") if isinstance(usage, dict) else None
                return ProviderResult(
                    key=self.key, name=self.name, status="OK" if hits else "EMPTY", hits=hits,
                    message="" if hits else "Tavily 未返回结果", http_status=response.status_code,
                    duration_ms=int((time.monotonic() - started) * 1000), query=query, attempts=attempts,
                    page=page,
                    credits=credits if isinstance(credits, int) else None,
                )
            except httpx.TimeoutException:
                last_error = httpx.TimeoutException("timeout")
                if attempt >= retries:
                    return self.failed(query, "Tavily 请求超时", started=started, attempts=attempts)
            except (httpx.HTTPError, ValueError) as exc:
                last_error = exc
                if attempt >= retries:
                    return self.failed(query, f"Tavily 请求失败（{type(exc).__name__}）",
                                       started=started, attempts=attempts)
        return self.failed(query, f"Tavily 请求失败（{type(last_error).__name__}）",
                           started=started, attempts=attempts)


def _parse_date(value: Any):
    if not value:
        return None
    from datetime import datetime, timezone

    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)
