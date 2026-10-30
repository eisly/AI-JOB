<script setup lang="ts">
import { reactive, ref } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { Aim, Edit, TrendCharts, School, Message, Lock } from '@element-plus/icons-vue'

const features = [
  { icon: Aim, color: '#DBEAFE', title: '智能匹配岗位', desc: '基于简历与能力画像，精准推荐目标岗位' },
  { icon: Edit, color: '#EDE9FE', title: '定向提升能力', desc: '提供针对性的学习与训练建议' },
  { icon: TrendCharts, color: '#D1FAE5', title: '实战模拟练习', desc: '通过项目实战与模拟面试提升实战能力' },
  { icon: School, color: '#FEF3C7', title: '跟踪投递进展', desc: '管理投递记录，跟踪求职全流程' },
]

const formRef = ref<FormInstance>()
const form = reactive({
  email: '',
  password: '',
  remember: false,
})

const rules: FormRules = {
  email: [{ required: true, message: '请输入邮箱地址', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const handleLogin = () => {
  formRef.value?.validate((valid) => {
    if (valid) {
      // TODO: 预留登录接口对接
    }
  })
}
</script>

<template>
  <div class="login">
    <div class="login__brand">
      <h1 class="login__brand-title">AI-JOB</h1>
      <p class="login__brand-slogan">AI 一站式智能求职平台</p>
      <p class="login__brand-desc">基于你的简历和能力画像，智能匹配岗位、定向提升能力、跟踪投递进展</p>
      <div class="login__features">
        <div v-for="(f, i) in features" :key="i" class="login__feature">
          <div class="login__feature-icon" :style="{ backgroundColor: f.color }">
            <el-icon :size="22"><component :is="f.icon" /></el-icon>
          </div>
          <div class="login__feature-text">
            <div class="login__feature-title">{{ f.title }}</div>
            <div class="login__feature-desc">{{ f.desc }}</div>
          </div>
        </div>
      </div>
    </div>

    <div class="login__form-side">
      <div class="login__card">
        <el-tag type="danger" effect="light" size="small" class="login__demo-tag">演示数据</el-tag>
        <h2 class="login__title">登录</h2>
        <p class="login__subtitle">欢迎使用 AI-JOB，开启你的智能求职之旅。</p>

        <el-form ref="formRef" :model="form" :rules="rules" size="large" class="login__form">
          <el-form-item prop="email">
            <el-input v-model="form.email" placeholder="请输入邮箱地址" :prefix-icon="Message" />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              :prefix-icon="Lock"
              show-password
            />
          </el-form-item>
          <div class="login__options">
            <el-checkbox v-model="form.remember">记住我</el-checkbox>
            <el-link type="primary" :underline="false">忘记密码？</el-link>
          </div>
          <el-button type="primary" class="login__submit" @click="handleLogin">登录</el-button>
        </el-form>

        <el-divider>还没有账号？</el-divider>
        <el-button class="login__register" plain>注册账号</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.login {
  display: flex;
  height: 100vh;
  width: 100vw;

  &__brand {
    width: 55%;
    background: linear-gradient(135deg, #{$ai-primary-light} 0%, #DBEAFE 100%);
    display: flex;
    flex-direction: column;
    justify-content: center;
    padding: 0 8%;
  }

  &__brand-title {
    font-size: 42px;
    font-weight: 800;
    color: $ai-primary;
    margin-bottom: 12px;
  }

  &__brand-slogan {
    font-size: 24px;
    font-weight: 700;
    color: $ai-text-primary;
    margin-bottom: 8px;
  }

  &__brand-desc {
    font-size: 15px;
    color: $ai-text-secondary;
    margin-bottom: 40px;
    line-height: 1.6;
  }

  &__features {
    display: flex;
    flex-direction: column;
    gap: 20px;
  }

  &__feature {
    display: flex;
    align-items: center;
    gap: 16px;
  }

  &__feature-icon {
    width: 48px;
    height: 48px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    color: $ai-primary;
  }

  &__feature-title {
    font-size: 16px;
    font-weight: 600;
    color: $ai-text-primary;
  }

  &__feature-desc {
    font-size: 13px;
    color: $ai-text-secondary;
    margin-top: 2px;
  }

  &__form-side {
    width: 45%;
    display: flex;
    align-items: center;
    justify-content: center;
    background-color: $ai-bg-page;
  }

  &__card {
    width: 400px;
    background-color: $ai-bg-card;
    border-radius: $ai-radius-lg;
    box-shadow: $ai-shadow-card;
    padding: 40px;
    position: relative;
  }

  &__demo-tag {
    position: absolute;
    top: 24px;
    right: 24px;
  }

  &__title {
    font-size: 26px;
    font-weight: 700;
    color: $ai-text-primary;
    margin-bottom: 8px;
  }

  &__subtitle {
    font-size: 14px;
    color: $ai-text-secondary;
    margin-bottom: 28px;
  }

  &__options {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 20px;
  }

  &__submit {
    width: 100%;
  }

  &__register {
    width: 100%;
  }
}
</style>