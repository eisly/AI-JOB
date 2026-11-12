from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.config import Settings
from app.llm import create_llm
from app.search.models import SearchFilters
from app.search.providers.tavily import TavilyProvider
from app.search.sources.base import SourceContext
from app.search.sources.web import _extract_postings, _merge_hits


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="使用一个真实 Tavily 页面批次验证岗位提取模型耗时")
    parser.add_argument("--query", default="杭州 后端开发 实习 招聘 职位")
    parser.add_argument("--keyword", default="后端开发")
    parser.add_argument("--city", default="330100")
    parser.add_argument("--pages", type=int, default=5)
    parser.add_argument("--timeout", type=float, default=90.0)
    parser.add_argument("--output", default="data/diagnosis_phase1_llm_probe_20261112.json")
    return parser.parse_args()


async def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    args = parse_args()
    settings = Settings()
    inner_llm = create_llm(settings)
    llm_events = []

    class DiagnosticLLM:
        @property
        def name(self):
            return inner_llm.name

        async def complete(self, request):
            started = time.monotonic()
            try:
                response = await inner_llm.complete(request)
            except BaseException as exc:
                llm_events.append({
                    "status": "ERROR",
                    "errorType": type(exc).__name__,
                    "durationMs": int((time.monotonic() - started) * 1000),
                    "systemChars": len(request.system_prompt),
                    "userChars": len(request.user_prompt),
                })
                raise
            llm_events.append({
                "status": "OK",
                "durationMs": int((time.monotonic() - started) * 1000),
                "systemChars": len(request.system_prompt),
                "userChars": len(request.user_prompt),
                "responseChars": len(response.text),
                "inputTokens": response.input_tokens,
                "outputTokens": response.output_tokens,
            })
            return response

    summary = {
        "provider": settings.effective_llm_provider,
        "model": settings.effective_model_name,
        "query": args.query,
        "requestedPages": args.pages,
        "timeoutSeconds": args.timeout,
        "llmDiagnostics": llm_events,
    }
    started = time.monotonic()
    try:
        async with httpx.AsyncClient(
            timeout=httpx.Timeout(12.0, connect=5.0),
            limits=httpx.Limits(max_connections=4, max_keepalive_connections=2),
            trust_env=settings.job_search_trust_env,
            follow_redirects=False,
        ) as client:
            search = await TavilyProvider().search(
                client,
                args.query,
                settings=settings,
                limit=args.pages,
                timeout=12.0,
                retries=0,
            )
            links = _merge_hits([search.hits])
            selected = list(links.items())[:args.pages]
            summary["search"] = {
                "status": search.status,
                "httpStatus": search.http_status,
                "hitCount": len(search.hits),
                "usablePages": len(selected),
                "durationMs": search.duration_ms,
            }
            summary["pages"] = [
                {"url": url, "title": page["title"], "bodyChars": len(page["body"])}
                for url, page in selected
            ]
            filters = SearchFilters(
                keyword=args.keyword,
                cities=(args.city,),
                candidate_types=("INTERNSHIP",),
                include_unknown_size=True,
                max_results=30,
            )
            context = SourceContext(
                filters=filters,
                keyword=args.keyword,
                limit=30,
                settings=settings,
                client=client,
                extra={"llm": DiagnosticLLM()},
            )
            batch = [
                (url, {**page, "pageIndex": index})
                for index, (url, page) in enumerate(selected)
            ]
            postings, diagnostics = await asyncio.wait_for(
                _extract_postings(context, batch),
                timeout=args.timeout,
            )
            summary["extraction"] = {
                "accepted": len(postings),
                "diagnostics": diagnostics,
                "items": [
                    {
                        "title": item.title,
                        "company": item.company_name,
                        "city": item.city_name,
                        "candidateType": item.candidate_type,
                        "url": item.source_url,
                    }
                    for item in postings
                ],
            }
    except BaseException as exc:
        summary["probeError"] = type(exc).__name__
    summary["elapsedMs"] = int((time.monotonic() - started) * 1000)
    target = Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if "probeError" not in summary else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))