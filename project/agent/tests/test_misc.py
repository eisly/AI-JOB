from __future__ import annotations

import logging
from datetime import datetime, timezone
from uuid import uuid4

import jwt as pyjwt
import pytest
from pydantic import ValidationError

from app.config import DEFAULT_JWT_SECRET, Settings
from app.errors import (
    ApiError,
    HandlerError,
    conflict,
    forbidden,
    internal_error,
    not_found,
    rate_limited,
    too_large,
    unauthorized,
    validation_error,
)
from app.graphs import CareerOrchestrator, _validate_result, get_operation_domain
from app.logging_config import configure_logging, mask_secret
from app.operations import validate_version_binding
from app.schemas import (
    AIRequestEnvelope,
    DocumentRef,
    EvaluateQuestion,
    InterviewTurnPayload,
    LearningEvaluatePayload,
    LearningQuestionPayload,
)
from app.security import (
    Principal,
    create_callback_token,
    create_internal_token,
    verify_internal_token,
)

from tests.factories import (
    RESUME_VERSION_ID,
    RUBRIC,
    envelope,
    evaluate_envelope,
    growth_envelope,
    interview_turn_first_envelope,
    jd_envelope,
    match_envelope,
    plan_envelope,
    resume_envelope,
)


# ---------------------------------------------------------------------------
# errors
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "factory,status,code,key",
    [
        (unauthorized, 401, 40101, "UNAUTHORIZED"),
        (forbidden, 403, 40301, "FORBIDDEN"),
        (not_found, 404, 40401, "RESOURCE_NOT_FOUND"),
        (validation_error, 400, 40001, "VALIDATION_ERROR"),
        (conflict, 409, 40901, "CONFLICT"),
        (too_large, 413, 41301, "PAYLOAD_TOO_LARGE"),
        (rate_limited, 429, 42901, "RATE_LIMITED"),
        (internal_error, 500, 50001, "INTERNAL_ERROR"),
    ],
)
def test_error_factories_carry_http_contract(factory, status, code, key):
    if factory is validation_error:
        error = factory("bad", details={"fields": []})
        assert error.details == {"fields": []}
    elif factory in (too_large, conflict, rate_limited):
        error = factory("bad")
    else:
        error = factory()
    assert isinstance(error, ApiError)
    assert error.status_code == status
    assert error.code == code
    assert error.key == key
    assert error.retryable is (factory is rate_limited)


def test_handler_error_attributes():
    error = HandlerError("MODEL_OUTPUT_INVALID", "bad", retryable=True)
    assert error.code == "MODEL_OUTPUT_INVALID"
    assert error.message == "bad"
    assert error.retryable is True
    assert HandlerError("X", "y").retryable is False


# ---------------------------------------------------------------------------
# config & logging
# ---------------------------------------------------------------------------


def test_settings_normalizes_log_level_and_validates_worker_count():
    assert Settings(log_level="debug").log_level == "DEBUG"
    with pytest.raises(ValidationError):
        Settings(worker_count=0)
    with pytest.raises(ValidationError):
        Settings(worker_count=17)


def test_settings_host_scheme_sets_and_model_name():
    settings = Settings(
        allowed_file_hosts="A.com, b.com ,",
        file_allowed_schemes="HTTPS, http",
        llm_provider="fake",
        deepseek_api_key=None,
    )
    assert settings.file_hosts == {"a.com", "b.com"}
    assert settings.file_schemes == {"https", "http"}
    assert settings.effective_llm_provider == "fake"
    assert settings.effective_model_name == "fake-llm"
    deepseek = Settings(llm_provider="deepseek", deepseek_api_key="sk")
    assert deepseek.effective_model_name == "deepseek-v4-pro"


def test_prod_runtime_security_rules():
    with pytest.raises(RuntimeError, match="AGENT_AUTH_MODE"):
        Settings(app_env="prod", auth_mode="disabled").validate_runtime_security()
    with pytest.raises(RuntimeError, match="AGENT_JWT_SECRET"):
        Settings(app_env="prod").validate_runtime_security()
    with pytest.raises(RuntimeError, match="DEEPSEEK_API_KEY"):
        Settings(
            app_env="prod",
            jwt_secret="x" * 40,
            llm_provider="deepseek",
            deepseek_api_key=None,
        ).validate_runtime_security()
    Settings(app_env="prod", jwt_secret="x" * 40, llm_provider="fake",
             speech_provider="disabled").validate_runtime_security()
    Settings(app_env="dev", jwt_secret=DEFAULT_JWT_SECRET).validate_runtime_security()


def test_mask_secret_and_configure_logging():
    assert mask_secret(None) == "<unset>"
    assert mask_secret("short") == "***"
    assert mask_secret("sk-1234567890") == "sk-1***90"
    configure_logging("INFO")
    root = logging.getLogger()
    assert len(root.handlers) == 1
    assert root.level == logging.INFO
    configure_logging("WARNING")
    assert len(root.handlers) == 1
    assert logging.getLogger("httpx").level == logging.WARNING


# ---------------------------------------------------------------------------
# schemas validators
# ---------------------------------------------------------------------------


def test_envelope_requires_timezone_aware_deadline():
    body = envelope(uuid4(), {}, {"jobVersion": 1})
    body["deadlineAt"] = "2026-01-01T00:00:00"
    with pytest.raises(ValidationError, match="deadlineAt"):
        AIRequestEnvelope.model_validate(body)


@pytest.mark.parametrize("versions", [{}, {"a": True}, {"a": 0}, {"": 1}, {"a": 1.5}])
def test_envelope_rejects_invalid_input_versions(versions):
    body = envelope(uuid4(), {}, {"jobVersion": 1})
    body["inputVersions"] = versions
    with pytest.raises(ValidationError):
        AIRequestEnvelope.model_validate(body)


def test_document_requires_text_or_url():
    with pytest.raises(ValidationError, match="downloadUrl or text"):
        DocumentRef.model_validate(
            {
                "versionId": RESUME_VERSION_ID,
                "fileName": "cv.txt",
                "mimeType": "text/plain",
                "sizeBytes": 1,
            }
        )


def test_jd_text_length_boundaries():
    body = jd_envelope(uuid4())
    body["payload"]["jdText"] = "x" * 49
    from app.schemas import JDParsePayload

    with pytest.raises(ValidationError, match="50..30000"):
        JDParsePayload.model_validate(body["payload"])


def test_answer_must_not_be_blank():
    body = evaluate_envelope(uuid4())
    body["payload"]["answer"] = "   "
    with pytest.raises(ValidationError):
        LearningEvaluatePayload.model_validate(body["payload"])


def test_reuse_only_requires_exactly_one_candidate():
    body = {
        "sessionId": str(uuid4()),
        "planItem": {"skillCode": "redis"},
        "candidateQuestions": [],
        "mode": "REUSE_ONLY",
    }
    with pytest.raises(ValidationError, match="REUSE_ONLY"):
        LearningQuestionPayload.model_validate(body)


def test_interview_turn_latest_answer_cross_checks():
    body = interview_turn_first_envelope(uuid4())
    body["payload"]["latestAnswer"] = {
        "messageId": str(uuid4()),
        "questionMessageId": str(uuid4()),
        "content": "回答",
    }
    with pytest.raises(ValidationError, match="latestAnswer requires messages"):
        InterviewTurnPayload.model_validate(body["payload"])

    from tests.factories import interview_turn_followup_envelope

    body = interview_turn_followup_envelope(uuid4())
    body["payload"]["latestAnswer"]["questionMessageId"] = body["payload"]["messages"][1]["id"]
    with pytest.raises(ValidationError, match="must reference an ASSISTANT message"):
        InterviewTurnPayload.model_validate(body["payload"])

    body = interview_turn_followup_envelope(uuid4())
    body["payload"]["latestAnswer"]["messageId"] = str(uuid4())
    with pytest.raises(ValidationError, match="must reference a USER message"):
        InterviewTurnPayload.model_validate(body["payload"])


def test_learning_evaluate_rejects_non_fixed_rubric():
    body = evaluate_envelope(uuid4())
    body["payload"]["question"]["rubric"] = {
        "version": "v",
        "dimensions": [{"key": "correctness", "weight": 1.0}],
    }
    with pytest.raises(ValidationError, match="rubric"):
        LearningEvaluatePayload.model_validate(body["payload"])


# ---------------------------------------------------------------------------
# version binding
# ---------------------------------------------------------------------------


def test_version_binding_requires_keys():
    with pytest.raises(ApiError) as caught:
        validate_version_binding("RESUME_PARSE", {}, None)
    assert caught.value.status_code == 400
    assert "resumeVersionId" in caught.value.message


def test_version_binding_resume_and_jd():
    resume = __import__("app.schemas", fromlist=["ResumeParsePayload"]).ResumeParsePayload.model_validate(
        resume_envelope(uuid4())["payload"]
    )
    validate_version_binding("RESUME_PARSE", {"resumeVersionId": RESUME_VERSION_ID}, resume)
    with pytest.raises(ApiError, match="不一致"):
        validate_version_binding("RESUME_PARSE", {"resumeVersionId": str(uuid4())}, resume)

    from app.schemas import JDParsePayload

    jd = JDParsePayload.model_validate(jd_envelope(uuid4())["payload"])
    validate_version_binding("JD_ANALYZE", {"jobVersion": 1}, jd)
    with pytest.raises(ApiError, match="不一致"):
        validate_version_binding("JD_ANALYZE", {"jobVersion": 2}, jd)


def test_version_binding_match_plan_growth_evaluate():
    from app.schemas import GrowthPayload, JobMatchPayload, LearningPlanPayload

    match = JobMatchPayload.model_validate(match_envelope(uuid4())["payload"])
    validate_version_binding("JOB_MATCH", {"profileVersion": 3}, match)
    with pytest.raises(ApiError, match="profileVersion"):
        validate_version_binding("JOB_MATCH", {"profileVersion": 9}, match)

    plan = LearningPlanPayload.model_validate(plan_envelope(uuid4())["payload"])
    validate_version_binding("LEARNING_PLAN", {"profileVersion": 3}, plan)
    with pytest.raises(ApiError, match="不一致"):
        validate_version_binding("LEARNING_PLAN", {"profileVersion": 9}, plan)

    growth = GrowthPayload.model_validate(growth_envelope(uuid4())["payload"])
    validate_version_binding("GROWTH_ANALYZE", {"profileVersion": 4}, growth)
    with pytest.raises(ApiError, match="不一致"):
        validate_version_binding("GROWTH_ANALYZE", {"profileVersion": 3}, growth)

    evaluate = LearningEvaluatePayload.model_validate(evaluate_envelope(uuid4())["payload"])
    validate_version_binding("LEARNING_EVALUATE", {"questionVersion": 1}, evaluate)
    with pytest.raises(ApiError, match="questionVersion"):
        validate_version_binding("LEARNING_EVALUATE", {"questionVersion": 2}, evaluate)


def test_version_binding_interview_turn():
    payload = InterviewTurnPayload.model_validate(interview_turn_first_envelope(uuid4())["payload"])
    validate_version_binding("INTERVIEW_TURN", {"profileVersion": 3, "interviewVersion": 1, "jobVersion": 1}, payload)
    with pytest.raises(ApiError, match="profileVersion"):
        validate_version_binding("INTERVIEW_TURN", {"profileVersion": 9, "interviewVersion": 1}, payload)
    with pytest.raises(ApiError, match="jobVersion"):
        validate_version_binding("INTERVIEW_TURN", {"profileVersion": 3, "interviewVersion": 1, "jobVersion": 2}, payload)


# ---------------------------------------------------------------------------
# security
# ---------------------------------------------------------------------------


def _security_settings(**overrides):
    base = dict(
        app_env="test",
        jwt_secret="test-internal-secret-0123456789abcdef",
        llm_provider="fake",
        callback_enabled=False,
    )
    base.update(overrides)
    return Settings(**base)


def test_principal_task_scope_checks():
    with pytest.raises(ApiError, match="未限定任务范围"):
        Principal(subject="s").assert_task("t")
    principal = Principal(subject="s", task_ids={"a"})
    with pytest.raises(ApiError, match="不包含该任务"):
        principal.assert_task("b")
    principal.assert_task("a")


def test_verify_token_expired_invalid_audience_and_scope():
    settings = _security_settings()
    expired = create_internal_token(settings, task_ids=["t"], ttl_seconds=-30)
    with pytest.raises(ApiError, match="已过期"):
        verify_internal_token(expired, settings, settings.run_scope)

    with pytest.raises(ApiError):
        verify_internal_token("not-a-token", settings, settings.run_scope)

    wrong_aud = create_internal_token(settings, task_ids=["t"], audience="wrong")
    with pytest.raises(ApiError):
        verify_internal_token(wrong_aud, settings, settings.run_scope)

    wrong_scope = create_internal_token(settings, task_ids=["t"], scope="ai:other")
    with pytest.raises(ApiError, match="缺少内部 scope"):
        verify_internal_token(wrong_scope, settings, settings.run_scope)


def test_verify_token_parses_string_task_ids():
    settings = _security_settings()
    now = datetime.now(timezone.utc)
    payload = {
        "iss": settings.jwt_issuer,
        "aud": settings.jwt_audience,
        "sub": "tester",
        "iat": int(now.timestamp()),
        "exp": int(now.timestamp()) + 60,
        "scope": settings.run_scope,
        "taskIds": "a,b",
    }
    token = pyjwt.encode(payload, settings.jwt_secret, algorithm="HS256")
    principal = verify_internal_token(token, settings, settings.run_scope)
    assert principal.task_ids == {"a", "b"}
    assert principal.subject == "tester"


def test_auth_disabled_mode_returns_synthetic_principal():
    settings = _security_settings(auth_mode="disabled")
    principal = verify_internal_token("anything", settings, settings.run_scope)
    assert principal.subject == "auth-disabled"
    assert settings.run_scope in principal.scopes


def test_callback_token_claims():
    settings = _security_settings()
    task_id = str(uuid4())
    token = create_callback_token(settings, task_id)
    raw = pyjwt.decode(
        token,
        settings.jwt_secret,
        algorithms=["HS256"],
        audience=settings.callback_audience,
        issuer=settings.callback_issuer,
    )
    assert raw["scope"] == settings.callback_scope
    assert raw["taskIds"] == [task_id]
    assert raw["sub"] == settings.callback_issuer


# ---------------------------------------------------------------------------
# graphs
# ---------------------------------------------------------------------------


def test_unknown_operation_rejected_by_domain_router():
    with pytest.raises(HandlerError) as caught:
        get_operation_domain("NOPE")
    assert caught.value.code == "UNSUPPORTED_OPERATION"
    assert caught.value.retryable is False


async def test_orchestrator_rejects_unknown_operation():
    from types import SimpleNamespace

    ctx = SimpleNamespace(run=SimpleNamespace(operation="NOPE"))
    with pytest.raises(HandlerError) as caught:
        await CareerOrchestrator().execute(ctx)
    assert caught.value.code == "UNSUPPORTED_OPERATION"


async def test_validate_result_contract_failure_and_success():
    with pytest.raises(HandlerError) as caught:
        await _validate_result({"operation": "GROWTH_ANALYZE", "result": {"suggestions": []}, "meta": {}})
    assert caught.value.code == "RESULT_CONTRACT_INVALID"

    final = await _validate_result(
        {
            "operation": "GROWTH_ANALYZE",
            "result": {"suggestions": ["a"], "citedEvidenceIds": []},
            "meta": {"x": 1},
        }
    )
    assert final["result"]["suggestions"] == ["a"]
    assert final["meta"]["x"] == 1
    assert final["meta"]["subgraph"] == "learning"
    assert final["meta"]["resultValidated"] is True
    assert final["meta"]["graph"] == "career-orchestrator-v1"