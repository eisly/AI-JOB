# 专项训练模块开发过程记录

本文件记录「专项训练」模块改造过程中的文件变更、执行命令、验证结果与遗留问题。

---

### [2026-11-12 10:05] 现状梳理与方案确定

**任务目标：**
让用户在简历解析完成后，即使尚未完成岗位匹配，也能从「专项训练」页面直接生成与自身背景相关的训练计划，并保留历史计划、展示计划依据、约束 GitHub 推荐来源。

**执行行为：**

1. 只读梳理前端 `TrainingView.vue`、`api/learning.ts`、`api/types.ts`、`api/task-registry.ts`，后端 `learning` 包、任务中心、简历/画像/岗位匹配模块，Agent 的 `schemas.py`、`handlers.py`、`graphs.py`、`skills/prompts`，以及 `V1` 数据库结构与现有文档。
2. 确认现有链路只支持「岗位匹配条目 → 创建计划」，计划表无来源字段，计划项无分类与训练要素字段，Agent 计划输入不含简历、历史训练与 GitHub 候选。
3. 确定方案：新增 `RESUME` 计划来源与 `/api/v1/learning/plans/from-resume`，复用任务中心、幂等、鉴权与 Agent `LEARNING_PLAN` operation；扩展数据库字段与 Agent 输入输出契约；GitHub 由 Agent 真实检索公开 API，模型只能从候选选择。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 只读 | `project/frontend/src/views/training/TrainingView.vue` 等 | 无 | 梳理现状 |

**执行命令与验证：**

- 命令：`git status --short`
- 结果：通过
- 说明：工作区已有大量岗位搜索相关未提交改动，本次保留未覆盖。

**影响范围：**
仅完成方案确认，未修改文件。

**遗留问题：**
简历解析事实中没有结构化项目列表，需从 `resume_version.extracted_text` 原文中提取并约束引用。

---

### [2026-11-12 10:12] 数据库迁移 V8

**任务目标：**
为计划来源、简历版本、计划依据、计划项分类与训练要素提供存储结构，并支持计划历史版本。

**执行行为：**

1. 新增 Flyway 迁移 `V8__extend_learning_plan_sources.sql`。
2. `learning_plan` 增加 `source`、`resume_version_id`、`target_direction`、`supersedes_plan_id`、`generation_no`、`basis_json`，将 `match_item_id` 改为可空并重建外键。
3. `learning_plan_item` 增加 `category`、`objective`、`reason`、`method`、`completion_criteria`、`evidence_status`、`evidence_quote`、`resource_links_json`，并增加分类与证据状态 CHECK 约束。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `project/backend/src/main/resources/db/migration/V8__extend_learning_plan_sources.sql` | 计划与计划项字段扩展、外键与约束 | 支持简历来源与历史版本 |

**执行命令与验证：**

- 命令：在临时库 `career_v8_check_20261112103256` 导入现有 8 张相关表结构后执行 V8 脚本，并查询 `information_schema.COLUMNS`
- 结果：通过
- 说明：`learning_plan` 出现 `source/resume_version_id/target_direction/supersedes_plan_id/generation_no/basis_json`；`learning_plan_item` 出现 `category/objective/reason/method/completion_criteria/evidence_status/evidence_quote/resource_links_json`。

**影响范围：**
现有 `MATCH` 来源计划默认 `source='MATCH'`，旧接口兼容。

**遗留问题：**
本机 `career_business` 仍为 V7，因 8080 端口已有旧实例运行，本次未直接迁移生产库；临时验证库 `career_v8_check_20261112103256` 因安全策略禁止删库而保留，可人工清理。

---

### [2026-11-12 10:20] Java 后端：简历直建计划与结果校验

**任务目标：**
新增基于简历生成计划接口，扩展计划 VO 与结果处理校验，修复无效评价错误完成计划项的问题。

**执行行为：**

1. `LearningService` 新增 `createPlanFromResume`：校验可用简历版本（`PARSED`/`CONFIRMED`）、目标方向、每日投入与周期；支持可选参考岗位匹配；组装简历事实、原文、训练历史与限制说明；幂等创建任务与计划占位行。
2. `LearningController` 新增 `POST /api/v1/learning/plans/from-resume`。
3. `LearningMapper` 扩展 `insertPlan`、`insertItem`、`updatePlanBasis`、计划代际与训练历史统计查询。
4. `LearningResultHandler` 校验计划项分类、训练要素、待评估状态、简历项目原文引用、GitHub 候选链接白名单，并将 GitHub 状态写入计划依据。
5. 修复无效评价（`INSUFFICIENT`/`INVALID`）仍将计划项置为完成的问题：仅 `VALID` 评价完成计划项。
6. `ensureNoActive` 的 409 冲突补充 `details.sessionId`，便于前端打开已有会话。
7. `ProfileService` 暴露支持的目标方向集合；`ResumeMapper` 增加可用简历版本查询。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/backend/src/main/java/com/aijob/backend/learning/LearningService.java` | 新增简历计划创建、payload 组装、计划 VO 扩展、409 会话 ID | 核心业务实现 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/learning/LearningController.java` | 新增 `POST /plans/from-resume` | 暴露接口 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/learning/LearningMapper.java` | 扩展 SQL 与统计查询 | 新字段落库与依据展示 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/learning/LearningResultHandler.java` | 结果校验、依据回写、无效评价修复 | 真实性与正确性约束 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/profile/ProfileService.java` | 暴露 `roles()` | 目标方向校验复用 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/resume/ResumeMapper.java` | 新增可用版本查询 | 简历计划输入 |

**执行命令与验证：**

- 命令：`mvn -q -DskipTests compile`
- 结果：通过
- 说明：首次编译发现并修复 `ResumeContext.skills()` 与 `Map<?,?>.getOrDefault` 类型问题。

**影响范围：**
新增接口；旧岗位匹配创建计划流程保留；计划 VO 新增字段向后兼容。

**遗留问题：**
Java 回调未额外执行 GitHub 链接外网复核，真实性依赖 Agent 候选来源标记 + URL 白名单；可后续按配置开启服务端复核。

---

### [2026-11-12 10:24] Agent：简历输入、GitHub 真实检索与输出校验

**任务目标：**
让 Agent 计划能力支持简历来源、训练历史与真实 GitHub 候选，并禁止模型编造仓库与链接。

**执行行为：**

1. 扩展 `schemas.py`：新增 `ResumeSnapshotInput`、`TrainingHistoryInput`、`GitHubCandidate`、`GitHubLookup`、`ResourceLink`；`PlanItem` 增加分类、目标、原因、方式、完成标准、证据状态、原文引用与资源链接；`LearningPlanPayload` 增加 `planSource`、`resume`、`targetDirection`、`trainingHistory`、`limitations`。
2. 新增 `app/learning_github.py`：调用 GitHub 公开检索 API，过滤归档/无描述/低星仓库，缓存 1 小时；失败返回 `UNAVAILABLE` 并给出原因。
3. `LearningPlanHandler.prepare` 在 `RESUME` 来源下真实检索候选；`validate_business` 校验简历项目引用必须来自 `projectsText`、GitHub 链接必须属于候选、待评估仅限简历来源。
4. 重写 `learning_plan.md` 为 `learning-plan-v3`，明确三类训练内容、待评估与 GitHub 降级规则。
5. 更新 `skills/tools.py` 模型输入裁剪与 `fake_model.py` 的确定性输出。
6. 更新 `tests/test_handlers.py`，新增简历项目、GitHub 候选、岗位来源禁止待评估等用例。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `project/agent/app/learning_github.py` | GitHub 公开检索与缓存 | 真实仓库来源 |
| 修改 | `project/agent/app/schemas.py` | 计划输入输出模型扩展 | 契约扩展 |
| 修改 | `project/agent/app/handlers.py` | 计划 handler 检索与校验 | 真实性约束 |
| 修改 | `project/agent/app/skills/prompts/learning_plan.md` | 重写提示词至 v3 | 规范化提示词 |
| 修改 | `project/agent/app/skills/tools.py` | 模型输入扩展 | 贯通新字段 |
| 修改 | `project/agent/app/operations.py` | 提示词版本改为 `learning-plan-v3` | 版本一致性 |
| 修改 | `project/agent/app/config.py` | 新增 GitHub 检索配置 | 可配置与降级 |
| 修改 | `project/agent/app/fake_model.py` | 假模型输出新字段与三类训练 | 本地/测试可运行 |
| 修改 | `project/agent/tests/test_handlers.py` | 新增 4 个计划校验用例 | 测试覆盖 |

**执行命令与验证：**

- 命令：`conda run -n ai-job python -m pytest tests/test_handlers.py tests/test_misc.py tests/test_operations.py -q`
- 结果：通过（129 项）
- 命令：`conda run -n ai-job python -m pytest tests -q --ignore=tests/test_job_search_render.py`
- 结果：通过
- 命令：`conda run -n ai-job python -c "... search_github_repositories(...)"`
- 结果：通过
- 说明：GitHub 检索真实返回 `AVAILABLE`，8 个候选，首个为 `https://github.com/iluwatar/java-design-patterns`。

**影响范围：**
`LEARNING_PLAN` 输出新增必填训练要素；旧任务若仍在运行可能因缺少字段失败，新任务均使用 v3 提示词。

**遗留问题：**
GitHub 未配置 Token 时受公开接口限流影响，检索失败会降级但不阻塞计划生成。

---

### [2026-11-12 10:30] 前端：生成入口、依据展示、深链与顶栏遮挡

**任务目标：**
在专项训练页面直接生成简历计划，展示计划依据与训练要素，支持无简历引导与历史版本，并修复右上角遮挡。

**执行行为：**

1. `TrainingView.vue` 页头新增「生成训练计划」入口与生成对话框（目标方向、每日投入、周期、可选参考岗位）。
2. 无可用简历时展示引导并可跳转简历中心；生成任务复用 `useTask` 展示加载、成功、失败与重试。
3. 计划列表展示来源、目标方向、代际、生成时间；详情展示简历版本、是否参考岗位、限制说明、GitHub 数据状态与获取时间。
4. 计划项展示分类、训练目标、推荐原因、练习方式、完成标准、待评估状态、简历原文依据与 GitHub 链接。
5. 支持 `planItemId` 深链定位所属计划。
6. 修复顶栏遮挡：`MainLayout` 内容区统一顶部留白 64px，移除仅岗位页生效的 `:has()` 特例。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/frontend/src/views/training/TrainingView.vue` | 生成入口/对话框/依据/训练要素/深链 | 功能实现 |
| 修改 | `project/frontend/src/api/types.ts` | 计划与计划项类型扩展 | 契约同步 |
| 修改 | `project/frontend/src/api/learning.ts` | 新增 `createPlanFromResume` | 接口调用 |
| 修改 | `project/frontend/src/layouts/MainLayout.vue` | 统一顶部留白 | 修复右上角遮挡 |

**执行命令与验证：**

- 命令：`npm run type-check`
- 结果：通过
- 命令：`npm run build`
- 结果：通过（存在既有 chunk 体积警告）
- 说明：构建产物已更新到 `dist`。

**影响范围：**
专项训练页面交互与展示；所有页面内容区顶部增加 40px 留白以避开固定顶栏。

**遗留问题：**
尚未在真实浏览器中进行页面截图复核，需在服务启动后用实际账号验证加载、失败、重试与遮挡修复效果。

---

### [2026-11-12 10:34] Java 单元测试

**任务目标：**
覆盖无简历引导、简历计划 payload、无效评价不完成计划项、简历项目与 GitHub 链接校验。

**执行行为：**

1. 新增 `LearningServiceResumePlanTest`：无可用简历返回 409 `RESUME_REQUIRED`；有简历时 payload 含 `planSource=RESUME`、目标方向、技能与训练历史，并写入来源与代际。
2. 新增 `LearningResultHandlerTest`：无效评价不调用 `completePractice`；简历项目编造引用被拒绝；GitHub 链接超出候选被拒绝。
3. 更新 `LearningServiceTest` 适配新增 `ResumeMapper` 构造参数。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `project/backend/src/test/java/com/aijob/backend/learning/LearningServiceResumePlanTest.java` | 2 个用例 | 简历计划覆盖 |
| 新增 | `project/backend/src/test/java/com/aijob/backend/learning/LearningResultHandlerTest.java` | 3 个用例 | 结果校验覆盖 |
| 修改 | `project/backend/src/test/java/com/aijob/backend/learning/LearningServiceTest.java` | 构造参数与导入 | 适配新依赖 |

**执行命令与验证：**

- 命令：`mvn -q "-Dtest=LearningServiceResumePlanTest,LearningResultHandlerTest,LearningServiceTest,EvaluationServiceTest" test`
- 结果：通过
- 说明：9 个用例全部通过。

**影响范围：**
仅测试代码。

**遗留问题：**
未执行全量 Java 测试（部分测试依赖 3307 测试库，当前未启动）。

---

### [2026-11-12 10:36] 验证范围说明

**任务目标：**
如实记录已执行与未执行的验证。

**执行行为：**

1. 已执行：Java 编译、专项训练 Java 单测、Agent 全量测试（排除依赖 Playwright 浏览器的 render 测试）、GitHub 真实检索、前端类型检查与构建、V8 迁移临时库执行。
2. 未执行：启动完整前后端与 Agent 的真实页面联调。原因：8080 端口已有旧后端实例占用，8100 Agent 未启动，重启现有服务与应用 V8 到业务库存在中断风险；未获得可安全操作的测试账号与简历上传样本。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 无 | - | - | - |

**执行命令与验证：**

- 命令：端口探测 `3306/3307/8080/8100`
- 结果：3306 与 8080 可用，3307 与 8100 未监听
- 说明：现有 8080 实例仍连接 V7 业务库。

**影响范围：**
无代码影响。

**遗留问题：**
1. 需重启后端使 Flyway V8 应用到 `career_business`。
2. 需启动 Agent 与前端后进行真实 UI 联调：无简历引导、有简历无岗位、结合岗位生成、失败重试、历史计划保留、GitHub 不可用降级。
3. `tests/test_job_search_render.py` 3 个失败为既有 Playwright 浏览器依赖缺失，与本次改动无关。
4. 临时验证库 `career_v8_check_20261112103256` 需人工清理。
---

### [2026-11-12 10:40] Java 全量测试结果

**任务目标：**
确认本次改动未引入模块外回归。

**执行行为：**

1. 执行后端全量测试 `mvn -q test`。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 无 | - | - | - |

**执行命令与验证：**

- 命令：`mvn -q test`
- 结果：失败（环境限制）
- 说明：`Tests run: 141, Failures: 0, Errors: 18, Skipped: 30`。18 个错误全部来自 `AuthFlowIntegrationTest`，原因是该集成测试固定连接 `jdbc:mysql://127.0.0.1:3307/career_business_test`，而本机 3307 测试库未启动，ApplicationContext 加载失败；与本次专项训练改动无关。本次新增的 `LearningServiceResumePlanTest`、`LearningResultHandlerTest`、`LearningServiceTest`、`EvaluationServiceTest` 均已通过。

**影响范围：**
无。

**遗留问题：**
如需全量后端测试通过，需先启动 3307 测试库；该环境准备不在本次任务范围内。
### [2026-11-13 16:26:24] 提示训练计划使用旧画像

**任务目标：** 用户修改求职方向或画像后，明确识别旧训练计划。

**执行行为：**

1. 训练页加载当前画像，与计划记录的画像版本和目标方向比较。
2. 在旧计划卡片及详情显示更新提醒，并提供基于当前画像新建计划的入口。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/views/training/TrainingView.vue` | 增加当前画像比较和旧计划提示 | 避免旧训练内容被当作当前目标计划 |
| 修改 | `docs/功能资料/07-专项训练/专项训练_process.md` | 追加本记录 | 跟踪开发行为 |

**执行命令与验证：**

- 命令：修改页面代码。
- 结果：代码修改完成，类型检查和运行验证待执行。

**影响范围：** 专项训练计划列表与详情。

**遗留问题：** 新计划与旧计划的保留/替换流程仍需产品层确认；当前实现仅提醒并使用已有创建入口。

---

### [2026-11-13 16:27:18] 验证搜索与训练页面类型

**任务目标：** 检查新空态和旧计划提醒的前端类型。

**执行行为：**

1. 运行前端 `vue-tsc --noEmit`。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/岗位推荐与投递_process.md` | 追加类型检查结果 | 记录验证 |
| 修改 | `docs/功能资料/07-专项训练/专项训练_process.md` | 追加同一验证结果 | 记录验证 |

**执行命令与验证：**

- 命令：`npm.cmd run type-check`（`project/frontend`）。
- 结果：通过，退出码 0。
- 说明：本次尚未对搜索空态和旧计划提醒进行浏览器交互验收。

**影响范围：** 前端静态类型。

**遗留问题：** 需要基于真实搜索结果与不同画像版本完成页面回归。

---
### [2026-11-13 17:11:21] 刷新旧训练计划的画像与简历依据

**任务目标：** 页面保持打开时，用户变更画像或简历后仍能发现旧计划依据已过期。

**执行行为：**

1. 训练页切换页签或刷新列表时重新读取当前画像；刷新计划详情时也同步读取。
2. 旧计划提示加入画像版本、目标方向和活动简历版本变化，并允许继续查看旧计划或生成新计划。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/views/training/TrainingView.vue` | 更新画像刷新路径与计划过期原因展示 | 防止旧计划在目标变化后静默呈现 |
| 修改 | `docs/功能资料/07-专项训练/专项训练_process.md` | 追加本记录 | 跟踪开发行为 |

**执行命令与验证：**

- 命令：读取训练页和计划版本字段后修改前端文件。
- 结果：代码已修改，类型检查与浏览器回归待执行。

**影响范围：** 专项训练计划列表和详情的过期提醒，不改变已有计划或进度数据。

**遗留问题：** 需要使用有旧计划的账号验证真实提醒文案。

---
### [2026-11-13 17:12] 验证训练页类型

**任务目标：** 确认训练页刷新与过期提示改动可通过前端类型检查。

**执行行为：** 运行前端 `vue-tsc`。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/07-专项训练/专项训练_process.md` | 追加验证结果 | 跟踪实际结果 |

**执行命令与验证：**

- 命令：`npm.cmd run type-check`。
- 结果：退出码 0。

**影响范围：** 前端静态类型验证。

**遗留问题：** 未做有旧计划账号的浏览器回归。

---
