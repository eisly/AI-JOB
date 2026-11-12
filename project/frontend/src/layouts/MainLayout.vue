<script setup lang="ts">
import Sidebar from './components/Sidebar.vue'
import Header from './components/Header.vue'
</script>
<template><div class="main-layout"><Sidebar /><div class="main-layout__right"><Header /><main class="main-layout__content"><router-view /></main></div></div></template>
<style scoped lang="scss">
.main-layout { display:flex; height:100vh; width:100%; }
.main-layout__right { flex:1; min-width:0; display:flex; flex-direction:column; background:$ai-bg-page; }
.main-layout__content { flex:1; overflow-y:auto; padding:64px 14px 24px; }
</style>
