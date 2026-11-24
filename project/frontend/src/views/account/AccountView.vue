<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { accountApi } from '@/api/account'
import { ApiError } from '@/api/request'
import type { AccountCapabilitiesVO, AccountProfileVO } from '@/api/types'
import { useUserStore } from '@/stores/user'
import LoadingSkeleton from '@/components/LoadingSkeleton.vue'
import AccountSummaryCard from './components/AccountSummaryCard.vue'
import AvatarUploader from './components/AvatarUploader.vue'
import NicknameForm from './components/NicknameForm.vue'
import PasswordForm from './components/PasswordForm.vue'
import PhoneBindingSection from './components/PhoneBindingSection.vue'

const router = useRouter()
const userStore = useUserStore()

const profile = ref<AccountProfileVO | null>(null)
const capabilities = ref<AccountCapabilitiesVO | null>(null)
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  const results = await Promise.allSettled([
    accountApi.getProfile(),
    accountApi.getCapabilities(),
    userStore.ensureAccountCsrf(),
  ])
  const [profileResult, capabilitiesResult] = results

  if (profileResult.status === 'fulfilled') {
    profile.value = profileResult.value
    userStore.applyProfile(profileResult.value)
    void userStore.loadAvatar(profileResult.value.avatarVersion)
  } else {
    const reason = profileResult.reason
    if (reason instanceof ApiError && reason.status === 401) {
      await userStore.logout().catch(() => userStore.clearSession())
      await router.replace('/login?expired=1')
      return
    }
    error.value = reason instanceof Error ? reason.message : '账号资料加载失败'
  }

  if (capabilitiesResult.status === 'fulfilled') {
    capabilities.value = capabilitiesResult.value
  }

  loading.value = false
}

function onSaved(next: AccountProfileVO) {
  profile.value = next
  userStore.applyProfile(next)
  void userStore.loadAvatar(next.avatarVersion)
}

async function onPasswordChanged() {
  ElMessage.success('密码已修改，请使用新密码重新登录')
  userStore.clearSession()
  await router.replace('/login')
}

onMounted(load)
</script>

<template>
  <div class="account-page">
    <header class="account-page__header">
      <h1>账号设置</h1>
      <p>管理你的昵称、登录密码、手机号与头像，修改后会在全站同步生效。</p>
    </header>

    <el-alert v-if="error" class="account-page__alert" type="error" :title="error" show-icon :closable="false">
      <template #default>
        <el-button link type="primary" @click="load">重新加载</el-button>
      </template>
    </el-alert>

    <LoadingSkeleton v-if="loading && !profile" :rows="5" label="正在加载账号资料" />

    <div v-else-if="profile && capabilities" class="account-page__grid">
      <aside class="account-page__side">
        <AccountSummaryCard :profile="profile" />
        <AvatarUploader :profile="profile" @saved="onSaved" />
      </aside>
      <div class="account-page__main">
        <NicknameForm :nickname="profile.nickname" @saved="onSaved" />
        <PasswordForm @changed="onPasswordChanged" />
        <PhoneBindingSection :profile="profile" :capabilities="capabilities" @saved="onSaved" />
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.account-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-width: 0;
}
.account-page__header h1 { margin: 0; font-size: 24px; color: $ai-text-primary; }
.account-page__header p { margin: 8px 0 0; font-size: 13px; color: $ai-text-secondary; }
.account-page__alert { margin: 0; }
.account-page__grid {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 18px;
  min-width: 0;
}
.account-page__side, .account-page__main {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-width: 0;
}
@media (max-width: 1279px) {
  .account-page__grid { grid-template-columns: 280px minmax(0, 1fr); }
}
@media (max-width: 1049px) {
  .account-page__grid { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 700px) {
  .account-page { gap: 12px; padding: 0 4px; }
  .account-page__grid, .account-page__side, .account-page__main { gap: 12px; }
  .account-page__header h1 { font-size: 20px; overflow-wrap: anywhere; }
}
</style>
