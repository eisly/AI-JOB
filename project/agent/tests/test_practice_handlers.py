from __future__ import annotations

from types import SimpleNamespace
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.config import Settings
from app.fake_model import FakeLLM
from app.files import FileFetcher
from app.handlers import (
    ExecutionContext,
    HandlerValidationError,
    LearningPracticeEvaluateHandler,
    LearningPracticeQuestionsHandler,
)
from app.schemas import (
    AIPracticeEvaluateResult,
    AIPracticeQuestionSet,
    LearningPracticeEvaluatePayload,
    LearningPracticeQuestionsPayload,
)

RUBRIC = {
    "version": "technical-answer-v1",
    "dimensions": [
        {"key": "correctness", "weight": 0.5},
        {"key": "completeness", "weight": 0.3},
        {"key": "expression", "weight": 0.2},
    ],
}


def practice_questions_payload(choice: int = 1, fill: int = 1, subjective: int = 2):
    return LearningPracticeQuestionsPayload.model_validate(
        {
            "sessionId": str(uuid4()),
            "planItem": {"skillCode": "redis", "difficulty": "MEDIUM", "objective": "掌握缓存穿透处理"},
            "config": {
                "totalQuestions": choice + fill + subjective,
                "typeDistribution": {"choice": choice, "fill": fill, "subjective": subjective},
                "difficulty": "MEDIUM",
                "direction": "JAVA_BACKEND",
            },
            "excludedQuestionStems": [],
            "knowledgeRefs": [],
        }
    )


def practice_evaluate_payload(count: int = 2):
    answers = [
        {
            "answerId": str(uuid4()),
            "questionId": str(uuid4()),
            "questionVersion": 1,
            "stem": f"请说明 redis 场景设计 {index}",
            "skillCodes": ["redis"],
            "referenceAnswer": "说明缓存穿透的布隆过滤器与空值缓存方案",
            "rubric": RUBRIC,
            "answer": "我会先用布隆过滤器拦截不存在的 key，再对空结果做短 TTL 缓存，并用压测验证命中率。",
        }
        for index in range(count)
    ]
    return LearningPracticeEvaluatePayload.model_validate(
        {"sessionId": str(uuid4()), "answers": answers}
    )


def context(payload, operation: str) -> ExecutionContext:
    settings = Settings(app_env="test", llm_provider="fake", callback_enabled=False)

    async def emit_stage(name, progress):
        return None

    return ExecutionContext(
        run=SimpleNamespace(operation=operation),
        payload=payload,
        settings=settings,
        llm=FakeLLM(),
        files=FileFetcher(settings),
        emit_stage=emit_stage,
    )


def test_practice_config_requires_distribution_sum():
    # 显式构造不一致的分布
    with pytest.raises(ValidationError):
        LearningPracticeQuestionsPayload.model_validate(
            {
                "sessionId": str(uuid4()),
                "planItem": {"skillCode": "redis"},
                "config": {
                    "totalQuestions": 5,
                    "typeDistribution": {"choice": 1, "fill": 1, "subjective": 1},
                },
            }
        )


async def test_practice_questions_generate_mixed_types():
    payload = practice_questions_payload(choice=1, fill=1, subjective=2)
    result, meta = await LearningPracticeQuestionsHandler().execute(context(payload, "LEARNING_PRACTICE_QUESTIONS"))
    validated = AIPracticeQuestionSet.model_validate(result)
    assert len(validated.questions) == 4
    types = [item.type for item in validated.questions]
    assert types.count("CHOICE") == 1
    assert types.count("FILL") == 1
    assert types.count("SUBJECTIVE") == 2
    choice = next(item for item in validated.questions if item.type == "CHOICE")
    assert len(choice.options) >= 2
    assert choice.answer_key and choice.answer_key[0] in [option.key for option in choice.options]
    fill = next(item for item in validated.questions if item.type == "FILL")
    assert fill.blank_count == len(fill.answer_key)
    assert meta["operation"] == "LEARNING_PRACTICE_QUESTIONS"


async def test_practice_questions_reject_wrong_distribution():
    payload = practice_questions_payload(choice=1, fill=1, subjective=1)
    handler = LearningPracticeQuestionsHandler()
    output = AIPracticeQuestionSet.model_validate(
        {
            "questions": [
                {
                    "stem": "关于 redis 的缓存穿透场景说明（第 1 题）",
                    "type": "SUBJECTIVE",
                    "skillCodes": ["redis"],
                    "difficulty": "MEDIUM",
                    "options": [],
                    "answerKey": [],
                    "blankCount": 0,
                    "score": 10,
                    "referenceAnswer": "布隆过滤器 + 空值缓存",
                    "rubric": RUBRIC,
                }
            ]
        }
    )
    with pytest.raises(HandlerValidationError):
        handler.validate_business(context(payload, "LEARNING_PRACTICE_QUESTIONS"), output, {}, {})


async def test_practice_evaluate_returns_one_evaluation_per_answer():
    payload = practice_evaluate_payload(count=2)
    result, meta = await LearningPracticeEvaluateHandler().execute(context(payload, "LEARNING_PRACTICE_EVALUATE"))
    validated = AIPracticeEvaluateResult.model_validate(result)
    assert len(validated.evaluations) == 2
    sent = {str(item.answer_id) for item in payload.answers}
    got = {str(item.answer_id) for item in validated.evaluations}
    assert got == sent
    assert validated.report.summary
    assert meta["operation"] == "LEARNING_PRACTICE_EVALUATE"
