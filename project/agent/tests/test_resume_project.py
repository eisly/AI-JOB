from __future__ import annotations

import asyncio
from types import SimpleNamespace
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.config import Settings
from app.errors import ApiError, HandlerValidationError
from app.fake_model import fake_llm_from_payload
from app.handlers import (
    ExecutionContext,
    ProjectPlanHandler,
    ProjectRecommendHandler,
    ResumeDiagnoseHandler,
    ResumeOptimizeHandler,
)
from app.graphs import CareerOrchestrator
from app.llm import LLMProviderError, LLMRequest
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
    ProjectRecommendationItem,
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


def _diagnosis_output(quote="使用Redis实现缓存"):
    return AIResumeDiagnoseResult(
        items=[
            ResumeDiagnosisItem(
                priority="SUGGESTION",
                title="补充结果",
                issue="结果不明确",
                quote=quote,
                rationale="原文仅描述做法",
                suggestion="只补充可核实结果",
            )
        ]
    )


def _optimize_output():
    return AIResumeOptimizeResult(
        title="候选优化",
        sections=[
            ResumeOptimizeSection(
                sectionKey="experience",
                title="经历",
                originalText="使用Redis实现缓存",
                suggestedText="使用Redis实现缓存。",
                sourceQuotes=["参与Java接口开发"],
            )
        ],
    )


def _recommend_output():
    return AIProjectRecommendResult(
        items=[
            ProjectRecommendationItem(
                title="Redis 实战",
                summary="完成可自测的学习项目",
                skillCodes=["redis"],
                difficulty="BEGINNER",
                estimatedWeeks=2,
                reason="依据技能差距生成学习建议",
                deliverables=["源码与自测记录"],
            )
        ]
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
    with pytest.raises(HandlerValidationError, match="quote 必须在简历授权原文中"):
        ResumeDiagnoseHandler().validate_business(
            _ctx(payload), _diagnosis_output("原文不存在"), {"document_text": RESUME_TEXT}, {}
        )


def test_resume_optimize_validates_references_and_unique_section_keys():
    payload = ResumeOptimizePayload.model_validate(resume_optimize_envelope(uuid4())["payload"])
    handler = ResumeOptimizeHandler()
    result = handler.validate_business(
        _ctx(payload), _optimize_output(), {"document_text": RESUME_TEXT}, {}
    )
    assert result.sections[0].section_key == "experience"

    output = _optimize_output()
    output.sections.append(output.sections[0].model_copy(deep=True))
    with pytest.raises(HandlerValidationError, match="sectionKey 必须唯一"):
        handler.validate_business(_ctx(payload), output, {"document_text": RESUME_TEXT}, {})

    output = _optimize_output()
    output.sections[0].original_text = "虚构项目经历"
    with pytest.raises(HandlerValidationError, match="originalText 必须来自"):
        handler.validate_business(_ctx(payload), output, {"document_text": RESUME_TEXT}, {})

    output = _optimize_output()
    output.sections[0].source_quotes = ["虚构技能"]
    with pytest.raises(HandlerValidationError, match="sourceQuotes 必须来自"):
        handler.validate_business(_ctx(payload), output, {"document_text": RESUME_TEXT}, {})


def test_project_recommend_checks_skill_difficulty_and_duration():
    payload = ProjectRecommendPayload.model_validate(project_recommend_envelope(uuid4())["payload"])
    handler = ProjectRecommendHandler()
    assert handler.validate_business(_ctx(payload), _recommend_output(), {}, {}).items[0].estimated_weeks == 2

    output = _recommend_output()
    output.items[0].skill_codes = ["python"]
    with pytest.raises(HandlerValidationError, match="allowedSkillCodes"):
        handler.validate_business(_ctx(payload), output, {}, {})

    output = _recommend_output()
    output.items[0].estimated_weeks = 5
    with pytest.raises(HandlerValidationError, match="maxDurationWeeks"):
        handler.validate_business(_ctx(payload), output, {}, {})

    output = _recommend_output()
    output.items[0].difficulty = "ADVANCED"
    with pytest.raises(HandlerValidationError, match="difficulty"):
        handler.validate_business(_ctx(payload), output, {}, {})


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


@pytest.mark.asyncio
async def test_project_recommend_and_plan_degrade_through_result_contract():
    stages = []

    class BlockedProvider:
        name = "deepseek"
        calls = 0

        async def complete(self, request):
            self.calls += 1
            assert request.thinking is False
            raise LLMProviderError("model rejected request", retryable=False, http_status=402)

    async def emit(name, progress):
        stages.append(name)

    provider = BlockedProvider()
    recommend = project_recommend_envelope(uuid4())["payload"]
    recommend.update(allowedSkillCodes=["JAVA"], difficulty="ADVANCED", maxDurationWeeks=1)
    orchestrator = CareerOrchestrator()
    settings = Settings(llm_provider="fake")
    result, meta = await orchestrator.execute(ExecutionContext(
        SimpleNamespace(operation=PROJECT_RECOMMEND), ProjectRecommendPayload.model_validate(recommend),
        settings, provider, None, emit,
    ))
    item = result["items"][0]
    assert item["title"] == "Java 投递进度看板"
    assert (item["skillCodes"], item["difficulty"], item["estimatedWeeks"]) == (["JAVA"], "ADVANCED", 1)
    assert item["reason"].startswith("本地规则建议")
    assert (meta["fallbackReason"], meta["resultValidated"], provider.calls) == ("MODEL_HTTP_402", True, 1)
    result, meta = await orchestrator.execute(ExecutionContext(
        SimpleNamespace(operation=PROJECT_PLAN),
        ProjectPlanPayload.model_validate({"recommendation": item, "weeklyHours": 1}),
        settings, provider, None, emit,
    ))
    assert result["summary"].startswith("本地规则计划")
    assert result["milestones"][0]["tasks"][0]["estimatedHours"] == 1
    assert len(result["milestones"]) == 1
    assert (meta["fallbackReason"], meta["resultValidated"], provider.calls) == ("MODEL_HTTP_402", True, 2)
    assert stages.count("using_local_project_rules") == 2


@pytest.mark.asyncio
async def test_project_model_deadline_returns_local_recommendation():
    class SlowProvider:
        async def complete(self, request):
            await asyncio.sleep(10)

    async def emit(*_):
        pass

    handler = ProjectRecommendHandler()
    handler.model_deadline_seconds = 0.05
    payload = ProjectRecommendPayload.model_validate(project_recommend_envelope(uuid4())["payload"])
    result, meta = await handler.execute(ExecutionContext(
        None, payload, Settings(llm_provider="fake"), SlowProvider(), None, emit,
    ))
    assert result["items"]
    assert meta["fallbackReason"] == "MODEL_TIMEOUT"


def test_new_result_models_enforce_required_collections():
    with pytest.raises(ValidationError):
        AIResumeDiagnoseResult(items=[])
    with pytest.raises(ValidationError):
        AIResumeOptimizeResult(title="候选", sections=[])
    with pytest.raises(ValidationError):
        AIProjectRecommendResult(items=[])
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
        (PROJECT_RECOMMEND, project_recommend_envelope, "不得声称用户已经做过"),
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
    validated = validate_result_contract(operation, first)
    assert isinstance(validated, dict)
