from __future__ import annotations

import asyncio
import logging
import re
import time
from datetime import datetime, timezone
from typing import Any

import httpx

from .config import Settings
from .schemas import GitHubCandidate, GitHubLookup
from .project_selection import select_repositories

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
    "FASTAPI": "fastapi",
    "GO": "go",
    "SQL": "sql",
    "TEST": "testing",
}

_PROJECT_SKILL_LANGUAGES = {
    "JAVA": "Java",
    "PYTHON": "Python",
    "GO": "Go",
    "JAVASCRIPT": "JavaScript",
    "TYPESCRIPT": "TypeScript",
    "RUST": "Rust",
}

_PROJECT_SKILL_ALIASES = {
    "SPRING": ("spring", "spring-boot", "spring boot", "springboot"),
    "SPRING_BOOT": ("spring-boot", "spring boot", "springboot"),
    "GO": ("go", "golang"),
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


def _candidate(
    raw: dict[str, Any],
    verified_at: datetime,
    matched_skill_codes: list[str] | None = None,
) -> GitHubCandidate | None:
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
        matched_skill_codes=matched_skill_codes or [],
        verified_at=verified_at,
    )


def _project_query(direction: str | None, skill_codes: list[str]) -> str:
    """先查询技能交集；不使用互斥的多主语言过滤条件。"""
    terms: list[str] = []
    for raw in skill_codes:
        code = str(raw).upper()
        topic = _SKILL_TOPICS.get(code)
        language = _PROJECT_SKILL_LANGUAGES.get(code)
        term = (f"language:{language}" if language and len(skill_codes) == 1
                else f"topic:{topic}" if topic else (f"language:{language}" if language else None))
        if term and term not in terms:
            terms.append(term)
    if not terms:
        base = _DIRECTION_QUERIES.get(direction or "", "language:Java")
    else:
        base = " ".join(terms)
    return f"{base} stars:>={_MIN_STARS} pushed:>2024-01-01"


def _matched_skills(candidate: GitHubCandidate, skill_codes: list[str]) -> list[str]:
    topics = {str(item).strip().lower() for item in candidate.topics}
    searchable = f"{candidate.full_name.rsplit('/', 1)[-1]} {candidate.description}".lower()
    language = (candidate.language or "").lower()
    matched: list[str] = []
    for raw in skill_codes:
        code = str(raw).upper()
        topic = _SKILL_TOPICS.get(code, code.lower().replace("_", "-"))
        aliases = _PROJECT_SKILL_ALIASES.get(code, (topic,))
        expected_language = _PROJECT_SKILL_LANGUAGES.get(code)
        topic_match = any(
            alias in topics or re.search(r"(?<![a-z0-9])" + re.escape(alias) + r"(?![a-z0-9])", searchable)
            for alias in aliases
        )
        language_match = bool(expected_language and language == expected_language.lower())
        if topic_match or language_match:
            matched.append(code)
    return matched


def _is_practice_repository(candidate: GitHubCandidate) -> bool:
    """排除明确的资料合集；这是元数据筛选，不代表已验证仓库可运行。"""
    name = candidate.full_name.rsplit("/", 1)[-1].lower()
    topics = {str(topic).strip().lower() for topic in candidate.topics}
    if topics & {"awesome", "awesome-list", "awesome-lists", "cheatsheet", "cheat-sheet"}:
        return False
    if re.search(r"^(?:awesome(?:[-_]|$)|javaguide$)|"
                 r"(?:interview[-_](?:questions?|guide|handbook|notes)|cheat[-_]?sheets?)", name):
        return False
    description = candidate.description.lower()
    if re.search(r"(?:面试.{0,24}(?:指南|知识点|资料合集)|"
                 r"(?:知识|资源|资料|教程)合集|速查[表手册]|"
                 r"\b(?:curated\s+(?:list|collection)|awesome\s+list|"
                 r"interview\s+(?:questions?|guide|handbook|notes)|cheat\s*sheets?)\b)", description):
        return False
    return True


async def _search_query(
    settings: Settings,
    query: str,
    matched_skill_codes: list[str] | None = None,
) -> GitHubLookup:
    now = datetime.now(timezone.utc)
    matched_key = ",".join(sorted(matched_skill_codes or []))
    cache_key = f"{query}|{settings.github_search_per_page}|{matched_key}"
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
    except (httpx.HTTPError, ValueError) as exc:
        logger.warning("github search request failed error=%s", type(exc).__name__)
        return GitHubLookup(status="UNAVAILABLE", fetched_at=now, reason="GitHub 检索请求失败，请稍后重试")

    items = payload.get("items") if isinstance(payload, dict) else None
    candidates: list[GitHubCandidate] = []
    for raw in items or []:
        if not isinstance(raw, dict):
            continue
        candidate = _candidate(raw, now, matched_skill_codes)
        if candidate is None:
            continue
        if any(item.html_url.lower() == candidate.html_url.lower() for item in candidates):
            continue
        candidates.append(candidate)
        if len(candidates) >= 10:
            break
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
    return await _search_query(settings, query)


async def search_project_repositories(
    settings: Settings,
    direction: str | None,
    skill_codes: list[str],
) -> GitHubLookup:
    """检索技能并按仓库可覆盖的技能交集排序，优先返回覆盖最多技能的项目。"""
    now = datetime.now(timezone.utc)
    if not settings.github_search_enabled:
        return GitHubLookup(status="DISABLED", fetched_at=now, reason="GitHub 检索未启用")

    codes = []
    for raw in skill_codes:
        code = str(raw).strip().upper()
        if code and code not in codes:
            codes.append(code)
    result = await _search_query(settings, _project_query(direction, codes))
    if result.status != "AVAILABLE" and result.reason != "GitHub 检索未返回可用仓库":
        return result
    # 资料仓库不能计入候选数量或完整覆盖，否则可能阻止后续扩大检索。
    pool = [candidate for candidate in result.candidates if _is_practice_repository(candidate)]
    has_full_coverage = any(len(_matched_skills(candidate, codes)) == len(codes) for candidate in pool)
    # 交集不足时扩大召回池，最终按单仓库覆盖率统一排序，不按技能分配推荐名额。
    if len(codes) > 1 and (not has_full_coverage or len(pool) < 3):
        queries = []
        for code in codes:
            language = _PROJECT_SKILL_LANGUAGES.get(code)
            query = (f"language:{language} stars:>={_MIN_STARS} pushed:>2024-01-01"
                     if language else _project_query(direction, [code]))
            if query not in queries:
                queries.append(query)
        lookups = await asyncio.gather(*(_search_query(settings, query) for query in queries))
        for lookup in lookups:
            pool.extend(candidate for candidate in lookup.candidates if _is_practice_repository(candidate))

    scored_by_url: dict[str, GitHubCandidate] = {}
    for candidate in pool:
        matched = _matched_skills(candidate, codes)
        if matched:
            key = candidate.html_url.lower().rstrip("/")
            scored_by_url[key] = candidate.model_copy(update={"matched_skill_codes": matched})
    scored = list(scored_by_url.values())
    scored.sort(key=lambda item: (-len(item.matched_skill_codes), -item.stargazers_count, item.full_name.lower()))
    if not scored:
        return GitHubLookup(status="UNAVAILABLE", fetched_at=now,
                            reason=result.reason or "GitHub 检索结果未覆盖所选技能")
    candidates = select_repositories(scored, codes, limit=10)
    logger.info("github project selection pool=%s candidates=%s covered=%s targets=%s",
                len(scored), len(candidates),
                len({code for item in candidates for code in item.matched_skill_codes}), len(codes))
    return GitHubLookup(status="AVAILABLE", fetched_at=now, candidates=candidates)
