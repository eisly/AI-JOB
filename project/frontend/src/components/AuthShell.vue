<template>
  <div class="auth-page">
    <section class="brand-panel">
      <p class="brand-eyebrow">AI-JOB</p>
      <h1>让每一次求职<br />都更有方向</h1>
      <p class="brand-tagline">一站式智能求职平台，从画像到 offer 全程陪伴。</p>
      <ul class="brand-points">
        <li>智能匹配岗位</li>
        <li>定向提升能力</li>
        <li>实战模拟练习</li>
        <li>跟踪投递进展</li>
      </ul>
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
defineProps<{
  title: string
  subtitle: string
}>()
</script>