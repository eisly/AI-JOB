<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ArrowLeft, ArrowRight, Aim, Box, Check, Clock, Collection, Document, EditPen, FolderOpened, InfoFilled, List, MagicStick, Medal, Refresh, Setting, Star, TrendCharts, VideoPlay } from '@element-plus/icons-vue'
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
const routeTab = computed<'recommendations' | 'projects'>(() => {
  const { tab, recommendationId } = route.query
  if (tab === 'recommendations' || tab === 'projects') return tab
  return recommendationId ? 'recommendations' : 'projects'
})
const activeTab = ref(routeTab.value)
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
const recommendationDialogVisible = ref(false)
const projectPickerVisible = ref(false)
const deliverableDialogVisible = ref(false)
const selectedCandidateId = ref(queryString(route.query.candidateId))
const focusedTaskId = ref('')
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
const taskStatusLabels: Record<ProjectTaskVO['status'], string> = {
  PENDING: '未开始',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
}
const localDate = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(new Date())
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
  reasoningText: recommendationReasoning,
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
  reasoningText: planReasoning,
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
const sortedMilestones = computed(() => [...(project.value?.milestones || [])].sort((a, b) => a.seq - b.seq))
const projectTasks = computed(() => sortedMilestones.value.flatMap(milestone => milestone.tasks))
const candidate = computed(() => recommendation.value?.status === 'READY'
  ? recommendation.value.items.find(item => item.id === selectedCandidateId.value) || recommendation.value.items[0] || null
  : null)
const totalEstimatedHours = computed(() => projectTasks.value.reduce((total, task) => total + task.estimatedHours, 0))
const nextTask = computed(() => projectTasks.value.find(task => task.status === 'IN_PROGRESS') || projectTasks.value.find(task => task.status === 'PENDING'))
const tasksUpdating = computed(() => updatingTaskIds.value.size > 0)
const projectEditable = computed(() => project.value?.status === 'ACTIVE' || project.value?.status === 'COMPLETED')
const skillNames = reactive<Record<string, string>>({})

function skillName(code: string) {
  return skillNames[code] || code
}

function selectCandidate(item: ProjectRecommendationItem) {
  selectedCandidateId.value = item.id
  void replaceQuery({ candidateId: item.id })
}

function switchPage(name: 'recommendations' | 'projects') {
  activeTab.value = name
  void replaceQuery({ tab: name })
}

function openRecommendationSettings() {
  recommendSubmitError.value = null
  recommendationDialogVisible.value = true
}

function openDeliverableDialog(task?: ProjectTaskVO) {
  if (task) deliverableForm.taskId = task.id
  deliverableError.value = null
  deliverableDialogVisible.value = true
}

async function startNextTask() {
  const task = nextTask.value
  if (!task || tasksUpdating.value || !projectEditable.value) return
  focusedTaskId.value = task.id
  if (task.status === 'PENDING') await setTaskStatus(task, 'IN_PROGRESS')
  await nextTick()
  document.getElementById('project-task-' + task.id)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}
const completedTaskCount = computed(() => projectTasks.value.filter(task => task.status === 'COMPLETED').length)

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

async function loadSkills(query = '') {
  skillsLoading.value = true
  skillsError.value = null
  try {
    const result = await profileApi.skills(query || undefined)
    skills.value = result.filter(skill => skill.enabled)
    result.forEach(skill => { skillNames[skill.code] = skill.name })
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
  void router.push({ path: '/jobs', query: { tab: 'matches' } })
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
    selectedCandidateId.value = ''
    recommendationLoadingId.value = ''
    recommendationLoading.value = false
    recommendationError.value = null
    recommendationDialogVisible.value = false
    activeTab.value = 'recommendations'
    await replaceQuery({ tab: 'recommendations', recommendationId: undefined, candidateId: undefined })
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
    selectedCandidateId.value = ''
    recommendationLoadingId.value = ''
    recommendationLoading.value = false
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
    const result = await projectApi.getRecommendation(target)
    if (recommendationLoadingId.value !== target) return false
    recommendation.value = result
    if (!result.items.some(item => item.id === selectedCandidateId.value)) selectedCandidateId.value = result.items[0]?.id || ''
    return true
  } catch (reason) {
    if (recommendationLoadingId.value !== target) return false
    recommendationError.value = reason
    recommendation.value = null
    return false
  } finally {
    if (recommendationLoadingId.value === target) recommendationLoading.value = false
  }
}

function openPlanDialog(item: ProjectRecommendationItem) {
  if (planBusy.value || recommendation.value?.status !== 'READY') return
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
    project.value = null
    projectLoadingId.value = ''
    projectLoading.value = false
    projectError.value = null
    activeTab.value = 'projects'
    await replaceQuery({ tab: 'projects', projectId: undefined })
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
  handleProjectPage(1)
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
  if (project.value?.id !== target) {
    project.value = null
    focusedTaskId.value = ''
    deliverableForm.taskId = ''
    deliverableForm.title = ''
    deliverableForm.url = ''
    deliverableForm.notes = ''
    taskUpdateError.value = null
    conflictMessage.value = ''
  }
  projectLoading.value = true
  projectLoadingId.value = target
  projectError.value = null
  try {
    const result = await projectApi.get(target)
    if (projectLoadingId.value !== target) return false
    applyProject(result)
    return true
  } catch (reason) {
    if (projectLoadingId.value !== target) return false
    projectError.value = reason
    return false
  } finally {
    if (projectLoadingId.value === target) projectLoading.value = false
  }
}

async function openProject(projectId: string) {
  activeTab.value = 'projects'
  projectPickerVisible.value = false
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
  if (!project.value || !projectEditable.value || tasksUpdating.value || !['PENDING', 'IN_PROGRESS', 'COMPLETED'].includes(status) || task.status === status) return
  const projectId = project.value.id
  const nextStatus = status as ProjectTaskVO['status']
  const nextUpdating = new Set(updatingTaskIds.value)
  nextUpdating.add(task.id)
  updatingTaskIds.value = nextUpdating
  taskUpdateError.value = null
  conflictMessage.value = ''
  try {
    const result = await projectApi.updateTask(projectId, task.id, { status: nextStatus, expectedVersion: project.value.version })
    if (project.value?.id === projectId) applyProject(result)
    ElMessage.success('任务状态已更新')
  } catch (reason) {
    if (project.value?.id !== projectId) return
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
    const projectId = project.value.id
    const result = await projectApi.addDeliverable(projectId, body)
    if (project.value?.id === projectId) applyProject(result)
    deliverableForm.title = ''
    deliverableForm.url = ''
    deliverableForm.notes = ''
    deliverableFormRef.value?.clearValidate()
    deliverableDialogVisible.value = false
    ElMessage.success('交付记录已保存')
  } catch (reason) {
    deliverableError.value = reason
  } finally {
    deliverableSubmitting.value = false
  }
}

watch(routeTab, value => { activeTab.value = value })
watch(() => route.query.candidateId, value => { selectedCandidateId.value = queryString(value) })
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
    <header class="practice-header">
      <div>
        <p class="practice-date">{{ localDate }}</p>
        <div class="practice-title">
          <h1>{{ activeTab === 'recommendations' ? '项目实战推荐' : '项目实战计划' }}</h1>
          <span class="page-badge">{{ activeTab === 'recommendations' ? '探索项目' : '实践成长' }}</span>
        </div>
        <p class="practice-description">{{ activeTab === 'recommendations' ? '精选贴合求职方向的项目进行实战练习，帮助你在真实场景中提升项目经验与技术能力。' : '通过完整的项目实战，提升工程能力，积累可展示的项目经验，为求职做好准备。' }}</p>
      </div>
      <nav class="page-switch" aria-label="项目实战页面">
        <button type="button" :class="{ active: activeTab === 'recommendations' }" :aria-current="activeTab === 'recommendations' ? 'page' : undefined" @click="switchPage('recommendations')">项目推荐</button>
        <button type="button" :class="{ active: activeTab === 'projects' }" :aria-current="activeTab === 'projects' ? 'page' : undefined" @click="switchPage('projects')">我的计划<span v-if="projectPage?.total" class="nav-count">{{ projectPage.total }}</span></button>
      </nav>
    </header>

    <template v-if="activeTab === 'recommendations'">
      <div class="practice-toolbar">
        <span class="toolbar-tip"><el-icon><InfoFilled /></el-icon>一次推荐 1～6 个候选，选择一个后生成专属实战计划</span>
        <div class="toolbar-actions">
          <el-button v-if="recommendation" text :icon="Refresh" :loading="recommendationLoading" @click="loadRecommendation()">刷新</el-button>
          <el-button :icon="Setting" :disabled="recommendationBusy" @click="openRecommendationSettings">{{ recommendation ? '调整推荐条件' : '推荐设置' }}</el-button>
          <el-button v-if="!recommendation" type="primary" :icon="MagicStick" :disabled="recommendationBusy" :loading="recommendationBusy" @click="openRecommendationSettings">生成项目推荐</el-button>
        </div>
      </div>
      <el-alert v-if="recommendSubmitError && !recommendationDialogVisible" class="inline-alert" type="error" :title="errorMessage(recommendSubmitError)" :closable="false" show-icon />
      <section v-if="recommendationTaskId && (recommendationBusy || recommendationTaskError || recommendationTask?.status !== 'SUCCEEDED')" class="surface task-status-panel" aria-live="polite">
        <el-skeleton v-if="recommendationTaskLoading && !recommendationTask" :rows="2" animated />
        <TaskStatus v-else :task="recommendationTask" :reasoning="recommendationReasoning">
          <template #actions>
            <el-button v-if="recommendationTask?.status === 'FAILED'" size="small" :loading="recommendSubmitting" @click="retryRecommendationTask">按原条件重试</el-button>
          </template>
        </TaskStatus>
        <el-alert v-if="recommendationTaskError" type="warning" :closable="false" show-icon>
          <template #title>推荐状态更新中断，生成任务仍会继续。{{ errorMessage(recommendationTaskError) }}</template>
          <el-button size="small" @click="restartRecommendationTask">恢复任务状态</el-button>
        </el-alert>
      </section>

      <AsyncState :loading="recommendationLoading" :error="recommendationError" @retry="loadRecommendation()">
        <div class="recommendation-layout">
          <section class="surface candidate-panel">
            <div class="section-heading">
              <h2>推荐项目<span class="heading-count">（{{ recommendation?.status === 'READY' ? recommendation.items.length : 0 }}）</span></h2>
              <el-tooltip content="点击候选查看详情；确认后可为所选项目生成独立计划。" placement="top"><el-icon class="help-icon" aria-label="候选项目说明" tabindex="0"><InfoFilled /></el-icon></el-tooltip>
            </div>
            <div v-if="recommendation?.status === 'READY' && recommendation.items.length" class="candidate-list" role="group" aria-label="推荐候选项目">
              <button v-for="(item, index) in recommendation.items" :key="item.id" type="button" class="candidate-card" :class="{ 'candidate-card--selected': candidate?.id === item.id }" :aria-pressed="candidate?.id === item.id" @click="selectCandidate(item)">
                <span class="project-icon" :class="'project-icon--' + index % 3"><el-icon><component :is="[Box, Aim, Collection][index % 3]" /></el-icon></span>
                <span class="candidate-card__body">
                  <span class="candidate-card__title">{{ item.title }}<el-icon><ArrowRight /></el-icon></span>
                  <span class="candidate-card__summary">{{ item.summary }}</span>
                  <span class="skill-tags"><span v-for="code in item.skillCodes" :key="code">{{ skillName(code) }}</span></span>
                  <span class="candidate-card__meta">{{ difficultyLabels[item.difficulty] }}<span>·</span>预计 {{ item.estimatedWeeks }} 周</span>
                  <span class="candidate-card__reason"><strong>推荐理由</strong><span>{{ item.reason }}</span></span>
                </span>
              </button>
            </div>
            <div v-else class="candidate-placeholder">
              <el-icon><Collection /></el-icon>
              <strong>{{ recommendationBusy || recommendation?.status === 'GENERATING' ? '正在寻找适合你的项目' : '你的实战，从一个项目开始' }}</strong>
              <p>候选项目将在这里展示，点击即可比较项目方向与技能。</p>
            </div>
            <p v-if="recommendation?.status === 'READY'" class="candidate-footer">生成于 {{ formatDate(recommendation.createdAt) }}</p>
          </section>

          <article v-if="candidate" class="surface candidate-detail" aria-live="polite">
            <div class="candidate-detail__hero">
              <span class="project-icon project-icon--large"><el-icon><Box /></el-icon></span>
              <div>
                <span class="eyebrow">{{ candidate.reason.startsWith('本地规则建议 ·') ? '本地规则建议' : '为你推荐的实战项目' }}</span>
                <h2>{{ candidate.title }}</h2>
                <p>{{ candidate.summary }}</p>
                <div class="skill-tags"><span v-for="code in candidate.skillCodes" :key="code">{{ skillName(code) }}</span></div>
              </div>
            </div>
            <div class="candidate-facts">
              <div><el-icon><Aim /></el-icon><span>项目形式<strong>实战练习</strong></span></div>
              <div><el-icon><TrendCharts /></el-icon><span>难度等级<strong>{{ difficultyLabels[candidate.difficulty] }}</strong></span></div>
              <div><el-icon><Clock /></el-icon><span>预计周期<strong>{{ candidate.estimatedWeeks }} 周</strong></span></div>
              <div><el-icon><Collection /></el-icon><span>涉及技能<strong>{{ candidate.skillCodes.length }} 项</strong></span></div>
            </div>
            <section class="detail-section">
              <h3><el-icon><Aim /></el-icon>实战目标与交付</h3>
              <ol v-if="candidate.deliverables.length" class="goal-list">
                <li v-for="(deliverable, index) in candidate.deliverables" :key="index"><span>{{ index + 1 }}</span><p>{{ deliverable }}</p></li>
              </ol>
              <p v-else class="muted">具体任务与交付要求将在计划生成后展示。</p>
            </section>
            <section class="detail-section">
              <h3><el-icon><TrendCharts /></el-icon>核心技能提升</h3>
              <div v-if="candidate.skillCodes.length" class="skill-cards">
                <div v-for="code in candidate.skillCodes" :key="code" class="skill-card"><span class="skill-monogram">{{ skillName(code).slice(0, 2) }}</span><div><strong>{{ skillName(code) }}</strong><small>在项目任务中练习与应用</small></div></div>
              </div>
              <p v-else class="muted">具体技能以实战计划为准。</p>
            </section>
            <section class="detail-section">
              <h3><el-icon><Star /></el-icon>推荐理由</h3>
              <p class="reason-callout">{{ candidate.reason }}</p>
            </section>
            <footer class="candidate-detail__footer">
              <p><el-icon><InfoFilled /></el-icon>项目建议用于学习实践，请以实际完成的成果记录经历。</p>
              <el-button type="primary" size="large" :icon="VideoPlay" :disabled="planBusy" @click="openPlanDialog(candidate)">选择此项目，生成计划<el-icon class="trailing-icon"><ArrowRight /></el-icon></el-button>
            </footer>
          </article>
          <section v-else class="surface recommendation-empty">
            <template v-if="recommendationBusy || recommendation?.status === 'GENERATING'">
              <span class="empty-illustration"><el-icon><MagicStick /></el-icon></span>
              <h2>正在为你准备实战项目</h2><p>结合你的求职方向与目标技能，整理适合的项目建议。</p>
              <el-button v-if="recommendation" :icon="Refresh" @click="loadRecommendation()">刷新推荐状态</el-button>
            </template>
            <template v-else>
              <span class="empty-illustration"><el-icon><Box /></el-icon></span>
              <span class="eyebrow">从技能到实践</span>
              <h2>{{ recommendation?.status === 'FAILED' ? '本次推荐生成失败' : recommendation?.status === 'READY' ? '本次暂无候选项目' : '找到适合你的下一个项目' }}</h2>
              <p>设置目标技能、项目难度和练习周期，<br>探索项目方向，再把一个想法变成可执行的实战计划。</p>
              <el-button type="primary" size="large" :icon="MagicStick" @click="openRecommendationSettings">{{ recommendation ? '调整条件，重新推荐' : '获取项目推荐' }}</el-button>
              <div class="empty-steps"><span>01 发现项目</span><el-icon><ArrowRight /></el-icon><span>02 制定计划</span><el-icon><ArrowRight /></el-icon><span>03 完成实战</span></div>
            </template>
          </section>
        </div>
      </AsyncState>
    </template>

    <template v-else>
      <div class="practice-toolbar">
        <el-button text :icon="ArrowLeft" @click="switchPage('recommendations')">返回项目推荐</el-button>
        <div class="toolbar-actions">
          <el-button v-if="project" :icon="Refresh" :loading="projectLoading" @click="loadProject()">刷新计划</el-button>
          <el-button :icon="FolderOpened" @click="projectPickerVisible = true">切换实战计划<span v-if="projectPage?.total">（{{ projectPage.total }}）</span></el-button>
        </div>
      </div>
      <section v-if="planTaskId && (planBusy || planTaskError || planTask?.status !== 'SUCCEEDED') || planSubmitError" class="surface task-status-panel" aria-live="polite">
        <el-alert v-if="planSubmitError" class="inline-alert" type="error" :title="errorMessage(planSubmitError)" :closable="false" show-icon />
        <el-skeleton v-if="planTaskLoading && !planTask" :rows="2" animated />
        <TaskStatus v-else :task="planTask" :reasoning="planReasoning">
          <template #actions><el-button v-if="planTask?.status === 'FAILED'" size="small" :loading="planSubmitting" @click="retryPlanTask">按原参数重试</el-button></template>
        </TaskStatus>
        <el-alert v-if="planTaskError" type="warning" :closable="false" show-icon>
          <template #title>计划状态更新中断，生成任务仍会继续。{{ errorMessage(planTaskError) }}</template>
          <el-button size="small" @click="restartPlanTask">恢复任务状态</el-button>
        </el-alert>
      </section>

      <AsyncState :loading="projectLoading" :error="projectError" @retry="retryProject">
        <div v-if="project" class="plan-layout">
          <article class="surface plan-main">
            <div class="plan-hero">
              <span class="project-icon project-icon--large"><el-icon><Box /></el-icon></span>
              <div class="plan-hero__body"><div class="plan-hero__title"><h2>{{ project.title || '项目实战计划' }}</h2><el-tag size="small" :type="project.status === 'COMPLETED' ? 'success' : project.status === 'FAILED' ? 'danger' : 'primary'">{{ commonStatusLabels[project.status] || project.status }}</el-tag></div><p><el-icon><Collection /></el-icon>{{ project.skillCodes.map(skillName).join(' · ') || '项目技能待生成' }}</p></div>
              <div class="estimated-time"><span><el-icon><Clock /></el-icon>预计总时长</span><strong>{{ totalEstimatedHours || '待估算' }}<small v-if="totalEstimatedHours"> 小时</small></strong><span>每周投入 {{ project.weeklyHours }} 小时</span></div>
            </div>
            <section class="project-intro"><h3>项目简介</h3><p>{{ project.summary || '项目计划生成后，将展示项目简介与具体任务。' }}</p></section>
            <p class="info-callout"><el-icon><InfoFilled /></el-icon>本项目用于学习和能力提升，请根据实际完成情况记录进度与成果。</p>
            <div class="section-heading task-plan-heading"><h3>任务计划</h3><span>已完成 <strong>{{ completedTaskCount }}</strong> / {{ projectTasks.length }} 项</span></div>
            <el-progress class="plan-progress" :percentage="normalizedProgress(project.progress)" :stroke-width="7" />
            <p class="progress-note">进度按已完成任务数计算，勾选后自动保存。</p>
            <el-alert v-if="conflictMessage" class="inline-alert" type="warning" :title="conflictMessage" :closable="false" show-icon />
            <el-alert v-if="taskUpdateError" class="inline-alert" type="error" :title="errorMessage(taskUpdateError)" :closable="false" show-icon />
            <el-result v-if="project.status === 'GENERATING'" icon="info" title="实战计划正在生成" sub-title="任务准备好后即可开始练习。"><template #extra><el-button :loading="projectLoading" @click="loadProject()">刷新计划</el-button></template></el-result>
            <el-result v-else-if="project.status === 'FAILED'" icon="warning" title="计划生成失败" sub-title="可以返回推荐页重新选择候选项目生成计划。" />
            <el-empty v-else-if="!project.milestones.length" description="当前项目尚未返回任务计划" />
            <div v-else class="milestone-list">
              <section v-for="milestone in sortedMilestones" :key="milestone.id" class="milestone-section">
                <div class="milestone-heading"><span>阶段 {{ milestone.seq }}</span><h4>{{ milestone.title }}</h4></div>
                <p v-if="milestone.description" class="milestone-description">{{ milestone.description }}</p>
                <p v-if="!milestone.tasks.length" class="muted">此阶段暂无任务。</p>
                <ol v-else class="task-timeline">
                  <li v-for="task in milestone.tasks" :id="'project-task-' + task.id" :key="task.id" class="timeline-task" :class="{ 'timeline-task--completed': task.status === 'COMPLETED', 'timeline-task--current': focusedTaskId === task.id || task.status === 'IN_PROGRESS' }">
                    <span class="task-number"><el-icon v-if="task.status === 'COMPLETED'"><Check /></el-icon><template v-else>{{ projectTasks.indexOf(task) + 1 }}</template></span>
                    <div class="timeline-task__content">
                      <div class="timeline-task__heading"><h4>{{ task.title }}</h4><div class="task-check"><span :class="'status-' + task.status.toLowerCase()">{{ taskStatusLabels[task.status] }}</span><el-checkbox :model-value="task.status === 'COMPLETED'" :disabled="tasksUpdating || !projectEditable" :aria-label="task.title + '：标记完成'" @change="onTaskCheckbox(task, $event)" /></div></div>
                      <p class="task-duration"><el-icon><Clock /></el-icon>预计 {{ task.estimatedHours }} 小时</p>
                      <p class="task-description">{{ task.description }}</p>
                      <div class="task-controls">
                        <el-select :model-value="task.status" size="small" class="task-status-select" :aria-label="task.title + '的任务状态'" :disabled="tasksUpdating || !projectEditable" :loading="taskIsUpdating(task.id)" @change="setTaskStatus(task, $event)">
                          <el-option label="未开始" value="PENDING" /><el-option label="进行中" value="IN_PROGRESS" /><el-option label="已完成" value="COMPLETED" />
                        </el-select>
                        <el-button link type="primary" :icon="EditPen" :disabled="!projectEditable" @click="openDeliverableDialog(task)">记录交付</el-button>
                      </div>
                    </div>
                  </li>
                </ol>
              </section>
            </div>
          </article>

          <aside class="surface evidence-panel">
            <h2 class="evidence-heading"><span><el-icon><Document /></el-icon></span>能力证据</h2>
            <div class="evidence-callout"><h3>{{ project.status === 'COMPLETED' ? '把实战成果整理成项目证明' : '完成任务后可整理项目证明' }}</h3><p>完成项目任务后，你可以将实际成果与项目经历整理到简历中，展示你的工程实践能力。</p></div>
            <section class="evidence-skills"><h3>涉及的核心技能</h3><div class="evidence-skill-tags"><span v-for="(code, index) in project.skillCodes" :key="code" :class="'skill-color-' + index % 3">{{ skillName(code) }}</span><span v-if="!project.skillCodes.length">待生成</span></div></section>
            <div class="learning-advice"><el-icon><Medal /></el-icon><div><h3>学习建议</h3><p>建议按照任务顺序逐步完成，每个任务完成后，记录实现过程、验证结果和学习心得。</p></div></div>
            <el-button class="start-task-button" type="primary" size="large" :icon="VideoPlay" :loading="tasksUpdating" :disabled="!nextTask || !projectEditable" @click="startNextTask">{{ !projectTasks.length ? '等待任务生成' : !nextTask ? '任务已全部完成' : nextTask.status === 'IN_PROGRESS' ? '继续当前任务' : completedTaskCount ? '开始下一个任务' : '开始第一个任务' }}<el-icon class="trailing-icon"><ArrowRight /></el-icon></el-button>
            <section class="evidence-records">
              <div class="section-heading"><h3>交付记录<span class="heading-count">（{{ project.deliverables.length }}）</span></h3><el-button link type="primary" :disabled="!projectTasks.length || !projectEditable" @click="openDeliverableDialog()">添加记录</el-button></div>
              <p v-if="!project.deliverables.length" class="no-records">记录代码仓库、演示链接或实践心得，让每一步成长都有迹可循。</p>
              <div v-else class="deliverable-history">
                <article v-for="item in project.deliverables" :key="item.id" class="deliverable-record"><h4>{{ item.title }}</h4><p class="deliverable-record__task">{{ taskTitle(item.taskId) }}</p><a v-if="safeUrl(item.url)" :href="safeUrl(item.url)" target="_blank" rel="noopener noreferrer">查看交付链接<el-icon><ArrowRight /></el-icon></a><p v-if="item.notes" class="pre-wrap">{{ item.notes }}</p><small>{{ formatDate(item.createdAt) }}</small></article>
              </div>
              <p class="evidence-note"><el-icon><InfoFilled /></el-icon>任务进度与交付由你记录，不代表成果已通过验收，也不产生技能评分。</p>
            </section>
          </aside>
        </div>
        <section v-else-if="planBusy" class="surface plan-waiting"><span class="empty-illustration"><el-icon><List /></el-icon></span><h2>正在生成你的独立实战计划</h2><p>按选定的候选项目和每周投入时长，拆解阶段与任务。</p></section>
        <section v-else class="surface project-index">
          <div class="section-heading"><h2>我的实战计划</h2><el-button :icon="Refresh" :loading="projectsLoading" @click="loadProjects()">刷新列表</el-button></div>
          <AsyncState :loading="projectsLoading" :error="projectsError" :empty="!projectPage?.records.length" empty-text="还没有实战计划" empty-description="从推荐中选定一个项目，生成你的第一份实战计划。" @retry="loadProjects()">
            <template #empty><el-button type="primary" @click="switchPage('recommendations')">去发现项目</el-button></template>
            <div class="project-list">
              <button v-for="item in projectPage?.records || []" :key="item.id" type="button" class="saved-project" @click="openProject(item.id)"><span class="project-icon"><el-icon><Box /></el-icon></span><span class="saved-project__body"><strong>{{ item.title || '项目实战计划' }}</strong><span>{{ item.summary || '计划准备中' }}</span><small>{{ commonStatusLabels[item.status] || item.status }} · 每周 {{ item.weeklyHours }} 小时 · {{ formatDate(item.createdAt) }}</small></span><span class="saved-project__progress"><strong>{{ normalizedProgress(item.progress) }}%</strong><span>查看计划<el-icon><ArrowRight /></el-icon></span></span></button>
            </div>
            <div class="pagination">
              <el-pagination background layout="total, sizes, prev, pager, next" :total="projectPage?.total || 0" :current-page="projectPage?.current || projectQuery.current" :page-size="projectPage?.size || projectQuery.size" :page-sizes="[6, 12, 24]" :pager-count="5" @current-change="handleProjectPage" @size-change="handleProjectSize" />
            </div>
          </AsyncState>
        </section>
      </AsyncState>
    </template>

    <el-dialog v-model="recommendationDialogVisible" title="设置项目推荐" width="min(720px, 94vw)" :close-on-click-modal="!recommendSubmitting" :close-on-press-escape="!recommendSubmitting" :show-close="!recommendSubmitting">
      <p class="dialog-intro">根据当前确认画像与目标技能推荐 1～6 个候选项目，也可关联一个有效岗位匹配结果。</p>
          <el-form ref="recommendationFormRef" :model="recommendationForm" :rules="recommendationRules" label-position="top">
            <div class="form-grid">
              <el-form-item label="关联岗位匹配结果（可选）" prop="matchItemId">
                <el-select
                  v-model="recommendationForm.matchItemId"
                  class="full-width"
                  clearable
                  filterable
                  :loading="matchItemOptionsLoading"
                  placeholder="使用当前画像与技能，或选择有效匹配结果"
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
                  暂无可用匹配结果。可直接使用当前画像与所选技能，也可先完成岗位匹配。
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
    </el-dialog>

    <el-dialog v-model="planDialogVisible" title="生成项目实战计划" width="min(520px, 94vw)" :close-on-click-modal="!planSubmitting" :close-on-press-escape="!planSubmitting" :show-close="!planSubmitting">
      <template v-if="selectedItem">
        <div class="selected-candidate"><span>已选择的候选项目</span><h3>{{ selectedItem.title }}</h3><p>{{ selectedItem.summary }}</p><small>{{ difficultyLabels[selectedItem.difficulty] }} · 预计 {{ selectedItem.estimatedWeeks }} 周</small></div>
        <el-form ref="planFormRef" :model="planForm" :rules="planRules" label-position="top"><el-form-item label="每周可投入时长（小时）" prop="weeklyHours"><el-input-number v-model="planForm.weeklyHours" class="full-width" :min="1" :max="40" :step="1" step-strictly /></el-form-item><p class="dialog-intro">将根据此候选项目和你的时间安排，生成一份独立的实战计划。</p><el-alert v-if="planSubmitError" type="error" :title="errorMessage(planSubmitError)" :closable="false" show-icon /></el-form>
      </template>
      <template #footer><el-button :disabled="planSubmitting" @click="planDialogVisible = false">取消</el-button><el-button type="primary" :loading="planSubmitting" :disabled="planBusy" @click="submitPlan">确认生成计划</el-button></template>
    </el-dialog>

    <el-dialog v-model="projectPickerVisible" title="切换实战计划" width="min(760px, 94vw)">
      <AsyncState :loading="projectsLoading" :error="projectsError" :empty="!projectPage?.records.length" empty-text="暂无实战计划" empty-description="在项目推荐中选择候选后即可生成计划。" @retry="loadProjects()">
        <div class="project-picker-list"><button v-for="item in projectPage?.records || []" :key="item.id" type="button" class="project-picker-item" :class="{ selected: project?.id === item.id }" @click="openProject(item.id)"><span><strong>{{ item.title || '项目实战计划' }}</strong><small>{{ commonStatusLabels[item.status] || item.status }} · 每周 {{ item.weeklyHours }} 小时 · 完成 {{ normalizedProgress(item.progress) }}%</small></span><el-icon><ArrowRight /></el-icon></button></div>
        <div class="pagination">
          <el-pagination background layout="total, sizes, prev, pager, next" :total="projectPage?.total || 0" :current-page="projectPage?.current || projectQuery.current" :page-size="projectPage?.size || projectQuery.size" :page-sizes="[6, 12, 24]" :pager-count="5" @current-change="handleProjectPage" @size-change="handleProjectSize" />
        </div>
      </AsyncState>
    </el-dialog>

    <el-dialog v-model="deliverableDialogVisible" title="添加交付记录" width="min(560px, 94vw)" :close-on-click-modal="!deliverableSubmitting" :close-on-press-escape="!deliverableSubmitting" :show-close="!deliverableSubmitting">
      <p class="dialog-intro">记录你实际完成的成果，便于后续整理项目经历。</p>
      <el-form ref="deliverableFormRef" :model="deliverableForm" :rules="deliverableRules" label-position="top">
        <el-form-item label="关联任务" prop="taskId"><el-select v-model="deliverableForm.taskId" class="full-width" placeholder="选择项目任务"><el-option v-for="task in projectTasks" :key="task.id" :label="task.title" :value="task.id" /></el-select></el-form-item>
        <el-form-item label="交付标题" prop="title"><el-input v-model="deliverableForm.title" maxlength="200" show-word-limit placeholder="例如：项目 README 与核心功能实现" /></el-form-item>
        <el-form-item label="交付链接（可选）" prop="url"><el-input v-model="deliverableForm.url" placeholder="输入 http 或 https 链接" clearable /></el-form-item>
        <el-form-item label="交付说明（可选）" prop="notes"><el-input v-model="deliverableForm.notes" type="textarea" :rows="4" maxlength="2000" show-word-limit placeholder="记录实现范围、验证方式或待改进事项" /></el-form-item>
        <el-alert v-if="deliverableError" type="error" :title="errorMessage(deliverableError)" :closable="false" show-icon />
      </el-form>
      <template #footer><el-button :disabled="deliverableSubmitting" @click="deliverableDialogVisible = false">取消</el-button><el-button type="primary" :loading="deliverableSubmitting" :disabled="!projectTasks.length" @click="submitDeliverable">保存交付记录</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.project-page {
  --practice-blue: #0860ff;
  --el-color-primary: #0860ff;
  --el-color-primary-light-9: #eef5ff;
  padding: 0 2px 24px;
  color: #10183b;
  h2, h3, h4, p { margin-top: 0; }
  button { font: inherit; }
  button:focus-visible, a:focus-visible { outline: 3px solid #82b4ff; outline-offset: 3px; }
}
.practice-header { display: flex; justify-content: space-between; align-items: center; gap: 24px; margin: 0 12px 20px; }
.practice-date { margin: 0 0 9px; color: #53678f; font-size: 13px; }
.practice-title { display: flex; align-items: center; flex-wrap: wrap; gap: 14px; }
.practice-title h1 { margin: 0; color: #0b1233; font-size: 32px; line-height: 1.35; font-weight: 750; letter-spacing: -.6px; }
.page-badge { padding: 5px 11px; border-radius: 7px; color: #3f73c5; background: #e5efff; font-size: 12px; font-weight: 600; white-space: nowrap; }
.practice-description { margin: 9px 0 0; color: #52658e; font-size: 15px; line-height: 1.8; }
.page-switch { display: flex; flex-shrink: 0; align-self: flex-start; gap: 4px; padding: 4px; border: 1px solid #e0eafa; border-radius: 10px; background: #edf3fb; }
.page-switch button { display: flex; align-items: center; gap: 6px; padding: 9px 14px; border: 0; border-radius: 7px; color: #52658e; background: transparent; cursor: pointer; white-space: nowrap; }
.page-switch button.active { color: var(--practice-blue); background: #fff; box-shadow: 0 2px 6px #163a7710; font-weight: 600; }
.nav-count { display: inline-grid; min-width: 18px; height: 18px; padding: 0 4px; place-items: center; border-radius: 5px; color: #5475ab; background: #eaf2ff; font-size: 11px; }
.practice-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 0 8px 14px; }
.toolbar-tip { display: flex; align-items: center; gap: 7px; color: #667a9e; font-size: 12px; line-height: 1.6; }
.toolbar-tip .el-icon { color: #6087c7; flex-shrink: 0; }
.toolbar-actions { display: flex; flex-shrink: 0; align-items: center; gap: 8px; }
.toolbar-actions :deep(.el-button + .el-button) { margin-left: 0; }
.surface { min-width: 0; border: 1px solid #e1ebfa; border-radius: 11px; background: #fff; box-shadow: 0 3px 12px #1d4a8a03; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 15px; }
.section-heading h2, .section-heading h3 { margin: 0; font-size: 18px; font-weight: 700; }
.heading-count { font-weight: 500; }
.help-icon { color: #667ba4; font-size: 16px; }
.recommendation-layout { display: grid; grid-template-columns: minmax(290px, .35fr) minmax(0, .65fr); gap: 12px; align-items: stretch; }
.candidate-panel { padding: 20px 12px 12px; }
.candidate-panel > .section-heading { justify-content: flex-start; padding: 0 9px; }
.candidate-list { display: grid; align-content: start; gap: 10px; max-height: max(520px, calc(100vh - 290px)); padding: 2px; overflow-y: auto; scrollbar-width: thin; scrollbar-color: #d6e6fb transparent; }
.candidate-card { display: flex; align-items: flex-start; gap: 15px; width: 100%; padding: 15px 13px; text-align: left; border: 1px solid #e0eafa; border-radius: 9px; color: inherit; background: #fff; cursor: pointer; transition: border-color .18s, background .18s; }
.candidate-card:hover { border-color: #9dc2ff; background: #f8fbff; }
.candidate-card--selected { border-color: #328cff; background: linear-gradient(120deg, #f1f8ff, #edf5ff); box-shadow: 0 0 0 1px #328cff12; }
.project-icon { display: inline-grid; flex: 0 0 46px; width: 46px; height: 49px; place-items: center; border: 1px solid #ffffff35; border-radius: 10px; color: #fff; background: linear-gradient(140deg, #1677ff, #4b5aff); box-shadow: inset 0 1px 4px #ffffff45, 0 3px 7px #3972ed15; font-size: 28px; }
.project-icon--1 { background: linear-gradient(145deg, #387fff, #6554ff); }
.project-icon--2 { background: linear-gradient(145deg, #009dff, #165cff); }
.project-icon--large { flex-basis: 62px; width: 62px; height: 64px; font-size: 36px; border-radius: 11px; }
.candidate-card__body { display: flex; flex: 1; flex-direction: column; min-width: 0; gap: 10px; }
.candidate-card__title { display: flex; justify-content: space-between; align-items: flex-start; gap: 7px; color: #111a3d; font-size: 17px; line-height: 1.6; font-weight: 700; overflow-wrap: anywhere; }
.candidate-card__title .el-icon { flex-shrink: 0; margin-top: 5px; font-size: 15px; }
.candidate-card__summary { color: #52658e; font-size: 13px; line-height: 1.75; overflow-wrap: anywhere; }
.skill-tags { display: flex; flex-wrap: wrap; gap: 7px; }
.skill-tags > span { display: inline-block; padding: 4px 10px; border-radius: 5px; color: #3e6098; background: #eef4ff; font-size: 12px; line-height: 1.4; overflow-wrap: anywhere; }
.candidate-card--selected .skill-tags > span { background: #e4efff; }
.candidate-card__meta { display: flex; gap: 9px; color: #637aa5; font-size: 12px; }
.candidate-card__reason { display: flex; flex-direction: column; gap: 4px; color: #52658e; font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.candidate-card__reason strong { color: #27395f; font-size: 13px; }
.candidate-footer { margin: 14px 7px 0; color: #91a0b9; font-size: 11px; }
.candidate-detail { display: flex; flex-direction: column; padding: 25px 23px 20px; }
.candidate-detail__hero { display: flex; gap: 21px; padding: 0 2px 22px; }
.candidate-detail__hero > div { min-width: 0; }
.eyebrow { display: block; margin: 0 0 8px; color: #6385c4; font-size: 12px; }
.candidate-detail__hero h2 { margin: 0 0 12px; font-size: 27px; line-height: 1.4; color: #0c1437; overflow-wrap: anywhere; }
.candidate-detail__hero p { margin: 0 0 14px; color: #52658e; font-size: 14px; line-height: 1.8; overflow-wrap: anywhere; }
.candidate-detail__hero .skill-tags > span { color: #2964dc; }
.candidate-facts { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border-block: 1px solid #e6eefb; }
.candidate-facts > div { display: flex; align-items: flex-start; gap: 10px; padding: 18px 10px; border-right: 1px solid #eaf0fa; }
.candidate-facts > div:last-child { border-right: 0; }
.candidate-facts .el-icon { flex-shrink: 0; padding: 7px; box-sizing: content-box; border-radius: 8px; color: var(--practice-blue); background: #f1f7ff; font-size: 22px; }
.candidate-facts span { min-width: 0; color: #617398; font-size: 12px; line-height: 1.6; }
.candidate-facts strong { display: block; margin-top: 5px; color: #142248; font-size: 14px; font-weight: 600; }
.detail-section { margin-top: 25px; }
.detail-section h3 { display: flex; align-items: center; gap: 10px; margin: 0 0 15px; font-size: 18px; }
.detail-section h3 > .el-icon { color: var(--practice-blue); font-size: 22px; }
.goal-list { display: grid; gap: 12px; margin: 0; padding: 0 0 0 18px; list-style: none; }
.goal-list li { display: flex; align-items: flex-start; gap: 14px; color: #52658e; font-size: 14px; line-height: 1.75; }
.goal-list li > span { display: grid; flex: 0 0 28px; height: 28px; place-items: center; border-radius: 50%; background: #eaf3ff; color: var(--practice-blue); font-size: 16px; font-weight: 700; }
.goal-list p { margin: 2px 0 0; overflow-wrap: anywhere; }
.skill-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 10px; }
.skill-card { display: flex; align-items: center; gap: 12px; min-width: 0; padding: 15px 10px; border: 1px solid #e1ebfb; border-radius: 8px; background: linear-gradient(130deg, #fff, #f5f9ff); }
.skill-card > div { min-width: 0; overflow-wrap: anywhere; }
.skill-monogram { display: grid; flex: 0 0 36px; height: 40px; place-items: center; border-radius: 8px; color: #2867d4; background: #eaf2ff; font-size: 15px; font-weight: 700; }
.skill-card strong { display: block; color: #172349; font-size: 14px; }
.skill-card small { display: block; margin-top: 5px; color: #7a8bab; font-size: 11px; line-height: 1.5; }
.reason-callout { margin: 0; padding: 17px 20px; border-radius: 9px; color: #52658e; background: linear-gradient(110deg, #eef6ff, #f3f8ff); font-size: 14px; line-height: 1.9; overflow-wrap: anywhere; }
.candidate-detail__footer { display: flex; flex-direction: column; align-items: flex-end; gap: 15px; margin-top: auto; padding-top: 25px; }
.candidate-detail__footer > p { display: flex; align-items: flex-start; gap: 6px; align-self: flex-start; margin: 0; color: #8999b5; font-size: 11px; line-height: 1.6; }
.candidate-detail__footer > p .el-icon { flex-shrink: 0; margin-top: 3px; }
.candidate-detail__footer :deep(.el-button) { height: 46px; padding-inline: 25px; }
.trailing-icon { margin-left: 16px; }
.candidate-placeholder { display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 520px; padding: 24px; text-align: center; }
.candidate-placeholder > .el-icon { margin-bottom: 20px; color: #90b9ff; font-size: 40px; }
.candidate-placeholder strong { font-size: 15px; color: #4c6896; }
.candidate-placeholder p { margin: 12px 0 0; color: #91a0b9; font-size: 13px; line-height: 1.8; }
.recommendation-empty, .plan-waiting { display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 650px; padding: 40px 24px; text-align: center; }
.empty-illustration { display: grid; width: 96px; height: 96px; place-items: center; margin-bottom: 28px; border: 1px solid #e1ecff; border-radius: 27px; color: #3b79f2; background: linear-gradient(135deg, #f2f8ff, #e6efff); font-size: 48px; box-shadow: 0 12px 30px #2678ff0a; }
.recommendation-empty h2, .plan-waiting h2 { margin: 4px 0 12px; color: #182c54; font-size: 24px; }
.recommendation-empty p, .plan-waiting p { margin: 0 0 27px; color: #7b8fad; font-size: 14px; line-height: 1.9; }
.empty-steps { display: flex; flex-wrap: wrap; justify-content: center; align-items: center; gap: 13px; margin-top: 35px; color: #8b9dba; font-size: 12px; }
.task-status-panel { display: grid; gap: 10px; margin-bottom: 14px; padding: 18px; }
.plan-layout { display: grid; grid-template-columns: minmax(0, 2.1fr) minmax(280px, 1fr); gap: 12px; align-items: start; }
.plan-main { padding: 23px 22px; }
.plan-hero { display: flex; align-items: flex-start; gap: 18px; margin-bottom: 25px; }
.plan-hero__body { flex: 1; min-width: 0; }
.plan-hero__title { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-bottom: 14px; }
.plan-hero__title h2 { margin: 3px 0 0; font-size: 25px; line-height: 1.45; overflow-wrap: anywhere; }
.plan-hero__body > p { display: flex; align-items: center; gap: 7px; margin: 0; color: #657b9e; font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.plan-hero__body > p .el-icon { flex-shrink: 0; }
.estimated-time { display: flex; flex-shrink: 0; flex-direction: column; align-items: flex-start; gap: 9px; margin-left: auto; padding: 4px 0 3px 18px; border-left: 1px solid #e6eefb; }
.estimated-time > span { display: flex; align-items: center; gap: 6px; color: #7183a5; font-size: 12px; }
.estimated-time strong { font-size: 25px; letter-spacing: -.5px; }
.estimated-time small { font-size: 12px; font-weight: 600; letter-spacing: 0; }
.project-intro h3 { margin: 0 0 11px; font-size: 18px; }
.project-intro > p { margin: 0 0 15px; color: #52658e; font-size: 14px; line-height: 1.9; overflow-wrap: anywhere; }
.info-callout { display: flex; align-items: flex-start; gap: 9px; padding: 13px 15px; margin: 0; border-radius: 8px; color: #5977af; background: #f0f7ff; font-size: 12px; line-height: 1.7; }
.info-callout > .el-icon { flex-shrink: 0; margin-top: 2px; color: var(--practice-blue); font-size: 17px; }
.task-plan-heading { margin: 25px 0 13px; }
.task-plan-heading > span { color: #7a8eae; font-size: 12px; }
.task-plan-heading > span strong { color: var(--practice-blue); }
.plan-progress { margin-bottom: 8px; }
.plan-progress :deep(.el-progress__text) { min-width: 36px; color: #4372be; font-size: 12px !important; }
.progress-note { margin-bottom: 18px; color: #9aa7bc; font-size: 11px; }
.milestone-list { border: 1px solid #e3ecfa; border-radius: 10px; padding: 16px 15px 4px; }
.milestone-section + .milestone-section { padding-top: 15px; border-top: 1px solid #e6eef9; }
.milestone-heading { display: flex; align-items: baseline; flex-wrap: wrap; gap: 10px; margin-bottom: 6px; }
.milestone-heading > span { color: #7c94bb; font-size: 11px; }
.milestone-heading h4 { margin: 0; color: #345783; font-size: 14px; font-weight: 600; }
.milestone-description { margin: 0 0 13px; color: #8a9bb7; font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.task-timeline { margin: 0; padding: 0; list-style: none; }
.timeline-task { position: relative; display: flex; gap: 18px; padding: 8px 0 0; scroll-margin-top: 30px; }
.timeline-task::before { position: absolute; content: ''; left: 17px; top: 42px; bottom: -8px; width: 2px; background: #e4ecf8; }
.timeline-task:last-child::before { display: none; }
.task-number { position: relative; z-index: 0; display: grid; flex: 0 0 36px; height: 36px; place-items: center; margin-top: 1px; border: 3px solid #eef6ff; border-radius: 50%; background: #d6e8ff; color: #0562fd; font-size: 19px; font-weight: 700; }
.timeline-task--current .task-number { color: #fff; background: linear-gradient(135deg, #2b8dff, #4d81ff); }
.timeline-task--completed .task-number { color: #139d73; background: #ddf6eb; border-color: #effbf6; }
.timeline-task__content { flex: 1; min-width: 0; padding: 3px 0 16px; margin-bottom: 4px; border-bottom: 1px solid #e8eef8; }
.timeline-task:last-child .timeline-task__content { border-bottom: 0; }
.timeline-task--current .timeline-task__content h4 { color: #174da7; }
.timeline-task__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 15px; margin-bottom: 8px; }
.timeline-task__heading h4 { margin: 2px 0 0; font-size: 17px; line-height: 1.6; overflow-wrap: anywhere; }
.task-check { display: flex; flex-shrink: 0; align-items: center; gap: 9px; color: #788baa; font-size: 12px; }
.task-check :deep(.el-checkbox) { height: 28px; margin: 0; }
.task-check :deep(.el-checkbox__inner) { width: 17px; height: 17px; border-radius: 4px; border-color: #bed0eb; }
.task-check :deep(.is-checked .el-checkbox__inner) { border-color: var(--practice-blue); }
.status-in_progress { color: var(--practice-blue); }
.status-completed { color: #0f9b6d; }
.task-duration { display: flex; align-items: center; gap: 8px; margin: 0 0 7px; color: #7589ac; font-size: 13px; }
.task-duration .el-icon { font-size: 17px; }
.task-description { margin: 0 0 12px; color: #5b6f94; font-size: 13px; line-height: 1.8; overflow-wrap: anywhere; }
.task-controls { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; }
.task-status-select { width: 108px; }
.task-status-select :deep(.el-select__wrapper) { box-shadow: 0 0 0 1px #e5edfa inset; }
.evidence-panel { padding: 24px 20px; }
.evidence-heading { display: flex; align-items: center; gap: 13px; margin: 0 0 22px; font-size: 23px; }
.evidence-heading > span { display: grid; width: 42px; height: 44px; place-items: center; border-radius: 9px; color: var(--practice-blue); background: #e9f3ff; font-size: 27px; }
.evidence-callout { padding: 23px 20px; border-radius: 10px; background: linear-gradient(125deg, #edf6ff, #f4f9ff); }
.evidence-callout h3 { margin: 0 0 14px; color: #075fff; font-size: 17px; line-height: 1.7; }
.evidence-callout p { margin: 0; color: #5c7198; font-size: 14px; line-height: 1.9; }
.evidence-skills { margin-top: 28px; }
.evidence-skills h3 { margin: 0 0 17px; font-size: 17px; }
.evidence-skill-tags { display: flex; flex-wrap: wrap; gap: 10px; }
.evidence-skill-tags > span { padding: 10px 18px; border-radius: 7px; background: #eef5ff; color: #0860ff; font-size: 13px; overflow-wrap: anywhere; }
.evidence-skill-tags > .skill-color-1 { background: #fff0f1; color: #e85d71; }
.evidence-skill-tags > .skill-color-2 { background: #edf9f5; color: #158963; }
.learning-advice { display: flex; align-items: flex-start; gap: 12px; margin-top: 30px; padding: 20px 17px; border-radius: 9px; color: #178057; background: linear-gradient(120deg, #effaf4, #f2fbf7); }
.learning-advice > .el-icon { display: grid; flex: 0 0 29px; width: 29px; height: 29px; border-radius: 50%; color: #fff; background: #18a264; font-size: 19px; }
.learning-advice h3 { margin: 2px 0 10px; color: #10734b; font-size: 16px; }
.learning-advice p { margin: 0; color: #64807f; font-size: 13px; line-height: 1.8; }
.start-task-button { width: 100%; height: 54px; margin: 30px 0; font-size: 16px; }
.evidence-records { padding-top: 22px; border-top: 1px solid #e7eef8; }
.evidence-records .section-heading h3 { font-size: 16px; }
.no-records { color: #8c9db7; font-size: 12px; line-height: 1.8; }
.evidence-note { display: flex; align-items: flex-start; gap: 6px; margin: 20px 0 0; color: #92a1b8; font-size: 11px; line-height: 1.7; }
.evidence-note > .el-icon { flex-shrink: 0; margin-top: 3px; }
.deliverable-history { display: grid; gap: 10px; }
.deliverable-record { padding: 13px; border: 1px solid #e6eef9; border-radius: 8px; overflow-wrap: anywhere; }
.deliverable-record h4 { margin: 0 0 7px; font-size: 14px; }
.deliverable-record p { margin: 7px 0; font-size: 12px; line-height: 1.7; }
.deliverable-record__task { color: #8495b0; }
.deliverable-record a { display: inline-flex; align-items: center; gap: 6px; margin: 3px 0 7px; color: #2b6cda; font-size: 12px; }
.deliverable-record small { color: #9aa9bf; font-size: 11px; }
.project-index { padding: 24px; }
.project-list { display: grid; gap: 12px; }
.saved-project { display: flex; align-items: center; gap: 18px; width: 100%; padding: 20px; border: 1px solid #e0eafa; border-radius: 10px; text-align: left; color: inherit; background: #fff; cursor: pointer; }
.saved-project:hover { background: #f6faff; border-color: #a9c9fc; }
.saved-project__body { display: flex; flex: 1; flex-direction: column; min-width: 0; gap: 9px; overflow-wrap: anywhere; }
.saved-project__body > strong { font-size: 18px; }
.saved-project__body > span { color: #6a7f9f; font-size: 13px; line-height: 1.7; }
.saved-project__body small { color: #8f9fb6; font-size: 12px; }
.saved-project__progress { display: flex; flex-shrink: 0; flex-direction: column; align-items: flex-end; gap: 10px; }
.saved-project__progress > strong { color: #1a68e9; font-size: 23px; }
.saved-project__progress > span { display: flex; align-items: center; gap: 6px; color: #6b8aba; font-size: 12px; }
.project-picker-list { display: grid; gap: 10px; }
.project-picker-item { display: flex; align-items: center; justify-content: space-between; gap: 12px; width: 100%; padding: 18px; border: 1px solid #e0eafa; border-radius: 9px; color: #314769; text-align: left; background: #fff; cursor: pointer; }
.project-picker-item:hover, .project-picker-item.selected { border-color: #609aff; background: #f1f7ff; }
.project-picker-item > span { display: flex; flex-direction: column; min-width: 0; gap: 9px; overflow-wrap: anywhere; }
.project-picker-item small { color: #8a9bb7; }
.dialog-intro { margin: 0 0 22px; color: #7a8ba7; font-size: 13px; line-height: 1.8; }
.section-note, .field-hint { color: #8191aa; font-size: 12px; line-height: 1.7; }
.field-error { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; width: 100%; color: #e75c67; font-size: 12px; line-height: 1.5; }
.match-option { display: flex; flex-direction: column; gap: 3px; padding: 3px 0; }
.match-option strong { color: #101d4b; font-weight: 600; }
.match-option span { color: #8191aa; font-size: 12px; }
.selected-match { display: flex; justify-content: space-between; gap: 16px; margin: 0 0 14px; padding: 13px 15px; border: 1px solid #dbe8fb; border-radius: 9px; background: #f3f8ff; }
.selected-match__eyebrow { display: block; margin-bottom: 5px; color: #377cd8; font-size: 12px; font-weight: 700; }
.selected-match p { margin: 5px 0 0; color: #8191aa; line-height: 1.6; }
.selected-match__reason { font-size: 12px; }
.selected-match__meta { display: flex; flex: 0 0 auto; flex-direction: column; align-items: flex-end; gap: 7px; color: #8191aa; font-size: 12px; }
.selected-candidate { margin-bottom: 22px; padding: 18px; border: 1px solid #e3edfb; border-radius: 9px; background: #f7faff; }
.selected-candidate > span { color: #4c82c8; font-size: 12px; }
.selected-candidate h3 { margin: 9px 0; color: #173158; font-size: 20px; line-height: 1.5; }
.selected-candidate p { margin: 0 0 12px; color: #6c7e9b; font-size: 13px; line-height: 1.8; }
.selected-candidate small { color: #8599b8; }
.inline-alert { margin-bottom: 14px; }
.project-page :deep(.el-dialog__title) { color: #19345f; font-weight: 600; }
@media (max-width: 1200px) {
  .practice-header { flex-direction: column; align-items: flex-start; gap: 15px; }
  .page-switch { align-self: flex-end; margin-top: -2px; }
  .candidate-card { gap: 10px; padding: 14px 10px; }
  .candidate-card > .project-icon { flex-basis: 37px; width: 37px; height: 40px; font-size: 23px; }
  .candidate-facts { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .candidate-facts > div:nth-child(2) { border-right: 0; }
  .plan-hero { flex-wrap: wrap; }
  .estimated-time { flex-direction: row; align-items: center; flex-wrap: wrap; width: 100%; padding: 12px 0 0; border-left: 0; border-top: 1px solid #e6eefb; }
  .estimated-time strong { font-size: 21px; }
}
@media (max-width: 1000px) {
  .recommendation-layout { grid-template-columns: minmax(250px, .4fr) minmax(0, .6fr); }
  .plan-layout { grid-template-columns: minmax(0, 1fr); }
  .evidence-panel { display: block; }
  .candidate-detail { padding: 20px 17px; }
  .candidate-detail__hero { gap: 13px; }
  .candidate-detail__hero > .project-icon { display: none; }
  .candidate-detail__hero h2 { font-size: 24px; }
  .toolbar-tip { max-width: 48%; }
  .skill-cards { grid-template-columns: 1fr; }
  .candidate-detail__footer :deep(.el-button) { max-width: 100%; padding-inline: 15px; font-size: 13px; }
}
@media (max-width: 760px) {
  .practice-header { margin-inline: 0; }
  .practice-title h1 { font-size: 27px; }
  .practice-description { font-size: 13px; }
  .page-switch { align-self: stretch; justify-content: center; }
  .page-switch > button { flex: 1; justify-content: center; }
  .practice-toolbar { align-items: flex-start; flex-wrap: wrap; margin-inline: 0; }
  .toolbar-tip { max-width: none; width: 100%; }
  .toolbar-actions { flex-wrap: wrap; }
  .recommendation-layout { grid-template-columns: 1fr; }
  .candidate-list { max-height: 420px; }
  .candidate-placeholder { min-height: 180px; }
  .candidate-detail { padding: 20px 16px; }
  .candidate-detail__hero > .project-icon { display: inline-grid; flex-basis: 46px; width: 46px; height: 49px; font-size: 28px; }
  .candidate-detail__hero h2 { font-size: 22px; }
  .candidate-facts > div { padding: 13px 6px; }
  .candidate-facts .el-icon { font-size: 18px; }
  .skill-cards { grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); }
  .recommendation-empty, .plan-waiting { min-height: 450px; padding: 30px 16px; }
  .recommendation-empty h2, .plan-waiting h2 { font-size: 20px; }
  .plan-main { padding: 20px 14px; }
  .plan-hero { gap: 12px; }
  .plan-hero > .project-icon { flex-basis: 48px; width: 48px; height: 50px; font-size: 29px; }
  .plan-hero__title h2 { font-size: 22px; }
  .milestone-list { padding: 14px 10px 0; }
  .timeline-task { gap: 10px; }
  .timeline-task__heading { flex-wrap: wrap; gap: 3px; }
  .timeline-task__heading h4 { font-size: 16px; }
  .task-check { margin-left: auto; }
  .saved-project { gap: 12px; padding: 14px 10px; flex-wrap: wrap; }
  .saved-project > .project-icon { display: none; }
  .saved-project__progress { flex-direction: row; align-items: center; width: 100%; justify-content: space-between; }
  .project-index { padding: 20px 14px; }
  .selected-match { flex-direction: column; }
  .selected-match__meta { flex-direction: row; align-items: center; }
  .form-grid { grid-template-columns: 1fr; }
}
@media (prefers-reduced-motion: reduce) {
  .candidate-card { transition: none; }
}
</style>
