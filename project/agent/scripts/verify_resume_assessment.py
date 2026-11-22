"""简历诊断五维度评分真实链路冒烟测试。

验证 RESUME_DIAGNOSE 在升级后的提示词（resume-diagnose-v2）+ DeepSeek 真实模型下：
1. 一次模型生成同时返回诊断建议与五维度评分（attempts 应为 1）；
2. 评分维度齐全、分数在满分内、正分引用能逐字命中授权原文（PDF 空白差异回填后）；
3. 记录耗时与 token 输出量，供改动前后对比。

复现：
    conda activate ai-job
    cd project/agent
    python scripts/verify_resume_assessment.py

不会打印 API Key；缺少 Key 时以退出码 2 跳过。
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.compat import ensure_asyncio_self_pipe

ensure_asyncio_self_pipe()

from app.config import Settings
from app.files import FileFetcher
from app.handlers import ExecutionContext, ResumeDiagnoseHandler
from app.llm import create_llm
from app.schemas import RESUME_ASSESSMENT_DEFAULT_RULES, ResumeDiagnosePayload

# 合成简历：包含 \r\n 换行以覆盖 PDF 提取文本的空白差异回填路径。
RESUME_TEXT = (
    "张三\r\n"
    "教育背景\r\n"
    "某某大学 计算机科学与技术 本科 2023.09-2027.06\r\n"
    "技能\r\n"
    "熟悉 Java、Spring Boot、MySQL、Redis，了解消息队列与缓存设计\r\n"
    "项目经历\r\n"
    "电商平台后端开发 2025.03-2025.09\r\n"
    "负责订单模块的接口开发，使用 Redis 实现缓存，参与数据库表结构设计\r\n"
    "实习经历\r\n"
    "某互联网公司 Java 开发实习 2025.07-2025.09\r\n"
    "参与后台管理系统的功能迭代，编写单元测试并修复线上问题\r\n"
)

MAX_SCORES = {d.code: d.max_score for d in RESUME_ASSESSMENT_DEFAULT_RULES.dimensions}


async def main() -> int:
    settings = Settings()
    if settings.effective_llm_provider != "deepseek":
        print("SKIP: 未配置 DEEPSEEK_API_KEY，无法执行 DeepSeek 冒烟")
        return 2

    payload = ResumeDiagnosePayload.model_validate(
        {
            "document": {
                "versionId": str(uuid4()),
                "fileName": "resume.txt",
                "mimeType": "text/plain",
                "sizeBytes": len(RESUME_TEXT.encode("utf-8")),
                "text": RESUME_TEXT,
            },
            "profileVersion": 3,
            "assessmentRules": RESUME_ASSESSMENT_DEFAULT_RULES.model_dump(by_alias=True),
        }
    )
    stages: list[str] = []

    async def emit_stage(name: str, progress: int | None) -> None:
        stages.append(f"{name}:{progress}")

    ctx = ExecutionContext(
        run=SimpleNamespace(operation="RESUME_DIAGNOSE", run_id=str(uuid4())),
        payload=payload,
        settings=settings,
        llm=create_llm(settings),
        files=FileFetcher(settings),
        emit_stage=emit_stage,
    )
    from app.errors import HandlerError

    try:
        result, meta = await ResumeDiagnoseHandler().execute(ctx)
    except HandlerError as exc:
        print(f"SMOKE FAILED: {exc.code}: {exc.message}")
        print("提示：真实模型调用需要能够访问 api.deepseek.com；")
        print("如本机通过代理出网，请设置 AGENT_LLM_TRUST_ENV=true 后重试。")
        return 1

    snapshot_text = RESUME_TEXT
    items = result["items"]
    assessment = result["assessment"]
    assert assessment is not None, "诊断结果必须包含评分 assessment"
    assert meta["attempts"] == 1, f"快速模式应单次模型调用，实际 attempts={meta['attempts']}"

    dimensions = assessment["dimensions"]
    assert len(dimensions) == 5, f"评分维度应为 5 项，实际 {len(dimensions)}"
    codes = [d["code"] for d in dimensions]
    assert sorted(codes) == sorted(MAX_SCORES), f"维度编码不符: {codes}"
    total = 0
    for dimension in dimensions:
        score = dimension["score"]
        max_score = MAX_SCORES[dimension["code"]]
        assert isinstance(score, int), f"{dimension['code']} 分数应为整数"
        assert 0 <= score <= max_score, f"{dimension['code']} 分数越界: {score}/{max_score}"
        total += score
        if score > 0:
            assert dimension["quote"], f"{dimension['code']} 正分必须提供 quote"
            assert dimension["quote"] in snapshot_text, (
                f"{dimension['code']} 引用未逐字命中快照原文"
            )
        else:
            assert dimension["quote"] is None, f"{dimension['code']} 零分 quote 应为 null"
    for strength in assessment["strengths"]:
        assert strength["quote"] in snapshot_text, "优势引用未逐字命中快照原文"
    for item in items:
        assert item["quote"] in snapshot_text, "诊断项引用未逐字命中快照原文"

    print("SMOKE OK")
    print(f"model={meta['model']} attempts={meta['attempts']} latencyMs={meta['latencyMs']}")
    print(f"tokens(in/out)={meta['inputTokens']}/{meta['outputTokens']}")
    print(f"promptVersion={meta['promptVersion']}")
    print(f"ruleVersion={assessment['ruleVersion']} totalScore={total}")
    for dimension in dimensions:
        print(
            f"  {dimension['code']}: {dimension['score']}/{MAX_SCORES[dimension['code']]}"
            f" quote_len={len(dimension['quote'] or '')}"
        )
    print(f"items={len(items)} strengths={len(assessment['strengths'])}")
    print(f"stages={' -> '.join(stages)}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
