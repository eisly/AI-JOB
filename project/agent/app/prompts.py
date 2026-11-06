from __future__ import annotations

from typing import Any

from .skills import get_registry, get_skill


def build_prompt(
    operation: str,
    payload: dict[str, Any],
    context: dict[str, Any],
) -> tuple[str, str, str]:
    skill = get_skill(operation)
    body = skill.build_input(payload, context)
    return skill.system_prompt, skill.render_user_prompt(body), skill.schema_hint


def build_repair_prompt(
    error_kind: str,
    validation_error: str,
    previous_output: str,
) -> str:
    return get_registry().render_repair_prompt(
        error_kind,
        validation_error,
        previous_output,
    )