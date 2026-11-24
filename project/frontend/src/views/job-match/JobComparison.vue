<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { jobApi } from '@/api/jobs'
import { profileApi } from '@/api/profile'
import { resumeApi } from '@/api/resumes'
import { canRetryTrackedTask, retryTrackedTask } from '@/api/task-registry'
import type { JobVO, MatchBatchVO, Page, PageQuery, ResumeVersionVO, TaskVO } from '@/api/types'
import AsyncState from '@/components/AsyncState.vue'
import TaskStatus from '@/components/TaskStatus.vue'
import { isTerminalTask, useTask } from '@/composables/useTask'
import { errorMessage, formatDate } from '@/utils/format'

const props = defineProps<{ initialJob: JobVO | null }>()
const historyOpen = defineModel<boolean>('historyOpen', { required: true })
const route = useRoute()
const router = useRouter()

const resumeId = ref('')
const jobId = ref('')
const resumes = ref<{ id: string; label: string }[]>([])
const personalJobs = ref<JobVO[]>([])
const publicJobs = ref<JobVO[]>([])
const pinnedJob = ref<{ id: string; title: string; companyName?: string | null } | null>(null)
const sourcesLoading = ref(true)
const sourceError = ref<unknown>(null)
const jobsLoading = ref(false)
const jobsError = ref<unknown>(null)
const jobQuery = ref('')
let jobsRequest = 0

const targetOptions = computed(() => {
  const options = new Map<string, NonNullable<typeof pinnedJob.value>>()
  if (pinnedJob.value) options.set(pinnedJob.value.id, pinnedJob.value)
  const imports = personalJobs.value.filter(job => !jobQuery.value
    || [job.title, job.companyName].join(' ').toLowerCase().includes(jobQuery.value))
  for (const job of [...imports, ...publicJobs.value]) {
    if (job.status === 'ACTIVE' && job.requirements) options.set(job.id, job)
  }
  return [...options.values()]
})

async function allRecords<T>(request: (query: PageQuery) => Promise<Page<T>>) {
  const size = 100
  const first = await request({ current: 1, size })
  const pages = Math.ceil(first.total / size)
  const remaining = await Promise.all(Array.from({ length: Math.max(0, pages - 1) },
    (_, index) => request({ current: index + 2, size })))
  return [first, ...remaining].flatMap(page => page.records)
}

function canCompare(version: ResumeVersionVO) {
  return version.status === 'PARSED' || version.status === 'CONFIRMED'
}

async function loadSources() {
  sourcesLoading.value = true
  sourceError.value = null
  try {
    const [documents, imports, profile] = await Promise.all([
      allRecords(resumeApi.list), allRecords(jobApi.imports), profileApi.get(),
    ])
    personalJobs.value = imports
    const versions = await Promise.all(documents.map(async (document) => {
      const available = canCompare(document.latestVersion) ? document.latestVersion
        : (await resumeApi.versions(document.id)).find(canCompare)
      if (!available) return null
      return { id: available.id, label: `${document.title} · v${available.versionNo}` }
    }))
    resumes.value = versions.filter(option => option !== null)
    if (!resumeId.value) {
      resumeId.value = resumes.value.find(option => option.id === profile.activeResumeVersionId)?.id
        || resumes.value[0]?.id || ''
    }
  } catch (reason) {
    sourceError.value = reason
  } finally {
    sourcesLoading.value = false
  }
}

async function searchJobs(query: string) {
  const request = ++jobsRequest
  jobQuery.value = query.trim().toLowerCase()
  jobsLoading.value = true
  jobsError.value = null
  try {
    const page = await jobApi.list({ current: 1, size: 30, q: query.trim() || undefined })
    if (request !== jobsRequest) return
    publicJobs.value = page.records
  } catch (reason) {
    if (request === jobsRequest) jobsError.value = reason
  } finally {
    if (request === jobsRequest) jobsLoading.value = false
  }
}

function chooseTarget(id: string) {
  pinnedJob.value = targetOptions.value.find(option => option.id === id) || null
}

watch(() => props.initialJob, (job) => {
  if (!job) return
  pinnedJob.value = job
  jobId.value = job.id
}, { immediate: true })

const history = ref<Page<MatchBatchVO>>({ records: [], total: 0, current: 1, size: 10 })
const historyLoading = ref(false)
const historyError = ref<unknown>(null)
const result = ref<MatchBatchVO | null>(null)
const resultJobId = ref('')
const resultLoading = ref(false)
const resultError = ref<unknown>(null)
let resultRequest = 0
let historyRequest = 0
const resultJob = computed(() => result.value?.items.find(item => item.jobId === resultJobId.value) || result.value?.items[0])
const evidence = computed(() => resultJob.value?.requirementEvidence ?? [])
const gaps = computed(() => evidence.value.filter(entry => entry.supportLevel !== 'DIRECT'))

async function loadHistory(page = 1) {
  const request = ++historyRequest
  historyLoading.value = true
  historyError.value = null
  try {
    const value = await jobApi.listMatches({ current: page, size: history.value.size })
    if (request !== historyRequest) return
    history.value = value
  } catch (reason) {
    if (request === historyRequest) historyError.value = reason
  } finally {
    if (request === historyRequest) historyLoading.value = false
  }
}

async function loadResult(id: string) {
  const request = ++resultRequest
  resultLoading.value = Boolean(id)
  resultError.value = null
  result.value = null
  resultJobId.value = typeof route.query.jobId === 'string' ? route.query.jobId : ''
  if (!id) return
  try {
    const value = await jobApi.getMatch(id)
    if (request !== resultRequest) return
    result.value = value
    const job = resultJob.value
    if (job) resultJobId.value = job.jobId
    if (job && !jobId.value) {
      pinnedJob.value = { id: job.jobId, title: job.title, companyName: job.companyName }
      jobId.value = job.jobId
    }
  } catch (reason) {
    if (request === resultRequest) resultError.value = reason
  } finally {
    if (request === resultRequest) resultLoading.value = false
  }
}

function routeMatchId() {
  return typeof route.query.matchId === 'string' ? route.query.matchId : ''
}

async function showResult(id: string) {
  historyOpen.value = false
  if (routeMatchId() === id && !route.query.jobId) {
    await loadResult(id)
  } else {
    await router.replace({ query: { ...route.query, tab: 'matches', matchId: id, jobId: undefined } })
  }
}

watch(() => [routeMatchId(), route.query.jobId] as const, ([id]) => void loadResult(id), { immediate: true })
watch(historyOpen, open => { if (open) void loadHistory() })
void loadSources()
void searchJobs('')

const taskId = ref<string | null>(null)
const submitting = ref(false)
const retrying = ref(false)
const actionError = ref<unknown>(null)
const tracker = useTask(taskId, { onDone: comparisonDone })
const task = tracker.task
const busy = computed(() => submitting.value || retrying.value || Boolean(taskId.value && !isTerminalTask(task.value?.status)))

async function compare() {
  const selectedResume = resumeId.value
  const selectedJob = jobId.value
  if (!selectedResume || !selectedJob || busy.value) return
  submitting.value = true
  actionError.value = null
  taskId.value = null
  try {
    const profile = await profileApi.get()
    const accepted = await jobApi.createMatch({
      resumeVersionId: selectedResume,
      jobIds: [selectedJob],
      expectedProfileVersion: profile.profileVersion,
    })
    taskId.value = accepted.id
  } catch (reason) {
    actionError.value = reason
  } finally {
    submitting.value = false
  }
}

async function comparisonDone(value: TaskVO) {
  const id = value.result?.resourceId || value.resourceId
  if (!id) {
    actionError.value = new Error('比较完成但未返回结果')
    return
  }
  await showResult(id)
  ElMessage.success('岗位差距对比已生成')
}

async function retry() {
  if (!task.value || !canRetryTrackedTask(task.value)) return
  retrying.value = true
  actionError.value = null
  try {
    taskId.value = (await retryTrackedTask(task.value)).id
  } catch (reason) {
    actionError.value = reason
  } finally {
    retrying.value = false
  }
}
</script>

<template>
  <div class="comparison">
    <form class="panel comparison-setup" @submit.prevent="compare">
      <div class="comparison-field">
        <label for="comparison-resume">原简历</label>
        <el-select id="comparison-resume" v-model="resumeId" filterable placeholder="选择已解析的原简历" :loading="sourcesLoading" :disabled="busy || sourcesLoading" no-data-text="暂无已解析的简历">
          <el-option v-for="option in resumes" :key="option.id" :label="option.label" :value="option.id" />
        </el-select>
        <router-link v-if="!sourcesLoading && !sourceError && !resumes.length" to="/resume">前往简历中心</router-link>
      </div>
      <div class="comparison-field">
        <label for="comparison-job">目标岗位</label>
        <el-select id="comparison-job" v-model="jobId" filterable remote :remote-method="searchJobs" placeholder="搜索或选择目标岗位" :loading="jobsLoading" :disabled="busy" no-data-text="暂无可对比岗位" @change="chooseTarget">
          <el-option v-for="job in targetOptions" :key="job.id" :label="[job.title, job.companyName].filter(Boolean).join(' · ')" :value="job.id" />
        </el-select>
      </div>
      <el-button native-type="submit" type="primary" :loading="busy" :disabled="!resumeId || !jobId">对比差距</el-button>
    </form>
    <el-alert v-if="sourceError || jobsError" class="comparison-alert" type="error" :title="errorMessage(sourceError || jobsError)" :closable="false">
      <el-button v-if="sourceError" size="small" @click="loadSources">重载简历和个人 JD</el-button>
      <el-button v-if="jobsError" size="small" @click="searchJobs('')">重载岗位</el-button>
    </el-alert>
    <el-alert v-if="actionError" class="comparison-alert" type="error" :title="errorMessage(actionError)" :closable="false" />
    <TaskStatus v-if="taskId && (!task || task.status !== 'SUCCEEDED')" class="comparison-alert" :task="task" :loading="tracker.loading.value" :tracking-error="tracker.error.value" :refreshing="tracker.refreshing.value" @refresh="tracker.refresh">
      <template #actions>
        <el-button v-if="task && canRetryTrackedTask(task)" size="small" :loading="retrying" @click="retry">重试对比</el-button>
      </template>
    </TaskStatus>

    <AsyncState :loading="resultLoading" :error="resultError" :empty="Boolean(routeMatchId()) && !result" @retry="loadResult(routeMatchId())">
      <section v-if="result" class="comparison-results" aria-labelledby="comparison-title">
        <header class="comparison-heading">
          <div>
            <h2 id="comparison-title">原简历与岗位差距</h2>
            <p v-if="resultJob">{{ [resultJob.title, resultJob.companyName].filter(Boolean).join(' · ') }}</p>
          </div>
          <span v-if="gaps.length">{{ gaps.length }} 项差距</span>
        </header>
        <p v-if="result.resumeName" class="comparison-source">原简历 <strong>{{ result.resumeName }}<template v-if="result.resumeVersionNo"> · v{{ result.resumeVersionNo }}</template></strong></p>
        <el-select v-if="result.items.length > 1" v-model="resultJobId" class="comparison-history-job" aria-label="历史目标岗位" placeholder="选择历史目标岗位">
          <el-option v-for="job in result.items" :key="job.id" :label="job.title" :value="job.jobId" />
        </el-select>
        <p v-if="result.status === 'GENERATING'" class="comparison-empty">正在生成对比。<el-button text type="primary" @click="loadResult(result.id)">刷新结果</el-button></p>
        <p v-else-if="result.status === 'FAILED'" class="comparison-empty">这次对比生成失败，可以用上方表单重新对比。</p>
        <template v-else>
          <p v-if="!evidence.length" class="comparison-empty">{{ result.resumeVersionId ? '岗位描述中没有可展示的具体对照项。' : '这条历史记录没有逐项差距，可以重新对比。' }}</p>
          <p v-else-if="!gaps.length" class="comparison-empty">此次对比核对的岗位要求均有原文依据。</p>
          <div v-else class="comparison-table-wrap">
            <table class="comparison-table">
              <thead><tr><th scope="col">岗位要求</th><th scope="col">原简历</th><th scope="col">具体差距</th></tr></thead>
              <tbody>
                <tr v-for="(gap, index) in gaps" :key="`${gap.requirementQuote}-${index}`">
                  <td data-label="岗位要求">
                    <strong v-if="gap.requirementTitle">{{ gap.requirementTitle }}</strong>
                    <p :class="{ 'comparison-plain-quote': !gap.requirementTitle }">{{ gap.requirementQuote }}</p>
                  </td>
                  <td data-label="原简历"><span v-if="gap.resumeQuote" class="comparison-quote">“{{ gap.resumeQuote }}”</span><span v-else class="comparison-muted">未找到对应原文</span></td>
                  <td data-label="具体差距">
                    <strong>{{ gap.gapTitle || (gap.resumeQuote ? '原简历仅体现部分要求' : '原简历未体现此项要求') }}</strong>
                    <p v-if="gap.gapSummary">{{ gap.gapSummary }}</p>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-if="result.resumeTruncated || resultJob?.requirementEvidenceCoverage?.jdTruncated" class="comparison-source">原文较长，本次只对比了可读取的前段内容。</p>
        </template>
      </section>
      <p v-else-if="!taskId" class="comparison-empty">选择原简历和目标岗位，即可查看差距。</p>
    </AsyncState>

    <el-drawer v-model="historyOpen" title="历史对比" size="420px" class="responsive-dialog">
      <AsyncState :loading="historyLoading" :error="historyError" :empty="!history.records.length" empty-text="暂无历史对比" @retry="loadHistory(history.current)">
        <div class="comparison-history">
          <button v-for="batch in history.records" :key="batch.id" type="button" :class="{ active: result?.id === batch.id }" @click="showResult(batch.id)">
            <strong>{{ batch.targetTitle || '历史岗位对比' }}</strong>
            <span>{{ [batch.resumeName, formatDate(batch.createdAt)].filter(Boolean).join(' · ') }}</span>
          </button>
        </div>
        <el-pagination v-if="history.total > history.size" class="comparison-pagination" small background layout="prev, pager, next" :total="history.total" :current-page="history.current" :page-size="history.size" @current-change="loadHistory" />
      </AsyncState>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.comparison-setup { display: grid; grid-template-columns: minmax(0, 2fr) minmax(0, 3fr) auto; align-items: start; gap: 14px; padding: 16px 18px; }
.comparison-field { display: grid; gap: 6px; min-width: 0; }
.comparison-field label, .comparison-source { color: $ai-text-secondary; font-size: 13px; }
.comparison-field :deep(.el-select) { width: 100%; }
.comparison-field :deep(.el-select__wrapper), .comparison-setup > button { min-height: 38px; }
.comparison-setup > button { margin-top: 24px; }
.comparison-alert { margin-top: 12px; }
.comparison-results { margin-top: 26px; }
.comparison-heading { display: flex; justify-content: space-between; align-items: end; gap: 12px; margin-bottom: 14px; }
.comparison-heading h2 { margin: 0 0 5px; font-size: 23px; }
.comparison-heading p, .comparison-heading span { margin: 0; color: $ai-text-secondary; font-size: 14px; }
.comparison-heading span { flex: none; }
.comparison-table-wrap { overflow: hidden; border: 1px solid $ai-border; border-radius: $ai-radius-lg; background: var(--ai-bg-card); }
.comparison-table { width: 100%; border-collapse: collapse; table-layout: fixed; line-height: 1.7; font-size: 14px; }
.comparison-table :is(th, td) { padding: 14px 18px; text-align: left; overflow-wrap: anywhere; vertical-align: top; }
.comparison-table th { padding-block: 9px; background: $ai-bg-light; color: $ai-text-primary; }
.comparison-table th:nth-child(1) { width: 33%; }
.comparison-table th:nth-child(2) { width: 29%; }
.comparison-table td { border-top: 1px solid $ai-border; color: $ai-text-secondary; white-space: pre-wrap; }
.comparison-table td:last-child { background: color-mix(in srgb, var(--ai-bg-card) 65%, var(--ai-bg-light)); border-inline-start: 1px solid $ai-border; }
.comparison-table td strong { color: $ai-text-primary; }
.comparison-table td:last-child strong { color: var(--ai-warning, #9a4c02); }
.comparison-table td p { margin: 4px 0 0; }
.comparison-table td .comparison-plain-quote { margin-top: 0; }
.comparison-muted { color: $ai-text-tertiary; }
.comparison-source { margin: 8px 0 12px; }
.comparison-source strong { color: $ai-text-primary; font-weight: 500; }
.comparison-empty { margin: 16px 0 0; padding: 16px 0; color: $ai-text-secondary; }
.comparison-history-job { width: min(100%, 360px); margin: 0 0 12px; }
.comparison-history { display: grid; gap: 10px; }
.comparison-history button { display: grid; gap: 5px; padding: 12px; border: 1px solid $ai-border; border-radius: $ai-radius; background: var(--ai-bg-card); color: $ai-text-primary; text-align: start; cursor: pointer; overflow-wrap: anywhere; }
.comparison-history button:hover, .comparison-history button.active { border-color: $ai-primary; background: $ai-primary-light; }
.comparison-history button span { color: $ai-text-secondary; font-size: 13px; }
.comparison-pagination { justify-content: center; margin-top: 12px; }
@media (max-width: 720px) {
  .comparison-setup { grid-template-columns: 1fr; }
  .comparison-setup > button { margin-top: 0; }
  .comparison-table thead { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
  .comparison-table :is(tbody, tr, td) { display: block; }
  .comparison-table tr + tr { border-top: 1px solid $ai-border; }
  .comparison-table td { border-top: 0; padding-block: 10px; }
  .comparison-table td:last-child { border-inline-start: 0; }
  .comparison-table td::before { display: block; content: attr(data-label); font-weight: 600; color: $ai-text-primary; }
}
</style>
