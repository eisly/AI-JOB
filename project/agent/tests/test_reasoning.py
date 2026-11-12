from __future__ import annotations

from types import SimpleNamespace
from uuid import uuid4

import pytest

from app.config import Settings
from app.handlers import ExecutionContext, LearningPlanHandler
from app.llm import DeepSeekClient, LLMRequest, LLMResponse
from app.schemas import LearningPlanPayload
from tests.factories import plan_envelope


class ReasoningLLM:
    name = "reasoning-llm"

    def __init__(self, text: str, reasoning: str) -> None:
        self._text = text
        self._reasoning = reasoning

    async def complete(self, request: LLMRequest) -> LLMResponse:
        return LLMResponse(
            text=self._text,
            model="deepseek-v4-pro",
            prompt_version="",
            reasoning=self._reasoning,
        )


PLAN_JSON = (
    '{"title":"t","summary":"s","items":[{"skillCode":"redis","title":"x","kind":"PRACTICE",'
    '"category":"BASIC","objective":"o","reason":"r","method":"m","completionCriteria":"c",'
    '"evidenceStatus":"ASSESSED","estimatedMinutes":20,"resourceRefs":[]}]}'
)


def _plan_context(settings: Settings, llm, captured: list[str]) -> ExecutionContext:
    async def emit_stage(name, progress=None):
        return None

    async def emit_reasoning(text: str):
        captured.append(text)

    return ExecutionContext(
        run=None,
        payload=LearningPlanPayload.model_validate(plan_envelope(uuid4())["payload"]),
        settings=settings,
        llm=llm,
        files=None,
        emit_stage=emit_stage,
        emit_reasoning=emit_reasoning,
    )


@pytest.mark.asyncio
async def test_plan_handler_emits_truncated_reasoning():
    settings = Settings(llm_provider="fake")
    captured: list[str] = []
    ctx = _plan_context(settings, ReasoningLLM(PLAN_JSON, "思考过程" * 4000), captured)

    await LearningPlanHandler().execute(ctx)

    assert captured
    assert len(captured[0]) == settings.reasoning_max_chars


@pytest.mark.asyncio
async def test_plan_handler_skips_reasoning_when_disabled():
    settings = Settings(llm_provider="fake", reasoning_display_enabled=False)
    captured: list[str] = []
    ctx = _plan_context(settings, ReasoningLLM(PLAN_JSON, "思考过程"), captured)

    await LearningPlanHandler().execute(ctx)

    assert captured == []


@pytest.mark.asyncio
async def test_deepseek_complete_extracts_reasoning(monkeypatch):
    client = DeepSeekClient(Settings(llm_provider="deepseek", deepseek_api_key="sk-test"))

    async def fake_create(**kwargs):
        message = SimpleNamespace(content='{"ok": true}', reasoning_content="第一步：理解问题")
        return SimpleNamespace(choices=[SimpleNamespace(message=message)], model="deepseek-v4-pro", usage=None)

    monkeypatch.setattr(client._client.chat.completions, "create", fake_create)
    response = await client.complete(
        LLMRequest(operation="SMOKE", system_prompt="sys", user_prompt="user", schema_hint="{}")
    )

    assert response.reasoning == "第一步：理解问题"
    assert response.model == "deepseek-v4-pro"