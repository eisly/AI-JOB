<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Aim, ArrowRight, Briefcase, Clock, Location, School, TrendCharts } from '@element-plus/icons-vue'
import { profileApi } from '@/api/profile'
import { learningApi } from '@/api/learning'
import { jobApi } from '@/api/jobs'
import { growthApi } from '@/api/growth'
import { taskApi } from '@/api/tasks'
import type { GrowthVO, LearningPlanVO, MatchItemVO, PlanItemVO, ProfileVO, TaskVO } from '@/api/types'
import AsyncState from '@/components/AsyncState.vue'
import { candidateTypeLabels, cityLabels, commonStatusLabels, displayScore, formatDate, roleLabels, taskStatusLabels, taskTypeLabels } from '@/utils/format'

const profile = ref<ProfileVO | null>(null)
const plans = ref<LearningPlanVO[]>([])
const targetJobs = ref<MatchItemVO[]>([])
const growth = ref<GrowthVO | null>(null)
const recentTasks = ref<TaskVO[]>([])
const loading = ref(false)
const profileError = ref<unknown>(null)
const plansError = ref<unknown>(null)
const jobsError = ref<unknown>(null)
const growthError = ref<unknown>(null)
const tasksError = ref<unknown>(null)

const nextPlan = computed(() => plans.value.find(plan => plan.status === 'ACTIVE' && plan.items.some(item => item.status !== 'COMPLETED')) || null)
const nextItem = computed<PlanItemVO | null>(() => nextPlan.value?.items.find(item => item.status !== 'COMPLETED') || null)
const completedItems = computed(() => plans.value.reduce((sum, plan) => sum + plan.items.filter(item => item.status === 'COMPLETED').length, 0))
const totalItems = computed(() => plans.value.reduce((sum, plan) => sum + plan.items.length, 0))
const evidenceSamples = computed(() => growth.value?.skills.reduce((sum, skill) => sum + skill.sampleCount, 0) || 0)
const evaluatedSkills = computed(() => growth.value?.skills.filter(skill => skill.score !== null).length || 0)
const profileCities = computed(() => profile.value?.cities.map(code => cityLabels[code] || code).join('、') || '未设置')

function kindLabel(kind: PlanItemVO['kind']) {
  return ({ READING: '阅读', PRACTICE: '答题训练', INTERVIEW: '模拟面试' } as Record<string, string>)[kind]
}

async function loadTargetJobs() {
  const page = await jobApi.listMatches({ current: 1, size: 1 })
  const latest = page.records[0]
  if (!latest || latest.status !== 'READY') return []
  const detail = await jobApi.getMatch(latest.id)
  return detail.items.filter(item => item.eligibility.status !== 'FAIL').slice(0, 3)
}

async function loadDashboard() {
  if (loading.value) return
  loading.value = true
  profileError.value = null
  plansError.value = null
  jobsError.value = null
  growthError.value = null
  tasksError.value = null
  const [profileResult, plansResult, jobsResult, growthResult, tasksResult] = await Promise.allSettled([
    profileApi.get(),
    learningApi.listPlans({ current: 1, size: 20 }),
    loadTargetJobs(),
    growthApi.get(),
    taskApi.list({ current: 1, size: 6 }),
  ])
  if (profileResult.status === 'fulfilled') profile.value = profileResult.value
  else profileError.value = profileResult.reason
  if (plansResult.status === 'fulfilled') plans.value = plansResult.value.records
  else plansError.value = plansResult.reason
  if (jobsResult.status === 'fulfilled') targetJobs.value = jobsResult.value
  else jobsError.value = jobsResult.reason
  if (growthResult.status === 'fulfilled') growth.value = growthResult.value
  else growthError.value = growthResult.reason
  if (tasksResult.status === 'fulfilled') recentTasks.value = tasksResult.value.records
  else tasksError.value = tasksResult.reason
  loading.value = false
}

onMounted(loadDashboard)
</script>

<template>
  <div class="dashboard">
    <section class="welcome">
      <div class="welcome__top">
        <div>
          <p>你好，欢迎回来！</p>
          <h1>今天，继续你的求职准备</h1>
          <p class="welcome__description">工作台数据来自当前求职画像、训练计划、匹配结果、成长摘要和任务记录。</p>
        </div>
        <el-button :loading="loading" @click="loadDashboard">刷新工作台</el-button>
      </div>
      <AsyncState :loading="loading && !profile" :error="profileError" @retry="loadDashboard">
        <div v-if="profile" class="goals">
          <span><el-icon><Briefcase /></el-icon>求职方向 <strong>{{ roleLabels[profile.targetRole] || profile.targetRole }}</strong></span>
          <span><el-icon><Location /></el-icon>目标城市 <strong>{{ profileCities }}</strong></span>
          <span><el-icon><School /></el-icon>求职阶段 <strong>{{ candidateTypeLabels[profile.candidateType] || profile.candidateType }}</strong></span>
        </div>
      </AsyncState>
    </section>

    <div class="overview">
      <section class="panel task-panel">
        <div class="panel__heading"><h2><el-icon><Aim /></el-icon>你的下一项任务</h2><router-link to="/training">查看训练计划 <el-icon><ArrowRight /></el-icon></router-link></div>
        <AsyncState :loading="loading && !plans.length" :error="plansError" :empty="!nextPlan || !nextItem" empty-text="暂无待完成训练任务" empty-description="可先从有效岗位匹配结果创建训练计划。" @retry="loadDashboard">
          <div v-if="nextPlan && nextItem" class="next-task">
            <el-tag type="primary" effect="plain">{{ kindLabel(nextItem.kind) }}</el-tag>
            <h3>{{ nextItem.title }}</h3>
            <p>{{ nextPlan.title }} · {{ nextItem.skillCode }} · 预计 {{ nextItem.estimatedMinutes }} 分钟</p>
            <div class="next-task__progress">当前计划 {{ nextPlan.items.filter(item => item.status === 'COMPLETED').length }} / {{ nextPlan.items.length }} 项已完成</div>
            <router-link class="primary-button" :to="`/training?planId=${nextPlan.id}`">继续训练 <el-icon><ArrowRight /></el-icon></router-link>
          </div>
          <template #empty><router-link class="primary-button" to="/job-match?tab=matches">前往岗位匹配 <el-icon><ArrowRight /></el-icon></router-link></template>
        </AsyncState>
      </section>

      <section class="panel job-panel">
        <div class="panel__heading"><h2><el-icon><Aim /></el-icon>近期目标岗位</h2><router-link to="/job-match?tab=matches">查看岗位匹配 <el-icon><ArrowRight /></el-icon></router-link></div>
        <AsyncState :loading="loading && !targetJobs.length" :error="jobsError" :empty="!targetJobs.length" empty-text="暂无可用目标岗位" empty-description="生成 READY 状态的岗位匹配后，这里会展示最新结果。" @retry="loadDashboard">
          <div class="target-jobs">
            <router-link v-for="item in targetJobs" :key="item.id" class="target-job" :to="`/job-match?jobId=${item.jobId}`">
              <span><strong>{{ item.title }}</strong><small>JD v{{ item.jobVersion }}</small></span>
              <span class="target-job__score">{{ displayScore(item.matchScore) }}</span>
            </router-link>
          </div>
        </AsyncState>
      </section>
    </div>

    <section class="panel progress-panel">
      <div class="panel__heading"><h2><el-icon><TrendCharts /></el-icon>求职进度 <small>仅汇总后端返回的计划项与有效能力证据。</small></h2><router-link to="/growth">查看详细进度 <el-icon><ArrowRight /></el-icon></router-link></div>
      <AsyncState :loading="loading && !growth" :error="growthError" @retry="loadDashboard">
        <div class="progress-metrics">
          <div><span>训练计划项</span><strong>{{ completedItems }} / {{ totalItems }}</strong><small>已完成 / 当前加载总数</small></div>
          <div><span>有效能力样本</span><strong>{{ evidenceSamples }}</strong><small>各技能样本数之和</small></div>
          <div><span>已评分技能</span><strong>{{ evaluatedSkills }}</strong><small>无评分技能不计入</small></div>
        </div>
        <div v-if="growth?.skills.length" class="skill-summary">
          <span v-for="skill in growth.skills.slice(0, 6)" :key="skill.skillCode">{{ skill.skillCode }}：<strong>{{ displayScore(skill.score) }}</strong>（{{ skill.sampleCount }} 个样本）</span>
        </div>
        <el-empty v-else :image-size="56" description="暂无技能能力摘要，完成有效评价后再查看" />
      </AsyncState>
    </section>

    <section class="panel activity-panel">
      <div class="panel__heading"><h2><el-icon><Clock /></el-icon>最新任务动态</h2><router-link to="/tasks">查看全部任务 <el-icon><ArrowRight /></el-icon></router-link></div>
      <AsyncState :loading="loading && !recentTasks.length" :error="tasksError" :empty="!recentTasks.length" empty-text="暂无任务记录" @retry="loadDashboard">
        <div class="activity-list">
          <router-link v-for="task in recentTasks" :key="task.id" class="activity-row" :to="`/tasks?taskId=${task.id}`">
            <span><strong>{{ taskTypeLabels[task.type] || task.type }}</strong><small>{{ formatDate(task.createdAt) }}</small></span>
            <el-tag size="small" :type="task.status === 'SUCCEEDED' ? 'success' : task.status === 'FAILED' ? 'danger' : 'primary'">{{ taskStatusLabels[task.status] || task.status }}</el-tag>
            <span class="activity-stage">{{ task.stage || commonStatusLabels[task.status] || task.status }}</span>
          </router-link>
        </div>
      </AsyncState>
    </section>
    <el-alert v-if="profileError || plansError || jobsError || growthError || tasksError" class="partial-error" type="warning" title="部分数据加载失败，对应区域可点击重新加载；其他区域仍展示已成功读取的数据。" :closable="false" show-icon />

  </div>
</template>

<style scoped lang="scss">
.dashboard { max-width: 1500px; margin: 0 auto; color: #0b1746; }
.welcome { padding: 0 14px 20px; color: #61749b; font-size: 16px; }
.welcome__top { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }
.welcome h1 { margin: 8px 0; color: #09143e; font-size: 30px; line-height: 1.3; font-weight: 750; }
.welcome__description { font-size: 15px; }
.goals { display: flex; flex-wrap: wrap; gap: 12px 0; margin-top: 20px; }
.goals span { display: flex; gap: 10px; align-items: center; padding: 0 28px; border-right: 1px solid #dce6f5; }
.goals span:first-child { padding-left: 0; }
.goals span:last-child { border: 0; }
.goals .el-icon { color: #122357; font-size: 21px; }
.goals strong { color: #101d4b; }
.overview { display: grid; grid-template-columns: 1.37fr 1fr; gap: 12px; }
.panel { padding: 16px 18px; border: 1px solid #e8f0fa; border-radius: 10px; background: #fff; }
.panel h2 { display: flex; align-items: center; gap: 10px; font-size: 18px; line-height: 1.4; }
.panel h2 > .el-icon { color: #0861fc; font-size: 23px; }
.panel__heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.panel__heading a { display: inline-flex; align-items: center; color: #0662ff; font-size: 14px; white-space: nowrap; }
.next-task { min-height: 210px; padding: 22px; border-radius: 9px; background: #ecf5ff; }
.next-task h3 { margin: 14px 0 8px; font-size: 20px; }
.next-task p, .next-task__progress { color: #7184a6; line-height: 1.6; }
.next-task__progress { margin-top: 10px; font-size: 13px; }
.primary-button { display: inline-flex; align-items: center; justify-content: center; gap: 10px; margin-top: 18px; padding: 11px 20px; border-radius: 7px; background: #0c5cf5; color: #fff; font-weight: 600; }
.primary-button:hover { background: #084ed5; }
.target-jobs { display: grid; gap: 10px; }
.target-job { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px; border: 1px solid #e5eef9; border-radius: 8px; color: inherit; }
.target-job:hover { border-color: #8fb8f6; background: #f5f9ff; }
.target-job > span:first-child { display: grid; gap: 5px; }
.target-job small { color: #7184a6; }
.target-job__score { color: #0c5cf5; font-size: 20px; font-weight: 700; }
.progress-panel, .activity-panel { margin-top: 12px; }
.progress-panel h2 small { margin-left: 8px; color: #7284a5; font-size: 14px; font-weight: 400; }
.progress-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.progress-metrics > div { display: grid; gap: 7px; padding: 16px; border-radius: 8px; background: #f5f9ff; }
.progress-metrics span, .progress-metrics small { color: #7284a5; }
.progress-metrics strong { font-size: 25px; }
.skill-summary { display: flex; flex-wrap: wrap; gap: 8px 16px; margin-top: 16px; color: #61749b; font-size: 13px; }
.activity-list { display: grid; }
.activity-row { display: grid; grid-template-columns: minmax(200px, 1fr) auto minmax(160px, .7fr); align-items: center; gap: 14px; padding: 12px 4px; border-bottom: 1px solid #edf2f8; color: inherit; }
.activity-row:last-child { border-bottom: 0; }
.activity-row > span:first-child { display: flex; align-items: center; gap: 12px; }
.activity-row small, .activity-stage { color: #8495b1; }
.activity-stage { text-align: right; }
.partial-error { margin-top: 12px; }
.hidden-error { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
@media (max-width: 1050px) { .overview { grid-template-columns: 1fr; } }
@media (max-width: 700px) { .welcome { padding-left: 0; } .welcome__top, .panel__heading { align-items: flex-start; flex-direction: column; } .welcome h1 { font-size: 24px; } .goals span { padding: 0 12px 0 0; border: 0; } .progress-panel h2 small { display: none; } .progress-metrics { grid-template-columns: 1fr; } .activity-row { grid-template-columns: 1fr auto; } .activity-stage { grid-column: 1 / -1; text-align: left; } .activity-row > span:first-child { align-items: flex-start; flex-direction: column; gap: 4px; } }
</style>
