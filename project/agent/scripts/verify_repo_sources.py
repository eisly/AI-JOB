from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import Settings  # noqa: E402

settings = Settings()
CHECKS = [
    ("GITHUB", "https://api.github.com/search/repositories?q=spring+boot&sort=stars&order=desc&per_page=1",
     settings.github_token),
    ("GITEE", "https://gitee.com/api/v5/search/repositories?q=spring&sort=stars_count&order=desc&per_page=1",
     settings.gitee_token),
    ("GITCODE", "https://api.gitcode.com/api/v5/search/repositories?q=spring&sort=stars_count&order=desc&per_page=1",
     settings.gitcode_token),
]


def main() -> None:
    for platform, url, token in CHECKS:
        headers = {"Authorization": f"Bearer {token}"} if platform == "GITHUB" and token else None
        params = {"access_token": token} if platform == "GITCODE" and token else None
        print(f"{platform} token={'configured' if token else 'missing'}")
        try:
            response = httpx.get(url, headers=headers, params=params, timeout=12.0, trust_env=False, follow_redirects=True)
            print(f"{platform} status={response.status_code} bytes={len(response.content)}")
            if response.status_code == 200:
                payload = response.json()
                rows = payload.get("items") if isinstance(payload, dict) else payload
                if rows:
                    row = rows[0]
                    print(json.dumps({
                        "full_name": row.get("full_name"),
                        "url": row.get("html_url") or row.get("web_url"),
                        "description": str(row.get("description") or "")[:80],
                        "license": row.get("license"),
                    }, ensure_ascii=False))
        except Exception as exc:  # noqa: BLE001 - 探测脚本需要记录所有异常类型
            print(f"{platform} error={type(exc).__name__} {str(exc)[:200]}")


async def module_check() -> None:
    from app.project_sources import search_project_repositories

    result = await search_project_repositories(settings, ["JAVA", "SPRING_BOOT"], None, ["GITHUB"])
    print("MODULE GITHUB terms=" + ",".join(result.terms))
    print("MODULE GITHUB source_runs=" + json.dumps(result.source_runs, ensure_ascii=False))
    for hit in result.candidates:
        print(json.dumps({
            "platform": hit.platform,
            "path": hit.path,
            "url": hit.url,
            "license": hit.license,
            "readmeUrl": hit.readme_url,
            "matchedSkillCodes": hit.matched_skill_codes,
            "descriptionChars": len(hit.description),
        }, ensure_ascii=False))


if __name__ == "__main__":
    main()
    asyncio.run(module_check())