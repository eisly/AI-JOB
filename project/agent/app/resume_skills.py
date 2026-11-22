"""简历技能补漏与原文引用定位。

- 目录补漏：用技能目录（含别名）在简历原文中做词边界匹配，补回模型遗漏的明确技能。
- 引用定位：从带页码标记的提取文本中定位引用片段，计算实际页码与行号。
- 区分前端 React 与 Agent 的 ReAct（冲突 needle 按精确大小写路由）、Java 与 JavaScript（词边界）。
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from .schemas import SkillCatalogItem

_PAGE_MARKER = re.compile(r"^\[page:(\d+)\]$")
_JOIN = r"[\s\-_—–]*"


@dataclass
class TextLine:
    page: int
    line_no: int
    text: str
    compact: str


def _compact(value: str) -> str:
    """去空白与连字符、转小写；保留 C++ / C# / Node.js 中的符号。"""
    return re.sub(_JOIN, "", value).lower()


def _exact_form(value: str) -> str:
    return re.sub(_JOIN, "", value)


def _index_lines(text: str) -> list[TextLine]:
    lines: list[TextLine] = []
    page = 1
    for line_no, raw_line in enumerate(text.splitlines(), 1):
        stripped = raw_line.strip()
        marker = _PAGE_MARKER.match(stripped)
        if marker:
            page = int(marker.group(1))
            continue
        lines.append(TextLine(page=page, line_no=line_no, text=stripped, compact=_compact(stripped)))
    return lines


_PATTERN_CACHE: dict[str, re.Pattern[str]] = {}


def _needle_pattern(needle: str) -> re.Pattern[str]:
    """忽略空白/连字符/大小写的词边界正则。"""
    pattern = _PATTERN_CACHE.get(needle)
    if pattern is None:
        inner = _JOIN.join(re.escape(ch) for ch in needle)
        pattern = re.compile(r"(?<![0-9a-z])" + inner + r"(?![0-9a-z])", re.IGNORECASE)
        _PATTERN_CACHE[needle] = pattern
    return pattern


def _needle_usable(needle: str) -> bool:
    """含字母/数字的词按词边界匹配；纯中文等其他文字要求至少 2 个字符，避免误命中。"""
    return bool(needle) and (re.search(r"[0-9a-z]", needle) or len(needle) >= 2)


@dataclass
class RecoveredSkill:
    skill_code: str
    quote: str
    claim: str
    location: str


def _catalog_needles(catalog: list[SkillCatalogItem]) -> dict[str, list[tuple[str, str]]]:
    """构建 needle → [(code, 精确大小写形式)] 映射。"""
    mapping: dict[str, list[tuple[str, str]]] = {}
    for item in catalog:
        for value in [item.name, *(item.aliases or [])]:
            needle = _compact(value)
            if not needle:
                continue
            entry = (item.code, _exact_form(value))
            if entry not in mapping.setdefault(needle, []):
                mapping[needle].append(entry)
    return mapping


def _codes_for_match(entries: list[tuple[str, str]], match_text: str) -> list[str]:
    """命中片段归属：needle 只属于一个技能时直接归属；
    多个技能共享 needle（如 React/ReAct 的 compact 同为 react）时按精确大小写形式路由，
    无法路由（如全大写 REACT）时返回空，保守跳过。
    """
    codes = list(dict.fromkeys(code for code, _ in entries))
    if len(codes) <= 1:
        return codes
    exact = _exact_form(match_text)
    routed = list(dict.fromkeys(code for code, form in entries if form == exact))
    return routed


def recover_catalog_skills(
    text: str,
    catalog: list[SkillCatalogItem],
    existing_codes: set[str],
) -> list[RecoveredSkill]:
    """补回目录中明确出现但未被模型识别的技能。"""
    lines = _index_lines(text)
    mapping = _catalog_needles(catalog)
    matched: set[str] = set()
    result: list[RecoveredSkill] = []
    for line in lines:
        for needle, entries in mapping.items():
            if not _needle_usable(needle):
                continue
            match = _needle_pattern(needle).search(line.text)
            if match is None:
                continue
            for code in _codes_for_match(entries, match.group(0)):
                if code in existing_codes or code in matched:
                    continue
                matched.add(code)
                result.append(
                    RecoveredSkill(
                        skill_code=code,
                        quote=line.text[:1000],
                        claim=line.text[:500],
                        location=f"page:{line.page};line:{line.line_no}",
                    )
                )
    return result


def locate_quote(text: str, quote: str) -> str | None:
    """定位引用片段，返回 ``page:N;line:M``（行号为全文行号，从 1 开始）。

    匹配语义与 quote_in_text 一致：忽略全部空白与大小写。找不到返回 None。
    多行引用只在同一页内拼接匹配，并按命中偏移定位到首行。
    """
    lines = _index_lines(text)
    target = _compact(quote)
    if not target:
        return None
    for line in lines:
        if target in line.compact:
            return f"page:{line.page};line:{line.line_no}"
    import bisect
    from itertools import groupby

    for _, group in groupby(lines, key=lambda item: item.page):
        page_lines = list(group)
        offsets: list[int] = []
        joined_parts: list[str] = []
        total = 0
        for line in page_lines:
            offsets.append(total)
            joined_parts.append(line.compact)
            total += len(line.compact)
        pos = "".join(joined_parts).find(target)
        if pos >= 0:
            index = bisect.bisect_right(offsets, pos) - 1
            line = page_lines[index]
            return f"page:{line.page};line:{line.line_no}"
    return None

