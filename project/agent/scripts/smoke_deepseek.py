"""DeepSeek 真实链路冒烟测试。

验证 JD_ANALYZE 在 LangGraph 编排 + skill 提示词 + DeepSeek 真实模型下
能够产出通过最终契约校验的结果。

复现：
    conda activate ai-job
    # 确保环境变量或根目录 .env 中配置了 DEEPSEEK_API_KEY
    cd project/agent
    python scripts/smoke_deepseek.py

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
from app.graphs import CareerOrchestrator
from app.handlers import ExecutionContext
from app.llm import create_llm
from app.schemas import JDParsePayload

JD_TEXT = (
    "本科及以上学历，2026至2027届毕业生；熟悉Java、MySQL与Redis，"
    "了解并发编程和缓存设计；参与业务接口开发、单元测试与性能优化。"
)


async def main() -> int:
    settings = Settings()
    if settings.effective_llm_provider != "deepseek":
        print("SKIP: 未配置 DEEPSEEK_API_KEY，无法执行 DeepSeek 冒烟")
        return 2

    payload = JDParsePayload.model_validate(
        {
            "jobId": str(uuid4()),
            "jobVersion": 1,
            "jdText": JD_TEXT,
            "skillCatalog": [
                {"code": "java", "name": "Java"},
                {"code": "mysql", "name": "MySQL"},
                {"code": "redis", "name": "Redis"},
            ],
        }
    )
    stages: list[str] = []

    async def emit_stage(name: str, progress: int | None) -> None:
        stages.append(f"{name}:{progress}")

    ctx = ExecutionContext(
        run=SimpleNamespace(operation="JD_ANALYZE", run_id=str(uuid4())),
        payload=payload,
        settings=settings,
        llm=create_llm(settings),
        files=FileFetcher(settings),
        emit_stage=emit_stage,
    )
    from app.errors import HandlerError

    try:
        result, meta = await CareerOrchestrator().execute(ctx)
    except HandlerError as exc:
        print(f"SMOKE FAILED: {exc.code}: {exc.message}")
        print("提示：真实模型调用需要能够访问 api.deepseek.com；")
        print("如本机通过代理出网，请设置 AGENT_LLM_TRUST_ENV=true 后重试。")
        return 1

    requirements = result["requirements"]
    assert meta["resultValidated"] is True
    assert meta["subgraph"] == "job"
    assert meta["provider"] == settings.deepseek_model
    assert requirements["educationMin"] == "BACHELOR", requirements
    assert set(requirements["skills"]) <= {"java", "mysql", "redis"}
    assert requirements["skills"], "应至少提取一个受控技能"
    assert requirements["quotes"]["educationMin"] in JD_TEXT

    print("SMOKE OK")
    print(f"model={meta['model']} attempts={meta['attempts']} latencyMs={meta['latencyMs']}")
    print(f"tokens(in/out)={meta['inputTokens']}/{meta['outputTokens']}")
    print(f"educationMin={requirements['educationMin']} skills={requirements['skills']}")
    print(f"graduationYear={requirements['graduationYearMin']}..{requirements['graduationYearMax']}")
    print(f"stages={' -> '.join(stages)}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))