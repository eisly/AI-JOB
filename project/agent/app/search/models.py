from __future__ import annotations

import hashlib
import re
import unicodedata
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

COMPANY_SIZES = (
    "SIZE_0_20",
    "SIZE_21_99",
    "SIZE_100_499",
    "SIZE_500_999",
    "SIZE_1000_9999",
    "SIZE_10000_PLUS",
    "SIZE_UNKNOWN",
)
COMPANY_SIZE_VALUES = COMPANY_SIZES
COMPANY_SIZE_LABELS = {
    "SIZE_0_20": "0-20人",
    "SIZE_21_99": "21-99人",
    "SIZE_100_499": "100-499人",
    "SIZE_500_999": "500-999人",
    "SIZE_1000_9999": "1000-9999人",
    "SIZE_10000_PLUS": "10000人及以上",
    "SIZE_UNKNOWN": "规模未知",
}
COMPANY_SIZE_BOUNDS = {
    "SIZE_0_20": (0, 20),
    "SIZE_21_99": (21, 99),
    "SIZE_100_499": (100, 499),
    "SIZE_500_999": (500, 999),
    "SIZE_1000_9999": (1000, 9999),
    "SIZE_10000_PLUS": (10000, None),
}
CANDIDATE_TYPES = ("INTERNSHIP", "CAMPUS", "EXPERIENCED")
EDUCATION_ORDER = {"HIGH_SCHOOL": 0, "ASSOCIATE": 1, "BACHELOR": 2, "MASTER": 3, "DOCTOR": 4}
EDUCATION_LABELS = {
    "HIGH_SCHOOL": "高中",
    "ASSOCIATE": "大专",
    "BACHELOR": "本科",
    "MASTER": "硕士",
    "DOCTOR": "博士",
}
CITY_CODES = {
    "北京": "110100",
    "北京市": "110100",
    "beijing": "110100",
    "上海": "310100",
    "上海市": "310100",
    "shanghai": "310100",
    "杭州": "330100",
    "杭州市": "330100",
    "hangzhou": "330100",
    "广州": "440100",
    "广州市": "440100",
    "guangzhou": "440100",
    "深圳": "440300",
    "深圳市": "440300",
    "shenzhen": "440300",
    "南京": "320100",
    "南京市": "320100",
    "nanjing": "320100",
    "成都": "510100",
    "成都市": "510100",
    "chengdu": "510100",
    "武汉": "420100",
    "武汉市": "420100",
    "wuhan": "420100",
    "东莞": "441900",
    "东莞市": "441900",
    "dongguan": "441900",
    "佛山": "440600",
    "佛山市": "440600",
    "foshan": "440600",
    "珠海": "440400",
    "珠海市": "440400",
    "zhuhai": "440400",
    "苏州": "320500",
    "苏州市": "320500",
    "suzhou": "320500",
    "西安": "610100",
    "西安市": "610100",
    "xian": "610100",
    "长沙": "430100",
    "长沙市": "430100",
    "changsha": "430100",
    "合肥": "340100",
    "合肥市": "340100",
    "hefei": "340100",
    "厦门": "350200",
    "厦门市": "350200",
    "xiamen": "350200",
    "天津": "120100",
    "天津市": "120100",
    "tianjin": "120100",
    "重庆": "500100",
    "重庆市": "500100",
    "chongqing": "500100",
    "大连": "210200",
    "大连市": "210200",
    "dalian": "210200",
    "沈阳": "210100",
    "沈阳市": "210100",
    "shenyang": "210100",
    "青岛": "370200",
    "青岛市": "370200",
    "qingdao": "370200",
    "济南": "370100",
    "济南市": "370100",
    "jinan": "370100",
    "郑州": "410100",
    "郑州市": "410100",
    "zhengzhou": "410100",
    "宁波": "330200",
    "宁波市": "330200",
    "ningbo": "330200",
    "无锡": "320200",
    "无锡市": "320200",
    "wuxi": "320200",
    "福州": "350100",
    "福州市": "350100",
    "fuzhou": "350100",
    "昆明": "530100",
    "昆明市": "530100",
    "kunming": "530100",
    "长春": "220100",
    "长春市": "220100",
    "changchun": "220100",
    "哈尔滨": "230100",
    "哈尔滨市": "230100",
    "harbin": "230100",
}
ROLE_KEYWORDS = {
    "JAVA_BACKEND": "Java 后端",
    "AI_APPLICATION_DEVELOPER": "AI 应用开发",
    "AI_AGENT_DEVELOPER": "AI Agent 智能体开发",
    "LLM_APPLICATION_DEVELOPER": "大模型应用开发",
    "RAG_APPLICATION_DEVELOPER": "RAG 检索增强生成开发",
    "FRONTEND": "前端开发",
    "ALGORITHM": "算法工程师",
    "TEST": "测试工程师",
    "DATA": "数据工程师",
}
SOURCE_STATUSES = ("AVAILABLE", "EMPTY", "PARTIAL", "FAILED", "UNAVAILABLE", "SKIPPED")
VERIFICATION_STATUSES = ("ACTIVE_CONFIRMED", "ACTIVE_PROBABLE", "UNKNOWN", "CLOSED")
VERIFICATION_REASONS = (
    "PAGE_ACCESS_FAILED",
    "HTTP_403",
    "HTTP_404",
    "TIMEOUT",
    "DETAIL_NOT_FOUND",
    "APPLY_LINK_MISSING",
    "OFFICIAL_CLOSED",
    "EXPIRED",
    "INSUFFICIENT_EVIDENCE",
    "ACTIVE_EVIDENCE_FOUND",
)
ROLE_CATEGORIES = {
    "JAVA_BACKEND": "Java 后端",
    "AI_APPLICATION_DEVELOPER": "AI 应用开发",
    "AI_AGENT_DEVELOPER": "AI Agent 开发",
    "LLM_APPLICATION_DEVELOPER": "大模型应用开发",
    "RAG_APPLICATION_DEVELOPER": "RAG 开发",
    "FRONTEND": "前端开发",
    "ALGORITHM": "算法工程师",
    "TEST": "测试工程师",
    "DATA": "数据工程师",
}


@dataclass(frozen=True)
class SearchFilters:
    keyword: str | None = None
    cities: tuple[str, ...] = ()
    candidate_types: tuple[str, ...] = ()
    company_sizes: tuple[str, ...] = ()
    include_unknown_size: bool = True
    max_results: int = 30


@dataclass
class SourcePosting:
    source_key: str
    source_name: str
    source_type: str
    source_url: str
    title: str
    company_name: str
    jd_text: str
    city_name: str | None = None
    city_code: str | None = None
    candidate_type: str | None = None
    salary_min_monthly_cents: int | None = None
    salary_max_monthly_cents: int | None = None
    salary_text: str | None = None
    experience_text: str | None = None
    experience_months_min: int | None = None
    education_text: str | None = None
    education_min: str | None = None
    company_size: str | None = None
    company_size_text: str | None = None
    field_sources: dict[str, Any] = field(default_factory=dict)
    published_at: datetime | None = None
    updated_at: datetime | None = None
    fetched_at: datetime = field(default_factory=datetime.utcnow)
    verification: str = "UNVERIFIED"
    verification_status: str = "UNKNOWN"
    verification_reason: str | None = None
    verification_evidence: str | None = None
    verification_checked_at: datetime | None = None
    verification_source_url: str | None = None
    verification_http_status: int | None = None
    verification_history: list[dict[str, Any]] = field(default_factory=list)
    recruitment_evidence: str | None = None
    recruitment_status: str = "UNKNOWN"
    search_providers: list[str] = field(default_factory=list)
    query: str | None = None
    warnings: list[str] = field(default_factory=list)
    aliases: list[dict[str, Any]] = field(default_factory=list)
    requirements: dict[str, Any] | None = None
    semantic_score: float | None = None
    reasons: list[str] = field(default_factory=list)
    gaps: list[dict[str, str]] = field(default_factory=list)

    @property
    def fingerprint(self) -> str:
        return posting_fingerprint(self.company_name, self.title, self.city_code or self.city_name or "")

    @property
    def is_closed(self) -> bool:
        return self.verification_status == "CLOSED" or self.recruitment_status == "CLOSED"

    @property
    def is_confirmed_active(self) -> bool:
        return self.verification_status == "ACTIVE_CONFIRMED"


@dataclass
class SourceResult:
    key: str
    name: str
    type: str
    status: str
    postings: list[SourcePosting] = field(default_factory=list)
    message: str = ""
    http_status: int | None = None
    duration_ms: int = 0
    verification: str = "UNVERIFIED"
    requires_credentials: bool = False
    verification_counts: dict[str, int] = field(default_factory=dict)
    providers: list[str] = field(default_factory=list)

    def as_status(self) -> dict[str, Any]:
        return {
            "key": self.key,
            "name": self.name,
            "status": self.status,
            "count": len(self.postings),
            "message": self.message,
            "httpStatus": self.http_status,
            "durationMs": self.duration_ms,
            "verification": self.verification,
            "requiresCredentials": self.requires_credentials,
            "verificationCounts": self.verification_counts,
            "providers": self.providers,
        }


def normalize_company_size(value: str | None) -> str:
    if not value:
        return "SIZE_UNKNOWN"
    text = str(value).strip().upper()
    if text in COMPANY_SIZES:
        return text
    return "SIZE_UNKNOWN"


def parse_company_size(text: str | None) -> str:
    original = unicodedata.normalize("NFKC", str(text or ""))
    if not original:
        return "SIZE_UNKNOWN"
    normalized = original.lower().replace(",", "").replace("，", "").replace(" ", "")
    compact = re.sub(r"[^\u4e00-\u9fff0-9a-z+\-~至以上下少于多]", "", normalized)
    if not compact:
        return "SIZE_UNKNOWN"
    if re.search(r"(不知|未知|保密|未公开|无法确定)", compact):
        return "SIZE_UNKNOWN"
    if re.search(r"(10000|10,000|1万|一万|万人|10000人以上|10000人及以上)", compact):
        return "SIZE_10000_PLUS"
    if re.search(r"(5000|5000人以上|5000人及以上)", compact):
        return "SIZE_1000_9999"
    if re.search(r"(少于20|少于 20|20人以下|0-20|0至20|0~20)", compact):
        return "SIZE_0_20"
    ranges = re.findall(r"(\d{1,6})\s*(?:-|~|至|到|—)\s*(\d{1,6})", compact)
    for raw_low, raw_high in ranges:
        low, high = int(raw_low), int(raw_high)
        if low > high:
            low, high = high, low
        for name, (min_value, max_value) in COMPANY_SIZE_BOUNDS.items():
            if min_value is None:
                continue
            if max_value is None:
                if low >= min_value:
                    return name
                continue
            if min_value <= low and high <= max_value:
                return name
        return "SIZE_UNKNOWN"
    count = re.search(r"(\d{1,6})\s*人", compact)
    if count:
        return size_for_headcount(int(count.group(1)))
    if re.search(r"(10000|1万|一万)", compact):
        return "SIZE_10000_PLUS"
    return "SIZE_UNKNOWN"


def size_for_headcount(count: int | None) -> str:
    if count is None or count < 0:
        return "SIZE_UNKNOWN"
    if count <= 20:
        return "SIZE_0_20"
    if count <= 99:
        return "SIZE_21_99"
    if count <= 499:
        return "SIZE_100_499"
    if count <= 999:
        return "SIZE_500_999"
    if count <= 9999:
        return "SIZE_1000_9999"
    return "SIZE_10000_PLUS"


def company_size_matches(size: str, selected: tuple[str, ...], include_unknown: bool) -> bool:
    normalized = normalize_company_size(size)
    if normalized == "SIZE_UNKNOWN":
        return include_unknown or "SIZE_UNKNOWN" in selected
    if not selected:
        return True
    return normalized in selected


def parse_salary(text: str | None) -> tuple[int | None, int | None]:
    if not text:
        return None, None
    original = unicodedata.normalize("NFKC", str(text))
    if re.search(r"(面议|保密|不限|薪资面谈)", original):
        return None, None
    if re.search(r"(元/天|元/日|天/元|日薪|时薪|元/时)", original):
        return None, None
    annual = bool(re.search(r"(年薪|/年|每年)", original))
    numbers = re.findall(r"(\d+(?:\.\d+)?)\s*([kK千万元]?)", original)
    if len(numbers) < 1:
        return None, None
    unit_hint = None
    if re.search(r"[kK]", original):
        unit_hint = "k"
    elif "万" in original:
        unit_hint = "万"
    elif "千" in original:
        unit_hint = "千"
    values: list[int] = []
    for raw, unit in numbers[:2]:
        try:
            number = float(raw)
        except ValueError:
            return None, None
        effective = unit or unit_hint
        if effective in ("k", "K", "千"):
            yuan = number * 1000
        elif effective == "万":
            yuan = number * 10000
        elif effective == "元" or number >= 1000:
            yuan = number
        else:
            return None, None
        if annual:
            yuan = yuan / 12
        values.append(int(round(yuan * 100)))
    low = values[0]
    high = values[1] if len(values) > 1 else values[0]
    if low > high:
        low, high = high, low
    if low < 0 or high > 100_000_000:
        return None, None
    return low, high


def parse_salary_text(text: str | None) -> str | None:
    if not text:
        return None
    value = str(text).strip()
    return value or None


def city_code_for(value: str | None) -> str | None:
    if not value:
        return None
    text = unicodedata.normalize("NFKC", str(value)).strip().lower()
    if not text:
        return None
    for name, code in CITY_CODES.items():
        if text == name.lower():
            return code
    for name, code in CITY_CODES.items():
        if name.lower() in text:
            return code
    return None


def infer_candidate_type(*texts: str | None) -> tuple[str, bool]:
    text = " ".join(item for item in texts if item)
    if re.search(r"(实习|(?<![a-z])intern(?:ship)?(?![a-z]))", text, re.IGNORECASE):
        return "INTERNSHIP", True
    if re.search(r"(校招|校园招聘|应届|(?<![a-z])campus(?![a-z])|(?<![a-z])graduate(?![a-z]))", text, re.IGNORECASE):
        return "CAMPUS", True
    if re.search(r"(社招|(?<![a-z])experienced(?![a-z])|三年以上|3年以上|五年以上|5年以上|工作经验)", text, re.IGNORECASE):
        return "EXPERIENCED", True
    return "EXPERIENCED", False


CN_NUMBERS = {"一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}


def parse_experience_months(text: str | None) -> int | None:
    if not text:
        return None
    value = unicodedata.normalize("NFKC", str(text))
    if re.search(r"(不限|无要求|不限经验)", value):
        return None
    match = re.search(r"([0-9]{1,2})\s*[-~至到]\s*([0-9]{1,2})\s*年", value)
    if match:
        return int(match.group(1)) * 12
    match = re.search(r"([0-9]{1,2})\s*年(?:以上|及以上|\+)", value)
    if match:
        return int(match.group(1)) * 12
    cn = re.search(r"([一二两三四五六七八九十])\s*年(?:以上|及以上)?", value)
    if cn:
        return CN_NUMBERS[cn.group(1)] * 12
    if re.search(r"(应届|无需经验|经验不限)", value):
        return 0
    return None


def education_from_text(text: str | None) -> str | None:
    if not text:
        return None
    for code, label in EDUCATION_LABELS.items():
        if label in text:
            return code
    if "本科" in text:
        return "BACHELOR"
    if "硕士" in text or "研究生" in text:
        return "MASTER"
    if "博士" in text:
        return "DOCTOR"
    if "大专" in text or "专科" in text:
        return "ASSOCIATE"
    if "高中" in text:
        return "HIGH_SCHOOL"
    return None


def normalize_key(value: str) -> str:
    text = unicodedata.normalize("NFKC", value or "").lower()
    text = re.sub(r"[（(].*?[）)]", "", text)
    text = re.sub(r"[\s\-_/·,，。:：;；'\"“”‘’、|]+", "", text)
    return text


def normalize_company(value: str) -> str:
    text = normalize_key(value)
    for suffix in ("股份有限公司", "有限责任公司", "有限公司", "集团", "科技", "网络", "信息技术"):
        if text.endswith(suffix) and len(text) > len(suffix):
            text = text[: -len(suffix)]
    return text


def normalize_title(value: str) -> str:
    text = normalize_key(value)
    for suffix in ("急聘", "热招", "招聘", "高薪"):
        if text.endswith(suffix) and len(text) > len(suffix):
            text = text[: -len(suffix)]
    return text


def posting_fingerprint(company: str, title: str, city: str) -> str:
    normalized_city = normalize_key(city)
    if normalized_city.endswith("市") and len(normalized_city) > 1:
        normalized_city = normalized_city[:-1]
    raw = "|".join((normalize_company(company), normalize_title(title), normalized_city))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:32]
