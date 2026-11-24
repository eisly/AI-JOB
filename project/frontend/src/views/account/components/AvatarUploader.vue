<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { accountApi } from '@/api/account'
import type { AccountProfileVO } from '@/api/types'
import { avatarValidation } from '../account-rules'
import UserAvatar from '@/components/UserAvatar.vue'

const props = defineProps<{ profile: AccountProfileVO }>()
const emit = defineEmits<{ saved: [profile: AccountProfileVO] }>()

const inputRef = ref<HTMLInputElement>()
const selectedFile = ref<File | null>(null)
const previewUrl = ref('')
const error = ref('')
const saving = ref(false)

function clearPreview() {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = ''
  }
}

onUnmounted(clearPreview)

function choose() {
  if (saving.value) return
  inputRef.value?.click()
}

function onChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  input.value = ''
  clearPreview()
  selectedFile.value = null
  error.value = ''
  if (!file) return
  const invalid = avatarValidation(file)
  if (invalid) {
    error.value = invalid
    return
  }
  selectedFile.value = file
  previewUrl.value = URL.createObjectURL(file)
}

function cancel() {
  clearPreview()
  selectedFile.value = null
  error.value = ''
}

async function save() {
  if (saving.value || !selectedFile.value) return
  saving.value = true
  try {
    const updated = await accountApi.uploadAvatar(selectedFile.value)
    emit('saved', updated)
    cancel()
    ElMessage.success('头像已更新')
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '头像上传失败'
  } finally {
    saving.value = false
  }
}

async function resetDefault() {
  if (saving.value) return
  saving.value = true
  error.value = ''
  try {
    const updated = await accountApi.resetAvatar()
    emit('saved', updated)
    cancel()
    ElMessage.success('已恢复默认头像')
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '恢复默认头像失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="avatar-card">
    <h2 class="avatar-card__title">头像</h2>
    <div class="avatar-card__preview">
      <img v-if="previewUrl" :src="previewUrl" alt="头像预览" class="avatar-card__image" />
      <UserAvatar v-else :size="96" />
    </div>
    <input ref="inputRef" type="file" accept="image/jpeg,image/png,image/webp" class="avatar-card__input" @change="onChange" />
    <div class="avatar-card__actions">
      <el-button size="small" @click="choose">选择图片</el-button>
      <el-button v-if="selectedFile" size="small" type="primary" :loading="saving" @click="save">保存头像</el-button>
      <el-button v-if="selectedFile" size="small" :disabled="saving" @click="cancel">取消</el-button>
      <el-button v-if="!selectedFile && profile.avatarUrl" size="small" :loading="saving" text @click="resetDefault">
        恢复默认头像
      </el-button>
    </div>
    <p class="avatar-card__hint">支持 JPG、PNG、WebP，大小不超过 2 MB</p>
    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
  </section>
</template>

<style scoped lang="scss">
.avatar-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 20px 24px;
  border-radius: 12px;
  background: var(--ai-bg-card, #fff);
  border: 1px solid var(--ai-border-light, #e2edf9);
}
.avatar-card__title { margin: 0; font-size: 16px; color: $ai-text-primary; }
.avatar-card__preview { width: 96px; height: 96px; border-radius: 50%; overflow: hidden; }
.avatar-card__image { width: 100%; height: 100%; object-fit: cover; display: block; }
.avatar-card__input { display: none; }
.avatar-card__actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: center; }
.avatar-card__hint { margin: 0; font-size: 12px; color: $ai-text-secondary; text-align: center; }
</style>