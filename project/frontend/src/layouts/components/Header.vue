<script setup lang="ts">
import { Bell, ArrowDown, User } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { errorMessage } from '@/utils/format'

const router = useRouter()
const userStore = useUserStore()

async function logout() {
  try {
    await userStore.logout()
    await router.push('/login')
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  }
}
</script>

<template>
  <header class="header">
    <el-button class="header__notification" text circle title="打开任务中心" @click="router.push('/tasks')">
      <el-icon :size="21"><Bell /></el-icon>
    </el-button>
    <el-dropdown trigger="click">
      <div class="header__user">
        <el-avatar :size="38" class="header__avatar"><el-icon><User /></el-icon></el-avatar>
        <span class="header__name">{{ userStore.userInfo?.nickname || '用户' }}</span>
        <el-icon><ArrowDown /></el-icon>
      </div>
      <template #dropdown>
        <el-dropdown-menu>
          <!-- U04：入口语义与实际页面一致——该页面是求职画像（目标岗位/城市/求职类型偏好），不是账号设置 -->
          <el-dropdown-item @click="router.push('/profile')">求职画像</el-dropdown-item>
          <el-dropdown-item divided @click="logout">退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </header>
</template>

<style scoped lang="scss">
.header { position:absolute; z-index:1; top:16px; right:24px; display:flex; align-items:center; gap:18px; color:#445e90; pointer-events:none; }
.header__notification, .header__user { pointer-events:auto; }
.header__notification { color:#445e90; }
.header__user { display:flex; align-items:center; gap:10px; cursor:pointer; outline:none; }
.header__avatar { background:#3868b2; color:#fff; }
.header__name { max-width:120px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
@media(max-width:700px){.header{right:12px}.header__name{display:none}}
</style>
