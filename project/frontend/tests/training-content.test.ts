import test from 'node:test'
import assert from 'node:assert/strict'
import { stemSegments } from '../src/utils/training-content.ts'

test('renders a leading code fence as copyable code, preserving indentation', () => {
  assert.deepEqual(stemSegments('```python\n  print(1)\n```\nExplain the result.'), [
    { type: 'code', content: '  print(1)', language: 'python' },
    { type: 'text', content: '\nExplain the result.' },
  ])
})

test('preserves multiple blocks, Windows newlines, and intervening prose', () => {
  assert.deepEqual(stemSegments('Before\n```c#\r\nx();\r\n```\nAfter\n```sql\nselect 1;\n```'), [
    { type: 'text', content: 'Before\n' },
    { type: 'code', content: 'x();', language: 'c#' },
    { type: 'text', content: '\nAfter\n' },
    { type: 'code', content: 'select 1;', language: 'sql' },
  ])
})

test('keeps incomplete fences readable and never interprets HTML', () => {
  assert.deepEqual(stemSegments('```js\n<script>alert(1)</script>'), [
    { type: 'text', content: '```js\n<script>alert(1)</script>' },
  ])
})
