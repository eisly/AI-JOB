<script setup lang="ts">
import { computed } from 'vue'
import AsyncState from '@/components/AsyncState.vue'
import type { InterviewReportVO } from '@/api/types'

const props = defineProps<{
  report: InterviewReportVO | null
  loading: boolean
  reevaluating: boolean
  error: unknown
}>()
const emit = defineEmits<{ retry: []; reevaluate: [] }>()

const completionTypeLabels: Record<InterviewReportVO['completionType'], string> = {
  NORMAL: '正常完成',
  EARLY_END: '提前结束',
  NO_VALID_ANSWER: '未作答',
}
const dimensionLabels: Record<string, string> = {
  correctness: '正确性',
  completeness: '完整性',
  expression: '表达能力',
}
const evidenceStatusLabels: Record<string, string> = {
  VALID: '有效',
  INSUFFICIENT: '证据不足',
  INVALID: '无效',
  MISSING: '缺失',
  LIMITED: '有限',
  SUFFICIENT: '充分',
}

const reportVersion = computed(() => props.report?.reportVersion)

function nullableScore(value: number | null | undefined) {
  return value === null || value === undefined ? '暂无' : String(value)
}
</script>

<template>
  <section class="panel report-panel">
    <div class="panel__heading">
      <h2>面试复盘报告</h2>
      <div v-if="report" class="report-actions">
        <el-tag type="info">报告 v{{ reportVersion }}</el-tag>
        <el-button v-if="report.canReevaluate" type="primary" plain :loading="reevaluating" @click="emit('reevaluate')">重新评估回答</el-button>
      </div>
    </div>
    <AsyncState :loading="loading" :retain="!!report" :error="error" @retry="emit('retry')">
      <template v-if="report">
        <div class="report-summary">
          <div class="score-card" :class="{ 'score-card--empty': report.evaluation.overallScore === null }">
            <span>综合评分</span>
            <strong>{{ report.evaluation.overallScore ?? (report.canReevaluate ? '待重新评估' : '未作答') }}</strong>
            <small v-if="report.evaluation.overallScore !== null">满分 100</small>
          </div>
          <div class="report-facts">
            <div><span>完成方式</span><strong>{{ report.canReevaluate && report.completionType === 'NO_VALID_ANSWER' ? '已结束' : completionTypeLabels[report.completionType] }}</strong></div>
            <div><span>{{ report.canReevaluate ? '已评分主问题数' : '已作答主问题数' }}</span><strong>{{ report.answeredQuestionCount }}</strong></div>
            <div><span>证据状态</span><strong>{{ evidenceStatusLabels[report.evaluation.evidenceStatus] || report.evaluation.evidenceStatus }}</strong></div>
          </div>
        </div>
        <el-alert v-if="report.canReevaluate" class="report-notice" title="这份历史报告有回答尚未评分。点击「重新评估回答」可补全评分和复盘建议。" type="info" :closable="false" show-icon />
        <el-alert v-else-if="report.completionType === 'NO_VALID_ANSWER'" class="report-notice" title="本次面试未提交回答，暂无评分。" type="info" :closable="false" show-icon />
        <el-alert v-else-if="!report.evidenceIds.length" class="report-notice" :title="report.skillEvidenceMessage || '暂无可计入成长记录的逐技能评分。'" type="info" :closable="false" show-icon />

        <div class="report-section">
          <h3>能力维度</h3>
          <div v-if="report.evaluation.dimensions.length" class="dimension-list">
            <div v-for="dimension in report.evaluation.dimensions" :key="dimension.key" class="dimension-item">
              <div class="dimension-item__heading">
                <strong>{{ dimensionLabels[dimension.key] || dimension.key }}</strong>
                <span>{{ dimension.score }} 分 · 权重 {{ Math.round(dimension.weight * 100) }}%</span>
              </div>
              <el-progress :percentage="Math.max(0, Math.min(100, dimension.score))" />
              <p class="pre-wrap">{{ dimension.comment }}</p>
            </div>
          </div>
          <el-empty v-else :image-size="72" description="暂无有效维度评分" />
        </div>

        <div class="responsive-grid report-columns">
          <div class="report-section">
            <h3>表现优点</h3>
            <ul v-if="report.evaluation.strengths.length" class="report-list strengths">
              <li v-for="(item, index) in report.evaluation.strengths" :key="index">{{ item }}</li>
            </ul>
            <el-empty v-else :image-size="64" description="暂无优点记录" />
          </div>
          <div class="report-section">
            <h3>改进建议</h3>
            <ul v-if="report.evaluation.improvements.length" class="report-list improvements">
              <li v-for="(item, index) in report.evaluation.improvements" :key="index">{{ item }}</li>
            </ul>
            <el-empty v-else :image-size="64" description="暂无改进建议" />
          </div>
        </div>

        <div class="report-section">
          <h3>能力差距</h3>
          <div v-if="report.gaps.length" class="gap-list">
            <article v-for="gap in report.gaps" :key="gap.skillCode" class="gap-item">
              <div class="gap-item__heading">
                <strong>{{ gap.skillCode }}</strong>
                <el-tag size="small" :type="gap.evidenceStatus === 'SUFFICIENT' ? 'success' : 'warning'">{{ evidenceStatusLabels[gap.evidenceStatus] || gap.evidenceStatus }}</el-tag>
              </div>
              <div class="gap-scores">
                <span>当前：{{ nullableScore(gap.currentScore) }}</span>
                <span>目标：{{ nullableScore(gap.targetScore) }}</span>
                <span>差距：{{ nullableScore(gap.gap) }}</span>
              </div>
              <p class="pre-wrap">{{ gap.suggestion }}</p>
            </article>
          </div>
          <el-empty v-else :image-size="72" description="暂无能力差距记录" />
        </div>
      </template>
    </AsyncState>
  </section>
</template>

<style scoped lang="scss">
.report-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.report-summary { display: grid; grid-template-columns: 220px 1fr; gap: 16px; }
.score-card { display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 150px; padding: 18px; border-radius: $ai-radius-lg; color: #fff; background: linear-gradient(145deg, var(--ai-primary-solid, $ai-primary), var(--ai-primary-solid, #668fec)); }
.score-card strong { margin: 8px 0; font-size: 44px; line-height: 1.1; }
.score-card--empty { color: $ai-text-primary; background: $ai-bg-light; }
.score-card--empty strong { font-size: 25px; }
.report-facts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.report-facts > div { display: flex; flex-direction: column; justify-content: center; gap: 10px; padding: 18px; border: 1px solid $ai-border; border-radius: $ai-radius; }
.report-facts span { color: $ai-text-secondary; }
.report-facts strong { font-size: 18px; }
.report-notice { margin-top: 16px; }
.report-section { margin-top: 20px; }
.report-section h3 { margin: 0 0 12px; color: var(--ai-text-primary, #101d4b); font-size: 16px; }
.dimension-list, .gap-list { display: grid; gap: 12px; }
.dimension-item, .gap-item { padding: 14px; border: 1px solid $ai-border; border-radius: $ai-radius; }
.dimension-item__heading, .gap-item__heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.dimension-item__heading span { color: $ai-text-secondary; }
.dimension-item p, .gap-item p { margin: 10px 0 0; color: $ai-text-secondary; }
.report-columns { gap: 20px; }
.report-list { display: grid; gap: 10px; margin: 0; padding: 0; list-style: none; }
.report-list li { position: relative; padding: 12px 12px 12px 32px; border-radius: $ai-radius; line-height: 1.6; }
.report-list li::before { position: absolute; left: 14px; font-weight: 700; }
.strengths li { background: var(--ai-success-bg, #ecfdf5); }
.strengths li::before { content: '✓'; color: $ai-success; }
.improvements li { background: var(--ai-warning-bg, #fff7ed); }
.improvements li::before { content: '↑'; color: $ai-warning; }
.gap-scores { display: flex; flex-wrap: wrap; gap: 8px 20px; color: $ai-text-secondary; }
@media (max-width: 850px) {
  .report-summary { grid-template-columns: 1fr; }
}
@media (max-width: 700px) {
  .report-facts { grid-template-columns: 1fr; }
}
</style>
