from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Protocol

import httpx

from .config import Settings

logger = logging.getLogger("agent.llm")


class LLMProviderError(Exception):
    def __init__(self, message: str, *, retryable: bool = True) -> None:
        super().__init__(message)
        self.message = message
        self.retryable = retryable


@dataclass
class LLMRequest:
    operation: str
    system_prompt: str
    user_prompt: str
    schema_hint: str
    context: dict[str, Any] = field(default_factory=dict)


@dataclass
class LLMResponse:
    text: str
    model: str
    prompt_version: str
    latency_ms: int = 0
    input_tokens: int | None = None
    output_tokens: int | None = None


class LLMClient(Protocol):
    @property
    def name(self) -> str: ...

    async def complete(self, request: LLMRequest) -> LLMResponse: ...


def parse_json_object(text: str) -> dict[str, Any]:
    value = text.strip().lstrip("\ufeff")
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
        started = time.monotonic()
        logger.info(
            "provider request provider=deepseek model=%s system_chars=%s user_chars=%s",
            self._settings.deepseek_model,
            len(request.system_prompt),
            len(request.user_prompt),
        )
        try:
            response = await self._client.chat.completions.create(
                model=self._settings.deepseek_model,
                messages=[
                    {"role": "system", "content": request.system_prompt},
                    {"role": "user", "content": request.user_prompt},
                ],
                response_format={"type": "json_object"},
                temperature=self._settings.llm_temperature,
                max_tokens=self._settings.llm_max_output_tokens,
            )
        except Exception as exc:  # provider errors must not leak request content
            logger.error(
                "provider failed provider=deepseek model=%s duration_ms=%s error=%s",
                self._settings.deepseek_model,
                int((time.monotonic() - started) * 1000),
                type(exc).__name__,
            )
            raise LLMProviderError(f"模型调用失败: {type(exc).__name__}", retryable=True) from exc

        choice = response.choices[0] if response.choices else None
        content = choice.message.content if choice and choice.message else None
        if not content:
            logger.warning("provider empty response provider=deepseek model=%s", self._settings.deepseek_model)
            raise LLMProviderError("模型返回空内容", retryable=True)
        usage = getattr(response, "usage", None)
        result = LLMResponse(
            text=content,
            model=response.model or self._settings.deepseek_model,
            prompt_version="",
            latency_ms=int((time.monotonic() - started) * 1000),
            input_tokens=getattr(usage, "prompt_tokens", None) if usage else None,
            output_tokens=getattr(usage, "completion_tokens", None) if usage else None,
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