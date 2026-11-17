<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { canRetryTrackedTask, getTaskSubmission, retryTrackedTask } from '@/api/task-registry'
import { practiceApi } from '@/api/training'
import { useTask } from '@/composables/useTask'
import type { PracticeConfigVO, TaskVO } from '@/api/types'
import TaskStatus from '@/components/TaskStatus.vue'
import { getJobRoleLabel } from '@/config/jobRoles'
import { errorMessage } from '@/utils/format'

interface PendingPracticeConfig {
  planTitle?: string
  totalQuestions: number
  typeDistribution: { choice: number; fill: number; subjective: number }
  difficulty: string
  direction?: string
}

const difficultyLabels: Record<string, string> = { EASY: '入门', MEDIUM: '进阶', HARD: '挑战', MIXED: '混合' }

const route = useRoute()
const router = useRouter()
const taskId = computed(() => String(route.params.taskId || ''))
const retrying = ref(false)

function readPending(): PendingPracticeConfig | null {
  const submission = getTaskSubmission(taskId.value)
  if (submission?.path !== '/api/v1/learning/practice-sets') return null
  return submission.body as PendingPracticeConfig
}

const pending = computed(readPending)
const taskTracker = useTask(taskId, { onDone: handleDone })
const task = taskTracker.task

async function handleDone(value: TaskVO) {
  const setId = value.result?.resourceId
  if (value.type !== 'LEARNING_PRACTICE_QUESTIONS' || value.result?.resourceType !== 'PRACTICE_SET' || !setId) {
    ElMessage.error('任务结果不是有效的整组练习，请返回训练中心重试')
    return
  }
  try {
    const set = await practiceApi.getSet(setId)
    const config = (getTaskSubmission(value.id)?.body as PracticeConfigVO | undefined) || set.config
    if (!set.config || !config || set.config.totalQuestions !== config.totalQuestions ||
      set.config.typeDistribution.choice !== config.typeDistribution.choice ||
      set.config.typeDistribution.fill !== config.typeDistribution.fill ||
      set.config.typeDistribution.subjective !== config.typeDistribution.subjective ||
      set.questions.length !== config.totalQuestions ||
      set.questions.filter(question => question.type === 'CHOICE').length !== config.typeDistribution.choice ||
      set.questions.filter(question => question.type === 'FILL').length !== config.typeDistribution.fill ||
      set.questions.filter(question => question.type === 'SUBJECTIVE').length !== config.typeDistribution.subjective) {
      ElMessage.error('实际题型数量与提交的配置不一致，请返回训练中心检查')
      return
    }
    await router.replace({ name: 'training-practice', params: { setId } })
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  }
}

async function retry() {
  if (!task.value) return
  retrying.value = true
  try {
    const next = await retryTrackedTask(task.value)
    await router.replace({ name: 'training-prepare', params: { taskId: next.id } })
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  } finally {
    retrying.value = false
  }
}

function backToTraining() {
  void router.push('/training')
}
</script>

<template>
  <div class="page prepare-page">
    <header class="page-header">
      <div>
        <el-button link :icon="ArrowLeft" class="prepare-back" @click="backToTraining">返回训练中心</el-button>
        <h1>正在生成本组题目</h1>
        <p>将按右侧自定义出题配置一次性预生成整组题目，完成后自动进入独立答题页面，可随时返回训练中心。</p>
      </div>
      <div class="actions">
        <el-button :icon="Refresh" @click="taskTracker.restart">刷新进度</el-button>
        <el-button type="primary" @click="backToTraining">返回训练中心</el-button>
      </div>
    </header>

    <section class="panel prepare-panel">
      <div v-if="pending" class="prepare-config">
        <div class="prepare-config__heading">
          <h2>{{ pending.planTitle || '专项训练题目' }}</h2>
          <el-tag type="primary" effect="plain">{{ pending.totalQuestions }} 道题</el-tag>
        </div>
        <dl class="prepare-config__stats">
          <div><dt>题型分布</dt><dd>选择 {{ pending.typeDistribution.choice }} · 填空 {{ pending.typeDistribution.fill }} · 主观 {{ pending.typeDistribution.subjective }}</dd></div>
          <div><dt>训练难度</dt><dd>{{ difficultyLabels[pending.difficulty] || pending.difficulty }}</dd></div>
          <div v-if="pending.direction"><dt>训练方向</dt><dd>{{ getJobRoleLabel(pending.direction) }}</dd></div>
        </dl>
      </div>

      <el-alert
        title="题目生成中"
        description="AI 正在按配置生成题目与标准答案；生成完成后本页会自动进入答题页面，无需重复点击。"
        type="info"
        :closable="false"
        show-icon
      />

      <TaskStatus v-if="task" :task="task" :reasoning="taskTracker.reasoningText.value">
        <template #actions>
          <el-button v-if="canRetryTrackedTask(task)" size="small" type="primary" :loading="retrying" @click="retry">按原参数重试</el-button>
        </template>
      </TaskStatus>
      <el-empty v-else-if="!taskTracker.loading" description="未找到题目生成任务，请返回训练中心重新生成" />

      <div v-if="taskTracker.error" class="tracking-error">
        <span>{{ errorMessage(taskTracker.error) }}</span>
        <el-button size="small" @click="taskTracker.restart">恢复任务跟踪</el-button>
      </div>

      <p class="prepare-tip">生成期间可以离开本页，返回训练中心后仍可通过训练单元重新进入。</p>
    </section>
  </div>
</template>

<style scoped lang="scss">
.prepare-page { padding-bottom: 28px; }
.prepare-back { padding: 0; margin-bottom: 6px; }
.prepare-panel { display: grid; gap: 14px; max-width: 880px; }
.prepare-config { display: grid; gap: 10px; padding: 16px 18px; border: 1px solid #dbe7fa; border-radius: $ai-radius; background: $ai-primary-light; }
.prepare-config__heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.prepare-config__heading h2 { margin: 0; font-size: 17px; color: #101d4b; }
.prepare-config__stats { display: grid; gap: 6px; margin: 0; font-size: 13px; color: $ai-text-secondary; }
.prepare-config__stats div { display: flex; gap: 12px; }
.prepare-config__stats dt { min-width: 64px; color: $ai-text-tertiary; }
.prepare-config__stats dd { margin: 0; color: $ai-text-primary; }
.prepare-tip { margin: 0; color: $ai-text-tertiary; font-size: 12px; }
.tracking-error { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: $ai-danger; font-size: 13px; }
</style>
