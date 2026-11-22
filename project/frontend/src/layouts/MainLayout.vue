<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import Sidebar from './components/Sidebar.vue'
import Header from './components/Header.vue'

const route = useRoute()
const trainingHome = computed(() => route.name === 'training')
const trainingFocus = computed(() => route.name === 'training-practice' || route.name === 'training-prepare'
  || trainingHome.value && ['library', 'unit', 'setup', 'new', 'mistake'].includes(String(route.query.view || '')))
</script>
<template>
  <div class="main-layout" :class="{ 'main-layout--training': trainingHome, 'main-layout--focus': trainingFocus }">
    <Sidebar v-if="!trainingFocus" />
    <div class="main-layout__right">
      <Header v-if="!trainingFocus" :section="trainingHome ? '专项训练' : undefined" />
      <main class="main-layout__content"><router-view /></main>
    </div>
  </div>
</template>
<style scoped lang="scss">
.main-layout { display:flex; height:100vh; width:100%; }
.main-layout__right { flex:1; min-width:0; display:flex; flex-direction:column; background:$ai-bg-page; }
.main-layout__content { flex:1; min-height:0; overflow-y:auto; padding:0 14px 24px; }
.main-layout--training .main-layout__content { padding-inline:clamp(14px, 2.5vw, 36px); }
.main-layout--training :deep(.sidebar), .main-layout--training :deep(.header) { background:$ai-bg-card; }
.main-layout--training :deep(.sidebar__menu .el-menu-item.is-active) { background:$ai-primary-light; color:$ai-primary; font-weight:700; }
.main-layout--focus .main-layout__content { padding:0; }
</style>
