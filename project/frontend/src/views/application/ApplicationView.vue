<script setup lang="ts">
import LoadingSkeleton from '@/components/LoadingSkeleton.vue'
import AsyncState from '@/components/AsyncState.vue'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Plus } from '@element-plus/icons-vue'
import { ApiError } from '@/api/request'
import {
  deleteApplication,
  getApplicationDetail,
  getApplicationSummary,
  listApplications,
} from '@/api/application'
import {
  APPLICATION_STATUSES,
  APPLICATION_STATUS_LABELS,
  type ApplicationPage,
  type ApplicationStatus,
  type ApplicationSummary,
  type ApplicationVO,
} from '@/types/application'
import { formatDate as formatLocalDateTime } from '@/utils/format'
import ApplicationForm from './ApplicationForm.vue'
import ApplicationDetail from './ApplicationDetail.vue'
import ApplicationDeleteConfirm from './ApplicationDeleteConfirm.vue'

/**
 * 投递进度页面：统计卡片 + 分页表格（沿用现有布局与 Element Plus 风格；
 * 与原型 15-投递进度.png 的四列看板布局有意不同，交付说明中注明）。
 *
 * 稳定性设计：
 * - listSeq / summarySeq / detailSeq 自增序号：成功回调、catch、finally 三处均检查，
 *   过期请求不渲染数据、不复位 loading、不写错误态
 * - 详情弹层快速切换记录、关闭弹层时 ++detailSeq 作废在途请求，迟到响应零接触状态
 * - 筛选变化重置页码；删除当前页最后一条且非第一页时回退一页
 * - 统计与列表为独立请求，全部状态查询完全省略 status 参数
 */

type StatusFilter = ApplicationStatus | ''

const page = reactive({
  current: 1,
  size: 20,
  total: 0,
})
const statusFilter = ref<StatusFilter>('')
const records = ref<ApplicationVO[]>([])
const listLoading = ref(false)
const listError = ref<string | null>(null)

const summary = ref<ApplicationSummary>({ total: 0, counts: { PLANNED: 0, APPLIED: 0, INTERVIEWING: 0, CLOSED: 0 } })
const summaryLoading = ref(false)
const summaryLoaded = ref(false)
const summaryError = ref<string | null>(null)

/** 请求竞争序号 */
let listSeq = 0
let summarySeq = 0
let detailSeq = 0

/** 新增/编辑表单 */
const formVisible = ref(false)
const formMode = ref<'create' | 'edit'>('create')
const formBaseline = ref<ApplicationVO | null>(null)

/** 详情弹层 */
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref<string | null>(null)
const detailRecord = ref<ApplicationVO | null>(null)
/** 详情弹层打开时的记录 ID（用于断言迟到响应归属） */
let detailTargetId: string | null = null

/** 删除确认 */
const deleteVisible = ref(false)
const deleteTarget = ref<ApplicationVO | null>(null)
const deleting = ref(false)

async function loadList(): Promise<void> {
  const seq = ++listSeq
  listLoading.value = true
  listError.value = null
  try {
    const data: ApplicationPage = await listApplications({
      // 全部状态查询完全省略 status 参数（空串/空白会被后端 400 拒绝；
      // el-select clearable 清空后值为 undefined，与空串统一按“查全部”处理）
      status: !statusFilter.value ? null : statusFilter.value,
      current: page.current,
      size: page.size,
    })
    if (seq !== listSeq) return // 过期响应：不渲染、不复位（由最新请求管理状态）
    records.value = data.records
    page.total = data.total
  } catch (e) {
    if (seq !== listSeq) return // 过期请求的失败不覆盖新请求状态
    listError.value = e instanceof ApiError ? e.message : '加载投递列表失败，请稍后重试'
  } finally {
    if (seq === listSeq) listLoading.value = false
  }
}

async function loadSummary(): Promise<void> {
  const seq = ++summarySeq
  summaryLoading.value = true
  summaryError.value = null
  try {
    const data = await getApplicationSummary()
    if (seq !== summarySeq) return
    summary.value = data
    summaryLoaded.value = true
  } catch (e) {
    if (seq !== summarySeq) return
    summaryError.value = e instanceof ApiError ? e.message : '加载统计数据失败，请稍后重试'
  } finally {
    if (seq === summarySeq) summaryLoading.value = false
  }
}

function refreshAll(): void {
  loadList()
  loadSummary()
}

/** 筛选变化：重置页码后重查（旧请求自动被序号作废） */
function handleFilterChange(): void {
  page.current = 1
  loadList()
}

/** 空态清除筛选：回到全部记录 */
function clearStatusFilter(): void {
  statusFilter.value = ''
  handleFilterChange()
}

function handlePageChange(current: number): void {
  page.current = current
  loadList()
}

function handleSizeChange(size: number): void {
  page.size = size
  page.current = 1
  loadList()
}

/**
 * 删除当前页最后一条时的页码调整：非第一页则回退一页再查询。
 * 注意调用时机：删除成功后立即调用（此时 records.value 仍是删除前的旧列表，
 * 旧列表恰好只剩被删的这一条 → length === 1），在 refreshAll 刷新列表之前。
 */
function adjustPageAfterRemoval(): void {
  if (records.value.length === 1 && page.current > 1) {
    page.current -= 1
  }
}

// ---------- 表单（新增/编辑） ----------

function openCreate(): void {
  formMode.value = 'create'
  formBaseline.value = null
  formVisible.value = true
}

function openEdit(record: ApplicationVO): void {
  formMode.value = 'edit'
  formBaseline.value = record
  formVisible.value = true
}

function handleFormSuccess(): void {
  refreshAll()
}

// ---------- 详情 ----------

async function openDetail(id: string): Promise<void> {
  const seq = ++detailSeq
  detailTargetId = id
  detailRecord.value = null
  detailError.value = null
  detailLoading.value = true
  detailVisible.value = true
  try {
    const data = await getApplicationDetail(id)
    // 弹层已关闭 / 已切换到其他记录：迟到响应丢弃
    if (seq !== detailSeq || !detailVisible.value || detailTargetId !== id) return
    detailRecord.value = data
  } catch (e) {
    if (seq !== detailSeq || !detailVisible.value || detailTargetId !== id) return
    detailRecord.value = null
    detailError.value = e instanceof ApiError ? e.message : '加载详情失败'
  } finally {
    if (seq === detailSeq) detailLoading.value = false
  }
}

/** 关闭详情弹层：作废在途请求，迟到响应（含 404）零接触状态 */
function handleDetailClose(): void {
  detailSeq++
  detailTargetId = null
}

// ---------- 删除 ----------

function openDelete(record: ApplicationVO): void {
  deleteTarget.value = record
  deleteVisible.value = true
}

async function handleDeleteConfirm(): Promise<void> {
  const target = deleteTarget.value
  if (!target || deleting.value) return
  deleting.value = true
  try {
    await deleteApplication(target.id, target.version)
    ElMessage.success('删除成功')
    deleteVisible.value = false
    deleteTarget.value = null
    adjustPageAfterRemoval()
    refreshAll()
  } catch (e) {
    if (e instanceof ApiError) {
      if (e.isVersionConflict) {
        ElMessage.error('数据已被其他操作修改，请刷新后重试')
        deleteVisible.value = false
        refreshAll()
      } else if (e.httpStatus === 404) {
        // 记录已被其他操作删除（如另一窗口）：关闭弹窗并刷新列表与统计，
        // 移除页面上已失效的行，否则旧记录会一直残留
        ElMessage.info('该记录已被删除，列表已刷新')
        deleteVisible.value = false
        deleteTarget.value = null
        adjustPageAfterRemoval()
        refreshAll()
      } else {
        ElMessage.error(e.message)
      }
    } else {
      ElMessage.error('删除失败，请稍后重试')
    }
  } finally {
    deleting.value = false
  }
}

onMounted(() => {
  refreshAll()
})

const summaryCards = [
  { key: 'total', label: '总投递', accent: 'primary' },
  { key: 'PLANNED', label: '待投递', accent: 'info' },
  { key: 'APPLIED', label: '已投递', accent: 'applied' },
  { key: 'INTERVIEWING', label: '面试中', accent: 'warning' },
  { key: 'CLOSED', label: '已结束', accent: 'success' },
] as const

function cardValue(key: string): number {
  if (key === 'total') return summary.value.total
  return summary.value.counts[key as ApplicationStatus] ?? 0
}

/** el-table 插槽的 row 为宽泛的 DefaultRow 类型，入口处收窄为 ApplicationVO */
function asRecord(row: unknown): ApplicationVO {
  return row as ApplicationVO
}

function statusTagType(status: ApplicationStatus): 'info' | 'primary' | 'warning' | 'success' {
  switch (status) {
    case 'PLANNED':
      return 'info'
    case 'APPLIED':
      return 'primary'
    case 'INTERVIEWING':
      return 'warning'
    case 'CLOSED':
      return 'success'
  }
}

function formatDate(value: string | null): string {
  return value ?? '—'
}

/** 时间戳按用户本地时区（Asia/Shanghai）展示，不再暴露 UTC 原文 */
function formatDateTime(value: string): string {
  return formatLocalDateTime(value)
}
</script>

<template>
  <div class="application-page">
    <!-- 统计卡片（独立请求，失败可独立重试） -->
    <LoadingSkeleton v-if="summaryLoading && !summaryLoaded" :rows="2" label="正在读取投递统计" />
    <section v-else-if="summaryLoaded" class="summary-row" :aria-busy="summaryLoading">
      <div v-for="card in summaryCards" :key="card.key" class="summary-card" :data-accent="card.accent">
        <span class="summary-card__value">{{ cardValue(card.key) }}</span>
        <span class="summary-card__label">{{ card.label }}</span>
      </div>
    </section>
    <el-alert
      v-if="summaryError"
      :title="summaryError"
      type="error"
      show-icon
      :closable="false"
      style="margin-bottom: 12px"
    >
      <el-button size="small" @click="loadSummary">重试</el-button>
    </el-alert>

    <!-- 列表 -->
    <section class="panel">
      <div class="panel__toolbar">
        <div class="panel__filters">
          <el-select
            v-model="statusFilter"
            placeholder="全部状态"
            clearable
            style="width: 160px"
            @change="handleFilterChange"
          >
            <el-option
              v-for="status in APPLICATION_STATUSES"
              :key="status"
              :label="APPLICATION_STATUS_LABELS[status]"
              :value="status"
            />
          </el-select>
          <el-button :icon="Refresh" :loading="listLoading" @click="refreshAll">{{ listLoading ? '正在刷新…' : '刷新' }}</el-button>
        </div>
        <el-button type="primary" :icon="Plus" @click="openCreate">新增投递记录</el-button>
      </div>


      <AsyncState :loading="listLoading" :retain="records.length > 0" :error="listError" @retry="loadList">
      <el-table :data="records" stripe style="width: 100%">
        <el-table-column prop="companyName" label="公司" min-width="150" show-overflow-tooltip />
        <el-table-column prop="jobTitle" label="岗位" min-width="170" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ APPLICATION_STATUS_LABELS[row.status as ApplicationStatus] ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="计划投递" width="110">
          <template #default="{ row }">{{ formatDate(row.plannedDate) }}</template>
        </el-table-column>
        <el-table-column label="实际投递" width="110">
          <template #default="{ row }">{{ formatDate(row.appliedDate) }}</template>
        </el-table-column>
        <el-table-column label="面试日期" width="110">
          <template #default="{ row }">{{ formatDate(row.interviewDate) }}</template>
        </el-table-column>
        <el-table-column label="更新时间" width="200">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(asRecord(row).id)">详情</el-button>
            <el-button link type="primary" @click="openEdit(asRecord(row))">编辑</el-button>
            <el-button link type="danger" @click="openDelete(asRecord(row))">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty
            :description="statusFilter
              ? `当前筛选（${APPLICATION_STATUS_LABELS[statusFilter]}）没有投递记录，可清除筛选查看全部记录`
              : '暂无投递记录，点击右上角新增'"
          >
            <el-button v-if="statusFilter" type="primary" plain @click="clearStatusFilter">清除筛选</el-button>
          </el-empty>
        </template>
      </el-table>
      </AsyncState>

      <div class="panel__pagination">
        <el-pagination
          v-model:current-page="page.current"
          v-model:page-size="page.size"
          :total="page.total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </section>

    <!-- 新增/编辑表单（含 409 重新加载流程） -->
    <ApplicationForm v-model:visible="formVisible" :mode="formMode" :baseline="formBaseline" @success="handleFormSuccess" />

    <!-- 详情弹层（迟到响应由 detailSeq 丢弃） -->
    <ApplicationDetail v-model:visible="detailVisible" :loading="detailLoading" :record="detailRecord" :error="detailError" @retry="detailTargetId && openDetail(detailTargetId)" @update:visible="handleDetailClose" />

    <!-- 删除确认 -->
    <ApplicationDeleteConfirm v-model:visible="deleteVisible" :record="deleteTarget" :deleting="deleting" @confirm="handleDeleteConfirm" />
  </div>
</template>

<style scoped lang="scss">
.application-page {
  max-width: 1500px;
  margin: 0 auto;
}

.summary-row {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
  margin-bottom: 12px;
}

.summary-card {
  background: $ai-bg-card;
  border: 1px solid $ai-border-light;
  border-radius: $ai-radius-lg;
  box-shadow: $ai-shadow-card;
  padding: 18px 20px 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  border-top: 3px solid transparent;

  &[data-accent='primary'] { border-top-color: $ai-primary; }
  &[data-accent='info'] { border-top-color: $ai-text-tertiary; }
  &[data-accent='applied'] { border-top-color: var(--ai-accent-border, #2563eb); }
  &[data-accent='warning'] { border-top-color: $ai-warning; }
  &[data-accent='success'] { border-top-color: $ai-success; }

  &__value {
    font-size: 28px;
    font-weight: 700;
    color: $ai-text-primary;
  }

  &__label {
    font-size: 13px;
    color: $ai-text-secondary;
  }
}

.panel {
  background: $ai-bg-card;
  border: 1px solid var(--ai-border-light, #e8f0fa);
  border-radius: $ai-radius-lg;
  padding: 16px 18px;
}

.panel__toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.panel__filters {
  display: flex;
  gap: 10px;
  align-items: center;
}

.panel__pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

@media (max-width: 900px) {
  .summary-row {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>