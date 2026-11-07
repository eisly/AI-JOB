from __future__ import annotations

import re

from .expansion import expand_keyword, strip_candidate_suffix
from .models import SourcePosting

GENERIC_TERMS = {"招聘", "职位", "岗位", "实习", "校招", "社招", "校园招聘"}
NON_DEVELOPMENT_TITLE = re.compile(
    r"(产品经理|产品运营|产品助理|产品实习|数据标注|研究助理|科研助理|助教|教师|招生|行政|财务|销售|市场|"
    r"人力资源|招聘专员|account executive|sales|abuse|compliance|recruit|marketing|finance|legal|"
    r"product manager|operations|investigator|designer)",
    re.IGNORECASE,
)
DEVELOPMENT_TITLE = re.compile(
    r"(开发|研发|工程师|工程|架构|测试|算法|科学|技术|实习|校招|"
    r"back\s*end|front\s*end|full\s*stack|engineer|developer|scientist|sde|intern|graduate)",
    re.IGNORECASE,
)


def compact(value: str) -> str:
    return re.sub(r"[\s\-_/·,，。:：;；'\"“”‘’、|（）()]+", "", str(value or "").casefold())


def keyword_relevant(posting: SourcePosting, keyword: str) -> bool:
    """官方来源岗位相关性：先要求标题属于研发岗，再按扩展变体任一命中。"""
    title = posting.title.casefold()
    terms = keyword.casefold().split()
    if NON_DEVELOPMENT_TITLE.search(title):
        return False
    if "".join(terms) == "java后端":
        return any(word in title for word in ("后端", "后台", "服务端", "服务器", "java开发", "java 开发")) and (
            re.search(r"(?<![a-z0-9_])java(?![a-z0-9_])", f"{title} {posting.jd_text}", flags=re.IGNORECASE) is not None)
    if "".join(terms) == "ai应用开发":
        if any(word in title for word in ("产品经理", "产品运营", "数据标注", "测试实习", "算法")):
            return False
        jd = posting.jd_text.casefold()
        return any(word in title for word in ("应用", "开发", "研发", "工程", "后端", "服务端")) and any(
            word in title or word in jd for word in ("ai", "大模型", "agent", "智能体", "llm")) and any(
            word in title or word in jd for word in ("应用开发", "应用研发", "智能体开发", "智能体应用", "智能体研发",
                                                    "agent 开发", "agent开发", "agent应用", "agent 应用", "软件开发",
                                                    "服务端研发", "后端研发"))
    if not DEVELOPMENT_TITLE.search(title):
        return False
    combined = compact(f"{posting.title} {posting.jd_text}")
    for variant in expand_keyword(keyword, limit=8):
        core, _ = strip_candidate_suffix(variant)
        normalized = compact(core)
        if normalized and normalized in combined:
            return True
    tokens = [
        token for token in re.findall(r"[a-z][a-z0-9+#\.]{1,}|[\u4e00-\u9fff]{2,}", keyword.casefold())
        if token not in GENERIC_TERMS
    ]
    return any(token in combined for token in tokens)