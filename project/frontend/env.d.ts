/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_WORKBENCH_MOCK?: string
  readonly VITE_WORKBENCH_MOCK_SCENARIO?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}