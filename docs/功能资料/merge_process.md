### [2026-11-06 10:45] 分支整合与画像接口统一

**任务目标：** 将当前 Agent、投递进度、登录和求职画像分支整合。

**执行行为：** 已在隔离副本提交 Agent 与投递进度、登录的合并；登录主体对接现有业务身份解析。画像分支存在重复控制器，正在将可配置岗位和画像页面整合到现有接口。

**文件变更：** `profile/ProfileController.java`、`profile/ProfileService.java` 支持岗位输入和技能搜索；`frontend/src/api/profile.ts`、`frontend/src/api/types.ts`、`frontend/src/views/profile/ProfileView.vue` 对接画像界面。移除画像分支重复的 controller、service、mapper、dto、entity、vo 和临时请求头解析器，避免重复路由及身份伪造。

**执行命令与验证：** 登录合并后执行 `mvn -q -DskipTests compile` 与 `npm run type-check` 均通过；画像合并后的验证尚未执行。

**影响范围：** 画像偏好、技能搜索、认证及使用画像的业务模块。

**遗留问题：** 继续检查岗位匹配、认证、数据库与端到端行为。

---
### [2026-11-06 10:48] 会话恢复和身份测试

**任务目标：** 恢复登录刷新后的会话，并验证认证主体与业务用户解析的衔接。

**执行行为：** 合并前端路由守卫、CSRF 获取与刷新令牌流程；登录页增加找回密码入口。新增 `CurrentUserResolverTest.java` 覆盖已认证主体和未认证请求。

**文件变更：** `frontend/src/api/auth.ts`、`frontend/src/stores/user.ts`、`frontend/src/router/index.ts`、`frontend/src/views/login/LoginView.vue`；新增 `backend/src/test/java/com/aijob/backend/common/CurrentUserResolverTest.java`。

**执行命令与验证：** 后端业务测试 76 项通过，前端生产构建通过；本步骤修改后仍需重新验证。

**影响范围：** 登录会话恢复、路由访问和业务用户身份解析。

**遗留问题：** MySQL 测试服务当前不可达，完整登录及业务联调尚未验证。

---
### [2026-11-06 10:50] 修复 Agent 注册表测试

**任务目标：** 修复岗位搜索加入后过时的技能数量断言。

**执行行为：** 将固定数量断言改为逐项比较注册技能与操作集合，并明确检查岗位搜索操作。

**文件变更：** `project/agent/tests/test_skills.py`。

**执行命令与验证：** `D:\Anaconda\envs\ai-job\python.exe -m pytest -q` 首次执行发现固定断言仍为 13，而实际操作为 14；修复后待重新运行。

**影响范围：** Agent 测试，不改变生产行为。

**遗留问题：** 需复跑全部 Agent 测试。

---
### [2026-11-06 10:52] 跨模块集成验证

**任务目标：** 验证真实登录令牌可以安全访问合并后的画像与投递接口。

**执行行为：** 使用本机独立 MySQL、Redis 和测试 SMTP 服务，运行登录与投递进度集成测试；新增跨模块登录、画像更新、投递查询及伪造身份头拒绝测试。

**文件变更：** `project/backend/src/test/java/com/aijob/backend/AuthFlowIntegrationTest.java`。

**执行命令与验证：** `mvn -q test -Dtest=AuthFlowIntegrationTest,ApplicationIntegrationTest -Dintegration-tests=true` 首轮通过；Flyway V1 至 V5 在独立数据库执行成功。新增测试后待复跑。Agent 完整测试修复断言后通过，含 1 项跳过。

**影响范围：** 登录、用户画像、投递进度与身份隔离。

**遗留问题：** 新增跨模块测试尚需执行；测试服务为独立实例。

---
### [2026-11-06 10:54] 修复新用户画像版本冲突

**任务目标：** 统一登录注册与业务模块对画像初始版本的要求。

**执行行为：** 跨模块测试发现注册插入的画像默认版本为 0，而画像、简历、成长、匹配及 Agent 期望版本至少为 1。注册时明确写入版本 1，并新增 Flyway V6 将已有版本为 0 的画像调整为 1。

**文件变更：** 修改 `project/backend/src/main/java/com/aijob/backend/user/mapper/UserProfileMapper.java`；新增 `project/backend/src/main/resources/db/migration/V6__normalize_initial_profile_version.sql`。

**执行命令与验证：** 新增跨模块测试首次失败，画像保存返回版本校验错误；修复后待重跑。

**影响范围：** 新注册用户及历史零版本画像；版本变更可能要求已缓存旧版本的客户端刷新后重试。

**遗留问题：** 需要验证 V6 迁移与登录、画像、投递链路。

---
### [2026-11-06 10:56] 完成整合验证与文档修正

**任务目标：** 确保合并结果、启动说明和实际测试一致。

**执行行为：** 修正根目录和后端 README 中过时的 Redis、认证、Agent、测试和迁移描述。

**文件变更：** `readme.md`、`project/backend/README.md`；前端生产构建产物由 `npm run build` 重新生成。

**执行命令与验证：** 后端在隔离 MySQL、Redis、SMTP 上运行 `mvn -q test -Dtest=*Test,!DatabaseQueryTest -Dintegration-tests=true`，120 项通过；新增真实登录、画像、投递和伪造身份测试通过。Agent 完整 pytest 通过，1 项 SQLite 参数场景以外的测试按设计跳过；前端 `npm run build` 通过。

**影响范围：** 本地启动、测试环境及零版本画像迁移。

**遗留问题：** 未运行依赖共享演示数据的 `DatabaseQueryTest`；未进行浏览器全流程人工验收。

---

### [2026-11-10 14:32] 核对求职工作台合并来源与工作区

**任务目标：** 将截图所示的 feature/job-dashboard 合入本地 main，冲突保留 main，验收后按优先级记录缺陷。

**执行行为：**

1. 核对当前 main 为 6798292，已有多项岗位搜索、配置、文档及 Agent SQLite 未提交改动；采用隔离副本验收，暂不改动这些文件。
2. 已检查规范提到的旧目录 D:/vscode/AI-JOB/docs/功能资料，不存在；在实际仓库内已有的合并日志末尾追加。
3. 核对本地分支、远端跟踪分支和截图中的短提交号；本地没有目标分支或 3ff6aca 对象。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | docs/功能资料/merge_process.md | 追加来源核对与隔离验收记录 | 保存合并前状态 |

**执行命令与验证：**

- 命令：git status --short --branch、git branch -avv、git cat-file -t 3ff6aca、git ls-remote --heads origin refs/heads/main refs/heads/feature/job-dashboard。
- 结果：本地核对通过；默认网络请求无法连接本机代理，单命令取消代理后的沙箱请求也未成功。

**影响范围：** 只追加本过程文件；尚未改变分支、功能代码或数据库。

**遗留问题：** 获取私有远端目标提交并检查合并差异。

---

### [2026-11-10 14:36] 获取目标提交并预合并

**任务目标：** 核实提交身份，定位冲突文件。

**执行行为：** 通过现有代理查询私有远端；确认远端 main 为 6798292，目标分支为 3ff6aca，与截图一致。在独立副本获取分支并预合并，原工作区保持原状。

**文件变更：** 本工作区只追加本过程记录。独立副本暂存目标分支新增的工作台、安装脚本与规格文件；冲突文件仍待处理。

**执行命令与验证：** git ls-remote、git fetch、git merge --no-commit --no-ff 已执行；合并预期停在冲突：.gitignore、project/backend/README.md、project/frontend/src/api/request.ts、project/frontend/src/views/dashboard/DashboardView.vue、readme.md，共 5 个。

**影响范围：** 独立副本，尚未更新本地 main 或远端。

**遗留问题：** 按主分支优先解决冲突，复核自动合并的运行配置及工作台接线。

---

### [2026-11-10 14:40] 按 main 解决五个冲突

**任务目标：** 保留主分支的工作台入口、认证请求层与现有配置说明。

**执行行为：** 在隔离副本对五个冲突文件执行 git restore --ours 并暂存。按用户补充要求，待本地 main 接收独立合并提交后，再在原工作区执行 git add . 暂存已有改动。

**文件变更：** 隔离副本中五个冲突文件与原 main 一致；其余无冲突新增文件暂存。本工作区只追加本日志。

**执行命令与验证：** git diff --name-only --diff-filter=U、git diff --cached --name-only（限定五个冲突文件）、git diff --cached --check 均无输出，未发现未解决冲突或空白错误。

**影响范围：** 新工作台页面及替代请求层的冲突内容未覆盖 main；工作台模型和安装脚本保留。

**遗留问题：** 核对前端新模块能否在 main 请求层下编译，并验证用户可达页面与后端数据。

---

### [2026-11-10 14:42] 发现保留主分支请求层后的兼容问题

**任务目标：** 核对无冲突合入的工作台模块在主分支请求层下能否运行。

**执行行为：** 使用隔离副本离线恢复前端依赖并执行首轮类型检查、模型测试。

**文件变更：** 隔离副本产生 node_modules 和工作台测试临时输出；原工作区只追加此日志。

**执行命令与验证：** npm ci --offline --no-audit --no-fund 通过；npm run type-check 失败，工作台引用已按主分支保留的请求层没有的 apiGet、ApiRequestError、QueryParams、toApiError 及 timeoutMs；npm run verify:models 失败，旧 CommonJS 测试直接编译包含 import.meta.env 的主分支模块。

**影响范围：** 合并候选前端构建及分支新增模型测试。

**遗留问题：** 在新增模块内复用主分支 request，保留认证、取消及超时行为，并调整测试入口后复测。

---

### [2026-11-10 14:45] 修复合并接线并完成自动化回归

**任务目标：** 恢复新增工作台模块的构建与验证，确认合并候选的后端及 Agent。

**执行行为：** 新增工作台 API 改用 main 的 request，保留 Bearer、会话刷新机制、超时和取消；对工作台模型测试使用 Vite 加载真实模块。启动专用 MySQL 3307、Redis 16393 和本机 SMTP 11025。未使用已有业务 MySQL。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | project/frontend/src/api/workbench.ts | 复用认证请求并转换工作台错误 | 兼容 main 的请求层 |
| 修改 | project/frontend/src/features/workbench/useWorkbench.ts | 改用工作台 API 的选项及错误转换 | 保留区域失败处理 |
| 修改 | project/frontend/package.json；project/frontend/scripts/verify-workbench-models.cjs | 用 Vite SSR 执行模型及请求验证，覆盖 Bearer、认证失败、超时、取消与网络失败 | 避免 CommonJS 直接编译 import.meta.env |
| 移除待合入文件 | project/frontend/scripts/tsconfig.workbench-verify.json | 移除旧 CommonJS 测试编译配置 | 不再需要单独编译 |
| 修改 | docs/功能资料/merge_process.md | 追加兼容修复、测试和隔离依赖记录 | 保留验收证据 |

**执行命令与验证：** npm run build、npm run verify:models 通过；mvn -o -q verify -Dintegration-tests=true 完成，125 通过、6 跳过，Flyway 执行 V1–V7；Agent python -m pytest -q 全量执行成功，1 跳过；10 个 PowerShell 文件语法和 bash -n scripts/dev-env/setup.sh 通过。

**影响范围：** 保留主分支页面和公共认证；测试依赖只写验收副本。构建生成的 dist、声明和临时输出不纳入合并提交。

**遗留问题：** 完成真实前后端浏览器验收，核实工作台结果及 Agent 回调。

---

### [2026-11-10 14:47] 合并提交进入本地 main

**任务目标：** 将已解决冲突的提交合入本地 main，并保留原工作区的已有改动。

**执行行为：** 在隔离副本创建合并提交 fb59cf9，父提交为 6798292 与 3ff6aca；从本地隔离副本获取后快进原工作区 main。

**文件变更：** 合并提交包含 46 个文件，完整列表见 git diff --name-status 6798292 fb59cf9；主要是工作台模型、组件与测试、开发环境脚本及规格。五个冲突文件与第一父提交一致；过程日志在合并提交之外。

**执行命令与验证：** git diff --cached --check、git merge --ff-only FETCH_HEAD、git merge-base --is-ancestor 3ff6aca HEAD 通过。未推送远端。

**影响范围：** 本地 main 已包含目标分支历史；原有工作区改动保留。

**遗留问题：** 继续浏览器验收并记录可复现缺陷，然后按用户要求暂存当前工作区。

---

### [2026-11-10 14:53] 浏览器验收、数据库补测与缺陷复现

**任务目标：** 验证已合并版本的功能入口、实际数据流和关键结果，记录可复现缺陷。

**执行行为：**

1. 通过本机 Playwright Chromium 访问隔离站点 http://127.0.0.1:5173，使用虚构账号。检查 1440×960 桌面和 390×844 移动视口、未登录跳转和登录、工作台空态、画像更新后刷新，以及画像、简历、智能简历优化、岗位推荐、旧岗位搜索跳转、训练、项目、面试、成长、投递、任务共 11 个入口。
2. 用虚构 JD 走真实 Vite 代理、Java、Agent 和回调。Agent 使用 fake 模型；任务状态为 SUCCEEDED，岗位状态为 ACTIVE 并有结构化要求，工作台动态链接跳转到对应任务。
3. 使用可控匹配接口响应验证：可用 READY 批次显示岗位；当最新批次改为 FAILED 时，工作台变为无可用岗位且未请求旧 READY 详情。此场景使用接口桩，其他页面及 JD 回调使用真实隔离服务。
4. 仅向隔离库导入仓库自带虚构演示 SQL，打开 mysqlIt 测试开关补跑 6 项。安装脚本完成语法与 11 项内置断言；Docker daemon 未运行，未执行安装、回滚或清理功能。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | docs/功能资料/merge_process.md | 追加验收结果、分级缺陷和复现步骤 | 保存可复核的证据 |
| 无业务代码改动 | 隔离副本及其外部验收目录 | 生成浏览器截图、运行日志及测试夹具 | 记录行为，不纳入项目提交 |

**执行命令与验证：**

- node browser-qa.cjs：工作台正确加载且无框架错误遮罩；登录、画像刷新、11 个入口及 JD 回调通过。新会话 GET /api/v1/auth/csrf 的一次 401 是未登录分支，后续登录成功。桌面遮挡与批次回退缺陷已复现；390px 未见横向溢出。
- mvn -o -q verify -Dintegration-tests=true：125 通过；另执行 mvn -o -q test -Dtest=DatabaseQueryTest -DmysqlIt=true：5 通过、1 失败。两次合计 130 通过、1 失败。
- python -m pytest -q：Agent 367 通过、1 跳过。npm run type-check、npm run verify:models、npm run build 通过。
- pwsh -NoProfile -File scripts/dev-env/setup.ps1 -Command check -DryRun -NonInteractive：内置核心断言 6/6、版本解析断言 5/5；实际环境检查返回 DockerUnavailable 14。非 DryRun check 在隔离副本生成 .local-dev-env/，git status 显示未跟踪。

**影响范围：** 验收只针对合并提交 fb59cf9；原工作区已有岗位搜索改动不在验收版本中。浏览器业务路径未使用真实账户或真实投递。

**遗留问题：**

1. P1 新工作台页面未接入。启动系统，登录后点击「求职工作台」，仍显示 main 的旧布局；新增区域组件未被 DashboardView 引用。复查 routes.ts 与 DashboardView.vue 可见。属于主分支优先冲突取舍，新版 UI 的完整交付未达成。
2. P2 工作台隐藏既有岗位。先生成一个有合格岗位的 READY 匹配批次，再令下一次匹配失败，回工作台刷新。实际显示「暂无可用目标岗位」；预期保留仍有效的上次结果并提示本次失败。测试时固定两个批次，旧批次 82 分且 PASS。定位 DashboardView.vue:37。
3. P2 桌面刷新控件被遮挡。在 1440×960 打开工作台、保持页面顶部，点击右上「刷新工作台」中心。实际点击命中用户名/账户菜单；预期应发出刷新请求。390×844 视口未出现同样遮挡。截图 dashboard-before-refresh.png。
4. P2 演示库技能编码与验收断言不一致。在独立库执行 Flyway，导入 src/main/resources/db/demo/demo_data.sql，执行 mvn -o -q test -Dtest=DatabaseQueryTest -DmysqlIt=true。skillCatalogQuery 失败：演示数据是 java/mysql/redis/spring，测试要求 JAVA/MYSQL/REDIS/SPRING_BOOT。旧 main 已有该断言；此次分支新增显式测试开关。
5. P3 安装脚本运行产物未忽略。在干净副本执行 pwsh -NoProfile -File scripts/dev-env/setup.ps1 -Command check -NonInteractive，再执行 git status --short -- .local-dev-env。输出 ?? .local-dev-env/，与脚本 README 声称已忽略不一致。

未验证生产环境、真实联网岗位来源的覆盖与结果质量、真实模型生成质量；部分模块仅验证入口与现有自动化用例。

---

### [2026-11-10 14:54] 收尾：关闭隔离服务并按要求暂存

**任务目标：** 释放验收端口，按用户补充要求暂存原工作区改动。

**执行行为：** 按记录的进程标识停止本轮独立的 Vite、Java、Agent、SMTP、Redis 与 MySQL，保留验收截图和日志。原工作区执行 git add .，包含此前已有未提交改动及过程记录。

**文件变更：** 追加本过程记录，暂存区共 46 个变更文件。暂存内容未提交，不属于 fb59cf9。

**执行命令与验证：** git add . 返回成功；核对当前分支为 main 且 HEAD 仍为 fb59cf9。随后同步暂存本条过程记录。

**影响范围：** 仅本地 Git 暂存区及隔离验收进程。未推送、未替换或关闭原业务数据库。

**遗留问题：** 上条记录中的五项缺陷待处理；远端 main 未更新。

---

### [2026-11-10 15:10] 接入新版求职工作台页面

**任务目标：** 修复 P1，使左侧「求职工作台」打开 feature/job-dashboard 实现的新版页面。

**执行行为：** 检查当前 main 和用户的已有暂存改动，确认工作台页面无本地编辑。保留 /dashboard 路由和左侧入口，将页面切换到分支已有的欢迎区、目标摘要、下一项任务、目标岗位、阶段进度及最新动态组件。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/frontend/src/views/dashboard/DashboardView.vue | 接入分支组件和 useWorkbench | 让现有入口显示新版 |
| 修改 | docs/功能资料/merge_process.md | 追加 P1 修复过程 | 记录页面接线与验证范围 |

**执行命令与验证：** git diff HEAD --name-only 检查目标页面无已有修改；git show 3ff6aca 查看并复用分支页面。构建及浏览器验证待执行。

**影响范围：** 登录后进入 /dashboard 的工作台页面。

**遗留问题：** 对齐 main 的接口及路由，验证已登录、空数据、有数据和请求失败的表现。

---

### [2026-11-10 15:12] 对齐新版工作台的接口、导航和进度

**任务目标：** 使接入的新版工作台在 main 的现有认证、匹配和投递接口下正常工作。

**执行行为：** 统一共享匹配请求返回的记录数组，避免同一个缓存键在岗位、进度和动态区域被当成不同类型。采用当前岗位入口和 matchId 参数，投递阶段读取应用统计真实数据，保持六阶段计算。为欢迎区预留顶部空间，避免现有账户菜单拦截刷新控件。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/frontend/src/views/dashboard/DashboardView.vue | 在挂载时加载，调整工作台网格与顶部间距 | 正常进入及操作新版 |
| 修改 | project/frontend/src/api/workbench.ts；project/frontend/src/api/mock/workbench.mock.ts；project/frontend/src/features/workbench/useWorkbench.ts | 统一匹配数据格式，读取投递总数，保留区域错误状态 | 兼容 main 数据契约 |
| 修改 | project/frontend/src/features/workbench/model/progress.ts | 投递阶段按实际记录判定，使用六阶段进度 | 投递功能在 main 已开放 |
| 修改 | project/frontend/src/features/workbench/model/activities.ts；project/frontend/src/features/workbench/model/targetJobs.ts；project/frontend/src/features/workbench/model/nextTask.ts；project/frontend/src/views/dashboard/components/TargetJobsPanel.vue | 改用 main 的岗位匹配入口和参数 | 点击打开正确页面与结果 |
| 修改 | project/frontend/scripts/verify-workbench-models.cjs | 更新六阶段百分比、投递状态和匹配跳转断言 | 检查接口适配后的关键计算与导航 |

**执行命令与验证：** 已检查共享请求调用方、投递统计接口和岗位页面的 query 参数。构建和浏览器验收待执行。

**影响范围：** 新版 /dashboard 的数据请求、各区域显示和跳转。

**遗留问题：** 使用隔离环境验证入口、真实接口和可控失败响应。

---

### [2026-11-10 15:16] 验证新版的真实接口与页面跳转

**任务目标：** 确认新版能使用当前后端数据，并复核工作台入口下的辅助导航。

**执行行为：** 沿用 main 的登录身份查询规则，目标岗位名称补用画像岗位字典。检查下一项任务的空态跳转、匹配生成文案以及离开页面后的请求失效处理。在隔离副本启动 Vite、Java、Redis 与测试 MySQL，使用虚构账号验收。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/frontend/src/api/workbench.ts；project/frontend/src/features/workbench/model/goalSummary.ts | 对齐身份读取规则和实际画像岗位名称 | 适配 main 已支持的登录和画像 |
| 修改 | project/frontend/src/views/dashboard/components/NextTaskCard.vue；project/frontend/src/views/dashboard/components/TargetJobsPanel.vue | 空态前往正确匹配标签，说明手动刷新 | 对齐入口及实际刷新行为 |
| 修改 | project/frontend/src/features/workbench/useWorkbench.ts | 离开页面时失效未完成加载 | 避免卸载后的请求结果回写 |
| 修改 | docs/功能资料/merge_process.md | 记录测试进展 | 保留验收证据 |

**执行命令与验证：** 隔离副本 npm run verify:models 与首次 npm run build 通过。Chromium 首轮确认登录、工作台空态、真实画像更新、真实投递统计以及 2/6 显示 33%。匹配卡片与跳转 URL 正常；首轮匹配详情测试夹具缺少 reasons 和 source 字段，详情断言超时，已修正夹具待复测。

**影响范围：** 仅新版工作台及本机隔离验收环境。

**遗留问题：** 复测匹配详情、局部失败重试和移动端布局。

---

### [2026-11-10 15:18] 完成新版工作台 P1 验收

**任务目标：** 确认当前左侧入口打开新版工作台，并在桌面和移动视口可用。

**执行行为：** 使用 Playwright Chromium 运行真实隔离登录和业务数据流程，验证画像、投递统计、岗位卡片跳转、从侧栏返回和区域重试。匹配批次和岗位详情使用符合当前接口契约的固定夹具。检查现有 Vite 5173 提供的工作台模块已包含 useWorkbench 和 ProgressPanel。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | docs/功能资料/merge_process.md | 追加 P1 验收结论 | 记录实测范围与结果 |
| 验收产物 | 仓库外 dashboard-p1 目录 | 测试脚本、构建产物、桌面与移动截图 | 独立复现与视觉核对 |

**执行命令与验证：** npm run verify:models 和最终 npm run build 均通过。Chromium 1440×960 与 390×844 检查通过。登录后工作台由 0/6 随真实画像与投递数据更新至 2/6、33%；注入一批 READY 匹配后显示 3/6、50%，且共用匹配列表只发出一次请求。匹配卡片跳转至对应岗位和匹配批次，局部 503 后区域重试恢复，移动端刷新正常且无横向溢出。没有页面运行异常或 Vue 警告。Vite 5173 已提供新版模块。

**影响范围：** 当前工作区的 /dashboard 路由；正常前端开发服务刷新即可查看。

**遗留问题：** 真实岗位匹配生成链路本轮使用固定响应替代；其余缺陷按原记录跟进。生产构建仍提示主包超过 Vite 的大小提醒阈值。

---

### [2026-11-10 15:19] 收尾隔离验收环境

**任务目标：** 结束本轮启动的测试服务，释放验收端口。

**执行行为：** 按监听端口和进程核对后结束验收 Vite、Java、Redis。MySQL 首次核对发现监听者为子进程，复核其数据目录后通过 mysqladmin 正常关闭。

**文件变更：** 仅追加本过程记录，保留验收证据。

**执行命令与验证：** 测试服务停止命令均已完成；git diff --check 检查本轮文件，无空白错误。

**影响范围：** 本轮独立验收端口和进程。

**遗留问题：** 暂无验收环境收尾事项。

---

### [2026-11-18 10:51:00] 获取项目实战分支并导入新版页面

**任务目标：** 将 feature/project-practice 的项目实战界面合入 feature/bug，沿用当前系统架构。

**执行行为：**

1. 从 origin 定向获取 feature/project-practice，确认分叉点 f7df42b、页面提交 a7287c4 与分支末端 fda1a43。
2. 核对目标分支的推荐数据扩展与现有接口。目标 V14 与当前专项训练 V14 重号，页面布局提交可使用当前接口独立运行。
3. 读取 a7287c4 中的项目实战页面并写入当前文件，导入推荐候选及详情、计划卡片、任务时间线与交付弹窗。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/views/project/ProjectView.vue` | 导入目标分支新版项目实战页面 | 接入新 UI |
| 修改 | `docs/功能资料/merge_process.md` | 追加分支核对及页面导入记录 | 记录合并范围 |

**执行命令与验证：**

- 命令：`git fetch --no-tags origin refs/heads/feature/project-practice:refs/remotes/origin/feature/project-practice`；结果：通过。
- 命令：`git merge-base`、`git log`、`git diff` 与 `git show a7287c4:project/frontend/src/views/project/ProjectView.vue`；结果：确认页面与 GitHub 推荐数据扩展可分开导入，当前分支已有独立的 V14。

**影响范围：** 工作区的项目实战页面和本过程记录。启动服务日志及 Agent SQLite 运行时文件在本任务开始前已有修改。

**遗留问题：** 适配原有入口与分页，完成浏览器和构建验证后提交合并。

---

### [2026-11-18 10:51:42] 适配当前项目实战入口和交互

**任务目标：** 接入新页面并兼容 feature/bug 已有项目实战操作。

**执行行为：**

1. 保留无参数入口默认显示计划列表，兼容推荐与计划直达链接以及显式页面切换。
2. 在新版计划列表和切换计划弹窗保留原来的 6、12、24 条分页选项；推荐任务重试失败时，在页面上显示错误。
3. 保持现有项目实战请求、任务追踪及版本冲突处理的服务接口。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/views/project/ProjectView.vue` | 适配路由、分页与重试反馈 | 保留现有入口及操作 |
| 修改 | `docs/功能资料/merge_process.md` | 追加页面适配记录 | 记录兼容行为 |

**执行命令与验证：**

- 命令：`git diff --check`；后续核对构建与浏览器结果。

**影响范围：** /project 的页面行为。继续使用现有 Java、Agent 和数据库。

**遗留问题：** 待验证构建、推荐和计划界面的主要交互及移动端。

---

### [2026-11-18 10:56:07] 验证项目实战新版页面

**任务目标：** 验证现有接口契约下新版项目实战页面的构建及交互。

**执行行为：**

1. 使用独立输出目录生成前端生产包，运行工作台模型回归命令。
2. Browser 插件不可用，使用本机 Chrome 与已有 Playwright，在 1440×960 和 390×844 视口运行浏览器验收。测试通过拦截请求提供符合现有接口的固定数据。
3. 首轮浏览器测试定位到了 Element Plus 隐藏的 checkbox 原生 input，点击超时。改为点击可见的 checkbox 元件并校验原生状态，复测通过。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/merge_process.md` | 追加构建与交互验证结果 | 记录证据 |
| 验收产物 | 仓库外本任务可视化目录 | 临时 Playwright 脚本、桌面和移动端截图及生产构建产物 | 复现和核对验证 |

**执行命令与验证：**

- 命令：`npm.cmd run build -- --outDir <独立目录>`；结果：通过类型检查与生产构建，Vite 提示主包超过默认大小提醒阈值。
- 命令：`npm.cmd run verify:models`；结果：通过。
- 命令：`node.exe <仓库外目录>/project-practice-qa.cjs`；结果：复测通过。覆盖项目入口及深链接、分页条数调整、推荐提交、候选详情切换、计划生成、任务进度、409 版本冲突恢复、交付记录保存和切换历史计划。未见框架错误叠层或意外的控制台错误。

**影响范围：** 本机前端开发服务与临时浏览器测试环境，没有写入本机业务数据库。

**遗留问题：** 本轮通过现有接口格式的固定响应验收，未登录真实账号或调用真实模型。待核验最终 Git 合并边界。

---

### [2026-11-18 10:56:44] 建立仅引入项目实战 UI 的分支合并

**任务目标：** 在 feature/bug 上建立双亲合并，限定合并结果为项目实战新版页面和过程记录。

**执行行为：**

1. 确认合并前的当前提交为 24482ac、目标为 fda1a43，且暂存区无既有改动。
2. 使用 ours 策略启动不自动提交的合并，保留当前分支文件树作为基线，并确认 MERGE_HEAD 指向目标分支。对合并结果仅暂存经过适配的项目实战页面和过程记录。
3. 目标分支的后端、Agent、接口类型与 GitHub 推荐来源迁移不纳入本次文件变更。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/views/project/ProjectView.vue` | 暂存经验证的新版项目实战页面 | 交付项目实战 UI |
| 修改 | `docs/功能资料/merge_process.md` | 追加选择性合并的执行记录 | 记录合并历史及范围 |

**执行命令与验证：**

- 命令：`git merge --no-ff --no-commit -s ours origin/feature/project-practice`；结果：已进入合并状态，第二父提交指向 fda1a43。
- 命令：`git diff --cached --name-only` 与 `git diff --cached --check`；用于提交前核对范围和空白。

**影响范围：** 当前分支的 Git 合并历史及项目实战页面。已有启动记录与 SQLite 运行时改动均不纳入暂存。

**遗留问题：** 继续核对最终浏览器验收与合并提交。

---

### [2026-11-18 10:57:39] 修复窄屏下计划分页溢出

**任务目标：** 保留分页尺寸选项，避免计划列表和切换弹窗横向滚动。

**执行行为：**

1. 在 390×844 视口增加对实际内容滚动容器的测量，发现计划列表的 326 像素容器出现 341 像素滚动宽度。分页类直接挂在组件根节点时，现有样式的后代选择器不生效。
2. 为计划列表与切换弹窗的分页补上项目通用的分页容器并收窄页码组上限，保留原有每页条数选项。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/views/project/ProjectView.vue` | 复用通用分页容器，单组最多显示 5 个页码 | 使窄屏分页正常换行 |
| 修改 | `docs/功能资料/merge_process.md` | 记录失败证据及修复 | 记录合并中发现的问题 |

**执行命令与验证：**

- 命令：`node.exe <仓库外目录>/project-practice-qa.cjs`；结果：增强检查首次因窄屏溢出失败，修复已应用，待重跑。
- 命令：`git diff --check`；结果：检查补丁空白。

**影响范围：** 项目实战计划列表和切换弹窗的分页排版。

**遗留问题：** 重新执行浏览器验收和生产构建，再完成合并提交。

---

### [2026-11-18 10:58:47] 完成复测并核对提交范围

**任务目标：** 核对修复后的 UI，并提交仅包含页面和过程记录的分支合并结果。

**执行行为：**

1. 复跑项目实战全流程浏览器测试。测量桌面和移动端页面、内容容器与弹窗的滚动宽度，确认计划列表及切换弹窗无横向溢出。
2. 重新运行生产构建并核对合并父提交，检查暂存范围只包含项目实战页面与过程记录。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/views/project/ProjectView.vue` | 纳入最终版页面和分页修复 | 交付测试通过的界面 |
| 修改 | `docs/功能资料/merge_process.md` | 追加最终复测与范围记录 | 保留验证结论 |

**执行命令与验证：**

- 命令：`node.exe <仓库外目录>/project-practice-qa.cjs`；结果：桌面和移动端完整复测通过，生成三张页面截图。
- 命令：`npm.cmd run build -- --outDir <独立目录> --emptyOutDir`；结果：类型检查和生产构建通过，保留 Vite 体积提醒。
- 命令：`git diff --cached --check` 和 `git diff --cached --name-only`；作为建立本地合并提交前的检查。

**影响范围：** 仅项目实战新版页面和合并记录进入本次提交，继续使用 feature/bug 既有接口。Git 合并历史纳入目标分支截至 fda1a43 的提交，目标分支的 GitHub 推荐链路未进入合并文件树。

**遗留问题：** 本次浏览器验收使用接口测试数据，未调用真实模型或写入真实账号数据。

---

### [2026-11-19 14:09:05] 获取语音面试分支并核对合并起点

**任务目标：** 将 `feature/voice-interview` 合入当前 `feature/bug`。

**执行行为：**

1. 确认当前提交为 `cc71c08`，暂存区为空，工作区已有项目实战、文档、构建产物和 SQLite 运行时改动。
2. 从配置的 `origin` 获取 `feature/voice-interview` 到本地远程跟踪引用。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/merge_process.md` | 追加合并起点与分支获取记录 | 保存操作依据 |

**执行命令与验证：**

- 命令：`git status --short --branch`、`git diff --cached --name-only`。结果：当前分支为 `feature/bug`，可见已有工作区改动，暂存区为空。
- 命令：`git fetch origin refs/heads/feature/voice-interview:refs/remotes/origin/feature/voice-interview`。结果：通过，建立本地远程跟踪引用。

**影响范围：** 本地 Git 引用及合并过程记录。规范举例的 `D:/vscode/AI-JOB/docs/功能资料` 不存在，记录追加到当前仓库的同一相对路径。

**遗留问题：** 核对变更重叠、数据库迁移和测试范围后进行合并。

---

### [2026-11-19 14:10:53] 暂存语音面试分支并恢复本地项目实战改动

**任务目标：** 完整引入语音面试分支，同时保护合并前的未提交工作。

**执行行为：**

1. 比对源分支和工作区，确认仅 `project/agent/app/config.py` 和 `project/frontend/src/api/types.ts` 路径重叠。在独立目录保存原文件及 SHA-256，并用 `git merge-file -p` 预先合成保留两侧改动的工作区版本。
2. 校验原文件未发生变化，临时恢复两处重叠文件到当前提交，执行 `git merge --no-ff --no-commit origin/feature/voice-interview`，随后恢复两处合成后的工作区文件。项目实战部分保留在暂存区外。
3. 核对源分支和现有工作区，发现语音面试和项目仓库来源的迁移均使用 V15，工作区还有项目推荐发现的 V16。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `.codeartsdoer/specs/voice_interview/`、`docs/功能资料/09-模拟面试/` | 引入源分支的语音规格、设计、任务、过程及回滚文档 | 纳入语音面试交付资料 |
| 新增或修改 | `project/agent/app/`、`project/agent/tests/`、`requirements.txt` | 引入语音配置、内部 API、Mock Provider、单测和 multipart 依赖 | 支持 Mock 语音识别和播报 |
| 新增或修改 | `project/backend/src/main/`、`project/backend/src/test/` | 引入面试语音 API、权限、存储、Flyway 脚本、相关面试和 Java 测试 | 支持语音面试业务功能 |
| 新增或修改 | `project/frontend/src/api/`、`project/frontend/src/composables/voice/`、`project/frontend/src/utils/voice/`、`project/frontend/src/views/interview/` | 引入录音、转写确认、播报及面试页面集成 | 支持语音面试交互 |
| 修改 | `docs/功能资料/merge_process.md` | 追加暂存和保护本地工作区记录 | 保存合并过程 |

**执行命令与验证：**

- 命令：`git merge-file -p`、`git merge --no-ff --no-commit origin/feature/voice-interview`、`git rev-parse MERGE_HEAD`。结果：无 Git 冲突，暂存 55 个源分支文件，`MERGE_HEAD` 为 `6da3514`。
- 命令：`git diff --name-only -- project/agent/app/config.py project/frontend/src/api/types.ts`。结果：两处原有项目实战改动已恢复为工作区差异。

**影响范围：** 当前分支处于待提交合并状态，源分支变更已暂存，本地项目实战改动仍在工作区。

**遗留问题：** 调整语音迁移版本后执行验证并完成提交。

---

### [2026-11-19 14:11:49] 解决 Flyway 迁移编号冲突

**任务目标：** 保留项目实战 V15、V16，将语音面试迁移放在其后。

**执行行为：**

1. 将语音面试脚本从 V15 移至 V17，更新脚本版本注释，保留 SQL 语句。
2. 更新运维手册的迁移版本及旧语音分支数据库的核对说明。设计和历史过程记录仍保留当时的编号。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 重命名 | `project/backend/src/main/resources/db/migration/V15__add_voice_interview.sql` → `project/backend/src/main/resources/db/migration/V17__add_voice_interview.sql` | 调整语音迁移编号及头部注释 | 避免与项目实战 V15 重复 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试-回滚与清理.md` | 对齐 V17，并补充旧语音 V15 数据库的迁移提示 | 防止发布时误用旧编号 |
| 修改 | `docs/功能资料/merge_process.md` | 记录迁移改号 | 保留合并依据 |

**执行命令与验证：**

- 命令：`Get-ChildItem ... -Filter 'V*__*.sql' | Group-Object { $_.BaseName.Split('__')[0] }`。结果：工作区未发现重复版本，V15 和 V16 为项目实战迁移，V17 为语音迁移。

**影响范围：** 语音面试的 Flyway 版本调整，项目实战的本地 V15 和 V16 脚本仍保留在工作区。

**遗留问题：** 有旧语音 V15 的数据库需单独核对迁移历史。项目实战 V15 和 V16 仍未提交，部署到共享环境前需确认它们与 V17 一起发布。

---

### [2026-11-19 14:14:04] 首轮三端检查与隔离迁移验证

**任务目标：** 验证暂存合并与本地项目实战改动共同运行。

**执行行为：**

1. 运行 Agent 全量测试、Java 定向测试和前端构建。
2. 临时初始化独立 MySQL 8.0 实例，使用 Flyway 在空库迁移到 V16，再执行 V17 与校验，检查历史面试的模式列默认值为 `TEXT`，最后关闭临时实例。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/merge_process.md` | 追加首轮验证结论 | 保留实际结果 |

**执行命令与验证：**

- 命令：`python -m pytest -o addopts='' -q`。结果：391 通过，4 失败，118 错误。默认临时目录权限导致 118 项夹具错误，浏览器子进程权限导致 2 项渲染测试失败，语音的生产配置错误文案及音频类型校验各失败 1 项。
- 命令：`mvn -o -q -Dtest=InterviewServiceTest,QuestionSpeechServiceTest,ProjectResultHandlerTest,ProjectServiceTechStackKeywordsTest,TaskRetryServiceTechStackKeywordsTest test`。结果：23 项通过。
- 命令：`npm.cmd run build -- --outDir <独立目录> --emptyOutDir`。结果：TypeScript 校验已通过，Vite 在启动 esbuild 子进程时遇到 `spawn EPERM`。
- 命令：`python <独立目录>/run_mysql_check.py <输出根目录>`。结果：隔离 MySQL 的 V1→V16、V16→V17 及 Flyway 校验均通过。

**影响范围：** 验证仅使用临时测试数据和构建目录，未写入现有业务库。

**遗留问题：** 修复语音测试暴露的问题并调整测试环境后复跑。

---


### [2026-11-19 14:15:03] 修复语音面试合入后的 Agent 校验和启动问题

**任务目标：** 修复源分支既有语音测试暴露的问题，并确保未启用语音的生产 Agent 能正常启动。

**执行行为：**

1. 将声明音频格式与可识别的文件头匹配，拒绝未识别或不一致的内容。
2. 生产环境的自动模式关闭 Mock 语音路由，显式配置 Mock 仍触发生产限制。补充回归覆盖，有无语音服务密钥都可创建核心应用。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/app/config.py`、`project/agent/app/api.py` | 生产自动模式关闭语音路由，明确 Mock 配置错误 | 避免 Mock 功能阻断已有生产 Agent |
| 修改 | `project/agent/app/speech/service.py` | 根据音频文件头检验声明格式 | 使语音服务拒绝格式不符的上传 |
| 修改 | `project/agent/tests/test_speech_config.py` | 覆盖生产自动模式保持核心接口可用 | 防止启动兼容性回归 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试-回滚与清理.md` | 补充当前 Mock 发布边界及后端语音开关 | 记录生产配置要求 |
| 修改 | `docs/功能资料/merge_process.md` | 追加修复过程 | 记录合并改动 |

**执行命令与验证：**

- 语音全量复测待执行。

**影响范围：** 仅涉及 Agent 语音功能启用和输入校验，真实语音服务仍由源分支后续工作实现。

**遗留问题：** 复跑 Agent 测试、前端构建和提交范围检查。

---


### [2026-11-19 14:17:00] 修正本地自动选择语音 Provider 的启动回归

**任务目标：** 让含有语音配置的既有本地环境继续使用 Mock，并完成三端复测。

**执行行为：**

1. 将 Agent 临时目录改到独立目录后复跑全量测试，确认已有本地配置使自动模式选择尚未实现的 Qwen Provider，导致 96 项测试夹具在启动时出错。
2. 调整自动模式：开发和测试使用 Mock，生产关闭语音。保留显式指定 Provider 的行为，增加本地有无语音服务密钥的覆盖。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/app/config.py`、`project/agent/tests/test_speech_config.py` | 修正自动模式并覆盖本地配置兼容性 | 避免未实现的 Qwen Provider 阻断已有功能 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试-回滚与清理.md`、`docs/功能资料/merge_process.md` | 同步行为说明和阶段结果 | 记录合并发现的问题及环境要求 |

**执行命令与验证：**

- 命令：`python -m pytest -o addopts='' -p no:cacheprovider --basetemp <独立目录> --tb=short -q`。结果：418 项通过、1 项跳过、96 项夹具错误，均定位到自动选择尚未实现的 Qwen Provider。
- 命令：`npm.cmd run build -- --outDir <独立目录> --emptyOutDir`。结果：在允许启动 esbuild 子进程后，类型校验和生产构建通过，Vite 提示大 chunk。

**影响范围：** 自动模式保持已有 Agent 路由可用，开发和测试环境仍可使用源分支的 Mock 语音功能。

**遗留问题：** 复跑 Agent 和核对最终提交范围。

---


### [2026-11-19 14:18:31] 对齐语音 API 测试的共享夹具

**任务目标：** 使新测试使用现有 `client_and_app` 夹具的返回契约。

**执行行为：**

1. Agent 全量复跑确认启动恢复。新语音 API 测试把 `(client, app)` 元组当作 HTTP 客户端，调用接口前即失败。
2. 只调整新语音测试，从共享夹具解包 HTTP 客户端，保留既有夹具和其他调用方。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/tests/test_speech_api.py` | 解包共享夹具返回的 HTTP 客户端 | 让转写、播报和授权断言进入实际 API |
| 修改 | `docs/功能资料/merge_process.md` | 记录复测结果与测试修复 | 保留验证过程 |

**执行命令与验证：**

- 命令：`python -m pytest -o addopts='' -p no:cacheprovider --basetemp <独立目录> --tb=short -q -rs`。结果：505 项通过、1 项跳过、10 项失败，失败均为语音 API 测试对夹具元组调用 `post`。

**影响范围：** 只涉及新语音 API 测试的调用方式。

**遗留问题：** 复跑语音测试并核对最终全量结果。

---


### [2026-11-19 14:19:55] 完成合并前验证并核对提交范围

**任务目标：** 确认合并结果可验证，原工作区改动保持未提交。

**执行行为：**

1. 复跑 Agent 全量测试，语音 API 授权、转写和播报测试通过。独立复核两个重叠文件，去掉合入内容后与合并前本地文件逐字节一致（按 LF 统一换行）。
2. 将暂存路径与源分支差异比对，确认共 56 个路径，仅额外纳入合并过程记录，语音 SQL 使用 V17。清单由暂存区生成。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/merge_process.md` | 记录最终回归结果、提交边界和文件清单 | 保留合并验收证据 |

**执行命令与验证：**

- 命令：`python -m pytest -o addopts='' -p no:cacheprovider --basetemp <独立目录> --tb=short -q -rs`。结果：515 项通过，1 项因仅适用于 SQLite 的内存后端参数跳过。直接在默认沙箱执行语音定向测试时仍有 pytest 临时目录权限错误，全量测试通过授权的独立临时目录完成。
- Java 23 项定向测试通过，前端类型校验和生产构建通过。隔离 MySQL 的 V1→V16、V16→V17 及 Flyway 校验通过。
- 命令：`git ls-files -u`、`git diff --cached --name-only HEAD`、`git merge-file -p`。结果：无未合并条目，路径范围符合预期，两个重叠文件的既有本地改动已核对。


<details>
<summary>暂存文件清单</summary>

- 新增 `.codeartsdoer/specs/voice_interview/design.md`
- 新增 `.codeartsdoer/specs/voice_interview/spec.md`
- 新增 `.codeartsdoer/specs/voice_interview/tasks.md`
- 新增 `docs/功能资料/09-模拟面试/语音模拟面试-回滚与清理.md`
- 新增 `docs/功能资料/09-模拟面试/语音模拟面试_process.md`
- 修改 `docs/功能资料/merge_process.md`
- 修改 `project/agent/app/api.py`
- 修改 `project/agent/app/config.py`
- 新增 `project/agent/app/speech/__init__.py`
- 新增 `project/agent/app/speech/base.py`
- 新增 `project/agent/app/speech/fake.py`
- 新增 `project/agent/app/speech/router.py`
- 新增 `project/agent/app/speech/schemas.py`
- 新增 `project/agent/app/speech/service.py`
- 新增 `project/agent/tests/test_speech_api.py`
- 新增 `project/agent/tests/test_speech_config.py`
- 新增 `project/agent/tests/test_speech_fake.py`
- 新增 `project/agent/tests/test_speech_service.py`
- 修改 `project/backend/src/main/java/com/aijob/backend/common/BusinessException.java`
- 修改 `project/backend/src/main/java/com/aijob/backend/common/GlobalExceptionHandler.java`
- 修改 `project/backend/src/main/java/com/aijob/backend/interview/InterviewMapper.java`
- 修改 `project/backend/src/main/java/com/aijob/backend/interview/InterviewResultHandler.java`
- 修改 `project/backend/src/main/java/com/aijob/backend/interview/InterviewService.java`
- 新增 `project/backend/src/main/java/com/aijob/backend/interview/voice/QuestionSpeechService.java`
- 新增 `project/backend/src/main/java/com/aijob/backend/interview/voice/SpeechAgentClient.java`
- 新增 `project/backend/src/main/java/com/aijob/backend/interview/voice/SpeechAudioCache.java`
- 新增 `project/backend/src/main/java/com/aijob/backend/interview/voice/VoiceCapabilityService.java`
- 新增 `project/backend/src/main/java/com/aijob/backend/interview/voice/VoiceInterviewController.java`
- 新增 `project/backend/src/main/java/com/aijob/backend/interview/voice/VoiceLimits.java`
- 新增 `project/backend/src/main/java/com/aijob/backend/interview/voice/VoiceMapper.java`
- 新增 `project/backend/src/main/java/com/aijob/backend/interview/voice/VoiceRateLimiter.java`
- 新增 `project/backend/src/main/java/com/aijob/backend/interview/voice/VoiceStatus.java`
- 新增 `project/backend/src/main/java/com/aijob/backend/interview/voice/VoiceTranscriptionService.java`
- 新增 `project/backend/src/main/java/com/aijob/backend/interview/voice/VoiceTranscriptionSweeper.java`
- 修改 `project/backend/src/main/java/com/aijob/backend/task/InternalJwtService.java`
- 修改 `project/backend/src/main/resources/application.yml`
- 新增 `project/backend/src/main/resources/db/migration/V17__add_voice_interview.sql`
- 修改 `project/backend/src/test/java/com/aijob/backend/interview/InterviewServiceTest.java`
- 新增 `project/backend/src/test/java/com/aijob/backend/interview/voice/QuestionSpeechServiceTest.java`
- 修改 `project/backend/src/test/java/com/aijob/backend/task/SevenCaseIntegrationTest.java`
- 修改 `project/frontend/src/api/interviews.ts`
- 修改 `project/frontend/src/api/request.ts`
- 修改 `project/frontend/src/api/types.ts`
- 新增 `project/frontend/src/api/voiceInterviews.ts`
- 新增 `project/frontend/src/composables/voice/useQuestionSpeech.ts`
- 新增 `project/frontend/src/composables/voice/useVoiceAnswer.ts`
- 新增 `project/frontend/src/composables/voice/useVoiceRecorder.ts`
- 新增 `project/frontend/src/utils/voice/formats.ts`
- 新增 `project/frontend/src/utils/voice/limits.ts`
- 修改 `project/frontend/src/views/interview/InterviewView.vue`
- 新增 `project/frontend/src/views/interview/components/QuestionAudioPlayer.vue`
- 新增 `project/frontend/src/views/interview/components/RecordingControls.vue`
- 新增 `project/frontend/src/views/interview/components/TranscriptionEditor.vue`
- 新增 `project/frontend/src/views/interview/components/VoiceAnswerPanel.vue`
- 新增 `project/frontend/src/views/interview/components/VoicePrivacyDialog.vue`
- 修改 `requirements.txt`

</details>

**影响范围：** 本次引入语音面试 Mock 功能和上述合入修复，既有项目实战未提交工作仍在工作区。

**遗留问题：** 提交后核对两个父提交。未执行真实语音供应商与真实麦克风端到端验收。发布到共享库前应将本地项目实战 V15、V16 与 V17 一同发布。

---

### [2026-11-19 14:20:27] 确认语音面试本地合并提交

**任务目标：** 核对当前分支已包含目标分支历史。

**执行行为：**

1. 在 `feature/bug` 创建双亲合并提交 `d897a67ee14ae65f3f1d2e803ff4a2c81172538e`。
2. 核对第一父提交为原 `feature/bug` 的 `cc71c08`，第二父提交为语音面试分支的 `6da3514`。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/merge_process.md` | 追加提交后的核对记录 | 保存实际提交与父提交证据 |

**执行命令与验证：**

- 命令：`git diff --cached --check`、`git commit -m 'merge: 合入语音模拟面试'`、`git show -s --format='%H %P %s' HEAD`、`git merge-base --is-ancestor origin/feature/voice-interview HEAD`、`git diff --quiet --cached`。结果：通过。

**影响范围：** 本地合并已完成，原有未提交工作区改动及本条提交后记录保留在工作区。

**遗留问题：** 本次合并尚未推送。

---

### [2026-11-22 17:05:25] 核对录音功能合并范围

**任务目标：** 在 feature/bug 的现有页面和业务逻辑中接入录音回答。

**执行行为：** 核对双方共同祖先 6da3514 和分支差异。创建独立 feature/bug 工作树，源工作树的无语音修复和其他在途改动继续保留。feature/bug 已有 V15–V21 迁移，录音尝试序号迁移将顺延为 V22。

**文件变更：** 修改 docs/功能资料/merge_process.md，追加合并范围记录。

**执行命令与验证：** git log --left-right、git diff、git worktree add。核对目标分支为 07be802，语音源分支为 cb467b3。

**影响范围：** 录音作答、真实转写、失败重试和提交。现有页面框架、文本面试提问规则及其他业务以 feature/bug 为准。

**遗留问题：** 处理重叠改动、应用本地无语音修复并运行回归。

---

### [2026-11-22 17:08:16] 合并录音和转写调用链

**任务目标：** 引入真实转写和录音作答，并保持 feature/bug 的基础布局及已有业务行为。

**执行行为：** 解决配置、Agent 入口和录音校验冲突。保留现有录音容器与 MIME 一致性检查、现有任务状态和主题样式、文本面试题量规则及生产环境默认不开放语音接口的行为。显式启用 Qwen 时接入真实转写，带入无语音响应修复。新增转写重试序号防止迟到结果覆盖，按 V22 新增迁移。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | .env.example、project/agent/app/api.py、config.py、speech/{fake,router,service}.py | 合并语音设置、生命周期和严格音频校验 | 接入 Qwen 并保留既有模式 |
| 新增 | project/agent/app/speech/qwen.py、project/agent/app/speech_config.py | Qwen 转写、配置来源校验和无语音分类 | 提供真实识别 |
| 新增或修改 | project/agent/tests/conftest.py、project/agent/tests/test_speech_*.py | 覆盖配置、格式、错误分类和接口 | 验证合入语音行为 |
| 新增或修改 | project/backend/src/main/java/com/aijob/backend/common/BusinessException.java、interview/InterviewService.java、interview/voice/{SpeechAgentClient,VoiceMapper,VoiceReasons,VoiceTranscriptionService}.java | 校验录音，约束转写状态和答案提交 | 安全支持重试和并发会话 |
| 新增或修改 | project/backend/src/main/resources/application.yml、db/migration/V22__add_voice_transcription_attempt.sql、project/backend/src/test/java/com/aijob/backend/interview/voice/*Test.java | 默认关闭模拟题目播报入口，添加转写序号和测试 | 区分真实识别与模拟音频，沿用目标分支迁移顺序 |
| 修改 | project/frontend/src/api/types.ts、composables/voice/useVoiceAnswer.ts、views/interview/InterviewView.vue、views/interview/components/VoiceAnswerPanel.vue | 保留录音并允许重试，提交时携带转写来源 | 将作答能力接入原页面 |
| 修改 | docs/功能资料/merge_process.md | 追加本阶段记录 | 保存合并过程 |

**执行命令与验证：** git merge --no-commit --no-ff、git restore、git mv 已执行。首次前端检查因隔离目录无法创建 node_modules 联接而未能运行，待离线安装或复用依赖后重试。

**影响范围：** 原工作树、正在运行的服务和在用数据库尚未进行切换。

**遗留问题：** 完成前后端构建、数据库集成和录音交互验收。

---

### [2026-11-22 17:12:40] 验证迁移与并发转写，并完善页面重试

**任务目标：** 验证新增迁移、重试保护和提交行为，检查语音交互在原面试页面正常工作。

**执行行为：** 原样验证现有 V1–V21 迁移，在隔离测试库追加 V22。保留可回放的录音，并使页面在同题已有新识别时等待现有请求，只有成功转写才能按语音提交。第一轮 Edge 自动化页面标题断言早于路由完成，将断言移至面试内容加载后。

**文件变更：** 修改 project/frontend/src/composables/voice/useVoiceAnswer.ts、project/frontend/src/views/interview/components/VoiceAnswerPanel.vue 和本记录。独立测试库及浏览器测试脚本均未加入版本控制。

**执行命令与验证：** Maven 启用 integration-tests 后，VoiceAttemptMigrationIntegrationTest 与 VoiceLateArrivalIntegrationTest 共 20 项通过，Flyway 从空库成功迁移到 V22。首次 npm 类型检查、工作台模型检查和 Vite 生产构建通过，构建提示现有入口包超过 500 KB。最新页面修正仍将重新检查。

**影响范围：** 测试使用独立数据库和端口，原工作树及在用业务数据无运行层变更。

**遗留问题：** 完成浏览器复验、Agent 语音回归和最终提交。

---

### [2026-11-22 17:17:06] 完成合并验收

**任务目标：** 审核最终改动和验收结果，准备在本地 feature/bug 创建双亲合并提交。

**执行行为：** 更正合并冲突脚本截断配置、应用返回和服务校验代码的问题，核对目标分支的项目推荐环境变量和生产关闭语音行为已保留。补齐项目已固定的 Playwright 依赖到独立验证目录，未修改运行时环境。关闭独立 Vite，整理生成文件并保留原工作树。

**文件变更：** 修改 project/agent/app/api.py、project/agent/app/config.py、project/agent/app/speech/service.py、project/agent/tests/test_speech_config.py、.env.example 和本记录。测试脚本、截图、临时依赖、构建产物均在提交范围外。

**执行命令与验证：**

- Python pytest 全量回归最终退出码为 0。首次回归中两条岗位页面渲染测试因缺少 Playwright 失败，补齐隔离依赖后通过。
- Maven 定向单元与独立 MySQL 集成合计 92 项通过，1 项跳过。V22 迁移、并发识别、过期、提交和重试均有测试覆盖。
- npm run type-check、npm run verify:models 与 Vite 生产构建通过。Edge 合成麦克风和模拟业务响应验证录音、实际回放、无语音提示、原录音重试、并发识别轮询、编辑提交及文字回退。
- 合并后 Agent 实际调用 Qwen，合成 WebM 返回非空文本，静音 WAV 返回 empty=true，接口均为 200。未使用用户录音。
- 对照第一父提交核对布局、主题、简历、训练、项目页面和 Agent 文本业务处理文件。

**影响范围：** 变更集中在模拟面试录音和转写。新迁移需在使用 feature/bug 迁移历史的数据库上部署。

**遗留问题：** 未验收实体麦克风或切换本机正在运行的服务。合并将在本地提交。

---


### [2026-11-22 19:58:42] 准备将 feature/bug 合入主分支

**任务目标：** 将已验证的 feature/bug 功能集纳入 main。

**执行行为：** 获取远端 main，确认本地与远端均为 f7df42b，feature/bug 为 13be7e1。待合入的历史含疑似凭据、本地上传和 SQLite 文件，因此在独立工作树做压缩合并，排除凭据、运行数据、生成产物及含账户信息风险的原始验收附件。

**文件变更：** 启用 feature/bug 的前后端、Agent、测试和迁移变更。保留主分支运行目录原有状态。修改 .gitignore，忽略本地上传、SQLite 和此类凭据导出。追加本记录。

**执行命令与验证：** git fetch --no-tags origin main、git switch main、git merge --squash feature/bug、git restore --source=HEAD --staged --worktree。核对排除路径在暂存区无变化。文本检测仅报告疑似凭据的文件名与命中数量。

**影响范围：** 独立工作树的本地 main，正在运行的服务工作树继续使用 feature/bug。

**遗留问题：** 完成暂存内容核对、构建和测试后提交。

---

### [2026-11-22 20:03:08] 验证并提交主分支合并

**任务目标：** 完成独立工作树的 main 合并，并核对功能与安全范围。

**执行行为：** 核对暂存区前端、Java、Agent 和迁移文件与已验证的 feature/bug 一致。首次 Java 全量测试因隔离工作树缺少数据库凭据而无法连接，随后使用独立 MySQL 测试库和 Redis 数据库 5 运行，不接触在用业务库。测试及构建产物保留在本地。

**文件变更：** 追加 docs/功能资料/merge_process.md。合并提交包含功能源码、测试、迁移、配置示例及项目文档，排除原始验收附件、凭据和运行目录变更。

**执行命令与验证：** 前端类型检查、工作台校验和 Vite 生产构建通过。Agent pytest 574 项通过、3 项跳过。Maven 启用 integration-tests 后 263 项通过、9 项跳过。暂存区文本凭据检查通过，排除路径与旧 main 一致。

**影响范围：** 本地 main。保留 feature/bug 工作区及其运行服务。

**遗留问题：** 未执行远端推送或服务部署。

---


### [2026-11-22 22:31:25] 合入语音面试出题与轮次提速

**任务目标：** 将 feature/bug 的已提交业务改动安全合入 main。

**执行行为：** 核对来源 b4b41d2、目标 92de952 与远端 main。目标已通过此前的压缩合并纳入录音作答。本次在独立工作树应用两分支间的业务差异，提交的父节点仅为现有 main。保留主分支历史、忽略规则和现有运行数据。保留原功能分支及其历史。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增、修改 | `project/agent/app/`、`project/agent/tests/`、`project/backend/src/`、`project/frontend/src/` | 合入真实题目播报、录音互斥、语音模式、回归测试与轮次优化 | 纳入已验证的业务功能 |
| 新增、修改 | `.env.example`、`.codeartsdoer/specs/voice_interview/spec.md`、`docs/功能资料/09-模拟面试/`、`docs/功能资料/服务启动_process.md` | 合入配置示例、规格、设计及相关过程记录 | 记录功能与配置 |
| 修改 | `docs/功能资料/merge_process.md` | 追加合入记录 | 留存合并依据 |

**执行命令与验证：** `git apply --index --check` 与 `git apply --index` 完成，核对业务范围差异。按原主分支的排除口径保留凭据导出、上传文件、SQLite、前端构建产物和原始验收附件在本地范围。

**影响范围：** 主分支语音面试和文字面试共用轮次逻辑。

**遗留问题：** 推送与工作区切换的结果在操作完成后追加。

---

### [2026-11-22 22:34:36] 验证合入内容

**任务目标：** 在提交前验证语音面试改动与暂存范围。

**执行行为：** 核对暂存区路径，未发现密钥模式匹配。对服务启动记录新增段落统一换行。执行前端类型检查、Agent 定向回归和 Java 定向回归。

**文件变更：** 规范服务启动记录新增段落换行，追加本合并记录。

**执行命令与验证：** npm run type-check 通过，Agent pytest 104 项通过，Java Maven 定向测试 20 项通过。

**影响范围：** 待合入的语音面试功能。

**遗留问题：** 等待提交、推送及工作区切换。

---

### [2026-11-22 22:36:08] 推送合并并切换主工作区到 main

**任务目标：** 推送已验证的业务合并并将 D:/py/AI-JOB 切换到 main。

**执行行为：** 将 689ebc0 正常推送至 origin/main，并通过 ls-remote 核对。切换前对 29 个本机上传、凭据导出和 SQLite 文件做字节校验与备份。释放旧工作树的 main，快进本地分支，切换当前工作区并恢复目标树中不存在的本地忽略文件。

**文件变更：** 修改本记录，追加推送和切换结果。Git 按合并提交检出项目文件。

**执行命令与验证：** git push、git ls-remote、git switch --detach、git update-ref、git switch --no-overwrite-ignore、git status 均成功。切换后当前分支为 main，工作区干净，.env 哈希与切换前相同。

**影响范围：** 远端主分支与当前本地工作区。保留 feature/bug 提交及本地数据备份。

**遗留问题：** 本次操作范围为 Git 合并和本地切换，未执行服务部署。

---
