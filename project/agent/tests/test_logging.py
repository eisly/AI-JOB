from __future__ import annotations

import io
import logging

from app.logging_config import (
    bind_log_context,
    configure_logging,
    current_log_context,
    log_context,
    reset_log_context,
    short_id,
    summarize_payload,
)


def test_short_id_truncates_and_handles_empty() -> None:
    assert short_id(None) == "-"
    assert short_id("") == "-"
    assert short_id("abc") == "abc"
    assert short_id("abcdefghijklmnop", 8) == "abcdefgh"


def test_summarize_payload_hides_business_text() -> None:
    jd_text = "这是一段很长的岗位描述" * 20
    summary = summarize_payload(
        {
            "jdText": jd_text,
            "document": {"url": "https://example.test/resume.pdf", "fileId": "f1"},
            "skillCatalog": [{"code": "JAVA"}],
            "salary": 100,
            "remote": True,
            "note": None,
        }
    )

    assert f"jdText:str({len(jd_text)})" in summary
    assert "岗位描述" not in summary
    assert "document:obj(url,fileId)" in summary
    assert "skillCatalog:list(1)" in summary
    assert "salary:100" in summary
    assert "remote:True" in summary
    assert "note:null" in summary


def test_summarize_payload_reports_overflow() -> None:
    payload = {f"k{index}": index for index in range(14)}
    summary = summarize_payload(payload, max_keys=10)
    assert summary.endswith("...(+4)]")


def test_log_context_injects_fields_into_output() -> None:
    stream = io.StringIO()
    configure_logging("INFO", stream=stream)
    try:
        with log_context(trace="trace-1", task="task-1", run="run-1", op="RESUME_PARSE"):
            logging.getLogger("agent.test").info("hello")
    finally:
        configure_logging("WARNING")

    output = stream.getvalue()
    assert "hello" in output
    assert "trace=trace-1" in output
    assert "task=task-1" in output
    assert "run=run-1" in output
    assert "op=RESUME_PARSE" in output


def test_log_context_restores_after_exit() -> None:
    with log_context(task="task-1"):
        assert current_log_context()["task"] == "task-1"
    assert "task" not in current_log_context()


def test_bind_and_reset_context() -> None:
    token = bind_log_context(run="run-1", op="JOB_SEARCH")
    try:
        assert current_log_context()["run"] == "run-1"
        assert current_log_context()["op"] == "JOB_SEARCH"
    finally:
        reset_log_context(token)
    assert "run" not in current_log_context()