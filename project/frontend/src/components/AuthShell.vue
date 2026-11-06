<template>
  <div class="auth-page">
    <div class="auth-wave auth-wave--top" aria-hidden="true"></div>
    <div class="auth-wave auth-wave--bottom" aria-hidden="true"></div>
    <section class="brand-panel">
      <p class="brand-eyebrow">AI-JOB</p>
      <h1 class="brand-title">AI-JOB</h1>
      <p class="brand-slogan">AI 一站式智能求职平台</p>
      <p class="brand-desc">基于你的简历和能力画像，智能匹配合适的岗位，并提供针对性的训练建议，助你更高效地拿到理想 Offer。</p>
      <div class="brand-features">
        <div v-for="feature in features" :key="feature.title" class="brand-feature">
          <div class="brand-feature-icon" :style="{ backgroundColor: feature.color }">
            <el-icon :size="22"><component :is="feature.icon" /></el-icon>
          </div>
          <div>
            <div class="brand-feature-title">{{ feature.title }}</div>
            <div class="brand-feature-desc">{{ feature.desc }}</div>
          </div>
        </div>
      </div>
    </section>
    <section class="form-panel">
      <div class="form-card">

        <h2>{{ title }}</h2>
        <p class="form-subtitle">{{ subtitle }}</p>
        <slot />
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { Aim, Edit, School, TrendCharts } from '@element-plus/icons-vue'

defineProps<{
  title: string
  subtitle: string
}>()

const features = [
  { icon: Aim, color: '#DBEAFE', title: '智能匹配岗位', desc: '基于简历与能力画像，精准推荐目标岗位' },
  { icon: Edit, color: '#E0E7FF', title: '定向提升能力', desc: '提供针对性的学习与训练建议' },
  { icon: TrendCharts, color: '#D1FAE5', title: '实战模拟练习', desc: '通过项目实战与模拟面试提升实战能力' },
  { icon: School, color: '#FFEDD5', title: '跟踪投递进展', desc: '管理投递记录，跟踪求职全流程' }
]
</script>

<style scoped lang="scss">
.auth-page {
  position: relative;
  display: flex;
  min-height: 100vh;
  width: 100vw;
  overflow: hidden;
  background: linear-gradient(135deg, #{$ai-primary-light} 0%, #DBEAFE 100%);
}

.auth-wave {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}

.auth-wave--top {
  top: -180px;
  right: -150px;
  width: 460px;
  height: 460px;
  background: rgba(255, 255, 255, 0.5);
}

.auth-wave--bottom {
  bottom: -220px;
  left: -160px;
  width: 520px;
  height: 520px;
  background: rgba(191, 219, 254, 0.38);
}

.brand-panel {
  position: relative;
  z-index: 1;
  flex: 0 0 55%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 0 8%;
}

.brand-eyebrow {
  margin: 0;
  font-size: 13px;
  letter-spacing: 0.32em;
  text-transform: uppercase;
  color: $ai-primary;
}

.brand-title {
  margin: 12px 0;
  font-size: 42px;
  font-weight: 800;
  color: $ai-primary;
}

.brand-slogan {
  margin: 0 0 8px;
  font-size: 24px;
  font-weight: 700;
  color: $ai-text-primary;
}

.brand-desc {
  margin: 0 0 40px;
  max-width: 460px;
  font-size: 15px;
  line-height: 1.6;
  color: $ai-text-secondary;
}

.brand-features {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.brand-features::before {
  content: '';
  position: absolute;
  top: 24px;
  bottom: 24px;
  left: 23px;
  border-left: 1px dashed rgba(37, 99, 235, 0.28);
}

.brand-feature {
  display: flex;
  align-items: center;
  gap: 16px;
}

.brand-feature-icon {
  position: relative;
  z-index: 1;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: $ai-primary;
}

.brand-feature-title {
  font-size: 16px;
  font-weight: 600;
  color: $ai-text-primary;
}

.brand-feature-desc {
  margin-top: 2px;
  font-size: 13px;
  color: $ai-text-secondary;
}

.form-panel {
  position: relative;
  z-index: 1;
  flex: 0 0 45%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
}

.form-card {
  width: 100%;
  max-width: 400px;
  padding: 32px;
  background-color: $ai-bg-card;
  border-radius: $ai-radius-lg;
  box-shadow: $ai-shadow-card;
}

.form-card h2 {
  margin: 0 0 8px;
  font-size: 26px;
  font-weight: 700;
  color: $ai-text-primary;
}

.form-subtitle {
  margin: 0 0 20px;
  font-size: 14px;
  line-height: 1.6;
  color: $ai-text-secondary;
}

@media (max-width: 960px) {
  .brand-panel {
    display: none;
  }

  .form-panel {
    flex: 1 1 100%;
  }

  .form-card {
    padding: 32px 24px;
  }
}
</style>