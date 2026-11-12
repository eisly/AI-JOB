import sys
import httpx
import pytest
from app.search.sources.render import render_detail

URL = 'https://careers.example.com/job/123'

@pytest.mark.asyncio
async def test_js_fields_and_private_subrequest_blocked(monkeypatch):
    seen = []
    async def resolve(host):
        if host != 'careers.example.com':
            raise ValueError('private destination')
        return '93.184.216.34'
    monkeypatch.setattr('app.search.sources.render.resolve_public_ipv4', resolve)
    html = '''<main id="job"></main><script>
    document.querySelector('main').innerHTML='<h1>后端开发实习生</h1><span class="salary">100-150元/天</span><div class="job-properties">杭州 本科 实习</div>';
    fetch('https://127.0.0.1/private').catch(()=>{});
    </script>'''
    def respond(request):
        seen.append(str(request.url))
        return httpx.Response(200, text=html, headers={'content-type':'text/html; charset=utf-8'})
    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        result = await render_detail(client, URL)
    assert result.page, result.detail
    assert result.page['fields']['salary_text'] == '100-150元/天'
    assert all('127.0.0.1' not in url for url in seen)

@pytest.mark.asyncio
@pytest.mark.parametrize('html, reason', [('<body>请先登录 验证码</body>', 'HTTP_403'),
    ('<script>location.href="https://127.0.0.1/private"</script>', 'PAGE_ACCESS_FAILED')])
async def test_login_and_redirect_are_not_jobs(monkeypatch, html, reason):
    async def resolve(host):
        if host != 'careers.example.com':
            raise ValueError('private')
        return '93.184.216.34'
    monkeypatch.setattr('app.search.sources.render.resolve_public_ipv4', resolve)
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r:httpx.Response(200, text=html,
                headers={'content-type':'text/html; charset=utf-8'}))) as client:
        result = await render_detail(client, URL)
    assert result.status == 'UNKNOWN'
    assert result.reason == reason

@pytest.mark.asyncio
async def test_missing_browser_package_degrades(monkeypatch):
    monkeypatch.setitem(sys.modules, 'playwright.async_api', None)
    assert (await render_detail(None, URL)).status == 'UNKNOWN'

@pytest.mark.asyncio
async def test_render_timeout_returns_unknown():
    assert (await render_detail(None, URL, timeout=0.001)).reason == 'TIMEOUT'
