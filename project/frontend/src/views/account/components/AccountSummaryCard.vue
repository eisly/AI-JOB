<script setup lang="ts">
import { computed } from 'vue'
import UserAvatar from '@/components/UserAvatar.vue'
import type { AccountProfileVO } from '@/api/types'

const props = defineProps<{ profile: AccountProfileVO }>()

const phoneText = computed(() => props.profile.phoneMasked || '未绑定')

const registeredAt = computed(() => formatDateTime(props.profile.createdAt))

const updatedAt = computed(() => formatDateTime(props.profile.profileUpdatedAt) || '暂无修改记录')

function formatDateTime(value: string | null | undefined): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <section class="summary-card">
    <div class="summary-card__avatar">
      <UserAvatar :size="96" />
    </div>
    <div class="summary-card__identity">
      <h2 class="summary-card__name">{{ profile.nickname }}</h2>
      <p class="summary-card__email">{{ profile.email }}</p>
      <p class="summary-card__hint">登录邮箱不可修改</p>
    </div>
    <dl class="summary-card__facts">
      <div class="summary-card__fact">
        <dt>手机号</dt>
        <dd>{{ phoneText }}</dd>
      </div>
      <div class="summary-card__fact">
        <dt>注册时间</dt>
        <dd>{{ registeredAt || '—' }}</dd>
      </div>
      <div class="summary-card__fact">
        <dt>资料保存时点</dt>
        <dd>{{ updatedAt }}</dd>
      </div>
    </dl>
  </section>
</template>

<style scoped lang="scss">
.summary-card {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 24px;
  border-radius: 12px;
  background: var(--ai-bg-card, #fff);
  border: 1px solid var(--ai-border-light, #e2edf9);
}
.summary-card__avatar { display: flex; justify-content: center; }
.summary-card__identity { text-align: center; }
.summary-card__name { margin: 0; font-size: 20px; color: $ai-text-primary; overflow-wrap: anywhere; }
.summary-card__email { margin: 6px 0 0; color: $ai-text-secondary; font-size: 13px; overflow-wrap: anywhere; }
.summary-card__hint { margin: 4px 0 0; font-size: 12px; color: $ai-text-secondary; }
.summary-card__facts { margin: 0; display: grid; gap: 10px; }
.summary-card__fact { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; }
.summary-card__fact dt { color: $ai-text-secondary; }
.summary-card__fact dd { margin: 0; color: $ai-text-primary; text-align: right; overflow-wrap: anywhere; }
</style>