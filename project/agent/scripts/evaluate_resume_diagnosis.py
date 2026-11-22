"""简历诊断质量与耗时评测（改前 / 改后同一组合成样本）。

用法：
    cd project/agent
    python scripts/evaluate_resume_diagnosis.py

改后：使用当前 resume-diagnose-v3 提示词与 ResumeDiagnoseHandler，按正常链路执行一次。
改前：从 git HEAD 读取上一版 resume_diagnose.md，只替换任务规则，用同一份合成简历输入调用一次模型。
两边的模型、输入文本、评分规则满分与 temperature 保持一致，差异来自提示词与结果规范化逻辑。

不会打印 API Key；缺少 DEEPSEEK_API_KEY 时以退出码 2 跳过。
评测输出写入仓库 .tmp/resume_diagnosis_eval_<时间戳>.json，不写入业务数据库。
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import subprocess
import sys
import time
from datetime import date
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.compat import ensure_asyncio_self_pipe

ensure_asyncio_self_pipe()

from app.config import Settings
from app.errors import HandlerError
from app.files import FileFetcher
from app.handlers import ExecutionContext, ResumeDiagnoseHandler, norm_text
from app.llm import LLMRequest, create_llm
from app.operations import RESUME_DIAGNOSE
from app.schemas import (
    AIResumeDiagnoseResult,
    RESUME_ASSESSMENT_DEFAULT_RULES,
    ResumeDiagnosePayload,
)
from app.skills import get_skill
from app.skills.registry import PROMPT_DIR, _extract_schema, _section, _split_front_matter

REPO_ROOT = Path(__file__).resolve().parents[3]
OUTPUT_DIR = REPO_ROOT / ".tmp"
MAX_SCORES = {d.code: d.max_score for d in RESUME_ASSESSMENT_DEFAULT_RULES.dimensions}
NOISE_WORDS = ("格式", "排版", "空格", "标点", "错别字", "换行")
TIMELINE_WORDS = ("时间重叠", "时间线重叠", "并行", "同期", "时间冲突")
OLD_RULES = {
    "ruleVersion": "resume-quality-v1",
    "dimensions": [
        {"code": "EXPERIENCE_PROJECTS", "maxScore": 30, "criteria": "职责、个人贡献、解决的问题及成果是否说清楚"},
        {"code": "SKILLS_EVIDENCE", "maxScore": 20, "criteria": "技能描述是否具体，能否在经历或项目中找到支撑"},
        {"code": "CONTENT_COMPLETENESS", "maxScore": 20, "criteria": "教育、经历、技能等信息是否足以理解候选人的背景"},
        {"code": "STRUCTURE_CLARITY", "maxScore": 15, "criteria": "根据提取后的文本判断章节、层级和时间线是否清楚"},
        {"code": "EXPRESSION_PROFESSIONALISM", "maxScore": 15, "criteria": "表述是否具体、简洁，术语是否一致"},
    ],
}

RESUMES = [
    {
        "key": "backend_intern",
        "direction": "Java 后端实习",
        "text": (
            "张明\n"
            "教育背景\n"
            "某大学 计算机科学与技术 本科 2023.09-2027.06\n"
            "技能\n"
            "Java、Spring Boot、MySQL、Redis、RabbitMQ    熟悉常见设计模式\n"
            "项目经历\n"
            "校园二手交易平台后端 2024.09-2025.06\n"
            "独立负责订单与支付回调模块，使用 Redis 缓存热点商品，通过 MySQL 索引优化把商品列表查询耗时从 800ms 降到 120ms\n"
            "实习经历\n"
            "某科技公司 Java 开发实习 2025.03-2025.09\n"
            "参与权限系统改造，使用 Spring Security 实现 RBAC，编写 60 个单元测试，上线后缺陷率下降 30%\n"
            "竞赛荣誉\n"
            "ACM 校赛二等奖\n"
        ),
        "sections": {
            "项目经历": ("订单与支付回调", "二手交易", "800ms", "120ms"),
            "实习经历": ("权限系统", "RBAC", "单元测试", "缺陷率"),
            "技能": ("Spring Boot", "Redis", "RabbitMQ"),
            "竞赛荣誉": ("ACM", "校赛"),
        },
        "noise": "技能行的连续空格",
        "parallel_timeline": "项目 2024.09-2025.06 与实习 2025.03-2025.09 正常重叠",
    },
    {
        "key": "frontend_campus",
        "direction": "前端校招",
        "text": (
            "李华\n"
            "教育背景\n"
            "某大学 软件工程 本科 2022.09-2026.06\n"
            "技能\n"
            "TypeScript、Vue 3、Vite、Element Plus；熟悉前端性能优化\n"
            "项目经历\n"
            "数据可视化平台 2025.01-2025.08\n"
            "独立封装 12 个图表组件，使用虚拟滚动与按需加载，把首屏加载时间从 3.2s 降到 1.4s\n"
            "实习经历\n"
            "某 SaaS 公司 前端开发实习 2025.06-2025.12\n"
            "负责权限配置页重构，沉淀 8 个可复用表单组件，页面开发周期缩短约 40%\n"
            "开源贡献\n"
            "为某 Vue 组件库修复 SSR 兼容问题，提交的 PR 已合并\n"
        ),
        "sections": {
            "项目经历": ("数据可视化", "图表组件", "3.2s", "1.4s"),
            "实习经历": ("权限配置页", "表单组件", "40%"),
            "开源贡献": ("Vue 组件库", "SSR", "PR"),
            "技能": ("TypeScript", "Vue 3", "Vite"),
        },
        "noise": "技能行的分号分隔",
        "parallel_timeline": "项目 2025.01-2025.08 与实习 2025.06-2025.12 正常重叠",
    },
    {
        "key": "data_analyst",
        "direction": "数据分析社招转岗",
        "text": (
            "王芳\n"
            "教育背景\n"
            "某大学 统计学 硕士 2020.09-2022.06\n"
            "工作经历\n"
            "某零售公司 数据分析师 2022.07-2024.12\n"
            "使用 SQL 与 Python 搭建销售看板，覆盖 200+ 门店，把月度报表耗时从 2 天缩短到 2 小时\n"
            "项目经历\n"
            "用户流失预警模型 2024.03-2024.10\n"
            "使用 XGBoost 与特征工程，模型 AUC 达到 0.82，召回率提升 18%，推动运营团队完成两轮召回实验\n"
            "技能\n"
            "Python、SQL、Pandas、XGBoost、Tableau\n"
        ),
        "sections": {
            "工作经历": ("销售看板", "200+ 门店", "2 天", "2 小时"),
            "项目经历": ("流失预警", "XGBoost", "AUC", "召回率"),
            "技能": ("Pandas", "Tableau"),
        },
        "noise": "无明显解析噪声",
        "parallel_timeline": "工作 2022.07-2024.12 与项目 2024.03-2024.10 正常重叠",
    },
]


class CountingLLM:
    def __init__(self, inner) -> None:
        self.inner = inner
        self.calls = 0
        self.name = getattr(inner, "name", "deepseek")

    async def complete(self, request):
        self.calls += 1
        return await self.inner.complete(request)


def payload_for(resume: dict) -> ResumeDiagnosePayload:
    text = resume["text"]
    return ResumeDiagnosePayload.model_validate(
        {
            "document": {
                "versionId": str(uuid4()),
                "fileName": "resume.txt",
                "mimeType": "text/plain",
                "sizeBytes": len(text.encode("utf-8")),
                "text": text,
                "textLayoutVersion": "pdf-layout-v2",
                "readOrderVerified": True,
            },
            "profileVersion": 3,
            "assessmentRules": RESUME_ASSESSMENT_DEFAULT_RULES.model_dump(by_alias=True),
        }
    )


async def run_after(settings: Settings, inner_llm, resume: dict) -> dict:
    llm = CountingLLM(inner_llm)
    payload = payload_for(resume)
    stages: list[str] = []

    async def emit_stage(name: str, progress: int | None) -> None:
        stages.append(f"{name}:{progress}")

    ctx = ExecutionContext(
        run=SimpleNamespace(operation=RESUME_DIAGNOSE, run_id=str(uuid4())),
        payload=payload,
        settings=settings,
        llm=llm,
        files=FileFetcher(settings),
        emit_stage=emit_stage,
    )
    started = time.perf_counter()
    result, meta = await ResumeDiagnoseHandler().execute(ctx)
    elapsed_ms = int((time.perf_counter() - started) * 1000)
    metrics = measure(result, resume, elapsed_ms, meta["attempts"], meta["promptVersion"])
    metrics["stage_count"] = len(stages)
    return metrics


async def run_before(settings: Settings, inner_llm, resume: dict) -> dict | None:
    old_body = _git_file("project/agent/app/skills/prompts/resume_diagnose.md")
    if old_body is None:
        return None
    skill = get_skill(RESUME_DIAGNOSE)
    current_rules = _section((PROMPT_DIR / "resume_diagnose.md").read_text(encoding="utf-8"), "任务规则")
    _, old_markdown = _split_front_matter(old_body)
    old_rules = _section(old_markdown, "任务规则")
    old_system = skill.system_prompt.replace(current_rules, old_rules)
    if old_system == skill.system_prompt:
        return None
    old_schema = _extract_schema(old_markdown)

    payload = payload_for(resume)
    payload_dict = payload.model_dump(by_alias=True, mode="json")
    payload_dict["assessmentRules"] = OLD_RULES
    context = {
        "document_text": payload.document.text,
        "diagnosis_date": date.today().isoformat(),
    }
    body = skill.build_input(payload_dict, context)
    user_prompt = skill.user_template.format(
        input_json=json.dumps(body, ensure_ascii=False, separators=(",", ":")),
        output_schema=old_schema,
    )
    request = LLMRequest(
        operation=RESUME_DIAGNOSE,
        system_prompt=old_system,
        user_prompt=user_prompt,
        schema_hint=old_schema,
        context={**payload_dict, **context},
        thinking=False,
    )
    llm = CountingLLM(inner_llm)
    started = time.perf_counter()
    response = await llm.complete(request)
    elapsed_ms = int((time.perf_counter() - started) * 1000)
    try:
        validated = AIResumeDiagnoseResult.model_validate(skill.parse_output(response.text))
    except Exception as exc:  # noqa: BLE001 - 评测脚本需保留旧输出失败事实
        return {
            "mode": "before",
            "calls": llm.calls,
            "latencyMs": elapsed_ms,
            "parseError": f"{type(exc).__name__}: {exc}",
        }
    result = validated.model_dump(by_alias=True, mode="json")
    metrics = measure(result, resume, elapsed_ms, 1, "resume-diagnose-v2")
    metrics["parseError"] = None
    return metrics


def measure(result: dict, resume: dict, elapsed_ms: int, attempts: int, prompt_version: str) -> dict:
    text = resume["text"]
    items = result["items"]
    assessment = result["assessment"]
    compact = norm_text(text)

    def in_text(value: str | None) -> bool:
        return bool(value) and norm_text(value) in compact

    quote_hits = [in_text(item.get("quote")) for item in items]
    if assessment is not None:
        quote_hits.extend(in_text(d.get("quote")) for d in assessment["dimensions"] if d.get("score", 0) > 0)
        quote_hits.extend(in_text(s.get("quote")) for s in assessment["strengths"])
        for item in items:
            quote_hits.extend(in_text(q) for q in item.get("quotes") or [])
        for dimension in assessment["dimensions"]:
            quote_hits.extend(in_text(q) for q in dimension.get("quotes") or [])
        for strength in assessment["strengths"]:
            quote_hits.extend(in_text(q) for q in strength.get("quotes") or [])

    important = [item for item in items if item.get("priority") == "IMPORTANT"]
    metrics = {
        "calls": 1,
        "attempts": attempts,
        "latencyMs": elapsed_ms,
        "promptVersion": prompt_version,
        "itemCount": len(items),
        "importantCount": len(important),
        "totalScore": sum(d["score"] for d in assessment["dimensions"]) if assessment else None,
        "ruleVersion": assessment["ruleVersion"] if assessment else None,
        "quotesAllInText": all(quote_hits) if quote_hits else False,
        "impactCoverage": ratio(items, lambda item: bool(item.get("impact"))),
        "actionCoverage": ratio(items, lambda item: len(item.get("suggestion") or "") >= 10),
        "exampleOrPendingCoverage": ratio(
            items, lambda item: bool(item.get("example")) or bool(item.get("pendingVerification"))
        ),
        "top3NoiseOnly": any(
            any(word in f"{item.get('title', '')}{item.get('issue', '')}" for word in NOISE_WORDS)
            for item in items[:3]
        ),
        "timelineFalsePositive": any(
            any(word in f"{item.get('title', '')}{item.get('issue', '')}{item.get('rationale', '')}" for word in TIMELINE_WORDS)
            and not any(word in f"{item.get('title', '')}{item.get('issue', '')}" for word in ("矛盾", "晚于", "早于"))
            for item in items
        ),
        "strengthCount": len(assessment["strengths"]) if assessment else 0,
        "strengthEvidenceCoverage": ratio(
            assessment["strengths"] if assessment else [],
            lambda s: bool(s.get("approach")) and bool(s.get("contribution")) and bool(s.get("result")),
        ),
        "coveredSections": [],
        "crossSectionStrengths": 0,
        "itemSummaries": [
            {
                "priority": item.get("priority"),
                "title": item.get("title"),
                "issue": item.get("issue"),
                "impact": item.get("impact"),
                "hasExample": bool(item.get("example")),
                "pendingVerification": item.get("pendingVerification") or [],
            }
            for item in items
        ],
        "strengthSummaries": [
            {
                "title": strength.get("title"),
                "approach": strength.get("approach"),
                "contribution": strength.get("contribution"),
                "result": strength.get("result"),
                "hasAdditionalQuotes": bool(strength.get("quotes")),
            }
            for strength in (assessment["strengths"] if assessment else [])
        ],
    }
    if assessment is None:
        return metrics
    covered: set[str] = set()
    cross = 0
    for strength in assessment["strengths"]:
        blob = " ".join([strength.get("quote") or "", *(strength.get("quotes") or []), strength.get("description") or ""])
        hits = {
            name
            for name, markers in resume["sections"].items()
            if any(marker in blob for marker in markers) or name in blob
        }
        covered.update(hits)
        if len(hits) >= 2:
            cross += 1
    metrics["coveredSections"] = sorted(covered)
    metrics["crossSectionStrengths"] = cross
    metrics["sectionCoverage"] = f"{len(covered)}/{len(resume['sections'])}"
    return metrics


def ratio(values: list, predicate) -> float:
    if not values:
        return 0.0
    return round(sum(1 for value in values if predicate(value)) / len(values), 3)


def _git_file(path: str) -> str | None:
    try:
        completed = subprocess.run(
            ["git", "show", f"HEAD:{path}"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            check=True,
        )
        return completed.stdout
    except Exception:  # noqa: BLE001 - 缺少 git 或旧文件时跳过改前基线
        return None


async def main() -> int:
    logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(name)s %(message)s")
    settings = Settings()
    if settings.effective_llm_provider != "deepseek":
        print("SKIP: 未配置 DEEPSEEK_API_KEY，无法执行真实模型评测")
        return 2

    only = os.environ.get("EVAL_ONLY")
    results = {"generatedAt": date.today().isoformat(), "model": settings.deepseek_model, "samples": []}
    for resume in RESUMES:
        if only and resume["key"] != only:
            continue
        print(f"== {resume['key']} / {resume['direction']} ==")
        sample = {"key": resume["key"], "direction": resume["direction"], "noise": resume["noise"],
                  "parallelTimeline": resume["parallel_timeline"]}
        after_llm = create_llm(settings)
        try:
            sample["after"] = await run_after(settings, after_llm, resume)
        except HandlerError as exc:
            sample["after"] = {"error": f"{exc.code}: {exc.message}"}
        before_llm = create_llm(settings)
        try:
            sample["before"] = await run_before(settings, before_llm, resume)
        except Exception as exc:  # noqa: BLE001 - 保留真实失败原因
            sample["before"] = {"error": f"{type(exc).__name__}: {exc}"}
        results["samples"].append(sample)
        for mode in ("before", "after"):
            value = sample.get(mode) or {}
            print(f"  {mode}: {json.dumps(value, ensure_ascii=False)}")

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    output = OUTPUT_DIR / f"resume_diagnosis_eval_{time.strftime('%Y%m%d_%H%M%S')}.json"
    output.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nWROTE {output}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))