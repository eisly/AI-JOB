<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError, saveBinary } from '@/api/request'
import { resumeApi } from '@/api/resumes'
import { optimizationApi } from '@/api/optimizations'
import { jobApi } from '@/api/jobs'
import { retryTrackedTask } from '@/api/task-registry'
import type { JobVO, ResumeDiagnosisVO, ResumeOptimizationVO, ResumeVO, ResumeVersionVO } from '@/api/types'
import { useTask } from '@/composables/useTask'
import AsyncState from '@/components/AsyncState.vue'
import TaskStatus from '@/components/TaskStatus.vue'
import { addReference } from '@/utils/references'
import { commonStatusLabels, errorMessage, formatDate } from '@/utils/format'

interface DraftSection {
  sectionKey: string
  title: string
  content: string
}

interface OptimizationInput {
  resumeVersionId: string
  jobId?: string
  diagnosisId?: string
}

const route = useRoute()
const router = useRouter()
const resumeRecords = ref<ResumeVO[]>([])
const resumeVersions = ref<ResumeVersionVO[]>([])
function diagnosisTitle(versionId: string) {
  const version = resumeVersions.value.find(item => item.id === versionId)
  return version ? `${versionLabel(version)} · 诊断` : '简历诊断（历史版本）'
}
const resumeOptionsLoading = ref(false)
const resumeOptionsError = ref<unknown>(null)
const resumeOptionsWarning = ref('')
const selectedResumeVersionId = ref('')
const jobs = ref<JobVO[]>([])
const jobsLoading = ref(false)
const jobsError = ref<unknown>(null)
const jobsWarning = ref('')
const selectedJobId = ref('')
const diagnoses = ref<ResumeDiagnosisVO[]>([])
const diagnosisPage = ref(1)
const diagnosisSize = ref(5)
const diagnosisTotal = ref(0)
const diagnosisHistoryLoading = ref(false)
const diagnosisHistoryError = ref<unknown>(null)
const selectedDiagnosisId = ref('')
const currentDiagnosis = ref<ResumeDiagnosisVO | null>(null)
const diagnosisDetailLoading = ref(false)
const diagnosisDetailError = ref<unknown>(null)
const diagnoseSubmitting = ref(false)
const diagnoseError = ref<unknown>(null)
const linkDiagnosis = ref(true)
const optimizations = ref<ResumeOptimizationVO[]>([])
const optimizationPage = ref(1)
const optimizationSize = ref(5)
const optimizationTotal = ref(0)
const optimizationHistoryLoading = ref(false)
const optimizationHistoryError = ref<unknown>(null)
const selectedOptimizationId = ref('')
const currentOptimization = ref<ResumeOptimizationVO | null>(null)
const optimizationDetailLoading = ref(false)
const optimizationDetailError = ref<unknown>(null)
const optimizeSubmitting = ref(false)
const optimizeError = ref<unknown>(null)
const draftSections = ref<DraftSection[]>([])
const accepting = ref(false)
const acceptError = ref<unknown>(null)
const conflictMessage = ref('')
const conflictLoading = ref(false)
const baselineAcceptedAt = ref<string | null>(null)
const exporting = ref<'' | 'PDF' | 'DOCX'>('')
const exportError = ref<unknown>(null)
const activeTaskId = ref('')
const activeTaskKind = ref<'' | 'diagnosis' | 'optimization'>('')
const activeTaskInput = ref<OptimizationInput | null>(null)
const taskSubmitError = ref<unknown>(null)
const taskAuthorityError = ref<unknown>(null)
const taskRetryError = ref<unknown>(null)
const taskActionLoading = ref(false)
const initialized = ref(false)

const priorityLabels: Record<string, string> = { IMPORTANT: '重要', SUGGESTION: '建议', NOTICE: '提示' }
const selectedResumeVersion = computed(() => resumeVersions.value.find(item => item.id === selectedResumeVersionId.value) || null)
const confirmedVersions = computed(() => resumeVersions.value.filter(item => item.status === 'CONFIRMED').sort((a, b) => b.createdAt.localeCompare(a.createdAt)))
const analyzedJobs = computed(() => jobs.value.filter(item => item.status === 'ACTIVE' && item.requirements !== null))
const selectedJob = computed(() => jobs.value.find(item => item.id === selectedJobId.value) || null)
const canLinkDiagnosis = computed(() => !!currentDiagnosis.value && currentDiagnosis.value.status === 'READY' && currentDiagnosis.value.resumeVersionId === selectedResumeVersionId.value)
const taskRunning = computed(() => !!task.value && ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(task.value.status))
const optimizationDirty = computed(() => {
  if (!currentOptimization.value) return false
  return currentOptimization.value.sections.some((section, index) => (draftSections.value[index]?.content ?? section.content ?? section.suggestedText) !== (section.content ?? section.suggestedText))
})
const canEditOptimization = computed(() => !!currentOptimization.value && ['READY', 'ACCEPTED'].includes(currentOptimization.value.status))
const canExport = computed(() => currentOptimization.value?.status === 'ACCEPTED' && !optimizationDirty.value && !exporting.value)

const { task, deltaText, reasoningText, error: taskError, restart: restartTask } = useTask(activeTaskId, {
  onDone: async currentTask => {
    const id = currentTask.result?.resourceId || currentTask.resourceId
    if (!id) {
      taskAuthorityError.value = new Error('任务已完成，但未返回业务资源 ID')
      return
    }
    try {
      if (currentTask.type === 'RESUME_DIAGNOSE' || activeTaskKind.value === 'diagnosis') {
        const detail = await optimizationApi.getDiagnosis(id)
        await ensureResumeVersion(detail.resumeVersionId)
        applyDiagnosis(detail)
        await loadDiagnoses()
        ElMessage.success('简历诊断已完成')
      } else {
        await loadOptimization(id, { preserveDraft: true })
        await loadOptimizations()
        ElMessage.success('定向优化稿已生成')
      }
      taskAuthorityError.value = null
    } catch (reason) {
      taskAuthorityError.value = reason
    }
  },
})

function queryValue(value: unknown) {
  return typeof value === 'string' ? value : Array.isArray(value) && typeof value[0] === 'string' ? value[0] : ''
}

function statusLabel(status?: string) {
  return status ? commonStatusLabels[status] || status : '未知'
}

function statusTagType(status?: string): 'primary' | 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'READY' || status === 'ACCEPTED' || status === 'ACTIVE') return 'success'
  if (status === 'FAILED' || status === 'CLOSED') return 'danger'
  if (status === 'GENERATING' || status === 'ANALYZING') return 'warning'
  return 'info'
}

function priorityTagType(priority: string): 'primary' | 'success' | 'info' | 'warning' | 'danger' {
  if (priority === 'IMPORTANT') return 'danger'
  if (priority === 'SUGGESTION') return 'warning'
  return 'info'
}

function syncQuery(values: Record<string, string | undefined>) {
  const query = { ...route.query }
  Object.entries(values).forEach(([key, value]) => {
    if (value) query[key] = value
    else delete query[key]
  })
  void router.replace({ query })
}

async function listAllResumes() {
  const records: ResumeVO[] = []
  const size = 100
  let current = 1
  let total = 0
  do {
    const data = await resumeApi.list({ current, size })
    records.push(...data.records)
    total = data.total
    current += 1
    if (!data.records.length) break
  } while (records.length < total)
  return records
}

function upsertVersion(detail: ResumeVersionVO) {
  const index = resumeVersions.value.findIndex(item => item.id === detail.id)
  if (index >= 0) resumeVersions.value[index] = detail
  else resumeVersions.value.push(detail)
  addReference('resume-versions', detail.id, detail.resumeId)
}

async function loadResumeOptions() {
  resumeOptionsLoading.value = true
  resumeOptionsError.value = null
  resumeOptionsWarning.value = ''
  try {
    const records = await listAllResumes()
    resumeRecords.value = records
    const groups = await Promise.all(records.map(resume => resumeApi.versions(resume.id)))
    resumeVersions.value = groups.flat()
    resumeVersions.value.forEach(detail => addReference('resume-versions', detail.id, detail.resumeId))
  } catch (reason) {
    resumeOptionsError.value = reason
    resumeVersions.value = []
  } finally {
    resumeOptionsLoading.value = false
  }
}

async function ensureResumeVersion(versionId: string, select = true) {
  const detail = await resumeApi.getVersion(versionId)
  upsertVersion(detail)
  if (select) selectedResumeVersionId.value = versionId
  return detail
}

function versionLabel(version: ResumeVersionVO) {
  const resume = resumeRecords.value.find(item => item.id === version.resumeId)
  return `${resume?.title || version.fileName} · v${version.versionNo} · ${version.fileName}`
}

async function loadJobs() {
  jobsLoading.value = true
  jobsError.value = null
  jobsWarning.value = ''
  try {
    const [publicPage, personalPage] = await Promise.all([
      jobApi.list({ current: 1, size: 100 }),
      jobApi.imports({ current: 1, size: 100 }),
    ])
    const loaded = new Map<string, JobVO>()
    ;[...publicPage.records, ...personalPage.records].forEach(job => loaded.set(job.id, job))
    jobs.value = [...loaded.values()]
  } catch (reason) {
    jobsError.value = reason
    jobs.value = []
  } finally {
    jobsLoading.value = false
  }
}

async function ensureJob(jobId: string, select = true) {
  const detail = await jobApi.get(jobId)
  const index = jobs.value.findIndex(item => item.id === detail.id)
  if (index >= 0) jobs.value[index] = detail
  else jobs.value.push(detail)
  if (detail.source.type === 'USER_INPUT') addReference('personal-jobs', detail.id)
  if (select) selectedJobId.value = detail.id
  return detail
}

function jobLabel(job: JobVO) {
  return `${job.title} · ${job.companyName}`
}

async function bindTask(taskId: string, kind: 'diagnosis' | 'optimization', input: OptimizationInput) {
  activeTaskKind.value = kind
  activeTaskInput.value = { ...input }
  taskAuthorityError.value = null
  taskRetryError.value = null
  if (activeTaskId.value === taskId) await restartTask()
  else activeTaskId.value = taskId
}

async function submitDiagnosis(resumeVersionId: string) {
  const submitted = await optimizationApi.diagnose(resumeVersionId)
  await bindTask(submitted.id, 'diagnosis', { resumeVersionId })
  await loadDiagnoses()
}

async function startDiagnosis() {
  const versionId = selectedResumeVersionId.value
  if (!versionId || diagnoseSubmitting.value) return
  diagnoseSubmitting.value = true
  diagnoseError.value = null
  taskSubmitError.value = null
  try {
    const version = await ensureResumeVersion(versionId)
    if (version.status !== 'CONFIRMED') throw new Error('只有已确认的简历版本才能发起诊断')
    await submitDiagnosis(versionId)
  } catch (reason) {
    diagnoseError.value = reason
  } finally {
    diagnoseSubmitting.value = false
  }
}

async function submitOptimization(input: Required<Pick<OptimizationInput, 'resumeVersionId' | 'jobId'>> & { diagnosisId?: string }) {
  const submitted = await optimizationApi.create(input)
  await bindTask(submitted.id, 'optimization', input)
  await loadOptimizations()
}

async function startOptimization() {
  const resumeVersionId = selectedResumeVersionId.value
  const jobId = selectedJobId.value
  if (!resumeVersionId || !jobId || optimizeSubmitting.value) return
  optimizeSubmitting.value = true
  optimizeError.value = null
  taskSubmitError.value = null
  try {
    const [version, job] = await Promise.all([ensureResumeVersion(resumeVersionId), ensureJob(jobId)])
    if (version.status !== 'CONFIRMED') throw new Error('只有已确认的简历版本才能发起定向优化')
    if (job.status !== 'ACTIVE' || !job.requirements) throw new Error('请选择可见且已完成分析的岗位')
    const diagnosisId = linkDiagnosis.value && canLinkDiagnosis.value ? currentDiagnosis.value?.id : undefined
    await submitOptimization({ resumeVersionId, jobId, ...(diagnosisId ? { diagnosisId } : {}) })
  } catch (reason) {
    optimizeError.value = reason
  } finally {
    optimizeSubmitting.value = false
  }
}

async function retryFailedTask() {
  if (!task.value || !activeTaskKind.value || !activeTaskInput.value || taskActionLoading.value) return
  taskActionLoading.value = true
  taskRetryError.value = null
  try {
    const retried = await retryTrackedTask(task.value)
    await bindTask(retried.id, activeTaskKind.value, activeTaskInput.value)
    if (activeTaskKind.value === 'diagnosis') await loadDiagnoses()
    else await loadOptimizations()
  } catch (reason) {
    taskRetryError.value = reason
  } finally {
    taskActionLoading.value = false
  }
}

async function retryTaskAuthority() {
  const currentTask = task.value
  if (!currentTask || currentTask.status !== 'SUCCEEDED') return
  const id = currentTask.result?.resourceId || currentTask.resourceId
  if (!id) return
  taskAuthorityError.value = null
  try {
    if (currentTask.type === 'RESUME_DIAGNOSE' || activeTaskKind.value === 'diagnosis') await loadDiagnosis(id)
    else await loadOptimization(id, { preserveDraft: true })
    await refreshHistories()
  } catch (reason) {
    taskAuthorityError.value = reason
  }
}

async function loadDiagnoses() {
  diagnosisHistoryLoading.value = true
  diagnosisHistoryError.value = null
  try {
    const data = await optimizationApi.listDiagnoses({ resumeVersionId: selectedResumeVersionId.value || undefined, current: diagnosisPage.value, size: diagnosisSize.value })
    diagnoses.value = data.records
    diagnosisTotal.value = data.total
  } catch (reason) {
    diagnosisHistoryError.value = reason
  } finally {
    diagnosisHistoryLoading.value = false
  }
}

function applyDiagnosis(detail: ResumeDiagnosisVO) {
  currentDiagnosis.value = detail
  selectedDiagnosisId.value = detail.id
  linkDiagnosis.value = detail.status === 'READY'
  syncQuery({ diagnosisId: detail.id, resumeVersionId: detail.resumeVersionId })
}

async function loadDiagnosis(diagnosisId: string) {
  if (!diagnosisId) return
  diagnosisDetailLoading.value = true
  diagnosisDetailError.value = null
  try {
    const detail = await optimizationApi.getDiagnosis(diagnosisId)
    await ensureResumeVersion(detail.resumeVersionId)
    applyDiagnosis(detail)
  } catch (reason) {
    diagnosisDetailError.value = reason
  } finally {
    diagnosisDetailLoading.value = false
  }
}

async function loadOptimizations() {
  optimizationHistoryLoading.value = true
  optimizationHistoryError.value = null
  try {
    const data = await optimizationApi.list({ resumeVersionId: selectedResumeVersionId.value || undefined, jobId: selectedJobId.value || undefined, current: optimizationPage.value, size: optimizationSize.value })
    optimizations.value = data.records
    optimizationTotal.value = data.total
  } catch (reason) {
    optimizationHistoryError.value = reason
  } finally {
    optimizationHistoryLoading.value = false
  }
}

function applyOptimization(detail: ResumeOptimizationVO, preserveDraft = false) {
  const previous = draftSections.value
  currentOptimization.value = detail
  selectedOptimizationId.value = detail.id
  selectedResumeVersionId.value = detail.resumeVersionId
  selectedJobId.value = detail.jobId
  baselineAcceptedAt.value = detail.acceptedAt
  acceptError.value = null
  exportError.value = null
  draftSections.value = detail.sections.map((section, index) => {
    if (preserveDraft && previous[index]?.sectionKey === section.sectionKey) return { ...previous[index], title: section.title }
    const existing = preserveDraft ? previous.find(item => item.sectionKey === section.sectionKey) : null
    return { sectionKey: section.sectionKey, title: section.title, content: existing?.content ?? section.content ?? section.suggestedText }
  })
  syncQuery({ optimizationId: detail.id, resumeVersionId: detail.resumeVersionId, jobId: detail.jobId })
}

async function loadOptimization(optimizationId: string, options: { preserveDraft?: boolean; skipDirtyPrompt?: boolean } = {}) {
  if (!optimizationId) return
  if (!options.skipDirtyPrompt && !options.preserveDraft && currentOptimization.value && optimizationDirty.value) {
    const switching = currentOptimization.value.id !== optimizationId
    try {
      await ElMessageBox.confirm(switching ? '当前优化稿有尚未提交的最终文本，切换后将丢失这些编辑。' : '当前优化稿有尚未提交的最终文本，重新加载后将丢失这些编辑。', switching ? '确认切换优化稿' : '确认重新加载', { type: 'warning', confirmButtonText: switching ? '放弃编辑并切换' : '放弃编辑并加载', cancelButtonText: '保留当前编辑' })
    } catch {
      return
    }
  }
  optimizationDetailLoading.value = true
  optimizationDetailError.value = null
  try {
    const detail = await optimizationApi.get(optimizationId)
    await Promise.all([ensureResumeVersion(detail.resumeVersionId), ensureJob(detail.jobId)])
    const preserve = !!options.preserveDraft && currentOptimization.value?.id === detail.id && optimizationDirty.value
    applyOptimization(detail, preserve)
    if (preserve) ElMessage.info('已刷新服务器详情，当前最终文本编辑未被覆盖')
  } catch (reason) {
    optimizationDetailError.value = reason
  } finally {
    optimizationDetailLoading.value = false
  }
}

function acceptSection(index: number) {
  const section = currentOptimization.value?.sections[index]
  const draft = draftSections.value[index]
  if (section && draft && canEditOptimization.value) draft.content = section.suggestedText
}

function fillAllSuggestions() {
  if (!currentOptimization.value || !canEditOptimization.value) return
  draftSections.value = currentOptimization.value.sections.map(section => ({ sectionKey: section.sectionKey, title: section.title, content: section.suggestedText }))
  ElMessage.success('已将全部建议填入最终文本，请继续核对')
}

async function acceptAllAndSubmit() {
  if (!currentOptimization.value || !canEditOptimization.value) return
  fillAllSuggestions()
  await submitAcceptance()
}

async function submitAcceptance() {
  const optimization = currentOptimization.value
  if (!optimization || !canEditOptimization.value || accepting.value) return
  if (optimization.status === 'ACCEPTED' && !optimizationDirty.value) return
  try {
    await ElMessageBox.confirm('AI 建议不代表真实经历。请确认已逐段核对最终文本，且没有写入未经本人确认的事实。', '提交最终采纳文本', { type: 'warning', confirmButtonText: '已核对并提交', cancelButtonText: '继续编辑' })
  } catch {
    return
  }
  accepting.value = true
  acceptError.value = null
  conflictMessage.value = ''
  const submittedBase = baselineAcceptedAt.value
  const sections = optimization.sections.map((section, index) => ({
    sectionKey: section.sectionKey,
    title: section.title,
    content: draftSections.value[index]?.content ?? section.content ?? section.suggestedText,
  }))
  try {
    const accepted = await optimizationApi.accept(optimization.id, sections)
    applyOptimization(accepted)
    await loadOptimizations()
    ElMessage.success('完整优化稿已采纳，最终文本已保存')
  } catch (reason) {
    if (reason instanceof ApiError && reason.status === 409) {
      conflictMessage.value = `${errorMessage(reason)}。提交基于${submittedBase ? `服务器采纳时间 ${formatDate(submittedBase)}` : '尚未采纳的服务器稿'}；请刷新服务器稿后再核对，系统不会自动覆盖你的编辑。`
    } else acceptError.value = reason
  } finally {
    accepting.value = false
  }
}

async function refreshConflict(preserveDraft: boolean) {
  const optimization = currentOptimization.value
  if (!optimization || conflictLoading.value) return
  if (!preserveDraft) {
    try {
      await ElMessageBox.confirm('此操作会放弃当前最终文本编辑，并以服务器最新内容重新初始化。', '确认使用服务器版本', { type: 'warning', confirmButtonText: '放弃编辑并加载', cancelButtonText: '取消' })
    } catch {
      return
    }
  }
  conflictLoading.value = true
  acceptError.value = null
  try {
    const latest = await optimizationApi.get(optimization.id)
    applyOptimization(latest, preserveDraft)
    conflictMessage.value = ''
    ElMessage.success(preserveDraft ? '已刷新服务器稿，你的编辑已保留' : '已加载服务器最新优化稿')
  } catch (reason) {
    acceptError.value = reason
  } finally {
    conflictLoading.value = false
  }
}

async function refreshCurrentOptimization() {
  if (!currentOptimization.value) return
  if (optimizationDirty.value) await refreshConflict(true)
  else await loadOptimization(currentOptimization.value.id, { skipDirtyPrompt: true })
}

async function exportOptimization(format: 'PDF' | 'DOCX') {
  const optimization = currentOptimization.value
  if (!optimization || exporting.value) return
  exportError.value = null
  if (optimization.status !== 'ACCEPTED') {
    exportError.value = new Error('只有已采纳的优化稿才能导出')
    return
  }
  if (optimizationDirty.value) {
    exportError.value = new Error('当前最终文本有未提交编辑，请先提交采纳，再导出服务器保存的版本')
    return
  }
  exporting.value = format
  try {
    const binary = await optimizationApi.export(optimization.id, format)
    saveBinary(binary)
    ElMessage.success(`${format} 文件下载已开始`)
  } catch (reason) {
    exportError.value = reason
  } finally {
    exporting.value = ''
  }
}

/** E02：PDF 失败（多为服务器缺中文字体）时明确引导 DOCX 替代，不用笼统描述误导用户 */
const exportErrorHint = computed(() => {
  const message = exportError.value ? errorMessage(exportError.value) : ''
  if (message.includes('PDF')) {
    return '可直接点击上方「导出 DOCX」获取内容一致的文件；恢复服务器字体配置后 PDF 可再次导出，无需重新采纳。'
  }
  return '请确认优化稿已采纳且服务端文件生成可用后重试。'
})

async function refreshHistories() {
  await Promise.all([loadDiagnoses(), loadOptimizations()])
}

async function initialize() {
  await Promise.all([loadResumeOptions(), loadJobs()])
  const resumeVersionId = queryValue(route.query.resumeVersionId)
  const jobId = queryValue(route.query.jobId)
  if (resumeVersionId) {
    try { await ensureResumeVersion(resumeVersionId) } catch (reason) { diagnoseError.value = reason }
  }
  if (jobId) {
    try { await ensureJob(jobId) } catch (reason) { optimizeError.value = reason }
  }
  const diagnosisId = queryValue(route.query.diagnosisId)
  if (diagnosisId) await loadDiagnosis(diagnosisId)
  const optimizationId = queryValue(route.query.optimizationId)
  if (optimizationId) await loadOptimization(optimizationId, { skipDirtyPrompt: true })
  initialized.value = true
  await refreshHistories()
}

watch(selectedResumeVersionId, () => {
  if (!initialized.value) return
  diagnosisPage.value = 1
  optimizationPage.value = 1
  syncQuery({ resumeVersionId: selectedResumeVersionId.value || undefined })
  void refreshHistories()
})

watch(selectedJobId, () => {
  if (!initialized.value) return
  optimizationPage.value = 1
  syncQuery({ jobId: selectedJobId.value || undefined })
  void loadOptimizations()
})

watch(() => route.query.diagnosisId, value => {
  const id = queryValue(value)
  if (initialized.value && id && id !== selectedDiagnosisId.value) void loadDiagnosis(id)
})

watch(() => route.query.optimizationId, value => {
  const id = queryValue(value)
  if (initialized.value && id && id !== selectedOptimizationId.value) void loadOptimization(id)
})

watch(() => route.query.resumeVersionId, value => {
  const id = queryValue(value)
  if (initialized.value && id && id !== selectedResumeVersionId.value) void ensureResumeVersion(id).catch(reason => { diagnoseError.value = reason })
})

watch(() => route.query.jobId, value => {
  const id = queryValue(value)
  if (initialized.value && id && id !== selectedJobId.value) void ensureJob(id).catch(reason => { optimizeError.value = reason })
})

onMounted(() => {
  void initialize()
})
</script>

<template>
  <div class="page optimization-page">
    <header class="page-header">
      <div>
        <h1>智能简历优化</h1>
        <p>基于已确认事实进行诊断和岗位定向优化，逐段核对后再采纳与导出。</p>
      </div>
      <router-link to="/resume"><el-button>管理简历版本</el-button></router-link>
    </header>

    <el-alert class="truth-alert" type="warning" title="AI 建议不是事实，所有新增或改写内容都必须由本人核对；不得把未经历的项目、雇主、技能或指标写入最终简历。" :closable="false" show-icon />

    <div class="setup-grid">
      <section class="panel">
        <div class="panel__heading"><h2>1. 选择已确认版本并诊断</h2><el-button text type="primary" @click="loadResumeOptions">刷新版本</el-button></div>
        <AsyncState :loading="resumeOptionsLoading" :error="resumeOptionsError" :empty="!confirmedVersions.length" empty-text="暂无已确认简历版本" empty-description="请先在简历中心上传、解析并确认事实。" @retry="loadResumeOptions">
          <template #empty><router-link to="/resume"><el-button type="primary">前往简历中心</el-button></router-link></template>
          <el-select v-model="selectedResumeVersionId" class="full-width" filterable placeholder="选择已确认简历版本">
            <el-option v-for="version in confirmedVersions" :key="version.id" :value="version.id" :label="versionLabel(version)" />
          </el-select>
          <div v-if="selectedResumeVersion" class="selected-meta">
            <el-tag type="success">{{ statusLabel(selectedResumeVersion.status) }}</el-tag>
            <span>上传于 {{ formatDate(selectedResumeVersion.createdAt) }}</span>
          </div>
          <el-alert v-if="resumeOptionsWarning" class="inline-alert" type="warning" :title="resumeOptionsWarning" :closable="false" show-icon />
          <el-alert v-if="diagnoseError" class="inline-alert" type="error" :title="errorMessage(diagnoseError)" :closable="false" show-icon />
          <el-button class="setup-action" type="primary" :disabled="selectedResumeVersion?.status !== 'CONFIRMED' || taskRunning" :loading="diagnoseSubmitting" @click="startDiagnosis">发起简历诊断</el-button>
        </AsyncState>
      </section>

      <section class="panel">
        <div class="panel__heading"><h2>2. 选择已分析岗位并优化</h2><el-button text type="primary" @click="loadJobs">刷新岗位</el-button></div>
        <AsyncState :loading="jobsLoading" :error="jobsError" :empty="!analyzedJobs.length" empty-text="暂无已分析岗位" empty-description="请先导入或选择已完成 JD 分析的岗位。" @retry="loadJobs">
          <template #empty><router-link to="/jobs?tab=trusted"><el-button type="primary">前往岗位推荐</el-button></router-link></template>
          <el-select v-model="selectedJobId" class="full-width" filterable placeholder="选择目标岗位">
            <el-option v-for="job in analyzedJobs" :key="job.id" :value="job.id" :label="jobLabel(job)" />
          </el-select>
          <div v-if="selectedJob" class="selected-meta">
            <el-tag :type="statusTagType(selectedJob.status)">{{ statusLabel(selectedJob.status) }}</el-tag>
            <span>岗位版本 {{ selectedJob.jobVersion }} · {{ selectedJob.requirements ? '已完成分析' : '未完成分析' }}</span>
          </div>
          <el-alert v-if="jobsWarning" class="inline-alert" type="warning" :title="jobsWarning" :closable="false" show-icon />
          <el-checkbox class="diagnosis-link" v-model="linkDiagnosis" :disabled="!canLinkDiagnosis">关联当前诊断{{ currentDiagnosis && canLinkDiagnosis ? `（${currentDiagnosis.id}）` : '' }}</el-checkbox>
          <el-alert v-if="optimizeError" class="inline-alert" type="error" :title="errorMessage(optimizeError)" :closable="false" show-icon />
          <el-button class="setup-action" type="primary" :disabled="selectedResumeVersion?.status !== 'CONFIRMED' || !selectedJob || selectedJob.status !== 'ACTIVE' || !selectedJob.requirements || taskRunning" :loading="optimizeSubmitting" @click="startOptimization">发起定向优化</el-button>
        </AsyncState>
      </section>
    </div>

    <section v-if="task || taskError || taskAuthorityError || taskRetryError" class="panel">
      <div class="panel__heading"><h2>当前 AI 任务</h2><el-tag v-if="taskRunning" type="warning">任务处理中</el-tag></div>
      <TaskStatus :task="task" :reasoning="reasoningText">
        <template #actions>
          <el-button v-if="task?.status === 'FAILED'" type="primary" :loading="taskActionLoading" @click="retryFailedTask">按原参数重试任务</el-button>
        </template>
      </TaskStatus>
      <pre v-if="deltaText" class="task-delta pre-wrap">{{ deltaText }}</pre>
      <el-alert v-if="taskRetryError" class="inline-alert" type="error" :title="`重试失败：${errorMessage(taskRetryError)}`" description="可使用上方业务按钮按当前选择重新提交。" :closable="false" show-icon />
      <el-alert v-if="taskAuthorityError" class="inline-alert" type="error" :title="`任务完成，但业务详情加载失败：${errorMessage(taskAuthorityError)}`" :closable="false" show-icon>
        <template #default><el-button text type="primary" @click="retryTaskAuthority">重新加载权威详情</el-button></template>
      </el-alert>
      <el-alert v-if="taskError" class="inline-alert" type="warning" :title="`任务状态连接异常：${errorMessage(taskError)}`" description="后端任务可能仍在执行，可重新连接并恢复轮询。" :closable="false" show-icon>
        <template #default><el-button text type="primary" @click="restartTask">重新连接</el-button></template>
      </el-alert>
    </section>

    <div class="history-grid">
      <section class="panel">
        <div class="panel__heading"><h2>历史诊断</h2><el-button text type="primary" :loading="diagnosisHistoryLoading" @click="loadDiagnoses">刷新</el-button></div>
        <AsyncState :loading="diagnosisHistoryLoading" :error="diagnosisHistoryError" :empty="!diagnoses.length" empty-text="暂无历史诊断" @retry="loadDiagnoses">
          <div class="history-list">
            <button v-for="item in diagnoses" :key="item.id" type="button" class="history-item" :class="{ 'history-item--active': item.id === selectedDiagnosisId }" @click="loadDiagnosis(item.id)">
              <span><strong>{{ diagnosisTitle(item.resumeVersionId) }}</strong><el-tag size="small" :type="statusTagType(item.status)">{{ statusLabel(item.status) }}</el-tag></span>
              <small>{{ formatDate(item.createdAt) }}</small>
            </button>
          </div>
          <el-pagination v-if="diagnosisTotal > diagnosisSize" class="pagination" layout="prev, pager, next" small :total="diagnosisTotal" :current-page="diagnosisPage" :page-size="diagnosisSize" @current-change="diagnosisPage = $event; loadDiagnoses()" />
        </AsyncState>
      </section>

      <section class="panel">
        <div class="panel__heading"><h2>历史优化稿</h2><el-button text type="primary" :loading="optimizationHistoryLoading" @click="loadOptimizations">刷新</el-button></div>
        <AsyncState :loading="optimizationHistoryLoading" :error="optimizationHistoryError" :empty="!optimizations.length" empty-text="暂无历史优化稿" @retry="loadOptimizations">
          <div class="history-list">
            <button v-for="item in optimizations" :key="item.id" type="button" class="history-item" :class="{ 'history-item--active': item.id === selectedOptimizationId }" @click="loadOptimization(item.id)">
              <span><strong>{{ item.title }}</strong><el-tag size="small" :type="statusTagType(item.status)">{{ statusLabel(item.status) }}</el-tag></span>
              <small>{{ formatDate(item.createdAt) }}</small>
            </button>
          </div>
          <el-pagination v-if="optimizationTotal > optimizationSize" class="pagination" layout="prev, pager, next" small :total="optimizationTotal" :current-page="optimizationPage" :page-size="optimizationSize" @current-change="optimizationPage = $event; loadOptimizations()" />
        </AsyncState>
      </section>
    </div>

    <section class="panel">
      <div class="panel__heading">
        <h2>诊断详情</h2>
        <el-button v-if="currentDiagnosis" text type="primary" :loading="diagnosisDetailLoading" @click="loadDiagnosis(currentDiagnosis.id)">刷新详情</el-button>
      </div>
      <AsyncState :loading="diagnosisDetailLoading" :error="diagnosisDetailError" :empty="!currentDiagnosis" empty-text="选择历史诊断或发起新诊断" @retry="loadDiagnosis(selectedDiagnosisId)">
        <template v-if="currentDiagnosis">
          <div class="detail-summary">
            <div><strong>{{ diagnosisTitle(currentDiagnosis.resumeVersionId) }}</strong><p class="muted">{{ formatDate(currentDiagnosis.createdAt) }}</p><details><summary>诊断记录编号</summary>{{ currentDiagnosis.id }} · 简历版本 {{ currentDiagnosis.resumeVersionId }}</details></div>
            <el-tag :type="statusTagType(currentDiagnosis.status)">{{ statusLabel(currentDiagnosis.status) }}</el-tag>
          </div>
          <div v-if="currentDiagnosis.items.length" class="diagnosis-items">
            <article v-for="item in currentDiagnosis.items" :key="item.id" class="diagnosis-card">
              <div class="diagnosis-card__heading"><el-tag :type="priorityTagType(item.priority)">{{ priorityLabels[item.priority] || item.priority }}</el-tag><h3>{{ item.title }}</h3></div>
              <div class="diagnosis-field"><strong>问题</strong><p>{{ item.issue }}</p></div>
              <blockquote><strong>原文 quote</strong><p class="pre-wrap">{{ item.quote || '未返回原文引用' }}</p></blockquote>
              <div class="diagnosis-field"><strong>原因</strong><p class="pre-wrap">{{ item.rationale }}</p></div>
              <div class="diagnosis-field suggestion"><strong>建议（待本人核对）</strong><p class="pre-wrap">{{ item.suggestion }}</p></div>
            </article>
          </div>
          <el-empty v-else :description="currentDiagnosis.status === 'GENERATING' ? '诊断正在生成，请稍后刷新' : currentDiagnosis.status === 'FAILED' ? '诊断生成失败，请按原版本重新发起' : '诊断未返回问题项'" />
        </template>
      </AsyncState>
    </section>

    <section class="panel optimization-detail">
      <div class="panel__heading">
        <div><h2>优化稿核对与预览</h2><p v-if="currentOptimization" class="muted optimization-subtitle">{{ currentOptimization.title }} · {{ formatDate(currentOptimization.createdAt) }}</p><details v-if="currentOptimization"><summary>优化稿记录编号</summary>{{ currentOptimization.id }}</details></div>
        <div class="actions">
          <el-button v-if="currentOptimization" :loading="optimizationDetailLoading" @click="refreshCurrentOptimization">刷新详情</el-button>
          <el-button :disabled="!canExport" :loading="exporting === 'PDF'" @click="exportOptimization('PDF')">导出 PDF</el-button>
          <el-button :disabled="!canExport" :loading="exporting === 'DOCX'" @click="exportOptimization('DOCX')">导出 DOCX</el-button>
        </div>
      </div>
      <el-alert v-if="currentOptimization?.status === 'ACCEPTED' && optimizationDirty" class="inline-alert" type="warning" title="当前编辑尚未提交，导出按钮已禁用；请先采纳保存最终文本。" :closable="false" show-icon />
      <el-alert v-if="exportError" class="inline-alert" type="error" :title="`导出失败：${errorMessage(exportError)}`" :description="exportErrorHint" :closable="false" show-icon />
      <AsyncState :loading="optimizationDetailLoading" :error="optimizationDetailError" :empty="!currentOptimization" empty-text="选择历史优化稿或发起定向优化" @retry="loadOptimization(selectedOptimizationId)">
        <template v-if="currentOptimization">
          <div class="detail-summary">
            <div>
              <el-tag :type="statusTagType(currentOptimization.status)">{{ statusLabel(currentOptimization.status) }}</el-tag>
              <span class="muted detail-dates">创建于 {{ formatDate(currentOptimization.createdAt) }}<template v-if="currentOptimization.acceptedAt"> · 采纳于 {{ formatDate(currentOptimization.acceptedAt) }}</template></span>
            </div>
            <span class="muted">岗位：{{ selectedJob ? jobLabel(selectedJob) : currentOptimization.jobId }}</span>
          </div>
          <el-alert v-if="currentOptimization.status === 'GENERATING'" class="inline-alert" type="warning" title="优化稿正在生成，任务完成后会加载权威详情。" :closable="false" show-icon />
          <el-alert v-if="currentOptimization.status === 'FAILED'" class="inline-alert" type="error" title="优化稿生成失败，请使用原简历版本和岗位重新发起。" :closable="false" show-icon />
          <el-alert v-if="conflictMessage" class="inline-alert" type="error" title="优化稿版本冲突" :description="conflictMessage" :closable="false" show-icon>
            <template #default>
              <div class="actions conflict-actions">
                <el-button type="primary" :loading="conflictLoading" @click="refreshConflict(true)">刷新并保留我的编辑</el-button>
                <el-button :disabled="conflictLoading" @click="refreshConflict(false)">放弃编辑并加载服务器稿</el-button>
              </div>
            </template>
          </el-alert>
          <el-alert v-if="acceptError" class="inline-alert" type="error" :title="`采纳失败：${errorMessage(acceptError)}`" :closable="false" show-icon />

          <template v-if="currentOptimization.sections.length">
            <div class="review-toolbar">
              <el-alert title="左侧为原文，右侧为 AI 建议；最终文本必须由本人核对。接受单段只会复制建议，提交采纳后才保存。" type="info" :closable="false" show-icon />
              <div class="actions" v-if="canEditOptimization">
                <el-button @click="fillAllSuggestions">将全部建议填入最终文本</el-button>
                <el-button type="primary" :loading="accepting" @click="acceptAllAndSubmit">全部采纳并提交</el-button>
                <el-button type="success" :disabled="currentOptimization.status === 'ACCEPTED' && !optimizationDirty" :loading="accepting" @click="submitAcceptance">提交最终文本</el-button>
              </div>
            </div>
            <el-tabs type="border-card" class="review-tabs">
              <el-tab-pane label="逐段对比与编辑">
                <div class="section-list">
                  <article v-for="(section, index) in currentOptimization.sections" :key="section.sectionKey" class="section-card">
                    <div class="section-card__heading"><h3>{{ section.title }}</h3><el-button v-if="canEditOptimization" size="small" @click="acceptSection(index)">接受此段建议</el-button></div>
                    <div class="section-compare">
                      <div class="text-column original"><h4>原文 originalText</h4><p class="pre-wrap">{{ section.originalText || '无原文' }}</p></div>
                      <div class="text-column suggested"><h4>AI 建议 suggestedText · 非事实，待核对</h4><p class="pre-wrap">{{ section.suggestedText || '无建议文本' }}</p></div>
                    </div>
                    <div class="source-quotes">
                      <strong>sourceQuotes 原文依据</strong>
                      <blockquote v-for="quote in section.sourceQuotes" :key="quote" class="pre-wrap">{{ quote }}</blockquote>
                      <p v-if="!section.sourceQuotes.length" class="muted">未返回原文引用，请谨慎核对。</p>
                    </div>
                    <label class="final-editor">
                      <span>最终文本 content</span>
                      <el-input v-if="draftSections[index]" v-model="draftSections[index].content" type="textarea" :rows="7" maxlength="10000" show-word-limit resize="vertical" :disabled="!canEditOptimization" placeholder="请输入本人核对后的最终文本" />
                    </label>
                  </article>
                </div>
              </el-tab-pane>
              <el-tab-pane label="A4 样式预览">
                <div class="preview-stage">
                  <div class="a4-page">
                    <header><h2>{{ currentOptimization.title }}</h2><p>最终文本预览 · {{ statusLabel(currentOptimization.status) }}</p></header>
                    <section v-for="(section, index) in currentOptimization.sections" :key="section.sectionKey">
                      <h3>{{ section.title }}</h3>
                      <p class="pre-wrap">{{ draftSections[index]?.content ?? section.content ?? section.suggestedText }}</p>
                    </section>
                  </div>
                </div>
              </el-tab-pane>
            </el-tabs>
          </template>
          <el-empty v-else-if="!['GENERATING', 'FAILED'].includes(currentOptimization.status)" description="优化稿没有段落内容" />
        </template>
      </AsyncState>
    </section>
  </div>
</template>

<style scoped lang="scss">
.optimization-page { padding-bottom: 28px; }
.truth-alert { margin-bottom: 14px; }
.setup-grid, .history-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.selected-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-top: 12px; color: $ai-text-secondary; font-size: 12px; }
.setup-action { width: 100%; margin-top: 14px; }
.diagnosis-link { margin-top: 14px; white-space: normal; }
.inline-alert { margin-top: 14px; }
.task-delta { max-height: 180px; margin: 12px 0; padding: 12px; overflow: auto; border-radius: 8px; background: #f5f8fc; }
.history-list { display: grid; gap: 9px; }
.history-item { display: grid; gap: 7px; width: 100%; padding: 12px; border: 1px solid #e4ecf7; border-radius: 8px; background: #fff; color: inherit; text-align: left; cursor: pointer; }
.history-item:hover { border-color: #9dbff5; }
.history-item--active { border-color: #2d72ec; background: #f2f7ff; }
.history-item > span { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.history-item strong { min-width: 0; overflow-wrap: anywhere; }
.history-item small { color: $ai-text-tertiary; overflow-wrap: anywhere; }
.detail-summary { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 16px; }
.detail-summary strong { overflow-wrap: anywhere; }
.detail-summary p { margin: 6px 0 0; }
.detail-dates { margin-left: 10px; }
.diagnosis-items { display: grid; gap: 14px; }
.diagnosis-card { padding: 16px; border: 1px solid #e5edf8; border-radius: 10px; background: #fbfdff; }
.diagnosis-card__heading { display: flex; align-items: center; gap: 10px; }
.diagnosis-card__heading h3 { margin: 0; color: #152653; }
.diagnosis-field { margin-top: 13px; }
.diagnosis-field strong, .source-quotes > strong { color: #4d6185; font-size: 13px; }
.diagnosis-field p { margin: 5px 0 0; line-height: 1.7; white-space: pre-wrap; }
.diagnosis-card blockquote, .source-quotes blockquote { margin: 13px 0 0; padding: 11px 13px; border-left: 3px solid #8ca9d4; background: #f1f5fb; color: #4c5e7e; }
.diagnosis-card blockquote p { margin: 6px 0 0; }
.diagnosis-field.suggestion { padding: 12px; border-radius: 8px; background: #fff8e9; }
.optimization-subtitle { margin: 5px 0 0; font-size: 12px; overflow-wrap: anywhere; }
.conflict-actions { margin-top: 12px; }
.review-toolbar { display: grid; gap: 12px; margin-bottom: 14px; }
.review-toolbar > .actions { justify-content: flex-end; }
.review-tabs { margin-top: 14px; }
.section-list { display: grid; gap: 16px; }
.section-card { padding: 16px; border: 1px solid #e3ebf6; border-radius: 10px; }
.section-card__heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 13px; }
.section-card__heading h3 { margin: 0; color: #13244f; font-size: 17px; }
.section-compare { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.text-column { min-width: 0; padding: 14px; border-radius: 8px; }
.text-column h4 { margin: 0 0 10px; font-size: 13px; }
.text-column p { margin: 0; min-height: 80px; }
.text-column.original { background: #f5f7fa; }
.text-column.suggested { background: #fff7e8; }
.text-column.suggested h4 { color: #936411; }
.source-quotes { margin-top: 13px; }
.source-quotes p { margin: 8px 0 0; }
.final-editor { display: grid; gap: 8px; margin-top: 15px; color: #253c68; font-weight: 600; }
.preview-stage { padding: 24px; overflow: auto; background: #e9edf3; }
.a4-page { width: 794px; max-width: 100%; min-height: 1123px; margin: 0 auto; padding: 64px 68px; box-sizing: border-box; background: #fff; box-shadow: 0 5px 24px rgb(29 46 78 / 16%); color: #222; font-family: 'Times New Roman', 'SimSun', serif; }
.a4-page header { margin-bottom: 28px; padding-bottom: 18px; border-bottom: 2px solid #26364d; text-align: center; }
.a4-page header h2 { margin: 0; font-size: 25px; }
.a4-page header p { margin: 8px 0 0; color: #6c7280; font-size: 12px; }
.a4-page section { margin-top: 22px; }
.a4-page section h3 { margin: 0 0 10px; padding-bottom: 6px; border-bottom: 1px solid #ccd1d8; font-size: 17px; }
.a4-page section p { margin: 0; font-size: 13px; line-height: 1.85; }
@media (max-width: 1000px) { .setup-grid, .history-grid { grid-template-columns: 1fr; } }
@media (max-width: 700px) { .section-compare { grid-template-columns: 1fr; } .detail-summary, .section-card__heading { flex-direction: column; } .review-toolbar > .actions { align-items: stretch; flex-direction: column; } .review-toolbar .actions .el-button { width: 100%; margin: 0; } .optimization-detail .panel__heading { align-items: stretch; flex-direction: column; } .optimization-detail .panel__heading .actions .el-button { flex: 1; margin: 0; } .preview-stage { padding: 10px; } .a4-page { min-height: 900px; padding: 36px 28px; } }
</style>
