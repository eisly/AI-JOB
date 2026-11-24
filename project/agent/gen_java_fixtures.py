"""重新生成 Java 真实数据 fixtures（上轮清理误删 repro_v2_output.json 后的恢复路径）：
用失败任务 9deb692b 同一快照走 ResumeOptimizeHandler.execute 真实生产路径（真实模型），
输出直接落 .local-fixtures/optimize-result.json + optimize-snapshot.json（gitignore 不入库）。"""
from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

AGENT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(AGENT_DIR))

from app.config import Settings  # noqa: E402
from app.files import FileFetcher  # noqa: E402
from app.handlers import ExecutionContext, ResumeOptimizeHandler  # noqa: E402
from app.llm import DeepSeekClient  # noqa: E402
from app.schemas import ResumeOptimizePayload  # noqa: E402

ROOT = AGENT_DIR.parent.parent
SNAPSHOT_FILE = ROOT / "dbg-snapshot.json"
FIXTURES = AGENT_DIR.parent / "backend" / ".local-fixtures"


async def main() -> int:
    snapshot = json.loads(SNAPSHOT_FILE.read_text(encoding="utf-8"))
    payload = ResumeOptimizePayload.model_validate({
        "jobId": snapshot["jobId"],
        "jobVersion": snapshot["jobVersion"],
        "jdText": snapshot["jdText"],
        "diagnosis": snapshot["diagnosis"],
        "document": snapshot["document"],
    })
    settings = Settings()

    async def noop(name: str, progress: int | None = None) -> None:
        return None

    ctx = ExecutionContext(
        run=None, payload=payload, settings=settings, llm=DeepSeekClient(settings),
        files=FileFetcher(settings), emit_stage=noop, emit_reasoning=None,
    )
    result_dict, meta = await ResumeOptimizeHandler().execute(ctx)

    FIXTURES.mkdir(exist_ok=True)
    (FIXTURES / "optimize-snapshot.json").write_text(
        json.dumps({"document": snapshot["document"]}, ensure_ascii=False), encoding="utf-8")
    (FIXTURES / "optimize-result.json").write_text(
        json.dumps(result_dict, ensure_ascii=False, indent=1), encoding="utf-8")

    quotes = sum(len(s.get("sourceQuotes", [])) for s in result_dict["sections"])
    print(f"FIXTURES_OK attempts={meta.get('attempts')} tokens={meta.get('outputTokens')} "
          f"sections={len(result_dict['sections'])} quotes={quotes}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))