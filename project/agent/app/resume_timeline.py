"""Recognize dates within one resume entry without comparing different entries."""

import re


_DATE = r"(?P<{0}_year>(?:19|20)\d{{2}})(?:[./年-](?P<{0}_month>0?[1-9]|1[0-2])月?)?"
_RANGE = re.compile(
    rf"(?<![\d./]){_DATE.format('start')}[ \t]*(?:[-–—~～至到])[ \t]*"
    rf"(?:{_DATE.format('end')}|至今|目前|[Pp]resent|[Nn]ow)(?![\d./])"
    rf"|(?<![\d./]){_DATE.format('current')}[ \t]*至今"
)
_CONCURRENT_ENTRIES = re.compile(
    r"(?:日期|时间(?:线|段|范围|区间)?|经历)(?:存在|有|相互|相|出现|部分|有所|上)?"
    r"(?:重叠|交叉|交集|冲突|交叠)"
    r"|(?:实习|项目|教育|学业|在校).{0,20}(?:时间|同一时段|同期|同时|并行).{0,12}"
    r"(?:混乱|混淆|存疑|疑问|难以|不一致|不清晰|需.{0,4}说明)"
    r"|(?:跨章节|跨模块|项目与实习|实习与项目).{0,18}(?:顺序|先后|排序)"
    r"|(?:补充|注明|明确|说明|未交代).{0,16}(?:实习性质|暑期实习|是否兼职|并行方式)"
)
_NORMAL_CONCURRENCY = re.compile(r"(?:属于正常|是正常|不扣分|不影响|不构成|不视为|无需说明|无需解释)")


def has_unsupported_timeline_claim(*values: str | None) -> bool:
    for value in values:
        for sentence in re.split(r"[。！？；\n]", value or ""):
            if _CONCURRENT_ENTRIES.search(sentence) and not _NORMAL_CONCURRENCY.search(sentence):
                return True
    return False


def timeline_level(text: str) -> int | None:
    """Use readable date ranges as evidence and lower the level for a reversed range."""
    found_range = False
    for match in _RANGE.finditer(text):
        found_range = True
        if _reversed(match):
            return 2
    return 5 if found_range else None


def quotes_reversed_range(quote: str) -> bool:
    return any(_reversed(match) for match in _RANGE.finditer(quote))


def _reversed(match: re.Match[str]) -> bool:
    if match["end_year"] is None:
        return False
    # A missing month denotes the whole year. Flag only an unambiguous reversal.
    start = (int(match["start_year"]), int(match["start_month"] or 1))
    end = (int(match["end_year"]), int(match["end_month"] or 12))
    return end < start
