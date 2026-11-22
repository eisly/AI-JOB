"""Budget acceptance against a dedicated Fake-model backend; authenticate normally.

The full scenario starts at question 1 and checks 2 main + 3 follow-ups, evaluation,
report completion and idempotency. Early-ending answers once before ending; the
no-answer scenario independently verifies the existing NO_VALID_ANSWER rule.
Credentials are read from environment variables, never command-line values.
"""
from __future__ import annotations
import argparse
import json
import os
import time
import uuid
from pathlib import Path
import requests

TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED"}

class BudgetClient:
    def __init__(self, base_url, email, password):
        self.base = base_url.rstrip("/")
        self.session = requests.Session()
        self.session.trust_env = False
        self.session.headers["Origin"] = "http://localhost:5173"
        response = self.session.post(self.base + "/api/v1/auth/login", json={"email":email,"password":password}, timeout=20)
        if response.status_code != 200:
            raise AssertionError("Test account login failed (HTTP %s)" % response.status_code)
        self.session.headers["Authorization"] = "Bearer " + response.json()["data"]["accessToken"]
        self.session.headers["X-CSRF-Token"] = response.headers["X-CSRF-Token"]

    def call(self, method, path, *, body=None, key=None, expected=(200,)):
        if isinstance(expected,int): expected=(expected,)
        headers={}
        if key: headers["Idempotency-Key"]=key
        response=self.session.request(method,self.base+path,json=body,headers=headers,timeout=30)
        if response.status_code not in expected:
            raise AssertionError("%s %s returned HTTP %s" % (method,path,response.status_code))
        return response.json() if response.status_code >= 400 else response.json()["data"]

    def detail(self, iid):
        return self.call("GET",f"/api/v1/interviews/{iid}")

    def wait_task(self, tid, timeout=60):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            task=self.call("GET",f"/api/v1/tasks/{tid}")
            if task["status"] in TERMINAL:
                if task["status"]!="SUCCEEDED": raise AssertionError("Task ended as "+task["status"])
                return task
            time.sleep(.3)
        raise AssertionError("Task did not finish")

    def wait_completed(self,iid,timeout=60):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            detail=self.detail(iid)
            if detail["status"]=="COMPLETED":return detail
            if detail["status"]=="FAILED":raise AssertionError("Interview failed")
            time.sleep(.3)
        raise AssertionError("Interview/report did not reach COMPLETED")


def run(client, interview_id, scenario="full", output_dir=None):
    checks=[]
    result={"interviewId":interview_id,"scenario":scenario,"steps":checks,"passed":False}
    directory=Path(output_dir or Path(__file__).resolve().parents[1]/"data")
    directory.mkdir(parents=True,exist_ok=True)
    path=directory/("interview_budget_"+time.strftime("%Y%m%d-%H%M%S")+"_"+uuid.uuid4().hex[:8]+".json")
    def check(name,condition,**detail):
        checks.append({"step":name,"passed":bool(condition),"detail":detail})
        if not condition:raise AssertionError(name)
    try:
        before=client.detail(interview_id)
        check("TOTAL_ASKED fresh waiting session",before.get("questionCountRule")=="TOTAL_ASKED" and before["status"]=="WAITING_ANSWER")
        limit=before["questionLimit"]
        if scenario in ("early", "no-answer"):
            check("early scenario starts at first question",before["askedQuestionCount"]==1)
            if scenario=="early":
                question=[m for m in before["messages"] if m["role"]=="ASSISTANT"][-1]
                task=client.call("POST",f"/api/v1/interviews/{interview_id}/answers",body={"questionMessageId":question["id"],"content":"我会分析需求与失败边界，并通过测试验证方案。","expectedVersion":before["version"]},key=str(uuid.uuid4()),expected=202)
                client.wait_task(task["id"])
                before=client.detail(interview_id)
                check("one valid answer before early end",before["askedQuestionCount"]==2 and before["status"]=="WAITING_ANSWER")
            asked=before["askedQuestionCount"]
            check("early-end precondition",0<asked<limit)
            task=client.call("POST",f"/api/v1/interviews/{interview_id}/end",body={"expectedVersion":before["version"]},key=str(uuid.uuid4()),expected=(200,202))
            client.wait_task(task["id"])
            final=client.wait_completed(interview_id)
            report=client.call("GET",f"/api/v1/interviews/{interview_id}/report")
            check("early end creates no question",final["askedQuestionCount"]==asked)
            expected_completion="EARLY_END" if scenario=="early" else "NO_VALID_ANSWER"
            check("early report completed",report["completionType"]==expected_completion,expected=expected_completion,actual=report["completionType"])
            check("early report answer count",report.get("answeredAskCount")==int(scenario=="early"))
        else:
            check("full scenario requires five slots and first question",limit==5 and before["askedQuestionCount"]==1)
            expected_numbers=[(1,0),(1,1),(1,2),(2,0),(2,1)]
            for index,(main,follow) in enumerate(expected_numbers,1):
                detail=client.detail(interview_id)
                questions=[m for m in detail["messages"] if m["role"]=="ASSISTANT"]
                check(f"round {index} count",detail["askedQuestionCount"]==index==len(questions) and detail["status"]=="WAITING_ANSWER")
                current=questions[-1]
                check(f"round {index} main/follow-up",current["questionNo"]==main and current["followUpNo"]==follow)
                refreshed=client.detail(interview_id)
                check(f"round {index} refresh preserves budget",refreshed["askedQuestionCount"]==index and refreshed["version"]==detail["version"])
                body={"questionMessageId":current["id"],"content":"我会分析需求与失败边界，并通过测试验证方案。","expectedVersion":detail["version"]}
                key=str(uuid.uuid4())
                task=client.call("POST",f"/api/v1/interviews/{interview_id}/answers",body=body,key=key,expected=202)
                check(f"round {index} answer accepted (including last)",bool(task.get("id")))
                replay=client.call("POST",f"/api/v1/interviews/{interview_id}/answers",body=body,key=key,expected=(200,202))
                check(f"round {index} replay returns same task",replay["id"]==task["id"])
                client.call("POST",f"/api/v1/interviews/{interview_id}/answers",body=body,key=str(uuid.uuid4()),expected=409)
                check(f"round {index} new key cannot duplicate answer",True)
                client.wait_task(task["id"])
            final=client.wait_completed(interview_id)
            report=client.call("GET",f"/api/v1/interviews/{interview_id}/report")
            questions=[m for m in final["messages"] if m["role"]=="ASSISTANT"]
            answers=[m for m in final["messages"] if m["role"]=="USER"]
            check("no sixth question or duplicate answer",len(questions)==len(answers)==final["askedQuestionCount"]==5)
            check("last evaluation included",report.get("answeredAskCount")==5)
            check("two main questions and normal report",report.get("answeredQuestionCount")==2 and report.get("completionType")=="NORMAL")
            client.call("POST",f"/api/v1/interviews/{interview_id}/answers",body={**body,"expectedVersion":final["version"]},key=str(uuid.uuid4()),expected=409)
            check("completed session rejects extra answer",client.detail(interview_id)["askedQuestionCount"]==5)
        result["passed"]=True
        return result
    except Exception as exc:
        result["failure"]=type(exc).__name__+": "+str(exc)
        raise
    finally:
        path.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
        result["resultPath"]=str(path)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--interview-id",required=True)
    parser.add_argument("--base-url",default="http://127.0.0.1:8081")
    parser.add_argument("--scenario",choices=("full","early","no-answer"),default="full")
    parser.add_argument("--output-dir",type=Path)
    args=parser.parse_args()
    email,password=os.environ.get("BUDGET_TEST_EMAIL"),os.environ.get("BUDGET_TEST_PASSWORD")
    if not email or not password:parser.error("Set BUDGET_TEST_EMAIL and BUDGET_TEST_PASSWORD; no dev-auth bypass is used")
    result=run(BudgetClient(args.base_url,email,password),args.interview_id,args.scenario,args.output_dir)
    print(json.dumps({"passed":result["passed"],"checks":len(result["steps"]),"resultPath":result["resultPath"]},ensure_ascii=False))

if __name__=="__main__":main()
