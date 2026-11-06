<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Aim, Plus, Refresh, Search, Star, StarFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { jobApi } from '@/api/jobs'
import { learningApi } from '@/api/learning'
import { profileApi } from '@/api/profile'
import { canRetryTrackedTask, retryTrackedTask } from '@/api/task-registry'
import type { JobVO, MatchBatchVO, MatchItemVO, Page, ProfileVO, TaskVO } from '@/api/types'
import { useTask } from '@/composables/useTask'
import AsyncState from '@/components/AsyncState.vue'
import TaskStatus from '@/components/TaskStatus.vue'

import { candidateTypeLabels, cityLabels, commonStatusLabels, educationLabels, errorMessage, formatDate, formatSalary } from '@/utils/format'

type JobTab = 'trusted' | 'personal' | 'favorites' | 'matches'
type EligibilityStatus = 'PASS' | 'UNKNOWN' | 'FAIL'
type ImportBody = Parameters<typeof jobApi.importJd>[0]
type MatchBody = Parameters<typeof jobApi.createMatch>[0]
type PlanBody = Parameters<typeof learningApi.createPlan>[0]

const route = useRoute()
const router = useRouter()
const allowedTabs: JobTab[] = ['trusted', 'personal', 'favorites', 'matches']
const initialQueryTab = String(route.query.tab || '') as JobTab
const activeTab = ref<JobTab>(allowedTabs.includes(initialQueryTab) ? initialQueryTab : route.query.matchId ? 'matches' : 'trusted')
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

function rawValue(value?: number | null) {
  return value === null || value === undefined || !Number.isFinite(value) ? '待评估' : String(value)
}

function sourceTypeLabel(type: string) {
  return ({ OFFICIAL: '企业官方', AUTHORIZED: '授权来源', PUBLIC_SEARCH: '公开搜索', USER_INPUT: '个人导入' } as Record<string, string>)[type] || type
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
const collectionTitle = computed(() => ({ trusted: '可信岗位', personal: '我导入的岗位', favorites: '我的收藏', matches: '岗位列表' } as Record<JobTab, string>)[activeTab.value])
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

const selectedJobIds = ref<string[]>([])
const knownJobs = computed(() => {
  const jobs = new Map<string, JobVO>()
  ;[...trustedJobs.value.records, ...favoriteJobs.value.records, ...personalJobs.value].forEach(job => jobs.set(job.id, job))
  return jobs
})

function toggleJobSelection(jobId: string, selected: boolean) {
  if (selected && !selectedJobIds.value.includes(jobId)) {
    if (selectedJobIds.value.length >= 20) {
      ElMessage.warning('一次最多选择20个岗位')
      return
    }
    selectedJobIds.value.push(jobId)
  } else if (!selected) {
    selectedJobIds.value = selectedJobIds.value.filter(id => id !== jobId)
  }
}

const profile = ref<ProfileVO | null>(null)
const profileLoading = ref(false)
const profileError = ref<unknown>(null)
const includeUnknown = ref(true)
const matchSubmitting = ref(false)
const matchRetrying = ref(false)
const matchActionError = ref<unknown>(null)
const matchTaskId = ref<string | null>(null)
const lastMatchBody = ref<MatchBody | null>(null)
const matchTracker = useTask(matchTaskId, { onDone: handleMatchDone })
const matchTask = matchTracker.task
const matchTrackingError = matchTracker.error

async function loadProfile() {
  profileLoading.value = true
  profileError.value = null
  try {
    profile.value = await profileApi.get()
  } catch (reason) {
    profileError.value = reason
  } finally {
    profileLoading.value = false
  }
}

async function createMatch() {
  matchTaskId.value = null
  lastMatchBody.value = null
  matchSubmitting.value = true
  matchActionError.value = null
  try {
    const authorityProfile = await profileApi.get()
    profile.value = authorityProfile
    profileError.value = null
    const body: MatchBody = {
      expectedProfileVersion: authorityProfile.profileVersion,
      ...(selectedJobIds.value.length ? { jobIds: [...selectedJobIds.value] } : {}),
      includeUnknown: includeUnknown.value,
    }
    lastMatchBody.value = body

    matchTaskId.value = (await jobApi.createMatch(body)).id
  } catch (reason) {
    matchActionError.value = reason
  } finally {
    matchSubmitting.value = false
  }
}

async function retryMatch() {
  matchRetrying.value = true
  matchActionError.value = null
  try {
    if (matchTask.value) matchTaskId.value = (await retryTrackedTask(matchTask.value)).id
    else if (lastMatchBody.value) matchTaskId.value = (await jobApi.createMatch(lastMatchBody.value)).id
  } catch (reason) {
    matchActionError.value = reason
  } finally {
    matchRetrying.value = false
  }
}

const matchBatches = ref<Page<MatchBatchVO>>(emptyPage(5))
const matchesLoading = ref(false)
const matchesError = ref<unknown>(null)
const selectedMatch = ref<MatchBatchVO | null>(null)
const matchDetailRequestId = ref('')
const matchDetailLoading = ref(false)
const matchDetailError = ref<unknown>(null)

async function loadMatches() {
  matchesLoading.value = true
  matchesError.value = null
  try {
    matchBatches.value = await jobApi.listMatches({ current: matchBatches.value.current, size: matchBatches.value.size })
  } catch (reason) {
    matchesError.value = reason
  } finally {
    matchesLoading.value = false
  }
}

async function loadMatch(matchId: string, syncRoute = true) {
  if (!matchId) return
  matchDetailRequestId.value = matchId
  selectedMatch.value = null
  matchDetailLoading.value = true
  matchDetailError.value = null
  try {
    selectedMatch.value = await jobApi.getMatch(matchId)
    if (syncRoute) setQuery({ tab: 'matches', matchId })
  } catch (reason) {
    matchDetailError.value = reason
  } finally {
    matchDetailLoading.value = false
  }
}

async function handleMatchDone(task: TaskVO) {
  const matchId = task.result?.resourceId || task.resourceId
  if (!matchId) {
    matchActionError.value = new Error('任务完成但未返回匹配批次ID')
    return
  }
  await Promise.all([loadMatch(matchId), loadMatches()])
  if (!matchDetailError.value) ElMessage.success('匹配结果已生成并读取完整批次')
}

function changeMatchPage(page: number) {
  matchBatches.value.current = page
  void loadMatches()
}

function changeMatchSize(size: number) {
  matchBatches.value.size = size
  matchBatches.value.current = 1
  void loadMatches()
}

const matchGroups = computed(() => {
  const statuses: EligibilityStatus[] = ['PASS', 'UNKNOWN', 'FAIL']
  const items = selectedMatch.value?.items || []
  return statuses.map(status => ({
    status,
    items: items
      .filter(item => item.eligibility.status === status)
      .sort((left, right) => {
        const leftScore = left.matchScore
        const rightScore = right.matchScore
        const leftValid = leftScore !== null && Number.isFinite(leftScore)
        const rightValid = rightScore !== null && Number.isFinite(rightScore)
        if (leftValid && rightValid) return rightScore - leftScore
        if (leftValid) return -1
        if (rightValid) return 1
        return 0
      }),
  }))
})

function groupDescription(status: EligibilityStatus) {
  if (status === 'PASS') return '硬性资格已通过，可进入推荐；组内按有效匹配分降序排列。'
  if (status === 'UNKNOWN') return '部分资格缺少足够证据，需要人工确认，不等同于资格不符。'
  return '仅用于展示资格核验结果，不进入推荐主列表，也不创建训练计划。'
}

function componentLabel(key: string) {
  return ({ skill: '技能匹配', semantic: '语义匹配', preference: '求职偏好' } as Record<string, string>)[key] || key
}

function evidenceLabel(status: string) {
  return ({ MISSING: '缺少证据', LIMITED: '证据有限', SUFFICIENT: '证据充分' } as Record<string, string>)[status] || status
}

function goInterview(item: MatchItemVO) {
  void router.push({ path: '/interview', query: { jobId: item.jobId } })
}

const planDialogVisible = ref(false)
const planTarget = ref<MatchItemVO | null>(null)
const planSourceStale = ref(false)
const planForm = reactive({ dailyMinutes: 30, days: 7 })
const planSubmitting = ref(false)
const planRetrying = ref(false)
const planActionError = ref<unknown>(null)
const planTaskId = ref<string | null>(null)
const lastPlanBody = ref<PlanBody | null>(null)
const createdPlanId = ref<string | null>(null)
const planAuthorityLoading = ref(false)
const planTracker = useTask(planTaskId, { onDone: handlePlanDone })
const planTask = planTracker.task
const planTrackingError = planTracker.error
const planTaskActive = computed(() => taskIsActive(planTask.value) || planSubmitting.value || planAuthorityLoading.value)

function openPlanDialog(item: MatchItemVO) {
  if (!selectedMatch.value || selectedMatch.value.isStale || selectedMatch.value.status !== 'READY' || item.eligibility.status === 'FAIL') return
  planTarget.value = item
  planSourceStale.value = selectedMatch.value.isStale
  planActionError.value = null
  planTaskId.value = null
  createdPlanId.value = null
  planDialogVisible.value = true
}

async function submitPlan() {
  if (!planTarget.value || planSourceStale.value || selectedMatch.value?.isStale) {
    ElMessage.error('历史匹配已过期，请重新运行匹配后再创建计划')
    return
  }
  const body: PlanBody = { matchItemId: planTarget.value.id, dailyMinutes: planForm.dailyMinutes, days: planForm.days }
  lastPlanBody.value = body
  planTaskId.value = null
  createdPlanId.value = null
  planSubmitting.value = true
  planActionError.value = null
  try {
    planTaskId.value = (await learningApi.createPlan(body)).id
  } catch (reason) {
    planActionError.value = reason
  } finally {
    planSubmitting.value = false
  }
}

async function fetchCreatedPlan(planId: string) {
  planAuthorityLoading.value = true
  planActionError.value = null
  try {
    await learningApi.getPlan(planId)
    planDialogVisible.value = false
    ElMessage.success('训练计划已生成')
    await router.push({ path: '/training', query: { planId } })
  } catch (reason) {
    planActionError.value = reason
  } finally {
    planAuthorityLoading.value = false
  }
}

async function handlePlanDone(task: TaskVO) {
  const planId = task.result?.resourceId || task.resourceId
  if (!planId) {
    planActionError.value = new Error('任务完成但未返回训练计划ID')
    return
  }
  createdPlanId.value = planId
  await fetchCreatedPlan(planId)
}

async function retryPlan() {
  planRetrying.value = true
  planActionError.value = null
  try {
    if (planTask.value) planTaskId.value = (await retryTrackedTask(planTask.value)).id
    else if (lastPlanBody.value) planTaskId.value = (await learningApi.createPlan(lastPlanBody.value)).id
  } catch (reason) {
    planActionError.value = reason
  } finally {
    planRetrying.value = false
  }
}

function loadActiveTab() {
  if (activeTab.value === 'matches') {
    void loadProfile()
    void loadMatches()
  } else {
    void loadCollection()
  }
}

watch(activeTab, tab => {
  setQuery({ tab })
  loadActiveTab()
})

onMounted(() => {
  loadActiveTab()
  const jobId = queryValue(route.query.jobId)
  const matchId = queryValue(route.query.matchId)
  if (jobId) void openJobById(jobId, false)
  if (matchId) void loadMatch(matchId, false)
})
</script>

<template>
  <div class="page job-match-page">
    <header class="page-header">
      <div>
        <h1><el-icon><Aim /></el-icon> 岗位匹配</h1>
        <p>基于已确认画像核验资格、解释匹配差距，并将有效结果用于训练或模拟面试。</p>
      </div>
      <div class="page-header__actions">
        <el-tag v-if="selectedJobIds.length" type="primary" effect="plain">已选 {{ selectedJobIds.length }} / 20 个岗位</el-tag>
        <el-button v-if="selectedJobIds.length" @click="selectedJobIds = []">清空选择</el-button>
        <el-button type="primary" @click="activeTab = 'matches'">创建匹配</el-button>
      </div>
    </header>

    <el-tabs v-model="activeTab" class="job-tabs">
      <el-tab-pane label="可信岗位" name="trusted" />
      <el-tab-pane label="个人JD" name="personal" />
      <el-tab-pane label="收藏" name="favorites" />
      <el-tab-pane label="匹配结果" name="matches" />
    </el-tabs>

    <template v-if="activeTab !== 'matches'">
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
          <span class="muted">仅本人可见，不进入公共可信岗位库</span>
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
        <TaskStatus v-if="importTask" :task="importTask">
          <template #actions>
            <el-button v-if="canRetryTrackedTask(importTask)" size="small" type="primary" :loading="importRetrying" @click="retryImport">按原参数重试</el-button>
          </template>
        </TaskStatus>
        <div v-if="importTrackingError" class="tracking-error">
          <span>{{ errorMessage(importTrackingError) }}</span>
          <el-button size="small" @click="importTracker.restart">恢复任务跟踪</el-button>
        </div>
      </section>

      <section class="panel">
        <div class="panel__heading">
          <div>
            <h2>{{ collectionTitle }}</h2>
            <span v-if="activeTab === 'personal'" class="muted">服务端共 {{ personalTotal }} 条，当前页 {{ personalJobs.length }} 条</span>
          </div>
          <el-button :icon="Refresh" :loading="collectionLoading" @click="loadCollection">刷新</el-button>
        </div>

        <AsyncState :loading="collectionLoading" :error="collectionError" :empty="collectionEmpty" :empty-text="activeTab === 'personal' ? '当前设备尚未导入个人JD' : activeTab === 'favorites' ? '暂无收藏岗位' : '没有符合条件的可信岗位'" @retry="loadCollection">
          <div class="list-stack">
            <article v-for="job in collectionJobs" :key="job.id" class="job-card">
              <div class="job-card__selector">
                <el-checkbox :model-value="selectedJobIds.includes(job.id)" :aria-label="`选择${job.title}`" @change="toggleJobSelection(job.id, Boolean($event))" />
              </div>
              <div class="job-card__content">
                <div class="job-card__heading">
                  <div>
                    <h3>{{ job.title }}</h3>
                    <p>{{ job.companyName }}</p>
                  </div>
                  <div class="job-card__tags">
                    <el-tag :type="statusTagType(job.status)" size="small">{{ commonStatusLabels[job.status] || job.status }}</el-tag>
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

    <template v-else>
      <section class="panel">
        <div class="panel__heading">
          <div>
            <h2>创建岗位匹配</h2>
            <p class="muted">不勾选岗位时，由后端按契约选择最多50个可信候选；显式选择最多20个。</p>
          </div>
          <el-tag v-if="profile" type="info">画像版本 v{{ profile.profileVersion }}</el-tag>
        </div>
        <AsyncState :loading="profileLoading && !profile" :error="profileError" @retry="loadProfile">
          <div class="match-setup">
            <div class="selected-jobs">
              <span class="selected-jobs__label">目标岗位</span>
              <template v-if="selectedJobIds.length">
                <el-tag v-for="id in selectedJobIds" :key="id" closable @close="toggleJobSelection(id, false)">{{ knownJobs.get(id)?.title || id }}</el-tag>
              </template>
              <span v-else class="muted">当前为空，将使用默认可信候选</span>
            </div>
            <div class="match-setup__controls">
              <el-switch v-model="includeUnknown" active-text="包含资格待确认项" inactive-text="不包含待确认项" />
              <el-button type="primary" :loading="matchSubmitting" :disabled="taskIsActive(matchTask)" @click="createMatch">{{ selectedJobIds.length ? `匹配已选 ${selectedJobIds.length} 个岗位` : '按默认候选创建匹配' }}</el-button>
            </div>
          </div>
        </AsyncState>
        <el-alert v-if="matchActionError" class="task-alert" :title="errorMessage(matchActionError)" type="error" :closable="false" show-icon>
          <el-button v-if="lastMatchBody && (!matchTask || canRetryTrackedTask(matchTask))" size="small" :loading="matchRetrying" @click="retryMatch">按原参数重试</el-button>
        </el-alert>
        <TaskStatus v-if="matchTask" :task="matchTask">
          <template #actions>
            <el-button v-if="canRetryTrackedTask(matchTask)" size="small" type="primary" :loading="matchRetrying" @click="retryMatch">按原参数重试</el-button>
          </template>
        </TaskStatus>
        <div v-if="matchTrackingError" class="tracking-error">
          <span>{{ errorMessage(matchTrackingError) }}</span>
          <el-button size="small" @click="matchTracker.restart">恢复任务跟踪</el-button>
        </div>
      </section>

      <div class="match-layout">
        <section class="panel match-history">
          <div class="panel__heading">
            <h2>历史批次</h2>
            <el-button :icon="Refresh" :loading="matchesLoading" @click="loadMatches">刷新</el-button>
          </div>
          <AsyncState :loading="matchesLoading" :error="matchesError" :empty="!matchBatches.records.length" empty-text="暂无匹配批次" @retry="loadMatches">
            <div class="list-stack">
              <button v-for="batch in matchBatches.records" :key="batch.id" type="button" class="batch-card" :class="{ 'batch-card--active': selectedMatch?.id === batch.id }" @click="loadMatch(batch.id)">
                <span class="batch-card__top">
                  <strong>画像 v{{ batch.profileVersion }}</strong>
                  <el-tag size="small" :type="statusTagType(batch.status)">{{ commonStatusLabels[batch.status] || batch.status }}</el-tag>
                </span>
                <span class="muted">{{ formatDate(batch.createdAt) }}</span>
                <span class="batch-card__id">{{ batch.id }}</span>
                <el-tag v-if="batch.isStale" size="small" type="danger">结果已过期</el-tag>
              </button>
            </div>
          </AsyncState>
          <div class="pagination">
            <el-pagination background small layout="prev, pager, next" :total="matchBatches.total" :current-page="matchBatches.current" :page-size="matchBatches.size" @current-change="changeMatchPage" />
            <el-select class="history-size" :model-value="matchBatches.size" size="small" @change="changeMatchSize">
              <el-option :label="`5 条/页`" :value="5" />
              <el-option :label="`10 条/页`" :value="10" />
              <el-option :label="`20 条/页`" :value="20" />
            </el-select>
          </div>
        </section>

        <section class="panel match-detail">
          <AsyncState :loading="matchDetailLoading" :error="matchDetailError" :empty="!selectedMatch" empty-text="请选择历史批次查看完整结果" @retry="loadMatch(matchDetailRequestId)">
            <template v-if="selectedMatch">
              <div class="panel__heading">
                <div>
                  <h2>匹配批次详情</h2>
                  <span class="muted">{{ selectedMatch.id }} · {{ formatDate(selectedMatch.createdAt) }}</span>
                </div>
                <el-tag :type="statusTagType(selectedMatch.status)">{{ commonStatusLabels[selectedMatch.status] || selectedMatch.status }}</el-tag>
              </div>
              <el-alert v-if="selectedMatch.isStale" class="stale-alert" title="该批次输入版本已过期" description="画像或岗位版本已变化，结果仅供历史核对，禁止基于此批次创建训练计划。请重新运行匹配。" type="error" :closable="false" show-icon />
              <el-descriptions :column="2" border size="small" class="batch-meta">
                <el-descriptions-item label="画像版本">v{{ selectedMatch.profileVersion }}</el-descriptions-item>
                <el-descriptions-item label="任务ID"><span class="break-all">{{ selectedMatch.taskId }}</span></el-descriptions-item>
              </el-descriptions>
              <el-alert v-if="selectedMatch.status === 'GENERATING'" title="批次仍在生成，请稍后刷新详情" type="warning" :closable="false" show-icon />
              <el-alert v-else-if="selectedMatch.status === 'FAILED'" title="该匹配批次生成失败，请从任务状态核对原因并重试" type="error" :closable="false" show-icon />
              <el-empty v-else-if="!selectedMatch.items.length" description="该批次没有匹配条目" />
              <div v-else class="match-groups">
                <section v-for="group in matchGroups" :key="group.status" class="match-group">
                  <div class="match-group__heading">
                    <div>
                      <h3><el-tag :type="statusTagType(group.status)">{{ group.status }}</el-tag> {{ group.status === 'PASS' ? '资格通过' : group.status === 'UNKNOWN' ? '资格待确认' : '资格不符' }}（{{ group.items.length }}）</h3>
                      <p>{{ groupDescription(group.status) }}</p>
                    </div>
                  </div>
                  <el-empty v-if="!group.items.length" :image-size="54" :description="`暂无${group.status}条目`" />
                  <div v-else class="list-stack">
                    <article v-for="item in group.items" :key="item.id" class="match-item">
                      <div class="match-item__heading">
                        <div>
                          <h4>{{ item.title }}</h4>
                          <span class="muted">岗位 {{ item.jobId }} · JD v{{ item.jobVersion }}</span>
                        </div>
                        <div v-if="item.eligibility.status !== 'FAIL'" class="match-score">
                          <strong>{{ rawValue(item.matchScore) }}</strong>
                          <span>匹配分</span>
                        </div>
                        <el-tag v-else type="danger">仅资格核验</el-tag>
                      </div>

                      <template v-if="item.eligibility.status !== 'FAIL'">
                        <div class="detail-block">
                          <h5>匹配分构成</h5>
                          <p class="muted">评分版本：{{ item.scoreVersion }}</p>
                          <el-table :data="item.components" size="small" border>
                            <el-table-column label="分项" min-width="110"><template #default="scope">{{ componentLabel(scope.row.key) }}</template></el-table-column>
                            <el-table-column label="原值" min-width="90"><template #default="scope">{{ rawValue(scope.row.value) }}</template></el-table-column>
                            <el-table-column label="权重" min-width="80"><template #default="scope">{{ rawValue(scope.row.weight) }}</template></el-table-column>
                            <el-table-column label="provenance" min-width="190"><template #default="scope"><span class="break-all">{{ scope.row.provenance }}</span></template></el-table-column>
                          </el-table>
                        </div>

                        <div class="detail-block">
                          <h5>推荐理由</h5>
                          <ul v-if="item.reasons.length" class="reason-list"><li v-for="(reason, index) in item.reasons" :key="index">{{ reason }}</li></ul>
                          <p v-else class="muted">后端未提供额外理由</p>
                        </div>

                        <div class="detail-block">
                          <h5>能力差距与证据</h5>
                          <div v-if="item.gaps.length" class="gap-list">
                            <div v-for="gap in item.gaps" :key="gap.skillCode" class="gap-card">
                              <div class="gap-card__heading">
                                <strong>{{ gap.skillCode }}</strong>
                                <el-tag size="small" :type="gap.evidenceStatus === 'SUFFICIENT' ? 'success' : 'warning'">{{ evidenceLabel(gap.evidenceStatus) }}</el-tag>
                              </div>
                              <el-descriptions :column="3" size="small">
                                <el-descriptions-item label="当前分">{{ gap.currentScore === null ? '证据不足' : rawValue(gap.currentScore) }}</el-descriptions-item>
                                <el-descriptions-item label="目标分">{{ rawValue(gap.targetScore) }}</el-descriptions-item>
                                <el-descriptions-item label="差距">{{ rawValue(gap.gap) }}</el-descriptions-item>
                              </el-descriptions>
                              <p>{{ gap.suggestion }}</p>
                              <p class="muted break-all">证据ID：{{ gap.evidenceIds.length ? gap.evidenceIds.join('、') : '暂无' }}</p>
                            </div>
                          </div>
                          <p v-else class="muted">未返回能力差距</p>
                        </div>
                      </template>

                      <div class="detail-block">
                        <h5>完整资格核验 <span class="muted">规则 {{ item.eligibility.ruleVersion }}</span></h5>
                        <el-table :data="item.eligibility.checks" size="small" border>
                          <el-table-column prop="field" label="核验字段" min-width="110" />
                          <el-table-column label="结果" width="90"><template #default="scope"><el-tag size="small" :type="statusTagType(scope.row.result)">{{ scope.row.result }}</el-tag></template></el-table-column>
                          <el-table-column label="实际值" min-width="110"><template #default="scope">{{ scope.row.actual ?? '—' }}</template></el-table-column>
                          <el-table-column label="期望值" min-width="110"><template #default="scope">{{ scope.row.expected ?? '—' }}</template></el-table-column>
                          <el-table-column prop="reason" label="核验理由" min-width="190" />
                          <el-table-column label="sourceQuote" min-width="210"><template #default="scope"><span class="pre-wrap">{{ scope.row.sourceQuote || '无原文引述' }}</span></template></el-table-column>
                        </el-table>
                      </div>

                      <div class="detail-block source-block">
                        <h5>岗位来源</h5>
                        <p>{{ item.source.name }} · {{ sourceTypeLabel(item.source.type) }} · {{ verificationLabel(item.source.verificationStatus) }}</p>
                        <p class="muted">来源校验：{{ formatDate(item.source.verifiedAt) }} · 岗位校验：{{ formatDate(item.verifiedAt) }}</p>
                        <el-button v-if="httpUrl(item.sourceUrl)" size="small" text type="primary" @click="openSource(item.sourceUrl)">打开原始来源</el-button>
                      </div>

                      <div class="actions match-item__actions">
                        <el-button @click="openJobById(item.jobId)">查看岗位详情</el-button>
                        <template v-if="item.eligibility.status !== 'FAIL'">
                          <el-button type="primary" :disabled="selectedMatch.isStale || selectedMatch.status !== 'READY'" @click="openPlanDialog(item)">创建训练计划</el-button>
                          <el-button type="success" plain @click="goInterview(item)">进入模拟面试</el-button>
                        </template>
                      </div>
                    </article>
                  </div>
                </section>
              </div>
            </template>
          </AsyncState>
        </section>
      </div>
    </template>

    <el-drawer v-model="jobDrawerVisible" title="岗位完整详情" size="720px" class="job-drawer">
      <AsyncState :loading="jobDetailLoading" :error="jobDetailError" :empty="!jobDetail" @retry="openJobById(jobDetailRequestId)">
        <template v-if="jobDetail">
          <div class="drawer-job-heading">
            <div>
              <h2>{{ jobDetail.title }}</h2>
              <p>{{ jobDetail.companyName }}</p>
            </div>
            <el-tag :type="statusTagType(jobDetail.status)">{{ commonStatusLabels[jobDetail.status] || jobDetail.status }}</el-tag>
          </div>
          <div class="actions drawer-actions">
            <el-button :type="selectedJobIds.includes(jobDetail.id) ? 'success' : 'primary'" plain @click="toggleJobSelection(jobDetail.id, !selectedJobIds.includes(jobDetail.id))">{{ selectedJobIds.includes(jobDetail.id) ? '取消匹配选择' : '选择用于匹配' }}</el-button>
            <el-button :loading="favoritePendingIds.includes(jobDetail.id)" @click="toggleFavorite(jobDetail)">{{ jobDetail.isFavorite ? '取消收藏' : '收藏岗位' }}</el-button>
          </div>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="城市">{{ cityLabels[jobDetail.cityCode || ''] || jobDetail.cityCode || '未明确' }}</el-descriptions-item>
            <el-descriptions-item label="求职类型">{{ candidateTypeLabels[jobDetail.candidateType] || jobDetail.candidateType }}</el-descriptions-item>
            <el-descriptions-item label="薪资">{{ formatSalary(jobDetail.minMonthlySalaryCents, jobDetail.maxMonthlySalaryCents) }}</el-descriptions-item>
            <el-descriptions-item label="JD版本">v{{ jobDetail.jobVersion }}</el-descriptions-item>
          </el-descriptions>
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
                <el-descriptions-item label="最低经验">{{ jobDetail.requirements.experienceMonthsMin === null ? '未明确' : `${jobDetail.requirements.experienceMonthsMin}个月` }}</el-descriptions-item>
              </el-descriptions>
              <h4>技能要求</h4>
              <div class="skill-tags"><el-tag v-for="skill in jobDetail.requirements.skills" :key="skill">{{ skill }}<template v-if="jobDetail.requirements.requiredSkillLevels[skill] !== undefined"> · {{ jobDetail.requirements.requiredSkillLevels[skill] }}</template></el-tag></div>
              <div v-if="Object.keys(jobDetail.requirements.requiredSkillLevels).length" class="level-list">
                <span v-for="([skill, level]) in Object.entries(jobDetail.requirements.requiredSkillLevels)" :key="skill">{{ skill }}：{{ level }}</span>
              </div>
              <h4>要求原文引述</h4>
              <div v-if="Object.keys(jobDetail.requirements.quotes).length" class="quote-list">
                <blockquote v-for="([field, quote]) in Object.entries(jobDetail.requirements.quotes)" :key="field"><strong>{{ field }}</strong><p class="pre-wrap">{{ quote }}</p></blockquote>
              </div>
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
            <el-button v-if="httpUrl(jobDetail.sourceUrl)" class="source-link" type="primary" plain @click="openSource(jobDetail.sourceUrl)">新窗口打开原始来源</el-button>
            <p v-else class="muted">未提供可安全打开的http/https来源地址</p>
          </section>
        </template>
      </AsyncState>
    </el-drawer>

    <el-dialog v-model="planDialogVisible" title="创建专项训练计划" width="560px" class="responsive-dialog" :close-on-click-modal="false">
      <div v-if="planTarget" class="plan-dialog-content">
        <el-alert :title="planTarget.title" description="计划将依据该匹配条目的真实能力差距生成。" type="info" :closable="false" show-icon />
        <el-alert v-if="planSourceStale || selectedMatch?.isStale" title="匹配结果已过期，不能创建训练计划" type="error" :closable="false" show-icon />
        <el-form label-position="top">
          <div class="form-grid">
            <el-form-item label="每日投入（10至120分钟）">
              <el-input-number v-model="planForm.dailyMinutes" :min="10" :max="120" :step="5" class="full-width" :disabled="planTaskActive" />
            </el-form-item>
            <el-form-item label="训练周期（1至14天）">
              <el-input-number v-model="planForm.days" :min="1" :max="14" class="full-width" :disabled="planTaskActive" />
            </el-form-item>
          </div>
        </el-form>
        <el-alert v-if="planActionError" :title="errorMessage(planActionError)" type="error" :closable="false" show-icon />
        <div v-if="planAuthorityLoading" class="authority-loading" v-loading="true">正在读取已生成的训练计划</div>
        <TaskStatus v-if="planTask" :task="planTask">
          <template #actions>
            <el-button v-if="canRetryTrackedTask(planTask)" size="small" type="primary" :loading="planRetrying" @click="retryPlan">按原参数重试</el-button>
          </template>
        </TaskStatus>
        <div v-if="planTrackingError" class="tracking-error">
          <span>{{ errorMessage(planTrackingError) }}</span>
          <el-button size="small" @click="planTracker.restart">恢复任务跟踪</el-button>
        </div>
      </div>
      <template #footer>
        <el-button @click="planDialogVisible = false">关闭</el-button>
        <el-button v-if="createdPlanId && planTask?.status === 'SUCCEEDED' && planActionError" type="primary" :loading="planAuthorityLoading" @click="fetchCreatedPlan(createdPlanId)">重新读取并进入计划</el-button>
        <el-button v-else type="primary" :loading="planSubmitting" :disabled="planTaskActive || planSourceStale || selectedMatch?.isStale" @click="submitPlan">提交生成计划</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.job-match-page { padding-bottom: 28px; }
.page-header h1 { display: flex; align-items: center; gap: 10px; }
.job-tabs { margin-bottom: 16px; padding: 0 16px; border: 1px solid #e8f0fa; border-radius: $ai-radius-lg; background: #fff; }
.filter-keyword { width: min(320px, 100%); }
.filter-control { width: 170px; }
.filter-note { margin: 0; color: $ai-text-secondary; font-size: 13px; }
.task-alert { margin: 16px 0; }
.task-alert :deep(.el-alert__content) { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.tracking-error { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 10px; color: $ai-danger; font-size: 13px; }
.reference-failures { display: grid; gap: 8px; margin-bottom: 16px; }
.job-card { display: flex; gap: 12px; padding: 18px; border: 1px solid $ai-border; border-radius: $ai-radius; background: #fff; transition: border-color .2s, box-shadow .2s; }
.job-card:hover { border-color: #b8ccf4; box-shadow: $ai-shadow-hover; }
.job-card__selector { padding-top: 4px; }
.job-card__content { min-width: 0; flex: 1; }
.job-card__heading { display: flex; justify-content: space-between; gap: 16px; }
.job-card__heading h3 { margin: 0 0 6px; color: #101d4b; font-size: 18px; }
.job-card__heading p { margin: 0; color: $ai-text-secondary; }
.job-card__tags, .skill-tags { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; }
.job-card__meta, .job-card__source { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 18px; margin-top: 12px; color: $ai-text-secondary; font-size: 13px; }
.job-card__excerpt { display: -webkit-box; overflow: hidden; margin: 14px 0 0; color: $ai-text-primary; line-height: 1.7; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.job-card__source { padding-top: 12px; border-top: 1px dashed $ai-border; }
.job-card .actions { margin-top: 14px; }
.match-setup { display: grid; gap: 16px; }
.selected-jobs { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; min-height: 38px; padding: 12px; border-radius: $ai-radius; background: $ai-bg-page; }
.selected-jobs__label { margin-right: 4px; font-weight: 600; }
.match-setup__controls { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 14px; }
.match-layout { display: grid; grid-template-columns: 300px minmax(0, 1fr); align-items: start; gap: 14px; }
.match-history { position: sticky; top: 14px; }
.batch-card { display: grid; gap: 8px; width: 100%; padding: 13px; border: 1px solid $ai-border; border-radius: $ai-radius; color: inherit; text-align: left; background: #fff; cursor: pointer; }
.batch-card:hover, .batch-card--active { border-color: $ai-primary; background: $ai-primary-light; }
.batch-card__top { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.batch-card__id { overflow: hidden; color: $ai-text-tertiary; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.match-history .pagination { flex-wrap: wrap; align-items: center; justify-content: center; gap: 8px; overflow-x: auto; }
.history-size { width: 86px; }
.match-detail { min-width: 0; }
.stale-alert { margin-bottom: 16px; border: 2px solid $ai-danger; }
.batch-meta { margin-bottom: 16px; }
.break-all { word-break: break-all; }
.match-groups { display: grid; gap: 24px; }
.match-group { padding-top: 6px; }
.match-group__heading { margin-bottom: 12px; padding-bottom: 10px; border-bottom: 1px solid $ai-border; }
.match-group__heading h3 { display: flex; align-items: center; gap: 8px; margin: 0 0 7px; font-size: 17px; }
.match-group__heading p { margin: 0; color: $ai-text-secondary; line-height: 1.6; }
.match-item { padding: 16px; border: 1px solid $ai-border; border-radius: $ai-radius; background: #fff; }
.match-item__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.match-item__heading h4 { margin: 0 0 6px; color: #101d4b; font-size: 17px; }
.match-score { display: grid; flex: 0 0 76px; text-align: center; }
.match-score strong { color: $ai-primary; font-size: 26px; line-height: 1.2; }
.match-score span { color: $ai-text-secondary; font-size: 12px; }
.detail-block { margin-top: 16px; overflow-x: auto; }
.detail-block h5 { margin: 0 0 10px; color: #101d4b; font-size: 15px; }
.detail-block :deep(.el-table) { min-width: 600px; }
.reason-list { display: grid; gap: 6px; margin: 0; padding-left: 20px; line-height: 1.65; }
.gap-list { display: grid; gap: 10px; }
.gap-card { padding: 12px; border-radius: $ai-radius; background: $ai-bg-page; }
.gap-card__heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.gap-card p { margin: 10px 0 0; line-height: 1.6; }
.source-block { padding: 12px; border-left: 3px solid $ai-primary; background: $ai-primary-light; }
.source-block p { margin: 0 0 7px; }
.match-item__actions { padding-top: 14px; border-top: 1px solid $ai-border; }
.drawer-job-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 14px; }
.drawer-job-heading h2 { margin: 0 0 8px; color: #101d4b; }
.drawer-job-heading p { margin: 0; color: $ai-text-secondary; }
.drawer-actions { margin-bottom: 16px; }
.drawer-section { margin-top: 24px; }
.drawer-section h3 { margin: 0 0 12px; color: #101d4b; font-size: 17px; }
.drawer-section h4 { margin: 18px 0 10px; font-size: 14px; }
.drawer-section .skill-tags { justify-content: flex-start; }
.jd-full-text { max-height: 420px; overflow-y: auto; padding: 14px; border-radius: $ai-radius; background: $ai-bg-page; }
.level-list { display: flex; flex-wrap: wrap; gap: 8px 16px; margin-top: 10px; color: $ai-text-secondary; }
.quote-list { display: grid; gap: 10px; }
.quote-list blockquote { margin: 0; padding: 12px; border-left: 3px solid $ai-primary; background: $ai-bg-page; }
.quote-list p { margin: 6px 0 0; }
.source-link { margin: 12px 0; }
.plan-dialog-content { display: grid; gap: 16px; }
.authority-loading { min-height: 70px; padding: 24px; text-align: center; color: $ai-text-secondary; }
:global(.job-drawer) { max-width: 100%; }
:global(.responsive-dialog) { max-width: calc(100vw - 24px); }
@media (max-width: 1100px) {
  .match-layout { grid-template-columns: 1fr; }
  .match-history { position: static; }
}
@media (max-width: 700px) {
  .job-tabs { padding: 0 8px; overflow-x: auto; }
  .filter-keyword, .filter-control { width: 100%; }
  .job-card { padding: 13px; }
  .job-card__heading, .match-item__heading { flex-direction: column; }
  .job-card__tags { justify-content: flex-start; }
  .match-setup__controls { align-items: stretch; flex-direction: column; }
  .match-score { text-align: left; }
  .match-item { padding: 12px; }
}
</style>