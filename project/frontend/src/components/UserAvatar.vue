<script setup lang="ts">
import { onMounted } from 'vue'
import { User } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

withDefaults(defineProps<{ size?: number; version?: number }>(), { size: 38 })

const userStore = useUserStore()

onMounted(() => { void userStore.ensureAvatarLoaded() })
</script>

<template>
  <el-avatar v-if="userStore.avatarObjectUrl && !userStore.avatarFailed" :size="size" :src="userStore.avatarObjectUrl"
    class="user-avatar" @error="userStore.markAvatarFailed()" />
  <el-avatar v-else :size="size" class="user-avatar">
    <el-icon><User /></el-icon>
  </el-avatar>
</template>

<style scoped lang="scss">
.user-avatar {
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--ai-primary-solid, #3868b2);
  color: #fff;
  overflow: hidden;
}
.user-avatar :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
</style>