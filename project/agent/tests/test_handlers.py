from __future__ import annotations

from types import SimpleNamespace
from uuid import uuid4

import pytest

from app.errors import HandlerValidationError
from app.handlers import (
    FIXED_RUBRIC_WEIGHTS,
    GrowthAnalyzeHandler,
    InterviewReportHandler,
    InterviewTurnHandler,
    JDAnalyzeHandler,
    JobMatchHandler,
    LearningPlanHandler,
    LearningQuestionHandler,
    ResumeParseHandler,
    _build_evaluation,
    _build_gaps,
    _evidence_status,
    check_fixed_rubric,
    dedupe,
    fixed_rubric,
    half_up,
    norm_text,
    quote_in_text,
    utcnow_iso,
)
from app.schemas import (
    AIGrowthResult,
    AIJDResult,
    AIPlanResult,
    AIQuestionResult,
    Citation,
    EvaluateModelOutput,
    EvaluationDimension,
    EvaluationModelOutput,
    InterviewReportPayload,
    InterviewTurnPayload,
    JDParsePayload,
    JobMatchPayload,
    LearningPlanPayload,
    LearningQuestionPayload,
    MatchItemModelOutput,
    MatchModelOutput,
    ParseModelOutput,
    PlanItem,
    ReportModelOutput,
    Requirement,
    ResumeEvidenceFact,
    ResumeFacts,
    ResumeParsePayload,
    Rubric,
    RubricDimension,
    Salary,
    SkillFact,
    SkillScoreDraft,
    TurnModelOutput,
    TurnQuestionModelOutput,
)

from tests.factories import (
    EVIDENCE_ID,
    JOB_ID,
    QUESTION_ID,
    RESUME_TEXT,
    evaluate_envelope,
    growth_envelope,
    interview_report_envelope,
    interview_turn_followup_envelope,
    jd_envelope,
    match_envelope,
    plan_envelope,
    question_candidate,
    question_envelope,
    resume_envelope,
)


def _ctx(payload):
    return SimpleNamespace(payload=payload)


def _evaluation_output(status="VALID", score=80.0):
    return EvaluateModelOutput(
        evaluation=EvaluationModelOutput(
            dimensions=[
                EvaluationDimension(key="correctness", score=score, weight=0.5, comment="c"),
                EvaluationDimension(key="completeness", score=score, weight=0.3, comment="c"),
                EvaluationDimension(key="expression", score=score, weight=0.2, comment="c"),
            ],
            strengths=["s"],
            improvements=["i"],
            referenceAnswer="参考",
            evidenceStatus=status,
        ),
        skillScores=[],
    )


# ---------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------


def test_numeric_and_text_helpers():
    assert half_up(2.35, 1) == 2.4
    assert half_up(75.5, 1) == 75.5
    assert dedupe([" a ", "", "a", "b"], limit=1) == ["a"]
    assert dedupe(["a", "b", "a"]) == ["a", "b"]
    assert norm_text(" A\nB\tc ") == "abc"
    assert quote_in_text("本科及以上", "本科及以上学历") is True
    assert quote_in_text("", "任何文本") is False
    assert utcnow_iso().endswith("Z")


def test_fixed_rubric_and_weight_validation():
    rubric = fixed_rubric("technical-answer-v1")
    assert sum(item.weight for item in rubric.dimensions) == pytest.approx(1.0)
    check_fixed_rubric(rubric)

    with pytest.raises(HandlerValidationError, match="三个维度"):
        check_fixed_rubric(Rubric(version="v", dimensions=[RubricDimension(key="correctness", weight=1.0)]))

    skewed = Rubric(
        version="v",
        dimensions=[
            RubricDimension(key="correctness", weight=0.4),
            RubricDimension(key="completeness", weight=0.4),
            RubricDimension(key="expression", weight=0.2),
        ],
    )
    with pytest.raises(HandlerValidationError, match="权重必须为 0.5"):
        check_fixed_rubric(skewed)


def test_evidence_status_thresholds():
    assert _evidence_status(None) == "MISSING"
    assert _evidence_status(SimpleNamespace(score=None, sample_count=5)) == "MISSING"
    assert _evidence_status(SimpleNamespace(score=50, sample_count=0)) == "MISSING"
    assert _evidence_status(SimpleNamespace(score=50, sample_count=1)) == "LIMITED"
    assert _evidence_status(SimpleNamespace(score=50, sample_count=3)) == "SUFFICIENT"


# ---------------------------------------------------------------------------
# RESUME_PARSE
# ---------------------------------------------------------------------------


def _resume_case():
    payload = ResumeParsePayload.model_validate(resume_envelope(uuid4())["payload"])
    facts = ResumeFacts(
        education="BACHELOR",
        graduationYear=2027,
        experienceMonths=0,
        skills=[
            SkillFact(skillCode="java", claim="c", quote="参与Java接口开发", location="text:2"),
            SkillFact(skillCode="java", claim="c2", quote="参与Java接口开发", location="text:2"),
        ],
        warnings=["w", "w"],
    )
    output = ParseModelOutput(facts=facts, unmappedSkills=["x" * 101, "保留", "保留"])
    prepared = {"document_text": RESUME_TEXT, "text_hash": "hash"}
    return payload, output, prepared


def test_resume_validate_dedupes_and_strips_unmapped():
    payload, output, prepared = _resume_case()
    result = ResumeParseHandler().validate_business(_ctx(payload), output, prepared, {})
    assert len(result.facts.skills) == 1
    assert result.facts.skills[0].confirmed is False
    assert result.facts.warnings == ["w"]
    assert result.unmapped_skills == ["保留"]
    assert result.text_hash == "hash"


def test_resume_keeps_internship_and_award_quotes_for_review():
    payload, output, prepared = _resume_case()
    prepared["document_text"] += "\n获奖经历：2026 年获得校级一等奖学金。"
    output.facts.awards = [ResumeEvidenceFact(
        claim="校级一等奖学金", quote="2026 年获得校级一等奖学金",
        location="paragraph:4", confirmed=True,
    )]
    result = ResumeParseHandler().validate_business(_ctx(payload), output, prepared, {})
    assert result.facts.internships[0].quote.startswith("实习经历：使用Redis")
    assert result.facts.internships[0].confirmed is False
    assert len(result.facts.awards) == 1
    assert result.facts.awards[0].quote == "2026 年获得校级一等奖学金"
    assert result.facts.awards[0].confirmed is False


def test_resume_rejects_award_quote_outside_document():
    payload, output, prepared = _resume_case()
    output.facts.awards = [ResumeEvidenceFact(
        claim="金奖", quote="不存在的金奖", location="paragraph:4",
    )]
    with pytest.raises(HandlerValidationError, match="awards 的 quote 不在简历原文中"):
        ResumeParseHandler().validate_business(_ctx(payload), output, prepared, {})


def test_resume_rejects_unknown_skill_and_foreign_quote():
    payload, output, prepared = _resume_case()
    output.facts.skills[0].skill_code = "python"
    with pytest.raises(HandlerValidationError, match="不在 skillCatalog"):
        ResumeParseHandler().validate_business(_ctx(payload), output, prepared, {})

    payload, output, prepared = _resume_case()
    output.facts.skills[0].quote = "原文不存在的话"
    with pytest.raises(HandlerValidationError, match="quote 不在简历原文中"):
        ResumeParseHandler().validate_business(_ctx(payload), output, prepared, {})


def test_resume_rejects_out_of_range_fields():
    payload, output, prepared = _resume_case()
    output.facts.graduation_year = 1800
    with pytest.raises(HandlerValidationError, match="graduationYear 超出合理范围"):
        ResumeParseHandler().validate_business(_ctx(payload), output, prepared, {})

    payload, output, prepared = _resume_case()
    output.facts.experience_months = 700
    with pytest.raises(HandlerValidationError, match="experienceMonths 超出合理范围"):
        ResumeParseHandler().validate_business(_ctx(payload), output, prepared, {})

    payload, output, prepared = _resume_case()
    output.facts.education = "PHD"
    with pytest.raises(HandlerValidationError, match="education 不在允许枚举内"):
        ResumeParseHandler().validate_business(_ctx(payload), output, prepared, {})


# ---------------------------------------------------------------------------
# JD_ANALYZE
# ---------------------------------------------------------------------------


def _jd_output():
    return AIJDResult(
        requirements=Requirement(
            educationMin="BACHELOR",
            graduationYearMin=2026,
            graduationYearMax=2027,
            skills=["java", "mysql"],
            requiredSkillLevels={"java": 70},
            quotes={
                "educationMin": "本科及以上",
                "graduationYearMin": "2026",
                "graduationYearMax": "2027",
            },
        ),
        warnings=["w", "w"],
    )


def _jd_payload():
    return JDParsePayload.model_validate(jd_envelope(uuid4())["payload"])


def test_jd_validate_success_dedupes_warnings():
    result = JDAnalyzeHandler().validate_business(_ctx(_jd_payload()), _jd_output(), {}, {})
    assert result.warnings == ["w"]
    assert result.requirements.required_skill_levels == {"java": 70}


def test_jd_rejects_unknown_quote_key_and_foreign_quote():
    output = _jd_output()
    output.requirements.quotes["foo"] = "本科及以上"
    with pytest.raises(HandlerValidationError, match="quotes 含未知字段"):
        JDAnalyzeHandler().validate_business(_ctx(_jd_payload()), output, {}, {})

    output = _jd_output()
    output.requirements.quotes["educationMin"] = "博士及以上"
    with pytest.raises(HandlerValidationError, match="quotes.educationMin 必须是 JD 原文片段"):
        JDAnalyzeHandler().validate_business(_ctx(_jd_payload()), output, {}, {})


def test_jd_requires_quote_for_hard_fields():
    output = _jd_output()
    del output.requirements.quotes["graduationYearMin"]
    with pytest.raises(HandlerValidationError, match="硬性字段 graduationYearMin 缺少 JD 原文引用"):
        JDAnalyzeHandler().validate_business(_ctx(_jd_payload()), output, {}, {})


def test_jd_rejects_skill_catalog_and_level_violations():
    output = _jd_output()
    output.requirements.skills = ["python"]
    with pytest.raises(HandlerValidationError, match="技能 python 不在 skillCatalog"):
        JDAnalyzeHandler().validate_business(_ctx(_jd_payload()), output, {}, {})

    output = _jd_output()
    output.requirements.required_skill_levels = {"redis": 50}
    with pytest.raises(HandlerValidationError, match="requiredSkillLevels 只能包含"):
        JDAnalyzeHandler().validate_business(_ctx(_jd_payload()), output, {}, {})

    output = _jd_output()
    output.requirements.required_skill_levels = {"java": 120}
    with pytest.raises(HandlerValidationError, match="必须在 0..100"):
        JDAnalyzeHandler().validate_business(_ctx(_jd_payload()), output, {}, {})


def test_jd_rejects_invalid_year_experience_and_salary_ranges():
    output = _jd_output()
    output.requirements.graduation_year_min = 2028
    with pytest.raises(HandlerValidationError, match="graduationYearMin 不得大于 graduationYearMax"):
        JDAnalyzeHandler().validate_business(_ctx(_jd_payload()), output, {}, {})

    output = _jd_output()
    output.requirements.graduation_year_min = 1800
    with pytest.raises(HandlerValidationError, match="毕业年份超出合理范围"):
        JDAnalyzeHandler().validate_business(_ctx(_jd_payload()), output, {}, {})

    output = _jd_output()
    output.requirements.experience_months_min = 700
    output.requirements.quotes["experienceMonthsMin"] = "本科及以上"
    with pytest.raises(HandlerValidationError, match="experienceMonthsMin 超出合理范围"):
        JDAnalyzeHandler().validate_business(_ctx(_jd_payload()), output, {}, {})

    output = _jd_output()
    output.salary = Salary(minMonthlySalaryCents=200, maxMonthlySalaryCents=100)
    with pytest.raises(HandlerValidationError, match="salary 下限不得大于上限"):
        JDAnalyzeHandler().validate_business(_ctx(_jd_payload()), output, {}, {})


# ---------------------------------------------------------------------------
# JOB_MATCH
# ---------------------------------------------------------------------------


def _match_output():
    return MatchModelOutput(
        items=[
            MatchItemModelOutput(
                jobId=JOB_ID,
                jobVersion=1,
                semanticScore=0.8,
                reasons=["方向一致"],
                gapSuggestions={"java": "补强 Java"},
            )
        ]
    )


def test_match_validate_recomputes_gaps():
    payload = JobMatchPayload.model_validate(match_envelope(uuid4())["payload"])
    result = JobMatchHandler().validate_business(_ctx(payload), _match_output(), {}, {})
    gaps = {item.skill_code: item for item in result.items[0].gaps}
    assert set(gaps) == {"java", "mysql", "redis"}
    assert gaps["java"].gap is None
    assert gaps["java"].suggestion == "补强 Java"
    assert result.items[0].semantic_version == "semantic-v1"


def test_match_rejects_duplicate_missing_and_unknown_suggestions():
    payload = JobMatchPayload.model_validate(match_envelope(uuid4())["payload"])
    output = _match_output()
    output.items.append(output.items[0].model_copy(deep=True))
    with pytest.raises(HandlerValidationError, match="候选重复"):
        JobMatchHandler().validate_business(_ctx(payload), output, {}, {})

    with pytest.raises(HandlerValidationError, match="一一对应"):
        JobMatchHandler().validate_business(_ctx(payload), MatchModelOutput(items=[]), {}, {})

    output = _match_output()
    output.items[0].gap_suggestions = {"python": "x"}
    with pytest.raises(HandlerValidationError, match="gapSuggestions 含候选"):
        JobMatchHandler().validate_business(_ctx(payload), output, {}, {})


def test_build_gaps_covers_all_evidence_branches():
    requirements = Requirement(
        skills=["a", "b", "c", "d", "e"],
        requiredSkillLevels={"a": 50, "b": 60, "e": 60},
    )
    snapshots = {
        "a": SimpleNamespace(score=80, sample_count=3),
        "b": SimpleNamespace(score=40, sample_count=3),
        "c": SimpleNamespace(score=70, sample_count=0),
        "e": SimpleNamespace(score=40, sample_count=1),
    }
    gaps = _build_gaps(requirements, snapshots, {"b": "针对 b 训练"})
    by_code = {item.skill_code: item for item in gaps}
    assert list(by_code) == ["b", "d", "e"]
    assert by_code["b"].gap == 20
    assert by_code["b"].evidence_status == "SUFFICIENT"
    assert by_code["b"].suggestion == "针对 b 训练"
    assert by_code["d"].current_score is None
    assert by_code["d"].target_score is None
    assert by_code["d"].evidence_status == "MISSING"
    assert "补充 d" in by_code["d"].suggestion
    assert by_code["e"].evidence_status == "LIMITED"
    assert by_code["e"].gap == 20


# ---------------------------------------------------------------------------
# LEARNING_PLAN
# ---------------------------------------------------------------------------


def _plan_output(minutes=20):
    return AIPlanResult(
        title="  差距提升计划  ",
        summary="基于岗位差距生成",
        items=[
            PlanItem(
                skillCode="redis",
                title="  专项  ",
                kind="PRACTICE",
                category="BASIC",
                objective="掌握缓存设计",
                reason="岗位差距要求补充 Redis 实战证据",
                method="完成缓存穿透与一致性场景练习",
                completionCriteria="提交有效回答并达到 60 分",
                estimatedMinutes=minutes,
            )
        ],
    )


def _resume_payload(**overrides):
    data = {
        "planSource": "RESUME",
        "profileVersion": 3,
        "dailyMinutes": 30,
        "days": 7,
        "targetDirection": "JAVA_BACKEND",
        "allowedSkillCodes": ["JAVA", "REDIS"],
        "knowledgeRefs": [],
        "matchItem": None,
        "resume": {
            "versionId": str(uuid4()),
            "versionNo": 1,
            "title": "后端简历",
            "targetDirection": "JAVA_BACKEND",
            "skills": [
                {
                    "skillCode": "JAVA",
                    "claim": "负责订单系统开发",
                    "quote": "基于 Spring Boot 实现订单系统",
                    "location": "项目经历",
                }
            ],
            "projectsText": "项目经历：基于 Spring Boot 实现订单系统，使用 Redis 做缓存。",
        },
        "trainingHistory": {
            "completedItems": 0,
            "evaluatedAnswers": 0,
            "averageScore": None,
            "openMistakes": 0,
            "weakSkillCodes": [],
        },
        "limitations": [],
    }
    data.update(overrides)
    return LearningPlanPayload.model_validate(data)


def _github_prepared(url="https://github.com/spring-projects/spring-boot"):
    return {
        "github": {
            "status": "AVAILABLE",
            "fetchedAt": "2026-11-12T00:00:00Z",
            "candidates": [
                {
                    "fullName": "spring-projects/spring-boot",
                    "htmlUrl": url,
                    "description": "Spring Boot",
                    "stargazersCount": 70000,
                    "language": "Java",
                    "topics": ["spring-boot"],
                    "source": "GITHUB_API",
                    "verifiedAt": "2026-11-12T00:00:00Z",
                }
            ],
        }
    }


def test_plan_validate_trims_titles():
    payload = LearningPlanPayload.model_validate(plan_envelope(uuid4())["payload"])
    result = LearningPlanHandler().validate_business(_ctx(payload), _plan_output(), {}, {})
    assert result.title == "差距提升计划"
    assert result.items[0].title == "专项"


def test_plan_rejects_disallowed_skill_and_over_budget():
    payload = LearningPlanPayload.model_validate(plan_envelope(uuid4())["payload"])
    output = _plan_output()
    output.items[0].skill_code = "java"
    with pytest.raises(HandlerValidationError, match="不在 allowedSkillCodes"):
        LearningPlanHandler().validate_business(_ctx(payload), output, {}, {})

    output = AIPlanResult(
        title="t",
        items=[
            PlanItem(
                skillCode="redis", title="a", kind="PRACTICE", category="BASIC",
                objective="o", reason="r", method="m", completionCriteria="c",
                estimatedMinutes=120,
            ),
            PlanItem(
                skillCode="redis", title="b", kind="READING", category="BASIC",
                objective="o", reason="r", method="m", completionCriteria="c",
                estimatedMinutes=120,
            ),
        ],
    )
    with pytest.raises(HandlerValidationError, match="超过 dailyMinutes"):
        LearningPlanHandler().validate_business(_ctx(payload), output, {}, {})


def test_plan_resource_refs_must_be_authorized():
    payload = LearningPlanPayload.model_validate(plan_envelope(uuid4())["payload"])
    citation = Citation(documentId=uuid4(), documentVersion=1, chunkId="c1", quote="原文")
    output = _plan_output()
    output.items[0].resource_refs = [citation]
    with pytest.raises(HandlerValidationError, match="resourceRefs 含未授权的知识引用"):
        LearningPlanHandler().validate_business(_ctx(payload), output, {}, {})

    payload.knowledge_refs = [citation]
    result = LearningPlanHandler().validate_business(_ctx(payload), output, {}, {})
    assert result.items[0].resource_refs[0].chunk_id == "c1"
    assert result.items[0].resource_refs[0] is not citation


def test_resume_plan_accepts_project_quote_from_resume_text():
    payload = _resume_payload()
    output = AIPlanResult(
        title="简历专项计划",
        items=[
            PlanItem(
                skillCode="JAVA",
                title="订单系统项目深挖",
                kind="INTERVIEW",
                category="RESUME_PROJECT",
                objective="能讲清项目技术选型与个人贡献",
                reason="简历项目经历中真实出现订单系统",
                method="按 STAR 结构准备项目介绍并模拟追问",
                completionCriteria="完成一次模拟面试并通过评价",
                evidenceQuote="基于 Spring Boot 实现订单系统",
                estimatedMinutes=30,
            ),
            PlanItem(
                skillCode="REDIS",
                title="缓存基础补强",
                kind="PRACTICE",
                category="BASIC",
                objective="掌握缓存设计基础",
                reason="简历未提供 Redis 直接证据，待评估",
                method="完成缓存场景练习",
                completionCriteria="提交有效回答",
                evidenceStatus="PENDING",
                estimatedMinutes=20,
            ),
        ],
    )
    result = LearningPlanHandler().validate_business(_ctx(payload), output, {"github": {"status": "UNAVAILABLE"}}, {})
    assert result.items[0].category == "RESUME_PROJECT"
    assert result.items[1].evidence_status == "PENDING"


def test_resume_plan_rejects_fabricated_project_quote():
    payload = _resume_payload()
    output = AIPlanResult(
        title="简历专项计划",
        items=[
            PlanItem(
                skillCode="JAVA",
                title="编造项目",
                kind="INTERVIEW",
                category="RESUME_PROJECT",
                objective="o",
                reason="r",
                method="m",
                completionCriteria="c",
                evidenceQuote="基于 Kubernetes 实现多活架构",
                estimatedMinutes=30,
            )
        ],
    )
    with pytest.raises(HandlerValidationError, match="简历原文"):
        LearningPlanHandler().validate_business(_ctx(payload), output, {"github": {"status": "UNAVAILABLE"}}, {})


def test_github_item_requires_available_candidates():
    payload = _resume_payload()
    output = AIPlanResult(
        title="简历专项计划",
        items=[
            PlanItem(
                skillCode="JAVA",
                title="学习 Spring Boot 官方示例",
                kind="READING",
                category="GITHUB",
                objective="o",
                reason="r",
                method="m",
                completionCriteria="c",
                resourceLinks=[{"label": "spring-boot", "url": "https://github.com/spring-projects/spring-boot"}],
                estimatedMinutes=30,
            )
        ],
    )
    with pytest.raises(HandlerValidationError, match="GitHub 数据不可用"):
        LearningPlanHandler().validate_business(_ctx(payload), output, {"github": {"status": "UNAVAILABLE"}}, {})

    result = LearningPlanHandler().validate_business(_ctx(payload), output, _github_prepared(), {})
    assert result.items[0].category == "GITHUB"
    assert result.github.status == "AVAILABLE"

    output.items[0].resource_links[0].url = "https://github.com/evil/fake-repo"
    with pytest.raises(HandlerValidationError, match="候选之外"):
        LearningPlanHandler().validate_business(_ctx(payload), output, _github_prepared(), {})


def test_match_plan_rejects_pending_and_resume_category():
    payload = LearningPlanPayload.model_validate(plan_envelope(uuid4())["payload"])
    output = AIPlanResult(
        title="岗位差距计划",
        items=[
            PlanItem(
                skillCode="redis",
                title="待评估项",
                kind="PRACTICE",
                category="BASIC",
                objective="o",
                reason="r",
                method="m",
                completionCriteria="c",
                evidenceStatus="PENDING",
                estimatedMinutes=20,
            )
        ],
    )
    with pytest.raises(HandlerValidationError, match="待评估"):
        LearningPlanHandler().validate_business(_ctx(payload), output, {}, {})

    output.items[0].evidence_status = "ASSESSED"
    output.items[0].category = "RESUME_PROJECT"
    with pytest.raises(HandlerValidationError, match="岗位匹配来源"):
        LearningPlanHandler().validate_business(_ctx(payload), output, {}, {})


# ---------------------------------------------------------------------------
# LEARNING_QUESTION
# ---------------------------------------------------------------------------


def _question_output():
    return AIQuestionResult(
        stem="请说明缓存穿透的处理方式与边界条件。",
        skillCodes=["redis"],
        difficulty="MEDIUM",
        referenceAnswer="布隆过滤器与空值缓存",
        rubric=fixed_rubric("technical-answer-v1"),
        citations=[],
    )


def test_question_validate_business_rules():
    payload = LearningQuestionPayload.model_validate(question_envelope(uuid4())["payload"])
    handler = LearningQuestionHandler()
    result = handler.validate_business(_ctx(payload), _question_output(), {}, {})
    assert result.source_type == "GENERATED"
    assert result.source_question_id is None

    output = _question_output()
    output.skill_codes = ["java"]
    with pytest.raises(HandlerValidationError, match="skillCodes 必须包含"):
        handler.validate_business(_ctx(payload), output, {}, {})

    output = _question_output()
    output.stem = "短"
    with pytest.raises(HandlerValidationError, match="题干过短"):
        handler.validate_business(_ctx(payload), output, {}, {})

    output = _question_output()
    output.reference_answer = " "
    with pytest.raises(HandlerValidationError, match="缺少参考答案"):
        handler.validate_business(_ctx(payload), output, {}, {})

    output = _question_output()
    output.rubric.dimensions[0].weight = 0.7
    with pytest.raises(HandlerValidationError, match="权重必须为"):
        handler.validate_business(_ctx(payload), output, {}, {})

    output = _question_output()
    output.citations = [Citation(documentId=uuid4(), documentVersion=1, chunkId="c", quote="q")]
    with pytest.raises(HandlerValidationError, match="citations 含未授权的知识引用"):
        handler.validate_business(_ctx(payload), output, {}, {})


async def test_question_generate_path_when_all_candidates_excluded():
    from app.config import Settings
    from app.fake_model import FakeLLM
    from app.files import FileFetcher
    from app.handlers import ExecutionContext

    body = question_envelope(uuid4(), candidates=[question_candidate()])
    body["payload"]["excludedQuestionIds"] = [QUESTION_ID]
    payload = LearningQuestionPayload.model_validate(body["payload"])
    settings = Settings(app_env="test", llm_provider="fake", callback_enabled=False)
    llm = FakeLLM()
    stages = []

    async def emit_stage(name, progress):
        stages.append(name)

    ctx = ExecutionContext(
        run=SimpleNamespace(operation="LEARNING_QUESTION"),
        payload=payload,
        settings=settings,
        llm=llm,
        files=FileFetcher(settings),
        emit_stage=emit_stage,
    )
    result, meta = await LearningQuestionHandler().execute(ctx)
    assert result["sourceType"] == "GENERATED"
    assert result["skillCodes"] == ["redis"]
    assert len(llm.calls) == 1
    assert meta["operation"] == "LEARNING_QUESTION"
    assert "input_ready" in stages
    assert "validating_result" in stages


async def test_question_select_excludes_duplicate_stem_candidates():
    """候选题 id 未被排除、但题干与已用题干等价（仅排版差异）时仍被排除，改走生成路径"""
    from app.config import Settings
    from app.fake_model import FakeLLM
    from app.files import FileFetcher
    from app.handlers import ExecutionContext

    body = question_envelope(uuid4(), candidates=[question_candidate()])
    # 与候选题干"如何处理缓存穿透？"仅差一个空格，归一化后等价
    body["payload"]["excludedQuestionStems"] = ["如何处理缓存穿透 ？"]
    payload = LearningQuestionPayload.model_validate(body["payload"])
    settings = Settings(app_env="test", llm_provider="fake", callback_enabled=False)
    llm = FakeLLM()

    async def emit_stage(name, progress):
        return None

    ctx = ExecutionContext(
        run=SimpleNamespace(operation="LEARNING_QUESTION"),
        payload=payload,
        settings=settings,
        llm=llm,
        files=FileFetcher(settings),
        emit_stage=emit_stage,
    )
    result, _ = await LearningQuestionHandler().execute(ctx)
    assert result["sourceType"] == "GENERATED"
    assert len(llm.calls) == 1


def test_question_generated_stem_rejects_duplicate_of_used():
    """生成的新题干与已用题干等价时校验失败，要求换考查角度"""
    body = question_envelope(uuid4())
    body["payload"]["excludedQuestionStems"] = ["请说明缓存穿透的处理方式与边界条件。"]
    payload = LearningQuestionPayload.model_validate(body["payload"])
    handler = LearningQuestionHandler()
    output = _question_output()
    with pytest.raises(HandlerValidationError, match="生成题干与已用题目重复"):
        handler.validate_business(_ctx(payload), output, {}, {})


# ---------------------------------------------------------------------------
# Evaluation assembly
# ---------------------------------------------------------------------------


def test_build_evaluation_valid_weighted_score_and_dedupe():
    answer_id = uuid4()
    output = _evaluation_output()
    drafts = [
        SkillScoreDraft(skillCode="redis", score=80, confidence=0.6),
        SkillScoreDraft(skillCode="redis", score=70, confidence=0.5),
    ]
    evaluation, scores = _build_evaluation(
        model_output=output.evaluation,
        skill_score_drafts=drafts,
        rubric=fixed_rubric("v"),
        meta={"model": "m", "promptVersion": "p"},
        answer="这是一个足够长的有效回答",
        question_skills=["redis"],
        source_id=answer_id,
        reference_fallback="fallback",
    )
    assert evaluation.overall_score == 80.0
    assert evaluation.model_version == "m"
    assert evaluation.prompt_version == "p"
    assert evaluation.reference_answer == "参考"
    assert len(scores) == 1
    assert scores[0].source_id == answer_id


def test_build_evaluation_invalid_status_and_dimension_mismatch():
    output = _evaluation_output(status="BOGUS")
    with pytest.raises(HandlerValidationError, match="evidenceStatus 非法"):
        _build_evaluation(
            model_output=output.evaluation,
            rubric=fixed_rubric("v"),
            meta={},
            answer="有效回答内容",
            question_skills=[],
            source_id=uuid4(),
            reference_fallback="",
        )

    output = _evaluation_output()
    output.evaluation.dimensions = output.evaluation.dimensions[:2]
    with pytest.raises(HandlerValidationError, match="评价维度必须与输入量表"):
        _build_evaluation(
            model_output=output.evaluation,
            rubric=fixed_rubric("v"),
            meta={},
            answer="有效回答内容",
            question_skills=[],
            source_id=uuid4(),
            reference_fallback="",
        )


def test_build_evaluation_unusable_answer_forces_invalid():
    output = _evaluation_output()
    evaluation, scores = _build_evaluation(
        model_output=output.evaluation,
        skill_score_drafts=[SkillScoreDraft(skillCode="redis", score=80, confidence=0.5)],
        rubric=fixed_rubric("v"),
        meta={},
        answer="不知道",
        question_skills=["redis"],
        source_id=uuid4(),
        reference_fallback="",
    )
    assert evaluation.evidence_status == "INVALID"
    assert evaluation.overall_score is None
    assert scores == []
    # 明确回答"不会"必须标记 UNKNOWN，Java 侧据此写入错题本待复习路径
    assert evaluation.answer_class == "UNKNOWN"


def test_build_evaluation_short_answer_marks_invalid_class():
    output = _evaluation_output()
    evaluation, scores = _build_evaluation(
        model_output=output.evaluation,
        rubric=fixed_rubric("v"),
        meta={},
        answer="x",
        question_skills=["redis"],
        source_id=uuid4(),
        reference_fallback="",
    )
    assert evaluation.evidence_status == "INVALID"
    # 过短/乱输入属于无效回答（INVALID），与明确"不会"（UNKNOWN）区分
    assert evaluation.answer_class == "INVALID"
    assert evaluation.overall_score is None
    assert scores == []


def test_build_evaluation_valid_answer_has_no_answer_class():
    output = _evaluation_output()
    evaluation, _ = _build_evaluation(
        model_output=output.evaluation,
        rubric=fixed_rubric("v"),
        meta={},
        answer="这是一个足够长的有效回答",
        question_skills=["redis"],
        source_id=uuid4(),
        reference_fallback="",
    )
    assert evaluation.evidence_status == "VALID"
    assert evaluation.answer_class is None


def test_build_evaluation_insufficient_uses_reference_fallback():
    output = _evaluation_output(status="INSUFFICIENT")
    output.evaluation.reference_answer = ""
    evaluation, scores = _build_evaluation(
        model_output=output.evaluation,
        rubric=fixed_rubric("v"),
        meta={},
        answer="有效回答内容",
        question_skills=[],
        source_id=uuid4(),
        reference_fallback="兜底参考答案",
    )
    assert evaluation.evidence_status == "INSUFFICIENT"
    assert evaluation.reference_answer == "兜底参考答案"
    assert scores == []


@pytest.mark.parametrize("dimensions", [[], [{"key": key, "score": None, "weight": weight}
    for key, weight in FIXED_RUBRIC_WEIGHTS.items()]])
def test_unscorable_answer_accepts_missing_scores_without_creating_zero_evidence(dimensions):
    output = EvaluationModelOutput.model_validate({"evidenceStatus": "INSUFFICIENT", "dimensions": dimensions})
    evaluation, scores = _build_evaluation(model_output=output, rubric=fixed_rubric("v"), meta={},
        answer="无法提供相关经历", question_skills=[], source_id=uuid4(), reference_fallback="")
    assert evaluation.overall_score is None
    assert evaluation.dimensions == []
    assert scores == []


def test_valid_evaluation_still_rejects_null_dimension_scores():
    output = EvaluationModelOutput.model_validate({"evidenceStatus": "VALID", "dimensions": [
        {"key": key, "score": None, "weight": weight} for key, weight in FIXED_RUBRIC_WEIGHTS.items()]})
    with pytest.raises(HandlerValidationError, match="有效评价的维度"):
        _build_evaluation(model_output=output, rubric=fixed_rubric("v"), meta={},
            answer="这是有效回答内容", question_skills=[], source_id=uuid4(), reference_fallback="")


def test_build_evaluation_rejects_foreign_skill_score():
    output = _evaluation_output()
    with pytest.raises(HandlerValidationError, match="skillScores 含题目未包含的技能"):
        _build_evaluation(
            model_output=output.evaluation,
            skill_score_drafts=[SkillScoreDraft(skillCode="java", score=80, confidence=0.5)],
            rubric=fixed_rubric("v"),
            meta={},
            answer="有效回答内容",
            question_skills=["redis"],
            source_id=uuid4(),
            reference_fallback="",
        )


# ---------------------------------------------------------------------------
# INTERVIEW_TURN / INTERVIEW_REPORT
# ---------------------------------------------------------------------------


def _turn_payload(**changes):
    body = interview_turn_followup_envelope(uuid4())
    body["payload"].update(changes)
    return InterviewTurnPayload.model_validate(body["payload"])


def _turn_prepared(payload):
    return {
        "profile_skills": ["redis"],
        "is_first": False,
        "current_question": payload.messages[0],
        "main_questions": 1,
    }


def test_turn_requires_answer_evaluation_for_followup_rounds():
    payload = _turn_payload()
    with pytest.raises(HandlerValidationError, match="后续轮次必须提供 answerEvaluation"):
        InterviewTurnHandler().validate_business(
            _ctx(payload), TurnModelOutput(), _turn_prepared(payload), {"model": "m"}
        )


def test_turn_finishes_when_question_limit_reached():
    payload = _turn_payload(questionLimit=1)
    output = TurnModelOutput(answerEvaluation=_evaluation_output(), nextQuestion=None)
    result = InterviewTurnHandler().validate_business(
        _ctx(payload), output, _turn_prepared(payload), {"model": "m"}
    )
    assert result.action == "FINISH"
    assert result.stop_reason == "LIMIT_REACHED"
    assert result.question is None
    assert result.answer_evaluation.evaluation.overall_score == 80.0


def test_turn_asks_next_question_when_followup_budget_exhausted():
    payload = _turn_payload(maxFollowUps=0)
    output = TurnModelOutput(
        answerEvaluation=_evaluation_output(),
        needsFollowUp=True,
        followUpQuestion=TurnQuestionModelOutput(content="这个追问不应该被采纳", skillCodes=["redis"]),
        nextQuestion=TurnQuestionModelOutput(content="请介绍下一个项目的技术选型。", skillCodes=["redis"]),
    )
    result = InterviewTurnHandler().validate_business(
        _ctx(payload), output, _turn_prepared(payload), {"model": "m"}
    )
    assert result.action == "ASK"
    assert result.question.question_no == 2
    assert result.question.follow_up_no == 0


def test_interview_skill_scores_cannot_use_other_profile_skills():
    payload = _turn_payload()
    prepared = _turn_prepared(payload)
    prepared["profile_skills"] = ["redis", "java"]
    output = TurnModelOutput(answerEvaluation=_evaluation_output(),
        nextQuestion=TurnQuestionModelOutput(content="请说明缓存更新的取舍", skillCodes=["redis"]))
    output.answer_evaluation.skill_scores = [SkillScoreDraft(skillCode="java", score=80, confidence=.5)]
    with pytest.raises(HandlerValidationError, match="题目未包含的技能"):
        InterviewTurnHandler().validate_business(_ctx(payload), output, prepared, {})


def test_turn_question_content_and_skill_validation():
    payload = _turn_payload(maxFollowUps=0)
    prepared = _turn_prepared(payload)
    handler = InterviewTurnHandler()
    with pytest.raises(HandlerValidationError, match="缺少题目内容"):
        handler._turn_question(None, question_no=1, follow_up_no=0, allowed={"redis"})
    with pytest.raises(HandlerValidationError, match="长度必须为 5..3000"):
        handler._turn_question(
            TurnQuestionModelOutput(content="短", skillCodes=[]),
            question_no=1, follow_up_no=0, allowed={"redis"},
        )
    with pytest.raises(HandlerValidationError, match="不存在的技能编码"):
        handler._turn_question(
            TurnQuestionModelOutput(content="内容足够长的问题", skillCodes=["java"]),
            question_no=1, follow_up_no=0, allowed={"redis"},
        )


def test_report_rejects_unknown_gap_suggestion_and_filters_gaps():
    payload = InterviewReportPayload.model_validate(interview_report_envelope(uuid4())["payload"])
    handler = InterviewReportHandler()
    prepared = {"average_score": 70.0, "aggregated_dimensions": [], "valid_count": 1}

    output = ReportModelOutput(gapSuggestions={"java": "x"})
    with pytest.raises(HandlerValidationError, match="gapSuggestions 含 skillSnapshot 之外的技能"):
        handler.validate_business(_ctx(payload), output, prepared, {"model": "m"})

    output = ReportModelOutput(gapSuggestions={"redis": "继续训练"}, strengths=["s"], improvements=["i"])
    result = handler.validate_business(_ctx(payload), output, prepared, {"model": "m"})
    assert result.gaps == []  # snapshot score 70 已达到阈值
    assert result.evaluation.overall_score == 70.0
    assert result.answered_question_count == 1

    payload.skill_snapshot[0].score = 60
    result = handler.validate_business(_ctx(payload), output, prepared, {"model": "m"})
    assert len(result.gaps) == 1
    assert result.gaps[0].suggestion == "继续训练"
    assert result.gaps[0].evidence_status == "LIMITED"


# ---------------------------------------------------------------------------
# GROWTH_ANALYZE
# ---------------------------------------------------------------------------


def test_growth_validate_dedupes_and_checks_evidence():
    payload = GrowthAnalyzeHandler().payload_model.model_validate(growth_envelope(uuid4())["payload"])
    handler = GrowthAnalyzeHandler()

    output = AIGrowthResult(suggestions=["s1", "s1", "s2"], citedEvidenceIds=[EVIDENCE_ID, EVIDENCE_ID])
    result = handler.validate_business(_ctx(payload), output, {}, {})
    assert result.suggestions == ["s1", "s2"]
    assert len(result.cited_evidence_ids) == 1

    with pytest.raises(HandlerValidationError, match="suggestions 不能为空"):
        handler.validate_business(_ctx(payload), AIGrowthResult(suggestions=[" "]), {}, {})

    output = AIGrowthResult(suggestions=["s"], citedEvidenceIds=[str(uuid4())])
    with pytest.raises(HandlerValidationError, match="citedEvidenceIds 含输入中不存在的证据"):
        handler.validate_business(_ctx(payload), output, {}, {})
