from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Protocol

import httpx

from .config import Settings
from .operations import RESUME_PARSE

logger = logging.getLogger("agent.llm")


class LLMProviderError(Exception):
    def __init__(self, message: str, *, retryable: bool = True, reasoning: str = "",
                 http_status: int | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.retryable = retryable
        self.reasoning = reasoning
        self.http_status = http_status


@dataclass
class LLMRequest:
    operation: str
    system_prompt: str
    user_prompt: str
    schema_hint: str
    context: dict[str, Any] = field(default_factory=dict)
    reasoning_effort: str | None = None
    max_output_tokens: int | None = None
    timeout_seconds: float | None = None
    thinking: bool | None = None


@dataclass
class LLMResponse:
    text: str
    model: str
    prompt_version: str
    latency_ms: int = 0
    input_tokens: int | None = None
    output_tokens: int | None = None
    reasoning: str = ""


class LLMClient(Protocol):
    @property
    def name(self) -> str: ...

    async def complete(self, request: LLMRequest) -> LLMResponse: ...


def parse_json_object(text: str) -> dict[str, Any]:
    value = text.strip().lstrip("﻿")
    if value.startswith("```"):
        lines = value.splitlines()
        if lines:
            lines = lines[1:]
        if lines and lines[-1].strip().startswith("```"):
            lines = lines[:-1]
        value = "\n".join(lines).strip()
    if not value.startswith("{"):
        start = value.find("{")
        end = value.rfind("}")
        if start >= 0 and end > start:
            value = value[start : end + 1]
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError as exc:
        raise ValueError(f"模型输出不是合法 JSON: {exc.msg}") from exc
    if not isinstance(parsed, dict):
        raise ValueError("模型输出必须是 JSON 对象")
    return parsed


class DeepSeekClient:
    def __init__(self, settings: Settings) -> None:
        if not settings.deepseek_api_key:
            raise LLMProviderError("DEEPSEEK_API_KEY 未配置", retryable=False)
        from openai import AsyncOpenAI

        self._settings = settings
        self._client = AsyncOpenAI(
            api_key=settings.deepseek_api_key,
            base_url=settings.deepseek_base_url,
            timeout=settings.llm_timeout_seconds,
            max_retries=settings.llm_provider_max_retries,
            http_client=httpx.AsyncClient(
                timeout=httpx.Timeout(settings.llm_timeout_seconds),
                trust_env=settings.llm_trust_env,
            ),
        )

    @property
    def name(self) -> str:
        return self._settings.deepseek_model

    async def complete(self, request: LLMRequest) -> LLMResponse:
        from openai import APITimeoutError

        started = time.monotonic()
        logger.info(
            "provider request provider=deepseek model=%s system_chars=%s user_chars=%s",
            self._settings.deepseek_model,
            len(request.system_prompt),
            len(request.user_prompt),
        )
        thinking = self._settings.deepseek_thinking if request.thinking is None else request.thinking
        thinking_resume = thinking and request.operation == RESUME_PARSE
        request_timeout = max(1.0, float(request.timeout_seconds or self._settings.llm_timeout_seconds))
        max_tokens = int(request.max_output_tokens or self._settings.llm_max_output_tokens)
        kwargs: dict[str, Any] = {
            "model": self._settings.deepseek_model,
            "messages": [
                {"role": "system", "content": request.system_prompt},
                {"role": "user", "content": request.user_prompt},
            ],
            "response_format": {"type": "json_object"},
            "max_tokens": max_tokens,
        }
        if thinking:
            # 非流式请求要等思考和 JSON 都生成后才收到结果。
            kwargs["timeout"] = httpx.Timeout(
                request_timeout,
                read=max(request_timeout, self._settings.llm_thinking_read_timeout_seconds),
            )
            kwargs["reasoning_effort"] = request.reasoning_effort or self._settings.deepseek_reasoning_effort
            kwargs["extra_body"] = {"thinking": {"type": "enabled"}}
        else:
            kwargs["temperature"] = self._settings.llm_temperature
            kwargs["extra_body"] = {"thinking": {"type": "disabled"}}
        if thinking_resume:
            kwargs["max_tokens"] = max(
                kwargs["max_tokens"], self._settings.llm_resume_thinking_max_output_tokens
            )

        reasoning = ""
        for attempt in range(2 if thinking_resume else 1):
            if attempt:
                kwargs["reasoning_effort"] = "low"
                kwargs["messages"].append({
                    "role": "user",
                    "content": "请按要求返回一个完整的 JSON 对象，简洁提取原文事实与证据。",
                })
            try:
                response = await self._client.chat.completions.create(**kwargs)
            except Exception as exc:  # provider errors must not leak request content
                status = getattr(exc, "status_code", None)
                logger.error(
                    "provider failed provider=deepseek model=%s duration_ms=%s error=%s http_status=%s",
                    self._settings.deepseek_model,
                    int((time.monotonic() - started) * 1000),
                    type(exc).__name__,
                    status,
                )
                message = (
                    "模型响应超时，请稍后重试"
                    if isinstance(exc, APITimeoutError)
                    else "模型服务返回 HTTP 402，当前账号付费或额度受限"
                    if status == 402
                    else "模型服务拒绝访问，请检查服务端凭证或权限"
                    if status in {401, 403}
                    else f"模型调用失败: {type(exc).__name__}"
                )
                raise LLMProviderError(message, retryable=status is None or status in {408, 429} or status >= 500,
                                       http_status=status) from exc

            choice = response.choices[0] if response.choices else None
            content = choice.message.content if choice and choice.message else None
            usage = getattr(response, "usage", None)
            finish_reason = getattr(choice, "finish_reason", None)
            reasoning = ""
            if choice and choice.message:
                reasoning = getattr(choice.message, "reasoning_content", None) or ""
                if not reasoning:
                    extra = getattr(choice.message, "model_extra", None) or {}
                    reasoning = extra.get("reasoning_content") or ""
            if finish_reason != "length" and content and content.strip():
                break
            logger.warning(
                "provider incomplete JSON model=%s operation=%s attempt=%s finish_reason=%s output_tokens=%s max_tokens=%s",
                self._settings.deepseek_model,
                request.operation,
                attempt + 1,
                finish_reason,
                getattr(usage, "completion_tokens", None),
                kwargs["max_tokens"],
            )
        else:
            message = "模型输出达到长度上限，请重试" if finish_reason == "length" else "模型返回空内容"
            raise LLMProviderError(message, retryable=True, reasoning=str(reasoning))
        result = LLMResponse(
            text=content,
            model=response.model or self._settings.deepseek_model,
            prompt_version="",
            latency_ms=int((time.monotonic() - started) * 1000),
            input_tokens=getattr(usage, "prompt_tokens", None) if usage else None,
            output_tokens=getattr(usage, "completion_tokens", None) if usage else None,
            reasoning=str(reasoning),
        )
        logger.info(
            "provider response provider=deepseek model=%s latency_ms=%s tokens=%s/%s chars=%s",
            result.model,
            result.latency_ms,
            result.input_tokens,
            result.output_tokens,
            len(result.text),
        )
        return result


def create_llm(settings: Settings) -> LLMClient:
    if settings.effective_llm_provider == "deepseek":
        return DeepSeekClient(settings)
    from .fake_model import FakeLLM

    return FakeLLM(model_name="fake-llm")
