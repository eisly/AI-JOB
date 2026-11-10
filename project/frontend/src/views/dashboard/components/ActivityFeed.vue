<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { Clock, ArrowRight } from '@element-plus/icons-vue'
import type { ActivityItem, ApiError, NavigationTarget, RegionStatus } from '@/types/workbench'
import { resolveRoute } from '@/features/workbench/model/navigation'
import { formatActivityTime } from '@/features/workbench/model/timeFormat'
import RegionState from './RegionState.vue'

const props = defineProps<{
  items?: ActivityItem[]
  status: RegionStatus
  error?: ApiError
}>()

const emit = defineEmits<{ retry: [] }>()
const router = useRouter()

const showLoading = computed(() => props.status === 'IDLE' || props.status === 'LOADING' || props.status === 'ERROR')
const activities = computed(() => props.items ?? [])
const allRecordTarget = computed(() => resolveRoute({ name: 'growth', label: '查看全部记录' }, (name) => router.hasRoute(name)))
const emptyAction = computed<NavigationTarget>(() => ({ name: 'growth', label: '查看成长中心' }))

function routeOf(item: ActivityItem): NavigationTarget | null {
  return resolveRoute(item.target, (name) => router.hasRoute(name))
}

function go(target: NavigationTarget | null): void {
  if (target) {
    void router.push({ name: target.name, query: target.query })
  }
}
</script>

<template>
  <section class="panel activity-panel">
    <div class="panel__heading">
      <h2><el-icon><Clock /></el-icon>最新动态</h2>
      <button v-if="allRecordTarget" type="button" class="panel__link" @click="go(allRecordTarget)">
        查看全部记录 <el-icon><ArrowRight /></el-icon>
      </button>
    </div>

    <RegionState
      v-if="showLoading"
      :status="status"
      :error="error"
      loading-text="正在加载最新动态…"
      @retry="emit('retry')"
    />

    <RegionState
      v-else-if="status === 'EMPTY'"
      status="EMPTY"
      empty-text="暂无动态记录"
      :empty-action="emptyAction"
      @retry="emit('retry')"
    />

    <template v-else>
      <div v-if="status === 'REFRESHING'" class="activity-panel__refresh" aria-hidden="true" />
      <div class="activity-list">
        <button
          v-for="item in activities"
          :key="item.id"
          type="button"
          class="activity"
          :class="{ 'activity--link': !!routeOf(item) }"
          :disabled="!routeOf(item)"
          @click="go(routeOf(item))"
        >
          <span class="activity__type">{{ item.typeLabel }}</span>
          <span class="activity__summary">{{ item.summary }}</span>
          <span class="activity__time">{{ formatActivityTime(item.occurredAt) }}</span>
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped lang="scss">
.panel { background: #fff; border: 1px solid #e8f0fa; border-radius: 10px; padding: 16px 18px 14px; }
.panel__heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 14px; }
.panel h2 { font-size: 18px; display: flex; align-items: center; gap: 10px; line-height: 1.4; }
.panel h2 > .el-icon { color: #0861fc; font-size: 23px; }
.panel__link { display: inline-flex; align-items: center; gap: 4px; border: 0; background: transparent; padding: 0; white-space: nowrap; color: #0662ff; font-size: 14px; cursor: pointer; }
.activity-panel__refresh { height: 2px; margin-bottom: 10px; border-radius: 2px; background: linear-gradient(90deg, transparent, #0c5cf5, transparent); background-size: 50% 100%; animation: activity-refresh 1.1s linear infinite; }
@keyframes activity-refresh { 0% { background-position: -50% 0; } 100% { background-position: 150% 0; } }
.activity-list { display: flex; flex-direction: column; gap: 6px; }
.activity { display: flex; align-items: center; gap: 10px; width: 100%; padding: 10px 12px; border: 1px solid #e7eff9; border-radius: 8px; background: #fff; text-align: left; cursor: default; }
.activity--link { cursor: pointer; }
.activity--link:hover { border-color: #b9d4fb; background: #fbfdff; }
.activity:disabled { opacity: 1; }
.activity__type { flex: 0 0 auto; padding: 1px 8px; border-radius: 999px; background: #eef5ff; color: #4d6a9c; font-size: 12px; }
.activity__summary { flex: 1; min-width: 0; color: #33456f; font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.activity__time { flex: 0 0 auto; color: #8495b1; font-size: 12px; white-space: nowrap; }
</style>