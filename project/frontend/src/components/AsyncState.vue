<script setup lang="ts">
import { computed } from 'vue'
import { ApiError } from '@/api/request'
import LoadingSkeleton from '@/components/LoadingSkeleton.vue'

const props = withDefaults(defineProps<{
  loading?: boolean
  error?: unknown
  empty?: boolean
  loadingText?: string
  retain?: boolean
  skeletonRows?: number
  emptyText?: string
  emptyDescription?: string
}>(), {
  loading: false,

  empty: false,
  loadingText: '正在加载数据',
  emptyText: '暂无数据',
  emptyDescription: '',
})

defineEmits<{ retry: [] }>()

const message = computed(() => {
  if (!props.error) return ''
  if (props.error instanceof Error) return props.error.message
  return String(props.error)
})

const traceId = computed(() => props.error instanceof ApiError ? props.error.traceId : '')
</script>

<template>
  <div class="async-state">
    <template v-if="retain">
      <div v-if="loading" class="async-state__refresh" role="status">{{ loadingText }}…</div>
      <div v-else-if="error" class="async-state__refresh async-state__refresh--error" role="status">
        <span>刷新失败。{{ message }}</span>
        <el-button size="small" @click="$emit('retry')">重新加载</el-button>
      </div>
      <slot />
    </template>
    <LoadingSkeleton v-else-if="loading" :label="loadingText" :rows="skeletonRows" />
    <el-result v-else-if="error" icon="error" title="加载失败" :sub-title="message">
      <template #extra>
        <p v-if="traceId" class="async-state__trace">traceId：{{ traceId }}</p>
        <el-button type="primary" @click="$emit('retry')">重新加载</el-button>
        <slot name="error-extra" />
      </template>
    </el-result>
    <el-empty v-else-if="empty" :description="emptyText">
      <p v-if="emptyDescription" class="async-state__empty-description">{{ emptyDescription }}</p>
      <slot name="empty" />
    </el-empty>
    <slot v-else />
  </div>
</template>

<style scoped lang="scss">
.async-state__refresh { margin: 0 0 8px; color: $ai-text-secondary; font-size: 13px; }
.async-state__refresh--error { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; color: $ai-danger; }
.async-state__trace { margin: 0 0 14px; color: $ai-text-tertiary; font-size: 12px; word-break: break-all; }
.async-state__empty-description { margin: -12px 0 16px; color: $ai-text-secondary; }
</style>
