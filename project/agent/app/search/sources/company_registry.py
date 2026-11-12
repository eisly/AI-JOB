from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any


@dataclass(frozen=True)
class CompanySource:
    """企业招聘来源注册项。platform 决定使用哪个适配器，未知平台回退到网页搜索。"""

    key: str
    company: str
    platform: str
    token: str = ""
    official_domain: str = ""
    enabled: bool = True


@dataclass
class CompanyCollection:
    key: str
    last_collected_at: datetime | None = None
    last_status: str = ""
    last_count: int = 0

    def as_dict(self) -> dict[str, Any]:
        return {
            "key": self.key,
            "lastCollectedAt": self.last_collected_at.isoformat() if self.last_collected_at else None,
            "lastStatus": self.last_status,
            "lastCount": self.last_count,
        }


class CompanyRegistry:
    """企业招聘来源注册表（配置驱动，不重复建 Java 侧 job_source 表）。

    Agent 不直接写业务库；最后采集时间通过 Java 的 job_source.last_collected_at 持久化。
    """

    def __init__(self, entries: list[CompanySource] | None = None) -> None:
        self.entries: dict[str, CompanySource] = {item.key: item for item in entries or []}
        self.collections: dict[str, CompanyCollection] = {}

    @classmethod
    def from_settings(cls, settings: Any) -> "CompanyRegistry":
        entries: list[CompanySource] = []
        for platform, attribute in (("greenhouse", "job_search_greenhouse_boards"),
                                     ("lever", "job_search_lever_boards")):
            raw = str(getattr(settings, attribute, "") or "").strip()
            for item in raw.split(","):
                part = item.strip()
                if not part:
                    continue
                token, _, company = part.partition(":")
                token = token.strip()
                if not token:
                    continue
                entries.append(CompanySource(
                    key=f"{platform}:{token}",
                    company=(company.strip() or token.title()),
                    platform=platform,
                    token=token,
                ))
        import json
        sites = json.loads(getattr(settings, "job_search_company_sites", "{}") or "{}")
        for company, domain in sites.items():
            entries.append(CompanySource(key="official:" + domain, company=company,
                                         platform="company_official", official_domain=domain))
        return cls(entries)

    def for_platform(self, platform: str) -> list[CompanySource]:
        return [item for item in self.entries.values() if item.enabled and item.platform == platform]

    def record(self, key: str, status: str, count: int) -> None:
        self.collections[key] = CompanyCollection(
            key=key, last_collected_at=datetime.now(timezone.utc), last_status=status, last_count=count
        )

    def report(self) -> dict[str, Any]:
        return {
            "companies": [item.as_dict() for item in self.collections.values()],
            "registered": [
                {
                    "key": item.key,
                    "company": item.company,
                    "platform": item.platform,
                }
                for item in self.entries.values()
            ],
        }