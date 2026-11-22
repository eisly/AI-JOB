<script setup lang="ts">
import ThemeToggle from '@/components/ThemeToggle.vue'
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { Aim, Edit, TrendCharts, School, Message, Lock } from '@element-plus/icons-vue'
import { authApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import { errorMessage } from '@/utils/format'

const features = [
  { icon: Aim, color: 'var(--ai-primary-light, #DBEAFE)', title: '智能匹配岗位', desc: '基于简历与能力画像，精准推荐目标岗位' },
  { icon: Edit, color: 'var(--ai-accent-alt-bg, #EDE9FE)', title: '定向提升能力', desc: '提供针对性的学习与训练建议' },
  { icon: TrendCharts, color: 'var(--ai-success-bg, #D1FAE5)', title: '实战模拟练习', desc: '通过项目实战与模拟面试提升实战能力' },
  { icon: School, color: 'var(--ai-warning-bg, #FEF3C7)', title: '跟踪投递进展', desc: '管理投递记录，跟踪求职全流程' },
]

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const formRef = ref<FormInstance>()
const registerRef = ref<FormInstance>()
const loading = ref(false)
const registering = ref(false)
const registerVisible = ref(false)
const loginError = ref('')
const registerError = ref('')
const form = reactive({ email: '', password: '' })
const registerForm = reactive({ email: '', password: '', nickname: '' })

const rules: FormRules = {
  email: [{ required: true, type: 'email', message: '请输入有效邮箱地址', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

/** L02：会话失效/需要登录的中性提示——expired=1 表示会话中途失效；仅带 redirect 时提示先登录再继续 */
const sessionNotice = computed(() => {
  if (route.query.expired === '1') return '登录已过期，请重新登录后继续'
  if (typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/')) {
    return '请先登录，登录后将返回原页面'
  }
  return ''
})

const registerRules: FormRules = {
  email: [{ required: true, type: 'email', message: '请输入有效邮箱地址', trigger: 'blur' }],
  password: [{ required: true, min: 10, max: 72, message: '密码长度须为 10 到 72 位', trigger: 'blur' }],
  nickname: [{ required: true, min: 1, max: 30, message: '昵称长度须为 1 到 30 位', trigger: 'blur' }],
}

async function handleLogin() {
  if (!formRef.value || !(await formRef.value.validate().catch(() => false))) return
  loading.value = true
  loginError.value = ''
  try {
    await userStore.login(form.email, form.password)
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') ? route.query.redirect : '/dashboard'
    await router.push(redirect)
  } catch (reason) {
    loginError.value = errorMessage(reason)
  } finally {
    loading.value = false
  }
}

async function handleRegister() {
  if (!registerRef.value || !(await registerRef.value.validate().catch(() => false))) return
  registering.value = true
  registerError.value = ''
  try {
    await authApi.register(registerForm)
    registerVisible.value = false
    form.email = registerForm.email
    ElMessage.success('注册成功，请登录')
  } catch (reason) {
    registerError.value = errorMessage(reason)
  } finally {
    registering.value = false
  }
}
</script>

<template>
  <div class="login">
    <ThemeToggle class="auth-theme-toggle" />
    <div class="login__brand">
      <h1 class="login__brand-title">AI-JOB</h1>
      <p class="login__brand-slogan">AI 一站式智能求职平台</p>
      <p class="login__brand-desc">基于你的简历和能力画像，智能匹配岗位、定向提升能力、跟踪投递进展</p>
      <div class="login__features">
        <div v-for="(item, index) in features" :key="index" class="login__feature">
          <div class="login__feature-icon" :style="{ backgroundColor: item.color }"><el-icon :size="22"><component :is="item.icon" /></el-icon></div>
          <div class="login__feature-text"><div class="login__feature-title">{{ item.title }}</div><div class="login__feature-desc">{{ item.desc }}</div></div>
        </div>
      </div>
    </div>
    <div class="login__form-side">
      <div class="login__card">
        <h2 class="login__title">登录</h2>
        <p class="login__subtitle">登录后继续管理简历、训练和求职进度。</p>
        <el-alert v-if="sessionNotice" :title="sessionNotice" type="warning" show-icon :closable="true" class="login__error" />
        <el-alert v-if="loginError" :title="loginError" type="error" show-icon :closable="false" class="login__error" />
        <el-form ref="formRef" :model="form" :rules="rules" size="large" class="login__form" @submit.prevent="handleLogin">
          <el-form-item prop="email"><el-input v-model="form.email" aria-label="邮箱" placeholder="请输入邮箱地址" :prefix-icon="Message" autocomplete="email" /></el-form-item>
          <el-form-item prop="password"><el-input v-model="form.password" aria-label="密码" type="password" placeholder="请输入密码" :prefix-icon="Lock" show-password autocomplete="current-password" @keyup.enter="handleLogin" /></el-form-item>
          <el-button type="primary" native-type="submit" class="login__submit" :loading="loading">登录</el-button>
        </el-form>
        <router-link to="/forgot-password">忘记密码？</router-link>
        <el-divider>还没有账号？</el-divider>
        <el-button class="login__register" plain @click="registerVisible = true">注册账号</el-button>
      </div>
    </div>
    <el-dialog v-model="registerVisible" title="注册账号" width="min(420px, calc(100vw - 32px))" :close-on-click-modal="false">
      <el-alert v-if="registerError" :title="registerError" type="error" show-icon :closable="false" class="login__error" />
      <el-form ref="registerRef" :model="registerForm" :rules="registerRules" label-position="top" @submit.prevent="handleRegister">
        <el-form-item label="昵称" prop="nickname"><el-input v-model="registerForm.nickname" maxlength="30" /></el-form-item>
        <el-form-item label="邮箱" prop="email"><el-input v-model="registerForm.email" autocomplete="email" /></el-form-item>
        <el-form-item label="密码" prop="password"><el-input v-model="registerForm.password" type="password" show-password autocomplete="new-password" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="registerVisible = false">取消</el-button><el-button type="primary" :loading="registering" @click="handleRegister">注册</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.login { display:flex; min-height:100vh; width:100%; }
.login__brand { width:55%; background:linear-gradient(135deg,#{$ai-primary-light} 0%,var(--ai-primary-light, #DBEAFE) 100%); display:flex; flex-direction:column; justify-content:center; padding:48px 8%; }
.login__brand-title { font-size:42px; font-weight:800; color:$ai-primary; margin-bottom:12px; }
.login__brand-slogan { font-size:24px; font-weight:700; color:$ai-text-primary; margin-bottom:8px; }
.login__brand-desc { font-size:15px; color:$ai-text-secondary; margin-bottom:40px; line-height:1.6; }
.login__features { display:flex; flex-direction:column; gap:20px; }
.login__feature { display:flex; align-items:center; gap:16px; }
.login__feature-icon { width:48px; height:48px; border-radius:50%; display:flex; align-items:center; justify-content:center; flex-shrink:0; color:$ai-primary; }
.login__feature-title { font-size:16px; font-weight:600; color:$ai-text-primary; }
.login__feature-desc { font-size:13px; color:$ai-text-secondary; margin-top:2px; }
.login__form-side { width:45%; display:flex; align-items:center; justify-content:center; background-color:$ai-bg-page; padding:24px; }
.login__card { width:400px; max-width:100%; background-color:$ai-bg-card; border-radius:$ai-radius-lg; box-shadow:$ai-shadow-card; padding:40px; }
.login__title { font-size:26px; font-weight:700; color:$ai-text-primary; margin-bottom:8px; }
.login__subtitle { font-size:14px; color:$ai-text-secondary; margin-bottom:28px; }
.login__submit,.login__register { width:100%; }
.login__error { margin-bottom:16px; }
@media(max-width:800px){.login__brand{display:none}.login__form-side{width:100%;min-height:100vh}.login__card{padding:28px}}
</style>
