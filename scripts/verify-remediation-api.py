#!/usr/bin/env python3
"""Verify the local stack with existing synthetic data and optional new AI tasks."""
import argparse
import json
import time
import uuid
from pathlib import Path

import requests


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--account-file', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--real-tasks', action='store_true')
    parser.add_argument('--answer-session', help='Submit an unknown answer to a dedicated audit session')
    parser.add_argument('--new-practice', action='store_true', help='Create a fresh practice from the audit session item')
    parser.add_argument('--seven-phase', help='Run a resumable seven-case acceptance phase')
    args = parser.parse_args()
    if args.seven_phase:
        from seven_acceptance import run
        run(args)
        return
    args.output.mkdir(parents=True, exist_ok=True)
    session = requests.Session()
    session.trust_env = False
    session.headers['Origin'] = 'http://localhost:5173'
    base = 'http://127.0.0.1:8080/api/v1'
    account = json.loads(args.account_file.read_text())
    login = session.post(base + '/auth/login', json={k: account[k] for k in ('email', 'password')}, timeout=20)
    login.raise_for_status()
    session.headers['Authorization'] = 'Bearer ' + login.json()['data']['accessToken']
    session.headers['X-CSRF-Token'] = login.headers['X-CSRF-Token']
    checks = []

    def record(name, result):
        checks.append({'check': name, **result})
        (args.output / 'api-results.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2))
        print(json.dumps(checks[-1], ensure_ascii=False), flush=True)

    def call(method, path, body=None):
        response = session.request(method, base + path, json=body,
                                   headers={'Idempotency-Key': str(uuid.uuid4())}, timeout=35)
        response.raise_for_status()
        return response.json()['data']

    profile = call('GET', '/profile')
    for name, path in [('profile', '/profile'), ('resumes', '/resumes'), ('training', '/learning/plans'),
                       ('growth', '/growth'), ('tasks', '/tasks'), ('applications', '/applications')]:
        response = session.get(base + path, timeout=20)
        record(name, {'http': response.status_code, 'status': 'PASS' if response.ok else 'FAIL'})
    health = call('GET', '/job-searches/source-health')
    (args.output / 'source-health.json').write_text(json.dumps(health, ensure_ascii=False, indent=2))
    optimizations = call('GET', '/resume-optimizations')['records']
    accepted = next((item for item in optimizations if item['status'] == 'ACCEPTED'), None)
    if accepted:
        for fmt in ('PDF', 'DOCX'):
            response = session.get(base + f'/resume-optimizations/{accepted["id"]}/export',
                                   params={'format': fmt}, timeout=35)
            valid = response.ok and response.content.startswith(b'%PDF' if fmt == 'PDF' else b'PK')
            if valid:
                (args.output / f'accepted-resume.{fmt.lower()}').write_bytes(response.content)
            record('export-' + fmt, {'status': 'PASS' if valid else 'FAIL', 'http': response.status_code,
                                     'bytes': len(response.content)})
    if args.answer_session:
        learning = call('GET', '/learning/sessions/' + args.answer_session)
        if args.new_practice:
            task = call('POST', '/learning/sessions', {'planId': learning['planId'], 'planItemId': learning['planItemId']})
            deadline = time.monotonic() + 180
            while task['status'] not in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED') and time.monotonic() < deadline:
                time.sleep(2)
                task = call('GET', '/tasks/' + task['id'])
            record('real-new-question', {'status': task['status'], 'result': task.get('result'), 'error': task.get('error')})
            if task['status'] != 'SUCCEEDED':
                return
            args.answer_session = task['resourceId']
            learning = call('GET', '/learning/sessions/' + args.answer_session)
            (args.output / 'real-new-question.json').write_text(json.dumps(learning, ensure_ascii=False, indent=2))
        task = call('POST', '/learning/sessions/' + args.answer_session + '/answers',
                    {'content': '不会，暂时无法给出有效的分析方案。', 'expectedVersion': learning['version']})
        deadline = time.monotonic() + 180
        while task['status'] not in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED') and time.monotonic() < deadline:
            time.sleep(2)
            task = call('GET', '/tasks/' + task['id'])
        record('real-unknown-answer', {'status': task['status'], 'result': task.get('result'), 'error': task.get('error')})
        if task['status'] == 'SUCCEEDED':
            answer = call('GET', '/learning/answers/' + task['resourceId'])
            (args.output / 'real-unknown-answer.json').write_text(json.dumps(answer, ensure_ascii=False, indent=2))
            evaluation = answer.get('evaluation') or {}
            record('unknown-answer-classification', {'answerStatus': answer.get('status'),
                   'answerClass': evaluation.get('answerClass'), 'overallScore': evaluation.get('overallScore'),
                   'evidenceStatus': evaluation.get('evidenceStatus')})
            mistakes = call('GET', '/learning/mistakes')
            (args.output / 'mistakes-after-answer.json').write_text(json.dumps(mistakes, ensure_ascii=False, indent=2))
            record('unknown-answer-review-entry', {'found': any(item.get('cause') == 'UNKNOWN'
                   and item.get('question', {}).get('id') == learning['question']['id'] for item in mistakes.get('records', []))})
    if not args.real_tasks:
        return
    created = []
    for name, path, body in [
        ('real-jd', '/jobs/imports', {'title': 'Java 后端工程师（20261115虚构验收）',
             'companyName': '整改验收虚构公司',
             'jdText': '工作地点：广州市。招聘 Java 后端工程师，要求本科及以上学历，三年全职经验，掌握 Java、Spring Boot、MySQL、Redis。负责并发接口、缓存和自动化测试。'}),
        ('real-search', '/job-searches', {'expectedProfileVersion': profile['profileVersion'],
             'keyword': 'Java 后端', 'cities': ['440100'], 'candidateTypes': ['EXPERIENCED'], 'maxResults': 10}),
    ]:
        task = call('POST', path, body)
        created.append((name, task['id']))
        record(name + '-submitted', {'taskId': task['id'], 'resourceId': task['resourceId']})
    deadline = time.monotonic() + 300
    pending = dict(created)
    while pending and time.monotonic() < deadline:
        for name, task_id in list(pending.items()):
            task = call('GET', '/tasks/' + task_id)
            if task['status'] not in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'):
                continue
            record(name, {'status': task['status'], 'taskId': task_id, 'result': task.get('result'), 'error': task.get('error')})
            if task['status'] == 'SUCCEEDED':
                route = '/job-searches/' if name == 'real-search' else '/jobs/'
                detail = call('GET', route + task['resourceId'])
                (args.output / (name + '.json')).write_text(json.dumps(detail, ensure_ascii=False, indent=2))
            pending.pop(name)
        if pending:
            time.sleep(2)
    for name, task_id in pending.items():
        record(name, {'status': 'BLOCKED', 'reason': 'Task did not finish within 300 seconds', 'taskId': task_id})


if __name__ == '__main__':
    main()
