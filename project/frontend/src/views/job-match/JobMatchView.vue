<script setup lang="ts">
import ApplicationForm from '@/views/application/ApplicationForm.vue'
import type { ApplicationCreatePayload } from '@/types/application'
import JobSearchView from '@/views/job-search/JobSearchView.vue'
import JobComparison from './JobComparison.vue'
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Aim, Plus, Refresh, Search, Star, StarFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { jobApi } from '@/api/jobs'
import { profileApi } from '@/api/profile'
import { canRetryTrackedTask, retryTrackedTask } from '@/api/task-registry'
import type { JobSearchItemVO, JobVO, Page, SkillVO, TaskVO } from '@/api/types'
import { useTask } from '@/composables/useTask'
import { jobEducation, jobSalary } from '@/utils/job-search-fields'
import AsyncState from '@/components/AsyncState.vue'
import TaskStatus from '@/components/TaskStatus.vue'

import { candidateTypeLabels, cityLabels, commonStatusLabels, educationLabels, errorMessage, formatDate, formatSalary } from '@/utils/format'

type JobTab = 'search' | 'trusted' | 'personal' | 'favorites' | 'matches'
type ImportBody = Parameters<typeof jobApi.importJd>[0]

const route = useRoute()
const router = useRouter()
const applicationPlan = ref<ApplicationCreatePayload | null>(null)
const applicationVisible = ref(false)

function planApplication(job: JobVO | JobSearchItemVO) {
  const fromSearch = 'jobId' in job
  applicationPlan.value = {
    jobId: fromSearch ? job.jobId : job.id,
    companyName: job.companyName.slice(0, 100),
    jobTitle: job.title.slice(0, 100),
    cityCode: job.cityCode,
    education: jobEducation(job),
    salaryText: jobSalary(job)?.slice(0, 100),
    description: (fromSearch ? job.jdSummary : job.jdText)?.trim().slice(0, 1000),
    status: 'PLANNED',
  }
  applicationVisible.value = true
}

const allowedTabs: JobTab[] = ['search', 'trusted', 'personal', 'favorites', 'matches']
const activeTab = computed<JobTab>({
  get() {
    const tab = String(route.query.tab || '') as JobTab
    if (allowedTabs.includes(tab)) return tab
    if (route.query.matchId) return 'matches'
    return route.query.jobId ? 'trusted' : 'search'
  },
  set(tab) {
    setQuery({ tab, jobId: undefined, matchId: undefined, searchId: undefined, taskId: undefined })
  },
})
const cityOptions = Object.entries(cityLabels).map(([value, label]) => ({ value, label }))
const candidateOptions = Object.entries(candidateTypeLabels).map(([value, label]) => ({ value, label }))

function emptyPage<T>(size = 6): Page<T> {
  return { total: 0, records: [], current: 1, size }
}

function queryValue(value: unknown) {
  return typeof value === 'string' && value ? value : ''
}

function httpUrl(value?: string | null) {
  if (!value) return ''
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.toString() : ''
  } catch {
    return ''
  }
}

function openSource(value?: string | null) {
  const url = httpUrl(value)
  if (!url) return
  const opened = window.open(url, '_blank', 'noopener,noreferrer')
  if (opened) opened.opener = null
}

function setQuery(values: Record<string, string | undefined>) {
  const query = { ...route.query }
  Object.entries(values).forEach(([key, value]) => {
    if (value) query[key] = value
    else delete query[key]
  })
  void router.replace({ query })
}

function taskIsActive(task?: TaskVO | null) {
  return !!task && ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(task.status)
}

function statusTagType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'PASS' || status === 'ACTIVE' || status === 'READY') return 'success'
  if (status === 'UNKNOWN' || status === 'GENERATING' || status === 'ANALYZING') return 'warning'
  if (status === 'FAIL' || status === 'FAILED' || status === 'CLOSED') return 'danger'
  return 'info'
}

function sourceTypeLabel(type: string) {
  return ({ OFFICIAL: '企业官方', AUTHORIZED: '授权来源', PUBLIC_SEARCH: '公开搜索', USER_INPUT: '个人导入', SAVED_SEARCH: '搜索保存' } as Record<string, string>)[type] || type
}

function sourceIsPersonal(job: JobVO) {
  return job.source.type === 'USER_INPUT' || job.source.type === 'SAVED_SEARCH'
}

function verificationLabel(status: string) {
  return ({ VERIFIED: '已核验', UNVERIFIED: '未核验', DISABLED: '已停用' } as Record<string, string>)[status] || status
}

const trustedFilters = reactive({ q: '', cityCode: '', candidateType: '' })
const trustedJobs = ref<Page<JobVO>>(emptyPage())
const trustedLoading = ref(false)
const trustedError = ref<unknown>(null)
const favoriteJobs = ref<Page<JobVO>>(emptyPage())
const favoritesLoading = ref(false)
const favoritesError = ref<unknown>(null)
const personalJobs = ref<JobVO[]>([])
const personalError = ref<unknown>(null)
const personalTotal = ref(0)
const personalCurrent = ref(1)
const personalSize = ref(6)
const personalLoading = ref(false)
let personalLoadSequence = 0

async function loadTrustedJobs() {
  trustedLoading.value = true
  trustedError.value = null
  try {
    trustedJobs.value = await jobApi.list({
      current: trustedJobs.value.current,
      size: trustedJobs.value.size,
      q: trustedFilters.q.trim() || undefined,
      cityCode: trustedFilters.cityCode || undefined,
      candidateType: trustedFilters.candidateType || undefined,
    })
  } catch (reason) {
    trustedError.value = reason
  } finally {
    trustedLoading.value = false
  }
}

async function loadFavorites() {
  favoritesLoading.value = true
  favoritesError.value = null
  try {
    favoriteJobs.value = await jobApi.favorites({ current: favoriteJobs.value.current, size: favoriteJobs.value.size })
  } catch (reason) {
    favoritesError.value = reason
  } finally {
    favoritesLoading.value = false
  }
}

async function loadPersonalJobs() {
  const sequence = ++personalLoadSequence
  personalLoading.value = true
  personalError.value = null
  try {
    const data = await jobApi.imports({ current: personalCurrent.value, size: personalSize.value })
    if (sequence !== personalLoadSequence) return
    personalJobs.value = data.records
    personalTotal.value = data.total
    personalCurrent.value = data.current
    personalSize.value = data.size
  } catch (reason) {
    if (sequence === personalLoadSequence) personalError.value = reason
  } finally {
    if (sequence === personalLoadSequence) personalLoading.value = false
  }
}

function loadCollection() {
  if (activeTab.value === 'trusted') return loadTrustedJobs()
  if (activeTab.value === 'favorites') return loadFavorites()
  if (activeTab.value === 'personal') return loadPersonalJobs()
  return Promise.resolve()
}

const collectionJobs = computed(() => {
  if (activeTab.value === 'trusted') return trustedJobs.value.records
  if (activeTab.value === 'favorites') return favoriteJobs.value.records
  if (activeTab.value === 'personal') return personalJobs.value
  return []
})
const collectionLoading = computed(() => activeTab.value === 'trusted' ? trustedLoading.value : activeTab.value === 'favorites' ? favoritesLoading.value : personalLoading.value)
const collectionError = computed(() => activeTab.value === 'trusted' ? trustedError.value : activeTab.value === 'favorites' ? favoritesError.value : personalError.value)
const collectionTitle = computed(() => ({ search: 'AI 搜索', trusted: '可信岗位', personal: '我的个人 JD', favorites: '我的收藏', matches: '岗位列表' } as Record<JobTab, string>)[activeTab.value])
const collectionEmpty = computed(() => !collectionJobs.value.length)
const collectionTotal = computed(() => activeTab.value === 'trusted' ? trustedJobs.value.total : activeTab.value === 'favorites' ? favoriteJobs.value.total : personalTotal.value)
const collectionCurrent = computed(() => activeTab.value === 'trusted' ? trustedJobs.value.current : activeTab.value === 'favorites' ? favoriteJobs.value.current : personalCurrent.value)
const collectionSize = computed(() => activeTab.value === 'trusted' ? trustedJobs.value.size : activeTab.value === 'favorites' ? favoriteJobs.value.size : personalSize.value)

function applyTrustedFilters() {
  trustedJobs.value.current = 1
  void loadTrustedJobs()
}

function resetTrustedFilters() {
  trustedFilters.q = ''
  trustedFilters.cityCode = ''
  trustedFilters.candidateType = ''
  applyTrustedFilters()
}

function changeCollectionPage(page: number) {
  if (activeTab.value === 'trusted') {
    trustedJobs.value.current = page
    void loadTrustedJobs()
  } else if (activeTab.value === 'favorites') {
    favoriteJobs.value.current = page
    void loadFavorites()
  } else if (activeTab.value === 'personal') {
    personalCurrent.value = page
    void loadPersonalJobs()
  }
}

function changeCollectionSize(size: number) {
  if (activeTab.value === 'trusted') {
    trustedJobs.value.size = size
    trustedJobs.value.current = 1
    void loadTrustedJobs()
  } else if (activeTab.value === 'favorites') {
    favoriteJobs.value.size = size
    favoriteJobs.value.current = 1
    void loadFavorites()
  } else if (activeTab.value === 'personal') {
    personalSize.value = size
    personalCurrent.value = 1
    void loadPersonalJobs()
  }
}

const jobDrawerVisible = ref(false)
const jobDetail = ref<JobVO | null>(null)
const jobDetailRequestId = ref('')
const jobDetailLoading = ref(false)
const jobDetailError = ref<unknown>(null)
const fieldCorrectionVisible = ref(false)
const fieldCorrectionPending = ref(false)
const fieldCorrectionError = ref<unknown>(null)
const correctionSkills = ref<SkillVO[]>([])
const fieldCorrection = reactive({ cityCode: '' as string, skillCodes: [] as string[], sourceQuote: '', reason: '' })

function optimizeForJob(job: JobVO) {
  jobDrawerVisible.value = false
  void router.push({
    path: '/resume-optimization',
    query: { tab: 'optimization', jobId: job.id },
  })
}

async function openFieldCorrection() {
  if (!jobDetail.value || jobDetail.value.source.type !== 'USER_INPUT') return
  fieldCorrectionError.value = null
  fieldCorrection.cityCode = jobDetail.value.cityCode || ''
  fieldCorrection.skillCodes = [...(jobDetail.value.requirements?.skills || [])]
  fieldCorrection.sourceQuote = jobDetail.value.fieldEvidence?.confirmedQuote || jobDetail.value.fieldEvidence?.city || ''
  fieldCorrection.reason = ''
  try {
    correctionSkills.value = await profileApi.skills()
    fieldCorrectionVisible.value = true
  } catch (reason) {
    fieldCorrectionError.value = reason
  }
}

async function saveFieldCorrection() {
  const job = jobDetail.value
  if (!job || fieldCorrectionPending.value) return
  fieldCorrectionPending.value = true
  fieldCorrectionError.value = null
  try {
    const updated = await jobApi.correctFields(job.id, {
      expectedJobVersion: job.jobVersion,
      cityCode: fieldCorrection.cityCode || null,
      skillCodes: fieldCorrection.skillCodes,
      sourceQuote: fieldCorrection.sourceQuote.trim(),
      reason: fieldCorrection.reason.trim(),
    })
    replaceJob(updated)
    fieldCorrectionVisible.value = false
    await loadPersonalJobs()
    ElMessage.success(`已保存提取结果，JD 版本更新为 v${updated.jobVersion}`)
  } catch (reason) {
    fieldCorrectionError.value = reason
  } finally {
    fieldCorrectionPending.value = false
  }
}

function replaceJob(job: JobVO) {
  const replaceIn = (records: JobVO[]) => {
    const index = records.findIndex(item => item.id === job.id)
    if (index >= 0) records.splice(index, 1, job)
  }
  replaceIn(trustedJobs.value.records)
  replaceIn(favoriteJobs.value.records)
  replaceIn(personalJobs.value)
  if (jobDetail.value?.id === job.id) jobDetail.value = job
}

async function openJobById(jobId: string, syncRoute = true) {
  if (!jobId) return
  jobDetailRequestId.value = jobId
  jobDrawerVisible.value = true
  jobDetailLoading.value = true
  jobDetailError.value = null
  jobDetail.value = null
  try {
    const job = await jobApi.get(jobId)
    jobDetail.value = job
    replaceJob(job)
    if (syncRoute) setQuery({ jobId })
  } catch (reason) {
    jobDetailError.value = reason
  } finally {
    jobDetailLoading.value = false
  }
}

const favoritePendingIds = ref<string[]>([])

async function toggleFavorite(job: JobVO) {
  if (favoritePendingIds.value.includes(job.id)) return
  const next = !job.isFavorite
  favoritePendingIds.value.push(job.id)
  try {
    await jobApi.setFavorite(job.id, next)
    replaceJob({ ...job, isFavorite: next })
    ElMessage.success(next ? '已收藏岗位' : '已取消收藏')
    if (activeTab.value === 'favorites' && !next) {
      if (favoriteJobs.value.records.length === 1 && favoriteJobs.value.current > 1) favoriteJobs.value.current -= 1
      await loadFavorites()
    }
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  } finally {
    favoritePendingIds.value = favoritePendingIds.value.filter(id => id !== job.id)
  }
}

const personalSavingIds = ref<string[]>([])

async function saveToPersonal(job: JobVO) {
  if (personalSavingIds.value.includes(job.id)) return
  personalSavingIds.value.push(job.id)
  try {
    const result = await jobApi.saveToPersonal(job.id)
    ElMessage.success(result.created ? '已保存到个人 JD' : '该岗位已在个人 JD 中')
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  } finally {
    personalSavingIds.value = personalSavingIds.value.filter(id => id !== job.id)
  }
}

const importFormRef = ref<FormInstance>()
const importForm = reactive({ title: '', companyName: '', jdText: '', sourceUrl: '' })
const importRules: FormRules = {
  title: [
    { required: true, message: '请输入职位名称', trigger: 'blur' },
    { max: 100, message: '职位名称不能超过100个字符', trigger: 'blur' },
  ],
  companyName: [
    { required: true, message: '请输入公司名称', trigger: 'blur' },
    { max: 100, message: '公司名称不能超过100个字符', trigger: 'blur' },
  ],
  jdText: [
    { required: true, message: '请粘贴岗位JD', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        const length = String(value || '').trim().length
        if (length < 50 || length > 30000) callback(new Error('JD正文需为50至30000个字符'))
        else callback()
      },
      trigger: 'blur',
    },
  ],
  sourceUrl: [
    {
      validator: (_rule, value, callback) => {
        if (!value || httpUrl(String(value).trim())) callback()
        else callback(new Error('来源地址仅支持有效的http或https链接'))
      },
      trigger: 'blur',
    },
  ],
}
const importSubmitting = ref(false)
const importRetrying = ref(false)
const importActionError = ref<unknown>(null)
const importTaskId = ref<string | null>(null)
const lastImportBody = ref<ImportBody | null>(null)
const importedJobId = ref('')
const importTracker = useTask(importTaskId, { onDone: handleImportDone })
const importTask = importTracker.task
const importTrackingError = importTracker.error

async function submitImport() {
  const valid = await importFormRef.value?.validate().catch(() => false)
  if (!valid) return
  const body: ImportBody = {
    title: importForm.title.trim(),
    companyName: importForm.companyName.trim(),
    jdText: importForm.jdText.trim(),
    ...(importForm.sourceUrl.trim() ? { sourceUrl: importForm.sourceUrl.trim() } : {}),
  }
  lastImportBody.value = body
  importTaskId.value = null
  importedJobId.value = ''
  importSubmitting.value = true
  importActionError.value = null
  try {
    const task = await jobApi.importJd(body)
    importTaskId.value = task.id
  } catch (reason) {
    importActionError.value = reason
  } finally {
    importSubmitting.value = false
  }
}

async function handleImportDone(task: TaskVO) {
  const jobId = task.result?.resourceId || task.resourceId
  if (!jobId) {
    importActionError.value = new Error('任务完成但未返回岗位ID，请在任务中心核对')
    return
  }
  importedJobId.value = jobId

  await retryImportedJob()
}

async function retryImportedJob() {
  if (!importedJobId.value) return
  importRetrying.value = true
  importActionError.value = null
  try {
    const job = await jobApi.get(importedJobId.value)
    replaceJob(job)
    await loadPersonalJobs()
    importForm.title = ''
    importForm.companyName = ''
    importForm.jdText = ''
    importForm.sourceUrl = ''
    importFormRef.value?.clearValidate()
    jobDetailRequestId.value = job.id
    jobDetail.value = job
    jobDetailError.value = null
    jobDrawerVisible.value = true
    setQuery({ tab: 'personal', jobId: job.id })
    ElMessage.success('个人JD已解析并读取岗位详情')
  } catch (reason) {
    importActionError.value = reason
    await loadPersonalJobs()
  } finally {
    importRetrying.value = false
  }
}

async function retryImport() {
  importRetrying.value = true
  importActionError.value = null
  try {
    if (importTask.value) importTaskId.value = (await retryTrackedTask(importTask.value)).id
    else if (lastImportBody.value) importTaskId.value = (await jobApi.importJd(lastImportBody.value)).id
  } catch (reason) {
    importActionError.value = reason
  } finally {
    importRetrying.value = false
  }
}

const comparisonJob = ref<JobVO | null>(null)
const comparisonHistoryOpen = ref(false)

function startComparison(job: JobVO) {
  comparisonJob.value = job
  jobDrawerVisible.value = false
  activeTab.value = 'matches'
}

function loadActiveTab() {
  if (activeTab.value !== 'search' && activeTab.value !== 'matches') void loadCollection()
}

watch(activeTab, loadActiveTab, { immediate: true })
watch(() => [activeTab.value, queryValue(route.query.jobId)] as const, ([tab, id]) => {
  if (tab !== 'matches' && id) void openJobById(id, false)
}, { immediate: true })
</script>

<template>
  <div class="page job-match-page" :class="{ 'job-match-page--search': activeTab === 'search', 'job-match-page--comparison': activeTab === 'matches' }">
    <header class="page-header">
      <div>
        <h1><el-icon v-if="activeTab !== 'search' && activeTab !== 'matches'"><Aim /></el-icon>岗位推荐与投递</h1>
        <p v-if="activeTab !== 'search' && activeTab !== 'matches'">搜索岗位、比较匹配度，并将感兴趣的机会加入投递计划。</p>
      </div>
      <div v-if="activeTab !== 'search'" class="page-header__actions">
        <el-button v-if="activeTab === 'matches'" plain @click="comparisonHistoryOpen = true">历史对比</el-button>
        <el-button v-else type="primary" @click="activeTab = 'matches'">对比差距</el-button>
      </div>
    </header>

    <el-tabs v-model="activeTab" class="job-tabs">
      <el-tab-pane label="AI 搜索" name="search" />
      <el-tab-pane label="可信岗位" name="trusted" />
      <el-tab-pane label="个人 JD" name="personal" />
      <el-tab-pane label="收藏" name="favorites" />
      <el-tab-pane label="匹配结果" name="matches" />
    </el-tabs>

    <JobSearchView v-if="activeTab === 'search'" @plan="planApplication" :key="String(route.query.searchId || route.query.taskId || 'current')" />
    <template v-else-if="activeTab !== 'matches'">
      <section v-if="activeTab === 'trusted'" class="panel">
        <div class="filter-bar">
          <el-input v-model="trustedFilters.q" class="filter-keyword" placeholder="搜索职位关键词" maxlength="100" clearable @keyup.enter="applyTrustedFilters" @clear="applyTrustedFilters" />
          <el-select v-model="trustedFilters.cityCode" class="filter-control" placeholder="全部城市" clearable @change="applyTrustedFilters">
            <el-option v-for="option in cityOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-select v-model="trustedFilters.candidateType" class="filter-control" placeholder="全部求职类型" clearable @change="applyTrustedFilters">
            <el-option v-for="option in candidateOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-button type="primary" :icon="Search" @click="applyTrustedFilters">查询</el-button>
          <el-button @click="resetTrustedFilters">重置</el-button>
        </div>
        <p class="filter-note">仅展示有效、来源已核验且在校验有效期内的公共岗位。</p>
      </section>

      <section v-if="activeTab === 'personal'" class="panel">
        <div class="panel__heading">
          <h2>导入个人目标JD</h2>
          <span class="muted">只展示你主动填写、粘贴或保存的岗位，不进入公共可信岗位库</span>
        </div>
        <el-form ref="importFormRef" :model="importForm" :rules="importRules" label-position="top">
          <div class="form-grid">
            <el-form-item label="职位名称" prop="title">
              <el-input v-model="importForm.title" maxlength="100" show-word-limit placeholder="例如：Java 后端开发" />
            </el-form-item>
            <el-form-item label="公司名称" prop="companyName">
              <el-input v-model="importForm.companyName" maxlength="100" show-word-limit placeholder="请输入公司名称" />
            </el-form-item>
          </div>
          <el-form-item label="JD正文（50至30000字符）" prop="jdText">
            <el-input v-model="importForm.jdText" type="textarea" :rows="8" maxlength="30000" show-word-limit placeholder="粘贴完整岗位描述、职责与资格要求" />
          </el-form-item>
          <el-form-item label="来源地址（可选，仅保存线索，不抓取网页）" prop="sourceUrl">
            <el-input v-model="importForm.sourceUrl" placeholder="https://example.com/job" clearable />
          </el-form-item>
          <el-button type="primary" :icon="Plus" :loading="importSubmitting" :disabled="taskIsActive(importTask)" @click="submitImport">提交并解析JD</el-button>
        </el-form>
        <el-alert v-if="importActionError" class="task-alert" :title="errorMessage(importActionError)" type="error" :closable="false" show-icon>
          <el-button v-if="importTask?.status === 'SUCCEEDED' && importedJobId" size="small" :loading="importRetrying" @click="retryImportedJob">重新读取岗位详情</el-button>
          <el-button v-else-if="lastImportBody && (!importTask || canRetryTrackedTask(importTask))" size="small" :loading="importRetrying" @click="retryImport">按原参数重试</el-button>
        </el-alert>
        <TaskStatus v-if="importTaskId" :task="importTask" :loading="importTracker.loading.value" :reasoning="importTracker.reasoningText.value" :tracking-error="importTrackingError" :refreshing="importTracker.refreshing.value" @refresh="importTracker.refresh">
          <template #actions>
            <el-button v-if="importTask && canRetryTrackedTask(importTask)" size="small" type="primary" :loading="importRetrying" @click="retryImport">按原参数重试</el-button>
          </template>
        </TaskStatus>
      </section>

      <section class="panel">
        <div class="panel__heading">
          <div>
            <h2>{{ collectionTitle }}</h2>
            <span v-if="activeTab === 'personal'" class="muted">服务端共 {{ personalTotal }} 条，当前页 {{ personalJobs.length }} 条</span>
          </div>
          <el-button :icon="Refresh" :loading="collectionLoading" @click="loadCollection">刷新</el-button>
        </div>

        <AsyncState :loading="collectionLoading" :error="collectionError" :empty="collectionEmpty" :empty-text="activeTab === 'personal' ? '还没有个人 JD，可粘贴岗位描述或从 AI 搜索保存' : activeTab === 'favorites' ? '暂无收藏岗位' : '没有符合条件的可信岗位'" @retry="loadCollection">
          <div class="list-stack">
            <article v-for="job in collectionJobs" :key="job.id" class="job-card">
              <div class="job-card__content">
                <div class="job-card__heading">
                  <div>
                    <h3>{{ job.title }}</h3>
                    <p>{{ job.companyName }}</p>
                  </div>
                  <div class="job-card__tags">
                    <el-tag :type="statusTagType(job.status)" size="small">{{ job.status === 'ACTIVE' ? '可匹配' : commonStatusLabels[job.status] || job.status }}</el-tag>
                    <el-tag size="small" effect="plain">{{ candidateTypeLabels[job.candidateType] || job.candidateType }}</el-tag>
                  </div>
                </div>
                <div class="job-card__meta">
                  <span>{{ cityLabels[job.cityCode || ''] || job.cityCode || '城市未明确' }}</span>
                  <span>{{ formatSalary(job.minMonthlySalaryCents, job.maxMonthlySalaryCents) }}</span>
                  <span>JD v{{ job.jobVersion }}</span>
                </div>
                <p class="job-card__excerpt">{{ job.jdText }}</p>
                <div class="job-card__source">
                  <span>{{ job.source.name }} · {{ sourceTypeLabel(job.source.type) }}</span>
                  <el-tag size="small" :type="job.source.verificationStatus === 'VERIFIED' ? 'success' : 'warning'" effect="plain">{{ verificationLabel(job.source.verificationStatus) }}</el-tag>
                  <span>校验：{{ formatDate(job.verifiedAt) }}</span>
                </div>
                <div class="actions">
                  <el-button type="primary" plain @click="openJobById(job.id)">查看岗位与要求</el-button>
                  <el-button v-if="job.status === 'ACTIVE' && job.requirements" @click="startComparison(job)">对比差距</el-button>
                  <el-button v-if="sourceIsPersonal(job) && job.status === 'ACTIVE' && job.requirements" type="primary" plain @click="optimizeForJob(job)">针对该岗位优化简历</el-button>
                  <el-button v-if="!sourceIsPersonal(job) && job.status === 'ACTIVE' && job.requirements" :loading="personalSavingIds.includes(job.id)" @click="saveToPersonal(job)">保存到个人 JD</el-button>
                  <el-button :loading="favoritePendingIds.includes(job.id)" @click="toggleFavorite(job)">
                    <el-icon><StarFilled v-if="job.isFavorite" /><Star v-else /></el-icon>
                    {{ job.isFavorite ? '取消收藏' : '收藏' }}
                  </el-button>
                </div>
              </div>
            </article>
          </div>
        </AsyncState>
        <div v-if="activeTab === 'trusted' || activeTab === 'favorites' || activeTab === 'personal'" class="pagination">
          <el-pagination background layout="total, sizes, prev, pager, next" :total="collectionTotal" :current-page="collectionCurrent" :page-size="collectionSize" :page-sizes="[6, 12, 24]" @current-change="changeCollectionPage" @size-change="changeCollectionSize" />
        </div>
      </section>
    </template>

    <JobComparison v-else v-model:history-open="comparisonHistoryOpen" :initial-job="comparisonJob" />

    <el-drawer v-model="jobDrawerVisible" title="岗位完整详情" size="720px" class="job-drawer">
      <AsyncState :loading="jobDetailLoading" :error="jobDetailError" :empty="!jobDetail" @retry="openJobById(jobDetailRequestId)">
        <template v-if="jobDetail">
          <div class="drawer-job-heading">
            <div>
              <h2>{{ jobDetail.title }}</h2>
              <p>{{ jobDetail.companyName }}</p>
            </div>
            <el-tag :type="statusTagType(jobDetail.status)">{{ jobDetail.status === 'ACTIVE' ? '可匹配' : commonStatusLabels[jobDetail.status] || jobDetail.status }}</el-tag>
          </div>
          <div class="actions drawer-actions">
            <el-button :loading="favoritePendingIds.includes(jobDetail.id)" @click="toggleFavorite(jobDetail)">{{ jobDetail.isFavorite ? '取消收藏' : '收藏岗位' }}</el-button>
            <el-button v-if="jobDetail.source.type === 'USER_INPUT' && ['ACTIVE', 'FAILED'].includes(jobDetail.status)" type="primary" plain @click="openFieldCorrection">{{ jobDetail.status === 'FAILED' ? '人工补全 JD 字段' : '核对提取结果' }}</el-button>
            <el-button v-if="jobDetail.status === 'ACTIVE' && jobDetail.requirements" @click="startComparison(jobDetail)">对比差距</el-button>
            <el-button v-if="!sourceIsPersonal(jobDetail) && jobDetail.status === 'ACTIVE' && jobDetail.requirements" :loading="personalSavingIds.includes(jobDetail.id)" @click="saveToPersonal(jobDetail)">保存到个人 JD</el-button>
            <el-button v-if="sourceIsPersonal(jobDetail) && jobDetail.status === 'ACTIVE' && jobDetail.requirements" type="primary" @click="optimizeForJob(jobDetail)">针对该岗位优化简历</el-button>
          </div>
          <el-alert v-if="fieldCorrectionError && !fieldCorrectionVisible" class="inline-alert" type="error" :title="errorMessage(fieldCorrectionError)" :closable="false" show-icon />
          <el-descriptions :column="2" border>
            <el-descriptions-item label="城市">{{ cityLabels[jobDetail.cityCode || ''] || jobDetail.cityCode || '未明确' }}</el-descriptions-item>
            <el-descriptions-item label="求职类型">{{ candidateTypeLabels[jobDetail.candidateType] || jobDetail.candidateType }}</el-descriptions-item>
            <el-descriptions-item label="薪资">{{ formatSalary(jobDetail.minMonthlySalaryCents, jobDetail.maxMonthlySalaryCents) }}</el-descriptions-item>
            <el-descriptions-item label="JD版本">v{{ jobDetail.jobVersion }}</el-descriptions-item>
          </el-descriptions>
          <p v-if="jobDetail.fieldEvidence?.city" class="muted">地点原文：{{ jobDetail.fieldEvidence.city }}</p>
          <p v-else-if="jobDetail.source.type === 'USER_INPUT'" class="muted">地点暂无可追溯的明确原文，请核对 JD。</p>
          <p v-if="jobDetail.fieldEvidence?.confirmedQuote" class="muted">人工确认依据：{{ jobDetail.fieldEvidence.confirmedQuote }} · {{ jobDetail.fieldEvidence.correctionReason }}</p>
          <el-collapse v-if="jobDetail.revisions?.length" class="drawer-section">
            <el-collapse-item title="原解析与修订历史" name="revisions">
              <article v-for="revision in jobDetail.revisions" :key="revision.versionNo">
                <h4>JD v{{ revision.versionNo }} · {{ formatDate(revision.createdAt) }}</h4>
                <p>{{ revision.reason }}</p>
                <p>城市：{{ cityLabels[revision.cityCode || ''] || revision.cityCode || '未明确' }}</p>
                <p>技能：{{ revision.requirements?.skills.join('、') || '未提取' }}</p>
                <p v-if="revision.sourceQuote" class="pre-wrap">原文依据：{{ revision.sourceQuote }}</p>
              </article>
            </el-collapse-item>
          </el-collapse>
          <section class="drawer-section">
            <h3>岗位JD</h3>
            <div class="pre-wrap jd-full-text">{{ jobDetail.jdText }}</div>
          </section>
          <section class="drawer-section">
            <h3>资格要求</h3>
            <template v-if="jobDetail.requirements">
              <el-descriptions :column="1" border size="small">
                <el-descriptions-item label="最低学历">{{ jobDetail.requirements.educationMin ? educationLabels[jobDetail.requirements.educationMin] : '未明确' }}</el-descriptions-item>
                <el-descriptions-item label="毕业年份">{{ jobDetail.requirements.graduationYearMin ?? '未明确' }} 至 {{ jobDetail.requirements.graduationYearMax ?? '未明确' }}</el-descriptions-item>
                <el-descriptions-item label="最低经验">{{ jobDetail.requirements.experienceMonthsMin == null ? '未明确' : `${jobDetail.requirements.experienceMonthsMin}个月` }}</el-descriptions-item>
              </el-descriptions>
              <h4>技能要求</h4>
              <div class="skill-tags"><el-tag v-for="skill in jobDetail.requirements.skills" :key="skill">{{ skill }}<template v-if="jobDetail.requirements.requiredSkillLevels[skill] !== undefined"> · {{ jobDetail.requirements.requiredSkillLevels[skill] }}</template></el-tag></div>
              <p v-if="jobDetail.source.type === 'USER_INPUT' && !jobDetail.fieldEvidence?.confirmedQuote" class="muted">技能提取尚未经人工核对，请对照下方 JD 原文确认。</p>
              <div v-if="Object.keys(jobDetail.requirements.requiredSkillLevels).length" class="level-list">
                <span v-for="([skill, level]) in Object.entries(jobDetail.requirements.requiredSkillLevels)" :key="skill">{{ skill }}：{{ level }}</span>
              </div>
              <h4>要求原文引述</h4>
              <div v-if="Object.keys(jobDetail.requirements.quotes).length" class="quote-list">
                <blockquote v-for="([field, quote]) in Object.entries(jobDetail.requirements.quotes)" :key="field"><strong>{{ field }}</strong><p class="pre-wrap">{{ quote }}</p></blockquote>
              </div>
              <div v-else-if="jobDetail.fieldEvidence?.confirmedQuote" class="quote-list"><blockquote><strong>人工核对依据</strong><p class="pre-wrap">{{ jobDetail.fieldEvidence.confirmedQuote }}</p></blockquote></div>
              <p v-else class="muted">暂无原文引述</p>
            </template>
            <el-empty v-else :image-size="60" description="资格要求尚未解析" />
          </section>
          <section class="drawer-section">
            <h3>来源信息</h3>
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="来源名称">{{ jobDetail.source.name }}</el-descriptions-item>
              <el-descriptions-item label="来源类型">{{ sourceTypeLabel(jobDetail.source.type) }}</el-descriptions-item>
              <el-descriptions-item label="核验状态">{{ verificationLabel(jobDetail.source.verificationStatus) }}</el-descriptions-item>
              <el-descriptions-item label="来源校验时间">{{ formatDate(jobDetail.source.verifiedAt) }}</el-descriptions-item>
              <el-descriptions-item label="岗位校验时间">{{ formatDate(jobDetail.verifiedAt) }}</el-descriptions-item>
              <el-descriptions-item label="来源基础地址">{{ jobDetail.source.baseUrl || '未提供' }}</el-descriptions-item>
            </el-descriptions>
            <el-button v-if="httpUrl(jobDetail.sourceUrl)" class="source-link" type="primary" plain @click="openSource(jobDetail.sourceUrl)">前往投递</el-button>
            <p v-else class="muted">未提供可安全打开的http/https来源地址</p>
            <el-button class="source-link" @click="planApplication(jobDetail)">添加投递计划</el-button>
          </section>
        </template>
      </AsyncState>
    </el-drawer>

    <el-dialog v-model="fieldCorrectionVisible" title="核对个人 JD 提取结果" width="600px" class="responsive-dialog" :close-on-click-modal="false">
      <p class="muted">请选择工作城市与技能，粘贴一段支持修改的 JD 原文。自动分析失败时也可人工补全；保存后生成新的 JD 版本，旧匹配需要重新创建。</p>
      <el-form label-position="top">
        <el-form-item label="工作城市"><el-select v-model="fieldCorrection.cityCode" filterable clearable placeholder="原文未明确时留空" class="full-width"><el-option v-for="city in cityOptions" :key="city.value" :label="city.label" :value="city.value" /></el-select></el-form-item>
        <el-form-item label="技能要求"><el-select v-model="fieldCorrection.skillCodes" multiple filterable clearable placeholder="核对 JD 中提及的技术" class="full-width"><el-option v-for="skill in correctionSkills" :key="skill.code" :label="skill.name" :value="skill.code" /></el-select></el-form-item>
        <el-form-item label="JD 原文依据"><el-input v-model="fieldCorrection.sourceQuote" type="textarea" :rows="3" maxlength="1000" placeholder="从上方 JD 原文复制一段支持修改的内容" /></el-form-item>
        <el-form-item label="纠正原因"><el-input v-model="fieldCorrection.reason" type="textarea" :rows="2" maxlength="500" placeholder="说明原提取结果哪里有误" /></el-form-item>
      </el-form>
      <el-alert v-if="fieldCorrectionError" class="inline-alert" type="error" :title="errorMessage(fieldCorrectionError)" :closable="false" show-icon />
      <template #footer><el-button @click="fieldCorrectionVisible = false">取消</el-button><el-button type="primary" :disabled="!fieldCorrection.sourceQuote.trim() || !fieldCorrection.reason.trim()" :loading="fieldCorrectionPending" @click="saveFieldCorrection">确认并保存新版本</el-button></template>
    </el-dialog>

    <ApplicationForm v-model:visible="applicationVisible" mode="create" :baseline="null" :plan="applicationPlan" />
  </div>
</template>

<style scoped lang="scss">
.job-match-page { padding-bottom: 28px; }
.page-header h1 { display: flex; align-items: center; gap: 10px; }
.job-tabs { margin-bottom: 16px; padding: 0 16px; border: 1px solid var(--ai-border-light, #e8f0fa); border-radius: $ai-radius-lg; background: var(--ai-bg-card, #fff); }
.job-match-page--comparison .job-tabs { margin-bottom: 24px; padding: 0; border: 0; background: transparent; }
.job-match-page--comparison .job-tabs :deep(.el-tabs__header) { margin: 0; }
.job-match-page--comparison .job-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; }
.job-match-page--search .job-tabs { margin-bottom: 30px; padding: 0; border: 0; border-radius: 0; background: transparent; }
.job-match-page--search .job-tabs :deep(.el-tabs__header) { margin: 0; }
.job-match-page--search .job-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; }
.job-match-page--search .job-tabs :deep(.el-tabs__item) { font-size: 15px; }
@media (min-width: 1061px) {
  .job-match-page--search { padding: 10px 24px 28px; }
  .job-match-page--search .page-header h1 { font-size: 30px; font-weight: 700; }
}
.filter-keyword { width: min(320px, 100%); }
.filter-control { width: 170px; }
.filter-note { margin: 0; color: $ai-text-secondary; font-size: 13px; }
.task-alert { margin: 16px 0; }
.qualification-alert { margin-top: 12px; }
.task-alert :deep(.el-alert__content) { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.reference-failures { display: grid; gap: 8px; margin-bottom: 16px; }
.job-card { display: flex; gap: 12px; padding: 18px; border: 1px solid $ai-border; border-radius: $ai-radius; background: var(--ai-bg-card, #fff); transition: border-color .2s, box-shadow .2s; }
.job-card:hover { border-color: var(--ai-accent-border, #b8ccf4); box-shadow: $ai-shadow-hover; }
.job-card__content { min-width: 0; flex: 1; }
.job-card__heading { display: flex; justify-content: space-between; gap: 16px; }
.job-card__heading h3 { margin: 0 0 6px; color: var(--ai-text-primary, #101d4b); font-size: 18px; }
.job-card__heading p { margin: 0; color: $ai-text-secondary; }
.job-card__tags, .skill-tags { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; }
.job-card__meta, .job-card__source { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 18px; margin-top: 12px; color: $ai-text-secondary; font-size: 13px; }
.job-card__excerpt { display: -webkit-box; overflow: hidden; margin: 14px 0 0; color: $ai-text-primary; line-height: 1.7; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.job-card__source { padding-top: 12px; border-top: 1px dashed $ai-border; }
.job-card .actions { margin-top: 14px; }
.drawer-job-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 14px; }
.drawer-job-heading h2 { margin: 0 0 8px; color: var(--ai-text-primary, #101d4b); }
.drawer-job-heading p { margin: 0; color: $ai-text-secondary; }
.drawer-actions { margin-bottom: 16px; }
.drawer-section { margin-top: 24px; }
.drawer-section h3 { margin: 0 0 12px; color: var(--ai-text-primary, #101d4b); font-size: 17px; }
.drawer-section h4 { margin: 18px 0 10px; font-size: 14px; }
.drawer-section .skill-tags { justify-content: flex-start; }
.jd-full-text { max-height: 420px; overflow-y: auto; padding: 14px; border-radius: $ai-radius; background: $ai-bg-page; }
.level-list { display: flex; flex-wrap: wrap; gap: 8px 16px; margin-top: 10px; color: $ai-text-secondary; }
.quote-list { display: grid; gap: 10px; }
.quote-list blockquote { margin: 0; padding: 12px; border-left: 3px solid $ai-primary; background: $ai-bg-page; }
.quote-list p { margin: 6px 0 0; }
.source-link { margin: 12px 0; }
:global(.job-drawer) { max-width: 100%; }
:global(.responsive-dialog) { max-width: calc(100vw - 24px); }
@media (max-width: 700px) {
  .job-tabs { padding: 0 8px; overflow-x: auto; }
  .job-match-page--comparison .job-tabs :deep(.el-tabs__item) { padding-inline: 7px; font-size: 12px; }
  .filter-keyword, .filter-control { width: 100%; }
  .job-card { padding: 13px; }
  .job-card__heading { flex-direction: column; }
  .job-card__tags { justify-content: flex-start; }
}
</style>
