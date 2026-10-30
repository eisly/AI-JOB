# AI-JOB 前端基础骨架搭建 - 技术设计文档

> 本文档将「AI-JOB 前端基础骨架搭建」需求规格转化为可落地的技术设计方案。
> 交付范围：项目脚手架、路由、布局、导航栏、登录页骨架、9 个功能页面占位、全局样式变量；**不实现任何具体功能界面、业务逻辑与接口对接**。
> 所有前端代码统一存放于 `D:\py\AI-JOB\project\frontend\` 目录下。

---

# 一、需求与存量功能关系分析

## 1.1 需求功能与存量功能对比

### 1.1.1 已实现功能

当前 `project/frontend/` 目录下仅存在一个占位文件 `hello.html`（内容为单行测试文本），不构成任何可复用的前端工程能力。因此需求中的工程化能力（脚手架、构建、路由、布局、组件、样式体系）在存量代码中**均无对应实现**。

| 需求功能 | 存量功能 | 代码位置 | 匹配度 |
|---------|---------|---------|--------|
| 前端工程脚手架（Vite + Vue3 + TS） | 无（仅有 `hello.html` 静态占位） | `project/frontend/hello.html` | 0% |
| 路由体系（登录页 + 9 功能页） | 无 | — | 0% |
| 主布局（Sidebar + Header + Content） | 无 | — | 0% |
| 登录布局（左右分屏品牌区 + 表单区） | 无 | — | 0% |
| 全局样式变量与设计规范落地 | 无 | — | 0% |
| Element Plus / Icons 集成 | 无 | — | 0% |

> 结论：本任务为**纯增量（从零搭建）**场景，不存在可直接复用的存量前端代码。`hello.html` 将在脚手架初始化后删除。

### 1.1.2 需要扩展的功能

无。存量前端不存在可扩展的基础设施，所有能力均需新建。

### 1.1.3 需要新增的功能或接口

按业务模块分组，列出本次需要新增的全部功能点、输入/输出与核心逻辑、模块依赖关系。

**模块 A：工程脚手架与构建配置**
- A1. Vite + Vue 3 + TypeScript 项目初始化（`package.json`、`vite.config.ts`、`tsconfig.json`、`index.html`、`env.d.ts`）。
  - 输入：技术栈版本约束（Vue 3、Vite 5+、TS 5+）。
  - 输出：可执行 `dev` / `build` / `preview` 的标准工程。
  - 核心逻辑：配置 `@` 路径别名指向 `src`；配置开发服务器代理（预留 Java 后端 `/api`）。
- A2. 依赖装配：`vue-router@4`、`element-plus`、`@element-plus/icons-vue`；预留 `pinia`、`axios`（写入依赖但不强制使用）。
  - 依赖关系：Element Plus 需在应用入口全局注册或按需引入；Icons 作为独立包供 Sidebar/Header 使用。

**模块 B：路由体系**
- B1. 路由表定义：1 个登录路由（`/login`，独立布局）+ 9 个功能页路由（`/dashboard` 等，共享主布局）。
  - 输入：导航菜单 9 项配置（路径、名称、图标、标签）。
  - 输出：`RouteRecordRaw[]` 强类型路由表。
  - 核心逻辑：使用路由 `meta` 携带 `title`、`icon`、`badge`（规划中/方案预览标签）；默认重定向 `/` → `/dashboard`。
- B2. 路由守卫（骨架级）：全局前置守卫 `beforeEach`，预留登录态判断钩子（本期不实现真实鉴权，仅放行）。
  - 依赖关系：依赖 Pinia user store（预留），本期以注释占位。

**模块 C：布局组件**
- C1. `MainLayout`：已登录页面外壳，组合 Sidebar(220px) + Header(64px) + Content(#F8FAFC)。
  - 输入：`<router-view>` 作为内容插槽。
  - 输出：三段式布局 DOM。
  - 核心逻辑：使用 Flex 布局；Content 区通过 `<router-view>` 渲染功能页。
- C2. `Sidebar`：侧边导航栏。
  - 输入：9 项菜单配置（静态常量）、当前用户信息（预留）。
  - 输出：Logo 区 + 菜单列表 + 底部用户信息区。
  - 核心逻辑：基于 Element Plus `el-menu` 渲染；菜单项含图标 + 文案 + 可选标签（"规划中"/"方案预览"）；底部含头像、姓名、专业、"编辑简历"链接。
- C3. `Header`：顶部头部。
  - 输出：演示数据标签 + 通知铃铛（带红点）+ 用户头像下拉。
  - 核心逻辑：铃铛使用 `el-badge` 红点；头像下拉使用 `el-dropdown`（菜单项为占位）。

**模块 D：登录页骨架**
- D1. `LoginView`：独立全屏左右分屏布局。
  - 输出：左侧(55%)浅蓝渐变品牌区（AI-JOB 标题、Slogan、4 个功能特性卡片）+ 右侧(45%)白色登录表单卡片。
  - 核心逻辑：表单含邮箱、密码、记住我、登录按钮、注册切换；表单仅做骨架渲染与基本校验占位，不提交接口。

**模块 E：功能页面占位**
- E1. 9 个占位页面组件（`DashboardView`、`ProfileView`、`ResumeView`、`JobMatchView`、`TrainingView`、`ProjectView`、`InterviewView`、`GrowthView`、`ApplicationView`）。
  - 输出：统一占位卡片（页面标题 + "功能建设中"提示）。
  - 核心逻辑：复用统一占位组件 `PagePlaceholder`，传入页面标题与图标。

**模块 F：全局样式与设计规范**
- F1. 全局 SCSS 变量文件：品牌色、文字色、背景色、圆角、阴影 token。
  - 输出：`src/styles/variables.scss`，并在 `vite.config.ts` 中全局注入。
- F2. 全局基础样式：重置样式、滚动条、Element Plus 主题变量覆盖。

**模块 G：应用入口与全局注册**
- G1. `main.ts`：创建应用实例，注册 Router、Element Plus、Icons、全局样式。
- G2. `App.vue`：根组件，承载顶层 `<router-view>`。

**模块间依赖关系**
```
G(入口) → B(路由) → C(主布局)/D(登录布局)
C(MainLayout) → C(Sidebar) + C(Header) + E(占位页)
B(路由) → E(9 占位页) + D(登录页)
C(Sidebar)/C(Header) → A2(Icons)
F(样式) ← G(入口) 全局注入
```

## 1.2 存量功能详细分析

### 1.2.1 前端存量：`hello.html`

- **接口契约**：无。纯静态 HTML 文件，不暴露任何模块、组件或接口。
- **业务规则**：无。文件内容为测试文本 `111111`，无业务语义。
- **扩展点**：无。无构建工具、无依赖管理、无类型系统，无法在其上扩展。
- **约束**：该文件与目标 Vue 工程无任何兼容性，**将在脚手架初始化阶段删除**，不作为存量资产保留。

### 1.2.2 后端存量对前端骨架的约束

虽然本期不对接接口，但后端已存在的约定对前端骨架存在约束，需在设计阶段对齐，避免后续返工：

- **接口前缀**：Java 后端对外接口统一为 `/api/*`（见 `docs/项目架构.md`）。前端 `vite.config.ts` 开发代理须将 `/api` 代理至后端服务地址，Axios 预留实例的 `baseURL` 须设为 `/api`。
- **认证方式**：后端采用 Spring Security + JWT（见 `docs/项目架构.md`）。前端路由守卫须预留 token 判断位与请求拦截器注入 Authorization 头的占位（本期仅注释占位，不实现）。
- **数据格式**：后端统一返回 `CommonResult`（见 `project/backend/.../common/CommonResult.java`）。前端 Axios 响应拦截器须预留对该结构的解包占位（本期仅注释占位）。
- **流式接口**：模拟面试等场景使用 SSE。前端骨架不实现 SSE 客户端，但须在目录结构中预留 `src/api/` 目录以承接后续接口模块。

### 1.2.3 设计规范约束（来自需求规格）

以下设计 token 为硬约束，须在全局样式变量中精确落地，供所有组件引用：

| 类别 | Token | 值 |
|------|-------|----|
| 主品牌色 | `--ai-primary` | `#2563EB` |
| 浅蓝 | `--ai-primary-light` | `#EFF6FF` |
| 成功绿 | `--ai-success` | `#10B981` |
| 警告橙 | `--ai-warning` | `#F59E0B` |
| 文字主色 | `--ai-text-primary` | `#111827` |
| 文字次要 | `--ai-text-secondary` | `#6B7280` |
| 页面背景 | `--ai-bg-page` | `#F8FAFC` |
| 圆角 | `--ai-radius` | `8px` / `--ai-radius-lg` `12px` |
| 卡片阴影 | `--ai-shadow-card` | 微阴影 |

布局尺寸硬约束：侧边栏宽度 `220px`、顶部头部高度 `64px`；登录页左区 `55%` / 右区 `45%`。