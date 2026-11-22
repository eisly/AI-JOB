<script setup lang="ts">
import { computed } from 'vue'
import { CopyDocument } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { stemSegments } from '@/utils/training-content'
import type { PracticeAnswerPayload, PracticeQuestionVO } from '@/api/types'

/**
 * 题型作答组件：按题型（选择题/填空题/主观题）渲染题干与作答控件。
 * 作答数据通过 answer 双向绑定，由父组件统一负责草稿保存与交卷。
 */
const props = defineProps<{
  question: PracticeQuestionVO
  answer: PracticeAnswerPayload
  /** 结果模式下只读，并展示判定/评估 */
  readonly?: boolean
  correctKeys?: string[]
}>()

const emit = defineEmits<{ 'update:answer': [value: PracticeAnswerPayload] }>()

const optionLetterIndex = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H']

const blanks = computed<string[]>(() => {
  const value = props.answer.blanks
  const count = Math.max(1, props.question.blankCount || 1)
  return Array.from({ length: count }, (_, i) => value?.[i] ?? '')
})

const typeLabel = computed(() => {
  return ({ CHOICE: '选择题', FILL: '填空题', SUBJECTIVE: '主观题' } as Record<string, string>)[props.question.type] || props.question.type
})

const difficultyLabel = computed(() => {
  return ({ EASY: '简单', MEDIUM: '中等', HARD: '困难' } as Record<string, string>)[props.question.difficulty] || props.question.difficulty
})

const isObjective = computed(() => props.question.type !== 'SUBJECTIVE')

function patch(patchValue: Partial<PracticeAnswerPayload>) {
  if (props.readonly) return
  emit('update:answer', { ...props.answer, ...patchValue })
}

function selectChoice(key: string) {
  patch({ choiceKey: props.answer.choiceKey === key ? undefined : key })
}

function setBlank(index: number, value: string) {
  const next = [...blanks.value]
  next[index] = value
  patch({ blanks: next })
}

function isCorrectChoice(key: string) {
  return !!props.correctKeys?.includes(key)
}

async function copyCode(content: string) {
  try {
    await navigator.clipboard.writeText(content)
    ElMessage.success('代码已复制')
  } catch {
    ElMessage.warning('复制未成功，请选中代码后手动复制')
  }
}
</script>

<template>
  <article class="question-renderer">
    <header class="question-renderer__meta">
      <div class="question-renderer__tags">
        <span class="q-tag q-tag--type">{{ typeLabel }}</span>
        <span v-for="skill in question.skillCodes" :key="skill" class="q-tag q-tag--skill">{{ skill }}</span>
        <span class="q-tag q-tag--plain">{{ difficultyLabel }}</span>
        <span v-if="isObjective" class="q-tag q-tag--plain">客观题 · 自动判分</span>
      </div>
      <span class="question-renderer__position">
        建议用时 {{ question.estimatedMinutes }} 分钟 · {{ question.score }} 分
      </span>
    </header>

    <h2 class="question-renderer__stem-label">题目</h2>
    <template v-for="(segment, index) in stemSegments(question.stem)" :key="index">
      <div v-if="segment.type === 'code'" class="question-code">
        <div class="question-code__toolbar">
          <span>{{ segment.language || '代码' }}</span>
          <el-button size="small" text :icon="CopyDocument" :aria-label="`复制第 ${index + 1} 段代码`" @click="copyCode(segment.content)">复制</el-button>
        </div>
        <pre class="question-code__block" tabindex="0"><code>{{ segment.content }}</code></pre>
      </div>
      <p v-else class="question-renderer__stem">{{ segment.content }}</p>
    </template>

    <!-- 选择题 -->
    <div v-if="question.type === 'CHOICE'" class="choice-group" :class="{ 'choice-group--readonly': readonly }" role="radiogroup" aria-label="选择题选项">
      <button
        v-for="option in question.options"
        :key="option.key"
        type="button"
        class="choice-option"
        :class="{
          'choice-option--selected': answer.choiceKey === option.key,
          'choice-option--correct': readonly && isCorrectChoice(option.key),
        }"
        :disabled="readonly"
        role="radio"
        :aria-checked="answer.choiceKey === option.key"
        @click="selectChoice(option.key)"
      >
        <span class="choice-option__key">{{ option.key }}</span>
        <span class="choice-option__content">{{ option.content }}</span>
        <span v-if="readonly && isCorrectChoice(option.key)" class="choice-option__badge">正确答案</span>
      </button>
    </div>

    <!-- 填空题 -->
    <div v-else-if="question.type === 'FILL'" class="fill-group">
      <p class="fill-group__hint">
        共 {{ Math.max(1, question.blankCount || 1) }} 个空，请按顺序填写关键词 / 术语（答案按内容比对判分）。
      </p>
      <div v-for="(_, index) in Math.max(1, question.blankCount || 1)" :key="index" class="fill-item">
        <span class="fill-item__label">第 {{ index + 1 }} 空</span>
        <el-input
          :model-value="blanks[index]"
          :disabled="readonly"
          placeholder="请输入该空答案"
          :aria-label="`第 ${index + 1} 空答案`"
          @update:model-value="value => setBlank(index, value)"
        />
      </div>
    </div>

    <!-- 主观题 -->
    <div v-else class="subjective-group">
      <p class="subjective-group__hint">
        场景 / 项目 / 设计 / 代码类题目：请写出完整分析思路；如需代码请以文本粘贴，系统评估设计与逻辑，不实际运行代码。
      </p>
      <el-input
        :model-value="answer.content || ''"
        type="textarea"
        :rows="12"
        :disabled="readonly"
        maxlength="10000"
        show-word-limit
        resize="vertical"
        placeholder="请输入完整分析与解决方案，草稿会自动保存在当前浏览器"
        aria-label="主观题回答"
        @update:model-value="value => patch({ content: value })"
      />
    </div>
  </article>
</template>

<style scoped lang="scss">
.question-renderer { display: grid; gap: 12px; }

.question-renderer__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.question-renderer__tags { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; }
.question-renderer__position { color: $ai-text-tertiary; font-size: 12px; }

.q-tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 9px;
  border-radius: 5px;
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
}
.q-tag--type { color: $ai-primary; background: $ai-primary-light; font-weight: 600; }
.q-tag--skill { color: var(--ai-text-secondary, #475569); background: $ai-bg-light; }
.q-tag--plain { color: $ai-text-secondary; border: 1px solid $ai-border; background: var(--ai-bg-card, #fff); }

.question-renderer__stem-label { position:absolute; width:1px; height:1px; overflow:hidden; clip-path:inset(50%); white-space:nowrap; }
.question-renderer__stem {
  margin: 0;
  color: var(--ai-text-primary, #101d4b);
  font-size: 20px;
  font-weight: 600;
  line-height: 1.7;
  white-space: pre-wrap;
}

.question-code { min-width: 0; margin: 6px 0; }
.question-code__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: $ai-text-secondary;
  font-size: 12px;
}
.question-code__block {
  margin: 8px 0 0;
  padding: 12px 14px;
  border: 1px solid var(--ai-accent-border, #dbe7fa);
  border-radius: 8px;
  background: var(--ai-bg-light, #f4f7fd);
  overflow-x: auto;
}
.question-code__block code {
  font-family: 'JetBrains Mono', Consolas, Monaco, monospace;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ai-text-secondary, #1f3576);
  white-space: pre;
}

.choice-group { display: grid; gap: 10px; margin-top: 8px; }
.choice-option {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  width: 100%;
  padding: 13px 15px;
  border: 1px solid $ai-border;
  border-radius: $ai-radius;
  background: var(--ai-bg-card, #fff);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s, box-shadow 0.15s;

  &:hover:not(:disabled) { border-color: $ai-primary; }

  &--selected {
    border-color: $ai-primary;
    background: $ai-primary-light;
    box-shadow: 0 0 0 1px $ai-primary inset;
  }

  &--correct {
    border-color: $ai-success;
    background: var(--ai-success-bg, #ecfdf5);
  }

  &:disabled { cursor: default; }
}
.choice-option__key {
  display: flex;
  flex: 0 0 24px;
  align-items: center;
  justify-content: center;
  height: 24px;
  border: 1px solid $ai-border;
  border-radius: 6px;
  color: $ai-text-secondary;
  font-size: 12px;
  font-weight: 700;
}
.choice-option--selected .choice-option__key { border-color: $ai-primary; color: #fff; background: var(--ai-primary-solid, $ai-primary); }
.choice-option--correct .choice-option__key { border-color: $ai-success; color: var(--ai-on-status, #fff); background: $ai-success; }
.choice-option__content { color: var(--ai-text-primary, #1f2937); font-size: 14px; line-height: 1.65; white-space: pre-wrap; }
.choice-option__badge { margin-left: auto; color: $ai-success; font-size: 12px; white-space: nowrap; }

.fill-group { display: grid; gap: 10px; margin-top: 10px; }
.fill-group__hint { margin: 0; color: $ai-text-secondary; font-size: 12px; }
.fill-item { display: flex; align-items: center; gap: 10px; }
.fill-item__label { flex: 0 0 58px; color: $ai-text-secondary; font-size: 13px; }

.subjective-group { display: grid; gap: 8px; margin-top: 10px; }
.subjective-group__hint { margin: 0; color: $ai-text-secondary; font-size: 12px; }

.muted { color: $ai-text-secondary; }
</style>
