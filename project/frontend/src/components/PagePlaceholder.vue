<script setup lang="ts">
import type { Component } from 'vue'
import { InfoFilled } from '@element-plus/icons-vue'

defineProps<{
  title: string
  icon?: Component
}>()
</script>

<template>
  <div class="page-placeholder">
    <div class="page-placeholder__card">
      <el-icon class="page-placeholder__icon" :size="40">
        <component :is="icon || InfoFilled" />
      </el-icon>
      <h2 class="page-placeholder__title">{{ title }}</h2>
      <p class="page-placeholder__tip">功能建设中，敬请期待</p>
    </div>
  </div>
</template>

<style scoped lang="scss">
.page-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: calc(100vh - #{$header-height} - 48px);

  &__card {
    background-color: $ai-bg-card;
    border-radius: $ai-radius-lg;
    box-shadow: $ai-shadow-card;
    padding: 60px 80px;
    text-align: center;
  }

  &__icon {
    color: $ai-primary;
    margin-bottom: 20px;
  }

  &__title {
    font-size: 22px;
    font-weight: 700;
    color: $ai-text-primary;
    margin-bottom: 8px;
  }

  &__tip {
    font-size: 14px;
    color: $ai-text-secondary;
  }
}
</style>