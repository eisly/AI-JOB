# 模型输出上限与思考展示修复过程记录

本文件记录"模型输出达到长度上限"与"深度思考过程无法展示"两个实际问题的定位、修复与验证。

---

### [2026-11-12 13:45] 修复输出长度上限与思考事件链路

**任务目标：**
解决实际运行中"生成专项训练计划失败（模型输出达到长度上限，请重试）"，以及"深度思考过程无法在页面展示"的问题。

**根因分析：**

1. 输出上限：`llm_max_output_tokens=4000`，思考模式（默认 `high` effort）的思维链会消耗大量 output token，导致计划 JSON 被截断（`finish_reason=length`）；且长度重试只对 `RESUME_PARSE` 开启，`LEARNING_PLAN` 一次失败即报错。
2. 思考不展示：
   - 长度失败时 `DeepSeekClient.complete` 直接抛异常，`reasoning` 未返回，因此没有思考事件。
   - `AgentEventSyncService` 只同步 `RUNNING` 任务，短任务在 2 秒轮询间隙内已终态，思考事件来不及同步到 `ai_task_event`。

**执行行为：**

1. `llm_max_output_tokens` 默认由 4000 提高为 16384。
2. `LLMProviderError` 新增 `reasoning` 字段；`DeepSeekClient.complete` 在长度截断/空内容时把已获取的思维链放入异常；`BaseHandler.execute` 捕获异常时先发出 reasoning 事件再抛错，保证失败任务也能看到思考过程。
3. `AgentEventSyncService` 抽出 `syncTask(taskId, runId)`；`TaskResultService.submit` 在写入 `done`/`error` 事件前调用，补齐短任务在轮询间隙结束导致的思考事件丢失。
4. 同步更新 `TaskResultServiceTest` 的构造签名与 `test_llm.py` 的输出上限断言。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/agent/app/config.py` | `llm_max_output_tokens` 4000 → 16384 | 思考模式输出预算不足 |
| 修改 | `project/agent/app/llm.py` | 异常携带 reasoning、length 也返回思维链 | 失败也能展示思考 |
| 修改 | `project/agent/app/handlers.py` | 模型异常时先 emit reasoning | 失败也能展示思考 |
| 修改 | `project/backend/.../task/AgentEventSyncService.java` | 抽出 `syncTask` | 支持回调兜底同步 |
| 修改 | `project/backend/.../task/TaskResultService.java` | 回调落终态前同步 Agent 事件 | 防止短任务事件丢失 |
| 修改 | `project/backend/.../task/TaskResultServiceTest.java` | 构造签名更新 | 适配新依赖 |
| 修改 | `project/agent/tests/test_llm.py` | 输出上限断言更新 | 适配新默认值 |

**执行命令与验证：**

- 命令：临时脚本真实调用 DeepSeek 生成训练计划（已删除）
- 结果：通过
- 说明：`model=deepseek-v4-pro`、`max_tokens=16384`、`plan_items=7`、`reasoning_events=1`、`reasoning_len=4000`，证明计划可成功生成且思考过程已捕获。
- 命令：`conda run -n ai-job python -m pytest tests/test_llm.py tests/test_reasoning.py -q`
- 结果：通过
- 命令：`mvn -q "-Dtest=TaskResultServiceTest,LearningResultHandlerTest,LearningServiceResumePlanTest" test`
- 结果：通过
- 命令：`mvn -q -DskipTests compile`
- 结果：通过

**影响范围：**
所有 Agent 模型调用的输出预算提高；失败任务也会保留思考事件；短任务的思考事件在回调时补齐。

**遗留问题：**
1. 需重启后端（应用 V9 迁移与新同步逻辑）与 Agent 后，在页面**重新生成计划**才能看到思考过程；旧任务的历史事件不会补发。
2. 仍为非流式：思考过程在单次模型返回后整段推送；超 4000 字截断；事件 24h 过期。
3. 前端折叠面板（`ModelReasoning.vue`）未显示"用时 X 秒"，后续可按需补充。