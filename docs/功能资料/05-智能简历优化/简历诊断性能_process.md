# 简历诊断性能开发记录

### [2026-11-20 21:32:07] 修复诊断耗时和结果被拒后持续生成

**任务目标：**
缩短简历诊断等待，修复结果回传被拒后只能等超时的问题。

**执行行为：**

1. 对齐 Java 和 Agent 日志。原任务派发只用 17 毫秒，DeepSeek 推理用 96.264 秒，输出计量 6,569 tokens。Agent 回调时 Java 因一条引用与快照不完全相同而返回 422，重试直到任务过期。
2. 只读核对本机 Agent 快照及结果。7 条引用中 6 条完全匹配，剩余 1 条仅在忽略格式空白后匹配。未输出简历原文或模型建议。
3. 针对诊断关闭模型深度思考并要求优先短引用。对仅空白不同的引用取回任务快照中的实际连续片段，保留原换行，真正不存在的引用继续拒绝。
4. 对已验证身份、操作和输入版本的诊断回调，内容不合法时立即记录可重试的失败，并更新诊断资源。补充相关回归用例。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/app/handlers.py` | 诊断关闭深度思考并从原始快照回填引用 | 缩短生成耗时、对齐 Java 精确校验 |
| 修改 | `project/agent/app/skills/prompts/resume_diagnose.md` | 优先给出重点问题、引用短连续原文 | 减少不必要的生成和格式改写 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/task/TaskResultService.java` | 诊断结果结构无效时持久化失败和错误事件 | 避免任务继续显示生成中 |
| 修改 | `project/agent/tests/test_resume_project.py`、`project/backend/src/test/java/com/aijob/backend/task/TaskResultServiceTest.java` | 覆盖思考开关、PDF 格式回填和失败终态 | 防止同类回归 |
| 新增 | `docs/功能资料/05-智能简历优化/简历诊断性能_process.md` | 开发记录 | 本工作区对应目录存在，规范中的另一工作区不存在 |

**执行命令与验证：**

- 命令：`rg -n` 查看本机日志并使用 Python `sqlite3` 只读核对引用。
- 结果：确认两阶段耗时及引用格式差异；修改已完成，回归待执行。

**影响范围：**
简历诊断生成与回调。简历定向优化和其他模型任务保留原有策略。

**遗留问题：**
执行 Python、Java 回归并验证运行实例加载修复。

---

### [2026-11-20 21:34:13] 验证原任务引用并实测合成诊断

**任务目标：**
验证现场失败样本、回调终态与真实模型路径。

**执行行为：**

1. 在本机只读重放原任务已生成的结果，逐字匹配从 6/7 变为 7/7，无需再次发送用户简历。
2. 对一份合成简历实际调用当前 DeepSeek，校验所有引用均来自原文。合成样本不同于原任务，耗时不作严格同比。
3. 验证 Java 结果回调与简历诊断处理器相关测试。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/05-智能简历优化/简历诊断性能_process.md` | 追加测试记录 | 留存现场证据和性能实测 |

**执行命令与验证：**

- 命令：`mvn -o -B "-Dtest=TaskResultServiceTest,ResumeOptimizationResultHandlerTest" "-DforkCount=0" test`。结果：7 项通过。
- 命令：Python 只读查询 SQLite 后调用 `ResumeDiagnoseHandler.validate_business`。结果：7 条全部命中原文，Agent 当时无排队或运行中的任务。
- 命令：Python 调用 `ResumeDiagnoseHandler.execute`。首次合成请求元数据缺少必填字段，发送前被校验拒绝；修正后 DeepSeek 仅调用一次，耗时 11.765 秒，生成 629 个输出 token，5 条引用全部逐字匹配。
- 命令：`git -c core.fsmonitor=false diff --check`。结果：通过，仅已有换行格式提示。

**影响范围：**
合成样本用于真实模型实测，原任务仅本机只读回放。

**遗留问题：**
等待 Python 定向回归，重启 Agent 和 Java 加载修复。

---

### [2026-11-20 21:35:06] 通过 Python 回归并启动修复后的服务

**任务目标：**
完成验证，并让当前开发页面连接修复后的 Agent 和 Java。

**执行行为：**

1. 沙箱内的 Python 测试未及时返回，改用本机环境并显式加载 pytest-asyncio 插件执行定向测试。
2. 通过端口、启动命令和只读 SQLite 计数确认原实例归属，以及重启前无排队或运行中的 Agent 任务。
3. 按既有配置重启 8080 Java 和 8100 Agent，前端继续使用原实例。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/target/local-restarts/resume-diagnosis-20261120-213449/` | 本地 Java 与 Agent 启动日志 | 核实修复生效 |
| 修改 | `docs/功能资料/05-智能简历优化/简历诊断性能_process.md` | 追加测试和重启记录 | 记录实际动作 |

**执行命令与验证：**

- 命令：`python -m pytest -p pytest_asyncio.plugin tests/test_resume_project.py tests/test_llm.py`。结果：24 项通过。
- 命令：`Get-NetTCPConnection`、`Stop-Process`、`Start-Process` 运行 `mvn -o -B -DskipTests spring-boot:run` 和 `python project/agent/run.py`。结果：两个新实例均已发起启动。

**影响范围：**
本机 Java 与 Agent 短时重启。业务代码与模型设置以当前工作区为准。

**遗留问题：**
核验两个新实例的 HTTP 状态与前端代理。

---

### [2026-11-20 21:35:56] 确认修复后服务在线

**任务目标：**
确认现用简历诊断页、Java 和 Agent 已连通。

**执行行为：**
复查三个端口及 HTTP 响应。关闭本轮先前卡住的沙箱 pytest 进程，已通过的本机测试结果保留。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/data/agent_runs.db-shm` | 重启打开 SQLite 时更新共享内存文件 | 正常运行时行为 |
| 修改 | `docs/功能资料/05-智能简历优化/简历诊断性能_process.md` | 追加上线检查 | 记录最终状态 |

**执行命令与验证：**

- 命令：HTTP GET `/resume-optimization`、`/api/v1/resume-optimizations/diagnoses`、`/readyz`。结果：页面 200，受保护的诊断接口直连和代理均为预期的匿名 401，Agent 就绪 200。
- 命令：核对 Java 启动日志和 `git -c core.fsmonitor=false diff --check`。结果：数据库仍为 V17，无新迁移；本次文件差异无空白错误。

**影响范围：**
当前本机页面的新诊断使用修复后的 Java 和 Agent。

**遗留问题：**
截图中的任务此前已过期。只读复核了当时的结果，未用用户简历重新发起线上任务；实际耗时随简历和模型服务而变。

---

# 简历多维度评分与 PDF 报告（resume-quality-v1）

### [2026-11-20 22:05] 阶段一：Agent 侧评分 schema 与提示词改造

**任务目标：**
RESUME_DIAGNOSE 快速模式一次生成同时返回诊断建议与五维度评分（resume-quality-v1），评分引用沿用快照空白回填与逐字校验口径。

**执行行为：**

1. schemas.py 新增 `ResumeAssessment`（ruleVersion + 5 dimensions + strengths）、`ResumeAssessmentDimension`（code 枚举 / 整数分 / rationale / 可空 quote）、`ResumeAssessmentStrength`，并固化 `RESUME_ASSESSMENT_DEFAULT_RULES`（30/20/20/15/15）；`ResumeDiagnosePayload` 新增可选 `assessmentRules`（维度 code 不得重复）；`AIResumeDiagnoseResult` 新增可选 `assessment`。
2. 提示词 `resume_diagnose.md` 升级为 `resume-diagnose-v2`：新增五维度评分表、分档口径（0 分 / 1-49% / 50-79% / 80-100%）、零分 quote 为 null 且 rationale 须说明缺什么、正分与优势必须有原文依据。
3. `ResumeDiagnoseHandler`：把原诊断项 quote 回填逻辑抽为模块级 `_restore_quote()` 复用；新增 `_validated_assessment()` 校验维度重复/缺失/越界/正分无 quote，assessment 缺失直接抛 `HandlerValidationError` 触发修复重试（避免无效回调）。`operations.py` 版本号同步 v2；`skills/tools.py` 把 payload 的 assessmentRules 传入模型输入。
4. fake_model `_resume_diagnose` 补充合法 assessment 输出；测试补评分校验用例（缺 assessment/维度缺失/重复/越界/正分无 quote/伪造优势引用/快照规则重复 code）。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/app/schemas.py` | 新增评分 schema 与默认规则常量 | 评分数据契约 |
| 修改 | `project/agent/app/skills/prompts/resume_diagnose.md` | v1 → v2，新增评分规则与输出结构 | 指导模型输出评分 |
| 修改 | `project/agent/app/handlers.py` | 抽出 `_restore_quote`、新增评分校验 | 复用回填、Agent 侧提前拒绝 |
| 修改 | `project/agent/app/operations.py` | PROMPT_VERSIONS 升 v2 | 与提示词一致 |
| 修改 | `project/agent/app/skills/tools.py` | 模型输入透传 assessmentRules | 规则随任务快照下发 |
| 修改 | `project/agent/app/fake_model.py` | 诊断替身输出评分 | 测试契约对齐 |
| 修改 | `project/agent/tests/test_resume_project.py`、`project/agent/tests/factories.py` | 新增评分用例、OPERATION_CASES 增 assessment 键 | 覆盖评分链路 |

**执行命令与验证：**

- 命令：`conda run -n ai-job python -m pytest tests -q --ignore=tests/test_job_search_render.py`
- 结果：通过（505 通过 / 1 skip，skip 为既有 Playwright 依赖）
- 说明：首次运行有 1 项失败（重复维度测试先被 max_length=5 拦截），修正测试构造为 5 项内含重复 code 后通过。

**影响范围：**
仅 RESUME_DIAGNOSE；RESUME_OPTIMIZE 携带的 diagnosis payload 只用 items，不受影响。

**遗留问题：**
无。

---

### [2026-11-20 22:20] 阶段二：Java 侧校验求和、可空列迁移与导出接口

**任务目标：**
Java 校验评分（维度/整数/越界/引用）后求和落库；详情透出可空 assessment；新增诊断报告 PDF 导出接口（404/409 语义）。

**执行行为：**

1. 新增 Flyway `V18__add_resume_diagnosis_assessment.sql`：`resume_diagnosis` 加可空 `assessment_json`；`completeDiagnosis` 同一 UPDATE 保存 items 与评分，`retryDiagnosis` 清空该列。
2. `ResumeOptimizationResultHandler.diagnose()`：新增 `assessment()` 校验——ruleVersion 必须为 resume-quality-v1、维度恰好 5 项不重不漏、score 必须为 Integer 且 0..满分、正分 quote 逐字 `contains` 校验、优势 quote 同样校验；通过后 Java 侧求 `totalScore`、补 `assessedAt`，与 items 同事务保存。校验失败抛 `AI_SCHEMA`，沿用 TaskResultService 既有的"诊断回调校验失败立即终态"流程。
3. `ResumeOptimizationService`：诊断 payload 附带 `assessmentRules` 快照；`diagnosisVo` 透出可空 `assessment`（分项补 label/maxScore）；新增 `exportDiagnosis()`（他人记录 404、非 READY 或无评分 409）与 `assessmentPdf()`（复用 `ResumeExportService.pdf` 的中文字体/换行/分页，报告含简历版本、评分时间、规则版本、总分、分项理由与引用、优势、优先改进建议）；Controller 新增 `GET /api/v1/resume-optimizations/diagnoses/{diagnosisId}/export`。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/src/main/resources/db/migration/V18__add_resume_diagnosis_assessment.sql` | 可空 assessment_json 列 | 评分持久化，旧记录保持 NULL |
| 修改 | `project/backend/.../resumeoptimization/ResumeOptimizationMapper.java` | complete/retry 增列 | 同事务保存与重试清空 |
| 修改 | `project/backend/.../resumeoptimization/ResumeOptimizationResultHandler.java` | 评分校验与求和 | 信任边界：Java 最终校验 |
| 修改 | `project/backend/.../resumeoptimization/ResumeOptimizationService.java` | 规则快照、VO、导出 | 接口与页面数据 |
| 修改 | `project/backend/.../resumeoptimization/ResumeOptimizationController.java` | 导出端点 | 下载入口 |
| 修改 | `project/backend/.../resumeoptimization/ResumeOptimizationResultHandlerTest.java` | 12 项（含评分 8 类异常与正常路径） | 回归 |
| 新增 | `project/backend/.../resumeoptimization/ResumeOptimizationServiceDiagnosisExportTest.java` | 导出 404/409/PDF 内容/多页/旧记录无评分 | 回归 |

**执行命令与验证：**

- 命令：`mvn -o -q "-Djacoco.skip=true" "-DforkCount=0" "-Dtest=ResumeOptimizationServiceDiagnosisExportTest,ResumeOptimizationResultHandlerTest,ResumeExportServiceTest" test`
- 结果：通过（20 项，1 skip 为既有 macOS 字体假设）
- 说明：过程修复了两个真实缺陷——`assessmentVo` 的 for-each 泛型转换在 ECJ 下编译失败（此前 `mvn compile` 曾被 stderr 噪音误判通过）；测试断言索引 3 的维度应为 0 分（quote 为 null）而初版写成 10 分。另按 PowerShell 参数加引号口径执行（`"-Djacoco.skip=true"` 等）。

**影响范围：**
诊断完成/重试/详情/导出链路；旧诊断（无 assessment）详情返回 `assessment: null`，前端按历史记录提示，需用户重新发起诊断获取评分。

**遗留问题：**
全量 Java 测试受既有 JaCoCo fork 问题限制未跑，仅跑受影响用例（与本机既有口径一致）。

---

### [2026-11-20 22:30] 阶段三：前端评分展示与报告下载

**任务目标：**
诊断详情展示总分、五项得分、评分理由、原文依据、优势和改进建议；READY 且有评分时启用下载报告。

**执行行为：**

1. `types.ts` 新增 `ResumeAssessmentVO`/`AssessmentDimension`/`AssessmentStrength`，`ResumeDiagnosisVO` 增可空 `assessment`；`optimizations.ts` 新增 `exportDiagnosis()`（downloadBinary）。
2. `ResumeOptimizationView.vue`：诊断详情面板新增评分区块（总分 +100、规则版本、评分时间、五维度卡片含进度条/理由/原文引用 blockquote、优势卡片）；`READY` 无评分的历史诊断显示引导提示；标题栏新增「下载评分报告 PDF」按钮（`canExportAssessment` 控制 READY+有评分），失败用 el-alert 展示；新增配套样式。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/api/types.ts` | 评分类型 | 数据契约 |
| 修改 | `project/frontend/src/api/optimizations.ts` | exportDiagnosis | 下载 API |
| 修改 | `project/frontend/src/views/resume-optimization/ResumeOptimizationView.vue` | 评分区块 + 下载按钮 + 样式 | 页面功能 |

**执行命令与验证：**

- 命令：`npm run type-check`；`npm run build`
- 结果：均通过（build 仅既有大 chunk 告警）

**影响范围：**
仅诊断详情展示；优化稿导出等既有功能未动。

**遗留问题：**
登录态浏览器点击链路（发起诊断→看评分→下载报告）本地无测试账号未实测，需真实环境验收。

---

### [2026-11-20 22:40] 阶段四：V18 迁移隔离库验证

**任务目标：**
在隔离库验证 V18 全量迁移与 V17+旧数据增量升级兼容。

**执行行为：**

1. 用本地仓库 Flyway 11.14.1 jar 写驱动 `target/flyway-check/FlywayMigrateCheck.java` 执行真实迁移（V13 依赖 `flyway_schema_history`，手工 source 方式不可用，首试失败后废弃）。
2. 干净库全量：`aijob_v18_fresh_20261120` 执行 V1→V18，18 个迁移全部成功。
3. 增量升级：`aijob_v18_upgrade_20261120` 先迁到 V17，插入旧诊断数据（UTF-8 SQL 文件方式，规避 PowerShell 中文命令行编码损坏），再升级 V18。
4. 验证旧数据：升级后旧记录 `assessment_json IS NULL`、`items_json` 可读；新列可写可读（ruleVersion/totalScore 回读正确）。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/target/flyway-check/FlywayMigrateCheck.java`、`legacy_data.sql`、`verify_legacy.sql` | 迁移验证驱动与数据 | target 下临时验证产物，不入库 |

**执行命令与验证：**

- 命令：`java -cp <flyway+jackson+mysql jars> FlywayMigrateCheck <db> [target]`
- 结果：全量 18 迁移成功；增量 1 迁移成功（V17→V18）；旧数据 NULL 兼容、新列读写通过
- 说明：驱动缺 jackson-databind 首次失败，补齐 classpath 后通过；过程创建 `aijob_v18_check_20261120`（手工 source 半途废弃）、`aijob_v18_fresh_20261120`、`aijob_v18_upgrade_20261120` 三个临时库，**未清理**，后续可手动删除。

**影响范围：**
仅隔离临时库；业务库（3307/career_business）未动，发布时按"先更新 Agent（兼容旧任务）→ 再迁移 → 再更新 Java/前端"顺序执行。

**遗留问题：**
本地业务库尚未应用 V18（当前运行实例连的是旧库，新代码启动时 Flyway 会自动执行）。

---

### [2026-11-20 22:55] 阶段五：真实 DeepSeek 链路验证与提示词修复

**任务目标：**
验证合成简历经真实 DeepSeek 单次调用返回合法评分（五维度、引用逐字命中、单次调用、记录耗时与输出量）。

**执行行为：**

1. 新增 `project/agent/scripts/verify_resume_assessment.py`：合成简历（含 `\r\n` 覆盖空白差异回填路径）→ `ResumeDiagnoseHandler.execute` → 断言 attempts=1、五维度齐全、整数分不越界、正分/优势/诊断项引用逐字命中快照。
2. 首次运行失败：`STRUCTURE_CLARITY` 维度与一条优势的 quote 是跨段拼接（把「项目经历」「实习经历」等不相邻标题行拼成一条），去空白匹配也无法命中；其余引用均 OK。
3. 提示词两次强化：① 明确 quote 从首字符到末字符必须与原文完全一致、禁止跳行拼接；② 评分维度只引用一个短片段，结构类维度只引用单个章节标题行。第二次强化后复跑通过。
4. 临时调试脚本 `debug_assessment_quotes.py`（逐条核对 quote 命中）用后已删除。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/agent/scripts/verify_resume_assessment.py` | 真实链路冒烟脚本 | 可复现验证 |
| 修改 | `project/agent/app/skills/prompts/resume_diagnose.md` | quote 连续性两处强化 | 修复跨段拼接引用 |
| 删除 | `project/agent/scripts/debug_assessment_quotes.py` | 临时调试脚本 | 定位完成后清理 |

**执行命令与验证：**

- 命令：`conda run -n ai-job python scripts/verify_resume_assessment.py`
- 结果：通过（最终 SMOKE OK）
- 关键输出：`model=deepseek-v4-pro attempts=1 latencyMs=16297 tokens(in/out)=1499/1057 promptVersion=resume-diagnose-v2 totalScore=66`；分项 18/30、12/20、14/20、12/15、10/15；items=4 strengths=2；全部引用逐字命中。
- 对比：上一轮（21:34 记录）无评分时单次调用 11.765s / 629 输出 token；本次带评分 16.297s / 1057 输出 token，仍为单次模型调用，符合"一次生成返回评分和建议"要求。

**影响范围：**
提示词强化对所有诊断任务的引用行为生效（更严格的连续片段要求）。

**遗留问题：**
① 登录态端到端（发起诊断→详情评分→下载 PDF）未实测；② 三个临时验证库（`aijob_v18_check_20261120`、`aijob_v18_fresh_20261120`、`aijob_v18_upgrade_20261120`）未清理，后续可手动删除。
- 补充（22:58）：提示词强化后重跑 `conda run -n ai-job python -m pytest tests/test_resume_project.py tests/test_operations.py -q`，66 项通过，契约断言未受影响，遗留项①之外已全部闭环。

---

### [2026-11-21 10:30] 简历解析阅读顺序与诊断质量升级（resume-quality-v2）

**任务目标：**

按页面位置恢复 PDF 阅读顺序，清理装饰字符；让优势有结构化证据、建议按影响排序且可执行；新诊断使用 `resume-quality-v2`，历史报告保留原分数与规则版本；正常诊断保持一次模型调用。

**执行行为：**

1. Java 文本提取：`FileStorageService` 改用 `PDFTextStripper.setSortByPosition(true)` 并按页提取，输出 `[page:N]` 页边界；新增装饰字符清理（零宽、控制字符、替换字符），保留章节、段落、日期、数值与缩进。
2. 新增 `reExtract`：诊断前优先用已保存原文件按 `pdf-layout-v2` 重新提取；原文件缺失/不可读时回退 `resume_version.extracted_text`，并在诊断快照标记 `readOrderVerified=false`、`textLayoutVersion=legacy-v1`。
3. V19 迁移：`resume_version.text_layout_version`；`resume_diagnosis.source_text / text_layout_version / read_order_verified`。诊断完成时把实际授权文本快照写入诊断记录；关联该诊断的定向优化复用同一份 `source_text`。
4. Agent 契约升级：`resume-quality-v2`；维度/建议/优势保留主引用并支持最多 2 条补充引用；建议新增 `impact`、`example`、`pendingVerification`；优势新增 `approach`、`contribution`、`result`；提示词升级 `resume-diagnose-v3`，明确诊断时点日期核查、正常并行经历不得视为问题、解析噪声不得占据高优先级。
5. Java 结果处理：接受 v1/v2 规则版本并原样保存；逐条校验主引用与补充引用；五维总分仍由 Java 求和；历史 v1 诊断与 PDF 继续可读。
6. 前端：展示补充引用、影响、修改动作、示例、待核实信息与优势三要素；`readOrderVerified === false` 时提示阅读顺序未复核；PDF 报告同步标注。
7. 新增 `evaluate_resume_diagnosis.py`，用 3 份不同方向的合成简历对比改前/改后。改前使用 `git show HEAD` 的旧提示词与旧输出结构，改后走当前正常链路。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/backend/src/main/java/com/aijob/backend/resume/FileStorageService.java` | 按页+位置排序提取、装饰字符清理、原文件重提 | 恢复阅读顺序 |
| 新增 | `project/backend/src/main/resources/db/migration/V19__add_resume_text_layout.sql` | 布局版本、授权文本快照、阅读顺序标记 | 诊断与优化共用同一快照 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/resume/ResumeMapper.java`、`ResumeService.java` | 上传时记录布局版本 | 新上传与历史诊断口径一致 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/resumeoptimization/ResumeOptimizationService.java` | 诊断重提/回退、优化复用快照、VO 与 PDF 提示 | 阅读顺序与报告一致性 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/resumeoptimization/ResumeOptimizationMapper.java`、`ResumeOptimizationResultHandler.java` | v1/v2 兼容、多引用校验、快照落库、总分计算 | 新规则落库与历史兼容 |
| 修改 | `project/agent/app/schemas.py`、`handlers.py`、`operations.py`、`fake_model.py`、`skills/tools.py`、`skills/prompts/resume_diagnose.md` | v2 契约、补充引用、结构化优势与可执行建议、诊断时点 | Agent 输出质量 |
| 修改 | `project/agent/tests/test_resume_project.py` | v2、多引用、优势证据、回退规则用例 | 契约回归 |
| 新增 | `project/agent/scripts/evaluate_resume_diagnosis.py` | 改前/改后质量与耗时评测 | 验收证据 |
| 新增 | `project/backend/src/test/java/com/aijob/backend/resume/FileStorageServiceLayoutTest.java` | 合成 PDF/DOCX 阅读顺序、装饰字符、日期数值保真、原文件缺失 | 解析回归 |
| 修改 | `project/backend/src/test/java/com/aijob/backend/resumeoptimization/ResumeOptimizationResultHandlerTest.java`、`ResumeOptimizationServiceDiagnosisExportTest.java` | v2、多引用、快照、历史 PDF 提示 | Java 回归 |
| 修改 | `project/frontend/src/api/types.ts`、`views/resume-optimization/ResumeOptimizationView.vue` | 补充引用、影响/动作/示例/待核实、阅读顺序提示 | 详情页与 PDF 同源 |
| 新增 | `docs/功能资料/05-智能简历优化/resume-diagnosis-eval-20261121.json` | 三份合成样本改前/改后评测原始结果 | 验收证据留存 |

**执行命令与验证：**

- 命令：`python -m pytest tests/test_resume_project.py tests/test_files.py tests/test_skills.py tests/test_misc.py -q`
- 结果：85 项通过。
- 命令：`python -m pytest tests -q --disable-warnings --ignore=tests/test_job_search_render.py`
- 结果：退出码 0（全量通过；渲染用例因本机缺 Playwright 浏览器依赖继续排除）。
- 命令：`mvn -o -B "-Dtest=FileStorageServiceLayoutTest,ResumeOptimizationResultHandlerTest,ResumeOptimizationServiceDiagnosisExportTest,ResumeResultHandlerTest,ResumeServiceCorrectionTest,ResumeExportServiceTest,TaskResultServiceTest" "-Djacoco.skip=true" "-DforkCount=0" test`
- 结果：41 项通过、1 项跳过（`ResumeExportServiceTest` 既有字体用例），BUILD SUCCESS。
- 命令：`npm run type-check`、`npm run build`
- 结果：通过（仅有既有大 chunk 告警）。
- 命令：本地 MySQL `aijob_v19_check_20261121`：手工执行 V1→V18（V13 需补 `flyway_schema_history` V12 记录），插入历史 `resume_version` / `resume_diagnosis` / `ai_task`，再执行 V19。
- 结果：旧 `resume_version.text_layout_version` 为 NULL；旧诊断 `source_text`、`text_layout_version` 为 NULL、`read_order_verified=0`；更新新列后读取为 `历史简历文本 / pdf-layout-v2 / 1`，历史数据可读、新列可写。
- 命令：`python scripts/evaluate_resume_diagnosis.py`
- 结果（3 份合成简历，`deepseek-v4-pro`，最终代码复跑）：
  - 改后：每份 `calls=1`；耗时 22.766s / 21.140s / 29.277s；总分 64/66/70；`quotesAllInText=true`；`impactCoverage=1.0`、`actionCoverage=1.0`、`exampleOrPendingCoverage=1.0`；优势 3/3/3 条且三要素覆盖 1.0；跨章节优势 3/2/2 条；章节覆盖 3/4、4/4、3/3；前 3 项无解析噪声、无正常时间线误报。
  - 单次调用口径：backend/frontend `attempts=1`；data_analyst `attempts=2`，即模型首次输出未通过结构/引用校验触发一次 repair 后成功（异常路径保留原有 1 次修复调用），该样本单独复跑为 `attempts=1`。正常诊断路径仍为一次模型调用。
  - 改前：耗时 6.917s / 6.720s / 6.076s，但本次调用均未返回 assessment（`strengthCount=0`、无 impact/example）；Java 后端样本把“教育背景与实习经历时间重叠”列为 NOTICE，属于正常并行经历误报。
  - 结论边界：改前未返回评分，因此不计算“评分准确率提升”；只能确认改后满足结构化要求且正常路径为单次模型调用。改后耗时增加对应更多输出（优势、影响、示例、待核实）以及 1 次异常 repair，不是重复调用正常任务。
- 原始结果：`docs/功能资料/05-智能简历优化/resume-diagnosis-eval-20261121.json`。

**影响范围：**

新诊断统一使用 `resume-quality-v2` 与按页布局文本；历史诊断保留原 `resume-quality-v1` 分数与规则版本，详情页和 PDF 继续可读。关联新诊断的定向优化使用同一份授权文本快照；未关联诊断时沿用版本文本。

**遗留问题：**

① 未重启 Agent/Java，登录态浏览器端到端（上传 PDF→诊断→PDF 下载→定向优化）未实测；真实业务库的 V19 将在 Java 重启时由 Flyway 自动执行。② 临时库 `aijob_v19_check_20261121` 因命令行安全策略禁止 `DROP DATABASE` 未清理，可在 MySQL 客户端手动删除。③ 未创建 Git 提交，工作区既有未提交改动全部保留。

---


### [2026-11-21 10:59] Agent 评分点契约升级

**任务目标：**
让新诊断按独立评分点定档，并兼容旧版任务快照。

**执行行为：**

1. 新增 v3 档位、综合评价和建议所属维度的校验，保留旧版分项得分及异常修复流程。
2. 更新提示词和模拟模型，正常路径在一次生成中返回全部报告字段。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/app/schemas.py`、`handlers.py`、`operations.py` | 新增 v3 契约和验证，保留 v1/v2 快照支持 | 限制评分点与档位输入 |
| 修改 | `project/agent/app/skills/prompts/resume_diagnose.md`、`project/agent/app/fake_model.py` | 升级提示词和模拟输出 | 覆盖新旧报告形态 |

**执行命令与验证：**

- 命令：`python -m pytest tests/test_resume_project.py tests/test_operations.py -q`
- 结果：已启动，正在执行。

**影响范围：**
简历诊断 Agent 契约。定向优化可接收有效的空诊断建议列表。

**遗留问题：**
待完成 Java 校验、前端和 PDF，再统一验证。

---

### [2026-11-21 11:05] 评分落库、报告页面和 PDF 改版

**任务目标：**
与 Agent 新契约对接，展示和导出一致的评分、依据与建议。

**执行行为：**

1. Java 统一管理评分权重，核对任务快照和评分点档位，计算总分并写入现有 JSON 字段。详情及 PDF 按记录的规则版本解析满分。
2. 将页面整理为浅色分析与定向优化双标签。分析顶部集中操作和历史抽屉，报告采用独立组件，按得分率绘制雷达图并折叠原文证据。
3. 保留任务查询重试、链接定位和定向稿编辑。新增诊断任务 ID，使历史失败记录可以通过已有任务接口读取原因。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/src/main/java/com/aijob/backend/resumeoptimization/ResumeAssessmentRules.java` | 定义规则版本、满分和评分点 | 共用权重与标签 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/resumeoptimization/ResumeOptimizationResultHandler.java`、`ResumeOptimizationService.java` | 评分入库、历史分母兼容、PDF 与详情字段 | 确保数据一致 |
| 新增 | `project/frontend/src/views/resume-optimization/ResumeDiagnosticReport.vue` | 总览、雷达图、证据明细和分级建议 | 缩短报告首屏 |
| 修改 | `project/frontend/src/views/resume-optimization/ResumeOptimizationView.vue`、`project/frontend/src/api/types.ts` | 双标签、历史抽屉、任务与版本选择、报告类型 | 支持新交互 |

**执行命令与验证：**

- 命令：`git -c core.fsmonitor=false diff --check -- project/agent/app project/backend/src/main/java/com/aijob/backend/resumeoptimization project/frontend/src`
- 结果：通过，仅提示已有行尾格式转换。
- 命令：`npm run type-check`、Maven 定向用例
- 结果：已启动。Java 主代码和测试源码已编译，测试结果待核对。

**影响范围：**
诊断报告、诊断 PDF 与智能简历优化页。继续使用现有数据库字段。

**遗留问题：**
待补齐评分回归用例与浏览器验收。

---

### [2026-11-21 11:14] 定向回归与真实模型对照验收

**任务目标：**
核对评分可解释性、旧报告兼容与导出内容。

**执行行为：**

1. 补充 Agent 的评分点缺失、越界和零建议用例。输出模型继续要求旧报告至少有一条建议，定向优化输入允许新报告的空建议列表。
2. 补充 Java 的档位重算和 v2/v3 网页字段及 PDF 对照用例，覆盖忽略模型提供的错误总分输入。
3. 用同一对合成简历调用真实 DeepSeek。首轮时间重叠误报后，收紧条目自身日期矛盾规则，增加个人职责与成果档位示例，移除输出示例中的固定等级。
4. 基于桌面报告截图将简历标签中的同名文件去重，并给评分点折叠区补充展开指示。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/app/schemas.py`、`handlers.py`、`fake_model.py` | 保持旧契约，兼容零建议及评分点顺序 | 恢复回归通过 |
| 修改 | `project/agent/app/skills/prompts/resume_diagnose.md` | 先看证据再定档，忽略正常同期，取消示例固定分 | 修复真实模型验收发现的问题 |
| 修改 | `project/agent/tests/test_resume_project.py` | 覆盖 v3 空建议、缺失、越界 | 验证 Agent |
| 修改 | `project/backend/src/test/java/com/aijob/backend/resumeoptimization/ResumeOptimizationResultHandlerTest.java`、`ResumeOptimizationServiceDiagnosisExportTest.java` | 覆盖服务端重算与 v2/v3 导出 | 锁定历史兼容 |
| 修改 | `project/frontend/src/views/resume-optimization/ResumeOptimizationView.vue`、`ResumeDiagnosticReport.vue` | 去重版本标签，标注折叠入口 | 提高操作可见性 |

**执行命令与验证：**

- 命令：`D:/Anaconda/envs/ai-job/python.exe -m pytest tests/test_resume_project.py tests/test_operations.py -q --disable-warnings`
- 结果：68 项通过。系统 Python 缺少 pytest-asyncio，改用项目环境。旧版空建议断言首轮发现失效，调整模型后复跑通过。
- 命令：`mvn -o -B -Dtest=ResumeOptimizationResultHandlerTest,ResumeOptimizationServiceDiagnosisExportTest,ResumeExportServiceTest,TaskResultServiceTest -Djacoco.skip=true -DforkCount=0 test`
- 结果：33 项通过，1 项跳过（既有字体用例）。本次显式传入 Mockito Java agent，规避受限环境动态挂载失败。
- 命令：`npm run type-check`、`npm run build -- --outDir <临时构建目录>`
- 结果：通过，有既有的大 chunk 提示。
- 真实模型：`deepseek-v4-pro` 两份合成简历各调用一次，耗时 16,361 ms 与 20,167 ms。总分 37 → 77，经历与项目 4/40 → 36/40，个人职责档位 1 → 4，成果档位 0 → 5。19 条与 17 条引用均命中原文，未生成正常时间重叠的建议。

**影响范围：**
v3 新报告，旧任务按其规则快照处理。只使用合成简历调用真实模型。

**遗留问题：**
桌面已验证历史分页、证据、下载按钮及重新分析。定向优化与窄屏脚本仍在复跑。运行中的既有业务服务未重启。

---

### [2026-11-21 11:16] 双标签浏览器验收与最终检查

**任务目标：**
验收报告页常用操作、反馈以及桌面和窄屏布局。

**执行行为：**

1. 在本机 Vite 启动页面，用已有 Playwright 和 Chromium 加载模拟业务接口，验证报告评分和依据展开、历史分页及旧分母、下载、重新分析、失败后重试、同版本关联定向优化、草稿跨标签保留、诊断／优化直达链接。
2. 对照参考图核对两栏首屏、总分和时间层级、五边雷达与分项、优先级提示、详情折叠和窄屏单列。保留 AI-JOB 浅色主体样式。保存 1440×1050 和 390×844 截图。
3. 浏览器发现 Element Plus 分页弃用提示，改用 size 属性，并复跑验收。版本请求恢复成功后会自动选取最近确认版本。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/views/resume-optimization/ResumeOptimizationView.vue` | 分页改用 size，恢复后默认选中版本 | 清理控制台提示和完善重试体验 |
| 修改 | `project/backend/src/test/java/com/aijob/backend/resumeoptimization/ResumeOptimizationResultHandlerTest.java` | 清理文件末尾空行 | 通过差异格式检查 |

**执行命令与验证：**

- 命令：`npm run dev -- --host 127.0.0.1 --port 5184 --strictPort`；本机 Playwright Chromium 使用模拟 API。
- 结果：通过。验证了报告页标题、历史 v1/v2 与 v3 的不同满分、链接和表单交互、下载及重试。桌面控制台无报错或告警，窄屏无页面横向溢出。
- 命令：`npm run build -- --outDir <最终临时构建目录>`
- 结果：通过，含 Vue 类型检查，仍有既有大 chunk 提示。
- 命令：`D:/Anaconda/envs/ai-job/python.exe -m pytest tests/test_resume_project.py tests/test_operations.py -q --disable-warnings`、定向 `git diff --check`
- 结果：68 项通过，差异格式检查退出 0。

**影响范围：**
本次代码与验证在 `D:/py/AI-JOB`。前端使用现有依赖，评分继续存储在诊断 JSON 中。

**遗留问题：**
浏览器验收使用模拟 API，实际 PDF 文件由 Java 测试解析验证，真实模型由 Agent 合成样本验证。未在现行业务库和用户账号上跑全栈登录态验收，未重启或部署现有业务服务。

---

### [2026-11-21 21:43] 绑定诊断建议与评分点并核验日期

**任务目标：**
使用实际扣分项生成有原文依据的建议，消除跨条目日期同期误报。

**执行行为：**

1. 更新诊断提示词至 v5，要求建议引用所属评分点，Agent 对维度、评分点、档位和原文引用进行校验，违规结果进入已有修正流程。
2. 对可识别的单条日期范围核验起止先后，并把核验档位提供给模型。拦截评分、综合评价和建议中的典型跨条目日期误报。
3. 同步模拟模型的评分点及日期档位。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | project/agent/app/resume_timeline.py | 核验单条日期范围和跨条目误报 | 以原文判定日期矛盾 |
| 修改 | project/agent/app/schemas.py、handlers.py、skills/tools.py | 新增 checkCode 与日期评分点校验 | 建议对应实际扣分 |
| 修改 | project/agent/app/skills/prompts/resume_diagnose.md、operations.py、fake_model.py | 更新模型规则和模拟输出 | 对齐诊断契约 |

**执行命令与验证：**

- 命令：apply_patch
- 结果：完成 Agent 代码修改，待执行回归。

**影响范围：**
新生成的五维度诊断结果。

**遗留问题：**
待完成服务端落库与网页、PDF 展示及验收。

---

### [2026-11-21 21:44] 保存并展示建议对应的评分点

**任务目标：**
让网页与导出的报告引用同一份扣分依据。

**执行行为：**

1. 后端保存 checkCode 并核验评分点归属及得分，对于在途旧结果保留缺失该字段时的兼容。
2. 网页和 PDF 从已保存评分查找评分点标签与分值，在建议标题旁展示。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/backend/src/main/java/com/aijob/backend/resumeoptimization/ResumeAssessmentRules.java、ResumeOptimizationResultHandler.java、ResumeOptimizationService.java | 按评分结果校验、保存及打印评分点 | 统一报告依据 |
| 修改 | project/frontend/src/api/types.ts、project/frontend/src/views/resume-optimization/ResumeDiagnosticReport.vue | 增加可选字段与得分展示 | 支持新旧报告 |

**执行命令与验证：**

- 命令：apply_patch
- 结果：完成后端与前端修改，待执行回归。

**影响范围：**
简历诊断新结果及评分报告，沿用现有数据库字段。

**遗留问题：**
待执行定向测试与真实模型合成样本回归。

---

### [2026-11-21 21:49] 检查建议校验、报告保存和展示

**任务目标：**
覆盖正常同期、日期倒置、不符合评分契约的建议以及历史报告兼容。

**执行行为：**

1. 扩展 Agent 回归，覆盖正常同期与错开时段的同档评分、跨条目误报、真实日期倒置、错误归属、满分项和无效引用。
2. 扩展 Java 测试，验证 checkCode 的保存、扣分关联以及 PDF 中的分项得分。
3. 使用编译后的 Vue 报告组件验证新报告、旧报告和无建议报告的渲染结果。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/agent/tests/test_resume_project.py | 增加评分和日期回归断言 | 防止建议脱离评分或误判同期 |
| 修改 | project/backend/src/test/java/com/aijob/backend/resumeoptimization/ResumeOptimizationResultHandlerTest.java、ResumeOptimizationServiceDiagnosisExportTest.java | 增加评分点保存和 PDF 展示测试 | 确认报告数据一致与历史兼容 |

**执行命令与验证：**

- 命令：D:/Anaconda/envs/ai-job/python.exe -m pytest tests/test_resume_project.py tests/test_operations.py -q --disable-warnings
- 结果：68 项通过。沙箱内 Python socketpair 无法及时建立，沙箱外复跑退出码为 0。
- 命令：mvn -o -B -Dtest=ResumeOptimizationResultHandlerTest,ResumeOptimizationServiceDiagnosisExportTest,ResumeExportServiceTest,TaskResultServiceTest -Djacoco.skip=true -DforkCount=0 test
- 结果：33 项通过，1 项既有字体用例跳过。设置本地 Mockito Java agent。
- 命令：npm run type-check；通过 Vite SSR 加载实际报告组件并核验 HTML；git diff --check
- 结果：通过。SSR 覆盖评分点分值、历史报告和无建议报告。首次 SSR 扫描与服务关闭并发产生诊断，关闭预扫描后干净复跑。

**影响范围：**
覆盖诊断 Agent、Java 回调落库和 PDF，以及报告组件渲染。

**遗留问题：**
真实 DeepSeek 合成样例正在执行，未用个人简历调用模型。

---

### [2026-11-21 21:50] 真实模型日期对照验收

**任务目标：**
确认实际模型链路遵循评分点契约，对正常同期和真实倒置给出不同结果。

**执行行为：**

1. 通过项目 Python 环境调用 ResumeDiagnoseHandler 和已配置的 DeepSeek，评分规则取自当前 Java 规则。
2. 运行同一合成简历的同期、错开日期和实习起止倒置三个版本。验证每条建议绑定非满分项且带原文引用，正常日期版本无日期建议，倒置版本含日期建议。

**文件变更：**
无新增项目文件。本阶段仅在本过程记录末尾追加结果。

**执行命令与验证：**

- 命令：D:/Anaconda/envs/ai-job/python.exe -u -，通过标准输入执行三组合成样例。
- 结果：通过。三个样例均为 deepseek-v4-pro 首次生成通过。同期与错开样例时间线均为 5/5，无日期建议，耗时约 19.4 秒、20.5 秒。起止倒置样例为 2/5，生成有原文支持的日期核对建议，耗时约 24.3 秒。

**影响范围：**
合成样例通过实际 Agent 处理链路。未写入业务数据库。

**遗留问题：**
验证未使用个人简历或登录态，未重启或部署在运服务。历史报告在新版服务中可通过重新分析生成新结果。

---

### [2026-11-21 22:08] 定位诊断 500 与旧报告混显

**任务目标：**
查明重新分析超时的实际失败点。

**执行行为：**

1. 对照 Java、Agent 日志确认，两次模型均在约 33 秒返回，Agent 仍运行 resume-diagnose-v4。
2. Java 写入 resume_diagnosis 时数据库返回 Unknown column 'source_text'，回调持续 500，任务在截止后才转为超时。
3. 核对代码发现 V19 已包含该列及阅读顺序字段，但运行库启动日志只到 V18。前端提交后刷新列表却继续展示原报告，并优先选 READY 记录。

**文件变更：**
本阶段仅追加本记录。

**执行命令与验证：**

- 命令：rg、Get-Content、Get-NetTCPConnection、本地健康接口 GET。
- 结果：已从两端日志定位故障。Agent 就绪，Java 正在监听。待核对实时 Flyway 版本。

**影响范围：**
当前本地简历诊断提交与新版评分展示。

**遗留问题：**
待备份并应用既有迁移、启动当前源码、核验真实写入。

---

### [2026-11-21 22:10] 修复报告切换并备份迁移涉及的简历表

**任务目标：**
超时只显示当前任务状态，旧报告从历史入口查看。迁移前保全涉及的本地表。

**执行行为：**

1. 提交与重试后加载任务返回的诊断资源。默认打开最新记录，切换记录时清除旧报告，超时也提供原参数重试入口。
2. 查询本地 career_business，确认 Flyway 已成功到 V18，V19 所需列均缺失，无在途任务。备份 resume_version 和 resume_diagnosis 两张表。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/frontend/src/views/resume-optimization/ResumeOptimizationView.vue | 选中当前任务资源、清除旧报告、允许超时重试 | 防止旧分数冒充新结果 |
| 新增 | project/backend/target/local-restarts/resume-diagnosis-20261121-220922/before-v19-resume-tables.sql | 79,615 字节本地备份 | 迁移前保全数据 |

**执行命令与验证：**

- 命令：MySQL 查询 Flyway、information_schema 和任务计数；mysqldump --single-transaction --skip-lock-tables --no-tablespaces --set-gtid-purged=OFF。
- 结果：确认 V18，备份成功。首次传入不受支持的 --no-lock-tables，改用 --skip-lock-tables 后完成。
- 命令：npm run type-check。
- 结果：通过。

**影响范围：**
本地诊断任务页面及迁移准备。数据库尚未变更。

**遗留问题：**
待构建并发布当前 Java 和 Agent，验证 V19 与真实落库。

---

### [2026-11-21 22:12] 应用 V19 并发布当前本地服务

**任务目标：**
使当前 Java 诊断写入与实际数据库结构匹配，使在运 Agent 加载新契约。

**执行行为：**

1. 离线打包并确认 JAR 含 V19，停止已核实的 8080 Java 与 8100 Agent 进程，按原本地 MySQL 和 Agent 配置启动新进程。
2. Flyway 将 career_business 从 V18 升至 V19。Agent 启动正常且启用回调，前端原开发服务继续使用。
3. 打开登录态简历分析历史中的实际失败记录，核对超时提示、重试入口、旧评分隐藏及 PDF 按钮禁用。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | project/backend/target/backend-0.0.1-SNAPSHOT.jar | 当前源码构建产物 | 启动当前 Java |
| 新增 | project/backend/target/local-restarts/resume-diagnosis-20261121-220922/ | Java 和 Agent 输出日志 | 核对发布与回调 |
| 修改 | 本地 career_business 的 Flyway 记录和简历两表结构 | 复用既有 V19 迁移增列 | 消除诊断落库 500 |

**执行命令与验证：**

- 命令：mvn -o -B -DskipTests package、jar tf、Stop-Process、Start-Process、本地 HTTP GET。
- 结果：打包成功，Flyway 成功执行 V19，Java 受保护接口正常响应，Agent /readyz 返回 200。
- 命令：Edge 中点击历史记录并打开失败条目。
- 结果：显示简历诊断超时与原参数重试，旧分数不显示，PDF 下载禁用。

**影响范围：**
本机 AI-JOB Java 8080、Agent 8100 及本地业务库。

**遗留问题：**
待完成合成数据的诊断落库核验，未代用户提交个人简历。

---

### [2026-11-21 22:15] 验证新版报告从重新分析到落库和导出

**任务目标：**
验证部署后真实 Agent 回调能写入数据库，并能通过业务接口读取评分依据与导出 PDF。

**执行行为：**

1. 在本地专用合成账号下经公开 API 上传虚构 DOCX，解析、确认并发起简历分析，轮询任务至终态后从业务接口读取报告。
2. 核对报告为 READY、关联本次任务、使用 resume-quality-v3，时间线 5/5。三条建议都有 checkCode 并指向实际扣分项，普通同期未产生日期建议。
3. 导出 PDF，核对 HTTP 200、PDF 文件头及 145,510 字节响应。简历阅读顺序标记为已复核。

**文件变更：**
本阶段仅追加本记录。业务库新增独立验收账号和虚构简历记录，Agent SQLite 保存相应运行记录。

**执行命令与验证：**

- 命令：D:/Anaconda/envs/ai-job/python.exe -u -，通过 HTTP 调用注册、登录、上传、解析、确认、诊断、任务查询和 PDF 导出接口。
- 结果：首次验收脚本误把正常的 HTTP 202 判成失败，解析任务本身完成。修正测试脚本后完整流程通过，诊断任务 SUCCEEDED，报告 READY。
- 命令：git diff --check。
- 结果：本次前端改动通过。

**影响范围：**
实际本地 AI-JOB 服务、数据库 V19 和 DeepSeek 新诊断契约，旧报告仍可访问。

**遗留问题：**
本次只使用合成简历进行真实模型验收，未代用户重交个人简历。原超时记录保持原状态，可从页面按原参数重试。

---
