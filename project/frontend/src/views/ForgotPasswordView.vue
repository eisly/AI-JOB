<template>
  <AuthShell title="找回密码" subtitle="输入注册邮箱，我们会发送 6 位验证码。">
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
      <el-form-item label="邮箱" prop="email">
        <el-input v-model="form.email" placeholder="请输入注册邮箱" autocomplete="username" />
      </el-form-item>
      <el-button class="submit-button" type="primary" :loading="submitting" native-type="submit">发送验证码</el-button>
      <p class="field-hint">
        开发环境可在 Mailpit（http://localhost:8025）查看邮件。
      </p>
      <p class="form-footer">
        想起密码了?
        <router-link class="link" to="/login">去登录</router-link>
      </p>
    </el-form>
  </AuthShell>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import AuthShell from '@/components/AuthShell.vue'
import { authApi } from '@/api/auth'
import { errorMessage } from '@/api/http'

const router = useRouter()
const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({ email: '' })

const rules: FormRules = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
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
    await authApi.forgotPassword(form.email)
    ElMessage.success('若该邮箱已注册，验证码已发送，请查收')
    await router.push({ path: '/reset-password', query: { email: form.email } })
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    submitting.value = false
  }
}
</script>