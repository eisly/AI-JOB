<script setup lang="ts">
import { computed } from 'vue'
import { APPLICATION_STATUS_LABELS, type ApplicationStatus, type ApplicationVO } from '@/types/application'

/**
 * 删除确认弹层：展示待删记录关键信息与版本，防误删。
 * 删除操作本身（含 expectedVersion query 参数）由父组件执行。
 */
const props = defineProps<{
  visible: boolean
  record: ApplicationVO | null
  deleting: boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'confirm'): void
}>()

const dialogVisible = computed({
  get: () => props.visible,
  set: (value: boolean) => emit('update:visible', value),
})
</script>

<template>
  <el-dialog
    v-model="dialogVisible"
    title="删除投递记录"
    width="440px"
    :close-on-click-modal="false"
    append-to-body
  >
    <template v-if="record">
      <p style="line-height: 1.8; margin: 0">
        确认删除以下投递记录？此操作为<strong>物理删除</strong>，不可恢复。
      </p>
      <el-descriptions :column="1" border style="margin-top: 14px">
        <el-descriptions-item label="公司">{{ record.companyName }}</el-descriptions-item>
        <el-descriptions-item label="岗位">{{ record.jobTitle }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          {{ APPLICATION_STATUS_LABELS[record.status as ApplicationStatus] ?? record.status }}（版本
          v{{ record.version }}）
        </el-descriptions-item>
      </el-descriptions>
    </template>
    <template #footer>
      <el-button :disabled="deleting" @click="dialogVisible = false">取消</el-button>
      <el-button type="danger" :loading="deleting" @click="emit('confirm')">
        {{ deleting ? '删除中…' : '确认删除' }}
      </el-button>
    </template>
  </el-dialog>
</template>