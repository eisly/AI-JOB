from __future__ import annotations

import asyncio
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.config import Settings
from app.llm import create_llm
from app.search.pipeline import JobSearchPipeline, SearchRuntime


async def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    settings = Settings()
    payload = {
        "profile": {
            "userId": "10000000-0000-4000-8000-00000000d001",
            "profileVersion": 1,
            "targetRole": "JAVA_BACKEND",
            "candidateType": "EXPERIENCED",
            "cities": ["440300"],
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
            "activeResumeVersionId": "10000000-0000-4000-8000-00000000r001",
        },
        "filters": {
            "keyword": "Java",
            "cities": ["440300"],
            "candidateTypes": ["EXPERIENCED"],
            "companySizes": [],
            "includeUnknownSize": True,
            "maxResults": 10,
        },
        "sources": ["tencent", "greenhouse", "liepin", "boss", "zhaopin"],
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
    async with httpx.AsyncClient(
        timeout=httpx.Timeout(12.0, connect=5.0),
        limits=httpx.Limits(max_connections=8, max_keepalive_connections=4),
        trust_env=settings.job_search_trust_env,
        follow_redirects=False,
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

    summary = {
        "provider": settings.effective_llm_provider,
        "model": settings.effective_model_name,
        "now": datetime.now(timezone.utc).isoformat(),
        "sourceStatus": result["sources"],
        "queryPlan": result["queryPlan"],
        "warnings": result["warnings"],
        "dedupe": result["dedupe"],
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
                "publishedAt": item["publishedAt"],
                "fetchedAt": item["fetchedAt"],
                "verification": item["verification"],
                "warnings": item["warnings"],
                "url": item["sourceUrl"],
                "semanticScore": item["semanticScore"],
            }
            for item in result["items"][:5]
        ],
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if result["items"] else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))