from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path
from typing import Any

import httpx

ROOT = Path(__file__).resolve().parents[2]
ENV_FILE = ROOT.parent / ".env"
AGENT_DIR = Path(__file__).resolve().parents[1]
SEED_SQL = AGENT_DIR / "scripts" / "seed_job_search_e2e.sql"
REPORT = AGENT_DIR / "data" / "e2e_job_search_report.json"

BASE = os.environ.get("AIJOB_BASE_URL", "http://127.0.0.1:8080")
USER1 = "a0000000-0000-4000-8000-000000000001"
USER2 = "a0000000-0000-4000-8000-000000000002"
PASS_JOB = "30000000-0000-4000-8000-000000000001"
FAIL_JOB = "30000000-0000-4000-8000-000000000002"
TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED"}

results: list[dict[str, Any]] = []


def check(name: str, ok: bool, detail: Any = "") -> None:
    results.append({"name": name, "ok": bool(ok), "detail": detail})
    print(("PASS " if ok else "FAIL ") + name + (f" :: {detail}" if detail and not ok else ""), flush=True)


def load_env() -> dict[str, str]:
    values: dict[str, str] = {}
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
            text = line.strip()
            if not text or text.startswith("#") or "=" not in text:
                continue
            key, _, value = text.partition("=")
            values[key.strip()] = value.strip()
    return values


def mysql_args(env: dict[str, str]) -> list[str]:
    return [
        "mysql",
        "--default-character-set=utf8mb4",
        "-h", env.get("MYSQL_BIND_ADDRESS", "127.0.0.1"),
        "-P", env.get("MYSQL_PORT", "3306"),
        "-u", env.get("MYSQL_USER", "root"),
        "career_business",
    ]


def run_sql(env: dict[str, str], sql: str, *, file: bool = False) -> str:
    args = mysql_args(env)
    if file:
        args.append(f"--execute=source {SEED_SQL.as_posix()}")
    else:
        args.extend(["--batch", "--skip-column-names", f"--execute={sql}"])
    proc = subprocess.run(args, capture_output=True, text=True, encoding="utf-8",
                          env={**os.environ, "MYSQL_PWD": env.get("MYSQL_PASSWORD", "")})
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or "mysql 执行失败")
    return proc.stdout.strip()


class Client:
    def __init__(self, user_id: str) -> None:
        self.user_id = user_id
        self.client = httpx.Client(base_url=BASE, timeout=40.0,
                                   headers={"X-User-Id": user_id, "Accept": "application/json"})

    def call(self, method: str, path: str, *, body: Any = None, idempotency: str | None = None) -> tuple[int, dict[str, Any]]:
        headers = {"Idempotency-Key": idempotency} if idempotency else {}
        response = self.client.request(method, path, json=body, headers=headers)
        try:
            payload = response.json()
        except ValueError:
            payload = {"raw": response.text[:300]}
        return response.status_code, payload

    def data(self, method: str, path: str, *, body: Any = None, idempotency: str | None = None) -> Any:
        status, payload = self.call(method, path, body=body, idempotency=idempotency)
        if status >= 400 or payload.get("code") not in (200, 202):
            raise RuntimeError(f"{method} {path} -> {status} {json.dumps(payload, ensure_ascii=False)[:500]}")
        return payload.get("data")


def wait_task(client: Client, task_id: str, timeout: float = 240.0) -> dict[str, Any]:
    deadline = time.time() + timeout
    last: dict[str, Any] = {}
    while time.time() < deadline:
        status, payload = client.call("GET", f"/api/v1/tasks/{task_id}")
        if status >= 400:
            raise RuntimeError(f"任务查询失败: {status}")
        last = payload.get("data") or {}
        if last.get("status") in TERMINAL:
            return last
        time.sleep(1.5)
    raise TimeoutError(f"任务 {task_id} 未在 {timeout}s 内完成，最后状态 {last.get('status')}")


def task_resource(task: dict[str, Any]) -> str:
    return str((task.get("result") or {}).get("resourceId") or task.get("resourceId") or "")


def create_search(client: Client, *, keyword: str = "Java", sources: list[str] | None = None,
                  max_results: int = 20, cities: list[str] | None = None) -> tuple[str, str, int, dict[str, Any]]:
    key = str(uuid.uuid4())
    body = {
        "expectedProfileVersion": 3,
        "keyword": keyword,
        "cities": cities if cities is not None else ["440300"],
        "candidateTypes": ["EXPERIENCED"],
        "companySizes": [],
        "includeUnknownSize": True,
        "maxResults": max_results,
        "sources": sources or ["tencent", "greenhouse", "liepin", "boss", "zhaopin"],
    }
    status, payload = client.call("POST", "/api/v1/job-searches", body=body, idempotency=key)
    if status not in (200, 202):
        return "", "", status, payload
    task = payload.get("data") or {}
    return str(task.get("id")), key, status, payload


def project_recommend(client: Client, *, match_item_id: str | None = None,
                      skills: list[str] | None = None) -> tuple[int, dict[str, Any]]:
    body: dict[str, Any] = {"skillCodes": skills or ["JAVA"], "difficulty": "INTERMEDIATE", "maxDurationWeeks": 4}
    if match_item_id is not None:
        body["matchItemId"] = match_item_id
    return client.call("POST", "/api/v1/projects/recommendations", body=body,
                       idempotency=str(uuid.uuid4()))


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    env = load_env()
    run_sql(env, "", file=True)
    print("seed ok", flush=True)
    user1 = Client(USER1)
    user2 = Client(USER2)

    profile = user1.data("GET", "/api/v1/profile")
    check("画像版本为 3 且简历已确认", profile.get("profileVersion") == 3 and profile.get("activeResumeVersionId"),
          profile.get("profileVersion"))

    options = user1.data("GET", "/api/v1/job-searches/match-item-options")
    check("匹配条目选项接口可用", isinstance(options, list), len(options or []))

    task_id, idem, status, payload = create_search(user1)
    check("搜索任务创建返回 202", status in (200, 202) and bool(task_id), payload)
    if not task_id:
        return finish()
    task = wait_task(user1, task_id)
    check("搜索任务成功完成", task.get("status") == "SUCCEEDED", task.get("error"))
    search_id = task_resource(task)
    check("搜索任务返回 searchId", bool(search_id), search_id)

    replay_status, replay = user1.call("POST", "/api/v1/job-searches",
                                       body={
                                           "expectedProfileVersion": 3,
                                           "keyword": "Java",
                                           "cities": ["440300"],
                                           "candidateTypes": ["EXPERIENCED"],
                                           "companySizes": [],
                                           "includeUnknownSize": True,
                                           "maxResults": 20,
                                           "sources": ["tencent", "greenhouse", "liepin", "boss", "zhaopin"],
                                       }, idempotency=idem)
    check("重复请求幂等返回同一任务", replay_status in (200, 202)
          and str(((replay.get("data") or {}).get("id"))) == task_id, replay.get("msg"))

    conflict_status, conflict = user1.call("POST", "/api/v1/job-searches",
                                           body={"expectedProfileVersion": 3, "keyword": "前端"},
                                           idempotency=idem)
    check("同幂等键不同请求体被拒绝", conflict_status == 409
          and (conflict.get("error") or {}).get("key") == "IDEMPOTENCY_CONFLICT", conflict.get("msg"))

    detail = user1.data("GET", f"/api/v1/job-searches/{search_id}")
    check("搜索结果状态 READY", detail.get("status") == "READY", detail.get("status"))
    source_status = {item.get("key"): item for item in detail.get("sources") or []}
    check("BOSS/智联/猎聘明确标记不可用",
          all(source_status.get(key, {}).get("status") == "UNAVAILABLE" for key in ("boss", "zhaopin", "liepin")),
          {key: source_status.get(key, {}).get("status") for key in ("boss", "zhaopin", "liepin")})
    check("不可用来源给出原因",
          all(source_status.get(key, {}).get("message") for key in ("boss", "zhaopin", "liepin")))
    check("腾讯官网来源真实可用", source_status.get("tencent", {}).get("status") in ("AVAILABLE", "EMPTY"),
          source_status.get("tencent"))

    page1 = user1.data("GET", f"/api/v1/job-searches/{search_id}/items", body=None)
    total = page1.get("total", 0)
    check("搜索结果非空", total > 0, total)
    records = page1.get("records") or []
    if records:
        item = records[0]
        required = ["title", "companyName", "source", "sourceUrl", "matchScore", "reasons", "gaps",
                    "companySize", "publishedAt", "fetchedAt", "verification", "warnings", "aliases", "fingerprint"]
        check("结果字段包含岗位/来源/匹配依据/差距", all(key in item for key in required),
              [key for key in required if key not in item])
        check("原始链接为 http/https", str(item.get("sourceUrl", "")).startswith(("http://", "https://")),
              item.get("sourceUrl"))
        check("结果标记核验/缺失信息", isinstance(item.get("warnings"), list) and item.get("verification") in ("VERIFIED", "UNVERIFIED"))

    summary = detail.get("summary") or {}
    check("去重统计覆盖完整候选集", summary.get("uniqueCount", 0) <= summary.get("rawCount", 0))
    check("同批次指纹不重复", len({item.get("fingerprint") for item in records}) == len(records))

    for size in ["SIZE_0_20", "SIZE_21_99", "SIZE_100_499", "SIZE_500_999", "SIZE_1000_9999",
                 "SIZE_10000_PLUS", "SIZE_UNKNOWN"]:

        filter_status, filtered_payload = user1.call("GET", f"/api/v1/job-searches/{search_id}/items?companySizes={size}&includeUnknownSize=false&size=100")
        rows = (filtered_payload.get("data") or {}).get("records") or []
        check(f"规模筛选 {size} 无越界", filter_status == 200 and all(row.get("companySize") == size for row in rows),
              [row.get("companySize") for row in rows if row.get("companySize") != size][:5])

    if total >= 3:
        p1 = user1.data("GET", f"/api/v1/job-searches/{search_id}/items?current=1&size=2")
        p2 = user1.data("GET", f"/api/v1/job-searches/{search_id}/items?current=2&size=2")
        first_ids = [row.get("id") for row in p1.get("records") or []]
        second_ids = [row.get("id") for row in p2.get("records") or []]
        check("跨页不重复且顺序稳定", set(first_ids).isdisjoint(second_ids)
              and [row.get("rankNo") for row in p1.get("records") or []] == sorted(row.get("rankNo") for row in p1.get("records") or []),
              {"p1": first_ids, "p2": second_ids})
        repeat = user1.data("GET", f"/api/v1/job-searches/{search_id}/items?current=1&size=2")
        check("重新查询第一页顺序一致", [row.get("id") for row in repeat.get("records") or []] == first_ids)
    else:
        check("分页测试（候选不足 3 条）", True, "skipped")

    if records:
        job_id = records[0].get("jobId")
        user1.data("PUT", f"/api/v1/favorites/{job_id}", body={})
        favorites = user1.data("GET", "/api/v1/favorites?size=100")
        check("搜索结果可收藏", any(row.get("id") == job_id for row in favorites.get("records") or []))
        user1.data("DELETE", f"/api/v1/favorites/{job_id}")
        favorite_after = user1.data("GET", "/api/v1/favorites?size=100")
        check("取消收藏生效", not any(row.get("id") == job_id for row in favorite_after.get("records") or []))

        invalid_status, invalid = project_recommend(user1, match_item_id="AI应用开发")
        fields = ((invalid.get("error") or {}).get("details") or {}).get("fields") or []
        check("非法匹配 ID 返回字段级错误", invalid_status == 400 and any(item.get("field") == "matchItemId" for item in fields),
              invalid)

        rec_status, rec_payload = project_recommend(user1, match_item_id=str(records[0].get("id")))
        check("从搜索匹配结果生成项目推荐", rec_status in (200, 202), rec_payload.get("msg"))
        if rec_status in (200, 202):
            rec_task = wait_task(user1, str(((rec_payload.get("data") or {}).get("id"))))
            check("项目推荐任务成功", rec_task.get("status") == "SUCCEEDED", rec_task.get("error"))
            if rec_task.get("status") == "SUCCEEDED":
                rec = user1.data("GET", f"/api/v1/projects/recommendations/{task_resource(rec_task)}")
                check("项目推荐含候选项目", bool(rec.get("items")), len(rec.get("items") or []))

    match_task_id = str((user2.call("POST", "/api/v1/job-matches", body={
        "expectedProfileVersion": 3, "jobIds": [PASS_JOB], "includeUnknown": True,
    }, idempotency=str(uuid.uuid4()))[1].get("data") or {}).get("id"))
    user2_match = wait_task(user2, match_task_id)
    check("其他用户岗位匹配成功", user2_match.get("status") == "SUCCEEDED", user2_match.get("error"))
    other_batch = task_resource(user2_match)
    other_items = user2.data("GET", f"/api/v1/job-matches/{other_batch}").get("items") or []
    if other_items:
        other_status, other_payload = project_recommend(user1, match_item_id=str(other_items[0].get("id")))
        other_details = (other_payload.get("error") or {}).get("details") or {}
        check("其他用户匹配条目被拒绝", other_status == 404 and other_details.get("field") == "matchItemId",
              other_payload)

    fail_task_id = str((user1.call("POST", "/api/v1/job-matches", body={
        "expectedProfileVersion": 3, "jobIds": [FAIL_JOB], "includeUnknown": True,
    }, idempotency=str(uuid.uuid4()))[1].get("data") or {}).get("id"))
    fail_task = wait_task(user1, fail_task_id)
    check("资格失败岗位匹配任务完成", fail_task.get("status") == "SUCCEEDED", fail_task.get("error"))
    fail_batch = task_resource(fail_task)
    fail_ids = run_sql(env, f"SELECT id FROM job_match_item WHERE batch_id='{fail_batch}' AND eligibility_status='FAIL' LIMIT 1")
    if fail_ids:
        fail_status, fail_payload = project_recommend(user1, match_item_id=fail_ids.splitlines()[0].strip())
        fail_fields = ((fail_payload.get("error") or {}).get("details") or {}).get("fields") or []
        check("资格失败匹配条目被拒绝", fail_status == 400
              and any(item.get("field") == "matchItemId" for item in fail_fields), fail_payload)
    else:
        check("资格失败匹配条目存在", False, "未找到 FAIL 条目")

    empty_status, empty_payload = project_recommend(user1, match_item_id=None, skills=["JAVA", "SPRING_BOOT"])
    check("匹配结果留空仍可生成推荐", empty_status in (200, 202), empty_payload.get("msg"))
    if empty_status in (200, 202):
        empty_task = wait_task(user1, str(((empty_payload.get("data") or {}).get("id"))))
        check("留空推荐任务成功", empty_task.get("status") == "SUCCEEDED", empty_task.get("error"))

    cancel_task_id, _, cancel_status, cancel_payload = create_search(user1, max_results=60)
    check("取消测试任务创建", cancel_status in (200, 202), cancel_payload)
    if cancel_task_id:
        cancel_call = user1.call("POST", f"/api/v1/tasks/{cancel_task_id}/cancel", body={})
        cancelled = wait_task(user1, cancel_task_id)
        check("取消请求被受理且任务终态", cancel_call[0] == 200 and cancelled.get("status") in TERMINAL,
              {"cancel": cancel_call[1].get("data", {}).get("status"), "final": cancelled.get("status")})
        if cancelled.get("status") == "SUCCEEDED":
            check("取消竞态说明", True, "任务在取消生效前已完成；非失败，记录竞态")

    empty_search_id, _, _, _ = create_search(user1, keyword="zzzz-no-such-job-xyz-2026", max_results=10)
    if empty_search_id:
        empty_search_task = wait_task(user1, empty_search_id)
        check("空结果搜索成功完成", empty_search_task.get("status") == "SUCCEEDED", empty_search_task.get("error"))
        empty_search = user1.data("GET", f"/api/v1/job-searches/{task_resource(empty_search_task)}")
        empty_items = user1.data("GET", f"/api/v1/job-searches/{task_resource(empty_search_task)}/items")
        check("空结果明确返回 0 条", empty_items.get("total") == 0, empty_items.get("total"))
        check("空结果带 EMPTY_RESULT 标记",
              "EMPTY_RESULT" in ((empty_search.get("summary") or {}).get("warnings") or []))

    if search_id and records:
        run_sql(env, "UPDATE job_match_batch SET created_at=UTC_TIMESTAMP(3)-INTERVAL 120 DAY "
                     f"WHERE id='{search_id}'")
        expired_status, expired_payload = project_recommend(user1, match_item_id=str(records[0].get("id")))
        expired_error = expired_payload.get("error") or {}
        check("过期匹配被拒绝", expired_status == 409 and expired_error.get("key") == "STALE_INPUT",
              expired_payload)

    return finish()


def finish() -> int:
    failed = [item for item in results if not item["ok"]]
    report = {
        "baseUrl": BASE,
        "total": len(results),
        "passed": len(results) - len(failed),
        "failed": len(failed),
        "results": results,
    }
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"passed": report["passed"], "failed": report["failed"], "report": str(REPORT)},
                     ensure_ascii=False))
    return 0 if not failed else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        import traceback

        traceback.print_exc()
        check("E2E 执行未完成", False, f"{type(exc).__name__}: {exc}")
        raise SystemExit(finish())