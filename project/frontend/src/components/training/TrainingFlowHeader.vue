<script setup lang="ts">
import { ArrowLeft } from '@element-plus/icons-vue'
import ThemeToggle from '@/components/ThemeToggle.vue'

withDefaults(defineProps<{ title: string; subtitle?: string; backLabel?: string; disabled?: boolean }>(), {
  backLabel: '返回训练计划',
})
defineEmits<{ back: [] }>()
</script>

<template>
  <header class="flow-header">
    <div class="flow-header__context">
      <el-button :icon="ArrowLeft" type="primary" plain :disabled="disabled" @click="$emit('back')">{{ backLabel }}</el-button>
      <div class="flow-header__title">
        <strong>{{ title }}</strong>
        <span v-if="subtitle">{{ subtitle }}</span>
      </div>
    </div>
    <div class="flow-header__actions">
      <ThemeToggle />
      <slot />
    </div>
  </header>
</template>

<style scoped lang="scss">
.flow-header { display:flex; align-items:center; justify-content:space-between; gap:12px; min-height:72px; padding:12px clamp(16px, 3vw, 44px); border-bottom:1px solid $ai-border-light; background:$ai-bg-card; }
.flow-header__context,.flow-header__actions { display:flex; align-items:center; gap:12px; }
.flow-header__context { min-width:0; }
.flow-header__title { display:grid; min-width:0; gap:3px; border-left:1px solid $ai-border; padding-left:12px; overflow-wrap:anywhere; }
.flow-header__title span { color:$ai-text-secondary; font-size:12px; }
@media (max-width: 560px) {
  .flow-header { flex-wrap:wrap; }
  .flow-header__context { flex:1; }
  .flow-header__actions { flex-wrap:wrap; }
}
</style>
