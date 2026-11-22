"""Prevent acceptance tooling from passing skipped or incomplete scenarios."""
import importlib.util
import json
from pathlib import Path
import pytest

spec = importlib.util.spec_from_file_location(
    "budget_acceptance", Path(__file__).resolve().parents[1] / "scripts/interview_budget_acceptance.py"
)
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)


class EmptyInterviewClient:
    def detail(self, iid):
        return {"status": "WAITING_ANSWER", "questionCountRule": "TOTAL_ASKED",
                "questionLimit": 5, "askedQuestionCount": 1, "version": 2}

    def call(self, method, path, **kwargs):
        if method == "POST":
            assert kwargs["key"]  # /end requires normal idempotency protection.
            return {"id": "report-task"}
        return {"completionType": "NO_VALID_ANSWER", "answeredAskCount": 0}

    def wait_task(self, task_id):
        pass

    def wait_completed(self, iid):
        return {"status": "COMPLETED", "askedQuestionCount": 1}


def saved_result(directory):
    return json.loads(next(directory.glob("interview_budget_*.json")).read_text(encoding="utf-8"))


def test_full_scenario_cannot_skip_last_answer_and_claim_success(tmp_path):
    client = EmptyInterviewClient()
    client.detail = lambda _: {"status": "WAITING_ANSWER", "questionCountRule": "TOTAL_ASKED",
                               "questionLimit": 5, "askedQuestionCount": 5}
    with pytest.raises(AssertionError, match="requires five slots and first question"):
        acceptance.run(client, "test", "full", tmp_path)
    assert saved_result(tmp_path)["passed"] is False


def test_no_answer_end_keeps_existing_report_semantics(tmp_path):
    result = acceptance.run(EmptyInterviewClient(), "test", "no-answer", tmp_path)
    assert result["passed"] is True
    assert saved_result(tmp_path)["passed"] is True


def test_report_timeout_is_persisted_as_failure(tmp_path):
    client = EmptyInterviewClient()
    def timeout(_):
        raise AssertionError("Interview/report did not reach COMPLETED")
    client.wait_completed = timeout
    with pytest.raises(AssertionError, match="did not reach COMPLETED"):
        acceptance.run(client, "test", "no-answer", tmp_path)
    result = saved_result(tmp_path)
    assert result["passed"] is False
    assert "did not reach COMPLETED" in result["failure"]
