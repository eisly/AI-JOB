from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Iterable

from .models import VERIFICATION_REASONS, VERIFICATION_STATUSES

# 四态语义：
# - ACTIVE_CONFIRMED: 官方列表或官方接口直接证明岗位当前在招
# - ACTIVE_PROBABLE : 具体岗位详情页可访问且存在该岗位，但未完成官方核验
# - UNKNOWN         : 页面无法访问、信息不足或无法判断；不得当作已下线
# - CLOSED          : 来源页面明确声明岗位关闭/下线，或投递截止日期已过期
STATUS_CONFIRMED = "ACTIVE_CONFIRMED"
STATUS_PROBABLE = "ACTIVE_PROBABLE"
STATUS_UNKNOWN = "UNKNOWN"
STATUS_CLOSED = "CLOSED"

EVIDENCE_OFFICIAL_LIST = "OFFICIAL_LIST"
EVIDENCE_DETAIL_APPLY_LINK = "DETAIL_APPLY_LINK"
EVIDENCE_DETAIL_TEXT = "DETAIL_TEXT"
EVIDENCE_SEARCH_SNIPPET = "SEARCH_SNIPPET"


@dataclass(frozen=True)
class VerificationRecord:
    status: str
    reason: str
    evidence: str | None
    checked_at: datetime
    source_url: str
    http_status: int | None = None
    detail: str = ""

    def __post_init__(self) -> None:
        if self.status not in VERIFICATION_STATUSES:
            raise ValueError(f"未知核验状态: {self.status}")
        if self.reason not in VERIFICATION_REASONS:
            raise ValueError(f"未知核验原因: {self.reason}")

    def as_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "reason": self.reason,
            "evidence": self.evidence,
            "checkedAt": self.checked_at.isoformat(),
            "sourceUrl": self.source_url,
            "httpStatus": self.http_status,
            "detail": self.detail[:300],
        }

    def apply(self, posting: Any) -> Any:
        posting.verification_status = self.status
        posting.verification_reason = self.reason
        posting.verification_evidence = self.evidence
        posting.verification_checked_at = self.checked_at
        posting.verification_source_url = self.source_url
        posting.verification_http_status = self.http_status
        posting.recruitment_status = legacy_status(self.status)
        # UNKNOWN 不携带“证据”，避免下游把搜索摘要误当作在招证据。
        posting.recruitment_evidence = None if self.status == STATUS_UNKNOWN else self.evidence
        posting.verification_history = [
            *(getattr(posting, "verification_history", None) or []),
            self.as_dict(),
        ][-10:]
        return posting


def legacy_status(status: str) -> str:
    if status == STATUS_CONFIRMED:
        return "OPEN"
    if status == STATUS_PROBABLE:
        return "PROBABLE"
    if status == STATUS_CLOSED:
        return "CLOSED"
    return "UNKNOWN"


def now() -> datetime:
    return datetime.now(timezone.utc)


def official_list(
    source_url: str,
    *,
    checked_at: datetime | None = None,
    detail: str = "官方在招列表包含该岗位",
) -> VerificationRecord:
    return VerificationRecord(
        status=STATUS_CONFIRMED,
        reason="ACTIVE_EVIDENCE_FOUND",
        evidence=EVIDENCE_OFFICIAL_LIST,
        checked_at=checked_at or now(),
        source_url=source_url,
        http_status=200,
        detail=detail,
    )


def detail_page(
    source_url: str,
    *,
    checked_at: datetime | None = None,
    apply_link: bool = False,
    http_status: int = 200,
    detail: str = "",
) -> VerificationRecord:
    return VerificationRecord(
        status=STATUS_PROBABLE,
        reason="ACTIVE_EVIDENCE_FOUND",
        evidence=EVIDENCE_DETAIL_APPLY_LINK if apply_link else EVIDENCE_DETAIL_TEXT,
        checked_at=checked_at or now(),
        source_url=source_url,
        http_status=http_status,
        detail=detail or ("详情页存在岗位专属投递入口" if apply_link else "详情页包含该岗位正文"),
    )


def snippet_only(source_url: str, *, checked_at: datetime | None = None) -> VerificationRecord:
    return VerificationRecord(
        status=STATUS_UNKNOWN,
        reason="INSUFFICIENT_EVIDENCE",
        evidence=EVIDENCE_SEARCH_SNIPPET,
        checked_at=checked_at or now(),
        source_url=source_url,
        detail="仅获得搜索结果摘要，未打开岗位详情页",
    )


def closed(source_url: str, reason: str, *, checked_at: datetime | None = None, http_status: int | None = None,
           detail: str = "") -> VerificationRecord:
    if reason not in {"OFFICIAL_CLOSED", "EXPIRED"}:
        raise ValueError("CLOSED 只允许 OFFICIAL_CLOSED 或 EXPIRED")
    return VerificationRecord(
        status=STATUS_CLOSED,
        reason=reason,
        evidence=reason,
        checked_at=checked_at or now(),
        source_url=source_url,
        http_status=http_status,
        detail=detail,
    )


def failure(source_url: str, reason: str, *, checked_at: datetime | None = None,
            http_status: int | None = None, detail: str = "") -> VerificationRecord:
    """访问异常一律返回 UNKNOWN，绝不作为岗位关闭证据。"""
    if reason not in {"PAGE_ACCESS_FAILED", "HTTP_403", "HTTP_404", "TIMEOUT", "DETAIL_NOT_FOUND",
                      "APPLY_LINK_MISSING", "INSUFFICIENT_EVIDENCE"}:
        raise ValueError(f"不支持的失败原因: {reason}")
    return VerificationRecord(
        status=STATUS_UNKNOWN,
        reason=reason,
        evidence=None,
        checked_at=checked_at or now(),
        source_url=source_url,
        http_status=http_status,
        detail=detail,
    )


@dataclass
class VerificationSummary:
    counts: dict[str, int] = field(default_factory=lambda: {status: 0 for status in VERIFICATION_STATUSES})
    reasons: dict[str, int] = field(default_factory=dict)
    records: int = 0

    @property
    def total(self) -> int:
        return sum(self.counts.values())

    @property
    def confirmed(self) -> int:
        return self.counts.get(STATUS_CONFIRMED, 0)

    @property
    def probable(self) -> int:
        return self.counts.get(STATUS_PROBABLE, 0)

    @property
    def unknown(self) -> int:
        return self.counts.get(STATUS_UNKNOWN, 0)

    @property
    def closed(self) -> int:
        return self.counts.get(STATUS_CLOSED, 0)

    def rates(self) -> dict[str, float]:
        total = self.total
        if not total:
            return {"officialVerificationRate": 0.0, "unknownRate": 0.0, "closedRate": 0.0}
        return {
            "officialVerificationRate": round(self.confirmed / total, 4),
            "unknownRate": round(self.unknown / total, 4),
            "closedRate": round(self.closed / total, 4),
        }

    def as_dict(self) -> dict[str, Any]:
        return {
            "counts": dict(self.counts),
            "reasons": dict(self.reasons),
            "records": self.records,
            "rates": self.rates(),
        }


def summarize(postings: Iterable[Any]) -> VerificationSummary:
    summary = VerificationSummary()
    for posting in postings:
        status = getattr(posting, "verification_status", STATUS_UNKNOWN)
        summary.counts[status] = summary.counts.get(status, 0) + 1
        reason = getattr(posting, "verification_reason", None)
        if reason:
            summary.reasons[reason] = summary.reasons.get(reason, 0) + 1
        summary.records += len(getattr(posting, "verification_history", None) or [])
    return summary