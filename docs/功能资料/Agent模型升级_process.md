# Agent 模型升级开发过程记录

本文件记录 Python Agent 默认模型切换过程中的文件变更、执行命令、验证结果与遗留问题。

---

### [2026-11-12 10:50] Agent 默认模型切换为 deepseek-v4-pro（思考模式）

**任务目标：**
将 Agent 默认 DeepSeek 模型从 `deepseek-chat` 替换为支持深度思考的模型，并参考 DeepSeek 官方 API 文档选择。

**执行行为：**

1. 查阅 DeepSeek 官方文档「模型 & 价格」与「思考模式」：当前官方在售模型为 `deepseek-flash`（DeepSeek-V4.1-Flash-1017）与 `deepseek-v4-pro`（DeepSeek-V4-Pro-0813），两者均默认开启思考模式（effort 默认 `high`）且支持 JSON Output；旧模型名 `deepseek-chat` 已不在官方模型表中。
2. 将 `Settings.deepseek_model` 默认值改为 `deepseek-v4-pro`。
3. 新增 `deepseek_thinking`（默认 `true`）与 `deepseek_reasoning_effort`（`low|high|max`，默认 `high`）配置，支持环境变量覆盖。
4. 按官方文档调整 `DeepSeekClient.complete` 请求：思考模式经 `extra_body={"thinking":{"type":"enabled"}}` 与顶层 `reasoning_effort` 传递，且**不传 `temperature`**（官方说明思考模式不支持该参数）；关闭思考模式时传 `thinking.disabled` 并保留 `temperature`。
5. 更新受影响单测断言（`test_misc.py`、`test_llm.py`），并新增关闭思考模式的行为用例。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/agent/app/config.py` | 默认模型改为 `deepseek-v4-pro`；新增 `deepseek_thinking`、`deepseek_reasoning_effort` | 按官方文档切换模型与思考控制 |
| 修改 | `project/agent/app/llm.py` | 思考模式下传 `thinking`/`reasoning_effort`、不传 `temperature` | 符合思考模式参数约束 |
| 修改 | `project/agent/tests/test_misc.py` | 默认模型断言改为 `deepseek-v4-pro` | 同步默认值 |
| 修改 | `project/agent/tests/test_llm.py` | 模型名断言同步；新增思考开关用例 | 覆盖新逻辑 |

**执行命令与验证：**

- 命令：`conda run -n ai-job python -m pytest tests/test_misc.py tests/test_llm.py -q`
- 结果：通过
- 命令：`conda run -n ai-job python -m pytest tests -q --ignore=tests/test_job_search_render.py`
- 结果：通过
- 命令：临时脚本真实调用一次 DeepSeek（已删除）
- 结果：通过
- 说明：`provider=deepseek`、`model=deepseek-v4-pro`、`thinking=True effort=high`，返回 `{"ok": true}`，`latency_ms=2686`、`output_tokens=43`，证明思考模式与 JSON Output 在真实链路上可用。

**影响范围：**
Agent 所有模型调用默认使用 `deepseek-v4-pro`；可用 `DEEPSEEK_MODEL`、`DEEPSEEK_THINKING`、`DEEPSEEK_REASONING_EFFORT` 覆盖。fake provider 的自动化测试不受影响。

**遗留问题：**
1. 思考模式会额外消耗输出 token（含思维链），现有 `llm_max_output_tokens=4000` 在高 effort 下可能偏紧，后续按真实长任务表现决定是否提高。
2. 本次仅做单次模型连通与 JSON 输出验证，未重跑简历解析、岗位匹配、专项训练等全链路真实用例。
3. 本机 `.env` 未写入模型变量，因此使用代码默认值；如需切回 Flash 可设 `DEEPSEEK_MODEL=deepseek-flash`。
### [2026-11-12 11:28:12] 定位简历解析模型超时

**任务目标：**
确认简历解析失败的环节。

**执行行为：**

1. 核对 Agent 运行日志及 DeepSeek 客户端配置。简历已提取文本并构建提示词，V4 Pro 请求耗时约 93 秒后报 APITimeoutError，任务总截止时间尚未到。
2. 核对简历中心失败提示。提示把模型错误也引向检查文件，容易误导。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | docs/功能资料/Agent模型升级_process.md | 追加超时定位 | 记录依据 |

**执行命令与验证：**

- 命令：rg 检索 Agent 日志与模型超时配置。
- 结果：日志与 45 秒 SDK 超时、一次自动重试相符。现有日志未区分网络连接和响应读取阶段。

**影响范围：**
简历解析及思考模式的模型请求。

**遗留问题：**
待调整读取等待时间并验证。

---

### [2026-11-12 11:28:54] 放宽思考模式读取超时并修正提示

**任务目标：**
允许思考模式等待更久并保留思考展示，帮助用户按原简历版本重试。

**执行行为：**

1. 思考模式读取超时默认 120 秒，连接、写入和连接池沿用原超时；保留模型、思考强度、重试与任务截止机制。
2. SDK 超时仍按可重试错误处理，向用户显示中文超时提示。简历失败提示引导查看任务详情。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/agent/app/config.py | 新增思考模式读取超时 | 允许模型完成较长推理 |
| 修改 | project/agent/app/llm.py | 按思考模式设置读取超时，转换超时提示 | 减少提前断开并明确错误 |
| 修改 | project/agent/tests/test_llm.py | 校验思考及非思考模式的超时行为 | 回归验证 |
| 修改 | project/frontend/src/views/resume/ResumeView.vue | 更新通用失败提示 | 避免把模型超时归因于文件 |
| 修改 | docs/功能资料/Agent模型升级_process.md | 追加修复记录 | 保留操作过程 |

**执行命令与验证：**

- 命令：使用 apply_patch 修改以上代码。
- 结果：修改已应用，测试及服务加载待核实。

**影响范围：**
DeepSeek 思考模式和简历中心错误提示。

**遗留问题：**
待执行测试与虚构简历探针。

---

### [2026-11-12 11:31:02] 通过 Agent 定向测试与前端类型检查

**任务目标：**
验证思考模式超时修改、简历相关逻辑及前端类型。

**执行行为：**

1. 运行 Agent 定向 pytest 和前端 vue-tsc。默认沙箱的 pytest 无输出，终止后在本机权限下重试。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | docs/功能资料/Agent模型升级_process.md | 追加验证记录 | 保留测试结果 |

**执行命令与验证：**

- 命令：python -B -m pytest -o addopts= -p no:cacheprovider -q tests/test_llm.py tests/test_reasoning.py tests/test_resume_project.py tests/test_misc.py。
- 结果：63 项通过。
- 命令：node node_modules/vue-tsc/bin/vue-tsc.js --noEmit；git diff --check（本次触及的源码）。
- 结果：均通过。

**影响范围：**
本地自动化验证。

**遗留问题：**
虚构简历真实模型探针已发起，结果待核实；运行中的 Agent 待加载修复。

---

### [2026-11-12 11:31:53] 验证真实模型解析并加载修复

**任务目标：**
确认真实 V4 Pro 思考模式能够完成简历解析并产生思考事件，加载修复到本机 Agent。

**执行行为：**

1. 使用 2,961 字符的虚构简历，经 CareerOrchestrator 执行 RESUME_PARSE，调用 DeepSeek 官方接口；记录指标及结构校验，不记录简历内容或思考文本。
2. 复用原 SQLite 目录和既有启动脚本，重启本机 Python Agent，检查健康接口与启动日志。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | docs/功能资料/Agent模型升级_process.md | 追加真实模型与服务重启结果 | 记录验证与发布状态 |

**执行命令与验证：**

- 命令：python -B -u -（内联虚构简历探针）。
- 结果：真实 V4 Pro 在 high 思考强度下约 43.5 秒返回，解析结果通过契约校验，提取 3 项技能，产生 4,000 字符思考事件（达到展示上限）。
- 命令：Get-NetTCPConnection、Stop-Process、既有 manual-services-20261111/start.ps1、Invoke-RestMethod 请求 127.0.0.1:8100/healthz。
- 结果：Agent 启动日志显示 deepseek-v4-pro，健康接口 HTTP 200、status=ok。

**影响范围：**
本机 Agent 加载此次修改；虚构探针未写入业务数据库。

**遗留问题：**
未重试原始简历，未通过浏览器验证思考事件端到端展示。

---

### [2026-11-12 11:35:05] 排查简历解析返回空正文

**任务目标：**
确认延长超时后再次失败的环节。

**执行行为：**

1. 核对 Agent 日志。简历文本已提取，DeepSeek V4 Pro 约 70 秒后返回响应，SDK 调用未超时，Agent 因正文为空而失败。
2. 核对输出预算与官方 JSON Output 说明。当前总输出上限为 4000 token；旧日志没有结束原因或 token 用量，无法区分预算截断和 JSON 模式空白。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | docs/功能资料/Agent模型升级_process.md | 追加排查记录 | 保留证据 |

**执行命令与验证：**

- 命令：rg 检索 Agent 运行日志和输出处理；查阅 DeepSeek 官方 JSON Output 文档。
- 结果：确认模型响应无正文，未复用用户文件进行探针。

**影响范围：**
本机简历解析任务。

**遗留问题：**
需要提高输出预算并处理空白与截断。

---

### [2026-11-12 11:36:33] 为简历解析补充输出预算和自动重试

**任务目标：**
处理思考模式返回空白或截断 JSON 的情况。

**执行行为：**

1. 仅为简历解析思考模式将默认输出预算由 4000 提至 8192，首轮仍使用配置的思考强度。
2. 检测空白和长度截断，自动以 low 强度重试一次，保留思考模式和业务校验。记录结束原因、输出 token 数和预算，不记录正文或思考文本。
3. 两次历史任务日志均显示模型空正文。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | project/agent/app/config.py | 增加简历解析思考模式输出预算 | 为正文留出空间 |
| 修改 | project/agent/app/llm.py | 判断空白和截断并重试；补充诊断日志 | 降低手动重试概率 |
| 修改 | project/agent/tests/test_llm.py | 覆盖空白及截断后恢复，检查保留思考输出 | 防止回归 |
| 修改 | docs/功能资料/Agent模型升级_process.md | 追加变更记录 | 记录实施过程 |

**执行命令与验证：**

- 命令：apply_patch。
- 结果：修改已应用，待测试。

**影响范围：**
DeepSeek 模型客户端及简历解析，其他业务沿用原输出预算。

**遗留问题：**
待执行回归、虚构简历真实调用和服务重启。

---

### [2026-11-12 11:37:40] 验证空白及截断的自动恢复

**任务目标：**
确认两种异常都会自动重试，并继续返回可校验结果和思考内容。

**执行行为：**

1. 模拟 JSON 正文只有空格及达到输出长度上限的响应，运行 Agent 定向回归。
2. 发起一次约 3300 字符的虚构简历真实模型探针。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | docs/功能资料/Agent模型升级_process.md | 追加测试记录 | 保存验证结果 |

**执行命令与验证：**

- 命令：python -B -m pytest -o addopts= -p no:cacheprovider -q tests/test_llm.py tests/test_reasoning.py tests/test_resume_project.py tests/test_misc.py tests/test_handlers.py；git diff --check。
- 结果：103 项通过，差异检查通过。真实模型探针待返回。

**影响范围：**
本机测试及虚构探针。

**遗留问题：**
待确认真实模型结果并重启 Agent。

---

### [2026-11-12 11:38:28] 真实模型验证通过并重启 Agent

**任务目标：**
确认调整后真实模型可交付完整解析结果和思考事件，并使本机服务生效。

**执行行为：**

1. 使用 3304 字符的纯虚构简历，通过 CareerOrchestrator 调用真实 DeepSeek V4 Pro。
2. 按既有配置重启 Python Agent，检查启动日志和健康接口。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | docs/功能资料/Agent模型升级_process.md | 追加真实模型和启动检查结果 | 记录验证状态 |

**执行命令与验证：**

- 命令：python -B -u -（内联虚构简历探针）。
- 结果：high 思考强度约 33.6 秒完成，2018 个输出 token，提取 3 项技能，结果通过契约校验并产生思考事件。探针首轮成功，自动重试分支由定向测试覆盖。
- 命令：Get-NetTCPConnection、Stop-Process、既有 manual-services-20261111/start.ps1、Invoke-RestMethod 请求本机 /healthz。
- 结果：Agent 使用 deepseek-v4-pro 启动，健康接口 HTTP 200、status=ok。

**影响范围：**
本机 Agent 已加载修改，继续使用原运行数据。

**遗留问题：**
原始简历未再次发给模型，旧任务仍保留失败状态，需按原版本新建任务。

---
