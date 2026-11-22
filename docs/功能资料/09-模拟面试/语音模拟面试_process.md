# 语音模拟面试开发过程记录

## [2026-11-17] 需求规格落盘

**任务目标：**
在实现方案设计阶段开始前，将上一阶段（需求规格设计）的调研结论固化为可被设计流程读取的 `spec.md`。

**执行行为：**

1. 确认当前分支与工作区状态：`feature/job-dashboard` @ `24482ac`，业务代码无未提交改动。
2. 复核上一阶段结论：文字面试主链路（会话状态机、版本与幂等、异步任务、逐轮评分、报告、成长证据）已实现并验收；语音相关前端/Java/Python/数据库能力均缺失。
3. 将需求规格写入 `.codeartsdoer/specs/voice_interview/spec.md`，内容覆盖组件定位、术语、角色与边界、DFX 约束、核心能力（模式选择、题目播报、录音采集、转写确认、提交推进、结束报告、隐私、降级）、数据约束、待确认决策与验收基线。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `.codeartsdoer/specs/voice_interview/spec.md` | 语音模拟面试需求规格说明 | 作为实现方案设计阶段的输入基线 |

**执行命令与验证：**

- 命令：`ls .codeartsdoer/specs`、`ls AI-JOB/.codeartsdoer/specs/job_workbench`
- 结果：通过
- 说明：确认既有 SPEC 目录结构为 `spec.md` + `design.md` + `tasks.md`，新建目录沿用同一结构。

**影响范围：**
仅新增设计文档，不涉及业务代码、接口、数据库与配置变更。

**遗留问题：**
`design.md` 待实现方案设计阶段产出；需求中的供应商、限额、TTL、HTTPS 形态等决策项仍需团队确认。
## [2026-11-17] 实现方案设计落盘（语音模拟面试）

**任务目标：**
基于 `spec.md` 与仓库源码调研，产出可直接用于任务拆分与编码的技术设计 `design.md`；本轮只做设计，不写业务代码、不改数据库、不提交代码。

**执行行为：**

1. 逐项核对前端面试页与请求层：`InterviewView.vue`（文字面试提示、提交与 409 处理、刷新恢复）、`api/interviews.ts`、`api/request.ts`、`api/task-registry.ts`、`composables/useTask.ts`、`api/types.ts`、`router/routes.ts`、`vite.config.ts`、`package.json`。
2. 核对 Java 面试与任务链路：`interview/{InterviewController,InterviewService,InterviewMapper,InterviewResultHandler}`、`task/{TaskService,IdempotencyService,TaskController,TaskMapper,InternalTaskController,InternalJwtService,WebClientAgentClient}`、`common/{ApiErrorCode,BusinessException,GlobalExceptionHandler,Data,CurrentUserResolver}`、`SecurityConfig`、`OriginGuard/OriginValidationFilter`、`RateLimitService`、`FileStorageService`、`application.yml`、`pom.xml`。
3. 核对 Python Agent：`api.py`、`runner.py`、`handlers.py`（InterviewTurnHandler/InterviewReportHandler）、`schemas.py`、`operations.py`、`config.py`、`logging_config.py`、`main.py`、`requirements.txt`、`pyproject.toml`。
4. 核对数据库：`V1`（面试四表/ai_task/audit_log）、`V3`、`V14`，确认现存最大版本为 `V14`，故设计 `V15`。
5. 尝试核对参考仓库 `Snailclimb/interview-guide`：成功获取文件树（535 个文件）并完整读取 `docs/voice-interview-architecture.md`（546 行）；`VoiceInterviewWebSocketHandler.java`、`QwenAsrService.java`、`QwenTtsService.java`、`AudioRecorder.tsx` 因沙箱网络连续失败（`curl: (2) getaddrinfo() thread failed to start`）未能逐行核对，已在文档中如实标注。
6. 产出 `design.md`：需求与存量对比（含【源码】【文档】【设计建议】【待验证】标记）、增量设计（上下文/组件架构、接口清单与错误码、V15 DDL、前端状态机、Python speech 模块、同步与异步边界、幂等并发、隐私）、失败恢复矩阵 40 条、关键决策 14 条、非功能与测试分层、第二阶段能力评估与阈值、里程碑/分工/验收清单。
7. 清理调研过程中的临时文件（`ig_tree.json`、`_ig_scan.py`、`_ig/architecture.md`）。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `.codeartsdoer/specs/voice_interview/design.md` | 语音模拟面试技术设计文档 | 作为任务拆分与编码输入基线 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加本次设计阶段记录 | 遵守开发过程记录规范 |

**执行命令与验证：**

- 命令：项目内文件读取与检索（Read/Grep/Glob）、`curl` 获取参考仓库文件树与架构文档
- 结果：仓库源码核对通过；参考仓库架构文档核对通过；实现代码逐行核对失败（网络受限）
- 说明：本轮未执行任何代码编译、单元测试或数据库迁移；未运行前端/后端/Agent。文档中的"验收清单"为待执行项，不代表已通过。

**影响范围：**
仅新增/更新设计文档，不涉及业务代码、接口实现、数据库结构、配置或依赖变更。文档提出的 `V15`、接口与组件均处于设计建议状态。

**遗留问题：**
1. `design.md` 待用户与团队评审确认后，方可进入任务拆分与编码。
2. 需产品/合规确认：供应商与音色、录音上限与配额、转写 TTL、是否逐题混用、生产 HTTPS、U13 是否纳入本期。
3. 需真实环境验证：浏览器录音格式与权限、真实 ASR/TTS P95 与首字节、multipart 超限状态码、移动端布局、双账号越权。
## [2026-11-17] 编码任务规划落盘（语音模拟面试）

**任务目标：**
基于已确认的 `spec.md` 与 `design.md`，结合当前仓库源码实际状态，产出可执行、可分工、可验收的 `tasks.md`；本轮只做任务规划，不写业务代码、不改数据库脚本、不运行迁移、不提交代码。

**执行行为：**

1. 完整读取 `.codeartsdoer/specs/voice_interview/spec.md`（373 行）与 `design.md`（1529 行），提取接口编号（V1–V6、A1–A2）、表名与 `V15` DDL、错误码映射、限额值、状态机、里程碑 M0–M3、失败矩阵 F01–F40、验收项 A1–A15。
2. 核对仓库实际状态以验证设计假设：迁移最大版本为 `V14`；`com/aijob/backend/interview` 仅 `InterviewController/InterviewService/InterviewMapper/InterviewResultHandler` 四个类；`project/agent/app` 无 `speech/` 模块且 `requirements.txt` 无 dashscope；前端 `src` 全量无 `MediaRecorder/getUserMedia/speechSynthesis` 使用；`package.json` 无测试框架。
3. 核对关键源码行：`InterviewService.answer`（验证版本/最新题/幂等重试分支与"内容一致"约束）、`sessionVo` 手工白名单、`agentMessages()` 不含来源字段、`InterviewMapper.insertMessage` 为 10 参数注解方法且被 ASSISTANT/USER 两处调用、`ApiErrorCode` 无 504 枚举、`GlobalExceptionHandler` 无 `MaxUploadSizeExceededException` 处理、`InternalJwtService.runToken`（ai:run/300s）、`security.verify_internal_token` 已参数化、`api.py` 的 `principal_dependency` 固定 `settings.run_scope`、`ApiView.map` 为全列驼峰映射、`request.ts` 仅 `request`（JSON）与 `downloadBinary`（落盘）。
4. 产出 `tasks.md`：53 个任务（M0 7 + M1 22 + M2 8 + M3 4 + 测试 12），每个任务含唯一编号、标题、角色、前置依赖、文件路径、实现要点、验收标准、验证方式、design/spec 追溯；另含常量单一事实源表、错误码使用表、关键路径与并行度、10 条设计与代码冲突记录（C1–C10）、"不做"清单与触发阈值、spec 条目追溯矩阵、A1–A15 验收映射、11 项阻塞决策。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `.codeartsdoer/specs/voice_interview/tasks.md` | 53 个编码任务、里程碑分组、冲突记录、追溯矩阵、阻塞项 | 作为后续编码阶段的执行清单 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加本次任务规划阶段记录 | 遵守开发过程记录规范 |

**执行命令与验证：**

- 命令：`Read/Grep/Glob` 读取源码与文档；`grep -c` 校验任务编号
- 结果：通过
- 说明：任务标题共 53 个（M0-01～M0-07、M1-01～M1-22、M2-01～M2-08、M3-01～M3-04、T-01～T-12），编号唯一且连续，无重复。本轮未编译、未运行测试、未执行迁移。

**影响范围：**
仅新增任务规划文档并追加过程记录，不涉及业务代码、接口实现、数据库结构、配置或依赖变更。

**遗留问题：**
1. `tasks.md` 待用户与团队评审确认后方可进入编码。
2. 11 项决策（O1–O11）需产品/合规/技术负责人在 M0 收口：供应商、限额、TTL、逐题混用、HTTPS、第三方留存、U13 范围、是否持久化 mode/source、同意接口归属、超时错误语义、录音时长下限。
3. 本轮核对发现 10 条设计与代码/设计内部不一致（C1–C10，含"同意接口缺失""504 无错误码""首段先播与一次性响应矛盾"），已在第 8 章给出建议处置，未擅自修改 `design.md`。
### [2026-11-22 20:23] 确定语音面试设计范围

**任务目标：**
为 AI-JOB 现有模拟面试输出一份桌面端界面设计稿，补齐题目播报和逐题语音作答的交互。

**执行行为：**

1. 查看现有语音面试参考图、页面、题目播放器和录音入口，沿用蓝白配色及左侧导航。
2. 将题目原文与播报控件放在同一区域，设计稿展示正在播报的追问。预设从开始面试按钮触发首题播报，后续题和追问自动播报，提供重播、文字作答、可编辑转写和手动确认提交。
3. 核对当前规格中的手势触发要求。记录该规则与逐题自动播报目标的差异，留待实现阶段同步规格和验证浏览器行为。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加设计范围和规格差异 | 保留设计依据 |

**执行命令与验证：**

- 命令：`rg -n '禁止自动|用户手势|自动播放|自动播报|首题' .codeartsdoer/specs/voice_interview/spec.md`
- 结果：通过
- 说明：第 177 行要求用户手势触发并禁止自动播放，第 191 行包含播放拦截时的处理提示。

**影响范围：**
桌面端模拟面试设计。

**遗留问题：**
生成、检查并保存视觉稿。语音链路与浏览器自动播放行为留待实现和联调。

---

### [2026-11-22 20:24] 生成并检查语音模拟面试视觉稿

**任务目标：**
交付一张以追问正在播报为主状态的桌面端静态设计稿。

**执行行为：**

1. 以 `21-语音模拟面试.png` 为参考，使用内置 imagegen 生成一张界面图。题目卡包含题文、播报进度、暂停和重播。作答区包含录音与文字入口。侧栏展示主问题进度及当前追问。
2. 检查成图的题文、控件、中文标签和状态一致性，保留未开始录音时的作答区空白，为后续状态显示转写内容预留空间。
3. 由于说明中的 `D:/vscode/AI-JOB` 路径不存在，将稿件和本次记录保存在当前项目 `D:/py/AI-JOB` 的对应目录。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `docs/功能资料/09-模拟面试/语音模拟面试-UI设计稿.png` | 保存桌面端播报追问状态图 | 供界面评审 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加生成和视觉验证结果 | 记录设计稿来源与范围 |

**执行命令与验证：**

- 命令：`Copy-Item -LiteralPath $src -Destination $dst`、`Get-FileHash -LiteralPath $src`、`Get-FileHash -LiteralPath $dst`、`git -c core.fsmonitor=false status --short -- 'docs/功能资料/09-模拟面试'`
- 结果：通过
- 说明：PNG 副本与原图哈希一致，可解码，实际尺寸 1487 × 1058。视觉检查确认题目、暂停、重播、录音和文字入口均可辨读。未执行编译、浏览器交互或供应商联调，本次仅交付静态视觉稿。

**影响范围：**
模拟面试功能资料。

**遗留问题：**
录音中、转写确认和播报失败等状态按本次交互说明落地时需继续验收。需同步自动播报相关规格。

---

### [2026-11-22 20:26] 确定模拟面试入口与语音页的衔接

**任务目标：**
根据现有深色模拟面试页面截图，补充「新建语音面试」入口及点击后的页面设计。

**执行行为：**

1. 将截图与 `InterviewView.vue` 对照，确认现有文字面试按钮位于页面右上角，并核对岗位、简历及主问题数量字段。
2. 设计在文字入口旁增加独立的语音入口。进入语音准备页时沿用已选信息，用户确认开始后才创建会话并进入题目播报状态。上一版作答稿作为开始后的状态参考。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加入口与跳转设计决策 | 记录两屏设计依据 |

**执行命令与验证：**

- 命令：`rg -n '新建文字面试|刷新当前面试|面试准备|已分析岗位|已确认简历|主问题数量|历史面试' project/frontend/src/views/interview/InterviewView.vue`、`rg -n 'interview' project/frontend/src/router -g '*.ts'`
- 结果：通过
- 说明：当前项目只注册了已有模拟面试入口。本次语音独立页面为设计方案。

**影响范围：**
桌面深色主题下模拟面试首页和语音面试准备页的视觉设计。

**遗留问题：**
生成并检查两屏视觉稿。导航与场景中的操作留待开发与联调。

---

### [2026-11-22 20:27] 保存并校验深色双屏设计稿

**任务目标：**
交付一张包含模拟面试入口与语音面试准备页的设计稿。

**执行行为：**

1. 使用内置 imagegen，以上传的深色页面截图与上一版语音作答稿为参考，生成从入口到准备页的双屏图。将新增语音入口设为蓝色实心，文字入口用描边显示。
2. 检查两屏导航、两处语音按钮、跳转标注和预填字段，确认图中采用示例岗位和简历，未复制截图中的账号资料。
3. 将 PNG 复制到当前仓库的 `docs/功能资料/09-模拟面试` 目录，保留上一版作答稿。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `docs/功能资料/09-模拟面试/语音面试入口与跳转-深色设计稿.png` | 双屏展示语音入口与跳转后的准备页 | 供评审 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加设计稿落盘与校验记录 | 保留交付信息 |

**执行命令与验证：**

- 命令：`Copy-Item -LiteralPath $src -Destination $dst`、`Get-FileHash -LiteralPath $src`、`Get-FileHash -LiteralPath $dst`
- 结果：通过
- 说明：文件可解码，尺寸 1149 × 1368，与原图哈希一致。静态检查可辨读「新建语音面试」和「开始语音面试」。未执行交互测试。

**影响范围：**
模拟面试功能资料。

**遗留问题：**
设计稿仅展示页面状态。实际跳转、预填与首题播报待实现。

---

### [2026-11-22 20:47] Agent 接入真实 Qwen TTS 提供方

**任务目标：**
按实施计划在 Python Agent 中增加真实 Qwen TTS，默认使用 `qwen3-tts-flash` + `Cherry`，复用现有百炼 Key 与地域配置，并让 Java 能按实际音频格式处理。

**执行行为：**

1. 在 `app/speech/qwen.py` 新增 `QwenTtsProvider`：调用百炼多模态生成接口，请求体包含 `model`、`input.text`、`input.voice`、`input.language_type=Chinese`；同时支持响应中的内联 Base64 音频与临时音频 URL 下载，下载请求不携带 Authorization。
2. 统一超时、鉴权、限流与上游错误映射：超时 → `SPEECH_TIMEOUT`，401/403 → `SPEECH_AUTH_FAILED`，429 → `RATE_LIMITED`，5xx → `SPEECH_UNAVAILABLE`；对百炼 `Arrearage`（账户欠费）单独映射为鉴权类失败，避免误判为普通响应异常。
3. 通过音频魔数识别实际格式（WAV/MP3/OGG/MP4）并返回真实 MIME 与 format；`SpeechService` 返回值新增 `format`、`model` 字段，供 Java 侧缓存键与响应类型使用。
4. `SpeechService` 支持 ASR/TTS 独立关闭（`disabled`），`api.py` 按能力分别装配 `QwenAsrProvider` / `QwenTtsProvider`；`auto` 模式下 ASR 为 qwen 时 TTS 跟随 qwen。
5. 调整默认配置：`speech_tts_model=qwen3-tts-flash`、`speech_tts_voice=Cherry`；同步更新 `.env.example` 说明与本地 `.env` 运行配置（本地 TTS Provider 改为 qwen）。
6. 补充/更新语音单测：内联音频、URL 下载、默认音色、空音频、鉴权/限流/欠费、下载失败、日志不泄露文本/URL/Key，以及 disabled 能力与 model/format 透传；同时隔离本地 `.env` 对测试的干扰（conftest 显式 `speech_api_key=None`，配置类测试显式 `auto`）。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/agent/app/speech/qwen.py` | 新增 `QwenTtsProvider`、音频格式识别与错误映射 | 提供真实 TTS 能力 |
| 修改 | `project/agent/app/speech/schemas.py` | 合成结果新增 `format`、`model` | 供 Java 缓存与 MIME 判定 |
| 修改 | `project/agent/app/speech/service.py` | 支持能力独立关闭，返回真实格式与模型 | 兼容 ASR/TTS 独立开关 |
| 修改 | `project/agent/app/speech/router.py` | 合成默认格式改为 wav | 与 qwen3-tts-flash 实际输出一致 |
| 修改 | `project/agent/app/api.py` | 分别装配 ASR/TTS Provider | 启用真实 TTS |
| 修改 | `project/agent/app/config.py` | 默认模型/音色、auto 跟随 qwen | 符合确认的方案 |
| 修改 | `.env.example`、`.env` | 同步 TTS 模型、音色与 Provider | 运行配置一致 |
| 修改 | `project/agent/tests/test_speech_qwen.py` 等 4 个测试文件 | 新增/更新语音测试与隔离 | 覆盖新增行为 |

**执行命令与验证：**

- 命令：`python.exe -m pytest tests/test_speech_qwen.py tests/test_speech_service.py tests/test_speech_config.py tests/test_speech_api.py tests/test_speech_fake.py -q`
- 结果：通过（60 项）
- 命令：真实调用 `QwenTtsProvider.synthesize("你好，这是语音模拟面试的题目播报测试。", voice="Cherry")`
- 结果：失败（环境阻塞）
- 说明：使用现有 workspace 专属域名返回 HTTP 403；改用公共 `dashscope.aliyuncs.com` 返回 HTTP 400 `Arrearage`（账户欠费）。两种域名、两套现有 Key 组合均未能取得真实音频。该结论仅说明当前账户/业务空间未开通或欠费，不代表提供方代码不可用；真实音频仍待账户恢复后复验。

**影响范围：**
Agent 语音能力新增真实 TTS；ASR 既有行为不变；合成响应新增字段，向后兼容；默认配置与本地运行配置发生变化。

**遗留问题：**
1. 真实 TTS 联调被百炼账户状态阻塞，需账户恢复或开通 `qwen3-tts-flash` 后复验音频可解码。
2. Java 端尚未按实际格式合并 WAV 分段与返回 MIME，下一步处理。

---

### [2026-11-22 20:55] Java 端按实际音频格式合并与返回

**任务目标：**
让 Java 后端正确处理 Agent 返回的 WAV 分段音频，响应真实 MIME；同时支持创建会话时写入 `mode`。

**执行行为：**

1. 新增 `AudioAssembler`：按 MIME/魔数识别分段格式；WAV 逐段解析 `fmt `/`data` 块，校验格式一致后只拼接 PCM 数据帧并重建单一 RIFF 头；MP3 等连续码流直接拼接；混合格式抛错，由服务层转为依赖不可用。
2. `SpeechAudioCache` 增加 MIME 存储，缓存键包含面试、消息、音色、模型、请求格式与文本摘要；容量调整为单项 8MB、总量 64MB，避免 WAV 因超过 512KB 而永不缓存。
3. `QuestionSpeechService` 注入 `app.voice.tts-voice/model/format`，按实际合成结果返回 `mimeType`；分段格式异常给出明确错误。
4. `SpeechAgentClient.synthesize` 透传 `format`，读取 Agent 返回的 `mimeType/format/model` 并写入日志（不含文本）。
5. `VoiceInterviewController` 不再固定 `audio/mpeg`，改为使用合成结果的实际 MIME。
6. `application.yml` 默认开启 TTS 入口，新增音色/模型/格式配置；合成超时调整为 20 秒。
7. 创建面试接口支持可选 `mode: TEXT | VOICE`（默认 TEXT，校验非法值），`InterviewMapper.insertSession` 写入 `mode` 列；会话类型在创建时确定。
8. 补充 `AudioAssemblerTest`、`QuestionSpeechServiceTest` 缓存键断言、`InterviewServiceTest` 模式写入与非法值拦截。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `project/backend/src/main/java/com/aijob/backend/interview/voice/AudioAssembler.java` | WAV/MP3 分段合并 | 保证完整可播放音频 |
| 新增 | `project/backend/src/test/java/com/aijob/backend/interview/voice/AudioAssemblerTest.java` | 合并与异常用例 | 回归覆盖 |
| 修改 | `SpeechAudioCache.java` | 存 MIME、调整容量 | WAV 较大且需正确响应类型 |
| 修改 | `QuestionSpeechService.java` | 动态 MIME、缓存键、配置注入 | 按实际格式返回 |
| 修改 | `SpeechAgentClient.java` | 透传 format、读取模型/格式 | 与 Agent 协议一致 |
| 修改 | `VoiceInterviewController.java` | 动态 Content-Type | 避免 WAV 当 MP3 |
| 修改 | `application.yml` | TTS 默认开启与音色/模型/格式配置 | 运行配置 |
| 修改 | `InterviewMapper.java`、`InterviewService.java` | 创建写入 mode、校验 | 会话类型在创建时确定 |
| 修改 | `InterviewServiceTest.java`、`QuestionSpeechServiceTest.java` | 新增断言 | 回归覆盖 |

**执行命令与验证：**

- 命令：`mvn -o -q -DskipTests compile`
- 结果：通过（首次因 lambda 捕获可变局部变量失败，改为 final 后通过）
- 命令：`mvn -o "-Djacoco.skip=true" "-DforkCount=0" "-Dtest=AudioAssemblerTest,QuestionSpeechServiceTest,InterviewServiceTest,SpeechAgentClientTest" test`
- 结果：通过（19 项）
- 说明：WAV 合并测试首次失败，原因是测试构造的 `fmt` 块漏写声道字段导致长度错位；补齐后 6 项全部通过。未启动后端实例、未执行数据库迁移。

**影响范围：**
音频响应类型与分段合并逻辑改变；缓存键与容量变化；创建接口新增可选参数，旧调用不受影响；TTS 入口默认开启。

**遗留问题：**
1. 真实 WAV 音频联调仍需百炼 TTS 可用后验证（当前账户阻塞）。
2. 未跑 Java 全量测试（本机 JaCoCo/fork 与 3307 测试库限制）。

---

### [2026-11-22 21:02] 前端语音页、共用流程与自动播报

**任务目标：**
新增 `/interview/voice` 页面与首页入口，按会话类型分流历史记录；抽出文字/语音共用业务流程，实现逐题自动播报、播放拦截提示与录音停播。

**执行行为：**

1. 新增 `useInterviewSession`，把选项、历史、会话、任务、提交、结束、报告的加载与状态机从 `InterviewView` 抽出；两个页面共用同一业务流程。
2. 新增 `InterviewReportPanel`，两个页面复用同一报告展示。
3. 新增 `VoiceInterviewView`：准备态（岗位/简历/题数/作答方式）与作答态（AI 面试官题目卡、播报进度/暂停/重播、回答卡、面试进度侧栏、历史列表）；有活动语音会话时提供「继续已有语音面试」，有活动文字面试时引导回文字页。
4. `useQuestionSpeech` 增加逐题自动播报：按 `messageId` 去重，重复刷新同一题不打断；浏览器拦截时显示「点击播放题目」并保留已取到的音频；合成失败保留错误供重试，题目原文始终可读；切题、录音、结束与离开页面时释放音频并取消未完成请求。
5. `VoiceAnswerPanel` 支持页面统一管理播放器（`showPlayer=false`）、嵌入卡片（`flat`）、自定义标题，并在开始录音时向页面发出事件以停止播报。
6. `InterviewView` 增加蓝色「新建语音面试」与描边「新建文字面试」；历史记录显示文字/语音类型并按类型跳转；直接打开语音会话链接时自动转到 `/interview/voice`，语音页打开文字会话时反向跳回。
7. 创建接口透传 `mode`，任务中心链接按语音/文字分别登记；新增路由 `/interview/voice`。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `project/frontend/src/composables/useInterviewSession.ts` | 共用面试业务流程 | 两个页面复用 |
| 新增 | `project/frontend/src/views/interview/components/InterviewReportPanel.vue` | 共用报告面板 | 避免复制 |
| 新增 | `project/frontend/src/views/interview/VoiceInterviewView.vue` | 语音准备/作答/报告/历史页 | 新增语音面试页面 |
| 修改 | `project/frontend/src/composables/voice/useQuestionSpeech.ts` | 自动播报去重、拦截、进度与清理 | 逐题播报行为 |
| 修改 | `project/frontend/src/views/interview/components/VoiceAnswerPanel.vue` | 播放器开关、嵌入模式、录音事件 | 适配语音页布局 |
| 修改 | `project/frontend/src/views/interview/InterviewView.vue` | 入口按钮、历史类型跳转、共用流程与报告组件 | 首页与分流 |
| 修改 | `project/frontend/src/api/interviews.ts` | 创建透传 mode | 接口对接 |
| 修改 | `project/frontend/src/router/routes.ts` | 新增语音路由 | 页面可达 |

**执行命令与验证：**

- 命令：`npm run type-check`
- 结果：通过
- 命令：`npm run build`
- 结果：通过（仅既有大 chunk 告警）
- 说明：未在浏览器中实测登录态下的入口跳转、自动播报、录音转写与报告恢复；这些仍需真实环境验证。

**影响范围：**
模拟面试首页新增语音入口与类型分流；语音页面依赖既有语音接口与新增 `mode` 创建参数；文字面试页面文案与按钮样式变化。

**遗留问题：**
1. 浏览器真实链路（入口、预填、首题、自动播报、录音、追问、历史恢复、报告）尚未验证。
2. 深色/浅色/窄屏视觉核对尚未进行。
3. 自动播报在异步生成首题时可能被浏览器拦截，需实测确认兜底提示可用。

---

### [2026-11-22 21:10] 本地重启与真实链路探测

**任务目标：**
让本地 Java/Agent 运行新代码，确认页面可达，并尝试用演示账号完成创建 VOICE 会话与合成端点的真实联调。

**执行行为：**

1. 重新打包后端并备份旧 jar（运行目录内 `backend-0.0.1-SNAPSHOT.jar.bak-voice`），复制新 jar 后重启后端；保持原运行环境（MySQL 3307 `career_business_feature_bug`、Redis 6380、Agent 回调 8080）。
2. 重启 Agent，使用原 SQLite 数据文件与回调地址；启动日志显示 `asr=qwen tts=qwen`，说明真实 TTS Provider 已装配。
3. 直接调用 `QwenTtsProvider` 探测：workspace 专属域名返回 HTTP 403；公共 `dashscope.aliyuncs.com` 返回 HTTP 400 `Arrearage`（账户欠费）；两套现有 Key 组合均未取得音频。
4. 尝试用仓库演示账号登录并联调：演示账号在 3307 业务库中不存在；实际登录返回 401「邮箱或密码错误」，未创建任何会话。
5. 使用 curl 检查前端路由：`/interview` 与 `/interview/voice` 均返回 200。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | 运行目录内 `backend-0.0.1-SNAPSHOT.jar` | 替换为新构建产物，保留备份 | 加载新后端代码 |
| 删除 | `docs/功能资料/三端联调_process.md` | 移除联调子任务误建的重复文档 | 记录统一保留在本文件 |

**执行命令与验证：**

- 命令：`mvn -o -q -DskipTests package`
- 结果：通过
- 结果：后端新进程 PID 64156，8080 监听，HTTP 401（存活）；Flyway `Schema ... version: 22`，22 个迁移校验通过，启动耗时 8.756 秒
- 结果：Agent 新进程 PID 54772，8100 监听，`/healthz` 200，启动日志 `asr=qwen tts=qwen`
- 结果：真实 TTS 探测失败（403 / `Arrearage`），未获得音频
- 结果：登录失败（演示账号不存在），未执行创建/合成端点联调；前端两个路由 200

**影响范围：**
本地 8080 与 8100 已切换到本次代码；前端 Vite 仍在 5173。真实 TTS 仍不可用，语音播报在当前账户状态下会走前端错误提示与浏览器本地朗读降级。

**遗留问题：**
1. **真实 TTS 音频未验证**：百炼账户欠费或业务空间未开通，需恢复账户/开通模型后复验音频可解码、WAV 合并与浏览器播放。
2. **端到端浏览器流程未验证**：缺少可用登录账号（演示账号未导入且密码编码不兼容），无法创建 VOICE 会话、录音转写与报告恢复。
3. 深色/浅色/窄屏视觉核对未执行。
4. 自动播报拦截提示、重复刷新保持播放、录音停播、过期请求取消仅通过静态阅读与构建验证，未在浏览器实测。

---

### [2026-11-22 21:22] 会话类型一致性修复与最终回归

**任务目标：**
确保会话类型只在创建时确定，语音回答不反向改变文字会话；完成 Agent/Java/前端回归并让本地运行实例加载最终代码。

**执行行为：**

1. 一致性修复：删除 `InterviewService.answer` 中语音回答时调用 `mapper.markVoiceMode` 的逻辑，并移除 `InterviewMapper.markVoiceMode`。文字会话即使使用语音转写作答也只记录 `answerSource=VOICE`，不会把会话 `mode` 改成 `VOICE`；历史分流严格按创建时的类型。
2. 修复 `test_misc.py` 中生产安全用例对本地语音环境变量的隐式依赖：显式设置 `speech_provider=disabled`，避免本机 `AGENT_SPEECH_ASR_PROVIDER=qwen` 导致无关键误报。
3. Agent 全量回归（排除 Playwright 渲染用例）：578 通过、1 失败、3 跳过；唯一失败即上述 `test_prod_runtime_security_rules`。修复后对 `test_misc` 与 4 个语音测试文件复验：89 通过、0 失败。
4. Java：重新编译通过；`InterviewServiceTest` 4 项通过；重新打包并替换运行目录 jar（保留 `backend-0.0.1-SNAPSHOT.jar.bak-before-mode-fix`）；重启后端。
5. 前端类型检查与生产构建保持通过（修复前已验证，本次只改后端与测试隔离）。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/backend/src/main/java/com/aijob/backend/interview/InterviewService.java` | 移除语音回答时改写会话 mode | 会话类型创建时确定 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/interview/InterviewMapper.java` | 删除 `markVoiceMode` | 不再需要 |
| 修改 | `project/agent/tests/test_misc.py` | 显式关闭语音能力 | 隔离本地环境变量 |
| 修改 | 运行目录内 `backend-0.0.1-SNAPSHOT.jar` | 替换为最终构建并重启 | 加载一致性修复 |

**执行命令与验证：**

- 命令：`mvn -o -q -DskipTests compile`
- 结果：通过
- 命令：`mvn -o "-Djacoco.skip=true" "-DforkCount=0" "-Dtest=InterviewServiceTest" test`
- 结果：通过（4 项）
- 命令：`mvn -o -q -DskipTests package`
- 结果：通过
- 命令：Agent `pytest tests -q --ignore=tests/test_job_search_render.py`
- 结果：578 通过 / 1 失败 / 3 跳过；修复后定向复验 89 通过
- 结果：后端新进程 PID 76900，8080 监听，HTTP 401（存活）；Flyway V22，22 个迁移校验通过，启动 7.752 秒
- 结果：Agent PID 54772，8100 监听，`/healthz` 200，启动日志 `asr=qwen tts=qwen`；前端 5173 持续运行，`/interview` 与 `/interview/voice` 返回 200

**影响范围：**
文字会话不再因语音作答被改判为语音类型；本地 8080 已加载最终代码；Agent 加载真实 TTS Provider 但供应商账户仍不可用。

**遗留问题：**
1. 真实 TTS 音频与 WAV 合并的端到端播放仍未验证：百炼账户欠费或业务空间未开通（403 / `Arrearage`）。
2. 登录态浏览器流程仍未验证：本地业务库缺少可用账号，演示账号未导入且密码编码不兼容。
3. 深色/浅色/窄屏视觉核对与自动播报浏览器行为未实测。

---

### [2026-11-22 21:25] 修复录音期间再次播报的问题

**任务目标：**
让独立语音页面在请求麦克风和录音期间保持题目播放互斥。

**执行行为：**

1. 检查 VoiceAnswerPanel 的两个调用方，保留既有 recording-start 事件，增加 recording-change 通知。
2. 录音组件在 requesting、recording 状态同步通知父页面，停止及卸载后解除锁定。
3. 独立语音页在录音期间禁用播放、重播、继续面试和重试播报，同时在处理函数中检查状态。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/frontend/src/views/interview/components/VoiceAnswerPanel.vue` | 同步报告录音活动状态及卸载清理 | 外层播放器需要感知麦克风状态 |
| 修改 | `project/frontend/src/views/interview/VoiceInterviewView.vue` | 播放控件与录音互斥 | 避免题目音频被录入回答 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加修复记录 | 追溯本次变更 |

**执行命令与验证：**

- 命令：`rg -n 'recording-start|<VoiceAnswerPanel' project/frontend/src`、`apply_patch`
- 结果：修改完成，验证待执行
- 说明：本阶段完成代码调整，接下来进行类型检查、构建和浏览器回归。

**影响范围：**
独立语音面试页面的录音和播报控制。

**遗留问题：**
真实 TTS、ASR、浏览器互斥及完整作答流程待本轮验收。

---

### [2026-11-22 21:31] 接入现有业务空间可用的真实 TTS 模型

**任务目标：**
复用现有 Key，让当前业务空间成功返回真实题目音频。

**执行行为：**

1. 使用虚构短文本探测现有配置：qwen3-tts-flash 在业务空间返回 403 AccessDenied.Unpurchased，公共域名返回 400 Arrearage。
2. 按阿里官方非实时 TTS 文档，使用相同 Key 调用业务空间的 qwen-audio-3.1-tts-flash，成功获得 65324 字节 WAV。
3. QwenTtsProvider 按配置模型选择官方接口。qwen-audio 系列采用 SpeechSynthesizer 路径和 WAV/24kHz 参数；原 qwen3 配置继续使用其既有协议。
4. 本地 .env 的 TTS 模型与音色调整为 qwen-audio-3.1-tts-flash 和 longanhuan_v3.1。Key 沿用现有值。
5. 增加两个基础 URL 形式的请求协议回归用例。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/agent/app/speech/qwen.py` | 按模型选择官方 TTS 路径和参数 | 修复当前业务空间模型协议兼容 |
| 修改 | `project/agent/tests/test_speech_qwen.py` | 覆盖带及不带 api/v1 的 Qwen-Audio 请求 | 防止地址拼接和协议回归 |
| 修改 | `.env` | 调整本地 TTS 模型与音色 | 使用已实测可用模型 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加排查与验证记录 | 保留真实供应商证据 |

**执行命令与验证：**

- 命令：临时验收脚本 `voice_acceptance.py probe`、`voice_acceptance.py diagnose`
- 结果：旧模型调用失败，新模型官方接口调用通过
- 说明：诊断仅使用虚构短文本。提供方接入修改后的自动化测试及运行实例重载待执行。
- 命令：`npm run build`
- 结果：通过
- 说明：录音互斥调整通过类型检查与生产构建，保留既有大 chunk 提示。另已通过正常注册接口创建独立验收账号，虚构岗位分析任务进行中。临时脚本遇到已打开 httpx Client 重入错误后已修正；独立探测进程中旧环境变量别名与 .env 冲突，清理该测试进程的旧别名后完成探测。

**影响范围：**
Agent 的真实 TTS 兼容与当前本地语音服务配置。

**遗留问题：**
重载 Agent 后验证业务接口音频、浏览器播放、录音转写与新报告。

---

### [2026-11-22 21:35] 修复真实 WAV 长度及 Java 与 Agent 音色配置一致性

**任务目标：**
解决真实供应商音频文件头与跨服务音色配置导致的播放问题。

**执行行为：**

1. 真实提供方已返回 WAV，但 wave 读取时得到约 44739 秒的错误时长。检查发现供应商文件包含流式占位长度，需要按实际收到的 PCM 帧重建文件头。
2. Agent 在返回 WAV 前使用标准库 wave 读取实际帧并重建头部，格式异常返回结构化错误；增加占位文件头回归测试。
3. Java 的模型、音色配置增加 SPEECH_TTS_MODEL / SPEECH_TTS_VOICE 回退，使显式 APP_VOICE 设置及统一语音配置均可生效。
4. 自动化语音回归 66 项通过。运行时检查发现 8100 已停止，使用原 SQLite 路径及后端鉴权启动缺失的 Agent。虚构岗位分析首次等待超过 240 秒，保留测试账号和任务以便安全恢复。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/agent/app/speech/qwen.py` | 重建实际 PCM 长度的 WAV 头 | 修复真实播放时长和分段合并 |
| 修改 | `project/agent/tests/test_speech_qwen.py` | 覆盖流式占位长度 | 防止模拟文件遗漏真实格式问题 |
| 修改 | `project/backend/src/main/resources/application.yml` | 使用统一语音模型、音色配置的回退值 | 避免 Java 仍向新模型发送旧音色 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加排查与服务状态 | 保留实际失败和恢复过程 |

**执行命令与验证：**

- 命令：`python -m pytest tests/test_speech_qwen.py tests/test_speech_service.py tests/test_speech_config.py tests/test_speech_api.py tests/test_speech_config_sources.py -q`
- 结果：修改 WAV 逻辑前 66 项通过，最新变更待回归
- 命令：临时脚本 `start_voice_services.py`、`voice_acceptance.py probe`
- 结果：Agent 启动且 healthz 200，提供方返回 provider=qwen、WAV 138284 字节；发现占位时长并修复
- 说明：尚需部署最新配置与规范化音频逻辑后复验。

**影响范围：**
真实 TTS 返回文件及本地 Java/Agent 模型音色一致性。

**遗留问题：**
服务重载后的业务接口音频、浏览器真实播放和录音转写验收继续进行。

---

### [2026-11-22 21:37] 回归通过并重载真实语音服务

**任务目标：**
让本地运行实例加载真实 TTS 兼容修复与 WAV 长度规范化。

**执行行为：**

1. 最新 Agent 语音回归 67 项通过。
2. Maven 离线打包成功，将新 jar 复制到原运行目录并保存 .bak-voice-acceptance 备份。
3. 保留 Java 原数据库、Redis、鉴权及 Agent SQLite 路径，重载 Java 与 Agent，运行日志保存在本次临时验收目录。
4. 提供方重测成功：qwen-audio-3.1-tts-flash 返回 142124 字节 WAV，标准库读取时长 2.96 秒，确认占位长度问题已修复。
5. 独立验收账号已在内置浏览器登录。虚构岗位任务在服务中断期间过期，已通过正常重试接口恢复处理。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/backend/target/local-restarts/feature-bug-v22-20261122-193713/backend-0.0.1-SNAPSHOT.jar` | 替换构建产物并保留旧 jar 备份 | 加载最新音色配置回退 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加测试与部署结果 | 记录真实验证 |

**执行命令与验证：**

- 命令：`mvn -o -q -DskipTests package`
- 结果：通过
- 命令：`python -m pytest tests/test_speech_qwen.py tests/test_speech_service.py tests/test_speech_config.py tests/test_speech_api.py tests/test_speech_config_sources.py -q`
- 结果：67 项通过
- 命令：临时脚本 `reload_voice_services.py`、`voice_acceptance.py probe`
- 结果：Java PID 69596、Agent PID 47852；真实 TTS 可解码且时长正常

**影响范围：**
本机 Java 与 Agent 运行实例。

**遗留问题：**
正在继续独立测试账号的端到端验收。

---

### [2026-11-22 21:42] 复现并修复完整题目音频解码失败

**任务目标：**
让正常题长的真实合成音频通过 Java 代理并能在浏览器播放。

**执行行为：**

1. 虚构岗位和 DOCX 简历已通过真实分析、解析、确认，浏览器创建了 3 个主问题的独立 VOICE 会话。
2. 使用 Qwen 生成 27.44 秒的虚构回答并转码为 WebM，经 Java 的真实转写接口成功得到 SUCCEEDED 和 149 字文本。
3. 首题自动播报请求在 Agent 已合成成功，Java 却报告 HTTP 200 解码失败。新增 600000 字符 Base64 响应的本地 HTTP 回归用例，修复前实际执行失败，复现默认缓冲区不足。
4. SpeechAgentClient 按既有 MAX_AUDIO_BYTES 的 Base64 膨胀加 64KB JSON 开销设置有界响应缓冲。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/backend/src/main/java/com/aijob/backend/interview/voice/SpeechAgentClient.java` | 设定与音频上限匹配的有界响应缓冲 | 完整题目音频大于 WebClient 默认值 |
| 修改 | `project/backend/src/test/java/com/aijob/backend/interview/voice/SpeechAgentClientTest.java` | 真实 HTTP 大音频响应回归用例 | 捕捉小模拟音频遗漏的问题 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 记录首次失败与修复 | 保留真实验收证据 |

**执行命令与验证：**

- 命令：`mvn -o -q -Djacoco.skip=true -DforkCount=0 -Dtest=SpeechAgentClientTest#acceptsFullQuestionAudioLargerThanDefaultCodecBuffer test`
- 结果：修复前失败（1 项错误），修复后待回归
- 命令：临时验收脚本 `voice_acceptance.py setup`、`prepare-answer`、`transcribe`
- 结果：岗位/简历就绪，真实 ASR 成功
- 说明：首次 TXT 上传返回 415，已改用系统支持的 DOCX 并成功解析。原始测试音频由虚构文本生成。

**影响范围：**
Java 对 Agent 同步语音响应的解码。

**遗留问题：**
重载修复后复验首题、追问自动播放和完整答题流程。

---

### [2026-11-22 21:43] 验证录音互斥并补齐延迟权限请求的取消

**任务目标：**
确保等待麦克风权限时，切换作答方式能安全取消旧录音请求。

**执行行为：**

1. 在独立验收浏览器中点击录音，页面进入「正在申请麦克风权限」，确认播放与重播按钮均禁用。
2. 切换文字后，播放控件恢复可用。检查发现原 getUserMedia Promise 若稍后才完成，旧逻辑仍可能在文字模式开启录音。
3. 为录音请求增加 generation 校验，重置/切换/卸载后，晚到的流立即停止；旧录音停止事件和错误不再覆盖新状态。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/frontend/src/composables/voice/useVoiceRecorder.ts` | 失效录音请求的 generation 校验与流释放 | 处理延迟授权及卸载竞态 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加真实 UI 观察与修复依据 | 留存验证范围 |

**执行命令与验证：**

- 命令：浏览器点击开始录音、同意隐私告知、切换文字；读取可访问性状态与截图
- 结果：请求麦克风期间播放/重播禁用，切换文字后恢复
- 说明：内置浏览器停留在权限请求状态，尚未得到实际录音流；本轮 ASR 使用合成录音样本。延迟授权取消逻辑待专项回归。

**影响范围：**
文字与语音面试共用录音器。

**遗留问题：**
专项回归及最终浏览器播报、转写确认、报告验收继续进行。

---

### [2026-11-22 21:45] 大音频响应与录音取消回归通过

**任务目标：**
验证真实联调发现的问题修复后可用，并让服务加载最新代码。

**执行行为：**

1. Java 的 InterviewServiceTest、AudioAssemblerTest、QuestionSpeechServiceTest、SpeechAgentClientTest 定向回归通过，包含新增的大音频 HTTP 用例。
2. 使用项目已有 esbuild 与 Vue 运行临时录音器测试，直接装载真实 useVoiceRecorder，覆盖延迟授权后重置、录音时重置、等待授权时卸载。晚到的流释放且旧回调不覆盖状态。
3. Maven 打包通过，再次重载本地 Java 与 Agent，以加载完整音频缓冲区修复，数据和测试会话沿用。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/backend/target/local-restarts/feature-bug-v22-20261122-193713/backend-0.0.1-SNAPSHOT.jar` | 加载最新语音响应缓冲修复 | 完整题目播放联调 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加回归结果 | 保留验证证据 |

**执行命令与验证：**

- 命令：`mvn -o -q -Djacoco.skip=true -DforkCount=0 -Dtest=InterviewServiceTest,AudioAssemblerTest,QuestionSpeechServiceTest,SpeechAgentClientTest test`、`mvn -o -q -DskipTests package`
- 结果：通过
- 命令：临时脚本 `node recording_regression.cjs`
- 结果：3 个录音竞态场景通过
- 命令：临时脚本 `reload_voice_services.py`
- 结果：Java PID 43828、Agent PID 33656

**影响范围：**
本地语音运行实例与录音请求竞态处理。

**遗留问题：**
重新登录恢复测试会话后继续实际播放、确认转写与报告流程。

---

### [2026-11-22 21:51] 真实题目播放、转写确认与追问推进验证

**任务目标：**
验证真实 Java、Agent、Qwen、DeepSeek 和浏览器形成逐题语音闭环。

**执行行为：**

1. 使用独立测试账号的 3 个主问题会话完成首题真实转写的恢复、编辑和确认提交。服务端核对 firstAnswerEdited=true，回答来源为 VOICE，后续文字作答为 TEXT，会话始终为 VOICE。
2. 修复后 Java 题目接口返回 audio/wav、560684 字节、11.68 秒、音色 longanhuan_v3.1。浏览器显示 12 秒并实际推进到 6 秒，暂停和继续均生效。
3. 第二题重播成功，暂停后点击刷新会话仍保持暂停状态。第二题部分回答触发追问，追问无需额外点击即进入正在播报，实际观察到 5/12 秒。
4. 在播报追问时点击开始录音，播放立即停止、进度归零，播放与重播按钮禁用。切换文字后恢复可用并提交追问回答。
5. 第三主问题自动播报，显示 16 秒。随后事务追问自动播报，显示 20 秒且进度到 12 秒，验证多段 WAV 合并播放。
6. 已提交第三题追问回答，正在等待完成与报告。最后一次前端构建通过。定向 diff --check 通过；全工作区检查在既有服务启动记录的 CRLF/尾随空白处失败，该文件本轮未修改。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/frontend/dist/index.html` | 生产构建更新资源哈希 | 对应最新录音互斥修复 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加真实浏览器与服务端验证 | 记录可追溯证据 |

**执行命令与验证：**

- 命令：`npm run build`、临时脚本 `voice_acceptance.py audit`、浏览器实际点击与状态读取
- 结果：通过
- 说明：Agent 语音回归 67 项，Java 定向回归 20 项，录音器 3 个竞态场景通过。
- 说明：本轮真实 ASR 使用由虚构文本合成的 WebM，物理麦克风在内置浏览器中停留在等待权限，未提交任何物理麦克风音频。

**影响范围：**
语音面试完整题目播放、转写确认、混合作答及后续题推进。

**遗留问题：**
等待新报告与历史恢复最终验收；物理设备麦克风授权后的采集尚未验证。

---

### [2026-11-22 21:52] 同步逐题自动播报规格

**任务目标：**
让规格中的播报触发、录音互斥和验收基线与用户确认及实际实现一致。

**执行行为：**

1. 将旧版禁止自动播放的描述更新为语音页开始/继续后按新题消息自动播报一次，保留文字页手动播放及拦截兜底。
2. 补充麦克风请求与录音时的播放互斥，以及失效权限请求的音轨释放。
3. 同步首题、追问、后续题与报告的验收基线。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `.codeartsdoer/specs/voice_interview/spec.md` | 播报触发、交互及验收说明 | 落实此前已确认的逐题语音目标 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加规格同步记录 | 保留变更依据 |

**执行命令与验证：**

- 命令：读取规格对应段落并执行 `apply_patch`
- 结果：通过
- 说明：规格描述与本轮实际自动播报、刷新保持状态及录音互斥验证一致。

**影响范围：**
语音面试规格文档。

**遗留问题：**
新报告仍在生成中，继续等待最终持久化验收。

---

### [2026-11-22 21:55] 完成独立账号的端到端语音验收

**任务目标：**
确认语音入口、真实题目音频、真实转写、确认提交、追问、后续主问题与报告可以形成完整闭环。

**执行行为：**

1. 独立测试账号使用虚构岗位与简历，完整完成 3 个主问题及 2 个追问，共 5 次有效回答。
2. 首次语音回答使用合成 WebM，经真实 Qwen ASR 转写后在浏览器编辑并确认提交；服务端保存来源为 VOICE。其余 4 次使用文字作答，来源为 TEXT，会话保持 VOICE。
3. DeepSeek 生成正常完成报告，COMPLETED、completionType=NORMAL、有效主问题数 3、总分 81.7。历史列表显示已完成与有效回答 5 次，从首页点击查看报告能恢复到独立语音页并读到同一报告。
4. 最终核对 Java 音频接口返回 audio/wav、560684 字节、11.68 秒和 longanhuan_v3.1 音色。首题、追问和后续题实际播放，暂停、继续、重播、同题刷新保持暂停与录音请求时停播均已验证。
5. 报告页面浏览器 error 日志为空，已将测试报告页面保留在内置浏览器。测试账号、虚构简历与报告保留以便复查。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `project/backend/data/private-files/b3971e60-dcac-4719-bf47-baaa93f79965/` | 正常上传接口保存的虚构 DOCX 简历文件 | 独立账号端到端测试数据 |
| 修改 | `project/agent/app/speech/qwen.py` | 更新提供方说明为 Qwen3 / Qwen-Audio | 与实际支持模型一致 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加最终验收结果与限制 | 对照实际变更完成记录 |

**执行命令与验证：**

- 命令：临时脚本 `voice_acceptance.py audit`、浏览器历史报告导航与控制台检查
- 结果：通过
- 说明：测试会话 7e168b17-e05e-4ec3-8a86-363bf631c862；问题序列为 (1,0)、(2,0)、(2,1)、(3,0)、(3,1)，第一条回答确认包含编辑后的 NotNull 内容。
- 命令：`npm run build`
- 结果：通过，保留既有大 chunk 提示
- 命令：Agent 语音回归、Java 语音定向回归、临时录音器竞态回归
- 结果：67 项、20 项、3 个场景通过
- 命令：对本轮源码、规格和过程文件运行 `git -c core.fsmonitor=false diff --check -- ...`
- 结果：通过
- 说明：全工作区既有服务启动文档的尾随空白未在本轮调整。

**影响范围：**
本机语音面试软件链路、当前 TTS 模型配置与对应开发规格。Java/Agent 已加载本次修复。

**遗留问题：**
物理麦克风的授权后实际采集尚未验收。内置浏览器停在权限请求时已验证停播、禁用播放器和切换文字取消请求；真实 ASR 成功结果来自虚构文本生成的 27.44 秒 WebM，不能据此声称实体设备录音通过。

---

### [2026-11-22 22:13] 缩短模拟面试出题与追问任务耗时

**任务目标：**
减少每轮作答后等待下一题的时间，并避免空参考答案触发整轮重试。

**执行行为：**

1. 检查 Agent 日志，近期两个轮次耗时约 34 秒和 66 秒。两次首轮输出均因 referenceAnswer 为 null 触发修复调用，Java 回调各用时不到 0.1 秒。
2. INTERVIEW_TURN 显式关闭 DeepSeek 思考模式，复用原模型、轮次结构和评分校验。文字和语音面试共用该任务。
3. 内部评价模型将参考答案 null 转为空字符串，保持已有缺省语义。将轮次提示词更新为 v6，明确参考答案类型。
4. 添加回归用例，确认空参考答案时只调用一次模型，保留合法的三维评分及最终轮次状态。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/agent/app/handlers.py` | 面试轮次关闭思考 | 缩短交互等待 |
| 修改 | `project/agent/app/schemas.py` | 评价草稿空参考答案归一化 | 避免无效修复调用 |
| 修改 | `project/agent/app/operations.py`、`project/agent/app/skills/prompts/interview_turn.md` | 轮次提示词升至 v6 | 明确参考答案契约 |
| 修改 | `project/agent/tests/test_handlers.py` | 验证一次调用及评分 | 覆盖实际日志发现的回归 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加定位和修改记录 | 追溯性能改动 |

**执行命令与验证：**

- 命令：`rg` 检查轮次日志及 `git diff --check`
- 结果：定位到模型耗时和重复调用，源码差异检查通过
- 说明：回归及真实模型性能待验证。

**影响范围：**
文字和语音共用的面试轮次，以及共用评价草稿空参考答案解析。

**遗留问题：**
验证优化后的真实模型耗时、输出及本地 Agent 加载状态。

---

### [2026-11-22 22:16] 验证面试轮次提速并重载本地 Agent

**任务目标：**
验证模型性能改动和业务回归，将新代码加载到本地服务。

**执行行为：**

1. 使用仓库虚构面试数据实际调用 DeepSeek V4 Pro。首题 3.03 秒、91 输出 token，通过轮次校验并返回 ASK。已答轮 6.78 秒、399 输出 token，返回 FOLLOW_UP 和完整三维评分，总分 69.0。两条请求均只调用一次模型。
2. 运行 handlers、llm、misc 和 skills 定向回归。首次错误指定不存在的 test_api.py，未执行用例。改正文件列表后，首次运行 105 项通过，17 项因测试进程继承旧语音配置别名而失败。在单独进程中清除冲突别名后重试，122 项通过。
3. 确认原 Agent 没有排队或执行中的运行记录，复用现有启动参数、环境及 SQLite 重启 Agent。新进程 PID 77876，8100 readyz 返回 ready，provider=deepseek、storage=sqlite。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加真实测量、自动化与重载记录 | 记录上线范围与验收证据 |

**执行命令与验证：**

- 命令：`python -m pytest tests/test_handlers.py tests/test_llm.py tests/test_misc.py tests/test_skills.py --tb=short`
- 结果：122 项通过
- 命令：临时脚本 `interview_latency_check.py` 和 `restart_interview_latency_agent.py`
- 结果：真实模型两个用例通过，Agent 就绪
- 命令：对本轮源码、测试与记录运行 `git diff --check`
- 结果：通过

**影响范围：**
本地文字与语音面试的逐轮模型处理。面试报告继续使用独立的模型汇总流程。

**遗留问题：**
3.03 秒与 6.78 秒是两条虚构请求的 Agent 模型处理耗时。浏览器端还会叠加任务调度、回调和语音合成耗时，未对同一用户会话进行前后配对测量。

---
