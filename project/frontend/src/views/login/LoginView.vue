<template>
  <AuthShell title="登录" subtitle="欢迎使用 AI-JOB，开启你的智能求职之旅。">
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
      <el-form-item label="邮箱" prop="email">
        <el-input v-model="form.email" :prefix-icon="Message" placeholder="请输入邮箱地址" autocomplete="username" />
      </el-form-item>
      <el-form-item label="密码" prop="password">
        <el-input v-model="form.password" :prefix-icon="Lock" type="password" show-password placeholder="请输入密码" autocomplete="current-password" @keyup.enter="submit" />
      </el-form-item>
      <div class="form-row">
        <el-checkbox v-model="rememberMe">记住我</el-checkbox>
        <router-link class="link" to="/forgot-password">忘记密码?</router-link>
      </div>
      <el-button class="submit-button" type="primary" :loading="submitting" native-type="submit">登录</el-button>
      <div class="form-divider"><span>还没有账号?</span></div>
      <router-link class="ghost-button" to="/register">注册账号</router-link>
    </el-form>
  </AuthShell>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Lock, Message } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import AuthShell from '@/components/AuthShell.vue'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/api/http'

const REMEMBER_KEY = 'ai-job:remember-email'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const rememberedEmail = localStorage.getItem(REMEMBER_KEY) ?? ''
const rememberMe = ref(true)

const queryEmail = typeof route.query.email === 'string' ? route.query.email : ''
const form = reactive({
  email: queryEmail || rememberedEmail,
  password: ''
})

const rules: FormRules = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
  ],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function submit(): Promise<void> {
  if (!formRef.value) {
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (valid !== true) {
    return
  }
  submitting.value = true
  try {
    await auth.login(form.email, form.password)
    if (rememberMe.value) {
      localStorage.setItem(REMEMBER_KEY, form.email)
    } else {
      localStorage.removeItem(REMEMBER_KEY)
    }
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    await router.push(redirect)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    submitting.value = false
  }
}
</script>