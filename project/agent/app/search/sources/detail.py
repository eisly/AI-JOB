from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from html.parser import HTMLParser
from urllib.parse import parse_qsl, urlencode, urljoin, urlsplit, urlunsplit

import httpx

from ..security import UnsafeUrlError, resolve_public_ipv4
from ..verification import STATUS_CLOSED, STATUS_PROBABLE, STATUS_UNKNOWN

MAX_DETAIL_BYTES = 512 * 1024
CLOSED = re.compile(
    r"(?:职位|岗位)\s*(?:已经?|已被|现已|暂时|已暂时)?\s*"
    r"(?:下线|下架|关闭|过期|失效|暂停招聘|停止招聘|停止招募|结束招聘|不存在|不再招聘)|"
    r"(?:职位|岗位)\s*(?:已经?|现已)\s*(?:结束|招满|删除|暂停)(?!招聘后)|"
    r"(?:投递|招聘|申请|报名|网申)\s*(?:已经?|现已)\s*(?:截止|结束|关闭)|"
    r"(?:投递|招聘|申请|报名|网申)\s*截止(?!\s*(?:[日期时]|[:：]?\s*\d))|"
    r"(?:已|暂时)\s*停止招聘|"
    r"[\[【](?:已结束|已过期|已截止)[\]】]|no longer accepting applications|"
    r"(?:this )?(?:job|position)(?: posting)? (?:is no longer available|has been filled|has expired|is closed)", re.I,
)
APPLY = re.compile(r"^(?:立即申请|申请职位|立即投递|投递简历|申请该职位|申请此职位|我要应聘|apply(?: now| for this job| for this position)?)$", re.I)
APPLY_ROUTE = re.compile(r"(?:^|[/_.-])(?:apply|application|submitapplication)(?:$|[/_.-])", re.I)
JOB_ID_KEYS = {"id", "jobid", "positionid", "postid", "requisitionid", "gh_jid"}
DEADLINE = re.compile(
    r"(?:(?:投递|招聘|申请|报名|网申)\s*截止(?:日期|时间)?|截止日期|过期时间)\s*[:：]?\s*"
    r"(20\d{2})[-/年](\d{1,2})[-/月](\d{1,2})"
)
APPLICATION_PERIOD = re.compile(
    r"(?:投递时间|申请时间)\s*[:：]\s*20\d{2}[-/年]\d{1,2}[-/月]\d{1,2}日?\s*[-~～至]\s*"
    r"(20\d{2})[-/年](\d{1,2})[-/月](\d{1,2})"
)
CHINA = timezone(timedelta(hours=8))


@dataclass
class DetailResult:
    """详情页核验结果：status 为四态，reason 记录失败或成功依据。"""

    status: str
    reason: str
    url: str
    http_status: int | None = None
    page: dict | None = None
    apply_link: bool = False
    detail: str = ""


def _result(status: str, reason: str, url: str, *, http_status: int | None = None,
            page: dict | None = None, apply_link: bool = False, detail: str = "") -> DetailResult:
    return DetailResult(status=status, reason=reason, url=url, http_status=http_status,
                        page=page, apply_link=apply_link, detail=detail)


def canonical_detail(url: str) -> str:
    if len(url) > 2000 or any(c.isspace() or c == "\\" for c in url):
        return ""
    try:
        parsed = urlsplit(url)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username is not None or parsed.port not in (None, 443):
            return ""
        query = sorted((k, v) for k, v in parse_qsl(parsed.query, keep_blank_values=True)
                       if not k.lower().startswith("utm_") and k.lower() not in {"gh_src", "spread", "trackingid"})
        return urlunsplit(("https", parsed.hostname.lower(), parsed.path.rstrip("/") or "/", urlencode(query), ""))
    except ValueError:
        return ""


class _Detail(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.text: list[str] = []
        self.structured: list[str] = []
        self.links: list[tuple[str, str]] = []
        self._skip = ""
        self._structured = False
        self._link = ""
        self._label: list[str] = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag in {"script", "style", "template"} and not self._skip:
            self._skip = tag
            self._structured = tag == "script" and attrs.get("type") == "application/ld+json"
        if tag == "a" and not self._skip:
            self._link = attrs.get("href") or ""
            if ("hidden" in attrs or "disabled" in attrs or attrs.get("aria-disabled") == "true"
                    or attrs.get("aria-hidden") == "true"
                    or re.search(r"(?:display\s*:\s*none|visibility\s*:\s*hidden)", attrs.get("style") or "", re.I)):
                self._link = ""
            self._label = []

    def handle_endtag(self, tag):
        if tag == self._skip:
            self._skip = ""
            self._structured = False
        if tag == "a" and self._link:
            self.links.append((self._link, " ".join(" ".join(self._label).split())))
            self._link = ""

    def handle_data(self, data):
        if self._structured:
            self.structured.append(data)
        elif not self._skip:
            self.text.append(data)
            if self._link:
                self._label.append(data)


def _job_postings(value):
    if isinstance(value, list):
        for entry in value:
            yield from _job_postings(entry)
    elif isinstance(value, dict):
        kind = value.get("@type")
        if kind == "JobPosting" or isinstance(kind, list) and "JobPosting" in kind:
            yield value
        yield from _job_postings(value.get("@graph"))


def _past_deadline(value: str, now: datetime) -> bool:
    date = datetime.fromisoformat(value)
    if len(value) == 10:
        return date.date() < now.astimezone(CHINA).date()
    return (date if date.tzinfo else date.replace(tzinfo=CHINA)) <= now


def closed_text(body: str, now: datetime) -> bool:
    if CLOSED.search(body):
        return True
    for y, m, d in [*DEADLINE.findall(body), *APPLICATION_PERIOD.findall(body[:1600])]:
        try:
            if _past_deadline(f"{y}-{int(m):02}-{int(d):02}", now):
                return True
        except ValueError:
            pass
    return False


def _job_ids(url) -> set[str]:
    return {value for key, value in parse_qsl(url.query) if key.casefold() in JOB_ID_KEYS and value}


def _html_title(html: str) -> str:
    match = re.search(r"<title[^>]*>(.*?)</title>", html, re.IGNORECASE | re.DOTALL)
    if match and match.group(1).strip():
        return " ".join(re.sub(r"<[^>]+>", " ", match.group(1)).split())[:300]
    from ..page_body import Tree
    for node in Tree(html).root.walk():
        if node.tag == "meta" and (node.attrs.get("property") or node.attrs.get("name")) in {"og:title", "twitter:title"}:
            return " ".join((node.attrs.get("content") or "").split())[:300]
    return ""


def read_detail_result(url: str, html: str, now: datetime) -> DetailResult:
    page = _Detail()
    page.feed(html)
    from ..page_body import job_body, listing_fields, structured_job
    body = job_body(html)
    matched = structured_job(html, url)
    page_data = {"title": _html_title(html), "body": body, "fields": listing_fields(html, url),
                 "html": html, "job": matched}
    if closed_text(f"{page_data['title']} {body}", now):
        return _result(STATUS_CLOSED, "OFFICIAL_CLOSED", url, http_status=200,
                       page=page_data, detail="页面明确声明岗位下线或截止")
    jobs = []
    for block in page.structured:
        try:
            jobs.extend(_job_postings(json.loads(block)))
        except (ValueError, RecursionError):
            continue
    if matched:
        jobs = [matched]
    if len(jobs) != 1:
        return _result(STATUS_UNKNOWN, "DETAIL_NOT_FOUND", url, http_status=200,
                       page=page_data, detail="页面未提供唯一的 JobPosting 结构化数据")
    job = jobs[0]
    if job.get("url") and canonical_detail(urljoin(url, str(job["url"]))) != url:
        return _result(STATUS_UNKNOWN, "DETAIL_NOT_FOUND", url, http_status=200,
                       page=page_data, detail="页面结构化岗位链接与候选链接不一致")
    if job.get("validThrough"):
        try:
            if _past_deadline(str(job["validThrough"]), now):
                return _result(STATUS_CLOSED, "EXPIRED", url, http_status=200,
                               page=page_data, detail="岗位投递截止日期已过")
        except ValueError:
            return _result(STATUS_UNKNOWN, "DETAIL_NOT_FOUND", url, http_status=200,
                           page=page_data, detail="岗位截止日期无法解析")
    title = job.get("title")
    if not isinstance(title, str) or not title.strip() or title.strip().casefold() not in body.casefold():
        return _result(STATUS_UNKNOWN, "DETAIL_NOT_FOUND", url, http_status=200,
                       page=page_data, detail="页面标题与岗位标题不一致")
    # Limit positive evidence to a job-specific apply route on the same origin.
    detail = urlsplit(url)
    path = detail.path.rstrip("/")
    detail_ids = _job_ids(detail)
    path_id = path.rsplit("/", 1)[-1]
    if len(path.split("/")) >= 3 and re.fullmatch(r"[a-zA-Z0-9-]+", path_id) and any(c.isdigit() for c in path_id):
        detail_ids.add(path_id)
    for href, label in page.links:
        target = urlsplit(canonical_detail(urljoin(url, href)))
        if not APPLY.fullmatch(label) or target.netloc != detail.netloc or not APPLY_ROUTE.search(target.path):
            continue
        target_ids = _job_ids(target)
        if target_ids and not target_ids <= detail_ids:
            continue
        if detail_ids & (set(target.path.split("/")) | target_ids):
            return _result(STATUS_PROBABLE, "ACTIVE_EVIDENCE_FOUND", url, http_status=200,
                           page={**page_data, "title": title}, apply_link=True,
                           detail="详情页包含岗位正文与岗位专属投递入口")
    return _result(STATUS_PROBABLE, "APPLY_LINK_MISSING", url, http_status=200,
                   page={**page_data, "title": title},
                   detail="详情页包含岗位正文，但未识别到岗位专属投递入口")


def read_detail(url: str, html: str, now: datetime) -> tuple[str, dict | None]:
    """兼容旧接口：open/closed/unknown 三值映射，详情存在即为 open。"""
    result = read_detail_result(url, html, now)
    status = {"ACTIVE_PROBABLE": "open", "CLOSED": "closed"}.get(result.status, "unknown")
    return status, result.page


async def fetch_detail(client, url: str, timeout: float, *, budget=None) -> DetailResult:
    original = url
    try:
        for redirect_count in range(3):
            url = canonical_detail(url)
            if not url:
                return _result(STATUS_UNKNOWN, "PAGE_ACCESS_FAILED", original, detail="候选链接无法规范化")
            destination = urlsplit(url)
            host = destination.hostname or ""
            address = await resolve_public_ipv4(host)
            if budget and redirect_count:
                if not budget.can_call():
                    return _result(STATUS_UNKNOWN, "TIMEOUT", original, detail="详情跳转核验预算已用完")
                budget.record_api_call()
            # Pin the connection to the checked address; validate TLS against the original host.
            pinned = urlunsplit(destination._replace(netloc=address))
            async with client.stream("GET", pinned, timeout=timeout, follow_redirects=False,
                                     extensions={"sni_hostname": host},
                                     headers={"Host": host, "Accept": "text/html", "Cache-Control": "no-cache"}) as response:
                if response.status_code in {401, 403}:
                    # 访问受限不等于岗位下线。
                    return _result(STATUS_UNKNOWN, "HTTP_403", url, http_status=response.status_code,
                                   detail="详情页拒绝匿名访问")
                if response.status_code in {404, 410}:
                    # 404/410 可能是站点改版、反爬或链接过期，仍需官方证据才能判 CLOSED。
                    return _result(STATUS_UNKNOWN, "HTTP_404", url, http_status=response.status_code,
                                   detail="详情页不存在或已迁移，不能据此判定岗位下线")
                if response.is_redirect:
                    url = canonical_detail(urljoin(url, response.headers.get("location", "")))
                    if not url or urlsplit(url).path != urlsplit(original).path or urlsplit(url).query != urlsplit(original).query:
                        return _result(STATUS_UNKNOWN, "PAGE_ACCESS_FAILED", original,
                                       http_status=response.status_code, detail="详情页跳转到非岗位页面")
                    continue
                if response.status_code != 200 or "html" not in response.headers.get("content-type", "").lower():
                    return _result(STATUS_UNKNOWN, "DETAIL_NOT_FOUND", url,
                                   http_status=response.status_code, detail="详情页不是可解析的 HTML")
                body = bytearray()
                async for chunk in response.aiter_bytes(chunk_size=16384):
                    body.extend(chunk)
                    if len(body) > MAX_DETAIL_BYTES:
                        return _result(STATUS_UNKNOWN, "DETAIL_NOT_FOUND", url,
                                       http_status=response.status_code, detail="详情页超出可解析大小")
                return read_detail_result(url, body.decode(response.encoding or "utf-8", errors="replace"),
                                          datetime.now(timezone.utc))
    except httpx.TimeoutException:
        return _result(STATUS_UNKNOWN, "TIMEOUT", original, detail="详情页读取超时")
    except (UnsafeUrlError, OSError, httpx.HTTPError, ValueError, LookupError):
        return _result(STATUS_UNKNOWN, "PAGE_ACCESS_FAILED", original, detail="详情页无法访问")
    return _result(STATUS_UNKNOWN, "PAGE_ACCESS_FAILED", original, detail="详情页无法访问")
