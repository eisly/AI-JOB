from __future__ import annotations

import json
import os
import sys
import threading
import time
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

os.environ["AGENT_AUTH_MODE"] = "disabled"
os.environ["AGENT_LLM_PROVIDER"] = "fake"
os.environ["AGENT_STORAGE_BACKEND"] = "memory"
os.environ["AGENT_LOG_LEVEL"] = "INFO"

import uvicorn

from app.api import create_app
from app.config import Settings

PORT = 18100
app = create_app(Settings())
server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=PORT, log_level="info"))
thread = threading.Thread(target=server.run, daemon=True)
thread.start()
for _ in range(100):
    if server.started:
        break
    time.sleep(0.1)
if not server.started:
    raise SystemExit("agent did not start")


def call(method: str, path: str, body: dict | None = None, headers: dict | None = None) -> dict:
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(f"http://127.0.0.1:{PORT}{path}", data=data, method=method)
    request.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


task_id = str(uuid.uuid4())
payload = {
    "taskId": task_id,
    "userId": str(uuid.uuid4()),
    "inputVersions": {"jobVersion": 1},
    "deadlineAt": (datetime.now(timezone.utc) + timedelta(seconds=60)).isoformat(),
    "payload": {
        "jobId": str(uuid.uuid4()),
        "jobVersion": 1,
        "jdText": "Java backend engineer. Java MySQL Redis required. Build reliable services for millions of users.",
        "skillCatalog": [
            {"code": "java", "name": "Java"},
            {"code": "mysql", "name": "MySQL"},
            {"code": "redis", "name": "Redis"},
        ],
    },
}
submitted = call("POST", "/internal/ai/v1/jobs/analyze", payload, {"Idempotency-Key": str(uuid.uuid4())})
time.sleep(1.5)
run = call("GET", f"/internal/ai/v1/runs?taskId={task_id}")
print(f"SUBMIT_CODE={submitted.get('code')} RUN_STATUS={run.get('data', {}).get('status')}")
server.should_exit = True
thread.join(timeout=10)