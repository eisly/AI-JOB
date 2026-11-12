"""Select job content before running text extraction; never scan recommendations."""
import json
import re
from html.parser import HTMLParser

class Node:
    def __init__(self, tag='', attrs=()):
        self.tag, self.attrs, self.children = tag, dict(attrs), []
    def text(self):
        if self.tag in {'script', 'style', 'nav', 'footer', 'header', 'aside'}:
            return ''
        marker = self.attrs.get('class', '') + ' ' + self.attrs.get('id', '')
        if re.search(r'recommend|related-job|similar-job|hot-job', marker, re.I):
            return ''
        return ' '.join(x.text() if isinstance(x, Node) else x for x in self.children)
    def walk(self):
        yield self
        for child in self.children:
            if isinstance(child, Node):
                yield from child.walk()

class Tree(HTMLParser):
    def __init__(self, html):
        super().__init__(convert_charrefs=True)
        self.root = Node()
        self.stack = [self.root]
        self.feed(html)
    def handle_starttag(self, tag, attrs):
        node = Node(tag, attrs)
        self.stack[-1].children.append(node)
        if tag not in {'meta', 'link', 'br', 'hr', 'img', 'input', 'source', 'wbr', 'embed', 'area', 'base', 'col', 'param'}:
            self.stack.append(node)
    def handle_endtag(self, tag):
        for i in range(len(self.stack)-1, 0, -1):
            if self.stack[i].tag == tag:
                del self.stack[i:]
                break
    def handle_data(self, data):
        self.stack[-1].children.append(data)

def job_body(html):
    tree = Tree(html)
    nodes = list(tree.root.walk())
    candidates = [n for n in nodes if n.tag in {'main', 'article'} or
                  re.search(r'job-detail|job_detail|jobDetail|position-detail|job-description',
                            n.attrs.get('class', '') + ' ' + n.attrs.get('id', ''))]
    return ' '.join((max(candidates, key=lambda n: len(n.text())).text()
                     if candidates else tree.root.text()).split())

def structured_job(html, url):
    from .sources.detail import canonical_detail, _job_postings
    from urllib.parse import urljoin
    jobs = []
    for n in Tree(html).root.walk():
        if n.tag == 'script' and n.attrs.get('type') == 'application/ld+json':
            try:
                jobs.extend(_job_postings(json.loads(''.join(x for x in n.children if isinstance(x, str)))))
            except (ValueError, RecursionError):
                pass
    matching = [j for j in jobs if j.get('url') and canonical_detail(urljoin(url, str(j['url']))) == canonical_detail(url)]
    return matching[0] if len(matching) == 1 else jobs[0] if len(jobs) == 1 and not jobs[0].get('url') else None

def listing_fields(html, url=''):
    from .text_extract import extract_job_fields, _html_text
    tree = Tree(html)
    nodes = list(tree.root.walk())
    result = {}
    for n in nodes:
        classes = set(n.attrs.get('class', '').split())
        text = ' '.join(n.text().split())
        if classes & {'salary', 'job-salary', 'job-properties'} and text in job_body(html):
            fields = vars(extract_job_fields(text))
            if 'job-properties' in classes:
                from .models import city_code_for
                city = text.split()[0] if text else ''
                if city_code_for(city):
                    fields.update(city_name=city, city_code=city_code_for(city))
            result.update({k:v for k,v in fields.items() if v not in (None, '', 'SIZE_UNKNOWN')})
    body = job_body(html)
    for label, key in [('人数规模', 'company_size_text')]:
        match = re.search(label + r'\s*[:：]?\s*(\d+\s*[-~至]\s*\d+\s*人|\d+\s*人以上)', body)
        if match:
            from .models import parse_company_size
            result.update(company_size_text=match[1], company_size=parse_company_size(match[1]))
    company = re.search(r'公司信息\s+(.{2,80}?(?:有限责任公司|股份有限公司|有限公司))', body)
    if company:
        result['company_name'] = company[1]
    job = structured_job(html, url)
    if job:
        description = _html_text(str(job.get('description') or ''))
        fields = vars(extract_job_fields(description, title=str(job.get('title') or '')))
        result.update({k:v for k,v in fields.items() if v not in (None, '', 'SIZE_UNKNOWN')})
        organization = job.get('hiringOrganization') or {}
        if isinstance(organization, dict) and organization.get('name'):
            result['company_name'] = str(organization['name'])
        location = job.get('jobLocation') or {}
        if isinstance(location, list):
            location = location[0] if len(location) == 1 else {}
        address = location.get('address', {}) if isinstance(location, dict) else {}
        if isinstance(address, dict) and address.get('addressLocality'):
            from .models import city_code_for
            city = str(address['addressLocality'])
            result.update(city_name=city, city_code=city_code_for(city))
        salary = job.get('baseSalary') or {}
        if isinstance(salary, dict) and salary.get('currency') in ('CNY', 'RMB'):
            amount = salary.get('value') or {}
            if isinstance(amount, dict):
                units = {'MONTH':'元/月', 'YEAR':'元/年', 'DAY':'元/天', 'HOUR':'元/时'}
                low, high = amount.get('minValue', amount.get('value')), amount.get('maxValue', amount.get('value'))
                unit = units.get(str(amount.get('unitText', '')).upper())
                if unit and isinstance(low, (int, float)) and isinstance(high, (int, float)) and 0 <= low <= high:
                    from .models import parse_salary
                    text = f"{low:g}-{high:g}{unit}"
                    minimum, maximum = parse_salary(text)
                    result.update(salary_text=text, salary_min_monthly_cents=minimum, salary_max_monthly_cents=maximum)
        if job.get('datePosted'):
            from datetime import datetime
            try:
                result['published_at'] = datetime.fromisoformat(str(job['datePosted']).replace('Z', '+00:00'))
            except ValueError:
                pass
    return result
