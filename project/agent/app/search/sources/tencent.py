from __future__ import annotations

import re
import time
from datetime import datetime, timezone
from typing import Any
from urllib.parse import urlencode

from ..models import (
    SourcePosting,
    city_code_for,
    infer_candidate_type,
    parse_experience_months,
    parse_salary,
)
from ..security import ensure_public_url
from ..verification import official_list
from .base import SourceAdapter, SourceContext, filter_postings
from .detail import closed_text

TENCENT_HOSTS = frozenset({"careers.tencent.com"})
LIST_URL = "https://careers.tencent.com/tencentcareer/api/post/Query"


class TencentCareerSource(SourceAdapter):
    key = "tencent"
    name = "腾讯招聘官网"
    type = "OFFICIAL"
    verification = "VERIFIED"
    available = True
    allowed_hosts = TENCENT_HOSTS

    async def search(self, context: SourceContext) -> SourceResult:
        started = time.monotonic()
        if context.client is None:
            return self.result("FAILED", message="HTTP 客户端未初始化", started=started)
        params = {
            "timestamp": int(time.time() * 1000),
            "countryId": "",
            "cityId": "",
            "bgIds": "",
            "productId": "",
            "categoryId": "",
            "parentCategoryId": "",
            "attrId": "",
            "keyword": context.keyword,
            "pageIndex": 1,
            "pageSize": min(max(context.limit, 1), 20),
            "language": "zh-cn",
            "area": "cn",
        }
        try:
            await ensure_public_url(LIST_URL, allowed_hosts=set(TENCENT_HOSTS))
            response = await context.client.get(
                LIST_URL,
                params=params,
                timeout=context.timeout_seconds,
                headers={"User-Agent": context.user_agent, "Accept": "application/json"},
                follow_redirects=False,
            )
        except Exception as exc:
            return self.result("FAILED", message=f"官方接口请求失败: {type(exc).__name__}", started=started)
        if response.status_code != 200:
            return self.result("FAILED", message=f"官方接口返回 HTTP {response.status_code}", http_status=response.status_code, started=started)
        try:
            body = response.json()
        except ValueError:
            return self.result("FAILED", message="官方接口返回非 JSON 内容", http_status=response.status_code, started=started)
        data = body.get("Data") if isinstance(body, dict) and body.get("Code") == 200 else None
        posts = data.get("Posts") if isinstance(data, dict) else None
        if not isinstance(posts, list):
            return self.result("FAILED", message="官方接口未确认岗位列表", started=started)
        postings: list[SourcePosting] = []
        for post in posts:
            posting = _to_posting(post)
            if posting is not None:
                postings.append(posting)
        postings = filter_postings(postings, context.filters)
        if not postings:
            return self.result("EMPTY", message="官方接口可用，但当前条件下没有匹配岗位", http_status=200, started=started)
        return self.result("AVAILABLE", postings[: context.limit], message=f"官方接口返回 {len(posts)} 条，筛选后 {len(postings)} 条", http_status=200, started=started)


def _to_posting(post: dict[str, Any]) -> SourcePosting | None:
    if not isinstance(post, dict):
        return None
    title = str(post.get("RecruitPostName") or "").strip()
    jd = str(post.get("Responsibility") or "").strip()
    post_id = str(post.get("PostId") or "")
    checked_at = datetime.now(timezone.utc)
    if not title or not jd or not re.fullmatch(r"[0-9]+", post_id) or closed_text(f"{title} {jd}", checked_at):
        return None
    url = "https://careers.tencent.com/jobdesc.html?" + urlencode({"postId": post_id})
    city_name = str(post.get("LocationName") or "").strip() or None
    company = str(post.get("ComName") or "").strip() or "腾讯"
    candidate_type, inferred = infer_candidate_type(title, post.get("RequireWorkYearsName"))
    experience_text = str(post.get("RequireWorkYearsName") or "").strip() or None
    published = _parse_date(post.get("LastUpdateTime"))
    warnings: list[str] = []
    if inferred:
        warnings.append("CANDIDATE_TYPE_INFERRED")
    if published is None:
        warnings.append("MISSING_PUBLISHED_AT")
    if not experience_text:
        warnings.append("MISSING_EXPERIENCE")
    salary_min, salary_max = parse_salary(None)
    posting = SourcePosting(
        source_key=TencentCareerSource.key,
        source_name=TencentCareerSource.name,
        source_type=TencentCareerSource.type,
        source_url=url,
        title=title,
        company_name=company,
        jd_text=jd,
        city_name=city_name,
        city_code=city_code_for(city_name),
        candidate_type=candidate_type,
        salary_min_monthly_cents=salary_min,
        salary_max_monthly_cents=salary_max,
        salary_text=None,
        experience_text=experience_text,
        experience_months_min=parse_experience_months(experience_text),
        education_text=None,
        education_min=None,
        company_size="SIZE_UNKNOWN",
        published_at=published,
        fetched_at=checked_at,
        verification="VERIFIED",
        recruitment_evidence="OFFICIAL_LIST",
        warnings=warnings,
    )
    official_list(url, checked_at=checked_at).apply(posting)
    return posting


def _parse_date(value: Any) -> datetime | None:
    if not value:
        return None
    match = re.search(r"(\d{4})\D+(\d{1,2})\D+(\d{1,2})", str(value))
    if not match:
        return None
    try:
        return datetime(int(match.group(1)), int(match.group(2)), int(match.group(3)), tzinfo=timezone.utc)
    except ValueError:
        return None
