<script setup lang="ts">
import { computed } from 'vue'
import type { TaskVO } from '@/api/types'
import { isTerminalTask } from '@/composables/useTask'
import { taskErrorSummary, taskStageText, taskTypeLabels } from '@/utils/format'
import ModelReasoning from '@/components/ModelReasoning.vue'
import WaitStatus from '@/components/WaitStatus.vue'

const props = defineProps<{
  task: TaskVO | null
  compact?: boolean
  reasoning?: string
  loading?: boolean
  trackingError?: unknown
  refreshing?: boolean
  subject?: string
  runningDescription?: string
  hideDetails?: boolean
}>()
defineEmits<{ refresh: [] }>()

const taskName = computed(() => props.subject || taskTypeLabels[props.task?.type || ''] || '任务')
const connectionLost = computed(() => !!props.trackingError && !isTerminalTask(props.task?.status))
const showError = computed(() => props.task?.status === 'FAILED' || props.task?.status === 'EXPIRED')
const errorDescription = computed(() => {
  const error = props.task?.error
  if (!error) return ''
  if (error.code === 'MODEL_PROVIDER_ERROR') {
    return error.retryable ? '模型服务暂时不可用，可稍后重试。' : '需要恢复服务端的模型账号或访问权限。'
  }
  return error.retryable ? '该任务可重试，重试会创建新任务。' : '请检查输入后重新提交。'
})

const display = computed(() => {
  const task = props.task
  const name = taskName.value
  const search = task?.type === 'JOB_SEARCH' || props.subject === '搜索'
  if (connectionLost.value) {
    return {
      state: 'warning',
      title: search ? '暂时无法更新搜索状态' : '暂时无法更新任务状态',
      description: task ? `上次状态为${taskStageText(task)}。` : '已提交的任务可在状态恢复后继续查看。',
    } as const
  }
  switch (task?.status) {
    case 'QUEUED': return { state: 'queued', title: search ? '等待开始搜索' : `${name}等待开始`, description: '任务已提交，开始后会自动更新。' } as const
    case 'RUNNING': return {
      state: 'loading',
      title: task.type === 'JOB_SEARCH' ? '正在搜索岗位' : task.type === 'LEARNING_PRACTICE_EVALUATE' || task.type === 'LEARNING_EVALUATE' ? '正在评估作答' : `${name}进行中`,
      description: [taskStageText(task) === '处理中' ? '' : taskStageText(task), props.runningDescription].filter(Boolean).join('。'),
    } as const
    case 'CANCEL_REQUESTED': return { state: 'loading', title: `正在取消${name}`, description: '已提交取消请求，正在等待确认。' } as const
    case 'CANCELLED': return { state: 'idle', title: `${name}已取消` } as const
    case 'FAILED': return {
      state: 'error',
      title: `${name}失败`,
      description: props.hideDetails ? '请稍后重试。' : task.error ? taskErrorSummary(task.error) : '请检查后重试。',
    } as const
    case 'EXPIRED': return {
      state: 'warning',
      title: `${name}超时`,
      description: props.hideDetails || !task.error ? '任务已超过截止时间。' : taskErrorSummary(task.error),
    } as const
    case 'SUCCEEDED': return { state: 'success', title: `${name}已完成` } as const
    default: return { state: 'loading', title: '正在读取任务状态' } as const
  }
})
</script>

<template>
  <div v-if="task || loading || trackingError" class="task-status">
    <WaitStatus v-bind="display" :compact="compact">
      <template #actions>
        <el-button v-if="connectionLost" size="small" :loading="refreshing" @click="$emit('refresh')">刷新状态</el-button>
        <slot v-else name="actions" />
      </template>
    </WaitStatus>
    <div v-if="!hideDetails && task?.retryOfTaskId" class="task-status__detail">
      本次重试基于原任务的输入快照。
      <router-link :to="{ path: '/tasks', query: { taskId: task.retryOfTaskId } }">查看原任务</router-link>
    </div>
    <div v-if="!hideDetails && showError && errorDescription" class="task-status__detail">{{ errorDescription }}</div>
    <details v-if="!hideDetails && showError && task?.error && taskErrorSummary(task.error) !== task.error.message">
      <summary>查看错误诊断</summary>
      <p class="pre-wrap">{{ task.error.code }}：{{ task.error.message }}</p>
    </details>
    <ModelReasoning v-if="!hideDetails && reasoning" :text="reasoning" />
  </div>
</template>

<style scoped lang="scss">
.task-status { display: grid; gap: 8px; min-width: 0; }
.task-status__detail { color: $ai-text-secondary; font-size: 13px; }
</style>
