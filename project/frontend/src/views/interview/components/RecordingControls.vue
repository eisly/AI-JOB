<script setup lang="ts">
import { computed } from 'vue'
import { secondsLabel } from '@/utils/voice/limits'
import type { RecorderState } from '@/composables/voice/useVoiceRecorder'

const props = defineProps<{
  state: RecorderState
  durationMs: number
  maxDurationSeconds: number
  audioUrl: string
  disabled?: boolean
  reachedLimit?: boolean
  tooShort?: boolean
  transcribing?: boolean
}>()
const emit = defineEmits<{
  start: []
  stop: []
  retry: []
  transcribe: []
}>()

const elapsed = computed(() => secondsLabel(props.durationMs / 1000))
const limit = computed(() => secondsLabel(props.maxDurationSeconds))
const stateLabel = computed(() => {
  switch (props.state) {
    case 'requesting':
      return '正在申请麦克风权限…'
    case 'recording':
      return `正在录音 ${elapsed.value} / ${limit.value}`
    case 'recorded':
      return `录音完成，时长 ${elapsed.value}`
    case 'denied':
      return '麦克风权限被拒绝'
    case 'unavailable':
      return '当前浏览器或设备无法录音'
    default:
      return '尚未开始录音'
  }
})
</script>

<template>
  <div class="recording-controls">
    <div class="recording-controls__status">
      <span class="status-dot" :class="{ recording: state === 'recording' }" />
      <span>{{ stateLabel }}</span>
    </div>
    <div class="recording-controls__actions">
      <el-button v-if="state === 'idle' || state === 'denied' || state === 'unavailable'" type="primary" :disabled="disabled" aria-label="开始录音，最多 3 分钟" @click="emit('start')">
        开始录音
      </el-button>
      <el-button v-if="state === 'recording'" type="danger" @click="emit('stop')">停止录音</el-button>
      <template v-if="state === 'recorded'">
        <audio v-if="audioUrl" :src="audioUrl" controls class="recording-controls__audio" />
        <el-button :disabled="disabled || transcribing" @click="emit('retry')">重新录音</el-button>
        <el-button type="primary" :loading="transcribing" :disabled="disabled || tooShort" @click="emit('transcribe')">
          转写为文字
        </el-button>
      </template>
    </div>
    <el-alert v-if="reachedLimit" class="recording-controls__hint" title="已达到单题录音时长上限，已自动停止" type="warning" :closable="false" show-icon />
    <el-alert v-else-if="tooShort" class="recording-controls__hint" title="录音时间过短，请至少录制 2 秒" type="warning" :closable="false" show-icon />
  </div>
</template>

<style scoped>
.recording-controls__status {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  color: #606266;
}
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c0c4cc;
}
.status-dot.recording {
  background: #f56c6c;
  animation: pulse 1s infinite;
}
.recording-controls__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.recording-controls__audio {
  width: 100%;
  max-width: 320px;
}
.recording-controls__hint {
  margin-top: 10px;
}
@keyframes pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}
</style>