<template>
  <AuthShell title="创建账号" subtitle="加入 AI-JOB，开启你的智能求职之旅。">
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
      <el-form-item label="昵称" prop="nickname">
        <el-input v-model="form.nickname" :prefix-icon="User" placeholder="请输入你的昵称（如：张同学）" maxlength="30" />
      </el-form-item>
      <el-form-item label="邮箱" prop="email">
        <el-input v-model="form.email" :prefix-icon="Message" placeholder="请输入邮箱地址" autocomplete="username" />
      </el-form-item>
      <el-form-item label="密码" prop="password">
        <el-input v-model="form.password" :prefix-icon="Lock" type="password" show-password placeholder="请设置密码（10-72 位字符）" autocomplete="new-password" />
      </el-form-item>
      <p class="field-hint">10-72 位字符，建议包含字母和数字。</p>
      <el-form-item label="确认密码" prop="confirmPassword">
        <el-input v-model="form.confirmPassword" :prefix-icon="Lock" type="password" show-password placeholder="请再次输入密码" autocomplete="new-password" @keyup.enter="submit" />
      </el-form-item>
      <el-button class="submit-button" type="primary" :loading="submitting" native-type="submit">注册账号</el-button>
      <div class="form-divider">
        <span>已有账号? <router-link class="link" to="/login">去登录</router-link></span>
      </div>
    </el-form>
  </AuthShell>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Lock, Message, User } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import AuthShell from '@/components/AuthShell.vue'
import { authApi } from '@/api/auth-legacy'
import { errorMessage } from '@/api/http'

const router = useRouter()
const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({
  nickname: '',
  email: '',
  password: '',
  confirmPassword: ''
})

const rules: FormRules = {
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { min: 1, max: 30, message: '昵称长度需为 1-30 个字符', trigger: 'blur' }
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 10, max: 72, message: '密码长度需为 10-72 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== form.password) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
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
    await authApi.register({
      email: form.email,
      password: form.password,
      nickname: form.nickname
    })
    ElMessage.success('注册成功，请登录')
    await router.push({ path: '/login', query: { email: form.email } })
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    submitting.value = false
  }
}
</script>