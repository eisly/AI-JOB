
### [2026-11-23 13:59:54] 制作个人 JD 定向简历交互原型

**任务目标：** 在接入业务代码前展示已确认的定向简历方案，便于审阅版式和操作路径。

**执行行为：** 参考现有定向简历优化原型及前端颜色变量，制作单文件 HTML。使用虚构个人 JD 和简历，展示目标岗位入口、双侧原文、修改理由、证据缺口，以及可联动的完整简历预览、确认和导出提示。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
|---|---|---|---|
| 新增 | `docs/功能资料/05-智能简历优化/个人JD定向简历原型.html` | 离线单页 HTML 原型 | 先验证前端体验 |
| 新增 | `docs/功能资料/个人JD定向简历_process.md` | 记录本次原型开发 | 按项目规范记录过程 |

**执行命令与验证：** 文件已创建并通过 Test-Path 检查。浏览器交互与版式待验证。

**影响范围：** 仅项目设计资料，未接入 API、模型或数据库。

**遗留问题：** 验证桌面及移动端版式、采纳与编辑、整页预览及确认后的导出提示。

---

### [2026-11-23 14:01:46] 验证原型并保存桌面预览

**任务目标：** 验证用户可在浏览器走通核对、确认和导出预览，并保存可直接查看的首屏截图。

**执行行为：** 内置浏览器连接超时，改用本机已安装的 Playwright Chromium 打开离线文件。核对个人 JD 入口、两条改动、采纳与保留、编辑后联动、完整简历、确认和两种导出预览。实际查看桌面与移动截图，确认桌面并列显示，移动端顺序堆叠，没有水平溢出。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
|---|---|---|---|
| 新增 | docs/功能资料/05-智能简历优化/个人JD定向简历原型.png | 保存一条建议采纳后的桌面截图 | 让用户看到改动在完整简历中上色的效果 |
| 修改 | docs/功能资料/个人JD定向简历_process.md | 追加浏览器验证结果 | 记录验收 |

**执行命令与验证：** 运行临时 Playwright 脚本。第一次检查早于确认弹窗关闭事件完成，调整脚本等待导出按钮启用后重跑通过。1440 像素桌面与 390 像素移动视口均通过关键交互，无运行时错误。git diff --check 无空白错误，现有生成类型文件有行尾转换提示。

**影响范围：** HTML 原型、桌面截图与过程记录，业务源码未修改。

**遗留问题：** 原型使用固定虚构数据，Word/PDF 仅演示确认与导出界面。真实生成、文档下载和持久化留待实现。

---

### [2026-11-23 14:10:00] 阶段一：Agent 扩展逐条建议与完整稿输入输出

**任务目标：** 让 `RESUME_OPTIMIZE` 同时返回 JD 引文、修改理由与缺证要求，并支持 `FULL_DOCUMENT` 模式允许无可靠建议，同时把公司与职位信息传给模型。

**执行行为：**

1. 扩展输出契约：`ResumeOptimizeSection` 新增可选 `jdQuote`、`reason`；新增 `ResumeOptimizeRequirementGap`；`AIResumeOptimizeResult` 新增最多 5 条 `missingRequirements`，`sections` 允许为空（0..20）。
2. 扩展输入契约：`ResumeOptimizePayload` 新增可选 `companyName`、`jobTitle` 与 `mode`（`SECTIONS` / `FULL_DOCUMENT`，默认 `SECTIONS`）。
3. Handler 校验：`SECTIONS` 模式仍要求至少一条建议；JD 引文无法在 JD 原文定位时清空并保留建议；缺证要求无法在 JD 定位时过滤；`FULL_DOCUMENT` 允许空建议。
4. 提示词升级为 `resume-optimize-v2`，明确 `jdQuote` 必须逐字可定位、`reason` 说明对应岗位要求、缺证要求只列 JD 明确要求且简历无依据者、完整稿模式原句必须唯一且互不重叠。
5. 模型输入透传 `mode`、`companyName`、`jobTitle`，测试替身补齐新字段。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/app/schemas.py` | 新增 `ResumeOptimizeMode`、`ResumeOptimizeRequirementGap`；扩展 section/result/payload 字段 | 承载 JD 引文、修改理由与缺证要求 |
| 修改 | `project/agent/app/handlers.py` | 按模式校验、JD 引文容错清空、缺证要求过滤 | 避免引文错误导致整任务失败，保留可用建议 |
| 修改 | `project/agent/app/skills/prompts/resume_optimize.md` | 版本 v2，新增字段规则与输出结构 | 约束模型只引用真实原文 |
| 修改 | `project/agent/app/operations.py` | 提示词版本改为 `resume-optimize-v2` | 与提示词文件一致 |
| 修改 | `project/agent/app/skills/tools.py` | 透传 `mode`、`companyName`、`jobTitle` | 公司与职位参与内容取舍 |
| 修改 | `project/agent/app/fake_model.py` | 假模型输出补 `jdQuote`、`reason`、`missingRequirements` | 保持结果契约一致 |
| 修改 | `project/agent/tests/test_resume_project.py` | 更新空建议断言，新增引文容错、缺证过滤、完整稿空建议用例 | 覆盖新语义 |

**执行命令与验证：**

- 命令：`& "D:\Anaconda\envs\ai-job\python.exe" -m pytest tests/test_resume_project.py tests/test_skills.py tests/test_operations.py -q -p no:warnings`
- 结果：通过（85 项，含新增 `test_resume_optimize_filters_invalid_jd_quotes_and_allows_full_document_without_changes`）
- 说明：本机 `conda` 命令不可用，直接使用 `ai-job` 环境的解释器路径执行。

**影响范围：** Agent 的 `RESUME_OPTIMIZE` 输入输出契约扩展；未传新字段的旧请求按 `SECTIONS` 模式与原行为兼容。

**遗留问题：** 真实 LLM 生成质量需在联调环境验证，本轮只验证 schema、提示词装载与业务校验。

---

### [2026-11-23 14:16:00] 阶段二：Java 支持完整稿模式、引文校验与完整正文导出

**任务目标：** 新增 `FULL_DOCUMENT` 模式与数据存储，校验双方引文、唯一替换位置、重叠与无来源数字，并让 PDF/DOCX 从确认后的完整正文导出，兼容历史逐段记录。

**执行行为：**

1. 新增 Flyway `V23`，为 `resume_optimization` 增加可空列：`mode`、`job_version`、`job_company_name`、`job_title`、`source_text`、`missing_requirements_json`、`notes_json`、`full_document_text`、`full_document_json`。
2. `ResumeOptimizationService.optimize()` 解析 `mode`，完整稿模式要求岗位为本人私有个人 JD，并校验提交的 `jobVersion` 与当前版本一致；payload 增加 `mode`、`companyName`、`jobTitle`；创建记录时固化简历原文与岗位快照。
3. `ResumeOptimizationResultHandler.optimize()` 校验 `jdQuote` 是否属于 JD 原文，无法定位则清空并写入提示；完整稿模式下跳过原句重复、位置重叠与新增无来源数字的建议；过滤无法定位的缺证要求；提示写入 `notes_json`。
4. `accept` 时以简历原文为底稿，按唯一原文位置应用已确认内容，生成 `full_document_text` 与分块 `full_document_json`（保留未修改内容与顺序）。
5. `export` 优先按 `full_document_json` 的完整正文导出 PDF/DOCX；无完整稿的历史记录回退 `accepted_sections_json`。
6. `optimizationVo` 返回 `mode`、岗位快照、缺证要求、提示、`sourceText` 与 `fullDocument`；`READY` 状态按“全部建议”生成预览完整稿。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/src/main/resources/db/migration/V23__add_resume_optimization_full_document.sql` | 新增 9 个可空列 | 保存模式、岗位快照、缺证要求与完整稿 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/resumeoptimization/ResumeOptimizationService.java` | 模式与 JD 版本校验、payload 组装、完整稿拼装、导出与 VO 扩展 | 实现完整稿生成与导出口径 |
| 修改 | `.../ResumeOptimizationMapper.java` | 扩展 insert/complete/accept/retry SQL | 持久化新增字段并清理重试结果 |
| 修改 | `.../ResumeOptimizationResultHandler.java` | 引文校验容错、重叠与数字校验、缺证过滤、提示落库 | 跳过不可靠建议并明确提示 |
| 修改 | `.../ResumeOptimizationResultHandlerTest.java` | 更新方法签名断言，新增完整稿、重复定位、无来源数字、JD 引文容错用例 | 覆盖新校验语义 |
| 修改 | `.../ResumeOptimizationServiceDiagnosisExportTest.java` | 新增模式/版本校验、完整稿拼装、完整稿导出用例；补幂等替身 | 覆盖 Service 新链路 |

**执行命令与验证：**

- 命令：`& mvn -o "-Djacoco.skip=true" "-DforkCount=0" "-Dtest=ResumeOptimizationResultHandlerTest,ResumeOptimizationServiceDiagnosisExportTest" test`
- 结果：通过（33 项，18 + 15）
- 迁移验证：在独立临时库 `aijob_v23_check_20261123` 顺序执行全部 `V*.sql`，`resume_optimization` 新增列全部存在且可空，原有列未变。`V13` 因直连执行缺少 `flyway_schema_history` 失败，与本次 V23 无关。
- 说明：本机 JaCoCo 与 fork 冲突，使用跳过 JaCoCo、同进程执行；PowerShell 下带 `=` 的 Maven 参数必须加引号。

**影响范围：** 定向优化新增字段全部可空，历史逐段记录与历史导出保持可用；`FULL_DOCUMENT` 仅对本人私有个人 JD 开放。

**遗留问题：** 未用真实数据库业务数据跑通“生成 → 确认 → 导出”全链路；临时库因安全策略禁止删库而保留。

---

### [2026-11-23 14:22:00] 阶段三：前端接入个人 JD 入口与逐条核对完整稿预览

**任务目标：** 从个人 JD 详情直接进入定向优化，按原型实现逐条核对（JD 原句、简历原文、修改建议、理由、缺证要求）与整份简历预览、确认和导出。

**执行行为：**

1. 个人 JD 详情抽屉新增「针对该岗位优化简历」，跳转 `/resume-optimization?tab=optimization&jobId=...`，进入后预选目标岗位与最新已确认简历。
2. 个人 JD 发起优化时显式传 `mode: FULL_DOCUMENT` 与 `jobVersion`；公共岗位沿用 `SECTIONS`。
3. 优化页重构为“目标岗位横条 + 左栏逐条核对 + 右栏完整简历预览”，新增建议标签页、核对进度、采纳/保留原文/编辑、缺证要求卡片、整页预览与返回。
4. 新增 `ResumePaperPreview.vue`，按后端同样的唯一位置替换规则在本地拼装预览，已采纳或编辑的片段高亮，未修改内容原样保留。
5. 确认时未核对段落按保留原文提交；确认后启用 Word/PDF 导出；历史无完整底稿的记录回退提示并保留逐段确认与旧导出。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/frontend/src/views/resume-optimization/ResumePaperPreview.vue` | 完整简历纸面预览组件 | 双栏与整页预览复用同一渲染 |
| 修改 | `project/frontend/src/api/types.ts` | 扩展 section、VO 与完整稿类型 | 对接后端新增字段 |
| 修改 | `project/frontend/src/api/optimizations.ts` | 创建请求支持 `mode` 与 `jobVersion` | 显式启用完整稿模式 |
| 修改 | `project/frontend/src/views/job-match/JobMatchView.vue` | 个人 JD 详情新增优化入口与跳转 | 需求入口 |
| 修改 | `project/frontend/src/views/resume-optimization/ResumeOptimizationView.vue` | 按原型重构核对与预览、决策状态、完整稿拼装与确认 | 实现逐条核对与完整稿导出流程 |

**执行命令与验证：**

- 命令：`npm run type-check`
- 结果：通过
- 命令：`npm run build`
- 结果：通过（仅既有大 chunk 警告）
- 说明：本地无可用登录账号，未执行浏览器点击链路与真实模型生成。

**影响范围：** 仅定向优化页与个人 JD 详情入口；诊断 tab、历史记录与旧导出路径保留。

**遗留问题：** 登录态下的「个人 JD → 优化 → 逐条采纳 → 确认 → 导出」未实测；Word/PDF 实际下载未在浏览器验证，后端 PDF 文本已由单测覆盖。

---

### [2026-11-23 14:30:30] 从当前工作区启动独立验收

**任务目标：** 测试用户已实现的个人 JD 定向简历，从浏览器提交直至真实 Word/PDF 导出。

**执行行为：** 确认 main 的定向简历改动尚在工作区，识别 5188 前端与原 V22 验证环境。运行 Java/Agent 定向测试与前端类型检查。核对 Agent 已配置 DeepSeek，验收仅发送合成岗位和合成简历。

**文件变更：** 仅追加本过程记录，保留原有工作区改动。

**执行命令与验证：** Maven 36 项中 35 项通过，1 项跳过；npm run type-check 通过；Agent 定向测试等待完成。

**影响范围：** 当前代码的静态检查及独立测试环境，未触碰用户简历或正式数据库。

**遗留问题：** 核验隔离库实际迁移至 V23、浏览器交互与导出文件内容。

---

### [2026-11-23 14:37:24] 完成真实模型、浏览器和双格式导出的验收

**任务目标：** 验证两个真实模型定向稿，核查完整稿、证据和关键失败边界。

**执行行为：** 重启前次隔离 Java 和 Agent，Flyway 在隔离库自动应用 V23。录入两个虚构账号及两版已确认的虚构简历，将 v1 设为生效。Playwright Chromium 在 5188 登录，导入数据库查询和接口质量两份 JD；手动把默认 v2 切换回同一份 v1。实际调用 DeepSeek v4 Pro、采纳第一条、第二条样例另做编辑与保留、展开预览、确认并下载两份 Word 和两份 PDF。用 pypdf 和 python-docx 提取正文与确认稿逐字比对（忽略排版空白）。对已编译的 Java 数字校验执行合成边界探针。

**文件变更：** 仅追加本日志。验证脚本、截图、报告和下载文件置于系统临时目录。

**执行命令与验证：** Java 三组测试 35 通过、1 跳过（macOS 专用字体路径）；前端 type-check 通过；Agent 定向 pytest 在宿主环境退出码 0。两份结果的公司与职位匹配，三处建议均带可定位的两侧原文和理由，分别显示缺少 Redis 缓存一致性、Kafka 消费端幂等依据。四份文件均含确认后完整正文。过期岗位版本两次返回 409，跨账号详情与生成均返回 404。V23 测试脚本最初读取了错误的 VO 字段并假设全屏预览为弹窗，修正脚本后复用原任务验收通过。

**影响范围：** 仅隔离数据库、前端页面和当前验收日志，未修改业务源码。

**遗留问题：** 两个入口均默认选择最近确认的 v2 而非生效 v1；完整稿在待核对时的修改建议区域错误展示原文；数字校验以子串命中放行 140% 到 40%，也无法区分原文中的覆盖率 40% 与改写中的性能提升 40%。无效 JD 引文清空之后仍保留建议的路径需对照设计要求。异步失败后的重试未做浏览器联调。

---

### [2026-11-23 14:38:20] 补验历史导出与用例计数

**任务目标：** 核对 V23 前的实际记录仍可沿用旧逻辑。

**执行行为：** 登录上轮隔离虚构账户，读取迁移前生成的 SECTIONS 记录，确认后下载 Word 和 PDF，以解析器检查旧建议正文。复核 Agent 测试的收集数量。

**文件变更：** 仅修改本验收日志。

**执行命令与验证：** 旧记录仍为 SECTIONS，确认成功且两种格式都含历史建议文本。Agent 定向 pytest 通过，收集到 85 项。版本切换、核对区显示和数字边界仍有已复现缺陷。

**影响范围：** 仅隔离验收数据；当前业务代码仅审查和测试。

**遗留问题：** 修复已复现问题后复测；异步失败后的重试尚未做浏览器验收。

---

### [2026-11-23 15:59:56] 定位定向简历核对与预览问题

**任务目标：** 定位深色模式可读性、无效建议和 Markdown 预览问题。

**执行行为：** 对照截图和现有实现，确认待核对的草稿初始化为原文，但建议栏读取该草稿。确认深色模式沿用主题文字颜色，部分卡片背景却固定为浅色。确认预览按替换片段输出普通段落，且模型结果校验未跳过与原文一致的改写。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/个人JD定向简历_process.md` | 追加排查记录 | 记录问题根因 |

**执行命令与验证：**

- 命令：`git status --short --branch`、`rg`、`Get-Content`
- 结果：完成代码排查，业务测试未执行。工作区已有未提交的定向简历实现。
- 说明：规范中的 `D:\vscode\AI-JOB\docs\功能资料` 不存在，沿用当前工作区内的已有过程记录。

**影响范围：** 定向简历的建议展示、模型结果入库和完整简历预览。

**遗留问题：** 待修复并执行回归验证。

---

### [2026-11-23 16:01:08] 拦截与原文一致的模型建议并引入 Markdown 解析器

**任务目标：** 只保留可核对的实际改写，为右侧预览引入 Markdown 解析能力。

**执行行为：** 在 Java 结果处理器保留原文与证据校验，跳过统一换行符后与原文相同的建议，写入跳过原因。补充 Agent 提示词和 Java 回归用例，验证跳过的建议不会阻止同一位置的有效改写。安装前端 Markdown 解析器 `markdown-it`。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/app/skills/prompts/resume_optimize.md` | 明确相同原文和仅首尾空白、换行符类型不同的建议应省略 | 减少生成无效建议 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/resumeoptimization/ResumeOptimizationResultHandler.java` | 保存前跳过无改写建议并记录原因 | 阻止无效结果写入 |
| 修改 | `project/backend/src/test/java/com/aijob/backend/resumeoptimization/ResumeOptimizationResultHandlerTest.java` | 新增无效建议回归用例，调整其他校验的示例改写 | 覆盖过滤后仍可入库的有效建议和原有校验分支 |
| 修改 | `project/frontend/package.json`、`project/frontend/package-lock.json` | 加入 Markdown 渲染依赖 | 支持标准 Markdown 解析 |

**执行命令与验证：**

- 命令：`npm install markdown-it --save --ignore-scripts --no-audit`
- 结果：受限执行无法连接本地代理，改用授权执行后成功安装。
- 说明：Java 用例与前端页面验证待执行。

**影响范围：** 新生成的定向简历结果，以及定向简历前端构建依赖。

**遗留问题：** 还需修复历史建议的展示、主题颜色和 Markdown 预览组件。

---

### [2026-11-23 16:05:14] 修复深色核对卡片、历史建议与全文 Markdown 预览

**任务目标：** 让核对页能正常比较有效改写，双栏与展开页能渲染完整简历。

**执行行为：** 待核对与保留原文状态直接显示模型建议，编辑后显示已编辑文字；历史无改写建议从核对进度和批量填入中排除，确认时保留原接口要求的完整段落及已保存的内容。核对标签、岗位引用、对比卡片、状态及缺证卡片切换为主题变量。对拼接后的整篇文本执行 Markdown 解析，以源码行映射高亮采纳片段所在的段落或列表项。预览识别 PDF 页码标记和常见简历章节，禁用原生 HTML。解析器自带 TypeScript 类型，因此移除试装的独立类型包。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/views/resume-optimization/ResumeOptimizationView.vue` | 修正建议与草稿的显示和选择逻辑，更新核对区主题样式 | 解决无效对比与深色模式不可读 |
| 修改 | `project/frontend/src/views/resume-optimization/ResumePaperPreview.vue` | 双栏及展开视图共用 Markdown 正文、主题边框和改动标记 | 让预览呈现标题、列表、强调等排版 |
| 新增 | `project/frontend/src/views/resume-optimization/resume-markdown.ts` | 解析拼好的整篇 Markdown，处理页码、常见标题和源码位置 | 保持 Markdown 结构并标记改动 |
| 新增 | `project/frontend/tests/resume-markdown.test.ts` | 覆盖 PDF 标记、跨片段 Markdown 语法、高亮和 HTML 转义 | 防止预览回归 |
| 修改 | `project/frontend/package.json`、`project/frontend/package-lock.json` | 保留自带类型的 `markdown-it` 依赖 | 避免重复类型定义 |

**执行命令与验证：**

- 命令：`node --test tests/resume-markdown.test.ts`
- 结果：2 项通过。
- 说明：浏览器交互、最终构建和 Java 测试仍待汇总。

**影响范围：** 定向简历核对页、双栏及展开的全文预览。Markdown 处理作用于页面预览。

**遗留问题：** 用合成数据验证深浅色对比度、历史建议过滤、采纳与编辑、确认请求和展开预览。

---

### [2026-11-23 16:08:17] 通过前端构建与 Java 定向回归

**任务目标：** 核验解析器依赖、前端编译和结果入库过滤。

**执行行为：** 执行 Vue 类型检查与生产构建，构建输出放在依赖目录的临时缓存下。执行 Java 结果处理和确认导出的定向测试，核对报告包含新增的无效改写回归用例。

**文件变更：** 仅追加此验证记录。

**执行命令与验证：**

- 命令：`npm run type-check`、`npm run build -- --outDir ./node_modules/.cache/resume-markdown-build`
- 结果：通过。Vite 报告主 chunk 体积提示。
- 命令：`mvn -o -Dtest=ResumeOptimizationResultHandlerTest,ResumeOptimizationServiceDiagnosisExportTest test`
- 结果：沙箱内 Surefire 子进程未返回，终止后在本机离线重跑，34 项通过。JaCoCo 报告已有执行数据与部分当前类不匹配，测试结果无失败或跳过。

**影响范围：** 本地验证，无发布操作。

**遗留问题：** Playwright 的默认 Chromium 版本不在本机缓存，改用已安装的 Chrome 跑浏览器验证。

---

### [2026-11-23 16:09:15] 通过 Chrome 合成数据核对与 Markdown 预览验收

**任务目标：** 复验截图中的三项问题及核对页正常流程。

**执行行为：** 用 Playwright 驱动本机 Chrome，从 `http://127.0.0.1:5193/resume-optimization` 打开两条模拟历史定向稿。桌面尺寸 1440×950 验证深浅主题、初始建议显示、无效条目过滤、采纳、保留原文、编辑、展开与返回、批量填入和确认提交。移动尺寸 390×844 验证全文预览宽度及结构。查看合成数据的深色核对截图与移动端预览截图。

**文件变更：** 仅追加本过程记录；验收脚本在忽略的依赖缓存目录，截图在系统临时目录。

**执行命令与验证：**

- 命令：`node node_modules/.cache/resume-review-qa.cjs`
- 结果：通过。受测文字最低对比度浅色 5.34∶1、深色 5.68∶1，控制台无错误或警告。含无效项的历史稿只显示两条有效建议，提交仍携带完整数据；只有无效项的稿件显示空态，仍可预览简历。
- 说明：建议采纳与编辑后保持列表和行内加粗，出现改动高亮；PDF 页码标记未出现在预览正文。截图为 `aijob-resume-review-dark.png` 和 `aijob-resume-preview-mobile.png`。

**影响范围：** 浏览器端使用合成数据和模拟接口，无真实简历、模型请求或数据库写入。

**遗留问题：** 本轮未调用真实模型，也未重新下载 Word/PDF；Markdown 样式用于页面预览。

---

### [2026-11-23 16:10:01] 完成 Agent 和前端单元回归及变更核对

**任务目标：** 核对剩余回归结果，检查本轮记录与实际改动一致。

**执行行为：** 使用项目内已有测试依赖执行 Agent 简历定向测试，同时执行 Markdown 和既有训练内容的 Node 测试。核对 Maven XML 里包含新增用例，检查本轮涉及的已跟踪源文件与依赖清单无新增空白问题。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/个人JD定向简历_process.md` | 追加最终回归结果 | 对齐实际验证范围 |

**执行命令与验证：**

- 命令：`python -m pytest tests/test_resume_project.py`（使用 `.testdeps`）
- 结果：沙箱进程无输出，终止后在本机运行通过，15 项通过，有 1 条既有 pytest 插件弃用提示。
- 命令：`node --test tests/resume-markdown.test.ts tests/training-content.test.ts`
- 结果：5 项通过。
- 命令：`git -c core.fsmonitor=false diff --check -- <本轮已跟踪源文件及依赖清单>`
- 结果：本轮文件检查通过。全工作区检查另报告已有 `docs/功能资料/服务启动_process.md` 的尾随空白，本轮未改动该文件。

**影响范围：** 当前工作区内的源码与过程记录，无部署、数据库迁移或真实简历提交。

**遗留问题：** 未重新调用真实模型或下载 Word/PDF。同一条 Markdown 语法跨替换片段时按全文渲染，改动标记以正文段落或项目列表项为单位。

---
