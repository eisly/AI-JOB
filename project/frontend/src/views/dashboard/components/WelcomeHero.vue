<script setup lang="ts">
import { computed } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import type { ApiError } from '@/types/workbench'
import { buildGreeting } from '@/features/workbench/model/greeting'
import { formatRefreshedAt } from '@/features/workbench/model/timeFormat'

const props = defineProps<{
  nickname?: string
  description: string
  refreshedAt?: number
  refreshing: boolean
  hasProfile: boolean
  identityError?: ApiError
}>()

const emit = defineEmits<{ refresh: [] }>()

const greeting = computed(() => buildGreeting(props.nickname))
const refreshedText = computed(() => formatRefreshedAt(props.refreshedAt))
</script>

<template>
  <div class="welcome-hero">
    <p class="welcome-hero__greeting">你好，{{ greeting }}，欢迎回来！</p>
    <div class="welcome-hero__title-row">
      <h1>今天，继续你的求职准备</h1>
      <button
        type="button"
        class="refresh-button"
        :disabled="refreshing || !!identityError"
        @click="emit('refresh')"
      >
        <el-icon><Refresh /></el-icon>
        <span>{{ refreshing ? '刷新中' : '刷新' }}</span>
        <small v-if="refreshedText">更新于 {{ refreshedText }}</small>
      </button>
    </div>
    <p class="welcome-hero__description">{{ description }}</p>
    <div v-if="identityError" class="identity-error" role="alert">
      <span>暂时无法确认登录状态，请稍后重试。</span>
      <button type="button" class="identity-error__retry" @click="emit('refresh')">重试</button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.welcome-hero { padding: 0 14px 4px; color: var(--ai-text-secondary, #61749b); font-size: 16px; }
.welcome-hero__greeting { margin: 0; }
.welcome-hero__title-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.welcome-hero h1 { color: var(--ai-text-primary, #09143e); font-size: 30px; line-height: 1.3; margin: 8px 0; font-weight: 750; }
.welcome-hero__description { font-size: 17px; margin: 0; }
.refresh-button { display: inline-flex; align-items: center; gap: 6px; margin-top: 10px; padding: 8px 12px; border: 1px solid var(--ai-accent-border, #d8e5f6); border-radius: 8px; background: var(--ai-bg-card, #fff); color: var(--ai-text-secondary, #33456f); cursor: pointer; white-space: nowrap; }
.refresh-button small { color: var(--ai-text-tertiary, #8495b1); font-size: 12px; }
.refresh-button:disabled { cursor: not-allowed; opacity: 0.7; }
.identity-error { display: flex; align-items: center; gap: 10px; margin-top: 12px; padding: 10px 14px; border: 1px solid var(--ai-danger-border, #ffd7d5); border-radius: 8px; background: var(--ai-danger-bg, #fff4f3); color: var(--ai-danger, #b43c35); font-size: 14px; }
.identity-error__retry { border: 0; background: transparent; color: var(--ai-primary, #0c5cf5); font-weight: 600; cursor: pointer; }
@media (max-width: 700px) {
  .welcome-hero { padding-left: 0; }
  .welcome-hero__title-row { flex-direction: column; }
  .welcome-hero h1 { font-size: 24px; }
}
</style>