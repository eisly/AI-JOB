<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Star, StarFilled, TopRight } from '@element-plus/icons-vue'
import { jobApi } from '@/api/jobs'
import { jobSearchApi } from '@/api/job-searches'
import { ApiError } from '@/api/request'
import { profileApi } from '@/api/profile'
import { taskApi } from '@/api/tasks'
import { canRetryTrackedTask, retryTrackedTask } from '@/api/task-registry'
import type {
  CompanySize,
  JobSearchCreateInput,
  JobSearchItemVO,
  JobSearchVO,
  ProfileVO,
  TaskVO,
} from '@/api/types'
import AsyncState from '@/components/AsyncState.vue'
import TaskStatus from '@/components/TaskStatus.vue'
import { isTerminalTask, useTask } from '@/composables/useTask'
import { useUserStore } from '@/stores/user'
import { candidateTypeLabels, cityLabels, companySizeLabels } from '@/utils/format'
import { jobSalary } from '@/utils/job-search-fields'

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
const actionError = ref('')
const canceling = ref(false)
const retrying = ref(false)
const favoritePendingIds = ref<string[]>([])
const filtersOpen = ref(false)

const createForm = reactive({
  keyword: '',
  cities: [] as string[],
  candidateTypes: [] as string[],
  companySizes: [] as CompanySize[],
  includeUnknownSize: true,
  maxResults: 30,
})
function defaultResultFilters() {
  return { keyword: '', cityCode: '', candidateType: '', companySizes: [] as CompanySize[], includeUnknownSize: true }
}
const itemFilters = reactive(defaultResultFilters())
const resultFilterForm = reactive(defaultResultFilters())

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
  refreshing: taskRefreshing,
  refresh: refreshTask,
  acceptSnapshot,
} = useTask(taskId, {
  onDone: async done => {
    const id = done.result?.resourceId || done.resourceId
    if (!id) {
      actionError.value = '未能读取搜索结果，请重新搜索。'
      return
    }
    searchId.value = id
    writeStorage(searchStorageKey.value, id)
    writeStorage(taskStorageKey.value, '')
    if (await loadSearch(id)) await loadItems()
  },
})

const taskActive = computed(() => {
  if (task.value) return !isTerminalTask(task.value.status)
  const inaccessible = taskError.value instanceof ApiError && [403, 404].includes(taskError.value.status)
  return !!taskId.value && !inaccessible
})
const busying = computed(() => createSubmitting.value || taskLoading.value || searchLoading.value || taskActive.value)
const showSearchTask = computed(() => !!taskId.value && task.value?.status !== 'SUCCEEDED')
let itemsRequest = 0
const hasItemFilters = computed(() => Boolean(
  itemFilters.keyword.trim() || itemFilters.cityCode || itemFilters.candidateType ||
  itemFilters.companySizes.length || !itemFilters.includeUnknownSize,
))
const emptyResultText = computed(() => {
  if (!search.value) return '输入关键词或选择条件，开始搜索岗位。'
  if (hasItemFilters.value && (search.value.summary?.uniqueCount ?? 0) > 0) return '当前筛选没有匹配岗位。'
  if (search.value.status === 'FAILED' || search.value.summary?.warnings?.includes('SEARCH_FAILED') ||
      task.value?.result?.outcome === 'NO_RESULTS_SOURCE_FAILED') return '暂时无法获取岗位，请重新搜索。'
  return '没有找到符合条件的岗位。'
})

function canCancel(value?: TaskVO | null) {
  return value?.status === 'QUEUED' || value?.status === 'RUNNING'
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
    return true
  } catch (reason) {
    searchError.value = reason
    // S06：恢复记录中的失效搜索 ID（如数据清理后残留）自动清理，刷新后不再反复报 404
    if (reason instanceof ApiError && reason.status === 404 && searchId.value === id) {
      search.value = null
      searchId.value = ''
      searchError.value = null
      writeStorage(searchStorageKey.value, '')
    }
    return false
  } finally {
    searchLoading.value = false
  }
}

async function loadItems() {
  const id = searchId.value
  if (!id) return false
  const request = ++itemsRequest
  itemsLoading.value = true
  itemsError.value = null
  try {
    const result = await jobSearchApi.items(id, {
      current: page.current,
      size: page.size,
      keyword: itemFilters.keyword.trim() || undefined,
      cityCode: itemFilters.cityCode || undefined,
      candidateType: itemFilters.candidateType || undefined,
      companySizes: itemFilters.companySizes,
      includeUnknownSize: itemFilters.includeUnknownSize,
    })
    if (request !== itemsRequest || id !== searchId.value) return false
    page.records = result.records
    page.total = result.total
    page.current = result.current
    page.size = result.size
    return true
  } catch (reason) {
    if (request === itemsRequest && id === searchId.value) itemsError.value = reason
    return false
  } finally {
    if (request === itemsRequest) itemsLoading.value = false
  }
}

async function createSearch() {
  if (!profile.value || !profileReady.value || busying.value) {
    if (!profileReady.value) ElMessage.warning('请先确认简历并保存目标岗位、求职类型和城市偏好')
    return
  }
  createSubmitting.value = true
  actionError.value = ''
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
    page.current = 1
    Object.assign(itemFilters, defaultResultFilters())
    if (linkedSearchId || linkedTaskId) {
      await router.replace({ name: 'jobs', query: { tab: 'search' } })
    }
  } catch (reason) {
    if (reason instanceof ApiError && reason.isVersionConflict) {
      actionError.value = '求职画像已更新，请重新搜索。'
      await loadProfile()
    } else {
      actionError.value = '搜索提交失败，请重试。'
    }
  } finally {
    createSubmitting.value = false
  }
}

async function cancelSearch() {
  if (!task.value || !canCancel(task.value) || canceling.value) return
  canceling.value = true
  actionError.value = ''
  try {
    await acceptSnapshot(await taskApi.cancel(task.value.id))
  } catch {
    actionError.value = '取消失败，请重试。'
  } finally {
    canceling.value = false
  }
}

async function retrySearch() {
  if (!task.value || !canRetryTrackedTask(task.value) || retrying.value) return
  retrying.value = true
  actionError.value = ''
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
  } catch {
    actionError.value = '重试失败，请稍后再试。'
  } finally {
    retrying.value = false
  }
}

function openFilters() {
  Object.assign(resultFilterForm, itemFilters, { companySizes: [...itemFilters.companySizes] })
  filtersOpen.value = true
}

function applyFilters() {
  Object.assign(itemFilters, resultFilterForm, { companySizes: [...resultFilterForm.companySizes] })
  page.current = 1
  filtersOpen.value = false
  void loadItems()
}

function resetFilters() {
  Object.assign(resultFilterForm, defaultResultFilters())
  applyFilters()
}

async function reloadResults() {
  if ((searchError.value || !search.value) && !await loadSearch()) return
  await loadItems()
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

function jobMetadata(item: JobSearchItemVO) {
  const warnings = item.warnings || []
  const company = warnings.includes('COMPANY_FROM_DOMAIN') ? '' : item.companyName?.trim()
  const city = (item.cityCode && cityLabels[item.cityCode]) || item.cityName
  const type = warnings.includes('CANDIDATE_TYPE_UNKNOWN') ? '' : candidateTypeLabels[item.candidateType]
  const verification = (item.verificationStatus ?? item.recruitmentStatus) === 'UNKNOWN' ? '在招状态待核验' : ''
  return [company, city, type, jobSalary(item), verification].filter(Boolean).join(' · ')
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

async function toggleFavorite(item: JobSearchItemVO) {
  if (favoritePendingIds.value.includes(item.jobId)) return
  favoritePendingIds.value = [...favoritePendingIds.value, item.jobId]
  try {
    const next = await jobApi.setFavorite(item.jobId, !item.isFavorite)
    item.isFavorite = next
    ElMessage.success(next ? '已收藏岗位' : '已取消收藏')
  } catch {
    ElMessage.error('收藏操作失败，请重试。')
  } finally {
    favoritePendingIds.value = favoritePendingIds.value.filter(id => id !== item.jobId)
  }
}

onMounted(() => {
  void loadProfile()
  if (searchId.value) void reloadResults()
})
</script>

<template>
  <div class="job-search-page">
    <AsyncState
      class="search-controls"
      :loading="profileLoading && !profile"
      :retain="!!profile"
      :error="profileError ? '暂时无法读取求职画像。' : null"
      :skeleton-rows="1"
      loading-text="正在读取搜索偏好"
      @retry="loadProfile"
    >
      <form class="search-toolbar" aria-label="岗位搜索" @submit.prevent="createSearch">
        <el-input
          v-model="createForm.keyword"
          :prefix-icon="Search"
          aria-label="岗位关键词"
          placeholder="岗位关键词"
          maxlength="100"
          clearable
          size="large"
        />
        <el-select v-model="createForm.cities" aria-label="城市" multiple collapse-tags :max-collapse-tags="2" collapse-tags-tooltip filterable clearable placeholder="城市" size="large">
          <el-option v-for="option in cityOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-select v-model="createForm.candidateTypes" aria-label="求职类型" multiple collapse-tags collapse-tags-tooltip clearable placeholder="求职类型" size="large">
          <el-option v-for="option in candidateOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-button link type="primary" class="search-toolbar__more" :aria-expanded="filtersOpen" @click="openFilters">更多筛选</el-button>
        <el-button class="search-toolbar__submit" type="primary" native-type="submit" size="large" :loading="createSubmitting" :disabled="busying || !profileReady">
          {{ createSubmitting ? '提交中…' : '搜索' }}
        </el-button>
      </form>
      <p v-if="profile && !profileReady" class="search-hint">
        确认简历并完善求职偏好后即可搜索。
        <router-link :to="{ name: 'profile' }">前往求职画像</router-link>
      </p>
    </AsyncState>

    <section class="search-results" aria-labelledby="search-results-title">
      <div class="search-results__heading">
        <h2 id="search-results-title">搜索结果</h2>
        <el-button v-if="hasItemFilters && search" link type="primary" @click="resetFilters">清除结果筛选</el-button>
      </div>
      <p v-if="actionError" class="search-action-error" role="alert">{{ actionError }}</p>
      <TaskStatus
        v-if="showSearchTask"
        :task="task"
        :loading="taskLoading || !task"
        :tracking-error="taskError"
        :refreshing="taskRefreshing"
        subject="搜索"
        hide-details
        running-description="结果准备好后会自动显示在这里。"
        @refresh="refreshTask"
      >
        <template #actions>
          <el-button v-if="task && canRetryTrackedTask(task)" size="small" :loading="retrying" @click="retrySearch">重试搜索</el-button>
          <el-button v-if="canCancel(task)" size="small" :loading="canceling" @click="cancelSearch">取消搜索</el-button>
          <el-button v-if="task?.status === 'CANCEL_REQUESTED'" size="small" disabled>取消中…</el-button>
        </template>
      </TaskStatus>
      <AsyncState
        v-else
        :loading="searchLoading || itemsLoading"
        :retain="page.records.length > 0"
        :error="searchError || itemsError ? '暂时无法读取岗位，请重试。' : null"
        loading-text="正在读取岗位"
        @retry="reloadResults"
      >
        <ul v-if="page.records.length" class="search-list">
          <li v-for="item in page.records" :key="item.id" class="search-row">
            <div class="search-row__content">
              <h3>{{ item.title }}</h3>
              <p v-if="jobMetadata(item)">{{ jobMetadata(item) }}</p>
            </div>
            <div class="search-row__actions">
              <el-button
                class="search-row__favorite"
                link
                :type="item.isFavorite ? 'primary' : undefined"
                :icon="item.isFavorite ? StarFilled : Star"
                :loading="favoritePendingIds.includes(item.jobId)"
                :aria-label="(item.isFavorite ? '取消收藏 ' : '收藏 ') + item.title"
                :aria-pressed="!!item.isFavorite"
                :title="item.isFavorite ? '取消收藏' : '收藏'"
                @click="toggleFavorite(item)"
              />
              <el-button link @click="emit('plan', item)">加入投递计划</el-button>
              <a v-if="safeUrl(item.sourceUrl)" class="search-row__source" :href="safeUrl(item.sourceUrl)" target="_blank" rel="noopener noreferrer">
                查看原页 <el-icon><TopRight /></el-icon>
              </a>
            </div>
          </li>
        </ul>
        <p v-else class="search-hint search-empty" role="status">{{ emptyResultText }}</p>
        <el-pagination
          v-if="page.total > 10"
          class="search-pagination"
          background
          layout="prev, pager, next, sizes"
          :total="page.total"
          :current-page="page.current"
          :page-size="page.size"
          :page-sizes="[10, 20, 50]"
          :pager-count="5"
          :disabled="itemsLoading"
          @current-change="changePage"
          @size-change="changeSize"
        />
      </AsyncState>
    </section>

    <el-drawer v-model="filtersOpen" title="更多筛选" size="min(420px, 100vw)">
      <el-form label-position="top" class="advanced-search" @submit.prevent>
        <h3>搜索选项</h3>
        <el-form-item label="公司规模">
          <el-select v-model="createForm.companySizes" aria-label="搜索公司规模" multiple clearable collapse-tags placeholder="不限规模">
            <el-option v-for="option in companySizeOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="createForm.includeUnknownSize">包含规模未知的岗位</el-checkbox>
        </el-form-item>
        <el-form-item label="最多返回岗位数">
          <el-input-number v-model="createForm.maxResults" aria-label="最多返回岗位数" :min="1" :max="60" :step="1" step-strictly />
        </el-form-item>
      </el-form>

      <el-form v-if="search && !showSearchTask" label-position="top" class="advanced-search advanced-search--results" @submit.prevent="applyFilters">
        <h3>筛选当前结果</h3>
        <el-form-item label="岗位或公司">
          <el-input v-model="resultFilterForm.keyword" aria-label="筛选结果岗位或公司" placeholder="岗位或公司关键词" maxlength="100" clearable />
        </el-form-item>
        <el-form-item label="城市">
          <el-select v-model="resultFilterForm.cityCode" aria-label="筛选结果城市" clearable filterable placeholder="全部城市">
            <el-option v-for="option in cityOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="求职类型">
          <el-select v-model="resultFilterForm.candidateType" aria-label="筛选结果求职类型" clearable placeholder="全部类型">
            <el-option v-for="option in candidateOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="公司规模">
          <el-select v-model="resultFilterForm.companySizes" aria-label="筛选结果公司规模" multiple clearable collapse-tags placeholder="不限规模">
            <el-option v-for="option in companySizeOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="resultFilterForm.includeUnknownSize">包含规模未知的岗位</el-checkbox>
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="itemsLoading">应用结果筛选</el-button>
        <el-button @click="resetFilters">清除筛选</el-button>
      </el-form>
      <template #footer>
        <el-button @click="filtersOpen = false">完成</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.job-search-page { display: grid; gap: 34px; min-width: 0; }
.search-toolbar {
  display: grid;
  grid-template-columns: minmax(140px, 2.35fr) minmax(130px, 1fr) minmax(108px, .82fr) auto auto;
  gap: 14px;
  align-items: center;
}
.search-toolbar :deep(.el-button) { margin-left: 0; }
.search-toolbar :deep(.el-input__wrapper), .search-toolbar :deep(.el-select__wrapper) { min-height: 44px; }
.search-toolbar__more { min-height: 44px; padding-inline: 8px; }
.search-toolbar__submit { min-width: 100px; min-height: 44px; }
.search-hint { margin: 10px 0 0; color: $ai-text-secondary; font-size: 14px; line-height: 1.5; }
.search-hint a { color: $ai-primary; }
.search-results { min-width: 0; }
.search-results__heading { display: flex; align-items: center; flex-wrap: wrap; justify-content: space-between; gap: 8px; margin-bottom: 12px; }
.search-results__heading h2 { margin: 0; font-size: 19px; line-height: 1.4; }
.search-action-error { margin: 0 0 12px; color: $ai-danger; font-size: 14px; }
.search-list { margin: 0; padding: 0; list-style: none; }
.search-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 102px;
  gap: 16px 24px;
  padding: 18px 0;
  border-bottom: 1px solid $ai-border-light;
}
.search-row__content { min-width: 0; overflow-wrap: anywhere; }
.search-row h3 { margin: 0; color: $ai-text-primary; font-size: 20px; font-weight: 650; line-height: 1.4; }
.search-row p { margin: 5px 0 0; color: $ai-text-secondary; font-size: 14px; line-height: 1.5; }
.search-row__actions { display: grid; grid-template-columns: 34px auto 83px; align-items: center; flex-shrink: 0; gap: 8px 20px; }
.search-row__actions :deep(.el-button) { min-height: 34px; margin-left: 0; }
.search-row__favorite { width: 34px; font-size: 19px; }
.search-row__source { display: inline-flex; align-items: center; justify-self: end; gap: 5px; min-height: 34px; color: $ai-primary; font-size: 14px; text-decoration: none; white-space: nowrap; }
.search-row__source:hover { color: $ai-primary-hover; text-decoration: underline; }
.search-empty { padding: 14px 0; }
.search-pagination { margin-top: 18px; justify-content: flex-end; }
.advanced-search { padding-bottom: 6px; }
.advanced-search h3 { margin: 0 0 16px; font-size: 15px; }
.advanced-search :deep(.el-select) { width: 100%; }
.advanced-search--results { border-top: 1px solid $ai-border-light; margin-top: 12px; padding-top: 20px; }

@media (max-width: 1060px) {
  .search-toolbar { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto auto; gap: 10px; }
  .search-toolbar > :first-child { grid-column: 1 / -1; }
}
@media (max-width: 620px) {
  .job-search-page { gap: 25px; }
  .search-toolbar { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .search-toolbar__more { justify-self: start; }
  .search-toolbar__submit { justify-self: end; }
  .search-row { display: grid; gap: 6px; padding: 14px 0; }
  .search-row h3 { font-size: 18px; }
  .search-row__actions { display: flex; flex-wrap: wrap; gap: 6px 12px; }
  .search-row__favorite { margin-left: -7px; }
  .search-pagination { justify-content: flex-start; flex-wrap: wrap; row-gap: 8px; }
}
</style>
