<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { profileApi } from '@/api/profile'
import { ApiError } from '@/api/request'
import type { ProfileVO } from '@/api/types'
import AsyncState from '@/components/AsyncState.vue'
import { candidateTypeLabels, cityLabels, educationLabels, errorMessage, roleLabels } from '@/utils/format'

const loading = ref(false)
const saving = ref(false)
const loadError = ref<unknown>(null)
const saveError = ref('')
const profile = ref<ProfileVO | null>(null)
const form = reactive({
  targetRole: 'JAVA_BACKEND' as ProfileVO['targetRole'],
  candidateType: 'CAMPUS' as ProfileVO['candidateType'],
  cities: [] as string[],
  minSalary: null as number | null,
  maxSalary: null as number | null,
})

function assignProfile(value: ProfileVO) {
  profile.value = value
  form.targetRole = value.targetRole
  form.candidateType = value.candidateType
  form.cities = [...value.cities]
  form.minSalary = value.minMonthlySalaryCents === null ? null : value.minMonthlySalaryCents / 100
  form.maxSalary = value.maxMonthlySalaryCents === null ? null : value.maxMonthlySalaryCents / 100
}

async function load() {
  loading.value = true
  loadError.value = null
  try {
    assignProfile(await profileApi.get())
  } catch (reason) {
    loadError.value = reason
  } finally {
    loading.value = false
  }
}

async function save() {
  saveError.value = ''
  if (!profile.value || !form.cities.length) {
    saveError.value = '请至少选择一个目标城市'
    return
  }
  if (form.minSalary !== null && form.maxSalary !== null && form.maxSalary < form.minSalary) {
    saveError.value = '月薪上限不能低于下限'
    return
  }
  saving.value = true
  try {
    const result = await profileApi.savePreferences({
      expectedProfileVersion: profile.value.profileVersion,
      targetRole: form.targetRole,
      candidateType: form.candidateType,
      cities: form.cities,
      minMonthlySalaryCents: form.minSalary === null ? null : Math.round(form.minSalary * 100),
      maxMonthlySalaryCents: form.maxSalary === null ? null : Math.round(form.maxSalary * 100),
    })
    assignProfile(result)
    ElMessage.success(`求职偏好已保存，画像版本 ${result.profileVersion}`)
  } catch (reason) {
    saveError.value = errorMessage(reason)
    if (reason instanceof ApiError && reason.status === 409) await load()
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div><h1>求职画像</h1><p>偏好用于岗位匹配；简历事实需在简历中心解析并确认。</p></div>
      <el-button type="primary" plain @click="$router.push('/job-match')">前往岗位匹配</el-button>
    </div>
    <AsyncState :loading="loading" :error="loadError" @retry="load">
      <section v-if="profile" class="panel">
        <div class="panel__heading"><h2>求职偏好</h2><el-tag>画像版本 {{ profile.profileVersion }}</el-tag></div>
        <el-alert v-if="saveError" :title="saveError" type="error" show-icon :closable="false" class="profile-error" />
        <el-form label-position="top" class="form-grid" @submit.prevent="save">
          <el-form-item label="目标方向" required>
            <el-select v-model="form.targetRole" class="full-width">
              <el-option v-for="(label, value) in roleLabels" :key="value" :label="label" :value="value" />
            </el-select>
          </el-form-item>
          <el-form-item label="求职类型" required>
            <el-select v-model="form.candidateType" class="full-width">
              <el-option v-for="(label, value) in candidateTypeLabels" :key="value" :label="label" :value="value" />
            </el-select>
          </el-form-item>
          <el-form-item label="目标城市（最多 5 个）" required class="profile-wide">
            <el-select v-model="form.cities" multiple :multiple-limit="5" class="full-width" placeholder="请选择城市">
              <el-option v-for="(label, code) in cityLabels" :key="code" :label="`${label} ${code}`" :value="code" />
            </el-select>
          </el-form-item>
          <el-form-item label="税前月薪下限（元）">
            <el-input-number v-model="form.minSalary" :min="0" :step="1000" class="full-width" placeholder="可不填" />
          </el-form-item>
          <el-form-item label="税前月薪上限（元）">
            <el-input-number v-model="form.maxSalary" :min="0" :step="1000" class="full-width" placeholder="可不填" />
          </el-form-item>
        </el-form>
        <div class="actions"><el-button type="primary" :loading="saving" @click="save">保存偏好</el-button><el-button @click="load">还原已保存内容</el-button></div>
      </section>
      <section v-if="profile" class="panel">
        <div class="panel__heading"><h2>当前确认事实</h2><el-button @click="$router.push('/resume')">管理简历</el-button></div>
        <el-empty v-if="!profile.activeResumeVersionId" description="尚未确认简历，确认后才能创建岗位匹配">
          <el-button type="primary" @click="$router.push('/resume')">上传并确认简历</el-button>
        </el-empty>
        <el-descriptions v-else :column="2" border>
          <el-descriptions-item label="生效简历版本">{{ profile.activeResumeVersionId }}</el-descriptions-item>
          <el-descriptions-item label="学历">{{ profile.facts.education ? educationLabels[profile.facts.education] : '未填写' }}</el-descriptions-item>
          <el-descriptions-item label="毕业年份">{{ profile.facts.graduationYear ?? '未填写' }}</el-descriptions-item>
          <el-descriptions-item label="全职经验">{{ profile.facts.experienceMonths === null ? '未填写' : `${profile.facts.experienceMonths} 个月` }}</el-descriptions-item>
          <el-descriptions-item label="专业">{{ profile.facts.major || '未填写' }}</el-descriptions-item>
          <el-descriptions-item label="技能">{{ profile.facts.skills.map(item => item.skillCode).join('、') || '未填写' }}</el-descriptions-item>
        </el-descriptions>
      </section>
    </AsyncState>
  </div>
</template>

<style scoped lang="scss">
.profile-error { margin-bottom: 16px; }
.profile-wide { grid-column: 1 / -1; }
.actions { margin-top: 8px; }
@media(max-width:700px){.profile-wide{grid-column:auto}}
</style>