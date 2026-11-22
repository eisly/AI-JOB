<script setup lang="ts">
import { CircleCheck, Clock, Warning, CircleClose, InfoFilled } from '@element-plus/icons-vue'

withDefaults(defineProps<{
  title: string
  description?: string
  state?: 'loading' | 'queued' | 'success' | 'warning' | 'error' | 'idle'
  compact?: boolean
}>(), { state: 'loading' })

const icons = { queued: Clock, success: CircleCheck, warning: Warning, error: CircleClose, idle: InfoFilled }
</script>

<template>
  <div class="wait-status" :class="{ 'wait-status--compact': compact }" :data-state="state">
    <span class="wait-status__icon" aria-hidden="true">
      <span v-if="state === 'loading'" class="wait-status__spinner" />
      <el-icon v-else><component :is="icons[state]" /></el-icon>
    </span>
    <div class="wait-status__text" role="status">
      <strong>{{ title }}</strong>
      <p v-if="description">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="wait-status__actions"><slot name="actions" /></div>
  </div>
</template>

<style scoped lang="scss">
.wait-status {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  min-height: 58px;
  padding: 10px 12px;
  background: $ai-bg-page;
  border: 1px solid $ai-border;
  border-radius: $ai-radius;
}
.wait-status--compact { min-height: 44px; padding: 7px 9px; }
.wait-status__icon {
  display: grid;
  place-items: center;
  flex: 0 0 32px;
  height: 32px;
  border-radius: 8px;
  background: $ai-primary-light;
  color: $ai-primary;
  font-size: 18px;
}
.wait-status[data-state="success"] .wait-status__icon { color: $ai-success; background: var(--ai-success-bg, #ecfdf5); }
.wait-status[data-state="warning"] .wait-status__icon { color: $ai-warning; background: var(--ai-warning-bg, #fffbeb); }
.wait-status[data-state="error"] .wait-status__icon { color: $ai-danger; background: var(--ai-danger-bg, #fef2f2); }
.wait-status__text { flex: 1 1 145px; min-width: 0; color: $ai-text-primary; font-size: 14px; overflow-wrap: anywhere; }
.wait-status__text p { margin: 3px 0 0; color: $ai-text-secondary; font-size: 13px; line-height: 1.5; }
.wait-status__actions { display: flex; flex-wrap: wrap; gap: 6px; }
.wait-status__actions :deep(.el-button + .el-button) { margin-left: 0; }
.wait-status__actions:empty { display: none; }
.wait-status__spinner {
  width: 17px;
  height: 17px;
  border: 2px solid color-mix(in srgb, currentColor 26%, transparent);
  border-top-color: currentColor;
  border-radius: 50%;
  animation: wait-spin .85s linear infinite;
}
@keyframes wait-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .wait-status__spinner { animation: none; } }
</style>
