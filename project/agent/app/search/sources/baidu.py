from __future__ import annotations

import asyncio
import json
import re
import time
from datetime import date, datetime, timezone

import httpx

from ..models import SourcePosting, SourceResult, city_code_for
from ..security import ensure_public_url
from ..verification import official_list
from .base import SourceAdapter, SourceContext, filter_postings
from .detail import closed_text
from .web import role_variants

BAIDU_HOST = "talent.baidu.com"
LIST_URL = f"https://{BAIDU_HOST}/jobs/list"
RECRUIT_TYPES = {"INTERNSHIP": "INTERN", "CAMPUS": "GRADUATE", "EXPERIENCED": "SOCIAL"}
MAX_LIST_QUERIES = 4


class BaiduCareerSource(SourceAdapter):
    key = "baidu"
    name = "百度招聘官网"
    type = "OFFICIAL"
    verification = "VERIFIED"

    async def search(self, context: SourceContext) -> SourceResult:
        started = time.monotonic()
        if context.client is None:
            return self.result("FAILED", message="HTTP 客户端未初始化", started=started)
        kinds = context.filters.candidate_types or tuple(RECRUIT_TYPES)
        queries = [(kind, keyword) for keyword in role_variants(context.keyword) for kind in kinds][:MAX_LIST_QUERIES]
        results = []
        for kind, keyword in queries:
            try:
                results.append(await self._search_list(context, kind, keyword))
            except (ValueError, OSError, httpx.HTTPError, asyncio.TimeoutError) as exc:
                results.append(type(exc).__name__)
        postings = []
        found = set()
        failures = []
        seen = set()
        truncated = False
        for result in results:
            if isinstance(result, str):
                failures.append(result)
                continue
            rows, kind, checked_at, total = result
            truncated |= total > len(rows)
            for row in rows:
                locations = list(_postings(row, kind, checked_at))
                if locations:
                    found.add(locations[0].source_url)
                matching = filter_postings(locations, context.filters)
                if matching and matching[0].source_url not in seen:
                    seen.add(matching[0].source_url)
                    postings.append(matching[0])
        status = ("FAILED" if len(failures) == len(queries) else "PARTIAL" if failures or truncated
                  else "AVAILABLE" if postings else "EMPTY")
        message = f"官网当前列表确认 {len(found)} 个职位，条件筛选后 {len(postings)} 条"
        if truncated:
            message += "；每个关键词取官网首屏"
        if failures:
            message += f"；{len(failures)} 次请求未完成（{', '.join(sorted(set(failures)))}）"
        result = self.result(status, postings, message=message, started=started)
        result.verification_counts = {"queries": len(queries), "discovered": len(found), "confirmedOpen": len(found)}
        return result

    async def _search_list(self, context: SourceContext, kind: str, keyword: str):
        await ensure_public_url(LIST_URL, allowed_hosts={BAIDU_HOST})
        recruit_type = RECRUIT_TYPES[kind]
        response = await context.client.get(LIST_URL, params={"recruitType": recruit_type, "search": keyword},
                                            headers={"Cache-Control": "no-cache"}, timeout=3.0, follow_redirects=False)
        response.raise_for_status()
        match = re.search(r'"listData"\s*:\s*\{', response.text)
        if match is None:
            raise ValueError("官网未返回职位列表")
        data, _ = json.JSONDecoder().raw_decode(response.text[match.end() - 1:])
        rows = data.get("listDetailData")
        if data.get("recruitType") != recruit_type or data.get("keyWord") != keyword or not isinstance(rows, list):
            raise ValueError("官网未确认本次筛选条件")
        total = data.get("total")
        return rows, kind, datetime.now(timezone.utc), total if isinstance(total, int) else len(rows)


def _postings(row, kind: str, checked_at: datetime):
    if not isinstance(row, dict):
        return
    post_id, title = row.get("postId"), row.get("name")
    if (not isinstance(post_id, str) or not re.fullmatch(r"[0-9a-fA-F-]{36}", post_id)
            or not isinstance(title, str) or not title.strip()):
        return
    jd = "\n".join(text.strip() for key in ("workContent", "serviceCondition")
                   if isinstance(text := row.get(key), str))[:30000]
    if not jd.strip() or closed_text(f"{title} {jd}", checked_at):
        return
    published = None
    try:
        published = datetime.combine(date.fromisoformat(row["publishDate"]), datetime.min.time(), timezone.utc)
    except (KeyError, TypeError, ValueError):
        pass
    locations = re.split(r"[,，、/;；]", str(row.get("workPlace") or ""))
    for city in dict.fromkeys(location.strip() for location in locations):
        source_url = f"https://{BAIDU_HOST}/jobs/detail/{RECRUIT_TYPES[kind]}/{post_id}"
        posting = SourcePosting(
            source_key="baidu", source_name=BaiduCareerSource.name, source_type="OFFICIAL",
            source_url=source_url,
            title=title.strip()[:200], company_name="百度", jd_text=jd, city_name=city[:100] or None,
            city_code=city_code_for(city), candidate_type=kind, published_at=published, fetched_at=checked_at,
            verification="VERIFIED", recruitment_evidence="OFFICIAL_LIST",
        )
        official_list(source_url, checked_at=checked_at).apply(posting)
        yield posting
