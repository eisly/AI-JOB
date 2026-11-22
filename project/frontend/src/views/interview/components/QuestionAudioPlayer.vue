<script setup lang="ts">
import { computed, toRef } from 'vue'
import { useQuestionSpeech } from '@/composables/voice/useQuestionSpeech'

const props = defineProps<{
  interviewId: string
  messageId: string
  text: string
  disabled?: boolean
}>()

const speech = useQuestionSpeech(toRef(props, 'interviewId'))
const label = computed(() => {
  if (speech.playing.value) return '暂停播报'
  if (speech.paused.value) return '继续播报'
  return '播放题目'
})

function toggle() {
  if (props.disabled) return
  if (speech.playing.value) {
    speech.pause()
  } else if (speech.paused.value) {
    speech.resume()
  } else {
    void speech.play(props.messageId, props.text)
  }
}

function replay() {
  if (props.disabled) return
  void speech.replay(props.messageId, props.text)
}

defineExpose({
  stop: () => speech.stop(),
  play: () => speech.play(props.messageId, props.text),
})
</script>

<template>
  <div class="question-player">
    <div class="question-player__actions">
      <el-button size="small" type="primary" plain :disabled="disabled" :aria-label="label" @click="toggle">
        {{ label }}
      </el-button>
      <el-button size="small" :disabled="disabled || (!speech.playing.value && !speech.paused.value)" @click="replay">
        重新播放
      </el-button>
      <el-button size="small" :disabled="disabled || !speech.active.value" @click="speech.stop()">停止播报</el-button>
      <el-tag v-if="speech.degraded.value" size="small" type="warning">使用浏览器语音</el-tag>
    </div>
    <p v-if="speech.degraded.value" class="muted">题目语音服务暂不可用，已降级为浏览器本地朗读。</p>
    <p v-else-if="speech.error.value" class="muted">题目语音暂不可用，可直接阅读文字作答。</p>
  </div>
</template>

<style scoped>
.question-player {
  margin-bottom: 12px;
}
.question-player__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.muted {
  color: var(--ai-text-tertiary, #909399);
  font-size: 12px;
  margin: 6px 0 0;
}
</style>