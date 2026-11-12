from __future__ import annotations

from datetime import datetime, timezone

from app.search.sources.baidu import _postings
from app.search.sources.tencent import _to_posting
from app.search.text_extract import extract_job_fields

JD = (
    "工作地点：深圳。薪资 20-30K，每年 13 薪。学历本科及以上，经验 3 年以上，团队规模 500-999 人。"
    "负责后端服务接口开发与性能优化。"
)
NOW = datetime(2026, 11, 7, tzinfo=timezone.utc)


def test_generic_extractor_reads_fields_from_jd() -> None:
    fields = extract_job_fields(JD, title="后端开发实习生")
    assert (fields.city_name, fields.city_code) == ("深圳", "440300")
    assert fields.salary_text == "20-30K"
    assert fields.salary_min_monthly_cents and fields.salary_max_monthly_cents
    assert fields.education_min == "BACHELOR"
    assert fields.experience_months_min == 36
    assert fields.company_size == "SIZE_500_999"


def test_generic_extractor_does_not_fabricate_missing_fields() -> None:
    fields = extract_job_fields("负责后端服务开发与接口维护。", title="后端开发工程师")
    assert fields.salary_text is None
    assert fields.salary_min_monthly_cents is None
    assert fields.education_min is None
    assert fields.experience_months_min is None
    assert fields.company_size == "SIZE_UNKNOWN"


def test_baidu_posting_fills_fields_from_jd() -> None:
    row = {
        "postId": "e3cec5b8-b7a3-4946-99fc-b292b749cd53",
        "name": "后端开发实习生",
        "workPlace": "深圳市",
        "workContent": JD,
        "serviceCondition": "",
        "publishDate": "2026-11-05",
    }
    postings = list(_postings(row, "INTERNSHIP", NOW))
    assert len(postings) == 1
    posting = postings[0]
    assert (posting.city_name, posting.city_code) == ("深圳市", "440300")
    assert posting.salary_text == "20-30K"
    assert posting.salary_min_monthly_cents and posting.salary_max_monthly_cents
    assert posting.education_min == "BACHELOR"
    assert posting.experience_months_min == 36
    assert posting.company_size == "SIZE_500_999"


def test_tencent_posting_fills_fields_from_jd() -> None:
    post = {
        "RecruitPostName": "后端开发实习生",
        "Responsibility": JD,
        "PostId": "123456",
        "LocationName": "深圳",
        "ComName": "腾讯",
        "RequireWorkYearsName": "",
        "LastUpdateTime": "2026-11-05",
    }
    posting = _to_posting(post)
    assert posting is not None
    assert posting.salary_text == "20-30K"
    assert posting.education_min == "BACHELOR"
    assert posting.experience_months_min == 36
    assert posting.company_size == "SIZE_500_999"

def test_baidu_structured_fields_take_precedence_over_jd_text() -> None:
    row = {
        "postId": "43b42d22-86f9-486b-adf6-7ea633dd4c92",
        "name": "Java 开发工程师",
        "workPlace": "北京市",
        "workContent": "负责后端服务开发。",
        "serviceCondition": "学历不限，经验不限。",
        "education": "本科",
        "workYears": "3-5年",
        "publishDate": "2026-04-27",
    }
    postings = list(_postings(row, "INTERNSHIP", NOW))
    assert len(postings) == 1
    posting = postings[0]
    assert posting.city_code == "110100"
    assert posting.education_text == "本科"
    assert posting.education_min == "BACHELOR"
    assert posting.experience_text == "3-5年"
    assert posting.experience_months_min == 36