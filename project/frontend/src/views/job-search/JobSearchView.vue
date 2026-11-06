<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search, Star, StarFilled } from '@element-plus/icons-vue'
import { jobApi } from '@/api/jobs'
import { jobSearchApi } from '@/api/job-searches'
import { profileApi } from '@/api/profile'
import { taskApi } from '@/api/tasks'
import { retryTrackedTask } from '@/api/task-registry'
import type {
  CompanySize,
  JobSearchCreateInput,
  JobSearchItemVO,
  JobSearchVO,
  JobSearchSourceStatusVO,
  Page,
  ProfileVO,
  TaskVO,
} from '@/api/types'
import AsyncState from '@/components/AsyncState.vue'
import TaskStatus from '@/components/TaskStatus.vue'
import { isTerminalTask, useTask } from '@/composables/useTask'
import {
  candidateTypeLabels,
  cityLabels,
  companySizeLabels,
  educationLabels,
  errorMessage,
  formatDate,
  formatSalary,
  searchSourceStatusLabels,
  searchWarningLabels,
} from '@/utils/format'

const taskStorageKey = 'ai-job-job-search-task'
const searchStorageKey = 'ai-job-job-search-id'

const profile = ref<ProfileVO | null>(null)
const profileLoading = ref(false)
const profileError = ref<unknown>(null)
const page = reactive({
  current: 1,
  size: 10,
  total: 0,
  records: [] as JobSearchItemVO[],
})
const itemsLoading = ref(false)
const itemsError = ref<unknown>(null)
const search = ref<JobSearchVO | null>(null)
const searchLoading = ref(false)
const searchError = ref<unknown>(null)
const createSubmitting = ref(false)
const createError = ref<unknown>(null)
const canceling = ref(false)
const retrying = ref(false)
const favoritePendingIds = ref<string[]>([])

const createForm = reactive({
  keyword: '',
  cities: [] as string[],
  candidateTypes: [] as string[],
  companySizes: [] as CompanySize[],
  includeUnknownSize: true,
  maxResults: 30,
})
const itemFilters = reactive({
  keyword: '',
  cityCode: '',
  candidateType: '',
  companySizes: [] as CompanySize[],
  includeUnknownSize: true,
})

const cityOptions = Object.entries(cityLabels).map(([value, label]) => ({ value, label }))
const candidateOptions = Object.entries(candidateTypeLabels).map(([value, label]) => ({ value, label }))
const companySizeOptions = Object.entries(companySizeLabels).map(([value, label]) => ({ value, label }))
const profileReady = computed(() => Boolean(profile.value?.targetRole && profile.value?.candidateType && profile.value?.cities?.length))

function readStorage(key: string) {
  try {
    return localStorage.getItem(key) || ''
  } catch {
    return ''
  }
}

function writeStorage(key: string, value: string) {
  try {
    if (value) localStorage.setItem(key, value)
    else localStorage.removeItem(key)
  } catch {
    return
  }
}

const taskId = ref(readStorage(taskStorageKey))
const searchId = ref(readStorage(searchStorageKey))
const {
  task,
  loading: taskLoading,
  error: taskError,
  restart: restartTask,
} = useTask(taskId, {
  onDone: async done => {
    const id = done.result?.resourceId || done.resourceId
    if (!id) {
      createError.value = new Error('搜索任务已完成，但返回结果缺少搜索 ID')
      return
    }
    searchId.value = id
    writeStorage(searchStorageKey, id)
    writeStorage(taskStorageKey, '')
    await loadSearch(id)
    await loadItems()
  },
})

const taskActive = computed(() => !!task.value && !isTerminalTask(task.value.status))
const busying = computed(() => createSubmitting.value || taskLoading.value || (!!taskId.value && !task.value && !taskError.value) || taskActive.value)
const queryAdjustments = computed<string[]>(() => {
  const value = search.value?.queryPlan?.adjustments
  return Array.isArray(value) ? value.map(item => String(item)) : []
})

function taskIsActive(value?: TaskVO | null) {
  return !!value && ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(value.status)
}

async function loadProfile() {
  profileLoading.value = true
  profileError.value = null
  try {
    profile.value = await profileApi.get()
    if (!createForm.cities.length) createForm.cities = [...(profile.value.cities || [])]
    if (!createForm.candidateTypes.length && profile.value.candidateType) createForm.candidateTypes = [profile.value.candidateType]
  } catch (reason) {
    profileError.value = reason
  } finally {
    profileLoading.value = false
  }
}

async function loadSearch(id = searchId.value) {
  if (!id) return false
  searchLoading.value = true
  searchError.value = null
  try {
    search.value = await jobSearchApi.get(id)
    if (!profile.value) await loadProfile()
    return true
  } catch (reason) {
    searchError.value = reason
    search.value = null
    return false
  } finally {
    searchLoading.value = false
  }
}

async function loadItems() {
  if (!searchId.value) return false
  itemsLoading.value = true
  itemsError.value = null
  try {
    const result = await jobSearchApi.items(searchId.value, {
      current: page.current,
      size: page.size,
      keyword: itemFilters.keyword.trim() || undefined,
      cityCode: itemFilters.cityCode || undefined,
      candidateType: itemFilters.candidateType || undefined,
      companySizes: itemFilters.companySizes,
      includeUnknownSize: itemFilters.includeUnknownSize,
    })
    page.records = result.records
    page.total = result.total
    page.current = result.current
    page.size = result.size
    return true
  } catch (reason) {
    itemsError.value = reason
    return false
  } finally {
    itemsLoading.value = false
  }
}

async function createSearch() {
  if (!profile.value || !profileReady.value || busying.value) {
    if (!profileReady.value) ElMessage.warning('请先完整保存目标岗位、求职类型和城市偏好')
    return
  }
  createSubmitting.value = true
  createError.value = null
  try {
    const body: JobSearchCreateInput = {
      expectedProfileVersion: profile.value.profileVersion,
      cities: createForm.cities,
      candidateTypes: createForm.candidateTypes,
      companySizes: createForm.companySizes,
      includeUnknownSize: createForm.includeUnknownSize,
      maxResults: createForm.maxResults,
    }
    const keyword = createForm.keyword.trim()
    if (keyword) body.keyword = keyword
    const created = await jobSearchApi.create(body)
    taskId.value = created.id
    writeStorage(taskStorageKey, created.id)
    searchId.value = ''
    writeStorage(searchStorageKey, '')
    search.value = null
    page.records = []
    page.total = 0
    ElMessage.success('联网搜索任务已提交')
  } catch (reason) {
    createError.value = reason
  } finally {
    createSubmitting.value = false
  }
}

async function cancelSearch() {
  if (!task.value || !taskIsActive(task.value) || canceling.value) return
  canceling.value = true
  try {
    await taskApi.cancel(task.value.id)
    ElMessage.success('已请求取消搜索任务')
    restartTask()
  } catch (reason) {
    createError.value = reason
  } finally {
    canceling.value = false
  }
}

async function retrySearch() {
  if (!task.value || retrying.value) return
  retrying.value = true
  try {
    const next = await retryTrackedTask(task.value)
    taskId.value = next.id
    writeStorage(taskStorageKey, next.id)
    search.value = null
    page.records = []
    page.total = 0
    ElMessage.success('已按原条件重新提交搜索')
  } catch (reason) {
    createError.value = reason
  } finally {
    retrying.value = false
  }
}

function applyFilters() {
  page.current = 1
  void loadItems()
}

function resetFilters() {
  itemFilters.keyword = ''
  itemFilters.cityCode = ''
  itemFilters.candidateType = ''
  itemFilters.companySizes = []
  itemFilters.includeUnknownSize = true
  page.current = 1
  void loadItems()
}

function changePage(current: number) {
  page.current = current
  void loadItems()
}

function changeSize(size: number) {
  page.size = size
  page.current = 1
  void loadItems()
}

function refresh() {
  void loadSearch()
  void loadItems()
}

function statusType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'AVAILABLE') return 'success'
  if (status === 'EMPTY' || status === 'PARTIAL' || status === 'SKIPPED') return 'warning'
  if (status === 'FAILED' || status === 'UNAVAILABLE') return 'danger'
  return 'info'
}

function eligibilityType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'PASS') return 'success'
  if (status === 'UNKNOWN') return 'warning'
  if (status === 'FAIL') return 'danger'
  return 'info'
}

function sourceStatus(status: string) {
  return searchSourceStatusLabels[status] || status
}

function warningLabel(code: string) {
  if (searchWarningLabels[code]) return searchWarningLabels[code]
  if (code.startsWith('MULTI_SOURCE_MERGED:')) return `已合并 ${code.split(':')[1]} 个重复岗位`
  if (code.startsWith('RESULT_TRUNCATED_TO_LIMIT:')) return `结果已按上限截断（${code.split(':')[1]} 条）`
  if (code.startsWith('MODEL_UNDERSTANDING_DEGRADED:')) return '模型理解不可用，已使用确定性规则降级'
  return code
}

function sourceAlerts() {
  if (!search.value) return []
  const unavailable = search.value.sources.filter(item => item.status === 'UNAVAILABLE')
  const failed = search.value.sources.filter(item => item.status === 'FAILED' || item.status === 'PARTIAL')
  const alerts: { type: 'warning' | 'error' | 'info'; title: string }[] = []
  if (unavailable.length) {
    alerts.push({
      type: 'warning',
      title: `以下来源当前不可用，未伪装为已接入：${unavailable.map(item => `${item.name}（${item.message || '缺少授权或公开接口'}）`).join('；')}`,
    })
  }
  if (failed.length) {
    alerts.push({
      type: 'error',
      title: `部分来源检索失败，其他来源结果仍可用：${failed.map(item => `${item.name}（${item.message || '请求失败'}）`).join('；')}`,
    })
  }
  if (search.value.summary?.warnings?.includes('EMPTY_RESULT')) {
    alerts.push({ type: 'info', title: '本次搜索没有返回岗位。可调整关键词、城市或公司规模后重试。' })
  }
  return alerts
}

function cityName(code?: string | null) {
  if (!code) return '未明确'
  return cityLabels[code] || code
}

function salary(item: JobSearchItemVO) {
  if (item.salaryText) return item.salaryText
  return formatSalary(item.minMonthlySalaryCents, item.maxMonthlySalaryCents)
}

function experience(item: JobSearchItemVO) {
  if (item.experienceText) return item.experienceText
  if (item.experienceMonthsMin !== null && item.experienceMonthsMin !== undefined) {
    return `${item.experienceMonthsMin} 个月起`
  }
  return '未明确'
}

function education(item: JobSearchItemVO) {
  if (item.educationText) return item.educationText
  if (item.educationMin) return educationLabels[item.educationMin] || item.educationMin
  return '未明确'
}

function sizeLabel(item: JobSearchItemVO) {
  return companySizeLabels[item.companySize] || item.companySize || '规模未知'
}

function safeUrl(url?: string | null) {
  if (!url) return ''
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.toString() : ''
  } catch {
    return ''
  }
}

function openSource(url?: string | null) {
  const target = safeUrl(url)
  if (!target) return
  const opened = window.open(target, '_blank', 'noopener,noreferrer')
  if (opened) opened.opener = null
}

function openMatchBatch(item: JobSearchItemVO) {
  void window.location.assign(`/job-match?tab=matches&matchId=${encodeURIComponent(item.batchId)}`)
}

async function toggleFavorite(item: JobSearchItemVO) {
  if (favoritePendingIds.value.includes(item.jobId)) return
  favoritePendingIds.value = [...favoritePendingIds.value, item.jobId]
  try {
    const next = await jobApi.setFavorite(item.jobId, !item.isFavorite)
    item.isFavorite = next
    ElMessage.success(next ? '已收藏岗位' : '已取消收藏')
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  } finally {
    favoritePendingIds.value = favoritePendingIds.value.filter(id => id !== item.jobId)
  }
}

onMounted(async () => {
  await loadProfile()
  if (searchId.value) {
    await loadSearch(searchId.value)
    await loadItems()
  }
})
</script>

<template>
  <div class="page job-search-page">
    <header class="page-header">
      <div>
        <h1>联网岗位搜索</h1>
        <p>以已确认简历与求职偏好自动制定搜索条件，联网检索岗位并解析 JD，按硬性资格与匹配度排序。</p>
      </div>
      <el-button :loading="searchLoading || itemsLoading" @click="refresh">刷新当前结果</el-button>
    </header>

    <el-alert
      class="ai-notice"
      type="info"
      :closable="false"
      show-icon
      title="匹配分只用于辅助排序，不是录用概率。学历、经验等明确硬性条件由 Java 端确定性规则核验；信息不足时标记为未知，不会由模型补造。"
    />

    <section class="panel">
      <div class="panel__heading">
        <div>
          <h2>搜索条件</h2>
          <p class="section-note">关键词、城市与求职类型可调整；未填写时使用求职偏好。公司规模按统一边界筛选。</p>
        </div>
      </div>
      <AsyncState :loading="profileLoading" :error="profileError" :empty="false" @retry="loadProfile">
        <template v-if="profile">
          <div class="profile-line">
            <span>画像版本 v{{ profile.profileVersion }}</span>
            <span>目标岗位：{{ profile.targetRole || '未设置' }}</span>
            <span>求职类型：{{ candidateTypeLabels[profile.candidateType] || '未设置' }}</span>
            <span>城市：{{ profile.cities?.length ? profile.cities.map(code => cityLabels[code] || code).join('、') : '未设置' }}</span>
          </div>
          <el-alert v-if="!profileReady" class="inline-alert" type="warning" :closable="false" show-icon title="请先完整保存目标岗位、求职类型和城市偏好，再到本页搜索。" />
          <div class="form-grid">
            <el-form-item label="岗位关键词（可选）">
              <el-input v-model="createForm.keyword" maxlength="100" placeholder="例如：Java 后端；留空则使用目标岗位" clearable />
            </el-form-item>
            <el-form-item label="城市">
              <el-select v-model="createForm.cities" multiple clearable filterable placeholder="选择城市" class="full-width">
                <el-option v-for="item in cityOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="实习 / 校招 / 社招">
              <el-select v-model="createForm.candidateTypes" multiple clearable placeholder="选择求职类型" class="full-width">
                <el-option v-for="item in candidateOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="公司规模">
              <el-select v-model="createForm.companySizes" multiple clearable collapse-tags placeholder="不限规模" class="full-width">
                <el-option v-for="item in companySizeOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="规模未知的处理">
              <el-switch v-model="createForm.includeUnknownSize" active-text="包含规模未知" inactive-text="排除规模未知" />
            </el-form-item>
            <el-form-item label="最多返回（1-60）">
              <el-input-number v-model="createForm.maxResults" :min="1" :max="60" :step="1" step-strictly class="full-width" />
            </el-form-item>
          </div>
          <el-alert v-if="createError" class="inline-alert" type="error" :title="errorMessage(createError)" :closable="false" show-icon />
          <div class="actions">
            <el-button type="primary" :loading="createSubmitting" :disabled="busying || !profileReady" @click="createSearch">开始联网搜索</el-button>
            <el-button v-if="task && taskIsActive(task)" :loading="canceling" @click="cancelSearch">取消任务</el-button>
          </div>
        </template>
      </AsyncState>
    </section>

    <section v-if="taskId" class="panel">
      <div class="panel__heading">
        <h2>搜索任务</h2>
        <el-tag v-if="task?.stage" size="small">{{ task.stage }}</el-tag>
      </div>
      <el-skeleton v-if="taskLoading && !task" :rows="2" animated />
      <TaskStatus v-else :task="task">
        <template #actions>
          <el-button v-if="task?.status === 'FAILED'" size="small" :loading="retrying" @click="retrySearch">按原条件重试</el-button>
          <el-button v-if="task && taskIsActive(task)" size="small" :loading="canceling" @click="cancelSearch">取消</el-button>
        </template>
      </TaskStatus>
      <el-alert v-if="taskError" type="warning" :closable="false" show-icon>
        <template #title>任务事件连接异常，业务任务未被取消。{{ errorMessage(taskError) }}</template>
        <el-button size="small" @click="restartTask">恢复任务状态</el-button>
      </el-alert>
    </section>

    <AsyncState :loading="searchLoading" :error="searchError" :empty="!search" empty-text="尚未发起联网搜索" empty-description="设置条件并提交后，这里会显示搜索进度、来源状态和排序结果。" @retry="loadSearch()">
      <template v-if="search">
        <section class="panel">
          <div class="panel__heading">
            <div>
              <h2>来源状态</h2>
              <p class="section-note">来源可用性来自本次真实请求；不可用来源会明确说明原因，不会被当作已接入。</p>
            </div>
            <el-tag :type="search.status === 'READY' ? 'success' : search.status === 'FAILED' ? 'danger' : 'primary'">{{ search.status === 'READY' ? '搜索完成' : search.status === 'FAILED' ? '搜索失败' : '搜索中' }}</el-tag>
          </div>
          <el-alert v-if="search.isStale" class="inline-alert" type="warning" :closable="false" show-icon :title="`该搜索结果已过期（${search.staleReason || '输入或岗位状态变化'}），仅供参考；请重新搜索后再据此做训练或面试。`" />
          <el-alert v-for="(alert, index) in sourceAlerts()" :key="index" class="inline-alert" :type="alert.type" :title="alert.title" :closable="false" show-icon />
          <el-table v-if="search.sources?.length" :data="search.sources" size="small" border class="source-table">
            <el-table-column prop="name" label="来源" min-width="180" />
            <el-table-column label="状态" width="130">
              <template #default="scope"><el-tag :type="statusType(scope.row.status)" size="small">{{ sourceStatus(scope.row.status) }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="count" label="返回" width="70" />
            <el-table-column label="耗时" width="90">
              <template #default="scope">{{ scope.row.durationMs }} ms</template>
            </el-table-column>
            <el-table-column label="说明" min-width="320">
              <template #default="scope"><span class="break-all">{{ scope.row.message || '—' }}</span></template>
            </el-table-column>
          </el-table>
          <div v-if="search.summary" class="summary-grid">
            <div><strong>{{ search.summary.uniqueCount }}</strong><span>去重后岗位</span></div>
            <div><strong>{{ search.summary.rawCount }}</strong><span>原始结果</span></div>
            <div><strong>{{ search.summary.mergedCount }}</strong><span>多来源合并</span></div>
            <div><strong>{{ search.summary.unknownSizeCount }}</strong><span>规模未知</span></div>
            <div><strong>{{ search.summary.possiblyExpiredCount }}</strong><span>可能过期</span></div>
            <div><strong>{{ search.summary.droppedCount }}</strong><span>丢弃无效条目</span></div>
          </div>
          <p v-if="queryAdjustments.length" class="section-note">
            自动调整：{{ queryAdjustments.join('；') }}
          </p>
          <el-alert class="inline-alert" type="info" :closable="false" show-icon title="排序规则：硬性资格 PASS > UNKNOWN > FAIL；同组按有效匹配分从高到低；再按来源核验与多来源覆盖稳定排序。匹配分 = 技能 60% + 语义 25% + 偏好 15%，缺项不计分。" />
        </section>

        <section class="panel">
          <div class="panel__heading">
            <div>
              <h2>搜索结果</h2>
              <p class="section-note">筛选与排序在完整候选集上执行后再分页，翻页顺序稳定。可收藏、打开原始岗位，或进入现有匹配结果流程创建训练与模拟面试。</p>
            </div>
            <el-button :loading="itemsLoading" @click="loadItems">刷新结果</el-button>
          </div>
          <div class="filter-grid">
            <el-input v-model="itemFilters.keyword" maxlength="100" placeholder="按岗位/公司关键词筛选" clearable />
            <el-select v-model="itemFilters.cityCode" clearable placeholder="城市" class="full-width">
              <el-option v-for="item in cityOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-select v-model="itemFilters.candidateType" clearable placeholder="求职类型" class="full-width">
              <el-option v-for="item in candidateOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-select v-model="itemFilters.companySizes" multiple clearable collapse-tags placeholder="公司规模" class="full-width">
              <el-option v-for="item in companySizeOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-checkbox v-model="itemFilters.includeUnknownSize">包含规模未知</el-checkbox>
            <div class="actions">
              <el-button type="primary" plain :loading="itemsLoading" @click="applyFilters">应用筛选</el-button>
              <el-button @click="resetFilters">重置</el-button>
            </div>
          </div>
          <AsyncState :loading="itemsLoading" :error="itemsError" :empty="!page.records.length" empty-text="没有符合条件的岗位" empty-description="可放宽关键词、城市或公司规模筛选，或调整搜索条件后重试。" @retry="loadItems">
            <div class="search-list">
              <article v-for="item in page.records" :key="item.id" class="search-card">
                <div class="search-card__heading">
                  <div>
                    <h3>{{ item.title }}</h3>
                    <p>{{ item.companyName }} · {{ cityName(item.cityCode) }} · {{ candidateTypeLabels[item.candidateType] || item.candidateType }}</p>
                  </div>
                  <div class="search-score">
                    <strong>{{ item.matchScore === null ? '待评估' : item.matchScore }}</strong>
                    <span>匹配分（辅助排序）</span>
                  </div>
                </div>

                <div class="tag-list">
                  <el-tag :type="eligibilityType(item.eligibility.status)" size="small">资格 {{ item.eligibility.status }}</el-tag>
                  <el-tag size="small" effect="plain">{{ item.source?.name || '来源未提供' }}</el-tag>
                  <el-tag :type="item.verification === 'VERIFIED' ? 'success' : 'warning'" size="small">{{ item.verification === 'VERIFIED' ? '来源已核验' : '来源未核验' }}</el-tag>
                  <el-tag v-if="item.warnings?.includes('POSSIBLY_EXPIRED')" type="danger" size="small">可能过期</el-tag>
                  <el-tag v-if="item.warnings?.includes('COMPANY_SIZE_UNKNOWN')" type="info" size="small">规模未知</el-tag>
                  <el-tag v-if="(item.aliases?.length || 0) > 1" type="success" size="small">多来源 {{ item.aliases.length }}</el-tag>
                </div>

                <el-descriptions :column="3" border size="small" class="job-facts">
                  <el-descriptions-item label="薪资">{{ salary(item) }}</el-descriptions-item>
                  <el-descriptions-item label="经验">{{ experience(item) }}</el-descriptions-item>
                  <el-descriptions-item label="学历">{{ education(item) }}</el-descriptions-item>
                  <el-descriptions-item label="公司规模">{{ sizeLabel(item) }}</el-descriptions-item>
                  <el-descriptions-item label="发布时间">{{ formatDate(item.publishedAt) }}</el-descriptions-item>
                  <el-descriptions-item label="抓取时间">{{ formatDate(item.fetchedAt) }}</el-descriptions-item>
                </el-descriptions>

                <div v-if="item.warnings?.length" class="warning-list">
                  <el-tag v-for="code in item.warnings" :key="code" size="small" type="warning" effect="plain">{{ warningLabel(code) }}</el-tag>
                </div>

                <div class="detail-block">
                  <h4>匹配依据</h4>
                  <ul v-if="item.reasons?.length" class="reason-list"><li v-for="(reason, index) in item.reasons" :key="index">{{ reason }}</li></ul>
                  <p v-else class="muted">未生成额外匹配理由</p>
                  <h4>主要差距</h4>
                  <div v-if="item.gaps?.length" class="gap-list">
                    <div v-for="gap in item.gaps" :key="gap.skillCode" class="gap-card">
                      <div class="gap-card__heading">
                        <strong>{{ gap.skillCode }}</strong>
                        <el-tag size="small" :type="gap.evidenceStatus === 'SUFFICIENT' ? 'success' : 'warning'">{{ gap.evidenceStatus }}</el-tag>
                      </div>
                      <p>{{ gap.suggestion }}</p>
                    </div>
                  </div>
                  <p v-else class="muted">未返回技能差距</p>
                </div>

                <el-collapse>
                  <el-collapse-item title="硬性资格核验明细与评分构成" :name="item.id">
                    <el-table :data="item.eligibility.checks" size="small" border>
                      <el-table-column prop="field" label="字段" min-width="120" />
                      <el-table-column label="结果" width="90">
                        <template #default="scope"><el-tag size="small" :type="eligibilityType(scope.row.result)">{{ scope.row.result }}</el-tag></template>
                      </el-table-column>
                      <el-table-column label="实际" min-width="100"><template #default="scope">{{ scope.row.actual ?? '—' }}</template></el-table-column>
                      <el-table-column label="要求" min-width="110"><template #default="scope">{{ scope.row.expected ?? '—' }}</template></el-table-column>
                      <el-table-column prop="reason" label="核验理由" min-width="190" />
                      <el-table-column label="原文引述" min-width="200"><template #default="scope"><span class="pre-wrap">{{ scope.row.sourceQuote || '无' }}</span></template></el-table-column>
                    </el-table>
                    <el-table :data="item.components" size="small" border class="component-table">
                      <el-table-column prop="key" label="分项" width="110" />
                      <el-table-column label="原值" width="110"><template #default="scope">{{ scope.row.value ?? '未知' }}</template></el-table-column>
                      <el-table-column label="权重" width="90"><template #default="scope">{{ scope.row.weight }}</template></el-table-column>
                      <el-table-column prop="provenance" label="来源/版本" min-width="180" />
                    </el-table>
                  </el-collapse-item>
                </el-collapse>

                <div class="source-aliases">
                  <span class="muted">可追溯来源：</span>
                  <template v-for="alias in item.aliases" :key="`${alias.sourceKey}:${alias.sourceUrl}`">
                    <el-button link type="primary" @click="openSource(alias.sourceUrl)">{{ alias.sourceName || alias.sourceKey }}</el-button>
                  </template>
                </div>

                <div class="actions search-card__actions">
                  <el-button :loading="favoritePendingIds.includes(item.jobId)" @click="toggleFavorite(item)">
                    <el-icon><StarFilled v-if="item.isFavorite" /><Star v-else /></el-icon>
                    {{ item.isFavorite ? '取消收藏' : '收藏岗位' }}
                  </el-button>
                  <el-button plain type="primary" :disabled="!safeUrl(item.sourceUrl)" @click="openSource(item.sourceUrl)">查看原始岗位</el-button>
                  <el-button @click="openMatchBatch(item)">在匹配结果中继续（训练/面试）</el-button>
                </div>
              </article>
            </div>
            <el-pagination
              class="pagination"
              background
              layout="total, sizes, prev, pager, next"
              :total="page.total"
              :current-page="page.current"
              :page-size="page.size"
              :page-sizes="[10, 20, 50]"
              @current-change="changePage"
              @size-change="changeSize"
            />
          </AsyncState>
        </section>
      </template>
    </AsyncState>
  </div>
</template>

<style scoped lang="scss">
.job-search-page { padding-bottom: 24px; }
.ai-notice { margin-bottom: 16px; }
.section-note { margin: 6px 0 0; color: $ai-text-secondary; line-height: 1.6; }
.inline-alert { margin: 0 0 14px; }
.actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.profile-line { display: flex; flex-wrap: wrap; gap: 8px 18px; margin-bottom: 16px; color: $ai-text-secondary; font-size: 12px; }
.source-table { margin-bottom: 14px; }
.summary-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 10px; margin: 14px 0; }
.summary-grid > div { display: flex; flex-direction: column; gap: 3px; padding: 11px; border: 1px solid #e3ebf8; border-radius: 9px; background: #fbfdff; }
.summary-grid strong { color: #101d4b; font-size: 20px; }
.summary-grid span { color: $ai-text-secondary; font-size: 12px; }
.filter-grid { display: grid; grid-template-columns: minmax(180px, 1.4fr) repeat(3, minmax(140px, 1fr)) auto; align-items: center; gap: 10px; margin-bottom: 16px; }
.search-list { display: grid; gap: 14px; }
.search-card { min-width: 0; padding: 16px; border: 1px solid #e1eaf8; border-radius: 11px; background: linear-gradient(145deg, #fff 0%, #fafcff 100%); }
.search-card__heading { display: flex; justify-content: space-between; gap: 16px; }
.search-card h3 { margin: 0 0 6px; color: #101d4b; font-size: 19px; line-height: 1.4; }
.search-card__heading p { margin: 0; color: $ai-text-secondary; }
.search-score { display: flex; flex: 0 0 auto; flex-direction: column; align-items: flex-end; }
.search-score strong { color: $ai-primary; font-size: 27px; }
.search-score span { color: $ai-text-secondary; font-size: 12px; }
.tag-list { display: flex; flex-wrap: wrap; gap: 6px; margin: 11px 0; }
.job-facts { margin: 12px 0; }
.warning-list { display: flex; flex-wrap: wrap; gap: 6px; margin: 10px 0; }
.detail-block { margin: 14px 0; padding: 13px; border-radius: 9px; background: $ai-bg-page; }
.detail-block h4 { margin: 0 0 8px; color: #101d4b; font-size: 14px; }
.detail-block h4:not(:first-child) { margin-top: 14px; }
.reason-list { margin: 0; padding-left: 18px; color: $ai-text-secondary; line-height: 1.7; }
.gap-list { display: grid; gap: 8px; }
.gap-card { padding: 10px; border: 1px solid #e5edf8; border-radius: 8px; background: #fff; }
.gap-card__heading { display: flex; align-items: center; gap: 8px; }
.gap-card p { margin: 6px 0 0; color: $ai-text-secondary; line-height: 1.6; }
.component-table { margin-top: 12px; }
.source-aliases { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin: 12px 0; }
.search-card__actions { margin-top: 8px; }
.break-all { overflow-wrap: anywhere; }
.pre-wrap { white-space: pre-wrap; overflow-wrap: anywhere; }
.muted { color: $ai-text-secondary; }
@media (max-width: 1100px) {
  .summary-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .filter-grid { grid-template-columns: 1fr 1fr; }
}
@media (max-width: 700px) {
  .summary-grid, .filter-grid { grid-template-columns: 1fr; }
  .search-card__heading { flex-direction: column; }
  .search-score { align-items: flex-start; }
}
</style>