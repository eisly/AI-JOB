from __future__ import annotations

import html as html_lib
import re
from dataclasses import dataclass
from datetime import datetime, timezone

from .models import (
    CITY_CODES,
    city_code_for,
    education_from_text,
    parse_company_size,
    parse_experience_months,
    parse_salary,
)

CITY_LABEL = re.compile(
    r"(?:工作地点|工作城市|办公地点|职位地点|上班地点|职位地址|工作地址|上班地址|地点|base)\s*[:：]?\s*([\u4e00-\u9fff]{2,16})",
    re.IGNORECASE,
)
SALARY_AMOUNT = r"\d{1,6}(?:\.\d+)?"
SALARY_UNIT = r"[kK千万]|元"
SALARY_PERIOD = r"\s*/\s*(?:月|年|天|日|小时|时)"
SALARY_PATTERN = re.compile(
    rf"(?<![\dA-Za-z./-])(?:[¥￥]\s*)?"
    rf"(?:{SALARY_AMOUNT}\s*(?:{SALARY_UNIT})?\s*[-–—~～至到]\s*)?"
    rf"{SALARY_AMOUNT}(?:\s*(?:{SALARY_UNIT})(?:{SALARY_PERIOD})?|{SALARY_PERIOD})"
    r"(?![\dA-Za-z\u4e00-\u9fff]|\s*/)"
)
SALARY_LABEL = re.compile(r"(薪资(?:待遇|范围)?|薪酬|工资|月薪|年薪|日薪|时薪)\s*[:：]\s*")
SIZE_PATTERN = re.compile(r"(\d{2,5}\s*[-~至]\s*\d{2,5}\s*人|\d{2,5}\s*人以上|少于\s*\d{2,5}\s*人)")
EXPERIENCE_PATTERN = re.compile(
    r"((?:\d{1,2}\s*[-~至]\s*\d{1,2}\s*年(?:经验)?)|(?:\d{1,2}\s*年(?:以上|及以上))|应届(?:毕业生)?|经验不限|无经验)"
)
EDUCATION_PATTERN = re.compile(
    r"学历不限|不限学历|(?:全日制\s*)?(?:统招\s*)?"
    r"(?:高中|大专|专科|本科|硕士(?:研究生)?|博士(?:研究生)?|研究生)(?:\s*(?:及以上|以上|或以上))?"
)
EDUCATION_LABEL = re.compile(r"(?:最低学历|学历要求|学历)\s*[:：]\s*")
DATE_PATTERN = re.compile(r"(20\d{2}\s*[-/年]\s*\d{1,2}\s*[-/月]\s*\d{1,2}\s*日?)")


@dataclass
class ExtractedJobFields:
    city_name: str | None = None
    city_code: str | None = None
    salary_text: str | None = None
    salary_min_monthly_cents: int | None = None
    salary_max_monthly_cents: int | None = None
    company_size: str = "SIZE_UNKNOWN"
    company_size_text: str | None = None
    experience_text: str | None = None
    experience_months_min: int | None = None
    education_text: str | None = None
    education_min: str | None = None
    published_at: datetime | None = None


def extract_city(text: str) -> tuple[str | None, str | None]:
    for match in CITY_LABEL.finditer(text):
        candidate = match.group(1).strip()
        code = city_code_for(candidate)
        if code:
            return candidate[:100], code
    return None, None


def extract_salary(text: str) -> tuple[str | None, int | None, int | None]:
    if text.strip() in {"面议", "薪资面议", "薪资面谈"}:
        return text.strip(), None, None
    for label in SALARY_LABEL.finditer(text):
        if text.startswith(("面议", "面谈"), label.end()):
            return "薪资" + text[label.end():label.end() + 2], None, None
        match = SALARY_PATTERN.match(text, label.end())
        if match:
            snippet = " ".join(match.group().split())
            if label[1] in {"年薪", "日薪", "时薪"} and "/" not in snippet:
                snippet = " ".join(text[label.start():match.end()].split())
            low, high = parse_salary(snippet)
            return snippet, low, high
    match = SALARY_PATTERN.search(text)
    if not match:
        return None, None, None
    snippet = " ".join(match.group().split())
    low, high = parse_salary(snippet)
    return snippet, low, high


def extract_size(text: str) -> tuple[str, str | None]:
    match = re.search(r"(?:公司规模|企业规模|人数规模|团队规模)\s*[:：]?\s*" + SIZE_PATTERN.pattern, text)
    if not match:
        return "SIZE_UNKNOWN", None
    snippet = " ".join(match.group(1).split())[:80]
    return parse_company_size(snippet), snippet


def extract_experience(text: str) -> tuple[str | None, int | None]:
    match = EXPERIENCE_PATTERN.search(text)
    if not match:
        return None, None
    snippet = " ".join(match.group(1).split())[:120]
    return snippet, parse_experience_months(snippet)


def extract_education(text: str) -> tuple[str | None, str | None]:
    for label in EDUCATION_LABEL.finditer(text):
        match = EDUCATION_PATTERN.match(text, label.end())
        if match:
            break
        if text.startswith("不限", label.end()):
            return "学历不限", None
    else:
        match = EDUCATION_PATTERN.search(text)
    if not match:
        return None, None
    snippet = " ".join(match.group().split())
    return snippet, education_from_text(snippet)


def extract_published(text: str) -> datetime | None:
    match = re.search(r"(?:发布时间|发布日期|发布于|更新于)\s*[:：]?\s*" + DATE_PATTERN.pattern, text)
    if not match:
        return None
    value = match.group(1)
    try:
        date_text = value.replace("年", "-").replace("月", "-").replace("日", "").replace("/", "-")
        parsed = datetime.fromisoformat(date_text)
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _html_text(fragment: str) -> str:
    text = html_lib.unescape(fragment)
    text = re.sub(r"<(script|style)[^>]*>.*?</\1>", " ", text, flags=re.IGNORECASE | re.DOTALL)
    text = re.sub(r"<[^>]+>", " ", text)
    return " ".join(text.split())


def extract_listing_fields(html: str) -> dict:
    """优先解析招聘平台岗位主区块，避免整页推荐岗位/导航污染字段。"""
    result: dict = {}
    salary_match = re.search(r'<span[^>]*class="[^"]*salary[^"]*"[^>]*>(.*?)</span>', html,
                             re.IGNORECASE | re.DOTALL)
    if salary_match:
        salary, low, high = extract_salary(_html_text(salary_match.group(1)))
        if salary:
            result.update(salary_text=salary, salary_min_monthly_cents=low,
                          salary_max_monthly_cents=high)
    props_match = re.search(r'<div[^>]*class="[^"]*job-properties[^"]*"[^>]*>(.*?)</div>', html,
                            re.IGNORECASE | re.DOTALL)
    if props_match:
        props = _html_text(props_match.group(1))
        tokens = [token for token in re.split(r"\s+", props) if token and token != "·"]
        if tokens:
            city = tokens[0]
            code = city_code_for(city)
            if code:
                result.update(city_name=city[:100], city_code=code)
        education_text, education_min = extract_education(props)
        if education_text:
            result.update(education_text=education_text, education_min=education_min)
        experience_text, experience_months = extract_experience(props)
        if experience_text and "实习" not in props:
            result.update(experience_text=experience_text, experience_months_min=experience_months)
    size_match = re.search(r"人数规模\s*[:：]?\s*(?:<[^>]+>\s*)*([0-9]{1,6}\s*[-~至]\s*[0-9]{1,6}\s*人|[0-9]{1,6}\s*人以上)",
                           html, re.IGNORECASE)
    if size_match:
        size_text = " ".join(size_match.group(1).split())[:80]
        result.update(company_size=parse_company_size(size_text), company_size_text=size_text)
    company_match = re.search(r"公司信息\s*(.{0,160}?)\s*(?:关注|企业行业|公司官网)", html,
                              re.IGNORECASE | re.DOTALL)
    if company_match:
        company = _html_text(company_match.group(1))[:200]
        if company and re.search(r"[\u4e00-\u9fff]", company):
            result["company_name"] = company
    return result


def extract_job_fields(text: str, *, title: str = "") -> ExtractedJobFields:
    """从 JD/页面原文提取字段；找不到就留空，不凭常识补全。"""
    combined = f"{title} {text}"
    salary_text, salary_min, salary_max = extract_salary(combined)
    experience_text, experience_months = extract_experience(combined)
    education_text, education_min = extract_education(combined)
    city_name, city_code = extract_city(combined)
    company_size, company_size_text = extract_size(combined)
    return ExtractedJobFields(
        city_name=city_name,
        city_code=city_code,
        salary_text=salary_text,
        salary_min_monthly_cents=salary_min,
        salary_max_monthly_cents=salary_max,
        company_size=company_size,
        company_size_text=company_size_text,
        experience_text=experience_text,
        experience_months_min=experience_months,
        education_text=education_text,
        education_min=education_min,
        published_at=extract_published(combined),
    )
