<script setup lang="ts">
import Sidebar from './components/Sidebar.vue'
import Header from './components/Header.vue'
</script>
<template><div class="main-layout"><Sidebar /><div class="main-layout__right"><Header /><main class="main-layout__content"><router-view /></main></div></div></template>
<style scoped lang="scss">
.main-layout { display:flex; height:100vh; width:100%; }.main-layout__right { flex:1; min-width:0; overflow-y:auto; background:#f2f8fe; position:relative; }.main-layout__content { padding:52px 14px 24px; } @media(max-width:700px){.main-layout__content{padding:72px 14px 20px;}}
</style>
