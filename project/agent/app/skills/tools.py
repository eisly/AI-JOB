from __future__ import annotations

from typing import Any

from ..llm import parse_json_object
from ..operations import RESULT_MODELS


def parse_model_output(text: str) -> dict[str, Any]:
    return parse_json_object(text)


def validate_result_contract(operation: str, result: dict[str, Any]) -> dict[str, Any]:
    validated = RESULT_MODELS[operation].model_validate(result)
    return validated.model_dump(by_alias=True, mode="json")


def _compact_messages(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result = []
    for item in messages[-40:]:
        result.append(
            {
                "id": item.get("id"),
                "seq": item.get("seq"),
                "role": item.get("role"),
                "content": str(item.get("content", ""))[:3000],
                "questionNo": item.get("questionNo"),
                "followUpNo": item.get("followUpNo"),
            }
        )
    return result


def build_model_input(
    operation: str,
    payload: dict[str, Any],
    context: dict[str, Any],
) -> dict[str, Any]:
    if operation == "RESUME_PARSE":
        return {
            "document": {
                "fileName": payload.get("document", {}).get("fileName"),
                "mimeType": payload.get("document", {}).get("mimeType"),
            },
            "skillCatalog": payload.get("skillCatalog") or [],
            "documentText": context.get("document_text", ""),
        }
    if operation == "RESUME_DIAGNOSE":
        return {
            "profileVersion": payload.get("profileVersion"),
            "documentText": context.get("document_text", ""),
        }
    if operation == "RESUME_OPTIMIZE":
        return {
            "jobId": payload.get("jobId"),
            "jobVersion": payload.get("jobVersion"),
            "jdText": str(payload.get("jdText", ""))[:30000],
            "diagnosis": payload.get("diagnosis"),
            "documentText": context.get("document_text", ""),
        }
    if operation == "JD_ANALYZE":
        sanitized = dict(payload)
        sanitized["jdText"] = sanitized.get("jdText", "")[:30000]
        return sanitized
    if operation == "JOB_MATCH":
        candidates = []
        for item in payload.get("candidates") or []:
            candidates.append(
                {
                    "jobId": item.get("jobId"),
                    "jobVersion": item.get("jobVersion"),
                    "title": item.get("title"),
                    "jdText": str(item.get("jdText", ""))[:4000],
                    "requirements": item.get("requirements") or {},
                }
            )
        return {
            "profile": payload.get("profile") or {},
            "skillSnapshot": payload.get("skillSnapshot") or [],
            "candidates": candidates,
        }
    if operation == "LEARNING_PLAN":
        resume = payload.get("resume") or None
        match_item = payload.get("matchItem") or None
        return {
            "planSource": payload.get("planSource") or "MATCH",
            "targetDirection": payload.get("targetDirection"),
            "matchItem": {
                "jobId": match_item.get("jobId"),
                "title": match_item.get("title"),
                "gaps": match_item.get("gaps") or [],
                "reasons": match_item.get("reasons") or [],
                "matchScore": match_item.get("matchScore"),
            }
            if match_item
            else None,
            "resume": {
                "versionId": resume.get("versionId"),
                "versionNo": resume.get("versionNo"),
                "title": resume.get("title"),
                "education": resume.get("education"),
                "experienceMonths": resume.get("experienceMonths"),
                "major": resume.get("major"),
                "skills": resume.get("skills") or [],
                "projectsText": str(resume.get("projectsText") or "")[:12000],
            }
            if resume
            else None,
            "trainingHistory": payload.get("trainingHistory") or None,
            "limitations": payload.get("limitations") or [],
            "github": context.get("github") or {"status": "UNAVAILABLE"},
            "dailyMinutes": payload.get("dailyMinutes"),
            "days": payload.get("days"),
            "allowedSkillCodes": payload.get("allowedSkillCodes") or [],
            "knowledgeRefs": payload.get("knowledgeRefs") or [],
        }
    if operation == "LEARNING_QUESTION":
        return {
            "planItem": payload.get("planItem") or {},
            "knowledgeRefs": payload.get("knowledgeRefs") or [],
        }
    if operation == "LEARNING_EVALUATE":
        return {
            "question": payload.get("question") or {},
            "answer": payload.get("answer"),
        }
    if operation == "GROWTH_ANALYZE":
        return {
            "profileVersion": payload.get("profileVersion"),
            "skills": payload.get("skills") or [],
            "evidence": payload.get("evidence") or [],
            "targetJobRequirements": payload.get("targetJobRequirements"),
        }
    if operation == "INTERVIEW_TURN":
        return {
            "profile": payload.get("profile") or {},
            "job": payload.get("job"),
            "messages": _compact_messages(payload.get("messages") or []),
            "latestAnswer": payload.get("latestAnswer"),
            "questionLimit": payload.get("questionLimit"),
            "maxFollowUps": payload.get("maxFollowUps"),
            "questionRubricVersion": payload.get("questionRubricVersion"),
        }
    if operation == "INTERVIEW_REPORT":
        return {
            "completionType": payload.get("completionType"),
            "messages": _compact_messages(payload.get("messages") or []),
            "turnEvaluations": payload.get("turnEvaluations") or [],
            "skillSnapshot": payload.get("skillSnapshot") or [],
        }
    if operation == "PROJECT_RECOMMEND":
        match_item = payload.get("matchItem") or {}
        return {
            "profile": payload.get("profile") or {},
            "allowedSkillCodes": payload.get("allowedSkillCodes") or [],
            "matchItem": {
                "jobId": match_item.get("jobId"),
                "jobVersion": match_item.get("jobVersion"),
                "title": match_item.get("title"),
                "gaps": match_item.get("gaps") or [],
                "reasons": match_item.get("reasons") or [],
            }
            if payload.get("matchItem") is not None
            else None,
            "difficulty": payload.get("difficulty"),
            "maxDurationWeeks": payload.get("maxDurationWeeks"),
        }
    if operation == "JOB_SEARCH":
        return {
            "profile": payload.get("profile") or {},
            "skillCatalog": payload.get("skillCatalog") or [],
            "filters": payload.get("filters") or {},
            "sources": payload.get("sources") or [],
            "limits": payload.get("limits") or {},
        }
    if operation == "PROJECT_PLAN":
        return {
            "recommendation": payload.get("recommendation") or {},
            "weeklyHours": payload.get("weeklyHours"),
        }
    raise ValueError(f"unknown operation {operation}")