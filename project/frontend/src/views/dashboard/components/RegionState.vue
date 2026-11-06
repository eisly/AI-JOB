<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import type { ApiError, NavigationTarget, RegionStatus } from '@/types/workbench'
import { resolveRoute } from '@/features/workbench/model/navigation'

const props = defineProps<{
  status: RegionStatus
  error?: ApiError
  retryCount?: number
  emptyText?: string
  emptyHint?: string
  emptyAction?: NavigationTarget
  loadingText?: string
}>()

const emit = defineEmits<{ retry: [] }>()
const router = useRouter()

const isLoading = computed(() => props.status === 'IDLE' || props.status === 'LOADING')

const errorText = computed(() => {
  if ((props.retryCount ?? 0) >= 3) {
    return '仍无法加载，请稍后重试或前往对应模块查看'
  }
  switch (props.error?.category) {
    case 'TIMEOUT':
      return '加载超时，点击重试'
    case 'RATE_LIMIT':
      return '请求过于频繁，请稍后重试'
    case 'NETWORK':
      return '网络连接异常，请检查网络'
    case 'SERVER':
      return '服务暂时不可用，点击重试'
    case 'AUTH_EXPIRED':
      return '登录已过期，请重新登录'
    case 'CANCELLED':
      return '加载已取消，点击重试'
    default:
      return '加载失败，点击重试'
  }
})

const reference = computed(() => props.error?.traceId ?? props.error?.requestId ?? '')
const actionTarget = computed(() => resolveRoute(props.emptyAction, (name) => router.hasRoute(name)))

function go(target: NavigationTarget): void {
  void router.push({ name: target.name, query: target.query })
}
</script>

<template>
  <div class="region-state">
    <div v-if="isLoading" class="region-loading" role="status">
      <span class="region-loading__line region-loading__line--wide" />
      <span class="region-loading__line" />
      <span class="region-loading__line region-loading__line--short" />
      <p class="region-loading__text">{{ loadingText || '正在加载…' }}</p>
    </div>

    <div v-else-if="status === 'ERROR'" class="region-error" role="alert">
      <p>{{ errorText }}</p>
      <button type="button" class="action-button" @click="emit('retry')">重试</button>
      <p v-if="reference" class="region-error__reference">排障编号：{{ reference }}</p>
    </div>

    <div v-else-if="status === 'EMPTY'" class="region-empty">
      <p class="region-empty__text">{{ emptyText || '暂无内容' }}</p>
      <p v-if="emptyHint" class="region-empty__hint">{{ emptyHint }}</p>
      <button v-if="actionTarget" type="button" class="action-button" @click="go(actionTarget)">
        {{ actionTarget.label || '前往查看' }}
      </button>
    </div>

    <template v-else>
      <div v-if="status === 'REFRESHING'" class="region-refresh-line" aria-hidden="true" />
      <slot />
    </template>
  </div>
</template>

<style scoped lang="scss">
.region-state { min-height: 105px; }
.region-loading { display: flex; flex-direction: column; gap: 10px; padding: 18px 4px; }
.region-loading__line { display: block; height: 14px; width: 72%; border-radius: 6px; background: linear-gradient(90deg, #eef4fb 25%, #f7fbff 37%, #eef4fb 63%); background-size: 400% 100%; animation: region-skeleton 1.4s ease infinite; }
.region-loading__line--wide { width: 92%; height: 18px; }
.region-loading__line--short { width: 46%; }
.region-loading__text { color: #8495b1; font-size: 13px; }
@keyframes region-skeleton { 0% { background-position: 100% 50%; } 100% { background-position: 0 50%; } }
.region-error { display: flex; flex-direction: column; align-items: flex-start; gap: 10px; color: #7184a6; padding: 10px 4px; }
.region-error__reference { color: #9aa9c2; font-size: 12px; }
.region-empty { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; padding: 10px 4px; color: #7184a6; }
.region-empty__text { color: #33456f; font-weight: 600; }
.region-empty__hint { line-height: 1.5; }
.region-refresh-line { height: 2px; margin-bottom: 10px; border-radius: 2px; background: linear-gradient(90deg, transparent, #0c5cf5, transparent); background-size: 50% 100%; animation: region-refresh 1.1s linear infinite; }
@keyframes region-refresh { 0% { background-position: -50% 0; } 100% { background-position: 150% 0; } }
.action-button { border: 0; cursor: pointer; padding: 9px 16px; border-radius: 7px; background: #0c5cf5; color: #fff; font-weight: 600; }
.action-button:hover { background: #084ed5; }
</style>