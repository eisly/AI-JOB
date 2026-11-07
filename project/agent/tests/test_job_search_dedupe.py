from __future__ import annotations

from datetime import datetime, timezone

from app.search.dedupe import dedupe_postings, jd_similarity, ranking_score
from app.search.models import SourcePosting

JD = "负责企业知识库问答与检索增强生成服务的设计开发，维护模型接入、评测和数据处理流程，要求熟悉 Python 与向量数据库。"


def posting(**overrides) -> SourcePosting:
    values = {
        "source_key": "web",
        "source_name": "jobs.example.com",
        "source_type": "PUBLIC_SEARCH",
        "source_url": "https://jobs.example.com/1",
        "title": "AI 应用开发工程师",
        "company_name": "示例科技",
        "jd_text": JD,
        "city_name": "深圳",
        "city_code": "440300",
        "candidate_type": "EXPERIENCED",
        "verification_status": "UNKNOWN",
        "published_at": datetime(2026, 11, 1, tzinfo=timezone.utc),
    }
    values.update(overrides)
    return SourcePosting(**values)


def test_same_job_across_platforms_is_merged_by_jd_similarity() -> None:
    left = posting()
    right = posting(
        source_key="web", source_name="www.jobs-board.cn",
        source_url="https://www.jobs-board.cn/post/9988",
        company_name="示例科技有限公司", title="AI 应用开发工程师（急聘）",
    )
    assert jd_similarity(left.jd_text, right.jd_text) == 1.0
    result, stats = dedupe_postings([left, right])
    assert len(result) == 1
    assert stats["mergedCount"] == 1
    assert len(result[0].aliases) == 2


def test_different_jobs_are_not_merged() -> None:
    left = posting()
    right = posting(
        source_url="https://jobs.example.com/2",
        title="Java 后端开发工程师",
        jd_text="负责 Java 服务端接口开发、数据库设计与高并发性能优化，要求熟悉 Spring Boot 与 MySQL。",
    )
    result, stats = dedupe_postings([left, right])
    assert len(result) == 2
    assert stats["mergedCount"] == 0


def test_url_and_job_id_dedupe_layers() -> None:
    left = posting(source_url="https://jobs.example.com/detail?jobId=123")
    same_url = posting(source_url="https://jobs.example.com/detail?jobId=123&utm_source=x")
    same_id = posting(source_url="https://jobs.example.com/detail?jobId=123&from=list")
    result, stats = dedupe_postings([left, same_url, same_id])
    assert len(result) == 1
    assert stats["methods"]


def test_ranking_prefers_confirmed_and_fresh() -> None:
    confirmed = posting(verification_status="ACTIVE_CONFIRMED", source_type="OFFICIAL")
    unknown = posting(source_url="https://jobs.example.com/2", verification_status="UNKNOWN")
    closed = posting(source_url="https://jobs.example.com/3", verification_status="CLOSED")
    assert ranking_score(confirmed, "AI 应用开发") > ranking_score(unknown, "AI 应用开发")
    assert ranking_score(unknown, "AI 应用开发") > ranking_score(closed, "AI 应用开发")