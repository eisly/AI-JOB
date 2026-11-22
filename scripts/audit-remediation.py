#!/usr/bin/env python3
"""Capture the local product with a synthetic account; never print credentials."""
import argparse
import json
from pathlib import Path

from playwright.sync_api import sync_playwright


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://localhost:5173')
    parser.add_argument('--account-file', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--interactions', action='store_true')
    parser.add_argument('--interactions-only', action='store_true')
    parser.add_argument('--modules', nargs='*', help='Limit capture to matching visible menu names')
    parser.add_argument('--review-mistakes', action='store_true')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    account = json.loads(args.account_file.read_text())
    results = []
    with sync_playwright() as runtime:
        browser = runtime.chromium.launch()
        (args.output / 'environment.json').write_text(json.dumps({
            'browser': 'Chromium', 'version': browser.version, 'locale': 'zh-CN',
            'viewports': ['1440x1000', '390x844'], 'baseUrl': args.base_url,
        }, ensure_ascii=False, indent=2))
        context = browser.new_context(viewport={'width': 1440, 'height': 1000}, locale='zh-CN',
                                      permissions=['clipboard-read', 'clipboard-write'])
        page = context.new_page()
        page.goto(args.base_url + '/login')
        page.get_by_role('button', name='登录', exact=True).wait_for()
        (args.output / 'login.txt').write_text(page.locator('body').aria_snapshot())
        page.get_by_placeholder('请输入邮箱地址').fill(account['email'])
        page.get_by_placeholder('请输入密码', exact=True).fill(account['password'])
        page.get_by_role('button', name='登录', exact=True).click()
        page.wait_for_url('**/dashboard', timeout=20000)
        page.locator('aside').wait_for()
        page.wait_for_timeout(600)
        links = page.locator('a[href]').evaluate_all(
            '(items) => items.map(a => ({href: a.getAttribute("href"), text: a.textContent.trim()}))')
        (args.output / 'navigation.json').write_text(json.dumps(links, ensure_ascii=False, indent=2))
        menu_names = page.get_by_role('menuitem').all_text_contents()
        for size, viewport in [] if args.interactions_only else [('desktop', {'width': 1440, 'height': 1000}),
                               ('mobile', {'width': 390, 'height': 844})]:
            page.set_viewport_size(viewport)
            for index, menu_name in enumerate(menu_names):
                if args.modules and menu_name.strip() not in args.modules:
                    continue
                page.locator('body').aria_snapshot()
                page.get_by_role('menuitem', name=menu_name.strip(), exact=True).click()
                page.wait_for_load_state('networkidle', timeout=20000)
                page.wait_for_timeout(300)
                route = page.url.removeprefix(args.base_url)
                name = f'{index + 2:02}-{size}-{route.split("?")[0].strip("/").replace("/", "-") or "home"}'
                (args.output / f'{name}.txt').write_text(page.locator('body').aria_snapshot())
                page.screenshot(path=str(args.output / f'{name}.png'), full_page=True)
                metrics = page.evaluate('''() => ({
                    width: innerWidth, scrollWidth: document.documentElement.scrollWidth,
                    title: document.title,
                    heading: [...document.querySelectorAll('h1,h2')].map(e => e.textContent),
                    clippedCards: [...document.querySelectorAll('.plan-card,.task-mobile-card')].filter(e =>
                        e.getBoundingClientRect().width > 0 && e.scrollWidth > e.clientWidth + 2).length,
                    unlabeledInputs: [...document.querySelectorAll('input,textarea')].filter(e =>
                        e.getBoundingClientRect().width > 0 && !e.labels?.length &&
                        !e.getAttribute('aria-label') && !e.getAttribute('aria-labelledby')).length
                })''')
                results.append({'route': route, 'size': size, 'artifact': name, **metrics})
                print(json.dumps(results[-1], ensure_ascii=False), flush=True)
                (args.output / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
        if args.interactions or args.interactions_only:
            page.set_viewport_size({'width': 390, 'height': 844})
            page.goto(args.base_url + '/training')
            page.wait_for_load_state('networkidle')
            page.locator('body').aria_snapshot()
            page.get_by_role('button', name='Java 后端岗位差距专项训练', exact=False).click()
            page.get_by_text('正在加载数据', exact=True).wait_for(state='hidden')
            page.wait_for_timeout(1200)
            (args.output / '13-training-selected.txt').write_text(page.locator('body').aria_snapshot())
            page.screenshot(path=str(args.output / '13-training-selected.png'))
            page.locator('body').aria_snapshot()
            start = page.get_by_role('button', name='开始答题', exact=True).first
            if start.count() == 0:
                start = page.get_by_role('button', name='继续答题', exact=True).first
            start.click()
            answer = page.locator('textarea').first
            answer.wait_for(timeout=180000)
            page.wait_for_timeout(700)
            focused = answer.evaluate('(e) => document.activeElement === e')
            bounds = answer.bounding_box()
            results.append({'check': 'mobile-answer-focus', 'focused': focused,
                            'visible': bool(bounds and 0 <= bounds['y'] < 844), 'url': page.url})
            (args.output / '14-question.txt').write_text(page.locator('body').aria_snapshot())
            page.screenshot(path=str(args.output / '14-question.png'))
            old_draft = answer.input_value()
            draft = '整改交互测试草稿：说明索引与执行计划，不提交评分。'
            answer.fill(draft)
            page.wait_for_timeout(500)
            session_url = page.url
            page.goto(args.base_url + '/dashboard')
            page.wait_for_load_state('networkidle')
            page.goto(session_url)
            answer.wait_for(timeout=20000)
            results.append({'check': 'draft-after-navigation', 'restored': answer.input_value() == draft})
            answer.fill(old_draft)
            page.reload()
            answer.wait_for(timeout=20000)
            results.append({'check': 'protected-refresh', 'route': page.url, 'answerVisible': answer.is_visible()})
            # This response fixture checks rendering/copy only; no answer is submitted.
            fixture_code = 'SELECT id, created_at FROM orders WHERE user_id = 10086;'
            def question_fixture(route):
                response = route.fetch()
                body = response.json()
                if body.get('data', {}).get('question'):
                    body['data']['question']['stem'] = '```sql\n' + fixture_code + '\n```\n浏览器渲染测试题，不用于评分。'
                    body['data']['question']['generationEnv'] = 'FAKE'
                route.fulfill(response=response, json=body)
            context.route('**/api/v1/learning/sessions/*', question_fixture)
            page.reload()
            code = page.get_by_label('题目代码，可横向滚动')
            code.wait_for(timeout=20000)
            code.scroll_into_view_if_needed()
            page.locator('body').aria_snapshot()
            page.get_by_role('button', name='复制第 1 段代码').click()
            copied = page.evaluate('navigator.clipboard.readText()')
            results.append({'check': 'code-copy-fixture', 'copied': copied == fixture_code,
                            'simulation': True, 'keyboardFocusable': code.get_attribute('tabindex') == '0'})
            page.screenshot(path=str(args.output / '19-code-copy-fixture.png'))
            context.unroute('**/api/v1/learning/sessions/*', question_fixture)
            page.goto(args.base_url + '/tasks')
            page.get_by_role('button', name='实时详情', exact=True).first.wait_for()
            page.locator('body').aria_snapshot()
            page.get_by_role('button', name='实时详情', exact=True).first.click()
            page.wait_for_timeout(700)
            (args.output / '15-task-detail.txt').write_text(page.locator('body').aria_snapshot())
            page.screenshot(path=str(args.output / '15-task-detail.png'))
            guest = browser.new_context(viewport={'width': 390, 'height': 844}, locale='zh-CN')
            public = guest.new_page()
            public.goto(args.base_url + '/login')
            public.get_by_role('button', name='注册账号', exact=True).wait_for()
            public.locator('body').aria_snapshot()
            public.get_by_role('button', name='注册账号', exact=True).click()
            public.get_by_role('dialog').wait_for()
            public.wait_for_timeout(300)
            box = public.locator('.el-dialog').bounding_box()
            results.append({'check': 'mobile-registration-dialog', 'bounds': box,
                            'fits': bool(box and box['x'] >= 0 and box['x'] + box['width'] <= 390)})
            public.screenshot(path=str(args.output / '16-register-dialog.png'))
            (args.output / '16-register-dialog.txt').write_text(public.locator('body').aria_snapshot())
            public.goto(args.base_url + '/register')
            public.get_by_role('button', name='注册账号', exact=True).wait_for()
            public.locator('body').aria_snapshot()
            public.get_by_role('button', name='注册账号', exact=True).click()
            public.wait_for_timeout(300)
            public.screenshot(path=str(args.output / '17-register-errors.png'))
            (args.output / '17-register-errors.txt').write_text(public.locator('body').aria_snapshot())
            public.goto(args.base_url + '/forgot-password')
            public.get_by_placeholder('请输入注册邮箱').fill('offline-audit@example.invalid')
            guest.set_offline(True)
            public.locator('body').aria_snapshot()
            public.get_by_role('button', name='发送验证码').click()
            public.wait_for_timeout(500)
            public.screenshot(path=str(args.output / '18-offline-password.png'))
            (args.output / '18-offline-password.txt').write_text(public.locator('body').aria_snapshot())
            guest.set_offline(False)
            (args.output / 'interaction-results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
        if args.review_mistakes:
            for size, width, height in [('desktop', 1440, 1000), ('mobile', 390, 844)]:
                page.set_viewport_size({'width': width, 'height': height})
                page.goto(args.base_url + '/training?tab=mistakes')
                marker = page.get_by_text('不会 · 1 次', exact=True).first
                marker.wait_for(timeout=20000)
                marker.scroll_into_view_if_needed()
                page.wait_for_timeout(500)
                (args.output / f'20-{size}-review.txt').write_text(page.locator('body').aria_snapshot())
                page.screenshot(path=str(args.output / f'20-{size}-review.png'))
        browser.close()


if __name__ == '__main__':
    main()
