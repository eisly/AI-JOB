<script setup lang="ts">
import WaitStatus from '@/components/WaitStatus.vue'
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { Aim, ArrowRight } from '@element-plus/icons-vue'
import type { ApiError, NavigationTarget, RegionStatus, TargetJobCard, TargetJobsModel } from '@/types/workbench'
import { resolveRoute } from '@/features/workbench/model/navigation'
import RegionState from './RegionState.vue'

const props = defineProps<{
  model?: TargetJobsModel
  status: RegionStatus
  error?: ApiError
}>()

const emit = defineEmits<{ retry: [] }>()
const router = useRouter()

const jobs = computed<TargetJobCard[]>(() => props.model?.jobs ?? [])
const emptyText = computed(() =>
  props.model?.emptyReason === 'ALL_INELIGIBLE' ? '当前条件暂未匹配到合适岗位' : '暂无目标岗位',
)
const emptyHint = computed(() =>
  props.model?.emptyReason === 'ALL_INELIGIBLE'
    ? '可前往岗位匹配查看资格未通过的原因。'
    : '设置求职方向后，查看适合你的岗位。',
)
const emptyAction: NavigationTarget = { name: 'jobs', query: { tab: 'matches' }, label: '去岗位匹配' }
const jobMatchRoute = computed(() => resolveRoute({ ...emptyAction, label: '查看岗位匹配' }, (name) => router.hasRoute(name)))
const showLoading = computed(() => props.status === 'IDLE' || props.status === 'LOADING' || props.status === 'ERROR')

function go(target: NavigationTarget | null): void {
  if (target) {
    void router.push({ name: target.name, query: target.query })
  }
}
</script>

<template>
  <section class="panel job-panel">
    <div class="panel__heading">
      <h2><el-icon><Aim /></el-icon>近期目标岗位</h2>
      <button v-if="jobMatchRoute" type="button" class="panel__link" @click="go(jobMatchRoute)">
        查看岗位匹配 <el-icon><ArrowRight /></el-icon>
      </button>
    </div>

    <RegionState
      v-if="showLoading"
      :status="status"
      :error="error"
      loading-text="正在加载目标岗位…"
      @retry="emit('retry')"
    />

    <RegionState
      v-else-if="status === 'EMPTY'"
      status="EMPTY"
      :empty-text="emptyText"
      :empty-hint="emptyHint"
      :empty-action="emptyAction"
      @retry="emit('retry')"
    />

    <template v-else>
      <div v-if="status === 'REFRESHING'" class="job-panel__refresh" aria-hidden="true" />

      <div v-if="model?.stale" class="job-panel__stale">
        结果已过期，请重新匹配
        <button v-if="jobMatchRoute" type="button" class="panel__link" @click="go(jobMatchRoute)">重新匹配</button>
      </div>

      <WaitStatus v-if="model?.phase === 'GENERATING'" title="正在匹配岗位" description="可进入岗位匹配查看任务状态。">
        <template #actions><el-button v-if="jobMatchRoute" size="small" @click="go(jobMatchRoute)">进入岗位匹配</el-button></template>
      </WaitStatus>

      <div v-else-if="model?.phase === 'FAILED'" class="job-state">
        <strong>岗位匹配未成功</strong>
        <p>可前往岗位匹配重新发起。</p>
        <button v-if="jobMatchRoute" type="button" class="panel__button" @click="go(jobMatchRoute)">重新匹配</button>
      </div>

      <div v-else class="job-list">
        <button
          v-for="job in jobs"
          :key="job.id"
          type="button"
          class="job-card"
          @click="go(job.action)"
        >
          <span class="job-card__title">{{ job.title }}</span>
          <span class="job-card__company">{{ job.companyLabel }}</span>
          <span class="job-card__meta">
            <span class="job-card__score">匹配指数 {{ job.matchScore !== undefined ? job.matchScore : '—' }}</span>
            <span class="job-card__tag">{{ job.eligibilityLabel }}</span>
          </span>
          <span v-if="job.sourceLabel" class="job-card__source">{{ job.sourceLabel }}</span>
          <span v-if="job.gapLabels.length > 0" class="job-card__gaps">差距技能：{{ job.gapLabels.join('、') }}</span>
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped lang="scss">
.panel { background: var(--ai-bg-card, #fff); border: 1px solid var(--ai-border-light, #e8f0fa); border-radius: 10px; padding: 16px 18px 12px; }
.panel__heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 14px; }
.panel h2 { font-size: 18px; display: flex; align-items: center; gap: 10px; line-height: 1.4; }
.panel h2 > .el-icon { color: var(--ai-primary, #0861fc); font-size: 23px; }
.panel__link { display: inline-flex; align-items: center; gap: 4px; border: 0; background: transparent; padding: 0; white-space: nowrap; color: var(--ai-primary, #0662ff); font-size: 14px; cursor: pointer; }
.job-panel__refresh { height: 2px; margin-bottom: 8px; border-radius: 2px; background: linear-gradient(90deg, transparent, var(--ai-primary-solid, #0c5cf5), transparent); background-size: 50% 100%; animation: job-refresh 1.1s linear infinite; }
@keyframes job-refresh { 0% { background-position: -50% 0; } 100% { background-position: 150% 0; } }
.job-panel__stale { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; padding: 8px 12px; border-radius: 8px; background: var(--ai-warning-bg, #fff8ef); border: 1px solid var(--ai-warning-border, #ffe2c2); color: var(--ai-warning, #9a5b09); font-size: 13px; }
.job-list { display: flex; flex-direction: column; gap: 10px; }
.job-card { display: flex; flex-direction: column; align-items: flex-start; gap: 5px; width: 100%; padding: 12px 14px; border: 1px solid var(--ai-border-light, #e5eef9); border-radius: 8px; background: var(--ai-bg-card, #fff); text-align: left; cursor: pointer; }
.job-card:hover { border-color: var(--ai-accent-border, #b9d4fb); box-shadow: 0 4px 12px rgba(12, 92, 245, 0.08); }
.job-card__title { color: var(--ai-text-primary, #0b1746); font-weight: 650; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; }
.job-card__company { color: var(--ai-text-secondary, #7184a6); font-size: 13px; }
.job-card__meta { display: flex; align-items: center; gap: 8px; margin-top: 2px; }
.job-card__score { color: var(--ai-primary, #0c5cf5); font-size: 13px; font-weight: 600; }
.job-card__tag { padding: 1px 7px; border-radius: 999px; background: var(--ai-bg-light, #eef5ff); color: var(--ai-text-secondary, #4d6a9c); font-size: 12px; }
.job-card__source { color: var(--ai-text-tertiary, #8495b1); font-size: 12px; }
.job-card__gaps { color: var(--ai-text-tertiary, #8495b1); font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; }
.job-state { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; min-height: 150px; justify-content: center; padding: 14px; border: 1px solid var(--ai-border-light, #e5eef9); border-radius: 9px; color: var(--ai-text-secondary, #7184a6); }
.job-state strong { color: var(--ai-text-secondary, #33456f); font-size: 16px; }
.panel__button { border: 0; cursor: pointer; padding: 9px 16px; border-radius: 7px; background: var(--ai-primary-solid, #0c5cf5); color: #fff; font-weight: 600; }
.panel__button:hover { background: var(--ai-primary-solid-hover, #084ed5); }
</style>
