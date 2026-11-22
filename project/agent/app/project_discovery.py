"""Bounded discovery of applications with evidence for every requested technology."""

from __future__ import annotations

import asyncio
import base64
import binascii
import json
import re
import tomllib
from urllib.parse import quote
from xml.etree import ElementTree

import httpx

from . import project_sources as source
from .config import Settings

_SPRING = re.compile(r"^spring[\s_-]*boot$", re.I)
_JAVA_FILES = ("pom.xml", "build.gradle", "build.gradle.kts")
_OTHER_FILES = ("package.json", "pyproject.toml", "requirements.txt", "go.mod", *_JAVA_FILES)
_MAX_FILE = 96 * 1024
_CATEGORIES = (
    ("commerce", r"mall|shop|e[- ]?commerce|电商|商城|购物"),
    ("management", r"management|admin|erp|cms|管理|后台|内容系统"),
    ("collaboration", r"chat|collab|forum|social|聊天|协作|论坛|社交"),
    ("services", r"reservation|booking|delivery|payment|预约|订餐|物流|支付"),
    ("vertical", r"library|school|hospital|hotel|图书|校园|医院|酒店"),
)


class RepositoryFileLimited(RuntimeError):
    pass


def _aliases(keyword: str) -> tuple[str, ...]:
    return ("Spring Boot", "spring-boot", "springboot") if _SPRING.fullmatch(keyword) else (keyword,)


def _mentions(keyword: str, text: str) -> bool:
    return any(source._keyword_present(alias, text) for alias in _aliases(keyword))


def _readme_claim(keyword: str, readme: str) -> bool:
    stack_section = False
    for line in readme.splitlines():
        if line.lstrip().startswith("#"):
            stack_section = bool(re.search(r"tech(?:nology)? stack|technologies|dependencies|技术栈|技术选型|主要技术", line, re.I))
        if not _mentions(keyword, line):
            continue
        if re.search(r"alternative|compared|unlike|without|versus|instead of|not use|doesn't use|对比|不使用|替代", line, re.I):
            continue
        if stack_section or re.search(r"built (?:with|on|using)|powered by|uses?\s|using\s|stack\s*[:：]|基于|采用|使用|技术栈", line, re.I):
            return True
    return False


def _manifest(path: str, text: str) -> tuple[set[str], list[str]]:
    """Return declared dependency names and local modules without evaluating build code."""
    name = path.rsplit("/", 1)[-1]
    try:
        if name == "pom.xml":
            if re.search(r"<!\s*(DOCTYPE|ENTITY)", text, re.I):
                return set(), []
            root = ElementTree.fromstring(text)
            for element in root.iter():
                element.tag = element.tag.rsplit("}", 1)[-1]
            declarations = [
                *root.findall("./dependencies/dependency"), *root.findall("./build/plugins/plugin"),
                *root.findall("./parent"),
            ]
            packages = set()
            for item in declarations:
                artifact = (item.findtext("artifactId") or "").strip()
                group = (item.findtext("groupId") or "").strip()
                if artifact:
                    packages.add(artifact)
                if group and artifact:
                    packages.add(f"{group}:{artifact}")
            return packages, [item.text or "" for item in root.findall("./modules/module")]
        if name.startswith("build.gradle"):
            text = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)
            packages = set(re.findall(r"""\b(?:id|implementation|api|runtimeOnly|compileOnly)\s*\(?\s*['"]([^'"]+)""", text))
            return packages, []
        if name == "package.json":
            data = json.loads(text)
            return set(data.get("dependencies", {})) | set(data.get("optionalDependencies", {})), []
        if name == "pyproject.toml":
            data = tomllib.loads(text)
            names = data.get("project", {}).get("dependencies", [])
            poetry = data.get("tool", {}).get("poetry", {}).get("dependencies", {})
            return set(poetry) | set(re.findall(r"(?m)^([\w.-]+)", "\n".join(names))), []
        if name == "go.mod":
            return set(re.findall(r"(?m)^\s*(?:require\s+)?([\w.-]+/[\w./-]+)\s+v[\d.]+", text)), []
        return set(re.findall(r"(?m)^\s*([\w.-]+)(?=\s*(?:[\[<>=!~;#]|$))", text)), []
    except (ValueError, TypeError, AttributeError, ElementTree.ParseError):
        return set(), []


def _uses(keyword: str, packages: set[str]) -> bool:
    if _SPRING.fullmatch(keyword):
        return any(
            item == "org.springframework.boot"
            or item.startswith("org.springframework.boot:spring-boot")
            for item in packages
        )
    normalize = lambda name: re.sub(r"[-_\s]+", "-", name.lower())
    return any(
        normalize(keyword) in {normalize(part) for part in re.split(r"[:/]", item) if part}
        for item in packages
    )


def _category(hit: source.RepoHit, readme: str) -> str:
    text = f"{hit.name} {hit.description} {' '.join(hit.topics)} {readme[:600]}"
    return next((label for label, pattern in _CATEGORIES if re.search(pattern, text, re.I)), "other")


def _example(hit: source.RepoHit, readme: str) -> bool:
    path = hit.path.lower()
    if path in {"spring-projects/spring-boot", "spring-projects/spring-petclinic"} or path.startswith("spring-guides/"):
        return True
    if re.fullmatch(r"(?:(?:spring[-_ ]?boot)[-_ ]*)?(?:tutorials?|examples?|learning|labs?|demo)",
                    hit.name, re.I) and _category(hit, readme) == "other":
        return True
    if re.search(r"(?:^|[-_])(?:tutorials|examples|demos|samples|learning[-_]example)(?:$|[-_])",
                 hit.name, re.I):
        return True
    text = f"{hit.name} {hit.description} {readme[:900]}"
    return bool(re.search(
        r"\b(?:awesome[- ]|hello[- ]world|minimal[- ](?:demo|example)|tutorial[- ]collection|"
        r"official[- ](?:sdk|framework)|(?:spring[- ]?boot[- ]?starter)(?:\s+for|$)|"
        r"(?:intentionally|deliberately) vulnerable)|"
        r"教程合集|最小示例|入门教程|(?:系列|各个|整合的各个)\s*(?:基本\s*)?demo",
        text.strip(), re.I
    ))


async def _file(client: httpx.AsyncClient, settings: Settings, hit: source.RepoHit,
                path: str) -> tuple[str, str]:
    if hit.platform == "GITHUB" and not settings.github_token:
        public = await _github_public_file(client, hit, path)
        if public is not None:
            return public
    base, token = {
        "GITHUB": (source.GITHUB_API_BASE, settings.github_token),
        "GITEE": (source.GITEE_API_BASE, settings.gitee_token),
        "GITCODE": (source.GITCODE_API_BASE, settings.gitcode_token),
    }[hit.platform]
    headers = {"User-Agent": settings.repository_search_user_agent}
    params = {"ref": hit.default_branch} if hit.default_branch and path else {}
    if token:
        if hit.platform == "GITHUB":
            headers["Authorization"] = f"Bearer {token}"
        else:
            params["access_token"] = token
    endpoint = f"contents/{quote(path, safe='/')}" if path else "readme"
    try:
        async with client.stream("GET", f"{base}/{hit.path}/{endpoint}", params=params,
                                 headers=headers, timeout=5, follow_redirects=False) as response:
            if response.status_code == 429 or (response.status_code == 403
                    and ("retry-after" in response.headers or response.headers.get("x-ratelimit-remaining") == "0")):
                raise RepositoryFileLimited(f"读取核验证据时触发平台限流（HTTP {response.status_code}）")
            if response.status_code != 200:
                return "", ""
            data = await _bounded_body(response, _MAX_FILE * 2)
            if data is None:
                return "", ""
        body = json.loads(data)
        if body.get("type") != "file" or body.get("encoding") != "base64":
            return "", ""
        text = base64.b64decode("".join(body["content"].split()), validate=True)
        actual = path or str(body.get("path", ""))
        if len(text) > _MAX_FILE or not _safe_path(actual):
            return "", ""
        return text.decode("utf-8-sig"), actual
    except (httpx.HTTPError, ValueError, KeyError, TypeError, AttributeError, binascii.Error):
        return "", ""


async def _bounded_body(response: httpx.Response, limit: int) -> bytearray | None:
    data = bytearray()
    async for chunk in response.aiter_bytes(chunk_size=8192):
        if len(data) + len(chunk) > limit:
            return None
        data.extend(chunk)
    return data


async def _github_public_file(client: httpx.AsyncClient, hit: source.RepoHit,
                              path: str) -> tuple[str, str] | None:
    ref = quote(hit.default_branch or "HEAD", safe="")
    # Use GitHub's public raw-file host for anonymous reads, keeping API quota for search.
    for actual in (path,) if path else ("README.md", "README", "README.adoc"):
        endpoint = f"https://raw.githubusercontent.com/{hit.path}/{ref}/{quote(actual, safe='/')}"
        try:
            async with client.stream("GET", endpoint, timeout=5, follow_redirects=False) as response:
                if response.status_code == 429:
                    raise RepositoryFileLimited("读取 GitHub 公开文件时触发平台限流（HTTP 429）")
                if response.status_code != 200:
                    continue
                data = await _bounded_body(response, _MAX_FILE)
                if data is not None:
                    return data.decode("utf-8-sig"), actual
        except httpx.HTTPError:
            return None
        except UnicodeError:
            continue
    return "", ""


def _safe_path(path: str) -> bool:
    return (0 < len(path) <= 240 and re.fullmatch(r"[\w./-]+", path) is not None
            and all(part not in {"", ".", ".."} for part in path.split("/")))


def _evidence_url(hit: source.RepoHit, path: str) -> str:
    ref = quote(hit.default_branch or "HEAD", safe="")
    return f"{hit.url}/blob/{ref}/{quote(path, safe='/')}"


async def _inspect(client: httpx.AsyncClient, settings: Settings, hit: source.RepoHit,
                   keywords: list[str]) -> tuple[source.RepoHit, str] | None:
    if _example(hit, ""):
        return None
    if not await source._enrich_license(client, settings, hit):
        return None
    evidence = {}
    pending = list(_JAVA_FILES if all(_SPRING.fullmatch(item) for item in keywords) else _OTHER_FILES)
    if any(_SPRING.fullmatch(item) for item in keywords):
        pending.extend(("settings.gradle", "settings.gradle.kts"))
    files_read = 0
    while pending and files_read < 9 and len(evidence) < len(keywords):
        path = pending.pop(0)
        files_read += 1
        text, actual = await _file(client, settings, hit, path)
        if not actual:
            continue
        packages, modules = _manifest(actual, text)
        for keyword in keywords:
            if keyword not in evidence and _uses(keyword, packages):
                evidence[keyword] = {"keyword": keyword, "status": "VERIFIED", "path": actual,
                                     "url": _evidence_url(hit, actual)}
        pending[0:0] = [f"{module.strip()}/pom.xml" for module in modules[:3] if _safe_path(module.strip())]
        if path.startswith("settings.gradle"):
            includes = re.findall(r"(?m)^\s*include\b([^\r\n]*)", text)
            for module in re.findall(r"""['"]:?(\w[\w:.-]*)['"]""", "\n".join(includes))[:3]:
                folder = module.replace(":", "/")
                if _safe_path(folder):
                    pending.extend(f"{folder}/{name}" for name in _JAVA_FILES[1:])
    readme = ""
    try:
        readme, readme_path = await _file(client, settings, hit, "" if hit.platform == "GITHUB" else "README.md")
        if not readme and hit.platform != "GITHUB":
            readme, readme_path = await _file(client, settings, hit, "README")
    except RepositoryFileLimited:
        if len(evidence) == len(keywords):
            hit.stack_evidence = list(evidence.values())
            return hit, _category(hit, "")
        raise
    if _example(hit, readme):
        return None
    if readme_path:
        hit.readme_url = _evidence_url(hit, readme_path)
    for keyword in keywords:
        if keyword not in evidence and readme_path and _readme_claim(keyword, readme):
            evidence[keyword] = {"keyword": keyword, "status": "DOCUMENTED", "path": readme_path,
                                 "url": _evidence_url(hit, readme_path)}
    if len(evidence) != len(keywords):
        return None
    hit.stack_evidence = list(evidence.values())
    return hit, _category(hit, readme)


def _queries(platform: str, query: str, keywords: list[str]) -> list[tuple[str, bool]]:
    canonical = " ".join(_aliases(item)[0] for item in keywords)
    readme = " ".join(_aliases(item)[-1] for item in keywords)
    anchor = _aliases(keywords[0])[1 if _SPRING.fullmatch(keywords[0]) else 0]
    topic_query = " ".join(filter(None, (query, canonical)))
    readme_query = f"{readme} in:readme" if platform == "GITHUB" else readme
    if platform == "GITHUB" and _SPRING.fullmatch(keywords[0]):
        readme_query += " language:Java stars:3..1500"
        anchor += " stars:>=3"
        if not query:
            topic_query = "topic:spring-boot"
    candidates = [
        (topic_query, False),
        (readme_query, False),
        (anchor, True),
    ]
    return list(dict.fromkeys(candidates))


def _keys(hit: source.RepoHit) -> set[str]:
    keys = {f"{hit.platform}:{hit.path.lower()}"}
    if hit.mirror_url:
        for platform in source.REPO_PLATFORMS:
            path = source._repo_path(hit.mirror_url, platform)
            if path:
                keys.add(f"{platform}:{path.lower()}")
    return keys


def _rank(rows: list[tuple[source.RepoHit, str]], query: str) -> list[source.RepoHit]:
    rows.sort(key=lambda row: row[0].updated_at, reverse=True)
    rows.sort(key=lambda row: (
        any(item["status"] != "VERIFIED" for item in row[0].stack_evidence),
        row[1] == "other",
        bool(query) and not source._keyword_present(query, f"{row[0].name} {row[0].description}"),
        -len(row[0].matched_skill_codes),
    ))
    ranked = []
    for verified in (True, False):
        tier = [row for row in rows if all(item["status"] == "VERIFIED" for item in row[0].stack_evidence) == verified]
        counts: dict[str, int] = {}
        extra = []
        for hit, category in tier:
            counts[category] = counts.get(category, 0) + 1
            (ranked if counts[category] <= 3 else extra).append(hit)
        ranked.extend(extra)
    return ranked


async def discover(settings: Settings, skills: list[str], query: str | None, platforms: list[str],
                   keywords: list[str], cursor: dict[str, int], seen: list[str],
                   client: httpx.AsyncClient | None = None) -> tuple[source.RepositorySearchResult, dict[str, int], list[str]]:
    if client is None:
        async with httpx.AsyncClient(timeout=8, trust_env=settings.repository_search_trust_env) as owned:
            return await discover(settings, skills, query, platforms, keywords, cursor, seen, owned)
    selected = [item for item in source.REPO_PLATFORMS if not platforms or item in platforms]
    searchers = {"GITHUB": source._search_github, "GITEE": source._search_gitee, "GITCODE": source._search_gitcode}
    statuses = {}
    streams = []
    next_cursor = {}
    for platform in selected:
        if (platform == "GITEE" and not settings.gitee_token) or (platform == "GITCODE" and not settings.gitcode_token):
            statuses[platform] = {"platform": platform, "status": "FAILED", "reason": "未配置该平台的检索令牌"}
            continue
        for index, (term, recent) in enumerate(_queries(platform, (query or "").strip(), keywords)):
            key = f"{platform}:{index}"
            page = cursor.get(key, 1)
            next_cursor[key] = page
            if page:
                streams.append((page, index, platform, key, term, recent))
    streams.sort(key=lambda item: (item[0] < 0, abs(item[0]), item[1], selected.index(item[2])))

    async def search(stream):
        page, _, platform, _, term, recent = stream
        try:
            async with asyncio.timeout(8):
                return await searchers[platform](client, settings, term, skills, page=abs(page), discovery=True, recent=recent)
        except (ValueError, TimeoutError):
            return source.PlatformResult(platform, "FAILED", reason=f"{platform} 检索超时或返回格式异常")

    chosen = streams[:3]
    results = await asyncio.gather(*(search(stream) for stream in chosen))
    hits = {}
    excluded = set(seen)
    for (_, _, platform, key, _, _), result in zip(chosen, results):
        if result.status == "FAILED":
            next_cursor[key] = -abs(next_cursor[key])
            if statuses.get(platform, {}).get("status") == "SUCCEEDED":
                statuses[platform]["reason"] = result.reason
            else:
                statuses[platform] = {"platform": platform, "status": "FAILED", "reason": result.reason}
            continue
        statuses[platform] = {"platform": platform, "status": "SUCCEEDED", "reason": statuses.get(platform, {}).get("reason")}
        next_cursor[key] = abs(next_cursor[key]) + 1 if result.has_next else 0
        for hit in result.hits:
            if not _keys(hit) & excluded:
                hits.setdefault((hit.platform, hit.path.lower()), hit)
    if not any(result.status == "SUCCEEDED" for result in results):
        raise source.RepositorySourcesUnavailable("；".join(item.get("reason") or name for name, item in statuses.items()))

    semaphore = asyncio.Semaphore(8)
    limited = {}

    async def inspect(hit):
        async with semaphore:
            if hit.platform in limited:
                return None
            try:
                return await _inspect(client, settings, hit, keywords)
            except RepositoryFileLimited as exc:
                limited[hit.platform] = str(exc)
                return None

    tasks = [asyncio.create_task(inspect(hit)) for hit in hits.values()]
    try:
        done, pending = await asyncio.wait(tasks, timeout=30) if tasks else (set(), set())
        eligible = [result for task in tasks if task in done and (result := task.result()) is not None]
    finally:
        for task in tasks:
            if not task.done():
                task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
    incomplete = set(limited)
    for task, hit in zip(tasks, hits.values()):
        if task in pending:
            incomplete.add(hit.platform)
            statuses[hit.platform]["reason"] = "部分仓库未在时限内完成文件核验"
    for platform, reason in limited.items():
        statuses[platform]["reason"] = reason
    for page, _, platform, key, _, _ in chosen:
        if platform in incomplete:
            next_cursor[key] = -abs(page)
    rows = {f"{hit.platform}:{hit.path.lower()}": category for hit, category in eligible}
    merged = []
    for primary in source._merge_hits([hit for hit, _ in eligible], selected):
        group = [primary, *primary.alternate_sources]
        primary.alternate_sources = []
        group.sort(key=lambda hit: sum(item["status"] != "VERIFIED" for item in hit.stack_evidence))
        group[0].alternate_sources = group[1:]
        merged.append(group[0])
    accepted = []
    added = set()
    for hit in _rank([(hit, rows[f"{hit.platform}:{hit.path.lower()}"]) for hit in merged], query or ""):
        keys = _keys(hit).union(*(_keys(item) for item in hit.alternate_sources))
        if not keys & added:
            accepted.append(hit)
            added.update(keys)
    return source.RepositorySearchResult(source_runs=list(statuses.values()), candidates=accepted), next_cursor, sorted(added)
