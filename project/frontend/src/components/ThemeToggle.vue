<script setup lang="ts">
import { Moon, Sunny } from '@element-plus/icons-vue'
import { isDarkTheme, toggleTheme } from '@/stores/theme'
</script>

<template>
  <button
    type="button"
    class="theme-toggle"
    aria-label="深色模式"
    :aria-pressed="isDarkTheme"
    :title="isDarkTheme ? '切换到浅色模式' : '切换到深色模式'"
    @click="toggleTheme"
  >
    <el-icon :size="17" aria-hidden="true"><Sunny v-if="isDarkTheme" /><Moon v-else /></el-icon>
  </button>
</template>

<style scoped lang="scss">
.theme-toggle {
  display: inline-grid;
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  place-items: center;
  border: 1px solid $ai-border;
  border-radius: 9px;
  background: $ai-bg-card;
  color: $ai-text-secondary;
  cursor: pointer;
}

.theme-toggle:hover { color: $ai-primary; background: $ai-primary-light; }
.theme-toggle:focus-visible { outline: 2px solid $ai-primary; outline-offset: 2px; }
</style>
