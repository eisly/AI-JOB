from types import SimpleNamespace
from uuid import uuid4

import pytest

from app.config import Settings
from app.errors import HandlerError
from app.fake_model import FakeLLM
from app.files import FileFetcher
from app.handlers import ExecutionContext, LearningQuestionHandler, norm_text
from app.schemas import LearningQuestionPayload
from tests.factories import question_envelope
from tests.factories import question_candidate


def test_question_keeps_specific_plan_goal_in_model_input():
    body = question_envelope(uuid4(), candidates=[])
    body["payload"]["planItem"].update({
        "title": "接口并发控制", "objective": "修复重复提交",
        "completionCriteria": "说明幂等键和并发测试", "reason": "岗位要求并发经验",
    })
    payload = LearningQuestionPayload.model_validate(body["payload"])
    assert payload.model_dump(by_alias=True)["planItem"]["objective"] == "修复重复提交"


@pytest.mark.asyncio
async def test_fake_practice_handles_normalized_history_and_exhaustion():
    settings = Settings(app_env="test", llm_provider="fake", callback_enabled=False)
    history = []

    async def emit_stage(name, progress):
        pass

    for attempt in range(7):
        body = question_envelope(uuid4(), candidates=[])
        body["payload"]["excludedQuestionStems"] = history.copy()
        ctx = ExecutionContext(
            run=SimpleNamespace(operation="LEARNING_QUESTION"),
            payload=LearningQuestionPayload.model_validate(body["payload"]),
            settings=settings, llm=FakeLLM(), files=FileFetcher(settings), emit_stage=emit_stage,
        )
        if attempt == 6:
            with pytest.raises(HandlerError) as caught:
                await LearningQuestionHandler().execute(ctx)
            assert not caught.value.retryable
            assert "复习" in str(caught.value)
        else:
            result, _ = await LearningQuestionHandler().execute(ctx)
            stem = norm_text(result["stem"])
            assert stem not in history
            history.append(stem)


@pytest.mark.asyncio
async def test_specific_goal_does_not_silently_reuse_a_generic_skill_question():
    settings = Settings(app_env="test", llm_provider="fake", callback_enabled=False)
    body = question_envelope(uuid4(), candidates=[question_candidate()])
    body["payload"]["planItem"]["objective"] = "缓存集群故障恢复演练"
    llm = FakeLLM()

    async def emit_stage(name, progress):
        pass

    ctx = ExecutionContext(run=SimpleNamespace(operation="LEARNING_QUESTION"),
                           payload=LearningQuestionPayload.model_validate(body["payload"]),
                           settings=settings, llm=llm, files=FileFetcher(settings), emit_stage=emit_stage)
    result, _ = await LearningQuestionHandler().execute(ctx)
    assert result["sourceType"] == "GENERATED"
    assert len(llm.calls) == 1
    assert llm.calls[0].context["payload"]["planItem"]["objective"] == "缓存集群故障恢复演练"
