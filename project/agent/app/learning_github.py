from __future__ import annotations

import logging
import time
from datetime import datetime, timezone
from typing import Any

import httpx

from .config import Settings
from .schemas import GitHubCandidate, GitHubLookup

logger = logging.getLogger(__name__)

GITHUB_SEARCH_URL = "https://api.github.com/search/repositories"

_DIRECTION_QUERIES = {
    "JAVA_BACKEND": "language:Java",
    "FRONTEND": "language:TypeScript",
    "ALGORITHM": "language:Python topic:algorithm",
    "TEST": "language:Java topic:testing",
    "DATA": "language:Python topic:data-engineering",
}

_SKILL_TOPICS = {
    "JAVA": "java",
    "SPRING": "spring-boot",
    "SPRING_BOOT": "spring-boot",
    "MYSQL": "mysql",
    "REDIS": "redis",
    "SYSTEM_DESIGN": "system-design",
    "JAVASCRIPT": "javascript",
    "TYPESCRIPT": "typescript",
    "VUE": "vue",
    "REACT": "react",
    "PYTHON": "python",
    "ALGORITHM": "algorithm",
    "MACHINE_LEARNING": "machine-learning",
    "DOCKER": "docker",
    "SQL": "sql",
    "TEST": "testing",
}

_MIN_STARS = 100
_cache: dict[str, tuple[float, GitHubLookup]] = {}


def _query(direction: str | None, skill_codes: list[str]) -> str:
    base = _DIRECTION_QUERIES.get(direction or "", "language:Java")
    topics: list[str] = []
    for code in skill_codes:
        topic = _SKILL_TOPICS.get(str(code).upper())
        if topic and topic not in topics:
            topics.append(topic)
    if topics:
        base = f"{base} topic:{topics[0]}"
    return f"{base} stars:>={_MIN_STARS} pushed:>2024-01-01"


def _candidate(raw: dict[str, Any], verified_at: datetime) -> GitHubCandidate | None:
    if raw.get("archived") or raw.get("disabled") or raw.get("fork"):
        return None
    full_name = str(raw.get("full_name") or "").strip()
    html_url = str(raw.get("html_url") or "").strip()
    description = str(raw.get("description") or "").strip()
    if not full_name or not html_url or not description:
        return None
    if not html_url.lower().startswith("https://github.com/"):
        return None
    pushed_at = raw.get("pushed_at")
    return GitHubCandidate(
        full_name=full_name,
        html_url=html_url,
        description=description[:1000],
        stargazers_count=int(raw.get("stargazers_count") or 0),
        language=(str(raw["language"])[:64] if raw.get("language") else None),
        pushed_at=pushed_at,
        topics=[str(item)[:64] for item in (raw.get("topics") or [])][:20],
        verified_at=verified_at,
    )


async def search_github_repositories(
    settings: Settings,
    direction: str | None,
    skill_codes: list[str],
) -> GitHubLookup:
    """从 GitHub 公开检索接口获取真实仓库候选；失败时返回可降级的状态。"""
    now = datetime.now(timezone.utc)
    if not settings.github_search_enabled:
        return GitHubLookup(status="DISABLED", fetched_at=now, reason="GitHub 检索未启用")

    query = _query(direction, skill_codes)
    cache_key = f"{query}|{settings.github_search_per_page}"
    cached = _cache.get(cache_key)
    if cached and time.monotonic() - cached[0] < settings.github_search_cache_ttl_seconds:
        return cached[1].model_copy(deep=True)

    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": settings.github_search_user_agent,
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if settings.github_token:
        headers["Authorization"] = f"Bearer {settings.github_token}"
    params = {
        "q": query,
        "sort": "stars",
        "order": "desc",
        "per_page": str(settings.github_search_per_page),
    }
    try:
        async with httpx.AsyncClient(
            timeout=settings.github_search_timeout_seconds,
            trust_env=settings.github_search_trust_env,
        ) as client:
            response = await client.get(GITHUB_SEARCH_URL, params=params, headers=headers)
        if response.status_code != 200:
            reason = "GitHub 检索被限流，请稍后重试" if response.status_code in {403, 429} else (
                f"GitHub 检索失败（HTTP {response.status_code}）"
            )
            logger.warning("github search unavailable status=%s query=%s", response.status_code, query)
            return GitHubLookup(status="UNAVAILABLE", fetched_at=now, reason=reason)
        payload = response.json()
    except httpx.HTTPError as exc:
        logger.warning("github search request failed error=%s", type(exc).__name__)
        return GitHubLookup(status="UNAVAILABLE", fetched_at=now, reason="GitHub 检索请求失败，请稍后重试")

    items = payload.get("items") if isinstance(payload, dict) else None
    candidates: list[GitHubCandidate] = []
    for raw in items or []:
        if not isinstance(raw, dict):
            continue
        candidate = _candidate(raw, now)
        if candidate is None:
            continue
        if any(item.html_url.lower() == candidate.html_url.lower() for item in candidates):
            continue
        candidates.append(candidate)
    result = (
        GitHubLookup(status="AVAILABLE", fetched_at=now, candidates=candidates)
        if candidates
        else GitHubLookup(status="UNAVAILABLE", fetched_at=now, reason="GitHub 检索未返回可用仓库")
    )
    _cache[cache_key] = (time.monotonic(), result)
    logger.info(
        "github search done status=%s query=%s candidates=%s",
        result.status,
        query,
        len(result.candidates),
    )
    return result.model_copy(deep=True)