from __future__ import annotations

import time
from typing import Any

import httpx

from .base import ProviderResult, SearchHit, SearchProvider

BRAVE_URL = "https://api.search.brave.com/res/v1/web/search"
BRAVE_FRESHNESS = {"pd": "pd", "pw": "pw", "pm": "pm", "py": "py"}


class BraveProvider(SearchProvider):
    key = "brave"
    name = "Brave Search"
    requires_credentials = True

    def configured(self, settings: Any) -> bool:
        return bool(getattr(settings, "brave_search_api_key", None))

    async def search(self, client: Any, query: str, *, settings: Any, limit: int, timeout: float,
                     freshness: str | None = None, retries: int = 1) -> ProviderResult:
        api_key = getattr(settings, "brave_search_api_key", None)
        if not api_key:
            return self.unconfigured(query)
        started = time.monotonic()
        params = {
            "q": query,
            "count": max(1, min(limit, self.max_results)),
            "country": "cn",
            "search_lang": "zh-hans",
            "safesearch": "moderate",
            "text_decorations": "false",
            "spellcheck": "true",
        }
        if freshness in BRAVE_FRESHNESS:
            params["freshness"] = BRAVE_FRESHNESS[freshness]
        attempts = 0
        last_error: Exception | None = None
        for attempt in range(retries + 1):
            attempts = attempt + 1
            try:
                response = await client.get(
                    BRAVE_URL,
                    headers={
                        "X-Subscription-Token": api_key,
                        "Accept": "application/json",
                        "Accept-Encoding": "gzip",
                    },
                    params=params,
                    timeout=timeout,
                    follow_redirects=False,
                )
                if response.status_code in {429, 500, 502, 503, 504} and attempt < retries:
                    last_error = httpx.HTTPStatusError("retryable", request=response.request, response=response)
                    continue
                if response.status_code >= 400:
                    return self.failed(query, f"Brave 返回 HTTP {response.status_code}",
                                       http_status=response.status_code, started=started, attempts=attempts)
                body = response.json()
                web = body.get("web") if isinstance(body, dict) else None
                rows = web.get("results") if isinstance(web, dict) else None
                if rows is None and isinstance(body, dict) and body.get("type") == "search" and "web" not in body:
                    rows = []
                if not isinstance(rows, list):
                    return self.failed(query, "Brave 响应缺少 web.results", http_status=response.status_code,
                                       started=started, attempts=attempts)
                hits = []
                for item in rows:
                    if not isinstance(item, dict) or not item.get("url"):
                        continue
                    extra = item.get("extra_snippets")
                    snippets = [str(value) for value in extra] if isinstance(extra, list) else []
                    content = "\n".join([str(item.get("description") or ""), *snippets]).strip()
                    hits.append(SearchHit(
                        url=str(item["url"]),
                        title=" ".join(str(item.get("title") or "").split()),
                        content=content,
                        provider=self.key,
                        query=query,
                        published_at=_parse_age(item.get("page_age") or item.get("age")),
                        score=None,
                    ))
                return ProviderResult(
                    key=self.key, name=self.name, status="OK" if hits else "EMPTY", hits=hits,
                    message="" if hits else "Brave 未返回结果", http_status=response.status_code,
                    duration_ms=int((time.monotonic() - started) * 1000), query=query, attempts=attempts,
                )
            except httpx.TimeoutException:
                last_error = httpx.TimeoutException("timeout")
                if attempt >= retries:
                    return self.failed(query, "Brave 请求超时", started=started, attempts=attempts)
            except (httpx.HTTPError, ValueError) as exc:
                last_error = exc
                if attempt >= retries:
                    return self.failed(query, f"Brave 请求失败（{type(exc).__name__}）",
                                       started=started, attempts=attempts)
        return self.failed(query, f"Brave 请求失败（{type(last_error).__name__}）",
                           started=started, attempts=attempts)


def _parse_age(value: Any):
    if not isinstance(value, str) or not value.strip():
        return None
    from datetime import datetime, timezone

    text = value.strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)