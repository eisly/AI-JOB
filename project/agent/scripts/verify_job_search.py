from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.config import Settings
from app.llm import create_llm
from app.search.pipeline import JobSearchPipeline, SearchRuntime
from app.schemas import JobSearchPayload
from app.skills.tools import validate_result_contract


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="使用真实搜索与模型执行一次岗位搜索验证")
    parser.add_argument("--keyword", default="Java")
    parser.add_argument("--cities", default="440300", help="逗号分隔的 6 位城市编码")
    parser.add_argument("--candidate-types", default="EXPERIENCED", help="逗号分隔：INTERNSHIP,CAMPUS,EXPERIENCED")
    parser.add_argument("--sources", default="tencent,greenhouse,liepin,boss,zhaopin")
    parser.add_argument("--max-results", type=int, default=10)
    parser.add_argument("--target-role", default="JAVA_BACKEND")
    parser.add_argument("--output", default="", help="将完整结果写入指定 JSON 文件")
    parser.add_argument("--replay-output", default="", help="保存合成画像和完整 Agent 结果，用于 Java 隔离回放")
    parser.add_argument("--phase-one-budget", action="store_true", help="固定 60 次、150 秒、3 轮阶段一全局预算")
    parser.add_argument("--diagnose-llm", action="store_true", help="记录模型调用耗时与异常类型，不记录提示词")
    return parser.parse_args()


async def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    args = parse_args()
    settings = Settings()
    if args.phase_one_budget:
        settings = settings.model_copy(update={"job_search_max_api_calls": 60,
                                               "job_search_max_elapsed_seconds": 150,
                                               "job_search_max_rounds": 3})
    payload = {
        "profile": {
            "userId": "10000000-0000-4000-8000-00000000d001",
            "profileVersion": 1,
            "targetRole": args.target_role,
            "candidateType": args.candidate_types.split(",")[0],
            "cities": args.cities.split(","),
            "minMonthlySalaryCents": None,
            "maxMonthlySalaryCents": None,
            "facts": {
                "education": "BACHELOR",
                "graduationYear": 2022,
                "experienceMonths": 36,
                "major": "计算机科学与技术",
                "skills": [
                    {"skillCode": "JAVA", "claim": "项目使用 Java", "quote": "虚构简历", "location": "项目", "confirmed": True},
                    {"skillCode": "SPRING_BOOT", "claim": "项目使用 Spring Boot", "quote": "虚构简历", "location": "项目", "confirmed": True},
                ],
                "warnings": [],
            },
            "activeResumeVersionId": "10000000-0000-4000-8000-00000000a001",
        },
        "filters": {
            "keyword": args.keyword,
            "cities": args.cities.split(","),
            "candidateTypes": args.candidate_types.split(","),
            "companySizes": [],
            "includeUnknownSize": True,
            "maxResults": args.max_results,
        },
        "sources": args.sources.split(","),
        "skillCatalog": [
            {"code": "JAVA", "name": "Java"},
            {"code": "SPRING_BOOT", "name": "Spring Boot"},
            {"code": "MYSQL", "name": "MySQL"},
            {"code": "REDIS", "name": "Redis"},
            {"code": "SYSTEM_DESIGN", "name": "系统设计"},
        ],
        "limits": {"perSource": 10, "maxTotal": 30, "concurrency": 4, "sourceTimeoutSeconds": 12},
    }

    async def emit_stage(name: str, progress: int | None) -> None:
        print(f"stage {name} {progress}", flush=True)

    llm = create_llm(settings)
    llm_diagnostics = []
    if args.diagnose_llm:
        inner_llm = llm

        class DiagnosticLLM:
            @property
            def name(self):
                return inner_llm.name

            async def complete(self, request):
                started = time.monotonic()
                event = {
                    "operation": request.operation,
                    "schema": request.schema_hint,
                    "systemChars": len(request.system_prompt),
                    "userChars": len(request.user_prompt),
                }
                try:
                    response = await inner_llm.complete(request)
                except BaseException as exc:
                    event.update({
                        "status": "ERROR",
                        "errorType": type(exc).__name__,
                        "httpStatus": getattr(exc, "http_status", None),
                        "durationMs": int((time.monotonic() - started) * 1000),
                    })
                    llm_diagnostics.append(event)
                    print("llm diagnostic " + json.dumps(event, ensure_ascii=False), flush=True)
                    raise
                event.update({
                    "status": "OK",
                    "durationMs": int((time.monotonic() - started) * 1000),
                    "responseChars": len(response.text),
                    "inputTokens": response.input_tokens,
                    "outputTokens": response.output_tokens,
                })
                llm_diagnostics.append(event)
                print("llm diagnostic " + json.dumps(event, ensure_ascii=False), flush=True)
                return response

        llm = DiagnosticLLM()
    http_attempts = {}

    async def record_request(request):
        key = request.headers.get("host", request.url.host)
        http_attempts[key] = http_attempts.get(key, 0) + 1

    async with httpx.AsyncClient(
        timeout=httpx.Timeout(12.0, connect=5.0),
        limits=httpx.Limits(max_connections=8, max_keepalive_connections=4),
        trust_env=settings.job_search_trust_env,
        follow_redirects=False,
        event_hooks={"request": [record_request]},
    ) as client:
        runtime = SearchRuntime(
            settings=settings,
            llm=llm,
            emit_stage=emit_stage,
            client=client,
            profile=payload["profile"],
            payload=payload,
        )
        result = await JobSearchPipeline(runtime).run()

    validated = validate_result_contract("JOB_SEARCH", result)
    JobSearchPayload.model_validate(payload)
    if args.replay_output:
        replay = Path(args.replay_output)
        replay.parent.mkdir(parents=True, exist_ok=True)
        replay.write_text(json.dumps({"payload": payload, "result": validated}, ensure_ascii=False), encoding="utf-8")

    summary = {
        "provider": settings.effective_llm_provider,
        "model": settings.effective_model_name,
        "now": datetime.now(timezone.utc).isoformat(),
        "sourceStatus": result["sources"],
        "queryPlan": result["queryPlan"],
        "warnings": result["warnings"],
        "dedupe": result["dedupe"],
        "metrics": result.get("metrics"),
        "meta": result.get("meta"),
        "llmDiagnostics": llm_diagnostics,
        "httpAttempts": http_attempts,
        "itemCount": len(result["items"]),
        "items": [
            {
                "title": item["title"],
                "company": item["companyName"],
                "city": item["cityName"],
                "salary": [item["salaryMinMonthlyCents"], item["salaryMaxMonthlyCents"]],
                "experience": item["experienceText"],
                "education": item["educationMin"],
                "companySize": item["companySize"],
                "source": item["sourceKey"],
                "searchProviders": item.get("searchProviders"),
                "publishedAt": item["publishedAt"],
                "fetchedAt": item["fetchedAt"],
                "verification": item["verification"],
                "verificationStatus": item.get("verificationStatus"),
                "verificationReason": item.get("verificationReason"),
                "warnings": item["warnings"],
                "url": item["sourceUrl"],
                "semanticScore": item["semanticScore"],
            }
            for item in result["items"][:30]
        ],
    }
    if args.output:
        target = Path(args.output)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        compact = {
            "provider": summary["provider"],
            "itemCount": summary["itemCount"],
            "warnings": summary["warnings"],
            "sources": [
                {
                    "key": item["key"],
                    "status": item["status"],
                    "count": item["count"],
                    "verificationCounts": item["verificationCounts"],
                    "message": item["message"],
                }
                for item in summary["sourceStatus"]
            ],
            "dedupe": summary["dedupe"],
            "funnel": summary["metrics"]["funnel"],
            "rates": summary["metrics"]["rates"],
            "budget": summary["meta"]["budget"],
            "llmDiagnostics": llm_diagnostics,
            "output": str(target),
        }
        print(json.dumps(compact, ensure_ascii=False, indent=2))
    else:
        print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if result["items"] else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
