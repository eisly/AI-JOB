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
from ..text_extract import extract_job_fields
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
        found = {}
        status_code = None
        raw_count = 0
        pages = 0
        failure = None
        seen = set()
        try:
            await ensure_public_url(LIST_URL, allowed_hosts=set(TENCENT_HOSTS))
            for page in range(1, min(3, max(1, int(getattr(context.settings, "job_search_tencent_pages", 1)))) + 1):
                if not context.reserve_request():
                    raise TimeoutError("来源请求预算已用完")
                response = await context.client.get(
                    LIST_URL, params={**params, "pageIndex": page}, timeout=context.timeout_seconds,
                    headers={"User-Agent": context.user_agent, "Accept": "application/json"},
                    follow_redirects=False,
                )
                status_code = response.status_code
                if status_code != 200:
                    failure = f"官方接口返回 HTTP {status_code}"
                    break
                body = response.json()
                data = body.get("Data") if isinstance(body, dict) and body.get("Code") == 200 else None
                posts = data.get("Posts") if isinstance(data, dict) else None
                if not isinstance(posts, list):
                    failure = "官方接口未确认岗位列表"
                    break
                pages += 1
                raw_count += len(posts)
                for post in posts:
                    posting = _to_posting(post)
                    if posting:
                        found.setdefault(posting.source_url, posting)
                ids = {str(post.get("PostId")) for post in posts if isinstance(post, dict)}
                if len(posts) < params["pageSize"] or not ids - seen:
                    break
                seen.update(ids)
                total = data.get("Count")
                if isinstance(total, int) and raw_count >= total:
                    break
        except Exception as exc:
            failure = f"官方接口请求失败: {type(exc).__name__}"
        postings = filter_postings(list(found.values()), context.filters)
        status = ("PARTIAL" if pages else "FAILED") if failure else "AVAILABLE" if postings else "EMPTY"
        result = self.result(status, postings[:context.limit], message=failure or
                             f"官方接口返回 {raw_count} 条，筛选后 {len(postings)} 条",
                             http_status=status_code, started=started)
        result.verification_counts = {"queries": pages, "discovered": raw_count,
                                      "normalized": len(found), "hardFilterPass": len(postings)}
        return result


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
    fields = extract_job_fields(jd, title=title)
    experience_text = str(post.get("RequireWorkYearsName") or "").strip() or fields.experience_text
    experience_months = parse_experience_months(experience_text)
    if experience_months is None:
        experience_months = fields.experience_months_min
    published = _parse_date(post.get("LastUpdateTime")) or fields.published_at
    city_name = city_name or fields.city_name
    city_code = city_code_for(city_name) or fields.city_code
    warnings: list[str] = []
    if inferred:
        warnings.append("CANDIDATE_TYPE_INFERRED")
    if published is None:
        warnings.append("MISSING_PUBLISHED_AT")
    if not experience_text:
        warnings.append("MISSING_EXPERIENCE")
    salary_text = fields.salary_text
    salary_min = fields.salary_min_monthly_cents
    salary_max = fields.salary_max_monthly_cents
    posting = SourcePosting(
        source_key=TencentCareerSource.key,
        source_name=TencentCareerSource.name,
        source_type=TencentCareerSource.type,
        source_url=url,
        title=title,
        company_name=company,
        jd_text=jd,
        city_name=city_name,
        city_code=city_code,
        candidate_type=candidate_type,
        salary_min_monthly_cents=salary_min,
        salary_max_monthly_cents=salary_max,
        salary_text=salary_text,
        experience_text=experience_text,
        experience_months_min=experience_months,
        education_text=fields.education_text,
        education_min=fields.education_min,
        company_size=fields.company_size,
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
