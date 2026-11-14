<script setup lang="ts">
import { computed } from 'vue'
import {
  APPLICATION_STATUS_LABELS,
  type ApplicationStatus,
  type ApplicationVO,
} from '@/types/application'
import { cityLabels, formatDate as formatLocalDateTime } from '@/utils/format'

/**
 * 投递记录详情弹层（纯展示）。
 * 数据与加载状态由父组件管理（含快速切换记录、关闭弹层后的迟到响应丢弃）。
 */
const props = defineProps<{
  visible: boolean
  loading: boolean
  record: ApplicationVO | null
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
}>()

const dialogVisible = computed({
  get: () => props.visible,
  set: (value: boolean) => emit('update:visible', value),
})

const statusTagType = (status: ApplicationStatus | string): 'info' | 'primary' | 'warning' | 'success' => {
  switch (status) {
    case 'PLANNED':
      return 'info'
    case 'APPLIED':
      return 'primary'
    case 'INTERVIEWING':
      return 'warning'
    case 'CLOSED':
      return 'success'
    default:
      return 'info'
  }
}

function formatDateTime(value: string | null): string {
  if (!value) return '—'
  return formatLocalDateTime(value)
}

/** 城市编码优先展示中文名称，未收录编码原样展示并标注 */
const cityDisplay = computed(() => {
  const code = props.record?.cityCode
  if (!code) return '—'
  return cityLabels[code] ?? `编码 ${code}`
})
</script>

<template>
  <el-dialog v-model="dialogVisible" title="投递记录详情" width="620px" top="8vh">
    <div v-loading="loading" class="detail-body">
      <template v-if="record">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="公司名称" :span="2">{{ record.companyName }}</el-descriptions-item>
          <el-descriptions-item label="岗位名称" :span="2">{{ record.jobTitle }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(record.status)" effect="light">
              {{ APPLICATION_STATUS_LABELS[record.status as ApplicationStatus] ?? record.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="版本">v{{ record.version }}</el-descriptions-item>
          <el-descriptions-item label="城市">{{ cityDisplay }}</el-descriptions-item>
          <el-descriptions-item label="学历">{{ record.education ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="薪资">{{ record.salaryText ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="关联岗位">{{ record.jobId ?? '手动录入' }}</el-descriptions-item>
          <el-descriptions-item label="计划投递">{{ record.plannedDate ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="实际投递">{{ record.appliedDate ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="面试日期">{{ record.interviewDate ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="结束日期">{{ record.closedDate ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDateTime(record.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="更新时间" :span="2">{{ formatDateTime(record.updatedAt) }}</el-descriptions-item>
          <el-descriptions-item label="岗位简述" :span="2">{{ record.description ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">
            <span class="detail-note">{{ record.note ?? '—' }}</span>
          </el-descriptions-item>
        </el-descriptions>
      </template>
      <el-empty v-else-if="!loading" description="暂无数据" />
    </div>
    <template #footer>
      <el-button @click="dialogVisible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.detail-body {
  min-height: 120px;
}

.detail-note {
  white-space: pre-wrap;
  word-break: break-all;
}
</style>