import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  AVATAR_MAX_BYTES,
  avatarValidation,
  countdownText,
  isValidCode,
  isValidPhone,
  maskPhone,
  nicknameUnchanged,
  passwordLength,
  passwordStrengthHint,
  validateNickname,
  validatePassword,
} from '../src/views/account/account-rules.ts'

test('昵称：去空格与长度边界', () => {
  assert.equal(validateNickname('  张  '), null)
  assert.equal(validateNickname('a'.repeat(30)), null)
  assert.equal(validateNickname('a'.repeat(31)), '昵称不能超过 30 个字符')
})

test('昵称：空值与全空格拒绝', () => {
  assert.equal(validateNickname(''), '昵称不能为空')
  assert.equal(validateNickname('   '), '昵称不能为空')
  assert.equal(validateNickname(null), '昵称不能为空')
})

test('昵称：控制字符拒绝', () => {
  assert.equal(validateNickname('昵称\n换行'), '昵称包含不支持的字符')
  assert.equal(validateNickname('昵称\t制表'), '昵称包含不支持的字符')
})

test('昵称：无变更判定', () => {
  assert.equal(nicknameUnchanged('  张三  ', '张三'), true)
  assert.equal(nicknameUnchanged('李四', '张三'), false)
})

test('密码：10/72 边界与 9 位拒绝', () => {
  assert.equal(passwordLength('a'.repeat(10)), 10)
  assert.equal(validatePassword('CurrentPass_1', 'a'.repeat(10), 'a'.repeat(10)), null)
  assert.equal(validatePassword('CurrentPass_1', 'a'.repeat(72), 'a'.repeat(72)), null)
  assert.equal(validatePassword('CurrentPass_1', 'a'.repeat(9), 'a'.repeat(9)), '密码长度需为 10-72 位')
})

test('密码：不一致与新旧相同', () => {
  assert.equal(validatePassword('CurrentPass_1', 'a'.repeat(12), 'b'.repeat(12)), '两次输入的密码不一致')
  assert.equal(validatePassword('CurrentPass_1', 'CurrentPass_1', 'CurrentPass_1'), '新密码不能与当前密码相同')
})

test('密码：强度提示不阻断', () => {
  assert.equal(validatePassword('CurrentPass_1', 'aaaaaaaaaaaa', 'aaaaaaaaaaaa'), null)
  assert.equal(passwordStrengthHint('abc123'), '已包含字母和数字')
  assert.equal(passwordStrengthHint('abcdef'), '建议包含字母和数字')
})

test('手机号与验证码格式', () => {
  assert.equal(isValidPhone('13812345678'), true)
  assert.equal(isValidPhone('12812345678'), false)
  assert.equal(isValidPhone('1381234567'), false)
  assert.equal(isValidCode('123456'), true)
  assert.equal(isValidCode('12345'), false)
  assert.equal(isValidCode('abcdef'), false)
})

test('手机号脱敏', () => {
  assert.equal(maskPhone('13812345678'), '138****5678')
  assert.equal(maskPhone('123'), null)
})

test('头像大小与类型校验', () => {
  assert.equal(avatarValidation({ size: 1024, type: 'image/png' }), null)
  assert.equal(avatarValidation({ size: AVATAR_MAX_BYTES + 1, type: 'image/png' }), '图片大小不能超过 2 MB')
  assert.equal(avatarValidation({ size: 1024, type: 'image/gif' }), '仅支持 JPG、PNG、WebP 格式')
  assert.equal(avatarValidation(null), '请选择要上传的图片')
})

test('倒计时文案', () => {
  assert.equal(countdownText(60), '60s 后重新获取')
  assert.equal(countdownText(0), '0s 后重新获取')
})