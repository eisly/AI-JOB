<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { profileApi } from '@/api/profile'
import { projectApi } from '@/api/projects'
import { ApiError } from '@/api/request'
import { retryTrackedTask } from '@/api/task-registry'
import type { MatchItemOptionVO, Page, ProjectRecommendationItem, ProjectRecommendationVO, ProjectSummaryVO, ProjectTaskVO, ProjectVO, SkillVO } from '@/api/types'
import AsyncState from '@/components/AsyncState.vue'
import TaskStatus from '@/components/TaskStatus.vue'
import { isTerminalTask, useTask } from '@/composables/useTask'
import { commonStatusLabels, errorMessage, formatDate } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const recommendationTaskKey = 'ai-job-project-recommendation-task'
const planTaskKey = 'ai-job-project-plan-task'
const initialTab = route.query.tab === 'recommendations' || route.query.recommendationId ? 'recommendations' : 'projects'
const activeTab = ref<'recommendations' | 'projects'>(initialTab)
const recommendation = ref<ProjectRecommendationVO | null>(null)
const recommendationLoading = ref(false)
const recommendationError = ref<unknown>(null)
const recommendationLoadingId = ref('')
const recommendSubmitting = ref(false)
const recommendSubmitError = ref<unknown>(null)
const project = ref<ProjectVO | null>(null)
const projectLoading = ref(false)
const projectError = ref<unknown>(null)
const projectLoadingId = ref('')
const projectPage = ref<Page<ProjectSummaryVO> | null>(null)
const projectsLoading = ref(false)
const projectsError = ref<unknown>(null)
const projectQuery = reactive({ current: 1, size: 6 })
const skills = ref<SkillVO[]>([])
const skillsLoading = ref(false)
const skillsError = ref<unknown>(null)
const matchItemOptions = ref<MatchItemOptionVO[]>([])
const matchItemOptionsLoading = ref(false)
const matchItemOptionsError = ref<unknown>(null)
const recommendationFormRef = ref<FormInstance>()
const planFormRef = ref<FormInstance>()
const deliverableFormRef = ref<FormInstance>()
const planDialogVisible = ref(false)
const planSubmitting = ref(false)
const planSubmitError = ref<unknown>(null)
const selectedItem = ref<ProjectRecommendationItem | null>(null)
const updatingTaskIds = ref<Set<string>>(new Set())
const taskUpdateError = ref<unknown>(null)
const conflictMessage = ref('')
const deliverableSubmitting = ref(false)
const deliverableError = ref<unknown>(null)
const recommendationForm = reactive<{
  matchItemId: string
  skillCodes: string[]
  difficulty: ProjectRecommendationItem['difficulty']
  maxDurationWeeks: number
}>({ matchItemId: '', skillCodes: [], difficulty: 'INTERMEDIATE', maxDurationWeeks: 4 })
const planForm = reactive({ weeklyHours: 10 })
const deliverableForm = reactive({ taskId: '', title: '', url: '', notes: '' })
const selectedMatchItem = computed(() => matchItemOptions.value.find(item => item.matchItemId === recommendationForm.matchItemId) || null)
const difficultyLabels: Record<ProjectRecommendationItem['difficulty'], string> = {
  BEGINNER: '入门',
  INTERMEDIATE: '进阶',
  ADVANCED: '高级',
}
const taskStatusTypes: Record<ProjectTaskVO['status'], 'info' | 'primary' | 'success'> = {
  PENDING: 'info',
  IN_PROGRESS: 'primary',
  COMPLETED: 'success',
}
const recommendationRules: FormRules = {
  skillCodes: [{ type: 'array', max: 5, message: '最多选择 5 项技能', trigger: 'change' }],
  difficulty: [{ required: true, message: '请选择项目难度', trigger: 'change' }],
  maxDurationWeeks: [
    { required: true, message: '请设置最长项目周期', trigger: 'change' },
    { type: 'number', min: 1, max: 12, message: '项目周期须为 1 至 12 周', trigger: 'change' },
  ],
}
const planRules: FormRules = {
  weeklyHours: [
    { required: true, message: '请输入每周可投入时长', trigger: 'change' },
    { type: 'number', min: 1, max: 40, message: '每周投入须为 1 至 40 小时', trigger: 'change' },
  ],
}
const deliverableRules: FormRules = {
  taskId: [{ required: true, message: '请选择关联任务', trigger: 'change' }],
  title: [{ required: true, whitespace: true, message: '请输入交付标题', trigger: 'blur' }],
  url: [{
    validator: (_rule, value: string, callback) => {
      if (!value) return callback()
      try {
        const parsed = new URL(value)
        if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return callback(new Error('链接仅支持 http 或 https'))
        callback()
      } catch {
        callback(new Error('请输入完整有效的 http 或 https 链接'))
      }
    },
    trigger: 'blur',
  }],
}

function readStoredTask(key: string) {
  try {
    return localStorage.getItem(key) || ''
  } catch {
    return ''
  }
}

function storeTask(key: string, taskId: string) {
  try {
    localStorage.setItem(key, taskId)
  } catch {
    return
  }
}

function clearStoredTask(key: string) {
  try {
    localStorage.removeItem(key)
  } catch {
    return
  }
}

const recommendationTaskId = ref(readStoredTask(recommendationTaskKey))
const planTaskId = ref(readStoredTask(planTaskKey))
const {
  task: recommendationTask,
  loading: recommendationTaskLoading,
  error: recommendationTaskError,
  restart: restartRecommendationTask,
} = useTask(recommendationTaskId, {
  onDone: async task => {
    const id = task.result?.resourceId || task.resourceId
    if (!id) {
      recommendationError.value = new Error('推荐任务已完成，但返回结果缺少推荐 ID')
      return
    }
    clearStoredTask(recommendationTaskKey)
    await loadRecommendation(id)
    await replaceQuery({ tab: 'recommendations', recommendationId: id })
  },
})
const {
  task: planTask,
  loading: planTaskLoading,
  error: planTaskError,
  restart: restartPlanTask,
} = useTask(planTaskId, {
  onDone: async task => {
    const id = task.result?.resourceId || task.resourceId
    if (!id) {
      projectError.value = new Error('计划任务已完成，但返回结果缺少项目 ID')
      return
    }
    clearStoredTask(planTaskKey)
    activeTab.value = 'projects'
    await loadProject(id)
    await loadProjects()
    await replaceQuery({ tab: 'projects', projectId: id })
    ElMessage.success('项目计划已生成')
  },
})

const recommendationBusy = computed(() => recommendSubmitting.value || recommendationTaskLoading.value || (!!recommendationTaskId.value && !recommendationTask.value && !recommendationTaskError.value) || (!!recommendationTask.value && !isTerminalTask(recommendationTask.value.status)))
const planBusy = computed(() => planSubmitting.value || planTaskLoading.value || (!!planTaskId.value && !planTask.value && !planTaskError.value) || (!!planTask.value && !isTerminalTask(planTask.value.status)))
const projectTasks = computed(() => project.value?.milestones.flatMap(milestone => milestone.tasks) || [])
const completedTaskCount = computed(() => projectTasks.value.filter(task => task.status === 'COMPLETED').length)
const recommendationEmpty = computed(() => !recommendation.value && !recommendationTaskLoading.value && (!recommendationTask.value || isTerminalTask(recommendationTask.value.status)))

function queryString(value: unknown) {
  return typeof value === 'string' ? value : ''
}

async function replaceQuery(patch: Record<string, string | undefined>) {
  const query = { ...route.query }
  Object.entries(patch).forEach(([key, value]) => {
    if (value) query[key] = value
    else delete query[key]
  })
  await router.replace({ query })
}

function handleTabChange(name: string | number) {
  void replaceQuery({ tab: String(name) })
}

async function loadSkills(query = '') {
  skillsLoading.value = true
  skillsError.value = null
  try {
    const result = await profileApi.skills(query || undefined)
    skills.value = result.filter(skill => skill.enabled)
  } catch (reason) {
    skillsError.value = reason
  } finally {
    skillsLoading.value = false
  }
}

async function loadMatchItemOptions() {
  matchItemOptionsLoading.value = true
  matchItemOptionsError.value = null
  try {
    matchItemOptions.value = await projectApi.matchItemOptions()
  } catch (reason) {
    matchItemOptionsError.value = reason
    matchItemOptions.value = []
  } finally {
    matchItemOptionsLoading.value = false
  }
}

function goMatchResults() {
  void router.push({ path: '/job-match', query: { tab: 'matches' } })
}

function matchItemOptionLabel(item: MatchItemOptionVO) {
  const company = item.companyName || '公司未提供'
  const score = item.matchScore === null ? '匹配分待评估' : `匹配分 ${item.matchScore}`
  return `${item.title} · ${company} · ${score}`
}

async function submitRecommendation() {
  if (recommendationBusy.value) return
  try {
    await recommendationFormRef.value?.validate()
  } catch {
    return
  }
  recommendSubmitting.value = true
  recommendSubmitError.value = null
  try {
    const body: { matchItemId?: string; skillCodes?: string[]; difficulty?: string; maxDurationWeeks?: number } = {
      skillCodes: [...recommendationForm.skillCodes],
      difficulty: recommendationForm.difficulty,
      maxDurationWeeks: recommendationForm.maxDurationWeeks,
    }
    const matchItemId = recommendationForm.matchItemId.trim()
    if (matchItemId) body.matchItemId = matchItemId
    const task = await projectApi.recommend(body)
    recommendationTaskId.value = task.id
    storeTask(recommendationTaskKey, task.id)
    recommendation.value = null
    recommendationError.value = null
    activeTab.value = 'recommendations'
    await replaceQuery({ tab: 'recommendations', recommendationId: undefined })
    ElMessage.success('项目推荐任务已提交')
  } catch (reason) {
    recommendSubmitError.value = reason
  } finally {
    recommendSubmitting.value = false
  }
}

async function retryRecommendationTask() {
  if (!recommendationTask.value || recommendSubmitting.value) return
  recommendSubmitting.value = true
  recommendSubmitError.value = null
  try {
    const task = await retryTrackedTask(recommendationTask.value)
    recommendationTaskId.value = task.id
    storeTask(recommendationTaskKey, task.id)
    recommendation.value = null
    ElMessage.success('已使用原推荐条件重新提交')
  } catch (reason) {
    recommendSubmitError.value = reason
  } finally {
    recommendSubmitting.value = false
  }
}

async function loadRecommendation(id?: string) {
  const target = id || queryString(route.query.recommendationId) || recommendation.value?.id || recommendationLoadingId.value || ''
  if (!target) return false
  recommendationLoading.value = true
  recommendationLoadingId.value = target
  recommendationError.value = null
  try {
    recommendation.value = await projectApi.getRecommendation(target)
    return true
  } catch (reason) {
    recommendationError.value = reason
    recommendation.value = null
    return false
  } finally {
    if (recommendationLoadingId.value === target) recommendationLoading.value = false
  }
}

function openPlanDialog(item: ProjectRecommendationItem) {
  selectedItem.value = item
  planForm.weeklyHours = 10
  planSubmitError.value = null
  planDialogVisible.value = true
}

async function submitPlan() {
  if (!selectedItem.value || planBusy.value) return
  try {
    await planFormRef.value?.validate()
  } catch {
    return
  }
  planSubmitting.value = true
  planSubmitError.value = null
  try {
    const task = await projectApi.createPlan({ recommendationItemId: selectedItem.value.id, weeklyHours: planForm.weeklyHours })
    planTaskId.value = task.id
    storeTask(planTaskKey, task.id)
    planDialogVisible.value = false
    activeTab.value = 'projects'
    await replaceQuery({ tab: 'projects' })
    ElMessage.success('项目计划任务已提交')
  } catch (reason) {
    planSubmitError.value = reason
  } finally {
    planSubmitting.value = false
  }
}

async function retryPlanTask() {
  if (!planTask.value || planSubmitting.value) return
  planSubmitting.value = true
  planSubmitError.value = null
  try {
    const task = await retryTrackedTask(planTask.value)
    planTaskId.value = task.id
    storeTask(planTaskKey, task.id)
    ElMessage.success('已使用原候选项目和每周时长重新提交')
  } catch (reason) {
    planSubmitError.value = reason

  } finally {
    planSubmitting.value = false
  }
}

async function loadProjects() {
  projectsLoading.value = true
  projectsError.value = null
  try {
    projectPage.value = await projectApi.list({ current: projectQuery.current, size: projectQuery.size })
    return true
  } catch (reason) {
    projectsError.value = reason
    return false
  } finally {
    projectsLoading.value = false
  }
}

function handleProjectPage(current: number) {
  projectQuery.current = current
  void loadProjects()
}

function handleProjectSize(size: number) {
  projectQuery.size = size
  projectQuery.current = 1
  void loadProjects()
}

function applyProject(value: ProjectVO) {
  project.value = value
  if (projectPage.value) {
    const index = projectPage.value.records.findIndex(item => item.id === value.id)
    if (index >= 0) projectPage.value.records.splice(index, 1, value)
  }
  if (!projectTasks.value.some(task => task.id === deliverableForm.taskId)) {
    deliverableForm.taskId = projectTasks.value[0]?.id || ''
  }
}

async function loadProject(id?: string) {
  const target = id || queryString(route.query.projectId) || project.value?.id || projectLoadingId.value || ''
  if (!target) return false
  projectLoading.value = true
  projectLoadingId.value = target
  projectError.value = null
  try {
    applyProject(await projectApi.get(target))
    return true
  } catch (reason) {
    projectError.value = reason
    return false
  } finally {
    if (projectLoadingId.value === target) projectLoading.value = false
  }
}

async function openProject(projectId: string) {
  activeTab.value = 'projects'
  await loadProject(projectId)
  await replaceQuery({ tab: 'projects', projectId })
}

function retryProject() {
  const id = queryString(route.query.projectId) || project.value?.id || projectLoadingId.value
  if (id) void loadProject(id)
  else void loadProjects()
}

function normalizedProgress(value: number) {
  return Math.max(0, Math.min(100, Math.round(value)))
}

function taskIsUpdating(taskId: string) {
  return updatingTaskIds.value.has(taskId)
}

async function setTaskStatus(task: ProjectTaskVO, status: string) {
  if (!project.value || taskIsUpdating(task.id) || !['PENDING', 'IN_PROGRESS', 'COMPLETED'].includes(status) || task.status === status) return
  const projectId = project.value.id
  const nextStatus = status as ProjectTaskVO['status']
  const nextUpdating = new Set(updatingTaskIds.value)
  nextUpdating.add(task.id)
  updatingTaskIds.value = nextUpdating
  taskUpdateError.value = null
  conflictMessage.value = ''
  try {
    const result = await projectApi.updateTask(projectId, task.id, { status: nextStatus, expectedVersion: project.value.version })
    applyProject(result)
    ElMessage.success('任务状态已更新')
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 409) {
      const refreshed = await loadProject(projectId)
      conflictMessage.value = refreshed
        ? '检测到项目版本冲突，已刷新为服务端最新内容。请核对当前任务状态后手动重试，本次修改未被覆盖提交。'
        : '检测到项目版本冲突，但自动刷新失败。请手动刷新项目后再重试，本次修改未被覆盖提交。'
      ElMessage.warning('项目版本已变化，请确认最新状态后重试')
    } else {
      taskUpdateError.value = reason
    }
  } finally {
    const remaining = new Set(updatingTaskIds.value)
    remaining.delete(task.id)
    updatingTaskIds.value = remaining
  }
}

function onTaskCheckbox(task: ProjectTaskVO, checked: string | number | boolean) {
  void setTaskStatus(task, checked === true ? 'COMPLETED' : 'PENDING')
}

function taskTitle(taskId: string) {
  return projectTasks.value.find(task => task.id === taskId)?.title || '任务已不在当前计划中'
}

function safeUrl(url: string | null) {
  if (!url) return ''
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? url : ''
  } catch {
    return ''
  }
}

async function submitDeliverable() {
  if (!project.value || deliverableSubmitting.value) return
  try {
    await deliverableFormRef.value?.validate()
  } catch {
    return
  }
  deliverableSubmitting.value = true
  deliverableError.value = null
  try {
    const body: { taskId: string; title: string; url?: string; notes?: string } = {
      taskId: deliverableForm.taskId,
      title: deliverableForm.title.trim(),
    }
    const url = deliverableForm.url.trim()
    const notes = deliverableForm.notes.trim()
    if (url) body.url = url
    if (notes) body.notes = notes
    applyProject(await projectApi.addDeliverable(project.value.id, body))
    deliverableForm.title = ''
    deliverableForm.url = ''
    deliverableForm.notes = ''
    deliverableFormRef.value?.clearValidate()
    ElMessage.success('交付记录已保存，不代表已完成验收')
  } catch (reason) {
    deliverableError.value = reason
  } finally {
    deliverableSubmitting.value = false
  }
}

watch(() => route.query.tab, value => {
  if (value === 'recommendations' || value === 'projects') activeTab.value = value
})
watch(() => route.query.recommendationId, value => {
  const id = queryString(value)
  if (id && id !== recommendation.value?.id && id !== recommendationLoadingId.value) void loadRecommendation(id)
})
watch(() => route.query.projectId, value => {
  const id = queryString(value)
  if (id && id !== project.value?.id && id !== projectLoadingId.value) void loadProject(id)
})

onMounted(() => {
  void loadSkills()
  void loadMatchItemOptions()
  void loadProjects()
  const recommendationId = queryString(route.query.recommendationId)
  const projectId = queryString(route.query.projectId)
  if (recommendationId) void loadRecommendation(recommendationId)
  if (projectId) void loadProject(projectId)
})
</script>

<template>
  <div class="page project-page">
    <header class="page-header">
      <div>
        <h1>项目实战</h1>
        <p>从 AI 项目建议中选择方向，用可追踪的里程碑、任务与交付记录推进实践。</p>
      </div>
      <el-button @click="activeTab === 'recommendations' ? loadRecommendation() : loadProjects()">刷新当前列表</el-button>
    </header>

    <el-tabs v-model="activeTab" class="project-tabs" @tab-change="handleTabChange">
      <el-tab-pane label="项目推荐" name="recommendations">
        <section class="panel">
          <div class="panel__heading">
            <div>
              <h2>推荐条件</h2>
              <p class="section-note">可从本人有效匹配结果中选择岗位，也可留空；留空时按所选技能生成。仅展示未过期且未通过资格硬性失败的条目。</p>
            </div>
          </div>
          <el-form ref="recommendationFormRef" :model="recommendationForm" :rules="recommendationRules" label-position="top">
            <div class="form-grid">
              <el-form-item label="关联岗位匹配结果（可选）" prop="matchItemId">
                <el-select
                  v-model="recommendationForm.matchItemId"
                  class="full-width"
                  clearable
                  filterable
                  :loading="matchItemOptionsLoading"
                  placeholder="留空则按技能生成；也可选择本人有效匹配结果"
                >
                  <el-option label="不指定匹配结果（留空）" value="" />
                  <el-option
                    v-for="item in matchItemOptions"
                    :key="item.matchItemId"
                    :label="matchItemOptionLabel(item)"
                    :value="item.matchItemId"
                  >
                    <div class="match-option">
                      <strong>{{ item.title }}</strong>
                      <span>{{ item.companyName || '公司未提供' }} · 匹配分 {{ item.matchScore ?? '待评估' }} · {{ item.eligibilityStatus === 'PASS' ? '资格通过' : '资格待确认' }}</span>
                    </div>
                  </el-option>
                </el-select>
                <div v-if="matchItemOptionsError" class="field-error">
                  <span>{{ errorMessage(matchItemOptionsError) }}</span>
                  <el-button link type="primary" @click="loadMatchItemOptions">重试加载匹配结果</el-button>
                </div>
                <div v-else-if="!matchItemOptionsLoading && !matchItemOptions.length" class="field-hint">
                  暂无可用匹配结果。请先到岗位匹配完成一次有效匹配，或直接留空按技能生成。
                  <el-button link type="primary" @click="goMatchResults">去岗位匹配</el-button>
                </div>
              </el-form-item>
              <el-form-item label="目标技能（最多 5 项）" prop="skillCodes">
                <el-select
                  v-model="recommendationForm.skillCodes"
                  class="full-width"
                  multiple
                  filterable
                  remote
                  clearable
                  :remote-method="loadSkills"
                  :loading="skillsLoading"
                  :multiple-limit="5"
                  placeholder="搜索并选择技能目录"
                >
                  <el-option v-for="skill in skills" :key="skill.code" :label="`${skill.name}（${skill.code}）`" :value="skill.code" />
                </el-select>
                <div v-if="skillsError" class="field-error">
                  <span>{{ errorMessage(skillsError) }}</span>
                  <el-button link type="primary" @click="loadSkills()">重试技能目录</el-button>
                </div>
              </el-form-item>
              <el-form-item label="项目难度" prop="difficulty">
                <el-select v-model="recommendationForm.difficulty" class="full-width">
                  <el-option v-for="(label, value) in difficultyLabels" :key="value" :label="label" :value="value" />
                </el-select>
              </el-form-item>
              <el-form-item label="最长周期（周）" prop="maxDurationWeeks">
                <el-input-number v-model="recommendationForm.maxDurationWeeks" class="full-width" :min="1" :max="12" :step="1" step-strictly />
              </el-form-item>
            </div>
            <div v-if="selectedMatchItem" class="selected-match">
              <div>
                <span class="selected-match__eyebrow">已选岗位匹配结果</span>
                <strong>{{ selectedMatchItem.title }}</strong>
                <p>{{ selectedMatchItem.companyName || '公司未提供' }}<template v-if="selectedMatchItem.sourceName"> · 来源 {{ selectedMatchItem.sourceName }}</template></p>
                <p v-if="selectedMatchItem.reasons?.length" class="selected-match__reason">{{ selectedMatchItem.reasons.join('；') }}</p>
              </div>
              <div class="selected-match__meta">
                <el-tag :type="selectedMatchItem.eligibilityStatus === 'PASS' ? 'success' : 'warning'">{{ selectedMatchItem.eligibilityStatus === 'PASS' ? '资格通过' : '资格待确认' }}</el-tag>
                <span>匹配分 {{ selectedMatchItem.matchScore ?? '待评估' }}</span>
              </div>
            </div>
            <el-alert v-if="recommendSubmitError" class="inline-alert" type="error" :title="errorMessage(recommendSubmitError)" :closable="false" show-icon />
            <el-button type="primary" :loading="recommendSubmitting" :disabled="recommendationBusy" @click="submitRecommendation">生成项目推荐</el-button>
          </el-form>
          <div v-if="recommendationTaskId" class="task-block">
            <el-skeleton v-if="recommendationTaskLoading && !recommendationTask" :rows="2" animated />
            <TaskStatus v-else :task="recommendationTask">
              <template #actions>
                <el-button v-if="recommendationTask?.status === 'FAILED'" size="small" :loading="recommendSubmitting" @click="retryRecommendationTask">按原条件重试</el-button>
              </template>
            </TaskStatus>
            <el-alert v-if="recommendationTaskError" type="warning" :closable="false" show-icon>
              <template #title>任务事件连接异常，业务任务未被取消。{{ errorMessage(recommendationTaskError) }}</template>
              <el-button size="small" @click="restartRecommendationTask">恢复任务状态</el-button>
            </el-alert>
          </div>
        </section>

        <section class="panel">
          <div class="panel__heading">
            <h2>推荐候选</h2>
            <el-button v-if="recommendation" :loading="recommendationLoading" @click="loadRecommendation(recommendation.id)">刷新推荐</el-button>
          </div>
          <AsyncState
            :loading="recommendationLoading"
            :error="recommendationError"
            :empty="recommendationEmpty"
            empty-text="尚未生成项目推荐"
            empty-description="设置条件并提交后，候选项目会显示在这里。"
            @retry="loadRecommendation()"
          >
            <template v-if="recommendation">
              <div class="result-meta">
                <el-tag :type="recommendation.status === 'READY' ? 'success' : recommendation.status === 'FAILED' ? 'danger' : 'primary'">
                  {{ commonStatusLabels[recommendation.status] || recommendation.status }}
                </el-tag>
                <span>生成时间：{{ formatDate(recommendation.createdAt) }}</span>
                <span>推荐 ID：{{ recommendation.id }}</span>
              </div>
              <el-alert class="ai-notice" type="info" :closable="false" show-icon title="以下内容为 AI 学习项目建议，不是你的既有项目经历，也不代表真实工作经验。" />
              <el-alert v-if="recommendation.status === 'FAILED'" class="inline-alert" type="error" title="推荐生成失败，请调整条件后重新提交。" :closable="false" show-icon />
              <el-result v-else-if="recommendation.status === 'GENERATING'" icon="info" title="推荐仍在生成中" sub-title="可稍后刷新推荐；任务恢复后会自动读取权威结果。">
                <template #extra><el-button :loading="recommendationLoading" @click="loadRecommendation(recommendation.id)">刷新推荐状态</el-button></template>
              </el-result>
              <el-empty v-else-if="!recommendation.items.length" description="本次没有返回候选项目，可调整条件后重试" />
              <div v-else class="recommendation-grid">
                <article v-for="item in recommendation.items" :key="item.id" class="recommendation-card">
                  <div class="recommendation-card__eyebrow">AI 建议 · 非既有经历</div>
                  <div class="recommendation-card__heading">
                    <h3>{{ item.title }}</h3>
                    <el-tag>{{ difficultyLabels[item.difficulty] }}</el-tag>
                  </div>
                  <p class="recommendation-card__summary">{{ item.summary }}</p>
                  <dl class="recommendation-facts">
                    <div>
                      <dt>技能</dt>
                      <dd class="tag-list"><el-tag v-for="code in item.skillCodes" :key="code" size="small" effect="plain">{{ code }}</el-tag></dd>
                    </div>
                    <div><dt>预计周期</dt><dd>{{ item.estimatedWeeks }} 周</dd></div>
                    <div><dt>推荐理由</dt><dd>{{ item.reason }}</dd></div>
                    <div>
                      <dt>预期交付物</dt>
                      <dd><ul class="deliverable-list"><li v-for="deliverable in item.deliverables" :key="deliverable">{{ deliverable }}</li></ul></dd>
                    </div>
                  </dl>
                  <el-button type="primary" plain :disabled="planBusy" @click="openPlanDialog(item)">选择此候选并生成计划</el-button>
                </article>
              </div>
            </template>
          </AsyncState>
        </section>
      </el-tab-pane>

      <el-tab-pane label="我的项目" name="projects">
        <section v-if="planTaskId || planSubmitError" class="panel">
          <div class="panel__heading"><h2>计划生成任务</h2></div>
          <el-alert v-if="planSubmitError" class="inline-alert" type="error" :title="errorMessage(planSubmitError)" :closable="false" show-icon />
          <el-skeleton v-if="planTaskLoading && !planTask" :rows="2" animated />
          <TaskStatus v-else :task="planTask">
            <template #actions>
              <el-button v-if="planTask?.status === 'FAILED'" size="small" :loading="planSubmitting" @click="retryPlanTask">按原参数重试</el-button>
            </template>
          </TaskStatus>
          <el-alert v-if="planTaskError" type="warning" :closable="false" show-icon>
            <template #title>任务事件连接异常，业务任务未被取消。{{ errorMessage(planTaskError) }}</template>
            <el-button size="small" @click="restartPlanTask">恢复任务状态</el-button>
          </el-alert>
        </section>

        <section class="panel">
          <div class="panel__heading">
            <h2>项目列表</h2>
            <el-button :loading="projectsLoading" @click="loadProjects">刷新列表</el-button>
          </div>
          <AsyncState :loading="projectsLoading" :error="projectsError" :empty="!projectPage?.records.length" empty-text="暂无项目" empty-description="从项目推荐中选择候选，生成你的实战计划。" @retry="loadProjects">
            <div class="project-list">
              <article v-for="item in projectPage?.records || []" :key="item.id" class="project-row" :class="{ 'project-row--active': project?.id === item.id }">
                <div class="project-row__main">
                  <div class="project-row__title">
                    <h3>{{ item.title }}</h3>
                    <el-tag size="small" :type="item.status === 'COMPLETED' ? 'success' : item.status === 'FAILED' ? 'danger' : 'primary'">{{ commonStatusLabels[item.status] || item.status }}</el-tag>
                  </div>
                  <p>{{ item.summary }}</p>
                  <div class="tag-list"><el-tag v-for="code in item.skillCodes" :key="code" size="small" effect="plain">{{ code }}</el-tag></div>
                  <small>每周 {{ item.weeklyHours }} 小时 · 创建于 {{ formatDate(item.createdAt) }}</small>
                </div>
                <div class="project-row__progress">
                  <strong>{{ normalizedProgress(item.progress) }}%</strong>
                  <el-progress :percentage="normalizedProgress(item.progress)" :show-text="false" />
                  <el-button type="primary" plain @click="openProject(item.id)">查看详情</el-button>
                </div>
              </article>
            </div>
            <el-pagination
              class="pagination"
              background
              layout="total, sizes, prev, pager, next"
              :total="projectPage?.total || 0"
              :current-page="projectPage?.current || projectQuery.current"
              :page-size="projectPage?.size || projectQuery.size"
              :page-sizes="[6, 12, 24]"
              @current-change="handleProjectPage"
              @size-change="handleProjectSize"
            />
          </AsyncState>
        </section>

        <section class="panel project-detail">
          <div class="panel__heading">
            <div>
              <h2>项目详情</h2>
              <p v-if="project" class="section-note">项目 ID：{{ project.id }} · 版本 {{ project.version }}</p>
            </div>
            <el-button v-if="project" :loading="projectLoading" @click="loadProject(project.id)">刷新详情</el-button>
          </div>
          <AsyncState :loading="projectLoading" :error="projectError" :empty="!project" empty-text="尚未选择项目" empty-description="从上方项目列表选择一个项目查看完整计划。" @retry="retryProject">
            <template v-if="project">
              <div class="detail-heading">
                <div>
                  <div class="detail-heading__title"><h3>{{ project.title }}</h3><el-tag>{{ commonStatusLabels[project.status] || project.status }}</el-tag></div>
                  <p>{{ project.summary }}</p>
                </div>
                <div class="detail-progress">
                  <strong>{{ normalizedProgress(project.progress) }}%</strong>
                  <span>服务端真实进度</span>
                </div>
              </div>
              <el-progress :percentage="normalizedProgress(project.progress)" :stroke-width="12" />
              <p class="section-note">已完成 {{ completedTaskCount }} / {{ projectTasks.length }} 项任务 · 每周投入 {{ project.weeklyHours }} 小时 · 创建于 {{ formatDate(project.createdAt) }}</p>
              <div class="tag-list detail-skills"><el-tag v-for="code in project.skillCodes" :key="code" effect="plain">{{ code }}</el-tag></div>
              <el-alert v-if="conflictMessage" class="inline-alert" type="warning" :title="conflictMessage" :closable="false" show-icon />
              <el-alert v-if="taskUpdateError" class="inline-alert" type="error" :title="errorMessage(taskUpdateError)" :closable="false" show-icon />

              <div class="section-title"><h3>里程碑与任务</h3><span>勾选表示完成，也可直接选择状态。</span></div>
              <el-empty v-if="!project.milestones.length" description="当前项目尚未返回里程碑" />
              <div v-else class="milestone-list">
                <section v-for="milestone in project.milestones" :key="milestone.id" class="milestone-card">
                  <div class="milestone-card__heading"><span>{{ milestone.seq }}</span><div><h4>{{ milestone.title }}</h4><p>{{ milestone.description }}</p></div></div>
                  <el-empty v-if="!milestone.tasks.length" :image-size="60" description="此里程碑暂无任务" />
                  <div v-else class="milestone-tasks">
                    <div v-for="task in milestone.tasks" :key="task.id" class="task-row">
                      <el-checkbox
                        :model-value="task.status === 'COMPLETED'"
                        :disabled="taskIsUpdating(task.id)"
                        @change="onTaskCheckbox(task, $event)"
                      >{{ task.title }}</el-checkbox>
                      <p>{{ task.description }}</p>
                      <div class="task-row__controls">
                        <span>预计 {{ task.estimatedHours }} 小时</span>
                        <el-tag size="small" :type="taskStatusTypes[task.status]">{{ commonStatusLabels[task.status] }}</el-tag>
                        <el-select
                          :model-value="task.status"
                          class="task-status-select"
                          size="small"
                          :disabled="taskIsUpdating(task.id)"
                          @change="setTaskStatus(task, $event)"
                        >
                          <el-option label="待开始" value="PENDING" />
                          <el-option label="进行中" value="IN_PROGRESS" />
                          <el-option label="已完成" value="COMPLETED" />
                        </el-select>
                      </div>
                    </div>
                  </div>
                </section>
              </div>

              <div class="deliverable-layout">
                <section class="deliverable-form">
                  <div class="section-title"><h3>添加交付记录</h3></div>
                  <p class="section-note">仅保存你提交的交付信息，不表示系统已自动验收链接或内容。</p>
                  <el-form ref="deliverableFormRef" :model="deliverableForm" :rules="deliverableRules" label-position="top">
                    <el-form-item label="关联任务" prop="taskId">
                      <el-select v-model="deliverableForm.taskId" class="full-width" placeholder="选择项目任务">
                        <el-option v-for="task in projectTasks" :key="task.id" :label="task.title" :value="task.id" />
                      </el-select>
                    </el-form-item>
                    <el-form-item label="交付标题" prop="title">
                      <el-input v-model="deliverableForm.title" maxlength="200" show-word-limit placeholder="例如：项目 README 与核心功能实现" />
                    </el-form-item>
                    <el-form-item label="交付链接（可选，http/https）" prop="url">
                      <el-input v-model="deliverableForm.url" placeholder="https://example.com/repository" clearable />
                    </el-form-item>
                    <el-form-item label="交付说明（可选）" prop="notes">
                      <el-input v-model="deliverableForm.notes" type="textarea" :rows="4" maxlength="2000" show-word-limit placeholder="记录实现范围、验证方式或待改进事项" />
                    </el-form-item>
                    <el-alert v-if="deliverableError" class="inline-alert" type="error" :title="errorMessage(deliverableError)" :closable="false" show-icon />
                    <el-button type="primary" :loading="deliverableSubmitting" :disabled="!projectTasks.length" @click="submitDeliverable">保存交付记录</el-button>
                  </el-form>
                </section>
                <section class="deliverable-history">
                  <div class="section-title"><h3>历史交付记录</h3><span>{{ project.deliverables.length }} 条</span></div>
                  <el-empty v-if="!project.deliverables.length" :image-size="70" description="暂无交付记录" />
                  <div v-else class="deliverable-history__list">
                    <article v-for="item in project.deliverables" :key="item.id" class="deliverable-record">
                      <h4>{{ item.title }}</h4>
                      <p class="deliverable-record__task">关联任务：{{ taskTitle(item.taskId) }}</p>
                      <a v-if="safeUrl(item.url)" :href="safeUrl(item.url)" target="_blank" rel="noopener noreferrer">{{ item.url }}</a>
                      <p v-if="item.notes" class="pre-wrap">{{ item.notes }}</p>
                      <small>保存于 {{ formatDate(item.createdAt) }} · 仅记录，不代表已验收</small>
                    </article>
                  </div>
                </section>
              </div>
            </template>
          </AsyncState>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="planDialogVisible" title="生成项目实战计划" width="min(520px, 92vw)" :close-on-click-modal="!planSubmitting">
      <template v-if="selectedItem">
        <div class="selected-candidate">
          <span>已选 AI 建议</span>
          <h3>{{ selectedItem.title }}</h3>
          <p>{{ selectedItem.summary }}</p>
          <small>{{ difficultyLabels[selectedItem.difficulty] }} · 预计 {{ selectedItem.estimatedWeeks }} 周 · 此项不代表既有经历</small>
        </div>
        <el-form ref="planFormRef" :model="planForm" :rules="planRules" label-position="top">
          <el-form-item label="每周可投入时长（小时）" prop="weeklyHours">
            <el-input-number v-model="planForm.weeklyHours" class="full-width" :min="1" :max="40" :step="1" step-strictly />
          </el-form-item>
          <el-alert v-if="planSubmitError" class="inline-alert" type="error" :title="errorMessage(planSubmitError)" :closable="false" show-icon />
        </el-form>
      </template>
      <template #footer>
        <el-button :disabled="planSubmitting" @click="planDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="planSubmitting" @click="submitPlan">提交计划生成任务</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.project-page { padding-bottom: 24px; }
.project-tabs :deep(.el-tabs__item) { font-size: 15px; }
.section-note { margin: 6px 0 0; color: $ai-text-secondary; line-height: 1.6; }
.field-error { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; width: 100%; color: $ai-danger; font-size: 12px; line-height: 1.5; }
.field-hint { width: 100%; color: $ai-text-secondary; font-size: 12px; line-height: 1.6; }
.match-option { display: flex; flex-direction: column; gap: 3px; padding: 3px 0; }
.match-option strong { color: #101d4b; font-weight: 600; }
.match-option span { color: $ai-text-secondary; font-size: 12px; }
.selected-match { display: flex; justify-content: space-between; gap: 16px; margin: 0 0 14px; padding: 13px 15px; border: 1px solid #dbe8fb; border-radius: 9px; background: $ai-primary-light; }
.selected-match__eyebrow { display: block; margin-bottom: 5px; color: $ai-primary; font-size: 12px; font-weight: 700; }
.selected-match strong { color: #101d4b; }
.selected-match p { margin: 5px 0 0; color: $ai-text-secondary; line-height: 1.6; }
.selected-match__reason { font-size: 12px; }
.selected-match__meta { display: flex; flex: 0 0 auto; flex-direction: column; align-items: flex-end; gap: 7px; color: $ai-text-secondary; font-size: 12px; }
.inline-alert { margin: 0 0 14px; }
.task-block { display: grid; gap: 10px; margin-top: 18px; }
.task-block .el-alert { align-items: flex-start; }
.result-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 10px 16px; margin-bottom: 14px; color: $ai-text-secondary; font-size: 12px; overflow-wrap: anywhere; }
.ai-notice { margin-bottom: 16px; }
.recommendation-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.recommendation-card { display: flex; flex-direction: column; min-width: 0; padding: 18px; border: 1px solid #dfe9f7; border-radius: 12px; background: linear-gradient(145deg, #fff 0%, #f7faff 100%); }
.recommendation-card__eyebrow { margin-bottom: 8px; color: $ai-primary; font-size: 12px; font-weight: 700; letter-spacing: .06em; }
.recommendation-card__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.recommendation-card h3 { margin: 0; color: #101d4b; font-size: 19px; line-height: 1.4; }
.recommendation-card__summary { min-height: 48px; margin: 12px 0 16px; color: $ai-text-secondary; line-height: 1.7; }
.recommendation-facts { display: grid; gap: 12px; margin: 0 0 18px; }
.recommendation-facts > div { display: grid; grid-template-columns: 76px minmax(0, 1fr); gap: 10px; }
.recommendation-facts dt { color: $ai-text-secondary; }
.recommendation-facts dd { margin: 0; line-height: 1.65; overflow-wrap: anywhere; }
.recommendation-card > .el-button { align-self: flex-start; margin-top: auto; }
.tag-list { display: flex; flex-wrap: wrap; gap: 6px; }
.deliverable-list { margin: 0; padding-left: 18px; }
.project-list { display: grid; gap: 10px; }
.project-row { display: grid; grid-template-columns: minmax(0, 1fr) 180px; gap: 20px; padding: 16px; border: 1px solid #e4ecf8; border-radius: 10px; transition: border-color .2s, background .2s; }
.project-row--active { border-color: $ai-primary; background: $ai-primary-light; }
.project-row__title { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.project-row h3 { margin: 0; color: #101d4b; font-size: 17px; }
.project-row p { margin: 8px 0 12px; color: $ai-text-secondary; line-height: 1.6; }
.project-row small { display: block; margin-top: 10px; color: $ai-text-tertiary; }
.project-row__progress { display: flex; flex-direction: column; justify-content: center; gap: 9px; }
.project-row__progress strong { color: #101d4b; font-size: 22px; }
.detail-heading { display: flex; justify-content: space-between; gap: 18px; margin-bottom: 16px; }
.detail-heading__title { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.detail-heading h3 { margin: 0; color: #101d4b; font-size: 22px; }
.detail-heading p { margin: 9px 0 0; color: $ai-text-secondary; line-height: 1.7; }
.detail-progress { display: flex; flex: 0 0 auto; flex-direction: column; align-items: flex-end; }
.detail-progress strong { color: $ai-primary; font-size: 28px; }
.detail-progress span { color: $ai-text-secondary; font-size: 12px; }
.detail-skills { margin: 12px 0 18px; }
.section-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 20px 0 12px; }
.section-title h3 { margin: 0; color: #101d4b; font-size: 17px; }
.section-title span { color: $ai-text-secondary; font-size: 12px; }
.milestone-list { display: grid; gap: 12px; }
.milestone-card { padding: 16px; border: 1px solid #e3ebf8; border-radius: 10px; background: #fbfdff; }
.milestone-card__heading { display: flex; gap: 12px; margin-bottom: 14px; }
.milestone-card__heading > span { display: grid; flex: 0 0 30px; height: 30px; place-items: center; border-radius: 50%; background: $ai-primary; color: #fff; font-weight: 700; }
.milestone-card h4 { margin: 2px 0 5px; color: #101d4b; font-size: 16px; }
.milestone-card__heading p { margin: 0; color: $ai-text-secondary; line-height: 1.6; }
.milestone-tasks { display: grid; gap: 8px; }
.task-row { display: grid; grid-template-columns: minmax(180px, .8fr) minmax(220px, 1.4fr) auto; align-items: center; gap: 12px; padding: 12px; border-radius: 8px; background: #fff; }
.task-row > p { margin: 0; color: $ai-text-secondary; line-height: 1.55; }
.task-row__controls { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 8px; color: $ai-text-secondary; font-size: 12px; }
.task-status-select { width: 112px; }
.deliverable-layout { display: grid; grid-template-columns: minmax(0, .9fr) minmax(0, 1.1fr); gap: 24px; margin-top: 24px; padding-top: 4px; border-top: 1px solid #e8eef8; }
.deliverable-history__list { display: grid; gap: 10px; max-height: 620px; overflow-y: auto; }
.deliverable-record { min-width: 0; padding: 14px; border: 1px solid #e5edf8; border-radius: 9px; }
.deliverable-record h4 { margin: 0 0 7px; color: #101d4b; }
.deliverable-record p { margin: 7px 0; line-height: 1.65; }
.deliverable-record__task { color: $ai-text-secondary; }
.deliverable-record a { display: block; margin: 7px 0; color: $ai-primary; overflow-wrap: anywhere; }
.deliverable-record small { color: $ai-text-tertiary; }
.selected-candidate { margin-bottom: 18px; padding: 14px; border-radius: 9px; background: $ai-bg-page; }
.selected-candidate > span { color: $ai-primary; font-size: 12px; font-weight: 700; }
.selected-candidate h3 { margin: 7px 0; color: #101d4b; }
.selected-candidate p { margin: 0 0 7px; color: $ai-text-secondary; line-height: 1.6; }
.selected-candidate small { color: $ai-text-tertiary; }
@media (max-width: 1050px) {
  .recommendation-grid, .deliverable-layout { grid-template-columns: 1fr; }
  .task-row { grid-template-columns: 1fr; }
  .task-row__controls { justify-content: flex-start; }
}
@media (max-width: 700px) {
  .project-row { grid-template-columns: 1fr; }
  .project-row__progress { align-items: flex-start; }
  .project-row__progress .el-progress { width: 100%; }
  .detail-heading { flex-direction: column; }
  .detail-progress { align-items: flex-start; }
  .recommendation-facts > div { grid-template-columns: 1fr; gap: 4px; }
  .section-title { align-items: flex-start; flex-direction: column; }
}
</style>