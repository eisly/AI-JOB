<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { applicationApi } from '@/api/applications'
import { jobApi } from '@/api/jobs'
import { ApiError } from '@/api/request'
import type { ApplicationCreateInput, ApplicationStatus, ApplicationSummaryVO, ApplicationVO, JobVO, Page } from '@/api/types'
import AsyncState from '@/components/AsyncState.vue'
import { cityLabels, educationLabels, errorMessage, formatDate, formatSalary } from '@/utils/format'

interface ApplicationFormModel {
  jobId: string
  companyName: string
  jobTitle: string
  cityCode: string
  education: string
  salaryText: string
  description: string
  status: ApplicationStatus
  plannedDate: string | null
  appliedDate: string | null
  interviewDate: string | null
  closedDate: string | null
  note: string
}

const router = useRouter()
const statusLabels: Record<ApplicationStatus, string> = { PLANNED: '待投递', APPLIED: '已投递', INTERVIEWING: '面试中', CLOSED: '已结束' }
const statusTypes: Record<ApplicationStatus, 'info' | 'primary' | 'success' | 'warning'> = { PLANNED: 'info', APPLIED: 'primary', INTERVIEWING: 'warning', CLOSED: 'success' }
const statusOptions = Object.entries(statusLabels).map(([value, label]) => ({ value: value as ApplicationStatus, label }))
const cityOptions = Object.entries(cityLabels).map(([value, label]) => ({ value, label }))
const page = ref<Page<ApplicationVO>>({ total: 0, records: [], current: 1, size: 10 })
const summary = ref<ApplicationSummaryVO | null>(null)
const jobs = ref<JobVO[]>([])
const listLoading = ref(false)
const summaryLoading = ref(false)
const jobsLoading = ref(false)
const listError = ref<unknown>(null)
const summaryError = ref<unknown>(null)
const jobsError = ref<unknown>(null)
const actionError = ref<unknown>(null)
const statusFilter = ref('')
const editorVisible = ref(false)
const editingId = ref('')
const editingVersion = ref(1)
const detailLoading = ref(false)
const saving = ref(false)
const deletingId = ref('')
const formRef = ref<FormInstance>()
const form = reactive<ApplicationFormModel>(emptyForm())
const rules: FormRules = {
  companyName: [{ required: true, whitespace: true, message: '请输入公司名称', trigger: 'blur' }, { max: 100, message: '公司名称不能超过100字符', trigger: 'blur' }],
  jobTitle: [{ required: true, whitespace: true, message: '请输入岗位名称', trigger: 'blur' }, { max: 100, message: '岗位名称不能超过100字符', trigger: 'blur' }],
  status: [{ required: true, message: '请选择投递状态', trigger: 'change' }],
}

function emptyForm(): ApplicationFormModel {
  return { jobId: '', companyName: '', jobTitle: '', cityCode: '', education: '', salaryText: '', description: '', status: 'PLANNED', plannedDate: null, appliedDate: null, interviewDate: null, closedDate: null, note: '' }
}

function assignForm(value: ApplicationFormModel) {
  Object.assign(form, value)
}

function nullable(value: string) {
  const normalized = value.trim()
  return normalized || null
}

async function loadApplications() {
  listLoading.value = true
  listError.value = null
  try {
    page.value = await applicationApi.list({ current: page.value.current, size: page.value.size, status: statusFilter.value || undefined })
  } catch (reason) {
    listError.value = reason
  } finally {
    listLoading.value = false
  }
}

async function loadSummary() {
  summaryLoading.value = true
  summaryError.value = null
  try {
    summary.value = await applicationApi.summary()
  } catch (reason) {
    summaryError.value = reason
  } finally {
    summaryLoading.value = false
  }
}

async function loadJobs() {
  jobsLoading.value = true
  jobsError.value = null
  try {
    const [publicPage, personalPage] = await Promise.all([jobApi.list({ current: 1, size: 100 }), jobApi.imports({ current: 1, size: 100 })])
    const values = new Map<string, JobVO>()
    ;[...publicPage.records, ...personalPage.records].forEach(job => values.set(job.id, job))
    jobs.value = [...values.values()]
  } catch (reason) {
    jobsError.value = reason
  } finally {
    jobsLoading.value = false
  }
}

function refreshAll() {
  return Promise.all([loadApplications(), loadSummary()])
}

function applyFilter() {
  page.value.current = 1
  void loadApplications()
}

function changePage(current: number) {
  page.value.current = current
  void loadApplications()
}

function changeSize(size: number) {
  page.value.size = size
  page.value.current = 1
  void loadApplications()
}

function fillJob(jobId: string) {
  if (editingId.value || !jobId) return
  const job = jobs.value.find(item => item.id === jobId)
  if (!job) return
  form.companyName = job.companyName
  form.jobTitle = job.title
  form.cityCode = job.cityCode || ''
  form.education = job.requirements?.educationMin ? educationLabels[job.requirements.educationMin] : ''
  form.salaryText = job.minMonthlySalaryCents !== null && job.maxMonthlySalaryCents !== null ? formatSalary(job.minMonthlySalaryCents, job.maxMonthlySalaryCents) : ''
  form.description = job.jdText.slice(0, 1000)
}

function openCreate() {
  editingId.value = ''
  editingVersion.value = 1
  assignForm(emptyForm())
  actionError.value = null
  editorVisible.value = true
  formRef.value?.clearValidate()
  if (!jobs.value.length && !jobsLoading.value) void loadJobs()
}

async function openEdit(record: ApplicationVO) {
  editingId.value = record.id
  editingVersion.value = record.version
  actionError.value = null
  detailLoading.value = true
  editorVisible.value = true
  try {
    const latest = await applicationApi.get(record.id)
    editingVersion.value = latest.version
    assignForm({
      jobId: latest.jobId || '', companyName: latest.companyName, jobTitle: latest.jobTitle,
      cityCode: latest.cityCode || '', education: latest.education || '', salaryText: latest.salaryText || '',
      description: latest.description || '', status: latest.status, plannedDate: latest.plannedDate,
      appliedDate: latest.appliedDate, interviewDate: latest.interviewDate, closedDate: latest.closedDate, note: latest.note || '',
    })
    formRef.value?.clearValidate()
  } catch (reason) {
    actionError.value = reason
  } finally {
    detailLoading.value = false
  }
}

async function save() {
  if (saving.value || detailLoading.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  actionError.value = null
  const fields = {
    companyName: form.companyName.trim(), jobTitle: form.jobTitle.trim(), cityCode: nullable(form.cityCode),
    education: nullable(form.education), salaryText: nullable(form.salaryText), description: nullable(form.description),
    status: form.status, plannedDate: form.plannedDate, appliedDate: form.appliedDate,
    interviewDate: form.interviewDate, closedDate: form.closedDate, note: nullable(form.note),
  }
  try {
    if (editingId.value) {
      await applicationApi.update(editingId.value, { ...fields, expectedVersion: editingVersion.value })
      ElMessage.success('投递记录已更新')
    } else {
      const body: ApplicationCreateInput = { ...fields, jobId: form.jobId || null }
      await applicationApi.create(body)
      ElMessage.success('投递记录已创建')
    }
    editorVisible.value = false
    await refreshAll()
  } catch (reason) {
    actionError.value = reason
    if (reason instanceof ApiError && reason.status === 409 && editingId.value) {
      const current = page.value.records.find(item => item.id === editingId.value)
      if (current) await openEdit(current)
      actionError.value = new Error('记录版本已变化，已重新读取服务端内容，请核对后再次保存')
    }
  } finally {
    saving.value = false
  }
}

async function remove(record: ApplicationVO) {
  if (deletingId.value) return
  try {
    await ElMessageBox.confirm(`确定删除“${record.companyName} · ${record.jobTitle}”的投递记录吗？`, '删除投递记录', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' })
  } catch {
    return
  }
  deletingId.value = record.id
  actionError.value = null
  try {
    await applicationApi.remove(record.id, record.version)
    if (page.value.records.length === 1 && page.value.current > 1) page.value.current -= 1
    await refreshAll()
    ElMessage.success('投递记录已删除')
  } catch (reason) {
    actionError.value = reason
    if (reason instanceof ApiError && reason.status === 409) await loadApplications()
  } finally {
    deletingId.value = ''
  }
}

function applicationRow(row: unknown) {
  return row as ApplicationVO
}

function openJob(jobId: string | null) {
  if (jobId) void router.push({ path: '/job-match', query: { jobId } })
}

onMounted(() => {
  void refreshAll()
  void loadJobs()
})
</script>

<template>
  <div class="page application-page">
    <header class="page-header">
      <div><h1>投递进度</h1><p>手动记录真实投递状态与日期；关联岗位只用于带入快照，后续岗位变化不会改写历史记录。</p></div>
      <div class="page-header__actions"><el-button :loading="listLoading || summaryLoading" @click="refreshAll">刷新</el-button><el-button type="primary" @click="openCreate">新增投递记录</el-button></div>
    </header>

    <el-alert v-if="actionError && !editorVisible" class="action-alert" :title="errorMessage(actionError)" type="error" show-icon :closable="false" />

    <section class="summary-section">
      <AsyncState :loading="summaryLoading && !summary" :error="summaryError" @retry="loadSummary">
        <div class="summary-grid">
          <article class="summary-card summary-card--total"><span>全部记录</span><strong>{{ summary?.total ?? 0 }}</strong></article>
          <article v-for="option in statusOptions" :key="option.value" class="summary-card"><span>{{ option.label }}</span><strong>{{ summary?.counts[option.value] ?? 0 }}</strong></article>
        </div>
      </AsyncState>
    </section>

    <section class="panel">
      <div class="panel__heading">
        <div><h2>投递记录</h2><span class="muted">按创建时间倒序展示本人记录</span></div>
        <el-radio-group v-model="statusFilter" @change="applyFilter">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button v-for="option in statusOptions" :key="option.value" :value="option.value">{{ option.label }}</el-radio-button>
        </el-radio-group>
      </div>
      <AsyncState :loading="listLoading" :error="listError" :empty="!page.records.length" empty-text="当前筛选条件下暂无投递记录" @retry="loadApplications">
        <template #empty><el-button type="primary" @click="openCreate">新增第一条记录</el-button></template>
        <div class="application-table-wrap">
          <el-table :data="page.records" row-key="id" class="application-table">
            <el-table-column label="公司与岗位" min-width="230">
              <template #default="{ row }"><div class="job-cell"><strong>{{ row.companyName }}</strong><span>{{ row.jobTitle }}</span><small>{{ row.id }}</small></div></template>
            </el-table-column>
            <el-table-column label="城市 / 学历" min-width="130"><template #default="{ row }">{{ cityLabels[row.cityCode || ''] || row.cityCode || '未填写' }}<br><span class="muted">{{ row.education || '学历未填写' }}</span></template></el-table-column>
            <el-table-column prop="salaryText" label="薪资" min-width="120"><template #default="{ row }">{{ row.salaryText || '未填写' }}</template></el-table-column>
            <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="statusTypes[row.status as ApplicationStatus]">{{ statusLabels[row.status as ApplicationStatus] }}</el-tag></template></el-table-column>
            <el-table-column label="阶段日期" min-width="190"><template #default="{ row }"><div class="date-cell"><span>计划：{{ row.plannedDate || '—' }}</span><span>投递：{{ row.appliedDate || '—' }}</span><span>面试：{{ row.interviewDate || '—' }}</span><span>结束：{{ row.closedDate || '—' }}</span></div></template></el-table-column>
            <el-table-column label="更新时间" min-width="160"><template #default="{ row }">{{ formatDate(row.updatedAt) }}</template></el-table-column>
            <el-table-column label="操作" fixed="right" min-width="250"><template #default="{ row }"><div class="row-actions"><el-button size="small" @click="openEdit(applicationRow(row))">查看 / 编辑</el-button><el-button v-if="row.jobId" size="small" @click="openJob(row.jobId)">关联岗位</el-button><el-button size="small" type="danger" plain :loading="deletingId === row.id" @click="remove(applicationRow(row))">删除</el-button></div></template></el-table-column>
          </el-table>
        </div>
        <el-pagination class="pagination" background layout="total, sizes, prev, pager, next" :total="page.total" :current-page="page.current" :page-size="page.size" :page-sizes="[10, 20, 50]" @current-change="changePage" @size-change="changeSize" />
      </AsyncState>
    </section>

    <el-dialog v-model="editorVisible" :title="editingId ? '查看并编辑投递记录' : '新增投递记录'" width="min(860px, 94vw)" :close-on-click-modal="false">
      <el-alert v-if="actionError" class="dialog-alert" :title="errorMessage(actionError)" type="error" show-icon :closable="false" />
      <el-alert v-if="jobsError" class="dialog-alert" type="warning" :closable="false" show-icon><template #title>岗位列表加载失败：{{ errorMessage(jobsError) }}</template><el-button size="small" @click="loadJobs">重试</el-button></el-alert>
      <el-form ref="formRef" v-loading="detailLoading" :model="form" :rules="rules" label-position="top">
        <el-form-item label="关联岗位（可选）">
          <el-select v-model="form.jobId" class="full-width" filterable clearable :loading="jobsLoading" :disabled="!!editingId" placeholder="手动录入或选择岗位带入信息" @change="fillJob">
            <el-option label="手动录入（不关联岗位）" value="" />
            <el-option v-for="job in jobs" :key="job.id" :value="job.id" :label="`${job.companyName} · ${job.title}`" />
          </el-select>
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="公司名称" prop="companyName" required><el-input v-model="form.companyName" maxlength="100" show-word-limit /></el-form-item>
          <el-form-item label="岗位名称" prop="jobTitle" required><el-input v-model="form.jobTitle" maxlength="100" show-word-limit /></el-form-item>
          <el-form-item label="城市"><el-select v-model="form.cityCode" class="full-width" clearable filterable placeholder="请选择城市"><el-option v-for="option in cityOptions" :key="option.value" :label="option.label" :value="option.value" /></el-select></el-form-item>
          <el-form-item label="学历展示文本"><el-input v-model="form.education" maxlength="50" placeholder="例如：本科" /></el-form-item>
          <el-form-item label="薪资展示文本"><el-input v-model="form.salaryText" maxlength="100" placeholder="例如：10–16K" /></el-form-item>
          <el-form-item label="投递状态" prop="status" required><el-select v-model="form.status" class="full-width"><el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" /></el-select></el-form-item>
          <el-form-item label="计划投递日期"><el-date-picker v-model="form.plannedDate" class="full-width" type="date" value-format="YYYY-MM-DD" placeholder="可不填" /></el-form-item>
          <el-form-item label="实际投递日期"><el-date-picker v-model="form.appliedDate" class="full-width" type="date" value-format="YYYY-MM-DD" placeholder="可不填" /></el-form-item>
          <el-form-item label="面试日期"><el-date-picker v-model="form.interviewDate" class="full-width" type="date" value-format="YYYY-MM-DD" placeholder="可不填" /></el-form-item>
          <el-form-item label="结束日期"><el-date-picker v-model="form.closedDate" class="full-width" type="date" value-format="YYYY-MM-DD" placeholder="可不填" /></el-form-item>
        </div>
        <el-form-item label="岗位简述"><el-input v-model="form.description" type="textarea" :rows="4" maxlength="1000" show-word-limit /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" type="textarea" :rows="4" maxlength="2000" show-word-limit placeholder="记录反馈、联系人或下一步安排" /></el-form-item>
        <el-descriptions v-if="editingId" :column="2" border size="small"><el-descriptions-item label="记录版本">v{{ editingVersion }}</el-descriptions-item><el-descriptions-item label="记录ID"><span class="break-all">{{ editingId }}</span></el-descriptions-item></el-descriptions>
      </el-form>
      <template #footer><el-button :disabled="saving" @click="editorVisible = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="detailLoading" @click="save">{{ editingId ? '保存修改' : '创建记录' }}</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.application-page { padding-bottom: 24px; }
.action-alert, .dialog-alert { margin-bottom: 14px; }
.summary-section { margin-bottom: 14px; }
.summary-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; }
.summary-card { display: grid; gap: 10px; padding: 18px; border: 1px solid $ai-border; border-radius: $ai-radius; background: $ai-bg-card; }
.summary-card span { color: $ai-text-secondary; }
.summary-card strong { color: $ai-primary; font-size: 28px; }
.summary-card--total { background: $ai-primary-light; }
.application-table-wrap { overflow-x: auto; }
.application-table { min-width: 1200px; }
.job-cell, .date-cell { display: grid; gap: 4px; }
.job-cell small { color: $ai-text-tertiary; overflow-wrap: anywhere; }
.row-actions { display: flex; flex-wrap: wrap; gap: 6px; }
.row-actions .el-button { margin: 0; }
.break-all { word-break: break-all; }
@media (max-width: 900px) { .summary-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 700px) { .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .summary-card--total { grid-column: 1 / -1; } .page-header__actions { width: 100%; } .page-header__actions .el-button { flex: 1; margin: 0; } }
</style>