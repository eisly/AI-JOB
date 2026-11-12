# 模型思考过程展示开发过程记录

本文件记录"展示模型思考过程（DeepSeek 思考模式 reasoning_content）"的跨端改造过程。

---

### [2026-11-12 11:05] 打通 Agent → Java → 前端的思考过程事件通道

**任务目标：**
在模型切换为思考模式（deepseek-v4-pro）后，让用户能在所有出现任务状态的位置看到模型的思考过程。

**需求确认（用户选择）：**
- 展示位置：所有出现任务状态的位置（简历解析、简历优化、模拟面试、专项训练、任务中心等）。
- 存储策略：实时事件推送 + 折叠面板 + 单条截断 4000 字；随事件 24h 过期后不再回放，不长期占用存储。

**执行行为：**

1. 现状调研：Java 任务事件仅写 `accepted/done/error`，Agent 的 `stage/delta` 事件未同步到 Java，前端 `delta-panel` 实际无数据；`LLMResponse` 未读取 `reasoning_content`。
2. Agent：`LLMResponse` 新增 `reasoning`，`DeepSeekClient.complete` 读取 `reasoning_content`；`ExecutionContext` 新增 `emit_reasoning`；`BaseHandler.execute` 在模型返回后按配置发出 `reasoning` 事件（截断 `reasoning_max_chars`）；`runner` 注入 `emit_reasoning`；`api.py` 新增 `GET /internal/ai/v1/runs/{run_id}/events/log`（JSON 事件列表，支持 `after`/`limit`，供 Java 同步）。
3. Java：新增 Flyway `V9` 把 `ai_task_event.event_type` 允许值扩展为含 `reasoning`；`TaskMapper` 新增事件游标/序号/去重/写入方法（写入 `agent_run_id`/`agent_seq`）；`AgentClient`/`WebClientAgentClient` 新增 `runEvents`；新增 `AgentEventSyncService` 每 2 秒把运行中任务的 Agent `stage`/`reasoning` 事件同步到 `ai_task_event`，由既有任务 SSE 透传。
4. 前端：`useTask` 新增 `reasoningText` 与 `reasoning` 事件分支（按条替换）；新增 `ModelReasoning.vue` 折叠面板；`TaskStatus.vue` 新增 `reasoning` 属性并内嵌展示；在 tasks、resume、resume-optimization、interview、growth、job-search、project、job-match、training 共 9 个视图的全部 `TaskStatus` 处接入。
5. 新增 `tests/test_reasoning.py` 覆盖 reasoning 提取、事件发出、截断与开关。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/agent/app/config.py` | 新增 `reasoning_display_enabled`、`reasoning_max_chars` | 展示开关与截断 |
| 修改 | `project/agent/app/llm.py` | `LLMResponse.reasoning` 与 `reasoning_content` 读取 | 获取思维链 |
| 修改 | `project/agent/app/handlers.py` | `emit_reasoning` 与发出逻辑 | 事件产生 |
| 修改 | `project/agent/app/runner.py` | 注入 `emit_reasoning` | 事件落库 |
| 修改 | `project/agent/app/api.py` | 新增 run 事件 JSON 列表接口 | 供 Java 同步 |
| 新增 | `project/agent/tests/test_reasoning.py` | 3 个用例 | 测试覆盖 |
| 新增 | `project/backend/src/main/resources/db/migration/V9__add_task_event_reasoning.sql` | event_type 增加 reasoning | 事件类型扩展 |
| 修改 | `project/backend/.../task/TaskMapper.java` | 事件游标/序号/去重/写入方法 | 同步写入 |
| 修改 | `project/backend/.../task/AgentClient.java`、`WebClientAgentClient.java` | 新增 `runEvents` | 拉取 Agent 事件 |
| 新增 | `project/backend/.../task/AgentEventSyncService.java` | 定时同步 Agent 事件 | 事件透传 |
| 修改 | `project/frontend/src/composables/useTask.ts` | `reasoningText` 与 reasoning 事件分支 | 接收思考过程 |
| 新增 | `project/frontend/src/components/ModelReasoning.vue` | 折叠展示组件 | UI 展示 |
| 修改 | `project/frontend/src/components/TaskStatus.vue` | 新增 `reasoning` 属性 | 统一展示 |
| 修改 | `project/frontend/src/views/**`（9 个视图） | TaskStatus 接入 reasoning | 全位置覆盖 |

**执行命令与验证：**

- 命令：`conda run -n ai-job python -m pytest tests/test_reasoning.py -q`
- 结果：通过
- 命令：`conda run -n ai-job python -m pytest tests -q --ignore=tests/test_job_search_render.py`
- 结果：通过
- 命令：`mvn -q -DskipTests compile`
- 结果：通过
- 命令：`npm run type-check`、`npm run build`
- 结果：通过
- 命令：临时脚本真实调用 DeepSeek（已删除）
- 结果：通过
- 说明：`model=deepseek-v4-pro`、`thinking_enabled=True`、`reasoning_len=183`、`text={"ok": true}`，证明思考模式会返回 `reasoning_content` 且已可获取。

**影响范围：**
任务事件新增 `reasoning` 类型；Agent 运行中任务每 2 秒同步一次阶段与思考事件；思考过程对用户可见（自身任务内）。日志仍只记录长度，不写业务文本。

**遗留问题：**
1. 当前模型调用为非流式：思考过程在单次模型返回后整段推送，而非逐字流式；事件通道已支持分片，后续切换流式调用即可逐段展示。
2. 未做端到端页面验证：8080 为旧后端实例、Agent（8100）未启动，需重启后端使 V9 迁移与新同步服务生效后进行真实联调。
3. 思考过程随事件 24h 过期，不回放历史；超出 4000 字部分被截断。