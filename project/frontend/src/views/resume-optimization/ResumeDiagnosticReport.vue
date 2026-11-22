<script setup lang="ts">
import { computed, ref, watchEffect } from 'vue'
import type { AssessmentDimension, AssessmentDimensionCode, DiagnosisItem, ResumeDiagnosisVO } from '@/api/types'
import { formatDate } from '@/utils/format'

const props = defineProps<{ diagnosis: ResumeDiagnosisVO }>()
const dimensionLabels: Record<AssessmentDimensionCode, string> = {
  EXPERIENCE_PROJECTS: '经历与项目',
  SKILLS_EVIDENCE: '技能与证据',
  CONTENT_COMPLETENESS: '内容完整性',
  STRUCTURE_CLARITY: '结构清晰度',
  EXPRESSION_PROFESSIONALISM: '表达专业性',
}
const dimensionColors = ['#285fcb', '#5263c5', '#16816d', '#0a7596', '#b26314']
const priorities: { key: DiagnosisItem['priority']; label: string }[] = [
  { key: 'IMPORTANT', label: '高优先级' },
  { key: 'SUGGESTION', label: '中优先级' },
  { key: 'NOTICE', label: '低优先级' },
]
const assessment = computed(() => props.diagnosis.assessment)
const dimensions = computed(() => Object.keys(dimensionLabels)
  .flatMap(code => assessment.value?.dimensions.filter(dimension => dimension.code === code) || []))
const items = computed(() => props.diagnosis.items.map(item => ({
  ...item,
  check: dimensions.value.find(dimension => dimension.code === item.dimensionCode)
    ?.criteria?.find(check => check.code === item.checkCode),
})))
const groups = computed(() => priorities.map(group => ({
  ...group, items: items.value.filter(item => item.priority === group.key),
})).filter(group => group.items.length))
const radar = ref<HTMLCanvasElement | null>(null)

function scoreRate(dimension: AssessmentDimension) {
  return dimension.maxScore ? Math.min(1, Math.max(0, dimension.score / dimension.maxScore)) : 0
}

watchEffect(() => {
  const canvas = radar.value
  const axes = dimensions.value
  if (!canvas || axes.length !== 5) return
  const context = canvas.getContext('2d')
  if (!context) return
  const width = 400
  const height = 222
  const pixelRatio = window.devicePixelRatio || 1
  canvas.width = width * pixelRatio
  canvas.height = height * pixelRatio
  context.scale(pixelRatio, pixelRatio)
  const center = { x: width / 2, y: 115 }
  const radius = 83
  function point(index: number, rate: number) {
    const angle = index * 2 * Math.PI / axes.length - Math.PI / 2
    return { x: center.x + radius * rate * Math.cos(angle), y: center.y + radius * rate * Math.sin(angle) }
  }
  function polygon(rates: number[]) {
    if (!context) return
    context.beginPath()
    rates.forEach((rate, index) => {
      const vertex = point(index, rate)
      if (index === 0) context.moveTo(vertex.x, vertex.y)
      else context.lineTo(vertex.x, vertex.y)
    })
    context.closePath()
  }
  context.strokeStyle = '#dce6f3'
  for (const rate of [.25, .5, .75, 1]) {
    polygon(axes.map(() => rate))
    context.stroke()
  }
  axes.forEach((_, index) => {
    const vertex = point(index, 1)
    context.beginPath()
    context.moveTo(center.x, center.y)
    context.lineTo(vertex.x, vertex.y)
    context.stroke()
  })
  polygon(axes.map(scoreRate))
  context.fillStyle = 'rgba(40, 95, 203, .18)'
  context.strokeStyle = '#285fcb'
  context.lineWidth = 2
  context.fill()
  context.stroke()
  context.fillStyle = '#405775'
  context.font = '13px system-ui, sans-serif'
  context.textBaseline = 'middle'
  axes.forEach((dimension, index) => {
    const label = point(index, 1.17)
    context.textAlign = index === 0 ? 'center' : index < 3 ? 'left' : 'right'
    context.fillText(dimension.label || dimensionLabels[dimension.code], label.x, label.y)
    const vertex = point(index, scoreRate(dimension))
    context.beginPath()
    context.arc(vertex.x, vertex.y, 3, 0, 2 * Math.PI)
    context.stroke()
  })
}, { flush: 'post' })
</script>

<template>
  <div class="diagnostic-report">
    <el-alert v-if="diagnosis.readOrderVerified === false" type="warning" title="本次使用已保存的简历文本，阅读顺序可能有偏差。" :closable="false" show-icon />
    <div v-if="assessment" class="report-overview">
      <section class="panel report-summary">
        <h2>综合评价</h2>
        <div class="score-line">
          <div class="score-number"><strong>{{ assessment.totalScore }}</strong><span> / 100</span></div>
          <div class="score-meta"><b>总分</b><span>分析于 {{ formatDate(assessment.assessedAt) }}</span></div>
        </div>
        <p v-if="assessment.summary" class="summary-text">{{ assessment.summary }}</p>
        <div v-if="assessment.strengths.length" class="highlights">
          <h3>优势亮点</h3>
          <details v-for="(strength, index) in assessment.strengths" :key="index" class="highlight">
            <summary>{{ strength.title }}</summary>
            <p>{{ strength.description }}</p>
            <p v-if="strength.approach"><b>具体做法</b> {{ strength.approach }}</p>
            <p v-if="strength.contribution"><b>个人贡献</b> {{ strength.contribution }}</p>
            <p v-if="strength.result"><b>已有结果</b> {{ strength.result }}</p>
            <blockquote v-for="(quote, quoteIndex) in [strength.quote, ...(strength.quotes || [])]" :key="quoteIndex">{{ quote }}</blockquote>
          </details>
        </div>
        <p class="report-note">评分依据为本版本的简历文本 · {{ assessment.ruleVersion }}</p>
      </section>
      <section class="panel report-dimensions">
        <h2>多维度评分</h2>
        <canvas v-if="dimensions.length === 5" ref="radar" class="radar" aria-hidden="true" />
        <div class="dimension-grid">
          <details v-for="(dimension, index) in dimensions" :key="dimension.code" class="dimension">
            <summary>
              <span class="dimension-heading">
                <span class="dimension-title">{{ dimension.label || dimensionLabels[dimension.code] }}</span>
                <strong>{{ dimension.score }} / {{ dimension.maxScore ?? '—' }}</strong>
              </span>
              <span class="score-track" aria-hidden="true"><span :style="{ width: scoreRate(dimension) * 100 + '%', background: dimensionColors[index] }"></span></span>
            </summary>
            <ul v-if="dimension.criteria?.length" class="criteria">
              <li v-for="check in dimension.criteria" :key="check.code"><span>{{ check.label }}</span><strong>{{ check.score }} / {{ check.maxScore }}</strong></li>
            </ul>
            <p>{{ dimension.rationale }}</p>
            <blockquote v-for="(quote, quoteIndex) in [dimension.quote, ...(dimension.quotes || [])].filter(Boolean)" :key="quoteIndex">{{ quote }}</blockquote>
          </details>
        </div>
      </section>
    </div>
    <el-alert v-else type="info" title="这份历史诊断没有评分。可重新分析当前简历版本获取评分报告。" :closable="false" show-icon />
    <section class="panel improvements">
      <h2>改进建议 <span>{{ diagnosis.items.length }} 条</span></h2>
      <p v-if="!groups.length" class="report-note">本次分析未发现有充分原文依据的改进项。</p>
      <section v-for="group in groups" :key="group.key" class="priority-group" :data-priority="group.key">
        <h3 class="priority-heading">{{ group.label }} <span>{{ group.items.length }}</span></h3>
        <article v-for="item in group.items" :key="item.id" class="improvement">
          <div class="improvement-heading">
            <h4>{{ item.title }}</h4>
            <span v-if="item.dimensionCode">
              {{ dimensionLabels[item.dimensionCode] }}
              <template v-if="item.check"> · {{ item.check.label }} {{ item.check.score }}/{{ item.check.maxScore }}</template>
            </span>
          </div>
          <p>{{ item.issue }}</p>
          <p class="improvement-action"><b>修改动作</b> {{ item.suggestion }}</p>
          <details>
            <summary>查看依据与核对项</summary>
            <blockquote v-for="(quote, index) in [item.quote, ...(item.quotes || [])].filter(Boolean)" :key="index">{{ quote }}</blockquote>
            <p>{{ item.rationale }}</p>
            <p v-if="item.impact"><b>影响</b> {{ item.impact }}</p>
            <p v-if="item.example"><b>改写示例</b> {{ item.example }}</p>
            <p v-if="item.pendingVerification?.length"><b>待核实</b> {{ item.pendingVerification.join('、') }}</p>
          </details>
        </article>
      </section>
    </section>
  </div>
</template>

<style scoped>
.diagnostic-report { display: grid; gap: 16px; min-width: 0; }
.report-overview { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.diagnostic-report .panel { min-width: 0; margin: 0; padding: clamp(16px, 2vw, 24px); }
.diagnostic-report h2 { margin: 0 0 14px; color: var(--ai-text-primary, #162f52); font-size: 18px; }
.diagnostic-report p, .diagnostic-report blockquote { overflow-wrap: anywhere; white-space: pre-wrap; }
.score-line { display: flex; align-items: center; flex-wrap: wrap; gap: 12px 22px; padding: 12px 0 14px; border-bottom: 1px solid var(--ai-border-light, #e6edf6); }
.score-number strong { color: var(--ai-primary, #235cbe); font-size: 49px; line-height: 1; letter-spacing: -.045em; }
.score-number span { color: var(--ai-text-secondary, #536880); font-size: 14px; }
.score-meta { display: grid; gap: 5px; color: var(--ai-text-secondary, #536880); font-size: 13px; }
.score-meta b { color: var(--ai-text-primary, #213c5e); font-size: 14px; }
.summary-text { margin: 16px 0; color: var(--ai-text-secondary, #243e5d); font-size: 15px; line-height: 1.75; }
.highlights { display: grid; gap: 8px; margin-top: 14px; }
.highlights h3 { margin: 0 0 3px; color: var(--ai-success, #215548); font-size: 14px; }
.highlight { padding: 9px 12px; background: var(--ai-bg-light, #f0f8f5); border-radius: 7px; color: var(--ai-success, #214c41); }
summary { cursor: pointer; overflow-wrap: anywhere; }
summary:focus-visible { outline: 2px solid #275ec7; outline-offset: 3px; }
.highlight summary { font-weight: 600; font-size: 13px; }
.report-note { margin: 14px 0 0; color: var(--ai-text-secondary, #536880); font-size: 12px; }
.radar { display: block; width: min(100%, 400px); height: auto; aspect-ratio: 400 / 222; margin: -10px auto 5px; }
.dimension-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.dimension { border: 1px solid var(--ai-border-light, #e4edf7); background: var(--ai-bg-light, #f8fafd); border-radius: 8px; padding: 9px 11px; min-width: 0; }
.dimension:first-child { grid-column: 1 / -1; }
.dimension summary { list-style: none; }
.dimension summary::-webkit-details-marker { display: none; }
.dimension-heading { display: flex; flex-wrap: wrap; align-items: baseline; gap: 3px 8px; justify-content: space-between; font-size: 12px; color: var(--ai-text-secondary, #314d6d); }
.dimension-heading strong { color: var(--ai-text-secondary, #1f3f6a); white-space: nowrap; }
.dimension-title::after { content: ''; display: inline-block; width: 5px; height: 5px; margin-left: 7px; vertical-align: 2px; border: solid currentColor; border-width: 0 1px 1px 0; transform: rotate(45deg); }
.dimension[open] .dimension-title::after { vertical-align: 0; transform: rotate(225deg); }
.score-track, .score-track > span { display: block; height: 5px; border-radius: 4px; }
.score-track { background: var(--ai-bg-light, #e0e8f2); margin-top: 7px; }
.criteria { list-style: none; padding: 0; margin: 10px 0 0; }
.criteria li { display: flex; gap: 8px; justify-content: space-between; font-size: 12px; padding: 4px 0; border-bottom: 1px solid var(--ai-border-light, #e6edf6); }
.criteria li strong { white-space: nowrap; }
.diagnostic-report details p { margin: 8px 0 0; font-size: 13px; line-height: 1.6; }
.diagnostic-report blockquote { margin: 8px 0 0; border-left: 2px solid var(--ai-accent-border, #a8bad5); padding: 7px 10px; color: var(--ai-text-secondary, #435d80); font-size: 13px; background: var(--ai-bg-light, #f2f6fc); }
.improvements > h2 span { font-size: 13px; font-weight: 500; color: var(--ai-text-secondary, #566d89); margin-left: 6px; }
.priority-group { --priority-color: var(--ai-primary, #375d85); --priority-bg: var(--ai-bg-light, #f4f7fc); --priority-line: var(--ai-border, #dbe5f3); display: grid; gap: 8px; margin-top: 16px; }
.priority-group[data-priority="IMPORTANT"] { --priority-color: var(--ai-danger, #9c393c); --priority-bg: var(--ai-danger-bg, #fff8f7); --priority-line: var(--ai-danger-border, #efd7d5); }
.priority-group[data-priority="SUGGESTION"] { --priority-color: var(--ai-warning, #865609); --priority-bg: var(--ai-warning-bg, #fffbf1); --priority-line: var(--ai-warning-border, #eee1c8); }
.priority-heading { font-size: 13px; color: var(--priority-color); margin: 0; display: flex; align-items: center; gap: 8px; }
.priority-heading::after { content: ''; flex: 1; height: 1px; background: var(--priority-line); }
.improvement { padding: 13px 15px; border-left: 3px solid var(--priority-line); border-radius: 6px; background: var(--priority-bg); }
.improvement-heading { display: flex; gap: 6px 12px; align-items: baseline; flex-wrap: wrap; }
.improvement h4 { margin: 0; font-size: 14px; color: var(--ai-text-primary, #233955); overflow-wrap: anywhere; }
.improvement-heading > span { font-size: 12px; color: var(--priority-color); }
.improvement p { margin: 6px 0 0; color: var(--ai-text-secondary, #475b73); font-size: 13px; line-height: 1.65; }
.improvement-action b { color: var(--ai-text-secondary, #243e5d); margin-right: 4px; }
.improvement details { margin-top: 8px; font-size: 12px; color: var(--ai-text-secondary, #3f5d85); }
@media (max-width: 1010px) { .report-overview { grid-template-columns: 1fr; } }
@media (max-width: 420px) { .dimension-grid { grid-template-columns: 1fr; } }
</style>
