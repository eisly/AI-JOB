"""定向优化行号引用方案（resume-optimize-v2）针对性回归测试。

覆盖：跨行中文/CRLF、中文引号标点全角空格、真实换行、重复片段与长段落、
未知/越界/非法区间拒绝（伪造引用负例）、回填结果为原文严格子串（Java
requireSource 等价断言：直接 substring 而非宽松匹配）。
"""
from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.errors import HandlerValidationError
from app.handlers import ExecutionContext, ResumeOptimizeHandler
from app.schemas import (
    AIResumeOptimizeDraft,
    DocumentRef,
    LineSpan,
    ResumeOptimizePayload,
    ResumeOptimizeSectionDraft,
)

UUID = "3f000000-0000-4000-8000-000000000001"
UUID2 = "3f000000-0000-4000-8000-000000000002"


class FakeFiles:
    def __init__(self, text: str) -> None:
        self._text = text

    async def extract(self, document: DocumentRef):
        from app.files import ExtractedDocument

        normalized = document.text.replace("\r\n", "\n").replace("\r", "\n").strip() if document.text else ""
        return ExtractedDocument(text=normalized, text_hash="hash", source="inline-text")


def make_payload(text: str) -> ResumeOptimizePayload:
    document = DocumentRef(
        versionId=UUID, fileName="resume.pdf", mimeType="application/pdf",
        sizeBytes=100, text=text,
    )
    return ResumeOptimizePayload(
        document=document, jobId=UUID2, jobVersion=1, jdText="岗位职责描述",
    )


def make_ctx(text: str) -> ExecutionContext:
    async def noop_stage(name: str, progress: int | None = None) -> None:
        return None

    payload = make_payload(text)
    return ExecutionContext(
        run=None, payload=payload, settings=None, llm=None,
        files=FakeFiles(text), emit_stage=noop_stage,
    )


def make_draft(sections: list[ResumeOptimizeSectionDraft], title: str = "定向优化候选") -> AIResumeOptimizeDraft:
    return AIResumeOptimizeDraft(title=title, sections=sections)


def make_section(key: str, start: int, end: int, sources: list[tuple[int, int]] | None = None,
                 suggested: str = "建议：补充量化成果。") -> ResumeOptimizeSectionDraft:
    return ResumeOptimizeSectionDraft(
        sectionKey=key, title="段落标题",
        originalSpan=LineSpan(start=start, end=end),
        suggestedText=suggested,
        sourceSpans=[LineSpan(start=s, end=e) for s, e in (sources or [])],
    )


# ---------- prepare：行号标记与偏移 ----------

@pytest.mark.asyncio
async def test_prepare_marks_lines_and_offsets_crlf():
    text = "教育经历\r\n主修课程：网络安全、高级软件工程\r\n\r\n自我评价"
    prepared = await ResumeOptimizeHandler().prepare(make_ctx(text))
    # CRLF 被 FileFetcher 归一为 LF；空行也是行
    assert prepared["document_lines"] == ["教育经历", "主修课程：网络安全、高级软件工程", "", "自我评价"]
    marked = prepared["document_text"]
    assert marked.split("\n")[0] == "[L1] 教育经历"
    assert marked.split("\n")[2] == "[L3] "
    # 偏移可精确切取第 2 行
    offsets = prepared["document_line_offsets"]
    lines = prepared["document_lines"]
    sliced = prepared["document_source_text"][offsets[1] : offsets[1] + len(lines[1])]
    assert sliced == "主修课程：网络安全、高级软件工程"


@pytest.mark.asyncio
async def test_prepare_keeps_chinese_punctuation_and_fullwidth_space():
    text = "意向岗位：前端开发，项目经理\n专业能力：本人能将专业所学知识　运用于实际操作中。“引号”"
    prepared = await ResumeOptimizeHandler().prepare(make_ctx(text))
    source = prepared["document_source_text"]
    # 中文引号、全角空格逐字保留（不改标点、不补文字）
    assert "运用于实际操作中。“引号”" in source
    assert "所学知识　运用于" in source


# ---------- 回填：严格子串（Java requireSource 等价） ----------

@pytest.mark.asyncio
async def test_validate_refills_exact_substring_with_newline():
    # 真实换行（非字面 \n）：切取含行间换行，Java 严格 contains 必过
    text = "项目一：AI 监控设备项目集成交付工作\n编写视联智盒 AI 监控设备\n项目二：数据平台建设"
    handler = ResumeOptimizeHandler()
    prepared = await handler.prepare(make_ctx(text))
    draft = make_draft([make_section("project1", 1, 2, sources=[(1, 1)])])
    result = handler.validate_business(None, draft, prepared, {})
    assert len(result.sections) == 1
    original = result.sections[0].original_text
    assert original == "项目一：AI 监控设备项目集成交付工作\n编写视联智盒 AI 监控设备"
    # 严格子串断言（非宽松归一匹配——与 Java requireSource 的 contains 一致）
    assert text.find(original) >= 0
    assert text.find(result.sections[0].source_quotes[0]) >= 0


@pytest.mark.asyncio
async def test_validate_duplicate_fragment_lines_located_by_line_id():
    # 重复片段：两行完全相同 → 行号唯一确定，不取第一次命中
    text = "熟悉 Java 后端开发\n熟悉 Java 后端开发\n熟悉 Python 数据处理"
    handler = ResumeOptimizeHandler()
    prepared = await handler.prepare(make_ctx(text))
    draft = make_draft([make_section("dup", 2, 2)])
    result = handler.validate_business(None, draft, prepared, {})
    offsets = prepared["document_line_offsets"]
    source = prepared["document_source_text"]
    assert result.sections[0].original_text == "熟悉 Java 后端开发"
    # 偏移切片证明来自第 2 行（find 恒返回首次命中 0，不能用作位置证明）
    start = offsets[1]
    assert source[start : start + len("熟悉 Java 后端开发")] == "熟悉 Java 后端开发"


@pytest.mark.asyncio
async def test_validate_long_paragraph_allowed_within_limit():
    long_line = "超长段落" + "内容" * 1000  # ~2006 chars < 10000
    text = long_line + "\n普通行"
    handler = ResumeOptimizeHandler()
    prepared = await handler.prepare(make_ctx(text))
    draft = make_draft([make_section("long", 1, 1)])
    result = handler.validate_business(None, draft, prepared, {})
    assert result.sections[0].original_text == long_line


# ---------- 拒绝：未知/越界/非法区间（伪造引用负例） ----------

@pytest.mark.asyncio
async def test_validate_rejects_unknown_line_id():
    text = "第一行\n第二行\n第三行"
    handler = ResumeOptimizeHandler()
    prepared = await handler.prepare(make_ctx(text))
    draft = make_draft([make_section("p1", 1, 99)])
    with pytest.raises(HandlerValidationError, match="未知行号.*合法范围 1..3"):
        handler.validate_business(None, draft, prepared, {})


@pytest.mark.asyncio
async def test_validate_rejects_reversed_span():
    text = "第一行\n第二行"
    handler = ResumeOptimizeHandler()
    prepared = await handler.prepare(make_ctx(text))
    draft = make_draft([make_section("p1", 2, 1)])
    with pytest.raises(HandlerValidationError, match="行号区间非法"):
        handler.validate_business(None, draft, prepared, {})


@pytest.mark.asyncio
async def test_validate_rejects_blank_line_span():
    text = "第一行\n\n第三行"
    handler = ResumeOptimizeHandler()
    prepared = await handler.prepare(make_ctx(text))
    draft = make_draft([make_section("p1", 2, 2)])
    with pytest.raises(HandlerValidationError, match="空行区间"):
        handler.validate_business(None, draft, prepared, {})


@pytest.mark.asyncio
async def test_validate_rejects_overlong_span():
    text = "行" * 6000 + "\n" + "行" * 6000  # 单区间合计 12001 > 10000
    handler = ResumeOptimizeHandler()
    prepared = await handler.prepare(make_ctx(text))
    draft = make_draft([make_section("p1", 1, 2)])
    with pytest.raises(HandlerValidationError, match="超过 10000 字符上限"):
        handler.validate_business(None, draft, prepared, {})


@pytest.mark.asyncio
async def test_validate_rejects_duplicate_section_key():
    text = "第一行\n第二行"
    handler = ResumeOptimizeHandler()
    prepared = await handler.prepare(make_ctx(text))
    draft = make_draft([make_section("dup", 1, 1), make_section("dup", 2, 2)])
    with pytest.raises(HandlerValidationError, match="sectionKey 必须唯一"):
        handler.validate_business(None, draft, prepared, {})


def test_span_schema_rejects_zero_and_negative():
    with pytest.raises(ValidationError):
        LineSpan(start=0, end=1)
    with pytest.raises(ValidationError):
        LineSpan(start=1, end=-1)


# ---------- 输出结构：草稿模型无原文抄写字段（伪造原文无入口） ----------

def test_draft_model_ignores_forged_original_text_field():
    # 模型若仍尝试输出 originalText（抄写原文），extra=ignore 使其被丢弃，
    # 回填仍以行号切取为准——不存在“接受模型原文”的通道
    draft = AIResumeOptimizeDraft.model_validate({
        "title": "t",
        "sections": [{
            "sectionKey": "s1", "title": "x",
            "originalSpan": {"start": 1, "end": 1},
            "originalText": "模型伪造的原文",
            "suggestedText": "y",
        }],
    })
    assert draft.sections[0].section_key == "s1"
    assert not hasattr(draft.sections[0], "original_text")


# ---------- 旧格式兜底：不得接受抄写原文，必须显式失败进修复循环 ----------

def test_legacy_output_without_span_rejected_by_schema():
    # 旧格式（originalText 直传、无 originalSpan）：即使 originalText 恰为原文
    # 严格子串（逐字正确），schema 也拒绝（originalSpan 必填）——不存在旧格式兜底通道
    from pydantic import ValidationError as PydanticValidationError

    legacy = {
        "title": "t",
        "sections": [{
            "sectionKey": "s1", "title": "x",
            "originalText": "第一行内容",  # 恰为原文第一行（逐字正确）
            "suggestedText": "y",
            "sourceQuotes": ["第一行内容"],
        }],
    }
    with pytest.raises(PydanticValidationError, match="originalSpan"):
        AIResumeOptimizeDraft.model_validate(legacy)


@pytest.mark.asyncio
async def test_execute_with_legacy_only_output_fails_after_repair():
    # 模型恒返回旧格式（无 originalSpan）：execute 两轮尝试（含修复重试）后
    # 以 MODEL_OUTPUT_INVALID 显式失败——不得放宽校验让任务“通过”
    from types import SimpleNamespace

    text = "第一行内容\n第二行内容"
    ctx = make_ctx(text)
    legacy_json = (
        '{"title":"t","sections":[{"sectionKey":"s1","title":"x",'
        '"originalText":"第一行内容","suggestedText":"y","sourceQuotes":["第一行内容"]}]}'
    )

    async def complete(request):
        return SimpleNamespace(model="fake", text=legacy_json, latency_ms=1,
                               input_tokens=1, output_tokens=1)

    async def noop_stage(*a, **k):
        return None

    ctx = SimpleNamespace(payload=ctx.payload, llm=SimpleNamespace(complete=complete),
                          files=ctx.files, emit_stage=noop_stage,
                          emit_reasoning=None, settings=None)
    from app.errors import HandlerError

    with pytest.raises(HandlerError) as exc_info:
        await ResumeOptimizeHandler().execute(ctx)
    assert exc_info.value.code == "MODEL_OUTPUT_INVALID"
    # 修复错误信息必须含缺失字段位置（可执行提示，而非让模型重复猜原文）
    assert "originalSpan" in exc_info.value.message

# ---------- 行号边界补充 ----------

@pytest.mark.asyncio
async def test_boundary_span_covers_whole_document():
    # 边界：1..len(lines) 全范围切取 = 整份原文（首尾行同时是边界）
    text = "第一行内容\n第二行内容\n第三行内容"
    handler = ResumeOptimizeHandler()
    prepared = await handler.prepare(make_ctx(text))
    draft = make_draft([make_section("all", 1, 3)])
    result = handler.validate_business(None, draft, prepared, {})
    assert result.sections[0].original_text == text


@pytest.mark.asyncio
async def test_boundary_last_line_span():
    # 边界：最后一行（end == len(lines)）
    text = "第一行\n第二行\n最后一行内容"
    handler = ResumeOptimizeHandler()
    prepared = await handler.prepare(make_ctx(text))
    draft = make_draft([make_section("last", 3, 3)])
    result = handler.validate_business(None, draft, prepared, {})
    assert result.sections[0].original_text == "最后一行内容"


@pytest.mark.asyncio
async def test_boundary_span_includes_internal_blank_lines():
    # 边界：区间内部含空行——空行按原文保留（不修剪、不拼接）
    text = "开头行\n\n中间行\n\n结尾行"
    handler = ResumeOptimizeHandler()
    prepared = await handler.prepare(make_ctx(text))
    draft = make_draft([make_section("with-blanks", 1, 5)])
    result = handler.validate_business(None, draft, prepared, {})
    assert result.sections[0].original_text == text


@pytest.mark.asyncio
async def test_boundary_one_past_last_line_rejected():
    # 边界：end = len(lines)+1 必须拒绝（越界 1 行）
    text = "第一行\n第二行"
    handler = ResumeOptimizeHandler()
    prepared = await handler.prepare(make_ctx(text))
    draft = make_draft([make_section("over", 1, 3)])
    with pytest.raises(HandlerValidationError, match="未知行号.*1..2"):
        handler.validate_business(None, draft, prepared, {})


@pytest.mark.asyncio
async def test_boundary_duplicate_source_spans_allowed():
    # 重复片段：同一 sourceSpan 引用两次是合法的（模型可多次引用同一证据行）
    text = "技能：熟悉 Java 后端开发\n经历：参与接口开发"
    handler = ResumeOptimizeHandler()
    prepared = await handler.prepare(make_ctx(text))
    draft = make_draft([make_section("dup-quotes", 1, 1, sources=[(1, 1), (1, 1)])])
    result = handler.validate_business(None, draft, prepared, {})
    assert result.sections[0].source_quotes == ["技能：熟悉 Java 后端开发", "技能：熟悉 Java 后端开发"]