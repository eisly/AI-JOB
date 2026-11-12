from datetime import datetime, timezone
from types import SimpleNamespace
import json
import pytest
from app.search.models import SourcePosting
from app.search.dedupe import dedupe_postings
from app.search.sources.detail import read_detail_result
from app.search.budget import SearchBudget, BudgetConfig
from app.search.page_body import listing_fields

def posting(**values):
    base = dict(source_key='web', source_name='平台', source_type='PUBLIC_SEARCH',
                source_url='https://board.example.com/job/1', title='后端开发',
                company_name='示例有限公司', city_code='330100', candidate_type='INTERNSHIP',
                jd_text='负责服务端开发和测试，维护业务接口与数据库。')
    return SourcePosting(**(base | values))

def test_official_primary_third_party_fills_atomic_salary_and_size():
    official = posting(source_key='company_official', source_type='OFFICIAL',
                       source_url='https://careers.example.com/job/1', verification_status='CLOSED')
    board = posting(salary_text='100-150元/天', company_size='SIZE_1000_9999',
                    company_size_text='2000-5000人', verification_status='ACTIVE_CONFIRMED')
    rows, _ = dedupe_postings([board, official])
    assert len(rows) == 1
    assert rows[0].source_url == official.source_url
    assert rows[0].verification_status == 'CLOSED'
    assert rows[0].salary_text == '100-150元/天'
    assert rows[0].salary_min_monthly_cents is None
    assert rows[0].company_size_text == '2000-5000人'
    assert rows[0].field_sources['salary']['sourceUrl'] == board.source_url

@pytest.mark.parametrize('change', [{'company_name':'另一家公司'}, {'candidate_type':'CAMPUS'}, {'city_code':'110100'}])
def test_similar_jd_does_not_merge_conflicting_jobs(change):
    rows, _ = dedupe_postings([posting(), posting(source_url='https://other.example.com/job/2', **change)])
    assert len(rows) == 2

def test_structured_fields_survive_positive_verification_and_recommendations():
    url = 'https://careers.example.com/job/123'
    job = {'@type':'JobPosting', 'url':url, 'title':'后端开发',
           'hiringOrganization':{'name':'示例有限公司'}, 'description':'岗位职责：后端开发，工作地点：杭州。学历本科。'}
    html = '<main><h1>后端开发</h1><span class="salary">100-150元/天</span><div class="job-properties">杭州 本科 实习</div></main>'
    html += '<aside class="recommend">郑州 招聘人数 1000-2000人 30-50K 截止日期2020-01-01 当前职位已下线</aside>'
    html += '<script type="application/ld+json">'+json.dumps(job)+'</script>'
    result = read_detail_result(url, html, datetime.now(timezone.utc))
    assert result.status == 'ACTIVE_PROBABLE'
    assert result.page['fields']['salary_text'] == '100-150元/天'
    assert result.page['fields']['city_code'] == '330100'
    assert '郑州' not in result.page['body']
    assert result.page['fields'].get('published_at') is None

def test_global_collection_deadline_reserves_thirty_seconds(monkeypatch):
    import app.search.budget as module
    monkeypatch.setattr(module.time, 'monotonic', lambda: 120)
    budget = SearchBudget(BudgetConfig(), started_at=0)
    assert budget.remaining_seconds() == 0
    assert not budget.can_call()

@pytest.mark.asyncio
@pytest.mark.parametrize('tenant, expected', [('employer', True), ('another', False)])
async def test_ats_requires_employer_specific_entry_link(tenant, expected):
    from app.search.sources.company_official import promote_official
    from app.search.sources.detail import DetailResult
    from app.search.sources.base import SourceContext
    from app.search.models import SearchFilters
    url = 'https://ats.example.com/employer/jobs/123'
    root = 'https://employer.example.com/'
    job = {'@type':'JobPosting', 'title':'后端开发', 'url':url,
           'hiringOrganization':{'name':'示例有限公司', 'url':root}}
    cache = {
        url:DetailResult('UNKNOWN', 'DETAIL_NOT_FOUND', url, page={'html':'<script type="application/ld+json">'+json.dumps(job)+'</script>'}),
        root:DetailResult('UNKNOWN', 'DETAIL_NOT_FOUND', root, page={'body':'示例有限公司',
            'html':f'<a href="https://ats.example.com/{tenant}/">人才招聘</a>'}),
    }
    context = SourceContext(SearchFilters(), '后端开发', 10, extra={'budget':SearchBudget(BudgetConfig()), 'detail_cache':cache})
    row = posting(source_url=url)
    assert await promote_official(context, row) is expected
    assert row.source_type == ('OFFICIAL' if expected else 'PUBLIC_SEARCH')

def test_company_size_can_fill_other_job_but_salary_cannot():
    first = posting(title='数据开发', salary_text='100-150元/天', company_size='SIZE_1000_9999', company_size_text='2000-5000人')
    other = posting(source_url='https://other.example.com/job/2')
    result, _ = dedupe_postings([first, other])
    other = next(p for p in result if p.title == '后端开发')
    assert other.company_size_text == '2000-5000人'
    assert other.salary_text is None

@pytest.mark.asyncio
@pytest.mark.parametrize('mode', ['complete', 'missing_fields', 'no_official'])
async def test_pipeline_official_first_and_conditional_fallback(mode):
    from app.fake_model import FakeLLM
    from app.search.pipeline import JobSearchPipeline, SearchRuntime
    from app.search.sources.base import SourceAdapter
    calls = []
    class Adapter(SourceAdapter):
        def __init__(self, key, rows):
            self.key, self.rows = key, rows
        async def search(self, context):
            calls.append(self.key)
            return self.result('AVAILABLE' if self.rows else 'EMPTY', self.rows)
    official = posting(source_key='company_official', source_type='OFFICIAL',
                       source_url='https://careers.example.com/job/123')
    if mode == 'complete':
        official.salary_text, official.company_size = '100-150元/天', 'SIZE_1000_9999'
    board = posting(salary_text='100-150元/天', company_size='SIZE_1000_9999', company_size_text='2000-5000人')
    profile = {'targetRole':'JAVA_BACKEND', 'candidateType':'INTERNSHIP', 'cities':['330100'], 'facts':{}}
    data = {'filters':{'keyword':'后端开发', 'cities':['330100'], 'candidateTypes':['INTERNSHIP'], 'maxResults':1},
            'sources':['company_official','web']}
    async def emit(*args):
        pass
    pipeline = JobSearchPipeline(SearchRuntime(SimpleNamespace(job_search_max_rounds=1), FakeLLM(), emit, None, profile, data))
    pipeline.adapters = [Adapter('company_official', [] if mode == 'no_official' else [official]), Adapter('web', [board])]
    result = await pipeline.run()
    assert calls[0] == 'company_official'
    assert ('web' in calls) is (mode != 'complete')
    assert len(result['items']) == 1
    assert result['items'][0]['sourceType'] == ('PUBLIC_SEARCH' if mode == 'no_official' else 'OFFICIAL')
    if mode == 'missing_fields':
        assert result['items'][0]['salaryText'] == '100-150元/天'
        assert result['items'][0]['fieldSources']['salary']['sourceUrl'] == board.source_url

@pytest.mark.asyncio
async def test_source_timeout_keeps_completed_batch():
    import asyncio
    from app.fake_model import FakeLLM
    from app.search.pipeline import JobSearchPipeline, SearchRuntime
    from app.search.sources.base import SourceAdapter
    class SlowSource(SourceAdapter):
        key = 'web'
        async def search(self, context):
            context.extra['partial_postings'].append(posting())
            await asyncio.sleep(0.2)
    async def emit(*args):
        pass
    data = {'filters':{'keyword':'后端开发'}, 'sources':['web']}
    runtime = SearchRuntime(SimpleNamespace(job_search_max_elapsed_seconds=30.05, job_search_max_rounds=1), FakeLLM(), emit, None, {}, data)
    pipeline = JobSearchPipeline(runtime)
    pipeline.adapters = [SlowSource()]
    result = await pipeline.run()
    assert len(result['items']) == 1
    assert result['sources'][0]['status'] == 'PARTIAL'

@pytest.mark.asyncio
async def test_detail_cache_reused_across_calls(monkeypatch):
    from app.search.sources.web import _fetch_detail_cached
    from app.search.sources.detail import DetailResult
    calls = []
    async def fetch(client, url, timeout, *, budget=None):
        calls.append(url)
        return DetailResult('UNKNOWN', 'HTTP_403', url, http_status=403)
    monkeypatch.setattr('app.search.sources.web.fetch_detail', fetch)
    context = SimpleNamespace(client=None, extra={'budget':SearchBudget(BudgetConfig())})
    first = await _fetch_detail_cached(context, 'https://example.com/job/123', 5)
    assert await _fetch_detail_cached(context, 'https://example.com/job/123', 5) is first
    assert len(calls) == 1
