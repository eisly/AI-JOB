<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { Briefcase, Location, School, ArrowRight } from '@element-plus/icons-vue'
import type { ApiError, GoalSummary, NavigationTarget, RegionStatus } from '@/types/workbench'
import { resolveRoute } from '@/features/workbench/model/navigation'
import RegionState from './RegionState.vue'

const props = defineProps<{
  summary?: GoalSummary
  status: RegionStatus
  error?: ApiError
}>()

const emit = defineEmits<{ retry: [] }>()
const router = useRouter()

const target: NavigationTarget = { name: 'profile', query: { section: 'goal' } }
const targetRoute = computed(() => resolveRoute(target, (name) => router.hasRoute(name)))
const isEmpty = computed(() => props.summary?.isEmpty ?? true)
const citiesText = computed(() => {
  const labels = props.summary?.cityLabels ?? []
  return labels.length > 0 ? labels.join('、') : '未设置'
})
const showLoading = computed(() => props.status === 'IDLE' || props.status === 'LOADING' || props.status === 'ERROR')

function go(): void {
  if (targetRoute.value) {
    void router.push({ name: targetRoute.value.name, query: targetRoute.value.query })
  }
}
</script>

<template>
  <div class="goal-summary">
    <RegionState
      v-if="showLoading"
      :status="status"
      :error="error"
      loading-text="正在加载求职目标…"
      @retry="emit('retry')"
    />

    <template v-else>
      <div v-if="status === 'REFRESHING'" class="goal-summary__refresh" aria-hidden="true" />
      <div class="goals">
        <button type="button" class="goals__item" @click="go">
          <el-icon><Briefcase /></el-icon>
          <span>求职方向</span>
          <strong>{{ summary?.targetRoleLabel || '未设置' }}</strong>
        </button>
        <button type="button" class="goals__item" @click="go">
          <el-icon><Location /></el-icon>
          <span>目标城市</span>
          <strong>{{ citiesText }}</strong>
        </button>
        <button type="button" class="goals__item" @click="go">
          <el-icon><School /></el-icon>
          <span>求职阶段</span>
          <strong>{{ summary?.candidateTypeLabel || '未设置' }}</strong>
        </button>
      </div>
      <div v-if="isEmpty" class="goal-summary__guide">
        <p>完善画像后可获得个性化任务与岗位推荐。</p>
        <button type="button" class="goal-summary__guide-button" @click="go">
          完善求职画像 <el-icon><ArrowRight /></el-icon>
        </button>
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss">
.goal-summary { padding: 0 14px; }
.goals { display: flex; flex-wrap: wrap; gap: 0; margin-top: 20px; }
.goals__item { display: flex; gap: 10px; align-items: center; padding: 0 28px; border: 0; border-right: 1px solid #dce6f5; background: transparent; color: #61749b; cursor: pointer; font-size: 16px; }
.goals__item:first-child { padding-left: 0; }
.goals__item:last-child { border-right: 0; }
.goals__item:hover strong { color: #0c5cf5; }
.goals .el-icon { font-size: 21px; color: #122357; }
.goals strong { color: #101d4b; }
.goal-summary__guide { display: flex; align-items: center; gap: 14px; margin-top: 14px; color: #7184a6; font-size: 14px; }
.goal-summary__guide-button { display: inline-flex; align-items: center; gap: 6px; border: 0; background: transparent; color: #0c5cf5; cursor: pointer; font-weight: 600; padding: 0; }
.goal-summary__refresh { height: 2px; margin-top: 12px; border-radius: 2px; background: linear-gradient(90deg, transparent, #0c5cf5, transparent); background-size: 50% 100%; animation: goal-refresh 1.1s linear infinite; }
@keyframes goal-refresh { 0% { background-position: -50% 0; } 100% { background-position: 150% 0; } }
@media (max-width: 700px) {
  .goal-summary { padding-left: 0; }
  .goals { gap: 10px; }
  .goals__item { padding: 0 12px 0 0; font-size: 14px; }
  .goal-summary__guide { flex-direction: column; align-items: flex-start; gap: 6px; }
}
</style>