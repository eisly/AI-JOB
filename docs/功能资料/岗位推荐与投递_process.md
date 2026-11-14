### [2026-11-06 11:46] 建立隔离工作树并合并入口

**任务目标：** 合并岗位匹配和 AI 搜索入口，保留现有开发改动。

**执行行为：** 基于本地 origin/main 的 023d299 建立 codex/job-recommendation 工作树。统一页签、旧地址跳转及任务入口。

**文件变更：** 更新前端路由、侧栏、岗位页签和现有岗位链接。

**执行命令与验证：** git worktree add 成功。前端构建与交互验证待执行。

**影响范围：** 岗位导航及相关业务深链接。

**遗留问题：** 搜索适配与投递确认待实现。

---

### [2026-11-06 11:48] 接入投递确认与搜索结果展示

**任务目标：** 在统一页面内查看来源、优先级和投递计划。

**执行行为：** 将搜索结果及岗位详情接入现有投递表单，复用岗位 ID 和 PLANNED 状态。折叠来源诊断，保留主检索异常提示和任务直达。

**文件变更：** 修改 JobMatchView.vue、JobSearchView.vue、ApplicationForm.vue。

**执行命令与验证：** 前端构建及页面交互待验证。

**影响范围：** 岗位结果、岗位详情及投递确认。

**遗留问题：** 全网搜索适配待完成。

---

### [2026-11-06 11:51] 接入全网检索和筛选

**任务目标：** 以公开网页检索为主，严格保留用户选择的筛选条件。

**执行行为：** Python Agent 调用 Tavily 和 DeepSeek，依据页面原文提取岗位并筛选。保留官网来源和现有搜索任务，补搜时仅调整同义词。区分检索失败、部分失败及空结果。

**文件变更：** 新增 search/sources/web.py；更新 Agent 搜索图、来源注册及配置，更新 Java 默认来源和环境变量示例。

**执行命令与验证：** Python 搜索回归及真实 API 验证待执行。

**影响范围：** 新发起的岗位搜索及来源状态。

**遗留问题：** Java 排名截取和真实场景验收待完成。

---

### [2026-11-06 11:52] 落实排序和结果上限

**任务目标：** 在结果集上按资格和匹配度排序后分页。

**执行行为：** Java 在回调时校验筛选并计算资格和分数，按来源和发布日期处理并列，按上限持久化。列表和计数统一规模未知筛选。

**文件变更：** JobSearchResultHandler.java、JobSearchService.java、JobSearchMapper.java。

**执行命令与验证：** 待运行后端回归和端到端搜索。

**影响范围：** 新搜索的入库排名及搜索结果查询。

**遗留问题：** 真实检索覆盖率和浏览器流程待验证。

---

### [2026-11-06 12:11] 修复联调中发现的问题

**任务目标：** 让真实网页结果可稳定进入排序，并消除并发提交冲突。

**执行行为：** 真测发现模型会以字符串返回网页序号，适配器改用原样匹配网页序号。单城市分组和原文引述筛选提高了有效职位覆盖。补充来源经岗位关键词过滤；搜索评分按可用证据计算。任务幂等事务先锁定用户，再插入带外键的幂等记录。修复移动端标题重叠和历史链接发起新搜索后的地址状态。

**文件变更：** 更新网页适配器、评分、任务幂等、前端布局及相关回归。

**执行命令与验证：** Agent 全套回归 287 通过、1 跳过。Playwright 通过真实登录、默认页签、可信岗位预填和旧搜索入口跳转。真实接口中期对照实习 0→4、Java 岗位总量 15→17，已继续收紧 Java 补充来源相关性。Java 相关用例通过；另有两个依赖演示数据的用例在隔离库缺少对应预置记录。

**影响范围：** 检索质量、匹配排名、同账号任务并发和小屏页面。

**遗留问题：** 最新服务的并发搜索和投递保存待复测。

---

### [2026-11-06 12:19] 完成真实搜索与浏览器验收

**任务目标：** 确认真实搜索、排名、历史入口和计划投递形成闭环。

**执行行为：** 使用独立 MySQL 验收库、Java 8081、Agent 8101 和前端 5174。对两种条件分别并发提交旧来源组合和 AI 全网组合，检查资格状态、排序、筛选和来源。Playwright 使用虚构用户验证桌面和移动端。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/agent/app/search/sources/web.py | 排除网页中明确的已结束、已截止标记 | 避免收录已关闭职位 |
| 修改 | project/agent/tests/test_job_search_sources.py | 覆盖关闭职位的提取筛选 | 回归真实查询发现的误收 |
| 修改 | project/frontend/src/layouts/MainLayout.vue | 为岗位页面滚动区预留顶部工具栏空间 | 避免滚动时工具栏盖住匹配分 |

**执行命令与验证：**

- Agent pytest 全套回归 288 通过、1 跳过。前端 vue-tsc 与 Vite 构建通过。
- Java 隔离环境回归 79 通过；固定依赖另一测试库的认证、投递集成套件以及依赖演示数据的数据库查询套件未计入。真实 HTTP 联调覆盖投递保存。
- 真实 Tavily 与 DeepSeek 对照任务全部成功。同账号并发提交未复现锁冲突。AI 应用开发实习从 0 条到 4 条；Java 后端从旧来源的 15 条宽匹配收敛到 4 条明确的 Java 开发岗位，旧来源前 10 条中有 2 条标题明确包含 Java。新版 Java 结果含智联和猎聘公开网页，各 1 条。
- 实测分页第二页仍返回原排名第 2 条；排除未知规模生效；未核验来源没有核验时间。
- Playwright 验证旧链接、任务中心直达、投递表单预填、打开原页不新增记录、确认后仅新增一条关联岗位的 PLANNED 记录、投递进度可见和顶部布局。前端未发现页面运行时异常。无 Browser 插件，使用 Playwright Chrome 独立会话。

**影响范围：** 新岗位入口及搜索结果，投递记录沿用既有接口。

**遗留问题：** 真实样本中 Greenhouse 查询仍超时。公开搜索覆盖受搜索服务索引及网页公开程度影响，结果页显示来源核验状态。

---

### [2026-11-06 12:20] 完成最终构建与专项回归

**任务目标：** 核对最终源码与验证结果。

**执行行为：** 将前端构建输出放在隔离工作树外的验收目录，恢复构建产生的仓库内文件变化。检查原工作区已有改动及最终差异。

**文件变更：** 仅追加验收记录。

**执行命令与验证：** 最终前端构建成功。Java 岗位排序、链接与任务幂等专项 10 项通过。git diff --check 通过。

**影响范围：** 验收和交付记录。

**遗留问题：** 认证及投递的完整集成测试依赖项目约定的专用数据库。本次在隔离验收库通过真实登录和投递接口检查对应流程。

---


### [2026-11-06 12:26] 将岗位功能合入本地主分支

**任务目标：** 直接在本地主分支启用已验收的岗位推荐与投递功能。

**执行行为：** 提交隔离分支的 30 个功能、测试与记录文件。备份同名设置与生成声明，快进 main 到 055a025，原样恢复已有的 IDE 暂存改动。原有前端声明新增项已包含在主分支的声明中。

**文件变更：** 合入岗位搜索、统一入口、投递计划和配套验证记录。本地配置备份另行保留。

**执行命令与验证：** git merge --ff-only 成功；核对 main 提交与功能分支一致，IDE 暂存补丁保持一致。

**影响范围：** 本地 main；默认业务服务随后从该工作区启动。

**遗留问题：** 服务准备及启动验证进行中。

---


### [2026-11-06 12:29] 启动主分支服务

**任务目标：** 用主分支工作区运行前端、Java 后端和 Python Agent。

**执行行为：** 从 main 打包后端，按锁文件安装前端依赖。沿用原 MySQL 业务库、Redis、简历文件目录、Agent SQLite 和模型配置，在原端口替换三个应用进程。主分支验证完成后关闭之前由本任务启动的隔离验收服务。

**文件变更：** 仅追加启动记录。

**执行命令与验证：** Maven 打包和 npm ci 成功。前端 5173 的岗位入口及实际 Vue 模块返回 200；后端 8080 已连接 career_business，直连与代理均正确拒绝未登录的岗位 API 请求；Agent 8100 返回 ready，确认 DeepSeek、SQLite 和 Tavily 配置可用。

**影响范围：** 本机 AI-JOB 运行实例及本地 main。

**遗留问题：** 服务启动验收使用只读探测。

---

### [2026-11-06 21:01] 拆分岗位发现与在招核验

**任务目标：** 扩大岗位搜索覆盖并阻止未经核验的岗位入榜。

**执行行为：** 运行两组真实请求留存改造前基线，检查百度、字节和杭州电子科技大学的公开样例。先在 Agent 中拆分搜索和详情核验，并让 Java 回调与前端结果只接受有核验记录的岗位。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | project/agent/app/search/sources/detail.py | 受限详情读取、失效和投递入口判定 | 避免直接采用搜索摘要 |
| 修改 | project/agent/app/search/sources/web.py、tencent.py、greenhouse.py（均在 sources 目录） | 按条件拆分查询、核验预算、官方接口证据 | 扩大有效候选并剔除失效信息 |
| 修改 | project/agent/app/search/models.py、pipeline.py；project/agent/app/schemas.py | 透传核验数据、汇总诊断并限制入榜 | 区分来源可信与在招状态 |
| 修改 | project/backend/src/main/java/com/aijob/backend/job/JobSearchResultHandler.java、JobSearchService.java | Java 入榜二次校验及保存、返回核验快照 | 拒绝无证据回调 |
| 修改 | project/frontend/src/api/types.ts、views/job-search/JobSearchView.vue（后者位于 project/frontend/src） | 在招标签、来源诊断和重新搜索 | 展示核验结果并提示历史批次 |

**执行命令与验证：** 使用本机 ai-job Python 环境执行临时对照脚本。改造前 Agent 返回 AI 应用开发实习 10 个、Java 后端校招 1 个候选。此时未验证其在招状态，也未运行改造后的测试。

**影响范围：** AI 岗位搜索及结果快照；无需数据库迁移。

**遗留问题：** 待增加来源覆盖、完成回归及新旧对照。用户指定的另一工作区记录目录不存在，记录追加在当前仓库的对应文件。

---

### [2026-11-06 21:19] 接入百度官网当前职位列表并分层验证

**任务目标：** 增加国内公开岗位来源，验证 Agent、Java 与页面在招契约。

**执行行为：** 通过官网公开列表的搜索参数验证分类、关键词回显和职位 ID。以当前职位列表作为在招证据，单独排除明确下线或超过投递截止日期的记录。多城市职位保留一个与本次筛选匹配的条目。新增同 URL 去重及跨域同岗合并处理，保留官网 JD。根据实网 JD 修复中文紧邻 Java 时的关键词边界误判，拒绝 JavaScript 和不相关算法岗。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | project/agent/app/search/sources/baidu.py | 官网公开职位列表适配器 | 增加中文岗位及在招证据 |
| 修改 | project/agent/app/config.py、app/search/sources/registry.py（后者位于 project/agent）；project/backend/src/main/resources/application.yml | 启用百度来源 | 两端来源选择一致 |
| 修改 | project/agent/app/search/pipeline.py、app/search/sources/{detail,web,tencent,greenhouse}.py（均位于 project/agent）；project/backend/src/main/java/com/aijob/backend/job/{JobSearchService,JobSearchResultHandler}.java | 调整词匹配、身份去重、证据校验和查询预算 | 保持硬筛选，减少误判 |
| 修改 | project/agent/tests/test_job_search.py、test_job_search_sources.py（均位于该 tests 目录）；project/backend/src/test/java/com/aijob/backend/job/JobSearchResultHandlerTest.java | 加入下线、超时、跳转、在招证据和过滤场景 | 覆盖本次回归风险 |

**执行命令与验证：** Agent 全量 pytest 在允许 Windows 创建临时目录后通过（304 通过、1 跳过），随后补充边界回归并重新执行岗位测试。Java 岗位相关测试 19 项通过；首次 Java 全量运行遇到隔离测试库连接失败和演示数据缺失，旧测试样本也已补齐核验字段。前端 npm run type-check、npm run build 通过；验证构建生成的 dist 文件已恢复原工作树内容。Playwright 使用隔离接口样本验证在招标签、时间、来源诊断、历史批次提示和按当前条件重新提交任务，浏览器未记录页面错误。

**影响范围：** 新搜索批次与历史结果展示，无数据库迁移。没有启动 Docker 或替换在运行的服务。

**遗留问题：** 待记录最终真实查询对照，浏览器验证使用接口样本，尚未验证真实用户登录后完整的 Java 到 Agent 任务回调。

---

### [2026-11-06 21:24] 真实搜索对照与交付核对

**任务目标：** 记录同日搜索质量、调用量、覆盖缺口和发布状态。

**执行行为：** 在本机 Python 环境用真实搜索接口对照杭州、北京、上海两组查询；复核候选原始链接和官网职位 ID。补齐 Tavily 与官网列表共用的 12 次发现请求预算，详情链接最多 60 个，网页各阶段到时保留已完成的核验结果。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/agent/app/search/pipeline.py、app/search/sources/{web,greenhouse,detail}.py（均位于 project/agent） | 统一计入官网列表请求、阶段限时、详情投递链接证据校验 | 避免超预算或丢弃已完成核验 |
| 修改 | docs/功能资料/岗位推荐与投递_process.md | 追加最终真实对照 | 记录验收边界 |

**执行命令与验证：**

- 本机临时脚本 aijob-search-comparison.py 和 aijob-search-audit.py：旧版分别返回 10、1 个候选，本次公开复核没有足够证据确认这些旧候选仍可投递，不能据此判为下线。
- 新版 AI 应用开发实习返回北京 2、上海 1、杭州 0，耗时 22.1 秒。Java 后端校招返回北京 1、上海 1、杭州 0，耗时 18.2 秒。新结果共 5 条都出现在当次官网列表，随后读取原始详情均返回 200，职位 ID 与标题一致。未实际投递。两组均执行 6 次 Tavily、4 次百度、1 次腾讯和 1 次 Greenhouse 列表查询。
- 最终岗位 pytest 55 项通过。Java 排除三个依赖真实测试库或演示数据的测试类后 81 项通过。前端生产构建和浏览器样本交互通过。git diff --check 通过。

**影响范围：** 岗位搜索及新批次展示。保留现有服务进程，未启动 Docker。

**遗留问题：** 杭州仍无确认结果，旧版可投递总量不可确定，覆盖上线门槛尚未完全验证。没有完成真实账号的 Java 到 Agent 回调联调，也没有切换当前运行的后端服务。

---

### [2026-11-06 22:01] 恢复不限定域名的全网检索

**任务目标：** 按用户更正取消固定站点范围，同时保留在招核验。

**执行行为：** 移除 Tavily 的域名限制和详情阶段的固定域名表。候选按查询和域名轮流核验。详情请求使用独立客户端，不读取环境代理，连接前解析并拒绝非公网地址，连接固定到已校验的地址，TLS 仍校验原域名，重定向逐跳复核。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/agent/app/search/sources/web.py、detail.py（均位于 sources 目录） | 全网发现与任意公网来源的受限详情读取，显示发现域名数 | 纠正前版域名限制造成的覆盖不足 |
| 修改 | project/agent/app/search/security.py | 校验公网 IPv4 并交给连接层固定使用 | 避免随搜索链接访问内网 |
| 修改 | project/agent/tests/test_job_search_sources.py | 非原白名单网站入榜、固定地址和内网跳转回归 | 验证全网与防护同时生效 |

**执行命令与验证：** 已开始执行岗位回归与真实 Tavily 对照，结果待记录。

**影响范围：** Agent 网页来源。百度等官网仍保留为补充。

**遗留问题：** 待核对实网结果与运行进程状态。

---

### [2026-11-06 22:04] 验证跨站发现和入榜条件

**任务目标：** 确认全网查询不再局限于官网和固定站点，防止仅凭摘要入榜。

**执行行为：** 同日重新执行 AI 应用开发实习和 Java 后端校招真实查询。复查部分高校和招聘站的详情页，区分仅有职位正文、学校账号限制及可核验入口。为带职位 ID 参数的通用投递链接补充核验规则；保留同一职位与截止日期判断。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/agent/app/search/sources/detail.py | 识别和详情 ID 一致的同站申请路由，包括查询参数型岗位链接 | 降低跨站核验对单一路径样式的依赖 |
| 修改 | project/agent/tests/test_job_search_sources.py | 覆盖非固定域名、非原格式的申请链接、错误职位 ID 和内网重定向 | 确认开放发现不会降低入榜门槛 |

**执行命令与验证：** 岗位相关 pytest 57 项通过，git diff --check 通过。真实 Tavily 对照中 AI 实习发现 52 条不同链接，首轮来自 20 个域名；Java 校招发现 40 条不同链接，首轮来自 16 个域名。全网网页候选均未获得足够的在招证据，Java 查询中有一条明确失效。最终仍由百度官网补充 3 条实习、2 条校招。复查样例中浙大就业平台投递脚本要求学生身份，杭电样例跳转，字节样例未从公开响应确认投递入口。

**影响范围：** 新版 Agent 搜索策略。现有服务未重启，未启动 Docker。

**遗留问题：** 跨站发现已验证，实测未获得官网补充以外的可入榜岗位；测试样本已证明其他域名的岗位有充分证据时可以入榜。运行中的 Agent 需要重启加载变更。

---

### [2026-11-06 22:50] 按相关性直接返回全网岗位

**任务目标：** 按用户最新要求解除在招状态入榜门槛，增加符合搜索条件的结果。

**执行行为：** Tavily 继续不限域名，改用检索响应中的网页内容提取岗位，无须逐页实时核验后才能返回。继续要求具体岗位、可追溯文字和符合城市、求职类型、公司规模筛选。Java 不再丢弃缺少在招证据的相关岗位；页面分开显示已核验在招、原文显示已截止或下线、状态未核实。此前只读核对了部分招聘站公开列表，未接入额外来源。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/agent/app/search/sources/web.py、app/search/pipeline.py、app/search/models.py、app/schemas.py（后四段路径均在 project/agent） | 网页内容直接提取、在招状态独立于相关性、扩充 Agent 开发同义表达 | 避免未知状态将相关岗位全部挡住 |
| 修改 | project/backend/src/main/java/com/aijob/backend/job/JobSearchResultHandler.java | 状态未知和原文下线可入榜，过期或无证据的在招标记降为未知 | 保留信息而不误标 |
| 修改 | project/frontend/src/api/types.ts、views/job-search/JobSearchView.vue（后者位于 project/frontend/src） | 展示所有相关结果、状态标签、未知或已下线岗位改用查看原页 | 让用户区分岗位相关性与能否投递 |
| 修改 | project/agent/tests/test_job_search_sources.py、project/backend/src/test/java/com/aijob/backend/job/JobSearchResultHandlerTest.java | 验证未知、下线和已核验样例可以入榜，仍执行原硬筛选 | 防止旧门槛回归 |

**执行命令与验证：** 已发起 Agent 岗位回归、Java 岗位回归、前端类型检查与同条件真实 Tavily 对照，结果待记录。

**影响范围：** 搜索结果生成、历史批次展示与投递入口文案；无需数据库迁移。

**遗留问题：** 待核对实测岗位数量和页面表现。

---

### [2026-11-06 22:54] 完成相关岗位实网对照和页面验证

**任务目标：** 验证不以在招状态拦截后，真实搜索能返回更多相关岗位。

**执行行为：** 使用本机 Agent 搜索入口和真实 Tavily、模型，按杭州／北京／上海重跑 AI 应用开发实习和 Java 后端校招。用隔离接口样本在临时 Vite 进程检查三种状态、历史批次和重新搜索。临时进程结束后退出。

**文件变更：** 追加本过程记录。对照数据与截图保存于本会话产物目录，未写入业务数据。

**执行命令与验证：**

- Agent 岗位 pytest 57 项通过；Java 岗位相关 Maven 测试通过；前端 npm run type-check 和 git diff --check 通过。
- AI 应用开发实习从上轮严格模式 3 条增至 11 条，杭州 3、北京 5、上海 3。跨站 8 条，包含 6 条状态未核实、2 条网页原文显示截止或下线。官方在招 3 条。Java 后端校招从 2 条增至 3 条。
- Playwright 在 127.0.0.1:15173 使用样本验证已核验、未知和原文下线均显示，未知或下线岗位展示查看原页，历史批次可见，重新搜索发送当前筛选条件，无页面错误。最初 5173 未监听，改用隔离 Vite；后续修正测试的卡片文字定位，测试通过。

**影响范围：** 本机代码与测试。未启动 Docker，未重启业务后端或 Agent。

**遗留问题：** 实网对照执行于 Agent，浏览器使用接口样本，尚未用真实登录账户在当前业务服务走完整搜索。需要启动或重启服务后重新搜索生成新批次。

---
### [2026-11-07] 岗位搜索召回、核验与时效性系统性改造

**任务目标：** 在不推翻现有 LangGraph 搜索架构的前提下，修复第二阶段核验误杀、扩大检索覆盖、增加 Brave 补充引擎、建立动态预算与搜索质量指标，并保持 Java 资格判定与既有数据库兼容。

**执行行为：**

1. 核验四态：新增 `verification.py`，将结果统一为 `ACTIVE_CONFIRMED / ACTIVE_PROBABLE / UNKNOWN / CLOSED` 与 `verification_reason`；详情页 401/403/404/410、超时、非 HTML、无法解析一律判 `UNKNOWN`，只有页面原文明确下线或投递截止日期已过才判 `CLOSED`。`SourcePosting` 保留 `recruitment_status` 兼容字段，并新增核验证据、时间、来源 URL、HTTP 状态与历史记录。
2. 详情核验重新接入 Web 来源：核验结果只用于标记状态和证据，不淘汰 `UNKNOWN`；可通过 `AGENT_JOB_SEARCH_DETAIL_VERIFICATION` 关闭（默认开启）。
3. Search Provider 抽象：新增 `app/search/providers/`，Tavily 保持主搜索，Brave 通过 `BRAVE_SEARCH_API_KEY` 启用；两者并行、统一 `SearchHit`、按 canonical URL 去重，并记录每条结果的 `searchProviders`。Brave 未配置时自动降级为 Tavily；429/5xx 重试一次，限流或失败不阻断另一引擎。
4. 关键词扩展与覆盖矩阵：新增 `expansion.py`，按技术族生成受控同义词（AI Agent、RAG、大模型、Java、前端、测试、算法、数据），按城市轮转生成查询；`CoverageMatrix` 记录城市 × 岗位类别的查询与来源完成情况，补搜优先未覆盖维度。
5. 动态预算：新增 `budget.py` 与集中配置（每轮查询/页面/详情上限、总 API 调用、最大耗时、最大轮数、目标与最小结果数、缓存 TTL、重试次数、时效天数）。查询缓存跨轮复用；当覆盖完成且本轮 Web 无新增岗位时停止补搜。
6. 企业 ATS 直连：新增 Lever 适配器与 `company_registry.py`（配置驱动），复用 Greenhouse；企业最后采集时间写入 `job_source.last_collected_at`（V7 迁移）。
7. 相关性修复：官方来源原先要求关键词所有词同时命中，百度 32 条被全部过滤；改为“标题必须是研发岗 + 扩展变体任一命中”。新增非研发岗位排除与 `internal` 不误判为实习的词边界修复。
8. 城市修复：补齐东莞、大连、青岛等城市码；纯英文境外城市与明确的其他中国城市在选择国内城市时淘汰，城市为空时保留并标记 `CITY_UNKNOWN`。
9. 多层去重与排序：新增 `dedupe.py`，按 URL、平台岗位 ID、企业+标题+城市指纹、同城跨站 JD 相似度（≥0.9）合并；排序综合相关性、时效、完整度、核验状态、语义分与来源质量，`CLOSED` 统一排在正常推荐之后。
10. 搜索漏斗与指标：新增 `metrics.py` 与 `evaluation.py`，输出 Search Results、Unique URLs、Unique Postings、Job Detail Pages、Extraction、四态核验、硬筛选与最终推荐，以及提取成功率、重复率、陈旧率、UNKNOWN 率、官方核验率、Freshness Lag、城市/岗位类别覆盖与来源多样性。未提供人工标注集时 Recall@K/NDCG@10 标记为 `NOT_MEASURED`。
11. 提示词规范化：Web 岗位提取提示词迁移到 `app/skills/prompts/job_search_extract.md`，并补充排除研究助理/销售/产品等非研发岗位；system prompt 显式包含 JSON 字样，修复 DeepSeek JSON mode 400。
12. Java 端：四态与 `verification_reason` 透传并写入结果快照；城市/求职类型缺失不再直接丢弃，改为标记未知并回退展示类型；`CLOSED` 排到最后；百度/腾讯/Greenhouse/Lever 官方列表才允许 `ACTIVE_CONFIRMED`，第三方详情页最多 `ACTIVE_PROBABLE`。
13. 前端：增加“已确认在招 / 待确认在招”标签、核验原因与搜索引擎展示，来源诊断增加待确认数量。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | project/agent/app/search/verification.py | 四态、reason 目录、证据记录与汇总 | 统一核验语义，避免 UNKNOWN 被当 CLOSED |
| 新增 | project/agent/app/search/providers/{__init__,base,tavily,brave,registry}.py | Search Provider 抽象、Tavily/Brave 适配与降级 | 增加独立补充引擎并保留 Tavily |
| 新增 | project/agent/app/search/expansion.py | 同义词扩展、查询计划、覆盖矩阵 | 扩大关键词与城市覆盖 |
| 新增 | project/agent/app/search/budget.py | 动态预算、查询缓存、阶段耗时 | 以可配置预算替代固定次数 |
| 新增 | project/agent/app/search/dedupe.py | 多层去重与排序评分 | 跨平台同岗去重，避免仅按 URL |
| 新增 | project/agent/app/search/metrics.py、evaluation.py | 搜索漏斗、指标与人工标注评测 | 可追踪各阶段问题，不虚构 Recall |
| 新增 | project/agent/app/search/relevance.py | 官方来源研发岗相关性与非研发排除 | 修复“所有词同时命中”造成的低召回 |
| 新增 | project/agent/app/search/sources/lever.py | Lever 公开 board 适配器 | 扩展企业 ATS 直连 |
| 新增 | project/agent/app/search/sources/company_registry.py | 企业来源注册与采集记录 | 配置驱动注册，避免重复建表 |
| 新增 | project/agent/app/skills/prompts/job_search_extract.md | 网页岗位提取提示词 | 遵循提示词 Markdown 化规范并修复 400 |
| 新增 | project/backend/src/main/resources/db/migration/V7__add_job_source_collection_time.sql | job_source 增加 last_collected_at | 记录企业最后采集时间，可空兼容 |
| 修改 | project/agent/app/search/models.py | 四态字段、搜索来源、城市表扩展、实习词边界 | 数据契约与误判修复 |
| 修改 | project/agent/app/search/pipeline.py | 预算、覆盖、去重、指标、排序、相关性接入 | 串联各阶段改造 |
| 修改 | project/agent/app/search/sources/{web,detail,baidu,tencent,greenhouse,registry}.py（均位于 sources） | 多引擎检索、详情核验、四态证据、Lever 注册 | 落地 P0/P1 能力 |
| 修改 | project/agent/app/search/sources/base.py | 缺失字段不淘汰、境外/其他城市判定 | 减少误杀同时避免境外误召回 |
| 修改 | project/agent/app/config.py | Brave Key 与集中预算参数 | 配置化 |
| 修改 | project/agent/app/schemas.py | 四态字段、PROBABLE、metrics、动态计数 | API 契约透传 |
| 修改 | project/agent/scripts/verify_job_search.py、run_local_e2e.ps1 | 命令行参数、JSON 输出、新测试清单 | 可重复真实验证 |
| 修改 | project/backend/src/main/java/com/aijob/backend/job/JobSearchResultHandler.java、JobSearchService.java、JobSearchMapper.java | 四态透传、缺失字段保留、CLOSED 末尾、Lever 来源、采集时间 | Java 端契约与筛选 |
| 修改 | project/backend/.../JobSearchResultHandlerTest.java | 四态、403、缺失城市/类型用例 | 回归覆盖 |
| 修改 | project/frontend/src/api/types.ts、views/job-search/JobSearchView.vue | 已确认/待确认标签、核验原因、搜索引擎 | 展示区分 |
| 修改 | .env.example | 增加 BRAVE_SEARCH_API_KEY | 配置说明 |
| 新增 | project/agent/tests/test_{search_providers,verification,search_planning,job_search_dedupe,job_search_lever,search_metrics,job_search_verification_flow}.py | 新增可重复测试 | 覆盖 P0/P1 能力 |

**执行命令与验证：**

- `conda run -n ai-job python -m pytest tests/test_job_search*.py tests/test_search_*.py tests/test_verification.py -q`：99 项通过；随后补充相关性与城市用例，单文件回归通过。
- `conda run -n ai-job python -m pytest tests -q`：全量通过（约 349 通过、1 跳过，执行于本轮中段；随后仅改动搜索模块并已单独回归相关文件）。
- `mvn -q -DskipTests compile`：通过。
- `mvn -q -Dtest=JobSearchResultHandlerTest test`：3 项通过（四态排序、UNKNOWN/PROBABLE 保留、缺失城市/类型保留）。
- `npm run type-check`：通过。
- 真实环境（Tavily + DeepSeek，系统环境变量已配置；Brave 未配置，验证降级）：
  - 场景 A：AI Agent 开发实习，广州/深圳/东莞。Tavily 8 次查询、原始 80 条、Unique URLs 66、Web 分析 20 页、提取 0、最终 2 条（百度官方，均 ACTIVE_CONFIRMED），耗时 13.3 秒，API 调用 8 次（不含官方来源请求）。
  - 场景 B：Java 后端校招，北京/上海/杭州。Tavily 5 次查询、原始 50 条、Unique URLs 36、Web 分析 20 页、提取 0、最终 2 条（百度官方，上海/北京，均 ACTIVE_CONFIRMED，大连岗位已正确过滤），耗时 11.6 秒，API 调用 5 次。
  - 结果文件：project/agent/data/verify_ai_agent_final.json、project/agent/data/verify_java_final.json。
- 未执行：Brave 真实调用（无 API Key）；Java job 包其余测试与 `npm run build`（工具调用上限中断）；Recall@K/NDCG（无人工标注集）。

**改造前基线说明：** 既有记录（2026-11-06）显示 AI 应用开发实习 Tavily 52 条链接/Java 校招 40 条链接，跨站核验无候选通过，最终 5 条来自百度。该基线不是本次在同条件下执行，条件与代码版本均不同，因此不做数值差值或“提升百分比”结论。

**影响范围：** 岗位搜索编排、来源适配、核验状态、结果契约与展示；Java 端结果快照新增字段；新增 V7 可空列迁移，不删除或改写既有数据；未改动其他业务模块。

**遗留问题：**

1. Web 全网检索在真实条件下仍未提取到岗位（20 页/轮），召回主要依赖百度官方列表；需进一步评估 Tavily 页面质量、提取阈值与页面预算。
2. Brave 未配置 API Key，真实并行检索未联调；降级与并行逻辑已由 Mock 测试覆盖。
3. Lever 默认未启用，需配置 `AGENT_JOB_SEARCH_LEVER_BOARDS` 后使用；真实 Lever board 未联调。
4. Recall@K/NDCG@10 需要人工标注集；当前固定为 NOT_MEASURED。
5. Java job 包其余单元测试与前端生产构建未在最终版本执行；前端 type-check 已通过。
6. 增量采集目前是“全量拉取 + job ID/指纹去重 + job_source.last_collected_at 记录”，尚未实现跨批次按更新时间跳过未变化岗位。

---
### [2026-11-07] 定位“后端开发搜索仅 1 条”的丢失阶段并修复误杀与补搜

**任务目标：** 按用户复现条件（后端开发、杭州/北京/上海、实习、公司规模不限且包含未知、最多 30 条）定位候选在哪一步丢失，修复常见误杀，并让“有链接但 0 提取”触发补搜；Brave 不作为本轮修复手段。

**执行行为：**

1. 逐阶段诊断：在 Web 来源新增 `extractionDiagnostics`，分别记录模型返回行数、接受数、规则拒绝原因（TITLE_MISSING/TITLE_MISMATCH/COMPANY_MISSING/JD_QUOTE_MISMATCH/JD_TOO_SHORT/MODEL_NO_ROW/DUPLICATE_PAGE_ROW）、公司缺失、类型未知、JD 模糊命中；城市/类型/规模淘汰由 `partition_postings` 单独计数。指标写入 `metrics.failures`（`extract:*`、`filter:*`）。
2. 修复标题误杀：不再要求“提取标题必须完整包含在搜索标题中”，改为 compact 包含关系或 2-gram 重合度 ≥0.6，允许搜索标题截断、站点后缀与括号编号。
3. 修复公司误杀：公司名不在正文时不再直接丢弃，使用页面域名兜底并标记 `COMPANY_FROM_DOMAIN`；不编造公司。
4. 修复 JD 引文误杀：引文允许空白/标点差异（compact 命中），排版差异用最长公共片段 ≥65% 确认并标记 `JD_QUOTE_FUZZY`；仍要求至少 35 字符来自页面证据，完全无证据继续拒绝。
5. 求职类型未知不再直接丢弃：保留岗位并标记 `CANDIDATE_TYPE_UNKNOWN`，交给 Java 端硬筛选；城市缺失同样保留并标记。
6. 覆盖判定修正：`CoverageMatrix` 增加 `analyzed/extracted/rejected`；只有“实际分析并产出岗位”或“同义词尝试达上限”才算完成。有链接但 0 提取仍算缺口。
7. 补搜修正：新增 `build_gap_queries`，对缺口城市/维度使用“后端研发、服务端开发、Java 后端”等同义词生成新查询，跳过已执行查询；`executed_queries` 显式回传并跨轮持久化，实测 `cacheHits=0`，不再重复发送同一查询。
8. 轮次修正：预算轮次改由 pipeline 统一 `begin_round()`，修复直接调用来源时查询预算为 0、以及补搜无限循环的问题。
9. 指标口径修正：`cityCoverage` 改为按最终岗位城市统计；查询维度覆盖单独放入 `queryCoverage`，避免把“为杭州查询”误报成“杭州有岗位”。
10. `expand_keyword` 增加后端/服务端技术族，使“后端开发”能扩展为后端研发、服务端开发、Java 后端、Python 后端、后端工程师。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/agent/app/search/sources/web.py | 提取诊断、标题/公司/JD/类型分级判定、逐维度覆盖标记 | 修复误杀并可追踪丢失原因 |
| 修改 | project/agent/app/search/sources/base.py | 新增 `partition_postings` 与城市/类型/规模淘汰计数 | 分阶段诊断 |
| 修改 | project/agent/app/search/expansion.py | 后端同义词族、覆盖 analyzed/extracted、`build_gap_queries`、`mark_exhausted` | 缺口补搜与新完成口径 |
| 修改 | project/agent/app/search/pipeline.py | 轮次统一初始化、executed 持久化、gap 补搜、按原因汇总指标 | 修复补搜与指标 |
| 修改 | project/agent/app/search/metrics.py | 城市/岗位覆盖按最终岗位统计，新增 queryCoverage | 指标准确性 |
| 修改 | project/agent/scripts/verify_job_search.py | 摘要输出最多 30 条 | 便于完整抽查 |
| 新增 | project/agent/tests/test_job_search_extraction_recovery.py | 标题截断、公司缺失、JD 排版差异、类型未知、城市/类型/规模排除、补搜触发、预算耗尽 | 固定候选样本验收 |
| 修改 | project/agent/tests/test_search_planning.py、test_job_search.py | 覆盖新完成口径、后端同义词、补搜轮次 | 回归口径 |

**执行命令与验证：**

- `conda run -n ai-job python -m pytest tests/test_job_search.py tests/test_job_search_sources.py tests/test_job_search_extraction_recovery.py tests/test_search_planning.py tests/test_search_metrics.py -q`：90 项通过。
- 完整岗位测试集（10 个文件）：118 项通过。
- 真实复跑（Tavily + DeepSeek，Brave 未启用；条件：后端开发、杭州/北京/上海、实习、规模不限且包含未知、最多 30）：
  - 轮次 3，查询 27，页面 60，API 调用 34（含详情核验），缓存命中 0，耗时 62.9 秒。
  - Tavily 原始结果 510 条，Unique URLs 73，Unique Postings 15，详情核验页 14。
  - 提取：Web 进入 60 页，模型返回 11 行，接受 8 条；`MODEL_NO_ROW` 49 页、`TITLE_MISMATCH` 3 条、规则拒绝合计 3 条；城市/类型/规模硬筛：`rejectedCity=0`、`rejectedType=0`、`rejectedSize=0`，`unknownCity=8`。
  - 四态：ACTIVE_CONFIRMED 8（全部百度官方）、UNKNOWN 6、CLOSED 1；原因分布 `DETAIL_NOT_FOUND 3 / HTTP_403 2 / PAGE_ACCESS_FAILED 1 / EXPIRED 1`。
  - 最终 15 条：百度 8 条，Web 7 条；城市分布北京 6、上海 2、城市未知 7。
  - 新增 Web 岗位抽查：
    1. 后端开发实习生-数据平台｜jobs.bytedance.com｜城市未确认｜UNKNOWN / DETAIL_NOT_FOUND｜https://jobs.bytedance.com/campus/m/position/detail/7605811463304005941
    2. Java-校招/实习（J15117）｜aeo.uibe.edu.cn｜城市未确认｜UNKNOWN / DETAIL_NOT_FOUND｜https://aeo.uibe.edu.cn/front/zwxx.jspa?xqzwId=2099763611223257089
    3. Java/Python后端实习生｜wap.shixiseng.com｜城市未确认｜UNKNOWN / HTTP_403｜https://wap.shixiseng.com/intern/inn_mnq3k9ppzp7p
    4. Java研发实习生｜cn.linkedin.com｜城市未确认｜UNKNOWN / PAGE_ACCESS_FAILED｜https://cn.linkedin.com/jobs/view/java研发实习生-4485055028
    5. Java开发实习生（服务端）｜mnowpick.nowcoder.com｜城市未确认｜UNKNOWN / DETAIL_NOT_FOUND｜https://mnowpick.nowcoder.com/m/detail/index?jobId=180060
    6. 后端研发实习生（资金方向）-国际支付｜shixiseng.com｜城市未确认｜UNKNOWN / HTTP_403｜https://www.shixiseng.com/intern/inn_cfg3gbqg9waj
    7. 后端开发实习生｜shushuqiuzhi.com｜城市未确认｜CLOSED / EXPIRED｜https://www.shushuqiuzhi.com/position/185036
  - 类型证据：Web 来源 `rejectedType=0`、`unknownType=0`，8 条均通过“实习”硬筛；城市 8 条均未确认，保留并标记，未编造城市。
- 结果文件：project/agent/data/verify_backend_dev_complete.json。

**与用户提供的旧复现对比（同条件）：**

| 阶段 | 旧复现 | 本次 |
| --- | --- | --- |
| 搜索请求/原始结果 | Tavily 3 次 / 30 条 | 27 查询 / 510 条累计 |
| URL 去重 | 27 | 73 |
| 进入提取页面 | 20 | 60 |
| 模型未提取 | 未区分 | 49 页 MODEL_NO_ROW |
| 规则拒绝 | 未区分 | 3 条（TITLE_MISMATCH 3） |
| 提取成功 | 0 | Web 8 |
| 补搜 | 未触发 | 触发 2 次，共 27 查询 |
| 最终岗位 | 1（百度） | 15（百度 8 + Web 7） |
| 耗时/调用 | 未记录 | 62.9 秒 / 34 次 |

**阻塞阶段说明：** 主要阻塞仍是 `MODEL_NO_ROW`（49/60 页）。这些页面多为招聘列表、新闻或摘要不含具体岗位证据，不属于规则误杀；本轮没有为了凑数放宽真实性。Web 新增岗位城市/类型可用性有限，均已保留为 UNKNOWN 并标注，未宣称“覆盖已完全改善”。

**遗留问题：** 1）详情页正文补抓尚未实现，是进一步降低 MODEL_NO_ROW 的下一步；2）Web 岗位城市确认率低；3）Brave 未启用、Lever 未联调；4）Java job 包其余测试与前端 build 未在最终版本执行；5）Recall/NDCG 仍无标注集。

---

### [2026-11-07 10:30] 复核岗位提取改动与测试准备

**任务目标：** 独立核对改动质量及开始联调的条件。

**执行行为：** 阅读搜索诊断、补搜、Agent 提取、Java 持久化和前端展示逻辑；核对 verify_backend_dev_complete.json 的最终岗位与城市分布。用固定输入直接验证模糊 JD 引文和未取证求职类型的行为。

**文件变更：** 仅追加本过程记录；前端构建产物写入临时目录。

**执行命令与验证：** Agent 岗位相关 10 个文件共 118 项通过；Java 岗位相关 7 个测试类共 20 项通过；前端 npm run type-check 和 npm run build（临时 outDir）通过。模糊引文探针复现模型添加的学历要求仍被保留；求职类型探针复现正文没有实习证据时模型给出的实习类型仍被接受。未运行真实 Java 到 Agent 回调联调或 V7 迁移验收。

**影响范围：** 本机审查和测试；未启动 Docker 或替换业务服务。

**遗留问题：** 改造样本的 7 条最终 Web 岗位均未确认城市；修正证据保留和未知类型展示后再做结果质量验收。隔离联调需验证 V7 迁移和结果持久化。

---
### [2026-11-07] 修复岗位卡片字段提取与“未明确/域名”展示

**任务目标：** 解决卡片显示“xxx.com / 未明确 / 实习校招”，以及薪资、公司规模、经验、学历未从 JD 和详情页提取的问题；只在原文或结构化来源可确认时填写，不编造。

**执行行为：**

1. 扩展网页提取提示词：新增 `salaryText`、`companySizeText`、`experienceText`、`educationText`、`publishedDate`，要求逐字引用原文，缺失填 null；`cityName` 优先取“工作地点/工作城市/base/办公地点”。
2. 新增通用 `text_extract.py`：从 JD/页面原文解析城市、薪资、公司规模、经验、学历、发布时间；找不到返回空值。
3. Web 提取：标题/公司继续使用页面证据；城市、薪资、规模、经验、学历、发布时间支持正文兜底，并标记 `CITY_FROM_TEXT`、`SALARY_FROM_TEXT` 等；详情页可访问时用 `_enrich_from_page` 仅补齐缺失字段。
4. 官方来源接入：百度、腾讯、Greenhouse、Lever 均使用通用解析；百度优先使用列表结构化 `education`/`workYears`，为空时再从 `serviceCondition` 原文解析。
5. 前端：城市无 `cityCode` 时回退显示 `cityName`；薪资/经验/学历/规模缺失统一显示“来源未提供”，不再显示“未明确”；补齐 `JobSearchItemVO.cityName` 类型。
6. 保留真实性边界：百度列表接口没有薪资和公司规模字段，Web 摘要/详情页 403/404 时不补造；只在原文或结构化字段存在时填写。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | project/agent/app/search/text_extract.py | JD 字段解析（城市/薪资/规模/经验/学历/发布时间） | 官方与网页来源共用 |
| 新增 | project/agent/tests/test_job_search_field_extraction.py、test_job_search_official_fields.py | 字段提取、无证据不编造、结构化字段优先 | 固定样本验证 |
| 修改 | project/agent/app/skills/prompts/job_search_extract.md | 输出结构增加字段与原文约束 | 提高提取完整度 |
| 修改 | project/agent/app/search/sources/web.py | 正文兜底、详情补全、字段提取 | Web 岗位字段 |
| 修改 | project/agent/app/search/sources/baidu.py | 结构化 education/workYears 优先，JD 兜底 | 百度官方岗位字段 |
| 修改 | project/agent/app/search/sources/tencent.py、greenhouse.py、lever.py | 接入通用 JD 字段解析 | 官方来源字段 |
| 修改 | project/frontend/src/views/job-search/JobSearchView.vue | 城市回退、“来源未提供”文案 | 避免“未明确/域名”误解 |
| 修改 | project/frontend/src/api/types.ts | `JobSearchItemVO.cityName` | 类型检查 |

**执行命令与验证：**

- `conda run -n ai-job python -m pytest tests/test_job_search_field_extraction.py tests/test_job_search_official_fields.py -q`：10 项通过（含无证据不编造、百度结构化字段优先）。
- 完整岗位测试集（12 个文件）：128 项通过。
- `npx vue-tsc --noEmit`：通过。
- 真实复跑（同条件：后端开发、杭州/北京/上海、实习、规模不限且包含未知、最多 30；Tavily 主搜索，Brave 未启用）：12 条（百度 8 + Web 4）。
  - 城市：12/12 有值（北京 6、上海 2、杭州 4）。
  - 公司：Web 4/4 已提取为公司名（如字节跳动、上海艾拉比智能科技有限公司），不再显示域名。
  - 学历：9/12（百度 6/8、Web 3/4）。
  - 薪资、经验、规模：0/12。证据：百度列表 JSON 的 `education`/`workYears` 为空，且没有 salary/companySize 字段；Web 4 条中 3 条 CLOSED、1 条 UNKNOWN，摘要或详情页（403/404）不含这些字段。
- 说明：真实复跑执行于“百度结构化字段优先”修改之前；修改后的固定测试已验证结构化字段优先，且实测这些字段为空，因此不改变真实结果，但未再次真实复跑。

**影响范围：** 岗位提取、官方来源适配、前端岗位卡片展示；未改数据库结构。

**遗留问题：** 1）薪资/公司规模需要额外企业档案或详情页数据源，当前官方列表与可访问摘要不提供，不能编造；2）经验在实习 JD 中多数未要求，显示“来源未提供”是事实；3）Brave 未启用、Lever 未联调；4）公司档案抓取（官网/招聘平台公司详情）尚未实现。

---
### [2026-11-07] 补招聘平台详情页抓取与结构化字段解析

**任务目标：** 用户指出此前只用了 Tavily 摘要和百度官方列表，没有真正抓取招聘平台详情页，导致页面中明明存在的薪资、公司规模、经验、学历没有进入结果。本轮补齐详情页抓取并按岗位主区块解析字段。

**执行行为：**

1. `detail.py` 在 200 HTML 详情页上即使没有 JobPosting JSON-LD，也返回页面正文与 `fields`；状态仍按四态规则判定，不因可访问就伪造在招。
2. Web 提取阶段新增预抓正文：对候选页在独立预算内抓取详情页（`max_page_fetch` 默认 12），预抓与详情核验预算分开，避免预抓耗尽核验额度。
3. 新增结构化解析：优先读取 `class="salary"`、`job-properties`（城市/实习/学历）、`人数规模`、`公司信息`，再回退到通用正则；避免整页推荐岗位污染。
4. 支持日薪原文（如 `100-150元/天`），即使无法换算月薪也保留 `salaryText` 展示。
5. Web 详情页补全时同时补城市、公司名、薪资、规模、学历、经验、发布时间，且只填空缺、不覆盖已有值。
6. 详情核验范围改为所有候选（含摘要判 CLOSED 的候选），以详情页证据重新判定状态并补字段；避免摘要误判导致跳过详情页。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/agent/app/search/sources/detail.py | 200 HTML 返回正文与结构化 fields | 详情页字段来源 |
| 修改 | project/agent/app/search/text_extract.py | 新增 `extract_listing_fields` 岗位主区块解析 | 避免整页正则误取 |
| 修改 | project/agent/app/search/sources/web.py | 预抓正文、独立预算、结构化优先、全候选核验 | 让页面字段进入结果 |
| 修改 | project/agent/app/search/budget.py、config.py | 新增 `max_page_fetch` | 预抓与核验预算分离 |
| 新增 | project/agent/tests/test_job_search_detail_fetch.py | Tavily 摘要过短 → 抓详情页 → 字段补全 | 端到端固定样本 |
| 修改 | project/frontend/src/api/types.ts、JobSearchView.vue | cityName 回退、缺失显示“来源未提供” | 展示准确性 |

**执行命令与验证：**

- `conda run -n ai-job python -m pytest tests/test_job_search_detail_fetch.py -q`：1 项通过；固定猎聘式页面可抽出 `100-150元/天`、杭州 330100、华创证券、规模 2000-5000 人、本科。
- `conda run -n ai-job python -m pytest tests/test_job_search_detail_fetch.py tests/test_job_search_verification_flow.py -q`：5 项通过。
- 真实复跑（同条件）：`pagesFetched=36`、`detailChecks=6`、`jobDetailPages=12`，说明确实发起了详情页抓取；最终 14 条（百度 8 + Web 6），城市 14/14、学历 12/14，薪资/经验/规模仍为 0。
- 真实 Web 候选为实习僧、LinkedIn、牛客系、shushuqiuzhi、offerseek 等，页面多为 403/登录墙/JS 渲染或已被判定关闭；用户截图中的猎聘岗位本轮未进入候选集。
- 直接实网复验猎聘已知 URL 时发生 TLS 握手超时，未能完成；结构化解析器仅在固定样本验证，未宣称实网验证完成。

**影响范围：** 详情页抓取、字段解析与展示；增加外部请求量，受独立预算限制。

**遗留问题：** 1）通用整页正则不可靠（实网曾把城市识别为郑州、薪资识别为 1000-2000），必须按平台结构化区块或平台 API/JSON 解析；2）招聘平台 JS 渲染与登录墙需要浏览器渲染或平台接口；3）当前搜索候选集未包含截图中的猎聘岗位，需扩展查询/来源；4）薪资/规模仍不能保证所有平台可得，不得编造。

---

### [2026-11-07 12:40] 官网优先修复：证据与合并基础

**任务目标：** 官网主来源、第三方补缺且保留证据。

**执行行为：** 新增字段组证据；薪资等关联字段整体补齐；跨域合并增加公司、城市和招聘类型限制；官网状态不被第三方提升；详情成功分支保留结构化字段。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | project/agent/app/search/evidence.py | 字段组与来源证据 | 防止混合薪资与来源丢失 |
| 修改 | project/agent/app/search/models.py | field_sources | 来源传递 |
| 修改 | project/agent/app/search/dedupe.py | 合并限制与字段组补全 | 防止误合并与官网状态覆盖 |
| 修改 | project/agent/app/search/sources/detail.py | 成功分支保留 fields | 修复结构化字段丢失 |

**执行命令与验证：** 使用 Python 定点修改上述文件；尚未运行测试，后续分阶段回归。

**影响范围：** Agent 搜索合并与详情解析；保留任务开始时既有未提交改动。

**遗留问题：** 官网发现、主体解析、渲染及跨模块传递待完成。

---

### [2026-11-07 12:42] 官网发现、岗位主体解析与渲染接入

**任务目标：** 官网取数优先并支持 JS 页面。

**执行行为：** 新增官网来源与企业入口链接核验；新增主体 HTML/JSON-LD 提取；串行执行官方阶段与 Web 阶段；详情缓存跨轮共享，按实际抓取计数；增加受限 Chromium 渲染，HTTP 请求通过公网解析后固定 IP 的客户端返回浏览器，禁用 Service Worker/WebSocket；模型模糊引文不再保留无证据内容。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | project/agent/app/search/page_body.py | 主体和结构化数据解析 | 减少推荐区污染 |
| 新增 | project/agent/app/search/sources/company_official.py | 官网发现与归属核验 | 官方来源优先 |
| 新增 | project/agent/app/search/sources/render.py | 匿名受限渲染 | 读取 JS 岗位 |
| 修改 | project/agent/app/search/sources/web.py、detail.py | 字段消费、缓存与渲染 | 修复提取链路 |
| 修改 | project/agent/app/search/sources/registry.py、pipeline.py、budget.py | 来源、跨轮状态与截止时间 | 统一取数预算 |
| 修改 | requirements.txt | Playwright 依赖 | 提供 Chromium 运行能力 |

**执行命令与验证：** 文件修改完成；本阶段尚未执行测试。当前主 Python 有 httpx，ai-job 环境探针未找到 httpx；后续使用已安装依赖的解释器验证。

**影响范围：** Agent 搜索、外部页面读取和依赖；未修改本地业务数据。

**遗留问题：** Java/前端证据传递、回归和真实页面验证待完成。

---

### [2026-11-07，Asia/Shanghai] Java 与卡片字段来源传递及首轮验证

**任务目标：** 将官网身份、规模原文和字段证据保存并展示。

**执行行为：** 修改 Java 来源归一化、快照、返回值与合并，前端增加可点击字段依据和规模原文。运行首轮专项验证。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/backend/src/main/java/com/aijob/backend/job/JobSearchResultHandler.java | 官网来源、证据归一化/快照、原子薪资合并 | 保留来源与关联字段 |
| 修改 | project/backend/src/main/java/com/aijob/backend/job/JobSearchService.java | 返回规模原文与证据 | API 传递 |
| 修改 | project/frontend/src/api/types.ts、src/views/job-search/JobSearchView.vue | 新类型与字段证据弹层 | 用户可核对原文 |

**执行命令与验证：**

- python -m pip install playwright：失败，当前网络/索引未返回可安装版本，尚未安装浏览器。
- 主 Python 直接 pytest：缺 pytest_asyncio；加入既有 .testdeps 后运行 4 个岗位测试文件，14 通过、2 失败（详情核验样本提取为空），正在排查。
- npm run type-check：通过。
- Maven 三个搜索专项类：已启动，待结果。
- git diff --check：通过。

**影响范围：** Java 搜索快照与卡片；不需要数据库迁移。

**遗留问题：** 修复回归失败、补新增功能测试、构建及真实页面验收。

---

### [2026-11-07，Asia/Shanghai] 回归修正与浏览器依赖安装

**任务目标：** 修复回归暴露问题并验证构建。

**执行行为：** 短详情页保留原摘要，模糊引文映射回真实原文；抓取计数改为搜索级共享；给官方首轮和 Web 留出页面配额；官网缺字段生成公司岗位定向补搜，第三方候选再查官网。补充字段来源 Java 快照测试和 Agent 官方优先测试。

**文件变更：** 修改 web.py、pipeline.py、company_official.py、text_extract.py、requirements.txt；新增 tests/test_job_search_official_priority.py；修改 test_job_search_extraction_recovery.py 与 Java JobSearchResultHandlerTest.java。旧模糊引用用例的实际原文不足 35 字符，改为验证拒绝短原文，避免靠模型增写凑长度。

**执行命令与验证：**

- 通过显式 PyPI 索引安装 Playwright 1.64.1 到项目现有 .testdeps 成功；Chromium 下载进行中，中途 ECONNRESET 后安装器重试。
- Agent 搜索专项首次 135 通过、2 失败；修正后 136 通过、1 失败（上述短原文用例），已修正预期，待复跑。
- 新增 Java 测试首次因 Harness 未模拟新来源 ID 报错，已补模拟，待复跑；原有 8 项通过。
- 前端普通构建因 esbuild 子进程权限失败，允许子进程后临时输出 .verify-dist 构建成功；存在既有大包体积提示。

**影响范围：** 搜索取数与字段证据；未改数据库或业务记录。

**遗留问题：** 最终回归、浏览器运行、真实页面和 UI 验证待完成。

---

### [2026-11-07，Asia/Shanghai] 浏览器运行验证与来源默认值修复

**任务目标：** 验证真实 Chromium 执行和来源贯通。

**执行行为：** Chromium 安装成功；最小启动探针成功。渲染回归发现 await 在生成器表达式中引发 TypeError，已提取为先读取正文后同步判断；微小剩余预算在启动浏览器前直接超时，避免进程清理挂起。补齐 Java 来源白名单、Java/Agent 默认来源配置。

**文件变更：** 新增 tests/test_job_search_render.py；修改 render.py、page_body.py、detail.py、config.py、registry.py、JobSearchService.java、application.yml；新增来源/快照测试维护在 JobSearchResultHandlerTest.java。

**执行命令与验证：** Agent 137 项搜索相关测试通过；Java 3 个专项类共 9 项通过（含新增官网身份和字段证据快照）；Chromium JS 字段和私网请求拦截通过、跳转拦截/依赖缺失/超时通过，登录墙用例因样本响应缺 UTF-8 charset 失败，已修正真实响应编码，待最终复跑。最初渲染组因极小超时测试子进程清理未返回而中断，未将中断运行报为通过。

**影响范围：** 搜索默认来源与无登录渲染。

**遗留问题：** 最终渲染全组、真实页面及页面交互验收。

---

### [2026-11-07，Asia/Shanghai] 搜索回归、真实页面核对与超时保留

**任务目标：** 保留部分结果并核对实网取数字段。

**执行行为：** 原文城市/规模/时间识别收紧为明确标签；新增同招聘主体的规模补全；超时来源保留已完成解析批次。新增可信企业域名配置入口。网页身份不匹配时不向旧岗位补字段。

**文件变更：** 修改 Agent web.py、pipeline.py、dedupe.py、company_registry.py、config.py；新增 data/verify_official_priority_20261107.json（仅公开岗位字段）；更新浏览器测试样本编码。

**执行命令与验证：** 142 项岗位搜索相关测试通过（含 5 个 Chromium 测试）。匿名真实页面探针调用 fetch_detail 和 render_detail：猎聘 /job/1985680015.shtml HTTP 200，可解析日薪 200-200元/天、深圳、学历/经验/发布时间；该 URL 当前内容与旧固定样本中的华创 AI 实习岗位不同，不把旧样本值当作实网结果。华创 hotjob 列表与字节指定链接 HTTP 200，但静态和渲染结果均没有可确认岗位字段，状态 UNKNOWN/DETAIL_NOT_FOUND。未运行外部模型分析或发送真实画像。前端临时服务 127.0.0.1:5187 已启动，虚构数据 UI 验收进行中。

**影响范围：** 公网页面读取、证据合并和超时降级；未写业务库。

**遗留问题：** 最新改动复跑与 UI 验收；尚无真实 Java—Agent HTTP 回调全链路验证。

---

### [2026-11-07，Asia/Shanghai] 前端交互验收与补充回归

**任务目标：** 验证用户能看到公司规模原文并查看字段证据。

**执行行为：** 按 frontend-testing-debugging / Playwright 技能验证。Browser plugin not available，使用已安装 Python Playwright。127.0.0.1:5187 的 /jobs?tab=search&searchId=fixture 注入虚构接口数据，点击薪资依据并确认第三方证据链接可见，保存截图并视觉检查。最初接口样本使用 code=0 与项目实际 code=200 不符导致页面未出现，改为真实响应信封后通过。

**文件变更：** README.md 增加依赖安装、官网域名配置、预算与旧记录兼容说明；tests/test_job_search_official_priority.py 增加 ATS 租户关系和同主体规模补全测试；tests/test_job_search_sources.py 的城市/公司规模样本增加明确标签，避免把未标记数字范围当作企业规模。

**执行命令与验证：** UI URL 和标题符合目标，页面内容正常、无框架错误层，pageerror=[]；规模原文 2000-5000人 可见，薪资依据点击后第三方链接可见。截图在本聊天可视化目录 official-priority-ui.png，未放入源码。最近 Agent 回归 141 通过、1 失败，原因为旧测试无标签的规模被收紧后的规则判未知；已补明确来源标签，待最终复跑。

**影响范围：** 页面字段证据展示与部署说明。

**遗留问题：** 真实 HTTP 回调/数据库全链路尚未验收；最终专项复跑与工作区清理待完成。

---

### [2026-11-07 13:00] 官网优先搜索修复最终验证与交付核对

**任务目标：** 完成核心修复并明确验证边界。

**执行行为：** 官网数量、城市/类型覆盖及字段均满足时跳过第三方，否则按缺口补搜；官网来源统计只计实际确认归属岗位。加入完整 Pipeline 的官网优先/补缺/无官网降级、超时保留批次及缓存复用回归。核对本次新增及修改文件，保留开始时已有的未提交改动与 agent_runs.db-wal。本次临时前端服务已停止，临时 .verify-dist 在核对绝对路径后删除。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/agent/app/search/pipeline.py、budget.py | 条件降级、部分结果保留与真实抓取计数 | 预算和行为准确 |
| 修改 | project/agent/app/search/sources/company_official.py | 官方接受数统计 | 避免候选统计充当官网数量 |
| 修改 | project/agent/tests/test_job_search_official_priority.py | 完整 Pipeline/超时/缓存回归 | 验证关键新分支 |
| 删除 | project/frontend/.verify-dist（本次生成产物） | 移除临时构建输出 | 不污染工作区 |

**执行命令与验证：**

- 在 project/agent 设置 PYTHONPATH 为既有 .testdeps 和 Agent 根目录，执行 `python -m pytest tests -q -k 'job_search or search_planning or search_metrics or search_providers or verification' -p no:cacheprovider`：150 项通过，退出码 0；含真实 Chromium JS 提取/私网子请求拦截/非岗位跳转/登录墙/超时/依赖缺失测试。既有弃用提示不影响测试结果。
- `mvn -Dtest=JobSearchResultHandlerTest,JobSearchLinksTest,JobSearchFingerprintTest test -q`：9 项通过，退出码 0。
- `npm run type-check`：通过；`npm run build -- --outDir D:/py/AI-JOB/project/frontend/.verify-dist`：通过，存在大包体积提示。
- Playwright 虚构数据页面：公司规模原文可见，点击薪资依据可查看第三方证据链接，pageerror=[]，截图已视觉检查。
- 真实页面抓取结果保存在 project/agent/data/verify_official_priority_20261107.json。猎聘返回结构化字段，但链接当前内容不同于旧固定样本；华创列表/字节指定链接未取得可确认字段，未宣称全部抓取成功。
- `git diff --check`：通过。

**影响范围：** Agent 搜索、Java JSON 快照和前端岗位卡片；无需数据库迁移。依赖安装方式已写入 README。现有业务服务未重启；生产环境需在对应 Python 环境安装 requirements.txt 和 Chromium 后重启加载新代码。

**遗留问题：** 未执行真实 Java—Agent HTTP 回调和数据库端到端验收；已有端到端脚本会操作 career_business 并停止当前端口服务，本轮未运行该脚本。外部站点的登录、验证码、空壳或网络限制仍会导致字段未知，不能保证官网岗位或薪资/规模全部可得。旧结果不自动更新，需要重新搜索。未运行整个项目全量测试，验证集中于改动相关模块。

---

### [2026-11-13 16:25:51] 区分岗位搜索空态和来源故障

**任务目标：** 避免来源不可用时只显示“没有岗位”，给出可执行的下一步。

**执行行为：**

1. 使用现有搜索来源状态和本地筛选条件区分来源异常、筛选无结果、搜索无匹配。
2. 在搜索表单提示上次来源不可用；结果空态提供清除筛选和导入个人 JD 入口。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/views/job-search/JobSearchView.vue` | 增加来源提示、原因型空态和操作入口 | 搜索失败可解释、可恢复 |
| 修改 | `docs/功能资料/岗位推荐与投递_process.md` | 追加本记录 | 跟踪开发行为 |

**执行命令与验证：**

- 命令：修改前端页面。
- 结果：代码已修改，类型检查及页面验证待执行。

**影响范围：** AI 岗位搜索表单与结果空态。

**遗留问题：** 提交前来源实时健康状态仍需后端接口支持；搜索 Provider 配置与抓取失败未在本步修复。

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

### [2026-11-13 16:28:58] 修复个人 JD 明示地点未入库

**任务目标：** 让个人 JD 原文标注的工作城市进入岗位字段，供匹配使用。

**执行行为：**

1. 查明个人 JD 导入 SQL 固定将 `city_code` 写为 NULL，分析回调也未更新城市。
2. 导入时从明确的“工作地点/工作城市/办公地点/职位地点/工作地址”标签提取已支持城市；多个不同标注城市时保持未明确，不猜测单一城市。
3. 将提取结果传入现有岗位版本插入语句。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/backend/src/main/java/com/aijob/backend/job/JobService.java` | 增加 JD 城市解析并传递城市编码 | 明示地点进入匹配字段 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/job/JobMapper.java` | 插入岗位版本时写入 city_code | 不再固定 NULL |
| 修改 | `docs/功能资料/岗位推荐与投递_process.md` | 追加本记录 | 跟踪开发行为 |

**执行命令与验证：**

- 命令：读取导入与回调代码、修改 Java 文件。
- 结果：修改完成；测试待执行。

**影响范围：** 新导入的个人 JD 城市字段；历史记录不自动回填。

**遗留问题：** 仍需字段原文证据展示、人工纠错及技能提取补全。

---

### [2026-11-13 16:29:15] 增加个人 JD 地点提取测试

**任务目标：** 验证明示城市提取及未知/冲突地点处理。

**执行行为：**

1. 增加广州、未标注工作地点、两个冲突地点的单元测试。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/src/test/java/com/aijob/backend/job/JobServiceCityTest.java` | 三种地点提取场景 | 验证解析边界 |
| 修改 | `docs/功能资料/岗位推荐与投递_process.md` | 追加本记录 | 跟踪开发行为 |

**执行命令与验证：**

- 命令：添加 JUnit 测试文件。
- 结果：测试文件已创建，执行待继续。

**影响范围：** 后端测试。

**遗留问题：** 运行测试并检查 SQL 签名。

---

### [2026-11-13 16:30:05] 验证 JD 地点提取测试

**任务目标：** 确认新导入岗位的地点解析逻辑可编译且边界正确。

**执行行为：**

1. 运行后端定向 JUnit 测试，查看 Surefire 报告并检查 diff 格式。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/岗位推荐与投递_process.md` | 追加测试结果 | 记录验证 |

**执行命令与验证：**

- 命令：`mvn -o -q -Dtest=JobServiceCityTest test`；`git diff --check`。
- 结果：JUnit 1 项通过，失败 0、错误 0；diff 检查无空白错误，仅有换行格式提示。
- 说明：首次沙箱中启动 Maven 输出 Access is denied，改用主机执行后测试通过。

**影响范围：** 后端构建与地点解析验证。

**遗留问题：** 尚未进行真实个人 JD 导入端到端测试，历史 JD 不自动回填。

---

### [2026-11-13 16:31:07] 处理同一 JD 多城市歧义并复测

**任务目标：** 避免“工作地点：广州、深圳”被误记为仅广州。

**执行行为：**

1. 扩展明示地点解析，识别同一字段中并列的城市；多个不同城市时保持未明确。
2. 增加同一行多城市测试并复测定向 JUnit。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/backend/src/main/java/com/aijob/backend/job/JobService.java` | 解析并列工作城市 | 防止错误选择单一城市 |
| 修改 | `project/backend/src/test/java/com/aijob/backend/job/JobServiceCityTest.java` | 增加同一行多城市案例 | 验证边界 |
| 修改 | `docs/功能资料/岗位推荐与投递_process.md` | 追加本记录 | 跟踪修改及验证 |

**执行命令与验证：**

- 命令：`mvn -o -q -Dtest=JobServiceCityTest test`。
- 结果：通过，退出码 0。

**影响范围：** 新导入个人 JD 的城市字段。

**遗留问题：** 暂只覆盖页面城市字典中的 8 个城市；其他城市及人工纠错仍需继续处理。

---

### [2026-11-13 16:49:28] 增加个人 JD 提取字段纠错接口

**任务目标：** 用户核对城市和技能后，新匹配使用确认结果并保留旧岗位版本。

**执行行为：**

1. 检查岗位匹配批次使用 job_version，旧版本变更后会被标记过期。
2. 新增仅限本人、已分析的个人 JD 字段纠错：要求当前岗位版本、原因和 JD 原文引用；验证技能归属。
3. 在事务中复制旧岗位版本为新版本，更新城市/技能和确认依据，再推进当前版本；旧匹配批次可识别为过期。
4. 岗位详情返回原文地点片段与人工纠错依据。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/backend/src/main/java/com/aijob/backend/job/JobMapper.java` | 复制新岗位版本并 CAS 推进当前版本 | 保留旧版本与过期检测 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/job/JobService.java` | 纠错校验、事务及字段依据输出 | 确认后用于匹配 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/job/JobController.java` | `PUT /jobs/{jobId}/extracted-fields` | 提供纠错接口 |
| 修改 | `docs/功能资料/岗位推荐与投递_process.md` | 追加本记录 | 跟踪开发行为 |

**执行命令与验证：**

- 命令：读取岗位版本及匹配过期代码，修改 Java 文件。
- 结果：接口代码已修改，测试待执行。

**影响范围：** 私有岗位版本、技能与城市字段、后续匹配批次。

**遗留问题：** 前端确认入口、字段证据显示和端到端测试待接入。

---

### [2026-11-13 16:51:24] 接入个人 JD 字段核对页面

**任务目标：** 普通用户可查看地点依据并纠正城市与技能提取。

**执行行为：**

1. 岗位详情展示地点原文和人工确认依据；证据不足时提示核对。
2. 个人 JD 详情新增核对入口、城市与技能选择、JD 原文引用及纠正原因输入。
3. 保存后刷新个人岗位、匹配批次与详情；旧匹配由岗位版本变化判过期。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/frontend/src/api/types.ts`、`project/frontend/src/api/jobs.ts` | 字段依据类型与纠错 API | 对接后端 |
| 修改 | `project/frontend/src/views/job-match/JobMatchView.vue` | 核对弹窗、证据展示与刷新 | 用户可修正后用于匹配 |
| 修改 | `docs/功能资料/岗位推荐与投递_process.md` | 追加本记录 | 跟踪开发行为 |

**执行命令与验证：**

- 命令：修改前端文件。
- 结果：代码已接入，类型检查和浏览器交互待验证。

**影响范围：** 个人 JD 详情与匹配批次状态。

**遗留问题：** 初始技能逐项证据仍可能缺失，目前明确标为未人工核对；需补充测试。

---

### [2026-11-13 16:57:28] 验证个人 JD 字段纠错

**任务目标：** 验证纠错会生成新岗位版本，并拒绝不属于 JD 原文的依据。

**执行行为：**

1. 新增 `JobServiceCorrectionTest` 两项测试，覆盖保存城市/技能和无效原文引用。
2. 执行 Java 编译、前端类型检查与定向测试；Maven 默认动态加载 Mockito agent 时被本机进程权限卡住，中断后显式指定 Mockito agent 重跑。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/src/test/java/com/aijob/backend/job/JobServiceCorrectionTest.java` | 保存新版本和拒绝无效引用的单元测试 | 验证纠错主要路径 |
| 修改 | `docs/功能资料/岗位推荐与投递_process.md` | 追加本记录 | 跟踪验证与异常 |

**执行命令与验证：**

- 命令：`npm.cmd run type-check`；`mvn -o -q -DskipTests test`；`mvn -o -q '-Dtest=JobServiceCorrectionTest,JobServiceCityTest' test`；`mvn -o -q '-Djacoco.skip=true' '-DargLine=-javaagent:D:\maven\maven-rep\org\mockito\mockito-core\5.20.0\mockito-core-5.20.0.jar' '-Dtest=JobServiceCorrectionTest,JobServiceCityTest' test`。
- 结果：前端类型检查、Java 编译与最后的定向测试退出码均为 0；默认 Mockito 动态附加测试未完成，已手动中断。首次未加引号的 Maven 参数被 PowerShell 解析失败。
- 说明：显式 Mockito agent 的定向测试退出码为 0；尚未进行浏览器或真实数据库端到端验证。

**影响范围：** 测试文件和验证记录；产品行为未因本次记录改变。

**遗留问题：** 仍需验证真实数据库版本复制、登录用户纠错和页面交互。

---
### [2026-11-13 17:22:14] 允许个人 JD 自动分析失败后人工补全

**任务目标：** 当模型分析失败时，用户仍可核对 JD 原文并保存城市与技能用于匹配。

**执行行为：**

1. 字段纠错接口允许本人私有 JD 处于 `ACTIVE` 或 `FAILED`；失败且无 requirements 时创建最小技能要求对象。
2. 保存新岗位版本时将私有岗位状态推进为 `ACTIVE`，仍要求当前版本匹配、JD 原文引用及本人技能权限。
3. 前端失败状态展示“人工补全 JD 字段”入口；新增 Java 单元测试覆盖失败恢复。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/backend/src/main/java/com/aijob/backend/job/JobService.java`、`JobMapper.java` | 失败状态校验与新版本激活 | 让模型失败可恢复 |
| 修改 | `project/backend/src/test/java/com/aijob/backend/job/JobServiceCorrectionTest.java` | 增加失败后人工补全场景 | 验证最小要求结构 |
| 修改 | `project/frontend/src/views/job-match/JobMatchView.vue` | 失败状态核对入口与说明 | 提供恢复路径 |
| 修改 | `docs/功能资料/岗位推荐与投递_process.md` | 追加本记录 | 跟踪开发行为 |

**执行命令与验证：**

- 命令：阅读 JD 状态及回调处理后修改代码。
- 结果：代码已修改，定向测试待执行。

**影响范围：** 本人私有 JD 自动分析失败后的人工补全；不会改变公共岗位。

**遗留问题：** 需运行定向测试与浏览器回归，旧失败任务仍保留失败记录。

---
### [2026-11-13 17:23] 验证个人 JD 失败恢复与前端类型

**任务目标：** 检查新失败恢复路径和简历字段扩展后的构建。

**执行行为：** 并行运行 Java 定向测试与前端类型检查。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/岗位推荐与投递_process.md` | 追加验证结果 | 跟踪测试 |

**执行命令与验证：**

- 命令：`mvn -o -q '-Djacoco.skip=true' '-DargLine=-javaagent:D:\maven\maven-rep\org\mockito\mockito-core\5.20.0\mockito-core-5.20.0.jar' '-Dtest=JobServiceCorrectionTest,JobServiceCityTest,ResumeResultHandlerTest,ResumeServiceCorrectionTest,ProfileCustomSkillTest' test`；`npm.cmd run type-check`。
- 结果：两项命令退出码均为 0；Maven 输出本机 `Access is denied.` 提示但测试完成。

**影响范围：** Java 定向单元验证与前端类型验证。

**遗留问题：** 尚未通过真实数据库或页面验证失败 JD 的版本恢复。

---
### [2026-11-13 17:25] 导入合成个人 JD 供端到端回归

**任务目标：** 用独立测试账号验证广州地点提取和分析失败后的人工补全。

**执行行为：** 登录本轮合成账号，导入一条 50 字以上的合成网络安全工程师 JD，原文含“工作地点：广州”。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/岗位推荐与投递_process.md` | 追加测试数据记录 | 跟踪数据库与任务变更 |

**执行命令与验证：**

- 命令：本机 `POST /api/v1/auth/login`、`POST /api/v1/jobs/imports`（合成资料）。
- 结果：导入返回 HTTP 202，JD 分析任务 `be60e2ab-47f8-4489-b846-1c1b5139a115`、岗位 `1dad0a6f-a8f5-4c53-9756-1d92761d19e8` 已创建；分析结果尚待观察。

**影响范围：** 本机开发库中的合成测试账号、私有 JD 与 Agent 任务；不涉及其他用户岗位。

**遗留问题：** 待检查城市字段、任务状态和页面人工补全入口；测试数据完成后需安全清理或明确保留。

---
### [2026-11-13 17:28] 实测失败 JD 人工补全并修复空字段显示

**任务目标：** 验证分析失败后可保存人工字段，且详情不显示未定义值。

**执行行为：**

1. 查看合成 JD 任务，确认任务/岗位均为 `FAILED`，但导入时广州地点已保存为 `440100`。
2. 浏览器打开个人 JD 详情，看到“地点原文：工作地点：广州”和“人工补全 JD 字段”。
3. 从技能目录选“网络安全”“Python”，填写整段 JD 原文依据和原因，保存后页面显示 JD v2、两项技能及人工确认依据。
4. 发现人工补全最小要求对象缺少经验字段时页面显示 `undefined个月`；后端补齐可空字段，前端使用空值判断并展示人工确认引用，岗位 `ACTIVE` 标签改为“可匹配”。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/backend/src/main/java/com/aijob/backend/job/JobService.java` | 人工补全写入完整的可空资格字段 | 保持要求对象契约一致 |
| 修改 | `project/frontend/src/views/job-match/JobMatchView.vue` | 空经验门槛、人工引用和状态标签显示 | 修复实测展示错误 |
| 修改 | `docs/功能资料/岗位推荐与投递_process.md` | 追加端到端结果 | 跟踪浏览器验证及数据库变更 |

**执行命令与验证：**

- 命令：本机 `GET /api/v1/tasks/{id}`、`GET /api/v1/jobs/{id}`；浏览器个人 JD 核对流程；`npm.cmd run type-check`。
- 结果：任务失败状态确认；浏览器成功保存新版本 v2，前端类型检查退出码 0。空字段显示修复尚未在浏览器复测。

**影响范围：** 本机合成 JD 产生 v2；私有 JD 人工补全和详情显示。公共岗位不变。

**遗留问题：** 需浏览器复测新显示、验证旧匹配过期行为；模型分析失败本身（DeepSeek 配额/服务）未解决。

---
### [2026-11-13 17:29] 复测人工补全 JD 的详情展示

**任务目标：** 确认 `undefined个月` 和缺少引述提示已修复。

**执行行为：** 在浏览器刷新合成 JD v2 详情，核对状态、最低经验与人工引用。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/岗位推荐与投递_process.md` | 追加浏览器复测结果 | 跟踪验证 |

**执行命令与验证：**

- 命令：浏览器刷新 `/jobs?tab=personal&jobId=...` 并读取页面可访问状态。
- 结果：JD v2 显示“可匹配”；最低经验显示“未明确”；“要求原文引述”中显示人工核对依据，未再显示 `undefined个月`。

**影响范围：** 个人 JD 详情显示。

**遗留问题：** 当前合成 JD v2 存于测试账号；仍需验证历史匹配批次版本过期和真实简历解析回归。

---
