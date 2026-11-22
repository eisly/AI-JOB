<script setup lang="ts">
withDefaults(defineProps<{ label?: string; rows?: number }>(), { label: '正在加载数据', rows: 3 })
</script>

<template>
  <div class="loading-skeleton" role="status" :aria-label="label">
    <div v-for="row in rows" :key="row" class="loading-skeleton__line" aria-hidden="true" />
  </div>
</template>

<style scoped lang="scss">
.loading-skeleton { display: grid; gap: 12px; padding: 16px 4px; }
.loading-skeleton__line {
  width: 83%;
  height: 12px;
  border-radius: 6px;
  background: linear-gradient(100deg, $ai-bg-light 35%, var(--ai-primary-light, #eff6ff) 50%, $ai-bg-light 65%);
  background-size: 220% 100%;
  animation: skeleton-wave 1.5s ease-in-out infinite;
}
.loading-skeleton__line:nth-child(3n + 1) { width: 100%; }
.loading-skeleton__line:nth-child(3n) { width: 58%; }
@keyframes skeleton-wave { from { background-position: 100% 0; } to { background-position: 0 0; } }
@media (prefers-reduced-motion: reduce) { .loading-skeleton__line { animation: none; } }
</style>
