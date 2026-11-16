"""Resumable local acceptance with synthetic data; credentials stay in memory."""
import json
import time
import uuid
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
BASE = 'http://127.0.0.1:8080/api/v1'


class Audit:
    def __init__(self, args):
        self.args = args
        self.out = args.output
        self.out.mkdir(parents=True, exist_ok=True)
        self.state_file = self.out / 'state.json'
        self.state = json.loads(self.state_file.read_text()) if self.state_file.exists() else {}
        self.account = json.loads(args.account_file.read_text())
        self.session = self.login(self.account)

    def login(self, account):
        session = requests.Session()
        session.trust_env = False
        session.headers['Origin'] = 'http://localhost:5173'
        response = session.post(BASE + '/auth/login', json={k: account[k] for k in ('email', 'password')}, timeout=25)
        response.raise_for_status()
        session.headers['Authorization'] = 'Bearer ' + response.json()['data']['accessToken']
        session.headers['X-CSRF-Token'] = response.headers['X-CSRF-Token']
        return session

    def call(self, method, path, body=None, expected=200, session=None):
        response = (session or self.session).request(method, BASE + path, json=body,
            headers={'Idempotency-Key': str(uuid.uuid4())}, timeout=35)
        if response.status_code != expected and not (expected == 200 and response.status_code == 202):
            raise AssertionError(f'{method} {path}: HTTP {response.status_code}: {response.text[:800]}')
        return response.json().get('data')

    def save(self, name, value):
        (self.out / f'{name}.json').write_text(json.dumps(value, ensure_ascii=False, indent=2))

    def check(self, name, condition, **details):
        record = {'check': name, 'passed': bool(condition), **details}
        with (self.out / 'checks.jsonl').open('a') as handle:
            handle.write(json.dumps(record, ensure_ascii=False) + '\n')
        print(json.dumps(record, ensure_ascii=False), flush=True)
        assert condition, name

    def remember(self, **values):
        self.state.update(values)
        self.save('state', self.state)

    def wait(self, task, seconds=240):
        deadline = time.monotonic() + seconds
        while task['status'] not in ('SUCCEEDED', 'FAILED', 'EXPIRED', 'CANCELLED') and time.monotonic() < deadline:
            time.sleep(2)
            task = self.call('GET', '/tasks/' + task['id'])
        self.save('task-' + task['id'], task)
        return task

    def inspect(self):
        paths = {'profile': '/profile', 'resumes': '/resumes?size=100', 'jobs': '/jobs/imports?size=100',
                 'plans': '/learning/plans?size=100', 'matches': '/job-matches?size=100',
                 'interviews': '/interviews?size=100', 'growth': '/growth'}
        for name, path in paths.items():
            value = self.call('GET', path)
            self.save('baseline-' + name, value)
            if isinstance(value, dict) and 'records' in value:
                print(name, json.dumps([{k: item.get(k) for k in ('id', 'title', 'status', 'latestVersion', 'jobTitle')
                     if k in item} for item in value['records']], ensure_ascii=False), flush=True)
        print('Baseline saved; no credentials included.', flush=True)

    def browser(self):
        from playwright.sync_api import sync_playwright
        self.runtime = sync_playwright().start()
        self.browser_instance = self.runtime.chromium.launch()
        self.context = self.browser_instance.new_context(viewport={'width': 1440, 'height': 1000}, locale='zh-CN')
        self.page = self.context.new_page()
        self.page.goto('http://localhost:5173/login')
        self.page.get_by_placeholder('请输入邮箱地址').fill(self.account['email'])
        self.page.get_by_placeholder('请输入密码', exact=True).fill(self.account['password'])
        self.page.get_by_role('button', name='登录', exact=True).click()
        self.page.wait_for_url('**/dashboard')
        self.save('browser', {'version': self.browser_instance.version, 'viewport': '1440x1000'})
        return self.page

    def capture(self, name):
        self.page.wait_for_timeout(350)
        (self.out / (name + '.txt')).write_text(self.page.locator('body').aria_snapshot())
        self.page.screenshot(path=str(self.out / (name + '.png')), full_page=True)

    def upload(self):
        from docx import Document
        document = Document()
        lines = ['七项验收虚构简历（仅用于开发测试）', '姓名：验收样本甲；全部学校、单位与经历均为虚构。',
                 '学历：本科，计算机科学与技术，2023年毕业。',
                 '全职工作：2023年7月至2026年6月，虚构杉树科技，Java后端工程师，共36个月全职工作。',
                 '实习：2023年1月至2023年6月，虚构蓝海实验室，后端开发实习生，共6个月；实习不计入全职经验。',
                 '技能：Java、Spring Boot、MySQL、Redis。使用 ZephyrAuditVM 编写测试工具。',
                 '奖项：2022年虚构校级程序设计赛二等奖。',
                 '项目：使用Java和MySQL实现订单接口、幂等控制和事务回滚；使用Redis缓存。']
        for line in lines:
            document.add_paragraph(line)
        file = self.out / 'fictional-seven-resume.docx'
        document.save(file)
        page = self.browser()
        page.goto('http://localhost:5173/resume')
        page.wait_for_load_state('networkidle')
        self.capture('01-resume-before-upload')
        page.get_by_role('button', name='上传新简历', exact=True).click()
        self.capture('02-upload-dialog')
        page.locator('input[type=file]').set_input_files(str(file.resolve()))
        self.capture('03-upload-file-selected')
        with page.expect_response(lambda r: '/resumes/upload' in r.url and r.request.method == 'POST', timeout=30000) as result:
            page.get_by_role('button', name='上传并自动解析', exact=True).click()
        response = result.value.json()
        self.save('upload-response', response)
        if not response.get('data'):
            self.capture('04-upload-error')
            raise AssertionError(f'Upload HTTP {result.value.status}: {response}')
        uploaded = response['data']
        version = uploaded['latestVersion']['id']
        self.remember(resume=uploaded['id'], resume_version=version)
        self.save('uploaded-resume', uploaded)
        self.capture('04-parsing')
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            detail = self.call('GET', '/resume-versions/' + version)
            if detail['status'] in ('PARSED', 'FAILED'):
                break
            time.sleep(2)
        self.save('parsed-resume', detail)
        self.check('R04-real-parse', detail['status'] == 'PARSED', status=detail['status'])
        page.reload()
        page.get_by_role('button', name='核对完成，确认事实').wait_for(timeout=20000)
        self.capture('05-parsed-facts')
        self.check('R04-separated-facts', detail['facts'].get('experienceMonths') == 36
                   and len(detail['facts'].get('internships', [])) == 1 and len(detail['facts'].get('awards', [])) == 1)
        self.browser_instance.close()
        self.runtime.stop()

    def prepare(self):
        other_account = json.loads((ROOT / '.local-dev-env/audit-account.json').read_text())
        other = self.login(other_account)
        before = self.call('GET', '/skills', session=other)
        self.save('other-skills-before', before)
        self.check('R03-existing-search', any(s['code'] == 'JAVA' for s in self.call('GET', '/skills?q=Java')))
        name = 'ZephyrAuditVM'
        skill = self.call('POST', '/skills/custom', {'name': name})
        self.remember(custom_skill=skill['code'])
        duplicate = self.call('POST', '/skills/custom', {'name': '  zephyrauditvm  '})
        self.check('R03-normalized-private-duplicate', skill['normalizationStatus'] == 'PENDING' and skill['code'] == duplicate['code'])
        for value in ['', '   ', '<script>alert(1)</script>', 'a' * 101]:
            self.call('POST', '/skills/custom', {'name': value}, expected=400)
        self.check('R03-invalid-inputs', True, cases=4)
        self.check('R03-owner-search', len(self.call('GET', '/skills?q=' + name)) == 1)
        self.check('R03-other-isolation', self.call('GET', '/skills?q=' + name, session=other) == [])
        self.check('R03-other-public-catalog-unchanged', before == self.call('GET', '/skills', session=other))
        jobs = self.call('GET', '/jobs/imports?size=100')['records']
        success = next(j for j in jobs if j['id'] == '987cb84a-e822-476b-a2cf-a59ad66fa32e')
        failed = next(j for j in jobs if j['id'] == 'a29babb5-5487-40a6-a3fe-5c8e07498ad8')
        self.remember(success_job=success['id'], failed_job=failed['id'])
        for label, job in [('success', success), ('failed', failed)]:
            detail = self.call('GET', '/jobs/' + job['id'])
            self.save('jd-' + label + '-before', detail)
            self.call('GET', '/jobs/' + job['id'], expected=404, session=other)
            body = {'expectedJobVersion': detail['jobVersion'], 'cityCode': '440100', 'skillCodes': ['JAVA'],
                    'sourceQuote': detail['jdText'], 'reason': '七项验收字段核对'}
            self.call('PUT', '/jobs/' + job['id'] + '/extracted-fields', body, expected=404, session=other)
            self.call('PUT', '/jobs/' + job['id'] + '/extracted-fields', dict(body, sourceQuote='不在原文中的虚构引述'), expected=400)
        self.check('J03-unauthorized-and-invalid-quote', True)
        profile = self.call('GET', '/profile')
        task = self.call('POST', '/job-matches', {'expectedProfileVersion': profile['profileVersion'],
                     'jobIds': [success['id']], 'includeUnknown': True})
        self.remember(old_match_task=task['id'], old_match=task['resourceId'])
        result = self.wait(task)
        self.check('J03-original-real-match', result['status'] == 'SUCCEEDED', taskId=result['id'])
        self.save('old-match-before', self.call('GET', '/job-matches/' + task['resourceId']))

    def confirm(self):
        page = self.browser()
        version = self.state['resume_version']
        page.goto('http://localhost:5173/resume?versionId=' + version)
        page.get_by_role('button', name='核对完成，确认事实').wait_for()
        self.capture('06-before-custom-skill')
        page.get_by_role('button', name='自定义技能', exact=True).click()
        self.capture('07-custom-skill-dialog')
        page.get_by_role('dialog').locator('input').fill('ZephyrAuditVM')
        page.get_by_role('button', name='添加到技能事实').click()
        page.get_by_role('dialog').wait_for(state='hidden')
        row = page.locator('.el-table__body tr').last
        row.locator('textarea').nth(0).fill('使用 ZephyrAuditVM 编写测试工具')
        row.locator('textarea').nth(1).fill('使用 ZephyrAuditVM 编写测试工具。')
        row.get_by_placeholder('page:1').fill('text:6')
        row.scroll_into_view_if_needed()
        self.capture('08-private-skill-added')
        before = self.call('GET', '/profile')
        with page.expect_response(lambda r: r.url.endswith('/confirm') and r.request.method == 'POST') as result:
            page.get_by_role('button', name='核对完成，确认事实').click()
        self.check('R04-browser-confirm', result.value.status == 200, http=result.value.status)
        page.get_by_role('button', name='纠正事实', exact=True).wait_for()
        page.reload()
        page.get_by_role('button', name='纠正事实', exact=True).wait_for()
        page.get_by_text('解析事实', exact=True).scroll_into_view_if_needed()
        self.capture('09-confirmed-after-refresh')
        profile = self.call('GET', '/profile')
        self.save('profile-confirmed', profile)
        self.remember(confirmed_profile_version=profile['profileVersion'])
        self.check('R04-profile-confirmed-version', profile['profileVersion'] == before['profileVersion'] + 1
                   and profile['activeResumeVersionId'] == version)
        old = json.loads((self.out / 'baseline-resumes.json').read_text())['records'][0]['latestVersion']['id']
        page.goto('http://localhost:5173/resume?versionId=' + old)
        page.get_by_text('解析事实', exact=True).wait_for()
        page.wait_for_timeout(600)
        self.capture('10-old-resume-after-confirm')
        self.browser_instance.close()
        self.runtime.stop()

    def jd_correct(self):
        page = self.browser()
        for label, job_id in [('success', self.state['success_job']), ('failed', self.state['failed_job'])]:
            before = self.call('GET', '/jobs/' + job_id)
            if (self.out / ('jd-' + label + '-after.json')).exists():
                continue
            page.goto('http://localhost:5173/jobs?tab=personal&jobId=' + job_id)
            action = '人工补全 JD 字段' if before['status'] == 'FAILED' else '核对提取结果'
            page.get_by_role('button', name=action, exact=True).wait_for(timeout=20000)
            self.capture('11-jd-' + label + '-before')
            page.get_by_role('button', name=action, exact=True).click()
            self.capture('12-jd-' + label + '-form')
            if before['status'] == 'FAILED':
                page.get_by_role('combobox', name='技能要求', exact=True).fill('Java')
                page.get_by_role('option', name='Java', exact=True).click()
                page.get_by_role('combobox', name='技能要求', exact=True).press('Escape')
            page.get_by_placeholder('从上方 JD 原文复制一段支持修改的内容').fill(before['jdText'])
            page.get_by_placeholder('说明原提取结果哪里有误').fill('七项验收：核对广州与原文技能，保留人工修订依据')
            with page.expect_response(lambda r: r.url.endswith('/extracted-fields') and r.request.method == 'PUT') as result:
                page.get_by_role('button', name='确认并保存新版本').click()
            self.save('jd-' + label + '-response', result.value.json())
            if result.value.status != 200:
                self.capture('12-jd-' + label + '-error')
            self.check('J03-browser-' + label, result.value.status == 200)
            page.get_by_role('dialog', name='核对个人 JD 提取结果').wait_for(state='hidden')
            page.reload()
            page.get_by_role('button', name='核对提取结果', exact=True).wait_for()
            self.capture('13-jd-' + label + '-refreshed')
            after = self.call('GET', '/jobs/' + job_id)
            self.save('jd-' + label + '-after', after)
            self.check('J03-version-' + label, after['jobVersion'] == before['jobVersion'] + 1 and after['status'] == 'ACTIVE')
        old = self.call('GET', '/job-matches/' + self.state['old_match'])
        self.save('old-match-after-jd', old)
        self.check('J03-old-match-stale', old.get('isStale') or any(i.get('isStale') for i in old.get('items', [])))
        page.goto('http://localhost:5173/jobs?tab=matches&matchId=' + self.state['old_match'])
        page.wait_for_load_state('networkidle')
        self.capture('14-stale-match')
        self.browser_instance.close()
        self.runtime.stop()
        profile = self.call('GET', '/profile')
        task = self.call('POST', '/job-matches', {'expectedProfileVersion': profile['profileVersion'],
                'jobIds': [self.state['success_job'], self.state['failed_job']], 'includeUnknown': True})
        self.remember(new_match=task['resourceId'])
        result = self.wait(task)
        self.check('J03-new-real-match', result['status'] == 'SUCCEEDED')
        match = self.call('GET', '/job-matches/' + task['resourceId'])
        self.save('new-match-after-jd', match)
        self.check('J03-new-version-used', all(i['jobVersion'] == 2 for i in match['items']), items=len(match['items']))

    def plan(self):
        task = self.call('POST', '/learning/plans/from-resume', {'resumeVersionId': self.state['resume_version'],
                         'targetDirection': 'JAVA_BACKEND', 'dailyMinutes': 30, 'days': 1})
        self.remember(new_plan=task['resourceId'], new_plan_task=task['id'])
        result = self.wait(task)
        self.check('R04-real-plan-before-correction', result['status'] == 'SUCCEEDED')
        self.save('plan-before-correction', self.call('GET', '/learning/plans/' + task['resourceId']))

    def correct(self):
        page = self.browser()
        version = self.state['resume_version']
        before = self.call('GET', '/profile')
        evidence_before = self.call('GET', '/growth/evidence?sourceType=INTERVIEW_ANSWER&size=100')
        self.save('growth-before-correction', self.call('GET', '/growth'))
        page.goto('http://localhost:5173/resume?versionId=' + version)
        page.get_by_role('button', name='纠正事实', exact=True).wait_for()
        self.capture('15-before-resume-correction')
        page.get_by_role('button', name='纠正事实', exact=True).click()
        page.locator('.el-table__body tr').first.locator('textarea').first.fill('使用Java实现订单接口、幂等控制和事务回滚')
        page.get_by_placeholder('请说明纠正原因或依据，例如：原文第 2 页的实习经历被误计入全职经验').fill('依据原文项目段，补全Java技能自述的具体内容；未改变全职和实习时长')
        self.capture('16-correction-editing')
        with page.expect_response(lambda r: r.url.endswith('/corrections') and r.request.method == 'POST') as result:
            page.get_by_role('button', name='保存事实纠正').click()
        self.check('R04-browser-correction', result.value.status == 200)
        page.get_by_role('button', name='纠正事实', exact=True).wait_for()
        page.reload()
        page.get_by_role('button', name='纠正事实', exact=True).wait_for()
        page.get_by_text('原解析事实与修订记录', exact=True).click()
        page.get_by_text('原解析事实与修订记录', exact=True).scroll_into_view_if_needed()
        self.capture('17-resume-history-refreshed')
        after = self.call('GET', '/profile')
        revisions = self.call('GET', '/resume-versions/' + version + '/corrections')
        self.save('resume-revisions', revisions)
        self.save('profile-corrected', after)
        self.save('growth-after-correction', self.call('GET', '/growth'))
        self.check('R04-correction-version-history', after['profileVersion'] == before['profileVersion'] + 1 and len(revisions) == 2)
        detail = self.call('GET', '/resume-versions/' + version)
        self.check('R04-original-parse-preserved', detail['parsedFacts'] == json.loads((self.out / 'parsed-resume.json').read_text())['parsedFacts'])
        evidence_after = self.call('GET', '/growth/evidence?sourceType=INTERVIEW_ANSWER&size=100')
        self.check('R04-no-fabricated-score-evidence', evidence_before == evidence_after)
        match = self.call('GET', '/job-matches/' + self.state['new_match'])
        self.save('match-after-resume-correction', match)
        self.check('R04-match-stale', match.get('isStale') or any(i.get('isStale') for i in match.get('items', [])))
        plan = self.call('GET', '/learning/plans/' + self.state['new_plan'])
        self.check('R04-training-preserves-old-basis', plan['profileVersion'] == before['profileVersion'] and plan['profileVersion'] != after['profileVersion'])
        page.goto('http://localhost:5173/training?planId=' + self.state['new_plan'])
        page.wait_for_load_state('networkidle')
        self.capture('18-training-old-basis')
        other = self.login(json.loads((ROOT / '.local-dev-env/audit-account.json').read_text()))
        for path in ['/resume-versions/' + version, '/resume-versions/' + version + '/corrections']:
            self.call('GET', path, expected=404, session=other)
        self.check('R03-private-revisions-isolated', True)
        self.browser_instance.close()
        self.runtime.stop()

    def interview_start(self):
        task = self.call('POST', '/interviews', {'jobId': self.state['success_job'],
                         'resumeVersionId': self.state['resume_version'], 'questionLimit': 3})
        self.remember(interview=task['resourceId'])
        result = self.wait(task)
        self.check('G01-real-interview-start', result['status'] == 'SUCCEEDED')
        detail = self.call('GET', '/interviews/' + task['resourceId'])
        self.save('interview-first-question', detail)
        print(json.dumps(detail['messages'][-1], ensure_ascii=False), flush=True)

    def reading(self):
        plan = self.call('GET', '/learning/plans/' + self.state['new_plan'])
        item = next(i for i in plan['items'] if i['kind'] == 'READING' and i['resourceLinks'])
        self.remember(reading_item=item['id'])
        before_evidence = self.call('GET', '/growth/evidence?size=100')
        page = self.browser()
        page.goto('http://localhost:5173/training?planId=' + plan['id'])
        page.get_by_text(item['title'], exact=True).wait_for(timeout=20000)
        card = page.locator('.plan-item').filter(has=page.get_by_text(item['title'], exact=True))
        card.scroll_into_view_if_needed()
        self.capture('19-reading-before-open')
        with page.expect_popup() as popup:
            card.get_by_role('link').first.click()
        resource = popup.value
        response = resource.wait_for_load_state('domcontentloaded', timeout=45000)
        self.save('reading-resource', {'url': resource.url, 'title': resource.title()})
        resource.screenshot(path=str(self.out / '20-reading-real-resource.png'))
        self.check('U03-resource-opened', 'github.com/macrozheng/mall' in resource.url and 'mall' in resource.title().lower())
        resource.close()
        current = self.call('GET', '/learning/plans/' + plan['id'])
        fresh = next(i for i in current['items'] if i['id'] == item['id'])
        self.check('U03-open-does-not-complete', fresh['status'] == item['status'] and not fresh.get('completedAt'))
        with page.expect_response(lambda r: r.url.endswith('/completion') and r.request.method == 'PUT') as result:
            card.get_by_role('button', name='我已阅读（自评）').click()
        self.check('U03-self-report-saved', result.value.status == 200)
        page.reload()
        page.get_by_role('button', name='已自评完成阅读').wait_for()
        card = page.locator('.plan-item').filter(has=page.get_by_text(item['title'], exact=True))
        card.scroll_into_view_if_needed()
        self.capture('21-reading-refresh')
        persisted = self.call('GET', '/learning/plans/' + plan['id'])
        saved = next(i for i in persisted['items'] if i['id'] == item['id'])
        self.save('reading-completed', saved)
        self.check('U03-refresh-status-time', saved['status'] == 'COMPLETED' and bool(saved.get('completedAt'))
                   and '自评完成时间' in card.inner_text() and '不代表系统已验证' in card.inner_text())
        self.call('PUT', f'/learning/plans/{plan["id"]}/items/{item["id"]}/completion', {})
        repeated = next(i for i in self.call('GET', '/learning/plans/' + plan['id'])['items'] if i['id'] == item['id'])
        self.check('U03-repeat-preserves-time', repeated['completedAt'] == saved['completedAt'])
        self.check('U03-no-score-evidence', before_evidence == self.call('GET', '/growth/evidence?size=100'))
        page.set_viewport_size({'width': 390, 'height': 844})
        card.scroll_into_view_if_needed()
        self.capture('22-reading-mobile')
        self.browser_instance.close()
        self.runtime.stop()

    def internal_run(self, task_id):
        import jwt
        config = {}
        for line in (ROOT / '.env').read_text().splitlines():
            if '=' in line and not line.lstrip().startswith('#'):
                key, value = line.split('=', 1)
                config[key.strip()] = value.strip().strip('\"\'')
        token = jwt.encode({'iss': 'career-business', 'aud': 'career-ai-agent', 'sub': 'career-business',
            'iat': int(time.time()), 'exp': int(time.time()) + 60, 'scope': 'ai:run', 'taskIds': [task_id]},
            config['AGENT_JWT_SECRET'], algorithm='HS256')
        session = requests.Session()
        session.trust_env = False
        response = session.get('http://127.0.0.1:8100/internal/ai/v1/runs', params={'taskId': task_id},
            headers={'Authorization': 'Bearer ' + token}, timeout=20)
        response.raise_for_status()
        return response.json()['data']

    def interview_answer(self):
        interview = self.state['interview']
        detail = self.call('GET', '/interviews/' + interview)
        question = detail['messages'][-1]
        content = ('以下以本验收的虚构订单接口说明设计，200 QPS只是压测目标，不冒充实测结果。'
            '同一用户重复创建订单时，使用userId和业务请求号组成幂等键，在MySQL建立唯一索引，避免不同实例同时插入重复订单。'
            'Spring单例Controller不保存请求级可变状态，参数和中间结果放在局部变量，避免共享HashMap。'
            '事务放在独立Service的public方法上，用@Transactional确保订单和明细原子提交，异常向外抛出以回滚，避免自调用失效。'
            '同一进程内计数用AtomicLong或LongAdder，缓存映射用ConcurrentHashMap，但数据库唯一约束才是跨实例的最终保证。'
            '需要跨实例互斥时，Redis SET NX PX配合随机持有者标识、Lua比对删除、有限超时；锁不代替数据库约束，锁过期时仍靠唯一键兜底。'
            '线程池使用有界队列、明确拒绝策略和超时，不使用无限队列。'
            '验证时用CountDownLatch让100个并发请求同时进入，断言同一幂等键只有一笔订单且响应订单号一致；'
            '再用JMeter从50递增到200 QPS，观测吞吐、p95/p99延迟、错误率、CPU、GC、数据库锁等待和连接池。'
            '注入事务异常、Redis超时、实例退出，验证不重复落库和恢复行为。这里尚未实际执行压测，所以不报告改善百分比。')
        if self.state.get('interview_answer_task'):
            task = self.call('GET', '/tasks/' + self.state['interview_answer_task'])
        else:
            task = self.call('POST', '/interviews/' + interview + '/answers', {
                'questionMessageId': question['id'], 'content': content, 'expectedVersion': detail['version']})
            self.remember(interview_answer_task=task['id'])
        result = self.wait(task)
        self.check('G01-real-answer', result['status'] == 'SUCCEEDED')
        run = self.internal_run(task['id'])
        evaluation = run['result']['answerEvaluation']
        self.save('interview-model-evaluation', evaluation)
        evidence = self.call('GET', '/growth/evidence?sourceType=INTERVIEW_ANSWER&size=100')['records']
        detail = self.call('GET', '/interviews/' + interview)
        answers = {message['id'] for message in detail['messages']
                   if message['role'] == 'USER' and message.get('taskId') == task['id']}
        self.check('G01-real-answer-traceable', len(answers) == 1)
        actual = [row for row in evidence if row['sourceId'] in answers]
        self.save('interview-real-evidence', actual)
        self.check('G01-real-model-skill-exact-match', len(actual) == len(evaluation.get('skillScores', []))
            and all(any(row['skillCode'] == score['skillCode'] and float(row['score']) == round(float(score['score']), 1)
                        for row in actual) for score in evaluation.get('skillScores', [])),
            skillScores=evaluation.get('skillScores', []), evidenceCount=len(actual))
        self.check('G01-real-valid-evaluation', evaluation['evaluation']['evidenceStatus'] == 'VALID',
                   sample='WITH_SKILL_SCORES' if actual else 'NO_SKILL_SCORES')
        detail = self.call('GET', '/interviews/' + interview)
        if self.state.get('interview_report_task'):
            task = self.call('GET', '/tasks/' + self.state['interview_report_task'])
        else:
            task = self.call('POST', '/interviews/' + interview + '/end', {'expectedVersion': detail['version']})
            self.remember(interview_report_task=task['id'])
        result = self.wait(task)
        self.check('G01-real-report', result['status'] == 'SUCCEEDED')
        report = self.call('GET', '/interviews/' + interview + '/report')
        self.save('interview-real-report', report)
        self.check('G01-report-links-only-existing-evidence', set(report['evidenceIds']) == {r['id'] for r in actual})
        self.check('G01-report-evidence-status', report['skillEvidenceStatus'] ==
                   ('AVAILABLE' if actual else 'NO_SKILL_SCORES'))

    def skill_sample_restart(self):
        if not self.state.get('skill_sample_before_fix'):
            previous = {key: value for key, value in self.state.items() if key.startswith('skill_sample_')}
            for name in ['skill-sample-question', 'skill-sample-model-evaluation', 'skill-sample-evidence']:
                self.save(name + '-before-fix', json.loads((self.out / (name + '.json')).read_text()))
            for key in previous:
                self.state.pop(key)
            self.remember(skill_sample_before_fix=previous)
        previous_id = self.state['skill_sample_before_fix']['skill_sample_interview']
        previous = self.call('GET', '/interviews/' + previous_id)
        if previous['status'] != 'COMPLETED':
            task = self.call('POST', '/interviews/' + previous_id + '/end', {'expectedVersion': previous['version']})
            self.check('G01-before-fix-interview-closed', self.wait(task)['status'] == 'SUCCEEDED')
        self.skill_sample_start()

    def skill_sample_start(self):
        if self.state.get('skill_sample_question_task'):
            task = self.call('GET', '/tasks/' + self.state['skill_sample_question_task'])
        else:
            task = self.call('POST', '/interviews', {'jobId': self.state['success_job'],
                             'resumeVersionId': self.state['resume_version'], 'questionLimit': 3})
            self.remember(skill_sample_interview=task['resourceId'], skill_sample_question_task=task['id'])
        self.check('G01-skill-sample-question', self.wait(task)['status'] == 'SUCCEEDED')
        detail = self.call('GET', '/interviews/' + self.state['skill_sample_interview'])
        self.save('skill-sample-question', detail)
        print(json.dumps(detail['messages'][-1], ensure_ascii=False), flush=True)

    def skill_sample_answer(self):
        interview = self.state['skill_sample_interview']
        detail = self.call('GET', '/interviews/' + interview)
        answer = ('以本次虚构订单接口为例，方案是业务幂等键加数据库唯一约束，Redis只做减压，不能作为最终正确性保证。'
            '客户端生成requestId，服务端从已认证会话取userId，对(user_id,request_id)建唯一索引，并保存请求体摘要。'
            'Controller只校验参数和身份，调用独立Spring Service的public方法，使用@Transactional(rollbackFor=Exception.class)，避免自调用绕过代理。'
            '事务内先INSERT订单，再写订单明细和扣库存；库存用UPDATE stock SET amount=amount-? WHERE sku_id=? AND amount>=?，检查影响行数。'
            '唯一键冲突的异常要在事务代理外捕获，失败事务先回滚，再用新事务查询已存在订单；摘要一致返回原订单，不一致返回409。'
            '不能在已标记rollback-only的事务里吞掉异常继续提交。所有业务表使用InnoDB，同一数据源保证原子性。'
            '不同实例并发也由唯一索引仲裁，Java synchronized只能保护单进程。请求超时重试仍使用原requestId。'
            '如果涉及消息，在同一事务写outbox，提交后异步发布，消费者按eventId去重；数据库事务不能直接保证外部支付原子性。'
            'Redis SET NX PX可在入口减少热点请求，但解锁用持有者token与Lua比较删除，锁过期或Redis故障仍依赖数据库约束。'
            '验证方案是在集成测试里通过CountDownLatch并发提交同一幂等键，断言订单仅一行、库存只扣一次；注入明细插入异常后订单与库存都回滚，再重试应成功。'
            '这些是设计和验证方法，不声称已执行真实项目压测。')
        if self.state.get('skill_sample_answer_task'):
            task = self.call('GET', '/tasks/' + self.state['skill_sample_answer_task'])
        else:
            task = self.call('POST', '/interviews/' + interview + '/answers', {
                'questionMessageId': detail['messages'][-1]['id'], 'content': answer, 'expectedVersion': detail['version']})
            self.remember(skill_sample_answer_task=task['id'])
        self.check('G01-second-real-answer', self.wait(task)['status'] == 'SUCCEEDED')
        evaluation = self.internal_run(task['id'])['result']['answerEvaluation']
        self.save('skill-sample-model-evaluation', evaluation)
        scores = evaluation.get('skillScores', [])
        rows = self.call('GET', '/growth/evidence?sourceType=INTERVIEW_ANSWER&size=100')['records']
        actual = [row for row in rows if any(row['sourceId'] == score['sourceId'] for score in scores)]
        self.save('skill-sample-evidence', actual)
        self.check('G01-real-nonempty-skill-exact-match', bool(scores) and len(actual) == len(scores)
            and all(any(row['sourceId'] == score['sourceId'] and row['skillCode'] == score['skillCode']
                    and float(row['score']) == round(float(score['score']), 1) for row in actual) for score in scores),
            skillScores=scores, evidenceCount=len(actual))
        detail = self.call('GET', '/interviews/' + interview)
        if self.state.get('skill_sample_report_task'):
            task = self.call('GET', '/tasks/' + self.state['skill_sample_report_task'])
        else:
            task = self.call('POST', '/interviews/' + interview + '/end', {'expectedVersion': detail['version']})
            self.remember(skill_sample_report_task=task['id'])
        self.check('G01-skill-sample-report', self.wait(task)['status'] == 'SUCCEEDED')
        report = self.call('GET', '/interviews/' + interview + '/report')
        self.save('skill-sample-report', report)
        self.check('G01-skill-sample-report-links', report['skillEvidenceStatus'] == 'AVAILABLE'
                   and set(report['evidenceIds']) == {row['id'] for row in actual})

    def empty_report(self):
        if self.state.get('empty_interview'):
            detail = self.call('GET', '/interviews/' + self.state['empty_interview'])
        else:
            task = self.call('POST', '/interviews', {'jobId': self.state['success_job'],
                             'resumeVersionId': self.state['resume_version'], 'questionLimit': 3})
            self.remember(empty_interview=task['resourceId'])
            self.check('G01-empty-question-ready', self.wait(task)['status'] == 'SUCCEEDED')
            detail = self.call('GET', '/interviews/' + self.state['empty_interview'])
        before = self.call('GET', '/growth/evidence?size=100')
        if self.state.get('empty_report_task'):
            task = self.call('GET', '/tasks/' + self.state['empty_report_task'])
        else:
            task = self.call('POST', '/interviews/' + detail['id'] + '/end', {'expectedVersion': detail['version']})
            self.remember(empty_report_task=task['id'])
        self.check('G01-empty-report-ready', self.wait(task)['status'] == 'SUCCEEDED')
        report = self.call('GET', '/interviews/' + detail['id'] + '/report')
        self.save('interview-empty-report', report)
        self.check('G01-empty-no-score-no-evidence', report['skillEvidenceStatus'] == 'NO_VALID_ANSWER'
                   and report['evaluation']['overallScore'] is None and not report['evidenceIds']
                   and before == self.call('GET', '/growth/evidence?size=100'))

    def supplement_ui(self):
        import copy
        other = self.login(json.loads((ROOT / '.local-dev-env/audit-account.json').read_text()))
        for name in ['success_job', 'failed_job']:
            detail = self.call('GET', '/jobs/' + self.state[name])
            self.save(name + '-final-history', detail)
            self.check('J03-final-history-' + name, [r['versionNo'] for r in detail['revisions']] == [1, 2])
            self.call('GET', '/jobs/' + self.state[name], expected=404, session=other)
        self.check('J03-final-history-isolated', True)
        page = self.browser()
        for size, viewport in [('desktop', {'width': 1440, 'height': 1000}), ('mobile', {'width': 390, 'height': 844})]:
            page.set_viewport_size(viewport)
            for key, name in [('skill_sample_interview', 'skills'), ('empty_interview', 'empty')]:
                page.goto('http://localhost:5173/interview?interviewId=' + self.state[key] + '&report=1')
                page.get_by_text('证据状态', exact=True).wait_for()
                page.get_by_text('证据状态', exact=True).scroll_into_view_if_needed()
                self.capture('32-real-report-' + name + '-' + size)
                if name == 'empty':
                    self.check('G01-real-empty-explanation-' + size,
                        page.get_by_text('本次没有有效回答', exact=False).is_visible())
        page.set_viewport_size({'width': 1440, 'height': 1000})
        original = self.call('GET', '/tasks/' + self.state['retried_task'])
        cases = [('UPSTREAM_TIMEOUT', '上游请求超时，请稍后重试', True),
                 ('MODEL_UNAVAILABLE', '模型服务暂时不可用', True),
                 ('BUSINESS_CONFLICT', '存在进行中的同类业务任务', True),
                 ('STALE_INPUT', '原任务的 JD 版本已变更，请基于当前版本重新提交', False)]
        for code, message, retryable in cases:
            sample = copy.deepcopy(original)
            sample.update(status='FAILED', stage='FAILED', error={'code': code, 'message': message, 'retryable': retryable})

            def fixture(route):
                route.fulfill(status=200, content_type='application/json', body=json.dumps({'code': 200, 'data': sample}))

            pattern = '**/api/v1/tasks/' + original['id']
            self.context.route(pattern, fixture)
            page.goto('http://localhost:5173/tasks?taskId=' + original['id'])
            page.locator('.task-status').get_by_text(message, exact=True).wait_for()
            self.check('A04-fault-ui-' + code,
                       page.get_by_role('button', name='按原参数重试', exact=True).count() == int(retryable)
                       and page.get_by_role('link', name='查看原任务').is_visible(), fixture=True)
            self.capture('33-task-fixture-' + code)
            self.context.unroute(pattern, fixture)
        self.save('task-ui-fixture-scope', {'codes': [case[0] for case in cases],
                  'purpose': '仅浏览器展示与操作入口；真实故障处理/重试由隔离MySQL矩阵验证；未写库'})
        self.browser_instance.close()
        self.runtime.stop()

    def resume_copy(self):
        page = self.browser()
        page.set_viewport_size({'width': 390, 'height': 844})
        page.goto('http://localhost:5173/resume?versionId=' + self.state['resume_version'])
        section = page.locator('.evidence-section').filter(has=page.get_by_role('heading', name='获奖与奖学金'))
        section.wait_for()
        section.scroll_into_view_if_needed()
        self.check('R04-award-help-correct', '实习' not in section.locator('.skills-heading').inner_text())
        self.capture('34-award-help-mobile')
        self.browser_instance.close()
        self.runtime.stop()

    def mobile_layout(self):
        page = self.browser()
        page.set_viewport_size({'width': 390, 'height': 844})
        page.goto('http://localhost:5173/jobs?tab=search&searchId=7701c8d4-6fa5-463b-bc3e-dc2c11400845')
        page.get_by_text('本次搜索时间：', exact=False).wait_for()
        page.get_by_text('本次搜索时间：', exact=False).scroll_into_view_if_needed()
        self.capture('31-mobile-search-layout')
        metrics = page.evaluate('''() => {
            const main = document.querySelector('.main-layout__content');
            const note = [...document.querySelectorAll('p')].find(e => e.textContent.includes('本次搜索时间：'));
            return {client: main.clientWidth, scroll: main.scrollWidth,
                    noteRight: note.getBoundingClientRect().right, viewport: innerWidth};
        }''')
        self.save('mobile-search-layout', metrics)
        self.check('S04-mobile-no-horizontal-clipping', metrics['scroll'] <= metrics['client'] + 1
                   and metrics['noteRight'] <= metrics['viewport'], **metrics)
        self.browser_instance.close()
        self.runtime.stop()

    def tasks(self):
        tasks = self.call('GET', '/tasks?size=100')['records']
        stale = next(t for t in tasks if t['type'] == 'JD_ANALYZE' and t['status'] == 'FAILED'
                     and t['resourceId'] == self.state['failed_job'])
        response = self.session.post(BASE + '/tasks/' + stale['id'] + '/retry', json={},
            headers={'Idempotency-Key': str(uuid.uuid4())}, timeout=30)
        self.save('stale-jd-retry', response.json())
        self.check('A04-stale-input-rejected', response.status_code == 409
                   and response.json()['error']['key'] == 'STALE_INPUT')
        original = next(t for t in tasks if t['type'] == 'JOB_SEARCH' and t['status'] == 'FAILED')
        retried = self.call('POST', '/tasks/' + original['id'] + '/retry', {})
        self.remember(retried_task=retried['id'], retry_original=original['id'])
        self.check('A04-original-link-and-version', retried['retryOfTaskId'] == original['id']
                   and retried['inputVersions'] == original['inputVersions'])
        self.call('POST', '/tasks/' + original['id'] + '/retry', {}, expected=409)
        self.check('A04-business-conflict-blocks-duplicate', True)
        deadline = time.monotonic() + 30
        while retried['status'] == 'QUEUED' and time.monotonic() < deadline:
            time.sleep(0.25)
            retried = self.call('GET', '/tasks/' + retried['id'])
        self.check('A04-actually-running', retried['status'] == 'RUNNING')
        page = self.browser()
        page.goto('http://localhost:5173/tasks?taskId=' + retried['id'])
        page.get_by_role('button', name='取消任务', exact=True).wait_for(timeout=20000)
        self.capture('23-running-retry')
        self.check('A04-original-link-visible', page.get_by_role('link', name='查看原任务').count() == 1)
        page.get_by_role('button', name='取消任务', exact=True).click()
        cancelled = self.wait(self.call('GET', '/tasks/' + retried['id']), seconds=40)
        self.check('A04-running-cancelled', cancelled['status'] == 'CANCELLED')
        page.reload()
        page.wait_for_load_state('networkidle')
        self.capture('24-cancelled-refreshed')
        self.check('A04-terminal-no-cancel-button', page.get_by_role('button', name='取消任务', exact=True).count() == 0)
        succeeded = next(t for t in tasks if t['status'] == 'SUCCEEDED')
        protected = self.call('POST', '/tasks/' + succeeded['id'] + '/cancel', {})
        self.check('A04-success-terminal-protected', protected['status'] == 'SUCCEEDED')
        self.browser_instance.close()
        self.runtime.stop()

    def final_ui(self):
        import copy
        page = self.browser()
        for size, viewport in [('desktop', {'width': 1440, 'height': 1000}), ('mobile', {'width': 390, 'height': 844})]:
            page.set_viewport_size(viewport)
            page.goto('http://localhost:5173/resume?versionId=' + self.state['resume_version'])
            page.get_by_text('待规范化 · 仅本人可见', exact=True).wait_for()
            page.get_by_text('待规范化 · 仅本人可见', exact=True).scroll_into_view_if_needed()
            self.capture('25-private-skill-' + size)
            self.check('R03-private-label-' + size, page.get_by_text('待规范化 · 仅本人可见', exact=True).is_visible())
            page.goto('http://localhost:5173/jobs?tab=personal&jobId=' + self.state['success_job'])
            page.get_by_text('原解析与修订历史', exact=True).wait_for()
            page.get_by_text('原解析与修订历史', exact=True).click()
            page.get_by_text('原解析与修订历史', exact=True).scroll_into_view_if_needed()
            self.capture('26-jd-history-' + size)
            page.goto('http://localhost:5173/training?planId=' + self.state['new_plan'])
            page.get_by_role('button', name='已自评完成阅读').wait_for()
            page.get_by_role('button', name='已自评完成阅读').scroll_into_view_if_needed()
            self.capture('27-reading-final-' + size)
            overlap = page.evaluate('''() => {
                const header = document.querySelector('header.header').getBoundingClientRect();
                const content = document.querySelector('.main-layout__content').getBoundingClientRect();
                return header.bottom > content.top + 1;
            }''')
            self.check('U03-header-does-not-overlay-' + size, not overlap)
            page.goto('http://localhost:5173/interview?interviewId=' + self.state['interview'] + '&report=1')
            page.get_by_text('证据状态', exact=True).wait_for()
            page.get_by_text('证据状态', exact=True).scroll_into_view_if_needed()
            self.capture('28-real-interview-report-' + size)
        report = json.loads((self.out / 'interview-real-report.json').read_text())
        for mode in ['NO_SKILL_SCORES', 'NO_VALID_ANSWER']:
            sample = copy.deepcopy(report)
            sample['evidenceIds'] = []
            sample['skillEvidenceStatus'] = mode
            sample.pop('skillEvidenceMessage', None)
            if mode == 'NO_VALID_ANSWER':
                sample['completionType'] = mode
                sample['answeredQuestionCount'] = 0
                sample['evaluation']['overallScore'] = None
                sample['evaluation']['dimensions'] = []
                sample['evaluation']['evidenceStatus'] = 'INSUFFICIENT'

            def fixture(route):
                route.fulfill(status=200, content_type='application/json', body=json.dumps({'code': 200, 'data': sample}))

            route_pattern = '**/api/v1/interviews/' + self.state['interview'] + '/report'
            self.context.route(route_pattern, fixture)
            page.reload()
            message = '本次没有有效回答' if mode == 'NO_VALID_ANSWER' else '本次有有效总评'
            page.get_by_text(message, exact=False).wait_for()
            page.get_by_text(message, exact=False).scroll_into_view_if_needed()
            self.capture('29-report-fixture-' + mode)
            self.check('G01-explanation-ui-' + mode, True, fixture=True)
            self.context.unroute(route_pattern, fixture)
        self.save('ui-fixture-scope', {'reportFixtures': ['NO_SKILL_SCORES', 'NO_VALID_ANSWER'],
                  'purpose': '仅页面解释展示；持久化行为由隔离MySQL测试验证，未写入合成账号报告'})
        page.goto('http://localhost:5173/jobs?tab=search&searchId=7701c8d4-6fa5-463b-bc3e-dc2c11400845')
        page.get_by_text('本次搜索时间：', exact=False).wait_for()
        page.get_by_text('本次搜索时间：', exact=False).scroll_into_view_if_needed()
        self.capture('30-search-snapshot-time')
        self.check('S04-cache-time-label', '并非实时状态' in page.get_by_text('本次搜索时间：', exact=False).inner_text())
        self.browser_instance.close()
        self.runtime.stop()


def run(args):
    audit = Audit(args)
    getattr(audit, args.seven_phase)()
