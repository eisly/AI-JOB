from __future__ import annotations

from datetime import datetime, timezone

from app.search.models import SourcePosting
from app.search.sources.web import _enrich_from_page, _to_posting

JD = (
    "岗位职责：负责后端服务接口开发，参与高并发系统设计与性能优化。"
    "工作地点：深圳。薪资 20-30K，每年 13 薪。学历本科及以上，经验 3 年以上，团队规模 500-999 人。"
    "发布时间 2026-11-05。"
)


def page(body: str = JD, title: str = "后端开发工程师 - 示例科技有限公司") -> dict:
    return {"title": title, "body": body, "providers": ["tavily"], "query": "深圳 后端开发 招聘"}


def row(**overrides) -> dict:
    values = {
        "title": "后端开发工程师",
        "companyName": "示例科技",
        "cityName": None,
        "candidateTypeText": "社招",
        "jdQuotes": [JD],
        "salaryText": None,
        "companySizeText": None,
        "experienceText": None,
        "educationText": None,
        "publishedDate": None,
    }
    values.update(overrides)
    return values


def test_fields_are_extracted_from_jd_text() -> None:
    posting, reason, flags = _to_posting(row(), "https://careers.example.com/job/1", page())
    assert reason is None and posting is not None
    assert (posting.city_name, posting.city_code) == ("深圳", "440300")
    assert posting.salary_text == "20-30K"
    assert posting.salary_min_monthly_cents and posting.salary_max_monthly_cents
    assert posting.education_text and "本科" in posting.education_text
    assert posting.education_min == "BACHELOR"
    assert posting.experience_text and "3" in posting.experience_text
    assert posting.experience_months_min == 36
    assert posting.company_size == "SIZE_500_999"
    assert posting.published_at is not None
    assert "CITY_FROM_TEXT" in posting.warnings
    assert "SALARY_FROM_TEXT" in posting.warnings
    assert flags["cityFromText"] is True


def test_missing_fields_are_not_fabricated() -> None:
    body = "岗位职责：负责后端服务开发，参与接口设计与性能优化，维护现有系统稳定运行。"
    posting, reason, _ = _to_posting(
        row(title="后端开发实习生", jdQuotes=[body], candidateTypeText="实习"),
        "https://careers.example.com/job/2",
        page(body=body, title="后端开发实习生 - 示例科技有限公司"),
    )
    assert reason is None and posting is not None
    assert posting.city_code is None
    assert posting.salary_text is None
    assert posting.salary_min_monthly_cents is None
    assert posting.education_min is None
    assert posting.experience_months_min is None
    assert posting.company_size == "SIZE_UNKNOWN"
    assert posting.published_at is None


def test_company_name_allows_legal_suffix_difference() -> None:
    posting, reason, _ = _to_posting(row(), "https://careers.example.com/job/1", page())
    assert reason is None and posting is not None
    assert posting.company_name == "示例科技"


def test_city_label_wins_over_company_headquarters() -> None:
    body = JD + " 公司总部位于北京，研发中心在杭州。"
    posting, reason, _ = _to_posting(row(jdQuotes=[body]), "https://careers.example.com/job/3", page(body=body))
    assert reason is None and posting is not None
    assert (posting.city_name, posting.city_code) == ("深圳", "440300")


def test_detail_page_enriches_only_missing_fields() -> None:
    posting = SourcePosting(
        source_key="web", source_name="careers.example.com", source_type="PUBLIC_SEARCH",
        source_url="https://careers.example.com/job/4", title="后端开发工程师",
        company_name="careers.example.com", jd_text=JD, city_name=None, city_code=None,
        candidate_type="INTERNSHIP", salary_text=None, company_size="SIZE_UNKNOWN",
    )
    _enrich_from_page(posting, page())
    assert (posting.city_name, posting.city_code) == ("深圳", "440300")
    assert posting.company_name == "示例科技有限公司"
    assert posting.salary_text == "20-30K"
    assert posting.education_min == "BACHELOR"
    assert posting.experience_months_min == 36
    assert posting.company_size == "SIZE_500_999"
    assert posting.published_at is not None
    assert "COMPANY_FROM_DETAIL" in posting.warnings


def test_detail_enrichment_does_not_overwrite_known_values() -> None:
    posting = SourcePosting(
        source_key="web", source_name="careers.example.com", source_type="PUBLIC_SEARCH",
        source_url="https://careers.example.com/job/5", title="后端开发工程师",
        company_name="已确认公司", jd_text=JD, city_name="上海", city_code="310100",
        candidate_type="INTERNSHIP", salary_text="15-20K", company_size="SIZE_100_499",
        education_text="硕士及以上", education_min="MASTER",
    )
    _enrich_from_page(posting, page())
    assert (posting.city_name, posting.city_code) == ("上海", "310100")
    assert posting.company_name == "已确认公司"
    assert posting.salary_text == "15-20K"
    assert posting.education_min == "MASTER"
    assert posting.company_size == "SIZE_100_499"

def test_liepin_style_detail_page_fills_salary_city_size_and_company() -> None:
    body = (
        "AI应用开发实习生（可留用） 100-150元/天 杭州-滨江区 实习 本科 学生可投 "
        "职位地址：杭州-滨江区中财大厦 公司信息 华创证券有限责任公司 "
        "人数规模：2000-5000人 企业行业：基金/证券/期货 "
        "岗位职责：协助团队参与金融场景下 AI Agent 应用开发与接口联调。"
    )
    posting = SourcePosting(
        source_key="web", source_name="www.liepin.com", source_type="PUBLIC_SEARCH",
        source_url="https://www.liepin.com/job/1985680015.shtml",
        title="AI应用开发实习生（可留用）", company_name="www.liepin.com", jd_text=body,
        candidate_type="INTERNSHIP", city_name=None, city_code=None,
        salary_text=None, company_size="SIZE_UNKNOWN", education_text=None, education_min=None,
    )
    _enrich_from_page(posting, {"title": "AI应用开发实习生（可留用）-猎聘", "body": body})
    assert posting.salary_text == "100-150元/天"
    assert (posting.city_name or "").startswith("杭州")
    assert posting.city_code == "330100"
    assert posting.company_name == "华创证券有限责任公司"
    assert posting.company_size == "SIZE_1000_9999"
    assert posting.education_min == "BACHELOR"
    assert "SALARY_FROM_DETAIL" in posting.warnings
    assert "COMPANY_SIZE_FROM_DETAIL" in posting.warnings

def test_result_contract_keeps_field_evidence_and_update_time() -> None:
    from app.search.pipeline import _items_to_result
    from app.skills.tools import validate_result_contract

    posting = SourcePosting(
        source_key="baidu", source_name="百度招聘官网", source_type="OFFICIAL",
        source_url="https://talent.baidu.com/jobs/detail/INTERN/e3cec5b8-b7a3-4946-99fc-b292b749cd53",
        title="AI 应用开发实习生", company_name="百度", jd_text=JD,
        candidate_type="INTERNSHIP", company_size="SIZE_UNKNOWN", company_size_text="50-150人",
        field_sources={"companySize": {
            "sourceUrl": "https://www.liepin.com/job/123.shtml", "sourceName": "猎聘",
            "sourceType": "PUBLIC_SEARCH", "rawText": "50-150人",
            "fetchedAt": "2026-11-10T00:00:00+00:00"}},
        published_at=datetime(2026, 4, 28, tzinfo=timezone.utc),
        updated_at=datetime(2026, 7, 21, tzinfo=timezone.utc),
        fetched_at=datetime(2026, 11, 10, tzinfo=timezone.utc),
        verification="VERIFIED",
    )
    validated = validate_result_contract(
        "JOB_SEARCH", {"items": _items_to_result([posting]), "sources": []}
    )
    item = validated["items"][0]
    assert item["companySizeText"] == "50-150人"
    assert item["fieldSources"]["companySize"]["rawText"] == "50-150人"
    assert item["publishedAt"].startswith("2026-04-28")
    assert item["updatedAt"].startswith("2026-07-21")