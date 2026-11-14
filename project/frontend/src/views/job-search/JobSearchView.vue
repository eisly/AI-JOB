<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Search, Star, StarFilled } from '@element-plus/icons-vue'
import { jobApi } from '@/api/jobs'
import { jobSearchApi } from '@/api/job-searches'
import { ApiError } from '@/api/request'
import { profileApi } from '@/api/profile'
import { getJobRoleLabel } from '@/config/jobRoles'
import { taskApi } from '@/api/tasks'
import { retryTrackedTask } from '@/api/task-registry'
import type {
  CompanySize,
  JobSearchCreateInput,
  JobSearchItemVO,
  JobSearchVO,
  Page,
  ProfileVO,
  SourceHealthVO,
  TaskVO,
} from '@/api/types'
import AsyncState from '@/components/AsyncState.vue'
import TaskStatus from '@/components/TaskStatus.vue'
import { isTerminalTask, useTask } from '@/composables/useTask'
import { useUserStore } from '@/stores/user'
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
  sourceHealthLabels,
} from '@/utils/format'

const route = useRoute()
const router = useRouter()
const emit = defineEmits<{ plan: [item: JobSearchItemVO] }>()
const userStore = useUserStore()
const linkedSearchId = typeof route.query.searchId === 'string' ? route.query.searchId : ''
const linkedTaskId = typeof route.query.taskId === 'string' ? route.query.taskId : ''
// 恢复状态按账号隔离：key 追加用户 ID，切换账号后不会读取旧账号的搜索/任务 ID（S06）
const storageOwner = computed(() => userStore.userInfo?.id || 'anon')
const taskStorageKey = computed(() => `ai-job-job-search-task:${storageOwner.value}`)
const searchStorageKey = computed(() => `ai-job-job-search-id:${storageOwner.value}`)

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
const companySizeOptions = Object.entries(companySizeLabels).filter(([value]) => value !== 'SIZE_UNKNOWN').map(([value, label]) => ({ value, label }))
const profileReady = computed(() => Boolean(profile.value?.activeResumeVersionId && profile.value.targetRole && profile.value?.candidateType && profile.value?.cities?.length))

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

const taskId = ref(linkedTaskId || (linkedSearchId ? '' : readStorage(taskStorageKey.value)))
const searchId = ref(linkedSearchId || (linkedTaskId ? '' : readStorage(searchStorageKey.value)))
const {
  task,
  loading: taskLoading,
  error: taskError,
  reasoningText,
  restart: restartTask,
} = useTask(taskId, {
  onDone: async done => {
    const id = done.result?.resourceId || done.resourceId
    if (!id) {
      createError.value = new Error('搜索任务已完成，但返回结果缺少搜索 ID')
      return
    }
    searchId.value = id
    writeStorage(searchStorageKey.value, id)
    writeStorage(taskStorageKey.value, '')
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
    // S06：恢复记录中的失效搜索 ID（如数据清理后残留）自动清理，刷新后不再反复报 404
    if (reason instanceof ApiError && reason.status === 404 && searchId.value === id) {
      searchId.value = ''
      writeStorage(searchStorageKey.value, '')
    }
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
    if (!profileReady.value) ElMessage.warning('请先确认简历并保存目标岗位、求职类型和城市偏好')
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
    writeStorage(taskStorageKey.value, created.id)
    searchId.value = ''
    writeStorage(searchStorageKey.value, '')
    search.value = null
    page.records = []
    page.total = 0
    resetFilters()
    if (linkedSearchId || linkedTaskId) {
      await router.replace({ name: 'jobs', query: { tab: 'search' } })
    }
    ElMessage.success('AI 搜索任务已提交')
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
    writeStorage(taskStorageKey.value, next.id)
    search.value = null
    page.records = []
    page.total = 0
    if (linkedTaskId) {
      await router.replace({ name: 'jobs', query: { tab: 'search' } })
    }
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

const sourceIncomplete = computed(() => search.value?.summary?.warnings?.includes('SEARCH_INCOMPLETE') || false)
const sourceFailed = computed(() => search.value?.summary?.warnings?.includes('SEARCH_FAILED') || false)
const sourceUnavailable = computed(() => sourceFailed.value || !!search.value?.sources?.some(source => ['FAILED', 'UNAVAILABLE'].includes(source.status)))
const hasItemFilters = computed(() => Boolean(itemFilters.keyword || itemFilters.cityCode || itemFilters.candidateType || itemFilters.companySizes.length || !itemFilters.includeUnknownSize))
/** S02：有可用岗位但被筛选条件排空——此时优先提示筛选无结果，不得误报为来源不可用 */
const filteredEmpty = computed(() => hasItemFilters.value && (search.value?.summary?.uniqueCount ?? 0) > 0 && !page.records.length)
const webUnavailable = computed(() => {
  const web = search.value?.sources?.find(source => source.key === 'web')
  return web && ['FAILED', 'UNAVAILABLE', 'PARTIAL'].includes(web.status)
})
const eligibilityLabels: Record<string, string> = { PASS: '资格通过', UNKNOWN: '资格未知', FAIL: '资格不符' }

function sourceAlerts() {
  if (!search.value) return []
  const unavailable = search.value.sources.filter(item => item.status === 'UNAVAILABLE')
  const failed = search.value.sources.filter(item => item.status === 'FAILED' || item.status === 'PARTIAL')
  const alerts: { type: 'warning' | 'error' | 'info'; title: string }[] = []
  if (unavailable.length) {
    alerts.push({
      type: 'warning',
      title: `当前不可用：${unavailable.map(item => `${item.name}（${item.message || '缺少授权或公开接口'}）`).join('；')}`,
    })
  }
  if (failed.length) {
    alerts.push({
      type: 'error',
      title: `检索异常：${failed.map(item => `${item.name}（${item.message || '请求失败'}）`).join('；')}`,
    })
  }
  return alerts
}

function cityName(code?: string | null, fallback?: string | null) {
  if (code) return cityLabels[code] || code
  return fallback || '来源未提供'
}

function salary(item: JobSearchItemVO) {
  if (item.salaryText) return item.salaryText
  const formatted = formatSalary(item.minMonthlySalaryCents, item.maxMonthlySalaryCents)
  return formatted && !formatted.includes('未明确') ? formatted : '来源未提供'
}

function experience(item: JobSearchItemVO) {
  if (item.experienceText) return item.experienceText
  if (item.experienceMonthsMin !== null && item.experienceMonthsMin !== undefined) {
    return `${item.experienceMonthsMin} 个月起`
  }
  return '来源未提供'
}

function education(item: JobSearchItemVO) {
  if (item.educationText) return item.educationText
  if (item.educationMin) return educationLabels[item.educationMin] || item.educationMin
  return '来源未提供'
}

function sizeLabel(item: JobSearchItemVO) {
  if (!item.companySize || item.companySize === 'SIZE_UNKNOWN') return '来源未提供'
  return companySizeLabels[item.companySize] || item.companySize
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
  void router.push({ name: 'jobs', query: { tab: 'matches', matchId: item.batchId } })
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

/** 搜索前来源状态（S01）：静态配置检查结论 + 最近检测时间，明确区分"配置检查"与"连接成功" */
const sourceHealth = ref<SourceHealthVO | null>(null)
const sourceHealthLoading = ref(false)
const sourceHealthError = ref<unknown>(null)
const sourceHealthUnconfigured = computed(() =>
  sourceHealth.value?.sources.some(item => item.status === 'UNCONFIGURED') ?? false)

async function loadSourceHealth() {
  sourceHealthLoading.value = true
  sourceHealthError.value = null
  try {
    sourceHealth.value = await jobSearchApi.sourceHealth()
  } catch (reason) {
    sourceHealthError.value = reason
  } finally {
    sourceHealthLoading.value = false
  }
}

onMounted(async () => {
  void loadSourceHealth()
  await loadProfile()
  if (searchId.value) {
    await loadSearch(searchId.value)
    await loadItems()
  }
})
</script>

<template>
  <div class="job-search-page">

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
          <h2>AI 岗位搜索</h2>
          <p class="section-note">关键词、城市与求职类型可调整；未填写时使用求职偏好。公司规模按统一边界筛选。</p>
        </div>
        <el-button v-if="searchId" :loading="createSubmitting" :disabled="busying || !profileReady" @click="createSearch">重新搜索</el-button>
      </div>
      <AsyncState :loading="profileLoading" :error="profileError" :empty="false" @retry="loadProfile">
        <template v-if="profile">
          <div class="profile-line">
            <span>画像版本 v{{ profile.profileVersion }}</span>
            <span>目标岗位：{{ profile.targetRole ? getJobRoleLabel(profile.targetRole) : '未设置' }}</span>
            <span>求职类型：{{ candidateTypeLabels[profile.candidateType] || '未设置' }}</span>
            <span>城市：{{ profile.cities?.length ? profile.cities.map(code => cityLabels[code] || code).join('、') : '未设置' }}</span>
          </div>
          <div v-if="sourceHealth" class="source-health">
            <details>
              <summary>
                来源状态（配置检查）· 检测时间 {{ formatDate(sourceHealth.checkedAt) }}
                <span v-if="sourceHealthLoading" class="muted">检测中…</span>
              </summary>
              <div class="source-health__items">
                <el-tag
                  v-for="item in sourceHealth.sources"
                  :key="item.key"
                  size="small"
                  :type="item.status === 'READY_TO_TRY' ? 'success' : item.status === 'UNCONFIGURED' ? 'warning' : 'info'"
                >
                  {{ item.name }}：{{ sourceHealthLabels[item.status] || item.status }}
                </el-tag>
              </div>
              <p class="muted source-health__note">
                以上为服务端配置检查结论，不代表已实际联网验证连接；连接将在本次检索时验证。
                <template v-if="sourceHealthUnconfigured">存在未配置的搜索服务，相关来源本次搜索将不可用；可联系管理员配置搜索服务，或直接导入个人 JD 进行匹配。</template>
              </p>
            </details>
          </div>
          <el-alert v-if="sourceHealthError" class="inline-alert" type="info" :closable="false" show-icon title="来源状态暂时无法检测，不影响发起搜索。" />
          <el-alert v-if="sourceUnavailable" class="inline-alert" type="warning" :closable="false" show-icon title="上次搜索有来源不可用；本次搜索仍可能无法获取完整岗位。可查看下方来源诊断，或导入个人 JD。" />
          <el-alert v-if="!profileReady" class="inline-alert" type="warning" :closable="false" show-icon title="请先确认简历并保存目标岗位、求职类型和城市偏好，再到本页搜索。" />
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
            <el-button type="primary" :loading="createSubmitting" :disabled="busying || !profileReady" @click="createSearch">开始 AI 搜索</el-button>
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
      <TaskStatus v-else :task="task" :reasoning="reasoningText">
        <template #actions>
          <el-button v-if="task?.status === 'FAILED'" size="small" :loading="retrying" @click="retrySearch">按原条件重试</el-button>
          <el-button v-if="task && taskIsActive(task)" size="small" :loading="canceling" @click="cancelSearch">取消</el-button>
        </template>
      </TaskStatus>
      <el-alert v-if="task?.status === 'SUCCEEDED' && task.result?.outcome === 'NO_RESULTS_SOURCE_FAILED'" type="warning" :closable="false" show-icon>
        <template #title>本次搜索执行完成，但来源失败，未获得可用岗位。可调整条件后重新发起搜索。</template>
        <el-button size="small" type="primary" :disabled="busying || !profileReady" @click="createSearch">重新搜索</el-button>
      </el-alert>
      <el-alert v-if="taskError" type="warning" :closable="false" show-icon>
        <template #title>任务事件连接异常，业务任务未被取消。{{ errorMessage(taskError) }}</template>
        <el-button size="small" @click="restartTask">恢复任务状态</el-button>
      </el-alert>
    </section>

    <AsyncState :loading="searchLoading" :error="searchError" :empty="!search" empty-text="尚未发起 AI 搜索" empty-description="设置条件并提交后，这里会显示搜索进度、来源状态和排序结果。" @retry="loadSearch()">
      <template v-if="search">
        <el-alert v-if="sourceFailed" class="inline-alert" type="error" title="检索服务不可用，请查看来源诊断后重试。" :closable="false" show-icon />
        <el-alert v-else-if="webUnavailable" class="inline-alert" type="warning" title="全网搜索能力受限，页面展示本次仍可用的结果。" :closable="false" show-icon />
        <el-alert v-else-if="sourceIncomplete" class="inline-alert" type="warning" title="部分来源未完成检索，已找到的相关岗位仍会展示。" :closable="false" show-icon />
        <section class="panel">
          <details>
          <summary class="section-note">来源诊断</summary>
          <el-alert v-if="search.isStale" class="inline-alert" type="warning" :closable="false" show-icon :title="`该搜索结果已过期（${search.staleReason || '输入或岗位状态变化'}），仅供参考；请重新搜索后再据此做训练或面试。`" />
          <el-alert v-for="(alert, index) in sourceAlerts()" :key="index" class="inline-alert" :type="alert.type" :title="alert.title" :closable="false" show-icon />
          <el-table v-if="search.sources?.length" :data="search.sources" size="small" border class="source-table">
            <el-table-column prop="name" label="来源" min-width="180" />
            <el-table-column label="状态" width="130">
              <template #default="scope"><el-tag :type="statusType(scope.row.status)" size="small">{{ sourceStatus(scope.row.status) }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="count" label="返回" width="70" />
            <el-table-column label="在招核验" min-width="215">
              <template #default="{ row }">
                <span v-if="row.verificationCounts && row.verificationCounts.discovered !== undefined">
                  发现 {{ row.verificationCounts.discovered }}，已确认 {{ row.verificationCounts.confirmedOpen ?? 0 }}，待确认 {{ row.verificationCounts.probable ?? 0 }}，下线 {{ row.verificationCounts.closed ?? 0 }}，未确认 {{ row.verificationCounts.unknown ?? 0 }}
                </span>
                <span v-else>—</span>
              </template>
            </el-table-column>
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
            <div><strong>{{ search.summary.droppedCount }}</strong><span>丢弃无效条目</span></div>
          </div>
          <p v-if="queryAdjustments.length" class="section-note">
            自动调整：{{ queryAdjustments.join('；') }}
          </p>
          </details>
        </section>

        <section class="panel">
          <div class="panel__heading">
            <div>
              <h2>搜索结果</h2>
              <p class="section-note">展示符合筛选条件的相关岗位。投递前可查看原页确认最新状态。</p>
            </div>
            <el-button :loading="createSubmitting" :disabled="busying || !profileReady" @click="createSearch">重新搜索</el-button>
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
          <AsyncState :loading="itemsLoading" :error="itemsError" :empty="!page.records.length" :empty-text="filteredEmpty ? '当前筛选没有匹配岗位' : sourceUnavailable ? '岗位来源不可用或结果不完整' : hasItemFilters ? '当前筛选没有匹配岗位' : '本次搜索没有匹配岗位'" :empty-description="filteredEmpty ? '本次搜索有可用岗位，可清除筛选查看全部结果。' : sourceUnavailable ? '请查看来源诊断，或导入个人 JD 继续匹配。' : hasItemFilters ? '可清除筛选查看本次搜索的全部岗位。' : '可放宽城市或求职类型后重新搜索。'" @retry="loadItems">
            <template #empty>
              <el-button v-if="hasItemFilters" @click="resetFilters">清除筛选</el-button>
              <el-button type="primary" plain @click="router.push({ name: 'jobs', query: { tab: 'personal' } })">导入个人 JD</el-button>
            </template>
            <div class="search-list">
              <article v-for="item in page.records" :key="item.id" class="search-card">
                <div class="search-card__heading">
                  <div>
                    <h3><el-tag effect="plain" size="small">优先级 {{ item.rankNo }}</el-tag> {{ item.title }}</h3>
                    <p>{{ item.companyName }} · {{ cityName(item.cityCode, item.cityName) }} · {{ candidateTypeLabels[item.candidateType] || item.candidateType }}</p>
                  </div>
                  <div class="search-score">
                    <strong>{{ item.matchScore === null ? '待评估' : item.matchScore }}</strong>
                    <span>匹配分（辅助排序）</span>
                  </div>
                </div>

                <div class="tag-list">
                  <el-tag :type="eligibilityType(item.eligibility.status)" size="small">{{ eligibilityLabels[item.eligibility.status] || item.eligibility.status }}</el-tag>
                  <el-tag size="small" effect="plain">{{ item.source?.name || '来源未提供' }}</el-tag>
                  <el-tag :type="item.verification === 'VERIFIED' ? 'success' : 'warning'" size="small">{{ item.verification === 'VERIFIED' ? '来源已核验' : '来源未核验' }}</el-tag>
                  <el-tag v-if="item.recruitmentStatus === 'OPEN'" type="success" size="small">已确认在招</el-tag>
                  <el-tag v-else-if="item.recruitmentStatus === 'PROBABLE'" type="warning" size="small">待确认在招</el-tag>
                  <el-tag v-else-if="item.recruitmentStatus === 'CLOSED'" type="info" size="small">原文显示已截止或下线</el-tag>
                  <el-tag v-else type="info" size="small">在招状态未核实</el-tag>
                  <el-tag v-if="item.warnings?.includes('COMPANY_SIZE_UNKNOWN')" type="info" size="small">规模未知</el-tag>
                  <el-tag v-if="(item.aliases?.length || 0) > 1" type="success" size="small">多来源 {{ item.aliases.length }}</el-tag>
                </div>

                <el-descriptions :column="3" border size="small" class="job-facts">
                  <el-descriptions-item label="薪资">{{ salary(item) }}</el-descriptions-item>
                  <el-descriptions-item label="经验">{{ experience(item) }}</el-descriptions-item>
                  <el-descriptions-item label="学历">{{ education(item) }}</el-descriptions-item>
                  <el-descriptions-item label="公司规模">{{ item.companySizeText || sizeLabel(item) }}</el-descriptions-item>
                  <el-descriptions-item label="发布时间">{{ formatDate(item.publishedAt) }}</el-descriptions-item>
                  <el-descriptions-item v-if="item.updatedAt" label="官网更新">{{ formatDate(item.updatedAt) }}</el-descriptions-item>
                  <el-descriptions-item v-if="item.recruitmentCheckedAt" label="在招核验时间">{{ formatDate(item.recruitmentCheckedAt) }}</el-descriptions-item>
                  <el-descriptions-item v-if="item.verificationReason" label="核验结论">{{ item.verificationReason }}</el-descriptions-item>
                  <el-descriptions-item v-if="item.searchProviders?.length" label="搜索引擎">{{ item.searchProviders.join(' / ') }}</el-descriptions-item>
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

                <div v-if="item.fieldSources" class="source-aliases">
                  <el-popover v-for="(proof, field) in item.fieldSources" :key="field" trigger="click" width="320">
                    <template #reference><el-button link type="primary">{{ ({ salary: '薪资', companySize: '规模', city: '城市', education: '学历', experience: '经验', publishedAt: '发布时间', updatedAt: '更新时间', companyName: '公司' } as Record<string, string>)[field] || field }}依据</el-button></template>
                    <p>{{ proof.rawText }}</p><p>{{ proof.fetchedAt }}</p>
                    <el-button link type="primary" @click="openSource(proof.sourceUrl)">{{ proof.sourceName }}</el-button>
                  </el-popover>
                </div>
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
                  <el-button plain type="primary" :disabled="!safeUrl(item.sourceUrl)" @click="openSource(item.sourceUrl)">{{ item.recruitmentStatus === 'OPEN' ? '前往投递' : '查看原页' }}</el-button>
                  <el-button @click="emit('plan', item)">添加投递计划</el-button>
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
.job-search-page { display: grid; gap: 16px; min-width: 0; padding-bottom: 24px; }
.job-search-page > .panel { margin-bottom: 0; }
.section-note { margin: 6px 0 0; color: $ai-text-secondary; line-height: 1.6; }
.inline-alert { margin: 0 0 14px; }
.actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.profile-line { display: flex; flex-wrap: wrap; gap: 8px 18px; margin-bottom: 16px; color: $ai-text-secondary; font-size: 12px; }
.source-health { margin: -8px 0 14px; }
.source-health summary { cursor: pointer; color: $ai-text-secondary; font-size: 12px; user-select: none; }
.source-health__items { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.source-health__note { margin: 6px 0 0; font-size: 12px; line-height: 1.6; }
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
  .form-grid :deep(.el-form-item) { display: block; }
  .form-grid :deep(.el-form-item__label) { justify-content: flex-start; }
  .summary-grid, .filter-grid { grid-template-columns: 1fr; }
  .search-card__heading { flex-direction: column; }
  .search-score { align-items: flex-start; }
}
</style>
