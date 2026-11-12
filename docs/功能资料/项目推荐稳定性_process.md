
### [2026-11-12 16:35，Asia/Shanghai] 定位项目推荐 402 并接入本地规则降级

**任务目标：**
修复项目推荐在模型服务拒绝请求时直接失败，以及页面误将供应端故障提示为输入问题。

**执行行为：**

1. 对照 Agent 与 Java 日志，确认 16:30:49 的项目推荐任务收到 DeepSeek HTTP 402，Java 正常接收并记录失败回调。该次请求未进入项目推荐结果的业务校验。
2. 给项目推荐和项目计划接入本地规则方案。正常路径仍使用 DeepSeek，采用非思考 JSON 请求和 35 秒模型工作流时限。供应端错误、超时或修复后仍无合法输出时切换到规则结果，继续走原 Pydantic、Agent 契约和 Java 结果校验。规则结果注明来源并保留用户技能、难度、周期和工时限制。
3. 对模型 402 和访问拒绝使用可读文案。推荐卡将规则建议与模型建议标明，旧失败任务可按原条件重试。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/agent/app/project_fallback.py` | 根据许可技能和匹配差距生成项目建议，按周期工时生成计划 | 模型故障时维持项目流程 |
| 修改 | `project/agent/app/handlers.py`、`app/llm.py` | 接入项目降级、超时及模型 HTTP 状态文案 | 减少失败并便于排障 |
| 修改 | `project/frontend/src/components/TaskStatus.vue`、`src/views/project/ProjectView.vue` | 改善模型故障提示和降级来源标签 | 避免误导用户 |
| 修改 | `project/agent/tests/test_resume_project.py` | 测试 402 降级、超时以及推荐到计划的契约 | 覆盖回归 |
| 新增 | `docs/功能资料/项目推荐稳定性_process.md` | 记录排障、文件和验证 | 保留真实过程 |

**执行命令与验证：**

- `rg -n` 核查真实日志，确认项目推荐 HTTP 402，未在记录中保存任务用户标识、密钥或请求内容。
- 初次 `npm.cmd run type-check` 通过。针对最终代码的自动测试及真实模型合成画像探针已启动，结果待下条记录。

**影响范围：**
项目推荐、项目计划的 Agent 处理和任务状态提示。Java API 和表结构保持现有契约。

**遗留问题：**
待完成测试、Agent 重启和就绪验证。外部账号的付费或额度状态由服务端响应反映。

---

### [2026-11-12 16:37，Asia/Shanghai] 验证真实 402 降级并重启 Agent

**任务目标：**
确认外部模型拒绝请求时推荐和计划可交付，启用当前工作区修复。

**执行行为：**

1. 通过 `CareerOrchestrator` 用合成画像请求真实 DeepSeek，推荐及计划均返回 402。生产降级逻辑返回两周的「Java 投递进度看板」及总工时 8 小时的两阶段计划，分别通过 Agent 结果契约。
2. 首轮测试暴露新增用例插入位置错误，导致既有工时断言落入另一用例，修正测试位置后重跑。
3. 仅重启本机 Python Agent，保持原绑定和渲染依赖，Agent PID 从 36720 更新为 34288。前端开发服务可读取当前源码，Java 结果契约无改动。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `project/agent/tests/test_resume_project.py` | 恢复原工时超限断言位置并验证降级回归 | 避免测试夹具误报 |
| 修改 | `docs/功能资料/项目推荐稳定性_process.md` | 追加真实探针和服务就绪状态 | 完成可复核记录 |
| 新增 | `project/backend/target/restarts/project-fallback-20261112-163642/` | 本地 Agent 启动日志 | 核对重启结果 |

**执行命令与验证：**

- 使用项目 Python 环境从标准输入运行真实 DeepSeek 合成画像探针。推荐和计划均记录 `MODEL_HTTP_402`、`local_rules` 和 `resultValidated=True`。
- `python -m pytest -p no:cacheprovider tests/test_resume_project.py tests/test_llm.py tests/test_operations.py -o addopts='' --disable-warnings -q`，81 项通过，有 15 条 Pydantic 警告。
- `npm.cmd run type-check`，通过。`git -c core.fsmonitor=false diff --check` 定向检查通过。
- `curl.exe` 检查 Agent `/healthz` 和 `/readyz`，均 HTTP 200。

**影响范围：**
已部署至本机 Agent。原失败任务仍保留，可使用页面上的按原条件重试。真实探针未使用用户画像或写入 Java 业务库。

**遗留问题：**
DeepSeek 实测仍返回 402，规则方案能支撑项目推荐和计划。真实账号浏览器操作和 Java 回调落库未在本轮复跑。

---
