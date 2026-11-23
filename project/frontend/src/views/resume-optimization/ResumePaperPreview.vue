<script setup lang="ts">
import { computed } from 'vue'
import { renderResumeMarkdown } from './resume-markdown'

const props = defineProps<{
  title: string
  subtitle?: string
  blocks: { text: string; changed: boolean }[]
  full?: boolean
}>()

const rendered = computed(() => renderResumeMarkdown(props.blocks))
</script>

<template>
  <article class="paper" :class="{ 'paper--full': full }">
    <header class="paper-head">
      <h3>{{ title }}</h3>
      <p v-if="subtitle" class="paper-role">{{ subtitle }}</p>
    </header>
    <div class="resume-markdown" v-html="rendered"></div>
  </article>
</template>

<style scoped lang="scss">
.paper {
  max-width: 630px;
  margin: 0 auto;
  padding: 34px 38px;
  background: var(--ai-bg-card, #fff);
  border: 1px solid var(--ai-border-light, #dfe8f3);
  box-shadow: 0 2px 10px rgb(29 46 78 / 8%);
  color: var(--ai-text-primary, #202e47);
  font-size: 13px;
  line-height: 1.7;
}
.paper--full {
  max-width: 720px;
  padding: 42px 50px;
  font-size: 14px;
}
.paper-head {
  margin-bottom: 14px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--ai-border-light, #dce5f1);
}
.paper-head h3 {
  margin: 0;
  font-size: 22px;
  letter-spacing: -0.02em;
}
.paper-role {
  margin: 4px 0 0;
  color: var(--ai-text-secondary, #53647d);
  font-size: 12px;
  font-weight: 600;
}
.resume-markdown {
  overflow-wrap: anywhere;

  :deep(h1) { font-size: 1.55em; }
  :deep(h2) {
    padding-bottom: 4px;
    border-bottom: 1px solid var(--ai-border-light, #dce5f1);
    font-size: 1.26em;
  }
  :deep(h3) { font-size: 1.14em; }
  :deep(:is(h1, h2, h3, h4, h5, h6)) { margin: 1.1em 0 0.35em; }
  :deep(:is(p, ul, ol, blockquote, pre, table)) { margin: 0.5em 0 0.85em; }
  > :deep(:first-child) { margin-top: 0; }
  > :deep(:last-child) { margin-bottom: 0; }
  :deep(:is(ul, ol)) { padding-inline-start: 1.6em; }
  :deep(ul) { list-style: disc; }
  :deep(ol) { list-style: decimal; }
  :deep(a) { color: var(--ai-primary, #235cbe); text-decoration: underline; }
  :deep(blockquote) { padding-left: 0.9em; border-left: 3px solid var(--ai-accent-border, #aac6fd); }
  :deep(:is(pre, code)) { background: var(--ai-bg-light, #f3f4f6); }
  :deep(pre) { overflow: auto; padding: 0.7em; }
  :deep(table) { display: block; max-width: 100%; overflow: auto; border-collapse: collapse; }
  :deep(:is(th, td)) { padding: 0.25em 0.6em; border: 1px solid var(--ai-border, #dce5f1); }
  :deep(img) { max-width: 100%; }
  :deep(.markdown-changed) {
    border-radius: 4px;
    background: var(--ai-primary-light, #eaf2ff);
    box-shadow: inset 3px 0 var(--ai-accent-border, #aac6fd);
    padding-inline: 0.55em;
  }
}
@media (max-width: 700px) {
  .paper,
  .paper--full {
    padding: 22px 20px;
  }
}
</style>
