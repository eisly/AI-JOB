<script setup lang="ts">
import LoadingSkeleton from '@/components/LoadingSkeleton.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Calendar, Cpu, InfoFilled, Reading, School } from '@element-plus/icons-vue'
import { ApiError } from '@/api/request'
import {
  getProfile,
  listSkills,
  savePreferences,
  type CandidateType,
  type ProfilePreferencesRequest,
  type ProfileVO,
  type SkillVO,
} from '@/api/profile'
import { findJobRoleByLabel, findJobRoleByValue, MAX_JOB_ROLE_LENGTH } from '@/config/jobRoles'
import JobRoleCascader from './components/JobRoleCascader.vue'

const router = useRouter()

/** U04：仅开发环境启用工作台 Mock 时才标识"演示数据"；真实账号页面不再误显演示标识 */
const isDemoMode = import.meta.env.DEV === true && import.meta.env.VITE_WORKBENCH_MOCK === 'true'

/** U05：最近一次保存成功时间——保存后提供持续可见的工作台/匹配入口，不弹框打断编辑上下文 */
const savedAt = ref('')

const CANDIDATE_OPTIONS: { value: CandidateType; label: string }[] = [
  { value: 'INTERNSHIP', label: '实习' },
  { value: 'CAMPUS', label: '校招' },
  { value: 'EXPERIENCED', label: '社招' },
]

const CITY_OPTIONS = [
  { value: '330100', label: '杭州' },
  { value: '110100', label: '北京' },
  { value: '310100', label: '上海' },
  { value: '440300', label: '深圳' },
  { value: '440100', label: '广州' },
  { value: '510100', label: '成都' },
  { value: '320100', label: '南京' },
  { value: '420100', label: '武汉' },
  { value: '610100', label: '西安' },
  { value: '500000', label: '重庆' },
  { value: '120100', label: '天津' },
  { value: '370200', label: '青岛' },
  { value: '320500', label: '苏州' },
  { value: '330200', label: '宁波' },
  { value: '350200', label: '厦门' },
]

const EDUCATION_LABELS: Record<string, string> = {
  HIGH_SCHOOL: '高中',
  ASSOCIATE: '大专',
  BACHELOR: '本科',
  MASTER: '硕士',
  DOCTOR: '博士',
}

const WEEKDAYS = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
const now = new Date()
const todayText = `${now.getFullYear()}年${now.getMonth() + 1}月${now.getDate()}日 ${WEEKDAYS[now.getDay()]}`

interface PreferencesForm {
  targetRole: string
  candidateType: CandidateType | ''
  cities: string[]
  minK: number | undefined
  maxK: number | undefined
}

const profile = ref<ProfileVO | null>(null)
const skills = ref<SkillVO[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')

const formRef = ref<FormInstance>()

const form = reactive<PreferencesForm>({
  targetRole: '',
  candidateType: '',
  cities: [],
  minK: undefined,
  maxK: undefined,
})

const validateSalary = (_rule: unknown, _value: unknown, callback: (error?: Error) => void) => {
  if (form.minK != null && form.maxK != null && form.minK > form.maxK) {
    callback(new Error('最高月薪不能低于最低月薪'))
  } else {
    callback()
  }
}

const rules: FormRules = {
  targetRole: [
    { required: true, message: '请选择或输入目标岗位', trigger: 'change' },
    { max: MAX_JOB_ROLE_LENGTH, message: `目标岗位不能超过 ${MAX_JOB_ROLE_LENGTH} 个字符`, trigger: 'change' },
  ],
  candidateType: [{ required: true, message: '请选择求职类型', trigger: 'change' }],
  cities: [
    { type: 'array', required: true, min: 1, max: 5, message: '请选择 1-5 个期望城市', trigger: 'change' },
  ],
  minK: [{ validator: validateSalary, trigger: 'change' }],
  maxK: [{ validator: validateSalary, trigger: 'change' }],
}


const cityOptions = computed(() => {
  const known = new Set(CITY_OPTIONS.map((item) => item.value))
  const extra = (profile.value?.cities ?? [])
    .filter((code) => !known.has(code))
    .map((code) => ({ value: code, label: code }))
  return [...CITY_OPTIONS, ...extra]
})

const educationText = computed(() => {
  const education = profile.value?.facts.education
  if (!education) return '待补充'
  return EDUCATION_LABELS[education] ?? education
})

const graduationText = computed(() => {
  const year = profile.value?.facts.graduationYear
  return year ? `${year}届` : '待补充'
})

const majorText = computed(() => profile.value?.facts.major || '待补充')

const warningsText = computed(() => (profile.value?.facts.warnings ?? []).join('；'))

const displaySkills = computed(() => {
  const facts = profile.value?.facts
  if (!facts) return []
  const names = new Map(skills.value.map((item) => [item.code, item.name]))
  return (facts.skills ?? []).map((item) => ({
    ...item,
    name: names.get(item.skillCode) ?? item.skillCode,
  }))
})

const fillForm = (data: ProfileVO) => {
  const storedRole = data.targetRole ?? ''
  const presetRole = storedRole
    ? (findJobRoleByValue(storedRole) ?? findJobRoleByLabel(storedRole))
    : undefined
  form.targetRole = presetRole ? presetRole.label : storedRole
  form.candidateType = data.candidateType ?? ''
  form.cities = [...(data.cities ?? [])]
  form.minK = data.minMonthlySalaryCents == null ? undefined : data.minMonthlySalaryCents / 100000
  form.maxK = data.maxMonthlySalaryCents == null ? undefined : data.maxMonthlySalaryCents / 100000
}

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const results = await Promise.allSettled([getProfile(), listSkills()] as const)
    const profileResult = results[0]
    const skillsResult = results[1]

    if (profileResult.status === 'fulfilled') {
      profile.value = profileResult.value
      fillForm(profileResult.value)
    } else {
      const reason = profileResult.reason
      error.value = reason instanceof Error ? reason.message : '画像加载失败'
    }

    if (skillsResult.status === 'fulfilled') skills.value = skillsResult.value
  } finally {
    loading.value = false
  }
}

const toCents = (value: number | undefined) => (value == null ? null : Math.round(value * 100000))

const handleRoleChange = (value: string | undefined) => {
  form.targetRole = (value ?? '').trim()
}

const handleSave = async () => {
  if (saving.value || !profile.value || !formRef.value) return

  saving.value = true
  try {
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return

    const targetRole = form.targetRole.trim()
    if (!targetRole) {
      ElMessage.warning('请选择或输入目标岗位')
      return
    }
    if (targetRole.length > MAX_JOB_ROLE_LENGTH) {
      ElMessage.warning(`目标岗位不能超过 ${MAX_JOB_ROLE_LENGTH} 个字符`)
      return
    }

    const payload: ProfilePreferencesRequest = {
      expectedProfileVersion: profile.value.profileVersion,
      targetRole: findJobRoleByLabel(targetRole)?.value ?? targetRole,
      candidateType: form.candidateType as CandidateType,
      cities: [...form.cities],
      minMonthlySalaryCents: toCents(form.minK),
      maxMonthlySalaryCents: toCents(form.maxK),
    }
    const updated = await savePreferences(payload)
    profile.value = updated
    fillForm(updated)
    savedAt.value = new Date().toLocaleString('zh-CN', { hour12: false })
    ElMessage.success('求职偏好已保存')
  } catch (err) {
    if (err instanceof ApiError && err.code === 40901) {
      ElMessage.warning('画像版本已变化，已为你刷新最新数据，请重新保存')
      await load()
    } else {
      ElMessage.error(err instanceof Error ? err.message : '保存失败')
    }
  } finally {
    saving.value = false
  }
}

const handleReset = () => {
  if (!profile.value) return
  fillForm(profile.value)
  formRef.value?.clearValidate()
  ElMessage.info('已恢复为上次保存的内容')
}

const formatDateTime = (value: string | null) => {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return date.toLocaleString('zh-CN', { hour12: false })
}

onMounted(load)
</script>

<template>
  <div class="profile-page">
    <div class="profile-page__meta">
      <span class="profile-page__date">{{ todayText }}</span>
      <el-tag v-if="isDemoMode" type="danger" effect="light" size="small">演示数据</el-tag>
      <span v-if="profile" class="profile-page__updated">
        画像更新于 {{ formatDateTime(profile.updatedAt) }}
      </span>
    </div>

    <header class="profile-page__header">
      <h1>求职画像</h1>
      <p>查看并完善你的求职目标，让 AI 为你匹配更合适的岗位和训练建议。</p>
    </header>

    <el-alert v-if="error" class="profile-page__alert" type="error" :title="error" show-icon :closable="false">
      <template #default>
        <el-button link type="primary" @click="load">重新加载</el-button>
      </template>
    </el-alert>

    <LoadingSkeleton v-if="loading && !profile" :rows="5" label="正在加载求职画像" />
    <div v-else-if="profile" class="profile-page__grid">
      <section class="panel">
        <div class="panel__header">
          <h2>求职偏好设置</h2>
        </div>
        <p class="panel__desc">设置你的求职目标，AI 将基于以下信息为你推荐合适的岗位并生成个性化的训练建议。</p>

        <div class="info-box">
          <el-icon><InfoFilled /></el-icon>
          <span>你可以随时修改，保存后将用于岗位匹配、简历优化和训练推荐。</span>
        </div>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          :disabled="loading"
          label-position="left"
          label-width="88px"
          class="preferences-form"
        >
          <el-form-item label="目标岗位" prop="targetRole">
            <JobRoleCascader v-model="form.targetRole" @change="handleRoleChange" />
            <div class="job-role__hint">支持搜索预设岗位，或直接输入自定义岗位后按 Enter</div>
          </el-form-item>

          <el-form-item label="求职类型" prop="candidateType">
            <el-select v-model="form.candidateType" placeholder="请选择求职类型" class="preferences-form__control">
              <el-option
                v-for="item in CANDIDATE_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="期望城市" prop="cities">
            <el-select
              v-model="form.cities"
              multiple
              filterable
              :multiple-limit="5"
              placeholder="可搜索并选择期望城市，最多 5 个"
              class="preferences-form__control"
            >
              <el-option v-for="item in cityOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>

          <el-form-item label="期望月薪" prop="minK">
            <div class="salary-range">
              <el-input-number
                v-model="form.minK"
                :min="0"
                :max="1000"
                :step="1"
                controls-position="right"
                placeholder="最低"
                class="salary-range__input"
              />
              <span class="salary-range__sep">—</span>
              <el-input-number
                v-model="form.maxK"
                :min="0"
                :max="1000"
                :step="1"
                controls-position="right"
                placeholder="最高"
                class="salary-range__input"
              />
              <span class="salary-range__unit">K/月</span>
            </div>
          </el-form-item>

          <div class="preferences-form__footer">
            <span class="preferences-form__version">当前画像版本：{{ profile.profileVersion }}</span>
            <div class="preferences-form__actions">
              <el-button type="primary" :loading="saving" @click="handleSave">{{ saving ? '保存中…' : '保存求职偏好' }}</el-button>
              <el-button @click="handleReset">重置</el-button>
            </div>
          </div>
          <!-- U05：保存成功后提供持续可见的工作台/岗位匹配入口，不弹框打断编辑上下文 -->
          <div v-if="savedAt" class="preferences-form__saved">
            <span>求职偏好已保存于 {{ savedAt }}</span>
            <div class="preferences-form__saved-actions">
              <el-button type="primary" plain size="small" @click="router.push('/jobs?tab=matches')">前往岗位匹配</el-button>
              <el-button size="small" @click="router.push('/dashboard')">前往工作台</el-button>
            </div>
          </div>
        </el-form>
      </section>

      <div class="profile-page__side">
        <section class="panel">
          <div class="panel__header">
            <h2>已确认的简历信息</h2>
            <el-tag type="info" effect="plain" size="small">来自已确认简历</el-tag>
          </div>
          <p class="panel__desc">以下信息来自你已确认的简历，如需修改请前往简历中心更新。</p>

          <div class="fact-list">
            <div class="fact-item">
              <span class="fact-item__icon"><el-icon><School /></el-icon></span>
              <span class="fact-item__label">学历</span>
              <span class="fact-item__value">{{ educationText }}</span>
            </div>
            <div class="fact-item">
              <span class="fact-item__icon"><el-icon><Calendar /></el-icon></span>
              <span class="fact-item__label">毕业时间</span>
              <span class="fact-item__value">{{ graduationText }}</span>
            </div>
            <div class="fact-item">
              <span class="fact-item__icon"><el-icon><Reading /></el-icon></span>
              <span class="fact-item__label">专业</span>
              <span class="fact-item__value">{{ majorText }}</span>
            </div>
          </div>

          <div class="info-box info-box--compact">
            <el-icon><InfoFilled /></el-icon>
            <span>这些信息已用于岗位匹配、简历优化和训练建议。如需修改，请前往简历中心更新。</span>
          </div>

          <el-alert
            v-if="warningsText"
            class="fact-warning"
            type="warning"
            :title="warningsText"
            show-icon
            :closable="false"
          />
        </section>

        <section class="panel">
          <div class="panel__header">
            <h2>技能掌握情况</h2>
            <el-tag type="info" effect="plain" size="small">来自已确认简历</el-tag>
          </div>
          <p class="panel__desc">当前仅展示已确认简历中的技能事实，技能名称来自技能目录。</p>

          <div v-if="displaySkills.length" class="skill-list">
            <div v-for="skill in displaySkills" :key="skill.skillCode" class="skill-item">
              <span class="skill-item__icon"><el-icon><Cpu /></el-icon></span>
              <div class="skill-item__body">
                <div class="skill-item__name">{{ skill.name }}</div>
                <div class="skill-item__claim">{{ skill.claim || '简历中提及该技能' }}</div>
              </div>
              <el-tag :type="skill.confirmed ? 'success' : 'info'" effect="light" size="small">
                {{ skill.confirmed ? '已确认' : '待确认' }}
              </el-tag>
            </div>
          </div>

          <el-empty v-else description="暂无已确认技能" :image-size="56" />

          <div class="info-box info-box--compact">
            <el-icon><InfoFilled /></el-icon>
            <span>技能掌握情况基于已确认简历内容识别；如有缺失可暂不填写，后续可通过训练逐步补充。</span>
          </div>
        </section>
      </div>
    </div>

    <el-empty v-else-if="!loading && !error" description="暂未获取到求职画像">
      <el-button type="primary" @click="load">重新加载</el-button>
    </el-empty>
  </div>
</template>

<style scoped lang="scss">
.profile-page {
  max-width: 1280px;
  margin: 0 auto;
  padding: 4px 8px 28px;

  &__meta {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
    margin-bottom: 12px;
  }

  &__date {
    font-size: 13px;
    color: $ai-text-secondary;
  }

  &__updated {
    margin-left: auto;
    font-size: 12px;
    color: $ai-text-tertiary;
  }

  &__header {
    h1 {
      margin: 0 0 6px;
      font-size: 28px;
      font-weight: 700;
      color: $ai-text-primary;
    }

    p {
      margin: 0 0 18px;
      font-size: 14px;
      color: $ai-text-secondary;
    }
  }

  &__alert {
    margin-bottom: 16px;
  }

  &__grid {
    display: grid;
    grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
    gap: 20px;
    align-items: start;
  }

  &__side {
    display: flex;
    flex-direction: column;
    gap: 20px;
  }
}

.panel {
  background-color: $ai-bg-card;
  border-radius: $ai-radius-lg;
  box-shadow: $ai-shadow-card;
  padding: 24px;

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 8px;

    h2 {
      margin: 0;
      font-size: 18px;
      font-weight: 700;
      color: $ai-text-primary;
    }
  }

  &__desc {
    margin: 0 0 16px;
    font-size: 13px;
    line-height: 1.6;
    color: $ai-text-secondary;
  }
}

.info-box {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  margin-bottom: 18px;
  border-radius: $ai-radius;
  background-color: $ai-primary-light;
  color: var(--ai-text-secondary, #3b5b8c);
  font-size: 13px;
  line-height: 1.6;

  .el-icon {
    color: $ai-primary;
    margin-top: 2px;
    flex-shrink: 0;
  }

  &--compact {
    margin: 16px 0 0;
  }
}

.preferences-form {
  &__control {
    width: 100%;
  }

  &__footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    flex-wrap: wrap;
    margin-top: 28px;
  }

  &__version {
    font-size: 12px;
    color: $ai-text-tertiary;
  }

  &__actions {
    display: flex;
    gap: 12px;
  }
}

.job-role__hint {
  margin-top: 4px;
  font-size: 12px;
  color: $ai-text-tertiary;
}

.salary-range {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;

  &__input {
    width: 130px;
  }

  &__sep {
    color: $ai-text-tertiary;
  }

  &__unit {
    color: $ai-text-secondary;
    font-size: 13px;
  }
}

.fact-list {
  display: flex;
  flex-direction: column;
}

.fact-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 0;
  border-bottom: 1px solid $ai-border-light;

  &:last-child {
    border-bottom: 0;
  }

  &__icon {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    border-radius: 8px;
    background-color: $ai-primary-light;
    color: $ai-primary;
    flex-shrink: 0;
  }

  &__label {
    width: 72px;
    font-size: 13px;
    color: $ai-text-secondary;
  }

  &__value {
    font-size: 14px;
    font-weight: 500;
    color: $ai-text-primary;
  }
}

.fact-warning {
  margin-top: 12px;
}

.skill-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.skill-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid $ai-border-light;
  border-radius: $ai-radius;

  &__icon {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    border-radius: 8px;
    background-color: $ai-bg-light;
    color: $ai-primary;
    flex-shrink: 0;
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__name {
    font-size: 14px;
    font-weight: 600;
    color: $ai-text-primary;
  }

  &__claim {
    margin-top: 2px;
    font-size: 12px;
    color: $ai-text-secondary;
  }
}

@media (max-width: 1100px) {
  .profile-page__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 700px) {
  .profile-page {
    padding: 0 4px 20px;
  }

  .panel {
    padding: 18px;
    min-width: 0;
  }

  .preferences-form :deep(.el-form-item) {
    display: block;
  }

  .preferences-form :deep(.el-form-item__label) {
    justify-content: flex-start;
    width: auto !important;
  }

  .preferences-form :deep(.el-form-item__content) {
    margin-left: 0 !important;
    min-width: 0;
  }

  .salary-range {
    flex-wrap: wrap;
  }

  .salary-range__input {
    width: 96px;
    min-width: 0;
  }

  .preferences-form__actions {
    flex-wrap: wrap;
  }

  .preferences-form__actions :deep(.el-button + .el-button) {
    margin-left: 0;
  }
}
</style>
