# 账号管理功能开发过程记录

### [2026-11-23] 生成账号管理需求规格文档

**任务目标：**
为项目用户模块新增"账号管理"功能编写需求规格说明，覆盖昵称（用户名）、密码、手机号绑定、头像上传，并明确新功能与既有 UI 的兼容约束。

**执行行为：**

1. 只读梳理现状：后端 `com.aijob.backend.user`（AppUserController、AppUser、AppUserMapper、AppUserServiceImpl）、`auth`（AuthController、AuthServiceImpl、VerificationCodeService、UserVO、UserPrincipal）、数据库迁移 `V1__init_business_schema.sql`（app_user 表仅有 email/password_hash/nickname/role/status/token_version，无手机号与头像字段）、`FileStorageService`；前端 `layouts/components/Header.vue`、`Sidebar.vue`、`MainLayout.vue`、`router/routes.ts`、`config/menu.ts`、`stores/user.ts`、`api/auth.ts`、`api/http.ts`、`views/login/RegisterView.vue`、`api/types.ts`。
2. 确认既有约束：登录标识为邮箱（只读，本期不开放修改）；昵称长度 1-30；密码长度 10-72；验证码既有参数为 6 位/10 分钟/60 秒冷却/最多 5 次；顶栏 64px、左侧栏 220px（窄屏 64px）、顶栏昵称 120px 截断；验证码通道现仅邮件（MailService），无短信通道实现。
3. 按 `spec_template.md` 结构生成 `.codeartsdoer/specs/account_management/spec.md`，含 6 章：组件定位、领域术语、角色与边界、DFX 约束、核心能力（7 个模块）、数据约束；共 7 个 PlantUML 图（1 个上下文图 + 6 个时序图）。
4. 将"新功能不与原 UI 不兼容"落为可验收条款：入口插入位置（顶栏下拉"求职画像"与"退出登录"之间）、账号页不进入左侧菜单、尺寸不变量、深色模式对比度 4.5:1、响应式断点 1050/700、样式变量禁止全局改动、既有页面流程不变。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `.codeartsdoer/specs/account_management/spec.md` | 账号管理需求规格说明，365 行 | 用户要求为用户模块新增账号管理功能并保证 UI 兼容 |
| 新增 | `.codeartsdoer/specs/account_management/`（目录） | 特性规格目录 | 存放该特性的 SDD 文档 |
| 新增 | `docs/功能资料/账号管理_process.md` | 本记录文件 | 遵循项目开发过程记录规范 |

**执行命令与验证：**

- 命令：`New-Item -ItemType Directory -Force -Path ".codeartsdoer\specs\account_management"`
- 结果：通过
- 说明：`.codeartsdoer/specs/` 下不存在同名目录，无命名冲突，无需追加数字后缀。

- 命令：`Get-Item ".codeartsdoer\specs\account_management\spec.md"` 与统计行数
- 结果：通过
- 说明：文件生成成功，大小 43532 字节，共 365 行。

- 命令：正则检索章节标题 `^#{1,3}`
- 结果：通过
- 说明：1~6 章及 5.1~5.7 各模块的三级标题齐全，结构符合模板。

**影响范围：**
仅新增需求文档，未修改任何代码、配置、数据库脚本与前端资源，运行时行为无变化。

**遗留问题：**

1. 手机号绑定依赖短信通道，项目当前仅有邮件通道（MailService），规格中已定义为"通道不可用则入口置灰并提示暂未开通"，后续需在设计阶段确认短信服务接入方案。
2. 头像展示涉及顶栏与左侧栏两处既有组件的头像替换，具体实现由设计阶段决定。
3. 登录邮箱变更、账号注销、第三方账号绑定已明确排除在本期范围外。
4. 本文档为需求规格，尚未进入设计（design.md）与任务分解（tasks.md）阶段，未执行构建与测试。

---

### [2026-11-23] 生成账号管理技术设计文档 design.md

**任务目标：**
基于已确认的 `spec.md`，为账号管理功能产出技术设计（数据模型与迁移、API 契约、业务规则落地、前端页面与路由、与原 UI 的兼容约束落地方式、测试策略与验证命令），不执行编码。

**执行行为：**

1. 只读复核存量实现：后端 `AppUserController`/`AppUserServiceImpl`/`AppUserMapper`/`AppUserAuth`/`UserVO`/`AuthServiceImpl`/`VerificationCodeService`/`SessionService`/`JwtAuthenticationFilter`/`CurrentUserResolver`/`FileStorageService`/`SecurityConfig`/`GlobalExceptionHandler`/`ApiErrorCode`/`AppProperties`/`TraceIdFilter`/`RequestLoggingFilter`/`OutboxService`；迁移 `V1__init_business_schema.sql`（`app_user`、`auth_session`、`audit_log`）与 `application.yml`；前端 `Header.vue`/`Sidebar.vue`/`MainLayout.vue`/`routes.ts`/`menu.ts`/`stores/user.ts`/`api/request.ts`/`api/http.ts`/`api/auth.ts`/`api/auth-legacy.ts`/`api/types.ts`/`LoginView.vue`/`RegisterView.vue`/`ProfileView.vue`/`LoadingSkeleton.vue`/`ThemeToggle.vue`/`styles/variables.scss`/`styles/theme.scss`；构建与测试配置 `pom.xml`、`package.json`、`vite.config.ts`、`project/backend/README.md`。
2. 识别关键设计约束：`app_user` 无手机号/头像/资料保存时点列；`/api/v1/users/**` 对普通用户为 ADMIN 规则，新接口需独立前缀；refresh cookie 的 path 仅为 `/api/v1/auth`，账号写接口无法复用既有 CSRF 校验；全局 multipart 上限 10MB，头像 2MB 需业务层校验；前端 `<img>` 无法携带 Authorization，头像需 blob 方案；前端存在 axios 与 fetch 两套请求层。
3. 按 `design_template.md` 分块写入 `.codeartsdoer/specs/account_management/design.md`：第一部分"需求与存量功能关系分析"（已实现/需扩展/需新增对比表 + 8 项存量功能详细分析）；第二部分"增量设计方案"（上下文视图、组件架构、状态机与 4 个流程图、事务/并发/扩展点、12 个接口契约与错误码、V24 迁移与领域类图、前端页面与路由、兼容约束落地表、测试策略与验证命令、实施顺序）。
4. 兼容性落地方案：顶栏下拉插入"账号设置"但不改尺寸与既有两项；左侧菜单零改动；四张既有页面（登录/注册/找回密码/画像）零改动；密码修改成功提示走全局消息，不改登录页；`UserVO` 仅追加可选字段；`request.ts` 仅调整为"不覆盖显式 CSRF 头"。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `.codeartsdoer/specs/account_management/design.md` | 账号管理技术设计说明，约 1370 行、81 KB | 将需求规格转为可落地的技术方案 |
| 修改 | `docs/功能资料/账号管理_process.md` | 追加本次设计阶段记录 | 遵循项目开发过程记录规范 |

**执行命令与验证：**

- 命令：`Get-Item ".codeartsdoer\specs\account_management\design.md"` 与行数统计
- 结果：通过
- 说明：文件存在，大小 81099 字节，内容完整（含 2.7 实施顺序与文档结尾）。

- 命令：正则检索文档章节标题 `^#{1,3}`
- 结果：通过
- 说明：一级标题 2 个（需求与存量功能关系分析、增量设计方案），二级标题 7 个（1.1/1.2/2.1–2.7），三级与四级标题覆盖迁移、接口、类图、前端、兼容、测试等全部要求项。

- 命令：未执行构建与测试（本阶段不编码）
- 结果：未执行
- 说明：设计文档不产生运行时代码，`mvn test`、`npm run type-check`、`npm run build` 留待实现阶段执行。

**影响范围：**
仅新增设计文档与过程记录，未修改任何 Java、Vue、SQL、配置文件，运行时行为无变化。

**遗留问题：**

1. 短信通道当前未接入（项目仅有 `MailService`），设计中以 `SmsSender` 抽象 + 能力探测 + 入口置灰落地，真实通道需后续确认供应商。
2. 头像 WebP 仅做容器结构校验（不引入解码库），像素级校验与 1:1 建议提示在前端完成，后续可按需增强。
3. 前端未引入组件测试框架，交互行为采用纯函数单测 + 手工验收清单覆盖。
4. 设计文档尚未进入任务分解（tasks.md）阶段，未执行编码。

---

### [2026-11-23] 生成账号管理编码任务清单 tasks.md

**任务目标：**
基于已确认的 `spec.md` 与 `design.md`，将技术设计拆解为按依赖顺序可执行的编码任务清单，覆盖数据库迁移 V24、后端实体/仓储/服务/控制器/安全与 CSRF、前端 API/路由/页面/组件、测试与 UI 兼容验收，本阶段不执行编码。

**执行行为：**

1. 只读复核前置文档：`.codeartsdoer/specs/account_management/spec.md`（721 行，7 个能力模块与 4.x DFX 约束）、`design.md`（1371 行，含 12 个接口契约、14 个新增错误码、V24 迁移 9 列 1 索引、前端文件清单、测试策略与实施顺序）。
2. 只读核实项目实际路径与约束，确保任务中的文件路径与命令可直接执行：迁移目录实为 `project/backend/src/main/resources/db/migration/`（最大版本 `V23`）；`AppUserMapper` 现有 `selectById/selectAuthById/updateNickname/updatePasswordAndBumpVersion`；`AppUser` 未含敏感字段、`AppUserAuth` 含 `password_hash`；`SecurityConfig` 中 `/api/v1/users/me` authenticated、`/api/v1/users/**` ADMIN；`ApiErrorCode` 已占用 40001–40003、40301、40901–40907、41301、41501、42901–42902、50301，新增编号无冲突；`request.ts` 第 130–132 行确实无条件注入全局 `X-CSRF-Token`；`Header.vue` 头像为 38px、侧栏 42px、`menu.ts` 无账号项；`mvn-run.bat` 与 `docker-compose.yml` 存在；前端测试为 `node --experimental-strip-types --test`，`package.json` 无 test 脚本。
3. 生成 `.codeartsdoer/specs/account_management/tasks.md`：26 个执行主任务、76 个子任务（另有第 0 章 4 个总览小节），按 A 数据层 → B 后端基础设施 → C 领域服务 → D 接口与安全 → E 前端 → F 测试 → G 兼容与交付 七阶段排列；每个子任务均标注涉及文件、验收标准、验证命令与前置依赖，并附需求覆盖矩阵、明确不做范围与风险表。
4. 依赖关键路径：`V24` → 实体/Mapper → 审计/脱敏、CSRF、密码限流、短信验证码 → 资料/密码/手机号/头像服务 → `AccountController` + `SecurityConfig` + `application.yml` → 前端 API/状态/头像组件/路由/账号页 → 测试 → UI 兼容验收。
5. 与 design 的关键一致性点已在任务中写实：12 个接口路径与方法、14 个新增错误码编号、9 个新增列与 `uk_app_user_phone`、Redis 键前缀 `career:account:`、7 类审计动作、前端 6 个组件文件、12 项手工验收清单、`request.ts` 仅调整为"不覆盖显式 CSRF 头"、四个既有页面与 `menu.ts`/`MainLayout.vue`/`styles/*` 零改动。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `.codeartsdoer/specs/account_management/tasks.md` | 账号管理编码任务清单，26 个执行主任务、76 个子任务 | 将技术设计转为可执行、可验收的任务 |
| 修改 | `docs/功能资料/账号管理_process.md` | 追加本阶段任务规划记录 | 遵循项目开发过程记录规范 |

**执行命令与验证：**

- 命令：`Get-Item ".codeartsdoer\specs\account_management\tasks.md"` 与 .NET `ReadAllLines` 行数统计
- 结果：通过
- 说明：文件已生成，976 行、63631 字节；内容包含第 0 章 0.1–0.4 总览、1–26 章任务与附录 A/B/C。

- 命令：正则检索子任务复选框 `- [ ]` 与二级标题 `^## `
- 结果：通过
- 说明：执行主任务 26 个（编号 1–26，另有第 0 章总览），子任务 76 个（另有 4 个总览小节），二级标题总数 30（含 3 个附录标题）；每个子任务首行均为复选框，层级不超过二级。

- 命令：核对任务中引用的文件路径是否存在（Glob 检索迁移目录、后端 Java 包、前端 ts/vue、测试目录、`mvn-run.bat`、`docker-compose.yml`）
- 结果：通过
- 说明：迁移目录最大版本为 `V23`，`V24` 为可用新版本号；`AppUserMapper`、`SecurityConfig`、`ApiErrorCode`、`request.ts`、`Header.vue`、`Sidebar.vue`、`menu.ts`、`stores/user.ts`、`routes.ts` 均存在且与设计描述一致。

- 命令：未执行 `mvn` 与 `npm` 构建/测试
- 结果：未执行
- 说明：本阶段只产出任务文档，不执行编码；所有验证命令留待编码阶段逐项执行。

**影响范围：**
仅新增任务清单文档与过程记录，未修改任何 Java、Vue、SQL、配置或依赖，运行时行为无变化。

**遗留问题：**

1. 任务清单尚未进入编码阶段，未产生任何代码改动，未执行构建与测试。
2. 真实短信通道仍未接入，任务中按"抽象接口 + 能力探测 + 入口置灰"落地，供应商选型需在编码前确认。
3. 前端无组件测试框架，任务 22 仅覆盖纯函数单测与手工验收，交互缺陷依赖 23 章验收清单发现。
4. 需在编码阶段确认 `GlobalExceptionHandler` 是否已把 `retryAfterSeconds` 写入 `Retry-After` 头（任务 11.3 已列为核对项）。

---

### [2026-11-23] 账号管理编码实施与验证

**任务目标：**
按 `.codeartsdoer/specs/account_management/tasks.md` 落地账号管理：V24 迁移、后端基础设施与领域服务、12 个接口、前端账号页与全局头像接入，并完成单测、构建与迁移验证，保证既有 UI 与流程不变。

**执行行为：**

1. 数据层：新增 `V24__add_account_management.sql`（9 列 + `uk_app_user_phone` + 头像 MIME/大小 CHECK；手机号 REGEXP CHECK 因 MySQL 版本兼容性保留为注释），扩展 `AppUser`、`AppUserAuth` 与 `AppUserMapper`（新增别名更新、绑定/换绑/解绑条件更新、头像版本递增、密码失败计数与条件改密语句）。
2. 基础设施：`ApiErrorCode` 追加 14 个错误码；新增 `PhoneMask`、`SourceDigest`、`AccountAction`、`AuditLogMapper`、`AccountAuditService`（`REQUIRES_NEW` + 失败降级）、`AccountExceptionFactory`、`AccountCsrfService`、`PasswordAttemptGuard`、`SmsSender`/`ConfiguredSmsSender`、`PhoneVerificationCodeService`、`NicknameRules`、`PhonePurpose`。
3. 领域服务：新增 `AccountProfileService`、`AccountCredentialService`、`PhoneBindingService`、`AvatarTypeDetector`、`AvatarStorageService`、`AvatarService`；`AccountController` 提供 12 个接口，写接口统一校验账号 CSRF；`SecurityConfig` 增加 `/api/v1/account/**` authenticated；`application.yml` 增加 `app.account.*` 与 `app.sms.*`。
4. 既有入口对齐：`AppUserServiceImpl.updateNickname` 改用 `NicknameRules` 与 `updateNicknameAndTouch`；`UserVO` 末尾追加 4 个可选字段并保留 5 参构造器（`@JsonInclude(NON_NULL)`）。
5. 前端：`api/types.ts` 追加账号类型与 `UserVO` 可选字段；新增 `api/account.ts`（含 CSRF 失效自动重试）、`components/UserAvatar.vue`、`views/account/account-rules.ts`、`AccountView.vue` 与 5 个子组件；`request.ts` 改为不覆盖显式 `X-CSRF-Token`，并在 `ApiError` 暴露 `Retry-After`；`stores/user.ts` 增加资料、头像 blob、账号 CSRF 与登出清理；`Header.vue` 插入"账号设置"并使用 `UserAvatar`；`Sidebar.vue` 使用 `UserAvatar`；`routes.ts` 新增 `/account`。
6. 测试：新增 11 个纯单测类（49 项）、5 个账号集成测试类与 `AccountIntegrationTestSupport`/`TestSmsSender`；新增前端 `tests/account-rules.test.ts`（11 项）。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `project/backend/src/main/resources/db/migration/V24__add_account_management.sql` | 9 列、唯一索引、2 个 CHECK | 手机号/头像/资料时点/密码限流的持久化 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/user/entity/AppUser.java` | 新增 phone、头像元数据、profileUpdatedAt | 资料读取与头像版本 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/user/entity/AppUserAuth.java` | 新增密码失败计数与锁定时间 | 修改密码限流 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/user/mapper/AppUserMapper.java` | 新列查询与 10 余条新增语句 | 账号写操作与乐观并发 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/common/ApiErrorCode.java` | 追加 14 个错误码 | 契约与 HTTP 语义 |
| 新增 | `project/backend/src/main/java/com/aijob/backend/common/AuditLogMapper.java` | 审计写入 Mapper | 复用既有 audit_log |
| 新增 | `project/backend/src/main/java/com/aijob/backend/account/**` | 29 个类（21 个服务/工具/VO + 8 个 DTO） | 账号管理领域实现 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/auth/vo/UserVO.java` | 追加可选字段与兼容构造器 | 既有响应只增不减 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/user/service/impl/AppUserServiceImpl.java` | 昵称规则与资料时点对齐 | 两个昵称入口行为一致 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/config/SecurityConfig.java` | 新增 `/api/v1/account/**` authenticated | 新接口鉴权 |
| 修改 | `project/backend/src/main/resources/application.yml` | 新增账号与短信配置 | 可配置限流/验证码/头像上限 |
| 新增 | `project/backend/src/test/java/com/aijob/backend/account/**` | 11 个单测 + 5 个集成测试 + 支持类 | 规则、服务与接口验证 |
| 新增 | `project/frontend/src/api/account.ts` | 11 个接口 + 头像 blob + CSRF 重试 | 前端调用封装 |
| 修改 | `project/frontend/src/api/types.ts` | 账号类型与 UserVO 可选字段 | 类型对齐 |
| 修改 | `project/frontend/src/api/request.ts` | 不覆盖显式 CSRF；ApiError 暴露 Retry-After | 账号令牌与限流提示 |
| 修改 | `project/frontend/src/stores/user.ts` | 资料、头像 blob、账号 CSRF、登出清理 | 全局同步与内存释放 |
| 新增 | `project/frontend/src/components/UserAvatar.vue` | 三处统一头像与失败回退 | 顶栏/侧栏/账号页一致 |
| 修改 | `project/frontend/src/layouts/components/Header.vue` | 插入"账号设置"、替换头像 | 入口与展示 |
| 修改 | `project/frontend/src/layouts/components/Sidebar.vue` | 替换头像 | 展示 |
| 修改 | `project/frontend/src/router/routes.ts` | 新增 `/account` 懒加载路由 | 主框架内页面 |
| 新增 | `project/frontend/src/views/account/**` | 页面容器、规则纯函数、5 个子组件 | 账号页 |
| 新增 | `project/frontend/tests/account-rules.test.ts` | 11 项纯函数测试 | 前端规则验证 |
| 修改 | `project/backend/README.md` | 账号接口、错误码、配置与测试命令 | 文档交付 |

**执行命令与验证：**

- 命令：`mvn -q -DskipTests compile`（经 `mvn-run.bat`）
- 结果：通过
- 说明：主代码编译成功，`target/classes/com/aijob/backend/account` 下生成全部新类。

- 命令：`mvn test "-Dtest=PhoneMaskTest,SourceDigestTest,AccountNicknameRulesTest,AccountPasswordRulesTest,AccountErrorCodeTest,AvatarTypeDetectorTest,AvatarStorageServiceTest,PasswordAttemptGuardTest,AccountCsrfServiceTest,PhoneVerificationCodeServiceTest,AccountProfileServiceTest" "-Djacoco.skip=true"`
- 结果：通过（修复 2 项断言后）
- 说明：共 49 项，0 失败 0 错误；首轮 `AccountNicknameRulesTest` 2 项失败，根因是测试断言检查了异常 message，而字段级提示在 `details.fields` 中，改为断言 `details` 后通过。

- 命令：`mvn test-compile "-Djacoco.skip=true" "-DskipTests"`
- 结果：通过
- 说明：81 个测试源文件编译成功，包含 5 个集成测试类与支持类。

- 命令：`mvn test "-Dtest=AccountCsrfIntegrationTest" "-Djacoco.skip=true"`
- 结果：失败（环境阻塞）
- 说明：4 项均因 `Failed to load ApplicationContext` 报错，根因链为 `FlywaySqlUnableToConnectToDbException` → `CommunicationsException` → `java.net.ConnectException: Connection refused`（测试库 127.0.0.1:3307 不可达），非业务代码缺陷；本机 Redis 6379 同时不可达，账号 CSRF 依赖 Redis，集成链路当前无法闭环。

- 命令：在 3306 创建临时库 `aijob_v24_check2_20261123`，临时 JUnit 类（`webEnvironment=NONE`）驱动 Flyway 全量迁移，断言最新版本为 24、`app_user` 含 9 个新列且 `uk_app_user_phone` 唯一
- 结果：通过
- 说明：1 项测试通过，迁移脚本在干净库一次执行成功；验证后已删除临时测试源文件，临时库按安全策略无法 `DROP DATABASE`，库名保留为 `aijob_v24_check2_20261123`。另有一次尝试用 `mysql -e "source ..."` 手工执行迁移，在 `V13` 因缺少 `flyway_schema_history`（Flyway 管理表）失败，已改用 Flyway 驱动方式重验。

- 命令：`npm run type-check`
- 结果：通过
- 说明：`vue-tsc --noEmit` 无类型错误。

- 命令：`npm run build`
- 结果：通过
- 说明：生产构建成功，生成 `AccountView` 独立 chunk；仅有既有的 chunk 体积提示。构建后回退了被跟踪的 `project/frontend/dist/index.html` 引用变更，避免提交与未跟踪 assets 不一致的产物。

- 命令：`node --experimental-strip-types --test tests/account-rules.test.ts`
- 结果：通过
- 说明：11 项全部通过（控制台中文显示为乱码，属终端编码问题，文件内容正常）。

- 命令：`git diff --stat -- project/frontend/src/views/login project/frontend/src/views/profile project/frontend/src/config/menu.ts project/frontend/src/layouts/MainLayout.vue project/frontend/src/styles`
- 结果：通过（输出为空）
- 说明：登录/注册/找回密码/重置密码/求职画像页面、`menu.ts`、`MainLayout.vue`、全局样式零改动。

- 事故与修复：曾用 PowerShell `Set-Content` 批量替换 `PhoneBindingSection.vue` 中的响应式属性，导致该文件中文被写坏；当场用写文件工具整文件重写恢复，并用 `Select-String` 确认"暂未开通手机号绑定""验证码已发送""解绑手机号"等中文完整。后续所有含中文文件不再用 shell 回写。

**影响范围：**
新增账号管理接口与页面，扩展 `app_user` 表（全部可空/带默认值，存量无需回填），`UserVO` 仅追加可选字段，`/api/v1/users/**` 鉴权规则不变；前端登录、注册、找回密码、求职画像页面与左侧菜单未改动。头像存放于 `app.files.storage-dir/{userId}/avatar/`，需登录态受控读取。

**遗留问题：**

1. 集成测试未在真实环境跑通：测试库 3307 与 Redis 6379 均不可达，5 个集成测试类只完成编译，未验证真实 HTTP 闭环。
2. 真实短信通道未接入：`ConfiguredSmsSender.send` 始终返回 503，`app.sms.enabled=true` 也不会假成功；上线前需新增真实实现并配置供应商。
3. 前端无组件测试框架，账号页交互（倒计时、预览、深色/多宽度）仅完成静态实现，未做浏览器手工验收。
4. `V24` 已在临时库验证，但尚未应用到业务库；启动常驻后端时会自动迁移，需在部署时确认。
5. 头像 WebP 仅做容器校验、未做像素级解码；手机号唯一索引不区分软删除，本期无注销入口。
### [2026-11-23 21:03:06] 修复修改密码事务锁等待

**任务目标：** 解决账号设置提交修改密码返回 HTTP 500。

**执行行为：** 根据运行日志确认清除密码失败计数时 MySQL 锁等待超时。将计数清除加入业务事务，失败计数继续独立提交。审计表外键也会访问账号行，将审计独立写入延后到业务事务结束，失败记录在回滚后仍写入。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/backend/src/main/java/com/aijob/backend/account/PasswordAttemptGuard.java | 计数重置加入业务事务 | 避免独立事务等待已锁住的账号行 |
| 修改 | project/backend/src/main/java/com/aijob/backend/account/AccountAuditService.java | 业务事务结束后写入审计 | 避免审计外键与业务更新争用同一行 |
| 修改 | docs/功能资料/账号管理_process.md | 追加排查和修复过程 | 保留记录 |

**执行命令与验证：** Select-String 核对历史 HTTP 500 及 clearPasswordFailure 锁等待超时。修复待编译和真实接口验证。

**影响范围：** 账号密码与手机号操作的失败计数重置，以及账号模块审计写入时点。

**遗留问题：** 待重启 Java 后端，用独立测试账号验证。

---

### [2026-11-23 21:05:16] 重启本地后端并验证改密

**任务目标：** 让事务修复生效并验证改密业务闭环。

**执行行为：** 核对并重启 8080 Java 后端。通过 5173 代理用随机测试账号验证错误密码、成功改密、会话失效以及新旧密码登录，并只读查询测试账号的审计和失败计数。

**文件变更：** 新增 project/backend/target/local-restarts/start-20261123/backend-password-fix.out.log 和 backend-password-fix.err.log。追加 docs/功能资料/账号管理_process.md。Vite 在接口验证期间更新了生成的类型声明，待核对。

**执行命令与验证：** Maven spring-boot:run、Invoke-WebRequest 和 MySQL SELECT。改密错误密码返回 400，正确密码返回 200，后端记录耗时 194 ms。旧会话和旧密码均返回 401，新密码登录返回 200。数据库记录失败与成功两条审计，失败计数为零且未锁定。首轮 Maven 定向单测中密码规则 5 项通过，Mockito 测试未结束，已终止该轮。git diff --check 提示此前服务启动日志的行尾空白。

**影响范围：** 当前 8080 后端已加载修复，验证数据为独立生成的测试账号。

**遗留问题：** 待补齐定向单测，手机号和浏览器交互未验证。

---

### [2026-11-23 21:05:38] 完成改密相关单测

**任务目标：** 补齐密码规则和失败计数单测。

**执行行为：** 在可运行 Mockito 的环境中重跑定向测试。核对生成声明的 Git 差异，auto-imports.d.ts 只有行尾提示，components.d.ts 保留任务开始前已经存在的变更。

**文件变更：** 追加 docs/功能资料/账号管理_process.md，Maven 更新 target/surefire-reports 中对应测试报告。

**执行命令与验证：** mvn -o -B -Dtest=PasswordAttemptGuardTest,AccountPasswordRulesTest -Djacoco.skip=true test。10 项通过，无失败或错误。

**影响范围：** 单测仅验证后端密码规则和失败计数，与前一条真实接口验证互补。

**遗留问题：** 未通过浏览器点按表单，未验证手机号操作。

---

### [2026-11-23 21:12:18] 修复退出登录的会话校验错误

**任务目标：** 修复退出时收到 401 并停留在账号设置的问题。

**执行行为：** 从后端日志定位会话 CSRF 校验失败。在退出请求前获取当前刷新会话的令牌，退出请求不触发自动续签。无有效刷新会话时直接清理本地状态；其余异常保留登录态便于重试。改密成功后直接清理已撤销的本地会话。

**文件变更：** 修改 project/frontend/src/api/auth.ts、stores/user.ts、layouts/components/Header.vue、views/account/AccountView.vue 下对应文件及本记录。使用 replace 返回登录页。

**执行命令与验证：** Select-String 确认退出接口返回 401，待执行类型检查及浏览器验证。

**影响范围：** 前端退出与账号页会话失效处理。后端 CSRF 校验规则保持原样。

**遗留问题：** 待核验正常、令牌陈旧和会话已失效的退出行为。

---

### [2026-11-23 21:15:20] 核验退出登录与失效会话

**任务目标：** 验证账号设置页退出后回到登录页，保护页面不能被旧会话重新打开。

**执行行为：** 浏览器首轮复测发现退出端点需要有效访问令牌。调整 auth.ts 使用共享请求层注入最新 CSRF 令牌并保留原有的一次续签重试，让重试自动读取续签后的令牌。使用随机测试账号在独立 Chrome 中完成四条路径。

**文件变更：** 修改 project/frontend/src/api/auth.ts 和本记录。Playwright 快照和控制台记录位于 project/backend/target/local-restarts/start-20261123/.playwright-cli/。

**执行命令与验证：** npm run type-check 通过。Playwright CLI 验证正常退出、CSRF 已更新、访问令牌无效但刷新会话有效、刷新会话已撤销四种场景均返回登录页。前三种确认退出请求 200、退出所用的访问令牌随后返回 401，四种情况均确认再次访问账号设置被拦截。测试搭建阶段曾因撤销请求缺少访问令牌返回 401，补齐请求头后通过。

**影响范围：** 本地 Vite 已加载前端修复。浏览器验证使用独立测试账号。

**遗留问题：** 本轮浏览器验证聚焦退出登录，未再次执行修改密码操作。

---
