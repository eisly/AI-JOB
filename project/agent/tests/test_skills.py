from __future__ import annotations

import shutil
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.fake_model import FakeLLM, fake_llm_from_payload
from app.llm import LLMRequest
from app.operations import OPERATIONS
from app.prompts import build_prompt, build_repair_prompt
from app.skills import get_registry, get_skill
from app.skills.registry import (
    PROMPT_DIR,
    SkillRegistry,
    _extract_schema,
    _section,
    _split_front_matter,
)
from app.skills.tools import build_model_input, validate_result_contract


def test_registry_loads_all_skills_with_normalized_metadata():
    registry = get_registry()
    skills = registry.all()
    assert len(skills) == len(OPERATIONS) == 13
    for skill in skills:
        assert skill.description
        assert skill.prompt_file.name == f"{skill.operation.lower()}.md"
        assert skill.prompt_file.parent == PROMPT_DIR
        assert skill.tool_names == ("build_model_input", "parse_model_output", "validate_result_contract")
        assert "你是 AI-JOB 的内部求职辅助模型" in skill.system_prompt
        assert skill.schema_hint.startswith("{")
        rendered = skill.render_user_prompt({"k": "值"})
        assert '"k":"值"' in rendered
        assert skill.schema_hint in rendered


def test_skill_invoke_tool_dispatch_and_unknown_tool():
    skill = get_skill("JD_ANALYZE")
    assert skill.invoke_tool("parse_model_output", '{"a": 1}') == {"a": 1}
    with pytest.raises(ValueError, match="未在技能 JD_ANALYZE 中注册"):
        skill.invoke_tool("not_a_tool")
    with pytest.raises(ValueError, match="unknown operation"):
        get_skill("NOPE")


def test_repair_prompt_contains_error_and_truncates_previous_output():
    previous = "x" * 2000
    prompt = get_registry().render_repair_prompt("结构与 JSON", "字段缺失", previous)
    assert "上一次输出未通过结构与 JSON 校验" in prompt
    assert "字段缺失" in prompt
    assert "x" * 1200 in prompt
    assert "x" * 1201 not in prompt

    via_module = build_repair_prompt("业务规则", "权重错误", "上一轮")
    assert "业务规则" in via_module
    assert "权重错误" in via_module


def test_build_prompt_uses_skill_pipeline():
    system, user, schema = build_prompt("JD_ANALYZE", {"jdText": "内容", "skillCatalog": []}, {})
    assert "## 系统规则" not in system  # Markdown 章节标题不进入最终提示词
    assert "不输出任何 PASS/FAIL" in system
    assert "<input>" in user
    assert "jdText" in user
    assert schema.startswith("{")


def test_front_matter_parsing_errors():
    with pytest.raises(ValueError, match="缺少 YAML front matter"):
        _split_front_matter("没有头")
    with pytest.raises(ValueError, match="未闭合"):
        _split_front_matter("---\nname: a\n")
    with pytest.raises(ValueError, match="无效的提示词元数据"):
        _split_front_matter("---\nbadline\n---\n正文")


def test_section_and_schema_extraction_errors():
    with pytest.raises(ValueError, match="提示词缺少章节"):
        _section("## 其他\n内容", "任务规则")
    with pytest.raises(ValueError, match="输出结构章节必须包含 JSON 代码块"):
        _extract_schema("## 输出结构\n没有代码块")


def _copy_prompts(tmp_path) -> Path:
    target = tmp_path / "prompts"
    shutil.copytree(PROMPT_DIR, target)
    return target


def _rewrite(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    assert old in text
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def test_registry_rejects_version_mismatch(tmp_path):
    directory = _copy_prompts(tmp_path)
    _rewrite(directory / "resume_parse.md", "version: resume-parse-v2", "version: wrong")
    with pytest.raises(ValueError, match="version 与 PROMPT_VERSIONS 不一致"):
        SkillRegistry(directory)


def test_registry_rejects_name_mismatch(tmp_path):
    directory = _copy_prompts(tmp_path)
    _rewrite(directory / "resume_parse.md", "name: resume_parse", "name: other")
    with pytest.raises(ValueError, match="name 元数据必须与文件名一致"):
        SkillRegistry(directory)


def test_registry_rejects_missing_description(tmp_path):
    directory = _copy_prompts(tmp_path)
    _rewrite(
        directory / "resume_parse.md",
        "description: 从简历文本提取候选事实、受控技能与原文证据。\n",
        "",
    )
    with pytest.raises(ValueError, match="缺少 description"):
        SkillRegistry(directory)


def test_registry_rejects_operation_mismatch(tmp_path):
    directory = _copy_prompts(tmp_path)
    _rewrite(directory / "resume_parse.md", "operation: RESUME_PARSE", "operation: JD_ANALYZE")
    with pytest.raises(ValueError, match="operation 元数据不匹配"):
        SkillRegistry(directory)


def test_registry_rejects_unknown_and_incomplete_tools(tmp_path):
    directory = _copy_prompts(tmp_path)
    _rewrite(
        directory / "resume_parse.md",
        "tools: build_model_input,parse_model_output,validate_result_contract",
        "tools: build_model_input,parse_model_output,validate_result_contract,hack",
    )
    with pytest.raises(ValueError, match="注册了未知工具"):
        SkillRegistry(directory)

    directory = _copy_prompts(tmp_path / "second")
    _rewrite(
        directory / "resume_parse.md",
        "tools: build_model_input,parse_model_output,validate_result_contract",
        "tools: build_model_input",
    )
    with pytest.raises(ValueError, match="必须注册完整的标准工具集"):
        SkillRegistry(directory)


def test_registry_requires_repair_version(tmp_path):
    directory = _copy_prompts(tmp_path)
    _rewrite(directory / "repair.md", "version: repair-v2\n", "")
    with pytest.raises(ValueError, match="repair.md 缺少 version"):
        SkillRegistry(directory)


def test_build_model_input_unknown_operation_and_result_contract():
    with pytest.raises(ValueError, match="unknown operation"):
        build_model_input("NOPE", {}, {})
    validated = validate_result_contract("GROWTH_ANALYZE", {"suggestions": ["a"], "citedEvidenceIds": []})
    assert validated == {"suggestions": ["a"], "citedEvidenceIds": []}
    with pytest.raises(ValidationError):
        validate_result_contract("GROWTH_ANALYZE", {"suggestions": []})


async def test_fake_llm_custom_responder_and_helper():
    request = LLMRequest(
        operation="JD_ANALYZE",
        system_prompt="s",
        user_prompt="u",
        schema_hint="{}",
        context={"payload": {"jdText": "本科", "skillCatalog": []}},
    )
    assert fake_llm_from_payload(request)["requirements"]["educationMin"] == "BACHELOR"

    llm = FakeLLM(responder=lambda req: "纯文本输出")
    response = await llm.complete(request)
    assert response.text == "纯文本输出"
    assert llm.calls[0] is request
    assert llm.name == "fake-llm"