from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
from typing import Any, Callable
from uuid import UUID, uuid4

USER_ID = "10000000-0000-4000-8000-000000000001"
RESUME_VERSION_ID = "20000000-0000-4000-8000-000000000002"
JOB_ID = "30000000-0000-4000-8000-000000000001"
MATCH_ITEM_ID = "40000000-0000-4000-8000-000000000002"
SESSION_ID = "50000000-0000-4000-8000-000000000002"
QUESTION_ID = "50000000-0000-4000-8000-000000000003"
ANSWER_ID = "50000000-0000-4000-8000-000000000004"
INTERVIEW_ID = "60000000-0000-4000-8000-000000000001"
ASSISTANT_MESSAGE_ID = "60000000-0000-4000-8000-000000000002"
USER_MESSAGE_ID = "60000000-0000-4000-8000-000000000003"
EVIDENCE_ID = "50000000-0000-4000-8000-000000000004"

RESUME_TEXT = (
    "教育背景：本科，2027年毕业，计算机科学与技术专业。\n"
    "实习经历：使用Redis实现缓存，参与Java接口开发。\n"
    "技能：了解MySQL索引优化，参与单元测试与性能优化。"
)

JD_TEXT = (
    "本科及以上学历，2026至2027届毕业生；熟悉Java、MySQL与Redis，"
    "了解并发编程和缓存设计；参与业务接口开发、单元测试与性能优化。"
)

RUBRIC = {
    "version": "technical-answer-v1",
    "dimensions": [
        {"key": "correctness", "weight": 0.5},
        {"key": "completeness", "weight": 0.3},
        {"key": "expression", "weight": 0.2},
    ],
}


def envelope(
    task_id: UUID,
    payload: dict[str, Any],
    input_versions: dict[str, Any],
    *,
    deadline_seconds: int = 30,
    user_id: str = USER_ID,
) -> dict[str, Any]:
    deadline = datetime.now(timezone.utc) + timedelta(seconds=deadline_seconds)
    return {
        "taskId": str(task_id),
        "userId": user_id,
        "inputVersions": input_versions,
        "deadlineAt": deadline.isoformat().replace("+00:00", "Z"),
        "payload": payload,
    }


def resume_envelope(task_id: UUID, *, size_bytes: int | None = None) -> dict[str, Any]:
    text = RESUME_TEXT
    document = {
        "versionId": RESUME_VERSION_ID,
        "fileName": "Java后端简历.txt",
        "mimeType": "text/plain",
        "sizeBytes": size_bytes if size_bytes is not None else len(text.encode("utf-8")),
        "text": text,
    }
    return envelope(
        task_id,
        {
            "document": document,
            "skillCatalog": [
                {"code": "redis", "name": "Redis"},
                {"code": "java", "name": "Java"},
                {"code": "mysql", "name": "MySQL"},
            ],
        },
        {"resumeVersionId": RESUME_VERSION_ID},
    )


def jd_envelope(task_id: UUID, *, jd_text: str = JD_TEXT) -> dict[str, Any]:
    return envelope(
        task_id,
        {
            "jobId": JOB_ID,
            "jobVersion": 1,
            "jdText": jd_text,
            "skillCatalog": [
                {"code": "java", "name": "Java"},
                {"code": "mysql", "name": "MySQL"},
                {"code": "redis", "name": "Redis"},
            ],
        },
        {"jobVersion": 1},
    )


def match_envelope(task_id: UUID) -> dict[str, Any]:
    return envelope(
        task_id,
        {
            "profile": {
                "userId": USER_ID,
                "profileVersion": 3,
                "targetRole": "JAVA_BACKEND",
                "candidateType": "CAMPUS",
                "cities": ["330100"],
                "minMonthlySalaryCents": 1200000,
                "maxMonthlySalaryCents": 1800000,
                "facts": {
                    "education": "BACHELOR",
                    "graduationYear": 2027,
                    "experienceMonths": 0,
                    "major": "计算机科学与技术",
                    "skills": [
                        {
                            "skillCode": "redis",
                            "claim": "了解缓存设计",
                            "quote": "使用Redis实现缓存",
                            "location": "page:1;paragraph:5",
                            "confirmed": True,
                        }
                    ],
                    "warnings": ["实习未计入全职经验"],
                },
                "activeResumeVersionId": RESUME_VERSION_ID,
                "updatedAt": "2026-10-27T02:30:00Z",
            },
            "skillSnapshot": [{"skillCode": "redis", "score": None, "sampleCount": 0}],
            "resumeQuotes": ["使用Redis实现缓存"],
            "candidates": [
                {
                    "jobId": JOB_ID,
                    "jobVersion": 1,
                    "title": "Java后端开发",
                    "jdText": "熟悉Java、MySQL和Redis",
                    "personalOwned": True,
                    "requirements": {
                        "educationMin": "BACHELOR",
                        "graduationYearMin": 2026,
                        "graduationYearMax": 2027,
                        "experienceMonthsMin": 0,
                        "skills": ["java", "mysql", "redis"],
                        "requiredSkillLevels": {"java": 70, "mysql": 65, "redis": 60},
                        "quotes": {"educationMin": "本科及以上"},
                    },
                }
            ],
        },
        {"profileVersion": 3, "resumeVersionId": RESUME_VERSION_ID},
    )


def plan_envelope(task_id: UUID) -> dict[str, Any]:
    return envelope(
        task_id,
        {
            "matchItem": {
                "id": MATCH_ITEM_ID,
                "jobId": JOB_ID,
                "jobVersion": 1,
                "title": "Java 后端开发",
                "eligibility": {"status": "PASS", "ruleVersion": "eligibility-v1", "checks": []},
                "matchScore": 86,
                "scoreVersion": "match-v1-trial",
                "components": [],
                "gaps": [
                    {
                        "skillCode": "redis",
                        "currentScore": None,
                        "targetScore": 60,
                        "gap": None,
                        "evidenceStatus": "MISSING",
                        "evidenceIds": [],
                        "suggestion": "补充Redis缓存设计场景训练",
                    }
                ],
                "reasons": ["方向一致，Redis实战证据不足"],
            },
            "profileVersion": 3,
            "dailyMinutes": 30,
            "days": 7,
            "allowedSkillCodes": ["redis"],
            "knowledgeRefs": [],
        },
        {"profileVersion": 3},
    )


def question_envelope(
    task_id: UUID,
    *,
    mode: str = "SELECT_OR_GENERATE",
    candidates: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    return envelope(
        task_id,
        {
            "sessionId": SESSION_ID,
            "planItem": {"skillCode": "redis", "difficulty": "MEDIUM"},
            "candidateQuestions": candidates or [],
            "excludedQuestionIds": [],
            "knowledgeRefs": [],
            "mode": mode,
        },
        {"profileVersion": 3},
    )


def question_candidate() -> dict[str, Any]:
    return {
        "id": QUESTION_ID,
        "version": 1,
        "stem": "如何处理缓存穿透？",
        "skillCodes": ["redis"],
        "referenceAnswer": "参数校验、布隆过滤器与空值缓存",
        "rubric": RUBRIC,
        "difficulty": "MEDIUM",
        "type": "TEXT_SCENARIO",
    }


def evaluate_envelope(task_id: UUID, *, answer: str | None = None) -> dict[str, Any]:
    return envelope(
        task_id,
        {
            "answerId": ANSWER_ID,
            "question": {
                "id": QUESTION_ID,
                "questionVersion": 1,
                "stem": "如何处理缓存穿透？",
                "skillCodes": ["redis"],
                "referenceAnswer": "参数校验、布隆过滤器与空值缓存",
                "rubric": RUBRIC,
            },
            "answer": answer
            if answer is not None
            else "先校验参数，再用布隆过滤器和短期空值缓存，并补充监控与降级策略。",
        },
        {"profileVersion": 3, "questionVersion": 1},
    )


def growth_envelope(task_id: UUID) -> dict[str, Any]:
    return envelope(
        task_id,
        {
            "profileVersion": 4,
            "skills": [
                {
                    "skillCode": "redis",
                    "score": 70,
                    "sampleCount": 1,
                    "evidenceStatus": "LIMITED",
                    "formulaVersion": "ability-v1-trial",
                }
            ],
            "evidence": [
                {
                    "id": EVIDENCE_ID,
                    "skillCode": "redis",
                    "sourceType": "LEARNING_ANSWER",
                    "sourceId": EVIDENCE_ID,
                    "score": 70,
                    "confidence": 0.8,
                    "status": "VALID",
                    "rubricVersion": "technical-answer-v1",
                }
            ],
            "targetJobRequirements": {
                "educationMin": "BACHELOR",
                "skills": ["redis"],
                "requiredSkillLevels": {"redis": 60},
            },
        },
        {"profileVersion": 4},
    )


def _profile() -> dict[str, Any]:
    return {
        "userId": USER_ID,
        "profileVersion": 3,
        "targetRole": "JAVA_BACKEND",
        "candidateType": "CAMPUS",
        "cities": ["330100"],
        "facts": {
            "education": "BACHELOR",
            "graduationYear": 2027,
            "experienceMonths": 0,
            "major": "计算机科学与技术",
            "skills": [
                {
                    "skillCode": "redis",
                    "claim": "了解缓存设计",
                    "quote": "使用Redis实现缓存",
                    "location": "page:1;paragraph:5",
                    "confirmed": True,
                }
            ],
            "warnings": [],
        },
        "activeResumeVersionId": RESUME_VERSION_ID,
    }


def interview_turn_first_envelope(task_id: UUID) -> dict[str, Any]:
    return envelope(
        task_id,
        {
            "interviewId": INTERVIEW_ID,
            "expectedVersion": 1,
            "questionLimit": 5,
            "maxFollowUps": 2,
            "profile": _profile(),
            "job": {"id": JOB_ID, "jobVersion": 1, "jdText": "Java后端，熟悉Redis"},
            "messages": [],
            "latestAnswer": None,
            "questionRubricVersion": "technical-answer-v1",
        },
        {"profileVersion": 3, "resumeVersionId": RESUME_VERSION_ID, "jobVersion": 1, "interviewVersion": 1},
    )


def _turn_messages(answer: str) -> list[dict[str, Any]]:
    return [
        {
            "id": ASSISTANT_MESSAGE_ID,
            "seq": 1,
            "role": "ASSISTANT",
            "content": "请介绍你在项目中使用缓存的场景。",
            "questionNo": 1,
            "followUpNo": 0,
            "skillCodes": ["redis"],
        },
        {
            "id": USER_MESSAGE_ID,
            "seq": 2,
            "role": "USER",
            "content": answer,
            "questionNo": 1,
            "followUpNo": 0,
        },
    ]


def interview_turn_followup_envelope(task_id: UUID, *, answer: str | None = None) -> dict[str, Any]:
    content = answer if answer is not None else "我用Redis做过缓存，解决了热点查询。"
    return envelope(
        task_id,
        {
            "interviewId": INTERVIEW_ID,
            "expectedVersion": 2,
            "questionLimit": 5,
            "maxFollowUps": 2,
            "profile": _profile(),
            "job": {"id": JOB_ID, "jobVersion": 1, "jdText": "Java后端，熟悉Redis"},
            "messages": _turn_messages(content),
            "latestAnswer": {
                "messageId": USER_MESSAGE_ID,
                "questionMessageId": ASSISTANT_MESSAGE_ID,
                "content": content,
            },
            "questionRubricVersion": "technical-answer-v1",
        },
        {"profileVersion": 3, "resumeVersionId": RESUME_VERSION_ID, "jobVersion": 1, "interviewVersion": 2},
    )


def interview_report_envelope(task_id: UUID, *, valid: bool = True) -> dict[str, Any]:
    evaluations = (
        [
            {
                "messageId": USER_MESSAGE_ID,
                "overallScore": 70,
                "dimensions": [
                    {"key": "correctness", "score": 75, "weight": 0.5, "comment": "正确"},
                    {"key": "completeness", "score": 65, "weight": 0.3, "comment": "需补充"},
                    {"key": "expression", "score": 70, "weight": 0.2, "comment": "清晰"},
                ],
                "evidenceStatus": "VALID",
            }
        ]
        if valid
        else []
    )
    return envelope(
        task_id,
        {
            "interviewId": INTERVIEW_ID,
            "completionType": "EARLY_END",
            "messages": _turn_messages("我用Redis做过缓存，解决了热点查询。"),
            "turnEvaluations": evaluations,
            "skillSnapshot": [{"skillCode": "redis", "score": 70, "sampleCount": 1}],
        },
        {"interviewVersion": 3},
    )


def resume_diagnose_envelope(task_id: UUID) -> dict[str, Any]:
    document = resume_envelope(task_id)["payload"]["document"]
    return envelope(
        task_id,
        {"document": document, "profileVersion": 3},
        {"resumeVersionId": RESUME_VERSION_ID, "profileVersion": 3},
    )


def resume_optimize_envelope(task_id: UUID) -> dict[str, Any]:
    document = resume_envelope(task_id)["payload"]["document"]
    return envelope(
        task_id,
        {
            "document": document,
            "jobId": JOB_ID,
            "jobVersion": 1,
            "jdText": JD_TEXT,
            "diagnosis": {
                "items": [
                    {
                        "priority": "SUGGESTION",
                        "title": "经历结果可进一步明确",
                        "issue": "结果信息不够明确",
                        "quote": "使用Redis实现缓存",
                        "rationale": "原文描述了做法但未单独说明结果",
                        "suggestion": "仅补充本人可核实的实际结果",
                    }
                ]
            },
        },
        {"resumeVersionId": RESUME_VERSION_ID, "jobVersion": 1},
    )


def project_recommend_envelope(task_id: UUID) -> dict[str, Any]:
    return envelope(
        task_id,
        {
            "profile": _profile(),
            "allowedSkillCodes": ["redis", "java"],
            "matchItem": plan_envelope(task_id)["payload"]["matchItem"],
            "difficulty": "BEGINNER",
            "maxDurationWeeks": 4,
        },
        {"profileVersion": 3, "jobVersion": 1},
    )


def project_plan_envelope(task_id: UUID) -> dict[str, Any]:
    return envelope(
        task_id,
        {
            "recommendation": {
                "title": "Redis 核心功能实战",
                "summary": "完成一个可演示和可自测的 Redis 学习项目",
                "skillCodes": ["redis"],
                "difficulty": "BEGINNER",
                "estimatedWeeks": 2,
                "reason": "依据确认画像和技能差距形成学习建议",
                "deliverables": ["项目说明与核心功能演示", "实现源码与自测记录"],
            },
            "weeklyHours": 8,
        },
        {"profileVersion": 3},
    )


OPERATION_CASES: list[tuple[str, str, Callable[[UUID], dict[str, Any]], set[str]]] = [
    ("/internal/ai/v1/resumes/parse", "RESUME_PARSE", resume_envelope, {"facts", "textHash", "unmappedSkills"}),
    ("/internal/ai/v1/resumes/diagnose", "RESUME_DIAGNOSE", resume_diagnose_envelope, {"items", "assessment"}),
    ("/internal/ai/v1/resumes/optimize", "RESUME_OPTIMIZE", resume_optimize_envelope, {"title", "sections"}),
    ("/internal/ai/v1/jobs/analyze", "JD_ANALYZE", jd_envelope, {"requirements", "salary", "warnings"}),
    ("/internal/ai/v1/jobs/match", "JOB_MATCH", match_envelope, {"items"}),
    ("/internal/ai/v1/learning/plans", "LEARNING_PLAN", plan_envelope, {"title", "items"}),
    ("/internal/ai/v1/learning/questions", "LEARNING_QUESTION", question_envelope, {"stem", "rubric", "referenceAnswer"}),
    ("/internal/ai/v1/learning/evaluate", "LEARNING_EVALUATE", evaluate_envelope, {"evaluation", "skillScores"}),
    ("/internal/ai/v1/interviews/turn", "INTERVIEW_TURN", interview_turn_first_envelope, {"action", "question", "answerEvaluation"}),
    ("/internal/ai/v1/interviews/report", "INTERVIEW_REPORT", interview_report_envelope, {"evaluation", "gaps", "answeredQuestionCount", "completionType"}),
    ("/internal/ai/v1/growth/analyze", "GROWTH_ANALYZE", growth_envelope, {"suggestions", "citedEvidenceIds"}),
    ("/internal/ai/v1/projects/recommend", "PROJECT_RECOMMEND", project_recommend_envelope, {"items"}),
    ("/internal/ai/v1/projects/plans", "PROJECT_PLAN", project_plan_envelope, {"title", "summary", "milestones"}),
]


def make_run_record(**overrides: Any):
    from app.storage import RunRecord, utcnow

    now = utcnow()
    data: dict[str, Any] = dict(
        run_id=str(uuid4()),
        task_id=str(uuid4()),
        user_id=USER_ID,
        operation="JD_ANALYZE",
        idempotency_key=str(uuid4()),
        request_digest="digest",
        input_versions={"jobVersion": 1},
        payload={"jdText": "示例"},
        status="QUEUED",
        attempt=1,
        result=None,
        error=None,
        model_meta=None,
        last_event_seq=0,
        callback_status="PENDING",
        callback_attempts=0,
        callback_next_at=None,
        callback_error=None,
        deadline_at=now + timedelta(seconds=60),
        created_at=now,
        updated_at=now,
        finished_at=None,
        purge_at=now + timedelta(days=1),
        trace_id="trace-test",
    )
    data.update(overrides)
    return RunRecord(**data)


async def wait_for_run(client, headers_for, run_id: str, task_id: str, timeout: float = 8.0) -> dict[str, Any]:
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    last: dict[str, Any] = {}
    while loop.time() < deadline:
        response = await client.get(f"/internal/ai/v1/runs/{run_id}", headers=headers_for(task_id))
        assert response.status_code == 200, response.text
        last = response.json()["data"]
        if last["status"] in {"SUCCEEDED", "FAILED", "CANCELLED"}:
            return last
        await asyncio.sleep(0.02)
    raise AssertionError(f"run did not finish: {last}")
