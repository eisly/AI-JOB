<template>
  <div class="home-page">
    <header class="home-header">
      <div>
        <p class="brand-eyebrow">AI-JOB</p>
        <h1>你好，{{ auth.user?.nickname }}</h1>
        <p class="home-subtitle">登录链路已打通，后续功能将在这里展开。</p>
      </div>
      <el-button @click="signOut">退出登录</el-button>
    </header>

    <main class="home-grid">
      <section class="home-card">
        <h2>当前账号</h2>
        <dl class="home-facts">
          <dt>用户 ID</dt>
          <dd>{{ auth.user?.id }}</dd>
          <dt>邮箱</dt>
          <dd>{{ auth.user?.email }}</dd>
          <dt>角色</dt>
          <dd>{{ auth.user?.role }}</dd>
          <dt>注册时间</dt>
          <dd>{{ createdAt }}</dd>
        </dl>
      </section>

      <section class="home-card">
        <h2>修改昵称</h2>
        <el-form label-position="top" @submit.prevent="saveNickname">
          <el-form-item label="新昵称">
            <el-input v-model="nickname" maxlength="30" placeholder="1-30 个字符" />
          </el-form-item>
          <el-button type="primary" :loading="saving" native-type="submit">保存</el-button>
        </el-form>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { authApi } from '@/api/auth-legacy'
import { errorMessage } from '@/api/http'

const router = useRouter()
const auth = useAuthStore()
const nickname = ref(auth.user?.nickname ?? '')
const saving = ref(false)

watch(
  () => auth.user?.nickname,
  (value) => {
    nickname.value = value ?? ''
  }
)

const createdAt = computed(() => {
  const value = auth.user?.createdAt
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })
})

async function saveNickname(): Promise<void> {
  if (!nickname.value.trim()) {
    ElMessage.warning('昵称不能为空')
    return
  }
  saving.value = true
  try {
    const response = await authApi.updateNickname(nickname.value.trim())
    auth.user = response.data.data
    ElMessage.success('昵称已更新')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function signOut(): Promise<void> {
  await auth.logout()
  ElMessage.success('已退出登录')
  await router.push('/login')
}
</script>