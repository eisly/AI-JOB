"""Anonymous, bounded renderer. All HTTP is served through validated pinned connections."""
import asyncio
from urllib.parse import urlsplit, urlunsplit
from ..security import resolve_public_ipv4

async def render_detail(client, url, timeout=10, *, budget=None):
    from .detail import DetailResult, read_detail_result, canonical_detail
    from datetime import datetime, timezone
    if timeout <= 0.05:
        return DetailResult('UNKNOWN', 'TIMEOUT', url, detail='渲染预算已用完')
    try:
        from playwright.async_api import async_playwright
    except ImportError:
        return DetailResult('UNKNOWN', 'PAGE_ACCESS_FAILED', url, detail='浏览器依赖未安装')
    async def run():
        async with async_playwright() as api:
            browser = await api.chromium.launch(headless=True)
            try:
                context = await browser.new_context(service_workers='block', accept_downloads=False)
                requests = [0]
                async def route_request(route):
                    request = route.request
                    target = canonical_detail(request.url)
                    if (request.is_navigation_request() and target != canonical_detail(url)) or not target or requests[0] >= 12 or request.method not in {'GET', 'HEAD'} or request.resource_type in {'image', 'font', 'media'}:
                        await route.abort()
                        return
                    requests[0] += 1
                    try:
                        dest = urlsplit(target)
                        address = await resolve_public_ipv4(dest.hostname)
                        if budget:
                            if not budget.can_call():
                                await route.abort()
                                return
                            budget.record_api_call()
                        headers = {k:v for k,v in request.headers.items() if k.lower() not in {'host','cookie','authorization','content-length'}}
                        headers['Host'] = dest.hostname
                        async with client.stream(request.method, urlunsplit(dest._replace(netloc=address)),
                                headers=headers, content=request.post_data_buffer,
                                extensions={'sni_hostname': dest.hostname}, timeout=min(5, timeout), follow_redirects=False) as response:
                            data = bytearray()
                            async for chunk in response.aiter_bytes():
                                data.extend(chunk)
                                if len(data) > 2 * 1024 * 1024:
                                    raise ValueError('resource too large')
                            await route.fulfill(status=response.status_code, body=bytes(data),
                                headers={k:v for k,v in response.headers.items() if k.lower() not in {'content-encoding','content-length','transfer-encoding','set-cookie'}})
                    except Exception:
                        await route.abort()
                await context.route('**/*', route_request)
                await context.route_web_socket('**/*', lambda ws: ws.close())
                page = await context.new_page()
                await page.goto(url, wait_until='domcontentloaded', timeout=int(timeout*1000))
                await page.wait_for_timeout(500)
                if canonical_detail(page.url) != canonical_detail(url):
                    return DetailResult('UNKNOWN', 'PAGE_ACCESS_FAILED', url, detail='浏览器跳转到非岗位页')
                html = await page.content()
                body_text = await page.inner_text('body')
                if any(word in body_text for word in ['请先登录', '安全验证', '验证码']):
                    return DetailResult('UNKNOWN', 'HTTP_403', url, detail='页面要求登录或验证')
                return read_detail_result(url, html, datetime.now(timezone.utc))
            finally:
                await browser.close()
    try:
        return await asyncio.wait_for(run(), timeout=timeout)
    except TimeoutError:
        return DetailResult('UNKNOWN', 'TIMEOUT', url, detail='浏览器渲染超时')
    except Exception as exc:
        import logging
        logging.getLogger(__name__).warning('Job renderer unavailable: %s', type(exc).__name__)
        return DetailResult('UNKNOWN', 'PAGE_ACCESS_FAILED', url, detail='浏览器不可用：' + type(exc).__name__)
