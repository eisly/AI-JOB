<script setup lang="ts">
import { computed } from 'vue'
import type { TaskStatus, TaskVO } from '@/api/types'
import { taskStageLabels, taskStatusLabels, taskTypeLabels } from '@/utils/format'
import ModelReasoning from '@/components/ModelReasoning.vue'

const props = defineProps<{ task: TaskVO | null; compact?: boolean; reasoning?: string }>()
const errorDescription = computed(() => {
  const error = props.task?.error
  if (!error) return ''
  if (error.code === 'MODEL_PROVIDER_ERROR') {
    return error.retryable ? '模型服务暂时不可用，可稍后重试。' : '需要恢复服务端的模型账号或访问权限。'
  }
  return error.retryable ? '该任务可重试，重试会创建新任务。' : '请检查输入后重新提交。'
})

const tagType = computed(() => {
  const types: Record<TaskStatus, 'primary' | 'success' | 'info' | 'warning' | 'danger'> = {
    QUEUED: 'info',
    RUNNING: 'primary',
    CANCEL_REQUESTED: 'warning',
    SUCCEEDED: 'success',
    FAILED: 'danger',
    CANCELLED: 'info',
    EXPIRED: 'warning',
  }
  return props.task ? types[props.task.status] : 'info'
})
</script>

<template>
  <div v-if="task" class="task-status" :class="{ 'task-status--compact': compact }">
    <div class="task-status__heading">
      <strong>{{ taskTypeLabels[task.type] || task.type }}</strong>
      <el-tag :type="tagType" size="small">{{ taskStatusLabels[task.status] || task.status }}</el-tag>
    </div>
    <div class="task-status__stage">{{ (task.stage && taskStageLabels[task.stage]) || task.stage || '等待状态更新' }}</div>
    <el-progress v-if="task.progress !== null" :percentage="Math.max(0, Math.min(100, task.progress))" :status="task.status === 'FAILED' ? 'exception' : task.status === 'SUCCEEDED' ? 'success' : undefined" />
    <div v-else-if="!compact" class="task-status__unknown">进度暂无法估算</div>
    <el-alert v-if="task.error" :title="task.error.message" :description="errorDescription" type="error" :closable="false" show-icon />
    <ModelReasoning v-if="reasoning" :text="reasoning" />
    <div v-if="$slots.actions" class="task-status__actions"><slot name="actions" /></div>
  </div>
</template>

<style scoped lang="scss">
.task-status { display: grid; gap: 10px; padding: 14px; border: 1px solid $ai-border; border-radius: $ai-radius; background: $ai-bg-page; }
.task-status__heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.task-status__stage, .task-status__unknown { color: $ai-text-secondary; font-size: 13px; }
.task-status__actions { display: flex; flex-wrap: wrap; gap: 8px; }
.task-status--compact { padding: 10px 12px; gap: 6px; }
</style>
