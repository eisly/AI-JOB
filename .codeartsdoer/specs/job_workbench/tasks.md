# 求职工作台 - 编码任务规划

> 需求来源：`.codeartsdoer/specs/job_workbench/spec.md`
> 设计来源：`.codeartsdoer/specs/job_workbench/design.md`
> 代码基线：`project/frontend`（Vue 3 + TypeScript + Vite + Element Plus + Pinia）、`project/backend`（Spring Boot 4 + MyBatis + MySQL 8）
> 任务范围：仅前端只读聚合页 `/dashboard` 的数据访问、六区域聚合规则、区域编排状态、展示组件、页面装配与验收。不新增后端聚合接口、数据表与接口契约，不实现画像、简历、匹配、训练、面试、成长、任务、投递等业务模块本身。
> 路径约定：以下路径均相对仓库根目录，前端文件统一以 `project/frontend/` 开头。

---

## 任务总览

| 编号 | 任务组 | 关键交付物 | 前置依赖 | 覆盖需求 |
| --- | --- | --- | --- | --- |
| 1 | 建立工作台契约、请求层与共享基础 | `types/workbench.ts`、`api/request.ts`、`api/workbench.ts`、`mock/workbench.mock.ts`、`constants.ts`、字典与城市映射、`RegionState.vue` | 无 | 5.7.1、4.4、6.1–6.4、4.5-2 |
| 2 | 实现欢迎区与求职目标摘要 | `greeting.ts`、`goalSummary.ts`、`WelcomeHero.vue`、`GoalSummary.vue` | 1 | 5.1.1、5.1.3、5.2、5.7.1-3 |
| 3 | 实现“下一项任务” | `nextTask.ts`、`NextTaskCard.vue` | 1 | 5.3、5.7、6.2 |
| 4 | 实现“近期目标岗位” | `targetJobs.ts`、`TargetJobsPanel.vue` | 1 | 5.4、6.3 |
| 5 | 实现“求职进度” | `progress.ts`、`ProgressPanel.vue` | 1 | 5.5、6.4 |
| 6 | 实现“最新动态” | `activities.ts`、`timeFormat.ts`、`ActivityFeed.vue` | 1 | 5.6、6.4 |
| 7 | 实现区域编排、刷新与异常降级 | `useWorkbench.ts` | 1 | 5.1.2、5.7、5.8、4.1–4.4 |
| 8 | 装配工作台页面与响应式布局 | `DashboardView.vue` | 2–7 | 5.1.1-3、5.1.1-4、5.7.1-3、4.5 |
| 9 | 联调准备与契约缺口处置 | 开关说明、降级规则、依赖登记 | 1、2–7 | 5.4.3、5.6.3、5.8.1-3、4.3 |
| 10 | 自动化测试、构建与验收 | Vitest 配置、单测、验收记录 | 1–9 | 2.5.6 全部验证项、4.1、4.2 |
| 11 | 交付说明 | `readme.md` 更新、遗留项清单 | 10 | 4.4-2、4.4-4 |

## 任务依赖关系

```text
1 ──┬──> 2 ──┐
    ├──> 3 ──┤
    ├──> 4 ──┼──> 8 ──> 10 ──> 11
    ├──> 5 ──┤           ▲
    ├──> 6 ──┤           │
    └──> 7 ──┴──> 9 ─────┘
```

- 主任务 2–6 之间无相互依赖，可在主任务 1 完成后并行开展。
- 主任务 7 依赖主任务 1 的数据源与类型，可与 2–6 并行。
- 主任务 8 必须等 2–7 的组件与编排全部就绪后再装配。
- 主任务 10 的用例可随 2–7 逐组补充，但整体验收必须在 8、9 之后。

## 需求覆盖矩阵

| 需求章节 | 覆盖任务 |
| --- | --- |
| 5.1.1 工作台概览与欢迎区 | 2.1、2.4、8.1、8.2 |
| 5.1.2 交互流程 | 7.2 |
| 5.1.3 异常场景 | 7.3、7.6 |
| 5.2 求职目标摘要 | 1.7、2.2、2.3、2.5 |
| 5.3 下一项任务 | 3.1–3.4 |
| 5.4 近期目标岗位 | 4.1–4.4 |
| 5.5 求职进度 | 5.1–5.3 |
| 5.6 最新动态 | 6.1–6.4 |
| 5.7 空状态与引导 | 1.9、7.3、8.3 |
| 5.8 数据加载、刷新与异常降级 | 7.1–7.6 |
| 4.1 性能 | 7.2–7.4、10.7 |
| 4.2 可靠性 | 7.2–7.5、10.4 |
| 4.3 安全性 | 7.6、9.3 |
| 4.4 可维护性 | 1.7、1.9、7.5、8.2 |
| 4.5 兼容性 | 1.2、8.4 |
| 6.1–6.4 数据约束 | 1.1、1.2、2.2、3.1、4.2、5.1、6.1 |

---

## 1. 建立工作台契约、请求层与共享基础

**目标**：先固定类型、请求、数据源与共享组件，让后续六个业务区域可以并行开发且互不返工。

### 1.1 定义领域展示模型与区域状态类型

- [ ] 新建 `project/frontend/src/types/workbench.ts`，定义区域与状态模型：`RegionKey`（`'identity' | 'goal' | 'nextTask' | 'targetJobs' | 'progress' | 'activities'`）、`RegionStatus`（`IDLE/LOADING/READY/EMPTY/ERROR/REFRESHING`）、`RegionState<T>`（`status/data?/error?/updatedAt?/retryCount/fromCache`）、`WorkbenchState`（六个区域 + `refreshedAt?/refreshing/generation`）、`WorkbenchError`。
- [ ] 在同一文件定义派生展示模型：`GoalSummary`、`NextTaskSuggestion`/`NextTaskKind`、`TargetJobsModel`/`TargetJobsPhase`/`TargetJobCard`、`ProgressModel`/`ProgressStage`/`ProgressStageKey`/`StageStatus`、`ActivityItem`/`ActivityType`、`NavigationTarget`。
- [ ] 约束：枚举一律使用字面量联合类型；`NextTaskSuggestion.progress` 与 `ProgressModel.percent` 允许 `undefined`，禁止用 0 代替“不可获取”；文件内不允许 `any`。
- 验收：`npm run type-check` 通过；`RegionState` 的 `data` 与 `status` 语义可表达“刷新时保留旧数据”。

### 1.2 定义后端 VO 与统一响应契约

- [ ] 在 `project/frontend/src/types/workbench.ts` 中定义统一响应：`CommonResult<T>`（`code/msg/data/traceId`）、`PageResult<T>`（`total/records/current/size`）、`ApiResult<T>`（`data/traceId?`）、`ApiErrorCategory`（`TIMEOUT/NETWORK/RATE_LIMIT/AUTH_EXPIRED/NOT_FOUND/SERVER/UNKNOWN`）、`ApiError`（`category/httpStatus?/businessCode?/traceId?`）。
- [ ] 按 `docs/功能资料/业务后端接口文档.md` 定义只读 VO：`UserVO`、`ProfileVO`、`MatchBatchVO`/`MatchItemVO`/`EligibilityVO`/`MatchSourceVO`、`LearningPlanVO`/`LearningPlanItemVO`、`InterviewVO`、`GrowthVO`/`SkillSnapshotVO`/`EvidenceVO`、`TaskVO`、`ResumeVO`/`ResumeVersionVO`。
- [ ] 将契约缺口字段声明为可选：`companyName?`、`confirmedAt?`、`endedAt?`、`completedAt?`、`updatedAt?`；不定义任何写请求体类型。
- 验收：字段名、可空性与接口文档一致；未知字段不参与解析也不报错；不出现 `applications` 相关类型。

### 1.3 声明环境变量与 Mock 开关

- [ ] 修改 `project/frontend/env.d.ts`，在 `ImportMetaEnv` 中声明 `VITE_WORKBENCH_MOCK?: string` 与 `VITE_WORKBENCH_MOCK_SCENARIO?: string`。
- [ ] 确认 `project/frontend/tsconfig.json` 的 `include` 覆盖 `src/**/*.ts`、`src/**/*.d.ts`、`src/**/*.vue`（当前已覆盖，不修改语义）。
- 验收：`import.meta.env.VITE_WORKBENCH_MOCK` 在组件与请求层可直接使用且通过类型检查；未设置时按“关闭 Mock”处理。

### 1.4 实现统一只读 GET 请求层

- [ ] 修改 `project/frontend/src/api/request.ts`，用原生 `fetch` 实现 `apiGet<T>(path, query?, options?)`，返回 `Promise<ApiResult<T>>`。
- [ ] 实现能力：查询串拼接与编码；默认 1500ms 超时；外部 `AbortSignal` 与内部超时信号合并；`CommonResult` 解包并提取 `traceId`；HTTP 与业务码映射（`401→AUTH_EXPIRED`、`429→RATE_LIMIT`、`404→NOT_FOUND`、`5xx→SERVER`、业务码非 200→`businessCode`）；超时→`TIMEOUT`；网络异常→`NETWORK`；JSON 解析失败→`UNKNOWN`；区分“用户主动取消”与“超时取消”。
- [ ] 约束：不安装 axios；不硬编码主机与端口；请求一律相对路径 `/api/v1/...`；不写 `userId`；错误对象不含堆栈与服务地址。
- 验收：单元可验证 1.5 秒内结束；`traceId` 缺失返回 `undefined`；工作台全部请求均为 GET，无写请求。

### 1.5 封装九个只读数据源

- [ ] 新建 `project/frontend/src/api/workbench.ts`，导出 `getCurrentUser`、`getProfile`、`getMatchBatches`、`getMatchBatchDetail`、`getLearningPlans`、`getInterviews`、`getGrowthSummary`、`getRecentTasks`、`getResumes`。
- [ ] 固定路径与分页：`/api/v1/users/me`；`/api/v1/profile`；`/api/v1/job-matches?current=1&size=20`；`/api/v1/job-matches/{matchId}`；`/api/v1/learning/plans?current=1&size=50`；`/api/v1/interviews?current=1&size=50`；`/api/v1/growth`；`/api/v1/tasks?current=1&size=5`；`/api/v1/resumes?current=1&size=20`。
- [ ] 画像接口 `404` 或 `200` + 空对象统一返回 `null`，表示“无画像”；不调用 `/api/v1/applications`。
- 验收：九个函数均只调用一次对应接口（详情接口按条件 0~1 次）；模块内不含筛选、排序、过期判定等业务规则。

### 1.6 提供开发期 Mock 适配器

- [ ] 新建 `project/frontend/src/api/mock/workbench.mock.ts`，提供 `empty`、`normal`、`partial-error` 三套场景，数据直接复用 `types/workbench.ts` 中的 VO 类型。
- [ ] 三套场景覆盖：全空数据（新用户）、完整正常数据、部分数据源失败（任务失败 + 岗位服务错误 + 投递未启用）。
- [ ] 在 `workbench.ts` 中以 `import.meta.env.DEV` 与 `VITE_WORKBENCH_MOCK === 'true'` 为双开关，使用动态 `import()` 引入，场景由 `VITE_WORKBENCH_MOCK_SCENARIO` 选择（默认 `normal`）。
- 验收：开关关闭时 Mock 模块不被加载；生产构建产物不含 Mock 样例数据；三套场景可切换且页面不报错。

### 1.7 集中区域常量、枚举字典与城市映射

- [ ] 新建 `project/frontend/src/features/workbench/constants.ts`：`REGION_ORDER`（`identity → goal → nextTask → targetJobs → progress → activities`）、区域标题、单请求超时 1500ms、手动重试阈值 3 次、`SNAPSHOT_KEY = 'aijob.workbench.snapshot.v1'`、快照结构版本号。
- [ ] 新建 `project/frontend/src/features/workbench/model/dictionaries.ts`：岗位方向（`JAVA_BACKEND/FRONTEND/ALGORITHM/TEST/DATA`）、求职阶段（`INTERNSHIP=实习`、`CAMPUS=校招`、`EXPERIENCED=社招`）、计划项类型（`READING/PRACTICE/INTERVIEW`）、任务类型与状态、资格状态（`PASS=资格已通过`、`UNKNOWN=资格待确认`）、来源核验（`VERIFIED/UNVERIFIED/DISABLED`）、动态类型文案；提供未知值兜底函数，未知枚举返回“未设置”或可读原值，不抛错。
- [ ] 新建 `project/frontend/src/features/workbench/model/cities.ts`：内置至少 20 个常见行政区划编码到城市名的映射（含 `330100→杭州`、`110100→北京`、`310100→上海`、`440300→深圳`、`440100→广州`），未知编码回退展示“城市码 <编码>”。
- 验收：模板中不出现枚举中文硬编码；`330100` 展示为“杭州”；未知城市编码不出现空白或错误文本。

### 1.8 实现跳转目标可达性解析

- [ ] 新建 `project/frontend/src/features/workbench/model/navigation.ts`，提供 `resolveRoute(target: NavigationTarget): NavigationTarget | null`，通过 `router.hasRoute()` 校验目标路由是否已注册。
- [ ] 任务中心（`/tasks`）不存在时，按“关联资源页 → 无可用目标返回 `null`”降级；`/application` 存在时保留入口并标注“方案预览”。
- 验收：`resolveRoute` 对未注册路由返回 `null`；组件据返回值隐藏按钮，不出现“点击无响应”；任务中心模块补齐路由后无需改工作台代码。

### 1.9 实现区域状态展示组件 `RegionState`

- [ ] 新建 `project/frontend/src/views/dashboard/components/RegionState.vue`，以 `props` 接收 `status`、`error`、`retryCount`、`emptyText`、`emptyAction`，以 `emit` 输出 `retry`。
- [ ] 覆盖四种互斥表现：加载骨架（`LOADING`）、失败提示 + 重试 + 排障编号（`ERROR`）、空态文案 + 引导按钮（`EMPTY`）、旧数据 + 顶部细进度条（`REFRESHING`）。
- [ ] 失败文案按错误分类区分：超时“加载超时，点击重试”、限流“请求过于频繁，请稍后重试”、网络“网络连接异常，请检查网络”、其他“加载失败，点击重试”；第 3 次失败升级为“仍无法加载，请稍后重试或前往对应模块查看”。
- 验收：`LOADING` 下不展示空态文案；`EMPTY` 下不展示失败与重试错误；错误区只展示编号，不含堆栈、服务地址、模型信息。

## 2. 实现欢迎区与求职目标摘要

**目标**：完成 5.1 与 5.2，欢迎区可问候、可刷新，目标摘要三项可读、可跳转、无数据有引导。

### 2.1 实现问候语与阶段说明规则

- [ ] 新建 `project/frontend/src/features/workbench/model/greeting.ts`，实现 `buildGreeting(nickname?: string)`：昵称去除首尾空格后非空则使用昵称，否则使用“同学”。
- [ ] 实现 `buildStageDescription(candidateType?: string, hasProfile?: boolean)`：有画像时按阶段输出一句行动说明，无画像时输出通用说明；文案不包含技术术语。
- 验收：昵称为空、全空格、缺失三种情况均为“同学”，不出现占位符或空白；说明文案为一句中文且不含英文技术缩写。

### 2.2 实现求职目标摘要规则

- [ ] 新建 `project/frontend/src/features/workbench/model/goalSummary.ts`，实现 `buildGoalSummary(profile: ProfileVO | null): GoalSummary`。
- [ ] 三项摘要：岗位方向、目标城市、求职阶段；值为空或无法翻译时展示“未设置”；城市按用户设置顺序取前 5 个，超出部分直接丢弃且不产生截断错误文本；城市顺序与 `profile.cities` 一致。
- [ ] 使用 1.7 的字典与城市映射，禁止在模板中直接展示枚举编码。
- 验收：画像为 `null` 时三项均为“未设置”且 `isEmpty=true`；3 个城市依序展示 3 个；6 个城市只展示前 5 个；`CAMPUS` 展示“校招”。

### 2.3 实现目标摘要展示组件 `GoalSummary`

- [ ] 新建 `project/frontend/src/views/dashboard/components/GoalSummary.vue`，接收 `GoalSummary` 数据（导入类型时使用别名，避免与组件名冲突），按“求职方向 / 目标城市 / 求职阶段”三项展示名称与当前值。
- [ ] 点击任一摘要或“完善求职画像”按钮时跳转 `{ name: 'profile', query: { section: 'goal' } }`；不渲染任何输入框、下拉框或就地编辑表单。
- 验收：三项均有名称与值；空值展示“未设置”；点击只发生路由跳转，不产生画像写入请求。

### 2.4 实现欢迎区组件 `WelcomeHero`

- [ ] 新建 `project/frontend/src/views/dashboard/components/WelcomeHero.vue`，接收 `nickname`、`description`、`refreshedAt`、`refreshing`，输出 `refresh` 事件。
- [ ] 展示标题、一句说明文案、问候语；刷新按钮在 `refreshing` 时禁用并显示加载态；展示“更新于 HH:mm”，无刷新时间时不展示占位符。
- [ ] 仅做展示与事件上抛，不直接调用数据源；样式沿用 `DashboardView` 现有 `.welcome` 视觉。
- 验收：昵称缺失展示“同学”；标题与说明同时存在；刷新按钮可点击并只触发一次 `refresh` 事件；`refreshing=true` 时按钮不可重复点击。

### 2.5 校验目标摘要的跳转与禁止项

- [ ] 检查 `GoalSummary.vue` 与 `WelcomeHero.vue`：画像未完善时提供一致的“完善求职画像”入口；画像已完善时不展示该引导。
- [ ] 核对工作台无任何画像写操作（无 `POST/PUT/PATCH`），目标摘要区域不出现就地编辑。
- 验收：新用户欢迎区出现“完善求职画像”并跳转 `/profile`；已完善用户不出现该按钮；网络面板中无画像写请求。

## 3. 实现“下一项任务”

**目标**：完成 5.3，确保全页只出现一条建议，优先级、进度展示、降级与失败提示均符合需求。

### 3.1 实现唯一建议的优先级规则

- [ ] 新建 `project/frontend/src/features/workbench/model/nextTask.ts`，实现 `buildNextTask({ tasks, plans, profile })`，按“`RUNNING` → `QUEUED` → `CANCEL_REQUESTED` → 未完成计划项 → 画像完善建议”顺序返回唯一一条。
- [ ] 任务排序：同状态内按 `createdAt` 倒序取第一条；计划项排序：计划按 `createdAt` 倒序、计划内按 `seq` 升序，`IN_PROGRESS` 优先于 `PENDING`。
- [ ] 状态不在 `QUEUED/RUNNING/CANCEL_REQUESTED` 内（含 `SUCCEEDED/FAILED/CANCELLED/EXPIRED` 与未知值）不参与推荐。
- 验收：同时存在执行中任务与未完成计划项时返回执行中任务；存在多条同状态任务时只返回 1 条；未知状态任务被忽略且不报错。

### 3.2 实现任务与计划项的信息映射

- [ ] 任务建议输出：任务类型文案、当前阶段、进度（仅当 `progress` 为 0–100 整数时）、继续入口；进度缺失时只展示阶段文案，禁止展示 0% 或估算值。
- [ ] 计划项建议输出：计划项标题、类型文案、预计时长、继续入口；画像建议输出标题“完善求职画像”、说明文案与 `/profile` 入口。
- [ ] 继续入口映射：训练计划项 → `{ name: 'training', query: { planItemId } }`；进行中面试 → `{ name: 'interview', query: { interviewId } }`；其他任务 → 关联资源页，任务详情入口按 1.8 规则降级。
- 验收：进度为 `null` 时页面无“0%”；计划项展示标题、类型与 5–120 分钟时长；跳转目标均可达或按降级规则隐藏。

### 3.3 实现降级、空态与失败提示

- [ ] 任务数据源失败而计划数据源成功时，降级使用计划项，不向用户暴露任务服务错误；两者均失败且画像未完善时使用画像建议，画像已完善时进入失败可重试。
- [ ] 使用任务列表 `records[0]` 判定“最近任务是否失败”，失败时输出提示“任务未完成，可前往任务中心查看并重试”及一个可达动作；提示条不改变唯一推荐，不触发自动重试。
- [ ] 无任何任务、无未完成计划项且画像已完善时，输出空态“暂无可执行任务”，至少提供“查看岗位匹配”或“开始专项训练”其中一个入口。
- 验收：任务接口 500 但计划可用时仍展示计划项；最近任务为失败时展示提示条且不发起任何写请求；空态包含可点击入口。

### 3.4 实现 `NextTaskCard` 组件

- [ ] 新建 `project/frontend/src/views/dashboard/components/NextTaskCard.vue`，接收 `NextTaskSuggestion`、`status`、`error`，输出 `retry`。
- [ ] 展示唯一建议的类型、标题、阶段、进度、元信息与“继续”按钮；失败提示条置于卡片内、不遮挡继续入口；空态与加载态复用 `RegionState`。
- 验收：卡片内最多出现一条建议；进度缺失不渲染百分比；点击继续只做路由跳转。

## 4. 实现“近期目标岗位”

**目标**：完成 5.4，正确选择单一最新批次，完成资格过滤、排序、截取、过期与来源标注。

### 4.1 实现批次选择与批次状态机

- [ ] 新建 `project/frontend/src/features/workbench/model/targetJobs.ts`，实现 `selectLatestBatch(batches)`：按 `createdAt` 取最新，时间相同按 `id` 稳定排序，不混用多批次。
- [ ] 实现状态分支：无记录 → `EMPTY`；`GENERATING` → `GENERATING`；`FAILED` → `FAILED`；`READY` → 进入条目处理；其他未知状态 → 区域失败（不猜测）。
- 验收：多个 `READY` 批次只使用最新一个；`GENERATING` 展示“正在为你匹配岗位”而非空态；`FAILED` 不展示任何岗位条目。

### 4.2 实现资格过滤、排序、截取与过期判定

- [ ] 过滤 `eligibility.status === 'FAIL'` 的条目；按资格分组（`PASS` 优先 `UNKNOWN`），组内按 `matchScore` 降序，`matchScore` 为空排末尾；截取前 3 条。
- [ ] 展示字段映射：标题；公司名缺失时固定“公司信息待补充”；匹配指数仅 0–100 整数；资格未知标注“资格待确认”；来源 `UNVERIFIED` 标注“来源未核验”，`DISABLED` 标注“来源已停用”；差距技能只展示技能名称。
- [ ] 过期判定：`batch.isStale === true`，或 `profile.profileVersion` 与 `batch.profileVersion` 均为合法非负整数且不相等时，标记 `stale=true`；画像版本缺失、非数字或负数时不判定过期，也不展示过期提示。
- 验收：5 条含 1 条不通过时最多展示 4 条；通过 80 分排在未知 95 分之前；10 条可展示岗位只返回 3 条；`stale=true` 时展示“结果已过期，请重新匹配”。

### 4.3 实现 `TargetJobsPanel` 组件

- [ ] 新建 `project/frontend/src/views/dashboard/components/TargetJobsPanel.vue`，接收 `TargetJobsModel`、`status`、`error`，输出 `retry`。
- [ ] 覆盖 `GENERATING/FAILED/READY/EMPTY` 四种表现与 `stale` 提示；`READY` 时每张卡片展示标题、公司、匹配指数或“—”占位（不展示 0）、资格、来源、差距技能；提供“查看岗位匹配”入口。
- [ ] 空态按原因区分：无记录“暂无目标岗位”+“去岗位匹配”；全部资格不通过“当前条件暂未匹配到合适岗位”+查看原因入口；生成失败展示“岗位匹配未成功，可前往岗位匹配重新发起”。
- 验收：四种状态互斥且均有可执行入口；过期提示与岗位条目可同时出现；任何文案不含“录用率/通过率/成功率/保 offer”。

### 4.4 校对岗位文案禁令与数据一致性

- [ ] 全量检查 `TargetJobsPanel.vue` 与 `targetJobs.ts` 的输出文案，移除录用概率、薪资承诺、结果预测类表述。
- [ ] 确认列表接口的 `items` 不作为条目来源；仅 `READY` 批次的详情接口提供条目。
- 验收：文案审查无违规词；`GENERATING` 时不展示旧批次条目；条目排序不依赖后端返回顺序。

## 5. 实现“求职进度”

**目标**：完成 5.5，六阶段客观判定，百分比向下取整，失败与未启用可区分。

### 5.1 实现六阶段判定与百分比计算

- [ ] 新建 `project/frontend/src/features/workbench/model/progress.ts`，按固定顺序实现六阶段：完善求职画像（`targetRole` 与 `candidateType` 均非空）、确认简历（任一 `latestVersion.status === 'CONFIRMED'`）、完成一次岗位匹配（存在 `READY` 且未过期批次）、完成一次专项训练（存在 `COMPLETED` 计划项，否则成长证据含 `sourceType === 'LEARNING_ANSWER'`）、完成一次模拟面试（存在 `status === 'COMPLETED'` 面试）、产生一条投递记录（P0 固定未启用）。
- [ ] `percent = Math.floor(doneCount / 6 * 100)`，仅 `DONE` 计入分子；阶段顺序固定，首位未完成阶段输出“优先”标识。
- 验收：0/6→0%，2/6→33%，4/6→66%，6/6→100%；4 个阶段完成不得展示 66% 以外的结果；简历仅上传未确认不计完成。

### 5.2 实现待确认、投递例外与不可计算

- [ ] 单阶段数据源失败标记 `UNKNOWN`，不按完成也不按未完成；任一阶段为 `UNKNOWN` 时 `percent=undefined`，页面展示“暂无法计算”。
- [ ] 投递阶段固定 `UNKNOWN` 并标注“投递进度功能未开放”；仅投递为 `UNKNOWN`、其余五阶段可判定时，按 5 个可判定阶段计算百分比，避免所有用户永久不可计算。
- [ ] 数据不一致时以简历服务状态为准，画像阶段仍只看画像字段。
- 验收：仅投递未启用时百分比可计算且标注未开放；简历服务失败且其他正常时百分比为“暂无法计算”并展示已确认阶段；无任何数据时展示 0% 与“完善画像即可开始”。

### 5.3 实现 `ProgressPanel` 组件

- [ ] 新建 `project/frontend/src/views/dashboard/components/ProgressPanel.vue`，接收 `ProgressModel`、`status`、`error`，输出 `retry`。
- [ ] 展示百分比（`undefined` 时“暂无法计算”）与六阶段清单，每项含阶段名、完成状态、备注；未完成阶段可点击跳转 `/profile`、`/resume`、`/job-match`、`/training`、`/interview`、`/application`（标注“方案预览”）。
- [ ] 进度用 CSS（条形或环形）实现，不引入图表库；禁止输出录用率、offer 概率或结果预测文案。
- 验收：六阶段顺序固定且不因空数据改变；未完成阶段均有可达入口；0% 时展示引导说明。

## 6. 实现“最新动态”

**目标**：完成 5.6，多来源事件归一、去重、倒序、截断、时间展示与脱敏。

### 6.1 实现事件归一化与排序

- [ ] 新建 `project/frontend/src/features/workbench/model/activities.ts`，实现 `buildActivities({ resumes, batches, plans, interviews, growth })`，产出 `ActivityItem[]`。
- [ ] 事件来源与判定：简历确认（`latestVersion.status === 'CONFIRMED'`，时间用 `latestVersion.createdAt`，标注版本创建时间）；匹配完成（最新批次 `READY`，时间用批次 `createdAt`）；训练完成（成长证据 `sourceType === 'LEARNING_ANSWER'`，按 `sourceId` 去重）；面试完成（`status === 'COMPLETED'`，时间用 `createdAt`）；成长建议（`suggestions` 非空，时间取 `skills[].updatedAt` 最大值）；投递更新永不产生。
- [ ] 排序：按 `occurredAt` 倒序；缺失时间排末尾并标注“时间未知”；时间相同按“简历 → 匹配 → 训练 → 面试 → 成长”稳定排序；最多 5 条。
- 验收：12 条事件只返回 5 条；时间相同的两类事件顺序稳定；同一训练回答不重复成条；投递不产生动态。

### 6.2 实现时间格式化

- [ ] 新建 `project/frontend/src/features/workbench/model/timeFormat.ts`，实现 `formatActivityTime(iso?: string, now?: Date)` 与 `formatRefreshedAt(date?: Date)`。
- [ ] 24 小时内输出相对时间（“刚刚 / N 分钟前 / N 小时前”）；超过 24 小时输出本地时区的“YYYY-MM-DD HH:mm”；非法或缺失返回“时间未知”；UTC 输入按浏览器时区转换。
- 验收：3 小时前显示“3 小时前”；25 小时前显示日期时间；非法时间显示“时间未知”；刷新时间显示为 `HH:mm`。

### 6.3 实现 `ActivityFeed` 组件

- [ ] 新建 `project/frontend/src/views/dashboard/components/ActivityFeed.vue`，接收 `ActivityItem[]`、`status`、`error`，输出 `retry`。
- [ ] 每条展示事件类型文案、摘要、时间；可点击时按 1.8 规则跳转（简历 → `/resume`、匹配 → `/job-match`、训练 → `/training`、面试 → `/interview`、成长 → `/growth`）；不可达时隐藏跳转但保留摘要。
- [ ] 空态保留区域并展示“暂无动态记录”；部分来源失败不影响其余动态；全部来源失败才进入失败可重试。
- 验收：区域标题在空态仍保留；点击面试动态跳转对应面试记录；“查看全部记录”入口可达。

### 6.4 实现摘要脱敏与长度约束

- [ ] 在 `activities.ts` 中实现摘要生成：训练“完成专项训练：<计划项标题>”、面试“完成模拟面试”、匹配“岗位匹配结果已更新”、简历“简历版本已确认”、成长“已生成新的成长建议”。
- [ ] 摘要截断到 100 字符；禁止包含回答全文、简历原文、邮箱、来源 URL；缺失标题时使用不含详情的通用描述。
- 验收：任意摘要长度 ≤100；页面与快照中均无邮箱与原文；标题缺失时展示通用文案而非空串。

## 7. 实现区域编排、刷新与异常降级

**目标**：完成 5.7 与 5.8，六区域独立加载、独立失败、独立重试、刷新不闪烁、401 清空并跳转。

### 7.1 实现区域状态机与对外契约

- [ ] 新建 `project/frontend/src/features/workbench/useWorkbench.ts`，维护 `WorkbenchState` 六个区域，实现 `IDLE/LOADING/READY/EMPTY/ERROR/REFRESHING` 迁移；`REFRESHING` 保留旧 `data`。
- [ ] 对外暴露 `WorkbenchController`：`state`（只读）、`refresh()`、`retryRegion(key)`；初始状态六区域均为 `IDLE`。
- 验收：状态迁移符合设计 2.1.3(1)；`READY` 必有数据，`EMPTY/ERROR` 无可渲染业务数据；单区域状态变化不影响其他区域。

### 7.2 实现并发加载与身份否决

- [ ] 进入页面时并行发起身份请求与八个业务请求；匹配区域先取批次列表，仅最新批次 `READY` 时再取详情，两请求共享该区域 1.5 秒预算。
- [ ] 身份请求 401 时立即中止其余请求；身份请求非 401 失败时中止其余请求并进入全页身份错误，不展示任何已返回业务数据。
- [ ] 各区域独立 `Promise` 与独立状态，任一区域成功即落状态并渲染，不等待全部完成。
- 验收：画像先返回时目标摘要立即展示，岗位区域保持加载中；身份服务不可用时页面保留框架与重试且无业务数据。

### 7.3 实现超时、手动重试与错误文案

- [ ] 单区域请求超时 1.5 秒进入 `ERROR`；超时后不得继续等待；匹配详情使用区域剩余预算，预算不足时不发请求并直接失败可重试。
- [ ] `retryRegion` 仅手动触发，重试计数累加；第 3 次失败后文案升级但按钮保持可用；不实现自动重试与无限重试。
- [ ] 错误分类映射到不同用户文案（超时/限流/网络/服务/未知），401 不进入区域错误而走全局登录过期。
- 验收：人为延迟 2 秒时区域 1.5 秒内进入可重试；连续 3 次失败后文案升级；无自动重试请求。

### 7.4 实现刷新合并与代次控制

- [ ] 为每个数据源维护 `inFlight`：同一数据源在途时刷新复用原 `Promise`，不重复发请求。
- [ ] 维护 `generation`：刷新自增；响应代次落后于当前代次时丢弃，防止旧响应覆盖新数据。
- [ ] `refreshedAt` 仅在本轮全部 settle 且至少一个成功时更新；全部失败时保留上次成功时间。
- 验收：1 秒内点击刷新 3 次，同一接口最多 1 个在途请求；旧代次响应不覆盖新数据；全部失败时刷新时间不变。

### 7.5 实现会话快照缓存与恢复

- [ ] 使用 `sessionStorage`、键 `aijob.workbench.snapshot.v1`，值包含 `{ userId, savedAt, schemaVersion, state }`，仅保存脱敏后的派生展示模型与刷新时间。
- [ ] 写入时机：一轮加载全部 settle 且至少一个成功；读取时机：进入页面且快照 `userId` 与当前用户一致；恢复后先渲染并标注“更新于 <时间>”，随即发起刷新。
- [ ] 禁止持久化令牌、邮箱、简历事实、回答正文、来源 URL；401、身份失败、用户切换、结构版本升级时清除。
- 验收：再次进入页面先展示快照再刷新；快照中搜索不到邮箱与令牌；切换用户后不展示上一用户数据。

### 7.6 实现登录态清理、卸载中止与排障标识

- [ ] 401 时中止全部请求、清空六区域与快照、跳转 `{ name: 'login', query: { reason: 'expired' } }`，文案“登录已过期，请重新登录”；未登录进入时提示“请先登录”。
- [ ] `onBeforeUnmount` 中止全部在途请求，避免路由切换后写状态。
- [ ] 每个失败区域记录排障标识：优先使用响应 `traceId`，缺失时生成 `wb-<时间戳>-<随机短串>`；只展示编号。
- 验收：401 后页面无残留个人数据且跳转登录页；路由切走后无状态写入告警；失败区域可见排障编号且无堆栈与内部地址。

## 8. 装配工作台页面与响应式布局

**目标**：将六个区域组件与编排层组装进现有 `DashboardView`，保持视觉骨架与固定顺序，完成布局兼容性。

### 8.1 改造 `DashboardView` 为数据驱动页面

- [ ] 修改 `project/frontend/src/views/dashboard/DashboardView.vue`，移除硬编码空态文案与静态“未设置”，调用 `useWorkbench()` 并把各区域状态与数据传给子组件。
- [ ] 按固定顺序渲染：欢迎区 → 求职目标摘要 → 下一项任务 → 近期目标岗位 → 求职进度 → 最新动态；任一区域为空时保留标题与占位区域。
- [ ] 保留现有 `.dashboard`、`.overview`、`.panel` 等 SCSS 类与两栏网格，不引入新 UI 框架或图表库。
- 验收：六区域顺序固定且 DOM 与原型一致；空区域仍保留标题；页面无内联硬编码业务数据。

### 8.2 接入刷新入口与最近刷新时间

- [ ] 将 `WelcomeHero` 的 `refresh` 事件接到 `useWorkbench().refresh()`，把 `refreshedAt` 与 `refreshing` 传入；刷新时六区域进入 `REFRESHING` 并保留旧内容。
- [ ] 移除 `DashboardView` 中重复的刷新逻辑，刷新入口全页唯一。
- 验收：点击刷新后六个区域进入刷新态且页面不空白闪烁；完成后“更新于 HH:mm”更新；刷新过程中按钮禁用。

### 8.3 统一空态引导与入口可达性

- [ ] 统一欢迎区、下一项任务、求职进度三处的“完善求职画像”入口文案与目标 `{ name: 'profile' }`，仅在画像未完善时展示。
- [ ] 所有引导按钮经 `resolveRoute` 校验，不可达时隐藏或替换为可用入口；`/application` 入口保留并标注“方案预览”。
- 验收：画像未完善时三处入口一致且均可跳转；不存在点击无响应的按钮。

### 8.4 校验响应式布局与横向滚动

- [ ] 在 1280px、1440px、1920px 下保持两栏总览；1050–1279px 保持两栏并压缩间距；小于 1050px 降为单列；小于 700px 无横向滚动且长文本省略。
- [ ] 确认新增组件不引入嵌套 `overflow`，滚动仍由 `MainLayout` 的 `.main-layout__right` 承担。
- 验收：四档宽度截图对照原型；`document.documentElement.scrollWidth <= clientWidth`；目标岗位标题与动态摘要长文本省略不换行破坏布局。

## 9. 联调准备与契约缺口处置

**目标**：在真实后端接口尚未实现的前提下，保证可用 Mock 验收、缺口有明确降级、对外依赖有登记。

### 9.1 准备 Mock 与真实数据的切换方式

- [ ] 在 `project/frontend/.env.development.local`（本地文件，已被 `.gitignore` 忽略）示例中说明 `VITE_WORKBENCH_MOCK=true` 与 `VITE_WORKBENCH_MOCK_SCENARIO=empty|normal|partial-error`；不改 `vite.config.ts` 代理配置。
- [ ] 用三套场景分别走查六区域，确认切换真实数据源时无需改组件与规则层。
- 验收：`empty` 场景六区域全部空态且无报错；`normal` 场景六区域均有数据；`partial-error` 场景仅对应区域失败，其余正常。

### 9.2 落地契约缺口的降级规则

- [ ] 核对并落实：公司名缺失“公司信息待补充”；简历确认时间缺失时使用版本创建时间并标注；面试完成时间缺失时使用 `createdAt` 或“时间未知”；训练完成时间优先成长证据时间；成长建议时间取技能更新时间最大值；投递阶段固定“功能未开放”。
- [ ] 核对画像 `404`、空对象、`401`、其他错误的处理分支与设计一致。
- 验收：任一缺口字段缺失时页面有明确降级文案，不报错、不臆造数据；画像无数据时展示空态而非失败。

### 9.3 登记后端依赖与安全约束

- [ ] 整理工作台依赖的九个只读接口实现状态（当前仅 `/api/v1/users` demo 已实现），以及 `CommonResult.traceId` 未填充、`SecurityConfig` 全量放行两项阻塞，写入交付说明；本模块不修改 `project/backend/**`。
- [ ] 核对安全约束：所有请求不携带 `userId`；401 中止并清空；页面与缓存不含简历原文、邮箱、回答全文；错误提示不含堆栈、服务地址、模型信息。
- 验收：依赖清单完整可追踪；代码中无 `userId` 参数与敏感原文；后端零改动（`git status` 无 `project/backend` 变更）。

## 10. 自动化测试、构建与验收

**目标**：以自动化用例覆盖纯函数与请求层，以人工清单覆盖交互与 DFX，确保可回归。

### 10.1 搭建 Vitest 测试环境

- [ ] 在 `project/frontend` 安装开发依赖 `vitest`、`@vue/test-utils`、`jsdom`（仅 devDependencies，不新增运行时依赖）。
- [ ] 新建 `project/frontend/vitest.config.ts`，用 `mergeConfig` 复用 `vite.config.ts` 的别名与 SCSS 注入，配置 `environment: 'jsdom'`、`globals: true`。
- [ ] 在 `project/frontend/package.json` 增加 `test` 与 `test:run` 脚本；在 `project/frontend/tsconfig.json` 的 `types` 中追加 `vitest/globals`。
- 验收：`npm run test:run` 可启动并执行空用例；`npm run type-check` 不因测试文件报错。

### 10.2 编写聚合规则单元测试（一）

- [ ] 新建 `project/frontend/src/features/workbench/model/__tests__/`，覆盖问候（昵称/空/全空格）、目标摘要（三项映射、5 城市截断、未知城市、空值“未设置”）、下一项任务（五级优先级、未知状态忽略、进度缺失不显示 0%、任务失败降级）。
- [ ] 覆盖目标岗位（最新批次、`GENERATING/FAILED`、`FAIL` 过滤、`PASS` 优先、分数降序、空分置末、3 条截取、过期判定、版本非法不判过期、公司名缺失文案）。
- 验收：用例全部通过；每条规则至少 1 个边界用例；不依赖真实网络。

### 10.3 编写聚合规则单元测试（二）

- [ ] 覆盖求职进度（0%/33%/66%/100%、向下取整、阶段失败“暂无法计算”、投递未启用例外、简历未确认不计完成）。
- [ ] 覆盖最新动态（倒序、同时间稳定排序、5 条截断、时间缺失置末、训练证据去重、摘要截断与脱敏）与时间格式化（24 小时内相对时间、超过 24 小时日期、UTC 转本地、非法时间）。
- 验收：上述用例全部通过；时间用例固定 `now` 注入，不依赖机器时区。

### 10.4 编写请求层与状态机测试

- [ ] 覆盖 `apiGet`：1.5 秒超时、`CommonResult` 解包、业务码非 200、`traceId` 提取、401 分类、429 分类、网络异常、主动取消与超时区分。
- [ ] 覆盖 `useWorkbench`：六状态迁移、刷新保留旧数据、401 清空、刷新合并（1 秒 3 次点击仅 1 个在途）、旧代次响应丢弃、身份失败全页阻断。
- 验收：请求层测试使用 `vi.stubGlobal('fetch')` 与假定时器；状态机测试不发起真实请求。

### 10.5 编写组件测试

- [ ] 覆盖 `RegionState`（加载/失败重试/空态/刷新保留）与 `NextTaskCard`、`TargetJobsPanel`、`ProgressPanel`、`ActivityFeed` 的关键渲染分支。
- [ ] 校验空态与失败态互斥、空态含可执行入口、过期提示与岗位条目可同时出现。
- 验收：组件测试通过；无 `console.error` 警告；不依赖 Element Plus 全局注册（按需 stub）。

### 10.6 执行静态检查与生产构建

- [ ] 运行 `npm run type-check`，要求零错误且无 `any` 新增。
- [ ] 运行 `npm run build`，确认构建通过且产物不包含 Mock 样例数据与开发期开关代码路径。
- 验收：两条命令退出码为 0；在 `dist` 产物中检索 Mock 场景特征字符串无结果。

### 10.7 执行手工验收清单

- [ ] 对照 `spec.md` 逐项验收：5.1（问候/默认入口/六区域顺序/空区域保留）、5.2（三项摘要/5 城市/枚举翻译/跳转/禁止就地编辑）、5.3（唯一推荐/优先级/进度缺失/画像建议/跳转）、5.4（最新批次/生成中/失败/空态/过滤/排序/3 条/公司缺失/来源/过期/文案禁令）。
- [ ] 对照 `spec.md` 验收：5.5（六阶段/百分比/客观判定/优先阶段/跳转/0%）、5.6（事件范围/倒序/5 条/脱敏/相对时间/跳转/空态）、5.7（空失败区分/引导可执行/三处一致/加载不显示空态/连续失败文案）、5.8（独立加载/刷新/降级/缓存时间/请求合并/排障标识/超时/限流/断网/部分失败）。
- [ ] 对照 DFX 验收：4.1（首屏 5 次刷新均 ≤2 秒、单区域 ≤1.5 秒、独立加载、刷新不闪烁）、4.2（单区域失败不影响全局、手动重试 ≥3 次、空数据按空态）、4.3（401 清空跳转、无敏感全文、错误无堆栈）、4.4（六区域独立、排障标识、刷新时间、顺序固定）、4.5（1280/1050/700 三档、无横向滚动、未知字段忽略、全空数据可用）。
- 验收：验收记录逐条标注“通过/不通过/待后端联调”，不通过项有跟进说明。

### 10.8 执行回归检查

- [ ] 回归其他页面路由（`/profile`、`/resume`、`/job-match`、`/training`、`/interview`、`/growth`、`/application`）跳转正常，`/` 仍重定向 `/dashboard`。
- [ ] 运行后端 `mvn test`（可选回归），确认后端零改动且测试不受影响。
- 验收：所有路由可达；后端测试结果与改动前一致。

## 11. 交付说明

**目标**：让后续接手的开发者能独立启动、切换 Mock、识别遗留依赖。

### 11.1 更新开发说明

- [ ] 在 `readme.md` 的前端相关章节补充：前端启动命令、工作台 Mock 开关与三套场景、切换到真实接口的前提（九个只读接口就绪 + 鉴权落地）。
- [ ] 说明测试命令（`npm run test:run`）、类型检查与构建命令。
- 验收：按说明可从零启动并复现空/正常/部分失败三种场景。

### 11.2 输出交付清单与遗留项

- [ ] 列出本次新增/修改文件清单（`types/workbench.ts`、`api/request.ts`、`api/workbench.ts`、`api/mock/workbench.mock.ts`、`features/workbench/**`、`views/dashboard/**`、`env.d.ts`、`package.json`、`tsconfig.json`、`vitest.config.ts`）。
- [ ] 登记遗留项：后端九个接口未实现、`traceId` 未填充、鉴权未落地、任务中心路由缺失、`companyName/confirmedAt/endedAt/completedAt` 字段缺口、投递功能未开放。
- 验收：清单与实际变更一致；遗留项均有责任模块与启用条件说明。

---

## 完成定义（DoD）

1. 主任务 1–9 的全部复选框完成，主任务 10 的测试与验收全部通过。
2. `npm run type-check` 零错误；`npm run build` 通过；`npm run test:run` 全绿。
3. `spec.md` 5.1–5.8 与 4.1–4.5 的验收项逐条有结论。
4. `project/backend/**` 零改动；工作台不新增后端接口、不新增数据表、不改变既有契约。
5. 工作台全部请求为只读 GET，重复刷新无业务副作用。
6. 遗留依赖已登记，且不影响 Mock 模式下的完整验收。