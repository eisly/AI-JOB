from __future__ import annotations

import asyncio
import logging
import re
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urlsplit

import httpx

from .config import Settings

logger = logging.getLogger(__name__)

REPO_PLATFORMS = ("GITHUB", "GITEE", "GITCODE")
MAX_TECH_STACK_KEYWORDS = 5
MAX_TECH_STACK_KEYWORD_LENGTH = 64

GITHUB_SEARCH_URL = "https://api.github.com/search/repositories"
GITHUB_API_BASE = "https://api.github.com/repos"
GITEE_SEARCH_URL = "https://gitee.com/api/v5/search/repositories"
GITEE_API_BASE = "https://gitee.com/api/v5/repos"
GITCODE_SEARCH_URL = "https://api.gitcode.com/api/v5/search/repositories"
GITCODE_API_BASE = "https://api.gitcode.com/api/v5/repos"

_PLATFORM_HOSTS = {
    "GITHUB": "github.com",
    "GITEE": "gitee.com",
    "GITCODE": "gitcode.com",
}

# SPDX License List 中标记为 OSI 批准的常见许可证。检索层只接受可归一化到这些 ID 的结果。
OSI_SPDX_LICENSES = frozenset(
    {
        "0BSD",
        "AFL-3.0",
        "AGPL-3.0",
        "Apache-1.1",
        "Apache-2.0",
        "Artistic-2.0",
        "BSD-2-Clause",
        "BSD-3-Clause",
        "BSL-1.0",
        "CDDL-1.0",
        "EPL-1.0",
        "EPL-2.0",
        "EUPL-1.2",
        "GPL-2.0",
        "GPL-3.0",
        "ISC",
        "LGPL-2.1",
        "LGPL-3.0",
        "MIT",
        "MPL-2.0",
        "MS-PL",
        "MS-RL",
        "NCSA",
        "OFL-1.1",
        "PostgreSQL",
        "Python-2.0",
        "Ruby",
        "Unlicense",
        "Zlib",
    }
)

_LICENSE_ALIASES = {
    "mit": "MIT",
    "mit license": "MIT",
    "the mit license": "MIT",
    "apache 2 0": "Apache-2.0",
    "apache 20": "Apache-2.0",
    "apache 2": "Apache-2.0",
    "apache license 2 0": "Apache-2.0",
    "apache license 20": "Apache-2.0",
    "apache license version 2 0": "Apache-2.0",
    "apache license": "Apache-2.0",
    "apache software license 2 0": "Apache-2.0",
    "bsd 2 clause": "BSD-2-Clause",
    "bsd 2 clause simplified": "BSD-2-Clause",
    "bsd 3 clause": "BSD-3-Clause",
    "bsd 3 clause new or revised": "BSD-3-Clause",
    "gpl 2 0": "GPL-2.0",
    "gpl 2": "GPL-2.0",
    "gnu general public license v2 0": "GPL-2.0",
    "gnu general public license 2 0": "GPL-2.0",
    "gpl 3 0": "GPL-3.0",
    "gpl 3": "GPL-3.0",
    "gnu general public license v3 0": "GPL-3.0",
    "gnu general public license 3 0": "GPL-3.0",
    "lgpl 2 1": "LGPL-2.1",
    "gnu lesser general public license v2 1": "LGPL-2.1",
    "gnu lesser general public license 2 1": "LGPL-2.1",
    "lgpl 3 0": "LGPL-3.0",
    "gnu lesser general public license v3 0": "LGPL-3.0",
    "gnu lesser general public license 3 0": "LGPL-3.0",
    "agpl 3 0": "AGPL-3.0",
    "gnu affero general public license v3 0": "AGPL-3.0",
    "gnu affero general public license 3 0": "AGPL-3.0",
    "gplv2": "GPL-2.0",
    "gplv3": "GPL-3.0",
    "lgplv2 1": "LGPL-2.1",
    "lgplv3": "LGPL-3.0",
    "agplv3": "AGPL-3.0",
    "apache2": "Apache-2.0",
    "mpl 2 0": "MPL-2.0",
    "mozilla public license 2 0": "MPL-2.0",
    "epl 1 0": "EPL-1.0",
    "epl 2 0": "EPL-2.0",
    "eclipse public license 2 0": "EPL-2.0",
    "isc": "ISC",
    "isc license": "ISC",
    "0bsd": "0BSD",
    "zlib": "Zlib",
    "zlib license": "Zlib",
    "unlicense": "Unlicense",
    "the unlicense": "Unlicense",
    "bsl 1 0": "BSL-1.0",
    "boost software license 1 0": "BSL-1.0",
    "artistic 2 0": "Artistic-2.0",
    "postgresql": "PostgreSQL",
    "ncsa": "NCSA",
    "ofl 1 1": "OFL-1.1",
    "sil open font license 1 1": "OFL-1.1",
    "ms pl": "MS-PL",
    "ms rl": "MS-RL",
    "python 2 0": "Python-2.0",
    "ruby": "Ruby",
    "cddl 1 0": "CDDL-1.0",
    "afl 3 0": "AFL-3.0",
    "eupl 1 2": "EUPL-1.2",
}

_SKILL_TERMS: dict[str, tuple[str, tuple[str, ...]]] = {
    "JAVA": ("java", ("java",)),
    "SPRING": ("spring", ("spring",)),
    "SPRING_BOOT": ("spring boot", ("spring boot", "springboot", "spring-boot")),
    "MYSQL": ("mysql", ("mysql",)),
    "REDIS": ("redis", ("redis",)),
    "SYSTEM_DESIGN": ("system design", ("system design", "system-design")),
    "JAVASCRIPT": ("javascript", ("javascript", "js")),
    "TYPESCRIPT": ("typescript", ("typescript",)),
    "VUE": ("vue", ("vue", "vuejs")),
    "REACT": ("react", ("react", "reactjs")),
    "PYTHON": ("python", ("python",)),
    "ALGORITHM": ("algorithm", ("algorithm", "algorithms")),
    "MACHINE_LEARNING": ("machine learning", ("machine learning", "machine-learning")),
    "DOCKER": ("docker", ("docker",)),
    "SQL": ("sql", ("sql",)),
    "TEST": ("testing", ("testing", "test", "pytest", "junit")),
}


class RepositorySourcesUnavailable(RuntimeError):
    """所选平台全部检索失败。"""


@dataclass
class RepoHit:
    platform: str
    path: str
    name: str
    url: str
    description: str
    stars: int
    default_branch: str | None
    license_hint: Any = None
    readme_hint: str | None = None
    mirror_url: str | None = None
    topics: list[str] = field(default_factory=list)
    language: str | None = None
    matched_skill_codes: list[str] = field(default_factory=list)
    license: str | None = None
    readme_url: str | None = None
    alternate_sources: list["RepoHit"] = field(default_factory=list)
    updated_at: str = ""
    stack_evidence: list[dict[str, str]] = field(default_factory=list)

    def source_dict(self) -> dict[str, Any]:
        return {
            "platform": self.platform,
            "path": self.path,
            "url": self.url,
            "license": self.license or "",
            "readmeUrl": self.readme_url,
        }


@dataclass
class PlatformResult:
    platform: str
    status: str
    hits: list[RepoHit] = field(default_factory=list)
    reason: str | None = None
    has_next: bool = False


@dataclass
class RepositorySearchResult:
    source_runs: list[dict[str, Any]] = field(default_factory=list)
    candidates: list[RepoHit] = field(default_factory=list)
    terms: list[str] = field(default_factory=list)
    rejected: dict[str, int] = field(default_factory=dict)

    @property
    def available(self) -> bool:
        return bool(self.candidates)


def normalize_license(raw: Any) -> str | None:
    """把平台返回的许可证元数据归一化为 SPDX ID，仅保留 OSI 批准的许可证。"""
    if isinstance(raw, dict):
        raw = raw.get("spdx_id") or raw.get("spdxId") or raw.get("key") or raw.get("name")
    if raw is None:
        return None
    text = str(raw).strip().lower()
    if not text or text in {"noassertion", "none", "other", "unknown", "no license"}:
        return None
    text = re.sub(r"[^a-z0-9]+", " ", text).strip()
    text = re.sub(r"\bv(?=\d)", " ", text)
    text = re.sub(r"\b(v|version)\b", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    spdx = _LICENSE_ALIASES.get(text)
    if spdx is None and text.replace(" ", "-") in OSI_SPDX_LICENSES:
        spdx = text.replace(" ", "-")
    if spdx is None:
        return None
    return spdx if spdx in OSI_SPDX_LICENSES else None


def _skill_words(code: str) -> tuple[str, tuple[str, ...]]:
    upper = code.strip().upper()
    entry = _SKILL_TERMS.get(upper)
    if entry:
        return entry
    lowered = upper.lower().replace("_", " ").strip()
    return lowered, (lowered, lowered.replace(" ", ""))


def _contains_word(text: str, word: str) -> bool:
    lowered = text.lower()
    token = word.lower().strip()
    if not token:
        return False
    if not token.isascii():
        return token in lowered
    pattern = r"(?<![a-z0-9])" + re.escape(token) + r"(?![a-z0-9])"
    return re.search(pattern, lowered) is not None


def _normalized_keyword_text(value: str) -> str:
    """忽略大小写并把空格、连字符与下划线归一到同一形态，保留 C++ / C# 等符号。"""
    lowered = str(value or "").strip().lower()
    if not lowered:
        return ""
    normalized = re.sub(r"[-_\s]+", " ", lowered).strip()
    return re.sub(r"\s+", " ", normalized)


def _keyword_present(keyword: str, text: str) -> bool:
    token = _normalized_keyword_text(keyword)
    if not token:
        return False
    haystack = _normalized_keyword_text(text)
    if not haystack:
        return False
    if not token.isascii():
        return token in haystack
    pattern = r"(?<![a-z0-9])" + re.escape(token) + r"(?![a-z0-9])"
    return re.search(pattern, haystack) is not None


def clean_tech_stack_keywords(values: list[str] | None) -> list[str]:
    """统一去首尾空白、忽略大小写去重；超过 5 个或 64 字符时抛 ValueError。"""
    cleaned: list[str] = []
    for raw in values or []:
        keyword = str(raw).strip()
        if not keyword:
            raise ValueError("技术栈关键词不能为空白")
        if len(keyword) > MAX_TECH_STACK_KEYWORD_LENGTH:
            raise ValueError("单个技术栈关键词不能超过 64 个字符")
        if all(keyword.lower() != item.lower() for item in cleaned):
            cleaned.append(keyword)
    if len(cleaned) > MAX_TECH_STACK_KEYWORDS:
        raise ValueError("技术栈关键词最多 5 个")
    return cleaned


def _matches_tech_stack(hit: RepoHit, keywords: list[str]) -> bool:
    """仓库名称、简介、主题标签与主语言中，每个关键词至少命中一项资料。"""
    if not keywords:
        return True
    fields = [
        hit.name,
        hit.path.rsplit("/", 1)[-1],
        hit.description,
        *hit.topics,
        hit.language or "",
    ]
    return all(
        any(_keyword_present(keyword, field) for field in fields)
        for keyword in keywords
    )


def _append_term(terms: list[tuple[str, tuple[str, ...]]], term: str, code: str | None = None) -> None:
    cleaned = (term or "").strip()
    if not cleaned:
        return
    if all(cleaned.lower() != existing.lower() for existing, _ in terms):
        terms.append((cleaned, (code,) if code else ()))


def build_query_terms(query: str | None, skill_codes: list[str],
                      tech_stack_keywords: list[str] | None = None) -> list[tuple[str, tuple[str, ...]]]:
    """构造最多 3 个检索词；填写技术栈时优先组合词与技术栈词。"""
    terms: list[tuple[str, tuple[str, ...]]] = []
    cleaned_query = (query or "").strip()
    tech_keywords = [str(item).strip() for item in (tech_stack_keywords or []) if str(item).strip()]
    if tech_keywords:
        joined_tech = " ".join(tech_keywords)
        if cleaned_query:
            _append_term(terms, f"{cleaned_query} {joined_tech}")
        _append_term(terms, joined_tech)
    elif cleaned_query:
        _append_term(terms, cleaned_query)
    for code in skill_codes:
        if len(terms) >= 3:
            break
        term, _ = _skill_words(code)
        _append_term(terms, term, code.strip().upper())
    return terms[:3]


def _matched_skills(text: str, skill_codes: list[str]) -> list[str]:
    result: list[str] = []
    for code in skill_codes:
        _, words = _skill_words(code)
        if any(_contains_word(text, word) for word in words):
            result.append(code)
    return result


def _repo_path(url: str, platform: str) -> str | None:
    try:
        parsed = urlsplit(url.strip())
        port = parsed.port
    except ValueError:
        return None
    if (parsed.scheme != "https" or parsed.hostname != _PLATFORM_HOSTS[platform]
            or parsed.username is not None or port not in (None, 443)
            or parsed.query or parsed.fragment):
        return None
    path = parsed.path.strip("/")
    if path.endswith(".git"):
        path = path[:-4]
    parts = path.split("/")
    if len(parts) < 2 or (platform != "GITCODE" and len(parts) != 2):
        return None
    if any(not re.fullmatch(r"[\w.-]+", part) or part in {".", ".."} for part in parts):
        return None
    return path


def _valid_readme(url: Any, platform: str, path: str) -> str | None:
    if not url:
        return None
    text = str(url).strip()
    try:
        parsed = urlsplit(text)
    except ValueError:
        return None
    if parsed.scheme.lower() != "https":
        return None
    if (parsed.hostname or "").lower() != _PLATFORM_HOSTS.get(platform):
        return None
    if not parsed.path.strip("/").lower().startswith(path.strip("/").lower() + "/"):
        return None
    return text


def _base_hit(platform: str, path: str, name: str, url: str, description: Any,
              stars: Any, default_branch: Any, raw_topics: Any, skill_codes: list[str],
              license_raw: Any, readme_raw: Any, mirror_url: Any = None,
              language: Any = None) -> RepoHit | None:
    summary = str(description or "").strip()
    if not summary:
        return None
    if not path or not name:
        return None
    topics = [str(item).strip() for item in (raw_topics or []) if str(item).strip()][:20]
    text = " ".join([path, name, summary, " ".join(topics)])
    return RepoHit(
        platform=platform,
        path=path,
        name=name[:120],
        url=url,
        description=summary[:2000],
        stars=int(stars or 0),
        default_branch=str(default_branch).strip() if default_branch else None,
        license_hint=license_raw,
        readme_hint=str(readme_raw).strip() if readme_raw else None,
        mirror_url=mirror_url if isinstance(mirror_url, str) else None,
        topics=topics,
        language=str(language).strip() if language else None,
        matched_skill_codes=_matched_skills(text, skill_codes),
    )


def _github_hit(raw: dict[str, Any], skill_codes: list[str]) -> RepoHit | None:
    if (raw.get("archived") or raw.get("disabled") or raw.get("fork")
            or raw.get("private") is not False or raw.get("visibility", "public") != "public"):
        return None
    full_name = str(raw.get("full_name") or "").strip()
    html_url = str(raw.get("html_url") or "").strip()
    path = _repo_path(html_url, "GITHUB")
    if not path or path.lower() != full_name.lower():
        return None
    license_raw = raw.get("license")
    return _base_hit(
        "GITHUB", path, str(raw.get("name") or path.split("/")[-1]), html_url,
        raw.get("description"), raw.get("stargazers_count"), raw.get("default_branch"),
        raw.get("topics"), skill_codes, license_raw, None, raw.get("mirror_url"),
        raw.get("language"),
    )


def _public_repository(raw: dict[str, Any]) -> bool:
    return (
        raw.get("public") in (True, 1)
        and raw.get("private") in (None, False, 0)
        and raw.get("internal") in (None, False, 0)
        and raw.get("visibility", "public") == "public"
    )


def _gitee_hit(raw: dict[str, Any], skill_codes: list[str]) -> RepoHit | None:
    if raw.get("fork") or not _public_repository(raw):
        return None
    full_name = str(raw.get("full_name") or "").strip()
    html_url = str(raw.get("html_url") or raw.get("web_url") or "").strip()
    path = _repo_path(html_url, "GITEE")
    if not path or path.lower() != full_name.lower():
        return None
    return _base_hit(
        "GITEE", path, str(raw.get("name") or path.split("/")[-1]), html_url,
        raw.get("description"), raw.get("stargazers_count") or raw.get("stars_count"),
        raw.get("default_branch"), raw.get("topics"), skill_codes,
        raw.get("license"), raw.get("readme_url"), raw.get("mirror_url"),
        raw.get("language"),
    )


def _gitcode_hit(raw: dict[str, Any], skill_codes: list[str]) -> RepoHit | None:
    status_text = str(raw.get("status") or "").strip().lower()
    if raw.get("fork") or not _public_repository(raw):
        return None
    if status_text in {"2", "关闭", "closed", "archived"}:
        return None
    full_name = str(raw.get("full_name") or "").strip()
    html_url = str(raw.get("web_url") or raw.get("html_url") or "").strip()
    path = _repo_path(html_url, "GITCODE")
    if not path or path.lower() != full_name.lower():
        return None
    return _base_hit(
        "GITCODE", path, str(raw.get("name") or path.split("/")[-1]), html_url,
        raw.get("description"), raw.get("stargazers_count") or raw.get("stars_count"),
        raw.get("default_branch"), raw.get("topics"), skill_codes,
        raw.get("license"), raw.get("readme_url"), raw.get("mirror_url"),
        raw.get("language"),
    )


def _status_reason(platform: str, status: int) -> str:
    if status in {401, 403}:
        return f"{platform} 检索鉴权失败或触发限流（HTTP {status}）"
    if status == 429:
        return f"{platform} 检索被限流（HTTP 429）"
    return f"{platform} 检索失败（HTTP {status}）"


def _list_payload(payload: Any) -> list[dict[str, Any]]:
    if isinstance(payload, list):
        return [item for item in payload if isinstance(item, dict)]
    if isinstance(payload, dict):
        for key in ("items", "data", "repositories"):
            value = payload.get(key)
            if isinstance(value, list):
                return [item for item in value if isinstance(item, dict)]
    raise ValueError("仓库检索响应缺少结果数组")


async def _search_github(client: httpx.AsyncClient, settings: Settings, term: str,
                         skill_codes: list[str], *, page: int = 1, discovery: bool = False,
                         recent: bool = False) -> PlatformResult:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": settings.repository_search_user_agent,
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if settings.github_token:
        headers["Authorization"] = f"Bearer {settings.github_token}"
    try:
        response = await client.get(
            GITHUB_SEARCH_URL,
            headers=headers,
            params={
                "q": f"{term} is:public",
                "sort": "stars",
                "order": "desc",
                "per_page": str(settings.repository_search_per_page),
            } if not discovery else {
                "q": f"{term} is:public", "page": page, "per_page": 8,
                **({"sort": "updated", "order": "desc"} if recent else {}),
            },
        )
    except httpx.HTTPError as exc:
        logger.warning("repository github request failed term=%s error=%s", term, type(exc).__name__)
        return PlatformResult("GITHUB", "FAILED", reason="GitHub 检索请求失败，请稍后重试")
    if response.status_code != 200:
        logger.warning("repository github unavailable term=%s status=%s", term, response.status_code)
        return PlatformResult("GITHUB", "FAILED", reason=_status_reason("GitHub", response.status_code))
    hits = []
    for raw in _list_payload(response.json())[:8 if discovery else None]:
        hit = _discovery_hit(_github_hit, raw, skill_codes) if discovery else _github_hit(raw, skill_codes)
        if hit:
            hits.append(hit)
    return PlatformResult("GITHUB", "SUCCEEDED", hits, has_next=discovery and "next" in response.links)


async def _search_gitee(client: httpx.AsyncClient, settings: Settings, term: str,
                        skill_codes: list[str], *, page: int = 1, discovery: bool = False,
                        recent: bool = False) -> PlatformResult:
    if not settings.gitee_token:
        return PlatformResult("GITEE", "FAILED", reason="Gitee 未配置服务端搜索令牌")
    params = {
        "q": term,
        "sort": "stars_count",
        "order": "desc",
        "per_page": str(settings.repository_search_per_page),
        "page": "1",
    }
    if settings.gitee_token:
        params["access_token"] = settings.gitee_token
    if discovery:
        params.update(page=str(page), per_page="8")
        if recent:
            params["sort"] = "updated_at"
        else:
            params.pop("sort")
    try:
        response = await client.get(GITEE_SEARCH_URL, params=params)
    except httpx.HTTPError as exc:
        logger.warning("repository gitee request failed term=%s error=%s", term, type(exc).__name__)
        return PlatformResult("GITEE", "FAILED", reason="Gitee 检索请求失败，请稍后重试")
    if response.status_code != 200:
        logger.warning("repository gitee unavailable term=%s status=%s", term, response.status_code)
        return PlatformResult("GITEE", "FAILED", reason=_status_reason("Gitee", response.status_code))
    hits = []
    rows = _list_payload(response.json())
    for raw in rows[:8 if discovery else None]:
        hit = _discovery_hit(_gitee_hit, raw, skill_codes) if discovery else _gitee_hit(raw, skill_codes)
        if hit:
            hits.append(hit)
    return PlatformResult("GITEE", "SUCCEEDED", hits, has_next=discovery and len(rows) >= 8)


async def _search_gitcode(client: httpx.AsyncClient, settings: Settings, term: str,
                          skill_codes: list[str], *, page: int = 1, discovery: bool = False,
                          recent: bool = False) -> PlatformResult:
    if not settings.gitcode_token:
        return PlatformResult("GITCODE", "FAILED", reason="GitCode 未配置服务端搜索令牌")
    try:
        response = await client.get(
            GITCODE_SEARCH_URL,
            params={
                "q": term,
                "access_token": settings.gitcode_token,
                "sort": "stars_count",
                "order": "desc",
                "per_page": str(settings.repository_search_per_page),
            } if not discovery else {
                "q": term, "access_token": settings.gitcode_token,
                "page": page, "per_page": 8,
                **({"sort": "last_push_at", "order": "desc"} if recent else {}),
            },
        )
    except httpx.HTTPError as exc:
        logger.warning("repository gitcode request failed term=%s error=%s", term, type(exc).__name__)
        return PlatformResult("GITCODE", "FAILED", reason="GitCode 检索请求失败，请稍后重试")
    if response.status_code != 200:
        logger.warning("repository gitcode unavailable term=%s status=%s", term, response.status_code)
        return PlatformResult("GITCODE", "FAILED", reason=_status_reason("GitCode", response.status_code))
    hits = []
    rows = _list_payload(response.json())
    for raw in rows[:8 if discovery else None]:
        hit = _discovery_hit(_gitcode_hit, raw, skill_codes) if discovery else _gitcode_hit(raw, skill_codes)
        if hit:
            hits.append(hit)
    return PlatformResult("GITCODE", "SUCCEEDED", hits, has_next=discovery and len(rows) >= 8)


def _discovery_hit(parse, raw: dict[str, Any], skills: list[str]) -> RepoHit | None:
    hit = parse({**raw, "description": raw.get("description") or "仓库未提供简介"}, skills)
    if hit:
        hit.updated_at = str(raw.get("pushed_at") or raw.get("updated_at") or "")
    return hit


async def _search_platform(client: httpx.AsyncClient, settings: Settings, platform: str,
                           terms: list[tuple[str, tuple[str, ...]]],
                           skill_codes: list[str]) -> PlatformResult:
    searcher = {
        "GITHUB": _search_github,
        "GITEE": _search_gitee,
        "GITCODE": _search_gitcode,
    }[platform]
    hits: list[RepoHit] = []
    failures: list[str] = []
    succeeded = False
    for term, _ in terms:
        try:
            result = await searcher(client, settings, term, skill_codes)
        except ValueError as exc:
            logger.warning("repository search invalid response platform=%s error=%s", platform, type(exc).__name__)
            failures.append(f"{platform} 检索返回了无法解析的响应")
            continue
        if result.status == "SUCCEEDED":
            succeeded = True
            hits.extend(result.hits)
        else:
            failures.append(result.reason or f"{platform} 检索失败")
    if not succeeded:
        return PlatformResult(platform, "FAILED", reason=failures[0] if failures else f"{platform} 检索失败")
    return PlatformResult(platform, "SUCCEEDED", hits)


def _merge_hits(hits: list[RepoHit], selected_platforms: list[str]) -> list[RepoHit]:
    priority = {platform: index for index, platform in enumerate(selected_platforms)}
    by_path = {(hit.platform, hit.path.lower()): hit for hit in hits}
    mirror_targets = {}
    for key, hit in by_path.items():
        if hit.mirror_url:
            for platform in selected_platforms:
                if platform != hit.platform and (path := _repo_path(hit.mirror_url, platform)):
                    target = (platform, path.lower())
                    if target in by_path:
                        mirror_targets[key] = target
                    break

    groups: dict[tuple[str, str], list[RepoHit]] = {}
    for key, hit in by_path.items():
        target = mirror_targets.get(key, key)
        if mirror_targets.get(target) == key:
            target = min(key, target)
        elif target in mirror_targets:
            target = key
        groups.setdefault(target, []).append(hit)
    merged: list[RepoHit] = []
    for bucket in groups.values():
        bucket.sort(key=lambda item: (
            priority.get(item.platform, len(priority)),
            0 if item.readme_url else 1,
        ))
        main = bucket[0]
        main.alternate_sources = bucket[1:]
        merged.append(main)
    return merged


async def _github_detail(client: httpx.AsyncClient, settings: Settings, hit: RepoHit) -> tuple[Any, str | None]:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": settings.repository_search_user_agent,
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if settings.github_token:
        headers["Authorization"] = f"Bearer {settings.github_token}"
    license_raw: Any = hit.license_hint
    readme: str | None = hit.readme_hint
    if not normalize_license(license_raw):
        try:
            response = await client.get(f"{GITHUB_API_BASE}/{hit.path}/license", headers=headers)
            if response.status_code == 200:
                payload = response.json()
                license_raw = payload.get("license") if isinstance(payload, dict) else None
        except (httpx.HTTPError, ValueError):
            pass
    if not readme:
        try:
            response = await client.get(f"{GITHUB_API_BASE}/{hit.path}/readme", headers=headers)
            if response.status_code == 200:
                payload = response.json()
                if isinstance(payload, dict):
                    readme = payload.get("html_url")
        except (httpx.HTTPError, ValueError):
            pass
    return license_raw, readme


async def _gitee_detail(client: httpx.AsyncClient, settings: Settings, hit: RepoHit) -> tuple[Any, str | None]:
    params = {"access_token": settings.gitee_token} if settings.gitee_token else {}
    license_raw: Any = hit.license_hint
    readme: str | None = hit.readme_hint
    try:
        response = await client.get(f"{GITEE_API_BASE}/{hit.path}", params=params)
        if response.status_code == 200 and isinstance(response.json(), dict):
            payload = response.json()
            license_raw = payload.get("license") or license_raw
            readme = payload.get("readme_url") or readme
    except (httpx.HTTPError, ValueError):
        pass
    return license_raw, readme


async def _gitcode_detail(client: httpx.AsyncClient, settings: Settings, hit: RepoHit) -> tuple[Any, str | None]:
    license_raw: Any = hit.license_hint
    readme: str | None = hit.readme_hint
    try:
        response = await client.get(
            f"{GITCODE_API_BASE}/{hit.path}",
            params={"access_token": settings.gitcode_token},
        )
        if response.status_code == 200 and isinstance(response.json(), dict):
            payload = response.json()
            license_raw = payload.get("license") or license_raw
            readme = payload.get("readme_url") or readme
    except (httpx.HTTPError, ValueError):
        pass
    return license_raw, readme


async def _enrich_license(client: httpx.AsyncClient, settings: Settings, hit: RepoHit) -> bool:
    hit.license = normalize_license(hit.license_hint)
    hit.readme_url = _valid_readme(hit.readme_hint, hit.platform, hit.path)
    if hit.license:
        return True
    detail = {
        "GITHUB": _github_detail,
        "GITEE": _gitee_detail,
        "GITCODE": _gitcode_detail,
    }[hit.platform]
    license_raw, readme = await detail(client, settings, hit)
    hit.license = normalize_license(license_raw)
    if not hit.readme_url and readme:
        hit.readme_url = _valid_readme(readme, hit.platform, hit.path)
    return hit.license is not None


async def _enrich_readme(client: httpx.AsyncClient, settings: Settings, hit: RepoHit) -> None:
    if hit.readme_url:
        return
    detail = {
        "GITHUB": _github_detail,
        "GITEE": _gitee_detail,
        "GITCODE": _gitcode_detail,
    }[hit.platform]
    _, readme = await detail(client, settings, hit)
    hit.readme_url = _valid_readme(readme, hit.platform, hit.path)


def _sorted(hits: list[RepoHit]) -> list[RepoHit]:
    return sorted(
        hits,
        key=lambda item: (
            -len(item.matched_skill_codes),
            -item.stars,
            item.platform,
            item.path.lower(),
        ),
    )


async def search_project_repositories(
    settings: Settings,
    skill_codes: list[str],
    query: str | None = None,
    platforms: list[str] | None = None,
    tech_stack_keywords: list[str] | None = None,
    client: httpx.AsyncClient | None = None,
) -> RepositorySearchResult:
    """三平台并行检索公开仓库，按许可证、技术栈、简介、去重与技能覆盖筛选候选。"""
    selected = [platform for platform in REPO_PLATFORMS
                if not platforms or platform in {item.strip().upper() for item in platforms}]
    if not selected:
        selected = list(REPO_PLATFORMS)
    tech_keywords = clean_tech_stack_keywords(tech_stack_keywords)
    terms = build_query_terms(query, skill_codes, tech_keywords)
    if not terms:
        terms = [("java", ())]
    owns_client = client is None
    if client is None:
        client = httpx.AsyncClient(
            timeout=settings.repository_search_timeout_seconds,
            trust_env=settings.repository_search_trust_env,
        )
    try:
        results = await asyncio.gather(*[
            _search_platform(client, settings, platform, terms, skill_codes)
            for platform in selected
        ])
        source_runs = [
            {"platform": result.platform, "status": result.status, "reason": result.reason}
            for result in results
        ]
        if all(result.status == "FAILED" for result in results):
            reasons = "；".join(result.reason or result.platform for result in results)
            raise RepositorySourcesUnavailable(reasons)

        hits = {(hit.platform, hit.path.lower()): hit for result in results for hit in result.hits}
        rejected: dict[str, int] = {}
        if tech_keywords:
            for key, hit in list(hits.items()):
                if not _matches_tech_stack(hit, tech_keywords):
                    rejected["TECH_STACK_MISMATCH"] = rejected.get("TECH_STACK_MISMATCH", 0) + 1
                    del hits[key]
        if hits:
            for hit in hits.values():
                if not await _enrich_license(client, settings, hit):
                    rejected["LICENSE_NOT_CONFIRMED"] = rejected.get("LICENSE_NOT_CONFIRMED", 0) + 1
        qualified = [hit for hit in hits.values() if hit.license]
        hits = _sorted(_merge_hits(qualified, selected))[:settings.repository_search_max_candidates]
        if hits:
            await asyncio.gather(*[_enrich_readme(client, settings, hit) for hit in hits])
        logger.info(
            "repository search done terms=%s tech_keywords=%s source_runs=%s candidates=%s rejected=%s",
            len(terms),
            len(tech_keywords),
            ",".join(f"{item['platform']}:{item['status']}" for item in source_runs),
            len(hits),
            rejected or "-",
        )
        return RepositorySearchResult(
            source_runs=source_runs,
            candidates=hits,
            terms=[term for term, _ in terms],
            rejected=rejected,
        )
    finally:
        if owns_client:
            await client.aclose()
