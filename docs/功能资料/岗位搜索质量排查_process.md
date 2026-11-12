# 岗位搜索质量排查过程记录

### [2026-11-07，Asia/Shanghai] 排查 Java 后端搜索结果方向偏离与高分失真

**任务目标：**
解释用户截图中的搜索结果质量问题，对照实际搜索快照与当前代码定位原因；本阶段仅排查，不修改业务逻辑。

**执行行为：**

1. 开始前检查过程记录：当前项目已有 `docs/功能资料/岗位推荐与投递_process.md`。规范中的 `D:\vscode\AI-JOB\docs\功能资料` 不存在，本次在实际项目根目录 `D:\py\AI-JOB` 的对应目录记录，保留原有记录。
2. 查看截图并只读查询 Agent SQLite 中最新 JOB_SEARCH 的筛选条件、来源统计、岗位公开字段。记录创建时间为 UTC 2026-11-07 02:53:48，对应上海时间 10:53:48；关键词 java后端开发、校招、杭州/北京/上海、最多 30 条，与截图一致。未记录账户、画像隐私、密钥或完整请求。
3. 来源快照：最终 12 条，其中百度 11、Web 1、腾讯 0、Greenhouse 0；Web 分析 40 页，35 页没有模型提取行，接受 3 条后招聘类型筛掉 2 条。最终城市为北京 7、上海 4、未知 1，没有确认杭州岗位。搜索成功不代表岗位覆盖充分。
4. 相关性根因：`relevance.py` 仅对规范化后精确等于 java后端 的字符串应用严格的后端标题与 Java 条件。java后端开发 走通用分支，对扩展词或任一 token 命中正文即通过。用本轮实际 JD 调用 `keyword_relevant`，7 条数据开发、移动端、AI 测试开发、前端岗位对本轮关键词全部返回 True，对 java后端 全部返回 False。
5. 评分根因：`searchScore` 对缺失分项重新分配权重；第一条城市未知，偏好分为空，技能覆盖 1、语义分 0.85 时 `(0.60 + 0.25 × 0.85) / 0.85 × 100` 四舍五入为 96。排序先比较资格、再比较分数，随后才比较在招核验，因此 UNKNOWN 来源可排在已确认在招岗位前面。
6. 资格根因：学历、毕业年份、经验要求为空时 `EligibilityService` 判为 PASS；`hasHardRequirement` 又把技能列表非空视为已有硬性要求，绕过整个要求缺失的 UNKNOWN 保护。第一条只有技能要求，未确认学历、经验、毕业年份及城市，却可显示资格通过。技能列表存在不等于已核实校招届次等资格。
7. 展示口径：模型分析后 `requirements.educationMin` 已有本科，但顶层 `educationMin` 仍为空；卡片及缺失警告读顶层字段，导致同一张卡上解释写“本科”而学历显示“未明确”。技能匹配使用简历确认事实，差距的 MISSING 使用能力评估样本数量；无评估样本不等于不会该技能，目前页面放在“主要差距”下造成误导。
8. 评分目标仍取原始画像的目标岗位、招聘类型和城市，没有将本轮关键词、校招覆盖为分析目标；截图本轮搜 Java 后端校招，原画像仍是 AI 应用开发实习，解释继续围绕 AI 方向。另发现 Web 用域名兜底公司名，页面直接作为公司展示；这表示来源网站，并非已确认招聘公司。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | docs/功能资料/岗位搜索质量排查_process.md | 记录本轮实际搜索证据、相关性复测、评分及展示根因 | 保留可追溯排查结论 |

**执行命令与验证：**

- `git status --short`：开始时只有既有 `project/agent/data/agent_runs.db-wal` 修改；未清理或覆盖该文件。
- SQLite 使用 `sqlite3.connect('file:project/agent/data/agent_runs.db?mode=ro', uri=True)`，仅 SELECT 最新搜索的必要字段；读取成功，未启动新联网搜索。
- 在 `project/agent` 通过 `conda run --no-capture-output -n ai-job python -` 执行只读复测，使用实际快照构造 SourcePosting 并调用两种关键词的 `keyword_relevant`；退出码 0，7 条偏离岗位复现上述 True/False 差异。
- 在 `project/backend` 通过 `jshell --class-path target/classes` 调用现有已编译服务：输出 `missing_city_score=96`、`skill_only_eligibility=PASS`。随后 JShell 保存历史时因 Windows Java Preferences 注册表权限失败抛出异常；这不是完整测试通过，数值输出已产生，并已对照当前源码确认相同逻辑。
- 初次 `conda run ... python -c` 探针因 PowerShell 引号转义导致 SyntaxError，改用 stdin 后复测成功；一次带 shell 通配路径的 rg 命令失败，后续以明确文件路径重新定位。
- 未运行完整单元测试、构建或浏览器交互测试：本轮为原因排查，未改业务代码；未联网重搜或重新发送用户画像到外部模型。

**影响范围：**
仅新增排查记录。未修改 API、搜索规则、数据库表结构、历史推荐结果或运行中的服务。截图分析裁剪文件仅生成在本聊天可视化输出目录。

**遗留问题：**
上述缺陷尚未修复。后续应先统一搜索意图并限制岗位方向，区分未知资格与已满足资格、补全字段及招聘公司证据，再调整核验质量与分数排序，并将评估证据不足与技能缺失分开展示。需要按真实岗位方向、城市、届次和在招证据验收，不能只以结果数量或已有测试通过作为质量结论。

---
### [2026-11-12 14:35，Asia/Shanghai] 联网岗位检索阶段一静态链路梳理与规则回归

**任务目标：**
在不修改搜索、抓取、解析、资格校验等核心代码的前提下，梳理当前岗位检索完整链路，并用既有测试确认正常路径的资格与排序规则，为固定画像真实基线诊断做准备。

**执行行为：**

1. 检查 Git 工作区，确认搜索链路、Java、前端及文档已存在大量未提交修改；本次以当前磁盘版本为基线，不回退或覆盖既有改动。
2. 只读梳理 Python LangGraph 的 plan → search/adjust → normalize → understand → finalize 链路，以及 Tavily/Brave、企业官网、百度、腾讯、Greenhouse/Lever、页面预抓、模型提取、详情核验、跨来源去重和排序实现。
3. 静态确认官网/ATS 先执行，通用 Web 按官网结果数量、城市、类型和字段完整度条件执行；Tavily/Brave 当前未传分页参数，百度与腾讯只取首屏；补搜轮次只重跑 Web。
4. 发现待分析页面写入来源上下文的 `pending_pages`，而继续搜索与最终警告读取另一份 `web_budget.pending`；该疑点需结合真实结果判断影响。
5. 只读梳理 Java 结果归一化、前置筛选、精确 URL 二次去重、资格校验、评分、排序、截断与持久化；确认正常路径下城市/类型/规模未知及在招 UNKNOWN 保留，但学历非枚举值、技能-only 要求、指标丢失和截断口径仍需列为边界风险。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/岗位搜索质量排查_process.md` | 在既有记录末尾追加本阶段静态链路与规则回归记录 | 保留可追溯诊断过程，不改动历史记录 |

**执行命令与验证：**

- 命令：`git status --short; git diff --stat; git diff --cached --stat`
- 结果：通过
- 说明：确认既有未提交变更范围，未执行清理、暂存或回退。
- 命令：`conda run --no-capture-output -n ai-job python -c "from app.config import Settings; ..."`
- 结果：通过
- 说明：当前真实模型为 DeepSeek，Tavily 已配置，Brave 未配置，Lever 未配置；默认搜索预算为每轮 12 查询、20 页面、12 次正文预抓、20 次详情核验、60 次内部 API 计数、150 秒、3 轮。输出不包含密钥。
- 命令：`mvn "-Dtest=JobSearchResultHandlerTest,EligibilityServiceTest,MatchScoreServiceTest,CompanySizesTest,JobSearchFingerprintTest,JobSearchLinksTest,JobServiceCandidateTypeTest,JobServiceMatchItemTest,JobMapperSqlTest" test`
- 结果：通过
- 说明：25 项测试，0 失败、0 错误、0 跳过；未连接 3307 测试库。Jacoco 报告有既有执行数据类不一致警告，不影响上述测试结论。
- 命令：`conda run --no-capture-output -n ai-job python -m pytest tests/test_job_search_official_priority.py tests/test_search_planning.py tests/test_search_providers.py tests/test_job_search_extraction_recovery.py tests/test_job_search_detail_fetch.py tests/test_search_metrics.py -q`
- 结果：通过
- 说明：所选搜索规划、Provider、官网优先、详情预抓、补搜和指标用例全部通过；这些测试含替身场景，不作为真实来源覆盖改善证据。

**影响范围：**
仅追加开发过程记录；未修改核心代码、API、数据库结构、前端或运行中的服务。Java 测试产生既有 `target` 构建产物，Python 测试可能产生测试缓存。

**遗留问题：**
固定画像的真实默认来源与 Web 对照检索尚待完成；静态发现的问题必须按真实漏斗数量确认影响，不能仅凭代码或单测断言其为最大损耗环节。Java 未知枚举及技能-only 边界尚无专门回归用例，本阶段不据此宣称已发生真实误杀。

---
### [2026-11-12 15:05，Asia/Shanghai] 固定画像真实检索对照与模型超时定位

**任务目标：**
使用同一固定合成画像、关键词、城市、求职类型和预算，对当前默认来源组合与 Web 单独来源进行真实复跑，量化各阶段损耗，并用受控模型探针确认 Web 提取失败原因。

**执行行为：**

1. 固定条件为关键词“后端开发”、杭州/北京/上海、实习、公司规模不限、最多 30 条；画像为脚本内合成数据，不包含真实个人简历。两次检索均未提高现有 60 次内部 API 计数、150 秒、3 轮预算。
2. 默认来源真实复跑：Web 记录 159 条 Tavily 原始命中、102 个唯一 URL，仅 20 页进入提取；4 个模型批次全部失败，20 页产出 0 条，82 个唯一 URL 未进入分析。百度首屏去重 26 条，条件筛选后 25 条、关键词相关 8 条；最终 8 条全部来自百度，腾讯和 Greenhouse 筛选后为 0。
3. 默认结果只覆盖北京 6 条、上海 2 条，杭州 0 条；6/8 带陈旧警告。薪资、经验、已知公司规模均为 0/8，学历为 6/8。DeepSeek JD 理解批次超时，8 条由规则降级保留，不能把现有指标中的 8/8 当作真实模型理解成功率。
4. 默认检索的覆盖矩阵在所有单元 analyzed=0、extracted=0 时仍全部 completed=true，并在第二轮后停止；实际待处理页面队列没有接入继续检索判断。官网优先本轮没有跳过 Web，但企业官网先消耗查询/页面/正文预算并发生 15 秒超时，存在预算挤压而非“其他来源未执行”的直接证据。
5. Web 对照复跑并增加诊断记录：6 次岗位提取中 5 次被外层 20 秒限制取消，1 次在 14.28 秒返回空岗位；另有 1 次在来源总超时下于 16.48 秒取消。唯一成功调用输入 4754 tokens、输出 854 tokens、响应 JSON 15 字符。对照同时暴露汇总指标重复累计：来源记录 180 条命中，而总漏斗写为 420，后者不能作为真实原始召回数。
6. 执行一次真实 5 页、90 秒上限的受控提取探针：模型 52.953 秒成功返回，接受 1 条杭州实习岗位；输入 2449 tokens、输出 3502 tokens、响应 JSON 381 字符。证明 20 秒外层限制会丢失可提取结果，但不据此宣称全链路已改善。探针岗位公司名与标题中的招聘企业不一致，列为后续准确性与来源归属核验样本。
7. Java 边界探针：画像学历为 null 且岗位要求本科时结果为 UNKNOWN；画像学历为字符串 UNKNOWN 时结果为 FAIL；只有技能要求且其余事实未知时 EligibilityService 返回 PASS。前者符合预期，后两项分别列为未知误判不符合和未知误判通过风险。Java 搜索处理器中资格 FAIL 本身不删除候选，因此不是本轮 20 页 Web 全损的原因。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/scripts/verify_job_search.py` | 新增可选 `--diagnose-llm`，记录模型阶段、输入输出字符数、耗时、异常类型和 token，不记录提示词正文 | 获取 Web 提取失败的直接证据，不改变默认检索行为 |
| 新增 | `project/agent/scripts/probe_job_search_extraction.py` | 对一个真实 Tavily 页面批次执行受控超时提取并保存公开岗位诊断 | 验证当前提示词在不被 20 秒提前取消时是否能产出岗位 |
| 新增 | `project/agent/data/diagnosis_phase1_full_20261112.json` | 默认来源固定画像真实基线 | 保存优化前漏斗、来源、覆盖、预算和岗位样本 |
| 新增 | `project/agent/data/diagnosis_phase1_web_20261112.json` | Web 对照及模型调用诊断 | 区分来源发现问题与模型提取问题 |
| 新增 | `project/agent/data/diagnosis_phase1_llm_probe_20261112.json` | 单批次 90 秒受控探针结果 | 证明可提取批次被短外层超时取消，并保留准确性核验线索 |
| 修改 | `docs/功能资料/岗位搜索质量排查_process.md` | 追加真实复跑、模型探针与 Java 边界结果 | 保留可追溯诊断证据 |

**执行命令与验证：**

- 命令：`conda run --no-capture-output -n ai-job python scripts/verify_job_search.py --keyword "后端开发" --cities "330100,110100,310100" --candidate-types "INTERNSHIP" --sources "company_official,web,baidu,tencent,greenhouse" --max-results 30 --output "data/diagnosis_phase1_full_20261112.json"`
- 结果：通过
- 说明：真实 DeepSeek/Tavily/官网检索完成，130.061 秒，最终 8 条；Web 提取为 0，不作为覆盖改善结果。
- 命令：`conda run --no-capture-output -n ai-job python scripts/verify_job_search.py --keyword "后端开发" --cities "330100,110100,310100" --candidate-types "INTERNSHIP" --sources "web" --max-results 30 --diagnose-llm --output "data/diagnosis_phase1_web_20261112.json"`
- 结果：检索流程执行完成并产出诊断文件；脚本因最终 0 条按既有约定返回 1，`conda run` 末尾因此报告失败
- 说明：120.014 秒；该退出码表示无岗位，不是诊断脚本异常。模型取消与空响应证据已保存。
- 命令：`conda run --no-capture-output -n ai-job python scripts/probe_job_search_extraction.py --query "杭州 后端开发 实习 招聘 职位" --keyword "后端开发" --city "330100" --pages 5 --timeout 90 --output "data/diagnosis_phase1_llm_probe_20261112.json"`
- 结果：通过
- 说明：Tavily HTTP 200，5 页进入提取，模型 52.953 秒返回，接受 1 条；总耗时 62.359 秒。仅为受控探针，不等于全链路验收。
- 命令：`@' ... '@ | jshell --class-path target/classes`
- 结果：通过
- 说明：实际输出 `NULL_EDUCATION=UNKNOWN`、`STRING_UNKNOWN_EDUCATION=FAIL`、`SKILL_ONLY_REQUIREMENT=PASS`。

**影响范围：**
仅修改/新增诊断脚本、诊断数据和过程记录；未修改搜索 Provider、搜索编排、网页抓取、JD 解析、Java 资格校验、前端、数据库结构或运行中服务。真实调用产生 Tavily 与 DeepSeek API 使用量，未输出或写死任何密钥。

**遗留问题：**
当前输出缺少正文抓取成功/失败分项，无法诚实计算抓取成功率；直接 Pipeline 基线未经过 Java 回调，真实 Java 资格通过率尚未测量。Brave 无 Key、Lever 无 board、人工标注集为空，未执行这些来源联调或召回率计算。探针只证明一个批次可提取，不能外推全部页面质量；需先修模型策略与停止条件，再按相同条件复跑对比。

---
### [2026-11-12 15:20，Asia/Shanghai] 阶段一诊断报告与最终一致性检查

**任务目标：**
汇总当前链路、真实漏斗、模型与 Java 边界证据，按影响程度给出阶段二改造方案，并在不进入核心代码修改的前提下完成阶段一交付检查。

**执行行为：**

1. 新增阶段一诊断报告，列出完整执行链路、固定画像与搜索条件、各来源输入输出、失败与淘汰原因、模型和官网成本耗时、Java 未知字段行为，以及按 P0/P1 依赖排序的具体改造步骤。
2. 明确本轮最大损失为 Web 模型提取 20/20 页全损；官网优先本轮未跳过 Web，Java 资格 FAIL 不删除候选。报告不使用重复累计的漏斗值，不将规则降级保留说成模型解析成功，不在缺人工标注时虚构召回率。
3. 阶段二方案确定先修指标、模型执行策略和待处理队列/停止条件，再扩展 Search Planner、Provider 分页与 ATS，最后完善抓取、去重、来源可信度和 Java 未知值边界；全程保持现有 API 与前端契约。
4. 校验三个诊断 JSON 均可解析、两个诊断脚本均可编译，检查本次变更清单和 Git 空白错误；未提交任何变更。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `docs/功能资料/联网岗位检索阶段一诊断报告_20261112.md` | 阶段一完整诊断、实测数据、成本耗时、分级改造和阶段三验证方案 | 形成可评审交付，确认原因后再进入核心代码修改 |
| 修改 | `docs/功能资料/岗位搜索质量排查_process.md` | 追加报告与最终检查结果 | 保持日志与实际变更一致 |

**执行命令与验证：**

- 命令：`conda run --no-capture-output -n ai-job python -c "import json, pathlib, py_compile; ..."`
- 结果：通过
- 说明：输出 `JSON_FILES_OK=3; SCRIPTS_COMPILE_OK=2`。
- 命令：`git diff --check -- "project/agent/scripts/verify_job_search.py" "docs/功能资料/岗位搜索质量排查_process.md"`
- 结果：通过
- 说明：未发现空白错误，仅有仓库既有 LF/CRLF 提示。
- 命令：`git status --short -- <本次诊断文件>`
- 结果：通过
- 说明：确认变更为 1 个既有诊断脚本修改、1 个新探针、3 个新 JSON、1 份新报告和既有过程文件追加；未混入核心代码修改，未执行 git commit。

**影响范围：**
阶段一仅新增诊断证据、诊断脚本和文档。未改变线上检索行为、API、数据库、前端展示或推荐流程。

**遗留问题：**
阶段二和阶段三尚未执行；报告中的方案不是已完成能力。下一步应先实施指标口径与模型执行策略修复，并使用本次相同条件复跑，再处理待处理页面和提前终止。真实 Java 资格通过率、抓取成功率、人工样本召回/准确性和完整 API 成本仍待后续验证。

---

### [2026-11-12 15:01，Asia/Shanghai] 复核搜索漏斗并出具诊断报告

**任务目标：**
按当前工作区源码和已有实跑快照，完成关键词、Provider、预算、分页、详情、JD、去重、资格与排序诊断，量化损耗并复现提前停止。

**执行行为：**

1. 检查当前工作区、现有过程日志和 3 份实跑快照。用户说明中的旧路径未找到，沿用当前工作区的功能资料目录和原过程日志。
2. 从 Web 来源明细重算漏斗。默认组合 159 个命中、102 个可用唯一链接、20 个提取页次、0 个 Web 岗位，最终 8 个百度岗位。Web 对照模型诊断逐条复核为 4 次约 20 秒取消、1 次 16.48 秒取消、1 次返回空列表。报告以原始快照为准，历史记录原样保留。
3. 读取当前 Agent 配置的非敏感项，确认 Tavily 可用、Brave 未配置、Lever 无 board，DeepSeek thinking 为开启且 effort 为 high。复核 Java 默认限额与诊断脚本限额差异。
4. 使用内存探针直接验证覆盖矩阵和补搜路由。三次查询得到 30 个命中，分析及产出均为 0，待处理队列尚有 1 页时路由进入 normalize。另验证 10 个有效候选、本轮无新增且存在缺口时停止，以及 150 秒总配置只留 120 秒采集。
5. 运行 Python 和 Java 定向用例，核对 Java 测试样本的城市淘汰、资格分布和最终顺序。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `docs/功能资料/岗位搜索链路诊断报告.md` | 记录完整链路、来源与阶段漏斗、停止原因、指标口径及后续验证顺序 | 交付可复核诊断报告 |
| 修改 | `docs/功能资料/岗位搜索质量排查_process.md` | 在末尾追加本次复核、探针与测试结果 | 记录实际操作并保留历史 |

**执行命令与验证：**

- 命令：`git status --short --branch`、`git log -5 --oneline`、`rg -n`、`Get-Content`，以及 `python -` 标准输入脚本读取诊断 JSON。
- 结果：通过。工作区为已有未提交修改的 main。按来源细分的命中、预抓、提取和规则损耗已核对。
- 命令：`D:/Anaconda/envs/ai-job/python.exe -`，从标准输入读取本地配置摘要及执行内存断言。
- 结果：通过。配置输出仅保留启用状态和非敏感值，覆盖路由、结果阈值及采集时间断言全部通过。
- 命令：`D:/Anaconda/envs/ai-job/python.exe -u -m pytest --noconftest -p no:cacheprovider tests/test_search_planning.py tests/test_job_search_dedupe.py tests/test_search_metrics.py tests/test_job_search_extraction_recovery.py -k 'not async' -ra --color=no`，关闭 pytest 插件自动加载。
- 结果：30 项通过，2 条警告来自未加载的 asyncio 插件对应配置项。最初 conda 启动遇到 Windows DLL 重定位错误。直接运行带项目 conftest 的测试等待期间无结果，已结束，改为独立定向用例。
- 命令：`mvn -o "-Dtest=JobSearchResultHandlerTest,EligibilityServiceTest,MatchScoreServiceTest,CompanySizesTest,JobSearchFingerprintTest,JobSearchLinksTest" "-Djacoco.skip=true" "-DargLine=-javaagent:D:/maven/maven-rep/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar" "-Dsurefire.timeout=35" test`。
- 结果：20 项通过，0 失败、0 错误、0 跳过。首次 Maven 运行遇到 Mockito 动态 attach 管道访问错误，已结束该次运行，重跑从命令行显式加载 Mockito agent。未修改 POM 或依赖。

- 文档检查：报告 16 个相对链接均指向现存文件，3 个原始快照的主漏斗和模型取消计数断言通过。

**影响范围：**
本次只新增报告、追加过程记录。Java 定向测试写入本地 target 构建及测试报告目录。业务代码、运行服务和数据库无操作。

**遗留问题：**
实跑证据沿用已有合成画像快照，当前轮未再发起外网请求或真实 Java 回调。正文预抓成功率、Java 实跑资格分布、腾讯列表原始数量、Stripe 分项淘汰原因和目标岗位召回率缺少证据。

---

### [2026-11-12 15:42，Asia/Shanghai] 阶段二检索核心改造

**任务目标：**
完成 P0 网页提取、待处理队列、来源预算和漏斗修复，沿用工作区已有阶段二代码。

**执行行为：**

1. 网页优先解析有证据的结构化岗位和正文，模型处理未解析页面。提取请求限制输入和总等待时间，使用 DeepSeek 非思考模式及一次有限重试。确定性候选在等待模型前保留。
2. 将逐源待处理 URL 保留到批次完成，接通补搜路由和终止状态。把原始轮次增量与展示累计口径拆开，补充超时、模型、抓取和待处理数量。
3. 接通官网与直连来源限额，短暂等待官网后允许 Web 与未完成来源并行，复用现有 ATS，腾讯按上限续页。核查 Tavily 与 Brave 的官方接口参数。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/app/config.py`、`app/llm.py` | 收紧提取请求配置，按请求控制思考模式 | 减少批次延时并保留其余模型行为 |
| 修改 | `project/agent/app/search/budget.py`、`pipeline.py` | 接入来源预算、补搜状态、非阻塞来源调度与计数 | 修复早停和重复统计 |
| 修改 | `project/agent/app/search/sources/web.py`、`detail.py`、`company_official.py`、`base.py` | 确定性优先、保留部分产出、来源计数 | 缓解网页和模型故障 |
| 修改 | `project/agent/app/search/sources/baidu.py`、`tencent.py`、`greenhouse.py`、`lever.py`、`providers/tavily.py` | 计入来源请求，限制分页，获取日期 | 扩展和衡量真实召回 |
| 修改 | `project/agent/scripts/verify_job_search.py` | 增加预算固定、HTTP 计数、Java 回放选项 | 支撑阶段三验收 |

**执行命令与验证：**

- `python -c` 导入检索编排并读取本机配置，通过。启用 DeepSeek 和 Tavily，未配置 Brave，全局配置为 60 次、150 秒、3 轮。
- `curl.exe --noproxy "*" --max-time 8 ...` 在默认沙箱中返回 DNS 失败。代理模式也无法连接本机代理，真实验收需使用工具批准的网络执行环境。

**影响范围：**
Agent 联网岗位检索，现有请求与结果格式保留。业务数据无写入。

**遗留问题：**
Java 资格与回调指标、回归测试及四场真实运行继续处理。

---

### [2026-11-12 15:53，Asia/Shanghai] 阶段二资格、结果契约及抽查纠偏

**任务目标：**
补齐 Java 三态资格与回调观测，消除首轮实跑发现的泛页面和明显错误招聘类型，并完成回归准备。

**执行行为：**

1. 通过获准网络运行 Tavily、DeepSeek 和招聘来源。第一次启动暴露来源预算视图缺少轮次和缓存属性，修复后跑完四个场景的首轮结果。首轮基线产生 16 个 Agent 候选，但抽查发现薪资页、搜索聚合页和社招岗位混入，保留为中间排查，不作为正式验收数字。
2. 排除明显非岗位页面，增加岗位专属位置和类型证据，使用候选实际城市及类型更新覆盖，补充当前来源网络开销和 JD 模型命中率。网页和 JD 的 DeepSeek 调用保持原返回格式。
3. Java 修复无效画像事实被当成明确不符合、仅技能被当成硬资格已确认。增加淘汰分项、资格分布、截断和 Agent 指标。用已有 stage SSE 承载增量候选，前端和数据库的最终结果契约保留。
4. 用实跑 payload 回放发现旧 Agent 结果 schema 拒绝未知招聘类型，合成画像样例 UUID 也无效。使 Agent 允许未知类型，并修正样例 UUID，让 Java 按现有逻辑标记未知。
5. 以仓库已锁定版本将 Playwright 临时安装到隔离目录，复用本机 Chromium；未改环境清单。启动最终四场实跑和回归复验。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/app/handlers.py`、`runner.py`、`schemas.py`、`llm.py` | 增量候选事件、接受未知类型、保留全局 DeepSeek 超时行为 | 接通正常入口并兼容现有调用 |
| 修改 | `project/agent/app/search/budget.py`、`pipeline.py`、`metrics.py`、`relevance.py` | 接通预算属性，准确统计 JD 模型产出、去重和覆盖，加强技术方向匹配 | 修正实跑暴露的错误与指标 |
| 修改 | `project/agent/app/search/providers/base.py`、`tavily.py`、`sources/web.py`、`detail.py`、`render.py` | 接收用量，过滤明显列表页，补地点、类型与 HTML 元数据，动态请求计入预算 | 提高结果准确性和预算可见性 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/job/EligibilityService.java`、`JobSearchResultHandler.java` | 补齐三态边界与摘要，陈旧岗位同资格下向后排 | 保持数据完整并优先较新岗位 |
| 新增／修改 | `project/agent/tests/test_job_search_phase2.py`、`test_search_planning.py`、`test_search_metrics.py`、`test_job_search_official_priority.py` | 增加模型降级、队列和 SSE、误入页面、未知类型契约回归 | 验证主要故障修复 |
| 修改 | `project/backend/src/test/java/com/aijob/backend/job/EligibilityServiceTest.java`、`JobSearchResultHandlerTest.java` | 增加未知事实、技能-only 资格和可选真实输入隔离回放 | 统计 Java 资格与排序 |
| 修改 | `project/agent/scripts/verify_job_search.py` | 修正合成输入并走生产结果校验 | 复用正式契约 |

**执行命令与验证：**

- Java 定向 Maven 首轮为 22 项通过、1 项未传真实回放目录而跳过。
- Python 初轮报告覆盖测试按 query 数误计格子、Playwright 未安装、DeepSeek 现有超时契约差异。加装隔离依赖后，152 项通过、2 项夹具口径未更新，已调整后复验。
- 四个首次联网场景退出码均为 0。正式版本在相同搜索条件与预算下重新采集，不将中间结果混入最终对照。

**影响范围：**
搜索业务源码与测试，Java 摘要 JSON 新增字段，岗位接口和数据库表结构保持现有定义。

**遗留问题：**
最终真实快照、Java 回放、统计及源代码状态核对进行中。尚未取得人工标注全集及账号账单。

---

### [2026-11-12 16:08，Asia/Shanghai] 阶段三联网复验与真实 JD 纠偏

**任务目标：**
以相同检索预算完成基线和三个扩展场景，核对真实岗位质量、来源、资格与剩余外部限制。

**执行行为：**

1. 通过正式 Tavily、DeepSeek 与公开岗位来源完成各场景。模型正常时，同条件基线为 124 个命中、87 个唯一 URL、34 页处理、9 页产出、11 个 Agent 候选，耗时 63.406 秒。人工查看结果发现三条 Web 结果尚未确认城市和在招状态，其中一条明显偏离后端，未当作有效增量。
2. 复跑时确认 DeepSeek HTTP 402，补充任务内停止重复调用和警告。四场降级依次产出 8、1、0、1 个候选，平均 86.378 秒。深圳为真实零结果，脚本按约定退出 1 并保留原始数据。Tavily 偶发多个请求超时，未把零响应解释为零岗位库存。
3. 接通官网待处理队列续跑和结束后的 URL 清单。最终基线单次重试收到 34 个 Tavily 命中、32 个唯一 URL、20 个分析页、1 条确定性提取和 9 个 Agent 候选，耗时 66.922 秒。API 内部计数 60、实际 HTTP 59，保留 9 个 Web URL。未对额外的暂时阻断反复重试。
4. 用真实回放发现广州样本原文已过投递区间，上海重试样本存在未结构化的明确毕业届次。增加投递日期区间识别、显式届次补验、证据内明确学历口径修正和跨行年限证据。原始联网快照原样保留，最终规则在抓获的 JD 上离线复验。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/app/llm.py`、`app/search/pipeline.py`、`app/search/sources/web.py`、`detail.py`、`app/search/relevance.py` | 记录永久模型错误、保留待处理 URL、补充招聘状态、日期和年限证据 | 支持真实外部故障下的降级并纠正实际样本 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/job/JobSearchResultHandler.java` 及其测试 | 明确届次和学历、经验引文回归；回放测试支持空匹配分 | 防止未知或冲突资格被误判 |
| 修改 | `project/agent/tests/test_job_search_phase2.py`、`scripts/verify_job_search.py` | 增加永久错误、官网续跑、明确投递期回归，诊断输出 HTTP 状态 | 准确定位错误并验证处理 |
| 新增 | `project/agent/data/phase3_*_20261112.json` | 保存不同阶段和场景的独立联网结果 | 提供实际证据 |
| 新增 | `docs/功能资料/联网岗位检索阶段二与阶段三验收.md` | 汇总代码、真实漏斗、费用口径、验证命令和遗留问题 | 交付可复核结果 |

**执行命令与验证：**

- 按场景执行 `python scripts/verify_job_search.py --phase-one-budget --diagnose-llm ...`，使用本地合成画像、原 60 次／150 秒／3 轮预算及公开岗位。记录详见验收报告和各独立 JSON。
- Maven 第一次真实回放因测试记录把可空匹配分声明为基本类型失败，修改测试夹具后，23 项通过且 6 个真实输入文件完整回放。届次及投递期修复后继续运行最终回归。
- `npm.cmd run type-check` 通过。Python 上一轮为 194 项通过。浏览器依赖和原始回放暂存已移至本地忽略的 `project/backend/target`。

**影响范围：**
检索、降级、在招核验、Java 资格与可观测性。测试使用本地隔离的模拟持久层，业务数据库未写入。

**遗留问题：**
DeepSeek 402 与 Tavily 请求超时使健康路径下的最终质量和覆盖验收未达标。人工标注全集、真实数据库端到端和账号账单缺证据。

---

### [2026-11-12 16:09，Asia/Shanghai] 阶段二和阶段三交付核对

**任务目标：**
对最终代码、真实响应回放、验证命令与验收报告进行一致性检查。

**执行行为：**

1. 完成最终 Python 与 Java 回归。用捕获的广州 JD 直接调用截止检测，确认最终规则识别为已关闭。用 Java 回放确认最终基线为 PASS 6、UNKNOWN 2、FAIL 1，新增上海样本因明确届次不符合而判为 FAIL。
2. 汇总四场独立降级复验、模型可用基线和最终单次重试，不混淆候选增长与有效在招岗位增长。
3. 将真实漏斗、资格分布、成本口径、原始数据链接、修改文件和复跑命令写入验收文档。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/联网岗位检索阶段二与阶段三验收.md` | 写入最终测试、真实回放资格分布和明确的未达标项 | 完成交付 |
| 修改 | `docs/功能资料/岗位搜索质量排查_process.md` | 追加最终验证结果 | 保留可追溯记录 |

**执行命令与验证：**

- `python -m pytest` 岗位检索、DeepSeek、Agent handler 和动态渲染定向套件，195 项通过。
- `mvn -o -Dtest=JobSearchResultHandlerTest,EligibilityServiceTest,MatchScoreServiceTest,JobSearchLinksTest,JobSearchFingerprintTest,CompanySizesTest -DjobSearchReplayDir=target/job-search-phase3-validation ... test`，25 项通过，0 失败、错误和跳过。Windows 环境通过命令行使用本机 Mockito javaagent，不修改项目 POM。
- `npm.cmd run type-check`，通过。
- `git -c core.fsmonitor=false diff --check` 定向检查通过，仅有行尾转换提示。

**影响范围：**
最终结果保留在当前工作区。真实搜索使用合成画像，Java 回放使用模拟持久层。

**遗留问题：**
当前 DeepSeek 仍返回 402，Tavily 有实测超时。未确认整体有效岗位量、新鲜度及覆盖改善。未测真实数据库回调、浏览器展示或人工标注指标。

---

### [2026-11-12 16:15，Asia/Shanghai] 重启 AI-JOB 本地三服务

**任务目标：**
重启前端、Java 后端和 Python Agent，验证当前工作区版本可启动。

**执行行为：**

1. 用端口和进程命令识别 Vite、BackendApplication 与 Agent，仅停止三项原服务。通过后台 `Start-Process` 按原开发入口启动 Vite、`mvn -o spring-boot:run` 和 `python run.py`。
2. 首次重启的 Java 默认绑定所有网卡，发现后立即设置 `SERVER_ADDRESS=127.0.0.1` 并重启 Java，恢复原监听范围。Redis 和 MySQL 沿用原进程。
3. 最终监听 PID 为前端 15700、Java 35036、Agent 36720，端口分别为 5173、8080、8100。Agent 复用阶段三隔离的 Playwright 依赖路径。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/岗位搜索质量排查_process.md` | 追加服务重启和健康验证 | 记录实际操作 |
| 新增 | `project/backend/target/restarts/20261112-161235/` | 后台启动日志，本地构建产物 | 保留排障依据 |

**执行命令与验证：**

- `Get-NetTCPConnection`、`Get-CimInstance Win32_Process` 和 `netstat -ano -p tcp`，确认三服务归属、新 PID 及三个端口仅监听本机。
- `curl.exe` 检查前端首页及 Agent `/healthz`、`/readyz`，均 HTTP 200。Java 直连与 Vite 转发的 `/api/v1/users/me` 均返回未登录预期的 HTTP 401。
- Java 启动日志确认数据库连接池启动，Flyway 校验 9 个迁移且无需迁移。`redis-cli ping` 返回 `PONG`。

**影响范围：**
仅本机开发服务重启和日志记录，无业务代码改动。

**遗留问题：**
未使用用户账号登录或再次发起联网岗位搜索。

---
