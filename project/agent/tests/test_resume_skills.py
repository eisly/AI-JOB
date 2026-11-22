"""resume_skills 模块测试：目录补漏、引用定位、React/ReAct 区分、Java/JavaScript 区分。"""
from __future__ import annotations

from app.resume_skills import locate_quote, recover_catalog_skills
from app.schemas import SkillCatalogItem

TEXT = (
    "[page:1]\n"
    "教育背景：本科，计算机科学与技术专业。\n"
    "实习经历：使用Redis实现缓存，参与Java接口开发。\n"
    "[page:2]\n"
    "前端：熟悉 React、Vue 组件开发。\n"
    "Agent：熟悉 ReAct 模式与 Plan-and-Execute。\n"
    "技能：了解MySQL索引优化。\n"
)

CATALOG = [
    SkillCatalogItem(code="JAVA", name="Java", aliases=[]),
    SkillCatalogItem(code="JAVASCRIPT", name="JavaScript", aliases=["JS"]),
    SkillCatalogItem(code="REACT", name="React", aliases=["React.js"]),
    SkillCatalogItem(code="REACT_PATTERN", name="ReAct", aliases=["ReAct 模式"]),
    SkillCatalogItem(code="MYSQL", name="MySQL", aliases=[]),
    SkillCatalogItem(code="QDRANT", name="Qdrant", aliases=[]),
]


def test_recovers_skills_missing_from_model_output():
    recovered = recover_catalog_skills(TEXT, CATALOG, existing_codes=set())
    codes = {item.skill_code for item in recovered}
    assert codes == {"JAVA", "REACT", "REACT_PATTERN", "MYSQL"}
    # 不在原文中的技能不补回
    assert "QDRANT" not in codes


def test_recovered_skills_have_quote_and_location():
    recovered = recover_catalog_skills(TEXT, CATALOG, existing_codes=set())
    by_code = {item.skill_code: item for item in recovered}
    java = by_code["JAVA"]
    # line_no 为全文行号（页码标记行也占一行）：实习经历行是全文第 3 行
    assert java.location == "page:1;line:3"
    assert "参与Java接口开发" in java.quote
    react = by_code["REACT"]
    assert react.location == "page:2;line:5"
    mysql = by_code["MYSQL"]
    assert mysql.location == "page:2;line:7"


def test_existing_codes_are_not_recovered_again():
    recovered = recover_catalog_skills(TEXT, CATALOG, existing_codes={"JAVA", "MYSQL"})
    assert {item.skill_code for item in recovered} == {"REACT", "REACT_PATTERN"}


def test_react_vs_react_framework_disambiguation():
    """前端 React 与 Agent 的 ReAct 必须按精确大小写区分。"""
    recovered = recover_catalog_skills(TEXT, CATALOG, existing_codes=set())
    by_code = {item.skill_code: item for item in recovered}
    assert "熟悉 React、Vue 组件开发" in by_code["REACT"].quote
    assert "熟悉 ReAct 模式" in by_code["REACT_PATTERN"].quote


def test_java_does_not_match_javascript():
    text = "技能：熟悉 JavaScript 与 TypeScript。"
    recovered = recover_catalog_skills(
        text,
        [
            SkillCatalogItem(code="JAVA", name="Java", aliases=[]),
            SkillCatalogItem(code="JAVASCRIPT", name="JavaScript", aliases=[]),
        ],
        existing_codes=set(),
    )
    codes = {item.skill_code for item in recovered}
    assert "JAVASCRIPT" in codes
    assert "JAVA" not in codes


def test_ambiguous_case_form_is_skipped():
    """全大写 REACT 无法路由到 React/ReAct，应保守跳过。"""
    text = "技能：了解 REACT 基础。"
    recovered = recover_catalog_skills(
        text,
        [
            SkillCatalogItem(code="REACT", name="React", aliases=[]),
            SkillCatalogItem(code="REACT_PATTERN", name="ReAct", aliases=[]),
        ],
        existing_codes=set(),
    )
    assert recovered == []


def test_alias_matching_ignores_hyphen_and_space():
    text = "项目：基于SpringBoot + Spring AI 开发。"
    recovered = recover_catalog_skills(
        text,
        [
            SkillCatalogItem(code="SPRING_BOOT", name="Spring Boot", aliases=["SpringBoot"]),
            SkillCatalogItem(code="SPRING_AI", name="Spring AI", aliases=[]),
        ],
        existing_codes=set(),
    )
    assert {item.skill_code for item in recovered} == {"SPRING_BOOT", "SPRING_AI"}


def test_locate_quote_single_and_multi_line():
    assert locate_quote(TEXT, "参与Java接口开发") == "page:1;line:3"
    assert locate_quote(TEXT, "了解MySQL索引优化") == "page:2;line:7"
    # 多行窗口命中：引用跨行时按首行定位
    assert locate_quote(TEXT, "熟悉 React、Vue 组件开发。Agent：") == "page:2;line:5"
    # 忽略空白与大小写
    assert locate_quote(TEXT, "  参 与 J a v a 接 口 开 发 ") == "page:1;line:3"
    assert locate_quote(TEXT, "不存在的引用") is None
    assert locate_quote(TEXT, "") is None
