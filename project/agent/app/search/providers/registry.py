from __future__ import annotations

from typing import Any

from .base import SearchProvider
from .brave import BraveProvider
from .tavily import TavilyProvider

ALL_PROVIDERS: dict[str, SearchProvider] = {
    "tavily": TavilyProvider(),
    "brave": BraveProvider(),
}
PRIMARY_PROVIDER = "tavily"


def resolve_providers(settings: Any) -> list[SearchProvider]:
    """Tavily 为主，Brave 在配置 Key 时作为独立补充；未配置则自动跳过。"""
    providers: list[SearchProvider] = []
    for key in (PRIMARY_PROVIDER, "brave"):
        provider = ALL_PROVIDERS[key]
        if provider.configured(settings):
            providers.append(provider)
    return providers


def provider_catalog(settings: Any) -> list[dict[str, Any]]:
    return [
        {
            "key": provider.key,
            "name": provider.name,
            "available": provider.configured(settings),
            "requiresCredentials": provider.requires_credentials,
        }
        for provider in ALL_PROVIDERS.values()
    ]