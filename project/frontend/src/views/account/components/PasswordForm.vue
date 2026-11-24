<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/request'
import { accountApi } from '@/api/account'
import { passwordStrengthHint, validatePassword } from '../account-rules'

const emit = defineEmits<{ changed: [] }>()

const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const saving = ref(false)
const error = ref('')
const lockedSeconds = ref(0)
let lockTimer: ReturnType<typeof setInterval> | null = null

const strengthHint = computed(() => passwordStrengthHint(newPassword.value))
const disabled = computed(() => saving.value || lockedSeconds.value > 0)

function startLock(seconds: number) {
  lockedSeconds.value = Math.max(1, Math.ceil(seconds))
  if (lockTimer) clearInterval(lockTimer)
  lockTimer = setInterval(() => {
    lockedSeconds.value = Math.max(0, lockedSeconds.value - 1)
    if (lockedSeconds.value === 0 && lockTimer) {
      clearInterval(lockTimer)
      lockTimer = null
    }
  }, 1000)
}

async function submit() {
  if (disabled.value) return
  error.value = ''
  const invalid = validatePassword(currentPassword.value, newPassword.value, confirmPassword.value)
  if (invalid) {
    error.value = invalid
    return
  }
  saving.value = true
  try {
    await accountApi.changePassword({
      currentPassword: currentPassword.value,
      newPassword: newPassword.value,
      confirmPassword: confirmPassword.value,
    })
    currentPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    emit('changed')
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 429) {
      startLock(reason.retryAfterSeconds ?? 900)
      ElMessage.error(reason.message)
    } else {
      error.value = reason instanceof Error ? reason.message : '密码修改失败'
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="account-section">
    <header class="account-section__header">
      <h2>登录密码</h2>
      <p>新密码需 10-72 位，修改成功后所有设备需要重新登录。</p>
    </header>
    <el-form label-position="top" @submit.prevent>
      <el-form-item label="当前密码">
        <el-input v-model="currentPassword" type="password" show-password autocomplete="current-password" />
      </el-form-item>
      <el-form-item label="新密码" :error="error">
        <el-input v-model="newPassword" type="password" show-password autocomplete="new-password" />
        <div class="account-section__hint">{{ strengthHint }}</div>
      </el-form-item>
      <el-form-item label="确认新密码">
        <el-input v-model="confirmPassword" type="password" show-password autocomplete="new-password" />
      </el-form-item>
      <div class="account-section__actions">
        <el-button type="primary" :loading="saving" :disabled="disabled" @click="submit">
          {{ saving ? '提交中…' : '修改密码' }}
        </el-button>
        <router-link class="account-section__link" to="/forgot-password">忘记当前密码？</router-link>
      </div>
      <el-alert v-if="lockedSeconds > 0" class="account-section__alert" type="warning" show-icon :closable="false"
        :title="`尝试次数过多，请 ${lockedSeconds} 秒后再试`" />
    </el-form>
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
.account-section__hint { margin-top: 4px; font-size: 12px; color: $ai-text-secondary; }
.account-section__actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.account-section__link { font-size: 13px; color: $ai-primary; }
.account-section__alert { margin-top: 12px; }
</style>