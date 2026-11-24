<script setup lang="ts">
import { Bell, ArrowDown, ArrowRight } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import ThemeToggle from '@/components/ThemeToggle.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { errorMessage } from '@/utils/format'

const router = useRouter()
const userStore = useUserStore()
defineProps<{ section?: string }>()

async function logout() {
  try {
    await userStore.logout()
    await router.replace('/login')
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  }
}
</script>

<template>
  <header class="header">
    <div v-if="section" class="header__location"><span>学习与成长</span><el-icon><ArrowRight /></el-icon><strong>{{ section }}</strong></div>
    <ThemeToggle />
    <el-button class="header__notification" text circle title="打开任务中心" @click="router.push('/tasks')">
      <el-icon :size="21"><Bell /></el-icon>
    </el-button>
    <el-dropdown trigger="click">
      <div class="header__user">
        <UserAvatar :size="38" class="header__avatar" />
        <span class="header__name">{{ userStore.userInfo?.nickname || '用户' }}</span>
        <el-icon><ArrowDown /></el-icon>
      </div>
      <template #dropdown>
        <el-dropdown-menu>
          <!-- U04：入口语义与实际页面一致——该页面是求职画像（目标岗位/城市/求职类型偏好），不是账号设置 -->
          <el-dropdown-item @click="router.push('/profile')">求职画像</el-dropdown-item>
          <el-dropdown-item @click="router.push('/account')">账号设置</el-dropdown-item>
          <el-dropdown-item divided @click="logout">退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </header>
</template>

<style scoped lang="scss">
.header { flex:0 0 64px; display:flex; justify-content:flex-end; align-items:center; gap:18px; padding:0 24px; color:var(--ai-text-secondary, #445e90); }
.header__notification, .header__user { pointer-events:auto; }
.header__notification { color:var(--ai-text-secondary, #445e90); }
.header__user { display:flex; align-items:center; gap:10px; cursor:pointer; outline:none; }
.header__avatar { background:var(--ai-primary-solid, #3868b2); color:#fff; }
.header__name { max-width:120px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.header__location { display:flex; align-items:center; gap:10px; margin-right:auto; font-size:13px; color:$ai-text-secondary; }
.header__location strong { color:$ai-text-primary; }
@media(max-width:460px){.header__location span,.header__location .el-icon{display:none}}
@media(max-width:700px){.header{padding:0 12px}.header__name{display:none}}
</style>
