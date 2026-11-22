# 项目实战推荐接入真实开源仓库 · 开发过程记录

### [2026-11-18 15:00-15:30] 现状核对与接口契约确认

**任务目标：**
在现有「项目实战推荐」入口上接入 GitHub、Gitee、GitCode 真实公开仓库，保留双栏页面与建计划接口，按技能覆盖与收藏数排序，并提供许可证、README、来源快照与旧数据兼容。

**执行行为：**

1. 核对当前 Git 工作区：HEAD 为 `cc71c08`，项目推荐页仍为「AI 练习方案」形态；`fda1a43` 的 GitHub 检索实现已在合入中被覆盖。
2. 读取 `docs/功能资料/08-项目实战/项目实战后端接口示例.md` 与 `项目实战UI与交互说明.md`，确认 `sourceRuns`、`primarySource`、`alternateSources`、`gettingStarted`、`LEGACY_IDEA` 契约。
3. 读取 `docs/.../SearchApi.md` 同源资料与 GitCode 官方仓库搜索/仓库详情文档，确认 GitCode 搜索必须携带 `access_token`，仓库详情返回 `license` 与 `readme_url`。
4. 确认现有 Flyway 迁移最大版本为 `V14__add_practice_sets.sql`，`V15` 未被占用。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 读取 | `project/agent/app/learning_github.py`、`handlers.py`、`schemas.py` | 核对现有 GitHub 检索与计划链路 | 复用技能匹配与候选校验口径 |
| 读取 | `project/backend/.../project/*.java`、`V14__add_practice_sets.sql` | 核对推荐保存、查询与迁移现状 | 确定 V15 与 Java 改动点 |

**执行命令与验证：**

- 命令：`git status --porcelain`、`git log --oneline -5`
- 结果：通过（只读）
- 说明：工作区原有 3 处未提交改动与本任务无关，已保留。

**影响范围：** 无代码变更。

**遗留问题：** 无。

---

### [2026-11-18 15:00-15:30] Agent：三平台仓库检索与 OSI 许可证筛选

**任务目标：**
新增三源并行检索，按仓库地址去重、技能覆盖数优先、收藏数排序，过滤私有/无简介/许可证不确认仓库，并记录各平台失败状态。

**执行行为：**

1. 新增 `app/project_sources.py`：
   - GitHub 使用 `/search/repositories`，Gitee 使用 `/api/v5/search/repositories`，GitCode 使用 `/api/v5/search/repositories`。
   - 每个平台最多 3 个检索词（用户关键词优先，其余取优先技能词），三平台 `asyncio.gather` 并行。
   - 过滤 `private`、`fork`、`archived/disabled`、无简介、非 HTTPS、平台/路径不一致的仓库。
   - 许可证读取平台元数据，缺失时调用仓库详情或许可证接口；归一化为 SPDX ID，仅保留 `OSI_SPDX_LICENSES` 中的许可证。
   - README 地址必须为平台 HTTPS 且位于对应仓库路径下，无法确认时为 `null`。
   - 同平台按规范化 URL 去重；跨平台仓库路径一致时合并，主入口按用户选中平台顺序、README/许可证完整度选择，其余进入 `alternate_sources`。
   - 排序按命中目标技能数降序、收藏数降序，最终最多 6 条。
   - 部分平台失败保留其他平台结果；全部平台失败抛出 `RepositorySourcesUnavailable`；检索成功但无合格仓库返回空候选。
- Gitee/GitCode 未配置服务端令牌时直接标记该平台 `FAILED`，避免匿名请求返回 200 空数组被误判为“成功但无结果”。
2. 新增配置：`repository_search_*`、`gitee_token`、`gitcode_token`；`.env.example` 增加 `GITHUB_TOKEN`、`GITEE_TOKEN`、`GITCODE_TOKEN`。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `project/agent/app/project_sources.py` | 三平台检索、许可证筛选、去重排序、README 校验 | 真实仓库推荐核心 |
| 修改 | `project/agent/app/config.py` | 新增仓库检索超时、分页、候选上限与平台令牌配置 | 集中配置 |
| 修改 | `.env.example` | 增加三平台令牌占位 | 部署说明 |
| 新增 | `project/agent/scripts/verify_repo_sources.py` | 逐平台真实探测与 GitHub 模块联调脚本 | 可重复验证 |

**执行命令与验证：**

- 命令：`conda run -n ai-job python -c "import app.handlers, app.schemas, app.project_sources; print('import ok')"`
- 结果：通过

**影响范围：** 新增模块，尚未接入业务 handler。

**遗留问题：** Gitee/GitCode 真实返回依赖服务端令牌。

---

### [2026-11-18 15:00-15:30] Agent：推荐 schema、提示词与 handler 改造

**任务目标：**
推荐结果改为真实仓库候选，模型只能补充学习建议，移除模型造题/本地练习补位。

**执行行为：**

1. `schemas.py` 新增 `RepositorySource`、`RepositorySourceRun`、`ProjectRecommendSuggestion`、`ProjectRecommendModelOutput`；`AIProjectRecommendResult` 增加 `mode`、`sourceRuns`，`items` 允许 0..6；`ProjectRecommendPayload` 增加 `query`、`sources`，难度改为可空。
2. `BaseHandler.execute` 支持传入已完成的 `prepared`，避免推荐 handler 重复检索。
3. `ProjectRecommendHandler` 改为真实检索 + 模型建议：
   - 无候选时直接返回空 `items`，不调用模型。
   - 模型失败/超时/输出非法时，仅基于真实候选生成规则化学习提示。
   - 校验 `sourceUrl` 必须属于本次候选、技能必须在 `allowedSkillCodes`、难度与周期符合输入。
   - 结果由服务端填充仓库名、原始简介、许可证、主/备来源。
4. 删除 `project_fallback.recommend_project`，保留基于已选真实仓库的计划规则降级。
5. 更新 `project_recommend.md` 到 v2、`project_plan.md` 到 v2，模型输入注入 `repositoryCandidates` 与 `primarySource`。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/agent/app/schemas.py` | 来源、模型输出、结果契约 | 真实仓库字段 |
| 修改 | `project/agent/app/handlers.py` | `ProjectRecommendHandler` 重写、`BaseHandler.execute` 支持 `prepared` | 移除本地造题 |
| 修改 | `project/agent/app/skills/tools.py` | 注入仓库候选与计划来源 | 模型只能基于候选输出 |
| 修改 | `project/agent/app/skills/prompts/project_recommend.md` | 升级 v2，禁止编造仓库 | 真实性约束 |
| 修改 | `project/agent/app/skills/prompts/project_plan.md` | 升级 v2，围绕真实仓库 | 计划与仓库一致 |
| 修改 | `project/agent/app/operations.py` | 提示词版本升级 | 版本校验 |
| 修改 | `project/agent/app/fake_model.py` | 测试假模型输出 `sourceUrl` | 契约同步 |
| 修改 | `project/agent/app/project_fallback.py` | 删除 `recommend_project` | 移除本地练习补位 |

**执行命令与验证：**

- 命令：`conda run -n ai-job python -m pytest tests/test_project_sources.py tests/test_resume_project.py tests/test_skills.py -q`
- 结果：通过
- 说明：首次运行许可证归一化用例失败（`Apache_License_v2.0` 未被识别），补充 `v2` 归一化后重跑通过。

**影响范围：** 项目推荐结果结构与提示词版本变更，旧推荐读取路径保持兼容。

**遗留问题：** 无。

---

### [2026-11-18 15:00-15:30] Java：V15 迁移与推荐来源保存/读取

**任务目标：**
保存推荐批次模式、各平台检索状态、候选来源快照与计划来源，保存前校验链接、平台、仓库路径一致。

**执行行为：**

1. 新增 `V15__add_project_repository_sources.sql`：
   - `project_recommendation` 增加 `mode`（默认 `LEGACY_IDEA`）与 `source_runs_json`。
   - `project_recommendation_item` 增加 `getting_started_json`、`primary_source_json`、`alternate_sources_json`。
   - `project` 增加 `primary_source_json`。
2. `ProjectMapper` 更新插入/完成 SQL，新增 `markRepositoryMode`。
3. `ProjectService` 支持 `query`、`sources`、可空 `difficulty`；新批次写入 `REPOSITORY`；计划创建保存来源快照；查询输出 `mode`、`sourceRuns`、`primarySource`；旧批次缺省 `LEGACY_IDEA`。
4. `ProjectResultHandler`：
   - 允许 0..6 条候选，保存 `sourceRuns` 与来源快照。
   - 校验平台枚举、仓库路径格式、HTTPS 仓库/README 链接与平台及路径一致。
   - 重试旧批次时将模式更新为 `REPOSITORY`。
5. 新增 `ProjectResultHandlerTest` 覆盖来源保存、三类链接不一致拒绝、零结果、旧模式读取与旧候选生成计划请求。
6. 旧候选的 `getting_started_json`/`alternate_sources_json` 为 NULL 时，计划请求回落为空数组，避免 Agent 计划 schema 校验失败；旧候选仍可正常生成和维护计划。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `project/backend/src/main/resources/db/migration/V15__add_project_repository_sources.sql` | 推荐/候选/计划来源字段 | 持久化真实仓库 |
| 修改 | `project/backend/.../project/ProjectMapper.java` | 新增模式、来源与学习步骤字段 | 落库 |
| 修改 | `project/backend/.../project/ProjectService.java` | 请求参数、来源快照、查询返回 | 接口契约 |
| 修改 | `project/backend/.../project/ProjectResultHandler.java` | 来源校验与保存 | 防伪造链接 |
| 新增 | `project/backend/src/test/java/.../project/ProjectResultHandlerTest.java` | 保存/读取/拒绝用例 | 回归 |

**执行命令与验证：**

- 命令：`mvn -q -DskipTests compile`
- 结果：通过
- 命令：`mvn -q "-Djacoco.skip=true" "-DforkCount=0" "-Dtest=ProjectResultHandlerTest" test`
- 结果：通过（`Tests run: 5, Failures: 0, Errors: 0`）
- 说明：默认 surefire fork 在 JaCoCo agent 下启动崩溃（exit `-1073740791`），改用同进程方式运行单测通过。新增的旧候选用例首次因测试请求缺少 `weeklyHours` 失败，补齐后通过；属于测试数据问题，不是实现缺陷。
- 命令：`mvn -q "-Djacoco.skip=true" "-DforkCount=0" "-Dtest=V15MigrationCheckTest" test`
- 结果：通过（`Tests run: 1, Failures: 0, Errors: 0`）
- 说明：经用户授权，在本地 3306 创建独立临时库 `aijob_v15_migration_check`，先由 Flyway 迁到 V14 并写入旧推荐、旧候选、旧计划数据，再升级到 V15；验证 `mode` 默认 `LEGACY_IDEA`、`source_runs_json`/`primary_source_json`/`getting_started_json` 对旧行为 NULL、`flyway_schema_history` 最新版本为 15；测试结束在 `finally` 中删除临时库。该临时测试文件验证后已删除，不作为长期用例。

**影响范围：** 推荐批次与计划新增可选字段，旧数据默认 `LEGACY_IDEA`，旧计划来源为 `null`。

**遗留问题：** V14→V15 真实 MySQL 升级未执行（见末节）。

---

### [2026-11-18 15:00-15:30] 前端：双栏仓库展示、筛选与计划页仓库入口

**任务目标：**
保留推荐页双栏；左栏展示仓库名、来源平台、简介；右栏展示打开仓库、项目概述、许可证、推荐理由、入门步骤；计划页标题区展示同一仓库入口；支持关键词、来源平台与不限难度。

**执行行为：**

1. `types.ts` 增加 `RepositorySourceVO`、`RepositorySourceRunVO`、`mode`、`gettingStarted`、`alternateSources`、`ProjectVO.primarySource`。
2. `projects.ts` 推荐请求增加 `query`、`sources`。
3. `ProjectView.vue`：
   - 推荐设置增加关键词、来源多选、难度「不限」。
   - 左栏候选增加平台标签与仓库路径。
   - 右栏增加打开仓库、查看 README、许可证、项目概述、从这里开始、为什么推荐给你、预期交付、其他来源。
   - 历史批次显示「历史练习建议」；部分来源失败显示缺失平台；零结果显示调整关键词提示。
   - 计划页标题区增加同一仓库的打开仓库/README/许可证入口。
4. `format.ts` 更新规则化计划阶段文案。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/frontend/src/api/types.ts` | 仓库来源与结果类型 | 契约同步 |
| 修改 | `project/frontend/src/api/projects.ts` | 推荐请求字段 | 搜索筛选 |
| 修改 | `project/frontend/src/views/project/ProjectView.vue` | 双栏仓库 UI、筛选、计划来源 | 页面需求 |
| 修改 | `project/frontend/src/utils/format.ts` | 阶段文案 | 语义一致 |

**执行命令与验证：**

- 命令：`npm run type-check`
- 结果：通过
- 命令：`npm run build`
- 结果：通过（仅原有 chunk 体积告警）

**影响范围：** 项目推荐页与计划页展示；建计划接口与旧数据路径保持兼容。

**遗留问题：** 登录态完整点击链路未实测（见末节）。

---

### [2026-11-18 15:00-15:30] 测试、真实联调与文档

**任务目标：**
覆盖许可证筛选、私有仓库过滤、去重排序、README 缺失、单源故障、全源故障与零结果；验证前端构建；尝试三平台真实返回。

**执行行为：**

1. 新增 `tests/test_project_sources.py`，用三平台响应样例覆盖上述场景，并补充 handler 校验、规则化降级、零候选不调用模型。
2. 更新 `tests/conftest.py` 为 API 集成测试注入仓库检索替身；更新 `tests/test_resume_project.py` 移除旧本地造题断言。
3. 更新后端接口与 UI 文档。
4. 新增 `scripts/verify_repo_sources.py` 并执行真实探测。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `project/agent/tests/test_project_sources.py` | 三平台样例与 handler 用例 | 验收覆盖 |
| 修改 | `project/agent/tests/conftest.py` | 仓库检索替身 | 避免测试触网 |
| 修改 | `project/agent/tests/test_resume_project.py` | 移除旧推荐断言 | 契约变更 |
| 修改 | `docs/功能资料/08-项目实战/项目实战后端接口示例.md` | 实现状态、许可证、V15、失败原因 | 文档同步 |
| 修改 | `docs/功能资料/08-项目实战/项目实战UI与交互说明.md` | 实现状态与详情顺序 | 文档同步 |

**执行命令与验证：**

- 命令：`conda run -n ai-job python -m pytest tests -q --ignore=tests/test_job_search_render.py`
- 结果：通过（1 项 skip）
- 命令：`conda run -n ai-job python scripts/verify_repo_sources.py`
- 结果：GitHub 真实可用；Gitee 未配置令牌时返回 HTTP 200 空数组，GitCode 未配置令牌时返回 HTTP 401。实现中已将两者统一标记为平台不可用，避免静默降级。
- GitHub 模块联调结果：`source_runs=[{"platform":"GITHUB","status":"SUCCEEDED"}]`，返回 6 个候选，均带 SPDX 许可证与 README 地址，例如：
  - `macrozheng/mall`，`Apache-2.0`，README 可确认
  - `spring-projects/spring-boot`，`Apache-2.0`，README 可确认
  - `xkcoding/spring-boot-demo`，`MIT`，README 可确认
- 控制台中文简介乱码为 Windows 终端编码显示问题，接口返回本身为 UTF-8。

**影响范围：** 无新增业务逻辑。

**遗留问题：**

1. **登录态完整链路未执行**：`推荐 → 打开仓库 → 生成计划 → 从计划页打开同一仓库` 需要可用的后端数据库、Agent 服务与测试账号；本次未启动登录态浏览器实测。
2. **Gitee/GitCode 真实返回未完成**：两者均因未配置服务端令牌而不可用（Gitee 匿名返回 200 空数组，GitCode 返回 401）。需在服务端配置 `GITEE_TOKEN`、`GITCODE_TOKEN` 后复跑。
3. **Java 全量测试未执行**：默认 surefire fork 受 JaCoCo/JVM 崩溃影响，仅执行了受影响的 `ProjectResultHandlerTest` 与一次性 V14→V15 迁移验证（同进程方式）；Java 主代码编译通过。
### [2026-11-18 16:15] 修复仓库准入、来源核验与平台故障隔离

**任务目标：** 修复项目实战复测确认的四项缺陷。

**执行行为：**

1. GitHub 显式检索公开仓库，Gitee 和 GitCode 同时核对公开、私有与内部标记，兼容布尔值及 API 返回的 0/1。
2. Agent 与 Java 按平台验证仓库路径，GitCode 支持多级命名空间。
3. 主、备候选各自先验证许可证。跨平台只合并 API 镜像元数据明确指向的候选，缺少镜像证据的同路径仓库分别展示。
4. 将检索响应解析失败转换为所属平台的失败状态，保留其他平台返回的合格仓库。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/app/project_sources.py` | 校验可见性和路径、独立验证许可、按镜像元数据合并、隔离解析故障 | 修复推荐准入及三源降级 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/project/ProjectResultHandler.java` | 支持校验合法的 GitCode 多级路径 | 使已核验候选可以保存和生成计划 |

**执行命令与验证：** 静态核对改动及相关调用点。回归测试和联调结果在后续条目记录。

**影响范围：** 项目实战仓库检索、来源展示与推荐保存。未修改 Flyway 迁移。

**遗留问题：** 待补回归用例及隔离环境联调。

### [2026-11-18 16:19] 补齐四项回归并同步项目实战验收契约

**任务目标：** 让已复现的失败场景纳入持续回归。

**执行行为：** 给 Agent 增加数字可见性标记、GitCode 多级路径、同路径异仓库、备用来源独立许可及异常响应回归。给 Java 增加 GitCode 多级路径和 README 保存用例。更新接口与 UI 的来源核验口径。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/tests/test_project_sources.py` | 增加可见性、镜像与单平台异常响应回归 | 防止本轮缺陷复现 |
| 修改 | `project/backend/src/test/java/com/aijob/backend/project/ProjectResultHandlerTest.java` | 增加 GitCode 多级仓库保存测试 | 覆盖后端校验与持久化路径 |
| 修改 | `docs/功能资料/08-项目实战/项目实战后端接口示例.md`、`项目实战UI与交互说明.md` | 同步来源合并、许可证核验及异常隔离约定 | 对齐当前实际行为 |

**执行命令与验证：**

- `mvn -o -Djacoco.skip=true -DforkCount=0 -Dtest=ProjectResultHandlerTest,ProjectProgressServiceTest test` 通过，9 项。
- 首轮定向 pytest 76 项通过、2 项失败。两项已返回平台失败和其余候选，断言因 GITEE 与 Gitee 大小写不同失败。已调整断言，结果待复跑。

**影响范围：** 项目实战测试与验收资料。

**遗留问题：** 待隔离库升级、登录态浏览器联调和真实平台复测。

### [2026-11-18 16:34] 定向回归通过并处理隔离验收进程残留

**任务目标：** 确认四项修复通过回归，并准备带历史数据的隔离数据库。

**执行行为：** 复跑 Agent、Java 测试并打包本地验收后端。新建临时 MySQL 库，Flyway 已迁到 V14，写入专用测试用户及历史推荐和计划。首轮切换后端时，Windows Java 启动入口留下子进程，导致就绪探测误判。核对端口和命令行后关闭本轮隔离旧进程，改为用 JDK 可执行文件直接启动。

**文件变更：** 项目源码无新增变更。验收辅助脚本和运行日志存放于 Maven 构建目录，不纳入源码。

**执行命令与验证：**

- Agent：`python -m pytest --disable-warnings -p no:cacheprovider tests/test_project_sources.py tests/test_resume_project.py tests/test_operations.py`，78 项通过。
- Java：`mvn -o -Djacoco.skip=true -DforkCount=0 -Dtest=ProjectResultHandlerTest,ProjectProgressServiceTest test`，9 项通过。
- Maven：`mvn -o -q -DskipTests package`，构建成功。首轮隔离验收已应用 V1–V14 并写入历史数据，受旧进程影响未校验 V15，临时库已清理。

**影响范围：** 仅本轮测试库和专用服务端口。

**遗留问题：** 待读取 V15 迁移结果及浏览器链路结果。

### [2026-11-18 16:36] 完成隔离 MySQL 的 V14 到 V15 升级复验

**任务目标：** 验证真实仓库来源迁移可以保留旧推荐、旧计划和任务进度。

**执行行为：** 在本机 MySQL 8.0.42 新建本轮专用库。使用后端打包产物把库迁移至 V14，创建测试用户与历史推荐、候选、计划及任务，重启隔离后端应用 V15。以测试用户登录后读取历史推荐、计划及任务，再经现有 API 完成任务并重读。

**文件变更：** 项目源码和现有迁移脚本无变更；测试辅助文件仍在 Maven 构建目录。

**执行命令与验证：** MySQL 查询确认 Flyway 成功版本从 14 升到 15。历史批次仍为 `LEGACY_IDEA`，仓库来源为 NULL。历史计划详情和任务进度更新均返回 HTTP 200，重读任务为 `COMPLETED`。

**影响范围：** 独立测试库及本轮启动的隔离服务。原有运行实例未重启。

**遗留问题：** 浏览器完整链路仍在执行。

### [2026-11-18 16:41] 完成专用账号链路验收并清理隔离环境

**任务目标：** 验证真实仓库项目从推荐到计划的来源保持一致，补测 Gitee 和 GitCode 的真实接口。

**执行行为：**

1. 用 Playwright 和本机 Chrome 在隔离环境登录专用账号，按 Spring Boot 获取推荐，检查 6 条 GitHub 候选、项目概述、许可证、README 和两平台不可用提示。计划生成使用测试模型，仓库检索为真实请求。
2. 从候选和计划页点击「打开仓库」，均捕获到新标签向同一 GitHub URL 发出导航请求。读取计划 API 确认状态为 ACTIVE 并带有 2 项任务。查询 MySQL，确认候选记录与计划来源快照的 URL 一致。
3. 在单独模拟的浏览器响应中验证 GitCode 多级路径完整展示。记录两张验收截图。关闭本轮隔离服务，删除并确认仅本轮生成的测试库已清理。
4. 只读探测 Gitee、GitCode 实际搜索。现有配置均无服务端令牌。匿名 Gitee 请求超时，GitCode 返回 HTTP 401。

**文件变更：** 追加本过程记录。浏览器截图在验收输出目录，隔离脚本和日志在 Maven 构建目录。无新增项目源代码文件。

**执行命令与验证：**

- `node project/backend/target/project-reacceptance/browser_qa.cjs` 通过。页面与 API 一致，无未捕获前端运行异常。
- GitHub 仓库 API 返回 HTTP 200。Chrome 向仓库网页发出正确的导航请求，网页连接重置或超时，本地网页内容未验证。
- 隔离后端在部分异步任务响应结束时记录 Spring Security 权限异常，两项任务均成功回调，页面和 API 读取正常。
- 隔离 MySQL 校验新推荐与计划来源同址，并确认临时库已清理。

**影响范围：** 项目实战推荐、计划、历史兼容与来源校验。实测使用本轮专用账号和隔离服务。

**遗留问题：** Gitee 和 GitCode 的成功检索仍待服务端配置可用令牌后验证。计划内容使用测试模型，仅验收链路和仓库来源保存。

### [2026-11-18 16:50] 修复历史推荐误标和仓库地址的展示入口

**任务目标：** 让项目实战详情直接显示可点击的仓库地址，并为缺少地址的历史建议提供搜索入口。

**执行行为：** 检查截图对应的兼容分支和本机服务。8080 当前后端进程启动时间早于仓库功能改动，数据库处于 V14，缺少批次来源列。前端原先在批次类型缺失时误标为真实开源项目。改为根据当前候选可用仓库地址决定标签；在标题下显示可点击的完整地址，历史建议在同一位置说明并提供「搜索开源项目」。计划页也显示完整地址，区分读取历史结果和发起新搜索。

**文件变更：** 修改 `project/frontend/src/views/project/ProjectView.vue` 的仓库标签、链接和历史结果提示样式。

**执行命令与验证：** 使用 `Get-NetTCPConnection`、本地 MySQL 只读查询核对服务和 Flyway 版本，无排队或运行中的任务。前端验证待执行。

**影响范围：** 项目实战前端、当前本机开发环境待加载新版服务。

**遗留问题：** 待验证前端检查、真实仓库新批次和本机 V15 状态。

### [2026-11-18 16:53] 本机服务加载项目仓库功能

**任务目标：** 让用户当前使用的本机开发环境具备真实仓库推荐能力。

**执行行为：** 确认 8080 和 8100 启动的是旧版本且数据库无运行中或排队任务。只重启本机 Java 和 Python Agent，前端继续由 Vite 提供。使用现有本机数据库配置，Java 正常应用已在隔离库验证的 V15。

**文件变更：** 无业务源码新增变更。运行日志存放于 Maven 构建目录。

**执行命令与验证：** 前端类型检查通过。Flyway 日志显示成功验证 15 条脚本并应用 V15。数据库确认 V15 成功，17 批历史推荐为 LEGACY_IDEA。前端首页、Agent 健康检查返回 200；前端代理及 Java 的未登录受保护路由返回 401。

**影响范围：** 本机开发服务和仓库来源字段，已有推荐及计划继续可查询。

**遗留问题：** 待验证旧建议提示及新结果的可点击完整地址。

### [2026-11-18 16:54] 复验旧候选提示与真实仓库链接

**任务目标：** 验证用户看到的链接位置与替换历史结果的入口。

**执行行为：** 用 Playwright 打开当前本机 5173 前端。用测试响应覆盖旧接口省略 mode、新版推荐含来源、已保存计划含来源三种情形。同时用当前 Agent 配置发起只读 GitHub 仓库查询。

**文件变更：** 追加项目实战过程记录。浏览器脚本仅保存在 Maven 构建目录。

**执行命令与验证：** Chrome 断言旧候选显示「历史练习建议」和无仓库提示，点击「搜索开源项目」打开设置。新版候选和计划均在标题下显示完整 URL，锚点 href 相同且配置新标签打开。浏览器无未捕获异常和 Vite 错误浮层。真实 GitHub 检索返回 6 个有仓库地址和许可证的候选。

**影响范围：** 本机项目实战推荐与计划显示。

**遗留问题：** 浏览器复验使用测试响应；真实查询单独执行。未在个人账号下新增推荐或计划。

### [2026-11-19 10:20] 推荐链路新增技术栈关键词筛选（接口与检索层）

**任务目标：** 在推荐请求和 `PROJECT_RECOMMEND` 任务输入中增加可选 `techStackKeywords`，并在检索、排序与模型调用前完成确定性技术栈过滤。

**执行行为：**

1. Agent 在 `ProjectRecommendPayload` 增加 `tech_stack_keywords`（最多 5 项、单条最多 64 字符），校验时去除首尾空白、忽略大小写去重，空白词与超长词直接拒绝。
2. `project_sources` 为仓库新增主语言字段；新增关键词归一化匹配，空格、连字符与下划线统一处理并保留 `C++`、`C#` 符号；每个关键词必须命中仓库名称、简介、主题标签或主语言之一。`Java` 因词边界不会命中 `JavaScript`。
3. 检索词构造调整为：填写技术栈时优先使用「项目关键词 + 技术栈」与「技术栈」，再用技能词补足，每个平台仍最多 3 个不同搜索词，且只在已选平台检索。
4. 技术栈过滤发生在许可证核验、排序和模型调用之前，未命中计入 `TECH_STACK_MISMATCH`；筛选后候选为空时直接返回空结果，模型失败降级同样只使用筛选后的候选集。
5. Java `ProjectService` 接收并校验 `techStackKeywords`，拒绝非数组、空白词、超限数组与单条超长输入，归一化后写入任务 payload；任务重试读取 `inputSnapshotJson`，因此沿用同一组关键词。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/agent/app/project_sources.py` | 增加主语言、关键词归一化与确定性匹配、检索词构造、候选过滤与拒绝统计 | 技术栈筛选 |
| 修改 | `project/agent/app/schemas.py` | 增加 `techStackKeywords` 字段与校验 | 统一输入口径 |
| 修改 | `project/agent/app/handlers.py` | 向检索层透传技术栈关键词 | 接入筛选 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/project/ProjectService.java` | 校验并透传 `techStackKeywords` | 接口与重试沿用 |
| 修改 | `project/agent/tests/conftest.py` | 搜索替身接受 `tech_stack_keywords` | 避免回归用例失败 |
| 修改 | `project/agent/tests/test_project_sources.py` | 固定仓库数据覆盖全量匹配、Java/JavaScript、连字符与符号、重复词、空筛选、零命中与模型降级 | 验收 |
| 新增 | `project/backend/src/test/java/com/aijob/backend/project/ProjectServiceTechStackKeywordsTest.java` | 合法透传、去重、类型错误、空白词、超限与超长拒绝 | 接口验收 |
| 新增 | `project/backend/src/test/java/com/aijob/backend/task/TaskRetryServiceTechStackKeywordsTest.java` | 断言新任务 payload 复用原快照中的 `techStackKeywords` | 重试验收 |

**执行命令与验证：**

- 命令：`conda run -n ai-job python -m pytest tests/test_project_sources.py -q -p no:cacheprovider --disable-warnings`
- 结果：通过，26 项。
- 命令：`mvn -o "-Djacoco.skip=true" "-DforkCount=0" "-Dtest=ProjectServiceTechStackKeywordsTest,ProjectResultHandlerTest" test`
- 结果：通过，12 项（新增 6 项，原有 6 项）。
- 命令：`mvn -o "-Djacoco.skip=true" "-DforkCount=0" "-Dtest=ProjectServiceTechStackKeywordsTest,ProjectResultHandlerTest,TaskRetryServiceTechStackKeywordsTest" test`
- 结果：通过，13 项（补充重试用例 1 项）。
- 命令：`conda run -n ai-job python -m pytest tests/test_project_sources.py tests/test_resume_project.py tests/test_operations.py -q -p no:cacheprovider --disable-warnings`
- 结果：通过，89 项。首轮 `test_operations` 两项失败，原因是测试搜索替身不接受新增关键字；修正替身签名后复跑通过。

**影响范围：** 推荐请求体新增可选字段，省略或传空数组时行为不变；既有候选、计划、数据库结构与前端展示字段未变化。

**遗留问题：** 未配置 Gitee/GitCode 令牌，真实三平台同时检索仍未验证；浏览器交互待前端阶段验证。

### [2026-11-19 10:22] 推荐弹窗新增技术栈关键词标签输入与空结果提示

**任务目标：** 在「设置项目推荐」弹窗「项目关键词」下方提供可添加、删除、清空的技术栈关键词标签，并提交到推荐接口。

**执行行为：**

1. 新增标签输入区：按回车添加，输入框提示 `LangGraph`、`FastAPI`，标签可逐个删除，支持一键清空；前端限制最多 5 个、单条最多 64 字符，忽略大小写去重。
2. 添加 `form-grid__full` 与标签输入样式，使技术栈关键词独占一行并位于「项目关键词」下方；「来源平台」仍与「项目关键词」同行，其他表单位置不变。
3. 提交时先收纳输入框中的草稿关键词，再传 `techStackKeywords`；未添加时不传该字段。
4. 推荐完成但结果为空时，标题改为「未找到符合当前筛选条件的开源仓库」，按钮改为「调整筛选条件」并打开同一设置弹窗。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/frontend/src/views/project/ProjectView.vue` | 标签输入、增删清空逻辑、提交字段、空结果文案与样式 | 交互需求 |
| 修改 | `project/frontend/src/api/projects.ts` | 推荐请求体增加 `techStackKeywords` | 接口对接 |
| 修改 | `docs/功能资料/08-项目实战/项目实战后端接口示例.md` | 请求字段、筛选口径与空结果说明 | 文档同步 |
| 修改 | `docs/功能资料/08-项目实战/项目实战UI与交互说明.md` | 技术栈关键词交互与空结果状态 | 文档同步 |

**执行命令与验证：**

- 命令：`npm run type-check`
- 结果：通过。
- 命令：`npm run build`
- 结果：通过，仅既有大 chunk 告警。
- 未执行：登录态下在浏览器逐项验证添加、删除、清空、提交、空结果提示，以及来源平台和目标技能选择回归；本地缺少可用登录账号，未声称已完成。

**影响范围：** 仅推荐设置弹窗与请求体；推荐结果卡片、计划生成与历史数据未变化。

**遗留问题：** 需在真实登录环境补做浏览器交互验证，并使用实际仓库数据确认 `LangGraph` + `FastAPI` 的端到端筛选结果。

### [2026-11-19 10:49] 实现仓库分页检索与技术证据核验

**任务目标：** 扩大有技术栈条件的项目检索，按真实技术证据筛选应用候选。

**执行行为：** 增加分页检索、Spring Boot 别名、依赖与 README 证据分级、README 文件链接、仓库类别排序、每轮检索及内容读取预算。Agent 增加续查游标和已展示仓库标识，最多对首轮前 6 个候选使用模型润色。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/agent/app/project_discovery.py` | 多流搜索、技术证据读取、业务类别排序和跨轮去重 | 扩大应用项目覆盖 |
| 修改 | `project/agent/app/project_sources.py` | 平台适配器接受页码、排序和返回下一页 | 复用现有搜索入口 |
| 修改 | `project/agent/app/handlers.py`、`project/agent/app/schemas.py` | 增加续查输入输出及证据，合并模型和规则结果 | 支持完整候选返回 |

**执行命令与验证：** `python -m compileall -q` 对上述 Python 文件执行通过。固定数据与真实接口验证待执行。

**影响范围：** 项目推荐 Agent。后端与前端尚待接入。

**遗留问题：** 续查批次还未持久化，浏览器尚未验证。

---

### [2026-11-19 10:52] 接入后端续查任务和结果保存

**任务目标：** 保存查找进度，使同一推荐可追加结果并支持刷新恢复。

**执行行为：** 增加续查接口，以原批次条件创建子任务，使用批次锁和唯一索引避免同一页重复创建。保存游标、仓库标识和逐项目证据。推荐详情汇总已完成批次，保留未完成续查任务的 ID。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/src/main/resources/db/migration/V16__project_recommendation_discovery.sql` | 增加续查批次关联及搜索状态 | 恢复与去重 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/project/ProjectController.java`、`ProjectService.java`、`ProjectMapper.java`、`ProjectResultHandler.java`（同目录） | 接口、批次操作、回调校验和结果聚合 | 接入既有任务流程 |

**执行命令与验证：** Java 编译和测试待执行。

**影响范围：** 新的技术栈推荐及同批续查。普通推荐继续使用现有接口。

**遗留问题：** 前端入口与自动化验收待接入。

---

### [2026-11-19 10:53] 增加加载更多和证据展示

**任务目标：** 让用户浏览更多项目，查看技术核验依据，并在刷新后恢复续查状态。

**执行行为：** 首屏渲染 12 项，使用已有任务组件跟踪后续批次；先展开已取回结果，再续查平台。候选列表标出核验状态，详情链接到证据文件。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/api/projects.ts`、`project/frontend/src/api/types.ts` | 续查请求与结果类型 | 接入后端接口 |
| 修改 | `project/frontend/src/views/project/ProjectView.vue` | 加载更多、核验标签、证据链接、恢复未完成批次 | 支持连续浏览 |

**执行命令与验证：** 已启动前端 `npm run type-check` 和定向 Maven 测试，等待结束。

**影响范围：** 项目实战推荐结果页。

**遗留问题：** 真实搜索、浏览器和续查用例待验证。

---

### [2026-11-19 11:01] 补充证据排序、限流提示与续查验收

**任务目标：** 验证多页检索的结果约束和浏览器交互，并修正真实拉取中的限流反馈。

**执行行为：** 增加文件接口限流提示、同一镜像分组优先选择证据更强的仓库、累计结果优先展示已核验项目、已取消续查的重试入口。增加仓库搜索、证据分级、续查回显及模型降级回归用例，更新项目实战接口与交互说明。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/agent/tests/test_project_discovery.py` | Spring Boot 别名、两轮去重、部分平台限流、README 分级用例 | 覆盖检索行为 |
| 修改 | `project/agent/app/project_discovery.py`、`project/agent/app/handlers.py`、`project/agent/app/skills/tools.py`、`project/agent/app/skills/prompts/project_recommend.md` | 修正证据读取、排序、镜像选择和模型使用证据的约束 | 保护筛选结果 |
| 修改 | `project/agent/tests/test_project_sources.py` | 增加模型失败或续查时保留全部合格候选的用例 | 验证结果不受模型条数限制 |
| 修改 | `project/backend/src/test/java/com/aijob/backend/project/ProjectServiceTechStackKeywordsTest.java`、`project/backend/src/main/java/com/aijob/backend/project/ProjectService.java`、`project/backend/src/main/java/com/aijob/backend/task/TaskRetryService.java` | 测试续查复用及刷新，聚合证据状态，允许重试已取消续查 | 恢复同一次搜索 |
| 修改 | `project/frontend/src/views/project/ProjectView.vue` | 来源局部故障提示和续查重试 | 展示部分可用结果 |
| 修改 | `docs/功能资料/08-项目实战/项目实战后端接口示例.md`、`docs/功能资料/08-项目实战/项目实战UI与交互说明.md` | 更新搜索证据与续查接口和交互 | 文档与实现同步 |

**执行命令与验证：** `npm run type-check` 通过；首次 Maven 沙箱动态附加 Mockito 卡住，显式加载 Mockito Java agent 后 16 条定向测试通过；允许本地回环后定向 pytest 首轮 40 条通过；Chrome 使用模拟 API 验证首屏、加载更多、证据链接和刷新恢复通过。真实 GitHub 前两页搜索请求成功但未得到合格候选，诊断随后收到 HTTP 429。新增测试和真实搜索待复测。

**影响范围：** 技术栈推荐及续查。验证使用独立临时前端和测试响应。

**遗留问题：** 待确认 GitHub 真实结果及数据库迁移，Gitee、GitCode 当前无服务端令牌。

---

### [2026-11-19 11:11] 完成真实 Spring Boot 复测和迁移验证

**任务目标：** 验证最新搜词、技术证据、去重以及本机使用条件。

**执行行为：** 匿名 GitHub 文件核验优先使用公开文件地址，连接异常可回退到仓库内容接口。增加 Spring Boot 主题检索，过滤实测发现的教程合集和 starter，完善 Gradle 模块识别。读取证据限流或超时时保留相应游标，续查可以重试该页。通过本机已有代理独立执行两轮公开搜索。使用临时 MySQL 库复核迁移 SQL，并在检查后删除临时库。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/app/project_discovery.py` | 公开文件备用读取、Spring Boot 主题和多种查询、教程规则、核验中断保留游标 | 提高真实搜索质量和可恢复性 |
| 修改 | `project/agent/tests/test_project_discovery.py` | 补充无令牌读取、教程排除和文件限流用例 | 覆盖实测问题 |
| 修改 | `docs/功能资料/08-项目实战/项目实战后端接口示例.md` | 记录进程代理和来源令牌配置 | 复现验证环境 |

**执行命令与验证：**

- 定向 Agent pytest，`python -m pytest -p pytest_asyncio.plugin -o addopts='' -q tests/test_project_discovery.py tests/test_project_sources.py tests/test_resume_project.py`，48 条通过。
- Maven 定向运行 `ProjectResultHandlerTest,ProjectServiceTechStackKeywordsTest,TaskRetryServiceTechStackKeywordsTest`，使用 Mockito Java agent 及 `-DforkCount=0 -Djacoco.skip=true`，16 条通过。
- `npm run type-check` 通过。会话临时 Vite 配合 Playwright/Chrome 模拟 API，验证首批 12 张卡片、展开本地结果、异步续查、证据链接、刷新恢复，以及续查候选的生成计划请求。临时 Vite 验收服务已停止。
- MySQL 探测首次漏跑依赖脚本，第二次缺少 V13 依赖的 Flyway 历史表。第三次在隔离库补齐该历史数据后顺序执行 16 份迁移 SQL，V16 4 个字段和批次唯一索引通过检查，各次临时库均清理。该验证直接执行 SQL。
- 真实 GitHub 两轮依次返回 5 项、6 项，共 11 个不同仓库，均找到构建文件证据。涉及工作流、微服务配置管理、电商、财务等。直连内容 API 曾限流、公开文件域名直连重置；复测只在独立验收进程启用已有代理。
- `git diff --check` 无空白错误，Git 报告若干既有换行符提示。

**影响范围：** 技术栈推荐、项目实战列表及计划入口、续查存储 V16。

**遗留问题：** Gitee 和 GitCode 缺少服务端令牌，未做两平台真实检索。浏览器使用模拟任务响应，未在个人账号发起实战计划。当前运行实例未切换，本机数据库未应用 V16；迁移验证仅使用隔离库。

---

### [2026-11-21 12:52] 复现 GitHub 核验限流并启用本机代理

**任务目标：** 恢复项目实战的开源仓库推荐，确认各来源令牌是否必要。

**执行行为：**

1. 检查三种令牌的本地配置，只输出存在状态，确认当前根目录配置和进程环境均未提供令牌。
2. 使用匿名 HTTP 请求分别探测 GitHub 搜索、公开文件和 API 限额。直连搜索返回 200，直连公开文件 TLS 握手超时，直连 REST 核心额度为 0。使用本机现有代理时，搜索和公开文件均返回 200。
3. 在本地根目录配置中启用仓库检索使用进程代理。规范写死的 `D:/vscode/AI-JOB` 路径不存在，过程日志追加到当前工作树同功能的既有日志。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `.env`（Git 已忽略） | 增加 `AGENT_REPOSITORY_SEARCH_TRUST_ENV=true` | 让本机推荐检索能够访问 GitHub 公开证据文件 |

**执行命令与验证：**

- 命令：Python `httpx.get` 分别以 `trust_env=False` 和 `trust_env=True` 请求 GitHub REST 限额、公开搜索及公开 POM 文件。
- 结果：代理路径公开文件请求通过，直连路径公开文件超时。完整推荐与运行实例待验证。

**影响范围：** 本机 Agent 项目实战仓库搜索与文件核验使用进程代理。

**遗留问题：** Gitee 与 GitCode 尚需服务端令牌，GitHub 无令牌的完整检索待复测。

---

### [2026-11-21 12:53] 修正仓库核验中断时的空结果提示

**任务目标：** 让用户识别截图中的来源核验问题，并直接按原条件恢复检索。

**执行行为：**

1. 当推荐已返回、没有候选且成功连接的来源报告中断时，列表和详情区显示核验未完成。
2. 游标仍可续查时显示「按原条件继续检索」，复用已有续查接口和任务状态。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/views/project/ProjectView.vue` | 根据来源状态区分核验中断、真实空结果及任务失败 | 避免把来源异常误报成无符合条件的项目 |

**执行命令与验证：**

- 命令：使用 `apply_patch` 更新项目实战组件。
- 结果：代码已修改，前端类型检查及页面交互验证待执行。

**影响范围：** 项目推荐的空结果状态和已有续查入口。

**遗留问题：** 有待验证真实 GitHub 推荐以及浏览器中断提示和续查。

---

### [2026-11-21 12:55] 验证代理配置下的真实 GitHub 推荐

**任务目标：** 确认当前环境无需 GitHub 令牌就能完成技术栈核验。

**执行行为：**

1. 使用 Agent 的实际 Python 环境和根目录配置执行 Spring Boot 推荐。首次 HTTP 客户端初始化因当前终端继承 SOCKS `ALL_PROXY`、Agent 现有环境没有 `socksio` 而失败。
2. 保留已验证的 `HTTP_PROXY`、`HTTPS_PROXY` 和 `NO_PROXY`，仅在验收子进程排除通用 `ALL_PROXY` 后重试。
3. 确认匿名 GitHub 连通且返回 6 个候选，抽查前 5 个均有 POM 或 Gradle 证据。Gitee 报缺少令牌。

**文件变更：** 无新增代码改动。

**执行命令与验证：**

- 命令：在 `project/agent` 用项目 Python 调用 `Settings()` 与 `discover(..., ['GITHUB','GITEE'], ['Spring Boot'], ...)`。
- 结果：首次初始化失败，调整仅对子进程生效的代理环境后真实仓库检索通过。
- 命令：`npm.cmd run type-check`。结果：通过。
- 命令：定向运行三份 Agent 测试。首次因 `pytest_asyncio` 已自动加载又手工启用而失败，已去掉重复参数重跑。

**影响范围：** 验证使用公开仓库请求，未向个人账号写入推荐。

**遗留问题：** 重启正在运行的 Agent，核对健康状态并完成浏览器交互验收。

---

### [2026-11-21 12:55] 重启 Agent 并检查服务健康

**任务目标：** 让正在使用的本地 Agent 载入仓库检索代理配置。

**执行行为：** 通过进程路径、启动入口和监听端口确认目标实例。重启前以只读方式查询 Agent SQLite 任务状态汇总，查到的记录均已终止。保留可用的 HTTP/HTTPS 代理及回环豁免，只在新进程的启动环境中移除 SOCKS `ALL_PROXY`，随后使用相同的 Python 和入口拉起 Agent。

**文件变更：** 无代码改动。启动输出写入临时日志目录。

**执行命令与验证：**

- 命令：`Stop-Process` 停止旧实例，`Start-Process` 启动 `D:/Anaconda/envs/ai-job/python.exe D:/py/AI-JOB/project/agent/run.py`。
- 结果：新实例监听 8100，`/healthz` 返回 `ok`，`/readyz` 返回 `ready`。

**影响范围：** 本地 Agent 运行实例。

**遗留问题：** 浏览器交互待验证，Gitee 和 GitCode 令牌待配置。

---

### [2026-11-21 12:58] 验证续查交互与正常空结果

**任务目标：** 验证限流提示和续查操作，确认正常空结果仍可调整条件。

**执行行为：** 用独立 Chrome 会话和模拟接口加载历史推荐。先验证 GitHub 文件核验 403 场景，点击「按原条件继续检索」，断言续查请求携带原批次 ID，并确认页面出现返回的候选。随后测试 GitHub 正常无候选且 Gitee 未配置时的空结果。Browser 技能当前未提供，使用本机已有 Playwright 与 Chrome。

**文件变更：** 无新项目文件。浏览器截图保存在系统临时目录。

**执行命令与验证：**

- 命令：Chrome 以 Playwright 无头模式访问本地 Vite。首次 5183 端口连接超时，改用探测通过的 5184 端口。结果：中断界面、原批次续查 POST、结果回显及正常空结果通过，无浏览器错误或 Vite 错误层。
- 命令：`python -m pytest -o addopts= -q tests/test_project_discovery.py tests/test_project_sources.py tests/test_resume_project.py`（加载已有 `.testdeps`，允许本机回环）。结果：53 条通过。
- 命令：`git -c core.fsmonitor=false diff --check -- project/frontend/src/views/project/ProjectView.vue`。结果：无空白错误，Git 提示该工作区文件未来可能转换为 CRLF。

**影响范围：** 项目实战空结果与续查体验。浏览器使用模拟账号和模拟接口。

**遗留问题：** 用户原账号的历史推荐没有代为提交续查。Gitee 和 GitCode 尚未配置令牌，因而未验证真实结果。

---

### [2026-11-21 13:00] 尝试为本机项目实战配置 Gitee 私人令牌

**任务目标：** 创建 Gitee 私人令牌，保存到本机 Agent 使用的配置并验证检索。

**执行行为：** 检查项目根目录及 Agent 可用配置的 `GITEE_TOKEN` 存在状态。尝试用浏览器打开 Gitee 私人令牌设置，浏览器的站点访问审批被拒绝，未继续创建。

**文件变更：** 本次未改动令牌配置或代码，仅追加本记录。

**执行命令与验证：** 浏览器打开 `https://gitee.com/profile/personal_access_tokens`。结果：访问被拒绝，未创建令牌，未执行鉴权检索。

**影响范围：** 本机 Agent 未新增 Gitee 凭据。

**遗留问题：** 需获得浏览器对 Gitee 的访问权限，或由用户生成令牌后通过本机剪贴板交接。

---

### [2026-11-21 13:03] 写入本机 GitHub 检索令牌

**任务目标：** 按用户本次要求先保存 GitHub 令牌。

**执行行为：** 确认项目根目录的 `.env` 被 Git 忽略且没有现存的 `GITHUB_TOKEN` 定义。追加新的配置项，保留其他配置内容，不输出令牌值。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `.env`（Git 已忽略） | 添加 `GITHUB_TOKEN` | 为 Agent 的 GitHub 仓库检索提供认证配置 |

**执行命令与验证：** 使用 Node.js 文件接口追加并回读确认；使用项目 Python 加载 `Settings` 验证配置仅输出布尔结果。

**影响范围：** 后续启动的本机 Agent 可以读取 GitHub 令牌。

**遗留问题：** 本次仅写入配置。运行中的 Agent 未重启，GitHub 鉴权请求待验证。

---

### [2026-11-21 13:05] 重启本机 Agent 并验证 GitHub 令牌与推荐

**任务目标：** 让运行中的 Agent 加载已保存的 GitHub 令牌，并验证鉴权与实际项目仓库检索。

**执行行为：**

1. 核对 8100 监听进程和工作区入口，只读查询发现重启前 Agent 的任务均已结束。
2. 在项目 Python 环境读取同一份 Agent 配置，向 GitHub 验证令牌并通过实际检索流程搜索 Spring Boot 项目，验证输出只包含状态、统计和公开仓库名称。
3. 初次组合命令在执行前被工具拒绝，复核旧进程仍健康。改为分开执行实例重启和健康检查，保留本机可用的 HTTP/HTTPS 代理，并仅在新进程启动环境中排除 SOCKS 默认代理。

**文件变更：** 无代码或配置变更。追加本记录，服务输出写入系统临时目录。

**执行命令与验证：**

- 命令：使用项目 Python 调用 `GET https://api.github.com/user` 和项目 `discover` 函数检索 Spring Boot 仓库。结果：鉴权为 HTTP 200，3 次搜索均为 HTTP 200，25 次带令牌的文件读取返回 HTTP 200。7 个候选中 6 个通过构建文件核验。
- 命令：停止已确认的旧 Agent，使用原 `project/agent/run.py` 入口启动。结果：新进程监听 8100，`/healthz` 为 `ok`，`/readyz` 为 `ready`。

**影响范围：** 本地 Agent 的运行实例和 GitHub 项目实战推荐。

**遗留问题：** 本次通过实际 Agent 检索函数验收，未在用户账号提交页面任务。Gitee 与 GitCode 未参与本轮验证。

---

