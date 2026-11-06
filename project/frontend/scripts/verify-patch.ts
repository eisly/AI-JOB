/**
 * buildUpdatePayload 针对性验证（node --experimental-strip-types 直接运行，零测试框架依赖）。
 * 覆盖 2026-11-03 审查修复点：
 * - sameText 曾导致“非空→非空”文本修改漏提交（cityCode/education/salaryText/description/note）
 * - 删尾页码回退为 UI 时机问题（浏览器验证），本脚本聚焦 PATCH 差量纯函数
 */
import { buildUpdatePayload, type ApplicationFormFieldValues, type ApplicationVO } from '../src/types/application.ts'

let passed = 0
let failed = 0

function check(name: string, condition: boolean, detail?: string): void {
  if (condition) {
    passed++
    console.log(`  PASS ${name}`)
  } else {
    failed++
    console.log(`  FAIL ${name}${detail ? ' :: ' + detail : ''}`)
  }
}

function makeBaseline(overrides: Partial<ApplicationVO> = {}): ApplicationVO {
  return {
    id: 'a0000000-0000-4000-8000-000000000001',
    jobId: null,
    companyName: '云启智能',
    jobTitle: 'Java 后端工程师',
    cityCode: '330100',
    education: '本科',
    salaryText: '10-16K',
    description: '后端开发',
    status: 'APPLIED',
    plannedDate: '2026-10-25',
    appliedDate: '2026-10-26',
    interviewDate: null,
    closedDate: null,
    note: '等待反馈',
    version: 3,
    createdAt: '2026-10-26T02:00:00Z',
    updatedAt: '2026-10-26T02:00:00Z',
    ...overrides,
  }
}

function makeForm(overrides: Partial<ApplicationFormFieldValues> = {}): ApplicationFormFieldValues {
  return {
    companyName: '云启智能',
    jobTitle: 'Java 后端工程师',
    cityCode: '330100',
    education: '本科',
    salaryText: '10-16K',
    description: '后端开发',
    status: 'APPLIED',
    plannedDate: '2026-10-25',
    appliedDate: '2026-10-26',
    interviewDate: '',
    closedDate: '',
    note: '等待反馈',
    ...overrides,
  }
}

console.log('== 1. 无任何修改 → null（阻止空提交） ==')
{
  const payload = buildUpdatePayload(makeBaseline(), makeForm())
  check('无变化返回 null', payload === null)
}

console.log('== 2. sameText 修复：非空→非空文本修改必须进 payload（曾全部漏提交） ==')
{
  const payload = buildUpdatePayload(makeBaseline(), makeForm({ cityCode: '310000' }))
  check('cityCode 330100→310000 进入 payload', payload !== null && payload.cityCode === '310000',
    JSON.stringify(payload))
}
{
  const payload = buildUpdatePayload(makeBaseline(), makeForm({ note: '已回复' }))
  check('note 等待反馈→已回复 进入 payload', payload !== null && payload.note === '已回复',
    JSON.stringify(payload))
}
{
  const payload = buildUpdatePayload(makeBaseline(), makeForm({ education: '硕士', salaryText: '20-25K', description: '平台开发' }))
  check('education 进入 payload', payload?.education === '硕士')
  check('salaryText 进入 payload', payload?.salaryText === '20-25K')
  check('description 进入 payload', payload?.description === '平台开发')
  check('未变化字段不出现（cityCode 不在 payload）', !('cityCode' in (payload ?? {})))
  check('expectedVersion 携带基线版本', payload?.expectedVersion === 3)
}

console.log('== 3. 文本 trim 口径：表单值带空白时按 trim 比较（与提交一致） ==')
{
  const payload = buildUpdatePayload(makeBaseline(), makeForm({ note: '  等待反馈  ' }))
  check('trim 后同值不重复提交', payload === null, JSON.stringify(payload))
}
{
  const payload = buildUpdatePayload(makeBaseline(), makeForm({ note: '  已回复 ' }))
  check('带空白的新值 trim 后提交', payload?.note === '已回复')
}

console.log('== 4. 清空可空字段 → 显式 null ==')
{
  const payload = buildUpdatePayload(makeBaseline(), makeForm({ note: '' }))
  check('note 清空发 null', payload?.note === null)
}
{
  const payload = buildUpdatePayload(makeBaseline(), makeForm({ note: '   ' }))
  check('note 纯空白视为清空发 null', payload?.note === null)
}
{
  const payload = buildUpdatePayload(makeBaseline(), makeForm({ cityCode: '', education: '', salaryText: '', description: '', note: '' }))
  check('全部可空文本清空均发 null', payload?.cityCode === null && payload?.education === null
    && payload?.salaryText === null && payload?.description === null && payload?.note === null)
}

console.log('== 5. 空态→填入（基线 null，表单有值） ==')
{
  const payload = buildUpdatePayload(makeBaseline({ interviewDate: null }), makeForm({ interviewDate: '2026-10-28' }))
  check('interviewDate 填入发新值', payload?.interviewDate === '2026-10-28')
}
{
  const payload = buildUpdatePayload(makeBaseline({ cityCode: null }), makeForm({ cityCode: '330100' }))
  check('cityCode 填入发新值', payload?.cityCode === '330100')
}

console.log('== 6. 日期字段：未变化不发 / 清空发 null / 变化发新值 ==')
{
  const payload = buildUpdatePayload(makeBaseline(), makeForm())
  check('日期未变化不出现', payload === null)
}
{
  const payload = buildUpdatePayload(makeBaseline(), makeForm({ appliedDate: '' }))
  check('appliedDate 清空发 null', payload?.appliedDate === null)
}
{
  const payload = buildUpdatePayload(makeBaseline(), makeForm({ plannedDate: '2026-11-01' }))
  check('plannedDate 变化发新值', payload?.plannedDate === '2026-11-01')
}

console.log('== 7. 必填字段与状态 ==')
{
  const payload = buildUpdatePayload(makeBaseline(), makeForm({ companyName: '新公司' }))
  check('companyName 修改进 payload', payload?.companyName === '新公司')
}
{
  const payload = buildUpdatePayload(makeBaseline(), makeForm({ jobTitle: '新岗位' }))
  check('jobTitle 修改进 payload', payload?.jobTitle === '新岗位')
}
{
  const payload = buildUpdatePayload(makeBaseline(), makeForm({ status: 'CLOSED' }))
  check('status 变化进 payload（APPLIED→CLOSED 回退合法）', payload?.status === 'CLOSED')
}
{
  // 必填字段留空：不应发 companyName 键（前端表单校验会拦截，函数层也不得发 null）
  const payload = buildUpdatePayload(makeBaseline(), makeForm({ companyName: '' }))
  check('companyName 空值不发送键', payload === null || !('companyName' in payload))
}

console.log('== 8. 状态变化但日期字段保持基线（不联动改日期） ==')
{
  const payload = buildUpdatePayload(makeBaseline(), makeForm({ status: 'INTERVIEWING' }))
  check('仅 status 变化时日期不进 payload', payload?.status === 'INTERVIEWING'
    && !('plannedDate' in payload) && !('appliedDate' in payload))
}

console.log('')
console.log(`RESULT: passed=${passed} failed=${failed}`)
process.exit(failed === 0 ? 0 : 1)