<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { menuItems } from '@/config/menu'
import { useUserStore } from '@/stores/user'
import UserAvatar from '@/components/UserAvatar.vue'

const route = useRoute()
const userStore = useUserStore()
const activeMenu = computed(() => '/' + (route.path.split('/')[1] || 'dashboard'))
</script>

<template>
  <aside class="sidebar">
    <div class="sidebar__logo">AI-JOB</div>
    <el-menu :default-active="activeMenu" class="sidebar__menu" router>
      <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path"
        :title="item.title" :aria-label="item.title">
        <el-icon><component :is="item.icon" /></el-icon>
        <span class="sidebar__menu-title">{{ item.title }}</span>
        <span v-if="item.badge" class="sidebar__badge">{{ item.badge }}</span>
      </el-menu-item>
    </el-menu>
    <div class="sidebar__user">
      <UserAvatar :size="42" class="sidebar__avatar" />
      <div>
        <div class="sidebar__user-name">{{ userStore.userInfo?.nickname || '用户' }}</div>
        <div class="sidebar__user-major">{{ userStore.userInfo?.email || '求职信息尚未完善' }}</div>
        <router-link class="sidebar__edit-link" to="/resume">编辑简历 ›</router-link>
      </div>
    </div>
  </aside>
</template>

<style scoped lang="scss">
.sidebar { width:220px; height:100vh; background:var(--ai-bg-light, #f5faff); border-right:1px solid var(--ai-border-light, #e2edf9); display:flex; flex-direction:column; flex-shrink:0; }
.sidebar__logo { height:76px; padding:18px 28px; font-size:28px; font-weight:800; color:var(--ai-primary, #0759ef); letter-spacing:-.5px; }
.sidebar__menu { flex:1; border:0; background:transparent; overflow-y:auto; padding:0 14px; }
.sidebar__menu :deep(.el-menu-item) { height:47px; line-height:47px; margin-bottom:3px; padding:0 14px !important; border-radius:7px; color:var(--ai-text-primary, #101d4b); font-size:15px; font-weight:500; }
.sidebar__menu :deep(.el-menu-item .el-icon) { color:inherit; font-size:19px; margin-right:14px; }
.sidebar__menu :deep(.el-menu-item.is-active) { background:var(--ai-primary-solid, #286ef2); color:white; }
.sidebar__menu :deep(.el-menu-item:not(.is-active):hover) { background:var(--ai-primary-light, #e9f2ff); }
.sidebar__badge { margin-left:auto; padding:2px 7px; border-radius:12px; background:var(--ai-primary-light, #e5eefc); color:var(--ai-text-secondary, #6b7fa4); font-size:11px; line-height:18px; font-weight:400; }
.sidebar__user { padding:18px 22px 22px; display:flex; gap:12px; align-items:flex-start; }
.sidebar__avatar { background:var(--ai-primary-solid, #3868b2); color:#fff; flex-shrink:0; }
.sidebar__user-name { font-weight:600; color:var(--ai-text-primary, #101d4b); }
.sidebar__user-major { max-width:125px; overflow:hidden; text-overflow:ellipsis; font-size:11px; color:var(--ai-text-secondary, #7184a6); margin-top:4px; white-space:nowrap; }
.sidebar__edit-link { display:inline-block; color:var(--ai-primary, #1764f5); font-size:13px; margin-top:10px; }
@media(max-width:700px) { .sidebar{width:64px}.sidebar__logo{font-size:13px;padding:24px 6px;text-align:center}.sidebar__menu{padding:0 7px}.sidebar__menu :deep(.el-menu-item){justify-content:center;padding:0 !important}.sidebar__menu :deep(.el-menu-item .el-icon){margin:0}.sidebar__menu-title{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}.sidebar__badge,.sidebar__user > div{display:none}.sidebar__user{padding:12px 11px} }
</style>
