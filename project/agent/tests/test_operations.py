from __future__ import annotations

from uuid import UUID, uuid4

import pytest

from tests.factories import OPERATION_CASES, wait_for_run


@pytest.mark.parametrize(
    "path,operation,factory,required_keys",
    OPERATION_CASES,
    ids=[case[1] for case in OPERATION_CASES],
)
async def test_operation_success_flow(
    client,
    settings,
    fake_llm,
    headers_for,
    path,
    operation,
    factory,
    required_keys,
):
    task_id = uuid4()
    body = factory(task_id)
    response = await client.post(
        path,
        json=body,
        headers={**headers_for(str(task_id)), "Idempotency-Key": str(uuid4())},
    )
    assert response.status_code == 202, response.text
    accepted = response.json()
    assert accepted["code"] == 202
    assert accepted["data"]["operation"] == operation
    assert accepted["data"]["status"] == "QUEUED"

    run_id = accepted["data"]["runId"]
    data = await wait_for_run(client, headers_for, run_id, str(task_id))
    assert data["status"] == "SUCCEEDED", data
    assert data["error"] is None
    result = data["result"]
    assert isinstance(result, dict)
    assert required_keys.issubset(result.keys())
    assert data["lastEventId"] >= 3
    assert len(fake_llm.calls) >= 1


async def test_resume_result_is_candidate_fact(client, fake_llm, headers_for):
    task_id = uuid4()
    response = await client.post(
        "/internal/ai/v1/resumes/parse",
        json=__import__("tests.factories", fromlist=["resume_envelope"]).resume_envelope(task_id),
        headers={**headers_for(str(task_id)), "Idempotency-Key": str(uuid4())},
    )
    run_id = response.json()["data"]["runId"]
    data = await wait_for_run(client, headers_for, run_id, str(task_id))
    result = data["result"]
    assert result["facts"]["education"] == "BACHELOR"
    assert result["facts"]["graduationYear"] == 2027
    assert all(item["confirmed"] is False for item in result["facts"]["skills"])
    assert len(result["textHash"]) == 64
    assert "实习未计入全职经验" in result["facts"]["warnings"]


async def test_jd_has_no_eligibility_verdict(client, fake_llm, headers_for):
    task_id = uuid4()
    from tests.factories import jd_envelope

    response = await client.post(
        "/internal/ai/v1/jobs/analyze",
        json=jd_envelope(task_id),
        headers={**headers_for(str(task_id)), "Idempotency-Key": str(uuid4())},
    )
    data = await wait_for_run(client, headers_for, response.json()["data"]["runId"], str(task_id))
    result = data["result"]
    assert "status" not in result
    assert result["requirements"]["educationMin"] == "BACHELOR"
    assert result["requirements"]["educationMin"] is not None
    assert result["requirements"]["quotes"]["educationMin"]


async def test_match_preserves_candidate_set_and_recomputes_gaps(client, fake_llm, headers_for):
    task_id = uuid4()
    from tests.factories import JOB_ID, match_envelope

    response = await client.post(
        "/internal/ai/v1/jobs/match",
        json=match_envelope(task_id),
        headers={**headers_for(str(task_id)), "Idempotency-Key": str(uuid4())},
    )
    data = await wait_for_run(client, headers_for, response.json()["data"]["runId"], str(task_id))
    items = data["result"]["items"]
    assert len(items) == 1
    assert items[0]["jobId"] == JOB_ID
    assert items[0]["semanticVersion"] == "semantic-v1"
    assert 0 <= items[0]["semanticScore"] <= 1
    gaps = {item["skillCode"]: item for item in items[0]["gaps"]}
    assert gaps["java"]["targetScore"] == 70
    assert gaps["java"]["currentScore"] is None
    assert gaps["java"]["gap"] is None
    assert gaps["java"]["evidenceStatus"] == "MISSING"


async def test_plan_respects_budget_and_allowed_skills(client, fake_llm, headers_for):
    task_id = uuid4()
    from tests.factories import plan_envelope

    response = await client.post(
        "/internal/ai/v1/learning/plans",
        json=plan_envelope(task_id),
        headers={**headers_for(str(task_id)), "Idempotency-Key": str(uuid4())},
    )
    data = await wait_for_run(client, headers_for, response.json()["data"]["runId"], str(task_id))
    result = data["result"]
    assert 1 <= len(result["items"]) <= 20
    assert all(item["skillCode"] == "redis" for item in result["items"])
    assert sum(item["estimatedMinutes"] for item in result["items"]) <= 30 * 7
    assert all(item["kind"] in {"READING", "PRACTICE", "INTERVIEW"} for item in result["items"])


async def test_question_generated_with_hidden_rubric(client, fake_llm, headers_for):
    task_id = uuid4()
    from tests.factories import question_envelope

    response = await client.post(
        "/internal/ai/v1/learning/questions",
        json=question_envelope(task_id),
        headers={**headers_for(str(task_id)), "Idempotency-Key": str(uuid4())},
    )
    data = await wait_for_run(client, headers_for, response.json()["data"]["runId"], str(task_id))
    result = data["result"]
    assert result["sourceType"] == "GENERATED"
    assert result["sourceQuestionId"] is None
    assert result["difficulty"] == "MEDIUM"
    assert result["skillCodes"] == ["redis"]
    assert len(result["rubric"]["dimensions"]) == 3
    assert result["referenceAnswer"]


async def test_question_reuses_candidate_without_model_call(client, fake_llm, headers_for):
    task_id = uuid4()
    from tests.factories import QUESTION_ID, question_candidate, question_envelope

    body = question_envelope(task_id, candidates=[question_candidate()])
    response = await client.post(
        "/internal/ai/v1/learning/questions",
        json=body,
        headers={**headers_for(str(task_id)), "Idempotency-Key": str(uuid4())},
    )
    data = await wait_for_run(client, headers_for, response.json()["data"]["runId"], str(task_id))
    result = data["result"]
    assert result["sourceType"] == "CURATED"
    assert result["sourceQuestionId"] == QUESTION_ID
    assert result["stem"] == "如何处理缓存穿透？"
    assert fake_llm.calls == []


async def test_question_reuse_only_must_return_same_question(client, fake_llm, headers_for):
    task_id = uuid4()
    from tests.factories import QUESTION_ID, question_candidate, question_envelope

    body = question_envelope(task_id, mode="REUSE_ONLY", candidates=[question_candidate()])
    response = await client.post(
        "/internal/ai/v1/learning/questions",
        json=body,
        headers={**headers_for(str(task_id)), "Idempotency-Key": str(uuid4())},
    )
    data = await wait_for_run(client, headers_for, response.json()["data"]["runId"], str(task_id))
    assert data["result"]["sourceQuestionId"] == QUESTION_ID
    assert data["result"]["referenceAnswer"] == "参数校验、布隆过滤器与空值缓存"
    assert fake_llm.calls == []


async def test_evaluate_recomputes_weighted_score(client, fake_llm, headers_for):
    task_id = uuid4()
    from tests.factories import evaluate_envelope

    response = await client.post(
        "/internal/ai/v1/learning/evaluate",
        json=evaluate_envelope(task_id),
        headers={**headers_for(str(task_id)), "Idempotency-Key": str(uuid4())},
    )
    data = await wait_for_run(client, headers_for, response.json()["data"]["runId"], str(task_id))
    evaluation = data["result"]["evaluation"]
    assert evaluation["evidenceStatus"] == "VALID"
    assert evaluation["overallScore"] is not None
    weighted = sum(item["score"] * item["weight"] for item in evaluation["dimensions"])
    assert abs(evaluation["overallScore"] - round(weighted, 1)) <= 0.05
    scores = data["result"]["skillScores"]
    assert len(scores) == 1
    assert scores[0]["skillCode"] == "redis"
    assert scores[0]["sourceId"] == "50000000-0000-4000-8000-000000000004"


async def test_evaluate_invalid_answer_creates_no_scoring_evidence(client, fake_llm, headers_for):
    task_id = uuid4()
    from tests.factories import evaluate_envelope

    body = evaluate_envelope(task_id, answer="不知道")
    response = await client.post(
        "/internal/ai/v1/learning/evaluate",
        json=body,
        headers={**headers_for(str(task_id)), "Idempotency-Key": str(uuid4())},
    )
    data = await wait_for_run(client, headers_for, response.json()["data"]["runId"], str(task_id))
    evaluation = data["result"]["evaluation"]
    assert evaluation["evidenceStatus"] == "INVALID"
    assert evaluation["overallScore"] is None
    assert data["result"]["skillScores"] == []


async def test_growth_cites_only_input_evidence(client, fake_llm, headers_for):
    task_id = uuid4()
    from tests.factories import EVIDENCE_ID, growth_envelope

    response = await client.post(
        "/internal/ai/v1/growth/analyze",
        json=growth_envelope(task_id),
        headers={**headers_for(str(task_id)), "Idempotency-Key": str(uuid4())},
    )
    data = await wait_for_run(client, headers_for, response.json()["data"]["runId"], str(task_id))
    result = data["result"]
    assert 1 <= len(result["suggestions"]) <= 5
    assert result["citedEvidenceIds"] == [EVIDENCE_ID]
    assert all("score" not in item for item in result["suggestions"])


async def test_interview_turn_follow_up(client, fake_llm, headers_for):
    task_id = uuid4()
    from tests.factories import interview_turn_followup_envelope

    response = await client.post(
        "/internal/ai/v1/interviews/turn",
        json=interview_turn_followup_envelope(task_id),
        headers={**headers_for(str(task_id)), "Idempotency-Key": str(uuid4())},
    )
    data = await wait_for_run(client, headers_for, response.json()["data"]["runId"], str(task_id))
    result = data["result"]
    assert result["action"] == "FOLLOW_UP"
    assert result["question"]["questionNo"] == 1
    assert result["question"]["followUpNo"] == 1
    assert result["answerEvaluation"]["evaluation"]["evidenceStatus"] == "VALID"
    assert result["answerEvaluation"]["skillScores"][0]["sourceId"] == "60000000-0000-4000-8000-000000000003"


async def test_interview_invalid_dimensions_are_repaired(client, fake_llm, headers_for):
    from app.fake_model import fake_llm_from_payload
    from tests.factories import interview_turn_followup_envelope

    def respond(request):
        output = fake_llm_from_payload(request)
        if len(fake_llm.calls) == 1:
            output["answerEvaluation"]["evaluation"]["dimensions"] = output["answerEvaluation"]["evaluation"]["dimensions"][:2]
        return output

    fake_llm.responder = respond
    task_id = uuid4()
    response = await client.post(
        "/internal/ai/v1/interviews/turn",
        json=interview_turn_followup_envelope(task_id),
        headers={**headers_for(str(task_id)), "Idempotency-Key": str(uuid4())},
    )
    assert response.status_code == 202
    data = await wait_for_run(client, headers_for, response.json()["data"]["runId"], str(task_id))
    assert data["status"] == "SUCCEEDED", data
    assert len(fake_llm.calls) == 2
    assert "缺少：expression" in fake_llm.calls[1].user_prompt
    assert len(data["result"]["answerEvaluation"]["evaluation"]["dimensions"]) == 3


async def test_interview_report_no_valid_answer(client, fake_llm, headers_for):
    task_id = uuid4()
    from tests.factories import interview_report_envelope

    body = interview_report_envelope(task_id, valid=False)
    body["payload"]["messages"].pop()
    response = await client.post(
        "/internal/ai/v1/interviews/report",
        json=body,
        headers={**headers_for(str(task_id)), "Idempotency-Key": str(uuid4())},
    )
    data = await wait_for_run(client, headers_for, response.json()["data"]["runId"], str(task_id))
    result = data["result"]
    assert result["completionType"] == "NO_VALID_ANSWER"
    assert result["answeredQuestionCount"] == 0
    assert result["reviewedAnswerCount"] == 0
    assert result["evaluation"]["overallScore"] is None
    assert result["evaluation"]["dimensions"] == []
    assert fake_llm.calls == []


async def test_interview_report_includes_unscored_followup(client, fake_llm, headers_for):
    task_id = uuid4()
    from tests.factories import interview_report_envelope

    body = interview_report_envelope(task_id)
    payload = body["payload"]
    question, answer = payload["messages"]
    followup_id = str(uuid4())
    payload["messages"].extend([
        {**question, "id": str(uuid4()), "seq": 3, "followUpNo": 1, "content": "说说缓存如何失效。"},
        {**answer, "id": followup_id, "seq": 4, "followUpNo": 1, "content": "不知道"},
        {**question, "id": str(uuid4()), "seq": 5, "questionNo": 2, "content": "如何应对缓存穿透？"},
    ])
    payload["turnEvaluations"].append({"messageId": followup_id, "evidenceStatus": "INVALID"})
    def respond(_request):
        if len(fake_llm.calls) == 1:
            return {"improvements": ["补充缓存失效策略"]}
        return {
            "unscoredAnswerScores": {followup_id: {"correctness": 0, "completeness": 0, "expression": 0}},
            "improvements": ["可从 TTL 与主动失效两种策略回答。"],
        }

    fake_llm.responder = respond

    response = await client.post(
        "/internal/ai/v1/interviews/report",
        json=body,
        headers={**headers_for(str(task_id)), "Idempotency-Key": str(uuid4())},
    )
    data = await wait_for_run(client, headers_for, response.json()["data"]["runId"], str(task_id))
    result = data["result"]
    assert result["evaluation"]["overallScore"] == 35.5
    assert [entry["score"] for entry in result["evaluation"]["dimensions"]] == [37.5, 32.5, 35]
    assert result["answeredQuestionCount"] == 1
    assert result["reviewedAnswerCount"] == 2
    assert result["completionType"] == "EARLY_END"
    assert len(fake_llm.calls) == 2


async def test_interview_report_scores_a_single_unscored_answer(client, fake_llm, headers_for):
    from tests.factories import interview_report_envelope

    task_id = uuid4()
    body = interview_report_envelope(task_id, valid=False)
    body["payload"]["messages"][-1]["content"] = "不知道"
    response = await client.post(
        "/internal/ai/v1/interviews/report",
        json=body,
        headers={**headers_for(str(task_id)), "Idempotency-Key": str(uuid4())},
    )
    data = await wait_for_run(client, headers_for, response.json()["data"]["runId"], str(task_id))
    result = data["result"]
    assert result["evaluation"]["overallScore"] == 0
    assert result["evaluation"]["evidenceStatus"] == "VALID"
    assert len(result["evaluation"]["dimensions"]) == 3
    assert result["evaluation"]["improvements"]
    assert result["answeredQuestionCount"] == result["reviewedAnswerCount"] == 1
    assert result["completionType"] == "EARLY_END"
