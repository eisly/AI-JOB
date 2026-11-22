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
### [2026-11-20] Qwen-Audio-ASR 真实调用链路验证

**任务目标：**
核对官方文档、业务空间接入地址与实际配置，编写独立脚本验证 `qwen-audio-3.1-asr-flash` 的鉴权、模型权限、地域、音频输入方式与响应耗时，为后续确定浏览器录音接入方式提供实测依据。

**执行行为：**

1. 核对官方文档：`qwen-audio-3.1-asr-flash` 属非实时短音频模型（≤5 分钟、≤2GB），支持 URL 或 Base64 Data URI，音频格式包含 `webm`/`ogg`/`opus`，采样率任意；同步调用地址为 `{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`，鉴权为 `Authorization: Bearer <API Key>`，响应读取 `output.text`。
2. 核对本机环境：Python 3.7.12（pykt）、3.10（cast/en1）、3.12.4（base），均未安装 `dashscope`；PyPI 最新 SDK 2.1.0 要求 Python ≥3.9，且官方该模型示例使用原生 HTTP，因此验证脚本仅使用 Python 标准库。
3. 新增 `scripts/verify_asr.py`：默认读取项目 `.env`，支持 `--demo`（官方示例音频 URL）与 `--audio`（本地文件转 Base64 Data URI），记录连接、首字节与总耗时，输出 HTTP 状态、`request_id`、识别文本与 usage；不打印密钥与音频内容。
4. 首次 `--demo` 因 shell 环境变量中残留的旧 35 位 key 覆盖 `.env`，返回 `403 Endpoint.AccessDenied`；将脚本改为 `.env` 优先后，使用 `.env` 中 116 位 key 复测通过。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `scripts/verify_asr.py` | 独立 ASR 验证脚本（标准库实现、.env 读取、URL/Base64 双输入、分阶段计时） | 验证供应商真实调用链路 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加本次验证记录 | 遵守开发过程记录规范 |

**执行命令与验证：**

- 命令：`/i/Acaconda3/python.exe scripts/verify_asr.py --demo --timeout 30`
- 结果：通过
- 说明：HTTP 200；连接 172ms、首字节约 1016ms、总耗时约 1203ms；`usage.duration=6` 秒；识别文本与官方示例音频内容一致（"请把漆园项目的报告发给罗科，下周我们要上线玄枢系统。"）。
- 未执行：本地 10 秒录音的 Base64 上传实测（等待用户提供录音文件）。

**影响范围：**
仅新增验证脚本与文档记录，不涉及业务代码、接口、数据库、依赖或运行配置变更。

**遗留问题：**
1. 本地浏览器录音（webm/opus）需在拿到录音后实测，确认 Base64 Data URI 可被接受。
2. `dashscope` SDK 未安装；若后续坚持使用 SDK，需 Python ≥3.9 与对应版本，但该模型未经 SDK 路径验证。
3. 正式接入时需把 `AGENT_SPEECH_PROVIDER=fake` 切换为真实实现，并确认 Java/前端超时与音频大小上限。
### [2026-11-20] 保存用户提供的 ASR 验证录音

**任务目标：**
将用户提供的录音复制到项目本地测试资料目录，供后续真实 ASR 验证使用。

**执行行为：**

1. 将用户指定的 M4A 录音复制为 `.local-dev-env/voice-samples/vue-springboot-answer.m4a`，保留源文件。
2. 对源文件与副本计算 SHA256 并比较，结果一致；文件大小为 173348 字节。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `.local-dev-env/voice-samples/vue-springboot-answer.m4a` | 用户提供的原始 M4A 录音副本，未转换格式 | 作为本地 ASR 验证输入 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加录音保存和完整性校验记录 | 遵守开发过程记录规范 |

**执行命令与验证：**

- 命令：`Copy-Item -LiteralPath $recordingSource -Destination $recordingTarget`；`Get-FileHash -LiteralPath $recordingSource -Algorithm SHA256`；`Get-FileHash -LiteralPath $recordingTarget -Algorithm SHA256`。
- 结果：通过，复制成功且 SHA256 一致。
- 未执行：音频解码、播放或真实 ASR 调用。

**影响范围：**
仅新增本地音频副本并追加过程记录，未修改业务代码或配置，未提交或推送 Git。

**遗留问题：**
此录音尚待进行 Base64 输入的真实 ASR 识别验证。
### [2026-11-20] 本地 9 秒录音 Base64 上传实测

**任务目标：**
使用用户提供的本地录音 `AI-JOB/.local-dev-env/voice-samples/vue-springboot-answer.m4a`，验证“本地二进制音频转 Base64 Data URI”能否被 `qwen-audio-3.1-asr-flash` 直接识别，并记录识别正确性与端到端耗时。

**执行行为：**

1. 检查录音文件：173,348 字节，容器识别为 `mp4`（MIME `audio/mp4`），脚本按 `format=mp4` 提交。
2. 连续执行两次 `scripts/verify_asr.py --audio ... --runs 2`，比较冷/热调用差异。
3. 核对前端录音候选格式：`audio/webm;codecs=opus` → `audio/webm` → `audio/ogg;codecs=opus` → `audio/mp4`（Chrome/Edge 实际走 webm/opus，Safari 走 mp4）。
4. 本机无 `ffmpeg`/`ffprobe`，无浏览器录音环境，暂无法生成等价 webm/opus 样本。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加本地录音实测记录 | 遵守开发过程记录规范 |

**执行命令与验证：**

- 命令：`/i/Acaconda3/python.exe scripts/verify_asr.py --audio "H:\AI_JOB\AI-JOB\.local-dev-env\voice-samples\vue-springboot-answer.m4a" --timeout 60 --runs 2`
- 结果：通过（2/2 次 HTTP 200）
- 说明：
  - 第 1 次：连接 141ms、首字节 937ms、总耗时 1,109ms；`usage.duration=9`。
  - 第 2 次：连接 125ms、首字节 1,063ms、总耗时 1,203ms；`usage.duration=9`。
  - 两次识别文本一致且语义正确：“我负责求职工作台项目，使用 Vue、Spring Boot、MySQL。”
  - 未实测：Chrome/Edge 的 webm/opus 录音（官方文档已列入支持格式，但本机无 ffmpeg 与浏览器录音条件）。

**影响范围：**
仅验证脚本调用与文档记录，未修改业务代码、接口、数据库、依赖或运行配置。

**遗留问题：**
1. webm/opus 建议在真实 Chrome/Edge 录音后再抽查一次，确认容器解析无差异。
2. 接入正式链路时以 Base64 Data URI 提交，无需音频 URL、无需格式转换；本地 m4a 已验证。
3. 生产环境需确保不被外部旧 API Key 覆盖，旧 key 会产生 `403 Endpoint.AccessDenied`。

### [2026-11-20] 再次执行用户录音 ASR 测试（受限环境首次尝试）

**任务目标：**
按用户要求运行现有脚本，重新验证本地录音的真实识别结果。

**执行行为：**
使用原始录音运行一次验证脚本，未转换音频，未修改脚本或配置。

**文件变更：**
仅向本过程记录追加本次执行结果。

**执行命令与验证：**

- 命令：`I:\Acaconda3\python.exe scripts\verify_asr.py --audio H:\AI_JOB\AI-JOB\.local-dev-env\voice-samples\vue-springboot-answer.m4a --timeout 30 --runs 1`
- 结果：失败，进程退出码 1。
- 说明：脚本读取到目标模型，按 MP4/audio/mp4 构造请求；连接阶段发生 `gaierror: [Errno 11001] getaddrinfo failed`，本次未获得模型响应，不能据此判断模型识别能力。

**影响范围：**
仅测试执行与记录，不涉及业务代码、配置、数据库或依赖变更。

**遗留问题：**
尝试在允许网络访问的执行环境重新验证。

### [2026-11-20] 用户录音 ASR 复测成功

**任务目标：**
在允许网络访问的环境运行同一验证脚本，取得本地录音的真实模型响应。

**执行行为：**

1. 使用同一原始 M4A 文件、同一脚本和配置复测一次，按 MP4/audio/mp4 转为 Base64 Data URI 提交给 `qwen-audio-3.1-asr-flash`。
2. 取得 HTTP 200 和非空转写结果；未对音频执行格式转换。

**文件变更：**
仅追加本过程记录，未修改测试脚本、业务代码或运行配置。

**执行命令与验证：**

- 命令：`I:\Acaconda3\python.exe scripts\verify_asr.py --audio H:\AI_JOB\AI-JOB\.local-dev-env\voice-samples\vue-springboot-answer.m4a --timeout 30 --runs 1`
- 结果：通过，进程退出码 0；HTTP 200 OK。
- 说明：连接 109ms，脚本统计的首字节等待 1562ms，总耗时 1703ms；供应商 usage.duration 为 9 秒，转写文本长度 36 字符，内容涉及求职工作台与技术栈。按最小记录原则不保存转写全文。
- 请求编号：`a959576f-609e-9867-8493-8d250c2b4d53`。

**影响范围：**
完成一次用户授权的真实 ASR 调用，仅证明本次原始 M4A/MP4 音频的 Base64 输入可用；未接入正式面试流程，未测试 TTS。

**遗留问题：**
需继续验证浏览器实际录制的 WebM/Opus 及长录音；本次单次样本不能证明所有格式、准确率或 P95 性能。首次受限环境 DNS 失败，允许网络访问后复测成功。
### [2026-11-20] 接入真实 ASR：Agent Provider 与配置解耦

**任务目标：**
按已验证的 Base64 Data URI 同步调用方式，把真实 `qwen-audio-3.1-asr-flash` 接入 Agent 语音模块；同时解除 ASR/TTS 共用的初始化耦合，保证默认测试不触网、真实调用显式执行。

**执行行为：**

1. 新增 `app/speech/qwen.py`：使用项目已有 `httpx` 异步客户端，按 `{base_url}/api/v1/services/aigc/multimodal-generation/generation` 提交 `input_audio.data = data:<mime>;base64,...`；解析 `output.text`，空识别返回空字符串，无置信度返回 `None`。
2. 供应商错误映射：401/403 → `SPEECH_AUTH_FAILED`，429 → `RATE_LIMITED`，400/415 → `UNSUPPORTED_FILE_TYPE`，超时 → `SPEECH_TIMEOUT(504)`，5xx/异常响应 → `SPEECH_UNAVAILABLE`/`SPEECH_BAD_RESPONSE`；不记录密钥、音频 Base64、响应原文与转写全文。
3. `config.py` 拆分 `speech_asr_provider` / `speech_tts_provider`，两者优先于兼容字段 `speech_provider`；`auto` 仍按有无 Key 选择 qwen/fake。默认 ASR 模型改为 `qwen-audio-3.1-asr-flash`，新增 `speech_trust_env=False`。
4. `SpeechService` 解耦初始化：ASR/TTS 分别校验注入实现与配置是否一致；生产禁止 Fake ASR；生产允许“真实 ASR + Fake TTS”，但记录告警，TTS 入口由 Java 默认关闭。新增 `close()` 释放 httpx 连接池。
5. `api.py` 在应用生命周期内构建 `QwenAsrProvider`，关闭时释放连接池；启动日志输出 `asr=qwen tts=fake`。
6. 配置示例与本地 `.env` 增加 `AGENT_SPEECH_ASR_PROVIDER=qwen`、`AGENT_SPEECH_TTS_PROVIDER=fake`；`scripts/verify_asr.py` 的优先级统一为“环境变量优先、`.env` 回退”，并提示两者不一致。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `project/agent/app/speech/qwen.py` | 真实 ASR Provider（Base64、httpx、错误映射、脱敏日志） | 接入已验证供应商链路 |
| 修改 | `project/agent/app/config.py` | ASR/TTS 独立 Provider、默认模型、生产校验 | 解除初始化耦合、统一配置 |
| 修改 | `project/agent/app/speech/service.py` | 分别校验注入实现、provider 名称一致性、close | 不允许静默回落到 Fake |
| 修改 | `project/agent/app/speech/fake.py` | Fake Provider 增加 `name` | 生产校验识别模拟实现 |
| 修改 | `project/agent/app/api.py` | 注入 QwenAsrProvider、生命周期关闭 | 正式服务使用真实 ASR |
| 修改 | `.env` / `.env.example` | 增加 ASR/TTS 独立 Provider 示例 | 配置与代码对齐 |
| 修改 | `scripts/verify_asr.py` | 与 Agent 统一读取优先级 | 测试与正式服务一致 |
| 新增 | `project/agent/tests/test_speech_qwen.py` | MockTransport 单测（请求结构、错误映射、脱敏） | 默认不触网 |
| 新增 | `project/agent/tests/test_speech_qwen_real.py` | 显式真实 Provider 测试 | 真实调用需显式开启 |
| 新增 | `project/agent/tests/test_speech_api_real.py` | 显式真实 Agent HTTP 接口测试 | 验证路由到供应商 |

**执行命令与验证：**

- 命令：`python -m py_compile ...`；`pytest tests/test_speech_*.py -q`
- 结果：通过；43 个语音相关测试全部通过（含原 `test_speech_api.py` 修正了 fixture 元组取值）。
- 命令：`RUN_REAL_ASR=1 pytest tests/test_speech_qwen_real.py tests/test_speech_api_real.py -q -s`
- 结果：通过；真实 Agent HTTP 接口返回 HTTP 200，`provider=qwen`，`usage.duration=9`，`text_chars=36`，供应商调用耗时 3,890ms。
- 说明：真实耗时明显高于验证脚本单次的 1.70s，说明不能把一次样本当作稳定 P95。

**影响范围：**
Agent 的 ASR 从 Fake 切换为真实模型；TTS 仍为 Fake 且 Java 默认关闭入口；不改接口路径、请求/响应结构、Java 转写记录机制。

**遗留问题：**
浏览器 WebM/Opus 未实测；长录音（>5 分钟）不在该模型范围；TTS 未接入真实供应商。

### [2026-11-20] Java 侧音频校验与并发防护修复

**任务目标：**
接入真实 ASR 后，修复上传音频“仅凭客户端 MIME 判定”、无界读取、同题并发 PENDING 未限制，以及音频格式错误沿用文档文案等问题，并保持既有转写记录/提交机制不变。

**执行行为：**

1. `VoiceTranscriptionService` 改为有界读取：先校验声明大小，再最多读取 8MB+1 字节，超限返回 413，不信任 `MultipartFile#getSize`。
2. 容器魔数与 MIME 必须一致（WebM/OGG/MP4）；不一致或未知容器返回 415，不再仅凭客户端 `Content-Type` 放行。
3. 新增同题 PENDING 检查：会话行锁内若存在 60 秒内仍新鲜的 PENDING 且音频 SHA 不同，返回 `409 TASK_IN_PROGRESS`，避免真实 ASR 变慢后并发重复调用。
4. `VoiceMapper` 增加 `findPendingByQuestion`；`SpeechAgentClient` 将 415 映射为音频专用文案，401/403 标记 `reason=AUTH`。
5. `application.yml` 将 `app.voice.tts-enabled` 默认改为 `false`：本次 TTS 仍为 Fake，默认不向前端暴露播放入口。
6. 新增 Java 单测覆盖容器识别、有界读取、MIME 不一致与同题并发 PENDING。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/backend/src/main/java/com/aijob/backend/interview/voice/VoiceTranscriptionService.java` | 有界读取、容器/MIME 校验、同题 PENDING | 真实 ASR 接入后的输入与并发防护 |
| 修改 | `.../voice/VoiceMapper.java` | 新增 `findPendingByQuestion` | 同题并发检查 |
| 修改 | `.../voice/SpeechAgentClient.java` | 音频 415 文案、鉴权失败标记 | 错误语义准确 |
| 修改 | `.../common/BusinessException.java` | 新增 `unsupportedAudioType()` | 不再复用 PDF/DOCX 文案 |
| 修改 | `project/backend/src/main/resources/application.yml` | TTS 默认关闭 | 不把 Fake TTS 当真实服务 |
| 新增 | `.../voice/VoiceAudioValidationTest.java` | 容器/有界读取测试 | 覆盖输入校验 |
| 新增 | `.../voice/VoiceConcurrencyGuardTest.java` | 同题 PENDING 与 MIME 不一致测试 | 覆盖并发与格式错误 |
| 修改 | `project/frontend/src/composables/voice/useVoiceAnswer.ts` | PENDING 恢复轮询、统一扩展名、卸载取消 | 刷新/慢识别时不再永久转圈 |

**执行命令与验证：**

- 命令：`mvn -q -DskipTests compile`；`mvn -q -Dtest='Interview*Test,QuestionSpeechServiceTest,Voice*Test' test`
- 结果：通过，退出码 0（含新增 Java 测试）。
- 命令：`npm run type-check`
- 结果：本次修改文件无类型错误；既有 `axios` 模块缺失及 `http.ts` 错误来自当前前端依赖环境，不是本次改动引入。
- 未执行：Java 接口 → Agent → 供应商的端到端联调。原因：本机 MySQL 服务在 3306，但 `.env` 中 `aijob`/`root` 凭据均无法连接；Docker 守护进程不可用；Redis 未安装，无法启动依赖完整后端的联调环境。

**影响范围：**
Java 对外接口、请求/响应结构、转写记录与提交消费逻辑保持不变；仅增强输入校验、并发防护与前端 PENDING 恢复。

**遗留问题：**
1. 待具备 MySQL/Redis/Agent 环境后，用 M4A 走一次 `POST /api/v1/interviews/{id}/voice-transcriptions` 完整接口联调。
2. 浏览器 WebM/Opus、其他未取得格式与长录音仍为待验收项。
3. TTS 仍为 Fake，不能宣称完整语音面试已完成。
### [2026-11-20] 全量回归与环境依赖补齐

**任务目标：**
确认真实 ASR 接入未破坏 Agent 其他能力，并区分“本次回归失败”与“本机环境缺依赖”。

**执行行为：**

1. 执行 `pytest tests -q`：6 项失败分别为 `cryptography`、`python-docx`、`pypdf` 缺失及 Playwright 浏览器未安装；无语音相关失败。
2. 补齐 `cryptography` 后，`test_storage.py` 相关用例通过；补齐 `python-docx`/`pypdf` 后 `test_files.py` 19 项全部通过。
3. `test_job_search_render.py` 仍有 2 项失败，原因依赖 Playwright Chromium 浏览器，未在本机安装；未重复执行。
4. 收尾修正：Agent 移除“魔数失败后回退到声明 MIME”的旧逻辑；Java `fresh()` 兼容 `java.sql.Timestamp`；前端 `restore()` 开始时清空旧错误。

**执行命令与验证：**

- 命令：`pytest tests/test_speech_service.py tests/test_speech_qwen.py -q`
- 结果：23 passed。
- 命令：`mvn -q -Dtest='Voice*Test' test`
- 结果：通过，退出码 0。
- 说明：真实 Agent HTTP 调用已在前一节通过（HTTP 200、3,890ms、36 字符）；本轮未重复真实付费调用。

**影响范围：**
仅测试与校验逻辑收尾，不改变对外接口与转写记录机制。

**遗留问题：**
Java 接口端到端仍受 MySQL 凭据/Redis/Docker 守护进程限制；浏览器 WebM/Opus、长录音、真实 TTS 未验证。

### [2026-11-20] 真实 ASR 完成度复核

**任务目标：**
检查真实 ASR 接入的当前实现、已有验证证据与尚未完成的验收项。

**执行行为：**

1. 阅读 QwenAsrProvider、SpeechService、Agent 生命周期与真实测试、Java 转写服务与调用客户端、前端语音提交和恢复逻辑。
2. 复跑语音相关测试，显式设置 RUN_REAL_ASR=0，未重复真实付费调用。
3. 核对现有过程记录与 Java Surefire 报告；真实 Agent 路由测试采用 ASGITransport，不包含 Java、真实 TCP 服务和数据库完整链路。
4. 发现待收尾项：Agent 路由 await audio.read() 仍无读取上限；前端 retryAnswerWithSameContent 仅重传 content，默认转为 TEXT 并遗漏转写关联；Provider 将供应商鉴权失败转为 502，但 Java 仅将 401/403 视为 AUTH，502 最终被归为 TIMEOUT。

**文件变更：**
仅追加本过程记录，未修改业务代码、配置、依赖或测试文件。

**执行命令与验证：**

- 命令：`RUN_REAL_ASR=0 python -m pytest -q -p no:cacheprovider tests/test_speech_api.py tests/test_speech_config.py tests/test_speech_fake.py tests/test_speech_service.py tests/test_speech_qwen.py tests/test_speech_api_real.py tests/test_speech_qwen_real.py`，使用 `.local-dev-env/venv-asr/Scripts/python.exe`，工作目录 `project/agent`。
- 结果：允许本地回环访问的执行环境中通过，退出码 0；43 项离线用例通过，2 项真实调用用例跳过。
- 说明：首次受限执行长时间无输出，主动中止；重新执行后完成。本轮未验证该无输出的确切根因。
- 命令：`git -c core.fsmonitor=false diff --check`。
- 结果：通过（追加本段记录前的检查）。
- 已有证据：Java VoiceAudioValidationTest 报告 4 项通过，VoiceConcurrencyGuardTest 报告 2 项通过；本轮未重新运行 Maven。

**影响范围：**
只做完成度检查和离线测试，不改变运行行为。

**遗留问题：**
需完成 Java 对外接口至供应商及转写提交的完整联调，核查当前数据库/Redis 环境状态；WebM/Opus 实录和长录音尚待验证。以上代码收尾项尚未修复，TTS 仍为 Fake。


### [2026-11-20] 联调环境排查与 Redis 配置补齐

**任务目标：**
排查 Docker、MySQL 和 Redis 对完整语音接口联调的阻塞。

**执行行为：**
1. 允许本机状态访问后确认 Docker 引擎管道不存在、docker-desktop WSL 发行版停止；已启动现有 Docker Desktop。
2. Compose 因 REDIS_PASSWORD 未设置而无法解析；向本地 .env 追加随机生成的 Redis 密码，未记录或输出其值，未修改已有数据库密码。
3. 本机另有 MySQL 服务监听 3306；项目配置端口为 3307，应验证项目容器而非把 3306 的登录失败认定为项目密码错误。

**文件变更：**
- 修改 .env：追加本地 Redis 密码配置。
- 修改本过程记录：记录排查与配置步骤。

**执行命令与验证：**
- docker version、docker compose ps -a、wsl --list --verbose：初次检查确认引擎未启动且 Compose 缺少 Redis 密码。
- Start-Process Docker Desktop.exe -WindowStyle Hidden：启动进程成功，引擎就绪尚待确认。
- Python dotenv_values 与 secrets.token_hex：补齐本地 Redis 密码，未打印密钥值。

**影响范围：**
仅本机开发配置和服务启动，未修改业务代码，未删除数据卷。

**遗留问题：**
继续验证引擎启动、原 MySQL 数据卷凭据和 Redis 连接。

### [2026-11-20] 联调环境根因确认

**任务目标：**
区分项目数据库密码问题、非项目 MySQL 服务，以及 Docker/Redis 启动阻塞。

**执行行为：**

1. 以真实用户权限检查 Docker 注册信息：HKCU 与 HKLM 的 Software/Docker Inc./Docker Desktop 均不存在。Docker Desktop 启动日志和 docker desktop start --timeout 45 均报告无法找到该注册表项。程序启动立即退出，Docker Linux 引擎管道不存在，WSL docker-desktop 为 Stopped。
2. 用项目已有 aijob 凭据执行只读 SELECT 1：3307 返回 MySQL ERROR 2003（无法连接）；另一个原生 MySQL 在 3306，使用同一项目账号返回 ERROR 1045。后者不是项目配置的数据库，不能据此认定项目数据卷密码错误。密码仅通过子进程环境变量传入，未输出或写入命令行。
3. 端口探测：3306 开放；3307、6379、8080、8100 未提供服务。Redis 未运行，Compose 原先又缺 REDIS_PASSWORD，已在上一阶段补齐。
4. Docker 的 docker_data.vhdx 与 ext4.vhdx 文件仍存在。未卸载、重置、注销 WSL、删除数据卷或手工更改安装注册表。
5. 参考 Docker 官方 Windows 安装、权限和备份文档，后续修复安装前应保留现有数据；本次任务仅完成排查和本地 Redis 配置修复，没有执行重装。

**文件变更：**
仅追加本过程记录；本轮唯一运行配置变更为上一阶段向 .env 补齐 Redis 密码。

**执行命令与验证：**

- docker desktop start --timeout 45：失败，安装注册表项不存在。
- Python socket.create_connection 与 mysql --no-defaults --protocol=TCP --connect-timeout=3 ... -e SELECT 1：确认端口与错误码，未读取业务数据。
- Get-ItemProperty、Docker 启动日志、Get-ChildItem *.vhdx：确认注册项缺失与原虚拟磁盘存在。

**影响范围：**
未修改业务代码、MySQL 密码、数据库数据或 Docker 数据卷；未执行模型调用。

**遗留问题：**
需保留/备份原 Docker 数据后修复 Docker Desktop 安装注册，启动原项目 MySQL 与 Redis，再验证 3307 凭据、Redis PING 和后端启动。项目 MySQL 的密码与迁移状态尚无法验证，完整语音链路联调仍未完成。

- 补充验证：docker compose config --quiet 退出码 0；补齐 Redis 密码后配置可解析（无需运行引擎）。

### [2026-11-20] Docker 修复前数据备份

**任务目标：**
按用户授权修复 Docker/MySQL/Redis，先保存原有 Docker 数据。

**执行行为：**
1. 确认 Docker 后端未运行；复制原 docker_data.vhdx（2903506944 字节）与 ext4.vhdx（100663296 字节）到 `.local-dev-env/docker-repair-backup-20261120/`。
2. 两个源文件与备份分别计算 SHA256，均一致；另保存 Docker settings-store.json。
3. 验证现有 Docker Desktop 4.98.0 安装包 Authenticode 签名，状态 Valid，签名者 Docker Inc。

**文件变更：**
新增本地备份目录和虚拟磁盘/设置副本；追加本过程记录。备份含本地服务数据，不应提交 Git。

**执行命令与验证：**
Copy-Item、Get-FileHash、Get-AuthenticodeSignature：通过。

**影响范围：**
仅备份，未删除原虚拟磁盘、数据卷或业务数据。

**遗留问题：**
继续以原 per-user/WSL2 安装方式修复 Docker Desktop 注册信息。

### [2026-11-20] Docker Desktop 安装修复完成

**任务目标：**
恢复缺失的 Docker 安装注册，保留原 WSL 数据。

**执行行为：**
1. 使用官方签名安装包按 per-user/WSL2 路线启动修复；外层自解压程序退出不代表内部安装已结束，继续检查安装日志。
2. 并行尝试显式内部安装器返回 -6，未据此判定安装成功；随后确认原安装流程已完成解压、注册产品和 Validation，日志明确 Installation succeeded。
3. 原 Docker 数据已在前一步备份并校验；未执行卸载、WSL 注销或数据卷删除。
4. computer-use 初始化与重置后重试均报 failed to write kernel assets，未执行任何 UI 输入；实际安装结果以系统安装日志为准。

**文件变更：**
本地安装器状态文件与本过程记录。

**执行命令与验证：**
- Start-Process 官方安装器；读取 C:/ProgramData/DockerDesktop/install-log-admin.txt。
- 结果：安装日志明确 Installation succeeded，下一步仍需验证引擎和原容器状态。

**影响范围：**
修复本机 Docker Desktop 安装，不改业务代码。

**遗留问题：**
启动并验证引擎、MySQL 和 Redis。

### [2026-11-20] MySQL 与 Redis 环境恢复并验证

**任务目标：**
恢复项目数据库和 Redis，验证原凭据与数据卷。

**执行行为：**
1. 修复后 Docker 引擎版本 29.9.3，原 ai-job_mysql_data 卷与 MySQL 8.0.42 镜像存在，原 ai-job-mysql-1 恢复运行。
2. docker compose up -d --wait --wait-timeout 60 mysql redis：启动项目服务，按 Compose 获取 redis:7.4-alpine 并建立 ai-job_redis_data 卷。
3. 使用项目既有 aijob 凭据连接 127.0.0.1:3307/career_business，成功执行只读 SQL；MySQL 8.0.42，32 张表，Flyway 当前 V1，success=1。
4. 使用 .env 的 Redis 密码执行 AUTH/PING，分别返回 OK/PONG。

**文件变更：**
仅本地容器与 Redis 数据卷运行状态，以及本过程记录；未改数据库密码或业务代码。

**执行命令与验证：**
- docker compose up -d --wait --wait-timeout 60 mysql redis：退出码 0，两个服务 healthy。
- mysql SELECT VERSION(), DATABASE() 与 flyway_schema_history：登录成功，确认项目数据库原凭据有效。
- Redis AUTH/PING：通过。

**影响范围：**
恢复本机项目 MySQL/Redis；本机另一个 3306 MySQL 服务未改动。未删除或重建原 MySQL 数据卷。

**遗留问题：**
原库仅 V1，当前后端需要后续迁移；继续评估启动时迁移并验证后端运行。

### [2026-11-20] 后端启动与本地数据库迁移完成

**任务目标：**
验证环境恢复后当前 Java 后端可以正常连接项目数据库并启动。

**执行行为：**
1. 尝试 mysqldump 逻辑备份，首次使用客户端不支持的 connect-timeout 参数失败，输出文件为 0 字节，未启动后端；移除该参数后备份成功，保存 career_business_before_migration.sql（66041 字节）。
2. 使用现有 .local-dev-env/start-backend.ps1 启动后端，为 Java 设置项目本地 Unix domain 临时目录；未使用 Windows 管理员身份运行项目代码。
3. Flyway 校验 15 个迁移通过，由 V1 执行至 V15，14 个待执行迁移成功。原有数据卷未重建，结构与历史数据按项目迁移脚本更新。
4. 日志显示 Tomcat started on port 8080，Started BackendApplication，数据库连接池建立成功。
5. 备份目录增加本地 .gitignore，防止数据库备份被误提交。

**文件变更：**
新增本地逻辑数据库备份和备份目录 .gitignore；现有本地启动日志/PID 更新；追加本过程记录。未修改业务代码或迁移脚本。

**执行命令与验证：**
- mysqldump --single-transaction --no-tablespaces --set-gtid-purged=OFF career_business：退出码 0。
- .local-dev-env/start-backend.ps1：已启动，后端 Java PID 30888。
- 后端日志：Successfully applied 14 migrations ... now at version v15；Started BackendApplication。

**影响范围：**
本地 career_business 数据库按项目现有 V2-V15 升级，后端监听 8080。备份保留于本地目录。

**遗留问题：**
继续进行最终服务健康与只读数据库验证。未在本轮执行真实 ASR/完整面试提交验收。

### [2026-11-20] 环境修复最终验收

**任务目标：**
确认此前阻碍完整 ASR 联调的本地服务环境已恢复。

**执行行为与验证：**
1. docker compose ps：MySQL 与 Redis 均 healthy，仅绑定 127.0.0.1:3307 与 127.0.0.1:6379。
2. 项目凭据登录 MySQL 成功：flyway_schema_history 共 15 条，15 条 success=1，最新 V15；interview_voice_transcription 与 interview_voice_consent 两张表均存在。
3. Redis AUTH 返回 OK，PING 返回 PONG。
4. GET http://127.0.0.1:8080/api/v1/interviews 返回 HTTP 401，证明后端接口可访问且未登录认证生效；不代表面试业务链路已验收。

**文件变更：**
追加本过程记录及本地环境说明。

**影响范围：**
本机基础服务与 Java 后端保持运行。未改动另一套原生 MySQL 3306，未提交或推送 Git。

**遗留问题：**
Python Agent 实际服务启动及 Java→Agent→真实模型→转写保存→确认提交仍是下一阶段联调；本次未执行真实模型调用，未修复先前指出的业务代码收尾项。

### [2026-11-21] 统一语音配置与三处链路问题修复（待验证）

**任务目标：**
统一脚本和 Agent 的语音配置规则，修复语音重试关联丢失、Agent 无界读取和上游错误误报超时。

**执行行为：**
1. 新增 app/speech_config.py 共享解析器；开发使用 dotenv（环境回退），生产仅环境；相同字段的别名/来源值不同则拒绝运行，错误仅列来源。Agent Settings 与验证脚本复用此解析器，日志仅输出来源。
2. 实际检测到进程 DASHSCOPE_API_KEY 与项目 .env 冲突；尚未修改系统级环境，后续专用启动进程显式清理遗留值。
3. 前端使用原内容重试时传回原 answerSource/voiceTranscriptionId。
4. Agent 上传仅 read(max_bytes+1)，超限在 Provider 调用前返回 413，finally 关闭上传对象。
5. Java 解析 Agent 安全错误键，分别标记 AUTH/TIMEOUT/UPSTREAM/CONNECTION，不再将所有 RuntimeException 标记 TIMEOUT。
6. 新增配置来源/冲突测试、超限不调用 Provider 测试、Java 错误分类测试。

**文件变更：**
新增 app/speech_config.py、tests/test_speech_config_sources.py、SpeechAgentClientTest.java；修改 config.py、api.py、speech/router.py、scripts/verify_asr.py、test_speech_api.py、SpeechAgentClient.java、InterviewView.vue 和本记录。

**执行命令与验证：**
仅完成实现与静态检查；测试结果在下一阶段记录，暂不宣称通过。

**影响范围：**
语音配置加载、上传与错误处理、语音提交重试。不改数据库结构，不提交 Git。

**遗留问题：**
执行针对性测试、启动真实服务并联调。

### [2026-11-21] 针对性验证与首次完整联调

**任务目标：**
验证配置、链路修复并走 Java→Agent→真实模型→数据库→确认提交。

**执行行为：**
1. Python 语音测试 51 项通过；Java 针对性测试通过，新增过期版本不能绕过缓存校验用例。
2. 前端首次类型检查因既有 axios 缺失失败；按 package-lock 执行 npm ci --ignore-scripts 恢复依赖后，npm run type-check 退出码 0。Node18 对部分依赖有 engine 警告，尚未验证打包运行。
3. Java 首次回归在受限环境无进展，停止后在允许回环的环境通过；另一次因根目录无 POM 提前退出，明确 -f project/backend/pom.xml 后通过。
4. 启动专用 Agent：仅清理子进程旧语音密钥，使用项目 dotenv；ASR=qwen，面试生成=fake，TTS=fake。后端重启并连接 V15 库。
5. 首次联调因假设存在公共岗位失败，尚未调用模型；改为创建独立合成岗位/简历/面试数据。
6. 第二次联调真实 ASR 返回 HTTP200，Java端耗时4750ms，数据库 provider=qwen；重复上传复用、错误版本/题目拒绝、过期转写拒绝、确认提交202、VOICE来源/消费关联、回答幂等均通过。
7. 后续 Fake 面试生成回调未完成：测试夹具使用数据库不存在的 JAVA 技能代码，造成 user_skill_snapshot 外键失败；非 ASR 失败。初次结果保存 voice-e2e-results-initial.json；调整后续夹具不附带不存在技能，不修改真实用户数据。

**文件变更：**
新增配置来源模块与回归测试、错误分类测试，修改上传/重试/缓存锁逻辑；新增本地 Agent 启动脚本和联调脚本；追加本记录。

**执行命令与验证：**
pytest 语音测试：51项通过；Maven 指定四组测试通过；npm ci 与 npm run type-check 通过；本地 verify-voice-e2e.py 核心 ASR/提交通过，Fake 后续任务因夹具缺陷未通过。

**影响范围：**
仅本地合成测试账号和夹具、真实 ASR 调用一次；未记录密钥、Token 或转写全文，未提交 Git。

**遗留问题：**
使用修正夹具重跑并完善服务异常验证；浏览器实录与长录音仍待验证。

### [2026-11-21] 修复后完整联调通过与服务故障验证

**任务目标：**
确认真实 ASR、数据库转写、确认提交与异常流程贯通。

**执行行为：**
1. 修正合成夹具：使用独立私有岗位和简历，题目不引用不存在的技能。真实账号注册/登录、隐私同意、Java→Agent→真实 ASR、转写落库、确认提交、VOICE 来源/消费关联均通过。
2. 同一音频再次上传复用原转写；错误版本、错误题目、过期转写返回409；相同回答幂等重放未生成第二条回答。
3. 面试后续任务使用 Fake LLM，任务最终 SUCCEEDED。本轮成功 ASR 的 Java 请求耗时781ms；此前一次4750ms，两次不是P95数据。
4. 故障验证先核对8100进程及本次启动PID归属；首次路径保护检查提前退出未停止进程，核对父PID后才停止专用 Agent。新夹具上传返回503，reason=CONNECTION，数据库转写状态FAILED。finally 已重启 Agent。
5. 前端依赖恢复后类型检查通过；Java四组测试共12项通过（3+2+4+3），Python语音测试51项通过。鉴权502与超时区别由Java回归用例验证，未故意更改真实密钥进行付费故障调用。

**文件变更：**
- 业务代码：配置共享解析、Agent有界读取、Java错误分类、会话锁内转写复用、前端保留语音重试关联。
- 新增回归测试和本地联调脚本/结果文件。
- 更新 `.env.example`、语音 design.md 与本过程记录，说明新配置与错误语义。

**执行命令与验证：**
- .local-dev-env/verify-voice-e2e.py：正常联调13项检查全部通过，结果位于 voice-e2e-results.json。
- VOICE_E2E_UNAVAILABLE=1 同脚本：5项检查通过，结果位于 voice-e2e-unavailable-results.json；未触发模型调用。
- 首次核心提交成功但夹具技能缺失造成后续任务失败的结果仍保留 voice-e2e-results-initial.json，不覆盖历史失败证据。

**影响范围：**
使用独立合成测试账号、岗位、简历和面试记录，真实 ASR 本轮合计两次调用；测试结果不含Token/密钥/转写全文。后端8080与专用Agent8100保持运行，Agent面试模型为Fake、ASR为qwen。

**遗留问题：**
浏览器WebM实录、长录音和多样本性能尚待验证，真实TTS不属于本轮；未提交或推送Git。

### [2026-11-21] 总题数预算与后端结束规则：文档修订与设计实验

**任务目标：**
推进语音面试第一阶段——把"主问题数量上限"修订为"包含追问的总提问数量上限"，并明确后端强制结束、历史兼容、验证实验与后续任务。本轮**只读代码、只改文档、只做纸面推演**：不改业务代码、不创建或执行数据库迁移、不调用真实模型、不提交或推送 Git。

**执行行为：**

1. 读取规范与基线：`AGENTS.md`、`spec.md`、`design.md`、`tasks.md`。
2. 核对现状（以代码为准，已记录行号）：
   - `InterviewService.create` 将 `questionLimit` 限定为 3..10（默认 5），`turnPayload` 只传 `questionLimit` 与 `maxFollowUps=2`，无已提问数/剩余额度。
   - `InterviewResultHandler.turn` 校验 `questionNo=current+1`、`followUpNo=current+1<=2`，并用 `answeredNo >= limit && action != FOLLOW_UP` 自动结束；模型选择追问时总题数可超过上限。
   - `InterviewMapper` 无提问计数查询；`interview_message` 的 `ASSISTANT` 行只由出题路径写入。
   - Python `InterviewTurnHandler` 用 `question_no < question_limit` 判断是否出下一主问题；追问只受 `maxFollowUps` 限制；`InterviewTurnPayload` 无计数相关字段。
   - `interview_turn` 提示词为 `interview-turn-v5`，规则仍写"主问题数受 questionLimit 限制"。
   - 数据库 `interview_session.question_limit CHECK 3..10`，无计数规则版本字段；`V15` 为当前最大迁移。
   - 前端创建表单为"主问题数量（3–10）"，会话头为"N 个主问题"，历史列表为"计划 N 个主问题"，报告为"有效回答主问题数"。
3. 更新 `spec.md`：修订 1.4 职责边界、新增 7 个术语、补充 4.2/4.5 一致性与兼容要求、修订 5.5.1/5.6.1、新增 5.9"总提问数量预算与结束"（业务规则、验收条件、交互流程、6 类异常）、修订 6.1 数据约束、补充 7.9/7.10 待确认项与 8.6–8.9 验收基线。
4. 更新 `design.md`：修订 1.1.1/1.1.2 中"题数上限"的差异描述与匹配度、修订 6.6 设计自检，新增第八章（现状差异、计数依据取舍、历史兼容与 `V16` 设计稿、Agent 输入与决策、后端硬约束与幂等并发、接口与页面、状态流转、A–N 实验矩阵、2 主 + 3 追问推演、文件清单、未执行事项与风险）。
5. 更新 `tasks.md`：任务总数 53 → 59，里程碑总览新增 Q 阶段，第 9 章"不做"清单拆分修订，第 10 章追溯矩阵补 A16–A18 与 5.9，第 11 章新增 O12/O13，第 12 章补 Q 阶段开工顺序，新增第 13 章 Q-01～Q-06（全部标记"待实现"）。
6. 设计结论：以数据库实际提问记录（`ASSISTANT` 行数）为权威计数；`questionLimit` 语义由会话级 `question_count_rule`（历史 `MAIN_ONLY` / 新会话 `TOTAL_ASKED`）解释；后端在会话锁内复核剩余额度，额度为 0 时先保存评价再结束并生成报告；新增 `pending_task_id` 校验防止旧结果写题。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `.codeartsdoer/specs/voice_interview/spec.md` | 新增增量说明与 5.9 节；新增"提问/总提问上限/已提问数/剩余额度/计数规则版本/最后一题"术语；修订 1.4-1、4.2、4.5、5.5.1、5.6.1、6.1、7、8 章 | 将"主问题数量限制"修订为"含追问的总提问数量限制"，并明确计数、结束顺序与历史兼容 |
| 修改 | `.codeartsdoer/specs/voice_interview/design.md` | 修订 1.1.1/1.1.2/6.6；新增第八章（计数方案、V16 设计稿、Agent 契约、后端约束、接口页面、状态图、A–N 矩阵、完整推演、风险） | 给出可实施的技术设计，并覆盖旧"主问题上限"表述 |
| 修改 | `.codeartsdoer/specs/voice_interview/tasks.md` | 任务总数改为 59；新增 Q 里程碑行；修订第 9 章冲突条目；补充第 10/11/12 章；新增第 13 章 Q-01～Q-06（待实现） | 把后续实现拆成可领取任务，且不覆盖既有编号与进度 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加本记录 | 遵守开发过程记录规范 |

**执行命令与验证：**

- 命令：`Read` / `Grep` 读取 `AGENTS.md`、三份规格文档及 Java/Python/前端/迁移源码；未执行编译、测试、迁移、模型调用或 Git 提交。
- 结果：静态阅读与核对完成；**未执行**任何运行态验证。
- 说明：本轮 A–N 实验与"2 主 + 3 追问"推演全部为设计预期，不能视为测试通过。

**影响范围：**
仅修改规格、设计与任务文档及本过程记录；未改业务代码、接口实现、数据库结构、配置、依赖，也未运行服务。`V15` 未被修改，`V16` 未创建。

**遗留问题：**
1. Q-01～Q-06 全部待实现；需按 Q-01 → Q-02 → Q-04 → Q-03 → Q-05 → Q-06 推进。
2. `V16` 仅为设计稿，未在隔离库执行；回滚脚本与历史数据分布需实测。
3. O12（历史未完成会话是否切换规则，建议不切换）与 O13（报告是否新增含追问口径，建议新增）待产品确认。
4. `pending_task_id` 校验、Python"取更严格值"防御逻辑、报告 `answeredAskCount` 口径均需在实际编码与测试中验证。
5. A–N 矩阵未实际执行；真实模型未调用。
### [2026-11-21] 总题数预算：实现方案设计（spec-design 阶段）

**任务目标：**
在上一阶段已完成的 `spec.md` 5.9、`design.md` 第八章与 `tasks.md` Q-01～Q-06 基础上，产出可直接指导编码的实现方案，覆盖计数依据、历史兼容、Agent 契约、后端硬约束、接口与前端字段、状态流转、实验与验收落点。本轮**不修改业务代码、不创建/执行迁移、不调用真实模型、不提交 Git**。

**执行行为：**

1. 读取上一阶段文档：`spec.md`（483 行）、`design.md`（1949 行）、`tasks.md`（1060 行）、`AGENTS.md`，以及本过程文档 2026-11-21 既有记录。
2. 复核实际代码（以当前工作区为准）：
   - Java：`InterviewService`（创建 :62-98、回答 :114-217、报告任务 :237-255、`sessionVo` :270-287、`turnPayload` :311-325）、`InterviewResultHandler`（`turn` :86-146、评价 :149-174、报告 :177-241）、`InterviewMapper`（会话读写 :30-34、`page` :38-48、`insertMessage` :73-83、`latestQuestion` :103-104）。
   - 数据库：`V1` 会话表 :409-434、消息表 :436-456（含 `idx_interview_id_role_seq`）、评价 :458-473、报告 :475-490；`V15__add_voice_interview.sql` 已存在（83 行），最大迁移为 V15。
   - Python：`handlers.py` 面试轮次 :1131-1253（评价 :1203-1218、追问 :1220-1232、主问题 :1234-1246、结束 :1248-1253）；`schemas.py` `InterviewTurnPayload` :827-836、`InterviewMessage` :804-812、`InterviewReportPayload` :862-867；提示词 `interview_turn.md` 版本 `interview-turn-v5`。
   - 前端：`InterviewView.vue` 创建表单 :634-637、会话头 :650、报告 :728、历史 :803、`canAnswer` :96-104；`api/types.ts` `InterviewVO` :802-818。
   - 任务与幂等：`TaskResultService.submit` :46-119、`TaskService.create` 的 `INTERVIEW_REPORT` 抵扣 :69-74、`IdempotencyService.executeTask` :35-41。
3. 更新 `design.md` 第八章（在既有结构内新增实现级小节，不另起矛盾章节）：
   - 8.2.4 计数查询 SQL、Mapper 签名、索引依据、三个读取点、为何必须"锁后立即读"、并发/恢复/回滚。
   - 8.3.5 `V16` 脚本与执行约束、`insertSession` 改造、规则读取、报告字段、新旧会话解释矩阵。
   - 8.4.5 payload JSON 形态与字段表、Python schema、有效额度算法、`TOTAL_ASKED` 判定顺序、提示词改动点。
   - 8.5.4 类/方法/行号级改造点清单；8.5.5 事务边界、幂等与失败组合矩阵。
   - 8.6.3 响应示例、DTO 位置、前端类型与展示方案、禁止改动项。
   - 8.7.1 状态实现伪代码与"最后一题已发出/已回答"判定表。
   - 8.8.1 A–N → 测试文件/方法/断言映射；8.9.1 可执行的验证步骤与校验 SQL。
   - 8.10.1 推荐编码顺序；8.11 补充本轮未执行事项与 4 类新增风险（快照边界、编号上限、报告任务抵扣、提示词版本）。
4. 更新 `tasks.md`：新增 13.1"实现落点与验收对应"，把 Q-01～Q-06 的文件行号、测试落点与关键断言回填；**未改动任何任务编号、状态与既有验收标准**（全部仍为待实现）。
5. 设计结论（与上一阶段一致并细化）：以 `interview_message` 中 `role='ASSISTANT'` 的实际行数为唯一权威计数；历史会话按 `MAIN_ONLY`、新会话按 `TOTAL_ASKED` 解释 `questionLimit`；后端在会话行锁内、`evaluateAnswer` 之后复核额度，额度为 0 时保存评价并进入 `NORMAL` 结束；新增 `pending_task_id` 校验阻止旧结果写题；不新增计数列、不新增幂等表。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `.codeartsdoer/specs/voice_interview/design.md` | 第八章新增 8.2.4、8.3.5、8.4.5、8.5.4、8.5.5、8.6.3、8.7.1、8.8.1、8.9.1、8.10.1；更新 8.10 文件清单与 8.11 未执行/风险 | 把设计草案升级为可指导编码的实现方案 |
| 修改 | `.codeartsdoer/specs/voice_interview/tasks.md` | 新增 13.1 实现落点与验收对应（Q-01～Q-06） | 同步实现要点与测试落点，保留编号与待实现状态 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加本记录 | 遵守开发过程记录规范 |

**执行命令与验证：**

- 命令：`Read` / `Grep` / `sed` 读取规格文档与 Java/Python/前端/迁移源码；未执行编译、建库、迁移、模型调用或 Git 操作。
- 结果：静态核对完成；**未执行**任何运行态验证。
- 说明：8.8.1 的测试文件与命名、8.9.1 的 SQL 与命令均为设计预期，不能视为测试通过。

**影响范围：**
仅修改设计与任务文档及本过程记录。未改业务代码、接口实现、数据库结构、配置、依赖；未启动服务；`V15` 未被修改，`V16` 未创建。

**遗留问题：**
1. Q-01～Q-06 仍全部待实现；编码顺序 Q-01 → Q-02 → Q-04（并行）→ Q-03 → Q-05 → Q-06。
2. `askedQuestions` 必须在 `turn` 事务中作为第一条普通 SELECT 执行（避免 RR 旧快照少计），需在代码评审中核对调用顺序。
3. `pending_task_id` 校验与 `failure`/`prepareRetry` 的既有缺口是否一并加固，待 Q-02 评审决定。
4. O12（历史是否切换规则，建议不切换）、O13（报告 `answeredAskCount` 口径，建议新增）待产品确认。
5. 报告任务并发额度抵扣依赖 `latestResourceTask` 语义，需由 `SevenCaseIntegrationTest` 固定。
6. 真实模型、隔离库迁移、并发与接口用例均未执行。
7. 既有 `InterviewResultHandlerTest` 只 stub 了会话状态与编号字段，新增 `pending_task_id` 校验后必须同步补 stub（否则既有用例失败）；该影响已写入 `design.md` 8.5.4(6) 与 `tasks.md` 13.1 Q-02 行。
### [2026-11-21] 总题数预算：编码任务规划（spec-task 阶段）

**任务目标：**
把 `spec.md` 5.9 与 `design.md` 第八章已定的"总题数预算与后端结束规则"转化为可直接领取执行的编码任务清单；只产出/修订 `tasks.md`，本轮**不修改业务代码、不创建或执行数据库迁移、不调用真实模型、不提交 Git**。

**执行行为：**

1. 读取上一阶段文档：`spec.md`（483 行，重点 5.9/6.1/7.9-7.10/8.6-8.9）、`design.md`（2409 行，重点第八章 8.1–8.11 与 8.8.1/8.9.1）、`tasks.md`（1076 行，重点第 13 章 Q-01～Q-06 与 13.1）、`AGENTS.md`，以及本过程文档 2026-11-21 既有三条记录。
2. 复核真实代码（以当前工作区为准，记录实测行号）：
   - Java：`InterviewMapper`（`insertSession:18-28`、`lockSession:33-34`、`page:38-48`、`insertMessage:73-83`、`latestQuestion:103-104`）、`InterviewService`（`create:62-98`、`answer:114-217`、`end:219-235`、`reportTask:237-255`、`sessionVo:270-287`、`turnPayload:311-325`）、`InterviewResultHandler`（`failure:62-73`、`prepareRetry:76-83`、`turn:86-146`、`report:176-241`）。
   - 基线确认：全仓库无 `askedQuestions`、`question_count_rule`、`askedCount`、`remainingQuestions`、`answeredAskCount` 实现；迁移最大版本仍为 `V15`，`V16` 不存在；`InterviewQuestionBudgetTest` 等测试文件不存在。
   - 测试现状：`InterviewResultHandlerTest` 仅 45 行、单个用例，stub 未含 `pending_task_id`/`question_count_rule`；`InterviewServiceTest` 两个用例；`SevenCaseIntegrationTest:131-135` 手工插入会话且未写 `pending_task_id`，`:147` 直接回调 `INTERVIEW_TURN`。
   - Python：`schemas.py` `InterviewMessage:804`、`LatestAnswer:815`、`InterviewTurnPayload:827`、`InterviewReportPayload:862`；`handlers.py` `InterviewTurnHandler:1131`、`prepare:1137-1156`、编号 `:1206`、追问分支 `:1220-1232`、主问题 `:1234-1246`、FINISH `:1248-1253`；`operations.py:128` 为 `interview-turn-v5`；提示词 `interview_turn.md:5/:16` 仍为 v5 表述；`tests/test_handlers.py:943-1019` 为面试区段，`factories.py:306-367` 为两个 envelope。
   - 前端：`api/types.ts` `InterviewVO:802-818`、`InterviewReportVO:851-861`；`types/workbench.ts:237-246`；`mock/workbench.mock.ts:153-158`；`InterviewView.vue` `canAnswer:96-104`、校验文案 `:305-306`、创建表单 `:634-637`、会话头 `:650`、报告 `:728`、历史列表 `:803`。
3. 修订 `tasks.md`：
   - 头部增量说明补充"36 个可领取子任务"与本轮只修订文档的边界；第 1 章里程碑总览 Q 行补子任务数与完成口径。
   - 新增 `13.0 任务总览与状态`：Q-01～Q-06 的状态、硬依赖、涉及文件、可否并行、主要阻塞点，并定义"待实现/完成"判定口径。
   - 在 Q-01～Q-06 每个任务下新增"可领取子任务"共 36 项（Q-01.1～Q-01.4、Q-02.1～Q-02.9、Q-03.1～Q-03.5、Q-04.1～Q-04.6、Q-05.1～Q-05.5、Q-06.1～Q-06.7），全部为 `- [ ]` 未勾选，每项含依赖编号、文件与类/方法（行号级）、实现要点、可断言验收标准、验证方式。
   - 保留 Q-01～Q-06 原有编号、状态、实现要点、验收标准与追溯，不删除既有内容；13.1 落点表补充"子任务五要素见各任务列表"的索引说明，并把 `turn` 行号由 `:87-145` 校正为实测 `:86-146`。
   - 新增 `13.2 推荐执行顺序与并行度`：Q-01 → Q-02（字段冻结）→ {Q-03, Q-04} → Q-05 → Q-06；列出三条并行工作线（Java/Python/前端）与四处强串行点、三条禁止顺序。
   - 新增 `13.3 回归与风险任务`：R-01（既有 `InterviewResultHandlerTest` 补 stub）、R-02（本轮新发现：`SevenCaseIntegrationTest` 手工插会话缺 `pending_task_id`，新增校验后必失败）、R-03（`failure`/`prepareRetry` 是否加固待评审）、R-04（`askedQuestions` 锁后立即读评审点）、R-05（`canAnswer` 守护）、R-06（`String.valueOf(null)` 得到 `"null"` 需按 `MAIN_ONLY` 解析）、R-07（报告任务额度抵扣）、R-08（提示词版本）、R-09（行号漂移）、R-10（历史 `MAIN_ONLY` 回归）。
   - 新增 `13.4 一致性检查`（12 项 spec/design 对照，全部"一致"，另列 3 条需注意项）与 `13.5 本轮边界与未执行事项`。
4. 在 `design.md` 8.10.1 表前补充一条任务映射引用（Q-01.1～Q-06.7 共 36 项、与 tasks 13.2 顺序等价），**未改变任何设计结论**。
5. 任务规划结论：计数权威值仍为 `interview_message` 中 `role='ASSISTANT'` 行数；规则版本 `MAIN_ONLY`（历史默认）/`TOTAL_ASKED`（新会话显式写入）；后端在会话锁内、`evaluateAnswer` 之前读计数、之后判额度；额度为 0 时先保存评价再结束并建报告任务；新增 `pending_task_id` 校验；不新增计数列、幂等表或业务接口。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `.codeartsdoer/specs/voice_interview/tasks.md` | 头部增量说明；第 1 章 Q 行；新增 13.0、Q-01～Q-06 下 36 个子任务、13.2、13.3、13.4、13.5；13.1 索引说明与 `turn` 行号校正 | 把任务概要细化为可领取、可验收的执行单元 |
| 修改 | `.codeartsdoer/specs/voice_interview/design.md` | 8.10.1 表前新增一条任务映射引用（36 个子任务、顺序等价） | 维持设计到任务的单向可追溯，不改设计结论 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加本记录 | 遵守开发过程记录规范 |

**执行命令与验证：**

- 命令：`Read` / `Grep` 读取规格文档、过程记录与 Java/Python/前端/迁移源码；未执行编译、建库、迁移、模型调用或 Git 操作。
- 结果：静态核对完成；**未执行**任何运行态验证。
- 说明：13.2 的顺序、13.3 的回归判定、13.4 的"一致"结论均基于文档与源码静态比对；A–N 实验与"2 主 3 追问"推演仍为设计预期，不能视为测试通过。

**影响范围：**
仅修改任务文档、设计文档中的一条映射引用与本过程记录。未改业务代码、接口实现、数据库结构、配置或依赖；未启动服务；`V15` 未被修改，`V16` 未创建，Q-01～Q-06 状态保持"待实现"。

**遗留问题：**

1. Q-01～Q-06 及 36 个子任务全部待实现，需按 Q-01 → Q-02（字段冻结）→ {Q-03, Q-04} → Q-05 → Q-06 推进。
2. `V16` 仅在隔离库验证后才算完成；回滚演练与历史行分布仍需实测。
3. R-02（`SevenCaseIntegrationTest` 手工插会话缺 `pending_task_id`）是本轮新发现，必须在 Q-02.9 一并处理，否则新增校验会让该集成用例失败。
4. R-03（`failure`/`prepareRetry` 是否补 `pending_task_id`）与 O12/O13 仍需评审或产品确认；未定前按"不加固/不切换历史规则/新增报告字段"执行。
5. 真实模型、隔离库迁移、并发与接口用例均未执行；行号存在漂移风险（R-09），编码前需复核。
### [2026-11-21] 总题数预算：任务执行（编码实现）

**任务目标：**
按 `.codeartsdoer/specs/voice_interview/tasks.md` 第 13 章 Q-01～Q-06 落地"总提问数量预算与后端结束规则"的编码实现，覆盖数据库规则字段、Java 锁内额度复核与结束顺序、Agent 契约与提示词、前端计数展示、单元/幂等/接口验收用例。执行前先读取 `spec.md`、`design.md`、`tasks.md`、`AGENTS.md` 与实际源码。

**执行行为：**

1. **Q-01 历史兼容**：新增 `V16__add_interview_question_count_rule.sql`（`question_count_rule VARCHAR(16) NOT NULL DEFAULT 'MAIN_ONLY'` + CHECK，历史行零回填、`V15` 未改）；新增 `QuestionCountRule` 枚举与宽松解析（缺失/空串/`"null"`/未知值 → `MAIN_ONLY`，`remaining=max(0, limit-asked)`）；`InterviewMapper.insertSession` 增加该列，`InterviewService.create` 显式写 `TOTAL_ASKED`。
2. **Q-02 Java 硬约束**：新增 `InterviewMapper.askedQuestions`（`COUNT(*) ... role='ASSISTANT'`）；`turnPayload` 增加 `questionCountRule/askedCount/remainingQuestions` 三个写入点（首题、新回答、重试覆盖且不改 `inputVersions`）；`answer` 在锁内读取规则与已提问数但**不校验额度**；`InterviewResultHandler.turn` 增加 `pending_task_id == task.id` 校验，并把 `askedQuestions` 放在状态校验之后、`evaluateAnswer` 之前；`TOTAL_ASKED` 下 `asked >= limit` 时跳过题目结构与编号校验，先保存评价再 `reportTask(NORMAL)`；`MAIN_ONLY` 保留原 `answeredNo >= limit && action != FOLLOW_UP` 分支；`sessionVo` 与列表 `page` 输出 `questionCountRule/askedQuestionCount/remainingQuestionCount`。
3. **Q-03 结束与报告**：保持"评价在结束之前"；`reportTask` payload 增加 `questionCountRule/askedQuestionCount`；`report` 增加 `answeredAskCount`（按有效评价的 `question_message_id` 去重），`answeredQuestionCount` 语义不变，`MAIN_ONLY` 不写 `answeredAskCount`；幂等继续复用任务 digest 与三个唯一键，未新增幂等表。
4. **Q-04 Agent**：`InterviewTurnPayload` 增加 `question_count_rule`（默认 `MAIN_ONLY`）、`asked_count`（默认 0）、`remaining_questions`（默认 None）；`prepare` 计算 `asked_effective=max(asked_count, ASSISTANT 消息数)` 与 `remaining_effective`；`validate_business` 增加 `TOTAL_ASKED` 分支（额度 0 → FINISH 且丢弃题目；否则追问/下一题/兜底），`MAIN_ONLY` 原分支逐行保留；提示词与 `PROMPT_VERSIONS` 同步升级 `interview-turn-v6`。
5. **Q-05 前端**：`types.ts`、`workbench.ts`、mock 增加计数三字段与报告口径；创建表单改为"总提问上限（含追问，3–10）"；会话头按规则显示"已提问 3 / 5（含追问）"或" N 个主问题"；末题提示"这是最后一题，提交后生成报告"；历史列表与报告按规则分支；`canAnswer` 未加入额度条件。
6. **Q-06 测试**：新增 `InterviewQuestionBudgetTest`（9 个用例：A/B/C/E/F/J/K/N + 报告口径）、`InterviewQuestionBudgetConcurrencyTest`（I 近似 + H 幂等）、`scripts/interview_budget_acceptance.py`（D/G/L 与 10 步）；同步 `InterviewResultHandlerTest`、`InterviewServiceTest` 的 stub；`SevenCaseIntegrationTest` 回调前补 `pending_task_id`；Python `factories.py` 增加可选字段、`test_handlers.py` 增加预算用例、`test_operations.py` 断言提示词 v6。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `project/backend/src/main/resources/db/migration/V16__add_interview_question_count_rule.sql` | 会话级计数规则列与 CHECK | Q-01 历史兼容 |
| 新增 | `project/backend/src/main/java/com/aijob/backend/interview/QuestionCountRule.java` | 枚举 + 宽松解析 + 剩余额度 | Q-01.3 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/interview/InterviewMapper.java` | `insertSession` 增列；新增 `askedQuestions`；`page` 增 `asked_count` 子查询 | Q-01.2 / Q-02.1 / Q-02.8 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/interview/InterviewService.java` | 写 `TOTAL_ASKED`；payload 三字段；锁内读取；`sessionVo` 三字段；报告 payload 两字段 | Q-01.2 / Q-02 / Q-03.2 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/interview/InterviewResultHandler.java` | `pending_task_id` 校验、额度耗尽分支、编号校验、先评价后结束、`answeredAskCount` | Q-02.4～Q-03.3 |
| 修改 | `project/agent/app/schemas.py` | `InterviewTurnPayload` 三字段 | Q-04.1 |
| 修改 | `project/agent/app/handlers.py` | `prepare` 有效额度；`TOTAL_ASKED` 分支 | Q-04.2 / Q-04.3 |
| 修改 | `project/agent/app/operations.py` | `interview-turn-v6` | Q-04.5 |
| 修改 | `project/agent/app/skills/prompts/interview_turn.md` | 版本与总上限规则 | Q-04.5 |
| 修改 | `project/frontend/src/api/types.ts`、`src/types/workbench.ts`、`src/api/mock/workbench.mock.ts` | 计数三字段与报告口径 | Q-05.1 |
| 修改 | `project/frontend/src/views/interview/InterviewView.vue` | 文案、会话头、末题提示、报告追加项、历史分支 | Q-05.2～Q-05.4 |
| 新增 | `project/backend/src/test/java/com/aijob/backend/interview/InterviewQuestionBudgetTest.java` | 预算单元用例 | Q-06.1 |
| 新增 | `project/backend/src/test/java/com/aijob/backend/interview/InterviewQuestionBudgetConcurrencyTest.java` | 并发近似 + 幂等 | Q-06.2 / Q-06.3 |
| 新增 | `project/agent/scripts/interview_budget_acceptance.py` | D/G/L 与十步验收 | Q-06.5 |
| 修改 | `InterviewResultHandlerTest`、`InterviewServiceTest`、`SevenCaseIntegrationTest` | 补 `pending_task_id`/`question_count_rule`/`askedQuestions` 前置 | Q-02.9 |
| 修改 | `project/agent/tests/factories.py`、`test_handlers.py`、`test_operations.py` | 可选预算字段、预算用例、v6 断言 | Q-04.6 / Q-06.4 |
| 修改 | `.codeartsdoer/specs/voice_interview/tasks.md` | 新增 13.6 实施进度；勾选 Q-05.1、Q-06.1 | 记录实际进度 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加本记录 | 遵守过程记录规范 |

**执行命令与验证：**

| 命令 | 结果 |
|------|------|
| `mvn -o -q compile`（`project/backend`） | 通过（无输出） |
| `mvn -o -Dtest="InterviewQuestionBudgetTest,InterviewQuestionBudgetConcurrencyTest,InterviewResultHandlerTest,InterviewServiceTest" test` | 通过：15 个用例，Failures 0，Errors 0 |
| `mvn -o -Dtest="InterviewEvidenceTest" test` | 通过：3 个用例 |
| `mvn -o -Dtest="SevenCaseIntegrationTest" test` | Tests run 6，全部 Skipped（未连数据库） |
| `npx vue-tsc --noEmit`（`project/frontend`） | 通过（无错误） |
| `python -m pytest project/agent/tests/test_handlers.py -q -k "budget or total_asked or main_only"` | 未执行：`No module named pytest` |
| `py -3.11` / `py -3.10` | 未执行：本机无可用 3.10/3.11 运行时 |

**影响范围：**
新增会话的 `questionLimit` 语义由"主问题数"变为"主问题 + 追问总数"；历史会话（`MAIN_ONLY`）的展示、编号校验与结束行为保持不变。会话详情/列表新增三个计数字段，报告新增含追问口径字段（仅 `TOTAL_ASKED`）。未新增业务接口、计数列或幂等表；`V15` 未修改。

**遗留问题：**
1. `V16` 未在 MySQL 隔离库执行，列/CHECK/回滚顺序与历史行分布待实测。
2. Python 用例未运行（无 pytest 与 3.10+ 运行时），`TOTAL_ASKED` 分支仅静态审阅。
3. 并发实验 I 只有"pending_task_id 已被覆盖"的近似单测，真实双事务争抢未验证。
4. `scripts/interview_budget_acceptance.py` 未执行（无运行中后端/数据库/Agent）。
5. `SevenCaseIntegrationTest` 全部 skipped，R-02 修复只保证编译通过。
6. O12（历史未完成会话是否切换）、O13（`answeredAskCount` 口径）仍待产品确认；当前按"不切换 + 新增字段"实现。

### [2026-11-21] 总题数预算六项交付完成度复核

**任务目标：**
按用户列出的六项交付核查现有文档、实现和验证证据，不修改业务实现。

**执行行为：**
1. 读取 spec/design/tasks 第8/13章及Java、Agent、前端实现。现状对比、COUNT权威计数、MAIN_ONLY/TOTAL_ASKED历史兼容、A-N矩阵、2主3追问完整推演、任务顺序和O12/O13风险均有文档。
2. 复跑当前源码的Java预算/面试回归，18项通过；npm run type-check通过。
3. 检查现有虚拟环境：.local-dev-env/venv-asr/Scripts/python.exe 为 Python3.12.4，已安装pytest9.2.0。原记录的“无可运行Python/pytest”不适用于此环境。
4. 使用该环境复跑Python面试/预算测试，5个新预算用例失败，原因均为调用async prepare()未await，得到coroutine后下标访问。未修改用例，不能宣称Python预算验证通过。
5. 接口验收脚本仍有缺口：仅用X-User-Id且未声明local profile与显式开发认证前置；提前结束请求缺必需Idempotency-Key；未覆盖末题时用passed=True标记skipped；未断言最终报告与最后评价确实生成，也未自动执行完整2主3追问十步或真实并发。
6. 只读探测本机项目MySQL端口连接返回2003，8080与8100不可访问；本次不能核实V16实际应用状态，未启动服务或执行迁移。

**文件变更：**
仅追加本过程记录。未修改业务代码、测试、任务勾选、数据库或配置。

**执行命令与验证：**
- Maven：-o -q -Dtest=InterviewQuestionBudgetTest,InterviewQuestionBudgetConcurrencyTest,InterviewResultHandlerTest,InterviewServiceTest,InterviewEvidenceTest test；18项通过，退出码0。
- npm run type-check：通过，退出码0。
- Python：RUN_REAL_ASR=0，使用现有venv运行 pytest -q -p no:cacheprovider tests/test_handlers.py tests/test_operations.py -k 'budget or total_asked or main_only or interview'；退出码1，5项失败（TypeError: coroutine object is not subscriptable）。仅在子进程清理遗留语音密钥，不调用真实模型。
- MySQL只读查询与HTTP探测：服务当前不可达，未查询到迁移状态。

**影响范围：**
文档交付基本齐全，代码已有实现；运行验收不完整，尤其Python用例、V16隔离库、真实并发和完整接口报告链路。

**遗留问题：**
优先修复Python异步用例和接口脚本，再恢复测试服务并在隔离库验证V16及A-N矩阵。O12/O13仍按文档的临时决定处理，不能标记已确认。需把tasks中的旧环境说明和实施/验收状态区分清楚。

### [2026-11-21] 修复预算测试与接口验收脚本

**任务目标：** 修复总题数预算验证缺口。

**执行行为：** 修复 5 个测试未 await 异步 prepare 的问题；接口验收改为正常登录、携带幂等键、完整等待任务及报告，区分提前结束和五题完整流程。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/agent/tests/test_handlers.py | 异步测试标记和 await | 避免协程未执行 |
| 修改 | project/agent/scripts/interview_budget_acceptance.py | 鉴权、幂等、完整报告校验及独立结果文件 | 防止跳过关键步骤仍误报通过 |

**执行命令与验证：** 使用 .local-dev-env/venv-asr 的 Python 运行 pytest，筛选 test_handlers.py、test_operations.py 的预算与面试相关测试；23 项通过。实际环境为 Python 3.12.4，已有 pytest，先前缺少 Python 的结论不再适用。

**影响范围：** 测试和验收工具，未调整业务数据。

**遗留问题：** Docker 启动状态待确认；V16 隔离迁移、真实事务并发和接口完整验收尚未完成。

---

### [2026-11-21] 建立隔离数据库并补充真实事务验证

**任务目标：** 不依赖故障 Docker 继续验证预算迁移和并发。

**执行行为：** Docker 日志提示 Virtual Machine Platform 未启用，未修改系统组件或重启电脑；使用现有 MySQL 8.0.41 程序在工作区新目录初始化仅监听 127.0.0.1:3308 的独立实例。创建 career_business_test 和 budget_migration_test 专用库，设置账户密码；原 3306/3307 数据未改动。为正常登录接口验收临时下载 tporadowski/redis v5.0.14.1 便携包，使用 6380、回环监听和密码；该版本仅供隔离测试，不代表团队 Redis 7.4 环境验收。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | project/backend/src/test/java/com/aijob/backend/interview/InterviewQuestionBudgetIntegrationTest.java | V15→V16 历史数据、约束、移除约束再移列，以及真实双事务争抢最后额度 | 补齐真实数据库证据 |
| 修改 | project/backend/src/test/java/com/aijob/backend/task/SevenCaseIntegrationTest.java | 测试 JDBC 地址可从 BUDGET_TEST_DB_URL 指定，保留隔离库名断言 | 支持独立端口 |

**执行命令与验证：** 18 项预算及面试 Java 单元测试通过；首次隔离集成运行 10 项 SevenCase 用例通过，新增测试暴露测试夹具缺少 ai_task 外键行、CHECK 3819 异常翻译与预期类型不同，已修正测试夹具及精确错误码断言，重跑中。

**影响范围：** 测试文件和独立临时服务，未更改生产配置。

**遗留问题：** 新增集成测试及完整接口流程等待结果；Docker 系统条件尚未恢复。

---

### [2026-11-21] 隔离迁移与并发通过，完成五题 API 联调

**任务目标：** 验证真实事务和完整五题结束链路，修复验收误判。

**执行行为：** 新增集成用例与 SevenCase 在 3308 隔离 MySQL 上强制执行，12 项通过、0 skipped。覆盖 V1→V15、插入历史会话、V16、历史默认 MAIN_ONLY、CHECK 拒绝 UNKNOWN、NOT NULL 拒绝空值、先 DROP CHECK 再 DROP COLUMN。真实两个线程使用独立事务同时回调，恰一方成功、一方 INVALID_STATE，最终 5 条提问和 1 条评价，重复回调不新增。独立后端 8081、Fake Agent 8101、Redis 6380 正常鉴权联调五题，得到 5 条评价、1 份 NORMAL 报告，36 个断言通过。Python 完整 test_handlers.py/test_operations.py 共 111 项通过。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/agent/scripts/interview_budget_acceptance.py | 提前结束先回答一题，新增 no-answer 场景；覆盖换键重复提交与完成后再提交的 409 | 首次提前结束无回答时按既有规则返回 NO_VALID_ANSWER，不能误判成 EARLY_END；拆开两种业务状态 |
| 修改 | project/backend/src/test/java/com/aijob/backend/interview/InterviewQuestionBudgetIntegrationTest.java | 校验并发失败方具体错误键 INVALID_STATE | 防止其他异常被当作并发保护成功 |

**执行命令与验证：** mvn -o -Dintegration-tests=true -Dtest=InterviewQuestionBudgetIntegrationTest,SevenCaseIntegrationTest test：12 项通过；venv Python -m pytest -q -p no:cacheprovider tests/test_handlers.py tests/test_operations.py：111 项通过；隔离 run_e2e.py 调用正式验收脚本 full：36 项通过。提前结束与无回答独立场景正在重跑。

**影响范围：** 验收脚本、数据库测试及独立服务。未调整正式业务规则或生产数据库。

**遗留问题：** 浏览器重录、重播、音频格式及真实语音供应商不在本轮题数预算联调范围；Docker 未恢复，未验证团队 Redis 7.4。

---

### [2026-11-21] 补齐验收脚本回归与最新文档状态

**任务目标：** 固定误报修复，提供可追溯的完成范围。

**执行行为：** 增加脚本回归，证明起始已到末题不能跳过验收、报告未完成不能报成功、无回答结束保留 NO_VALID_ANSWER。最终 API 分别验证 full/early/no-answer，36/7/6 项断言通过，数据库评价数 5/1/0，每场报告 1 份。SHOW CREATE TABLE 断言补齐列类型、默认值、约束及回滚后列消失；数据库测试凭据改从 Spring 已解析配置读取。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | project/agent/tests/test_interview_budget_acceptance.py | 3 个验收脚本回归 | 防止验收误报再发生 |
| 修改 | project/backend/src/test/java/com/aijob/backend/interview/InterviewQuestionBudgetIntegrationTest.java | SHOW CREATE TABLE、错误键、Spring 凭据 | 精确验证数据库行为与便于复现 |
| 修改 | project/backend/src/test/java/com/aijob/backend/interview/InterviewQuestionBudgetConcurrencyTest.java | 指向真实集成测试 | 避免把 Mock 当成真实并发 |
| 修改 | .codeartsdoer/specs/voice_interview/tasks.md | 13.7 最新证据、任务状态实施中、Q-06.2/Q-06.4 勾选、复现命令 | 修正未运行 Python、未迁移等过时状态 |
| 修改 | .codeartsdoer/specs/voice_interview/design.md | 区分历史设计基线，新增 8.12 当前验证 | 避免文档与实现矛盾 |
| 修改 | .codeartsdoer/specs/voice_interview/spec.md | 拆开误合并的验收条目 5/6 | 修复排版 |
| 修改 | docs/功能资料/09-模拟面试/语音模拟面试-回滚与清理.md | V16 隔离演练与发布回退边界 | 不把 DROP 列演练误称完整发布回滚 |

**执行命令与验证：** 最终 mvn -o -Dintegration-tests=true 指定 7 个相关类：30 项通过、0 失败/错误/跳过；Python Handler/Operation 111 项及脚本回归 3 项均通过；npm run type-check 退出码 0；git diff --check 无空白错误（仅既有 CRLF 转换提示）。EXPLAIN 选择 uk_interview_id_seq，按 interview_id 索引查找；未加索引提示或更改业务计数方案。

**影响范围：** 测试、验收工具、文档；未修改 V15、未对原业务库迁移、未提交或推送 Git。

**遗留问题：** 浏览器媒体全流程、报告失败重试完整矩阵、目标 Docker MySQL/Redis 组合、真实 TTS 尚未验收。O12/O13 产品待决保留。临时服务待停止，测试证据保留。

---

### [2026-11-21] 关闭临时测试服务并保留证据

**任务目标：** 完成修复收尾，避免临时 Fake 服务影响后续开发。

**执行行为：** 核对进程命令后停止本轮 8081 后端、8101 Agent；向独立 MySQL 3308 发出 SHUTDOWN，向隔离 Redis 6380 发送已认证 SHUTDOWN NOSAVE。Redis 关闭时 TCP 返回连接重置，随后检查四个端口均无监听，确认服务已停止。删除本轮 PID 标记，保留数据库文件、便携依赖与验收证据。

**文件变更：** 更新本过程记录；移除 .local-dev-env 下本轮三个临时 PID 标记，未移除源代码、测试结果或原业务数据。

**执行命令与验证：** Get-NetTCPConnection 检查 8081/8101/3308/6380，四个临时端口均已关闭；最终 Git 空白检查与文档索引核对。

**影响范围：** 仅本轮临时服务。原有开发服务端口、正式库与团队 Docker 配置未更改。

**遗留问题：** tasks.md 13.7 所列浏览器、报告失败重试矩阵、Docker 部署组合及真实 TTS 待后续推进；本轮未提交 Git。

---

### [2026-11-21] 根据 Docker 截图开始修复 Windows 虚拟机平台

**任务目标：** 用户明确授权修复截图中的 Virtual Machine Platform 未启用错误。

**执行行为：** 核对截图与既有日志；当前进程非管理员，Get-WindowsOptionalFeature 返回需要提升，WSL 提示当前配置不支持 WSL2。准备管理员脚本，仅检查并启用缺失的 VirtualMachinePlatform、Microsoft-Windows-Subsystem-Linux，使用 NoRestart，保留原状态及新状态；只读采集虚拟化和启动配置，不主动重启或更改 BIOS/启动项。

**文件变更：** 新增 .local-dev-env/repair-docker-platform.ps1，结构化记录每项组件的前后状态与重启需求；新增本地忽略的 docker-platform-repair 结果目录。

**执行命令与验证：** 非管理员只读查询失败（需要提升），不视为组件已启用。下一步启动管理员脚本并读取结果。

**影响范围：** 拟修复 Docker 所需 Windows 可选功能；未改动业务代码、数据库或 Docker 数据卷。

**遗留问题：** 等待提升权限后的真实组件状态、启用结果及重启要求。

---

### [2026-11-21 22:01 +08:00] 已启用虚拟机平台，等待 Windows 重启

**任务目标：** 修复 Docker 截图中确认的 Windows 组件缺失。

**执行行为：** 用户授权后，通过 Start-Process -Verb RunAs -WindowStyle Hidden 启动受限范围的组件修复脚本。管理员执行成功：VirtualMachinePlatform 从 Disabled 变为 EnablePending，RestartNeeded=true；Microsoft-Windows-Subsystem-Linux 原为 Enabled，保持不变。只读检查显示 VirtualizationFirmwareEnabled=true、支持 SLAT，HypervisorPresent=false；本轮未更改 BIOS 或引导配置，未主动重启。

**文件变更：** 新增的 .local-dev-env/repair-docker-platform.ps1 已实际运行；本地结果 .local-dev-env/docker-platform-repair/platform-repair-20261121-220050.json；更新 .local-dev-env/ENVIRONMENT.md 及 tasks.md 的最新环境状态。

**执行命令与验证：** 管理员 Get-WindowsOptionalFeature / Enable-WindowsOptionalFeature -All -NoRestart，脚本 succeeded=true、error=null、restartRequired=true；组件查询与固件虚拟化状态有结构化报告。尚未将 Docker、MySQL、Redis 标记恢复。

**影响范围：** 启用了 Docker/WSL2 所需的 Windows 虚拟机平台，重启后生效；未修改业务代码、数据库和 Docker 数据卷。

**遗留问题：** 需要用户保存工作并手动重启 Windows；重启后先复查组件与 WSL，再验证 docker info，随后启动并验证项目 MySQL 3307 / Redis 6379，继续使用隔离库做业务验收。

---

### [2026-11-21] Windows 重启后验证 Docker 与项目数据库恢复

**任务目标：** 接续用户重启后的修复工作，验证 Docker 引擎及 MySQL/Redis 实际可用。

**执行行为：** 读取前次修复记录；确认 Windows LastBootUpTime 为本地 22:03，HypervisorPresent=True。最初 Docker 命名管道不存在，执行 docker desktop start --timeout 50，随后 docker info 成功。执行 compose up -d --wait --wait-timeout 60 mysql redis，两容器 healthy。使用项目 .env 凭据通过 PyMySQL 连接 3307 业务库执行只读查询；通过本机 socket 验证 Redis 6379 未认证返回 NOAUTH、认证成功后 PONG，并读取版本。未输出凭据。

**文件变更：** 更新 .local-dev-env/ENVIRONMENT.md 和 .codeartsdoer/specs/voice_interview/tasks.md 最新环境结论；新增本地结构化报告 .local-dev-env/docker-platform-repair/post-reboot-services.json；追加本过程记录。

**执行命令与验证：** Docker Engine 29.9.3；MySQL 8.0.42 查询成功，career_business 共 46 表，Flyway 最新成功 V15；Redis 7.4.12 AUTH/PING 通过；compose ps 显示两容器 healthy。Docker 原启动故障已解决。

**影响范围：** 启动项目已有 Docker 服务并保留原数据卷；未执行迁移、未修改业务数据，未启动 Java/Agent/前端。容器保留运行供继续开发。

**遗留问题：** 专用测试库上的报告失败重试完整矩阵、浏览器录音、团队容器版本组合下的业务回归仍待推进；业务库尚未升级 V16。

---

### [2026-11-21] 开始报告失败重试矩阵修复

**任务目标：** 在 Docker 专用测试库验证报告失败、重试、并发、重复与旧回调。

**执行行为：** 创建并授权专用 career_business_test、budget_migration_test，未改业务库。发现 failure/prepareRetry/report 未统一校验待处理任务，已补会话行锁、当前任务与状态校验；旧失败回调忽略，旧成功/重试拒绝。新增真实数据库矩阵测试，控制回调而不调用付费模型。

**文件变更：** 修改 project/backend/src/main/java/com/aijob/backend/interview/InterviewResultHandler.java；新增 project/backend/src/test/java/com/aijob/backend/interview/InterviewReportRetryIntegrationTest.java。

**执行命令与验证：** 专用库准备成功；新测试尚待运行，不能宣称修复验证通过。

**影响范围：** 面试任务状态保护及测试；无正式库迁移。

**遗留问题：** 运行矩阵与既有回归后推进浏览器录音验收。

---

### [2026-11-21] 报告失败重试矩阵通过，准备浏览器真实格式验收

**任务目标：** 完成报告故障恢复验收，并验证浏览器产生的音频。

**执行行为：** 在 Docker MySQL career_business_test 执行新增矩阵及既有预算/SevenCase 回归：35 项通过、0 跳过。覆盖超时、不可用、限流、过期、原快照重试、同键重放/异键冲突、双线程争抢、重复成功/不同结果冲突、旧成功/失败/重试、刷新和畸形报告回滚恢复；始终保留五问题五回答五评价与最多一报告。启动浏览器验收时发现矩阵残留模拟 outbox 被调度尝试（400），立即切换浏览器到独立 career_voice_browser_test，避免场景相互影响。

**文件变更：** .local-dev-env/browser-validation 安装 Playwright 和临时 Vite 代理配置，代理至专用 8081 后端；真实 ASR Agent 使用 8101，LLM Fake、TTS Fake；临时 HTTP cookie 设置仅在子进程环境生效。

**执行命令与验证：** mvn -o -Dintegration-tests=true 指定 6 类，35 项全通过，日志 .local-dev-env/logs/report-retry-matrix.log。Playwright 当前依赖需 Node>=20，系统 Node18 不能直接运行，正在使用 Codex 自带 Node 运行时。

**影响范围：** 业务状态防护与专用测试环境；未迁移正式库。

**遗留问题：** 浏览器验收使用用户已提供的录音，通过虚拟麦克风输入，仍不等于真人物理麦克风验收；后续需如实区分。

---

### [2026-11-21] 浏览器 WebM/Opus 与真实 ASR 主链路通过

**任务目标：** 验证浏览器实际 MediaRecorder 音频容器与供应商输入兼容性。

**执行行为：** Playwright 使用 Codex 自带 Node 与现有 Edge，正常登录虚构测试账号；将用户已授权录音解码为 WAV 供虚拟麦克风输入，未开启物理麦克风、未安装 FFmpeg。真实 MediaRecorder 录制约 10 秒，audio/webm;codecs=opus，37274 字节，浏览器试听成功；先模拟一次上传 503 检查音频保留，再走真实 Java→Agent→Qwen ASR，获得 38 字候选文本。刷新恢复候选文本后修改确认，服务端保存 VOICE 来源及确认文本，随后只有一道新题；切换文字可用。

**文件变更：** 本地验收脚本与证据在 .local-dev-env/browser-validation/，未修改前端业务代码；新建 career_voice_browser_test 专用库，与报告矩阵库及正式库分离。

**执行命令与验证：** browser-acceptance.cjs 14 项断言全部通过；browser-acceptance.json、截图及真实 WebM 留作本地证据，不提交用户录音或账号令牌。启动 Node18 不兼容时改用已有运行时，未升级系统 Node。首次测试脚本读取会话函数名错误与复用已轮换刷新令牌造成登录失败，均为测试工具问题，已修正每次正常登录。

**影响范围：** 证明 Edge 浏览器真实 WebM/Opus 与当前 ASR 实现兼容，录音/试听/转写/刷新不消耗题数。面试生成模型仍 Fake，TTS 仍 Fake。

**遗留问题：** 不代表物理麦克风噪声质量或 Chrome/Safari 全浏览器支持；权限拒绝、短录音/重录及页面报告失败重试继续验证。

---

### [2026-11-21] 浏览器边界通过，并修复文字降级提交来源错误

**任务目标：** 验证录音异常降级及五题报告重试页面。

**执行行为：** Edge 浏览器级麦克风权限实际设为 denied，校验权限拒绝提示和切换文字可用；恢复权限后验证不足 2 秒录音禁用转写、重新录音后可转写，6 项断言通过，题数保持不变。继续从页面提交文字时发现 HTTP400：VoiceAnswerPanel 文字模式发空 transcriptionId，而父组件硬编码 VOICE。已显式传递 TEXT/VOICE 来源，并约束语音确认必须有转写 ID，文字模式无需转写 ID。

**文件变更：** 修改 project/frontend/src/views/interview/components/VoiceAnswerPanel.vue、project/frontend/src/views/interview/InterviewView.vue 的提交契约与语音确认条件。

**执行命令与验证：** browser-boundaries.cjs 6 项通过；browser-report-retry.cjs 首次因该实际缺陷失败，修复后重跑中。临时 fault_agent.py 仅在验收进程阻断报告，待浏览器点重试前放行；未增加正式环境故障开关。

**影响范围：** 语音面板内文字降级现在按 TEXT 提交，避免错误要求转写 ID；语音来源确认仍需合法转写。

**遗留问题：** 等待页面五题结束与失败报告刷新/重试验证，随后重跑语音路径及类型检查。

---

### [2026-11-21] 完成跨浏览器、报告页面重试与数据库核对

**任务目标：** 验证修复后真实语音及文字降级均可提交，报告失败可从页面恢复。

**执行行为：** 修复 TEXT/VOICE 后重跑 Edge 真实 ASR 14 项通过；新增 Chrome 同路径 14 项通过；权限/短录音/重录 6 项、五题与报告失败页面重试 10 项通过。数据库核对：5 提问、1 VOICE+4 TEXT、5 评价、唯一 NORMAL 报告；报告任务一 FAILED 一 SUCCEEDED。正式库 Flyway 仍 V15。扩大 Java 回归首次 40 项中 37 通过，3 条 Evidence 旧夹具缺 pending_task_id；补齐合法夹具后原 Evidence 3 项和 TaskRetryService 1 项通过，有效累计 41 项。前端类型检查通过。

**文件变更：** 修改 InterviewEvidenceTest.java 的会话夹具；新增 docs/功能资料/09-模拟面试/语音模拟面试-验收证据.md；更新 design.md 8.13、tasks.md 13.8 记录实际范围与保留未验收项。临时测试工具中的会话函数名、刷新令牌轮换、CDP 权限名称/级别、工作目录和实时页面更新竞态均已调整，失败过程未冒充产品通过。

**执行命令与验证：** report-retry-evidence.log 4 项通过；browser-acceptance.json 与 chrome-acceptance.json 各 14 项通过；browser-boundaries.json 6 项、browser-report-retry.json 10 项通过；database-evidence.json 保留聚合证据，不记录转写全文和凭据。

**影响范围：** 后端旧任务保护、前端文字降级；无生产数据迁移，未改全局 Node 或正式故障开关。

**遗留问题：** 物理麦克风、静音/长录音与更多浏览器/容器、真实 TTS、真实面试模型质量仍待验收；准备关闭临时服务并删除一次性登录凭据。

---

### [2026-11-21] 报告重试与浏览器验收收尾

**任务目标：** 清理临时故障注入及凭据，保留可核对证据。

**执行行为：** 核对端口进程命令后停止 8081 后端、8101 故障验收 Agent、5173 临时前端，确认三端口无监听。删除 browser-validation/session.secret.json、browser-state.secret.json、allow-report 及 PID 标记，保留忽略目录内音频和截图证据。Docker MySQL/Redis 保持 healthy。回写 tasks.md R-03 和 design.md 8.5.4 的原可选加固决策，明确现已通过验收。

**文件变更：** 更新任务/设计与本过程日志；删除上述本地一次性凭据和故障放行标记。

**执行命令与验证：** Get-NetTCPConnection 三临时端口均无监听；docker compose ps 两容器 healthy；git diff --check 无空白错误，仅换行转换提示。修改后前端 type-check 成功；本轮未提交或推送。

**影响范围：** 临时验收环境，不影响原 Docker 数据卷。

**遗留问题：** 参见验收证据中的真人麦克风、噪声、静音/长样本与其他容器、真实 TTS/面试模型质量等保留边界。

---
### [2026-11-22 01:03] ASR 结果晚到保护与真实数据库测试矩阵

**任务目标：**
补齐语音转写供应商返回后的业务状态二次校验，防止旧请求覆盖新状态、失效记录被晚到结果复活、重试后的旧尝试覆盖新尝试；并用真实 MySQL、真实事务和受控供应商替身覆盖并发与乱序矩阵。

**执行行为：**

1. 逐文件核对 `VoiceTranscriptionService`、`VoiceMapper`、`InterviewService.answer`、`VoiceTranscriptionSweeper`、前端 `useVoiceAnswer`/`VoiceAnswerPanel` 与既有验收证据。确认此前"上传前校验、同音频复用、同题 PENDING 互斥、过期/放弃/消费"已实现，但供应商返回后没有二次校验，`markSucceeded/markEmpty/markFailed` 均为无条件更新。
2. 新增 `V17__add_voice_transcription_attempt.sql`，为转写记录增加 `attempt` 识别尝试序号，不修改已执行的 V15。
3. `VoiceMapper` 将成功、空识别、失败、晚到丢弃、过期全部改为带 `id + attempt + status='PENDING' + consumed_at IS NULL (+ expires_at > NOW)` 的条件更新；`resetPending` 递增 `attempt` 并限定可重置状态；`markConsumed` 增加未过期条件；`markDiscarded` 不再把 EXPIRED 改写成 DISCARDED。
4. `VoiceTranscriptionService` 在供应商调用（事务外）返回后用短事务重新锁定会话，校验用户/会话归属、`WAITING_ANSWER`、会话版本、当前题目、记录状态、识别尝试、消费与过期状态。有效才回写；题变/会话结束落 `DISCARDED` 并返回 409 `STALE_INPUT`（`QUESTION_CHANGED`）；记录过期保持 `EXPIRED`（`TRANSCRIPTION_EXPIRED`）；旧尝试晚到不改任何记录。失败结果同样走该校验，失效失败不会覆盖新成功。
5. `InterviewService.answer` 检查 `markConsumed` 受影响行数，消费失败时抛 `STALE_INPUT` 并回滚，避免同一转写被两条正式回答消费。
6. 前端 `useVoiceAnswer` 将恢复轮询与转写请求统一到同一个请求令牌，切题/重录/重新识别均递增；异步返回先核对令牌与题目 ID，旧题结果不再覆盖当前编辑区，并提示"题目已变化，本次识别未生效"。识别失败保留录音，新增"重试识别"入口；`VoiceTranscriptionVO` 增加可选 `attempt`。
7. 新增 `VoiceLateArrivalIntegrationTest`：真实 MySQL（`career_business_test`）、真实事务、`@MockitoBean` 受控供应商（可阻塞和乱序放行），覆盖 9 个场景：正常保存一次、同音频并发只调用一次、另一标签页提交后旧结果失效、结束面试后不恢复、放弃后不复活、过期后保持失效、旧尝试成功不覆盖重试、旧尝试失败不覆盖新成功、重复确认提交只保存一条正式回答。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 新增 | `project/backend/src/main/resources/db/migration/V17__add_voice_transcription_attempt.sql` | 增加 `attempt` 列与 CHECK | 区分同一记录的多次识别尝试 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/interview/voice/VoiceMapper.java` | 条件回写、attempt、消费与放弃条件 | 旧请求不得覆盖新状态 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/interview/voice/VoiceTranscriptionService.java` | 短事务二次校验、失效落盘、错误映射 | 结果晚到保护 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/interview/InterviewService.java` | 检查 `markConsumed` 行数 | 防止重复消费转写 |
| 修改 | `project/frontend/src/composables/voice/useVoiceAnswer.ts` | 请求令牌、晚到提示、保留录音重试 | 旧题结果不覆盖新题 |
| 修改 | `project/frontend/src/views/interview/components/VoiceAnswerPanel.vue` | 晚到提示与"重试识别"按钮 | 用户可感知与恢复 |
| 修改 | `project/frontend/src/api/types.ts` | 转写 VO 增加 `attempt?` | 与后端字段对齐 |
| 新增 | `project/backend/src/test/java/com/aijob/backend/interview/voice/VoiceLateArrivalIntegrationTest.java` | 9 项真实数据库矩阵 | 并发、乱序、失效与幂等验证 |

**执行命令与验证：**

- 命令：`mvn -o -q -f project/backend/pom.xml -DskipTests test-compile`
- 结果：通过，退出码 0。
- 命令：`mvn -o -f project/backend/pom.xml -Dintegration-tests=true -Dsurefire.useManifestOnlyJar=false -Djacoco.skip=true -Dtest=VoiceLateArrivalIntegrationTest,VoiceConcurrencyGuardTest,VoiceAudioValidationTest,SpeechAgentClientTest,QuestionSpeechServiceTest,InterviewServiceTest test`
- 结果：通过；27 项通过，0 失败/错误/跳过，`BUILD SUCCESS`；其中新增晚到矩阵 9 项通过。
- 命令：`npm run type-check`（project/frontend）
- 结果：通过，退出码 0。
- 说明：首次运行矩阵时 seed 的 `resume_version.sha256` 误用 64 字符十六进制字符串（列为二进制 32 字节），修正为 `UNHEX(SHA2(...,256))` 后通过；另一用例因未插入 USER 消息导致断言错误，补插后通过。上述为测试夹具问题，不是业务代码失败。
- 说明：本机直接运行 Maven 会因 H:/I: 盘符与 manifest JAR 触发 surefire fork 提示，且 JaCoCo report 在 Windows 编码下报 `malformed input around byte 2`；使用 `-Dsurefire.useManifestOnlyJar=false` 与 `-Djacoco.skip=true` 后构建成功。JaCoCo 报告问题与本次功能无关，未修改项目构建配置。

**影响范围：**
新增 V17 迁移（测试库已升级到 V17，正式库未迁移）；转写回写由无条件改为条件更新，旧数据 `attempt` 默认 1；对外接口响应新增 `attempt` 字段（向后兼容）；确认提交新增消费失败保护。未调用任何付费供应商，未记录密钥、录音或转写全文，未提交 Git。

**遗留问题：**
1. 浏览器回归本轮未执行：需要 Node>=20 的 Playwright 运行时、后端/Agent/前端服务和专用浏览器测试库，当前仅完成编译与接口/数据库矩阵。
2. 未验证真人物理麦克风、噪声、回声、静音与超长/超大真实样本；既有证据仍来自虚拟麦克风。
3. 供应商无幂等键，跨网络故障仍不能承诺"绝对只调用一次"；当前语义是同音频成功复用、进行中不重复调用、显式重试递增 attempt。
4. JaCoCo 报告生成在本机失败，覆盖率未采集；未修改构建脚本规避。

---
## [2026-11-22] ASR 结果晚到保护：实现方案增量设计与既有代码核对

**任务目标：**
在不编码的前提下，为“语音识别结果晚到保护”产出与既有代码对齐的实现方案：明确状态机与允许操作、返回后二次校验的事务边界与顺序、条件更新语义与受影响行数、识别尝试号迁移与兼容、幂等/重试分层、前端令牌与提示、真实数据库测试矩阵，以及现有代码与设计的差异清单。

**执行行为：**

1. 完整读取 `.codeartsdoer/specs/voice_interview/spec.md`（484 行）、`design.md`（2431 行，重点第一、二章与 2.3/2.8/3.2/8.12/8.13）、`tasks.md`（1439 行，重点 13.7/13.8），确认上一阶段已提前落地 `V17`、条件更新、二次校验、前端令牌与 9 项真实库测试。
2. 逐文件核对实现：`VoiceTranscriptionService.java`（两段短事务、`evaluate` 九项校验、`Guard` 枚举、`settle` 白名单）、`VoiceMapper.java`（成功/空识别/失败回写与重置、放弃、消费、清理语句的 WHERE 条件）、`V17__add_voice_transcription_attempt.sql`、`InterviewService.answer`（`markConsumed` 行数校验与同事务回滚）、`SpeechAgentClient`（12 秒超时、无自动重试、`requestId` 派生）、`VoiceTranscriptionSweeper`、前端 `useVoiceAnswer.ts` / `VoiceAnswerPanel.vue` / `InterviewView.vue`、`VoiceLateArrivalIntegrationTest.java` / `VoiceConcurrencyGuardTest.java`。
3. 识别出 18 项实现与设计的偏差，其中判为 P0 的 4 项：已消费记录仍被同音频复用（D-01）、刷新恢复排序使用 `created_at`（D-02）、同题陈旧 `PENDING` 未收敛（D-06）、“另一标签页提交/结束”用例以 SQL 直改状态而非真实事务（D-13）；另识别应用回滚未关闭语音入口的覆盖风险（D-16）。
4. 在 `design.md` 新增第九章（9.0–9.13），覆盖全部交付项：`spec.md` 缺口以建议条款 `R-LA-1..5` 登记、状态机与允许操作矩阵、旧请求返回判定表、二次校验事务边界与固定校验顺序、条件更新语义表与行数处理、`attempt` 迁移与回滚约束、同题多 `PENDING` 收敛、幂等四层与“不承诺 exactly-once”、前端令牌与提示矩阵、18 项真实库测试矩阵（含同步屏障与乱序制造方式）、10 项浏览器回归、差异清单、文件级改动清单、20 项验收判定、风险与待确认项、关键决策汇总。
5. 同步修订原章节：文首章节导航新增第九章；2.3.2 登记 `V17` 增量列（不改 `V15` DDL）；2.8 把“同题只允许一个 `PENDING`”修正为“同 sha + 新鲜窗口互斥 + 超窗接管并收敛陈旧 `PENDING`”；3.2 的 F17 补充“返回后二次校验”与真实库验证方式。
6. 未修改任何 Java / Python / 前端业务代码，未执行迁移，未调用供应商，未提交或推送 Git。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `.codeartsdoer/specs/voice_interview/design.md` | 新增第九章（9.0–9.13）；文首导航加一行；2.3.2 登记 `V17`；2.8 修正同题 `PENDING` 口径；3.2 F17 补充二次校验与验证方式 | 输出本轮增量实现方案并如实登记既有实现与偏差 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加本条开发过程记录 | 遵守 AGENTS.md 记录规范 |

**执行命令与验证：**

- 命令：仅执行只读检索与读取（`ls`、`grep`、`sed -n`、`Read`），以及上述两处文档写入。
- 结果：文档写入成功；未执行编译、单元测试、集成测试与浏览器回归。
- 说明：本轮是设计阶段，按交付要求“不执行编码”。文中所有“待补/待验证”项均未执行，不得视为已通过。既有 `VoiceLateArrivalIntegrationTest` 9 项通过记录来自上一阶段日志，本轮未重跑。
- 说明：曾尝试用 shell heredoc 追加本记录，因 Windows git-bash 编码问题失败且未写入任何内容（文件行数保持 1165），随后改用文件编辑工具追加成功。

**影响范围：**
仅影响设计文档与过程记录。明确不改动：`V15`/`V16`/`V17` 已执行迁移、评分与报告链路、TTS/能力/限流模块、正式业务库。设计中提出的 `V18` 索引为条件新增（需 `EXPLAIN` 证据），前端 Vitest 为可选建议。

**遗留问题：**

1. `spec.md` 尚未收录识别尝试、条件回写、幂等分层五条需求（`R-LA-1..5`），当前以用户本次请求为依据，建议由需求阶段正式增补。
2. 差异清单中 P0 四项与回滚约束（D-16）尚未修复，需在编码阶段按 9.9 的收敛顺序处理。
3. 真实库矩阵缺 LA-09b、LA-10～LA-18，其中 LA-11（回写与提交真实争锁）为核心缺口；浏览器回归 BR-01～BR-10 未执行。
4. 供应商无幂等键，跨网络故障仍不能承诺“绝对只调用一次”；物理麦克风、噪声、回声与真实 TTS 仍未验收。

---
## [2026-11-22] ASR 结果晚到保护：编码任务规划（spec-task 阶段）

**任务目标：**
基于 `design.md` 第九章生成/增量更新 `tasks.md`，把"结果晚到保护"转化为可领取、可验收的编码任务；只做任务规划，不写业务代码、不执行迁移与测试。

**执行行为：**

1. 完整读取 `spec.md`（484 行）、`tasks.md`（1439 行，既有 M0–M3/T/Q 与 13.7/13.8 状态）、`AGENTS.md`、`语音模拟面试_process.md` 追加记录约定，以及 `design.md` 第九章 2447–3232 行与修订过的 2.3.2、2.8、3.2。
2. 对当前工作区做只读核对，确认既有实现与设计偏差：`VoiceMapper.findBySha` 无 `consumed_at` 过滤（D-01）；`findLatestByQuestion`/`findPendingByQuestion` 仍 `ORDER BY created_at DESC`（D-02）；`markFailedIfPending` 缺 `expires_at`（D-03）；`VoiceTranscriptionService:82-85` 只拦新鲜 `PENDING`（D-06）；`expired()` 用 JVM 时钟（D-17）；`InterviewService:166` 动态拼接 `TRANSCRIPTION_`+status（D-11）；`VoiceLateArrivalIntegrationTest` 的 LA-03/LA-04 仍用 SQL 直插消息、直改状态（D-13）；`auditRequestId` 只含记录 ID（D-05）；`:101` 版本来自记录 `interview_version`（D-07 可能只需注释）。
3. 在 `tasks.md` 新增第 14 章「M-LA：ASR 结果晚到保护」：9 个主任务（M-LA-01～09）+ 41 个可领取子任务，按"状态规则与差距确认 → 回写保护与 attempt 条件更新（含 D-01/02/06）→ 确认提交消费幂等 → 前端令牌与提示 → 真实 MySQL 测试矩阵（含修复 D-13）→ 浏览器回归 → 文档与过程记录"七阶段排序。
4. 每个子任务补齐：ID、角色、依赖、涉及文件与行号、实施要点、验收标准、验证方式、既有代码状态（完整实现/部分实现需修正/未实现需新增），并单列与既有 M1-09/M1-10/M1-11/M1-13/M1-20/M1-21/T-01/T-02/T-09/T-12/Q 系列/13.8 的映射与依赖。
5. 新增 14.1.1 主任务实施要素表、14.3 既有任务映射、14.4 关键路径与并行/禁止顺序、14.5 未覆盖风险与需人工执行项（U-01～U-11）、14.6 五项待用户决策（LA-D1～LA-D5，含临时口径与切换点）、14.7 实施边界、14.8 本轮边界声明。
6. 文件头部追加 2026-11-22 增量说明：累积主任务编号 68（原 59 + 新增 9）；既有任务编号、完成标记与状态一律保留。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `.codeartsdoer/specs/voice_interview/tasks.md` | 文首增量说明 + 新增第 14 章（约 470 行）：9 主任务 / 41 子任务、状态口径、总览、实施要素、映射、关键路径、未覆盖风险、待决策、实施边界、边界声明 | 把 design 第九章转化为可执行任务清单 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加本条过程记录 | 遵守 AGENTS.md 记录规范 |

**执行命令与验证：**

- 命令：只读检索与读取（`ls`、`grep -n`、`Read`、`wc -l`）；`grep -c -- "- \[ \] \*\*M-LA-" tasks.md`；`grep -o "\*\*M-LA-[0-9][0-9]\*\*" tasks.md | sort -u`。
- 结果：子任务计数 41、主任务编号 9（M-LA-01～M-LA-09），第 14.0～14.8 章节齐全，`tasks.md` 由 1439 行增至约 1908 行。
- 说明：本阶段按用户要求未修改业务代码、未创建/执行迁移、未调用供应商、未运行任何测试，未提交 Git；未执行项不得视为通过。

**影响范围：**
仅影响任务规划文档与过程记录。`design.md`、`spec.md` 与全部 Java/Python/前端代码、迁移脚本、正式业务库均未改动；既有任务状态与历史验收结论保留。

**遗留问题：**

1. 五项待用户决策（LA-D1～LA-D5）未定案，编码阶段按 14.6 临时口径执行并在切换点回改。
2. 真实库矩阵 LA-10/12/13/14/15/16/17/18 与 LA-09b/LA-11 仍未实现；浏览器 BR-01～BR-10 未执行。
3. `spec.md` 仍未收录 R-LA-1..5，需由需求阶段增补（跨阶段待办，不阻塞本轮）。
4. 浏览器真实 ASR 账号与次数上限（LA-D4）需用户提供，阻塞 BR 真实链路。

---
## [2026-11-22 11:35] ASR 结果晚到保护：编码执行（回写保护 + 真实库矩阵）

**任务目标：**
按 `tasks.md` 第 14 章实施 ASR 结果晚到保护：统一成功/空识别/失败三条回写路径的条件保护，区分识别尝试，收敛同题陈旧 PENDING，固定转写失效原因，完善前端旧结果提示，并用真实 MySQL + 受控供应商替身补齐并发、过期与重试用例。

**执行行为：**

1. `VoiceMapper`：`markFailedIfPending` 增加 `expires_at > UTC_TIMESTAMP(3)`；`findLatestByQuestion` / `findPendingByQuestion` 改为 `updated_at DESC, attempt DESC`，且 `latest` 排除已消费记录；新增 `discardStalePendingOfQuestion`，在接管前把同题超 60 秒窗口的其它 PENDING 落为 DISCARDED（不改 attempt、不触碰会话与消息）。
2. `VoiceTranscriptionService`：复用分支遇到已消费 SUCCEEDED 返回 `409 STALE_INPUT + TRANSCRIPTION_CONSUMED`，不再把文本当候选；tx1 插入/重置后收敛陈旧 PENDING；`evaluate` 移除 JVM 时钟过期判定，改由 SQL 条件与回读裁决；新增 `inspectAfterZeroRows`，0 行时区分 EXPIRED / CONSUMED / OLD_ATTEMPT / STALE，`GONE`/`OLD_ATTEMPT` 零落盘；`auditRequestId` 与日志加入 attempt，日志仍不输出转写文本。
3. 新增 `VoiceReasons`（NOT_FOUND/CONSUMED/DISCARDED/EXPIRED）；`InterviewService.answer` 不再用 `"TRANSCRIPTION_" + status` 拼接，过期判断改为 `expireIfStale` 后回读状态，`markConsumed` 0 行仍同事务回滚。
4. 前端 `useVoiceAnswer`：按 `ApiError.details.reason` 分流提示；`TASK_IN_PROGRESS` 静默转轮询现有结果；`SUPERSEDED` 静默；EXPIRED/CONSUMED/DISCARDED 保留录音并提示重录或改文字；`types.ts` 增加与后端一致的 reason 联合类型。
5. 测试：改造 LA-03 为真实 `interviewService.answer`、LA-04 为真实 `interviewService.end`；新增空识别晚到、放弃后重试、已消费重传、同题双 PENDING 收敛、过期三路径、V3 读路径排序、越权用户、不同幂等键并发提交共 8 项；新增 `VoiceAttemptMigrationIntegrationTest`（库名、列定义、默认值、CHECK、历史行）。
6. 更新 `tasks.md` 第 14.0 与新增 14.9 执行结果，追加本过程记录与本轮验收证据；未提交或推送 Git。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/backend/src/main/java/com/aijob/backend/interview/voice/VoiceMapper.java` | 失败回写补 expires 谓词；读路径改排序并排除已消费；新增陈旧 PENDING 收敛语句 | 统一条件保护、修正刷新恢复取到旧尝试 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/interview/voice/VoiceTranscriptionService.java` | 已消费不复用；0 行回读细分；SQL 唯一时间裁决；requestId/日志带 attempt | 旧请求不得覆盖新尝试、失效记录不得复活 |
| 新增 | `project/backend/src/main/java/com/aijob/backend/interview/voice/VoiceReasons.java` | 固定 4 个转写失效原因 | 禁止按状态拼接出前端未定义 reason |
| 修改 | `project/backend/src/main/java/com/aijob/backend/interview/InterviewService.java` | 替换 reason 拼接；过期改 SQL 裁决；删除 JVM 过期函数 | 前后端 reason 契约一致 |
| 修改 | `project/frontend/src/api/types.ts` | 新增 `VoiceTranscriptionReason` 联合类型 | 契约与后端对齐 |
| 修改 | `project/frontend/src/composables/voice/useVoiceAnswer.ts` | 按 reason 分流、TASK_IN_PROGRESS 轮询、SUPERSEDED 静默 | 旧题结果不覆盖编辑区并给出准确提示 |
| 修改 | `project/backend/src/test/java/com/aijob/backend/interview/voice/VoiceLateArrivalIntegrationTest.java` | 2 项改真实路径，新增 8 项，补夹具与断言 | 真实事务矩阵，修复 SQL 直改状态的证据缺口 |
| 新增 | `project/backend/src/test/java/com/aijob/backend/interview/voice/VoiceAttemptMigrationIntegrationTest.java` | V17 列/默认值/CHECK/历史行验证 | 迁移兼容证据 |
| 修改 | `.codeartsdoer/specs/voice_interview/tasks.md` | 14.0 说明 + 新增 14.9 执行结果 | 任务状态与未执行项如实登记 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试-验收证据.md` | 追加本轮矩阵与命令 | 证据归档 |
| 修改 | `docs/功能资料/09-模拟面试/语音模拟面试_process.md` | 追加本条记录 | 遵守 AGENTS.md |

**执行命令与验证：**

- 命令：`mvn -o -q -f project/backend/pom.xml -DskipTests test-compile`
- 结果：通过。
- 命令：`mvn -o -f project/backend/pom.xml -Dintegration-tests=true -Dsurefire.useManifestOnlyJar=false -Djacoco.skip=true -Dtest="Voice*IntegrationTest" test`
- 结果：20 项通过（`VoiceLateArrivalIntegrationTest` 17 项、`VoiceAttemptMigrationIntegrationTest` 3 项），0 失败/错误/跳过。
- 命令：`mvn -o -f project/backend/pom.xml -Dintegration-tests=true -Dsurefire.useManifestOnlyJar=false -Djacoco.skip=true -Dtest="VoiceAudioValidationTest,VoiceConcurrencyGuardTest,SpeechAgentClientTest,QuestionSpeechServiceTest,InterviewServiceTest" test`
- 结果：18 项通过。
- 命令：`npm run type-check`（`project/frontend`）
- 结果：通过。
- 说明：首次矩阵运行时“已消费重传”夹具因 `consumed_message_id` 外键失败（未插入真实消息），补齐消息后通过；不是业务代码失败。未执行浏览器回归与 `EXPLAIN`。

**影响范围：**
只影响语音转写回写、读路径、确认提交 reason 与前端提示；不改迁移版本（仍 V17）、不改会话状态机、总题数预算、评分与报告口径；正式库 `career_business` 未迁移。测试库 `career_business_test` 由 Flyway 升级到 V17。

**遗留问题：**

1. 浏览器 BR-01～BR-10 未执行：Node 18.19.0 不满足 Playwright 的 Node≥20，8080/8100/5173 未监听，验收账号需重新生成。
2. `EXPLAIN` 未采集，`V18` 索引未新增、未决策；并发用例仅单次通过，未做 3～5 次重复。
3. CHECK 拒绝 `attempt=0` 仅验证约束存在，未做插入级断言；真实库矩阵未对每个用例同时断言四类表。
4. 供应商无幂等键，跨网络故障不能承诺 exactly-once；物理麦克风、噪声、回声与真实 TTS 仍未验收。
5. `语音模拟面试-回滚与清理.md` 的 D-16 上线/回滚检查单未补。

---

### [2026-11-22] 审阅 ASR 晚到保护实施产物

**任务目标：** 核对用户提供的 V17、晚到矩阵与浏览器未执行说明，审阅当前实现。

**执行行为：** 只读审阅 VoiceTranscriptionService/VoiceMapper、V17、VoiceLateArrivalIntegrationTest/VoiceAttemptMigrationIntegrationTest 及 useVoiceAnswer。独立复跑语音集成20项、指定单元18项均通过，付费调用0次；类型检查另行完成。实际检测 Codex 自带 Node v24.22.3 可用，故系统 Node18 不是当前浏览器回归的硬阻塞。

**文件变更：** 新增本地 .local-dev-env/asr-review/repro.cjs，直接转译当前 useVoiceAnswer 并注入可控请求与最小响应式替身，验证切换文字的异步结果处理；未改业务实现。追加本记录。

**执行命令与验证：** mvn -o -Dintegration-tests=true -Dsurefire.useManifestOnlyJar=false -Djacoco.skip=true -Dtest=Voice*IntegrationTest test：20项通过；指定5类单测：18项通过。日志 asr-review-integration.log/asr-review-unit.log。最小逻辑复现：转写中切文字，输入“用户切换文字后刚输入的回答”，供应商返回后候选文本变为“旧录音的识别结果”，state=EDITING；已复现草稿覆盖。不是浏览器验收结果。

**影响范围：** 审阅与测试证据，未修改供应商或业务代码。

**遗留问题：** toTextMode 未使请求令牌失效；60秒新鲜度仍用 JVM 时间、收敛用 DB 时间，时钟偏差可能破坏单PENDING约束（静态风险，未用时钟偏移实测）；测试库断言在 Spring/Flyway 初始化之后且晚到类本身无库名断言，需前置保护；浏览器BR矩阵、真实迁移历史行与拒绝attempt=0的行为证据仍待补。

---

### [2026-11-22] 修复用户手工测试初始化失败

**任务目标：** 排查用户执行 seed.py 后首题 FAILED、账号文件不存在的问题，恢复手工录音测试。

**执行行为：** 专用库最新 INTERVIEW_TURN 为 FAILED，错误 MODEL_UNAVAILABLE、agent_run_id=NULL；端口检查8081/5173在运行、8101无监听。补启动正常 app.main Agent 于8101，ASR=qwen、面试LLM=fake、TTS=fake，回调8081，使用独立本地SQLite文件；未启动故障注入Agent。Windows PowerShell profile执行策略错误未阻止后续Python，属独立启动提示，未改变全局执行策略。

**文件变更：** 修改 .local-dev-env/browser-validation/seed.py：创建账号前检查8101 readiness；注册成功即保存一次性账号，创建首题后先保存interviewId/taskId再等待；任务失败打印安全错误码/任务ID，保留账号文件。新增本地 agent-manual-asr 日志，不输出凭据。

**执行命令与验证：** Agent /readyz HTTP200；重新执行seed.py首题成功，账号文件已生成。另核对前端代理及会话等待作答状态。该过程仅初始化Fake首题，未执行付费ASR调用。

**影响范围：** 本地测试脚本与Agent运行状态；用户已有8081/5173服务保留，账号文件留给用户手工测试，不提交Git。

**遗留问题：** 首个手工Agent为何退出无法由用户控制台现有信息确定；现在Agent已就绪，故障日志位于 .local-dev-env/logs/agent-manual-asr.err.log。前端已知草稿覆盖等代码问题不在本次启动排查中修改。

---

### [2026-11-22] 准备提交并推送独立语音识别分支

**任务目标：** 用户确认手工测试结束并明确要求上传 GitHub 分支。

**执行行为：** 核对当前 feature/voice-interview 与 origin；收集真实ASR、配置冲突检测、总题数预算、报告重试、V17晚到保护及测试/文档改动。使用独立 feature/voice-asr-hardening 分支承载本次成果。仅提交明确的源码、迁移、测试、配置示例和文档；新增本地环境/录音/私有文件忽略规则，不提交 .env、运行中的SQLite或账号凭据。

**文件变更：** 修改 .gitignore 排除本地开发环境、浏览器结果、私有上传文件与数据库产物；追加本记录。其他业务改动来自本次会话及用户确认的实施产物，保持既有实现。

**执行命令与验证：** 已核对 git status / diff、分支与远端；沿用此前独立复跑20项语音集成、18项相关单测和类型检查，以及用户本次手工测试反馈，不宣称全量后端回归已通过。提交前继续检查差异与敏感值。

**影响范围：** 准备Git提交；不合并主分支、不部署服务。

**遗留问题：** 上次审阅确认的文字草稿覆盖、双时钟风险、测试库前置校验和未执行BR矩阵仍按记录保留，不因本次推送而标为已修复或全部验收通过。远端推送结果待确认。

---

### [2026-11-22] 语音识别分支提交与推送成功

**任务目标：** 完成用户授权的 GitHub 分支上传。

**执行行为：** 创建 feature/voice-asr-hardening，按明确清单暂存64个源码/迁移/测试/配置示例/文档文件；与项目 .env 实际敏感值及高置信私钥模式比对未发现泄露。提交 3c3437a（feat: add real ASR and harden voice interview workflows）已创建。提交后 Git 自动维护延迟返回，另行执行带 gc.auto=0 的同分支推送，返回 Everything up-to-date 并成功设置上游，表明远端已有该提交。

**文件变更：** 本次提交64文件；SQLite运行数据、.env、录音、私有上传文件及测试凭据未纳入。追加本条发布记录，随后单独提交该文档记录并同步同一分支。

**执行命令与验证：** git push -u origin feature/voice-asr-hardening 成功；上游 origin/feature/voice-asr-hardening 已设置。后续核对远端最终HEAD与本地一致。

**影响范围：** 仅发布独立功能分支，无主分支合并、无部署与数据库迁移。

**遗留问题：** 既有审阅问题与未验收边界保持原记录，用户本轮手工测试不等于全量回归。

---
