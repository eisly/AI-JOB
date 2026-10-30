# AI-JOB 前端基础骨架搭建 - 编码任务清单

> 基于 `docs/spec/design.md` 技术设计文档生成。
> 所有前端代码统一存放于 `project/frontend/` 目录下。
> 技术栈：Vue 3 + TypeScript + Vite + Vue Router 4 + Pinia（预留）+ Element Plus + @element-plus/icons-vue + Sass。
> 交付范围：项目脚手架、路由、布局、导航栏、登录页骨架、9 个功能页面占位、全局样式变量；**不实现任何具体功能界面、业务逻辑与接口对接**。

---

## 1. 工程脚手架与构建配置

> 对应设计文档模块 A。为整个前端工程提供可执行的构建基础，是所有后续任务的前置依赖。

### 1.1 创建 Vite 构建配置
- [ ] 创建 `project/frontend/vite.config.ts`，配置以下内容：
  - 使用 `@vitejs/plugin-vue` 插件
  - 配置路径别名 `@` 指向 `src` 目录
  - 配置开发服务器端口（如 5173）及 `/api` 代理至后端服务地址（预留 `http://localhost:8080`）
  - 配置 `unplugin-auto-import`（自动导入 Vue、Vue Router、Pinia API）与 `unplugin-vue-components`（按需引入 Element Plus 组件）
  - 配置 CSS 预处理器，全局注入 `src/styles/variables.scss` 至所有 SCSS 文件
  - 验收：`vite.config.ts` 可被 Vite 正确加载，别名与代理配置生效

### 1.2 创建 TypeScript 配置
- [ ] 创建 `project/frontend/tsconfig.json`，配置以下内容：
  - `target` 设为 `ESNext`，`module` 设为 `ESNext`，`moduleResolution` 设为 `bundler`
  - 启用严格模式 `strict: true`
  - 配置路径映射 `@/*` 指向 `src/*`
  - 包含 `src` 目录，引用 `vite/client` 类型
  - 验收：`vue-tsc --noEmit` 类型检查可通过
- [ ] 创建 `project/frontend/tsconfig.node.json`，用于 `vite.config.ts` 的 Node 环境类型配置
  - 验收：Vite 配置文件不产生类型报错

### 1.3 创建入口 HTML 与类型声明
- [ ] 创建 `project/frontend/index.html`，包含：
  - `<div id="app">` 挂载点
  - 引入 `/src/main.ts` 模块脚本
  - 设置 `<title>` 为「AI-JOB」及基础 meta 标签
  - 验收：浏览器可正确加载该 HTML 并定位挂载点
- [ ] 创建 `project/frontend/env.d.ts`，声明 Vue 单文件组件类型（`*.vue`）及 Vite 环境变量类型
  - 验收：TS 能正确识别 `.vue` 文件导入

### 1.4 清理存量占位文件
- [ ] 删除 `project/frontend/hello.html`（存量静态占位文件，与 Vue 工程不兼容）
  - 验收：`hello.html` 不再存在，目录仅保留工程化文件

---

## 2. 全局样式与设计规范

> 对应设计文档模块 F。提供全局设计 token 与基础样式，被所有组件依赖，须在组件开发前完成。

### 2.1 创建设计变量文件
- [ ] 创建 `project/frontend/src/styles/variables.scss`，按设计文档硬约束精确落地以下 token：
  - 主品牌色 `$ai-primary: #2563EB`、浅蓝 `$ai-primary-light: #EFF6FF`
  - 成功绿 `$ai-success: #10B981`、警告橙 `$ai-warning: #F590B`
  - 文字主色 `$ai-text-primary: #111827`、文字次要 `$ai-text-secondary: #6B7280`
  - 页面背景 `$ai-bg-page: #F8FAFC`
  - 圆角 `$ai-radius: 8px`、`$ai-radius-lg: 12px`
  - 卡片阴影 `$ai-shadow-card`（微阴影值）
  - 布局尺寸 `$sidebar-width: 220px`、`$header-height: 64px`
  - 验收：变量值与设计文档表格完全一致，可供所有 SCSS 文件直接引用

### 2.2 创建重置样式文件
- [ ] 创建 `project/frontend/src/styles/reset.scss`，包含：
  - 基础 CSS Reset（margin、padding、box-sizing 重置）
  - 全局滚动条样式美化
  - 验收：页面默认样式被正确重置

### 2.3 创建全局基础样式文件
- [ ] 创建 `project/frontend/src/styles/global.scss`，包含：
  - 引入 `reset.scss`
  - `body`、`#app` 基础样式（高度 100%、字体、背景色引用变量）
  - Element Plus 主题变量覆盖（主色映射至 `$ai-primary`）
  - 通用工具类（如 `.flex`、`.flex-center`）
  - 验收：Element Plus 组件主色与品牌色一致，全局基础样式生效

### 2.4 创建样式入口文件
- [ ] 创建 `project/frontend/src/styles/index.scss`，聚合导出 `variables.scss`、`global.scss`，供 `main.ts` 统一引入
  - 验收：通过单一入口可加载全部全局样式

---

## 3. 应用入口与根组件

> 对应设计文档模块 G。创建应用启动入口，依赖模块 A（脚手架）与模块 F（样式）。

### 3.1 创建应用入口文件
- [ ] 创建 `project/frontend/src/main.ts`，完成以下注册：
  - 创建 Vue 应用实例
  - 注册 Vue Router（从 `src/router` 引入）
  - 全局注册 Element Plus
  - 全局注册 `@element-plus/icons-vue` 图标组件
  - 引入全局样式 `src/styles/index.scss`
  - 挂载至 `#app`
  - 验收：应用可正常启动并挂载，控制台无注册错误

### 3.2 创建根组件
- [ ] 创建 `project/frontend/src/App.vue`，包含：
  - 顶层 `<router-view>` 作为路由出口
  - 验收：根组件可正确渲染路由匹配的页面组件

---

## 4. 路由体系

> 对应设计文档模块 B。定义全部路由表与守卫骨架，依赖模块 E（占位页）与模块 D（登录页）的组件存在。

### 4.1 创建菜单与路由配置常量
- [ ] 创建 `project/frontend/src/config/menu.ts`，定义 9 项导航菜单静态常量，每项包含：
  - `path`（路由路径）、`name`（路由名称）、`title`（显示文案）、`icon`（Element Plus 图标组件名）、`badge`（可选标签："规划中"或"方案预览"）
  - 9 项依次为：工作台(`/dashboard`)、个人中心(`/profile`)、简历管理(`/resume`)、岗位匹配(`/job-match`)、能力训练(`/training`)、项目实战(`/project`)、模拟面试(`/interview`)、成长轨迹(`/growth`)、投递管理(`/application`)
  - 验收：菜单常量类型安全，可被 Sidebar 与路由模块共同消费

### 4.2 创建路由表定义
- [ ] 创建 `project/frontend/src/router/routes.ts`，定义 `RouteRecordRaw[]` 强类型路由表：
  - 登录路由 `/login`（独立布局，`meta.title: '登录'`）
  - 9 个功能页路由（共享 `MainLayout` 主布局），`meta` 携带 `title`、`icon`、`badge`
  - 默认重定向 `/` → `/dashboard`
  - 验收：路由表类型完整，`meta` 字段有 TS 类型声明

### 4.3 创建路由实例与守卫
- [ ] 创建 `project/frontend/src/router/index.ts`，完成以下内容：
  - 使用 `createRouter` + `createWebHistory` 创建路由实例
  - 注册全局前置守卫 `beforeEach`，预留登录态判断钩子（以注释占位，本期全部放行，不实现真实鉴权）
  - 导出路由实例
  - 验收：路由实例可被 `main.ts` 注册，守卫不阻断页面跳转

---

## 5. 布局组件

> 对应设计文档模块 C。实现已登录页面的三段式外壳，依赖模块 A2（Icons）、模块 F（样式）、模块 B（路由当前态）。

### 5.1 创建主布局组件
- [ ] 创建 `project/frontend/src/layouts/MainLayout.vue`，实现三段式布局：
  - 使用 Flex 布局组合 Sidebar（宽度 220px）+ 右侧区域
  - 右侧区域组合 Header（高度 64px）+ Content 区
  - Content 区背景色 `#F8FAFC`，通过 `<router-view>` 渲染功能页
  - 验收：布局尺寸与设计文档硬约束一致，内容区正确渲染路由页面

### 5.2 创建侧边导航栏组件
- [ ] 创建 `project/frontend/src/layouts/components/Sidebar.vue`，实现以下内容：
  - 顶部 Logo 区（AI-JOB 标识）
  - 中部菜单列表：基于 `src/config/menu.ts` 渲染 Element Plus `el-menu`，每项含图标 + 文案 + 可选标签（"规划中"/"方案预览"）
  - 菜单项点击跳转至对应路由，当前路由项高亮
  - 底部用户信息区：头像、姓名、专业、"编辑简历"链接（静态占位数据）
  - 验收：9 项菜单完整渲染，标签正确显示，点击可跳转且高亮正确

### 5.3 创建顶部头部组件
- [ ] 创建 `project/frontend/src/layouts/components/Header.vue`，实现以下内容：
  - 演示数据标签（静态占位文本）
  - 通知铃铛：使用 `el-badge` 显示红点
  - 用户头像下拉：使用 `el-dropdown`，菜单项为占位（如"个人设置"、"退出登录"）
  - 验收：头部元素正确渲染，铃铛红点与下拉菜单交互正常

---

## 6. 登录页骨架

> 对应设计文档模块 D。实现独立全屏左右分屏登录页骨架，不提交接口。

### 6.1 创建登录页组件
- [ ] 创建 `project/frontend/src/views/login/LoginView.vue`，实现左右分屏布局：
  - 左侧（55%）：浅蓝渐变品牌区，包含 AI-JOB 标题、Slogan、4 个功能特性卡片（静态内容）
  - 右侧（45%）：白色登录表单卡片，包含邮箱输入框、密码输入框、"记住我"复选框、登录按钮、注册切换链接
  - 表单使用 Element Plus `el-form`，配置基本校验规则占位（邮箱必填、密码必填），不提交接口
  - 验收：左右分屏比例 55%/45% 正确，表单元素完整渲染且校验提示可触发

---

## 7. 功能页面占位

> 对应设计文档模块 E。创建统一占位组件与 9 个功能页占位，被路由模块引用。

### 7.1 创建统一占位组件
- [ ] 创建 `project/frontend/src/components/PagePlaceholder.vue`，实现可复用占位卡片：
  - 接收 `title`（页面标题）与 `icon`（页面图标）props
  - 渲染页面标题 + "功能建设中"提示文案
  - 样式引用全局变量（居中卡片、品牌色）
  - 验收：传入不同 title/icon 可正确渲染对应占位内容

### 7.2 创建 9 个功能页占位组件
- [ ] 创建以下 9 个占位页面组件，均复用 `PagePlaceholder` 组件传入对应标题与图标：
  - `project/frontend/src/views/dashboard/DashboardView.vue`（工作台）
  - `project/frontend/src/views/profile/ProfileView.vue`（个人中心）
  - `project/frontend/src/views/resume/ResumeView.vue`（简历管理）
  - `project/frontend/src/views/job-match/JobMatchView.vue`（岗位匹配）
  - `project/frontend/src/views/training/TrainingView.vue`（能力训练）
  - `project/frontend/src/views/project/ProjectView.vue`（项目实战）
  - `project/frontend/src/views/interview/InterviewView.vue`（模拟面试）
  - `project/frontend/src/views/growth/GrowthView.vue`（成长轨迹）
  - `project/frontend/src/views/application/ApplicationView.vue`（投递管理）
  - 验收：9 个组件均可被路由正确引用并渲染占位内容

---

## 8. 接口与状态预留目录

> 对应设计文档 1.2.2 节后端约束。预留目录结构与占位实例，承接后续接口对接，本期不实现真实逻辑。

### 8.1 创建接口请求预留
- [ ] 创建 `project/frontend/src/api/request.ts`，预留 Axios 实例占位：
  - `baseURL` 设为 `/api`
  - 预留请求拦截器注入 Authorization 头的注释占位
  - 预留响应拦截器解包 `CommonResult` 结构的注释占位
  - 验收：文件存在且类型安全，不产生运行时副作用

### 8.2 创建状态管理预留
- [ ] 创建 `project/frontend/src/stores/user.ts`，预留 Pinia user store 占位：
  - 定义 store 骨架（token、用户信息状态字段），方法以注释占位
  - 验收：store 可被定义，路由守卫可引用其结构（本期不实现真实逻辑）

---

## 9. 集成验证与启动测试

> 确保骨架工程可安装、可启动、可构建、路由可跳转。

### 9.1 依赖安装验证
- [ ] 在 `project/frontend/` 目录执行依赖安装（`npm install` 或 `pnpm install`），确认全部依赖安装成功无冲突
  - 验收：`node_modules` 生成，无致命依赖错误

### 9.2 类型检查验证
- [ ] 执行 `npm run type-check`（`vue-tsc --noEmit`），确认全工程 TypeScript 类型检查通过
  - 验收：无类型错误输出

### 9.3 开发服务器启动验证
- [ ] 执行 `npm run dev` 启动开发服务器，确认：
  - 服务器正常启动并监听配置端口
  - 浏览器访问根路径自动重定向至 `/dashboard`
  - 主布局（Sidebar + Header + Content）正确渲染
  - 验收：开发服务器无启动报错，首页正常展示主布局与工作台占位页

### 9.4 路由跳转验证
- [ ] 在运行的开发服务器上逐项验证路由：
  - 点击 Sidebar 9 项菜单，逐一确认跳转至对应路由且占位页正确渲染
  - 直接访问 `/login`，确认登录页左右分屏布局正确渲染
  - 确认当前菜单项高亮状态正确
  - 验收：全部 10 条路由（1 登录 + 9 功能页）均可正常访问与渲染

### 9.5 生产构建验证
- [ ] 执行 `npm run build`，确认生产构建成功：
  - `vue-tsc` 类型检查通过
  - Vite 构建产物正常输出至 `dist` 目录
  - 验收：构建无错误，`dist` 目录包含完整产物

---

## 10. 代码审查与最终确认

> 确保交付质量与设计一致性。

### 10.1 设计一致性核对
- [ ] 核对全部实现与 `design.md` 的一致性：
  - 设计 token 变量值与文档表格逐项核对
  - 布局尺寸（侧边栏 220px、头部 64px、登录页 55%/45%）核对
  - 9 个功能页路由路径与菜单项核对
  - 验收：实现与设计文档零偏差

### 10.2 变更范围确认
- [ ] 确认本次变更范围：
  - 仅新增 `project/frontend/` 下工程骨架文件，未实现任何业务逻辑与接口对接
  - 已删除存量 `hello.html`
  - 未修改后端任何代码
  - 验收：变更范围与需求规格交付边界一致

### 10.3 骨架可扩展性确认
- [ ] 确认骨架为后续开发预留了扩展点：
  - `src/api/` 目录与 Axios 实例占位可承接接口对接
  - `src/stores/` 目录与 Pinia store 占位可承接状态管理
  - 路由守卫预留登录态判断钩子可承接鉴权
  - `PagePlaceholder` 组件可被各功能页替换为真实实现
  - 验收：所有预留点结构清晰，后续开发无需重构骨架