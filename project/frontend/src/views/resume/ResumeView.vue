<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type UploadFile, type UploadUserFile } from 'element-plus'
import { resumeApi } from '@/api/resumes'
import { profileApi } from '@/api/profile'
import { saveBinary } from '@/api/request'
import type { ProfileVO, ResumeFacts, ResumeVO, ResumeVersionVO, SkillVO } from '@/api/types'
import { useTask } from '@/composables/useTask'
import AsyncState from '@/components/AsyncState.vue'
import TaskStatus from '@/components/TaskStatus.vue'
import { addReference } from '@/utils/references'
import { cloneFacts, commonStatusLabels, educationLabels, emptyFacts, errorMessage, formatDate, formatFileSize } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const resumes = ref<ResumeVO[]>([])
const listPage = ref(1)
const listSize = ref(5)
const listTotal = ref(0)
const listLoading = ref(false)
const listError = ref<unknown>(null)
const selectedResumeId = ref('')
const versions = ref<ResumeVersionVO[]>([])
const versionsLoading = ref(false)
const versionsError = ref<unknown>(null)
const versionsWarning = ref('')
const selectedVersionId = ref('')
const selectedVersion = ref<ResumeVersionVO | null>(null)
const detailLoading = ref(false)
const detailError = ref<unknown>(null)
const facts = ref<ResumeFacts>(emptyFacts())
const skillCatalog = ref<SkillVO[]>([])
const skillsLoading = ref(false)
const skillsError = ref<unknown>(null)
const profileResult = ref<ProfileVO | null>(null)
const confirming = ref(false)
const confirmError = ref<unknown>(null)
const operationError = ref<unknown>(null)
const downloadError = ref<unknown>(null)
const downloading = ref(false)
const deletingId = ref('')
const uploadVisible = ref(false)
const uploadMode = ref<'new' | 'append'>('new')
const uploadTitle = ref('')
const uploadFile = ref<File | null>(null)
const uploadFiles = ref<UploadUserFile[]>([])
const uploadError = ref('')
const uploading = ref(false)
const activeTaskId = ref('')
const taskVersionId = ref('')
const parseSubmitting = ref(false)
const parseSubmissionError = ref<unknown>(null)
const taskAuthorityError = ref<unknown>(null)
const initialized = ref(false)

const selectedResume = computed(() => resumes.value.find(item => item.id === selectedResumeId.value) || null)
const currentTitle = computed(() => selectedResume.value?.title || selectedVersion.value?.fileName || '简历详情')
const canEditFacts = computed(() => selectedVersion.value?.status === 'PARSED')
const taskRunning = computed(() => !!task.value && ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(task.value.status))
const educationOptions = Object.entries(educationLabels).map(([value, label]) => ({ value, label }))
const maxGraduationYear = new Date().getFullYear() + 10

const { task, deltaText, reasoningText, error: taskError, restart: restartTask } = useTask(activeTaskId, {
  onTerminal: async currentTask => {
    const id = currentTask.result?.resourceId || currentTask.resourceId
    if (!id) {
      taskAuthorityError.value = new Error('任务已完成，但未返回简历版本 ID')
      return
    }
    taskVersionId.value = id
    await refreshParsedVersion(id)
  },
})

function queryValue(value: unknown) {
  return typeof value === 'string' ? value : Array.isArray(value) && typeof value[0] === 'string' ? value[0] : ''
}

function statusLabel(status?: string) {
  return status ? commonStatusLabels[status] || status : '未知'
}

function statusTagType(status?: string): 'primary' | 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'CONFIRMED') return 'success'
  if (status === 'PARSED') return 'primary'
  if (status === 'FAILED' || status === 'DELETED') return 'danger'
  if (status === 'PARSING') return 'warning'
  return 'info'
}

async function loadResumes(selectFirst = false) {
  listLoading.value = true
  listError.value = null
  try {
    const data = await resumeApi.list({ current: listPage.value, size: listSize.value })
    resumes.value = data.records
    listTotal.value = data.total
    if (selectFirst && data.records.length) await selectResume(data.records[0])
  } catch (reason) {
    listError.value = reason
  } finally {
    listLoading.value = false
  }
}

async function findResume(resumeId: string) {
  const local = resumes.value.find(item => item.id === resumeId)
  if (local) return local
  const size = 100
  let current = 1
  let total = 0
  do {
    const data = await resumeApi.list({ current, size })
    const match = data.records.find(item => item.id === resumeId)
    if (match) return match
    total = data.total
    current += 1
    if (!data.records.length) break
  } while ((current - 1) * size < total)
  return null
}

function applyVersion(detail: ResumeVersionVO) {
  const changed = selectedVersionId.value !== detail.id
  selectedVersionId.value = detail.id
  selectedResumeId.value = detail.resumeId
  selectedVersion.value = detail
  facts.value = cloneFacts(detail.facts)
  confirmError.value = null
  downloadError.value = null
  operationError.value = null
  if (changed) profileResult.value = null
  const index = versions.value.findIndex(item => item.id === detail.id)
  if (index >= 0) versions.value[index] = detail
  else versions.value.push(detail)
  versions.value.sort((a, b) => b.versionNo - a.versionNo)
  addReference('resume-versions', detail.id, detail.resumeId)
  if (initialized.value && queryValue(route.query.versionId) !== detail.id) {
    void router.replace({ query: { ...route.query, versionId: detail.id } })
  }
}

async function loadVersion(versionId: string) {
  if (!versionId) return
  detailLoading.value = true
  detailError.value = null
  try {
    const detail = await resumeApi.getVersion(versionId)
    applyVersion(detail)
  } catch (reason) {
    detailError.value = reason
  } finally {
    detailLoading.value = false
  }
}

async function loadVersions(resume: ResumeVO, preferredVersionId?: string) {
  selectedResumeId.value = resume.id
  versionsLoading.value = true
  versionsError.value = null
  versionsWarning.value = ''
  detailError.value = null
  try {
    const loaded = await resumeApi.versions(resume.id)
    versions.value = loaded.sort((a, b) => b.versionNo - a.versionNo)
    loaded.forEach(detail => addReference('resume-versions', detail.id, resume.id))
    const target = versions.value.find(item => item.id === preferredVersionId) || versions.value.find(item => item.id === resume.latestVersion.id) || versions.value[0]
    applyVersion(target)
  } catch (reason) {
    versions.value = []
    selectedVersion.value = null
    selectedVersionId.value = ''
    versionsError.value = reason
  } finally {
    versionsLoading.value = false
  }
}

async function selectResume(resume: ResumeVO, preferredVersionId?: string) {
  if (selectedResumeId.value !== resume.id) {
    selectedVersion.value = null
    selectedVersionId.value = ''
    profileResult.value = null
  }
  await loadVersions(resume, preferredVersionId)
}

async function restoreVersion(versionId: string) {
  detailLoading.value = true
  detailError.value = null
  try {
    const detail = await resumeApi.getVersion(versionId)
    addReference('resume-versions', detail.id, detail.resumeId)
    const resume = await findResume(detail.resumeId)
    if (resume) await selectResume(resume, detail.id)
    else {
      selectedResumeId.value = detail.resumeId
      versions.value = [detail]
      applyVersion(detail)
    }
  } catch (reason) {
    detailError.value = reason
  } finally {
    detailLoading.value = false
  }
}

async function retryVersions() {
  if (selectedResume.value) await loadVersions(selectedResume.value, selectedVersionId.value)
  else if (selectedVersionId.value) await loadVersion(selectedVersionId.value)
}

async function changePage(page: number) {
  listPage.value = page
  await loadResumes()
  if (resumes.value.length) await selectResume(resumes.value[0])
}

async function changePageSize(size: number) {
  listSize.value = size
  listPage.value = 1
  await loadResumes()
  if (resumes.value.length) await selectResume(resumes.value[0])
}

async function refreshParsedVersion(versionId: string) {
  taskAuthorityError.value = null
  try {
    const detail = await resumeApi.getVersion(versionId)
    addReference('resume-versions', detail.id, detail.resumeId)
    await loadResumes()
    const resume = resumes.value.find(item => item.id === detail.resumeId) || await findResume(detail.resumeId)
    if (resume) await selectResume(resume, detail.id)
    else applyVersion(detail)
    ElMessage.success('简历解析完成，已加载权威版本详情')
  } catch (reason) {
    taskAuthorityError.value = reason
  }
}

async function startParse(versionId: string) {
  if (!versionId || parseSubmitting.value) return
  parseSubmitting.value = true
  parseSubmissionError.value = null
  taskAuthorityError.value = null
  taskVersionId.value = versionId
  try {
    const submitted = await resumeApi.parse(versionId)
    if (activeTaskId.value === submitted.id) await restartTask()
    else activeTaskId.value = submitted.id
  } catch (reason) {
    parseSubmissionError.value = reason
  } finally {
    parseSubmitting.value = false
  }
}

function retryTaskAuthority() {
  if (taskVersionId.value) void refreshParsedVersion(taskVersionId.value)
}

function validateFile(file: File) {
  const extension = file.name.toLowerCase().split('.').pop() || ''
  const mime = file.type.toLowerCase()
  if (!['pdf', 'docx'].includes(extension)) return '仅支持扩展名为 .pdf 或 .docx 的文件'
  if (extension === 'pdf' && mime && !['application/pdf', 'application/x-pdf', 'application/octet-stream'].includes(mime)) return 'PDF 文件的 MIME 类型不正确'
  if (extension === 'docx' && mime && !['application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'application/octet-stream', 'application/zip'].includes(mime)) return 'DOCX 文件的 MIME 类型不正确'
  if (file.size <= 0) return '文件内容为空，请重新选择'
  if (file.size > 10 * 1024 * 1024) return '文件不能超过 10 MiB'
  return ''
}

function handleFileChange(file: UploadFile) {
  uploadError.value = ''
  uploadFile.value = null
  if (!file.raw) {
    uploadError.value = '无法读取文件，请重新选择'
    uploadFiles.value = []
    return
  }
  const message = validateFile(file.raw)
  if (message) {
    uploadError.value = message
    uploadFiles.value = []
    return
  }
  uploadFile.value = file.raw
}

function removeUploadFile() {
  uploadFile.value = null
  uploadError.value = ''
}

function openUpload(mode: 'new' | 'append') {
  uploadMode.value = mode === 'append' && !selectedResumeId.value ? 'new' : mode
  uploadTitle.value = ''
  uploadFile.value = null
  uploadFiles.value = []
  uploadError.value = ''
  uploadVisible.value = true
}

function resetUpload() {
  uploadFile.value = null
  uploadFiles.value = []
  uploadError.value = ''
  uploadTitle.value = ''
}

async function submitUpload() {
  const file = uploadFile.value
  if (!file) {
    uploadError.value = '请选择 PDF 或 DOCX 文件'
    return
  }
  const validation = validateFile(file)
  if (validation) {
    uploadError.value = validation
    return
  }
  uploading.value = true
  uploadError.value = ''
  operationError.value = null
  let uploaded: ResumeVO
  try {
    uploaded = await resumeApi.upload(file, uploadMode.value === 'new' ? uploadTitle.value.trim() || undefined : undefined, uploadMode.value === 'append' ? selectedResumeId.value : undefined)
  } catch (reason) {
    uploadError.value = errorMessage(reason)
    uploading.value = false
    return
  }
  uploading.value = false
  uploadVisible.value = false
  addReference('resume-versions', uploaded.latestVersion.id, uploaded.id)
  ElMessage.success('上传成功，正在提交解析任务')
  try {
    await loadResumes()
    await selectResume(uploaded, uploaded.latestVersion.id)
  } catch (reason) {
    operationError.value = reason
  }
  await startParse(uploaded.latestVersion.id)
}

async function downloadCurrent() {
  const version = selectedVersion.value
  if (!version || downloading.value) return
  downloading.value = true
  downloadError.value = null
  try {
    const binary = await resumeApi.download(version.id, version.fileName)
    saveBinary(binary)
    ElMessage.success('文件下载已开始')
  } catch (reason) {
    downloadError.value = reason
  } finally {
    downloading.value = false
  }
}

async function removeResume(resume: ResumeVO) {
  if (deletingId.value) return
  try {
    await ElMessageBox.confirm(`删除“${resume.title}”后，其全部版本将不可继续使用，相关画像事实也会按业务规则处理。`, '确认删除简历', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  deletingId.value = resume.id
  operationError.value = null
  try {
    await resumeApi.remove(resume.id)
    if (selectedResumeId.value === resume.id) {
      selectedResumeId.value = ''
      selectedVersionId.value = ''
      selectedVersion.value = null
      versions.value = []
      facts.value = emptyFacts()
      profileResult.value = null
      activeTaskId.value = ''
    }
    if (resumes.value.length === 1 && listPage.value > 1) listPage.value -= 1
    await loadResumes()
    if (resumes.value.length) await selectResume(resumes.value[0])
    ElMessage.success('简历已删除')
  } catch (reason) {
    operationError.value = reason
  } finally {
    deletingId.value = ''
  }
}

async function loadSkills() {
  skillsLoading.value = true
  skillsError.value = null
  try {
    skillCatalog.value = await profileApi.skills()
  } catch (reason) {
    skillsError.value = reason
  } finally {
    skillsLoading.value = false
  }
}

function skillName(code: string) {
  return skillCatalog.value.find(item => item.code === code)?.name || code
}

function skillOptions(currentCode: string) {
  if (currentCode && !skillCatalog.value.some(item => item.code === currentCode)) {
    return [...skillCatalog.value, { code: currentCode, name: `${currentCode}（当前事实）`, parentCode: null, enabled: true }]
  }
  return skillCatalog.value
}

function addSkill() {
  facts.value.skills.push({ skillCode: '', claim: '', quote: '', location: '', confirmed: false })
}

function removeSkill(index: number) {
  facts.value.skills.splice(index, 1)
}

function validateFacts() {
  const codes = facts.value.skills.map(item => item.skillCode.trim())
  if (codes.some(code => !code)) return '请为每一项技能选择技能目录中的技能'
  if (new Set(codes).size !== codes.length) return '技能不能重复，请合并或删除重复项'
  if (facts.value.graduationYear !== null && (!Number.isInteger(facts.value.graduationYear) || facts.value.graduationYear < 1900 || facts.value.graduationYear > maxGraduationYear)) return `毕业年份须为1900至${maxGraduationYear}的整数`
  if (facts.value.experienceMonths !== null && (!Number.isInteger(facts.value.experienceMonths) || facts.value.experienceMonths < 0 || facts.value.experienceMonths > 600)) return '经验月数须为0至600的整数'
  return ''
}

async function confirmFacts() {
  const version = selectedVersion.value
  if (!version || version.status !== 'PARSED' || confirming.value) return
  const validation = validateFacts()
  if (validation) {
    confirmError.value = new Error(validation)
    return
  }
  confirming.value = true
  confirmError.value = null
  try {
    const profile = await profileApi.get()
    const payload = cloneFacts(facts.value)
    payload.skills = payload.skills.map(skill => ({ ...skill, skillCode: skill.skillCode.trim(), confirmed: true }))
    const result = await resumeApi.confirm(version.id, profile.profileVersion, payload)
    profileResult.value = result
    await loadVersion(version.id)
    profileResult.value = result
    await loadResumes()
    ElMessage.success(`事实确认成功，画像版本已更新为 ${result.profileVersion}`)
  } catch (reason) {
    confirmError.value = reason
  } finally {
    confirming.value = false
  }
}

async function initialize() {
  await loadResumes()
  const versionId = queryValue(route.query.versionId)
  if (versionId) await restoreVersion(versionId)
  else if (resumes.value.length) await selectResume(resumes.value[0])
  initialized.value = true
}

watch(() => route.query.versionId, value => {
  const versionId = queryValue(value)
  if (initialized.value && versionId && versionId !== selectedVersionId.value) void restoreVersion(versionId)
})

onMounted(() => {
  void initialize()
  void loadSkills()
})
</script>

<template>
  <div class="page resume-page">
    <header class="page-header">
      <div>
        <h1>简历中心</h1>
        <p>管理文件版本，核对解析事实，确认后同步到求职画像。</p>
      </div>
      <div class="page-header__actions">
        <el-button @click="openUpload('new')">上传新简历</el-button>
        <el-button type="primary" :disabled="!selectedResumeId" @click="openUpload('append')">向当前简历追加版本</el-button>
      </div>
    </header>

    <el-alert v-if="operationError" class="action-alert" type="error" :title="errorMessage(operationError)" :closable="false" show-icon />

    <div class="resume-layout">
      <aside class="panel resume-list-panel">
        <div class="panel__heading">
          <h2>我的简历</h2>
          <el-button text type="primary" :loading="listLoading" @click="loadResumes(false)">刷新</el-button>
        </div>
        <AsyncState :loading="listLoading" :error="listError" :empty="!resumes.length" empty-text="还没有简历" empty-description="上传 PDF 或 DOCX 后，系统会开始解析。" @retry="loadResumes(true)">
          <template #empty>
            <el-button type="primary" @click="openUpload('new')">直接上传简历</el-button>
          </template>
          <div class="resume-list">
            <article v-for="resume in resumes" :key="resume.id" class="resume-card" :class="{ 'resume-card--active': resume.id === selectedResumeId }" @click="selectResume(resume)">
              <div class="resume-card__title">
                <strong>{{ resume.title }}</strong>
                <el-tag size="small" :type="statusTagType(resume.latestVersion.status)">{{ statusLabel(resume.latestVersion.status) }}</el-tag>
              </div>
              <p>{{ resume.latestVersion.fileName }}</p>
              <div class="resume-card__meta">
                <span>最新 v{{ resume.latestVersion.versionNo }}</span>
                <span>{{ formatDate(resume.latestVersion.createdAt) }}</span>
              </div>
              <div class="resume-card__actions">
                <el-button text type="primary" @click.stop="selectResume(resume)">查看版本</el-button>
                <el-button text type="danger" :loading="deletingId === resume.id" @click.stop="removeResume(resume)">删除</el-button>
              </div>
            </article>
          </div>
          <el-pagination v-if="listTotal > 0" class="pagination" layout="total, sizes, prev, pager, next" :total="listTotal" :current-page="listPage" :page-size="listSize" :page-sizes="[5, 10, 20]" small background @current-change="changePage" @size-change="changePageSize" />
        </AsyncState>
      </aside>

      <main class="resume-main">
        <section class="panel">
          <div class="panel__heading">
            <div>
              <h2>{{ currentTitle }}</h2>
              <p v-if="selectedVersion" class="muted version-subtitle">{{ selectedVersion.fileName }} · v{{ selectedVersion.versionNo }} · {{ formatFileSize(selectedVersion.sizeBytes) }}</p>
            </div>
            <div class="actions">
              <el-button v-if="selectedVersion" :loading="downloading" @click="downloadCurrent">下载原件</el-button>
              <el-button v-if="selectedResumeId" @click="openUpload('append')">追加版本</el-button>
            </div>
          </div>

          <AsyncState v-if="selectedResumeId" :loading="versionsLoading" :error="versionsError" :empty="!versions.length" empty-text="当前设备没有可显示的版本" empty-description="可追加新版本，或刷新后重试。" @retry="retryVersions">
            <template #empty>
              <el-button type="primary" @click="openUpload('append')">追加版本</el-button>
            </template>
            <div class="version-toolbar">
              <span>文件版本</span>
              <el-select :model-value="selectedVersionId" class="version-select" placeholder="选择版本" @change="loadVersion">
                <el-option v-for="version in versions" :key="version.id" :value="version.id" :label="`v${version.versionNo} · ${version.fileName} · ${statusLabel(version.status)}`" />
              </el-select>
              <el-tag v-if="selectedVersion" :type="statusTagType(selectedVersion.status)">{{ statusLabel(selectedVersion.status) }}</el-tag>
            </div>
            <el-alert v-if="versionsWarning" class="inline-alert" type="warning" :title="versionsWarning" :closable="false" show-icon>
              <template #default>
                <el-button text type="primary" @click="retryVersions">重试版本加载</el-button>
              </template>
            </el-alert>
            <p class="muted version-note">版本列表由业务后端返回；每次切换都会重新获取真实版本详情。</p>
          </AsyncState>
          <AsyncState v-else empty empty-text="请选择或上传一份简历">
            <template #empty><el-button type="primary" @click="openUpload('new')">上传简历</el-button></template>
          </AsyncState>
          <el-alert v-if="downloadError" class="inline-alert" type="error" :title="`下载失败：${errorMessage(downloadError)}`" :closable="false" show-icon>
            <template #default><el-button text type="primary" @click="downloadCurrent">重试下载</el-button></template>
          </el-alert>
        </section>

        <section v-if="task || parseSubmissionError || taskAuthorityError || taskError" class="panel">
          <div class="panel__heading"><h2>解析任务</h2><el-tag v-if="taskRunning" type="warning">轮询与事件流同步中</el-tag></div>
          <TaskStatus :task="task" :reasoning="reasoningText">
            <template #actions>
              <el-button v-if="task?.status === 'FAILED'" type="primary" :loading="parseSubmitting" @click="startParse(taskVersionId || selectedVersionId)">按原版本重试</el-button>
              <el-button v-if="taskVersionId" :loading="detailLoading" @click="loadVersion(taskVersionId)">刷新版本详情</el-button>
            </template>
          </TaskStatus>
          <pre v-if="deltaText" class="task-delta pre-wrap">{{ deltaText }}</pre>
          <el-alert v-if="parseSubmissionError" type="error" :title="`解析任务提交失败：${errorMessage(parseSubmissionError)}`" :closable="false" show-icon>
            <template #default><el-button text type="primary" :loading="parseSubmitting" @click="startParse(taskVersionId)">重试提交</el-button></template>
          </el-alert>
          <el-alert v-if="taskAuthorityError" type="error" :title="`任务已完成，但权威详情加载失败：${errorMessage(taskAuthorityError)}`" :closable="false" show-icon>
            <template #default><el-button text type="primary" @click="retryTaskAuthority">重新加载详情</el-button></template>
          </el-alert>
          <el-alert v-if="taskError" type="warning" :title="`任务状态连接异常：${errorMessage(taskError)}`" description="任务可能仍在后端执行，可重新连接并恢复轮询。" :closable="false" show-icon>
            <template #default><el-button text type="primary" @click="restartTask">重新连接</el-button></template>
          </el-alert>
        </section>

        <section class="panel">
          <div class="panel__heading">
            <div>
              <h2>解析事实</h2>
              <p class="muted facts-description">确认前请逐项核对；技能确认提交时会统一标记为已确认。</p>
            </div>
            <el-button v-if="selectedVersion && ['UPLOADED', 'FAILED'].includes(selectedVersion.status)" type="primary" :loading="parseSubmitting || taskRunning" @click="startParse(selectedVersion.id)">{{ selectedVersion.status === 'FAILED' ? '重试解析' : '开始解析' }}</el-button>
          </div>
          <AsyncState :loading="detailLoading" :error="detailError" :empty="!selectedVersion" empty-text="暂无版本详情" @retry="loadVersion(selectedVersionId)">
            <template v-if="selectedVersion">
              <div class="version-meta">
                <span>上传时间：{{ formatDate(selectedVersion.createdAt) }}</span>
                <span>MIME：{{ selectedVersion.mimeType }}</span>
              </div>
              <el-alert v-if="selectedVersion.status === 'FAILED'" class="inline-alert" type="error" title="该版本解析失败，请查看解析任务中的错误详情，或按原版本重试。" :closable="false" show-icon />
              <el-alert v-if="selectedVersion.status === 'UPLOADED'" class="inline-alert" type="info" title="文件已上传，开始解析后才能核对事实。" :closable="false" show-icon />
              <el-alert v-if="selectedVersion.status === 'PARSING'" class="inline-alert" type="warning" title="正在解析，请等待任务完成。" :closable="false" show-icon />
              <template v-if="['PARSED', 'CONFIRMED'].includes(selectedVersion.status)">
                <el-alert v-if="selectedVersion.status === 'CONFIRMED'" class="inline-alert" type="success" title="该版本事实已确认，当前仅供查看；如需修改，请上传新版本。" :closable="false" show-icon />
                <el-alert v-if="profileResult" class="inline-alert" type="success" :title="`确认成功，当前画像版本：${profileResult.profileVersion}`" :description="`生效简历版本：${profileResult.activeResumeVersionId || '—'}；更新时间：${formatDate(profileResult.updatedAt)}`" :closable="false" show-icon />

                <div class="facts-form">
                  <div class="form-grid">
                    <label class="field"><span>最高学历</span><el-select v-model="facts.education" clearable placeholder="请选择学历" :disabled="!canEditFacts"><el-option v-for="item in educationOptions" :key="item.value" :value="item.value" :label="item.label" /></el-select></label>
                    <label class="field"><span>毕业年份</span><el-input-number v-model="facts.graduationYear" :min="1900" :max="maxGraduationYear" :step="1" step-strictly placeholder="毕业年份" :disabled="!canEditFacts" controls-position="right" /></label>
                    <label class="field"><span>全职经验（月）</span><el-input-number v-model="facts.experienceMonths" :min="0" :max="600" :step="1" step-strictly placeholder="经验月数" :disabled="!canEditFacts" controls-position="right" /></label>
                    <label class="field"><span>专业</span><el-input v-model="facts.major" maxlength="200" placeholder="请输入专业" :disabled="!canEditFacts" /></label>
                  </div>

                  <div class="skills-heading">
                    <div><h3>技能事实</h3><p class="muted">技能必须来自技能目录，可补充自述、原文引用和位置。</p></div>
                    <el-button v-if="canEditFacts" @click="addSkill">添加技能</el-button>
                  </div>
                  <el-alert v-if="skillsError" class="inline-alert" type="error" :title="`技能目录加载失败：${errorMessage(skillsError)}`" :closable="false" show-icon>
                    <template #default><el-button text type="primary" :loading="skillsLoading" @click="loadSkills">重试目录加载</el-button></template>
                  </el-alert>
                  <el-skeleton v-if="skillsLoading" :rows="2" animated />
                  <el-empty v-else-if="!facts.skills.length" description="暂无技能事实">
                    <el-button v-if="canEditFacts" @click="addSkill">添加技能</el-button>
                  </el-empty>
                  <div v-else class="skill-table-wrap">
                    <el-table :data="facts.skills" row-key="skillCode" border>
                      <el-table-column label="技能" min-width="180">
                        <template #default="scope">
                          <el-select v-model="scope.row.skillCode" filterable placeholder="选择技能" :disabled="!canEditFacts">
                            <el-option v-for="skill in skillOptions(scope.row.skillCode)" :key="skill.code" :value="skill.code" :label="skill.name" :disabled="!skill.enabled" />
                          </el-select>
                          <small class="skill-code">{{ scope.row.skillCode || '未选择' }}</small>
                        </template>
                      </el-table-column>
                      <el-table-column label="能力自述" min-width="190"><template #default="scope"><el-input v-model="scope.row.claim" type="textarea" :rows="2" maxlength="500" :disabled="!canEditFacts" /></template></el-table-column>
                      <el-table-column label="原文引用" min-width="210"><template #default="scope"><el-input v-model="scope.row.quote" type="textarea" :rows="2" maxlength="1000" :disabled="!canEditFacts" /></template></el-table-column>
                      <el-table-column label="原文位置" min-width="150"><template #default="scope"><el-input v-model="scope.row.location" maxlength="200" placeholder="page:1" :disabled="!canEditFacts" /></template></el-table-column>
                      <el-table-column label="已核对" width="90" align="center"><template #default="scope"><el-switch v-model="scope.row.confirmed" :disabled="!canEditFacts" /></template></el-table-column>
                      <el-table-column v-if="canEditFacts" label="操作" width="80" align="center"><template #default="scope"><el-button text type="danger" @click="removeSkill(scope.$index)">删除</el-button></template></el-table-column>
                    </el-table>
                  </div>

                  <div v-if="facts.warnings.length" class="facts-warnings">
                    <strong>解析提示</strong>
                    <ul><li v-for="warning in facts.warnings" :key="warning">{{ warning }}</li></ul>
                  </div>
                  <el-alert v-if="confirmError" class="inline-alert" type="error" :title="`确认失败：${errorMessage(confirmError)}`" description="重新提交前会再次获取最新画像版本；请核对后重试。" :closable="false" show-icon />
                  <div v-if="canEditFacts" class="confirm-bar">
                    <span class="muted">提交完整事实并更新求职画像，此操作不会改写原始文件。</span>
                    <el-button type="primary" :loading="confirming" @click="confirmFacts">核对完成，确认事实</el-button>
                  </div>
                </div>
              </template>
              <el-empty v-else-if="!['FAILED', 'UPLOADED', 'PARSING'].includes(selectedVersion.status)" description="当前状态没有可展示的事实" />
            </template>
          </AsyncState>
        </section>
      </main>
    </div>

    <el-dialog v-model="uploadVisible" :title="uploadMode === 'new' ? '上传新简历' : '追加简历版本'" width="560px" @closed="resetUpload">
      <el-form label-position="top">
        <el-form-item label="上传方式">
          <el-radio-group v-model="uploadMode">
            <el-radio-button value="new">新简历</el-radio-button>
            <el-radio-button value="append" :disabled="!selectedResumeId">追加到当前简历</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="uploadMode === 'new'" label="简历标题（可选）">
          <el-input v-model="uploadTitle" maxlength="100" show-word-limit placeholder="不填写时由后端按文件信息处理" />
        </el-form-item>
        <el-form-item v-else label="追加到">
          <el-input :model-value="currentTitle" disabled />
        </el-form-item>
        <el-form-item label="简历文件">
          <el-upload v-model:file-list="uploadFiles" class="resume-uploader" drag action="#" :auto-upload="false" :limit="1" accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document" :disabled="uploading" :on-change="handleFileChange" :on-remove="removeUploadFile" :on-exceed="() => { uploadError = '一次只能上传一个文件，请先移除已选文件' }">
            <div class="upload-placeholder">
              <strong>拖拽文件到这里，或点击选择</strong>
              <p>仅 PDF / DOCX，单个文件不超过 10 MiB</p>
            </div>
          </el-upload>
        </el-form-item>
        <el-alert v-if="uploadError" type="error" :title="uploadError" :closable="false" show-icon />
      </el-form>
      <template #footer>
        <el-button :disabled="uploading" @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">上传并自动解析</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.resume-page { padding-bottom: 24px; }
.action-alert { margin-bottom: 14px; }
.resume-layout { display: grid; grid-template-columns: minmax(280px, 340px) minmax(0, 1fr); gap: 14px; align-items: start; }
.resume-list-panel { min-width: 0; }
.resume-main { min-width: 0; }
.resume-list { display: grid; gap: 10px; }
.resume-card { padding: 13px; border: 1px solid #e3ecf7; border-radius: 9px; cursor: pointer; transition: border-color .2s, background .2s; }
.resume-card:hover { border-color: #9dbff5; }
.resume-card--active { border-color: #2d72ec; background: #f2f7ff; }
.resume-card__title { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.resume-card__title strong { min-width: 0; color: #14234d; overflow-wrap: anywhere; }
.resume-card p { margin: 8px 0; color: $ai-text-secondary; font-size: 13px; overflow-wrap: anywhere; }
.resume-card__meta { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 6px; color: $ai-text-tertiary; font-size: 12px; }
.resume-card__actions { display: flex; justify-content: space-between; margin-top: 5px; }
.version-subtitle { margin: 6px 0 0; overflow-wrap: anywhere; }
.version-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.version-select { width: min(520px, 100%); }
.version-note { margin: 12px 0 0; font-size: 12px; line-height: 1.6; }
.inline-alert { margin-top: 14px; }
.task-delta { max-height: 180px; margin: 12px 0; padding: 12px; overflow: auto; border-radius: 8px; background: #f5f8fc; }
.facts-description { margin: 5px 0 0; font-size: 13px; }
.version-meta { display: flex; flex-wrap: wrap; gap: 12px 24px; color: $ai-text-secondary; font-size: 12px; }
.facts-form { margin-top: 18px; }
.field { display: grid; gap: 7px; min-width: 0; color: $ai-text-secondary; }
.field > .el-select, .field > .el-input-number { width: 100%; }
.skills-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 24px 0 12px; }
.skills-heading h3 { margin: 0; color: #14234d; }
.skills-heading p { margin: 5px 0 0; font-size: 12px; }
.skill-table-wrap { overflow-x: auto; }
.skill-code { display: block; margin-top: 5px; color: $ai-text-tertiary; overflow-wrap: anywhere; }
.facts-warnings { margin-top: 16px; padding: 13px; border-radius: 8px; background: #fff8e9; color: #805b12; }
.facts-warnings ul { margin: 8px 0 0; padding-left: 20px; line-height: 1.7; }
.confirm-bar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 18px; padding-top: 16px; border-top: 1px solid #e8eef7; }
.confirm-bar > span { line-height: 1.6; }
.resume-uploader { width: 100%; }
.resume-uploader :deep(.el-upload), .resume-uploader :deep(.el-upload-dragger) { width: 100%; }
.upload-placeholder { padding: 26px 12px; }
.upload-placeholder strong { color: #17285a; }
.upload-placeholder p { margin: 10px 0 0; color: $ai-text-secondary; }
@media (max-width: 1050px) { .resume-layout { grid-template-columns: 1fr; } .resume-list { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 700px) { .resume-list { grid-template-columns: 1fr; } .version-toolbar { align-items: stretch; flex-direction: column; } .version-select { width: 100%; } .skills-heading, .confirm-bar { align-items: stretch; flex-direction: column; } .confirm-bar .el-button { width: 100%; } .page-header__actions { width: 100%; } .page-header__actions .el-button { flex: 1; margin: 0; } }
</style>
