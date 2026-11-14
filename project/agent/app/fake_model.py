from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import re
from typing import Any, Callable

from .llm import LLMRequest, LLMResponse, LLMProviderError

logger = logging.getLogger("agent.fake_model")

RUBRIC = {
    "version": "technical-answer-v1",
    "dimensions": [
        {"key": "correctness", "weight": 0.5},
        {"key": "completeness", "weight": 0.3},
        {"key": "expression", "weight": 0.2},
    ],
}


def _payload(request: LLMRequest) -> dict[str, Any]:
    raw = request.context.get("payload")
    return raw if isinstance(raw, dict) else {}


def _text(request: LLMRequest) -> str:
    return str(request.context.get("document_text") or "")


def _lines(text: str) -> list[str]:
    parts = re.split(r"[\n。；;]+", text)
    return [part.strip() for part in parts if part.strip()]


def _education(text: str) -> str | None:
    for keyword, value in (
        ("博士", "DOCTOR"),
        ("硕士", "MASTER"),
        ("研究生", "MASTER"),
        ("本科", "BACHELOR"),
        ("大专", "ASSOCIATE"),
        ("专科", "ASSOCIATE"),
        ("高中", "HIGH_SCHOOL"),
    ):
        if keyword in text:
            return value
    return None


def _years(text: str) -> list[int]:
    return [int(item) for item in re.findall(r"(20\d{2})", text)]


def _quote_for(text: str, keyword: str, limit: int = 500) -> tuple[str | None, str | None]:
    for index, line in enumerate(_lines(text)):
        if keyword.lower() in line.lower():
            return line[:limit], f"text:{index + 1}"
    return None, None


def _catalog(request: LLMRequest) -> list[dict[str, str]]:
    items = _payload(request).get("skillCatalog") or []
    return [item for item in items if isinstance(item, dict) and item.get("code")]


def _profile_skills(request: LLMRequest) -> list[str]:
    profile = _payload(request).get("profile") or {}
    facts = profile.get("facts") or {}
    skills = facts.get("skills") or []
    return [item.get("skillCode") for item in skills if isinstance(item, dict) and item.get("skillCode")]


def _resume(request: LLMRequest) -> dict[str, Any]:
    text = _text(request)
    catalog = _catalog(request)
    skills = []
    unmapped = []
    for item in catalog:
        code = str(item.get("code"))
        name = str(item.get("name") or code)
        quote, location = _quote_for(text, name) or _quote_for(text, code)
        if quote and location:
            skills.append(
                {
                    "skillCode": code,
                    "claim": f"简历自述使用 {name}",
                    "quote": quote,
                    "location": location,
                    "confirmed": False,
                }
            )
        else:
            unmapped.append(name)
    years = _years(text)
    graduation = None
    for year in years:
        if 2000 <= year <= 2100:
            graduation = year
            break
    warnings = []
    if "实习" in text and "全职" not in text:
        warnings.append("实习未计入全职经验")
    return {
        "facts": {
            "education": _education(text),
            "graduationYear": graduation,
            "experienceMonths": 0 if "实习" in text else None,
            "major": "计算机科学与技术" if "计算机" in text else None,
            "skills": skills,
            "warnings": warnings,
        },
        "unmappedSkills": unmapped,
    }


def _jd(request: LLMRequest) -> dict[str, Any]:
    text = str(_payload(request).get("jdText") or "")
    catalog = _catalog(request)
    skills = []
    for item in catalog:
        code = str(item.get("code"))
        name = str(item.get("name") or code)
        if name.lower() in text.lower() or code.lower() in text.lower():
            skills.append(code)
    years = _years(text)
    quotes: dict[str, str] = {}
    education = _education(text)
    if education:
        quote, _ = _quote_for(text, "本科") or _quote_for(text, "硕士") or _quote_for(text, "大专")
        if quote:
            quotes["educationMin"] = quote
    year_min = min(years) if years else None
    year_max = max(years) if years else None
    if year_min is not None:
        quote, _ = _quote_for(text, str(year_min))
        if quote:
            quotes["graduationYearMin"] = quote
    if year_max is not None:
        quote, _ = _quote_for(text, str(year_max))
        if quote:
            quotes["graduationYearMax"] = quote
    warnings = []
    if "面议" in text:
        warnings.append("薪资面议，未提取明确月薪")
    if not skills:
        warnings.append("未从JD文本中提取到受控技能")
    return {
        "requirements": {
            "educationMin": education,
            "graduationYearMin": year_min,
            "graduationYearMax": year_max,
            "experienceMonthsMin": None,
            "skills": skills,
            "requiredSkillLevels": {},
            "quotes": quotes,
        },
        "salary": None,
        "warnings": warnings,
    }


def _match(request: LLMRequest) -> dict[str, Any]:
    payload = _payload(request)
    facts = (payload.get("profile") or {}).get("facts") or {}
    user_skills = {
        str(item.get("skillCode")) for item in facts.get("skills") or [] if isinstance(item, dict)
    }
    items = []
    for candidate in payload.get("candidates") or []:
        if not isinstance(candidate, dict):
            continue
        requirements = candidate.get("requirements") or {}
        required = [str(item) for item in requirements.get("skills") or []]
        matched = [item for item in required if item in user_skills]
        ratio = len(matched) / len(required) if required else 0.0
        title = str(candidate.get("title") or "")
        reasons = [f"目标方向与岗位 {title} 的JD描述存在语义相关性"]
        if matched:
            reasons.append("已确认技能覆盖: " + ", ".join(matched))
        gap_suggestions = {
            item: f"补充 {item} 的场景训练并提交可追溯回答"
            for item in required
            if item not in user_skills
        }
        items.append(
            {
                "jobId": str(candidate.get("jobId")),
                "jobVersion": candidate.get("jobVersion"),
                "semanticScore": round(0.55 + 0.45 * ratio, 4),
                "reasons": reasons[:5],
                "gapSuggestions": gap_suggestions,
            }
        )
    return {"items": items}


def _plan(request: LLMRequest) -> dict[str, Any]:
    payload = _payload(request)
    github = request.context.get("github") or {}
    source = str(payload.get("planSource") or "MATCH")
    gaps = ((payload.get("matchItem") or {}).get("gaps")) or []
    allowed = [str(item) for item in payload.get("allowedSkillCodes") or []]
    gap_skills = [
        str(item.get("skillCode"))
        for item in gaps
        if isinstance(item, dict) and item.get("skillCode") in allowed
    ]
    selected = gap_skills or allowed
    daily = int(payload.get("dailyMinutes") or 30)
    days = int(payload.get("days") or 7)
    budget = max(5, daily * days)
    items: list[dict[str, Any]] = []
    remaining = budget

    def add(skill: str, title: str, kind: str, category: str, minutes: int,
            extra: dict[str, Any] | None = None) -> None:
        nonlocal remaining
        if not skill:
            return
        minutes = min(minutes, remaining)
        if minutes < 5:
            return
        remaining -= minutes
        item: dict[str, Any] = {
            "skillCode": skill,
            "title": title,
            "kind": kind,
            "category": category,
            "objective": f"掌握 {skill} 的核心能力",
            "reason": "结合目标方向与可用证据安排",
            "method": "完成阅读与场景练习并记录结果",
            "completionCriteria": "提交有效训练记录并达到完成标准",
            "evidenceStatus": "ASSESSED",
            "estimatedMinutes": minutes,
            "resourceRefs": [],
        }
        if extra:
            item.update(extra)
        items.append(item)

    primary = selected[0] if selected else ""
    if source == "RESUME":
        resume = payload.get("resume") or {}
        text = str(resume.get("projectsText") or "").strip()
        if text:
            add(primary, "简历项目深挖", "INTERVIEW", "RESUME_PROJECT", 30, {"evidenceQuote": text[:40]})
        candidates = github.get("candidates") if isinstance(github, dict) else None
        if github.get("status") == "AVAILABLE" and candidates:
            candidate = candidates[0]
            add(
                primary,
                f"学习开源项目 {candidate.get('fullName')}",
                "READING",
                "GITHUB",
                30,
                {
                    "resourceLinks": [
                        {"label": str(candidate.get("fullName")), "url": str(candidate.get("htmlUrl"))}
                    ]
                },
            )
    for index, skill in enumerate(selected):
        if len(items) >= 20 or remaining < 5:
            break
        add(skill, f"{skill} 专项训练", ("PRACTICE", "READING", "INTERVIEW")[index % 3], "BASIC", 20)
    if not items and primary:
        add(primary, f"{primary} 专项训练", "PRACTICE", "BASIC", 5)
    return {"title": "差距专项提升计划", "summary": "根据可用证据生成的训练计划", "items": items}


_QUESTION_ANGLES = [
    "典型问题、处理方案与边界条件",
    "常见故障的定位思路、修复步骤与预防措施",
    "在高并发场景下的挑战、应对策略与权衡取舍",
    "在工程实践中的要点、规范约定与常见误区",
    "从零开始设计时的关键决策、备选方案与选型依据",
    "线上事故复盘角度的根因分析、应急处理与长期改进",
]


def _question(request: LLMRequest) -> dict[str, Any]:
    payload = _payload(request)
    plan_item = payload.get("planItem") or {}
    skill = str(plan_item.get("skillCode") or "general")
    difficulty = str(plan_item.get("difficulty") or "MEDIUM")
    citations = [
        {
            "documentId": item.get("documentId"),
            "documentVersion": item.get("documentVersion"),
            "chunkId": item.get("chunkId"),
            "quote": item.get("quote"),
        }
        for item in payload.get("knowledgeRefs") or []
        if isinstance(item, dict)
    ][:2]
    # T02：确定性题干变体——优先选择未在已用题干中出现过的考查角度，
    # 避免同技能连续生成等价题干被去重校验拒绝，保证普通练习路径可持续
    used_stems = [str(item) for item in payload.get("excludedQuestionStems") or []]
    angle = ""
    for candidate in _QUESTION_ANGLES:
        if f"请结合一个可验证的场景，说明 {skill} 的{candidate}。" not in used_stems:
            angle = candidate
            break
    if not angle:
        session_id = str(payload.get("sessionId") or "")
        digest = hashlib.sha256(f"{skill}:{session_id}".encode("utf-8")).hexdigest()
        angle = _QUESTION_ANGLES[int(digest, 16) % len(_QUESTION_ANGLES)]
    return {
        "stem": f"请结合一个可验证的场景，说明 {skill} 的{angle}。",
        "skillCodes": [skill],
        "difficulty": difficulty,
        "type": "TEXT_SCENARIO",
        "sourceType": "GENERATED",
        "sourceQuestionId": None,
        "referenceAnswer": f"围绕 {skill} 说明问题背景、核心方案、失败边界与验证方式。",
        "rubric": RUBRIC,
        "citations": citations,
    }


def _text_bigrams(text: str) -> set[str]:
    cleaned = "".join(str(text).split())
    return {cleaned[i:i + 2] for i in range(len(cleaned) - 1)}


def _evaluate_payload(request: LLMRequest) -> dict[str, Any]:
    payload = _payload(request)
    answer = str(payload.get("answer") or "")
    question = payload.get("question") or {}
    skills = [str(item) for item in question.get("skillCodes") or []]
    length_score = min(88, 40 + len(answer.strip()) // 8)
    valid = len(answer.strip()) >= 10
    # T03：无关回答判定（双信号）——长度足够、不含任何技能词、且与题干/参考答案
    # 无任何相邻双字重合时才判为无关。单一技能词信号会误判"不含英文技能字样但内容相关"
    # 的中文回答（如"布隆过滤器与空值缓存"不含 redis 但完全针对缓存穿透题）。
    answer_lower = answer.strip().lower()
    question_bigrams = _text_bigrams(
        str(question.get("stem") or "") + str(question.get("referenceAnswer") or "")
    )
    answer_bigrams = _text_bigrams(answer)
    unrelated = (
        valid
        and bool(skills)
        and not any(skill.lower() in answer_lower for skill in skills)
        and not (answer_bigrams & question_bigrams)
    )
    dimensions = []
    for index, key in enumerate(("correctness", "completeness", "expression")):
        score = max(0, min(100, length_score + (3 if index == 0 else -3 if index == 1 else 0)))
        dimensions.append(
            {
                "key": key,
                "score": score,
                "weight": (0.5, 0.3, 0.2)[index],
                "comment": (
                    "回答与题目考查技能无关，无法形成有效评价"
                    if unrelated
                    else "回答覆盖了主要要点" if valid else "回答内容不足，无法形成有效评价"
                ),
            }
        )
    return {
        "evaluation": {
            "overallScore": None,
            "dimensions": [],
            "strengths": [],
            "improvements": ["请针对题目考查的技能作答" if unrelated else "需要给出实质性回答"],
            "referenceAnswer": str(question.get("referenceAnswer") or ""),
            "evidenceStatus": "INVALID" if unrelated else "VALID" if valid else "INSUFFICIENT",
        },
        "skillScores": [],
    } if unrelated else {
        "evaluation": {
            "overallScore": length_score if valid else None,
            "dimensions": dimensions,
            "strengths": ["给出了可执行的处理方向"] if valid else [],
            "improvements": ["补充边界条件和验证方式"] if valid else ["需要给出实质性回答"],
            "referenceAnswer": str(question.get("referenceAnswer") or ""),
            "evidenceStatus": "VALID" if valid else "INSUFFICIENT",
        },
        "skillScores": [
            {"skillCode": skill, "score": length_score, "confidence": 0.6} for skill in skills
        ]
        if valid
        else [],
    }


def _evaluate(request: LLMRequest) -> dict[str, Any]:
    return _evaluate_payload(request)


def _growth(request: LLMRequest) -> dict[str, Any]:
    payload = _payload(request)
    skills = [item for item in payload.get("skills") or [] if isinstance(item, dict)]
    skills.sort(key=lambda item: (item.get("score") is not None, item.get("score") or 0))
    suggestions = []
    for item in skills[:3]:
        code = item.get("skillCode")
        score = item.get("score")
        if score is None:
            suggestions.append(f"为 {code} 完成一次专项训练，形成可追溯能力证据")
        else:
            suggestions.append(f"围绕 {code} 补强薄弱场景，目标提升到 70 分以上")
    if not suggestions:
        suggestions = ["先完成一次专项训练，建立可追溯的能力证据"]
    evidence = payload.get("evidence") or []
    cited = [str(evidence[0].get("id"))] if evidence else []
    return {"suggestions": suggestions[:5], "citedEvidenceIds": cited}


def _turn(request: LLMRequest) -> dict[str, Any]:
    payload = _payload(request)
    latest = payload.get("latestAnswer")
    profile_skills = _profile_skills(request)
    if not latest:
        skill = profile_skills[0] if profile_skills else "项目经历"
        return {
            "answerEvaluation": None,
            "needsFollowUp": False,
            "followUpQuestion": None,
            "nextQuestion": {
                "content": f"请介绍一个你使用 {skill} 解决实际问题的场景，并说明取舍。",
                "questionNo": 1,
                "followUpNo": 0,
                "skillCodes": [skill] if profile_skills else [],
            },
        }

    answer = str(latest.get("content") or "")
    length_score = min(88, 40 + len(answer.strip()) // 8)
    valid = len(answer.strip()) >= 10
    messages = payload.get("messages") or []
    current_question = next(
        (
            item
            for item in messages
            if isinstance(item, dict) and str(item.get("id")) == str(latest.get("questionMessageId"))
        ),
        {},
    )
    evaluated_skills = [str(code) for code in current_question.get("skillCodes") or []]
    evaluation = {
        "evaluation": {
            "overallScore": length_score if valid else None,
            "dimensions": [
                {
                    "key": "correctness",
                    "score": length_score,
                    "weight": 0.5,
                    "comment": "回答方向基本正确" if valid else "内容不足",
                },
                {
                    "key": "completeness",
                    "score": max(0, length_score - 8),
                    "weight": 0.3,
                    "comment": "需要补充边界条件" if valid else "内容不足",
                },
                {
                    "key": "expression",
                    "score": length_score,
                    "weight": 0.2,
                    "comment": "表达可读" if valid else "内容不足",
                },
            ],
            "strengths": ["回答包含具体做法"] if valid else [],
            "improvements": ["补充量化结果与失败边界"] if valid else ["需要给出实质性回答"],
            "referenceAnswer": "结合场景说明方案、取舍和验证方式。",
            "evidenceStatus": "VALID" if valid else "INSUFFICIENT",
        },
        "skillScores": [
            {"skillCode": code, "score": length_score, "confidence": 0.6}
            for code in evaluated_skills
        ]
        if valid
        else [],
    }
    follow_up_no = int(current_question.get("followUpNo") or 0)
    max_follow_ups = int(payload.get("maxFollowUps") or 2)
    needs_follow_up = valid and len(answer.strip()) < 40 and follow_up_no < max_follow_ups
    skill = profile_skills[1] if len(profile_skills) > 1 else (profile_skills[0] if profile_skills else "项目经历")
    return {
        "answerEvaluation": evaluation,
        "needsFollowUp": needs_follow_up,
        "followUpQuestion": {
            "content": "请补充说明该方案的失败边界，以及你会如何验证它有效。",
            "questionNo": 0,
            "followUpNo": follow_up_no + 1,
            "skillCodes": [skill] if profile_skills else [],
        }
        if needs_follow_up
        else None,
        "nextQuestion": {
            "content": f"请说明你在 {skill} 方面做过的最有价值的一次改进，以及衡量指标。",
            "questionNo": 0,
            "followUpNo": 0,
            "skillCodes": [skill] if profile_skills else [],
        },
    }


def _report(request: LLMRequest) -> dict[str, Any]:
    payload = _payload(request)
    evaluations = payload.get("turnEvaluations") or []
    valid_scores = [
        item.get("overallScore")
        for item in evaluations
        if isinstance(item, dict)
        and item.get("evidenceStatus") == "VALID"
        and item.get("overallScore") is not None
    ]
    average = round(sum(valid_scores) / len(valid_scores), 1) if valid_scores else None
    strengths = ["能够完成基本的技术回答"] if average is not None else []
    improvements = ["补充边界条件、量化结果和失败时的兜底方案"] if average is not None else ["需要完成有效回答后再生成复盘"]
    snapshots = [item for item in payload.get("skillSnapshot") or [] if isinstance(item, dict)]
    gaps = {
        str(item.get("skillCode")): "继续完成该技能的专项训练并积累有效证据"
        for item in snapshots
        if item.get("score") is None or float(item.get("score") or 0) < 70
    }
    return {
        "strengths": strengths,
        "improvements": improvements,
        "referenceAnswer": "回答应包含场景、方案、取舍、边界和验证方式。",
        "gapSuggestions": gaps,
    }


def _resume_diagnose(request: LLMRequest) -> dict[str, Any]:
    text = _text(request)
    lines = _lines(text)
    if not lines or not any(keyword in text.casefold() for keyword in (
        "简历", "教育", "经历", "项目", "技能", "工作", "实习",
        "resume", "education", "experience", "project", "skill", "developer", "internship",
    )):
        return {"items": []}
    quote = next(
        (
            line
            for line in lines
            if any(keyword in line for keyword in ("经历", "项目", "实习", "工作", "技能"))
        ),
        lines[0],
    )[:1000]
    return {
        "items": [
            {
                "priority": "SUGGESTION",
                "title": "补充可核实的经历结果",
                "issue": "该段主要呈现经历或技能描述，结果信息不够明确",
                "quote": quote,
                "rationale": "原文没有为该段提供清晰的结果说明，阅读者难以核对实际贡献",
                "suggestion": "仅补充本人能够核实的实际结果；没有可靠结果时保留原表述，不添加量化数据",
            }
        ]
    }


def _resume_optimize(request: LLMRequest) -> dict[str, Any]:
    lines = _lines(_text(request))
    quote = next(
        (
            line
            for line in lines
            if any(keyword in line for keyword in ("经历", "项目", "实习", "工作"))
        ),
        lines[0] if lines else "",
    )[:10000]
    suggested = quote if quote.endswith(("。", "！", "？", ".", "!", "?")) else quote + "。"
    return {
        "title": "定向优化候选建议",
        "sections": [
            {
                "sectionKey": "experience",
                "title": "经历表述候选调整",
                "originalText": quote,
                "suggestedText": suggested,
                "sourceQuotes": [quote[:1000]],
            }
        ],
    }


def _project_recommend(request: LLMRequest) -> dict[str, Any]:
    payload = _payload(request)
    allowed = [str(item) for item in payload.get("allowedSkillCodes") or []]
    gaps = ((payload.get("matchItem") or {}).get("gaps")) or []
    gap_skills = [
        str(item.get("skillCode"))
        for item in gaps
        if isinstance(item, dict) and item.get("skillCode") in allowed
    ]
    selected = gap_skills[:1] or allowed[:1]
    skill = selected[0] if selected else "general"
    max_weeks = int(payload.get("maxDurationWeeks") or 12)
    difficulty = str(payload.get("difficulty") or "INTERMEDIATE")
    return {
        "items": [
            {
                "title": f"{skill} 核心功能实战",
                "summary": f"围绕 {skill} 完成一个边界清晰、可演示和可自测的学习项目",
                "skillCodes": selected,
                "difficulty": difficulty,
                "estimatedWeeks": min(2, max_weeks),
                "reason": "依据确认画像、授权技能目录和已提供的匹配差距形成学习建议，不代表用户已有项目经验",
                "deliverables": ["项目说明与核心功能演示", "实现源码与自测记录"],
            }
        ]
    }


def _job_search(request: LLMRequest) -> dict[str, Any]:
    from .search.fake_understanding import understand as understand_postings

    return understand_postings(request)


def _project_plan(request: LLMRequest) -> dict[str, Any]:
    payload = _payload(request)
    recommendation = payload.get("recommendation") or {}
    weekly_hours = int(payload.get("weeklyHours") or 1)
    estimated_weeks = int(recommendation.get("estimatedWeeks") or 1)
    skill_codes = recommendation.get("skillCodes") or []
    skill = str(skill_codes[0]) if skill_codes else "项目"
    milestone_count = min(3, estimated_weeks)
    task_hours = min(8, weekly_hours)
    milestones = []
    for seq in range(1, milestone_count + 1):
        milestones.append(
            {
                "seq": seq,
                "title": f"第 {seq} 阶段：{skill} 项目交付",
                "description": "完成本阶段约定功能并保留可核对的交付记录",
                "tasks": [
                    {
                        "title": f"实现并验证第 {seq} 阶段核心任务",
                        "description": "依据已选项目范围完成实现、自测和结果整理",
                        "estimatedHours": task_hours,
                    }
                ],
            }
        )
    return {
        "title": str(recommendation.get("title") or "项目实战计划"),
        "summary": "按周投入拆分已选推荐项目，业务进度由 Java 根据任务完成情况管理",
        "milestones": milestones,
    }


RESPONDERS: dict[str, Callable[[LLMRequest], dict[str, Any]]] = {
    "RESUME_PARSE": _resume,
    "RESUME_DIAGNOSE": _resume_diagnose,
    "RESUME_OPTIMIZE": _resume_optimize,
    "JD_ANALYZE": _jd,
    "JOB_MATCH": _match,
    "JOB_SEARCH": _job_search,
    "LEARNING_PLAN": _plan,
    "LEARNING_QUESTION": _question,
    "LEARNING_EVALUATE": _evaluate,
    "GROWTH_ANALYZE": _growth,
    "INTERVIEW_TURN": _turn,
    "INTERVIEW_REPORT": _report,
    "PROJECT_RECOMMEND": _project_recommend,
    "PROJECT_PLAN": _project_plan,
}


class FakeLLM:
    """Deterministic model double used by tests and by local runs without credentials."""

    def __init__(
        self,
        model_name: str = "fake-llm",
        *,
        delay_seconds: float = 0.0,
        responder: Callable[[LLMRequest], dict[str, Any]] | None = None,
        fail_times: int = 0,
        invalid_json_times: int = 0,
    ) -> None:
        self._model_name = model_name
        self.delay_seconds = delay_seconds
        self.responder = responder
        self.fail_times = fail_times
        self.invalid_json_times = invalid_json_times
        self.calls: list[LLMRequest] = []

    @property
    def name(self) -> str:
        return self._model_name

    async def complete(self, request: LLMRequest) -> LLMResponse:
        self.calls.append(request)
        logger.info(
            "fake request operation=%s model=%s user_chars=%s",
            request.operation,
            self._model_name,
            len(request.user_prompt),
        )
        if self.delay_seconds:
            await asyncio.sleep(self.delay_seconds)
        if self.fail_times > 0:
            self.fail_times -= 1
            logger.warning("fake provider failure operation=%s", request.operation)
            raise LLMProviderError("simulated provider failure", retryable=True)
        if self.invalid_json_times > 0:
            self.invalid_json_times -= 1
            logger.warning("fake invalid json operation=%s", request.operation)
            return LLMResponse(
                text="这不是 JSON",
                model=self._model_name,
                prompt_version="",
                latency_ms=1,
            )
        payload = request.context.get("payload")
        if self.responder is not None:
            data = self.responder(request)
        else:
            data = RESPONDERS[request.operation](request)
        if isinstance(data, str):
            text = data
        else:
            text = json.dumps(data, ensure_ascii=False)
        logger.info("fake response operation=%s chars=%s", request.operation, len(text))
        return LLMResponse(
            text=text,
            model=self._model_name,
            prompt_version="",
            latency_ms=1,
        )


def fake_llm_from_payload(request: LLMRequest) -> dict[str, Any]:
    """Kept for backwards-compatible imports in tests."""
    return RESPONDERS[request.operation](request)
