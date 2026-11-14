from __future__ import annotations

from typing import Any

from .base import SourceAdapter
from .baidu import BaiduCareerSource
from .greenhouse import GreenhouseSource
from .lever import LeverSource
from .tencent import TencentCareerSource
from .unavailable import BossZhipinSource, LiepinSource, ZhaopinSource
from .web import WebSearchSource
from ..providers import resolve_providers

from .company_official import CompanyOfficialSource

ALL_SOURCES: dict[str, SourceAdapter] = {
    "company_official": CompanyOfficialSource(),
    "web": WebSearchSource(),
    "tencent": TencentCareerSource(),
    "baidu": BaiduCareerSource(),
    "greenhouse": GreenhouseSource(),
    "lever": LeverSource(),
    "liepin": LiepinSource(),
    "boss": BossZhipinSource(),
    "zhaopin": ZhaopinSource(),
}

DEFAULT_SOURCE_KEYS = ("company_official", "web", "baidu", "tencent", "greenhouse")


def configured_source_keys(settings: Any) -> list[str]:
    raw = str(getattr(settings, "job_search_sources", "") or "").strip()
    if not raw:
        return list(DEFAULT_SOURCE_KEYS)
    result: list[str] = []
    for item in raw.split(","):
        key = item.strip().lower()
        if key in ALL_SOURCES and key not in result:
            result.append(key)
    if "web" in result and "company_official" not in result:
        result.insert(0, "company_official")
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


def configured_source_health(settings: Any) -> list[dict[str, str]]:
    provider_ready = bool(resolve_providers(settings))
    result: list[dict[str, str]] = []
    for key in configured_source_keys(settings):
        adapter = ALL_SOURCES[key]
        if not adapter.available:
            status, message = "UNAVAILABLE", "暂未接入"
        elif key in {"web", "company_official"} and not provider_ready:
            status, message = "UNCONFIGURED", "搜索服务未配置"
        else:
            status, message = "READY_TO_TRY", "将在检索时验证连接"
        result.append({"key": key, "name": adapter.name, "status": status, "message": message})
    return result
