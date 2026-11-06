from __future__ import annotations

import json
import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any

from ..operations import OPERATIONS, PROMPT_VERSIONS
from .tools import build_model_input, parse_model_output, validate_result_contract

PROMPT_DIR = Path(__file__).resolve().with_name("prompts")
AVAILABLE_TOOLS = {
    "build_model_input": build_model_input,
    "parse_model_output": parse_model_output,
    "validate_result_contract": validate_result_contract,
}


@dataclass(frozen=True)
class Skill:
    """一个技能的完整封装：Markdown 提示词 + 声明式 tools。"""

    operation: str
    version: str
    description: str
    system_prompt: str
    user_template: str
    schema_hint: str
    prompt_file: Path
    tool_names: tuple[str, ...]

    def invoke_tool(self, name: str, *args: Any, **kwargs: Any) -> Any:
        """通过技能调用其 front matter 中声明的工具。"""
        if name not in self.tool_names:
            raise ValueError(f"工具 {name} 未在技能 {self.operation} 中注册")
        return AVAILABLE_TOOLS[name](*args, **kwargs)

    def build_input(
        self,
        payload: dict[str, Any],
        context: dict[str, Any],
    ) -> dict[str, Any]:
        return self.invoke_tool("build_model_input", self.operation, payload, context)

    def render_user_prompt(self, body: dict[str, Any]) -> str:
        input_json = json.dumps(body, ensure_ascii=False, separators=(",", ":"))
        return self.user_template.format(input_json=input_json, output_schema=self.schema_hint)

    def parse_output(self, text: str) -> dict[str, Any]:
        return self.invoke_tool("parse_model_output", text)

    def validate_result(self, result: dict[str, Any]) -> dict[str, Any]:
        return self.invoke_tool("validate_result_contract", self.operation, result)


def _split_front_matter(text: str) -> tuple[dict[str, str], str]:
    normalized = text.replace("\r\n", "\n").lstrip("\ufeff")
    if not normalized.startswith("---\n"):
        raise ValueError("提示词文件缺少 YAML front matter")
    end = normalized.find("\n---\n", 4)
    if end < 0:
        raise ValueError("提示词文件的 YAML front matter 未闭合")
    metadata: dict[str, str] = {}
    for line in normalized[4:end].splitlines():
        if not line.strip():
            continue
        key, separator, value = line.partition(":")
        if not separator or not key.strip() or not value.strip():
            raise ValueError(f"无效的提示词元数据: {line}")
        metadata[key.strip()] = value.strip()
    return metadata, normalized[end + 5 :].strip()


def _read_markdown(path: Path) -> tuple[dict[str, str], str]:
    return _split_front_matter(path.read_text(encoding="utf-8"))


def _section(text: str, title: str) -> str:
    marker = f"## {title}"
    start = text.find(marker)
    if start < 0:
        raise ValueError(f"提示词缺少章节: {title}")
    content = text[start + len(marker) :]
    next_section = content.find("\n## ")
    if next_section >= 0:
        content = content[:next_section]
    return content.strip()


def _extract_schema(text: str) -> str:
    section = _section(text, "输出结构")
    match = re.search(r"```json\s*(\{.*?\})\s*```", section, re.DOTALL)
    if match is None:
        raise ValueError("输出结构章节必须包含 JSON 代码块")
    return match.group(1).strip()


class SkillRegistry:
    def __init__(self, prompt_dir: Path | None = None) -> None:
        self.prompt_dir = prompt_dir or PROMPT_DIR
        _, base = _read_markdown(self.prompt_dir / "base.md")
        self._base_system = _section(base, "系统规则")
        self._user_template = _section(base, "用户输入模板")
        repair_metadata, self._repair_template = _read_markdown(self.prompt_dir / "repair.md")
        if not repair_metadata.get("version"):
            raise ValueError("repair.md 缺少 version")
        self._skills = {
            operation: self._load_skill(operation)
            for operation in OPERATIONS
        }

    def _load_skill(self, operation: str) -> Skill:
        path = self.prompt_dir / f"{operation.lower()}.md"
        metadata, body = _read_markdown(path)
        if metadata.get("name") != path.stem:
            raise ValueError(f"{path.name} 的 name 元数据必须与文件名一致")
        description = metadata.get("description", "")
        if not description:
            raise ValueError(f"{path.name} 缺少 description 元数据")
        if metadata.get("operation") != operation:
            raise ValueError(f"{path.name} 的 operation 元数据不匹配")
        version = metadata.get("version", "")
        if version != PROMPT_VERSIONS[operation]:
            raise ValueError(f"{path.name} 的 version 与 PROMPT_VERSIONS 不一致")
        tool_names = tuple(
            item.strip()
            for item in metadata.get("tools", "").split(",")
            if item.strip()
        )
        unknown_tools = set(tool_names) - set(AVAILABLE_TOOLS)
        if unknown_tools:
            raise ValueError(f"{path.name} 注册了未知工具: {', '.join(sorted(unknown_tools))}")
        if set(tool_names) != set(AVAILABLE_TOOLS):
            raise ValueError(f"{path.name} 必须注册完整的标准工具集")
        rules = _section(body, "任务规则")
        return Skill(
            operation=operation,
            version=version,
            description=description,
            system_prompt=f"{self._base_system}\n\n{rules}",
            user_template=self._user_template,
            schema_hint=_extract_schema(body),
            prompt_file=path,
            tool_names=tool_names,
        )

    def get(self, operation: str) -> Skill:
        skill = self._skills.get(operation)
        if skill is None:
            raise ValueError(f"unknown operation {operation}")
        return skill

    def all(self) -> tuple[Skill, ...]:
        return tuple(self._skills[operation] for operation in OPERATIONS)

    def render_repair_prompt(
        self,
        error_kind: str,
        validation_error: str,
        previous_output: str,
    ) -> str:
        return self._repair_template.format(
            error_kind=error_kind,
            validation_error=validation_error,
            previous_output=previous_output[:1200],
        )


@lru_cache(maxsize=1)
def get_registry() -> SkillRegistry:
    return SkillRegistry()


def get_skill(operation: str) -> Skill:
    return get_registry().get(operation)