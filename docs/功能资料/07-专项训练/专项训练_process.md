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

### [2026-11-16 11:33] 专项训练改版：批量出题契约、API 层、答题页与评估弹窗（阶段一）

**任务目标：**
按 3 张原型图将专项训练从「单题逐次生成」改版为「一次预生成一组题目 + 独立答题窗口 + AI 评估加载弹窗」，第一批完成数据契约、API 封装与独立答题页。

**执行行为：**

1. 梳理现有实现：TrainingView.vue（单题会话内联答题）、pi/learning.ts、pi/types.ts、useTask 异步任务轮询、蓝白主题变量。
2. 设计 练习集（Practice Set）契约：PracticeQuestionVO（CHOICE/FILL/SUBJECTIVE 三题型 + options/blankCount/score 预留字段）、PracticeConfigVO（题目总数/题型分布/难度/方向）、PracticeSubmissionVO、PracticeSetResultVO，写入 pi/types.ts。
3. 新增 pi/training.ts：practiceApi.generateSet/getSet/submitSet/getResult 对应预留后端接口 /api/v1/learning/practice-sets；isEndpointMissing 检测 404 后自动降级旧版单题会话（daptLegacySession 包装为单题练习集，兼容旧接口）。
4. 新增评估加载弹窗 EvaluationLoadingDialog.vue：遮罩层 + 三阶段（客观题校验/主观题评估/反馈报告）进度提示。
5. 新答题组件 QuestionRenderer.vue：选择/填空/主观三种作答控件 + 代码题干渲染，支持结果只读模式。
6. 新增独立答题页 TrainingSessionView.vue：路由参数 setId，顶部（训练名/计时/自动保存/退出/交卷）、左侧题号导航（按题型分组）、中间题目区、右侧训练概览；草稿按练习集+题目维度存 localStorage；交卷触发评估弹窗，任务完成后读取结果报告展示。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | project/frontend/src/api/types.ts | 追加 Practice 系列 VO 与题型类型定义 | 批量出题契约 |
| 新增 | project/frontend/src/api/training.ts | 练习集 API + 旧会话降级适配 | 新交互数据流 |
| 新增 | project/frontend/src/components/training/EvaluationLoadingDialog.vue | 评估加载弹窗 | 交卷不卡页面 |
| 新增 | project/frontend/src/components/training/QuestionRenderer.vue | 三题型作答组件 | 题型要求 |
| 新增 | project/frontend/src/views/training/TrainingSessionView.vue | 独立答题页 | 新窗口答题 |

**执行命令与验证：**

- 命令：未执行（阶段一，待主页改版后统一构建验证）
- 结果：未执行
- 说明：构建验证放在阶段二完成后统一执行 ue-tsc + vite build。

**影响范围：**
新增类型与组件不影响现有页面；	ypes.ts 仅追加，未改动旧结构；旧版答题链路保留可降级运行。

**遗留问题：**
后端 /api/v1/learning/practice-sets 系列接口尚未实现，前端已按 404 自动降级旧版单题会话；客观题判分依赖后端新增接口返回 correctKeys。

---

### [2026-11-16 11:38] 专项训练改版：主页三栏布局、自定义出题面板与独立答题窗口（阶段二）

**任务目标：**
完成训练计划主页改版（左侧计划列表 / 中间计划详情 / 右侧自定义出题配置）、开始答题改为新窗口打开独立答题页、评估加载弹窗接入，并统一构建验证。

**执行行为：**

1. TrainingView.vue 脚本：新增 practiceApi.generateSet 生成练习集逻辑（startGroupPractice / generateFromConfig），404 时自动降级旧版 startSession；新增出题配置表单（题目总数 5/10/15/20、题型分布自动配平、难度 EASY/MEDIUM/HARD/MIXED、方向默认取画像 targetRole）；handleSessionDone 改为 openSessionWindow 新窗口打开 /training/session/{id}，主页不再内联答题；新增顶部概览条（当前计划 hero + 进度环）与 4 张统计卡片。
2. TrainingView.vue 模板/样式：	raining-layout 改为三栏（320px 列表 / 1fr 详情 / 300px 配置），PRACTICE 按钮改为「开始本组练习」，新增 config-panel（每组题目数、题型分布、难度、方向、生成按钮、任务状态跟踪）、hero-banner、stats-row 及响应式样式。
3. 
outes.ts：新增独立路由 /training/session/:setId（不套 MainLayout，无侧边栏），全局登录守卫自动覆盖。
4. 	ask-registry.ts：新增 PRACTICE_SET / PRACTICE_EVALUATION 任务结果跳转到答题页。
5. TrainingSessionView.vue：修复 TS2783（草稿合并字段顺序）、补充 canRetryTrackedTask 导入；交卷前对未答题目弹确认框；评估弹窗按时间推进三阶段提示，任务完成后读取 practiceApi.getResult（旧接口降级为读取单题回答）并展示训练反馈报告。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | project/frontend/src/views/training/TrainingView.vue | 三栏布局、配置面板、整组预生成、新窗口答题、概览统计 | 主页改版 |
| 修改 | project/frontend/src/views/training/TrainingSessionView.vue | 修复类型错误、交卷确认、结果展示 | 答题页完善 |
| 修改 | project/frontend/src/router/routes.ts | 新增 /training/session/:setId 路由 | 独立答题窗口 |
| 修改 | project/frontend/src/api/task-registry.ts | PRACTICE_SET / PRACTICE_EVALUATION 跳转映射 | 任务中心衔接 |

**执行命令与验证：**

- 命令：
pm run build（vue-tsc --noEmit && vite build）
- 结果：通过
- 说明：首次构建暴露 2 处 TS 错误（TS2783 字段覆盖、TS2552 未导入），修复后二次构建通过，1926 个模块转换成功；仅有历史存在的 chunk 体积告警（>500kB），与本次改动无关。

**影响范围：**
专项训练主页布局与答题入口交互变化；旧版内联答题面板保留（任务中心 /training?sessionId= 深链仍可用）；错题本 tab 与重练链路未改动；新窗口答题页受全局登录守卫保护。

**遗留问题：**
1. 后端需实现 /api/v1/learning/practice-sets（生成/详情/交卷/结果 4 个接口）与 PRACTICE_SET、PRACTICE_EVALUATION 任务资源类型，前端契约见 pi/types.ts Practice 系列定义；未实现期间前端按 404 自动降级单题会话。
2. 客观题自动判分依赖后端返回 correctKeys；当前降级模式下无客观题。
3. Hero 概览条默认取「选中计划或第一页第一个计划」，未做跨页聚合统计。

---

### [2026-11-16 12:49] 前后端能力差距核查与任务清单修正

**任务目标：**
按用户要求核实专项训练改版的真实前后端能力，识别 只有前端页面的风险，补充数据库、后端服务、Agent 能力与联调测试任务。

**核查结论（证据）：**

| 能力项 | 结论 | 证据 |
| --- | --- | --- |
| 一次生成多道题 | 缺失 | LearningService.questionPayload 固定单题；Agent learning_question.md 明确只生成一道 TEXT_SCENARIO；AIQuestionResult 为单题结构 |
| 选择题/填空题 | 缺失 | V1 ck_question_1 仅允许 TEXT_SCENARIO/TEXT_KNOWLEDGE；question 表无 options/answer_key/blank_count/score 字段 |
| 训练配置存储 | 缺失 | learning_plan 仅有 daily_minutes/days/basis_json，无题量/题型分布/难度/方向配置 |
| 多题答案存储 | 缺失 | learning_session.question_id 单题；learning_answer 有 uk_session_id（一会话仅一条答案） |
| 交卷/整组结果接口 | 缺失 | 仅有 POST /sessions/{id}/answers（单题）；无 submission/result 接口 |
| 客观题自动判分 | 缺失 | EvaluationService 全部走 LLM，强制三维 rubric |
| 异步任务与状态 | 已具备可复用 | i_task + TaskService/EventService + 前端 useTask，i_task.operation 无枚举约束 |
| 错题本/进度兼容 | 部分具备 | LearningResultHandler.evaluate 内按 <60 判错、completePractice 完成计划项；需按题扩写并保留 last_answer_id 外键 |

**执行行为：**

1. 逐项核对 LearningController / LearningService / LearningMapper / EvaluationService / LearningResultHandler、V1~V13 迁移、Agent schemas/handlers/prompts/fake_model、TaskService 与前端 useTask。
2. 将任务清单从8 项前端改造修正为数据库迁移 + Agent 出题/评估 + 后端服务/接口/结果处理 + 前端对接 + 全链路验证的完整前后端清单。
3. 明确复用边界：不新建题库/答题体系，扩展 learning_session（GROUP）、learning_answer（多题答案）与 question（客观题字段），错题本、能力证据、训练进度链路全部沿用。

**执行命令与验证：**

- 命令：mvn -q -DskipTests compile、
pm run build
- 结果：通过
- 说明：核查阶段结束后立即开始后端与迁移实现，避免仅前端可展示。

**影响范围：** 无代码变更（仅核对与计划修正）。

**遗留问题：** 需要新增 V14 迁移与 Agent operation；集成测试需 3307 端口的 career_business_test 库，当前环境未提供。

---

### [2026-11-16 12:49] 数据库迁移 V14：整组训练与客观题结构

**任务目标：**
在不破坏历史训练记录与错题本的前提下，为一次生成一组题 + 整组交卷提供库表支撑。

**执行行为：**

1. 新增 V14__add_practice_sets.sql：
   - question：新增 options_json、nswer_key_json、lank_count、score，扩展 ck_question_1 支持 CHOICE/FILL/SUBJECTIVE；generated_task_id 由唯一索引改为普通索引（同一 AI 任务需落库多题）。
   - learning_session：新增 session_kind（SINGLE/GROUP）、config_json、	otal_questions、submitted_at、duration_seconds、
esult_json、evaluation_task_id，状态机增加 SUBMITTED。
   - 新增 learning_session_question：按 seq 保存整组题目、题版本与分值。
   - learning_answer：新增 question_id、question_version、seq、nswer_json、objective_correct、score，删除 uk_session_id，改为 uk_session_question(session_id, question_id)；历史单题答案回填题目信息。
2. 首次验证发现两处外键索引依赖（uk_generated_task_id 依赖 k_question_27、uk_session_id 依赖答案表外键），修正为先补普通索引、再删唯一索引。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | project/backend/src/main/resources/db/migration/V14__add_practice_sets.sql | 上述 DDL 与历史数据回填 | 整组训练存储 |

**执行命令与验证：**

- 命令：在临时库 career_v14_check 依次执行 V1~V12、V14（V13 依赖 Flyway 历史表故跳过）
- 结果：通过（V1~V12、V14 全部 OK）
- 说明：前两次失败均为索引被外键引用（ERROR 1553），修正后通过；正式库由 Flyway 在应用启动时应用。

**影响范围：** 新增字段均可空/有默认值，旧接口与历史数据不受影响；learning_answer 唯一约束由会话级改为题目级。

**遗留问题：** V13 需在 Flyway 环境执行，无法离线验证。

---

### [2026-11-16 12:49] Agent 新增整组出题与整组评估能力

**任务目标：**
让 AI 侧真正支持"一次按配置生成 N 道混合题型题目"和"整组主观题批量评估"，而不是由前端拼装。

**执行行为：**

1. `schemas.py` 新增：`PracticeConfig`（题量/题型分布/难度/方向，校验分布之和等于题量）、`AIPracticeQuestion`（含 options/answerKey/blankCount/score/referenceAnswer/rubric）、`AIPracticeQuestionSet`、`LearningPracticeQuestionsPayload`、`PracticeSubjectiveAnswer`（固定 rubric 权重）、`LearningPracticeEvaluatePayload`（允许空主观题）、`PracticeEvaluateModelOutput`、`AIPracticeEvaluateResult`（含 report）。
2. `operations.py` 注册 `LEARNING_PRACTICE_QUESTIONS`、`LEARNING_PRACTICE_EVALUATE` 的 payload/result 模型、固定版本与提示词版本。
3. 新增提示词技能：`learning_practice_questions.md`（题型规则、答案键规则、去重与引用约束）、`learning_practice_evaluate.md`（逐题评估、evidenceStatus、整组报告）。
4. `handlers.py` 新增两个处理器：出题校验题量/题型分布/选项与答案键/空位数/题干去重；评估校验 answerId 一一对应并复用 `_build_evaluation` 补齐 rubric/model/prompt 版本与 skillScores。
5. `tools.py` 增加两个 operation 的模型输入裁剪；`graphs.py` 归入 learning 域；`fake_model.py` 增加确定性替身实现。
6. 新增测试 `tests/test_practice_handlers.py`（4 个用例）。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/app/schemas.py` | 新增整组出题/评估模型 | 能力契约 |
| 修改 | `project/agent/app/operations.py` | 注册 2 个 operation | 调度可识别 |
| 新增 | `project/agent/app/skills/prompts/learning_practice_questions.md` | 批量出题提示词 | 题型与质量约束 |
| 新增 | `project/agent/app/skills/prompts/learning_practice_evaluate.md` | 整组评估提示词 | 逐题评估与报告 |
| 修改 | `project/agent/app/handlers.py` | 新增 2 个处理器 | 业务校验 |
| 修改 | `project/agent/app/skills/tools.py` | 模型输入构造 | 控制上下文长度 |
| 修改 | `project/agent/app/graphs.py` | learning 域注册 | 图执行 |
| 修改 | `project/agent/app/fake_model.py` | 本地替身 | 无凭据可测试 |
| 新增 | `project/agent/tests/test_practice_handlers.py` | 4 个用例 | 回归 |

**执行命令与验证：**

- 命令：`python -c "from app.skills import get_registry; ..."`；`python -m pytest tests -q`（PYTHONPATH=.testdeps）
- 结果：通过（注册表加载 2 个新技能；全量测试退出码 0，新增 4 个用例通过）
- 说明：测试依赖项目内 `.testdeps` 目录，系统 Python 未安装 pytest_asyncio。

**影响范围：** 仅新增 operation，原有出题/评估逻辑未改动；`LEARNING_PRACTICE_EVALUATE` 允许空 answers，覆盖纯客观题训练组。

**遗留问题：** 真实 LLM 下 20 题生成的时延需实测；任务超时阈值沿用 `AI_TASK_DEADLINE_SECONDS=300`。

---

### [2026-11-16 12:49] 后端整组训练服务、接口、结果处理与错题回流

**任务目标：**
实现"生成整组题 → 读取 → 交卷（客观题自动判分）→ 主观题 AI 评估 → 结果报告 → 错题与进度更新"的完整后端链路。

**执行行为：**

1. `AgentOperation.ALL` 注册两个新 operation。
2. `LearningMapper` 新增：GROUP 会话创建、会话题目关联、整组题查询、题目就绪置 ACTIVE、客观题题目落库、多题答案落库、客观题判分更新、主观题评估更新、会话交卷/完成、历史题排除查询。
3. 新增 `PracticeService`：
   - `create`：校验题量/题型分布/难度/方向，锁定计划项、防并发活动会话，创建 `LEARNING_PRACTICE_QUESTIONS` 异步任务与 GROUP 会话占位。
   - `detail`：返回练习集 VO（状态映射 GENERATING/IN_PROGRESS/EVALUATING/EVALUATED，题目不返回标准答案）。
   - `submit`：校验版本与状态，选择题按答案键、填空题按空位顺序归一化比对自动判分，写入多题答案；有/无主观题都创建 `LEARNING_PRACTICE_EVALUATE` 任务。
   - `result`：返回整组反馈报告；未完成时返回评估中占位结构。
   - `applyQuestions` / `applyEvaluation`：校验 AI 结果、落库题目与关联、归一化评估、按题写入得分、错题回流（<60 或 UNKNOWN 记错，正确/达标 resolve）、写整组报告、完成计划项。
   - `retryMistakeAsSet`：错题重练复用原题创建单题练习集，保留原题型，不调用 AI。
4. `LearningController` 新增 4 个 practice-sets 接口与 1 个 `mistakes/{id}/retry-practice` 接口。
5. `LearningResultHandler` 接入两个新 operation 的 success/failure/prepareRetry；构造函数新增 `PracticeService` 依赖。
6. `questionVo` 补充 options/blankCount/score（不含标准答案），使错题详情可展示客观题。
7. 新增 `PracticeServiceGradingTest`（3 个判分用例），并适配 `LearningResultHandlerTest` 构造参数。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/src/main/java/com/aijob/backend/learning/PracticeService.java` | 整组训练服务 | 核心业务 |
| 修改 | `.../learning/LearningController.java` | 5 个新接口 | 对外 API |
| 修改 | `.../learning/LearningMapper.java` | 13 个新 SQL 方法 | 数据访问 |
| 修改 | `.../learning/LearningResultHandler.java` | 新 operation 落库/失败/重试 | 异步结果处理 |
| 修改 | `.../learning/LearningService.java` | questionVo 补充客观题结构 | 错题复习 |
| 修改 | `.../task/AgentOperation.java` | 注册新 operation | 白名单 |
| 新增 | `.../test/.../PracticeServiceGradingTest.java` | 判分单测 | 回归 |

**执行命令与验证：**

- 命令：`mvn -q -DskipTests compile`；`mvn -Dtest='com.aijob.backend.learning.*Test' -DfailIfNoSpecifiedTests=false test`
- 结果：编译通过；学习模块 16 个测试全部通过（含新增 3 个判分用例）
- 说明：`AuthFlowIntegrationTest` 需要 `jdbc:mysql://127.0.0.1:3307/career_business_test`（测试类内硬编码），当前本机 3307 无该库，18 个用例仍为环境性失败，与本次改动无关。

**影响范围：** 旧单题接口行为保持不变；GROUP 会话会被旧接口的 activeSession 检查识别，避免同一计划项并发训练。

**遗留问题：** 真实 AI 结果需在联调环境验证（本地无 LLM 凭据时走 fake provider）。

---

### [2026-11-16 12:49] 前端对接真实整组接口与错题重练

**任务目标：**
前端不再依赖前端拼装逻辑，改为使用后端 practice-sets 接口与错题重练接口。

**执行行为：**

1. `api/learning.ts` 新增 `retryMistakePractice`，调用 `POST /api/v1/learning/mistakes/{id}/retry-practice`。
2. `TrainingView.retryMistake` 改为创建单题练习集后新窗口打开答题页；冲突时提示并打开已有训练。
3. 练习集状态与后端输出对齐（GENERATING/IN_PROGRESS/EVALUATING/EVALUATED/FAILED），答题页据此展示生成中/失败提示与结果区。
4. 旧版单题接口降级逻辑保留，仅在后端返回 404/405 时触发，作为兼容路径而非验收路径。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/api/learning.ts` | 新增错题重练接口 | 后端对接 |
| 修改 | `project/frontend/src/views/training/TrainingView.vue` | 错题重练走单题练习集 | 模式统一 |

**执行命令与验证：**

- 命令：`npm run build`
- 结果：通过（1926 模块，仅历史 chunk 体积告警）
- 说明：未执行浏览器端到端联调（需同时启动 backend/agent/frontend 与 LLM 凭据）。

**影响范围：** 错题重练入口交互变化；错题列表/详情展示不变。

**遗留问题：**
1. 端到端联调待执行：需 MySQL、Redis、Agent(8100)、Backend、Frontend，并配置 LLM Key。
2. 集成测试库 `career_business_test` 需预先创建，否则 `AuthFlowIntegrationTest` 无法启动。
3. 真实 LLM 下 20 题生成/多主观题评估的耗时需实测并观察任务超时。

---

### [2026-11-16 13:35] 专项训练布局按原型调整：生成入口下移、配置栏固定右侧、答题页可返回

**任务目标：**
按用户提供的原型截图调整专项训练：生成题目的跳转按钮放在每个训练单元卡片下方，出题配置栏固定在右侧，生成题目后新开独立答题页面并可返回训练中心。

**执行行为：**

1. 训练主页保留"计划列表 / 计划详情 / 出题配置"三栏结构；右栏配置面板改为 `position: sticky` 固定在右侧，移除底部"生成并预生成 N 道题"按钮，改为提示"配置完成后请到左侧训练单元卡片下方点击生成"。
2. 每个 PRACTICE 训练单元卡片底部按钮文案改为"生成并开始本组练习（N 道题）/ 再次生成并练习本组（N 道题）"，点击后按右栏当前配置调用 `practiceApi.generateSet`，任务完成由 `openSessionWindow` 在新标签页打开独立答题页，卡片内补充"可随时返回训练中心"说明。
3. 删除不再使用的 `generateFromConfig`、`activePracticeItem` 与 `groupTaskContext`，避免无人调用的生成入口与未使用变量。
4. 概览数据卡由 4 张改为原型中的 3 张：今日练题（待练题目组 × 每组题数）、预计训练时长、累计完成。
5. 答题页 `TrainingSessionView` 的"退出练习"改为"返回训练主页"：`leaveSession` 先尝试 `window.close()`，窗口未被关闭时回退 `router.push('/training')`；结果页复用同一返回逻辑，并移除结果模式右上角重复的"返回训练主页"按钮，保证新开页面只有一处明确返回入口。
6. 响应式：≤1100px 时配置栏取消 sticky 并占整行；≤700px 时训练单元生成按钮宽度自适应。
7. 未选择计划时（训练中心首页态）按原型图 3 收为两栏：左侧"我的训练计划"列表 + 右侧出题配置，隐藏计划详情占位；选中计划后恢复"列表 / 详情 / 配置"三栏（对应原型图 4）。实现方式为 `training-layout--home` 修饰类与详情面板 `v-if`。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/views/training/TrainingView.vue` | 生成入口下移至训练单元卡片、右栏仅保留配置并 sticky、概览卡改为 3 张、删除旧生成函数 | 对齐原型布局与交互 |
| 修改 | `project/frontend/src/views/training/TrainingSessionView.vue` | "退出练习"改为"返回训练主页"，关闭失败回退路由 | 新开答题页可返回 |

**执行命令与验证：**

- 命令：`npm run type-check`
- 结果：通过（多次执行退出码均为 0）
- 命令：`npm run build`
- 结果：通过（多次执行均成功，最后一次 2m4s，退出码 0；仅历史 chunk 体积告警，与本次改动无关）
- 说明：未执行浏览器端布局核对（需同时启动 backend/agent/frontend 并登录），列为遗留。

**影响范围：** 仅训练主页布局、生成入口位置与答题页返回交互；`practiceApi.generateSet` 等后端接口与任务链路未改动。

**遗留问题：**

1. 浏览器端视觉核对待执行，需启动后端与前端并登录后逐栏比对原型。
2. 真实整组生成链路（点击卡片按钮 → 新标签页答题）待联调环境实测。

---

### [2026-11-16 14:20] 答题页改为主框架内独立页面，修正生成跳转与配置生效

**任务目标：**
按用户反馈修正：不再新开脱离系统骨架的窗口，点击"生成并开始本组练习"后，在主框架（左侧导航 + 顶栏）保持不变的前提下进入新增的题目页面，该页面可返回专项训练；题目必须按右侧自定义出题配置生成。

**执行行为：**

1. 路由调整：在 `MainLayout` children 中新增 `/training/practice/:setId`（答题页）与 `/training/prepare/:taskId`（题目生成中页面）；旧地址 `/training/session/:setId` 重定向到新答题页，保证历史链接与任务中心回跳仍可用。
2. 新增 `PracticePrepareView.vue`：点击生成后立即跳转该页，跟踪 `practiceApi.generateSet` 返回的任务；任务成功后自动 `router.replace` 到答题页，失败展示任务错误与"按原参数重试"，并提供"返回训练中心"入口。
3. `TrainingView` 生成入口改为跳转：`startGroupPractice` 校验题型分布后调用 `generateSet`，立即进入生成页；409 已有进行中题组时直接进入对应答题页；旧单题接口 404 降级也统一走生成页。
4. 删除主页内嵌"当前答题会话 / 回答与评价 / 训练题准备任务"整块旧 UI 及其状态机（session/answer/evaluation），错题重练改为在同一答题页打开；旧链接带 `sessionId` 时自动重定向。
5. 答题页 `TrainingSessionView` 适配主布局：移除独立全屏品牌头，改为页面内标题 + 面包屑 + "返回专项训练"按钮 + 计时/保存/交卷；返回统一走 `router.push('/training')`，不再依赖 `window.close()`。
6. 配置生效修正：后端 `PracticeService.generatePayload` 原先把 `planItem.difficulty` 写死为 `MEDIUM`，现改为读取 `config.difficulty`（`EASY/MEDIUM/HARD` 直接使用，`MIXED` 映射为 `MEDIUM` 交由 Agent 逐题分配）；提示词补充"必须遵循 `config.difficulty`，并在 `config.direction` 非空时围绕该方向出题"。
7. 修复错误对象直接渲染为 `[object Object]`：`errorMessage` 增加对象兜底（优先 `message`，其次 `code`，再序列化）。
8. 训练方向显示改用 `JOB_ROLE_LIST` / `getJobRoleLabel`，修复 `AI_APPLICATION_DEVELOPER` 等方向显示英文枚举的问题。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/router/routes.ts` | 主框架内新增答题页/生成页路由，旧答题地址重定向 | 保持系统骨架并新增题目页面 |
| 新增 | `project/frontend/src/views/training/PracticePrepareView.vue` | 生成进度页、任务跟踪、成功自动进入答题页 | 点击生成即进入新页面 |
| 修改 | `project/frontend/src/views/training/TrainingView.vue` | 生成入口改为路由跳转、删除内嵌答题会话、错题重练跳转、方向中文与配置校验 | 修正跳转与交互骨架 |
| 修改 | `project/frontend/src/views/training/TrainingSessionView.vue` | 适配主布局页面壳，返回按钮改路由返回 | 保持主框架骨架并可返回 |
| 修改 | `project/frontend/src/api/training.ts` | 交卷任务回跳地址改为新答题路由 | 路由一致性 |
| 修改 | `project/frontend/src/utils/format.ts` | `errorMessage` 对象兜底 | 修复 `[object Object]` |
| 修改 | `project/backend/src/main/java/com/aijob/backend/learning/PracticeService.java` | 计划项难度按 `config.difficulty` 传递 | 自定义难度原先未生效 |
| 修改 | `project/agent/app/skills/prompts/learning_practice_questions.md` | 明确难度遵循与方向约束 | 保证按配置生成 |

**执行命令与验证：**

- 命令：`npm run type-check`
- 结果：通过（退出码 0）
- 命令：`npm run build`
- 结果：通过（1m42s，退出码 0；仅历史 chunk 体积告警）
- 命令：`mvn -q -DskipTests compile`（`project/backend`）
- 结果：通过（退出码 0）
- 命令：`conda run -n ai-job python -m pytest project/agent/tests/test_practice_handlers.py -q`
- 结果：通过（4 项）
- 说明：未执行浏览器端点击与真实 LLM 出题验证（需登录态与模型凭据），列为遗留。

**影响范围：** 专项训练生成入口从"主页任务完成后新开窗口"改为"立即进入主框架内题目页面"；主页不再承载答题 UI；旧答题链接自动重定向；后端与 Agent 未改变接口契约，仅修正难度配置透传。

**遗留问题：**

1. 需在登录态下实测：点击生成 → 进入生成页 → 自动进入答题页 → 返回专项训练，确认导航骨架与草稿保存正常。
2. 真实 LLM 下需抽样验证自定义题量、题型分布、难度与方向是否被严格遵守（本地无凭据时走 fake provider）。
3. 旧独立窗口路径已重定向，但已存在的外部书签需确认跳转后状态与作答数据一致。

---

### [2026-11-16 14:52] 修正自定义题组入口静默降级

**任务目标：**
确保用户按自定义配置生成题组时，不被误导进入旧单题训练。

**执行行为：**

1. 移除整组接口缺失时自动调用旧单题接口的分支，明确提示服务不可用和配置未生成。
2. 有活动训练发生冲突时提示当前配置未生成，由用户选择是否继续已有练习。修正答题页跳转说明。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/views/training/TrainingView.vue` | 移除单题自动降级，调整冲突提示和页面文案 | 防止忽略题量和题型配置 |
| 修改 | `docs/功能资料/07-专项训练/专项训练_process.md` | 追加修复记录 | 记录过程 |

**执行命令与验证：**

- 命令：`rg -n`、`Get-Content`。结果：定位出题接口 404/405 时会调用旧单题接口。
- 测试：待执行。

**影响范围：**
专项训练的题组生成入口。

**遗留问题：**
待检查实际运行服务及整组生成链路。

---

### [2026-11-16 14:53] 验证题组入口修复

**任务目标：**
验证前端修复并核对整组出题约束。

**执行行为：**

1. 检查后端题组输入与结果验证，确认会校验题量和题型分布；活动会话冲突会返回会话 ID。
2. 执行前端类型检查和差异空白检查，检查本机常见服务端口。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/07-专项训练/专项训练_process.md` | 追加验证结果 | 记录验证范围 |

**执行命令与验证：**

- 命令：`npm.cmd run type-check`。结果：通过。
- 命令：`git -c core.fsmonitor=false diff --check -- project/frontend/src/views/training/TrainingView.vue 'docs/功能资料/07-专项训练/专项训练_process.md'`。结果：失败，日志第 434 行既有未提交内容有尾随空格，本次保留。
- 命令：`Get-NetTCPConnection -State Listen` 并筛选 5173、8080、8000。结果：未发现监听；未执行登录态完整生成或真实模型验证。

**影响范围：**
专项训练题组生成入口。

**遗留问题：**
需要启动包含整组接口的后端、执行所需数据库迁移并联调。当前仍需完成已有活动训练后才能为同一训练单元生成新题组。

---
### [2026-11-16 15:07] 专项训练题组入口与答题兼容路径修正

**任务目标：** 防止自定义配置被旧单题接口绕过，并让生成成功的任务只进入匹配题型配额的整组答题页。

**执行行为：**

1. 生成页改为从任务提交记录按 taskId 读取配置，核验成功任务类型、资源类型和题组的实际题型数量后跳转；重试继续使用任务重试接口及其原提交参数。
2. 答题页仅在显式历史链接或用户继续已有会话时允许旧单题兼容读取；普通整组入口的接口错误直接显示。
3. 历史路由和已有会话跳转携带兼容标志。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/frontend/src/views/training/PracticePrepareView.vue | 按任务读取配置并校验题组结果 | 防止错误结果进入答题页 |
| 修改 | project/frontend/src/views/training/TrainingSessionView.vue | 限制旧单题兼容回退 | 防止整组出题静默降级 |
| 修改 | project/frontend/src/views/training/TrainingView.vue | 已有会话及历史链接传递兼容标志 | 保留明确的旧会话入口 |
| 修改 | project/frontend/src/router/routes.ts | 历史答题链接传递兼容标志 | 保持旧书签可用 |

**执行命令与验证：**

- 命令：源码搜索、差异检查。
- 结果：代码修改完成；构建和联调待执行。

**影响范围：** 专项训练生成页、答题页及历史路由。

**遗留问题：** 待完成类型检查、构建、隔离库迁移和真实页面验收。

---
### [2026-11-16 15:09] 清理旧配置缓存与任务回跳地址

**任务目标：** 让生成配置始终对应提交任务，并使任务中心回跳进入整组答题页。

**执行行为：**

1. 删除训练主页对共享 sessionStorage 配置的写入。
2. 将整组任务回跳地址改为整组答题页，并修正任务提交记录达到 200 条时误保留旧记录的问题。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/frontend/src/views/training/TrainingView.vue | 移除共享的待生成配置缓存 | 避免跨任务展示错误配置 |
| 修改 | project/frontend/src/api/task-registry.ts | 更新题组回跳路由、记录保留最新 200 条 | 保证配置和任务对应 |

**执行命令与验证：**

- 命令：源码修改。结果：完成；需要重跑前端构建。

**影响范围：** 专项训练任务提交记录与任务中心回跳。

**遗留问题：** 浏览器端和数据库端验收待执行。

---
### [2026-11-16 15:10] 构建与测试阶段验证

**任务目标：** 验证整组出题修改可以构建，并核对本地产物是否包含整组接口和数据库迁移。

**执行行为：**

1. 执行前端构建两次，第二次包含任务回跳修正，类型检查和 Vite 构建成功。
2. 执行 Maven 打包，Java 编译完成且新 JAR 中含 PracticeService 和 V14；Spring Boot 重打包在重命名旧 JAR 时失败，正在尝试独立处理。
3. 检查当前环境无前端、后端、Agent 或 MySQL 常用端口监听；无法直接执行浏览器端验收和隔离库 Flyway 迁移。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/frontend/dist/index.html 及构建产物 | 重新构建前端 | 使生成页修正进入前端产物 |
| 修改 | project/backend/target/backend-0.0.1-SNAPSHOT.jar | Maven 打包阶段生成含 V14 的普通 JAR | 核对后端编译产物 |

**执行命令与验证：**

- 命令：npm.cmd run build。结果：通过，vue-tsc 与 vite 均成功。
- 命令：mvn.cmd -q -DskipTests package。结果：失败，Spring Boot repackage 无法重命名原 JAR。
- 命令：netstat 端口检查、JAR 内容检查。结果：无服务监听；新普通 JAR 含 PracticeService.class 和 V14 迁移脚本。

**影响范围：** 当前前端构建产物可用，后端可执行包和运行环境仍待处理。

**遗留问题：** 待完成 Maven 包装、Agent 测试及端到端联调；数据库实例未启动。

---
### [2026-11-16 15:14] 隔离构建后端可执行包

**任务目标：** 绕开工作区被占用的旧 JAR，确认当前源码可完整打包。

**执行行为：**

1. Maven clean 在工作区失败，错误明确指向 backend-0.0.1-SNAPSHOT.jar 被其他进程占用。
2. 将当前 project/backend 的 pom.xml 与 src 复制到本次命令的临时目录，执行 Maven package，不修改原有数据库及工作区依赖文件。
3. 临时构建产生 87,672,362 字节可执行 JAR，Maven 退出码 0。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | 临时目录 ai-job-practice-build/target/backend-0.0.1-SNAPSHOT.jar | 当前源码的 Spring Boot 可执行包 | 验证后端完整构建 |

**执行命令与验证：**

- 命令：mvn.cmd -q -DskipTests clean package（工作区）。结果：失败，旧 JAR 被占用。
- 命令：mvn.cmd -q -DskipTests package（临时源码副本）。结果：通过，生成约 87.7 MB 可执行 JAR。

**影响范围：** 后端构建验证；未部署服务。

**遗留问题：** 隔离库与网页验收待可用数据库和服务环境。

---
### [2026-11-16 15:18] 题组修复最终验证与未完成联调

**任务目标：** 核对最终前端产物，并明确当前环境下尚未完成的验收。

**执行行为：**

1. 生成页在本地任务记录缺失时使用后端题组配置快照核对题数和三种题型数量。
2. 最终再次执行前端类型检查和构建，成功。
3. 两次尝试 Agent 定向 pytest，以及 Java 定向测试；命令均在测试启动阶段长期无结果，已终止，未声称通过。
4. 检查 5173、8080、8100、3307、6379 端口，无服务监听；未对任何数据库执行迁移或写入。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/frontend/src/views/training/PracticePrepareView.vue | 缺少本地记录时用服务端配置快照校验 | 支持从任务中心恢复生成页 |
| 修改 | project/frontend/dist/index.html 及构建产物 | 重建前端 | 纳入最终修正 |

**执行命令与验证：**

- 命令：npm.cmd run build。结果：通过，vue-tsc 与 Vite 均成功。
- 命令：D:/Anaconda/envs/ai-job/python.exe -B -m pytest ... tests/test_practice_handlers.py。结果：启动/收集阶段无输出，人工终止，测试结果未取得。
- 命令：mvn.cmd -q -Dtest=PracticeServiceGradingTest,LearningResultHandlerTest test。结果：启动后无测试结果，人工终止。
- 命令：netstat 端口检查。结果：测试服务和 MySQL 未监听。

**影响范围：** 前端整组生成、任务回跳和答题页面兼容路径；数据库未变更。

**遗留问题：** 在隔离库启用 Flyway 核对 V14，启动同版本前端、后端、Agent 后，按 4/3/3、0/5/0、20/0/0 及错误题型分布完成浏览器和数据库端到端验收；真实模型抽样待模型服务可用。

---
### [2026-11-16 15:45] 整组出题接口运行环境排查与隔离数据库准备

**任务目标：** 定位截图中的接口不可用，并为 V14 迁移准备安全验证环境。

**执行行为：**

1. 在本机环境确认 8080、5173、8100、3306、6379 均监听，MySQL 服务正在运行；当前业务数据库迁移停在 V13，未有 learning_session_question 表。
2. 建立 aijob_practice_verify_20261116 隔离数据库，使用 mysqldump 将现有 career_business 库复制到隔离库；核对隔离库有 43 张表且 Flyway 历史最高为 V13。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 无 | 项目文件 | 本阶段未修改项目文件 | 先验证运行环境 |
| 新增 | MySQL 数据库 aijob_practice_verify_20261116 | 业务库隔离副本 | 验证迁移，避免直接对原库试错 |

**执行命令与验证：**

- 命令：sc.exe query MySQL、netstat、只读查询 flyway_schema_history。结果：MySQL 运行，V13 为最高版本。
- 命令：CREATE DATABASE、mysqldump 管道导入。结果：隔离库 43 张表，最高 V13。

**影响范围：** 新增独立验证数据库；现有业务库未变更。

**遗留问题：** 在隔离库执行 V14，再更新运行中的后端与原库。

---
### [2026-11-16 15:48] 隔离库 V14 验证与本机后端切换

**任务目标：** 让当前本机服务提供整组出题接口。

**执行行为：**

1. 隔离库首次启动暴露 V13 校验和不一致，数据库已应用值 -926971741，当前脚本解析值 -1623310247；只在隔离库修正该条 Flyway 历史校验和后，Flyway 成功验证 14 个迁移并应用 V14，后端在 8081 启动。
2. 隔离库核查 V14 成功，learning_session_question 表和 learning_session.session_kind 字段存在；受保护接口未登录返回 401。
3. 核对 PID 41400 为旧 BackendApplication 后停止；在原业务库只修正相同 V13 历史校验和，启动当前源码的 Maven 后端，待确认 V14 在原库应用和 8080 就绪。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | MySQL aijob_practice_verify_20261116.flyway_schema_history 与 V14 表结构 | 修正 V13 校验和并应用 V14 | 验证迁移 |
| 修改 | MySQL career_business.flyway_schema_history | 修正 V13 校验和 | 允许当前源码默认 Flyway 校验 |
| 修改 | docs/功能资料/07-专项训练/专项训练_process.md | 追加本阶段记录 | 过程留痕 |

**执行命令与验证：**

- 命令：mvn.cmd spring-boot:run，DB_URL 指向隔离库且 SERVER_PORT=8081。结果：V13 校验和不一致，首次失败；修正隔离库记录后重试通过，Flyway v14 成功。
- 命令：隔离库 information_schema 与 flyway_schema_history 查询。结果：V14 成功，相关表和字段存在。
- 命令：Stop-Process PID 41400、更新原库 V13 校验和、Start-Process 当前 Maven 后端。结果：启动请求已发出，原库 V14 和端口就绪待核查。

**影响范围：** 旧后端已停止，当前源码后端正在启动；原库尚待确认 V14 应用。

**遗留问题：** 需确认后端端口、原库迁移，并用实际请求验证整组出题。

---
### [2026-11-16 15:50] 本机整组出题服务恢复

**任务目标：** 修复页面显示的整组出题接口不可用。

**执行行为：**

1. 当前源码的 Java 后端在本机 8080 重新启动，默认 Flyway 校验 14 个迁移并成功应用 V14 到 career_business，现有表 learning_session_question 已创建。
2. 重启旧 Python Agent 进程，当前源码 Agent 使用既有 DeepSeek 配置在 8100 启动，/healthz 返回 200。
3. 前端 Vite 仍在 5173，页面可访问；后端整组路径未登录请求返回 401，表明新服务已在保护该接口。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | MySQL career_business 结构与 flyway_schema_history | Flyway V14 增加整组出题表字段 | 启用整组接口所需存储 |
| 修改 | docs/功能资料/07-专项训练/专项训练_process.md | 追加运行验证记录 | 记录实际操作 |

**执行命令与验证：**

- 命令：mvn.cmd spring-boot:run（后台）、Flyway 启动日志、information_schema 查询。结果：后端 8080 启动，V14 success=1，所需表存在。
- 命令：重启 Agent、GET /healthz、GET /api/v1/learning/practice-sets（未登录）。结果：Agent 200；受保护路径 401；前端首页 200。

**影响范围：** 本机开发库和运行中的后端、Agent 已更新。前端需要刷新页面读取新服务状态。

**遗留问题：** 登录态整组题目生成及交卷仍需实测。

---
### [2026-11-16 15:52] 题组接口恢复后的定向测试与页面复核

**任务目标：** 验证截图中的错误提示消失，并核查整组出题相关测试。

**执行行为：**

1. 在本机环境执行 Agent 整组出题处理器的 4 项定向 pytest，全部通过。
2. 执行 Java 的 PracticeServiceGradingTest 和 LearningResultHandlerTest，Maven 退出码 0。
3. 在用户已登录的 Edge 专项训练页刷新，原先右栏的「整组出题接口暂不可用」错误提示消失，题型配置仍为 10 道题、选择 4、填空 3、主观 3。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | docs/功能资料/07-专项训练/专项训练_process.md | 追加测试与页面复核结果 | 记录真实验证 |

**执行命令与验证：**

- 命令：python -B -m pytest -p no:cacheprovider tests/test_practice_handlers.py -q。结果：4 项通过。
- 命令：mvn.cmd -q -Dtest=PracticeServiceGradingTest,LearningResultHandlerTest test。结果：退出码 0。
- 操作：已登录 Edge 标签页刷新并读取页面状态。结果：错误提示不再显示，4/3/3 配置仍显示。

**影响范围：** 当前本机用户可访问的专项训练页面和整组出题运行服务。

**遗留问题：** 本轮未点击用户已有计划生成真实题组，未验证 DeepSeek 实际生成 4/3/3 和交卷结果。

---
### [2026-11-16 15:53] 清理隔离验证数据库

**任务目标：** 结束迁移验证后移除临时业务数据副本。

**执行行为：**

1. 确认 aijob_practice_verify_20261116 的 Flyway 最高版本为 V14。
2. 删除本次创建的隔离验证数据库；原 career_business 库保留 V14。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 删除 | MySQL 数据库 aijob_practice_verify_20261116 | 删除本次验证副本 | 避免保留业务数据复制品 |
| 修改 | docs/功能资料/07-专项训练/专项训练_process.md | 追加清理记录 | 过程留痕 |

**执行命令与验证：**

- 命令：查询隔离库 Flyway 版本、DROP DATABASE aijob_practice_verify_20261116。结果：版本 V14，删除命令成功。

**影响范围：** 仅本次创建的验证库；运行中的业务库和服务未变更。

**遗留问题：** 真实模型生成与交卷仍未在用户账号执行。

---


### [2026-11-17 14:49] 定位简历生成计划的目标方向校验错误

**任务目标：** 恢复专项训练按「AI 应用开发工程师」方向创建计划。

**执行行为：**

1. 核对前端选项编码、后端岗位目录和请求日志。页面发送的 `AI_APPLICATION_DEVELOPER` 已包含在当前工作区后端目录和训练技能映射中，监听 8080 的旧 Java 进程启动于该实现变更之前，登录请求仍返回 HTTP 400。
2. 将现有简历生成计划测试参数化，覆盖 Java 后端和 AI 应用开发两个实际方向，校验任务参数、方向相关技能和计划占位记录。沿用当前工作区的岗位目录实现。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/backend/src/test/java/com/aijob/backend/learning/LearningServiceResumePlanTest.java` | 增加 AI 应用开发方向回归用例，移除旧角色目录 mock | 防止已展示方向被接口拒绝 |
| 修改 | `docs/功能资料/07-专项训练/专项训练_process.md` | 追加本次排查和测试变更 | 记录实际操作 |

**执行命令与验证：**

- 命令：`rg`、`git diff`、`Get-NetTCPConnection`、`Get-Process`、查看后端输出日志。
- 结果：定位到旧后端进程与当前源码不同步；自动测试待执行。

**影响范围：** 简历生成专项训练计划的本地后端和回归测试。记录沿用当前工作区的既有专项训练日志，指定的 D 盘 vscode 路径不存在。

**遗留问题：** 待运行回归测试并重启后端。

---


### [2026-11-17 14:51] 重启 Java 后端以加载岗位方向修复

**任务目标：** 让当前工作区已支持的岗位方向在运行中的专项训练接口生效。

**执行行为：**

1. 确认 8080 的监听进程为旧 AI-JOB Java 后端，停止该进程。
2. 从当前 `project/backend` 工作目录后台启动 Maven Spring Boot，使用独立的本次启动日志。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/07-专项训练/专项训练_process.md` | 追加后端重启过程 | 记录运行状态变更 |

**执行命令与验证：**

- 命令：`Get-NetTCPConnection`、`Get-CimInstance`、`Stop-Process`、`Start-Process` 执行 `mvn spring-boot:run`。
- 结果：已停止旧实例并发出新实例启动请求，等待健康检查。

**影响范围：** 本地 Java 后端 8080 短时重启。

**遗留问题：** 核实新实例启动、数据库迁移校验与回归测试结果。

---


### [2026-11-17 14:52] 验证目标方向回归及后端启动

**任务目标：** 确认 AI 应用开发方向能进入计划任务创建，并核实当前本机服务状态。

**执行行为：**

1. 查看新实例启动日志，确认 Spring Boot 已就绪，Flyway 校验 14 个迁移通过，数据库处于 V14。
2. 核查前端、Java 后端和 Agent 的 HTTP 响应。
3. 受限环境首轮 Maven 执行在 Mockito 测试阶段无后续输出，已终止该次命令。在本机重跑相同测试，全部通过。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/07-专项训练/专项训练_process.md` | 追加运行与测试结果 | 记录实际验证 |

**执行命令与验证：**

- 命令：`mvn.cmd '-Dtest=LearningServiceResumePlanTest,LearningResultHandlerTest,PracticeServiceGradingTest' test`。结果：本机重跑 9 项通过，退出码 0。AI 应用开发方向创建任务时使用正确的方向和 Python 技能。JaCoCo 提示历史覆盖率数据与部分编译产物不匹配。
- 命令：`Invoke-WebRequest` 检查前端首页、后端受保护岗位接口和 Agent 健康接口。结果：分别为 HTTP 200、401、200。后端 401 为未登录时的预期响应。

**影响范围：** 新实例已使用当前工作区代码对外服务，数据库无新迁移。

**遗留问题：** 未以用户账号提交真实简历生成计划，模型端到端结果未验证。

---

### [2026-11-21 14:24] 按 Figma 梳理专项训练页面与导航

**任务目标：** 将用户确认的 Figma 浅深原型接入现有专项训练前端。

**执行行为：** 核对首页画板和各流程图稿，保留既有计划、出题、答题、错题和任务追踪接口。梳理默认计划、训练单元详情、整组出题和新建计划的路由状态，为独立练习页面提供共享顶栏与主题切换。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/frontend/src/components/training/TrainingFlowHeader.vue` | 训练流程返回、上下文及浅深切换 | 对应原型的独立流程顶栏 |
| 修改 | `project/frontend/src/layouts/MainLayout.vue`、`project/frontend/src/layouts/components/Header.vue` | 专项训练首页的面包屑与布局、练习流程专注布局 | 支持工作区与独立流程 |
| 修改 | `project/frontend/src/views/training/TrainingView.vue` | 计划、单元、出题和错题导航与表单校验 | 复用现有业务调用 |

**执行命令与验证：** 使用 `rg` 与 `Get-Content` 核查现有路由、接口与视图，并用 Figma 截图核对主画板。模板和交互回归待完成。

**影响范围：** 前端专项训练及相关主框架分支。日志追加到实际工作树的 `docs/功能资料`。

**遗留问题：** 继续完成视图、适配答题和复盘，随后执行构建与浏览器验证。

---

### [2026-11-21 14:33] 完成训练页面、答题与复盘布局

**任务目标：** 实现 Figma 中的训练工作区和独立流程，并保留现有业务入口。

**执行行为：** 首页按下一项训练、训练单元和当前计划组织。计划库、单元详情、新建计划、配置题组和错题解析使用独立页面。继续复用题型分配、题组校验、草稿、任务跟踪和旧版会话。答题改为主答题区与右侧题号进度，结果保留逐题反馈且可筛选需要复盘的题。已评估练习刷新时重读结果，结果查询失败时保留草稿。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/views/training/TrainingView.vue` | 首页、计划库、单元、出题、新建计划、错题解析及响应式样式 | 落地 Figma 页面 |
| 修改 | `project/frontend/src/views/training/TrainingSessionView.vue` | 答题进度侧栏、评估摘要、逐题筛选和刷新恢复 | 落地答题及复盘设计 |
| 修改 | `project/frontend/src/views/training/PracticePrepareView.vue` | 复用流程顶栏并保留原验证与重试 | 连接异步出题 |
| 修改 | `project/frontend/src/components/training/QuestionRenderer.vue` | 调整题干层级和间距 | 对齐答题画板 |

**执行命令与验证：** 首次 `npm.cmd run type-check` 发现岗位选项标题可能为 null，已加回退文本。`git diff --check` 未报告空白错误，Git 提示部分文件换行可能转换。最新构建和浏览器验收待执行。

**影响范围：** 专项训练前端。现有 API、任务契约和数据库未改动。

**遗留问题：** 验证页面重载、出题到复盘、错题重练以及浅深主题。

---

### [2026-11-21 14:39] 完成专项训练兼容性与浏览器验收

**任务目标：** 确认 Figma 版本已连接既有训练流程，并验证桌面、手机与历史练习链接。

**执行行为：** 补齐已提交题组的只读状态、错题详情重载与旧版已完成会话的状态映射。自动生成的全局组件类型只移除本次衍生项，保留原有改动。核对本机开发站点是否已提供新版训练组件。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/api/training.ts` | 将旧版已完成会话映射为已评估 | 打开历史练习时正确读取结果 |
| 修改 | `project/frontend/src/views/training/TrainingView.vue`、`project/frontend/src/views/training/TrainingSessionView.vue` | 恢复错题详情、锁定已提交答案、使用服务端复盘条件 | 保持刷新与旧数据兼容 |
| 修改并恢复生成项 | `project/frontend/src/components.d.ts` | 移除本次 Vite 自动添加的全局组件声明 | 训练流程顶栏已显式导入 |

**执行命令与验证：**

- `npm.cmd run build -- --outDir <临时路径>` 通过，含 `vue-tsc --noEmit`。Vite 报告主包体积提示。
- Playwright 与 Chrome 使用模拟 API。验证默认计划、计划切换、单元详情、阅读自评、题型合计不符时禁止提交、异步出题、三种题型作答、交卷取消、逐题复盘筛选、报告和错题刷新、单题重练、选简历生成计划、旧版已完成会话、主题切换和 390 像素布局。首轮测试点击 Element Plus 下拉的内部输入被显示文本遮挡，改为点击控件外层后通过。最终运行退出码 0。
- `Invoke-WebRequest` 读取本机 5173 和 5184 的训练组件，返回 HTTP 200，确认包含新版布局。5183 未建立连接，验收使用独立的 5198 端口，测试服务用完即退出。

**影响范围：** AI-JOB 专项训练前端。本机 5173 已加载新版源文件，未部署构建产物。

**遗留问题：** 浏览器验证使用模拟接口，未在真实账号发起出题、评分或计划生成。

---

### [2026-11-21 19:21] 修复整组出题查询中的同名列

**任务目标：** 修复题量分配正确时点击「生成并开始练习」仍返回 HTTP 500 的问题。

**执行行为：** 核对正在运行的 Java 日志，确认 `POST /api/v1/learning/practice-sets` 在 `LearningMapper.usedSessionQuestionIds` 执行 `SELECT question_id` 时被 MySQL 拒绝。关联的 `learning_session_question` 与 `learning_session` 均有 `question_id`。将查询列限定为 `sq.question_id`，保留会话所属用户过滤。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/backend/src/main/java/com/aijob/backend/learning/LearningMapper.java` | 显式查询题组明细的 `sq.question_id` | 消除列歧义并保持历史题目去重 |

**执行命令与验证：** `rg` 与 `Get-Content` 核对实际日志中的 SQL、MyBatis 调用点及异常。已修正源代码，MySQL 和服务验收待执行。

**影响范围：** 整组出题在创建任务前的历史查重。没有引入数据库迁移。

**遗留问题：** 验证 SQL 与实际出题，并更新正在监听 8080 的后端实例。

---

### [2026-11-21 19:25] 执行真实 MySQL 回归并重启 Java 后端

**任务目标：** 确认 MyBatis 查询在真实库执行通过，并让本地运行实例加载修复。

**执行行为：** 用现有只读 MySQL 测试类增加回归用例，使用测试 UUID 触发同一查询。重启前确认业务库版本为 V18，查询无排队或运行中的 AI 任务。重启原 8080 后端并将 Flyway 目标保持在 V18，检查新实例启动日志。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/backend/src/test/java/com/aijob/backend/DatabaseQueryTest.java` | 新增真实 MyBatis 历史题目查询测试 | 覆盖同名列导致的 MySQL 错误 |
| 新增 | `project/backend/target/local-restarts/practice-500-20261121-192408/` | 记录本地 Java 重启的输出，目录由 Git 忽略 | 核实 Flyway 和服务启动 |

**执行命令与验证：** `mvn.cmd -o -B -Dtest=DatabaseQueryTest#practiceHistoryQueryWorksWhenBothTablesHaveQuestionId -DmysqlIt=true -Dapp.agent.scheduler-enabled=false test`，1 项通过。Java 使用 `spring-boot:run` 启动，Tomcat 监听 8080，Flyway 报告无需迁移，前端和 Agent 就绪端点返回 HTTP 200。

**影响范围：** 本地 Java 后端进程。没有执行新迁移。

**遗留问题：** 使用隔离测试账号通过浏览器验证题组完整生成。

---

### [2026-11-21 19:28] 接通整组出题与交卷评分的 Agent 派发

**任务目标：** 修复初始 500 后端到端验收发现的任务派发失败，并覆盖交卷时同类断点。

**执行行为：** 测试账号在 Chrome 提交 4 道选择题、3 道填空题、3 道主观题，Java 已返回 HTTP 202。任务随后在 Outbox 派发失败。核对代码发现 Java 的操作映射和 Agent HTTP 路由均缺少整组出题与评分，虽已有对应处理器。增加对应路由，复用原有内部鉴权、幂等和任务派发机制。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/backend/src/main/java/com/aijob/backend/task/WebClientAgentClient.java` | 增加整组出题与评分的操作映射 | 让 Outbox 能发送请求 |
| 修改 | `project/agent/app/api.py` | 添加 `/internal/ai/v1/learning/practice/questions` 和 `/internal/ai/v1/learning/practice/evaluate` | 将请求交给既有生成和评价处理器 |
| 修改 | `project/agent/tests/test_practice_handlers.py` | 新增经鉴权 HTTP 接收到任务成功的回归用例 | 覆盖内存和 SQLite 两种存储 |

**执行命令与验证：** 真实浏览器首轮确认出题请求为 HTTP 202，任务因缺失派发路由失败。`rg` 核对 Java 路由映射与 Agent 应用路由。修复后的定向测试和服务复测待执行。

**影响范围：** 整组出题、整组交卷评分以及相关任务重试。

**遗留问题：** 重启 Java 和 Agent 后，对 10 题配置以及交卷评分继续做真实浏览器验证。

---

### [2026-11-21 19:31] 验证派发接口并加载修复

**任务目标：** 验证出题及评分的新派发入口，并让本机 Java 和 Agent 加载改动。

**执行行为：** 分别执行 Java 和 Agent 定向测试。确认 Agent 无活动任务后，重启 8080 Java 与 8100 Agent，保留本机原有代理与 V18 迁移目标。首轮派发失败验收已退出，清理了该轮独立测试账号与记录。

**文件变更：** 新增 `project/backend/target/local-restarts/practice-dispatch-20261121-193007/` 下的运行日志，目录由 Git 忽略。

**执行命令与验证：** `python -m pytest -o addopts= -q tests/test_practice_handlers.py`，8 项通过，含内存与 SQLite 的实际 ASGI 派发。Maven 定向运行 `DatabaseQueryTest` 的回归用例、`PracticeServiceGradingTest` 与 `OutboxSchedulerTest`，共 5 项通过。Java 和 Agent 均已启动，Flyway 报告无需迁移。

**影响范围：** 整组练习的 Java/Agent 派发及本机服务。

**遗留问题：** 正在执行真实页面的 10 题生成及评分复测。

---

### [2026-11-21 19:32] 完成真实浏览器整组出题和交卷验收

**任务目标：** 用截图相同的 Redis Stream、AI 应用开发工程师、进阶和 10 道题设置确认修复生效。

**执行行为：** 使用临时测试账号和合成训练计划，Chrome 连接本机 5173 前端和 8080 Java 后端，通过实际 8100 Agent 和 DeepSeek 生成题目。逐题输入合成答案后交卷，确认结果页显示并读取完整评估。测试结束后仅清理该轮创建的 MySQL 测试账号与相关数据。

**文件变更：** 无新增源代码变更。本机服务日志记录了运行过程，Agent SQLite 留有正常运行历史。

**执行命令与验证：** 通过 Playwright、Chrome 与实际服务交互，整组出题和交卷请求均返回 HTTP 202，两个异步任务均为 `SUCCEEDED`。答题页实得 10 题，4 道选择、3 道填空、3 道主观。结果页有 10 道题的评估，其中 7 道客观题和 3 道主观题。测试账号清理验证通过。`git diff --check` 无新增空白错误。

**影响范围：** 本机专项训练整组出题、答题和交卷评分，业务库仍为 V18。

**遗留问题：** 未代用户在原账号中创建练习或交卷。

---
