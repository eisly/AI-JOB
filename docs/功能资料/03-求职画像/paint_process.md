# 求职画像开发过程记录

> 本文件用于持续记录 Agent 在该功能目录下的开发行为，按时间顺序在末尾追加，不覆盖历史记录。

### [2026-11-05 15:32] 求职画像后端接口实现与联调验证

**任务目标：**
按 `docs/功能资料/03-求职画像/求职画像后端接口示例.md` 实现求职画像功能的后端接口，以 `docs/功能资料/01-登录注册/登录注册后端接口示例.md` 的返回结构、字段命名、错误码和鉴权约定为基准。本次范围经用户确认：只做后端画像接口，使用临时请求头传递用户 ID，前端暂不对接；技能掌握情况暂不实现。

**执行行为：**
1. 核对两份接口示例、`docs/功能资料/数据说明.md`、`V1__init_business_schema.sql`、`demo_data.sql` 和现有后端代码，确认文档中真实存在的接口只有 3 个，数据库表 `user_profile`、`skill_catalog` 已存在且字段可对应。
2. 确认鉴权缺口：三个接口文档要求 `Authorization: Bearer 用户accessToken`，但后端没有登录注册实现，`SecurityConfig` 为 `permitAll`。经用户确认，本次采用临时请求头 `X-User-Id`，并在代码注释中标明认证落地后需替换。
3. 实现统一错误模型：`CommonResult` 增加 `error` 字段并自动生成 `traceId`；新增 `ErrorInfo`、`BizException`、`GlobalExceptionHandler`，错误响应使用真实 HTTP 状态，错误码对齐公共联调约定（40001/40101/40401/40901/50001）。
4. 实现 `ProfileVO`、`ResumeFacts`、`SkillFact`、`SkillVO` 与 `ProfilePreferencesRequest`，字段和校验规则按接口文档：`expectedProfileVersion` 必填，`targetRole`/`candidateType` 枚举校验，`cities` 1–5 项，月薪非负且上限不小于下限。
5. 实现 `UserProfile` 实体与 MyBatis Mapper，JSON 列 `cities_json`、`confirmed_facts_json` 由服务层用 Jackson 转换；偏好更新使用 `profile_version` 乐观锁，版本不一致返回 409。
6. 实现 `ProfileService`、`ProfileController`、`SkillController`，提供 3 个文档接口；技能目录只查询 `enabled=1`，`q` 支持名称匹配且长度不超过 50。
7. 编译、启动后端，导入 `demo_data.sql` 演示数据，并用临时 Python 脚本执行 10 个接口场景验证；验证后恢复演示画像原值并清理临时脚本。
8. 技术处理说明：Spring Boot 4.0.4 启动时未提供可注入的 `ObjectMapper` Bean，初次启动失败；改为在 `ProfileServiceImpl` 内直接创建 `ObjectMapper` 实例后启动成功。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
|---|---|---|---|
| 新增 | `project/backend/src/main/java/com/aijob/backend/common/ErrorInfo.java` | 错误附加信息 `key/retryable/details` | 对齐公共错误结构 |
| 新增 | `project/backend/src/main/java/com/aijob/backend/common/BizException.java` | 携带 HTTP 状态、业务码、error.key 的业务异常 | 统一错误抛出方式 |
| 新增 | `project/backend/src/main/java/com/aijob/backend/common/GlobalExceptionHandler.java` | 处理业务异常、参数校验、请求体解析和未知异常 | 保证错误返回真实 HTTP 状态和统一结构 |
| 新增 | `project/backend/src/main/java/com/aijob/backend/profile/controller/ProfileController.java` | `GET /api/v1/profile`、`PUT /api/v1/profile/preferences` | 实现画像读取与偏好保存 |
| 新增 | `project/backend/src/main/java/com/aijob/backend/profile/controller/SkillController.java` | `GET /api/v1/skills?q=` | 实现技能目录查询 |
| 新增 | `project/backend/src/main/java/com/aijob/backend/profile/dto/ProfilePreferencesRequest.java` | 偏好保存请求及 Bean Validation 校验 | 对齐文档请求参数 |
| 新增 | `project/backend/src/main/java/com/aijob/backend/profile/entity/UserProfile.java` | `user_profile` 表实体 | 承载数据库查询结果 |
| 新增 | `project/backend/src/main/java/com/aijob/backend/profile/mapper/UserProfileMapper.java` | 查询画像、带乐观锁更新偏好 | 数据访问与并发控制 |
| 新增 | `project/backend/src/main/java/com/aijob/backend/profile/mapper/SkillCatalogMapper.java` | 查询启用技能并支持名称过滤 | 技能目录查询 |
| 新增 | `project/backend/src/main/java/com/aijob/backend/profile/service/ProfileService.java` | 画像服务接口 | 分层实现 |
| 新增 | `project/backend/src/main/java/com/aijob/backend/profile/service/impl/ProfileServiceImpl.java` | 读取/保存画像、JSON 转换、版本冲突处理、技能查询 | 业务逻辑实现 |
| 新增 | `project/backend/src/main/java/com/aijob/backend/profile/support/UserIdResolver.java` | 解析临时请求头 `X-User-Id` 并校验 UUID | 认证未实现前的临时身份来源 |
| 新增 | `project/backend/src/main/java/com/aijob/backend/profile/vo/ProfileVO.java` | 画像返回字段 | 对齐 `ProfileVO` |
| 新增 | `project/backend/src/main/java/com/aijob/backend/profile/vo/ResumeFacts.java` | 已确认事实字段 | 对齐 `ResumeFacts` |
| 新增 | `project/backend/src/main/java/com/aijob/backend/profile/vo/SkillFact.java` | 技能事实字段 | 对齐 `SkillFact` |
| 新增 | `project/backend/src/main/java/com/aijob/backend/profile/vo/SkillVO.java` | 技能目录字段 | 对齐 `SkillVO` |
| 修改 | `project/backend/src/main/java/com/aijob/backend/common/CommonResult.java` | 增加 `error` 字段；构造时生成 `traceId`；新增 `failure` 工厂方法；`notFound` 调整为 40401 | 对齐公共返回与错误约定 |
| 删除 | `project/backend/target/tmp/check_db.py` | 临时数据库计数脚本 | 验证结束后清理，不影响源码 |
| 删除 | `project/backend/target/tmp/import_demo.py` | 临时演示数据导入脚本 | 验证结束后清理，不影响源码 |
| 删除 | `project/backend/target/tmp/test_api.py` | 临时接口验证脚本 | 验证结束后清理，不影响源码 |
| 删除 | `project/backend/target/tmp/restore_demo.py` | 临时演示数据恢复脚本 | 验证结束后清理，不影响源码 |

**执行命令与验证：**
- 命令：`mvn -q -DskipTests compile`（工作目录 `project/backend`）
- 结果：通过
- 说明：编译无错误输出。

- 命令：`mvn spring-boot:run`（工作目录 `project/backend`，启动前临时清除用户级 `DB_USERNAME`、`DB_PASSWORD`）
- 结果：通过
- 说明：日志出现 `HikariPool-1 - Start completed`、`Schema "career_business" is up to date`、`Tomcat started on port 8080`、`Started BackendApplication`。

- 命令：`D:/anaconda3/envs/AI-JOB/python.exe project/backend/target/tmp/import_demo.py`
- 结果：通过
- 说明：导入后计数为 `app_user=2`、`user_profile=1`、`skill_catalog=4`。

- 命令：`D:/anaconda3/envs/AI-JOB/python.exe project/backend/target/tmp/test_api.py`
- 结果：通过
- 说明：实际验证结果如下：

| 场景 | HTTP 状态 | 业务码 | 结果 |
|---|---|---|---|
| 不带 `X-User-Id` | 401 | 40101 UNAUTHENTICATED | 通过 |
| 非法 UUID | 401 | 40101 UNAUTHENTICATED | 通过 |
| `GET /api/v1/profile` | 200 | 200 | 通过，facts 未知字段为 null、skills 为 `[]` |
| `GET /api/v1/skills` | 200 | 200 | 通过，返回 Java/MySQL/Redis/Spring 4 条 |
| `GET /api/v1/skills?q=re` | 200 | 200 | 通过，只返回 Redis |
| `q` 超过 50 字符 | 400 | 40001 VALIDATION_ERROR | 通过，details.fields 指向 `q` |
| `PUT /preferences` 版本 0 | 200 | 200 | 通过，profileVersion 0 → 1 |
| `PUT /preferences` 旧版本 0 | 409 | 40901 VERSION_CONFLICT | 通过，details.currentVersion=1 |
| `PUT /preferences` 非法枚举/空城市/上限小于下限 | 400 | 40001 VALIDATION_ERROR | 通过，details.fields 返回字段错误 |
| `GET /api/v1/profile` 不存在用户 | 404 | 40401 RESOURCE_NOT_FOUND | 通过 |

- 命令：`D:/anaconda3/envs/AI-JOB/python.exe project/backend/target/tmp/restore_demo.py`
- 结果：通过
- 说明：演示画像恢复为 `profile_version=0`、`min_monthly_salary_cents=1000000`、`max_monthly_salary_cents=1500000`。

- 未执行项：未编写、未执行自动化单元测试。
- 未执行原因：项目当前没有现成测试用例，接口依赖真实 MySQL 容器；本次使用真实 HTTP 请求做集成验证。

**接口与临时身份约定：**

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/v1/profile` | GET | 读取当前用户画像；画像不存在返回 404/40401 |
| `/api/v1/profile/preferences` | PUT | 保存求职偏好，成功后 profileVersion +1 |
| `/api/v1/skills` | GET | 查询启用技能，支持 `q` 名称过滤 |

临时身份请求头：

```http
X-User-Id: 11111111-1111-4111-8111-111111111111
```

说明：该请求头是认证未实现前的临时方案，代码位于 `profile/support/UserIdResolver.java`，注释已标明认证落地后应改为从 Bearer accessToken 解析并删除该约定。

**用户侧验证命令：**

```powershell
curl.exe -H "X-User-Id: 11111111-1111-4111-8111-111111111111" http://localhost:8080/api/v1/profile

curl.exe -H "X-User-Id: 11111111-1111-4111-8111-111111111111" "http://localhost:8080/api/v1/skills?q=re"
```

说明：以上两条命令为交付给用户的验证入口；Agent 实际验证使用上述临时 Python 脚本，未再次执行这两条 curl 命令。

**影响范围：**
- 新增 3 个 HTTP 接口：`GET /api/v1/profile`、`PUT /api/v1/profile/preferences`、`GET /api/v1/skills`。
- `CommonResult` 的 `traceId` 由原先的 null 改为自动生成的 UUID；错误响应增加 `error` 对象，成功响应不输出 `error` 字段。现有 `/api/v1/users` 接口仍可用，响应结构兼容。
- 数据库表结构未变更；仅导入 `demo_data.sql` 演示数据，验证结束后已恢复画像演示值。
- 前端未修改，`ProfileView.vue` 仍为占位页。
- 后端当前运行在 8080 端口（启动进程 PID 20464），运行的是包含本次新接口的代码。

**遗留问题：**
1. 登录注册未实现，当前使用 `X-User-Id` 临时请求头；后续需要按登录注册接口文档实现 Bearer accessToken 并替换 `UserIdResolver`。
2. 原型中的"技能掌握情况"没有文档接口，本次未实现；如后续需要，应先补充接口设计再开发。
3. 前端画像页面未对接，仍为占位页。
4. `project/backend/src/main/resources/application.yml` 第 5 行 `optional:fmvn spring-boot:runile:./.env[.properties]` 存在笔误；当前依赖前一段 `../../.env` 生效，本次未修改。
5. 用户级环境变量 `DB_USERNAME`/`DB_PASSWORD` 可能覆盖 `.env` 的 `MYSQL_USER`/`MYSQL_PASSWORD`，自行重启后端前需临时清除，否则可能再次出现 `Access denied`。
6. 演示画像已恢复为 `profileVersion=0`，后续调用偏好保存接口时应使用 `expectedProfileVersion=0`。
---

### [2026-11-05 17:30] 求职画像前端页面与三个接口对接

**任务目标：**
将 `project/frontend/src/views/profile/ProfileView.vue` 从占位页改为可操作的求职画像页面，对接后端 3 个接口：`GET /api/v1/profile`、`PUT /api/v1/profile/preferences`、`GET /api/v1/skills`。

**执行行为：**
1. 读取 `vite.config.ts`、`main.ts`、`MainLayout.vue`、`Header.vue`、`Sidebar.vue`、`PagePlaceholder.vue`、`variables.scss`，确认 Vite 已配置 `/api` 代理到 8080、Element Plus 组件已全量注册、SCSS 变量可复用。
2. 将 `src/api/request.ts` 从注释占位改为 fetch 封装：统一注入临时 `X-User-Id` 演示用户，解析 `CommonResult`，`code !== 200` 或 HTTP 非 2xx 时抛出 `ApiError`（保留业务码和 error）。
3. 新增 `src/api/profile.ts`：定义 `ProfileVO`、`ResumeFacts`、`SkillFact`、`SkillVO`、`ProfilePreferencesRequest` 和 3 个请求函数。
4. 重写 `ProfileView.vue`：页面加载时并行请求画像和技能目录；表单支持目标岗位、求职类型、期望城市（多选最多 5 个）、期望月薪（K/月，提交转换为分）；保存使用当前 `profileVersion` 作为 `expectedProfileVersion`；409 时提示并自动重新加载；重置恢复上次保存值；简历信息展示学历/毕业时间/专业；技能区展示已确认技能事实，演示数据为空时显示空状态。
5. 按原型保留顶部日期、"演示数据"标签、两栏卡片布局和 Info 提示。
6. 前端启动时遇到 esbuild `The service was stopped`（退出码 3221226505），`npm run build` 和 `npm run dev` 均失败；执行 `npm install esbuild@0.21.5 --no-save --no-package-lock` 重新安装后，`npm run dev -- --host 127.0.0.1` 启动成功。
7. 后端在 8080 启动（PID 30336），Vite 在 `127.0.0.1:5173` 启动，并通过 IDE 内置浏览器打开 `/profile`。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
|---|---|---|---|
| 修改 | `project/frontend/src/api/request.ts` | 注释占位改为 fetch 封装，注入 `X-User-Id`，解包 `CommonResult`，抛出 `ApiError` | 让前端可以调用后端接口 |
| 新增 | `project/frontend/src/api/profile.ts` | 画像/技能类型定义，`getProfile`、`savePreferences`、`listSkills` | 对接 3 个文档接口 |
| 修改 | `project/frontend/src/views/profile/ProfileView.vue` | 占位页改为完整求职画像页面 | 实现偏好表单、简历信息、技能区与保存/重置 |
| 依赖调整 | `project/frontend/node_modules` | 重新安装 `esbuild@0.21.5`（`--no-save --no-package-lock`） | 修复 esbuild 崩溃导致 Vite 无法启动 |
| 删除 | `project/backend/target/tmp/check_frontend.py` | 临时前端检查脚本 | 验证取消后清理，不影响源码 |

**执行命令与验证：**
- 命令：`npm run type-check`（工作目录 `project/frontend`）
- 结果：通过
- 说明：`vue-tsc --noEmit` 无输出，新增页面与 API 类型检查通过。

- 命令：`npm run build`
- 结果：失败
- 说明：`failed to load config from vite.config.ts`，esbuild `The service was stopped`，不是 TS/模板错误。

- 命令：`npm run dev` / `npm run dev -- --host 127.0.0.1`
- 结果：首次失败，重装 esbuild 后通过
- 说明：Vite 5.4.21 启动成功，`VITE v5.4.21 ready`，地址 `http://127.0.0.1:5173/`。

- 命令：`mvn -q spring-boot:run`（工作目录 `project/backend`，启动前清除 `DB_USERNAME`/`DB_PASSWORD`）
- 结果：通过
- 说明：8080 端口监听，进程 PID 30336。

- 验证：Vite 日志显示 `new dependencies optimized: element-plus/es/components/loading/style/css, empty/style/css, input-number/style/css, select/style/css, option/style/css, alert/style/css`，没有编译错误；已通过 IDE 内置浏览器打开 `http://127.0.0.1:5173/profile`。
- 未执行：自动化浏览器点击/保存交互验证。临时检查脚本因沙箱拦截、用户拒绝执行而取消；页面运行时交互需人工确认。

**影响范围：**
- 求职画像前端页面从占位页变为可用页面，加载时调用 `GET /profile` 和 `GET /skills`，保存时调用 `PUT /profile/preferences`。
- 前端固定使用演示用户 `11111111-1111-4111-8111-111111111111` 作为 `X-User-Id`，与后端临时身份约定一致；登录实现后需替换 `request.ts` 中的注入逻辑。
- 技能区当前展示 `facts.skills`；演示数据为空，因此显示"暂无已确认技能"，未展示训练状态。
- 后端代码、数据库结构未改变。

**遗留问题：**
1. 前端运行时交互（保存、409、重置）尚未自动化验证，需要人工在页面确认。
2. esbuild 崩溃的具体原因未定位；重装同版本后恢复，若再次出现需重装 `node_modules` 或检查安全软件。
3. 前端仍使用固定演示用户，登录注册未实现。
4. 技能掌握状态仍未实现，技能区仅在简历确认技能有数据时展示。
5. `application.yml` 第 5 行笔误、`DB_USERNAME`/`DB_PASSWORD` 覆盖问题仍存在。

---

### [2026-11-05 18:05] 目标岗位选择器改造为二级级联导航

**任务目标：**
将求职画像页目标岗位从纵向下拉改为"一级岗位分类 + 二级具体岗位"的左右两栏级联面板，保留全部预设岗位、搜索、自定义岗位和后端保存逻辑。

**执行行为：**
1. 检查现有 `ProfileView.vue` 目标岗位 `el-select`、`src/config/jobRoles.ts` 和上一轮的自定义岗位逻辑。
2. 调整岗位配置第一组分类名称从"Java / 后端开发"改为"后端开发"，43 个预设岗位全部保留；新增 `findJobRoleByValue` 用于旧枚举编码回显。
3. 新增 `src/views/profile/components/JobRoleCascader.vue`：
   - 使用 Element Plus `el-popover + el-input` 实现，不引入新依赖。
   - 无搜索时左右两栏：左侧一级分类，右侧当前分类岗位；分类悬停/点击切换，当前分类高亮。
   - 面板顶部搜索框，输入关键词后跨所有分类搜索，结果项同时显示岗位名称和所属分类。
   - 无匹配时显示"+ 使用自定义岗位：XXX"，点击或 Enter 选择；超过 32 字符显示提示且不可选。
   - 支持 Esc 关闭、上下方向键移动、Enter 选中、左右方向键切换分类；选中项和键盘当前项均有高亮。
   - 面板最大高度 420px，分类、岗位、搜索结果区域各自内部滚动；使用 popper `preventOverflow` 和最大宽度避免超出视口。
   - 小屏幕下改为分类在上、岗位在下的布局，避免两栏拥挤。
   - 增加 `role="listbox"`、`role="option"`、`aria-selected`、`aria-expanded` 等基本无障碍语义。
4. 修改 `ProfileView.vue`：目标岗位替换为 `JobRoleCascader`；删除旧的 `filter-method`、自定义 option 计算和 `roleQuery` 状态；`fillForm` 将旧编码映射为中文 `label`，保存时提交中文岗位名称；求职类型、城市、月薪等其他字段不变。
5. 同步 3 个文档共 6 处 `targetRole` 描述为"预设岗位名称或自定义岗位名称，≤32字符"。
6. 未修改数据库结构，未新增或删除接口，未修改后端代码。
7. 验证后将演示画像恢复为 `profileVersion=0`、`targetRole=JAVA_BACKEND`、月薪 1000000–1500000。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
|---|---|---|---|
| 新增 | `project/frontend/src/views/profile/components/JobRoleCascader.vue` | 二级级联面板、搜索、自定义、键盘导航、滚动和响应式布局 | 实现一级分类 + 二级岗位选择 |
| 修改 | `project/frontend/src/config/jobRoles.ts` | 第一组分类改为"后端开发"；新增 `findJobRoleByValue` | 对齐一级分类名称并支持旧编码回显 |
| 修改 | `project/frontend/src/views/profile/ProfileView.vue` | 替换目标岗位组件；删除旧逻辑；保存中文岗位名称 | 接入级联选择器并统一保存名称 |
| 修改 | `docs/功能资料/03-求职画像/求职画像后端接口示例.md` | 2 处 targetRole 描述更新 | 同步接口说明 |
| 修改 | `docs/功能资料/数据说明.md` | 1 处 targetRole 描述更新 | 同步数据模型说明 |
| 修改 | `docs/功能资料/业务后端接口文档.md` | 3 处 targetRole 描述更新 | 同步接口文档 |
| 删除 | `project/backend/target/tmp/check_cascader.py` | 临时前端模块检查脚本 | 验证后清理 |
| 删除 | `project/backend/target/tmp/test_preset_label.py` | 临时中文岗位保存测试脚本 | 验证后清理 |

**执行命令与验证：**
- 命令：`npm run type-check`（工作目录 `project/frontend`）
- 结果：第二次通过
- 说明：第一次出现 `handleKeydown` 参数类型与 Element Plus `@keydown` 不兼容，已改为 `Event | KeyboardEvent` 并在函数内判断，重新执行后无输出、通过。

- 验证：通过临时 Python 脚本请求 Vite 模块
- 结果：通过
- 说明：`/profile` 200；`/src/views/profile/components/JobRoleCascader.vue` 200（47891 字节）；`/src/views/profile/ProfileView.vue` 200；`/src/config/jobRoles.ts` 200，说明 Vite 编译无 500 错误。

- 命令：`D:/anaconda3/envs/AI-JOB/python.exe project/backend/target/tmp/test_preset_label.py`
- 结果：通过
- 说明：PUT 中文预设岗位"Java 后端开发"返回 200，GET 回显一致；随后恢复演示数据为 `profileVersion=0`、`targetRole=JAVA_BACKEND`。

- 命令：`npm run build`
- 结果：未执行
- 说明：前一轮已确认本机 esbuild `The service was stopped` 导致构建失败，本次未重复执行；不能声明构建通过。

- 未执行：浏览器内自动点击分类、点击岗位、键盘上下/Enter、搜索和自定义选择验证。已用 Vite 模块编译和接口请求验证，但 UI 交互需人工确认。

**影响范围：**
- 目标岗位 UI 改为左右两栏级联面板，一级分类只用于筛选，最终保存的仍是具体岗位名称。
- 预设岗位保存中文 `label`，自定义岗位保存用户输入名称；旧枚举编码读取时映射为中文 `label` 后回显。
- 数据库、后端接口、其他表单字段均未改变。
- 下游岗位匹配、简历优化、模拟面试、训练推荐仍为占位实现，无法验证自定义岗位的匹配兜底。

**遗留问题：**
1. 浏览器内级联面板交互未自动化验证，需要人工确认分类切换、搜索、键盘和自定义选择。
2. `npm run build` 的 esbuild 失败问题仍未解决。
3. 下游模块尚未实现，自定义岗位的匹配兜底需要后续实现。
4. 接口示例仍使用 `JAVA_BACKEND` 等旧编码，前端读取时会映射为中文，属于兼容示例。
5. `application.yml` 第 5 行笔误、`DB_USERNAME`/`DB_PASSWORD` 覆盖问题仍存在。
---

### [2026-11-05 17:47] 目标岗位选择升级：预设扩充、搜索过滤、自定义岗位

**任务目标：**
将求职画像页"目标岗位"从固定下拉升级为支持搜索过滤、预设岗位分组选择和自定义岗位输入的选择器；同时调整后端校验，使自定义岗位可以正常保存和回显，并兼容已有画像数据。

**执行行为：**
1. 检查现有 `ProfileView.vue`、`src/api/profile.ts`、后端 `ProfilePreferencesRequest` 与 `ProfileServiceImpl`，确认当前目标岗位使用固定枚举校验，数据库 `user_profile.target_role` 为 `VARCHAR(32)`。
2. 新增 `src/config/jobRoles.ts` 作为统一岗位配置：按 6 个分类维护 43 个预设岗位，每项包含稳定 `value`、中文 `label` 和搜索关键词；提供分组过滤、`label` 映射、预设值判断和 32 字符长度常量。
3. 兼容已有枚举数据：`JAVA_BACKEND`、`FRONTEND`、`ALGORITHM`、`TEST`、`DATA` 分别保留为"Java 后端开发""前端开发工程师""算法工程师""软件测试工程师""数据开发工程师"的 `value`，已有画像无需迁移即可回显中文名称。
4. 修改前端 `api/profile.ts`：`TargetRole` 改为字符串类型，`ProfileVO.targetRole` 支持预设编码或自定义岗位名称。
5. 修改 `ProfileView.vue`：使用 Element Plus `el-select` 的 `filterable + clearable + default-first-option + filter-method`，按预设分组实时过滤；输入无匹配时显示"+ 使用自定义岗位：XXX"选项；支持 Enter 选择、Esc 关闭、键盘上下选择；自定义输入前后空格去除，超过 32 字符显示禁用提示；保存前再次校验空值和长度，并用 `saving` 防止重复提交；自定义值通过动态 option 回显。
6. 修改后端 `ProfilePreferencesRequest`：移除 `targetRole` 的固定枚举 `@Pattern`，改为 `@NotBlank + @Size(max=32)`；`ProfileServiceImpl` 在写入前执行 `trim()`，保持预设编码和自定义名称都能原样保存。
7. 同步文档中 6 处 `targetRole` 描述：`求职画像后端接口示例.md`、`数据说明.md`、`业务后端接口文档.md`，从固定枚举改为"预设岗位编码或自定义岗位名称，≤32字符"。
8. 未修改数据库结构：`target_role VARCHAR(32)` 足以容纳 32 字符内的自定义岗位，无需迁移。
9. 验证后恢复演示画像为 `profileVersion=0`、`targetRole=JAVA_BACKEND`、月薪 1000000–1500000。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
|---|---|---|---|
| 新增 | `project/frontend/src/config/jobRoles.ts` | 6 组 43 个预设岗位、关键词、过滤与 label 映射、32 字符上限 | 统一岗位配置，避免多处硬编码 |
| 修改 | `project/frontend/src/api/profile.ts` | `TargetRole` 改为 string，`targetRole` 支持自定义字符串 | 前后端字段类型保持一致 |
| 修改 | `project/frontend/src/views/profile/ProfileView.vue` | 目标岗位改为可搜索组合选择器，支持分组、自定义、长度校验、去空格、防重复提交、回显 | 实现岗位搜索与自定义岗位 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/profile/dto/ProfilePreferencesRequest.java` | 移除 targetRole 固定枚举校验，改为 `@NotBlank + @Size(max=32)` | 允许合法的自定义岗位 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/profile/service/impl/ProfileServiceImpl.java` | 写入前对 targetRole 执行 `trim()` | 防止前后空格入库 |
| 修改 | `docs/功能资料/03-求职画像/求职画像后端接口示例.md` | 2 处 targetRole 描述改为预设编码或自定义岗位名称 | 同步接口约束 |
| 修改 | `docs/功能资料/数据说明.md` | 1 处 targetRole 描述同步 | 同步数据模型约束 |
| 修改 | `docs/功能资料/业务后端接口文档.md` | 3 处 targetRole 描述同步 | 同步接口文档 |
| 删除 | `project/backend/target/tmp/test_role_validation.py` | 临时岗位校验测试脚本 | 验证完成后清理，不影响源码 |

**执行命令与验证：**
- 命令：`mvn -q -DskipTests compile`（工作目录 `project/backend`）
- 结果：通过
- 说明：无编译错误。

- 命令：`npm run type-check`（工作目录 `project/frontend`）
- 结果：通过
- 说明：`vue-tsc --noEmit` 无输出。

- 命令：`mvn spring-boot:run`（工作目录 `project/backend`，启动前清除 `DB_USERNAME`/`DB_PASSWORD`）
- 结果：通过
- 说明：Tomcat 8080 启动，`Started BackendApplication`，进程 PID 23376。

- 验证：Vite 日志出现 `hmr update /src/views/profile/ProfileView.vue`，并新增优化 `element-plus/es/components/option-group/style/css`，无编译错误。

- 命令：`D:/anaconda3/envs/AI-JOB/python.exe project/backend/target/tmp/test_role_validation.py`
- 结果：通过
- 说明：实际接口验证结果如下：

| 场景 | 结果 |
|---|---|
| GET 画像 | 200，返回原 `targetRole` |
| PUT 自定义岗位 `AI + Java 后端开发工程师` | 200，保存成功，版本递增 |
| GET 再次读取 | 200，返回同一自定义岗位 |
| PUT 空白岗位 `"   "` | 400 / 40001，`targetRole 不能为空` |
| PUT 33 字符岗位 | 400 / 40001，`targetRole 长度不能超过 32 个字符` |
| PUT 预设岗位 `JAVA_BACKEND` | 200，保存成功 |
| 恢复演示数据 | 200，`profileVersion=0`、`targetRole=JAVA_BACKEND` |

- 命令：`npm run build`
- 结果：失败
- 说明：`failed to load config from vite.config.ts`，esbuild `The service was stopped`；`vue-tsc --noEmit` 阶段已通过，失败发生在 Vite 打包阶段，属于本地 esbuild 运行问题，不是页面代码类型错误。

- 未执行：浏览器内自动点击/键盘操作验证。Vite 页面已热更新，但未通过自动化脚本操作下拉框和保存按钮。

**影响范围：**
- 目标岗位字段现在同时接受预设编码和自定义岗位名称，后端不再按固定枚举拒绝。
- 已有画像的 `JAVA_BACKEND/FRONTEND/ALGORITHM/TEST/DATA` 继续可读，并显示为对应中文预设岗位。
- 自定义岗位长度限制为 32 字符，与数据库 `VARCHAR(32)` 保持一致，未修改表结构。
- 求职类型、期望城市、期望月薪、简历信息、技能区的现有逻辑未改变。
- 下游岗位匹配/简历优化/模拟面试/训练推荐当前仍是占位实现，无法验证自定义岗位的实际匹配兜底；后端只保证字符串原样保存和读取。

**遗留问题：**
1. `npm run build` 仍因 esbuild `The service was stopped` 失败；`npm run type-check` 和 Vite dev 热更新正常，生产构建需进一步排查本地 esbuild/安全软件环境。
2. 浏览器内下拉搜索、键盘上下选择、Enter 自定义、清除后重选等交互未自动化验证，需要人工在页面确认。
3. 下游模块尚未实现，自定义岗位如何参与岗位匹配、训练推荐等需要后续按"无法识别时合理兜底"的原则实现。
4. 接口文档已同步 targetRole 描述，但示例仍保留 `JAVA_BACKEND` 等预设编码，属于兼容示例，不是自定义岗位示例。
5. `application.yml` 第 5 行笔误、`DB_USERNAME`/`DB_PASSWORD` 覆盖问题仍存在。
---

### [2026-11-05 18:08] 修复 .env 导入笔误与数据库变量覆盖

**任务目标：**
修复后端 `application.yml` 中 `.env` 路径的笔误，并解决系统环境变量 `DB_USERNAME`/`DB_PASSWORD` 覆盖 `.env` 中 `MYSQL_USER`/`MYSQL_PASSWORD` 的问题，避免再次出现 `Access denied for user 'root'`。

**执行行为：**
1. 检查 `application.yml` 第 5 行，确认存在笔误 `optional:fmvn spring-boot:runile:./.env[.properties]`。
2. 将导入路径修正为 `optional:file:../../.env[.properties],optional:file:./.env[.properties]`。
3. 调整数据源账号占位符优先级：
   - `username: ${AIJOB_DB_USERNAME:${MYSQL_USER:${DB_USERNAME:aijob}}}`
   - `password: ${AIJOB_DB_PASSWORD:${MYSQL_PASSWORD:${DB_PASSWORD:}}}`
   使 `.env` 的 `MYSQL_*` 优先于系统 `DB_*`，同时保留 `DB_*` 作为兜底，并新增项目专用 `AIJOB_DB_*` 作为最高优先级覆盖项。
4. 同步 `project/backend/README.md` 中数据库配置说明。
5. 停止旧后端进程（PID 23376），在**不清除** `DB_USERNAME`/`DB_PASSWORD` 的情况下重新启动，验证 `.env` 优先生效。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
|---|---|---|---|
| 修改 | `project/backend/src/main/resources/application.yml` | 修复 `.env` 导入路径；调整数据源账号占位符优先级 | 修复配置笔误并避免系统变量覆盖 `.env` |
| 修改 | `project/backend/README.md` | 更新数据库配置说明 | 同步新的变量优先级 |
| 修改 | `docs/功能资料/03-求职画像/paint_process.md` | 追加本次修复记录 | 遵守开发记录规范 |

**执行命令与验证：**
- 命令：`mvn spring-boot:run`（工作目录 `project/backend`，未清除 `DB_USERNAME`/`DB_PASSWORD`）
- 结果：通过
- 说明：日志出现 `HikariPool-1 - Start completed`、Flyway 验证成功、`Tomcat started on port 8080`、`Started BackendApplication`，进程 PID 24028。由于系统 `DB_USERNAME=root` 仍存在但连接成功，说明 `.env` 的 `MYSQL_USER`/`MYSQL_PASSWORD` 已优先生效。

- 未执行：没有删除用户级 `DB_USERNAME`/`DB_PASSWORD`，也没有修改其他项目的环境变量。这样本项目使用 `.env`，其他项目仍可使用原有变量。

**影响范围：**
- 不再需要每次启动前执行 `Remove-Item Env:DB_USERNAME` / `Remove-Item Env:DB_PASSWORD`。
- 本项目数据库账号优先来自根目录 `.env` 的 `MYSQL_USER`/`MYSQL_PASSWORD`。
- 部署时仍可通过 `AIJOB_DB_USERNAME`、`AIJOB_DB_PASSWORD`、`DB_URL` 覆盖。
- 其他项目的 `DB_USERNAME`/`DB_PASSWORD` 不受影响。

**遗留问题：**
1. `.env` 修改后，已初始化的 MySQL 容器密码不会自动同步，仍需保证 `.env` 与容器初始化时的密码一致。
2. `npm run build` 的 esbuild 问题仍未解决。
3. 浏览器内级联选择器交互仍需人工确认。
### [2026-11-13 16:32:13] 建立简历事实修订记录表

**任务目标：** 已确认简历事实可纠错且保留历史版本。

**执行行为：**

1. 查明目前确认接口拒绝修改 CONFIRMED 版本，原解析 JSON 与已确认 JSON 分开保存。
2. 新增 Flyway V10 修订表，记录每次事实快照、画像版本、修改原因和时间。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/src/main/resources/db/migration/V10__add_resume_fact_revisions.sql` | 新增简历事实修订记录表 | 支持纠错审计 |
| 修改 | `docs/功能资料/03-求职画像/paint_process.md` | 追加本记录 | 跟踪开发行为 |

**执行命令与验证：**

- 命令：读取确认流程和原表结构，新增迁移文件。
- 结果：迁移已编写，尚未执行。

**影响范围：** 下次后端启动时 Flyway 将新增数据表，不修改原解析事实。

**遗留问题：** 接口、页面和测试待接入。

---

### [2026-11-13 16:33:27] 接入已确认简历事实纠错接口

**任务目标：** 无需重新上传简历即可纠正当前生效版本，并保存修订历史。

**执行行为：**

1. 新增纠错和修订列表接口；只允许当前生效且已确认的简历版本，要求画像版本与纠错原因。
2. 事务中保留首次确认事实、写入新事实和修订记录，更新画像与能力自述证据；原解析 JSON 保持不变。
3. 版本详情增加 `parsedFacts`，便于对照原解析。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/backend/src/main/java/com/aijob/backend/resume/ResumeMapper.java` | 修订记录增查及已确认事实更新 SQL | 持久化纠错 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/resume/ResumeService.java` | 纠错事务、校验、历史读取与原解析输出 | 保证版本一致与可追溯 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/resume/ResumeController.java` | POST/GET corrections 接口 | 前端可操作和查询 |
| 修改 | `project/backend/src/main/resources/db/migration/V10__add_resume_fact_revisions.sql` | 初次确认历史允许未知画像版本 | 避免伪造旧版号 |
| 修改 | `docs/功能资料/03-求职画像/paint_process.md` | 追加本记录 | 跟踪开发行为 |

**执行命令与验证：**

- 命令：修改后端文件。
- 结果：接口代码已接入，构建与集成测试待执行。

**影响范围：** 简历版本、画像版本、能力自述证据及新修订表。

**遗留问题：** 前端入口和验证尚待完成；确认其他下游版本依赖回归。

---

### [2026-11-13 16:34:52] 接入简历事实纠错页面

**任务目标：** 已确认简历可在页面逐项纠错、提交依据并查看修订历史。

**执行行为：**

1. 前端 API 增加事实纠错提交和修订历史读取类型与方法。
2. 简历页在当前生效的已确认版本提供编辑入口、原因输入、取消和保存；展示原解析事实及历史快照。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/api/types.ts` | 增加原解析事实与修订记录类型 | 对接新接口 |
| 修改 | `project/frontend/src/api/resumes.ts` | 增加纠错提交、历史查询 | 调用后端 |
| 修改 | `project/frontend/src/views/resume/ResumeView.vue` | 纠错流程和历史展示 | 无需重传简历纠错 |
| 修改 | `docs/功能资料/03-求职画像/paint_process.md` | 追加本记录 | 跟踪开发行为 |

**执行命令与验证：**

- 命令：修改前端代码。
- 结果：代码已接入，类型和真实交互待验证。

**影响范围：** 简历详情和求职画像事实。

**遗留问题：** 待执行构建、迁移与接口回归；页面历史当前以结构化 JSON 展示，后续可优化为逐字段对比。

---

### [2026-11-13 16:36:35] 验证简历纠错与 JD 城市逻辑

**任务目标：** 检查纠错事务的关键调用和既有 JD 城市修复。

**执行行为：**

1. 增加纠错定向测试：当前生效版本写入原/新快照并更新画像，非生效版本被拒绝。
2. 运行前端类型检查和 Java 定向测试。首次 Java 测试因测试代码泛型推断错误而编译失败，修正断言参数后复测通过。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/src/test/java/com/aijob/backend/resume/ResumeServiceCorrectionTest.java` | 正常纠错与非生效版本拒绝测试 | 验证后端约束 |
| 修改 | `docs/功能资料/03-求职画像/paint_process.md` | 追加测试结果 | 跟踪验证 |

**执行命令与验证：**

- 命令：`npm.cmd run type-check`；`mvn -o -q -Dtest=ResumeServiceCorrectionTest,JobServiceCityTest test`。
- 结果：前端类型检查通过；Java 修正测试代码后通过。Mockito 输出动态 Agent 警告。
- 说明：尚未运行 Flyway 迁移或真实账号纠错端到端测试。

**影响范围：** 前端类型、后端纠错和 JD 地点定向验证。

**遗留问题：** 需在隔离环境或现有本机开发库验证迁移和接口；技能目录覆盖、自定义技能仍待实现。

---

### [2026-11-13 16:37:22] 应用简历事实修订迁移并启动后端

**任务目标：** 在本机开发库启用纠错历史表及新接口。

**执行行为：**

1. 确认 8080 属于 AI-JOB 后端后停止旧进程，执行既有启动脚本。
2. 查看启动日志和端口：连接本机 MySQL `career_business`，Flyway 从 v9 升至 v10，8080 成功监听。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/03-求职画像/paint_process.md` | 追加迁移与启动结果 | 跟踪运行操作 |

**执行命令与验证：**

- 命令：`Get-NetTCPConnection`、`Get-CimInstance`、`Stop-Process`、既有 `start.ps1`、读取 `java.out`。
- 结果：Flyway V10 成功应用到本机 `career_business`；Spring Boot 启动成功，8080 监听。

**影响范围：** 本机开发库新增 `resume_fact_revision` 表；本机后端已运行新代码。

**遗留问题：** 尚未用真实账号提交纠错与读取历史；其他模块的完整回归待执行。

---

### [2026-11-13 16:38:26] 复核纠错功能当前构建与记录

**任务目标：** 确认本轮代码和验证记录一致。

**执行行为：**

1. 调整纠错保存成功提示，避免把纠错误写为首次确认。
2. 复跑前端类型检查，核对 Surefire 报告并检查差异空白。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/views/resume/ResumeView.vue` | 保存提示统一为“事实已保存” | 区分纠错和首次确认 |
| 修改 | `docs/功能资料/03-求职画像/paint_process.md` | 追加复核结果 | 跟踪验证 |

**执行命令与验证：**

- 命令：`npm.cmd run type-check`、读取 Surefire 报告、`git diff --check`。
- 结果：前端类型检查退出码 0；纠错测试 2 项和 JD 地点测试 1 项均通过；差异检查无空白错误，只有行尾格式提示。
- 说明：首次从前端目录读取后端报告使用了错误相对路径，改用项目根目录后成功读取。

**影响范围：** 前端文案与验证记录。

**遗留问题：** 尚未用真实账号从浏览器提交纠错；技能目录和自定义技能等仍需继续。

---

### [2026-11-13 16:39:15] 设计用户私有自定义技能存储

**任务目标：** 允许补充目录外技术，同时防止未规范化名称进入公共技能目录。

**执行行为：**

1. 核对技能目录、简历确认和能力证据的外键关系。
2. 新增 Flyway V11，为技能目录增加所有者与规范化状态；已有公共技能保持 STANDARD，用户技能标记 PENDING。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/src/main/resources/db/migration/V11__add_private_custom_skills.sql` | 增加所有者、规范化状态及约束 | 隔离用户技能 |
| 修改 | `docs/功能资料/03-求职画像/paint_process.md` | 追加本记录 | 跟踪开发行为 |

**执行命令与验证：**

- 命令：读取目录与外键代码，新增迁移文件。
- 结果：迁移已编写，尚未执行。

**影响范围：** 技能目录表结构。

**遗留问题：** 服务层鉴权、前端入口和测试待接入。

---

### [2026-11-13 16:42:35] 接入用户私有技能创建与选择

**任务目标：** 技能目录没有所需技术时可直接添加，并标记待规范化。

**执行行为：**

1. 后端技能目录查询按当前用户过滤，公共技能所有人可见，自定义技能仅所有者可见；新增创建接口，复用同名已有技能。
2. 简历解析与个人 JD 分析使用当前用户的技能目录；事实确认、结果校验和相关成长/训练/面试/项目入口按用户校验技能所有权。
3. 简历页增加自定义技能输入框，创建后加入当前事实草稿，选项显示待规范化标识。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/backend/src/main/java/com/aijob/backend/profile/ProfileMapper.java` | 用户过滤、同名查找和私有技能插入 SQL | 防止跨账号暴露 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/profile/ProfileService.java` | 用户目录、技能校验及创建逻辑 | 自定义技能待规范化 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/profile/ProfileController.java` | 新增 `POST /api/v1/skills/custom` | 提供创建入口 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/resume/ResumeService.java` | 当前用户技能目录与校验 | 简历解析与确认可用 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/job/JobService.java`、`JobResultHandler.java` | 个人 JD 分析包含私有技能 | 技能提取可用 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/learning/LearningResultHandler.java`、`interview/InterviewResultHandler.java`、`growth/GrowthService.java`、`project/ProjectService.java` | 相关结果按用户校验自定义技能 | 防止误判无效或越权 |
| 修改 | `project/frontend/src/api/types.ts`、`profile.ts`、`views/resume/ResumeView.vue` | 创建 API、待规范化标签和页面入口 | 支持手动补充 |
| 修改 | `docs/功能资料/03-求职画像/paint_process.md` | 追加本记录 | 跟踪开发行为 |

**执行命令与验证：**

- 命令：修改后端和前端文件。
- 结果：代码修改完成，编译、迁移及权限验证待执行。

**影响范围：** 技能目录、简历解析/确认、个人 JD 与使用技能的业务模块。

**遗留问题：** 需要验证不同用户隔离；自动别名归一化和合并后台尚未实现。

---

### [2026-11-13 16:44:52] 扩充公共技能并验证私有技能

**任务目标：** 覆盖常见语言、安全技术，并检验自定义技能所有权。

**执行行为：**

1. V11 增加 Python、C/C++、JavaScript/TypeScript、Go、Rust、FastAPI、Vue、容器和网络安全等公共技能；私有技能插入改为幂等忽略重复。
2. 学习计划按当前用户目录构造可用技能；增加单元测试，验证私有技能创建和跨用户校验拒绝。
3. 运行前端类型检查和三组后端定向测试。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/backend/src/main/resources/db/migration/V11__add_private_custom_skills.sql` | 加入常用语言、框架和安全技能 | 扩大基础目录 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/profile/ProfileMapper.java` | 重复插入保持幂等 | 处理并发同名创建 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/learning/LearningService.java` | 学习技能使用用户目录 | 识别私有技能 |
| 新增 | `project/backend/src/test/java/com/aijob/backend/profile/ProfileCustomSkillTest.java` | 私有技能创建及所有权测试 | 验证隔离 |
| 修改 | `docs/功能资料/03-求职画像/paint_process.md` | 追加本记录 | 记录开发验证 |

**执行命令与验证：**

- 命令：`npm.cmd run type-check`；`mvn -o -q -Dtest=ProfileCustomSkillTest,ResumeServiceCorrectionTest,JobServiceCityTest test`。
- 结果：前端类型检查通过；后端定向测试退出码 0，Mockito 仅输出动态 Agent 警告。

**影响范围：** 技能目录、简历解析、JD 分析和学习计划。

**遗留问题：** V11 待运行迁移，真实账号创建/跨账号隔离待端到端验证；自动规范化尚未实现。

---

### [2026-11-13 16:45:31] 应用私有技能目录迁移

**任务目标：** 在本机开发库启用用户私有技能和扩充后的公共目录。

**执行行为：**

1. 核对后端 8080 进程后重启应用，读取 Flyway 与 Spring 启动日志。
2. Flyway 从 V10 升至 V11，本机 MySQL `career_business` 迁移成功，8080 已监听。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/03-求职画像/paint_process.md` | 追加迁移和启动结果 | 跟踪运行行为 |

**执行命令与验证：**

- 命令：`Get-NetTCPConnection`、`Get-CimInstance`、`Stop-Process`、既有 `start.ps1`、读取 `java.out`。
- 结果：V11 成功应用；Spring Boot 启动成功，8080 监听。

**影响范围：** 本机开发数据库 `skill_catalog` 新列及公共技能数据，后端运行新代码。

**遗留问题：** 尚需真实账号创建及跨账号读取验收；自动规范化/合并仍待实现。

---

### [2026-11-13 16:46:54] 复测用户技能对相关模块的影响

**任务目标：** 检查用户目录变更未破坏训练、面试、项目和岗位结果链路。

**执行行为：**

1. 扩大后端定向测试范围。首轮 `LearningServiceResumePlanTest` 因旧 Mock 仍配置无参目录方法而失败；把桩更新为按用户目录后复测。
2. 复测涵盖自定义技能、简历纠错、JD 地点、学习计划/题目、面试、项目和搜索结果测试类。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/backend/src/test/java/com/aijob/backend/learning/LearningServiceResumePlanTest.java` | 使用 `agentSkillCatalog("user")` 测试桩 | 对齐用户目录调用 |
| 修改 | `docs/功能资料/03-求职画像/paint_process.md` | 追加失败及复测结果 | 记录验证 |

**执行命令与验证：**

- 命令：`mvn -o -q -Dtest=ProfileCustomSkillTest,ResumeServiceCorrectionTest,JobServiceCityTest,LearningServiceTest,LearningServiceResumePlanTest,LearningResultHandlerTest,InterviewResultHandlerTest,ProjectProgressServiceTest,JobSearchResultHandlerTest test`。
- 结果：首轮 22 项中 1 项因旧桩失败；修正后命令退出码 0，Mockito 有动态 Agent 警告。

**影响范围：** 后端相关模块定向回归。

**遗留问题：** 真实账号隔离和浏览器完整操作仍待验证。

---
### [2026-11-13 17:14:39] 增加实习与获奖候选事实提取

**任务目标：** 将简历中明确出现的实习与获奖经历作为可核对事实保留，而非只写入解析警告。

**执行行为：**

1. Agent 解析结果新增 `internships`、`awards` 两组候选事实，每项包含概述、原文引用、位置和确认状态。
2. 更新解析提示词版本至 v3，要求提取明确经历并保留原文依据。
3. Handler 核验模型引用属于简历原文；模型遗漏时从含相关关键词的原文行补充候选项，统一标记为未确认。
4. 新增测试覆盖实习补取、奖项引用及伪造引用拒绝。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/app/schemas.py` | 新增证据事实类型及实习/奖项字段 | 让经历有结构化存储契约 |
| 修改 | `project/agent/app/operations.py`、`project/agent/app/skills/prompts/resume_parse.md` | 解析提示词升级至 v3 | 引导模型提取并引用原文 |
| 修改 | `project/agent/app/handlers.py` | 引用校验和原文行补取 | 减少明确经历遗漏 |
| 修改 | `project/agent/tests/test_handlers.py`、`project/agent/tests/test_skills.py` | 新增经历引用测试及版本断言 | 验证契约和依据 |
| 修改 | `docs/功能资料/03-求职画像/paint_process.md` | 追加本记录 | 跟踪开发行为 |

**执行命令与验证：**

- 命令：阅读 Agent 解析契约和测试夹具后修改上述文件。
- 结果：代码已修改；测试尚未执行。

**影响范围：** 新解析任务的 JSON 契约与实习、获奖候选事实；历史简历不会自动重新解析。

**遗留问题：** Java 确认接口和前端核对表单尚未接入新字段，需继续实现并测试。

---
### [2026-11-13 17:17] 验证实习和获奖解析契约

**任务目标：** 确认新字段可保留真实引用并拒绝伪造引用。

**执行行为：** 运行 Agent 的简历解析定向测试。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/03-求职画像/paint_process.md` | 追加验证与异常记录 | 跟踪实际执行情况 |

**执行命令与验证：**

- 命令：`D:\Anaconda\envs\ai-job\python.exe -m pytest -q tests/test_handlers.py tests/test_skills.py -k resume`。
- 结果：宿主机执行 8 项通过；首次隔离环境执行约 90 秒无输出，已手动中断，不计为通过。

**影响范围：** Agent 解析契约验证。

**遗留问题：** Java 与前端尚未处理新字段；实际模型解析质量需另行抽样。

---
### [2026-11-13 17:19:14] 接通实习与获奖事实确认链路

**任务目标：** 用户可以查看、补充、确认及纠正实习和获奖经历，画像与修订记录保留这些字段。

**执行行为：**

1. Java 解析回调强制将实习、获奖候选事实置为未确认；确认/纠错接口校验并保存内容、原文引用和位置。
2. 新画像和清空画像的默认事实补充两组空数组；旧 JSON 在前端克隆时补空数组以兼容历史简历。
3. 简历页面增加两组可编辑经历及证据，确认或纠错时一并提交；新增 Java 测试检查持久化入参及回调确认状态。
4. 前端类型检查已执行。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/backend/src/main/java/com/aijob/backend/resume/ResumeResultHandler.java`、`ResumeService.java` | 回调状态与确认校验 | 保留可核对经历事实 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/profile/ProfileMapper.java` | 默认事实包含实习和奖项数组 | 新画像结构完整 |
| 修改 | `project/backend/src/test/java/com/aijob/backend/resume/ResumeResultHandlerTest.java`、`ResumeServiceCorrectionTest.java` | 增加证据状态和保存测试 | 验证新字段路径 |
| 修改 | `project/frontend/src/api/types.ts`、`src/utils/format.ts`、`src/views/resume/ResumeView.vue` | 类型、旧数据兼容和核对表单 | 用户可编辑与确认 |
| 修改 | `docs/功能资料/03-求职画像/paint_process.md` | 追加本记录 | 跟踪开发行为 |

**执行命令与验证：**

- 命令：`npm.cmd run type-check`。
- 结果：前端类型检查退出码 0；Java 测试待执行。

**影响范围：** 新解析结果、简历确认/纠错、用户画像 JSON 和简历页面；不自动重解析历史简历。

**遗留问题：** 尚需运行 Java 测试并在真实浏览器核对有经历的简历。

---
### [2026-11-13 17:20] 验证简历经历字段的 Java 保存路径

**任务目标：** 检查回调强制未确认与已确认事实保存。

**执行行为：** 运行简历回调、纠错及画像技能定向测试，并读取 Surefire 报告。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/03-求职画像/paint_process.md` | 追加测试结果 | 跟踪验证 |

**执行命令与验证：**

- 命令：`mvn -o -q '-Djacoco.skip=true' '-DargLine=-javaagent:D:\maven\maven-rep\org\mockito\mockito-core\5.20.0\mockito-core-5.20.0.jar' '-Dtest=ResumeResultHandlerTest,ResumeServiceCorrectionTest,ProfileCustomSkillTest' test`。
- 结果：退出码 0；Surefire 报告中简历回调 2 项、纠错 2 项均无失败或错误。
- 说明：本机默认 Mockito 动态附加受限，显式使用已安装的 Mockito agent；Maven 启动仍打印 `Access is denied.`，但测试报告和退出码均正常。

**影响范围：** Java 简历与画像定向测试。

**遗留问题：** 真实解析、确认与修订端到端尚未验证。

---
