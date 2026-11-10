<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { TrendCharts, ArrowRight } from '@element-plus/icons-vue'
import type { ApiError, NavigationTarget, ProgressModel, ProgressStage, RegionStatus, StageStatus } from '@/types/workbench'
import { resolveRoute } from '@/features/workbench/model/navigation'
import RegionState from './RegionState.vue'

const props = defineProps<{
  model?: ProgressModel
  status: RegionStatus
  error?: ApiError
}>()

const emit = defineEmits<{ retry: [] }>()
const router = useRouter()

const percent = computed(() => props.model?.percent)
const stages = computed<ProgressStage[]>(() => props.model?.stages ?? [])
const showLoading = computed(() => props.status === 'IDLE' || props.status === 'LOADING' || props.status === 'ERROR')
const detailTarget = computed(() => resolveRoute({ name: 'growth', label: '查看详细进度' }, (name) => router.hasRoute(name)))

const ringStyle = computed(() => {
  const value = percent.value ?? 0
  return {
    background: `conic-gradient(#0c5cf5 ${value}%, #e6effb ${value}% 100%)`,
  }
})

function statusText(status: StageStatus): string {
  if (status === 'DONE') {
    return '已完成'
  }
  if (status === 'PENDING') {
    return '未完成'
  }
  return '待确认'
}

function stageTarget(stage: ProgressStage): NavigationTarget | null {
  return resolveRoute(stage.action, (name) => router.hasRoute(name))
}

function go(target: NavigationTarget | null): void {
  if (target) {
    void router.push({ name: target.name, query: target.query })
  }
}
</script>

<template>
  <section class="panel progress-panel">
    <div class="panel__heading">
      <h2>
        <el-icon><TrendCharts /></el-icon>求职进度
        <small>完成各阶段任务，逐步提升你的求职竞争力。</small>
      </h2>
      <button v-if="detailTarget" type="button" class="panel__link" @click="go(detailTarget)">
        查看详细进度 <el-icon><ArrowRight /></el-icon>
      </button>
    </div>

    <RegionState
      v-if="showLoading"
      :status="status"
      :error="error"
      loading-text="正在汇总求职进度…"
      @retry="emit('retry')"
    />

    <template v-else>
      <div v-if="status === 'REFRESHING'" class="progress-panel__refresh" aria-hidden="true" />

      <div class="progress-overview">
        <div class="progress-ring" :style="ringStyle">
          <div class="progress-ring__inner">
            <strong v-if="percent !== undefined">{{ percent }}%</strong>
            <strong v-else class="progress-ring__unknown">暂无法计算</strong>
            <span>已完成 {{ model?.completedCount ?? 0 }} / {{ model?.totalCount ?? 6 }} 个阶段</span>
          </div>
        </div>

        <div class="progress-meta">
          <p v-if="percent === 0" class="progress-meta__tip">完善画像即可开始。</p>
          <p v-else-if="percent === undefined" class="progress-meta__tip">
            部分阶段数据暂时不可用，展示的是已确认的阶段明细。
          </p>
          <p v-else class="progress-meta__tip">进度只统计已完成的客观阶段，不代表求职结果。</p>
          <button v-if="!model?.computable" type="button" class="progress-meta__retry" @click="emit('retry')">
            重新加载进度
          </button>
        </div>
      </div>

      <div class="stage-list">
        <div
          v-for="stage in stages"
          :key="stage.key"
          class="stage"
          :class="[`stage--${stage.status.toLowerCase()}`, { 'stage--priority': stage.priority }]"
        >
          <span class="stage__dot" aria-hidden="true" />
          <div class="stage__content">
            <div class="stage__title">
              {{ stage.label }}
              <span v-if="stage.priority" class="stage__priority">优先</span>
            </div>
            <p v-if="stage.note" class="stage__note">{{ stage.note }}</p>
          </div>
          <span class="stage__status">{{ statusText(stage.status) }}</span>
          <button
            v-if="stage.status === 'PENDING' && stageTarget(stage)"
            type="button"
            class="stage__link"
            @click="go(stageTarget(stage))"
          >
            去完成
          </button>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped lang="scss">
.panel { background: #fff; border: 1px solid #e8f0fa; border-radius: 10px; padding: 16px 18px 14px; }
.panel__heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 14px; }
.panel h2 { font-size: 18px; display: flex; align-items: center; gap: 10px; line-height: 1.4; }
.panel h2 > .el-icon { color: #0861fc; font-size: 23px; }
.panel h2 small { color: #7284a5; font-size: 14px; font-weight: 400; margin-left: 8px; }
.panel__link { display: inline-flex; align-items: center; gap: 4px; border: 0; background: transparent; padding: 0; white-space: nowrap; color: #0662ff; font-size: 14px; cursor: pointer; }
.progress-panel__refresh { height: 2px; margin-bottom: 10px; border-radius: 2px; background: linear-gradient(90deg, transparent, #0c5cf5, transparent); background-size: 50% 100%; animation: progress-refresh 1.1s linear infinite; }
@keyframes progress-refresh { 0% { background-position: -50% 0; } 100% { background-position: 150% 0; } }
.progress-overview { display: flex; align-items: center; gap: 24px; margin-bottom: 18px; }
.progress-ring { width: 132px; height: 132px; border-radius: 50%; display: grid; place-items: center; flex: 0 0 auto; }
.progress-ring__inner { width: 104px; height: 104px; border-radius: 50%; background: #fff; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 2px; text-align: center; }
.progress-ring__inner strong { color: #0b1746; font-size: 26px; line-height: 1.1; }
.progress-ring__inner strong.progress-ring__unknown { font-size: 13px; color: #7184a6; }
.progress-ring__inner span { color: #8495b1; font-size: 11px; }
.progress-meta { display: flex; flex-direction: column; align-items: flex-start; gap: 10px; color: #7184a6; }
.progress-meta__tip { line-height: 1.5; }
.progress-meta__retry { border: 1px solid #d8e5f6; background: #fff; color: #0c5cf5; cursor: pointer; padding: 7px 12px; border-radius: 7px; }
.stage-list { display: flex; flex-direction: column; gap: 8px; }
.stage { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border: 1px solid #e7eff9; border-radius: 8px; background: #fbfdff; }
.stage__dot { width: 9px; height: 9px; border-radius: 50%; background: #c3d3e8; flex: 0 0 auto; }
.stage--done .stage__dot { background: #10b981; }
.stage--pending .stage__dot { background: #f59e0b; }
.stage--unknown .stage__dot { background: #9ca3af; }
.stage__content { flex: 1; min-width: 0; }
.stage__title { color: #1b2a4e; font-weight: 600; font-size: 14px; display: flex; align-items: center; gap: 6px; }
.stage__priority { padding: 1px 7px; border-radius: 999px; background: #eef5ff; color: #0c5cf5; font-size: 11px; font-weight: 600; }
.stage__note { color: #8495b1; font-size: 12px; margin-top: 3px; }
.stage__status { color: #7184a6; font-size: 12px; white-space: nowrap; }
.stage--done .stage__status { color: #0f9d70; }
.stage__link { border: 0; background: transparent; color: #0c5cf5; cursor: pointer; font-size: 13px; font-weight: 600; white-space: nowrap; padding: 0; }
@media (max-width: 700px) {
  .panel h2 small { display: none; }
  .progress-overview { flex-direction: column; align-items: flex-start; gap: 14px; }
  .stage { flex-wrap: wrap; }
  .stage__content { flex-basis: calc(100% - 130px); }
}
</style>