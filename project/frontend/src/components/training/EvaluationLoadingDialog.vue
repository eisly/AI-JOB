<script setup lang="ts">
import { computed } from 'vue'
import { Check, WarningFilled } from '@element-plus/icons-vue'

/**
 * AI 评估加载弹窗：交卷后以遮罩层形式展示分阶段评估进度，
 * 避免交卷后页面"卡住"的无反馈状态。
 */
const props = withDefaults(defineProps<{
  visible: boolean
  /** 当前阶段下标：0=校验客观题 1=评估主观题 2=生成训练反馈报告 */
  stage?: number
  /** 0-100 整体进度，不传则按阶段估算 */
  progress?: number | null
  /** 是否有客观题（无则隐藏第一步） */
  hasObjective?: boolean
  error?: string | null
}>(), {
  stage: 0,
  progress: null,
  hasObjective: true,
  error: null,
})

const emit = defineEmits<{ close: [] }>()

interface StepItem { key: string; label: string; desc: string }

const steps = computed<StepItem[]>(() => {
  const list: StepItem[] = []
  if (props.hasObjective) list.push({ key: 'objective', label: '客观题答案校验', desc: '正在比对其选项与填空答案…' })
  list.push({ key: 'subjective', label: '主观题答案评估', desc: '正在结合场景、代码与要点深度评估…' })
  list.push({ key: 'report', label: '训练反馈报告生成', desc: '正在汇总得分、优点与改进建议…' })
  return list
})

const activeIndex = computed(() => {
  const base = props.hasObjective ? props.stage : Math.max(0, props.stage - 1)
  return Math.min(base, steps.value.length - 1)
})

const shownProgress = computed(() => {
  if (typeof props.progress === 'number') return Math.max(0, Math.min(100, Math.round(props.progress)))
  const stepBase = props.hasObjective ? [10, 45, 85] : [20, 65, 92]
  return stepBase[activeIndex.value] ?? 10
})

function stepState(index: number): 'done' | 'active' | 'pending' | 'error' {
  if (props.error && index >= activeIndex.value) return 'pending'
  if (index < activeIndex.value) return 'done'
  if (index === activeIndex.value) return props.error ? 'error' : 'active'
  return 'pending'
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    width="460px"
    align-center
    :show-close="!!error"
    :close-on-click-modal="false"
    :close-on-press-escape="!!error"
    class="evaluation-dialog"
    @update:model-value="value => { if (!value) emit('close') }"
  >
    <div class="evaluation-dialog__body">
      <div class="evaluation-dialog__icon" :class="{ 'evaluation-dialog__icon--error': !!error }">
        <span v-if="!error" class="evaluation-dialog__spark" />
        <el-icon v-else :size="30"><WarningFilled /></el-icon>
      </div>
      <h3 class="evaluation-dialog__title">{{ error ? '评估中断' : 'AI 正在评估你的答案' }}</h3>
      <p class="evaluation-dialog__sub">
        {{ error ? error : '已收到本组题目的作答，正在结合知识掌握情况与主观表达深度进行综合分析。' }}
      </p>

      <ul class="evaluation-dialog__steps">
        <li v-for="(step, index) in steps" :key="step.key" class="step" :class="`step--${stepState(index)}`">
          <span class="step__icon">
            <el-icon v-if="stepState(index) === 'done'"><Check /></el-icon>
            <span v-else-if="stepState(index) === 'active'" class="step__spinner" />
            <span v-else class="step__dot" />
          </span>
          <span class="step__text">
            <strong>{{ step.label }}</strong>
            <em v-if="stepState(index) === 'active' && !error">{{ step.desc }}</em>
          </span>
        </li>
      </ul>

      <el-progress
        class="evaluation-dialog__progress"
        :percentage="error ? 100 : shownProgress"
        :status="error ? 'exception' : undefined"
        :stroke-width="6"
        :show-text="false"
      />
      <p class="evaluation-dialog__tip">
        评估通常在一分钟左右完成，完成后将自动进入训练反馈报告。
        <a href="#" class="evaluation-dialog__home" @click.prevent="emit('close')">返回训练主页</a>
      </p>
    </div>
  </el-dialog>
</template>

<style scoped lang="scss">
.evaluation-dialog__body {
  display: grid;
  justify-items: center;
  gap: 8px;
  padding: 6px 8px 2px;
  text-align: center;
}

.evaluation-dialog__icon {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 62px;
  height: 62px;
  margin-bottom: 4px;
  border-radius: 50%;
  color: $ai-primary;
  background: $ai-primary-light;

  &::after {
    content: '';
    position: absolute;
    inset: -6px;
    border: 2px solid rgba(37, 99, 235, 0.18);
    border-top-color: $ai-primary;
    border-radius: 50%;
    animation: evaluation-spin 1.1s linear infinite;
  }

  &--error {
    color: $ai-danger;
    background: #fef2f2;

    &::after { animation: none; border: none; }
  }
}

.evaluation-dialog__spark {
  width: 16px;
  height: 16px;
  border-radius: 4px;
  background: $ai-primary;
  clip-path: polygon(50% 0, 62% 38%, 100% 50%, 62% 62%, 50% 100%, 38% 62%, 0 50%, 38% 38%);
}

@keyframes evaluation-spin {
  to { transform: rotate(360deg); }
}

.evaluation-dialog__title { margin: 0; font-size: 18px; color: $ai-text-primary; }
.evaluation-dialog__sub {
  max-width: 340px;
  margin: 0;
  color: $ai-text-secondary;
  font-size: 13px;
  line-height: 1.6;
}

.evaluation-dialog__steps {
  display: grid;
  gap: 12px;
  width: 100%;
  margin: 14px 0 4px;
  padding: 14px 16px;
  border-radius: $ai-radius;
  background: $ai-bg-page;
  text-align: left;
  list-style: none;
}

.step { display: flex; align-items: flex-start; gap: 10px; }
.step__icon {
  display: flex;
  flex: 0 0 22px;
  align-items: center;
  justify-content: center;
  height: 22px;
  margin-top: 1px;
  border-radius: 50%;
  font-size: 13px;
}
.step--done .step__icon { color: $ai-success; background: #ecfdf5; }
.step--active .step__icon { color: $ai-primary; background: $ai-primary-light; }
.step--error .step__icon { color: $ai-danger; background: #fef2f2; }
.step--pending .step__icon { color: $ai-text-tertiary; background: $ai-bg-light; }
.step__spinner {
  width: 12px;
  height: 12px;
  border: 2px solid rgba(37, 99, 235, 0.25);
  border-top-color: $ai-primary;
  border-radius: 50%;
  animation: evaluation-spin 0.9s linear infinite;
}
.step__dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; opacity: 0.5; }
.step__text { display: grid; gap: 2px; font-size: 13px; }
.step__text strong { color: $ai-text-primary; font-weight: 600; }
.step--pending .step__text strong { color: $ai-text-secondary; font-weight: 500; }
.step__text em { color: $ai-text-secondary; font-size: 12px; font-style: normal; }

.evaluation-dialog__progress { width: 100%; }
.evaluation-dialog__tip { margin: 6px 0 0; color: $ai-text-secondary; font-size: 12px; }
.evaluation-dialog__home { margin-left: 4px; color: $ai-primary; }
</style>
