<script setup lang="ts">
import { computed, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { accountApi } from '@/api/account'
import type { AccountCapabilitiesVO, AccountProfileVO, PhonePurpose } from '@/api/types'
import { countdownText, isValidCode, isValidPhone } from '../account-rules'

const props = defineProps<{ profile: AccountProfileVO; capabilities: AccountCapabilitiesVO }>()
const emit = defineEmits<{ saved: [profile: AccountProfileVO] }>()

const unavailable = computed(() => !props.capabilities.smsBindingAvailable)

const mode = ref<'VIEW' | 'BIND' | 'REBIND'>('VIEW')
const state = reactive({
  phone: '',
  code: '',
  currentPassword: '',
  sending: false,
  submitting: false,
  countdown: 0,
  error: '',
  codeError: '',
})
let timer: ReturnType<typeof setInterval> | null = null

function clearTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

onUnmounted(clearTimer)

function startCountdown() {
  clearTimer()
  state.countdown = 60
  timer = setInterval(() => {
    state.countdown = Math.max(0, state.countdown - 1)
    if (state.countdown === 0) clearTimer()
  }, 1000)
}

function open(modeValue: 'BIND' | 'REBIND') {
  if (unavailable.value) return
  mode.value = modeValue
  state.error = ''
  state.codeError = ''
  state.code = ''
  state.phone = ''
  state.currentPassword = ''
}

function cancel() {
  mode.value = 'VIEW'
  state.error = ''
  state.codeError = ''
}

async function sendCode(purpose: PhonePurpose) {
  if (unavailable.value || state.sending) return
  if (!isValidPhone(state.phone)) {
    state.error = '请输入正确的 11 位手机号'
    return
  }
  state.sending = true
  state.error = ''
  try {
    await accountApi.sendPhoneCode({ phone: state.phone, purpose })
    startCountdown()
    ElMessage.success('验证码已发送，请查看短信')
  } catch (reason) {
    state.error = reason instanceof Error ? reason.message : '验证码发送失败'
  } finally {
    state.sending = false
  }
}

async function submit() {
  if (state.submitting) return
  state.error = ''
  state.codeError = ''
  if (mode.value === 'BIND' && !isValidPhone(state.phone)) {
    state.error = '请输入正确的 11 位手机号'
    return
  }
  if (mode.value === 'REBIND' && !state.currentPassword) {
    state.error = '请输入当前密码'
    return
  }
  if (!isValidCode(state.code)) {
    state.codeError = '请输入 6 位数字验证码'
    return
  }
  state.submitting = true
  try {
    const updated = mode.value === 'BIND'
      ? await accountApi.bindPhone({ phone: state.phone, code: state.code })
      : await accountApi.rebindPhone({
        currentPassword: state.currentPassword,
        phone: state.phone,
        code: state.code,
      })
    emit('saved', updated)
    ElMessage.success(updated.phoneMasked ? `已绑定 ${updated.phoneMasked}` : '手机号已更新')
    cancel()
  } catch (reason) {
    state.code = ''
    const message = reason instanceof Error ? reason.message : '手机号操作失败'
    if (message.includes('验证码')) {
      state.codeError = message
    } else {
      state.error = message
    }
  } finally {
    state.submitting = false
  }
}

async function unbind() {
  if (state.submitting || unavailable.value) return
  if (!state.currentPassword) {
    state.error = '请输入当前密码'
    return
  }
  try {
    await ElMessageBox.confirm('解绑后将无法使用手机号找回或接收通知，确定继续？', '解绑手机号', {
      type: 'warning',
      confirmButtonText: '确定解绑',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  state.submitting = true
  state.error = ''
  try {
    const updated = await accountApi.unbindPhone({ currentPassword: state.currentPassword })
    emit('saved', updated)
    state.currentPassword = ''
    mode.value = 'VIEW'
    ElMessage.success('手机号已解绑')
  } catch (reason) {
    state.error = reason instanceof Error ? reason.message : '解绑失败'
  } finally {
    state.submitting = false
  }
}
</script>

<template>
  <section class="account-section">
    <header class="account-section__header">
      <h2>手机号</h2>
      <p>绑定手机号用于账号安全与重要通知。</p>
    </header>

    <el-alert v-if="unavailable" type="info" show-icon :closable="false"
      title="暂未开通手机号绑定" class="account-section__alert" />

    <template v-else>
      <div v-if="profile.phoneBound" class="phone-state">
        <span class="phone-state__label">已绑定</span>
        <strong class="phone-state__value">{{ profile.phoneMasked }}</strong>
        <div class="phone-state__actions">
          <el-button size="small" @click="open('REBIND')">换绑</el-button>
        </div>
      </div>
      <div v-else class="phone-state">
        <span class="phone-state__label">未绑定</span>
        <el-button size="small" type="primary" @click="open('BIND')">绑定手机号</el-button>
      </div>

      <div v-if="mode !== 'VIEW'" class="phone-form">
        <el-form label-position="top" @submit.prevent>
          <el-form-item v-if="mode === 'REBIND'" label="当前密码">
            <el-input v-model="state.currentPassword" type="password" show-password autocomplete="current-password" />
          </el-form-item>
          <el-form-item label="手机号">
            <el-input v-model="state.phone" maxlength="11" inputmode="numeric" placeholder="请输入 11 位手机号" />
          </el-form-item>
          <el-form-item label="验证码" :error="state.codeError">
            <div class="phone-form__code">
              <el-input v-model="state.code" maxlength="6" inputmode="numeric" placeholder="6 位数字验证码" />
              <el-button :loading="state.sending" :disabled="state.countdown > 0" @click="sendCode(mode === 'BIND' ? 'BIND' : 'REBIND')">
                {{ state.countdown > 0 ? countdownText(state.countdown) : '获取验证码' }}
              </el-button>
            </div>
          </el-form-item>
          <el-alert v-if="state.error" type="error" :title="state.error" show-icon :closable="false" />
          <div class="phone-form__actions">
            <el-button type="primary" :loading="state.submitting" @click="submit">
              {{ mode === 'BIND' ? '绑定' : '确认换绑' }}
            </el-button>
            <el-button @click="cancel">取消</el-button>
          </div>
        </el-form>
      </div>

      <div v-if="mode === 'VIEW' && profile.phoneBound" class="phone-unbind">
        <el-form label-position="top" @submit.prevent>
          <el-form-item label="解绑需验证当前密码">
            <el-input v-model="state.currentPassword" type="password" show-password autocomplete="current-password" />
          </el-form-item>
          <el-button :loading="state.submitting" @click="unbind">解绑手机号</el-button>
        </el-form>
        <el-alert v-if="state.error" type="error" :title="state.error" show-icon :closable="false" />
      </div>
    </template>
  </section>
</template>

<style scoped lang="scss">
.account-section {
  padding: 20px 24px;
  border-radius: 12px;
  background: var(--ai-bg-card, #fff);
  border: 1px solid var(--ai-border-light, #e2edf9);
}
.account-section__header h2 { margin: 0; font-size: 16px; color: $ai-text-primary; }
.account-section__header p { margin: 6px 0 16px; font-size: 13px; color: $ai-text-secondary; }
.account-section__alert { margin-bottom: 12px; }
.phone-state { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.phone-state__label { font-size: 13px; color: $ai-text-secondary; }
.phone-state__value { color: $ai-text-primary; }
.phone-state__actions { margin-left: auto; }
.phone-form { margin-top: 16px; }
.phone-form__code { display: flex; gap: 8px; flex-wrap: wrap; width: 100%; }
.phone-form__code .el-input { flex: 1 1 160px; min-width: 0; }
.phone-form__actions { display: flex; gap: 8px; margin-top: 12px; }
.phone-unbind { margin-top: 16px; padding-top: 16px; border-top: 1px dashed var(--ai-border-light, #e2edf9); }
</style>