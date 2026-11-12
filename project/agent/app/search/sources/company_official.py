"""Discover corporate job pages, requiring a corporate entry link as evidence."""
from dataclasses import replace
from urllib.parse import urlsplit, urljoin
from .web import WebSearchSource, _fetch_detail_cached
from .base import SourceContext
from ..expansion import build_query_specs
from ..page_body import Tree
from ..models import normalize_company

PLATFORMS = {'liepin.com', 'zhipin.com', 'shixiseng.com', 'nowcoder.com', 'linkedin.com', 'offerseek.ai'}

class CompanyOfficialSource(WebSearchSource):
    key = 'company_official'
    name = '企业招聘官网'
    type = 'OFFICIAL'

    async def search(self, context):
        specs = context.extra.get('query_specs') or build_query_specs(context.filters, context.keyword, limit=4)
        limit = max(1, int(getattr(context.settings, "job_search_company_official_queries", 4)))
        context.extra["query_specs"] = [replace(s, query=s.query + " 公司官网 招聘") for s in specs[:limit]]
        # Each source has its own pending queue; network cache and limits are shared.
        result = await super().search(context)
        accepted = []
        context.extra.setdefault("fallback_candidates", [])
        for posting in result.postings:
            if await promote_official(context, posting):
                accepted.append(posting)
            else:
                context.extra["fallback_candidates"].append(posting)
        result.postings = accepted
        for key, status in [("confirmedOpen", "ACTIVE_CONFIRMED"), ("probable", "ACTIVE_PROBABLE"), ("closed", "CLOSED"), ("unknown", "UNKNOWN")]:
            result.verification_counts[key] = sum(p.verification_status == status for p in accepted)
        if not accepted and result.status == 'AVAILABLE':
            result.status = 'EMPTY'
        result.message += f'；官网归属确认 {len(accepted)} 条'
        return result

async def promote_official(context, posting):
    host = urlsplit(posting.source_url).hostname or ''
    if any(host == h or host.endswith('.' + h) for h in PLATFORMS):
        return False
    registry = context.extra.get('company_registry')
    entries = registry.entries.values() if registry else []
    trusted = any(e.enabled and e.official_domain == host and
                  normalize_company(e.company) == normalize_company(posting.company_name) for e in entries)
    if not trusted:
        cache = context.extra.get('detail_cache', {})
        detail = cache.get(posting.source_url)
        html = (detail.page or {}).get('html', '') if detail else ''
        from ..page_body import structured_job
        job = structured_job(html, posting.source_url)
        organization = (job or {}).get('hiringOrganization') or {}
        root = organization.get('url') if isinstance(organization, dict) else None
        if not root:
            from ..providers import resolve_providers
            providers = resolve_providers(context.settings)
            state = context.extra.setdefault("official_lookups", {})
            company = posting.company_name
            if not providers or len(state) >= 6 or not context.extra['budget'].can_call():
                return False
            if company not in state:
                budget = context.extra['budget']
                budget.record_api_call()
                found = await providers[0].search(context.client, company + " 公司官网 招聘", settings=context.settings,
                    limit=3, timeout=min(5, budget.remaining_seconds()), retries=0)
                state[company] = next((h.url for h in found.hits
                    if not any((urlsplit(h.url).hostname or '').endswith(p) for p in PLATFORMS)
                    and normalize_company(company) in normalize_company(h.title)), None)
            root = state[company]
            if not root:
                return False
        budget = context.extra['budget']
        if not budget.can_call():
            return False
        budget.record_api_call()
        page = await _fetch_detail_cached(context, root, min(5, budget.remaining_seconds()))
        body = (page.page or {}).get('body', '')
        if normalize_company(posting.company_name) not in normalize_company(body):
            return False
        for node in Tree((page.page or {}).get('html', '')).root.walk():
            href = urljoin(root, node.attrs.get('href', ''))
            target = urlsplit(href)
            candidate = urlsplit(posting.source_url)
            if node.tag == 'a' and target.scheme == 'https' and target.hostname == candidate.hostname:
                # ATS tenants share hosts; require the specific tenant path.
                if target.hostname == urlsplit(root).hostname or candidate.path.startswith(target.path.rstrip('/') + '/') or href == posting.source_url:
                    trusted = True
                    break
    if trusted:
        posting.source_key = 'company_official'
        posting.source_type = 'OFFICIAL'
        posting.source_name = posting.company_name + '招聘官网'
        posting.verification = 'VERIFIED'
        for evidence in posting.field_sources.values():
            evidence.update(sourceType='OFFICIAL', sourceName=posting.source_name)
    return trusted
