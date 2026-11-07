from __future__ import annotations

from datetime import datetime, timezone

import pytest

from app.search.models import VERIFICATION_REASONS, VERIFICATION_STATUSES, SourcePosting
from app.search.verification import (
    STATUS_CLOSED,
    STATUS_CONFIRMED,
    STATUS_PROBABLE,
    STATUS_UNKNOWN,
    closed,
    detail_page,
    failure,
    legacy_status,
    official_list,
    snippet_only,
    summarize,
)


def posting(status: str = "UNKNOWN", reason: str | None = None) -> SourcePosting:
    return SourcePosting(
        source_key="web", source_name="example.com", source_type="PUBLIC_SEARCH",
        source_url="https://jobs.example.com/1", title="Java 后端", company_name="示例公司",
        jd_text="负责 Java 服务端开发", verification_status=status, verification_reason=reason,
    )


def test_verification_reason_catalog_is_complete() -> None:
    assert set(VERIFICATION_STATUSES) == {"ACTIVE_CONFIRMED", "ACTIVE_PROBABLE", "UNKNOWN", "CLOSED"}
    for reason in ("PAGE_ACCESS_FAILED", "HTTP_403", "HTTP_404", "TIMEOUT", "DETAIL_NOT_FOUND",
                   "APPLY_LINK_MISSING", "OFFICIAL_CLOSED", "EXPIRED", "INSUFFICIENT_EVIDENCE",
                   "ACTIVE_EVIDENCE_FOUND"):
        assert reason in VERIFICATION_REASONS


@pytest.mark.parametrize("reason", ["HTTP_403", "HTTP_404", "TIMEOUT", "PAGE_ACCESS_FAILED", "DETAIL_NOT_FOUND"])
def test_access_failures_are_unknown_never_closed(reason: str) -> None:
    record = failure("https://jobs.example.com/1", reason)
    assert record.status == STATUS_UNKNOWN
    assert record.status != STATUS_CLOSED


def test_closed_requires_explicit_reason() -> None:
    assert closed("https://x.example.com/1", "OFFICIAL_CLOSED").status == STATUS_CLOSED
    assert closed("https://x.example.com/1", "EXPIRED").status == STATUS_CLOSED
    with pytest.raises(ValueError):
        closed("https://x.example.com/1", "HTTP_404")


def test_four_states_map_to_legacy_and_keep_history() -> None:
    item = posting()
    official_list(item.source_url, checked_at=datetime(2026, 11, 7, tzinfo=timezone.utc)).apply(item)
    assert (item.verification_status, item.recruitment_status) == (STATUS_CONFIRMED, "OPEN")

    detail_page("https://jobs.example.com/1", apply_link=True).apply(item)
    assert (item.verification_status, item.recruitment_status) == (STATUS_PROBABLE, "PROBABLE")

    failure("https://jobs.example.com/1", "HTTP_403").apply(item)
    assert (item.verification_status, item.recruitment_status) == (STATUS_UNKNOWN, "UNKNOWN")
    assert item.recruitment_evidence is None
    assert len(item.verification_history) == 3


def test_summary_rates_and_unknown_retention() -> None:
    items = [posting(STATUS_CONFIRMED), posting(STATUS_PROBABLE), posting(STATUS_UNKNOWN), posting(STATUS_CLOSED)]
    summary = summarize(items)
    assert summary.counts == {"ACTIVE_CONFIRMED": 1, "ACTIVE_PROBABLE": 1, "UNKNOWN": 1, "CLOSED": 1}
    assert summary.rates()["officialVerificationRate"] == 0.25
    assert summary.rates()["unknownRate"] == 0.25
    # UNKNOWN 仍是有效返回项，只有 CLOSED 才是明确关闭。
    assert [posting(status).is_closed for status in (STATUS_CONFIRMED, STATUS_PROBABLE, STATUS_UNKNOWN)] == [False] * 3
    assert posting(STATUS_CLOSED).is_closed is True


def test_legacy_status_mapping() -> None:
    assert legacy_status(STATUS_CONFIRMED) == "OPEN"
    assert legacy_status(STATUS_PROBABLE) == "PROBABLE"
    assert legacy_status(STATUS_UNKNOWN) == "UNKNOWN"
    assert legacy_status(STATUS_CLOSED) == "CLOSED"


def test_snippet_only_is_insufficient_evidence() -> None:
    record = snippet_only("https://jobs.example.com/1")
    assert (record.status, record.reason, record.evidence) == (
        STATUS_UNKNOWN, "INSUFFICIENT_EVIDENCE", "SEARCH_SNIPPET"
    )