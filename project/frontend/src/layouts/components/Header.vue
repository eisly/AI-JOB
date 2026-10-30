<script setup lang="ts">
import { Bell, ArrowDown, User } from '@element-plus/icons-vue'
</script>
<template><header class="header"><el-icon :size="21"><Bell /></el-icon><el-dropdown trigger="click"><div class="header__user"><el-avatar :size="38" class="header__avatar"><el-icon><User /></el-icon></el-avatar><el-icon><ArrowDown /></el-icon></div><template #dropdown><el-dropdown-menu><el-dropdown-item>个人设置</el-dropdown-item><el-dropdown-item>退出登录</el-dropdown-item></el-dropdown-menu></template></el-dropdown></header></template>
<style scoped lang="scss">
.header { position:absolute; z-index:1; top:16px; right:24px; display:flex; align-items:center; gap:28px; color:#445e90; }.header__user { display:flex; align-items:center; gap:10px; cursor:pointer; }.header__avatar { background:#3868b2; color:#fff; }
</style>

