# 简历解析、技能补录与原文定位修复过程记录

## 背景

2026-11-22 确认两类问题：
1. 文字版简历解析漏掉原文明确写出的技能（React、MyBatis、LangChain、LangGraph、Qdrant 等）——当时 Agent 只收到约 19 项技能目录；目录外结果（unmappedSkills）未展示给用户；现有校验只检查 quote 能否在全文找到，未核对模型填报的页码和段落。
2. 求职方舟版简历为 4 页纯图片 PDF，无文字层；提取器生成的 `[page:N]` 页码标记通过了非空检查，最终把空结果标成「解析完成」。

修复方案（四部分）：
- 阶段一：支持图片 PDF（Tesseract 5 OCR，走现有任务队列后台逐页处理），纠正空结果状态。
- 阶段二：Flyway V20 扩充技能目录 + 别名匹配补漏 + 目录外技能候选展示与手动添加。
- 阶段三：由程序从提取文本定位原文片段，计算实际页码/行号（如「第 2 页 · 第 17 行」，图片页标注「OCR 识别」）；无原文依据的候选不作为已识别技能；区分 React/ReAct、Java/JavaScript。
- 阶段四：解析接口增加可选 `reparse` 参数；同一版本只允许一个在途任务；重跑失败保留已有有效草稿。

素材口径：代码、提示词与测试夹具使用通用规则与独立合成数据；用户两份个人附件仅用于本地验收定位，不写入仓库。

---

### [2026-11-22 15:30] 阶段一：图片 PDF OCR 支持与空结果状态纠正

**任务目标：**
图片 PDF 能经任务队列后台 OCR 出带页码统一文本；OCR 未配置/文件不可读/无有效事实时明确失败而非标「解析完成」。

**执行行为：**
1. 本机安装 Tesseract 5.4.0（UB-Mannheim）+ chi_sim（44MB 标准版语言包，存于 `project/agent/tessdata/`，经 `TESSDATA_DIR` 指定）+ PyMuPDF 1.24.9（渲染页面为 PNG）。
2. Agent 新增 `app/ocr.py`：pypdf 逐页提取文字层（文字页保留原生文本），无文字页用 PyMuPDF 渲染 + Tesseract（chi_sim+eng, psm 4）识别，输出统一 `[page:N]` 文本；页数上限 30、总时长上限 240s、单页超时 60s；纯图片 PDF 在 Tesseract 不可用时抛 `OCR_NOT_CONFIGURED` 并给出具体原因（未安装/缺语言包）；混合 PDF 单页 OCR 失败保留其余原生页。
3. 新增 `RESUME_OCR` 操作（schemas/operations/handlers/graphs/api/提示词 resume_ocr.md 全链路注册），handler 纯程序化执行不调用模型；`FileFetcher` 新增 `download_bytes` 下载原始字节。
4. Java 端：上传时图片 PDF（有图片 XObject 且无文字层）允许仅页码标记占位文本入库；`ResumeService.parse` 检测「PDF 且文本仅页码标记」时创建 RESUME_OCR 任务（payload 带签名 downloadUrl）；新增 `ResumeOcrResultHandler` 校验 textHash、拒绝无有效文本结果，保存统一文本 + `ocr_pages_json` 后自动接续 RESUME_PARSE；`WebClientAgentClient` 增加 `/internal/ai/v1/resumes/ocr` 路由；`ResumeResultHandler` 增加空事实拒绝（skills/internships/awards 全空时不落 PARSED）；`TaskResultService` 将解析/OCR 的 AI_SCHEMA_INVALID 失败按业务失败落库（不再 500 重试）。
5. Flyway V21：`resume_version` 增加 `ocr_pages_json`。
6. `.env` 增加 `TESSERACT_CMD`/`TESSDATA_DIR`/`AGENT_ALLOWED_FILE_HOSTS=127.0.0.1:8080`；`requirements.txt` 增加 PyMuPDF；Agent 侧 `_validate_url` 对 loopback 主机放行 http。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `project/agent/app/ocr.py` | OCR 提取核心模块 | 支持图片 PDF |
| 新增 | `project/agent/app/skills/prompts/resume_ocr.md` | RESUME_OCR 技能契约 | 注册新操作 |
| 新增 | `project/backend/.../resume/ResumeOcrResultHandler.java` | OCR 结果落库与接续解析 | 任务闭环 |
| 新增 | `V21__add_resume_ocr_pages.sql` | ocr_pages_json 列 | 前端 OCR 标注数据源 |
| 修改 | `project/agent/app/{config,files,schemas,operations,handlers,graphs,api}.py`、`skills/tools.py` | OCR 配置/下载/注册 | 全链路接入 |
| 修改 | `project/backend/.../resume/{FileStorageService,ResumeService,ResumeMapper,ResumeController,ResumeResultHandler}.java`、`task/{WebClientAgentClient,TaskResultService}.java`、`config/SecurityConfig.java`、`application.yml` | 空文本上传/OCR 路由/空事实拒绝 | 阶段一 Java 侧 |
| 修改 | `.env`、`requirements.txt` | 环境配置 | OCR 运行依赖 |

**执行命令与验证：**
- Agent pytest：`tests/test_ocr.py` 8 项通过；全量（排除 Playwright 渲染用例）通过
- Java：`mvn -q -DskipTests compile` 通过；受影响单测通过
- 真机 OCR 实测：图片版附件 4 页全 OCR，第 4 页正确识别「专业技能」区（Java/Python/LangGraph/LangChain/LangChain4j/Spring AI/ReAct/RAG/MySQL/Redis/Qdrant/Chroma/PGVector/Spring Boot/MyBatis/FastAPI 等）

**影响范围：**
简历上传与解析链路；不影响 DOCX/纯文字 PDF 原有路径。

**遗留问题：**
OCR 文本截断上限 60000 字符沿既有约定。

---

### [2026-11-22 15:35] 阶段二：技能目录扩充、别名补漏与目录外候选

**任务目标：**
补齐常见技能目录；解析覆盖全文（专业技能/项目/实习正文）；目录外技能带原文依据展示并可手动添加为私有技能。

**执行行为：**
1. Flyway V20：`skill_catalog` 增加 `aliases` 列（分号分隔）；INSERT IGNORE 补 45+ 项技能（前端 React/Node.js/HTML/CSS/Webpack/Vite；后端 MyBatis/Spring/Maven/MongoDB/PostgreSQL/ES；Agent 与 RAG：LangChain/LangGraph/LangChain4j/Spring AI/ReAct/RAG/Prompt Engineering；向量库 Qdrant/Milvus/Faiss/Chroma/Pinecone/向量数据库；ML：PyTorch/TensorFlow/sklearn/Pandas/NumPy；工程：Kafka/RabbitMQ/Git/Linux/LLM/NLP/CV）。
2. Java `ProfileService.agentSkillCatalog` 下发 aliases 列表；`ProfileMapper.skills` 查询补列。
3. Agent 新增 `app/resume_skills.py`：`recover_catalog_skills` 用目录（含别名）词边界匹配补回模型遗漏技能（忽略空白/连字符/大小写；纯中文别名至少 2 字符；Java 不误匹配 JavaScript；React/ReAct 冲突 needle 按精确大小写路由，全大写等歧义形式保守跳过）；`locate_quote` 从页码标记文本定位引用并计算 `page:N;line:M`（多行引用同页内拼接、按命中偏移定位首行）。
4. `AIParseResult` 增加 `unmappedDetails`（名称+原文+位置）；`_locate_unmapped` 只保留能定位原文依据的目录外候选。
5. 前端：技能事实与证据的位置旁显示「第 N 页 · 第 M 行」+（OCR 页）「OCR 识别」标注；新增「目录外技能候选」区块（名称、位置、原文引用、「添加为私有技能」按钮 → 复用 createCustomSkill + 防重复添加）；版本详情暴露 `ocrPages`/`parseMeta`。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `V20__expand_skill_catalog_aliases.sql` | aliases 列 + 45 项技能 | 扩大识别范围 |
| 新增 | `project/agent/app/resume_skills.py` | 补漏与引用定位 | 程序化找回与回查 |
| 修改 | `project/agent/app/{schemas,handlers}.py`、`skills/prompts/resume_parse.md` | 别名/unmappedDetails/全文覆盖提示 | 阶段二 Agent 侧 |
| 修改 | `project/backend/.../profile/{ProfileService,ProfileMapper}.java` | aliases 下发 | 目录补漏数据源 |
| 修改 | `project/frontend/src/api/{types,resumes}.ts`、`views/resume/ResumeView.vue` | 位置格式化/OCR 标注/目录外候选 | 阶段二前端 |

**执行命令与验证：**
- Agent pytest：新增 `tests/test_resume_skills.py` 9 项（补漏/定位/React-ReAct 区分/Java-JavaScript 区分/别名/歧义跳过）；`test_handlers.py` 补漏后断言更新通过
- 文字版附件模块实测：React/MyBatis/LangChain/LangGraph/Qdrant 等全部补回，位置如 `page:2;line:174`

**影响范围：**
技能目录全量只增不改（INSERT IGNORE 兼容演示数据）；私有技能机制复用 V11。

**遗留问题：**
无。

---

### [2026-11-22 15:40] 阶段三/四：引用定位回查与 reparse

**任务目标：**
引用与位置程序计算可回查；待确认版本可重跑、失败保留草稿。

**执行行为：**
1. `ResumeParseHandler`：模型技能与证据的 location 一律由 `locate_quote` 重算（模型填报仅兜底），无法定位的技能抛校验错误重试；实习/获奖扫描行补漏同步计算 `page:N;line:M`。
2. `ResumeService.parse` 增加可选 `reparse`：仅 PARSED 版本可重跑（幂等键区分）；在途任务（QUEUED/RUNNING/CANCEL_REQUESTED）409 拦截；`ResumeResultHandler.failure` 在已有有效草稿时回退 PARSED 而非 FAILED（保留旧草稿）。
3. 前端「重新解析」按钮（PARSED 状态，二次确认后带 reparse:true 提交）。
4. 端到端冒烟中发现并修复：Agent 下载签名 URL 被 Spring Security 用户认证拦截（401）——`/api/v1/resume-versions/*/file` 放行匿名 + owner 参数参与 HMAC 验签（不可伪造，安全性不降级）。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/agent/app/handlers.py` | locate_quote 重算位置 | 引用可回查 |
| 修改 | `project/backend/.../resume/{ResumeService,ResumeController,ResumeResultHandler}.java` | reparse/在途拦截/草稿保留 | 阶段四 |
| 修改 | `project/frontend/src/views/resume/ResumeView.vue`、`api/resumes.ts` | 重新解析入口 | 阶段四前端 |
| 新增 | `ResumeServiceReparseTest.java` | reparse/CONFIRMED 拒绝/在途拦截/OCR 路由 4 项单测 | 阶段四回归 |

**执行命令与验证：**
- Java 单测：`ResumeResultHandlerTest`（含新增空事实拒绝用例）、`FileStorageServiceLayoutTest`、`ResumeServiceCorrectionTest`、`ResumeServiceReparseTest` 共 9 项通过（`mvn -o -Djacoco.skip=true -DforkCount=0`）
- 前端：`npm run type-check`、`npm run build` 通过（仅既有大 chunk 告警）

**影响范围：**
解析接口向后兼容（reparse 可选）。

**遗留问题：**
无。

---

### [2026-11-22 15:45] 端到端验收与本机服务更新

**任务目标：**
按验收清单用两份附件完成真实链路验收，并更新本机三端服务与技能目录迁移。

**执行行为：**
1. 重启三端（Agent 8100 / Java 后端 8080 / 前端 5173）；后端启动日志确认 **V20、V21 迁移成功执行（now at version v21）**，INSERT IGNORE 对已有演示数据的 Duplicate entry 警告为预期行为。
2. API 端到端冒烟（真实 DeepSeek 模型链路）：
   - 文字版附件：上传→解析 SUCCEEDED，**26 项技能**（REACT/MYBATIS/LANGCHAIN/LANGGRAPH/QDRANT 等全部找回），示例位置 `page:2;line:49`。
   - 图片版附件：上传→RESUME_OCR 4 页全识别（ocrPages=[1,2,3,4]，pdf-ocr-v1）→自动接续解析→PARSED，**22 项技能**，OCR 第 4 页专业技能区全部识别。
   - 期间发现并修复 Agent 下载 401（见阶段三/四记录）。
3. reparse 端到端验证：首次尝试因后台进程被工具超时机制中断（环境问题非代码问题）、PowerShell 版因 curl 中文路径上传失败，最终以 `ResumeServiceReparseTest` 4 项单测覆盖 reparse 路由/CONFIRMED 拒绝/在途拦截/OCR 任务路由（surefire 报告 Tests run: 4, Failures: 0）。

**执行命令与验证：**
- 后端迁移：启动日志 `Successfully applied 2 migrations ... now at version v21`
- 文字版/图片版冒烟脚本输出 TEXT SMOKE OK / IMAGE SMOKE OK（脚本为一次性验收工具，用后已删除；两份个人附件未写入仓库）
- 前端 `http://localhost:5173/` HTTP 200（Vite 绑定 IPv6 回环属正常）

**影响范围：**
本机三端已运行新代码；业务库已升级到 v21。

**遗留问题：**
1. 登录态浏览器中的完整 UI 链路（目录外候选添加、重新解析按钮、OCR 标注展示）建议用户在真实环境点验。
2. Git 提交未创建，改动留在 `feature/bug` 工作区（含本次之前已有的大量未提交改动，均保留）。

---

### [2026-11-22 15:48:02] 缩短简历解析的模型等待并隔离单条证据错误

**任务目标：** 缩短现有简历解析的等待时间，保留全文技能识别和原文定位。

**执行行为：** 根据旧服务日志确认，最近一份四页图片简历 OCR 约 14.8 秒、模型约 112.4 秒、回写 46 毫秒；另一次单条技能引用错误触发两次完整模型请求。启动旧 Agent 的合成基准后，调整 `RESUME_PARSE` 关闭深度思考并设置 60 秒总预算。无法定位的技能交给现有目录匹配补回；无法匹配的单项证据跳过，整体无有效事实才修复。使用现有原文还原逻辑保留逐字引用。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/app/handlers.py` | 普通模式、时间预算、证据局部校验与技能补回 | 减少整份简历的模型等待和重试 |
| 修改 | `project/agent/app/schemas.py` | 隔离格式有误的单条证据 | 正常条目可继续接受原文核验 |
| 新增 | `project/backend/target/resume-speed-bench.py` | 生成并通过正常 API 提交合成 PDF 的本机验收脚本 | 获取同口径前后对比 |
| 修改 | `docs/功能资料/04-简历管理与解析/简历解析修复_process.md` | 追加本阶段记录 | 留存排查依据与操作 |

**执行命令与验证：** `rg` 检查旧 Agent 分段计时；通过 `python -u project/backend/target/resume-speed-bench.py 3` 提交基准，合成文字和图片用例已进入任务队列，终态尚待验证。

**影响范围：** 简历中心 Agent 解析及其内部模型输出检查。

**遗留问题：** 补充回归测试，完成真实服务前后对比。

---

### [2026-11-22 15:51:13] 验证解析回归和记录旧版基准

**任务目标：** 验证局部证据修复、解析总预算与旧版性能基准。

**执行行为：** 扩展 Agent 回归用例，覆盖单条引用不存在或缺失、原文逐字还原、整体无效结果、JSON 修复与修复超时。通过 Vite 代理使用两份合成 PDF 跑旧版，获取三个成功的文字版样本及一个成功的四页图片版样本。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/tests/test_handlers.py` | 检查普通模式、一次请求补回技能、整体修复次数和预算取消 | 覆盖解析提速的质量与失败边界 |
| 新增 | `project/backend/target/resume-speed-{text,scan}.pdf`、`project/backend/target/resume-speed-before.jsonl` | 合成 PDF 样本及旧版基准结果 | 对比相同输入及不同入口 |
| 修改 | `docs/功能资料/04-简历管理与解析/简历解析修复_process.md` | 追加验证结果 | 留存旧版基准和上游限制 |

**执行命令与验证：** `python -m pytest -o addopts='' -q tests/test_handlers.py tests/test_resume_skills.py tests/test_ocr.py tests/test_llm.py tests/test_resume_project.py`，98 项通过。`git diff --check` 对本次 Agent 源码与测试通过。旧版文字端到端 58.52、58.08、88.81 秒，四页图片首个成功样本 121.80 秒；后续图片模型调用及已缓存 OCR 的重跑收到 DeepSeek HTTP 402，未达到完整基准样本数。失败的重跑仍可读取旧有效草稿。

**影响范围：** 本机新增合成验收账号和简历记录，以及忽略目录中的基准输出。无结构迁移。

**遗留问题：** 部署新版并核验上游状态；真实时延对比取决于模型额度。

---

### [2026-11-22 15:51:42] 更新本机简历解析 Agent

**任务目标：** 让 8100 的 Agent 加载普通模式和解析总预算。

**执行行为：** 核对原 8100 监听进程及 SQLite 队列无活动任务；用相同 ai-job Python 环境、项目配置和数据路径重启 Agent。

**文件变更：** 新增 `project/backend/target/local-restarts/resume-parse-speed-20261122-155142/` 的 Agent 启动日志；追加本过程记录。

**执行命令与验证：** `Get-NetTCPConnection`、`Get-CimInstance`、只读 SQLite 活动任务计数、`Stop-Process`、`Start-Process`。已创建新进程，待核对健康状态与新的真实入口日志。

**影响范围：** 本机 8100 Agent 短时重启，前端和 Java 沿用原进程。

**遗留问题：** 验证新版服务及 DeepSeek 额度状态。

---

### [2026-11-22 15:54:40] 核验新版 Agent、OCR 复用和模型阻塞

**任务目标：** 验证线上入口已加载新策略，核对回归和实测限制。

**执行行为：** 通过本机 Vite 代理发起合成文字 PDF、首次四页图片 PDF、已有 OCR 文本的同版本重试。Agent 日志确认三类路径接续到普通模式 `thinking=False`；同版本重试直接创建 `RESUME_PARSE`，未再排 OCR。只读回放两份合成基准的有效结果，验证新版逐条输出的原文与位置，技能分别保留 27 项和 28 项，实习、获奖及目录外候选数量保留。

**文件变更：** `project/backend/target/` 中新增新版 `resume-speed-after.jsonl` 与 `resume-speed-after-reuse.jsonl`；验收脚本支持只跑图片组并核验模型失败后的 OCR 文本复用；追加本过程记录。

**执行命令与验证：**

- `GET /healthz`、`GET /readyz` 均为 200，运行中的 8100 使用真实 DeepSeek 和 SQLite。通过正常公开入口观察三类模型调用均返回 HTTP 402，失败回写成功。新版成功 OCR 耗时 18.046 和 12.046 秒；Agent 接收到开始执行的日志落在同一秒，回写 15 至 92 毫秒。未把 402 失败耗时当作解析成功耗时。
- 旧版同合成文字样本的三次模型耗时为 56.766、57.546、87.671 秒，中位数 57.546 秒；首次成功图片样本模型 106.109 秒、OCR 13.062 秒。剩余真实成功对照受上游 402 阻塞。
- `python -m pytest -o addopts='' -q tests/test_operations.py tests/test_runner_api.py` 通过 86 项，与前阶段 98 项定向回归合计 184 项。`mvn -o '-Djacoco.skip=true' '-DforkCount=0' '-Dtest=ResumeResultHandlerTest,ResumeServiceReparseTest' test` 通过 7 项。首次 Maven 命令未给带小数点的属性参数加引号，PowerShell 拆分导致命令失败，修正传参后通过。

**影响范围：** 本机简历解析 Agent 已更新，已有 OCR 数据可复用。基准仅用合成 PDF，验收产物位于忽略目录。

**遗留问题：** DeepSeek 账号支付或额度限制解除后，用相同样本各复测三次，核对模型中位数减半及文字 45 秒、图片 65 秒目标，并确认普通模式的真实生成质量。

---
