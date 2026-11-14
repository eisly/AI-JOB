<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { taskApi } from '@/api/tasks'
import { canRetryTrackedTask, retryTrackedTask, taskBusinessPath } from '@/api/task-registry'
import type { TaskStatus as TaskStatusType, TaskVO } from '@/api/types'
import AsyncState from '@/components/AsyncState.vue'
import TaskStatus from '@/components/TaskStatus.vue'
import { isTerminalTask, useTask } from '@/composables/useTask'
import { errorMessage, formatDate, taskOutcomeLabels, taskStageLabels, taskStatusLabels, taskTypeLabels } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const tasks = ref<TaskVO[]>([])
const statusFilter = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const listLoading = ref(false)
const listError = ref<unknown>(null)
const selectedTaskId = ref('')
const drawerVisible = ref(false)
const cancelingId = ref('')
const retryingId = ref('')
const actionError = ref<unknown>(null)
let listRequestSeq = 0
let refreshTimer: number | undefined
let refreshing = false

const tracker = useTask(selectedTaskId, {
  onTask: mergeTask,
  onTerminal: handleTerminalTask,
})

const statusOptions = [
  { label: '全部任务', value: '' },
  { label: '进行中', value: 'QUEUED,RUNNING,CANCEL_REQUESTED' },
  { label: '成功', value: 'SUCCEEDED' },
  { label: '失败', value: 'FAILED' },
  { label: '已取消', value: 'CANCELLED' },
  { label: '已过期', value: 'EXPIRED' },
]
const selectedTask = computed(() => {
  if (tracker.task.value?.id === selectedTaskId.value) return tracker.task.value
  return tasks.value.find((item) => item.id === selectedTaskId.value) || null
})
const selectedDelta = computed(() => {
  if (!tracker.task.value || tracker.task.value.id !== selectedTaskId.value) return ''
  return isTerminalTask(tracker.task.value.status) ? '' : tracker.deltaText.value
})
const selectedReasoning = computed(() => {
  if (!tracker.task.value || tracker.task.value.id !== selectedTaskId.value) return ''
  return tracker.reasoningText.value
})
const hasRunningTasks = computed(() => tasks.value.some((item) => !isTerminalTask(item.status)))

function queryTaskId() {
  return typeof route.query.taskId === 'string' ? route.query.taskId : ''
}

function tagType(status: TaskStatusType): 'primary' | 'success' | 'info' | 'warning' | 'danger' {
  const types: Record<TaskStatusType, 'primary' | 'success' | 'info' | 'warning' | 'danger'> = {
    QUEUED: 'info',
    RUNNING: 'primary',
    CANCEL_REQUESTED: 'warning',
    SUCCEEDED: 'success',
    FAILED: 'danger',
    CANCELLED: 'info',
    EXPIRED: 'warning',
  }
  return types[status]
}

/** S03/A05：业务结果标签样式——来源失败无结果为危险，部分失败为警告，正常空结果为中性 */
function outcomeTagType(outcome: string): 'success' | 'info' | 'warning' | 'danger' {
  switch (outcome) {
    case 'OK':
      return 'success'
    case 'PARTIAL':
      return 'warning'
    case 'NO_RESULTS_SOURCE_FAILED':
      return 'danger'
    default:
      return 'info'
  }
}

function mergeTask(task: TaskVO) {
  const index = tasks.value.findIndex((item) => item.id === task.id)
  if (index >= 0) tasks.value[index] = task
}

async function loadTasks(showLoading = true) {
  const run = ++listRequestSeq
  if (showLoading) {
    listLoading.value = true
    listError.value = null
  }
  try {
    const page = await taskApi.list({
      current: currentPage.value,
      size: pageSize.value,
      status: statusFilter.value || undefined,
    })
    if (run !== listRequestSeq) return
    tasks.value = page.records
    total.value = page.total
    currentPage.value = page.current
    pageSize.value = page.size
    listError.value = null
  } catch (reason) {
    if (showLoading && run === listRequestSeq) listError.value = reason
  } finally {
    if (run === listRequestSeq) listLoading.value = false
  }
}

async function refreshRunningTasks() {
  if (refreshing || listLoading.value || (!hasRunningTasks.value && statusFilter.value !== 'QUEUED,RUNNING,CANCEL_REQUESTED')) return
  refreshing = true
  try {
    await loadTasks(false)
  } finally {
    refreshing = false
  }
}

function handleFilterChange() {
  currentPage.value = 1
  void loadTasks()
}

function handlePageChange(page: number) {
  currentPage.value = page
  void loadTasks()
}

function handleSizeChange(size: number) {
  pageSize.value = size
  currentPage.value = 1
  void loadTasks()
}

function taskRow(row: unknown) {
  return row as TaskVO
}

async function selectTask(task: TaskVO) {
  actionError.value = null
  drawerVisible.value = true
  selectedTaskId.value = task.id
  await router.replace({ query: { ...route.query, taskId: task.id } })
}

async function closeDrawer() {
  drawerVisible.value = false
  selectedTaskId.value = ''
  actionError.value = null
  if (queryTaskId()) await router.replace({ query: { ...route.query, taskId: undefined } })
}

async function handleTerminalTask() {
  await loadTasks(false)
}

async function cancelTask(task: TaskVO) {
  if (isTerminalTask(task.status) || cancelingId.value) return
  cancelingId.value = task.id
  actionError.value = null
  try {
    const cancelled = await taskApi.cancel(task.id)
    mergeTask(cancelled)
    if (tracker.task.value?.id === cancelled.id) tracker.task.value = cancelled
    ElMessage.success(cancelled.status === 'CANCEL_REQUESTED' ? '已请求取消，正在等待任务停止' : '任务状态已更新')
    await loadTasks(false)
  } catch (reason) {
    actionError.value = reason
    ElMessage.error(errorMessage(reason))
  } finally {
    cancelingId.value = ''
  }
}

function canRetryTask(task: TaskVO) {
  return canRetryTrackedTask(task)
}

async function retryTask(task: TaskVO) {
  if (!canRetryTask(task) || retryingId.value) return
  retryingId.value = task.id
  actionError.value = null
  try {
    const retried = await retryTrackedTask(task)
    ElMessage.success(`已创建重试任务 ${retried.id.slice(0, 8)}`)
    await loadTasks(false)
    await selectTask(retried)
  } catch (reason) {
    actionError.value = reason
    ElMessage.error(errorMessage(reason))
  } finally {
    retryingId.value = ''
  }
}

async function goToBusiness(task: TaskVO) {
  await router.push(taskBusinessPath(task))
}

watch(queryTaskId, (taskId) => {
  if (!taskId) {
    if (drawerVisible.value) {
      drawerVisible.value = false
      selectedTaskId.value = ''
    }
    return
  }
  if (taskId !== selectedTaskId.value) {
    selectedTaskId.value = taskId
    drawerVisible.value = true
  }
}, { immediate: true })

onMounted(() => {
  void loadTasks()
  refreshTimer = window.setInterval(() => void refreshRunningTasks(), 5000)
})

onBeforeUnmount(() => {
  if (refreshTimer !== undefined) window.clearInterval(refreshTimer)
})
</script>

<template>
  <div class="page tasks-page">
    <header class="page-header">
      <div>
        <h1>任务中心</h1>
        <p>任务状态与结果均从服务端恢复，可查看实时进度、请求取消或按原提交参数重试失败任务。</p>
      </div>
      <div class="page-header__actions">
        <el-button :loading="listLoading" @click="loadTasks()">刷新列表</el-button>
      </div>
    </header>

    <section class="panel">
      <div class="filter-bar">
        <span>任务状态</span>
        <el-radio-group v-model="statusFilter" @change="handleFilterChange">
          <el-radio-button v-for="option in statusOptions" :key="option.value || 'all'" :value="option.value">{{ option.label }}</el-radio-button>
        </el-radio-group>
        <span v-if="hasRunningTasks" class="auto-refresh">每 5 秒自动刷新进行中的任务</span>
      </div>

      <el-alert v-if="actionError && !drawerVisible" class="list-action-error" :title="errorMessage(actionError)" type="error" show-icon @close="actionError = null" />

      <AsyncState :loading="listLoading" :error="listError" :empty="!tasks.length" empty-text="当前筛选条件下暂无任务" @retry="loadTasks()">
        <div class="task-table-wrap">
          <el-table :data="tasks" row-key="id" class="task-table">
            <el-table-column label="任务类型" min-width="160">
              <template #default="{ row }">
                <div class="task-name">
                  <strong>{{ taskTypeLabels[row.type] || row.type }}</strong>
                  <span>{{ row.id.slice(0, 8) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="tagType(row.status)" size="small">{{ taskStatusLabels[row.status] || row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="业务结果" min-width="170">
              <template #default="{ row }">
                <el-tag v-if="row.result?.outcome" size="small" :type="outcomeTagType(row.result.outcome)">{{ taskOutcomeLabels[row.result.outcome] || row.result.outcome }}</el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="阶段" min-width="150">
              <template #default="{ row }">{{ taskStageLabels[row.stage] || row.stage || '—' }}</template>
            </el-table-column>
            <el-table-column label="进度" width="150">
              <template #default="{ row }">
                <el-progress v-if="row.progress !== null" :percentage="Math.max(0, Math.min(100, row.progress))" :status="row.status === 'FAILED' ? 'exception' : row.status === 'SUCCEEDED' ? 'success' : undefined" />
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="错误" min-width="180">
              <template #default="{ row }">
                <el-tooltip v-if="row.error" :content="row.error.message" placement="top" :show-after="300">
                  <span class="error-text">{{ row.error.message }}</span>
                </el-tooltip>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" min-width="165">
              <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="完成时间" min-width="165">
              <template #default="{ row }">{{ formatDate(row.finishedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" fixed="right" min-width="280">
              <template #default="{ row }">
                <div class="row-actions">
                  <el-button size="small" @click="selectTask(taskRow(row))">实时详情</el-button>
                  <el-button v-if="!isTerminalTask(row.status)" size="small" type="warning" plain :loading="cancelingId === row.id" @click="cancelTask(taskRow(row))">取消</el-button>
                  <el-button v-if="row.status === 'SUCCEEDED' && row.result" size="small" type="primary" plain @click="goToBusiness(taskRow(row))">查看结果</el-button>
                  <el-button v-if="canRetryTask(taskRow(row))" size="small" type="primary" :loading="retryingId === row.id" @click="retryTask(taskRow(row))">重试</el-button>
                  <el-button v-if="['FAILED', 'CANCELLED', 'EXPIRED'].includes(row.status) && !canRetryTask(taskRow(row))" size="small" @click="goToBusiness(taskRow(row))">前往处理</el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <el-pagination
          v-if="total > 0"
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          class="pagination"
          background
          layout="total, sizes, prev, pager, next, jumper"
          :total="total"
          :page-sizes="[10, 20, 50]"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </AsyncState>
    </section>

    <el-drawer v-model="drawerVisible" size="min(560px, 100vw)" title="任务实时详情" :destroy-on-close="false" @close="closeDrawer">
      <div class="drawer-content">
        <el-alert title="关闭详情只会停止实时订阅，不会取消业务任务。" type="info" :closable="false" show-icon />
        <el-alert v-if="actionError" :title="errorMessage(actionError)" type="error" show-icon @close="actionError = null" />
        <AsyncState v-if="!selectedTask" :loading="tracker.loading.value" :error="tracker.error.value" empty-text="未找到任务详情" @retry="tracker.restart" />
        <template v-else>
          <div class="detail-heading">
            <div>
              <strong>{{ taskTypeLabels[selectedTask.type] || selectedTask.type }}</strong>
              <span>{{ selectedTask.id }}</span>
            </div>
            <el-tag v-if="isTerminalTask(selectedTask.status)" type="info" size="small">已结束</el-tag>
            <el-tag v-else :type="tracker.connected.value ? 'success' : 'info'" size="small">{{ tracker.connected.value ? '实时连接中' : '轮询恢复中' }}</el-tag>
          </div>
          <TaskStatus :task="selectedTask" :reasoning="selectedReasoning">
            <template #actions>
              <el-button v-if="!isTerminalTask(selectedTask.status)" type="warning" plain :loading="cancelingId === selectedTask.id" @click="cancelTask(selectedTask)">取消任务</el-button>
              <el-button v-if="selectedTask.status === 'SUCCEEDED' && selectedTask.result" type="primary" @click="goToBusiness(selectedTask)">查看结果</el-button>
              <el-button v-if="canRetryTask(selectedTask)" type="primary" :loading="retryingId === selectedTask.id" @click="retryTask(selectedTask)">按原参数重试</el-button>
              <el-button v-if="['FAILED', 'CANCELLED', 'EXPIRED'].includes(selectedTask.status) && !canRetryTask(selectedTask)" @click="goToBusiness(selectedTask)">前往处理</el-button>
            </template>
          </TaskStatus>
          <div v-if="selectedDelta" class="delta-panel">
            <strong>实时增量（尚未保存为业务结果）</strong>
            <div class="pre-wrap">{{ selectedDelta }}</div>
          </div>
          <el-alert v-if="tracker.error.value" :title="errorMessage(tracker.error.value)" type="warning" :closable="false" show-icon>
            <el-button class="reconnect-button" size="small" @click="tracker.restart">重新连接</el-button>
          </el-alert>
          <dl class="task-metadata">
            <div><dt>任务 ID</dt><dd>{{ selectedTask.id }}</dd></div>
            <div><dt>任务类型</dt><dd>{{ selectedTask.type }}</dd></div>
            <div><dt>当前状态</dt><dd>{{ taskStatusLabels[selectedTask.status] || selectedTask.status }}</dd></div>
            <div><dt>关联资源</dt><dd>{{ selectedTask.result?.resourceId || selectedTask.resourceId || '—' }}</dd></div>
            <div><dt>结果版本</dt><dd>{{ selectedTask.result?.version ?? '—' }}</dd></div>
            <div><dt>重试来源</dt><dd>{{ selectedTask.retryOfTaskId || '—' }}</dd></div>
            <div><dt>创建时间</dt><dd>{{ formatDate(selectedTask.createdAt) }}</dd></div>
            <div><dt>完成时间</dt><dd>{{ formatDate(selectedTask.finishedAt) }}</dd></div>
          </dl>
          <div class="input-versions">
            <h3>固定输入版本</h3>
            <el-empty v-if="!Object.keys(selectedTask.inputVersions).length" :image-size="56" description="无固定输入版本" />
            <dl v-else>
              <div v-for="(value, key) in selectedTask.inputVersions" :key="key">
                <dt>{{ key }}</dt>
                <dd>{{ value ?? '—' }}</dd>
              </div>
            </dl>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.tasks-page { padding-bottom: 24px; }
.filter-bar > span:first-child { color: $ai-text-primary; font-weight: 600; }
.auto-refresh { color: $ai-text-secondary; font-size: 12px; }
.list-action-error { margin-bottom: 14px; }
.task-table-wrap { width: 100%; overflow-x: auto; }
.task-table { min-width: 1180px; }
.task-name { display: grid; gap: 4px; }
.task-name span { color: $ai-text-tertiary; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12px; }
.error-text { display: block; overflow: hidden; color: $ai-danger; text-overflow: ellipsis; white-space: nowrap; }
.row-actions { display: flex; flex-wrap: wrap; gap: 6px; }
.row-actions .el-button { margin: 0; }
.drawer-content { display: grid; gap: 16px; }
.detail-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.detail-heading > div { display: grid; gap: 5px; min-width: 0; }
.detail-heading strong { color: #101d4b; font-size: 18px; }
.detail-heading span { overflow-wrap: anywhere; color: $ai-text-secondary; font-size: 12px; }
.delta-panel { display: grid; gap: 8px; padding: 14px; border: 1px dashed $ai-primary; border-radius: $ai-radius; background: $ai-primary-light; }
.delta-panel strong { color: $ai-primary; }
.reconnect-button { margin-top: 8px; }
.task-metadata, .input-versions dl { display: grid; gap: 0; margin: 0; }
.task-metadata > div, .input-versions dl > div { display: grid; grid-template-columns: 110px minmax(0, 1fr); gap: 12px; padding: 10px 0; border-bottom: 1px solid $ai-border-light; }
.task-metadata dt, .input-versions dt { color: $ai-text-secondary; }
.task-metadata dd, .input-versions dd { margin: 0; overflow-wrap: anywhere; }
.input-versions h3 { margin: 0 0 8px; color: #101d4b; font-size: 15px; }
@media (max-width: 900px) {
  .filter-bar { align-items: flex-start; }
  .filter-bar .el-radio-group { display: flex; flex-wrap: wrap; }
}
@media (max-width: 700px) {
  .filter-bar .el-radio-group { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); width: 100%; }
  .filter-bar :deep(.el-radio-button__inner) { width: 100%; }
  .task-metadata > div, .input-versions dl > div { grid-template-columns: 90px minmax(0, 1fr); }
}
</style>