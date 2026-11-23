import assert from 'node:assert/strict'
import test from 'node:test'
import { renderResumeMarkdown } from '../src/views/resume-optimization/resume-markdown.ts'

test('renders PDF sections and Markdown without splitting inline syntax at a change', () => {
  const html = renderResumeMarkdown([
    { text: '[page:1]\r\n□ 教育经历\r\n大学\r\n项目经历\r\n- 使用 **', changed: false },
    { text: 'Redis', changed: true },
    { text: '** 做缓存\r\n- 编写测试\r\n\r\n### 专业技能\r\n1. Java\r\n2. SQL', changed: false },
  ])

  assert.doesNotMatch(html, /\[page:1\]/)
  assert.match(html, /<h2>教育经历<\/h2>/)
  assert.match(html, /<h2>项目经历<\/h2>/)
  assert.match(html, /<li class="markdown-changed">使用 <strong>Redis<\/strong> 做缓存<\/li>/)
  assert.match(html, /<li>编写测试<\/li>/)
  assert.match(html, /<h3>专业技能<\/h3>\s*<ol>/)
})

test('escapes raw HTML and preserves page markers and section labels inside code fences', () => {
  const html = renderResumeMarkdown([{
    text: '```text\n[page:1]\n项目经历\n```\n\n<img src=x>\n[跳转](javascript:alert%281%29)',
    changed: false,
  }])
  assert.match(html, /<code class="language-text">\[page:1\]\n项目经历\n<\/code>/)
  assert.match(html, /&lt;img src=x&gt;/)
  assert.doesNotMatch(html, /<img|<a\b/)
})
