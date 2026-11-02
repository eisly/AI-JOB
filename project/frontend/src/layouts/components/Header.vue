<script setup lang="ts">
import { Bell, ArrowDown, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

function handleCommand(command: string): void {
  if (command === 'settings') {
    void router.push('/account')
  } else if (command === 'logout') {
    void signOut()
  }
}

async function signOut(): Promise<void> {
  await auth.logout()
  ElMessage.success('已退出登录')
  await router.push('/login')
}
</script>
<template><header class="header"><span class="header__demo-tag">演示数据</span><div class="header__actions"><el-badge is-dot class="header__bell"><el-icon :size="21"><Bell /></el-icon></el-badge><el-dropdown trigger="click" @command="handleCommand"><div class="header__user"><el-avatar :size="38" class="header__avatar"><el-icon><User /></el-icon></el-avatar><el-icon><ArrowDown /></el-icon></div><template #dropdown><el-dropdown-menu><el-dropdown-item command="settings">个人设置</el-dropdown-item><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div></header></template>
<style scoped lang="scss">
.header { height: 64px; flex-shrink: 0; display: flex; align-items: center; justify-content: space-between; padding: 0 24px; background: $ai-bg-card; border-bottom: 1px solid #e2edf9; }
.header__demo-tag { padding: 3px 10px; border-radius: 12px; background: #e5eefc; color: #6b7fa4; font-size: 12px; }
.header__actions { display: flex; align-items: center; gap: 28px; color: #445e90; }
.header__bell { display: flex; align-items: center; }
.header__user { display: flex; align-items: center; gap: 10px; cursor: pointer; }
.header__avatar { background: #3868b2; color: #fff; }
</style>
