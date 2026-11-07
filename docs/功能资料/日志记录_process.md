### [2026-11-06 20:12] 为 Java 后端与 Python Agent 追加全链路交互日志

**任务目标：**

让后端与 Agent 在程序运行终端中清晰记录每一次交互，覆盖 HTTP 请求、任务受理/执行、模型调用、回调、外部来源检索与最终状态，同时避免在日志中泄露简历原文、JD 原文、JWT、API Key 等敏感内容。

**执行行为：**

1. 重写 Agent 日志基础设施 `app/logging_config.py`：
   - 引入 `ContextVar` 日志上下文，统一注入 `trace/task/run/op/user` 字段；
   - 提供 `log_context()` 上下文管理器与 `bind_log_context()/reset_log_context()`；
   - 统一时间、级别、模块与上下文的控制台格式，交互终端启用级别颜色，支持 `NO_COLOR`；
   - 仅在交互终端（isatty）下重设为 UTF-8，避免 `conda run` 管道下中文乱码；
   - 新增 `summarize_payload()`，只输出字段名、类型、长度、列表长度，不输出业务文本。
2. Agent API 中间件改为记录 `-->` 请求入口与 `<--` 状态/耗时；修复原中间件在 `call_next` 抛异常时 `response` 未定义、可能掩盖原始异常的缺陷；`/healthz`、`/readyz`、`/docs` 降为 DEBUG，避免健康检查刷屏。
3. Agent API 在提交、查询、取消、SSE 打开/关闭、参数校验失败、业务异常与未捕获异常处补充结构化日志；提交日志包含 operation、幂等键、deadline 与 payload 摘要。
4. Agent Runner 在任务恢复、开始执行、成功/失败/取消时记录状态；后台 worker 绑定原请求的 trace，保证请求日志与异步执行日志可串联。
5. Agent Handler 记录提示词构建（版本与字符数）、模型调用 attempt、模型响应耗时/tokens、结构校验失败与业务规则失败；不记录提示词正文。
6. LLM Provider（DeepSeek）与 Fake 模型分别记录请求、响应耗时/tokens、失败原因；不记录 prompt 内容。
7. 回调分发器记录每次回调 URL、attempt、HTTP 状态、是否被 Java 接纳、耗时、重试计划与耗尽/拒绝结果。
8. 岗位搜索记录每个来源的状态、数量、耗时，以及语义理解批次调用与响应。
9. Java 新增 `logback-spring.xml`：控制台统一时间、级别、线程、`trace`、`task` 与彩色输出，支持 `APP_LOG_LEVEL` 环境变量。
10. Java 新增 `RequestLoggingFilter`：记录所有 HTTP 请求入口、状态码、耗时、客户端与认证标记（不记录 Authorization 值和请求体）。
11. Java `WebClientAgentClient` 记录 Agent 提交/查询/取消的操作、taskId、runId、状态、耗时与失败原因，并透传 `X-Trace-Id`（无请求 trace 时以 taskId 作为回退值）。
12. Java `OutboxScheduler`、`TaskService`、`TaskResultService`、`InternalTaskController`、`GlobalExceptionHandler` 补充任务创建、派发、对账、取消、回调接收/幂等/成功/失败、业务异常日志。
13. 新增测试：`tests/test_logging.py`、`RequestLoggingFilterTest.java`；新增可复现验收入口 `scripts/verify_logging_e2e.py`。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/agent/app/logging_config.py` | 重写日志配置，新增上下文、彩色格式、摘要与编码处理 | 统一 Agent 终端日志格式并防止敏感文本外泄 |
| 修改 | `project/agent/app/api.py` | 请求/响应/异常/SSE 日志，修复中间件异常路径 | 记录每次内部 HTTP 交互 |
| 修改 | `project/agent/app/runner.py` | 任务生命周期日志与后台 trace 绑定 | 异步任务可追溯 |
| 修改 | `project/agent/app/handlers.py` | 提示词、模型调用、校验失败日志 | 记录每次模型交互 |
| 修改 | `project/agent/app/llm.py` | Provider 请求/响应/失败日志 | 记录真实模型调用耗时与 tokens |
| 修改 | `project/agent/app/fake_model.py` | Fake 模型请求/响应/失败日志 | 本地无凭证时也能看清交互 |
| 修改 | `project/agent/app/callbacks.py` | 回调发送、接纳、重试、耗尽日志 | 记录 Agent 到 Java 的回调交互 |
| 修改 | `project/agent/app/search/pipeline.py` | 来源结果与语义理解批次日志 | 记录外部来源交互 |
| 新增 | `project/agent/tests/test_logging.py` | 日志上下文、摘要、编码测试 | 防止日志功能回归 |
| 新增 | `project/agent/scripts/verify_logging_e2e.py` | 启动本地 Agent 并发起真实请求的验证脚本 | 提供可复现的日志验收步骤 |
| 新增 | `project/backend/src/main/resources/logback-spring.xml` | 控制台日志格式与级别配置 | Java 终端日志统一格式 |
| 新增 | `project/backend/src/main/java/com/aijob/backend/common/RequestLoggingFilter.java` | HTTP 请求入口/出口日志 | 记录每次前端或内部调用 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/task/WebClientAgentClient.java` | Agent 调用日志与 X-Trace-Id 透传 | 记录 Java 到 Agent 的交互并串联链路 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/task/OutboxScheduler.java` | 派发、取消、对账、重试日志 | 记录任务派发交互 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/task/TaskService.java` | 任务创建与取消日志 | 记录任务受理 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/task/TaskResultService.java` | 回调接收、幂等、成功、失败、超时、取消日志 | 记录 Agent 回调处理 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/task/InternalTaskController.java` | 回调 HTTP 接收日志与 MDC taskId | 回调日志带 task 上下文 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/common/GlobalExceptionHandler.java` | 业务异常 WARN 日志 | 记录被拒绝的交互 |
| 新增 | `project/backend/src/test/java/com/aijob/backend/common/RequestLoggingFilterTest.java` | 请求日志过滤器单测 | 验证过滤器不改变原链路 |

**执行命令与验证：**

- 命令：`conda run -n ai-job python -m compileall -q app`
- 结果：通过（`PY_COMPILE_OK`）
- 命令：`conda run -n ai-job python -m pytest -q`
- 结果：通过（全量 Python 测试）
- 命令：`conda run -n ai-job python -m pytest -q tests/test_logging.py tests/test_runner_api.py tests/test_misc.py`
- 结果：通过（最后一次改动后的相关测试）
- 命令：`conda run -n ai-job python scripts/verify_logging_e2e.py`
- 结果：通过（`SUBMIT_CODE=202 RUN_STATUS=SUCCEEDED`）
- 说明：实际终端可看到 `-->`/`<--`、payload 摘要、`accepted`、`execute start`、`prompt ready`、`model call`、`model response`、`result ready`、`succeeded`、`callback send`、`query by task` 等日志，且 `trace/task/run/op` 字段完整。
- 命令：`mvn -q -DskipTests compile`
- 结果：通过（`JAVA_COMPILE_OK`）
- 命令：`mvn -q "-Dtest=TaskResultServiceTest,OutboxSchedulerTest,TaskServiceTest,RequestLoggingFilterTest" test`
- 结果：通过（`JAVA_RELATED_TESTS_OK`）
- 命令：`mvn -q test`
- 结果：失败（129 个测试中 19 个错误、1 个失败）
- 说明：失败根因为测试库 `127.0.0.1:3307/career_business_test` 不可达，`Caused by: java.net.ConnectException: Connection refused`，属本地环境缺失，与本次日志改动无关；依赖该库的 `AuthFlowIntegrationTest`、`DatabaseQueryTest` 未通过。
- 命令：`conda run -n ai-job python verify_logging_java.py`（临时脚本，已删除）
- 结果：通过
- 说明：后端在 `local` profile 下启动成功（Flyway 校验 6 个迁移、端口 8080），`GET /api/v1/jobs` 返回 401，终端记录 `--> GET /api/v1/jobs client=127.0.0.1 auth=no` 与 `<-- ... status=401 duration=18ms`；按 UTF-8 读取后中文日志正常。
- 端口状态：验证结束后 8080、8100、18100 均无监听，无残留进程。

**影响范围：**

- Agent 侧日志输出格式变化，但不改变 API 契约、任务状态机、模型调用与回调语义。
- Java 侧新增日志配置与请求过滤器，未改变接口返回体、状态码与业务逻辑。
- `WebClientAgentClient` 新增 `X-Trace-Id` 请求头，Agent 中间件已支持该头，不影响鉴权与幂等。
- 日志不记录简历/JD 原文、prompt 正文、Authorization、API Key 或密码。

**遗留问题：**

- Java 全量测试依赖 `3307/career_business_test`，本地该库未启动，因此未能跑通全量集成测试；后续需启动测试库后重跑 `mvn test`。
- 本次分别验证了 Agent 真实 HTTP 全链路与 Java HTTP 日志，但未用真实 ACTIVE 用户发起「Java 创建任务 → 派发 Agent → 回调 Java」的完整业务任务；该链路需在本地种子数据就绪后补做一次真实联调。
- `scripts/verify_logging_e2e.py` 会启动本地 uvicorn（端口 18100）并使用 fake 模型，仅用于日志验收，不应在共享环境运行。
---

### [2026-11-06 20:17] 优化 Java 控制台日志颜色与占位符

**任务目标：**

避免 Java 日志重定向到文件时包含 ANSI 转义字符，并统一空 trace/task 的占位显示。

**执行行为：**

1. 将 `logback-spring.xml` 中的 `%highlight` 替换为 Spring Boot 的 `%clr`，由 Spring Boot 根据终端/重定向自动决定是否着色。
2. 引入 `org/springframework/boot/logging/logback/defaults.xml`，注册 `clr` 转换器。
3. 将空 trace/task 占位符改为 `-`，与 Agent 日志保持一致。
4. 首次替换后启动失败，日志根因为 `There is no conversion class registered for composite conversion word [clr]`；通过引入 defaults.xml 修复。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/backend/src/main/resources/logback-spring.xml` | 使用 `%clr` 并引入 defaults.xml | 终端着色且文件日志无 ANSI 转义 |

**执行命令与验证：**

- 命令：`conda run -n ai-job python verify_logging_java.py`（临时脚本，已删除）
- 结果：通过
- 说明：`mvn spring-boot:run -Dspring-boot.run.profiles=local` 启动成功，`GET /api/v1/jobs` 返回 401；日志为 `[trace=ba51df78...] [task=-]`，脚本检测 `ANSI_PRESENT=False`，过滤器输出 `--> GET /api/v1/jobs client=127.0.0.1 auth=no` 与 `<-- ... status=401 duration=14ms`。

**影响范围：**

- 仅影响 Java 控制台日志格式，不改变业务逻辑与接口行为。

**遗留问题：**

- 无新增遗留问题。