<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { Aim, ArrowRight } from '@element-plus/icons-vue'
import type { ApiError, NavigationTarget, NextTaskModel, RegionStatus } from '@/types/workbench'
import { resolveRoute } from '@/features/workbench/model/navigation'
import RegionState from './RegionState.vue'

const props = defineProps<{
  model?: NextTaskModel
  status: RegionStatus
  error?: ApiError
}>()

const emit = defineEmits<{ retry: [] }>()
const router = useRouter()

const suggestion = computed(() => props.model?.suggestion ?? null)
const actionTarget = computed(() => resolveRoute(suggestion.value?.action, (name) => router.hasRoute(name)))
const emptyAction: NavigationTarget = { name: 'jobs', query: { tab: 'matches' }, label: '查看岗位匹配' }
const taskCenterTarget = computed<NavigationTarget | null>(() =>
  resolveRoute({ name: 'tasks', fallback: emptyAction }, (name) => router.hasRoute(name)),
)
const showLoading = computed(() => props.status === 'IDLE' || props.status === 'LOADING' || props.status === 'ERROR')

function go(target: NavigationTarget | null): void {
  if (target) {
    void router.push({ name: target.name, query: target.query })
  }
}
</script>

<template>
  <section class="panel task-panel">
    <h2><el-icon><Aim /></el-icon>你的下一项任务</h2>

    <RegionState
      v-if="showLoading"
      :status="status"
      :error="error"
      loading-text="正在查找下一项任务…"
      @retry="emit('retry')"
    />

    <RegionState
      v-else-if="status === 'EMPTY' || !suggestion"
      status="EMPTY"
      empty-text="暂无可执行任务"
      empty-hint="可以先查看岗位匹配，或开始专项训练。"
      :empty-action="emptyAction"
      @retry="emit('retry')"
    />

    <template v-else>
      <div v-if="status === 'REFRESHING'" class="task-panel__refresh" aria-hidden="true" />
      <div class="task-card">
        <div class="task-card__label">{{ suggestion.typeLabel || '推荐行动' }}</div>
        <h3>{{ suggestion.title }}</h3>
        <p v-if="suggestion.stage" class="task-card__stage">{{ suggestion.stage }}</p>
        <p v-if="suggestion.description" class="task-card__description">{{ suggestion.description }}</p>
        <p v-if="suggestion.meta" class="task-card__meta">{{ suggestion.meta }}</p>
        <button v-if="actionTarget" type="button" class="task-card__action" @click="go(actionTarget)">
          继续 <el-icon><ArrowRight /></el-icon>
        </button>
      </div>

      <div v-if="model?.recentTaskFailed" class="task-failed">
        <span>任务未完成，可前往任务中心查看并重试。</span>
        <button v-if="taskCenterTarget" type="button" class="task-failed__link" @click="go(taskCenterTarget)">
          {{ taskCenterTarget.label || '前往查看' }}
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped lang="scss">
.panel { background: var(--ai-bg-card, #fff); border: 1px solid var(--ai-border-light, #e8f0fa); border-radius: 10px; padding: 16px 18px 12px; }
.panel h2 { font-size: 18px; display: flex; align-items: center; gap: 10px; line-height: 1.4; margin-bottom: 14px; }
.panel h2 > .el-icon { color: var(--ai-primary, #0861fc); font-size: 23px; }
.task-panel__refresh { height: 2px; margin-bottom: 8px; border-radius: 2px; background: linear-gradient(90deg, transparent, var(--ai-primary-solid, #0c5cf5), transparent); background-size: 50% 100%; animation: task-refresh 1.1s linear infinite; }
@keyframes task-refresh { 0% { background-position: -50% 0; } 100% { background-position: 150% 0; } }
.task-card { min-height: 228px; border-radius: 9px; padding: 26px; background: var(--ai-bg-light, #ecf5ff); display: flex; flex-direction: column; align-items: flex-start; gap: 10px; }
.task-card__label { color: var(--ai-primary, #0c5cf5); font-size: 13px; font-weight: 600; }
.task-card h3 { margin: 0; color: var(--ai-text-primary, #0b1746); font-size: 19px; }
.task-card__stage, .task-card__description { color: var(--ai-text-secondary, #5c6f94); line-height: 1.5; }
.task-card__meta { color: var(--ai-text-secondary, #7184a6); font-size: 13px; }
.task-card__action { display: inline-flex; align-items: center; gap: 8px; margin-top: auto; border: 0; cursor: pointer; padding: 11px 20px; border-radius: 7px; background: var(--ai-primary-solid, #0c5cf5); color: #fff; font-weight: 600; }
.task-card__action:hover { background: var(--ai-primary-solid-hover, #084ed5); }
.task-failed { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 12px; padding: 9px 12px; border: 1px solid var(--ai-warning-border, #ffe2c2); border-radius: 8px; background: var(--ai-warning-bg, #fff8ef); color: var(--ai-warning, #9a5b09); font-size: 13px; }
.task-failed__link { border: 0; background: transparent; color: var(--ai-primary, #0c5cf5); cursor: pointer; font-weight: 600; padding: 0; }
</style>
