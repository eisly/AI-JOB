from __future__ import annotations

from types import SimpleNamespace
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.errors import ApiError, HandlerValidationError
from app.fake_model import fake_llm_from_payload
from app.handlers import (
    ProjectPlanHandler,
    ResumeDiagnoseHandler,
    ResumeOptimizeHandler,
)
from app.llm import LLMRequest
from app.operations import (
    INTERVIEW_TURN,
    PROJECT_PLAN,
    PROJECT_RECOMMEND,
    RESUME_DIAGNOSE,
    RESUME_OPTIMIZE,
    validate_version_binding,
)
from app.schemas import (
    AIProjectPlanResult,
    AIProjectRecommendResult,
    AIResumeDiagnoseResult,
    AIResumeOptimizeResult,
    InterviewTurnPayload,
    ProjectPlanMilestone,
    ProjectPlanPayload,
    ProjectPlanTask,
    ProjectRecommendPayload,
    ResumeDiagnosePayload,
    ResumeDiagnosisItem,
    ResumeOptimizePayload,
    ResumeOptimizeSection,
)
from app.skills import get_skill
from app.skills.tools import validate_result_contract
from tests.factories import (
    RESUME_TEXT,
    interview_turn_first_envelope,
    project_plan_envelope,
    project_recommend_envelope,
    resume_diagnose_envelope,
    resume_optimize_envelope,
)


def _ctx(payload):
    return SimpleNamespace(payload=payload)


def _diagnosis_output(quote="使用Redis实现缓存", assessment=True, extra_quote="参与Java接口开发"):
    from app.schemas import ResumeAssessment, ResumeAssessmentDimension, ResumeAssessmentStrength

    return AIResumeDiagnoseResult(
        items=[
            ResumeDiagnosisItem(
                priority="SUGGESTION",
                title="补充结果",
                issue="结果不明确",
                quote=quote,
                quotes=[extra_quote],
                rationale="原文仅描述做法",
                impact="招聘方难以判断实际贡献",
                suggestion="只补充可核实结果",
                example=None,
                pending_verification=["实际结果与量化指标"],
            )
        ],
        assessment=ResumeAssessment(
            rule_version="resume-quality-v2",
            dimensions=[
                ResumeAssessmentDimension(
                    code="EXPERIENCE_PROJECTS",
                    score=18,
                    rationale="经历描述了职责，但成果不够明确。",
                    quote=quote,
                    quotes=[extra_quote],
                ),
                ResumeAssessmentDimension(
                    code="SKILLS_EVIDENCE",
                    score=12,
                    rationale="技能与经历可以对应。",
                    quote=quote,
                ),
                ResumeAssessmentDimension(
                    code="CONTENT_COMPLETENESS",
                    score=14,
                    rationale="教育、经历、技能信息基本齐全。",
                    quote=quote,
                ),
                ResumeAssessmentDimension(
                    code="STRUCTURE_CLARITY",
                    score=0,
                    rationale="原文缺少可辨认的章节与时间线，无法评估结构。",
                    quote=None,
                ),
                ResumeAssessmentDimension(
                    code="EXPRESSION_PROFESSIONALISM",
                    score=9,
                    rationale="表述总体具体。",
                    quote=quote,
                ),
            ],
            strengths=[
                ResumeAssessmentStrength(
                    title="技能与经历可以相互印证",
                    quote=quote,
                    quotes=[extra_quote],
                    description="技能描述在实习经历中有对应实现，属于可核对的优势。",
                    approach="在实习中使用 Redis 实现缓存",
                    contribution="参与接口开发并完成缓存实现",
                    result="原文未给出可核实结果",
                )
            ],
        )
        if assessment
        else None,
    )


def _optimize_prepared(text: str = RESUME_TEXT) -> dict:
    """按 ResumeOptimizeHandler.prepare 的行级片段契约构造 prepared（与生产同构）。"""
    source = text.replace("\r\n", "\n").replace("\r", "\n").strip()
    lines = source.split("\n")
    offsets: list[int] = []
    pos = 0
    for line in lines:
        offsets.append(pos)
        pos += len(line) + 1
    marked = "\n".join(f"[L{index + 1}] {line}" for index, line in enumerate(lines))
    return {
        "document_text": marked,
        "document_source_text": source,
        "document_lines": lines,
        "document_line_offsets": offsets,
    }


def _optimize_output():
    # 行号引用草稿（resume-optimize-v2）：RESUME_TEXT 第 2 行为实习经历行
    from app.schemas import LineSpan, ResumeOptimizeSectionDraft

    from app.schemas import AIResumeOptimizeDraft

    return AIResumeOptimizeDraft(
        title="候选优化",
        sections=[
            ResumeOptimizeSectionDraft(
                sectionKey="experience",
                title="经历",
                originalSpan=LineSpan(start=2, end=2),
                suggestedText="在保留原有事实的基础上补充本人可核实的成果说明。",
                sourceSpans=[LineSpan(start=2, end=2)],
            )
        ],
    )


def _plan_output():
    return AIProjectPlanResult(
        title="Redis 项目计划",
        summary="按已选项目拆分为两个阶段",
        milestones=[
            ProjectPlanMilestone(
                seq=1,
                title="核心实现",
                description="完成核心功能并验证",
                tasks=[
                    ProjectPlanTask(
                        title="实现缓存场景",
                        description="实现并记录自测结果",
                        estimatedHours=8,
                    )
                ],
            )
        ],
    )


def test_resume_diagnose_requires_inline_text_and_valid_quote():
    body = resume_diagnose_envelope(uuid4())
    body["payload"]["document"]["text"] = None
    body["payload"]["document"]["downloadUrl"] = "https://files.example.invalid/cv.txt"
    with pytest.raises(ValidationError, match="授权原文"):
        ResumeDiagnosePayload.model_validate(body["payload"])

    payload = ResumeDiagnosePayload.model_validate(resume_diagnose_envelope(uuid4())["payload"])
    result = ResumeDiagnoseHandler().validate_business(
        _ctx(payload), _diagnosis_output(), {"document_text": RESUME_TEXT}, {}
    )
    assert result.items[0].quote == "使用Redis实现缓存"
    assert result.items[0].quotes == ["参与Java接口开发"]
    assert result.assessment is not None
    assert result.assessment.rule_version == "resume-quality-v2"
    assert [d.score for d in result.assessment.dimensions] == [18, 12, 14, 0, 9]
    assert result.assessment.dimensions[0].quotes == ["参与Java接口开发"]
    assert result.assessment.dimensions[3].quote is None
    assert result.assessment.strengths[0].approach == "在实习中使用 Redis 实现缓存"
    assert result.assessment.strengths[0].contribution == "参与接口开发并完成缓存实现"
    assert result.assessment.strengths[0].result == "原文未给出可核实结果"
    with pytest.raises(HandlerValidationError, match="quote 必须在简历授权原文中"):
        ResumeDiagnoseHandler().validate_business(
            _ctx(payload), _diagnosis_output("原文不存在"), {"document_text": RESUME_TEXT}, {}
        )
    with pytest.raises(HandlerValidationError, match="quote 必须在简历授权原文中"):
        ResumeDiagnoseHandler().validate_business(
            _ctx(payload),
            _diagnosis_output(extra_quote="原文不存在的补充引用"),
            {"document_text": RESUME_TEXT},
            {},
        )


def test_resume_diagnose_assessment_validation_rules():
    from app.schemas import ResumeAssessment, ResumeAssessmentDimension, ResumeAssessmentStrength

    payload = ResumeDiagnosePayload.model_validate(resume_diagnose_envelope(uuid4())["payload"])
    handler = ResumeDiagnoseHandler()

    with pytest.raises(HandlerValidationError, match="缺少评分 assessment"):
        handler.validate_business(_ctx(payload), _diagnosis_output(assessment=False), {}, {})

    output = _diagnosis_output()
    output.assessment.dimensions = output.assessment.dimensions[:4]
    with pytest.raises(HandlerValidationError, match="评分维度缺失"):
        handler.validate_business(_ctx(payload), output, {}, {})

    output = _diagnosis_output()
    output.assessment.dimensions[0].score = 31
    with pytest.raises(HandlerValidationError, match="超过满分"):
        handler.validate_business(_ctx(payload), output, {}, {})

    output = _diagnosis_output()
    output.assessment.dimensions[1] = ResumeAssessmentDimension(
        code="EXPERIENCE_PROJECTS",
        score=10,
        rationale="重复维度",
        quote="使用Redis实现缓存",
    )
    with pytest.raises(HandlerValidationError, match="评分维度重复"):
        handler.validate_business(_ctx(payload), output, {}, {})

    output = _diagnosis_output()
    output.assessment.dimensions[3].score = 5
    output.assessment.dimensions[3].quote = None
    with pytest.raises(HandlerValidationError, match="正分必须提供简历原文依据"):
        handler.validate_business(_ctx(payload), output, {}, {})

    output = _diagnosis_output()
    output.assessment.strengths = [
        ResumeAssessmentStrength(title="伪造优势", quote="简历里不存在的句子", description="伪造描述")
    ]
    with pytest.raises(HandlerValidationError, match="quote 必须在简历授权原文中"):
        handler.validate_business(_ctx(payload), output, {}, {})


def test_resume_diagnose_v3_scores_rubric_with_empty_advice_and_rejects_invalid_levels():
    from app.schemas import AIResumeDiagnoseResult, RESUME_ASSESSMENT_DEFAULT_RULES

    body = resume_diagnose_envelope(uuid4())["payload"]
    body["document"]["text"] = "负责使用Redis实现缓存\n修复重复查询问题"
    codes = [
        ("problemContext", "personalRole", "approach", "outcomes"),
        ("skillSpecificity", "appliedEvidence"),
        ("background", "experienceFields", "context"),
        ("sections", "hierarchy", "timeline"),
        ("conciseness", "consistency"),
    ]
    rules = RESUME_ASSESSMENT_DEFAULT_RULES.model_dump(by_alias=True)
    rules["ruleVersion"] = "resume-quality-v3"
    for index, (dimension, names) in enumerate(zip(rules["dimensions"], codes)):
        weight = 10 if index < 2 else 5
        dimension["maxScore"] = len(names) * weight
        dimension["checks"] = [
            {"code": code, "label": code, "maxScore": weight} for code in names
        ]
    body["assessmentRules"] = rules
    payload = ResumeDiagnosePayload.model_validate(body)
    output = {
        "items": [],
        "assessment": {
            "ruleVersion": "resume-quality-v3",
            "summary": "描述了缓存改造的主要贡献。",
            "dimensions": [
                {
                    "code": dimension["code"],
                    "levels": {check["code"]: 3 for check in dimension["checks"]},
                    "rationale": "原文描述了职责与结果。",
                    "quote": "使用Redis实现缓存",
                } for dimension in rules["dimensions"]
            ],
        },
    }
    handler = ResumeDiagnoseHandler()
    result = handler.validate_business(_ctx(payload), AIResumeDiagnoseResult.model_validate(output), {}, {})
    assert not result.items
    assert [dimension.score for dimension in result.assessment.dimensions] == [24, 12, 9, 9, 6]
    levels = output["assessment"]["dimensions"][0]["levels"]
    del levels["problemContext"]
    with pytest.raises(HandlerValidationError, match="评分点缺失或未知"):
        handler.validate_business(_ctx(payload), AIResumeDiagnoseResult.model_validate(output), {}, {})
    levels["problemContext"] = 6
    with pytest.raises(ValidationError):
        AIResumeDiagnoseResult.model_validate(output)
    levels["problemContext"] = 3

    advice = _diagnosis_output(quote="负责使用Redis实现缓存", extra_quote="修复重复查询问题").items[0]
    advice.dimension_code = "EXPERIENCE_PROJECTS"
    advice.check_code = "outcomes"
    output["items"] = [advice]
    result = handler.validate_business(_ctx(payload), AIResumeDiagnoseResult.model_validate(output), {}, {})
    assert result.items[0].check_code == "outcomes"
    for code in (None, "timeline", "unknown"):
        advice.check_code = code
        with pytest.raises(HandlerValidationError, match="必须对应实际评分点"):
            handler.validate_business(_ctx(payload), AIResumeDiagnoseResult.model_validate(output), {}, {})
    advice.check_code = "outcomes"
    levels["outcomes"] = 5
    with pytest.raises(HandlerValidationError, match="不能关联满分项"):
        handler.validate_business(_ctx(payload), AIResumeDiagnoseResult.model_validate(output), {}, {})
    levels["outcomes"] = 3
    advice.quote = "不存在的引用"
    with pytest.raises(HandlerValidationError, match="quote 必须在简历授权原文中"):
        handler.validate_business(_ctx(payload), AIResumeDiagnoseResult.model_validate(output), {}, {})

    structure = output["assessment"]["dimensions"][3]
    for dates in ("2025-03 - 2025-06", "2028-07 - 2028-10"):
        body["document"]["text"] = f"硕士教育 2024-09 - 2028-06\n实习经历 {dates}\n负责使用Redis实现缓存"
        payload = ResumeDiagnosePayload.model_validate(body)
        structure["levels"]["timeline"] = 5
        output["items"] = []
        result = handler.validate_business(_ctx(payload), AIResumeDiagnoseResult.model_validate(output), {}, {})
        assert result.assessment.dimensions[3].score == 11
        structure["levels"]["timeline"] = 3
        with pytest.raises(HandlerValidationError, match="已核验的 timelineLevel=5"):
            handler.validate_business(_ctx(payload), AIResumeDiagnoseResult.model_validate(output), {}, {})
    structure["levels"]["timeline"] = 5
    structure["rationale"] = "实习时间与教育经历重叠，可能引起混乱。"
    with pytest.raises(HandlerValidationError, match="不能因跨条目同期"):
        handler.validate_business(_ctx(payload), AIResumeDiagnoseResult.model_validate(output), {}, {})
    structure["rationale"] = "日期清楚。"

    advice.quote = "负责使用Redis实现缓存"
    advice.quotes = []
    advice.title = "项目和实习时间线重叠"
    output["items"] = [advice]
    with pytest.raises(HandlerValidationError, match="不能作为简历问题"):
        handler.validate_business(_ctx(payload), AIResumeDiagnoseResult.model_validate(output), {}, {})

    body["document"]["text"] = "实习经历 2025-06 - 2025-03\n另一项目 2025-01 - 2025-02\n负责使用Redis实现缓存"
    payload = ResumeDiagnosePayload.model_validate(body)
    structure["levels"]["timeline"] = 2
    advice.dimension_code = "STRUCTURE_CLARITY"
    advice.check_code = "timeline"
    advice.title = "核对实习日期"
    advice.rationale = "结束日期早于开始日期。"
    advice.quote = "2025-01 - 2025-02"
    with pytest.raises(HandlerValidationError, match="确实倒置"):
        handler.validate_business(_ctx(payload), AIResumeDiagnoseResult.model_validate(output), {}, {})
    advice.quote = "2025-06 - 2025-03"
    assert handler.validate_business(
        _ctx(payload), AIResumeDiagnoseResult.model_validate(output), {}, {}
    ).items[0].check_code == "timeline"


def test_resume_diagnose_accepts_snapshot_assessment_rules():
    body = resume_diagnose_envelope(uuid4())["payload"]
    body["assessmentRules"] = {
        "ruleVersion": "resume-quality-v1",
        "dimensions": [
            {"code": "EXPERIENCE_PROJECTS", "maxScore": 30, "criteria": "经历与项目"},
            {"code": "SKILLS_EVIDENCE", "maxScore": 20, "criteria": "技能与证据"},
            {"code": "CONTENT_COMPLETENESS", "maxScore": 20, "criteria": "内容完整性"},
            {"code": "STRUCTURE_CLARITY", "maxScore": 15, "criteria": "结构清晰度"},
            {"code": "EXPRESSION_PROFESSIONALISM", "maxScore": 15, "criteria": "表达专业性"},
        ],
    }
    payload = ResumeDiagnosePayload.model_validate(body)
    result = ResumeDiagnoseHandler().validate_business(
        _ctx(payload), _diagnosis_output(), {"document_text": RESUME_TEXT}, {}
    )
    assert result.assessment.rule_version == "resume-quality-v1"

    duplicate = {
        "ruleVersion": "x",
        "dimensions": [
            {"code": "EXPERIENCE_PROJECTS", "maxScore": 30, "criteria": "经历与项目"},
            {"code": "SKILLS_EVIDENCE", "maxScore": 20, "criteria": "技能与证据"},
            {"code": "CONTENT_COMPLETENESS", "maxScore": 20, "criteria": "内容完整性"},
            {"code": "STRUCTURE_CLARITY", "maxScore": 15, "criteria": "结构清晰度"},
            {"code": "STRUCTURE_CLARITY", "maxScore": 15, "criteria": "重复"},
        ],
    }
    with pytest.raises(ValidationError, match="不得重复"):
        ResumeDiagnosePayload.model_validate({**body, "assessmentRules": duplicate})


async def test_resume_diagnose_disables_thinking_and_restores_original_quote():
    source = "项目描述：使用 Redis\r\n实现缓存。"
    body = resume_diagnose_envelope(uuid4())["payload"]
    body["document"]["text"] = source
    payload = ResumeDiagnosePayload.model_validate(body)
    response_data = _diagnosis_output("使用Redis实现缓存", extra_quote="项目描述").model_dump_json(by_alias=True)

    async def complete(request):
        assert request.thinking is False
        return SimpleNamespace(model="test", text=response_data, latency_ms=1, input_tokens=1, output_tokens=1)

    async def emit_stage(*args):
        pass

    ctx = SimpleNamespace(payload=payload, llm=SimpleNamespace(complete=complete),
                          emit_stage=emit_stage, emit_reasoning=None)
    result, _ = await ResumeDiagnoseHandler().execute(ctx, {"document_text": source.replace("\r\n", "\n")})

    assert result["items"][0]["quote"] == "使用 Redis\r\n实现缓存"


def test_resume_optimize_validates_references_and_unique_section_keys():
    payload = ResumeOptimizePayload.model_validate(resume_optimize_envelope(uuid4())["payload"])
    handler = ResumeOptimizeHandler()
    prepared = _optimize_prepared()
    result = handler.validate_business(_ctx(payload), _optimize_output(), prepared, {})
    assert result.sections[0].section_key == "experience"
    # 行号引用回填：originalText 为第 2 行原文的逐字切取（Java requireSource 严格子串）
    assert result.sections[0].original_text == "实习经历：使用Redis实现缓存，参与Java接口开发。"
    assert prepared["document_source_text"].find(result.sections[0].original_text) >= 0
    assert result.sections[0].source_quotes == ["实习经历：使用Redis实现缓存，参与Java接口开发。"]

    output = _optimize_output()
    output.sections.append(output.sections[0].model_copy(deep=True))
    with pytest.raises(HandlerValidationError, match="sectionKey 必须唯一"):
        handler.validate_business(_ctx(payload), output, prepared, {})

    output = _optimize_output()
    output.sections[0].original_span.end = 99
    with pytest.raises(HandlerValidationError, match="originalSpan 引用了未知行号"):
        handler.validate_business(_ctx(payload), output, prepared, {})

    output = _optimize_output()
    output.sections[0].source_spans[0].start = 0
    # 属性赋值绕过 pydantic 构造校验，行号非法由业务校验（_slice_span）拒绝
    with pytest.raises(HandlerValidationError, match="sourceSpans\\[0\\] 引用了未知行号"):
        handler.validate_business(_ctx(payload), output, prepared, {})


def test_project_plan_checks_sequence_and_hour_budget():
    payload = ProjectPlanPayload.model_validate(project_plan_envelope(uuid4())["payload"])
    handler = ProjectPlanHandler()
    assert handler.validate_business(_ctx(payload), _plan_output(), {}, {}).milestones[0].seq == 1

    output = _plan_output()
    output.milestones[0].seq = 2
    with pytest.raises(HandlerValidationError, match="连续编号"):
        handler.validate_business(_ctx(payload), output, {}, {})

    output = _plan_output()
    output.milestones[0].tasks[0].estimated_hours = 40
    with pytest.raises(HandlerValidationError, match="计划总工时"):
        handler.validate_business(_ctx(payload), output, {}, {})


def test_new_result_models_enforce_required_collections():
    with pytest.raises(ValidationError):
        AIResumeDiagnoseResult(items=[])
    with pytest.raises(ValidationError):
        AIResumeOptimizeResult(title="候选", sections=[])
    assert AIProjectRecommendResult(mode="REPOSITORY").items == []
    with pytest.raises(ValidationError):
        AIProjectPlanResult(title="计划", summary="摘要", milestones=[])


def test_fake_diagnosis_accepts_english_resume_and_preserves_quote():
    text = "Education: Bachelor of Computer Science. Experience: Java developer building REST services."
    request = LLMRequest(
        operation=RESUME_DIAGNOSE, system_prompt="", user_prompt="", schema_hint={},
        context={"document_text": text},
    )
    output = AIResumeDiagnoseResult.model_validate(fake_llm_from_payload(request))
    assert output.items[0].quote in text
    assert output.assessment is not None
    assert output.assessment.rule_version == "resume-quality-v2"
    assert output.items[0].impact
    assert output.items[0].pending_verification
    assert output.assessment.strengths[0].approach
    assert output.assessment.strengths[0].result


def test_resume_diagnose_falls_back_to_v2_rules_when_version_unsupported():
    body = resume_diagnose_envelope(uuid4())["payload"]
    body["assessmentRules"] = {
        "ruleVersion": "resume-quality-v9",
        "dimensions": [
            {"code": "EXPERIENCE_PROJECTS", "maxScore": 30, "criteria": "经历与项目"},
            {"code": "SKILLS_EVIDENCE", "maxScore": 20, "criteria": "技能与证据"},
            {"code": "CONTENT_COMPLETENESS", "maxScore": 20, "criteria": "内容完整性"},
            {"code": "STRUCTURE_CLARITY", "maxScore": 15, "criteria": "结构清晰度"},
            {"code": "EXPRESSION_PROFESSIONALISM", "maxScore": 15, "criteria": "表达专业性"},
        ],
    }
    payload = ResumeDiagnosePayload.model_validate(body)
    result = ResumeDiagnoseHandler().validate_business(
        _ctx(payload), _diagnosis_output(), {"document_text": RESUME_TEXT}, {}
    )
    assert result.assessment.rule_version == "resume-quality-v2"
    assert result.assessment.dimensions[0].quotes == ["参与Java接口开发"]
    assert result.items[0].impact == "招聘方难以判断实际贡献"
    assert result.items[0].pending_verification == ["实际结果与量化指标"]


def test_new_operation_version_bindings_and_interview_expected_version():
    body = resume_diagnose_envelope(uuid4())
    payload = ResumeDiagnosePayload.model_validate(body["payload"])
    validate_version_binding(RESUME_DIAGNOSE, body["inputVersions"], payload)
    with pytest.raises(ApiError, match="profileVersion"):
        validate_version_binding(
            RESUME_DIAGNOSE,
            {**body["inputVersions"], "profileVersion": 9},
            payload,
        )

    body = resume_optimize_envelope(uuid4())
    payload = ResumeOptimizePayload.model_validate(body["payload"])
    validate_version_binding(RESUME_OPTIMIZE, body["inputVersions"], payload)
    with pytest.raises(ApiError, match="jobVersion"):
        validate_version_binding(
            RESUME_OPTIMIZE,
            {**body["inputVersions"], "jobVersion": 2},
            payload,
        )

    body = project_recommend_envelope(uuid4())
    payload = ProjectRecommendPayload.model_validate(body["payload"])
    validate_version_binding(PROJECT_RECOMMEND, body["inputVersions"], payload)
    with pytest.raises(ApiError, match="profileVersion"):
        validate_version_binding(
            PROJECT_RECOMMEND,
            {**body["inputVersions"], "profileVersion": 9},
            payload,
        )

    body = project_plan_envelope(uuid4())
    payload = ProjectPlanPayload.model_validate(body["payload"])
    validate_version_binding(PROJECT_PLAN, body["inputVersions"], payload)
    with pytest.raises(ApiError, match="profileVersion"):
        validate_version_binding(PROJECT_PLAN, {}, payload)

    body = interview_turn_first_envelope(uuid4())
    payload = InterviewTurnPayload.model_validate(body["payload"])
    with pytest.raises(ApiError, match="interviewVersion.*expectedVersion"):
        validate_version_binding(
            INTERVIEW_TURN,
            {**body["inputVersions"], "interviewVersion": 2},
            payload,
        )


@pytest.mark.parametrize(
    "operation,factory,rule",
    [
        (RESUME_DIAGNOSE, resume_diagnose_envelope, "不得编造诊断项"),
        (RESUME_OPTIMIZE, resume_optimize_envelope, "只能改写用户已经提供"),
        (PROJECT_PLAN, project_plan_envelope, "不输出 status、progress"),
    ],
)
def test_new_prompts_load_and_fake_outputs_share_result_contract(operation, factory, rule):
    skill = get_skill(operation)
    assert skill.prompt_file.is_file()
    assert rule in skill.system_prompt
    body = factory(uuid4())
    request = LLMRequest(
        operation=operation,
        system_prompt=skill.system_prompt,
        user_prompt="",
        schema_hint=skill.schema_hint,
        context={"payload": body["payload"], "document_text": RESUME_TEXT},
    )
    first = fake_llm_from_payload(request)
    second = fake_llm_from_payload(request)
    assert first == second
    if operation == RESUME_OPTIMIZE:
        # v2 行号引用：fake 输出为模型原始草稿，与 handler.output_model（Draft）共享契约；
        # 对外 AIResumeOptimizeResult 由 validate_business 回填后产生（Java 端契约不变）
        from app.schemas import AIResumeOptimizeDraft

        validated = AIResumeOptimizeDraft.model_validate(first).model_dump(by_alias=True)
    else:
        validated = validate_result_contract(operation, first)
    assert isinstance(validated, dict)
