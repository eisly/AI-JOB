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
