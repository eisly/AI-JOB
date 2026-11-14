<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { ApiError } from '@/api/request'
import { createApplication, getApplicationDetail, updateApplication } from '@/api/application'
import { cityLabels } from '@/utils/format'
import {
  APPLICATION_STATUSES,
  APPLICATION_STATUS_LABELS,
  buildUpdatePayload,
  type ApplicationCreatePayload,
  type ApplicationFormFieldValues,
  type ApplicationStatus,
  type ApplicationVO,
} from '@/types/application'

/**
 * 新增/编辑共用表单（el-dialog）。
 * - 新增：手动录入闭环，jobId 为内部对接字段默认不采集、不发送
 * - 编辑：差量提交（未修改字段不发送、清空可空字段发送 null）；jobId 不可修改不展示
 * - 409 冲突：保留用户输入，弹确认框；确认后按记录 ID 调详情接口取最新完整记录，
 *   同步重置基线与表单（替换输入前已明确提示）；取消或加载失败保留原输入。
 *   绝不使用 error.details.currentVersion 顶替 expectedVersion 直接重提交。
 */
const props = defineProps<{
  visible: boolean
  mode: 'create' | 'edit'
  /** 编辑模式的基线记录（进入时快照） */
  baseline: ApplicationVO | null
  plan?: ApplicationCreatePayload | null
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'success', record: ApplicationVO): void
}>()

const dialogVisible = computed({
  get: () => props.visible,
  set: (value: boolean) => emit('update:visible', value),
})

const formRef = ref<FormInstance>()
const submitting = ref(false)
/** 本地基线（409 重新加载后同步重置） */
const localBaseline = ref<ApplicationVO | null>(null)
/** 409 冲突确认框 */
const conflictVisible = ref(false)
const reloading = ref(false)
/** 同一岗位重复添加确认框（DUPLICATE_APPLICATION） */
const duplicateVisible = ref(false)
const duplicateExistingCount = ref(0)
let pendingCreatePayload: ApplicationCreatePayload | null = null

/** 城市候选：优先展示已有编码映射，允许输入未收录编码 */
const cityOptions = computed(() => Object.entries(cityLabels).map(([code, name]) => ({ code, name })))

const form = reactive<ApplicationFormFieldValues>({
  companyName: '',
  jobTitle: '',
  cityCode: '',
  education: '',
  salaryText: '',
  description: '',
  status: 'PLANNED',
  plannedDate: '',
  appliedDate: '',
  interviewDate: '',
  closedDate: '',
  note: '',
})

const rules: FormRules = {
  companyName: [
    { required: true, whitespace: true, message: '请输入公司名称', trigger: 'blur' },
    { max: 100, message: '公司名称不能超过 100 字符', trigger: 'blur' },
  ],
  jobTitle: [
    { required: true, whitespace: true, message: '请输入岗位名称', trigger: 'blur' },
    { max: 100, message: '岗位名称不能超过 100 字符', trigger: 'blur' },
  ],
  cityCode: [{ max: 12, message: '城市编码不能超过 12 字符', trigger: 'blur' }],
  education: [{ max: 50, message: '学历不能超过 50 字符', trigger: 'blur' }],
  salaryText: [{ max: 100, message: '薪资不能超过 100 字符', trigger: 'blur' }],
  description: [{ max: 1000, message: '岗位简述不能超过 1000 字符', trigger: 'blur' }],
  note: [{ max: 2000, message: '备注不能超过 2000 字符', trigger: 'blur' }],
}

const isEdit = computed(() => props.mode === 'edit')
const title = computed(() => (isEdit.value ? '编辑投递记录' : props.plan ? '添加投递计划' : '新增投递记录'))

function fromVO(vo: ApplicationVO): ApplicationFormFieldValues {
  return {
    companyName: vo.companyName ?? '',
    jobTitle: vo.jobTitle ?? '',
    cityCode: vo.cityCode ?? '',
    education: vo.education ?? '',
    salaryText: vo.salaryText ?? '',
    description: vo.description ?? '',
    status: vo.status,
    plannedDate: vo.plannedDate ?? '',
    appliedDate: vo.appliedDate ?? '',
    interviewDate: vo.interviewDate ?? '',
    closedDate: vo.closedDate ?? '',
    note: vo.note ?? '',
  }
}

/** P02：表单填充期间抑制"日期已清除"提示，避免打开编辑弹窗时误报 */
let suppressDateClearNotice = false

watch(
  () => [props.visible, props.baseline, props.plan] as const,
  ([visible]) => {
    if (visible) {
      suppressDateClearNotice = true
      conflictVisible.value = false
      localBaseline.value = props.baseline ? { ...props.baseline } : null
      if (props.mode === 'edit' && props.baseline) {
        Object.assign(form, fromVO(props.baseline))
      } else {
        Object.assign(form, {
          companyName: props.plan?.companyName ?? '',
          jobTitle: props.plan?.jobTitle ?? '',
          cityCode: props.plan?.cityCode ?? '',
          education: props.plan?.education ?? '',
          salaryText: props.plan?.salaryText ?? '',
          description: props.plan?.description ?? '',
          status: 'PLANNED' as ApplicationStatus,
          plannedDate: '',
          appliedDate: '',
          interviewDate: '',
          closedDate: '',
          note: '',
        })
      }
      formRef.value?.clearValidate()
      void nextTick(() => {
        suppressDateClearNotice = false
      })
    }
  },
  { immediate: true },
)

watch(
  () => form.status,
  (status) => {
    // 状态-日期一致性：待投递状态不允许携带实际投递日期，切换时联动清空并明确告知（P02）
    if (status === 'PLANNED' && form.appliedDate !== '') {
      form.appliedDate = ''
      if (!suppressDateClearNotice) {
        ElMessage.info('状态改为待投递时，已自动清除原实际投递日期；如需保留日期请改选已投递')
      }
    }
  },
)

function buildCreatePayload() {
  return {
    ...(props.plan?.jobId ? { jobId: props.plan.jobId } : {}),
    companyName: form.companyName.trim(),
    jobTitle: form.jobTitle.trim(),
    cityCode: form.cityCode.trim() === '' ? null : form.cityCode.trim(),
    education: form.education.trim() === '' ? null : form.education.trim(),
    salaryText: form.salaryText.trim() === '' ? null : form.salaryText.trim(),
    description: form.description.trim() === '' ? null : form.description.trim(),
    status: props.plan ? 'PLANNED' as const : form.status,
    plannedDate: form.plannedDate === '' ? null : form.plannedDate,
    appliedDate: form.appliedDate === '' ? null : form.appliedDate,
    interviewDate: form.interviewDate === '' ? null : form.interviewDate,
    closedDate: form.closedDate === '' ? null : form.closedDate,
    note: form.note.trim() === '' ? null : form.note.trim(),
  }
}

async function handleSubmit() {
  // 防重复提交守卫：进入即同步置位 submitting，覆盖 await validate 挂起窗口。
  // 若仅在 validate 之后置位，同一事件循环内的同步双击会在首次调用挂起时
  // 二次进入（此时 submitting 仍为 false），导致重复 POST/PATCH。
  if (submitting.value) return
  submitting.value = true
  try {
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid) return

    if (isEdit.value) {
      const baseline = localBaseline.value
      if (!baseline) return
      const payload = buildUpdatePayload(baseline, form)
      if (!payload) {
        ElMessage.info('未修改任何字段')
        return
      }
      const saved = await updateApplication(baseline.id, payload)
      ElMessage.success('修改成功')
      emit('success', saved)
      dialogVisible.value = false
    } else {
      const payload = buildCreatePayload()
      const saved = await createApplication(payload)
      ElMessage.success('新增成功')
      emit('success', saved)
      dialogVisible.value = false
    }
  } catch (e) {
    handleRequestError(e)
  } finally {
    submitting.value = false
  }
}

/** 用户确认重复添加：携带 confirmDuplicate=true 重新提交，形成独立记录 */
async function handleConfirmDuplicate() {
  if (!pendingCreatePayload) return
  duplicateVisible.value = false
  submitting.value = true
  try {
    const saved = await createApplication({ ...pendingCreatePayload, confirmDuplicate: true })
    ElMessage.success('已再次添加，形成独立投递记录')
    emit('success', saved)
    dialogVisible.value = false
  } catch (e) {
    handleRequestError(e)
  } finally {
    submitting.value = false
  }
}

/** 用户取消重复添加：关闭确认框，保留表单输入 */
function handleCancelDuplicate() {
  duplicateVisible.value = false
  pendingCreatePayload = null
}

function handleRequestError(e: unknown) {
  if (e instanceof ApiError) {
    if (e.isVersionConflict) {
      // 保留用户输入，交由确认框决定是否重新加载
      conflictVisible.value = true
      return
    }
    if (e.status === 409 && e.key === 'DUPLICATE_APPLICATION') {
      // 同一岗位已有投递记录：保留输入，交由确认框决定是否再次添加
      pendingCreatePayload = buildCreatePayload()
      const details = e.details as { existingCount?: number } | undefined
      duplicateExistingCount.value = details?.existingCount ?? 1
      duplicateVisible.value = true
      return
    }
    ElMessage.error(e.message)
    return
  }
  ElMessage.error('请求失败，请稍后重试')
}

/** 409 后用户确认“重新加载”：按记录 ID 调详情接口取最新完整记录，重置基线与表单 */
async function handleReloadLatest() {
  const baseline = localBaseline.value
  if (!baseline) return
  reloading.value = true
  try {
    const latest = await getApplicationDetail(baseline.id)
    localBaseline.value = latest
    Object.assign(form, fromVO(latest))
    conflictVisible.value = false
    ElMessage.success('已加载最新数据，请基于最新内容修改后重新提交')
  } catch (e) {
    // 加载失败：保留原输入与原基线
    const message = e instanceof ApiError ? e.message : '网络异常'
    ElMessage.error(`重新加载失败：${message}，当前输入已保留`)
  } finally {
    reloading.value = false
  }
}

/** 用户取消重新加载：保留原输入，仅关闭确认框 */
function handleCancelReload() {
  conflictVisible.value = false
}

function handleClosed() {
  formRef.value?.resetFields()
}
</script>

<template>
  <el-dialog
    v-model="dialogVisible"
    :title="title"
    width="640px"
    top="8vh"
    :close-on-click-modal="false"
    @closed="handleClosed"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="92px">
      <el-form-item label="公司名称" prop="companyName">
        <el-input v-model="form.companyName" maxlength="100" show-word-limit placeholder="如：云启智能" />
      </el-form-item>
      <el-form-item label="岗位名称" prop="jobTitle">
        <el-input v-model="form.jobTitle" maxlength="100" show-word-limit placeholder="如：Java 后端开发工程师" />
      </el-form-item>
      <el-form-item label="城市" prop="cityCode">
        <el-select
          v-model="form.cityCode"
          filterable
          allow-create
          clearable
          placeholder="搜索或输入城市（可选）"
          style="width: 260px"
        >
          <el-option
            v-for="city in cityOptions"
            :key="city.code"
            :label="city.name"
            :value="city.code"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="学历" prop="education">
        <el-input v-model="form.education" maxlength="50" placeholder="如：本科（可选）" />
      </el-form-item>
      <el-form-item label="薪资" prop="salaryText">
        <el-input v-model="form.salaryText" maxlength="100" placeholder="如：10–16K（可选）" />
      </el-form-item>
      <el-form-item label="岗位简述" prop="description">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          maxlength="1000"
          show-word-limit
          placeholder="岗位职责或亮点（可选）"
        />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="form.status" :disabled="!!plan" style="width: 200px">
          <el-option
            v-for="status in APPLICATION_STATUSES"
            :key="status"
            :label="APPLICATION_STATUS_LABELS[status]"
            :value="status"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="计划投递" prop="plannedDate">
        <el-date-picker v-model="form.plannedDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期（可选）" style="width: 200px" />
      </el-form-item>
      <el-form-item label="实际投递" prop="appliedDate">
        <el-date-picker
          v-model="form.appliedDate"
          type="date"
          value-format="YYYY-MM-DD"
          :disabled="form.status === 'PLANNED'"
          placeholder="待投递状态不可填写"
          style="width: 200px"
        />
      </el-form-item>
      <el-form-item label="面试日期" prop="interviewDate">
        <el-date-picker v-model="form.interviewDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期（可选）" style="width: 200px" />
      </el-form-item>
      <el-form-item label="结束日期" prop="closedDate">
        <el-date-picker v-model="form.closedDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期（可选）" style="width: 200px" />
      </el-form-item>
      <el-form-item label="备注" prop="note">
        <el-input
          v-model="form.note"
          type="textarea"
          :rows="3"
          maxlength="2000"
          show-word-limit
          placeholder="备注信息（可选）"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="submitting" @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">
        {{ submitting ? '提交中…' : '保存' }}
      </el-button>
    </template>

    <!-- 409 冲突确认框：重新加载将替换当前输入，需用户明确确认 -->
    <el-dialog
      v-model="conflictVisible"
      title="数据版本冲突"
      width="420px"
      append-to-body
      :close-on-click-modal="false"
    >
      <p style="line-height: 1.7; margin: 0">
        该记录已被其他操作修改，当前表单输入已保留。<br />
        选择「重新加载」将获取最新记录并<strong>替换当前输入</strong>；取消则继续保留当前输入。
      </p>
      <template #footer>
        <el-button :disabled="reloading" @click="handleCancelReload">保留我的输入</el-button>
        <el-button type="primary" :loading="reloading" @click="handleReloadLatest">
          {{ reloading ? '加载中…' : '重新加载最新数据' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 同一岗位重复添加确认框：确认后创建独立的新投递记录 -->
    <el-dialog
      v-model="duplicateVisible"
      title="该岗位已有投递记录"
      width="440px"
      append-to-body
      :close-on-click-modal="false"
    >
      <p style="line-height: 1.7; margin: 0">
        该岗位已有 {{ duplicateExistingCount }} 条投递记录，可在投递列表中查看进度。<br />
        如需再次投递（如不同批次），选择「仍然添加」将创建<strong>独立的新记录</strong>；取消则保留当前输入不做保存。
      </p>
      <template #footer>
        <el-button @click="handleCancelDuplicate">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleConfirmDuplicate">
          {{ submitting ? '提交中…' : '仍然添加' }}
        </el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>