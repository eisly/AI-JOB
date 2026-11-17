<script setup lang="ts">
const props = defineProps<{
  modelValue: string
  disabled?: boolean
  loading?: boolean
  canSubmit?: boolean
  emptyHint?: string
}>()
const emit = defineEmits<{
  'update:modelValue': [value: string]
  submit: []
  discard: []
}>()
</script>

<template>
  <div class="transcription-editor">
    <div class="transcription-editor__heading">
      <strong>识别结果（需你确认）</strong>
      <span class="muted">可直接修改错别字，提交内容以你确认后的文字为准</span>
    </div>
    <el-input
      :model-value="props.modelValue"
      type="textarea"
      :rows="7"
      maxlength="10000"
      show-word-limit
      resize="vertical"
      :disabled="disabled"
      placeholder="识别文本会显示在这里，也可以直接输入文字回答"
      @update:model-value="(value: string) => emit('update:modelValue', value)"
    />
    <el-alert v-if="emptyHint" class="transcription-editor__hint" :title="emptyHint" type="warning" :closable="false" show-icon />
    <div class="transcription-editor__actions">
      <el-button :disabled="disabled || loading" @click="emit('discard')">放弃本次录音</el-button>
      <el-button type="primary" :loading="loading" :disabled="disabled || !canSubmit" @click="emit('submit')">
        确认并提交
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.transcription-editor__heading {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
  margin-bottom: 8px;
}
.transcription-editor__hint {
  margin-top: 10px;
}
.transcription-editor__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}
.muted {
  color: #909399;
  font-size: 12px;
}
</style>