<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { growthApi } from '@/api/growth'
import { ApiError } from '@/api/request'
import { retryTrackedTask } from '@/api/task-registry'
import type { EvidenceVO, GrowthVO, Page, SkillSnapshotVO } from '@/api/types'
import AsyncState from '@/components/AsyncState.vue'
import TaskStatus from '@/components/TaskStatus.vue'
import { isTerminalTask, useTask } from '@/composables/useTask'
import { errorMessage, formatDate } from '@/utils/format'

const suggestionTaskKey = 'ai-job-growth-suggestion-task'
const growth = ref<GrowthVO | null>(null)
const growthLoading = ref(false)
const growthError = ref<unknown>(null)
const suggestionSubmitting = ref(false)
const suggestionSubmitError = ref<unknown>(null)
const evidencePage = ref<Page<EvidenceVO> | null>(null)
const evidenceLoading = ref(false)
const evidenceError = ref<unknown>(null)
const evidenceFilters = reactive({ skillCode: '', sourceType: '' })
const evidenceQuery = reactive({ current: 1, size: 10 })
let evidenceRequestNo = 0
const sourceTypeLabels: Record<EvidenceVO['sourceType'], string> = {
  RESUME_CLAIM: '简历自述',
  LEARNING_ANSWER: '专项训练回答',
  INTERVIEW_ANSWER: '模拟面试回答',
}
const evidenceStatusLabels: Record<SkillSnapshotVO['evidenceStatus'], string> = {
  MISSING: '无有效证据',
  LIMITED: '证据有限',
  SUFFICIENT: '证据较充分',
}
const evidenceStatusTypes: Record<SkillSnapshotVO['evidenceStatus'], 'info' | 'warning' | 'success'> = {
  MISSING: 'info',
  LIMITED: 'warning',
  SUFFICIENT: 'success',
}
const timelineWidth = 960
const timelineHeight = 340
const timelineMargin = { top: 34, right: 34, bottom: 70, left: 62 }
const timelinePalette = ['#1769e0', '#12a594', '#8b5cf6', '#e67e22', '#d74776', '#437f29']
const yTicks = [0, 25, 50, 75, 100].map(score => ({
  score,
  y: timelineMargin.top + (100 - score) / 100 * (timelineHeight - timelineMargin.top - timelineMargin.bottom),
}))

interface TimelinePoint {
  id: string
  skillCode: string
  score: number
  createdAt: string
  timestamp: number
  x: number
  y: number
}

interface TimelineSeries {
  skillCode: string
  color: string
  points: TimelinePoint[]
  polyline: string
}

function readStoredTask() {
  try {
    return localStorage.getItem(suggestionTaskKey) || ''
  } catch {
    return ''
  }
}

function storeTask(taskId: string) {
  try {
    localStorage.setItem(suggestionTaskKey, taskId)
  } catch {
    return
  }
}

function clearStoredTask() {
  try {
    localStorage.removeItem(suggestionTaskKey)
  } catch {
    return
  }
}

const suggestionTaskId = ref(readStoredTask())
const {
  task: suggestionTask,
  loading: suggestionTaskLoading,
  error: suggestionTaskError,
  reasoningText,
  restart: restartSuggestionTask,
} = useTask(suggestionTaskId, {
  onDone: async () => {
    clearStoredTask()
    await loadGrowth()
    ElMessage.success('成长建议已根据权威摘要更新')
  },
})

const suggestionBusy = computed(() => suggestionSubmitting.value || suggestionTaskLoading.value || (!!suggestionTaskId.value && !suggestionTask.value && !suggestionTaskError.value) || (!!suggestionTask.value && !isTerminalTask(suggestionTask.value.status)))
const evidenceSkillOptions = computed(() => {
  const codes = new Set<string>()
  growth.value?.skills.forEach(skill => codes.add(skill.skillCode))
  growth.value?.recentEvidence.forEach(evidence => codes.add(evidence.skillCode))
  evidencePage.value?.records.forEach(evidence => codes.add(evidence.skillCode))
  return [...codes].sort((a, b) => a.localeCompare(b))
})
const usesFilteredTimeline = computed(() => Boolean(evidenceFilters.skillCode || evidenceFilters.sourceType))
const timelineEvidence = computed(() => usesFilteredTimeline.value ? evidencePage.value?.records || [] : growth.value?.recentEvidence || [])
const validTimelineEvidence = computed(() => timelineEvidence.value.filter(evidence => {
  if (evidence.status !== 'VALID' || evidence.score === null || !Number.isFinite(evidence.score) || evidence.score < 0 || evidence.score > 100) return false
  return Number.isFinite(new Date(evidence.createdAt).getTime())
}))
const timelineLoading = computed(() => usesFilteredTimeline.value ? evidenceLoading.value && !evidencePage.value : growthLoading.value && !growth.value)
const timelineError = computed(() => usesFilteredTimeline.value ? evidenceError.value : growth.value ? null : growthError.value)
const timelineModel = computed(() => {
  const valid = validTimelineEvidence.value
    .map(evidence => ({ ...evidence, score: evidence.score as number, timestamp: new Date(evidence.createdAt).getTime() }))
    .sort((a, b) => a.timestamp - b.timestamp || a.id.localeCompare(b.id))
  if (!valid.length) return { series: [] as TimelineSeries[], xTicks: [] as { id: string; x: number; label: string }[] }
  const minTime = valid[0].timestamp
  const maxTime = valid[valid.length - 1].timestamp
  const chartWidth = timelineWidth - timelineMargin.left - timelineMargin.right
  const chartHeight = timelineHeight - timelineMargin.top - timelineMargin.bottom
  const xFor = (timestamp: number) => minTime === maxTime
    ? timelineMargin.left + chartWidth / 2
    : timelineMargin.left + (timestamp - minTime) / (maxTime - minTime) * chartWidth
  const skillCodes = [...new Set(valid.map(evidence => evidence.skillCode))].sort((a, b) => a.localeCompare(b))
  const series = skillCodes.map((skillCode, index): TimelineSeries => {
    const points = valid.filter(evidence => evidence.skillCode === skillCode).map(evidence => ({
      id: evidence.id,
      skillCode,
      score: evidence.score,
      createdAt: evidence.createdAt,
      timestamp: evidence.timestamp,
      x: xFor(evidence.timestamp),
      y: timelineMargin.top + (100 - evidence.score) / 100 * chartHeight,
    }))
    return {
      skillCode,
      color: timelinePalette[index % timelinePalette.length],
      points,
      polyline: points.map(point => `${point.x},${point.y}`).join(' '),
    }
  })
  const tickItems = valid.length === 1
    ? [valid[0]]
    : [valid[0], valid[Math.floor((valid.length - 1) / 2)], valid[valid.length - 1]]
  const seen = new Set<number>()
  const xTicks = tickItems.filter(item => {
    const key = item.timestamp
    if (seen.has(key)) return false
    seen.add(key)
    return true
  }).map(item => ({ id: item.id, x: xFor(item.timestamp), label: formatTimelineTime(item.createdAt) }))
  return { series, xTicks }
})

async function loadGrowth() {
  growthLoading.value = true
  growthError.value = null
  try {
    const result = await growthApi.get()
    growth.value = result
    if (!suggestionTaskId.value && result.suggestionTaskId) suggestionTaskId.value = result.suggestionTaskId
    return true
  } catch (reason) {
    growthError.value = reason
    return false
  } finally {
    growthLoading.value = false
  }
}

async function generateSuggestions() {
  if (!growth.value || suggestionBusy.value) return
  suggestionSubmitting.value = true
  suggestionSubmitError.value = null
  try {
    const task = await growthApi.suggest(growth.value.profileVersion)
    suggestionTaskId.value = task.id
    storeTask(task.id)
    ElMessage.success('成长建议任务已提交')
  } catch (reason) {
    suggestionSubmitError.value = reason
    if (reason instanceof ApiError && reason.status === 409) await loadGrowth()
  } finally {
    suggestionSubmitting.value = false
  }
}

async function retrySuggestionTask() {
  if (!suggestionTask.value || suggestionSubmitting.value) return
  suggestionSubmitting.value = true
  suggestionSubmitError.value = null
  try {
    const task = await retryTrackedTask(suggestionTask.value)
    suggestionTaskId.value = task.id
    storeTask(task.id)
    ElMessage.success('已使用原画像版本重新提交建议任务')
  } catch (reason) {
    suggestionSubmitError.value = reason
  } finally {
    suggestionSubmitting.value = false
  }
}

async function loadEvidence() {
  const requestNo = ++evidenceRequestNo
  evidenceLoading.value = true
  evidenceError.value = null
  try {
    const result = await growthApi.evidence({
      current: evidenceQuery.current,
      size: evidenceQuery.size,
      skillCode: evidenceFilters.skillCode || undefined,
      sourceType: evidenceFilters.sourceType || undefined,
    })
    if (requestNo === evidenceRequestNo) evidencePage.value = result
    return true
  } catch (reason) {
    if (requestNo === evidenceRequestNo) evidenceError.value = reason
    return false
  } finally {
    if (requestNo === evidenceRequestNo) evidenceLoading.value = false
  }
}

function applyEvidenceFilters() {
  evidenceQuery.current = 1
  void loadEvidence()
}

function resetEvidenceFilters() {
  evidenceFilters.skillCode = ''
  evidenceFilters.sourceType = ''
  evidenceQuery.current = 1
  void loadEvidence()
}

function handleEvidencePage(current: number) {
  evidenceQuery.current = current
  void loadEvidence()
}

function handleEvidenceSize(size: number) {
  evidenceQuery.size = size
  evidenceQuery.current = 1
  void loadEvidence()
}

function retryTimeline() {
  if (usesFilteredTimeline.value) void loadEvidence()
  else void loadGrowth()
}

function displayScore(score: number | null) {
  return score === null ? '暂无有效评分' : String(score)
}

function formatConfidence(confidence: number) {
  return Number.isFinite(confidence) ? confidence.toFixed(2) : String(confidence)
}

function formatTimelineTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

onMounted(() => {
  void loadGrowth()
  void loadEvidence()
})
</script>

<template>
  <div class="page growth-page">
    <header class="page-header">
      <div>
        <h1>能力成长</h1>
        <p>能力分仅来自有效证据；样本量、证据状态与建议分别呈现，不将评分解释为录用概率。</p>
      </div>
      <div class="page-header__actions">
        <el-tag v-if="growth" effect="plain">画像版本 {{ growth.profileVersion }}</el-tag>
        <el-button :loading="growthLoading" @click="loadGrowth">刷新成长摘要</el-button>
      </div>
    </header>

    <el-alert v-if="growth && growthError" class="refresh-alert" type="warning" :closable="false" show-icon>
      <template #title>摘要刷新失败，当前显示上次成功加载的数据。{{ errorMessage(growthError) }}</template>
      <el-button size="small" @click="loadGrowth">重试刷新</el-button>
    </el-alert>

    <section class="panel">
      <div class="panel__heading">
        <div>
          <h2>技能能力摘要</h2>
          <p class="section-note">暂无有效评分不等于 0 分；样本阈值只是产品展示状态，不是统计置信结论。</p>
        </div>
      </div>
      <AsyncState :loading="growthLoading && !growth" :error="growth ? null : growthError" :empty="!!growth && !growth.skills.length" empty-text="暂无技能能力摘要" empty-description="完成有效训练或面试评价后，能力摘要会依据证据重新计算。" @retry="loadGrowth">
        <div class="skills-grid">
          <article v-for="skill in growth?.skills || []" :key="`${skill.skillCode}-${skill.formulaVersion}`" class="skill-card">
            <div class="skill-card__heading">
              <h3>{{ skill.skillCode }}</h3>
              <el-tag size="small" :type="evidenceStatusTypes[skill.evidenceStatus]">{{ evidenceStatusLabels[skill.evidenceStatus] }}</el-tag>
            </div>
            <div class="skill-score" :class="{ 'skill-score--empty': skill.score === null }">
              <strong>{{ displayScore(skill.score) }}</strong>
              <span v-if="skill.score !== null">/ 100</span>
              <span v-else>证据不足</span>
            </div>
            <dl class="skill-meta">
              <div><dt>有效样本数</dt><dd>{{ skill.sampleCount }}</dd></div>
              <div><dt>证据状态</dt><dd>{{ skill.evidenceStatus }}</dd></div>
              <div><dt>计算公式版本</dt><dd>{{ skill.formulaVersion }}</dd></div>
              <div><dt>更新时间</dt><dd>{{ formatDate(skill.updatedAt) }}</dd></div>
            </dl>
          </article>
        </div>
      </AsyncState>
    </section>

    <section class="panel timeline-panel">
      <div class="panel__heading">
        <div>
          <h2>成长时间线</h2>
          <p class="section-note">{{ usesFilteredTimeline ? '使用当前筛选证据的本页结果' : '使用成长摘要中的最近证据' }}，仅绘制真实创建时间、VALID 且非 null 的评分点。</p>
        </div>
        <el-button :loading="timelineLoading" @click="retryTimeline">刷新时间线</el-button>
      </div>
      <AsyncState :loading="timelineLoading" :error="timelineError" :empty="!validTimelineEvidence.length" empty-text="有效评分证据不足" empty-description="未评分、已撤销或无有效时间的证据不会按 0 分绘制。可调整筛选或完成新的有效评价。" @retry="retryTimeline">
        <div class="timeline-legend">
          <span v-for="series in timelineModel.series" :key="series.skillCode"><i :style="{ backgroundColor: series.color }" />{{ series.skillCode }}（{{ series.points.length }} 点）</span>
        </div>
        <div class="timeline-scroll">
          <svg class="growth-timeline" :viewBox="`0 0 ${timelineWidth} ${timelineHeight}`" role="img" aria-label="基于有效证据评分与创建时间的成长时间线">
            <text class="axis-title" :x="18" :y="22">能力评分</text>
            <g v-for="tick in yTicks" :key="tick.score">
              <line class="grid-line" :x1="timelineMargin.left" :x2="timelineWidth - timelineMargin.right" :y1="tick.y" :y2="tick.y" />
              <text class="axis-label" :x="timelineMargin.left - 12" :y="tick.y + 4" text-anchor="end">{{ tick.score }}</text>
            </g>
            <line class="axis-line" :x1="timelineMargin.left" :x2="timelineMargin.left" :y1="timelineMargin.top" :y2="timelineHeight - timelineMargin.bottom" />
            <line class="axis-line" :x1="timelineMargin.left" :x2="timelineWidth - timelineMargin.right" :y1="timelineHeight - timelineMargin.bottom" :y2="timelineHeight - timelineMargin.bottom" />
            <g v-for="series in timelineModel.series" :key="series.skillCode">
              <polyline v-if="series.points.length > 1" class="score-line" :points="series.polyline" :stroke="series.color" />
              <g v-for="point in series.points" :key="point.id">
                <circle class="score-point" :cx="point.x" :cy="point.y" r="5" :fill="series.color">
                  <title>{{ point.skillCode }} · {{ point.score }} 分 · {{ formatDate(point.createdAt) }}</title>
                </circle>
                <text class="point-score" :x="point.x" :y="point.y - 11" text-anchor="middle" :fill="series.color">{{ point.score }}</text>
              </g>
            </g>
            <g v-for="tick in timelineModel.xTicks" :key="tick.id">
              <line class="tick-line" :x1="tick.x" :x2="tick.x" :y1="timelineHeight - timelineMargin.bottom" :y2="timelineHeight - timelineMargin.bottom + 6" />
              <text class="axis-label axis-label--time" :x="tick.x" :y="timelineHeight - timelineMargin.bottom + 24" text-anchor="middle">{{ tick.label }}</text>
            </g>
            <text class="axis-title" :x="timelineWidth / 2" :y="timelineHeight - 12" text-anchor="middle">证据创建时间（Asia/Shanghai）</text>
          </svg>
        </div>
        <p class="timeline-caption">连线仅按同一技能的有效评分时间顺序连接，不虚构中间历史数据；悬停评分点可查看技能、分数与时间。</p>
      </AsyncState>
    </section>

    <section class="panel">
      <div class="panel__heading">
        <div>
          <h2>下一步成长建议</h2>
          <p class="section-note">建议任务使用当前摘要画像版本，完成后重新读取成长摘要，不直接修改能力分。</p>
        </div>
        <el-button type="primary" :loading="suggestionSubmitting" :disabled="!growth || suggestionBusy" @click="generateSuggestions">生成成长建议</el-button>
      </div>
      <div v-if="suggestionTaskId" class="task-block">
        <el-skeleton v-if="suggestionTaskLoading && !suggestionTask" :rows="2" animated />
        <TaskStatus v-else :task="suggestionTask" :reasoning="reasoningText">
          <template #actions>
            <el-button v-if="suggestionTask?.status === 'FAILED'" size="small" :loading="suggestionSubmitting" @click="retrySuggestionTask">按原参数重试</el-button>
          </template>
        </TaskStatus>
        <el-alert v-if="suggestionTaskError" type="warning" :closable="false" show-icon>
          <template #title>任务事件连接异常，业务任务未被取消。{{ errorMessage(suggestionTaskError) }}</template>
          <el-button size="small" @click="restartSuggestionTask">恢复任务状态</el-button>
        </el-alert>
      </div>
      <el-alert v-if="suggestionSubmitError" class="inline-alert" type="error" :closable="false" show-icon>
        <template #title>{{ errorMessage(suggestionSubmitError) }}</template>
        <el-button size="small" :disabled="!growth || suggestionBusy" @click="generateSuggestions">按最新摘要重试</el-button>
      </el-alert>
      <AsyncState :loading="growthLoading && !growth" :error="growth ? null : growthError" :empty="!!growth && !growth.suggestions.length && !suggestionBusy" empty-text="暂无成长建议" empty-description="可基于当前画像版本生成下一步建议。" @retry="loadGrowth">
        <ol v-if="growth?.suggestions.length" class="suggestion-list">
          <li v-for="(suggestion, index) in growth.suggestions" :key="`${index}-${suggestion}`"><span>{{ index + 1 }}</span><p>{{ suggestion }}</p></li>
        </ol>
      </AsyncState>
    </section>

    <section class="panel">
      <div class="panel__heading">
        <div>
          <h2>最近证据</h2>
          <p class="section-note">最多展示摘要返回的最近 10 条。置信度为模型自报诊断值，不是准确率或通过概率。</p>
        </div>
        <el-button :loading="growthLoading" @click="loadGrowth">刷新最近证据</el-button>
      </div>
      <AsyncState :loading="growthLoading && !growth" :error="growth ? null : growthError" :empty="!!growth && !growth.recentEvidence.length" empty-text="暂无最近证据" empty-description="简历自述可留下未评分证据，有效训练与面试评价可留下评分证据。" @retry="loadGrowth">
        <div class="evidence-list">
          <article v-for="evidence in growth?.recentEvidence || []" :key="evidence.id" class="evidence-row">
            <div class="evidence-row__title">
              <strong>{{ evidence.skillCode }}</strong>
              <el-tag size="small" effect="plain">{{ sourceTypeLabels[evidence.sourceType] }}</el-tag>
              <el-tag size="small" :type="evidence.status === 'VALID' ? 'success' : 'info'">{{ evidence.status === 'VALID' ? '有效' : '已撤销' }}</el-tag>
            </div>
            <div class="evidence-metrics">
              <div><span>分数</span><strong :class="{ 'null-score': evidence.score === null }">{{ displayScore(evidence.score) }}</strong></div>
              <div><span>模型自报置信度（非准确率）</span><strong>{{ formatConfidence(evidence.confidence) }}</strong></div>
              <div><span>量表版本</span><strong>{{ evidence.rubricVersion || '—' }}</strong></div>
              <div><span>创建时间</span><strong>{{ formatDate(evidence.createdAt) }}</strong></div>
            </div>
            <small>来源 ID：{{ evidence.sourceId }}</small>
          </article>
        </div>
      </AsyncState>
    </section>

    <section class="panel">
      <div class="panel__heading">
        <div>
          <h2>证据查询</h2>
          <p class="section-note">筛选会同时更新当前页列表与时间线；翻页只读取对应页的真实证据。</p>
        </div>
        <el-button :loading="evidenceLoading" @click="loadEvidence">刷新证据</el-button>
      </div>
      <div class="filter-bar evidence-filters">
        <el-select v-model="evidenceFilters.skillCode" class="filter-select" clearable filterable placeholder="全部技能" @change="applyEvidenceFilters">
          <el-option v-for="code in evidenceSkillOptions" :key="code" :label="code" :value="code" />
        </el-select>
        <el-select v-model="evidenceFilters.sourceType" class="filter-select" clearable placeholder="全部来源" @change="applyEvidenceFilters">
          <el-option v-for="(label, value) in sourceTypeLabels" :key="value" :label="label" :value="value" />
        </el-select>
        <el-button :disabled="!evidenceFilters.skillCode && !evidenceFilters.sourceType" @click="resetEvidenceFilters">清除筛选</el-button>
        <span v-if="usesFilteredTimeline" class="filter-hint">时间线正在使用筛选结果本页数据</span>
      </div>
      <AsyncState :loading="evidenceLoading" :error="evidenceError" :empty="!evidencePage?.records.length" empty-text="当前筛选条件下暂无证据" empty-description="可清除筛选或调整分页后重试。" @retry="loadEvidence">
        <div class="evidence-list evidence-list--query">
          <article v-for="evidence in evidencePage?.records || []" :key="evidence.id" class="evidence-row">
            <div class="evidence-row__title">
              <strong>{{ evidence.skillCode }}</strong>
              <el-tag size="small" effect="plain">{{ sourceTypeLabels[evidence.sourceType] }}</el-tag>
              <el-tag size="small" :type="evidence.status === 'VALID' ? 'success' : 'info'">{{ evidence.status }}</el-tag>
            </div>
            <div class="evidence-metrics">
              <div><span>分数</span><strong :class="{ 'null-score': evidence.score === null }">{{ displayScore(evidence.score) }}</strong></div>
              <div><span>模型自报置信度（非准确率）</span><strong>{{ formatConfidence(evidence.confidence) }}</strong></div>
              <div><span>状态 / 量表</span><strong>{{ evidence.status }} / {{ evidence.rubricVersion || '—' }}</strong></div>
              <div><span>创建时间</span><strong>{{ formatDate(evidence.createdAt) }}</strong></div>
            </div>
            <small>证据 ID：{{ evidence.id }} · 来源 ID：{{ evidence.sourceId }}</small>
          </article>
        </div>
        <el-pagination
          class="pagination"
          background
          layout="total, sizes, prev, pager, next"
          :total="evidencePage?.total || 0"
          :current-page="evidencePage?.current || evidenceQuery.current"
          :page-size="evidencePage?.size || evidenceQuery.size"
          :page-sizes="[10, 20, 50]"
          @current-change="handleEvidencePage"
          @size-change="handleEvidenceSize"
        />
      </AsyncState>
    </section>
  </div>
</template>

<style scoped lang="scss">
.growth-page { padding-bottom: 24px; }
.section-note { margin: 6px 0 0; color: $ai-text-secondary; line-height: 1.6; }
.refresh-alert, .inline-alert { margin-bottom: 14px; }
.skills-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.skill-card { min-width: 0; padding: 16px; border: 1px solid #e1eaf7; border-radius: 11px; background: linear-gradient(150deg, #fff, #f8fbff); }
.skill-card__heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.skill-card h3 { margin: 0; color: #101d4b; font-size: 18px; overflow-wrap: anywhere; }
.skill-score { display: flex; align-items: baseline; gap: 7px; min-height: 61px; margin: 15px 0; }
.skill-score strong { color: $ai-primary; font-size: 36px; line-height: 1.2; }
.skill-score span { color: $ai-text-secondary; }
.skill-score--empty { align-items: flex-start; flex-direction: column; justify-content: center; gap: 2px; }
.skill-score--empty strong { color: $ai-text-secondary; font-size: 18px; }
.skill-score--empty span { font-size: 12px; }
.skill-meta { display: grid; gap: 7px; margin: 0; font-size: 12px; }
.skill-meta > div { display: flex; justify-content: space-between; gap: 10px; }
.skill-meta dt { flex: 0 0 auto; color: $ai-text-secondary; }
.skill-meta dd { margin: 0; text-align: right; overflow-wrap: anywhere; }
.timeline-legend { display: flex; flex-wrap: wrap; gap: 8px 18px; margin-bottom: 10px; color: $ai-text-secondary; font-size: 12px; }
.timeline-legend span { display: inline-flex; align-items: center; gap: 6px; }
.timeline-legend i { width: 9px; height: 9px; border-radius: 50%; }
.timeline-scroll { overflow-x: auto; border: 1px solid #e6edf8; border-radius: 10px; background: #fbfdff; }
.growth-timeline { display: block; width: 100%; min-width: 680px; height: auto; }
.grid-line { stroke: #e8eef7; stroke-width: 1; }
.axis-line, .tick-line { stroke: #9baac2; stroke-width: 1.2; }
.axis-label { fill: #71809a; font-size: 12px; }
.axis-label--time { font-size: 11px; }
.axis-title { fill: #536581; font-size: 12px; font-weight: 600; }
.score-line { fill: none; stroke-width: 2.4; stroke-linecap: round; stroke-linejoin: round; }
.score-point { stroke: #fff; stroke-width: 2; }
.point-score { font-size: 11px; font-weight: 700; }
.timeline-caption { margin: 10px 0 0; color: $ai-text-secondary; font-size: 12px; line-height: 1.6; }
.task-block { display: grid; gap: 10px; margin-bottom: 14px; }
.suggestion-list { display: grid; gap: 10px; margin: 0; padding: 0; list-style: none; }
.suggestion-list li { display: flex; align-items: flex-start; gap: 12px; padding: 13px; border-radius: 9px; background: $ai-bg-page; }
.suggestion-list li > span { display: grid; flex: 0 0 26px; height: 26px; place-items: center; border-radius: 50%; background: $ai-primary; color: #fff; font-size: 12px; font-weight: 700; }
.suggestion-list p { margin: 2px 0 0; line-height: 1.7; }
.evidence-list { display: grid; gap: 10px; }
.evidence-row { min-width: 0; padding: 14px; border: 1px solid #e5edf8; border-radius: 9px; }
.evidence-row__title { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.evidence-row__title > strong { margin-right: 3px; color: #101d4b; font-size: 16px; }
.evidence-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.evidence-metrics > div { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
.evidence-metrics span { color: $ai-text-secondary; font-size: 12px; line-height: 1.45; }
.evidence-metrics strong { font-size: 13px; font-weight: 600; overflow-wrap: anywhere; }
.evidence-metrics .null-score { color: $ai-text-secondary; }
.evidence-row > small { display: block; margin-top: 12px; color: $ai-text-tertiary; overflow-wrap: anywhere; }
.evidence-filters { align-items: center; }
.filter-select { width: 230px; }
.filter-hint { color: $ai-primary; font-size: 12px; }
@media (max-width: 1100px) {
  .skills-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .evidence-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 700px) {
  .skills-grid, .evidence-metrics { grid-template-columns: 1fr; }
  .filter-select { width: 100%; }
  .evidence-filters { align-items: stretch; }
  .growth-timeline { min-width: 620px; }
}
</style>