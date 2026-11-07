from __future__ import annotations

from typing import Any

from .base import SourceAdapter
from .baidu import BaiduCareerSource
from .greenhouse import GreenhouseSource
from .lever import LeverSource
from .tencent import TencentCareerSource
from .unavailable import BossZhipinSource, LiepinSource, ZhaopinSource
from .web import WebSearchSource

ALL_SOURCES: dict[str, SourceAdapter] = {
    "web": WebSearchSource(),
    "tencent": TencentCareerSource(),
    "baidu": BaiduCareerSource(),
    "greenhouse": GreenhouseSource(),
    "lever": LeverSource(),
    "liepin": LiepinSource(),
    "boss": BossZhipinSource(),
    "zhaopin": ZhaopinSource(),
}

DEFAULT_SOURCE_KEYS = ("web", "baidu", "tencent", "greenhouse")


def configured_source_keys(settings: Any) -> list[str]:
    raw = str(getattr(settings, "job_search_sources", "") or "").strip()
    if not raw:
        return list(DEFAULT_SOURCE_KEYS)
    result: list[str] = []
    for item in raw.split(","):
        key = item.strip().lower()
        if key in ALL_SOURCES and key not in result:
            result.append(key)
    return result or list(DEFAULT_SOURCE_KEYS)


def resolve_sources(requested: list[str] | None, settings: Any) -> list[SourceAdapter]:
    configured = configured_source_keys(settings)
    if not requested:
        return [ALL_SOURCES[key] for key in configured]
    result: list[SourceAdapter] = []
    for key in requested:
        normalized = str(key).strip().lower()
        adapter = ALL_SOURCES.get(normalized)
        if adapter is not None and adapter not in result:
            result.append(adapter)
    return result


def source_catalog() -> list[dict[str, Any]]:
    return [
        {
            "key": adapter.key,
            "name": adapter.name,
            "type": adapter.type,
            "available": adapter.available,
            "requiresCredentials": adapter.requires_credentials,
            "reason": adapter.unavailable_reason if not adapter.available else "",
        }
        for adapter in ALL_SOURCES.values()
    ]
