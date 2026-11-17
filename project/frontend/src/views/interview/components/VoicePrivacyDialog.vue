<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  modelValue: boolean
  policyVersion: string
  loading?: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  agree: []
  reject: []
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})
</script>

<template>
  <el-dialog v-model="visible" title="语音作答隐私告知" width="520px" :close-on-click-modal="false">
    <div class="privacy-body">
      <p>开始录音前请阅读以下内容：</p>
      <ul>
        <li>你的录音会发送至第三方语音识别服务，仅用于把本题回答转写为文字。</li>
        <li>识别完成后音频不会长期保存，系统只保存你确认后的文字回答。</li>
        <li>你可以随时改用文字作答，已提交的文字回答不受影响。</li>
        <li>请勿在回答中提供身份证号、银行卡号等敏感个人信息。</li>
      </ul>
      <p class="muted">隐私告知版本：{{ policyVersion }}</p>
    </div>
    <template #footer>
      <el-button :disabled="loading" @click="emit('reject')">不同意，改用文字</el-button>
      <el-button type="primary" :loading="loading" @click="emit('agree')">同意并开始录音</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.privacy-body ul {
  margin: 8px 0 12px;
  padding-left: 20px;
  line-height: 1.8;
}
.muted {
  color: #909399;
  font-size: 12px;
}
</style>