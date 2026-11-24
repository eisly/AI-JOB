<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { accountApi } from '@/api/account'
import type { AccountProfileVO } from '@/api/types'
import { NICKNAME_MAX_LENGTH, nicknameUnchanged, validateNickname } from '../account-rules'

const props = defineProps<{ nickname: string }>()
const emit = defineEmits<{ saved: [profile: AccountProfileVO] }>()

const value = ref(props.nickname)
const saving = ref(false)
const error = ref('')

watch(() => props.nickname, next => {
  if (!saving.value) value.value = next
})

async function submit() {
  if (saving.value) return
  error.value = ''
  const invalid = validateNickname(value.value)
  if (invalid) {
    error.value = invalid
    return
  }
  if (nicknameUnchanged(value.value, props.nickname)) {
    ElMessage.info('未做修改')
    return
  }
  saving.value = true
  try {
    const updated = await accountApi.updateNickname(value.value.trim())
    value.value = updated.nickname
    ElMessage.success('昵称已更新')
    emit('saved', updated)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '昵称保存失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="account-section">
    <header class="account-section__header">
      <h2>昵称</h2>
      <p>昵称将显示在顶栏与左侧栏，1-30 个字符，可与其他用户重复。</p>
    </header>
    <el-form label-position="top" @submit.prevent>
      <el-form-item label="昵称" :error="error">
        <el-input
          v-model="value"
          :maxlength="NICKNAME_MAX_LENGTH"
          show-word-limit
          placeholder="请输入昵称"
          @keyup.enter="submit"
        />
      </el-form-item>
      <el-button type="primary" :loading="saving" @click="submit">{{ saving ? '保存中…' : '保存昵称' }}</el-button>
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
</style>