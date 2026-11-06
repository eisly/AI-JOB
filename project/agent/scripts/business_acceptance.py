from __future__ import annotations

import argparse
import io
import json
import time
import uuid
from pathlib import Path
from typing import Any

import requests
from docx import Document

BASE_URL = "http://127.0.0.1:8080"
AGENT_URL = "http://127.0.0.1:8100"
USER_ID = "10000000-0000-4000-8000-00000000d001"
OTHER_USER_ID = "10000000-0000-4000-8000-00000000d002"
PUBLIC_JOB_ID = "30000000-0000-4000-8000-00000000d001"
RESULT_PATH = Path(__file__).resolve().parents[1] / "data" / "business_acceptance_result.json"
TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED"}


def key() -> str:
    return str(uuid.uuid4())


def call(method: str, path: str, *, user: str | None = USER_ID, expected: int | tuple[int, ...] = (200,),
         idem: str | None = None, **kwargs: Any) -> requests.Response:
    headers = dict(kwargs.pop("headers", {}))
    query = kwargs.pop("query", None)
    if user:
        headers["X-User-Id"] = user
    if idem:
        headers["Idempotency-Key"] = idem
    response = requests.request(method, BASE_URL + path, headers=headers, params=query, timeout=90, **kwargs)
    allowed = (expected,) if isinstance(expected, int) else expected
    if response.status_code not in allowed:
        raise AssertionError(f"{method} {path} -> {response.status_code}: {response.text[:2000]}")
    return response


def data(method: str, path: str, **kwargs: Any) -> Any:
    response = call(method, path, **kwargs)
    payload = response.json()
    if payload.get("code") not in (200, 202):
        raise AssertionError(f"业务响应失败: {json.dumps(payload, ensure_ascii=False)[:2000]}")
    return payload.get("data")


def post_task(path: str, body: dict[str, Any] | None = None, idem: str | None = None) -> tuple[dict[str, Any], str]:
    request_key = idem or key()
    task = data("POST", path, json=body or {}, idem=request_key, expected=202)
    replay = data("POST", path, json=body or {}, idem=request_key, expected=202)
    if replay["id"] != task["id"]:
        raise AssertionError(f"幂等重放返回了不同任务: {path}")
    return task, request_key


def get_task(task_id: str) -> dict[str, Any]:
    return data("GET", f"/api/v1/tasks/{task_id}")


def wait_task(task_id: str, expected: str = "SUCCEEDED", timeout: float = 90) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    latest: dict[str, Any] = {}
    while time.monotonic() < deadline:
        latest = get_task(task_id)
        if latest["status"] == expected:
            return latest
        if latest["status"] in TERMINAL:
            raise AssertionError(f"任务进入非预期终态: {json.dumps(latest, ensure_ascii=False)}")
        time.sleep(0.25)
    raise AssertionError(f"任务等待超时: {json.dumps(latest, ensure_ascii=False)}")


def verify_sse(task_id: str) -> None:
    response = call("GET", f"/api/v1/tasks/{task_id}/events", headers={"Last-Event-ID": "0"}, stream=True)
    events: list[str] = []
    ids: list[str] = []
    for raw in response.iter_lines(decode_unicode=True):
        line = raw or ""
        if line.startswith("event:"):
            events.append(line.split(":", 1)[1].strip())
        if line.startswith("id:"):
            ids.append(line.split(":", 1)[1].strip())
        if "done" in events or "error" in events or "cancelled" in events:
            break
    response.close()
    if not events or events[0] != "accepted" or "done" not in events or len(ids) != len(set(ids)):
        raise AssertionError(f"SSE事件不完整或重复: events={events}, ids={ids}")


def resume_document() -> bytes:
    document = Document()
    document.add_heading("张三的 Java 后端简历", level=1)
    document.add_paragraph("学历：本科；专业：计算机科学与技术；预计毕业时间：2027年6月。")
    document.add_paragraph("全职工作经验：3年，共36个月。")
    document.add_paragraph("技能：Java、Spring Boot、MySQL、Redis。")
    document.add_paragraph("项目经历：校园二手交易平台，负责 Java 与 Spring Boot 接口开发，使用 MySQL 持久化数据。")
    document.add_paragraph("工作经历：参与业务系统需求分析、后端开发、联调与测试。")
    output = io.BytesIO()
    document.save(output)
    return output.getvalue()


def upload_resume(content: bytes) -> tuple[dict[str, Any], str]:
    request_key = key()
    files = {"file": ("acceptance-resume.docx", content, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")}
    uploaded = data("POST", "/api/v1/resumes/upload", data={"title": "验收简历"}, files=files, idem=request_key)
    files = {"file": ("acceptance-resume.docx", content, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")}
    replay = data("POST", "/api/v1/resumes/upload", data={"title": "验收简历"}, files=files, idem=request_key)
    if replay["id"] != uploaded["id"] or replay["latestVersion"]["id"] != uploaded["latestVersion"]["id"]:
        raise AssertionError("上传幂等重放创建了不同资源")
    files = {"file": ("acceptance-resume.docx", content, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")}
    conflict = call("POST", "/api/v1/resumes/upload", data={"title": "不同标题"}, files=files,
                    idem=request_key, expected=409).json()
    if conflict.get("code") != 40903:
        raise AssertionError(f"同键异体未返回40903: {conflict}")
    return uploaded, request_key


def wait_interview_ready(interview_id: str) -> dict[str, Any]:
    deadline = time.monotonic() + 90
    latest: dict[str, Any] = {}
    while time.monotonic() < deadline:
        latest = data("GET", f"/api/v1/interviews/{interview_id}")
        if latest["status"] in {"WAITING_ANSWER", "COMPLETED", "FAILED"}:
            return latest
        pending = latest.get("pendingTaskId")
        if pending:
            task = get_task(pending)
            if task["status"] in {"FAILED", "CANCELLED", "EXPIRED"}:
                raise AssertionError(f"面试任务失败: {json.dumps(task, ensure_ascii=False)}")
        time.sleep(0.25)
    raise AssertionError(f"面试状态等待超时: {latest}")


def close_waiting_interviews() -> None:
    page = data("GET", "/api/v1/interviews?size=50")
    for interview in page["records"]:
        if interview["status"] == "WAITING_ANSWER":
            task, _ = post_task(f"/api/v1/interviews/{interview['id']}/end",
                                {"expectedVersion": interview["version"]})
            wait_task(task["id"])
        elif interview["status"] in {"PREPARING", "GENERATING", "ENDING"}:
            raise AssertionError(f"存在无法自动清理的活动面试: {interview['id']} {interview['status']}")


def run() -> dict[str, Any]:
    ready = requests.get(AGENT_URL + "/readyz", timeout=10)
    ready.raise_for_status()
    if ready.json().get("provider") != "fake":
        raise AssertionError("本脚本必须使用明确标记的fake模型服务")

    unauthenticated = call("GET", "/api/v1/profile", user=None, expected=401).json()
    if unauthenticated.get("code") != 40101:
        raise AssertionError("未认证访问未返回40101")

    close_waiting_interviews()
    profile = data("GET", "/api/v1/profile")
    profile = data("PUT", "/api/v1/profile/preferences", json={
        "expectedProfileVersion": profile["profileVersion"],
        "targetRole": "JAVA_BACKEND",
        "candidateType": "EXPERIENCED",
        "cities": ["110100"],
        "minMonthlySalaryCents": 1500000,
        "maxMonthlySalaryCents": 3000000,
    })

    uploaded, _ = upload_resume(resume_document())
    resume_id = uploaded["id"]
    version_id = uploaded["latestVersion"]["id"]
    versions = data("GET", f"/api/v1/resumes/{resume_id}/versions")
    if len(versions) != 1:
        raise AssertionError("重复上传产生了额外版本")

    forbidden = call("GET", f"/api/v1/resume-versions/{version_id}", user=OTHER_USER_ID, expected=404).json()
    if forbidden.get("code") != 40401:
        raise AssertionError("跨用户简历访问未返回40401")

    parse_task, _ = post_task(f"/api/v1/resume-versions/{version_id}/parse")
    wait_task(parse_task["id"])
    verify_sse(parse_task["id"])
    version = data("GET", f"/api/v1/resume-versions/{version_id}")
    if version["status"] != "PARSED":
        raise AssertionError(f"简历未进入PARSED: {version}")

    source_text = "技能：Java、Spring Boot、MySQL、Redis。"
    facts = {
        "education": "BACHELOR",
        "graduationYear": 2027,
        "experienceMonths": 36,
        "major": "计算机科学与技术",
        "skills": [{"skillCode": code, "claim": f"简历自述掌握{code}", "quote": source_text,
                    "location": "document:skills", "confirmed": True}
                   for code in ["JAVA", "SPRING_BOOT", "MYSQL", "REDIS"]],
        "warnings": [],
    }
    profile = data("GET", "/api/v1/profile")
    before = data("GET", "/api/v1/growth")
    before_java = next((item for item in before["skills"] if item["skillCode"] == "JAVA"), None)
    confirm_key = key()
    confirmed = data("POST", f"/api/v1/resume-versions/{version_id}/confirm",
                     json={"expectedProfileVersion": profile["profileVersion"], "facts": facts}, idem=confirm_key)
    confirm_replay = data("POST", f"/api/v1/resume-versions/{version_id}/confirm",
                          json={"expectedProfileVersion": profile["profileVersion"], "facts": facts}, idem=confirm_key)
    if confirm_replay["activeResumeVersionId"] != confirmed["activeResumeVersionId"]:
        raise AssertionError("确认事实幂等重放失败")

    growth = data("GET", "/api/v1/growth")
    after_java = next((item for item in growth["skills"] if item["skillCode"] == "JAVA"), None)
    if (before_java and before_java.get("score"), before_java and before_java.get("sampleCount")) != (
            after_java and after_java.get("score"), after_java and after_java.get("sampleCount")):
        raise AssertionError("简历自述改变了能力分或回答样本数")
    claims = data("GET", "/api/v1/growth/evidence?sourceType=RESUME_CLAIM&size=100")
    if not claims["records"] or any(item.get("score") is not None for item in claims["records"]):
        raise AssertionError("简历自述证据缺少或被错误赋分")

    jd_text = "负责 Java 后端服务设计、开发、测试与维护。要求本科及以上学历，具备三年或以上全职后端开发经验，熟悉 Java、Spring Boot、MySQL、Redis，能够完成系统设计、数据库设计、接口联调和清晰的技术文档编写。"
    jd_task, _ = post_task("/api/v1/jobs/imports", {
        "title": "Java 后端开发工程师（验收）",
        "companyName": "验收测试公司",
        "jdText": jd_text,
    })
    wait_task(jd_task["id"])
    job_id = jd_task["resourceId"]
    job = data("GET", f"/api/v1/jobs/{job_id}")
    if job["status"] != "ACTIVE" or not job.get("requirements"):
        raise AssertionError(f"JD分析未生效: {job}")

    data("PUT", f"/api/v1/favorites/{job_id}", json={})
    data("PUT", f"/api/v1/favorites/{job_id}", json={})
    favorites = data("GET", "/api/v1/favorites")
    if not any(item["id"] == job_id for item in favorites["records"]):
        raise AssertionError("岗位收藏未持久化")
    data("DELETE", f"/api/v1/favorites/{job_id}")
    data("DELETE", f"/api/v1/favorites/{job_id}")

    profile = data("GET", "/api/v1/profile")
    match_task, _ = post_task("/api/v1/job-matches", {
        "expectedProfileVersion": profile["profileVersion"],
        "jobIds": [job_id, PUBLIC_JOB_ID],
        "includeUnknown": True,
    })
    wait_task(match_task["id"])
    match_id = match_task["resourceId"]
    match = data("GET", f"/api/v1/job-matches/{match_id}")
    if match["status"] != "READY" or not match["items"]:
        raise AssertionError(f"岗位匹配无有效结果: {match}")
    for item in match["items"]:
        if item["eligibility"]["status"] == "FAIL" or len(item["components"]) != 3:
            raise AssertionError("FAIL进入主匹配列表或评分组成不完整")
        if item.get("matchScore") is not None and not 0 <= item["matchScore"] <= 100:
            raise AssertionError("匹配分超出范围")
    match_item = next(item for item in match["items"] if item["jobId"] == job_id)

    plan_task, _ = post_task("/api/v1/learning/plans", {
        "matchItemId": match_item["id"], "dailyMinutes": 60, "days": 7,
    })
    wait_task(plan_task["id"])
    plan_id = plan_task["resourceId"]
    plan = data("GET", f"/api/v1/learning/plans/{plan_id}")
    if plan["status"] != "ACTIVE" or not plan["items"]:
        raise AssertionError(f"训练计划未生成: {plan}")

    reading = next((item for item in plan["items"] if item["kind"] == "READING"), None)
    if reading:
        data("PUT", f"/api/v1/learning/plans/{plan_id}/items/{reading['id']}/completion", json={})
    practice = next(item for item in plan["items"] if item["kind"] == "PRACTICE")
    interview_item = next(item for item in plan["items"] if item["kind"] == "INTERVIEW")

    session_task, _ = post_task("/api/v1/learning/sessions", {"planId": plan_id, "planItemId": practice["id"]})
    wait_task(session_task["id"])
    session_id = session_task["resourceId"]
    session = data("GET", f"/api/v1/learning/sessions/{session_id}")
    if session["status"] != "ACTIVE" or not session.get("question"):
        raise AssertionError(f"训练题目未就绪: {session}")
    if "referenceAnswer" in session["question"] or "rubric" in session["question"]:
        raise AssertionError("外部题目接口泄露参考答案或评分细则")

    answer_body = {"content": "先分析缓存穿透产生的原因，使用参数校验和布隆过滤器拦截不存在的键，对空结果设置较短过期时间，并结合限流、监控和降级保护数据库。会比较各方案的优缺点与适用场景。",
                   "expectedVersion": session["version"]}
    answer_task, _ = post_task(f"/api/v1/learning/sessions/{session_id}/answers", answer_body)
    wait_task(answer_task["id"])
    answer = data("GET", f"/api/v1/learning/answers/{answer_task['resourceId']}")
    if answer["status"] != "EVALUATED" or not answer.get("evaluation"):
        raise AssertionError(f"训练评价未完成: {answer}")

    interview_task, _ = post_task("/api/v1/interviews", {
        "jobId": job_id, "resumeVersionId": version_id, "questionLimit": 3, "planItemId": interview_item["id"],
    })
    interview_id = interview_task["resourceId"]
    wait_task(interview_task["id"])
    interview = wait_interview_ready(interview_id)
    answers = 0
    while interview["status"] != "COMPLETED" and answers < 16:
        if interview["status"] != "WAITING_ANSWER":
            raise AssertionError(f"面试进入异常状态: {interview}")
        question = [item for item in interview["messages"] if item["role"] == "ASSISTANT"][-1]
        body = {"questionMessageId": question["id"],
                "content": "我会先澄清业务目标和约束，再给出 Java 与 Spring Boot 的实现方案、数据库一致性处理、测试结果和风险控制，并结合实际项目说明取舍。",
                "expectedVersion": interview["version"]}
        turn, _ = post_task(f"/api/v1/interviews/{interview_id}/answers", body)
        wait_task(turn["id"])
        interview = wait_interview_ready(interview_id)
        answers += 1
    if interview["status"] != "COMPLETED":
        raise AssertionError(f"面试未在限定轮次完成: {interview}")
    report = data("GET", f"/api/v1/interviews/{interview_id}/report")
    if report["answeredQuestionCount"] < 1:
        raise AssertionError("面试报告缺少有效回答")

    profile = data("GET", "/api/v1/profile")
    growth_task, _ = post_task("/api/v1/growth/suggestions", {"expectedProfileVersion": profile["profileVersion"]})
    wait_task(growth_task["id"])
    growth = data("GET", "/api/v1/growth")
    if not growth["suggestions"]:
        raise AssertionError("成长建议未持久化")
    independent = [item for item in growth["skills"] if item["sampleCount"] > 0]
    if not independent or any(item["score"] is None for item in independent):
        raise AssertionError("独立回答未形成有效能力证据")

    diagnosis_task, _ = post_task("/api/v1/resume-optimizations/diagnoses", {"resumeVersionId": version_id})
    wait_task(diagnosis_task["id"])
    diagnosis = data("GET", f"/api/v1/resume-optimizations/diagnoses/{diagnosis_task['resourceId']}")
    if diagnosis["status"] != "READY" or not isinstance(diagnosis["items"], list):
        raise AssertionError("简历诊断结果不可用")

    optimization_task, _ = post_task("/api/v1/resume-optimizations", {
        "resumeVersionId": version_id, "jobId": job_id, "diagnosisId": diagnosis["id"],
    })
    wait_task(optimization_task["id"])
    optimization_id = optimization_task["resourceId"]
    optimization = data("GET", f"/api/v1/resume-optimizations/{optimization_id}")
    if optimization["status"] != "READY" or not optimization["sections"]:
        raise AssertionError(f"定向优化未生成: {optimization}")
    accepted_sections = [{"sectionKey": item["sectionKey"], "title": item["title"], "content": item["suggestedText"]}
                         for item in optimization["sections"]]
    accepted = data("POST", f"/api/v1/resume-optimizations/{optimization_id}/accept", json={"sections": accepted_sections}, idem=key())
    if accepted["status"] != "ACCEPTED":
        raise AssertionError("优化稿采纳失败")
    pdf = call("GET", f"/api/v1/resume-optimizations/{optimization_id}/export", query={"format": "PDF"})
    docx = call("GET", f"/api/v1/resume-optimizations/{optimization_id}/export", query={"format": "DOCX"})
    if not pdf.content.startswith(b"%PDF") or not docx.content.startswith(b"PK"):
        raise AssertionError("导出文件魔数不正确")

    recommendation_task, _ = post_task("/api/v1/projects/recommendations", {
        "skillCodes": ["JAVA", "SPRING_BOOT"], "difficulty": "INTERMEDIATE", "maxDurationWeeks": 4,
    })
    wait_task(recommendation_task["id"])
    recommendation = data("GET", f"/api/v1/projects/recommendations/{recommendation_task['resourceId']}")
    if recommendation["status"] != "READY" or not recommendation["items"]:
        raise AssertionError("项目推荐为空")
    project_task, _ = post_task("/api/v1/projects/plans", {
        "recommendationItemId": recommendation["items"][0]["id"], "weeklyHours": 5,
    })
    wait_task(project_task["id"])
    project_id = project_task["resourceId"]
    project = data("GET", f"/api/v1/projects/{project_id}")
    tasks = [task for milestone in project["milestones"] for task in milestone["tasks"]]
    if project["status"] != "ACTIVE" or not tasks:
        raise AssertionError("项目实战计划无任务")

    first = tasks[0]
    project = data("PATCH", f"/api/v1/projects/{project_id}/tasks/{first['id']}",
                   json={"status": "COMPLETED", "expectedVersion": project["version"]})
    if not 0 < project["progress"] < 100:
        raise AssertionError("项目进度未按任务数重算")
    project = data("POST", f"/api/v1/projects/{project_id}/deliverables", idem=key(), json={
        "taskId": first["id"], "title": "核心功能实现与测试记录", "notes": "已保存实现说明与自测结果，链接不作为自动验收依据。",
    })
    if len(project["deliverables"]) != 1:
        raise AssertionError("项目交付记录未保存")
    for milestone in project["milestones"]:
        for task in milestone["tasks"]:
            current = data("GET", f"/api/v1/projects/{project_id}")
            current_task = next(item for group in current["milestones"] for item in group["tasks"] if item["id"] == task["id"])
            if current_task["status"] != "COMPLETED":
                data("PATCH", f"/api/v1/projects/{project_id}/tasks/{task['id']}",
                     json={"status": "COMPLETED", "expectedVersion": current["version"]})
    project = data("GET", f"/api/v1/projects/{project_id}")
    if project["status"] != "COMPLETED" or project["progress"] != 100:
        raise AssertionError("全部任务完成后项目状态或进度不正确")

    task_page = data("GET", "/api/v1/tasks", query={"size": 100})
    task_types = {item["type"] for item in task_page["records"]}
    required = {"RESUME_PARSE", "JD_ANALYZE", "JOB_MATCH", "LEARNING_PLAN", "LEARNING_QUESTION",
                "LEARNING_EVALUATE", "INTERVIEW_TURN", "INTERVIEW_REPORT", "GROWTH_ANALYZE",
                "RESUME_DIAGNOSE", "RESUME_OPTIMIZE", "PROJECT_RECOMMEND", "PROJECT_PLAN"}
    missing = required - task_types
    if missing:
        raise AssertionError(f"任务中心缺少任务类型: {sorted(missing)}")

    result = {
        "provider": "fake",
        "resumeId": resume_id,
        "resumeVersionId": version_id,
        "jobId": job_id,
        "matchId": match_id,
        "learningPlanId": plan_id,
        "interviewId": interview_id,
        "optimizationId": optimization_id,
        "projectId": project_id,
        "taskTypes": sorted(required),
        "interviewAnswers": answers,
        "status": "PASSED",
    }
    RESULT_PATH.parent.mkdir(parents=True, exist_ok=True)
    RESULT_PATH.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return result


def verify_saved() -> dict[str, Any]:
    saved = json.loads(RESULT_PATH.read_text(encoding="utf-8"))
    project = data("GET", f"/api/v1/projects/{saved['projectId']}")
    optimization = data("GET", f"/api/v1/resume-optimizations/{saved['optimizationId']}")
    match = data("GET", f"/api/v1/job-matches/{saved['matchId']}")
    report = data("GET", f"/api/v1/interviews/{saved['interviewId']}/report")
    if project["progress"] != 100 or optimization["status"] != "ACCEPTED" or match["status"] != "READY" or report["answeredQuestionCount"] < 1:
        raise AssertionError("重启后持久化资源状态不一致")
    return {"status": "PASSED", "projectProgress": project["progress"], "optimizationStatus": optimization["status"],
            "matchStatus": match["status"], "answeredQuestionCount": report["answeredQuestionCount"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify-saved", action="store_true")
    args = parser.parse_args()
    output = verify_saved() if args.verify_saved else run()
    print(json.dumps(output, ensure_ascii=False, indent=2))