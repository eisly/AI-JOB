<script setup lang="ts">
withDefaults(defineProps<{ text?: string; title?: string }>(), {
  text: '',
  title: '模型思考过程',
})
</script>

<template>
  <el-collapse v-if="text" class="model-reasoning">
    <el-collapse-item :title="title" name="reasoning">
      <pre class="model-reasoning__text">{{ text }}</pre>
    </el-collapse-item>
  </el-collapse>
</template>

<style scoped lang="scss">
.model-reasoning { margin-top: 4px; border-top: 1px dashed $ai-border; }
.model-reasoning__text { max-height: 320px; margin: 0; overflow: auto; white-space: pre-wrap; word-break: break-word; color: $ai-text-secondary; font-size: 12px; line-height: 1.7; }
</style>