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