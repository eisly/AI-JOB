from __future__ import annotations

import time
from typing import Any

import httpx

from .base import ProviderResult, SearchHit, SearchProvider
from .tavily import _parse_date


class DeepSeekSearchProvider(SearchProvider):
    key = "deepseek"
    name = "DeepSeek Search"
    max_results = 50

    def configured(self, settings: Any) -> bool:
        return bool(getattr(settings, "deepseek_api_key", None))

    async def search(self, client: Any, query: str, *, settings: Any, limit: int, timeout: float,
                     freshness: str | None = None, retries: int = 1, page: int = 0) -> ProviderResult:
        api_key = getattr(settings, "deepseek_api_key", None)
        if not api_key:
            return self.unconfigured(query)
        query = query.strip()
        if not query or len(query) > 128:
            return self.failed(query, "DeepSeek 搜索词须为 1 至 128 个字符")
        base = str(getattr(settings, "deepseek_base_url", "https://api.deepseek.com")).rstrip("/")
        url = base + ("/tools/search" if base.endswith("/v1") else "/v1/tools/search")
        payload = {"query": query, "count": max(1, min(limit, self.max_results)), "need_content": True}
        started = time.monotonic()
        for attempt in range(max(0, retries) + 1):
            attempts = attempt + 1
            try:
                response = await client.post(url, headers={"Authorization": f"Bearer {api_key}"},
                                             json=payload, timeout=timeout, follow_redirects=False)
                if response.status_code in {408, 429, 500, 502, 503, 504} and attempt < retries:
                    continue
                if response.status_code >= 400:
                    return self.failed(query, f"DeepSeek Search 返回 HTTP {response.status_code}",
                                       http_status=response.status_code, started=started, attempts=attempts)
                body = response.json()
                results = body.get("results") if isinstance(body, dict) else None
                if not isinstance(results, list):
                    return self.failed(query, "DeepSeek Search 响应缺少结果列表", http_status=response.status_code,
                                       started=started, attempts=attempts)
                hits = [SearchHit(
                    url=str(item["url"]), title=" ".join(str(item.get("title") or "").split()),
                    content=str(item.get("content") or item.get("summary") or item.get("snippet") or ""),
                    provider=self.key, query=query, published_at=_parse_date(item.get("date")),
                    score=item.get("rerank_score") if isinstance(item.get("rerank_score"), (int, float)) else None,
                ) for item in results if isinstance(item, dict) and item.get("url")]
                return ProviderResult(key=self.key, name=self.name, status="OK" if hits else "EMPTY", hits=hits,
                                      message="" if hits else "DeepSeek Search 未返回结果",
                                      http_status=response.status_code, duration_ms=int((time.monotonic()-started)*1000),
                                      query=query, attempts=attempts, page=page)
            except httpx.TimeoutException:
                if attempt >= retries:
                    return self.failed(query, "DeepSeek Search 请求超时", started=started, attempts=attempts)
            except (httpx.HTTPError, ValueError) as exc:
                if attempt >= retries:
                    return self.failed(query, f"DeepSeek Search 请求失败（{type(exc).__name__}）",
                                       started=started, attempts=attempts)
        return self.failed(query, "DeepSeek Search 请求失败", started=started)
