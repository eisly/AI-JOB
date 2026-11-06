from __future__ import annotations

import json
from types import SimpleNamespace

import pytest

from app.config import Settings
from app.fake_model import FakeLLM
from app.llm import (
    DeepSeekClient,
    LLMProviderError,
    LLMRequest,
    create_llm,
    parse_json_object,
)


def _settings(**overrides) -> Settings:
    base = dict(app_env="test", llm_provider="fake", callback_enabled=False)
    base.update(overrides)
    return Settings(**base)


def test_provider_error_carries_message_and_retryable_flag():
    error = LLMProviderError("DEEPSEEK_API_KEY 未配置", retryable=False)
    assert error.message == "DEEPSEEK_API_KEY 未配置"
    assert error.retryable is False
    assert str(error) == "DEEPSEEK_API_KEY 未配置"
    assert LLMProviderError("x").retryable is True


def test_parse_json_object_accepts_plain_json():
    assert parse_json_object(' {"a": 1} ') == {"a": 1}


def test_parse_json_object_strips_bom_and_fence():
    text = '\ufeff```json\n{"a": 1, "b": [2]}\n```'
    assert parse_json_object(text) == {"a": 1, "b": [2]}


def test_parse_json_object_extracts_embedded_object():
    text = '前缀说明 {"a": 1} 后缀说明'
    assert parse_json_object(text) == {"a": 1}


def test_parse_json_object_rejects_invalid_json():
    with pytest.raises(ValueError, match="模型输出不是合法 JSON"):
        parse_json_object("这不是 JSON")


def test_parse_json_object_rejects_non_object():
    with pytest.raises(ValueError, match="模型输出必须是 JSON 对象"):
        parse_json_object("[1, 2]")


def test_deepseek_client_requires_api_key():
    with pytest.raises(LLMProviderError) as caught:
        DeepSeekClient(_settings(llm_provider="deepseek", deepseek_api_key=None))
    assert "DEEPSEEK_API_KEY" in caught.value.message
    assert caught.value.retryable is False


def _request() -> LLMRequest:
    return LLMRequest(
        operation="JD_ANALYZE",
        system_prompt="sys",
        user_prompt="user",
        schema_hint="{}",
        context={"payload": {"secret": "不得泄露"}},
    )


def _completion(content: str | None, *, with_usage: bool = True):
    message = SimpleNamespace(content=content)
    usage = SimpleNamespace(prompt_tokens=11, completion_tokens=7) if with_usage else None
    return SimpleNamespace(
        choices=[SimpleNamespace(message=message)] if content is not None else [],
        model="deepseek-chat",
        usage=usage,
    )


async def test_deepseek_complete_returns_text_model_and_usage(monkeypatch):
    client = DeepSeekClient(_settings(llm_provider="deepseek", deepseek_api_key="sk-test"))
    captured: dict = {}

    async def fake_create(**kwargs):
        captured.update(kwargs)
        return _completion('{"ok": true}')

    monkeypatch.setattr(client._client.chat.completions, "create", fake_create)
    response = await client.complete(_request())

    assert json.loads(response.text) == {"ok": True}
    assert response.model == "deepseek-chat"
    assert response.input_tokens == 11
    assert response.output_tokens == 7
    assert response.latency_ms >= 0
    assert client.name == "deepseek-chat"
    assert captured["model"] == "deepseek-chat"
    assert captured["messages"] == [
        {"role": "system", "content": "sys"},
        {"role": "user", "content": "user"},
    ]
    assert captured["response_format"] == {"type": "json_object"}


async def test_deepseek_complete_rejects_empty_content(monkeypatch):
    client = DeepSeekClient(_settings(llm_provider="deepseek", deepseek_api_key="sk-test"))

    async def fake_create(**kwargs):
        return _completion(None)

    monkeypatch.setattr(client._client.chat.completions, "create", fake_create)
    with pytest.raises(LLMProviderError, match="模型返回空内容") as caught:
        await client.complete(_request())
    assert caught.value.retryable is True


async def test_deepseek_complete_wraps_provider_error_without_leaking_payload(monkeypatch):
    client = DeepSeekClient(_settings(llm_provider="deepseek", deepseek_api_key="sk-test"))

    async def fake_create(**kwargs):
        raise RuntimeError("boom 不得泄露")

    monkeypatch.setattr(client._client.chat.completions, "create", fake_create)
    with pytest.raises(LLMProviderError) as caught:
        await client.complete(_request())
    assert caught.value.message == "模型调用失败: RuntimeError"
    assert "不得泄露" not in caught.value.message
    assert caught.value.retryable is True


def test_create_llm_selects_provider():
    assert isinstance(create_llm(_settings(llm_provider="fake")), FakeLLM)
    assert isinstance(create_llm(_settings(llm_provider="auto", deepseek_api_key=None)), FakeLLM)
    deepseek = create_llm(_settings(llm_provider="deepseek", deepseek_api_key="sk-test"))
    assert isinstance(deepseek, DeepSeekClient)
    auto = create_llm(_settings(llm_provider="auto", deepseek_api_key="sk-test"))
    assert isinstance(auto, DeepSeekClient)