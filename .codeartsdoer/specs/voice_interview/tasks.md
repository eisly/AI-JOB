# 语音模拟面试 - 编码任务清单

> 文档定位：本文件是「语音模拟面试」首版（逐题整段录音）的编码执行清单，供后续编码阶段按编号领取任务。
> 需求基线：`.codeartsdoer/specs/voice_interview/spec.md`
> 设计基线：`.codeartsdoer/specs/voice_interview/design.md`（接口编号 V1–V6 / A1–A2、表名 `interview_voice_transcription`、迁移 `V15`、错误码、限额、状态机、里程碑 M0–M3 均以该文档为准）
> 代码基线：`AI-JOB` 工作区（迁移最大版本 `V14`；`interview` 包仅 4 个类；`agent/app` 无 `speech/`；前端无任何 `MediaRecorder` 使用）
> 本轮范围：**只产出任务清单**，不写业务代码、不改数据库脚本、不执行迁移、不提交代码。
> 任务总数：**59**（M0 7 + M1 22 + M2 8 + M3 4 + 测试 12 + 总题数预算 6）
>
> **增量（2026-11-21）**：新增第 13 章"总题数预算与后端结束规则"，含 Q-01～Q-06 六个**待实现**任务，对应 `spec.md` 5.9 与 `design.md` 第八章。既有任务编号与进度标记一律保留；第 9、10、11、12 章中与新规则冲突的表述已按同一章内修订处理（见各节末尾的"2026-11-21 修订"）。
>
> **增量（2026-11-21 编码任务规划阶段）**：Q-01～Q-06 由"任务概要"细化为 **36 个可领取子任务（Q-01.1 ～ Q-06.7，全部待实现）**，每个子任务补齐依赖编号、文件与类/方法（含当前工作区行号）、实现要点、可断言验收标准与验证方式；新增 13.0 任务总览、13.2 推荐执行顺序与并行度、13.3 回归与风险任务、13.4 一致性检查、13.5 本轮边界声明。主任务编号与既有完成状态不变（本文件此前无已完成任务，本次也未把任何任务标记为完成）。
>
> **增量（2026-11-22 编码任务规划阶段）**：新增第 14 章「ASR 结果晚到保护」，含 **`M-LA-01`～`M-LA-09` 九个主任务与 41 个可领取子任务**（`M-LA-01.1`～`M-LA-09.4`），对应 `design.md` 第九章（9.0–9.13）与 `spec.md` 4.2-1、5.4.1-5/6、5.4.3-4/5、5.9.1-9；每个子任务均标注"既有代码状态"（完整实现 / 部分实现需修正 / 未实现需新增）。既有任务编号、完成标记与 `M1-09`/`M1-10`/`M1-13`/`T-09` 等不重写、不删除、不改状态；累积主任务编号 **68**（原 59 + 新增 9）。本章只做任务规划，未修改业务代码、未执行迁移与测试。

---

## 0. 阅读约定

### 0.1 任务编号与 `design.md` 6.2 的对应关系

| 本文件编号 | design 6.2 编号 | 说明 |
|-----------|----------------|------|
| M0-01 ～ M0-07 | —（design 6.5 决策 + 6.1 M0 目标） | 契约与决策收口，design 未单列任务号，本轮补齐 |
| M1-01 / M1-02 | DB-1 / DB-2 | `V15` 迁移与回滚脚本 |
| M1-03 / M1-04 / M1-05 | P-1 / P-2 / P-3 | Provider 抽象、Fake、SpeechService |
| M1-06 / M1-07 | P-4 + P-5 / P-7 | 路由注册与 scope；配置项 |
| M1-08 / M1-09 / M1-10 | J-1 / J-2 / J-3 | 同步调用与令牌、Mapper、转写主流程 |
| M1-11 / M1-12 / M1-15 | J-4 / J-5 / J-7 | 路由、合成服务、413 处理 |
| M1-13 / M1-14 | J-6 / J-8 | `/answers` 扩展、字段回显 |
| M1-16 ～ M1-22 | F-1 / F-9 / F-2 / F-3 / F-4 / F-5 / F-6+F-7+F-8 | 前端（F-9 独立为 M1-17） |
| M2-01 / M2-08 | P-6 / T-5 | 真实 Provider、实测报告 |
| M2-07 | PR-1 + PR-2 | 文案与参数定稿（合并） |
| T-01 ～ T-12 | T-1 ～ T-4 + 6.4 验收清单 | 测试与阶段验收 |
| **Q-01 ～ Q-06（2026-11-21 新增）** | design 第八章（8.2–8.9） | 总题数预算与后端结束规则；编号独立，避免覆盖既有任务 |

### 0.2 任务完成定义（DoD，全部任务通用）

1. 代码/脚本已提交前先在本地跑通"验证"列中的命令，或完成列出的手工检查并留下记录。
2. 新增或修改的接口、字段、错误码与 `design.md` 一致；若被迫偏离，必须同步在 `design.md` 与第 8 章"冲突记录"中登记。
3. 不引入 `design.md` 2.4（D1–D14）之外的额外中间件（消息队列、WebSocket、对象存储）。
4. 日志中不得出现音频字节、转写全文、语音密钥、`Idempotency-Key` 原文（spec 4.3-5）。
5. 任务涉及的既有文字面试行为零变化（未传新字段时行为与当前完全一致）；**Q 阶段例外**：新建会话按 `spec.md` 5.9 的总提问预算执行，历史会话（`MAIN_ONLY`）的展示与结束行为必须零变化。

### 0.3 常量单一事实源（三处数值必须一致，禁止各写各的）

| 常量 | 值 | Java | Python | 前端 |
|------|-----|------|--------|------|
| 单段音频上限 | `8388608`（8MB） | `VoiceLimits.MAX_AUDIO_BYTES` | `speech_max_bytes` | `utils/voice/limits.ts` |
| 录音时长上限 | `180000` ms（180s） | `VoiceLimits.MAX_DURATION_MS` | `speech_max_audio_ms` | 同上 |
| 录音时长下限 | 见 O11（临时 `2000` ms） | `VoiceLimits.MIN_DURATION_MS` | 同左 | 同左 |
| 转写 TTL | `1800` s（30min，见 O3） | `VoiceLimits.TRANSCRIPTION_TTL_SECONDS` | — | `transcriptionTtlSeconds`（由 V1 下发） |
| 单次合成字符上限 | `500` | `VoiceLimits.MAX_TTS_CHARS` | `speech_max_tts_chars` | — |
| 分句段长上限 | `120` 字/段 | `VoiceLimits.TTS_SEGMENT_CHARS` | — | — |
| 回答长度 | `1..10000` | `Data.text(...,10000)`（既有） | — | `maxlength=10000`（既有） |
| 转写次数限流 | 30 次/10min、200 次/日 | `VoiceRateLimiter` | — | 按 429 提示 |
| TTS 字数配额 | 20000 字/日 | `VoiceRateLimiter` | — | 按 429 提示 |
| 转写超时 | Agent 8s / Java 12s | `voiceAgentTimeout=12s` | `speech_timeout_seconds=8.0` | 15s |
| 合成超时 | Agent 6s / Java 10s | `voiceSynthTimeout=10s` | `speech_tts_timeout_seconds=6.0` | 12s |
| 合成缓存 | ≤200 条、单条 ≤512KB、≤5min | `QuestionSpeechService` LRU | — | — |

> 前端 `limits.ts` 的默认值必须与 V1 下发值一致；运行时应以 V1 返回值为准（避免"前端 3 分钟、后端拒 2 分钟"）。

### 0.4 错误码使用表（实读 `common/ApiErrorCode.java`，不得擅自新增）

| 场景 | HTTP | code | key | 来源 |
|------|------|------|-----|------|
| 参数缺失/类型/长度 | 400 | 40001 | `VALIDATION_ERROR` | 既有枚举 |
| 未登录/令牌过期 | 401 | 40101 | `UNAUTHENTICATED` | 既有 |
| 会话/消息/转写不属于本人 | 404 | 40401 | `RESOURCE_NOT_FOUND` | 既有（不区分不存在与非本人） |
| 版本冲突 | 409 | 40901 | `VERSION_CONFLICT` | 既有 |
| 同音频仍在处理 | 409 | 40902 | `TASK_IN_PROGRESS` | 既有（`details.recordId`） |
| 状态不允许（非 WAITING_ANSWER、同意撤回） | 409 | 40904 | `INVALID_STATE` | 既有（`details.reason`） |
| 旧题晚到 / 转写过期、已消费、已丢弃 | 409 | 40905 | `STALE_INPUT` | 既有（`details.reason` / `details.questionMessageId`） |
| 超 8MB 或 multipart 超限 | 413 | 41301 | `PAYLOAD_TOO_LARGE` | 既有 |
| 非音频/伪装音频 | 415 | 41501 | `UNSUPPORTED_FILE_TYPE` | 既有 |
| 限流 | 429 | 42901 | `RATE_LIMITED` + `Retry-After` | 既有 |
| Agent 内部错误 | 500 | 50001 | `INTERNAL_ERROR` | 既有 |
| 语音服务不可用/超时 | 503 | 50301 | `DEPENDENCY_UNAVAILABLE` | 既有（504 见冲突 C2） |

> `EMPTY`（空识别）**不是错误码**：V2 返回 200 + `status=EMPTY`，由前端按状态提示。

---

## 1. 里程碑总览

| 里程碑 | 任务数 | 入口条件 | 出口判定（可演示） |
|--------|--------|---------|------------------|
| **M0 契约与决策收口** | 7 | `spec.md`/`design.md` 已评审 | 接口签名、错误码、限额表、`V15` 脚本冻结；决策项有结论或临时值 |
| **M1 Fake Provider 完整闭环** | 22 | M0 出口 | Chrome/Edge 本地：播放→录音→停止→转写→编辑→提交→追问/下一题；刷新可恢复；切文字可完成面试 |
| **M2 真实能力与生产就绪** | 8 | M1 出口 + O1/O6 有结论 | 真实 ASR/TTS 接入、限流配额、同意落库、过期清理、降级与观测 |
| **M3 联调回归与二期评估** | 4 | M2 出口 | 异常矩阵全绿、压测数据、二期投入评估结论、U13 证据归档 |
| **测试（跨里程碑单列）** | 12 | 随对应模块 | 见第 6 章 |
| **Q 总题数预算（2026-11-21 新增）** | 6（细分 36 个可领取子任务） | `spec.md` 5.9 与 `design.md` 八章已评审 | 总上限 5 时主问题+追问最多 5 道；最后一题可答；超额被后端拒绝；历史会话行为不变；A–N 用例实际执行通过后才可标完成 |

---

## 2. M0：契约与决策收口（7 个任务，全部为 M1 的前置）

### M0-01 决策项收口与临时值冻结
- **角色**：产品 + 技术负责人 + 合规
- **前置**：无
- **文件**：在 `design.md` 6.5 与本文第 11 章追加"已决/临时值"记录（不改 DDL）
- **实现要点**：
  1. 逐项给出结论或"临时值 + 复核时间"：O1 供应商/账号/地域/音色、O2 时长/大小/配额、O3 转写 TTL、O4 逐题混用、O5 生产 HTTPS、O6 第三方音频留存、O7 U13 是否纳入本期、O8 是否持久化 `mode`/`answer_source`。
  2. 本轮核对新增三项：O9 隐私同意接口归属、O10 超时错误语义（503 还是 504）、O11 录音时长下限。
  3. 未决项必须给临时值，保证 M1 可开工（建议：O2 用 3 分钟/8MB/30 次/10min，O3 用 30 分钟，O11 用 2000ms，O8 采"均保存"，O10 用 503 + `details.reason=TIMEOUT`）。
- **验收标准**：11 项决策在文档中有唯一结论（含临时值标记）；无"待定且无默认"的项。
- **验证**：评审会议纪要 + 文档 diff 检查。
- **追溯**：`design.md` 6.5、2.4 D10/D14；`spec.md` 7.1–7.8

### M0-02 接口契约与错误码表冻结（V1–V6 / A1–A2）
- **角色**：Java + Python + 前端（三方共同签署）
- **前置**：M0-01（限额值可先用临时值）
- **文件**：`design.md` 2.2.1/2.2.2/2.6；按需更新 `docs/功能资料/09-模拟面试/模拟面试后端接口示例.md`
- **实现要点**：
  1. 冻结 8 个接口的路径、方法、请求字段、响应字段、HTTP 状态与 `error.key`（严格对齐第 0.4 节）。
  2. 明确 V2 的 `EMPTY` 走 200；V3 的 `DISCARDED/EXPIRED` 返回 `data=null` 并附 `status`。
  3. 明确 V5 的响应头（`Content-Type: audio/mpeg`、`Cache-Control: no-store`、`X-Speech-Voice`）与"失败时返回 JSON 错误体"的双形态。
  4. 明确 A1/A2 的 `requestId` 绑定语义（`taskIds=[requestId]`，scope `ai:speech`，TTL 60s）。
  5. 明确 V6 新增字段可选性与缺省行为（未传 = 现状）。
- **验收标准**：三方对同一份契约表签字确认；任一字段变更必须同时改 `design.md` 与本文。
- **验证**：契约评审会 + 与 `InterviewController.java` 现有 6 个端点比对（不得改动既有签名）。
- **追溯**：`design.md` 2.2.1、2.2.2、2.2.4

### M0-03 缺口处置：隐私同意接口与超时/下限语义
- **角色**：Java + 产品
- **前置**：M0-01（O9/O10/O11）、M0-02
- **文件**：`design.md` 2.2.1（补接口行）；本文第 8 章冲突记录 C1/C2/C3
- **实现要点**：
  1. **同意接口缺口（C1）**：`design.md` 2.9 与 F-7 要求"同意写 `interview_voice_consent`"，但 2.2.1 未定义接口。二选一并记录：① 新增 `V7 POST /api/v1/interviews/{interviewId}/voice-consent`（请求 `{policyVersion, agreed}`，返回 `{agreed, policyVersion, agreedAt}`）；② 本期豁免服务端校验，由配置 `app.voice.consent-required=false` 控制，M3 前补。**推荐 ①**。
  2. **超时语义（C2）**：`ApiErrorCode` 无 504 枚举。推荐统一返回 503 + `details.reason=TIMEOUT`（不新增错误码）；若坚持 504，需新增 `DEPENDENCY_TIMEOUT(504,50302,...)` 并同步前端映射。
  3. **时长下限（C3）**：V2 写 10_000ms、DB CHECK 写 1000ms、F04 写 1000ms。统一为"业务下限取配置（默认 2000ms），DB CHECK 保持 1000ms 硬边界"。
- **验收标准**：三项各有唯一结论；若新增 V7，接口行已补入 `design.md` 2.2.1 并标注"本轮补充"。
- **验证**：文档 diff + 契约表复核。
- **追溯**：`design.md` 2.9、F-7、F12、2.2.1；`spec.md` 4.3-4、5.4.3、5.7

### M0-04 落地方式修正登记（避免开发按错误理解动手）
- **角色**：Java + 前端 + Python
- **前置**：M0-02
- **文件**：本文第 8 章 C4–C10；`design.md` 2.1.2 文件清单
- **实现要点**：
  1. 登记"V5 一次性返回与'首段先播'目标矛盾"（C4），本期采用"一次性返回完整 mp3"，长题首字节在 M2-08 实测后决定是否做分段。
  2. 登记"前端缺少二进制获取函数"（C5）：需在 `api/request.ts` 新增 `requestBlob()`，不复用 `downloadBinary`（后者语义是下载落盘）。
  3. 登记 `api/interviews.ts` 的 `answer` 签名与 `AnswerAttempt` 需扩展（C6）。
  4. 登记 `InterviewMapper.insertMessage` 双调用点（C7）与 `ApiView.map` 通用映射的泄露风险（C9）、`sessionVo` 手工白名单需单独加 `mode`（C10）。
  5. 登记 P-5 表述修正（C8）：`security.py` 无需改动，仅需 `config.py` 新增 scope 与独立依赖。
- **验收标准**：10 条冲突全部有"影响 + 本期处理方式"，且不改变 `design.md` 的接口编号与表名。
- **验证**：逐条比对本文第 8 章与源码行号。
- **追溯**：`design.md` 2.1.2、2.2.2-(5)、2.6.2、6.2 P-5/F-1

### M0-05 `V15` 迁移脚本评审与隔离库空跑
- **角色**：数据库 + Java
- **前置**：M0-01（O3 决定 `expires_at` 默认语义）、M0-02
- **文件**：新增 `project/backend/src/main/resources/db/migration/V15__add_voice_interview.sql`（评审稿）；`docs/功能资料/09-模拟面试/`（回滚说明草稿）
- **实现要点**：
  1. 按 `design.md` 2.3.2 DDL 评审：`interview_session.mode`、`interview_message.answer_source` 及两条 CHECK、`interview_voice_transcription`（唯一键 + 6 个索引/约束 + 4 个外键）、`interview_voice_consent`。
  2. 核对与 `V1` 既有约束兼容：`ck_interview_message_2`（USER 必须有 `question_message_id`）、`ck_interview_message_1`（role 枚举）、`interview_message.content` 为 `TEXT`。
  3. 在隔离库（如 `aijob_voice_dev`）空跑 `V1`→`V15`，确认历史会话/消息可读、`mode` 默认 `TEXT`、`answer_source` 为 `NULL`。
  4. 编写回滚脚本说明（`DROP TABLE` 顺序：consent → transcription → 删列），不做自动回滚。
- **验收标准**：隔离库迁移成功；`SELECT` 历史会话返回 `mode='TEXT'`；回滚脚本可执行且顺序正确。
- **验证**：`docker compose up -d mysql` 后在隔离库执行 Flyway（或临时改 `spring.datasource.url` 启动后端）；`SHOW CREATE TABLE` 核对约束。
- **追溯**：`design.md` 2.3.2、2.3.2"兼容与回填"；`spec.md` 6.1–6.3

### M0-06 Python `speech` 模块骨架与 Fake 契约
- **角色**：Python
- **前置**：无（可与 M0-02 并行）
- **文件**：新增 `project/agent/app/speech/__init__.py`、`base.py`、`schemas.py`（仅目录与空实现骨架）
- **实现要点**：
  1. 定义 `AudioInput`/`AsrResult`/`AudioOutput` 数据类与 `AsrProvider`/`TtsProvider` 协议签名（对齐 `design.md` 2.6.2）。
  2. 定义 Pydantic `TranscribeResult`/`SynthesizeResult`，字段名与 A1/A2 响应体一致。
  3. 定义 `SpeechError`（含 `key`、`http_status`、`retryable`）。
  4. 不接入任何第三方依赖；`speech_provider=fake` 可离线导入。
- **验收标准**：`python -c "from app.speech import base, schemas"` 成功；协议签名与设计一致。
- **验证**：`python -m pytest project/agent/tests -q -k speech`（此时仅导入级用例）。
- **追溯**：`design.md` 2.6.1、2.6.2

### M0-07 测试用例矩阵、验收口径与原型对照
- **角色**：测试 + 前端 + 产品
- **前置**：M0-02、M0-03
- **文件**：`docs/功能资料/09-模拟面试/语音模拟面试_process.md`（追加用例清单）；对照 `docs/功能资料/09-模拟面试/21-语音模拟面试.png`
- **实现要点**：
  1. 把 `design.md` 三章 F01–F40 映射为可执行用例（编号、前置、步骤、期望、验证方式），标注"可自动化/仅手工/需真机"。
  2. 把 6.4 A1–A15 验收项分配到 M1/M2/M3（M1 必过：A1、A2、A3、A7、A9、A10、A11、A13、A15）。
  3. 前端对照原型图确认布局：题目区、播放条、录音按钮与计时、试听/重录/转写、编辑区、隐私对话框；标注与设计的差异项（原型图此前未读取，需人工比对）。
- **验收标准**：40 条失败矩阵每条都有用例编号；原型差异清单 ≤10 项且有结论。
- **验证**：用例清单评审 + 原型走查截图。
- **追溯**：`design.md` 三章、6.4、2.5.4；`spec.md` 8.1–8.5

---

## 3. M1：Fake Provider 下的完整逐题闭环（22 个任务）

### 3.1 数据库（2）

#### M1-01 落地 `V15` 迁移
- **角色**：数据库
- **前置**：M0-05
- **文件**：新增 `project/backend/src/main/resources/db/migration/V15__add_voice_interview.sql`
- **实现要点**：
  1. `ALTER TABLE interview_session ADD COLUMN mode VARCHAR(8) NOT NULL DEFAULT 'TEXT' AFTER status` + `ck_interview_session_mode`。
  2. `ALTER TABLE interview_message ADD COLUMN answer_source VARCHAR(8) NULL DEFAULT NULL AFTER role` + `ck_interview_message_source`、`ck_interview_message_source_role`（`role='USER' OR answer_source IS NULL`）。
  3. 建 `interview_voice_transcription`（状态枚举 6 值、`audio_sha256` 唯一键 `(interview_id,question_message_id,audio_sha256)`、`ck_voice_consumed` 成对约束、4 个外键）。
  4. 建 `interview_voice_consent`（`uk_voice_consent_user_policy`、`revoked_at`）。
  5. 字符集/排序规则/`DATETIME(3)`/`CHAR(36) ascii_bin` 与 `V1`/`V14` 风格一致。
- **验收标准**：迁移在开发库与隔离库均可执行；`SHOW CREATE TABLE` 与设计 DDL 一致；历史数据无变更。
- **验证**：启动后端触发 Flyway；`mysql -e "SHOW CREATE TABLE interview_voice_transcription\G"`。
- **追溯**：`design.md` 2.3.2；`spec.md` 6.1–6.3

#### M1-02 回滚与清理脚本（运维手册）
- **角色**：数据库
- **前置**：M1-01
- **文件**：新增 `docs/功能资料/09-模拟面试/语音模拟面试-回滚与清理.md`
- **实现要点**：
  1. 回滚顺序：`interview_voice_consent` → `interview_voice_transcription` → `interview_message` 删列 → `interview_session` 删列（先删 CHECK 再删列）。
  2. 清理 SQL 模板：`expires_at < now-24h AND consumed_at IS NULL` 批量删除（带 `LIMIT` 与事务）。
  3. 说明"已消费记录保留 7 天且 `text` 置空"的执行语句。
- **验收标准**：在隔离库演练一次回滚与清理，记录耗时与影响行数。
- **验证**：隔离库执行脚本 + 演练记录归档。
- **追溯**：`design.md` 2.3.2"惰性过期与清理"、6.2 DB-2；`spec.md` 5.7.1-3

### 3.2 Python `speech` 模块（5）

#### M1-03 Provider 协议与数据模型
- **角色**：Python
- **前置**：M0-06
- **文件**：`project/agent/app/speech/base.py`、`schemas.py`、`__init__.py`
- **实现要点**：
  1. `AsrProvider.transcribe(audio, *, language="zh")`、`TtsProvider.synthesize(text, *, voice, fmt)`。
  2. `AudioInput` 携带 `data`、`mime_type`、`format`、`duration_ms`；`AsrResult` 携带 `text`、`provider`、`model`、`confidence`。
  3. 文本归一化：去首尾空白、合并连续空白；空结果统一返回 `""`（由 Service 映射 `EMPTY`）。
- **验收标准**：类型注解完整；`python -c "import app.speech"` 无警告。
- **验证**：`python -m pytest project/agent/tests -q -k speech_schema`。
- **追溯**：`design.md` 2.6.2

#### M1-04 Fake ASR / TTS Provider
- **角色**：Python
- **前置**：M1-03
- **文件**：新增 `project/agent/app/speech/fake.py`
- **实现要点**：
  1. `FakeAsrProvider`：按 `sha256(audio)[:8]` 派生确定文本（如 `"（模拟转写）这是一段语音回答，编号 3f2a91cc"`）。
  2. 静音判定：`len(data) < 2KB` 或 `duration_ms < 1000` 时返回 `""`（覆盖 F11/空识别）。
  3. `FakeTtsProvider`：返回内置 1 秒可播放 mp3 常量（base64），`chars=len(text)`，`latency_ms=30`，可断言。
  4. 不写任何文件、不访问网络。
- **验收标准**：同字节音频两次转写结果完全一致；静音样本返回空；TTS 输出可被 `audio/mpeg` 播放器识别。
- **验证**：`python -m pytest project/agent/tests -q -k fake_speech`。
- **追溯**：`design.md` 2.6.2"Fake provider 行为"；`spec.md` 4.4-1

#### M1-05 `SpeechService`：校验、调度、指标、脱敏
- **角色**：Python
- **前置**：M1-03、M1-04
- **文件**：新增 `project/agent/app/speech/service.py`
- **实现要点**：
  1. 校验顺序：字节大小 → MIME 白名单 → 魔数（`webm/ogg`、`mp4` 的 `ftyp`）→ `duration_ms` 范围 → 文本长度（≤500）。
  2. 时长二次估算与服务端声明差异 >30% 拒绝（返回 400）。
  3. Provider 选择：`effective_speech_provider`（`auto` 有 key 用 qwen，否则 fake）。
  4. `asyncio.Semaphore(speech_concurrency=4)` 保护供应商配额，满则 429。
  5. 结构化日志：`op`、`outcome`、`latency_ms`、`audio_duration_ms`、`text_chars`、`sha8`；**不记录音频与全文**。
  6. 错误映射为 `SpeechError`（413/415/400/429/503/504）。
- **验收标准**：8MB+1 字节触发 413；`text/plain` 伪装触发 415；超长文本触发 400；日志无音频/全文。
- **验证**：`python -m pytest project/agent/tests -q -k speech_service`（含 `caplog` 断言）。
- **追溯**：`design.md` 2.6.2–2.6.4；`spec.md` 4.3-2、4.3-5

#### M1-06 同步路由与 `ai:speech` 作用域
- **角色**：Python
- **前置**：M1-05、M0-02
- **文件**：新增 `project/agent/app/speech/router.py`；修改 `project/agent/app/api.py`、`project/agent/app/config.py`
- **实现要点**：
  1. `POST /internal/ai/v1/speech/transcribe`（multipart：`audio`、`requestId`、`userId`、`mimeType`、`durationMs`、`language`）。
  2. `POST /internal/ai/v1/speech/synthesize`（JSON：`requestId`、`userId`、`text`、`voice`、`format`）。
  3. 新增独立依赖 `speech_principal_dependency`（`settings.speech_scope`），**不改** `security.py` 校验逻辑（见 C8）。
  4. 在 `create_app` 的 `return app` 前 `app.include_router(build_speech_router(...))`；路由内 `principal.assert_task(requestId)`。
  5. 响应统一 `CommonResult`（`code=200`，含 `traceId`）；异常走既有 `ApiError` 处理器。
  6. 不进入 `RunService` 队列、不写 `agent_runs.db`、不触发回调。
- **验收标准**：`ai:speech` 令牌 200；`ai:run` 令牌 403；`requestId` 不在 `taskIds` 时 403；响应结构含 `traceId`。
- **验证**：`python -m pytest project/agent/tests -q -k speech_api`（TestClient + 自签令牌）。
- **追溯**：`design.md` 2.2.2-(7)(8)、2.2.4、2.7；`spec.md` 4.3-1

#### M1-07 `speech_*` 配置项与生产校验
- **角色**：Python
- **前置**：M1-03
- **文件**：修改 `project/agent/app/config.py`、根 `requirements.txt`（暂不加 dashscope，M2-01 加）
- **实现要点**：
  1. 新增 `speech_provider`（`auto/fake/qwen`）、`speech_api_key`（`repr=False`，别名 `AI_BAILIAN_API_KEY`/`SPEECH_API_KEY`）、`speech_base_url`、`speech_asr_model`、`speech_tts_model`、`speech_tts_voice`、`speech_timeout_seconds=8.0`、`speech_tts_timeout_seconds=6.0`、`speech_max_bytes`、`speech_max_audio_ms=180000`、`speech_max_tts_chars=500`、`speech_concurrency=4`、`speech_scope="ai:speech"`、`speech_vad_silence_ms=400`（预留不启用）。
  2. 新增 `effective_speech_provider` 属性（对齐 `effective_llm_provider` 的实现风格）。
  3. `validate_runtime_security()` 追加一条：`app_env=prod` 且 `effective_speech_provider=='fake'` 时抛错。
- **验收标准**：`prod + fake` 启动失败并给出明确原因；日志不打印 `speech_api_key`。
- **验证**：`python -m pytest project/agent/tests -q -k speech_config`。
- **追溯**：`design.md` 2.6.3、2.6.2-3

### 3.3 Java 编排（8）

#### M1-08 `speechToken` 与 `SpeechAgentClient`
- **角色**：Java
- **前置**：M0-02
- **文件**：修改 `project/backend/src/main/java/com/aijob/backend/task/InternalJwtService.java`；新增 `.../interview/voice/SpeechAgentClient.java`
- **实现要点**：
  1. `InternalJwtService.speechToken(String requestId)`：复用私有 `token()`，`scope="ai:speech"`、`taskIds=[requestId]`、TTL 60s（与 `runToken` 的 300s 区分）。
  2. `SpeechAgentClient.transcribe(...)` 与 `.synthesize(...)`：沿用 `WebClientAgentClient` 的调用风格（`app.agent.base-url`，Bearer `speechToken`）。
  3. 超时：转写 12s、合成 10s；连接失败 → 503，读超时 → 503 + `details.reason=TIMEOUT`（按 O10 结论）。
  4. 响应解析：`CommonResult.data` 中的 `text`/`audioBase64`/`mimeType`/`chars`/`latencyMs`。
  5. 日志只记录 `requestId`、`latencyMs`、`outcome`、`textChars`。
- **验收标准**：单测覆盖 token 的 scope/TTL/`taskIds`；Agent 500/超时分别映射；不打印令牌与音频。
- **验证**：`mvn -q -Dtest=InternalJwtServiceTest+SpeechAgentClientTest test`。
- **追溯**：`design.md` 2.2.4、2.1.1 交互契约、2.7；`spec.md` 4.3-3

#### M1-09 `VoiceMapper`、状态机与惰性过期
- **角色**：Java
- **前置**：M1-01
- **文件**：新增 `.../interview/voice/VoiceMapper.java`、`.../interview/voice/VoiceStatus.java`（枚举）、`.../interview/voice/VoiceTranscription.java`（行对象）
- **实现要点**：
  1. 方法：`insertPending`、`findBySha`、`findById`、`findLatestByQuestion`、`findUsable`、`markSucceeded/markEmpty/markFailed/markDiscarded/markExpired`、`markConsumed(consumedAt, messageId)`、`deleteExpiredBefore`、`clearTextOfConsumedBefore`。
  2. `isUsable = status==SUCCEEDED && consumedAt==null && now<expiresAt`（唯一判定入口，供 V6 复用）。
  3. 惰性过期：读取时 `expires_at < UTC_TIMESTAMP(3)` 且状态为 `PENDING/SUCCEEDED/EMPTY` → `EXPIRED` + `text=NULL`。
  4. **禁止**：任何语句不得触碰 `interview_session` 的 `version`、不得写 `interview_message`（`design.md` 2.1.3-2 要点 3）。
  5. 时间统一 `UTC_TIMESTAMP(3)`，与 `V1` 风格一致。
- **验收标准**：状态迁移合法路径全覆盖；非法迁移被拒绝；过期记录读取即置 `EXPIRED` 且文本清空。
- **验证**：`mvn -q -Dtest=VoiceMapperTest test`；隔离库断言 `version` 未变。
- **追溯**：`design.md` 2.3.3、2.8；`spec.md` 6.3-2、5.4.1-5

#### M1-10 转写上传主流程（校验/幂等/晚到/限流）
- **角色**：Java
- **前置**：M1-09、M1-08、M1-06
- **文件**：新增 `.../interview/voice/VoiceTranscriptionService.java`、`.../interview/voice/VoiceLimits.java`
- **实现要点**（严格按 `design.md` 2.1.3-② 顺序）：
  1. 鉴权 → 限流检查（M1 阶段先接空实现，M2-02 接入 Redis）→ `file.getSize()` 先于读入校验 → MIME 白名单 → 魔数 → `durationMs` 范围与差异校验。
  2. 计算 `audio_sha256`；`findBySha`：`SUCCEEDED` 且未过期 → 直接返回（`reused=true`）；`PENDING` 且未超时 → 409 `TASK_IN_PROGRESS`（`details.recordId`）。
  3. `lockSession`（复用 `InterviewMapper.lockSession`）校验归属/状态/版本/最新题；不匹配 → 插入 `DISCARDED` + 409 `STALE_INPUT`。
  4. 插入 `PENDING`（唯一键冲突则回读判状态返回）。
  5. **事务外**调用 Agent；失败 → `FAILED` + `error_key` + 503；空文本 → `EMPTY` + 200；成功 → `SUCCEEDED` + `expires_at=now+30min`。
  6. 返回 `VoiceTranscriptionVO`（手工白名单：`id/status/text/questionMessageId/interviewVersion/durationMs/expiresAt/reused`，**不得**直接映射整行，见 C9）。
  7. 同题仅允许一个 `PENDING`（`SELECT ... FOR UPDATE` 会话行），锁持有时间不包住外部调用。
- **验收标准**：F11/F15/F16/F17/F19/F20 六类场景行为与设计一致；`ai_task` 计数不变；`interview_session.version` 不变。
- **验证**：`mvn -q -Dtest=VoiceTranscriptionServiceTest test`。
- **追溯**：`design.md` 2.1.3-②、2.2.2-(2)、2.8、三章 F08–F20；`spec.md` 4.1-4、5.4.1、5.4.3

#### M1-11 `VoiceInterviewController`：V1–V5 五个接口
- **角色**：Java
- **前置**：M1-10、M1-12、M1-09
- **文件**：新增 `.../interview/voice/VoiceInterviewController.java`、`.../interview/voice/VoiceCapabilityService.java`（或并入 Controller）
- **实现要点**：
  1. V1 `GET /api/v1/interviews/{id}/voice-capability`：返回 `asrEnabled/ttsEnabled/consentRequired/consentVersion/consented/maxDurationSeconds/maxAudioBytes/allowedMimeTypes/transcriptionTtlSeconds`（`consented` 为 C1 结论所需，若豁免则不返回）。
  2. V2 `POST .../voice-transcriptions`（multipart）：字段 `audio/questionMessageId/expectedVersion/durationMs/mimeType`。
  3. V3 `GET .../voice-transcriptions?questionMessageId=`：只返回 `PENDING/SUCCEEDED/EMPTY`；`DISCARDED/EXPIRED` 返回 `data=null` + `status`。
  4. V4 `POST .../voice-transcriptions/{tid}/discard`：已消费 → 409 `INVALID_STATE`；否则 `DISCARDED` + 清空 `text`，返回 `true`。
  5. V5 `POST .../messages/{mid}/speech`：校验消息属于本人会话且 `role=ASSISTANT`；返回 `audio/mpeg` + 响应头；失败返回 JSON 错误体。
  6. 全部接口走 `CurrentUserResolver`；越权统一 404（不区分不存在与非本人）。
- **验收标准**：5 个接口主路径 200；越权 404；V4 已消费 409；V5 非 ASSISTANT 消息 404/400。
- **验证**：T-07 接口集成脚本；`mvn -q -Dtest=VoiceInterviewControllerTest test`。
- **追溯**：`design.md` 2.2.1、2.2.2-(1)–(5)；`spec.md` 4.3-1、5.3、5.4

#### M1-12 `QuestionSpeechService`：分句串行合成 + LRU 缓存
- **角色**：Java
- **前置**：M1-08、M1-06
- **文件**：新增 `.../interview/voice/QuestionSpeechService.java`、`.../interview/voice/SpeechAudioCache.java`
- **实现要点**：
  1. 分句：按 `。！？!?；;` 与换行切分，单段 ≤120 字，超长段再按逗号/空格硬切；累计超 500 字/段调用需分段。
  2. 逐段**串行**调用 A2，拼接 mp3 字节后一次性返回（本期不做流式/并行，见 C4 与第 9 章）。
  3. 缓存键 `(interviewId, messageId, voiceId, sha256(text))`；LRU ≤200 条、单条 ≤512KB、TTL ≤5 分钟；不落库不落盘。
  4. 文本为空或单段超 500 字 → 400；合成失败 → 503（前端降级 `speechSynthesis`）。
  5. 响应头：`Cache-Control: no-store`、`X-Speech-Voice`、`X-Speech-Chars`。
- **验收标准**：3000 字长题可完整合成（分段数可断言）；二次请求命中缓存不调用 Agent；超长单段 400。
- **验证**：`mvn -q -Dtest=QuestionSpeechServiceTest test`。
- **追溯**：`design.md` 2.1.3-④、2.2.2-(5)、2.4 D5、F35；`spec.md` 5.2

#### M1-13 扩展 `/answers`：来源与转写一次性消费
- **角色**：Java
- **前置**：M1-09、M1-01
- **文件**：修改 `.../interview/InterviewService.java`（`answer` 方法）、`.../interview/InterviewMapper.java`（`insertMessage`）、`.../interview/InterviewController.java`（透传无签名变更，仅确认）
- **实现要点**：
  1. `answerSource = Data.text(body,"answerSource",false,8)`（缺省视为 `TEXT`）；`voiceTranscriptionId = Data.uuid(body,"voiceTranscriptionId",false)`。
  2. `VOICE` 时：`VoiceMapper.findUsable(id)` 校验 `user/interview/question` 三者归属 + `isUsable`；不满足 → 409 `STALE_INPUT` + `details.reason=TRANSCRIPTION_EXPIRED|CONSUMED|DISCARDED`；缺 `voiceTranscriptionId` → 400。
  3. 插入 USER 消息时写 `answer_source`；**同一事务内** `markConsumed(consumedAt, messageId)`。
  4. `insertMessage` 改造（C7）：新增 `answerSource` 参数，ASSISTANT 调用点（`InterviewResultHandler:142`）显式传 `null`，USER 调用点传 `TEXT`/`VOICE`。
  5. **不改**既有重试分支语义（`InterviewService:132-151`，内容必须一致）；`agentMessages()`（246-256）**不**增加 `answerSource`，保证评分/报告口径不变。
  6. 未传新字段时：`answer_source='TEXT'`，行为与当前完全一致。
- **验收标准**：VOICE 提交后消息 `answer_source='VOICE'` 且转写 `consumed_at/consumed_message_id` 非空；重复提交不二次消费；TEXT 路径回归零差异；Agent 载荷无来源字段。
- **验证**：`mvn -q -Dtest=InterviewAnswerVoiceTest+InterviewAnswerTextRegressionTest test`。
- **追溯**：`design.md` 2.1.3-③、2.2.2-(6)、2.8、D2；`spec.md` 5.5.1、6.2、6.4

#### M1-14 `mode` / `answerSource` 回显
- **角色**：Java
- **前置**：M1-13
- **文件**：修改 `.../interview/InterviewService.java`（`sessionVo` 228-244）
- **实现要点**：
  1. `sessionVo` 手工白名单新增 `result.put("mode", session.get("mode"))`（`detail` 与 `list` 共用，一处生效）。
  2. 消息回显：`sessionVo` 使用 `view::map`（通用驼峰映射），`answer_source` 会**自动**成为 `answerSource`，历史数据为 `null` → 前端按 `TEXT` 处理。**不要**为此改 `ApiView`（避免影响其他模块）。
  3. 确认 `agentMessages()` 与报告 payload 不含 `answerSource`（由 M1-13 保证，此处只做断言）。
- **验收标准**：`GET /interviews/{id}` 返回 `mode` 与每条 USER 消息的 `answerSource`；历史会话 `mode='TEXT'`、历史消息 `answerSource=null`；报告接口响应字段无变化。
- **验证**：`mvn -q -Dtest=InterviewSessionVoTest test` + 手工 `curl` 两个接口比对。
- **追溯**：`design.md` 1.1.2、2.3.3 模型约束 2；`spec.md` 4.5-1/3、6.1、6.2

#### M1-15 上传超限映射 413
- **角色**：Java
- **前置**：无（可并行）
- **文件**：修改 `project/backend/src/main/java/com/aijob/backend/common/GlobalExceptionHandler.java`
- **实现要点**：
  1. 新增 `@ExceptionHandler(MaxUploadSizeExceededException.class)` → 413 `PAYLOAD_TOO_LARGE`（实读确认当前无此处理器）。
  2. 同时兜底 `MultipartException`（避免不同包装类型落到 500）。
  3. 日志只记 `error=UPLOAD_TOO_LARGE`，不记文件内容。
- **验收标准**：上传 >10MB 返回 413（不再 500）；错误体含 `traceId`；其他接口超限行为一致。
- **验证**：`mvn -q -Dtest=GlobalExceptionHandlerTest test` + 手工上传 11MB 文件。
- **追溯**：`design.md` 1.2.5"关键缺口"、F09；`spec.md` 5.3.1-6

### 3.4 前端（7）

#### M1-16 语音接口封装、类型与二进制获取
- **角色**：前端
- **前置**：M1-11、M1-12、M0-02
- **文件**：新增 `project/frontend/src/api/voiceInterviews.ts`；修改 `src/api/request.ts`（新增 `requestBlob`）、`src/api/types.ts`、`src/api/interviews.ts`
- **实现要点**：
  1. `requestBlob(path, options)`：`fetch` + Bearer + 401 刷新重试 + 非 2xx 解析 JSON 错误 + 返回 `Blob`；**不复用** `downloadBinary`（后者会落盘下载，C5）。
  2. `voiceInterviews.ts`：`capability(id)`、`transcribe(id, formData)`（multipart，**不**带 `Idempotency-Key`）、`getTranscription(id, qid)`、`discard(id, tid)`、`speech(id, mid)`（→ `requestBlob`）。
  3. `types.ts`：新增 `VoiceCapabilityVO`、`VoiceTranscriptionVO`、`VoiceTranscriptionStatus`；`InterviewVO.mode?: 'TEXT'|'VOICE'`；`MessageVO.answerSource?: 'TEXT'|'VOICE'|null`。
  4. `interviews.ts`：`answer` 增加可选 `answerSource?: 'TEXT'|'VOICE'`、`voiceTranscriptionId?: string`（C6）。
- **验收标准**：`npx vue-tsc --noEmit` 零错误；五个方法签名与 V1–V5 一致；`transcribe` 请求为 `FormData`。
- **验证**：`npx vue-tsc --noEmit` + 用 Mock 响应单测。
- **追溯**：`design.md` 2.2.1、2.1.2 文件清单；`spec.md` 4.5-3

#### M1-17 前端语音工具：格式协商与上限常量
- **角色**：前端
- **前置**：无（可与 M0 并行）
- **文件**：新增 `src/utils/voice/formats.ts`、`src/utils/voice/limits.ts`
- **实现要点**：
  1. `pickMimeType()`：按 `audio/webm;codecs=opus` → `audio/webm` → `audio/ogg;codecs=opus` → `audio/mp4` 顺序用 `MediaRecorder.isTypeSupported` 探测，返回首个可用项与候选列表。
  2. `limits.ts`：默认值与第 0.3 节一致，且支持"用 V1 返回值覆盖"的初始化函数。
  3. 前端魔数预检（可选）：`webm`(`1A 45 DF A3`)、`ogg`(`4F 67 67 53`)、`mp4`(`ftyp`)。
- **验收标准**：Chrome/Edge 命中 `webm`；Safari 命中 `mp4`；无可用格式时返回 `null`（入口隐藏）。
- **验证**：`npx vue-tsc --noEmit` + Vitest（T-05）。
- **追溯**：`design.md` 2.4 D6、2.5.2；`spec.md` 5.3.1-6

#### M1-18 `useVoiceRecorder`：采集、计时、上限、释放
- **角色**：前端
- **前置**：M1-17
- **文件**：新增 `src/composables/voice/useVoiceRecorder.ts`
- **实现要点**：
  1. 状态：`idle/requesting/recording/recorded/denied/unavailable`；错误区分 `NotAllowedError`、`NotFoundError`、`NotReadableError`。
  2. `start()` 内**先** `await stopSpeech()` 再 `getUserMedia({ audio: true })`（spec 5.3.1-2；**首版不加 `echoCancellation`/`noiseSuppression`**，属第二阶段，见第 9 章）。
  3. `MediaRecorder` 用 `pickMimeType()`，`audioBitsPerSecond: 32000`；`stop()` 后在 `onstop` 生成 `Blob` 并 `track.stop()`。
  4. 计时 250ms；到 `maxDurationSeconds` 自动停止并置 `reachedLimit=true`；`<1000ms` 标记 `tooShort`（不发上传）。
  5. `visibilitychange`（hidden）、路由离开、`beforeunload`、`onBeforeUnmount` 全部停止并释放；`audioUrl` 用 `URL.revokeObjectURL` 回收。
  6. 暴露 `durationMs`、`blob`、`mimeType`、`audioUrl`、`reset()`。
- **验收标准**：权限拒绝、无麦克风、被占用三类错误有区分文案；3 分钟自动停止；隐藏标签页后麦克风指示灯熄灭；重录后旧 URL 已回收。
- **验证**：手工（Chrome/Edge 权限、切页、上限）+ Vitest 计时逻辑。
- **追溯**：`design.md` 2.5.2、2.1.3-1、F01–F07；`spec.md` 5.3

#### M1-19 `useQuestionSpeech`：播放控制与降级
- **角色**：前端
- **前置**：M1-16
- **文件**：新增 `src/composables/voice/useQuestionSpeech.ts`
- **实现要点**：
  1. 单例 `Audio`；`play(messageId)` 前先 `stop()` 并递增请求序号，丢弃过期响应（防重复点击叠播，F33）。
  2. `stop()` 复位 `currentTime=0` 并 `release`；`pause()`/`resume()`/`replay()`。
  3. `play()` 被浏览器拦截（`NotAllowedError`）→ 不重试，提示"请点击播放按钮"（F32）。
  4. V5 失败（503/网络/超时）→ 若 `window.speechSynthesis` 可用，用 `SpeechSynthesisUtterance`（`lang='zh-CN'`）本地朗读并标注"使用浏览器语音"；否则仅展示文字（F30/F31）。
  5. 暴露 `playing`、`paused`、`degraded`、`progress`。
- **验收标准**：快速点击 5 次只播一路；录音开始时播放立即停止且进度归零；503 时降级成功。
- **验证**：手工（断网/停 Agent 模拟 503）+ Vitest 防抖。
- **追溯**：`design.md` 2.1.3-④、F30–F34；`spec.md` 5.2

#### M1-20 `useVoiceAnswer`：作答状态机与恢复
- **角色**：前端
- **前置**：M1-16、M1-18
- **文件**：新增 `src/composables/voice/useVoiceAnswer.ts`
- **实现要点**：
  1. 状态机严格按 `design.md` 2.1.3-1：`ANSWERABLE/PLAYING/RECORDING/RECORDED/TRANSCRIBING/TRANSCRIBED/EDITING/SUBMITTING/WAITING_NEXT/TEXT_MODE`。
  2. `transcribe()`：组装 `FormData`（`audio/questionMessageId/expectedVersion/durationMs/mimeType`）→ 按 `status` 分支：`SUCCEEDED` → `EDITING`；`EMPTY` → 提示重录并回 `RECORDED`；失败 → 保留 `blob` 回 `RECORDED`。
  3. `restore()`：进入题目时调 V3，`SUCCEEDED` 且未过期 → 回填候选文本并置 `EDITING`；`PENDING` → 置 `TRANSCRIBING` 且禁用重复上传；`null` → `ANSWERABLE`。
  4. `discard()`：调 V4 + 清空候选与 `transcriptionId`（失败不阻塞，仅告警）。
  5. `toTextMode()`：文本回填到同一编辑区，不调用语音接口；`submit()` 只透传 `answerSource/voiceTranscriptionId`，**不复制** 409 刷新逻辑。
  6. 不写 `localStorage`/`sessionStorage`（D13）。
- **验收标准**：状态迁移无死角（含失败回退）；刷新后候选文本回填并标"需确认"；切文字后文本保留。
- **验证**：Vitest 状态迁移用例（T-05）+ 手工刷新恢复。
- **追溯**：`design.md` 2.1.3-1、2.1.3-⑤、2.5.1；`spec.md` 5.1、5.4.2、5.4.3

#### M1-21 语音作答五组件
- **角色**：前端
- **前置**：M1-18、M1-19、M1-20
- **文件**：新增 `src/views/interview/components/QuestionAudioPlayer.vue`、`VoiceAnswerPanel.vue`、`RecordingControls.vue`、`TranscriptionEditor.vue`、`VoicePrivacyDialog.vue`
- **实现要点**：
  1. 组件职责与 `design.md` 2.1.2 表格一致；组件内不直接发 HTTP（除受控的父级回调）。
  2. `RecordingControls`：开始/停止/计时/试听/重录；`TranscriptionEditor`：候选文本 + "识别结果需确认" + 确认提交/重录/放弃。
  3. `VoicePrivacyDialog`：一次性告知（第三方转写、识别后删除音频、仅保存确认文本）+ 同意/拒绝；文案版本展示。
  4. 可访问性：按钮 `aria-label`（如"开始录音，最多 3 分钟"）；状态变化才播报，不逐秒 `aria-live`。
  5. 响应式：≤700px 全宽（对齐 `InterviewView.vue` 现有断点风格）。
  6. 失败出口组件：重试/重录/改用文字三按钮 + 文案映射（M1-22 的 `format.ts`）。
- **验收标准**：桌面与 390px 宽度可用；键盘可完成"录音→停止→转写→提交"；无控制台错误。
- **验证**：手工走查 + 原型对照（M0-07 结论）。
- **追溯**：`design.md` 2.5.1、2.5.4；`spec.md` 5.1.2、5.3.2、5.4.2

#### M1-22 `InterviewView` 接入、隐私入口与失败文案
- **角色**：前端
- **前置**：M1-21、M1-14
- **文件**：修改 `src/views/interview/InterviewView.vue`、`src/utils/format.ts`
- **实现要点**：
  1. 移除第 505 行"当前仅支持文字面试"硬编码提示（D14），改为能力驱动。
  2. 进入页面并行拉取 V1（3s 超时，失败则隐藏语音入口，文字完全可用）；`asrEnabled=false`、无 `MediaRecorder`、`!window.isSecureContext` 时禁用/隐藏入口并给原因。
  3. 模式切换：`VOICE ⇄ TEXT` 不改变会话状态与版本；`candidateText` 与 `answerContent` 共享编辑区。
  4. `submitAnswer` 增加可选来源参数（`answerSource/voiceTranscriptionId`），复用既有 409 刷新与"不自动重发"逻辑（`InterviewView.vue:344-354`）。
  5. 刷新恢复：`watch/onMounted` 取 `interviewId` 后调用 `restore()`；题目已变化时不回填旧候选。
  6. `format.ts` 增加语音失败文案映射：`SPEECH_EMPTY`、`SPEECH_TIMEOUT`、`SPEECH_UNAVAILABLE`、`SPEECH_STALE`、`SPEECH_RATE_LIMITED`、`SPEECH_TOO_LARGE`、`SPEECH_FORMAT`、`SPEECH_CONSENT`。
- **验收标准**：未启用语音的会话页面无语音控件；语音入口按能力显示；错误提示均带可执行出口；历史文字会话展示不变。
- **验证**：手工（Chrome/Edge）+ `npx vue-tsc --noEmit`。
- **追溯**：`design.md` 2.5.1、2.5.3、D14、F01–F03/F19；`spec.md` 5.1、5.8、4.5

---

## 4. M2：真实能力接入与生产就绪（8 个任务）

#### M2-01 真实 ASR / TTS Provider（DashScope）
- **状态（2026-11-20）**：ASR 已接入 `QwenAsrProvider`（Base64 Data URI 同步 HTTP，M4A 实测通过）；TTS 仍为 Fake，未接入真实 TTS；WebM/Opus 与长录音待真实浏览器验收。
- **角色**：Python
- **前置**：M1-03、M0-01（O1）
- **文件**：新增 `project/agent/app/speech/qwen.py`；修改 `project/agent/requirements.txt`（或根 `requirements.txt`）、`config.py`
- **实现要点**：
  1. `QwenAsrProvider`：按 `speech_base_url`/`speech_asr_model` 调用；首版非流式（一次性送整段）。
  2. `QwenTtsProvider`：按 `speech_tts_model`/`speech_tts_voice` 合成，返回 mp3 字节。
  3. 容器格式解封装（webm/ogg/mp4）与 16kHz/PCM 转换是否需要，取决于供应商是否接受容器格式——**先实测**再决定是否引入解码依赖【待验证】。
  4. 供应商错误体只保留 `error_key` + HTTP 状态；不记录响应原文。
  5. `auto` 模式下无 key 回落 fake，但 `capability` 必须如实返回 `asrEnabled=false`（禁止静默假结果）。
- **验收标准**：三种浏览器格式样本均可转写；合成音频可播放；密钥不出现在日志。
- **验证**：`python -m pytest project/agent/tests -q -k qwen`（需账号，标注跳过后手工实测）。
- **追溯**：`design.md` 2.6.2、2.6.3、2.6.4、D9、D10；`spec.md` 4.3-3

#### M2-02 限流与配额（Redis + Agent 信号量）
- **角色**：Java + Python
- **前置**：M1-10
- **文件**：新增 `.../interview/voice/VoiceRateLimiter.java`；修改 `VoiceTranscriptionService`、`QuestionSpeechService`
- **实现要点**：
  1. 键与阈值：`career:rate:voice:asr:{userId}` 30 次/600s；`career:rate:voice:asr:day:{userId}` 200 次/86400s；`career:rate:voice:tts:chars:{userId}` 20000 字/86400s。
  2. 用 `INCR` + `EXPIRE`（仅首次设 TTL，避免计数残留）；命中阈值 → 429 `RATE_LIMITED` + `Retry-After`。
  3. **Redis 故障降级放行 + 告警日志**（与 `RateLimitService` 登录限流的"503"策略不同，必须在代码注释说明差异，D8）。
  4. Agent 侧信号量默认 4，满则 429；Java 侧同步接口信号量默认 16（防线程耗尽）。
- **验收标准**：第 31 次请求 429 且带 `Retry-After`；停 Redis 后语音仍可用且有告警；TTS 字数累计正确。
- **验证**：固定时钟/固定计数单测 + 停容器手工验证。
- **追溯**：`design.md` 2.2.3、D8、4.7；`spec.md` 4.1-3、5.8.3-1

#### M2-03 隐私同意落库与撤回
- **角色**：Java + 前端
- **前置**：M0-03（C1 结论）、M1-11、M1-01
- **文件**：若采纳 V7：新增 V7 端点（`VoiceInterviewController`）；`VoiceMapper` 增 `insertConsent/findConsent/revokeConsent`；前端 `voiceInterviews.ts` 增方法、`VoicePrivacyDialog` 对接
- **实现要点**：
  1. 同意写 `interview_voice_consent`（`user_id + policy_version` 唯一，`agreed_at`）。
  2. V1 返回 `consented`（当前政策版本是否已同意且未撤回）。
  3. V2 前置校验同意；未同意/已撤回 → 409 `INVALID_STATE` + `details.reason=CONSENT_REQUIRED|CONSENT_REVOKED`。
  4. 撤回置 `revoked_at`；已提交文本不受影响（不删除历史消息）。
  5. 若 M0-03 选择"豁免"路径，则本任务改为"配置开关 + 前端告知留存"，并在设计文档标注延期到 M3。
- **验收标准**：首次录音前必须弹告知；拒绝后仅文字可用；撤回后新录音 409；重复同意幂等。
- **验证**：`mvn -q -Dtest=VoiceConsentTest test` + 手工拒绝/同意/撤回。
- **追溯**：`design.md` 2.9、D12、F19/F39；`spec.md` 4.3-4、5.7

#### M2-04 过期清理与惰性过期加固
- **角色**：Java
- **前置**：M1-09
- **文件**：新增 `.../interview/voice/VoiceTranscriptionSweeper.java`；修改 `VoiceMapper`
- **实现要点**：
  1. `@Scheduled(fixedDelay=600000)`（`BackendApplication` 已 `@EnableScheduling`）。
  2. 删除 `expires_at < now-24h AND consumed_at IS NULL`；已消费记录保留 7 天后删除。
  3. 删除前将已消费记录 `text=NULL`（只留 `status/audio_sha256/时间`）。
  4. 失败只记 `error=SWEEP_FAILED` 告警，不阻断业务，下一轮重试。
  5. 批量 `LIMIT`，避免长事务。
- **验收标准**：构造过期数据可被清理；清理失败不影响接口；已消费记录文本被清空。
- **验证**：`mvn -q -Dtest=VoiceTranscriptionSweeperTest test` + 隔离库手工插入过期行走一轮。
- **追溯**：`design.md` 2.3.2"惰性过期与清理"、F21；`spec.md` 5.7.1-3、5.7.3-2

#### M2-05 能力探测与降级联动（含熔断）
- **角色**：Java
- **前置**：M1-11、M2-01
- **文件**：修改 `.../interview/voice/VoiceCapabilityService.java`（含失败率滑动窗口/熔断）
- **实现要点**：
  1. `asrEnabled = 配置开关 && 供应商可用 && 熔断未打开`；`ttsEnabled` 同理。
  2. 5 分钟窗口失败率 >10% 打开熔断（默认 60s 半开探测）；熔断期间 capability 返回 `false`。
  3. 配置开关：`app.voice.asr-enabled`、`app.voice.tts-enabled`、`app.voice.consent-required`。
  4. 熔断状态变化记结构化日志（`voice_circuit=OPEN/CLOSED`）。
- **验收标准**：停 Agent 后 capability 在窗口期内变 `false`；恢复后自动回 `true`；前端入口随之隐藏/恢复。
- **验证**：停止 Agent 进程手工验证 + 单测（注入失败）。
- **追溯**：`design.md` 4.7、F40、2.5.3；`spec.md` 5.8.1、5.8.3-2

#### M2-06 观测指标与结构化日志
- **角色**：Java + Python
- **前置**：M1-10、M1-12、M2-01
- **文件**：修改 `VoiceTranscriptionService`、`QuestionSpeechService`、`speech/service.py`
- **实现要点**：
  1. Java 字段：`voice_op=ASR|TTS`、`outcome`、`latency_ms`、`audio_duration_ms`、`text_chars`、`sha8`、`reused`、`interview_id`（不记题目全文）。
  2. Python 字段对齐 `design.md` 2.6.4；`replay/edit` 派生指标：`asr_attempts`、`consumed_text_diff_len`（首版用日志统计）。
  3. 不新增监控依赖，指标以日志形式输出，阈值在 M3-01 固化。
- **验收标准**：一次完整语音作答可在日志中还原耗时链路（V2→A1→V6），且无敏感内容。
- **验证**：日志抽查（T-09 的一部分）+ 单测断言字段。
- **追溯**：`design.md` 2.6.4、4.7；`spec.md` 4.4-3

#### M2-07 隐私文案、政策版本与限额参数定稿
- **角色**：产品 + 合规 + 前端
- **前置**：M0-01（O2/O3/O6/O7）
- **文件**：`src/views/interview/components/VoicePrivacyDialog.vue`（文案）；`voiceInterviews` 常量；`design.md` 6.5 更新
- **实现要点**：
  1. 定稿告知文案：第三方转写范围、识别后删除音频、仅保存用户确认文本、不提供音频回放。
  2. 定稿 `policyVersion`（如 `voice-2026-11`）与升级策略（版本变更需重新同意）。
  3. 定稿上限/配额参数并回写第 0.3 节三处常量（Java/Python/前端）。
  4. 定稿是否将 U13 纳入本期验收（影响 M3-04 范围）。
- **验收标准**：文案经合规确认；`policyVersion` 在 V1/同意记录/前端展示三处一致；三处常量用单测断言相等。
- **验证**：合规签署 + 常量一致性单测（T-05）。
- **追溯**：`design.md` 2.2.1、6.5、4.5-2；`spec.md` 5.7.1、3.1-4

#### M2-08 真实供应商实测报告
- **角色**：测试
- **前置**：M2-01、M2-06
- **文件**：`docs/功能资料/09-模拟面试/语音模拟面试-实测报告.md`（新增）
- **实现要点**：
  1. 测转写 P95（目标 ≤8s）、TTS 首字节（目标 ≤2s，长题单独记录）。
  2. 测三种浏览器格式（Chrome webm、Firefox ogg、Safari mp4）成功率与空识别率。
  3. 记录长题（>500 字）分句串行合成的实际耗时（对应冲突 C4 的取舍依据）。
  4. 记录 429/503 实际表现与文案匹配度。
- **验收标准**：有数据表（样本量 ≥30 次/格式）；结论明确是否满足 M2 出口。
- **验证**：实测脚本执行 + 数据归档。
- **追溯**：`design.md` 4.1、4.8、F35；`spec.md` 4.1-1、4.1-2

---

## 5. M3：联调回归与第二阶段评估（4 个任务）

#### M3-01 并发压测与熔断参数固化
- **角色**：测试 + Java
- **前置**：M2-02、M2-05、T-07
- **文件**：`docs/功能资料/09-模拟面试/语音模拟面试-压测报告.md`（新增）；`VoiceLimits`（信号量参数）
- **实现要点**：
  1. 10 并发 × 8MB 上传，观察内存峰值（单请求 ≤8MB + 1 份副本）与 GC。
  2. 同步接口并发上限 16 的验证：超限应快速 429/503，不耗尽 Tomcat 线程。
  3. 记录 P95、错误率、线程/连接数；据此固化 `design.md` 4.7 阈值。
- **验收标准**：无 OOM、无线程耗尽；P95 与错误率有明确数值；阈值写入配置。
- **验证**：压测脚本（JMeter/k6 或 Python 并发）+ 监控数据。
- **追溯**：`design.md` 4.1"内存峰值"、4.7、4.8

#### M3-02 发布配置、开关与安全头
- **角色**：Java + 运维 + 前端
- **前置**：M2-07
- **文件**：`project/backend/src/main/resources/application.yml`、`.env.example`（若存在）、部署说明
- **实现要点**：
  1. `app.voice.*`（`asr-enabled/tts-enabled/consent-required/max-audio-bytes/max-duration-ms/transcription-ttl-seconds/agent-timeout/synth-timeout`）。
  2. Agent `speech_*` 环境变量清单（密钥只在 Agent，Java 配置不得出现任何语音密钥）。
  3. HTTPS 可用性检查（非安全上下文录音禁用）；`Permissions-Policy: microphone=(self)` 与 CSP 评估。
  4. 灰度策略：先 `asr-enabled=false` 上线（入口隐藏），再按会话/用户开启。
- **验收标准**：默认配置下语音关闭、文字面试零影响；开启后本地与预发可用；配置项有注释与默认值。
- **验证**：两套配置分别启动验证 + `curl` V1 观察 `asrEnabled`。
- **追溯**：`design.md` 2.5.3、4.3、4.5、D14；`spec.md` 4.5-2、5.1.3-2

#### M3-03 第二阶段投入评估报告
- **角色**：产品 + 技术负责人 + 数据
- **前置**：M2-08、T-12（首版上线数据，若不足用压测 + Fake 数据并标注）
- **文件**：`docs/功能资料/09-模拟面试/语音模拟面试-二期评估.md`（新增）
- **实现要点**：
  1. 按 `design.md` 5.3 门槛逐项判定：转写 P95>8s、空识别率>10%、语音题放弃率>10%（任一满足即进入）。
  2. 按优先级排序：分句 TTS（已部分做）→ 服务端 VAD + WebSocket（捆绑）→ 实时字幕 → 打断 → 回声 → 断线恢复。
  3. 明确"不建议首版同时引入 WS+VAD+字幕+打断"的结论与理由。
  4. 给出二期工作量级评估与前置条件（供应商流式配额、Java WS 代理、前端录音链路重构）。
- **验收标准**：每个能力有"投入/不投入 + 阈值数据"；结论可直接作为排期输入。
- **验证**：评估会评审 + 数据引用可追溯。
- **追溯**：`design.md` 五章 5.1–5.3

#### M3-04 U13 验收证据归档与上线检查单
- **角色**：测试 + 项目负责人
- **前置**：全部任务、T-12
- **文件**：`docs/功能资料/09-模拟面试/语音模拟面试-验收证据.md`（新增）
- **实现要点**：
  1. 逐条归档 A1–A15 证据（截图/日志/命令输出/数据表）。
  2. 核对"不得发生"清单 7 项（版本变化、正式消息、重复计费、晚到写入、评分差异、麦克风未释放、日志泄露）。
  3. U13 子项映射（若 O7 纳入本期）：录音授权、语音输入、追问、播放、失败恢复、文字降级、隐私提示。
  4. 上线检查单：迁移已执行、开关默认关闭、密钥仅在 Agent、回滚脚本可用、监控日志可检索。
- **验收标准**：A1–A15 每项有证据；反例清单 7 项全部无命中；检查单逐项勾选。
- **验证**：证据评审会 + 抽查 3 项现场复现。
- **追溯**：`design.md` 6.4、三章"不得发生"清单；`spec.md` 8.1–8.5

---

## 6. 测试任务（单列，12 个）

#### T-01 Java 单元测试（voice 包全量）
- **角色**：测试 + Java
- **前置**：M1-08 ～ M1-15
- **文件**：新增 `project/backend/src/test/java/com/aijob/backend/interview/voice/*Test.java`
- **覆盖**：`VoiceTranscriptionServiceTest`（同 sha 复用、`PENDING` 409、晚到 `DISCARDED`、过期、415/413/400）、`QuestionSpeechServiceTest`（分句边界、缓存命中、超长 400）、`InterviewAnswerVoiceTest`（VOICE 绑定、一次性消费、TEXT 零差异）、`GlobalExceptionHandlerTest`（413）、`InternalJwtServiceTest`（scope/TTL/taskIds）、`VoiceMapperTest`（状态迁移）。
- **验收标准**：全绿；断言"转写后 `ai_task` 计数为 0""`version` 未变""日志无全文"。
- **验证**：`mvn -q -Dtest="*Voice*Test+InterviewAnswer*Test" test`
- **追溯**：`design.md` 4.6、6.2 T-1

#### T-02 Java 隔离库集成测试（迁移与并发）
- **角色**：测试 + 数据库
- **前置**：M1-01、M1-09、M1-13
- **文件**：新增 `project/backend/src/test/java/.../voice/VoiceIntegrationTest.java`（`@SpringBootTest` + 隔离库配置）
- **覆盖**：`V1`→`V15` 空跑；历史会话/消息可读（`mode=TEXT`、`answerSource=null`）；唯一键并发（两线程同 sha）；行锁下"同题单 PENDING"；`consumed_at` 成对约束。
- **验收标准**：隔离库可重复创建；并发用例稳定通过（连跑 3 次）。
- **验证**：`docker compose up -d mysql` + `mvn -q -Dtest=VoiceIntegrationTest test`（专用库 `aijob_voice_test`）。
- **追溯**：`design.md` 4.6"Java 集成"、2.8

#### T-03 Python 单元测试（`speech/`）
- **角色**：测试 + Python
- **前置**：M1-03 ～ M1-07
- **文件**：新增 `project/agent/tests/test_speech_service.py`、`test_speech_fake.py`、`test_speech_config.py`
- **覆盖**：Fake 确定性与静音空识别、魔数校验（413/415）、超长文本 400、信号量 429、`caplog` 断言无音频/全文/密钥、prod 拒绝 fake。
- **验收标准**：全绿；`caplog` 结论可作为日志合规证据。
- **验证**：`python -m pytest project/agent/tests -q -k speech`
- **追溯**：`design.md` 4.6、2.6.4、F38

#### T-04 Python 接口测试（A1/A2）
- **角色**：测试 + Python
- **前置**：M1-06
- **文件**：新增 `project/agent/tests/test_speech_api.py`
- **覆盖**：`ai:speech` 通过 / `ai:run` 拒绝 / 无 scope 401；`requestId` 不在 `taskIds` 403；200 结构（`code/msg/data/traceId`）；413/415/422/429/503。
- **验收标准**：全绿；响应结构与 Java `CommonResult` 解析兼容（用 Java 侧样例反序列化验证）。
- **验证**：`python -m pytest project/agent/tests -q -k speech_api`
- **追溯**：`design.md` 2.2.2-(7)(8)、4.6

#### T-05 前端单元测试（新增 Vitest）
- **角色**：测试 + 前端
- **前置**：M1-17、M1-18、M1-20
- **文件**：新增 `vitest.config.ts`、`package.json`（devDependency + `test:unit` 脚本）、`src/utils/voice/__tests__/`、`src/composables/voice/__tests__/`
- **覆盖**：格式协商顺序（Chrome/Safari 模拟）、三处常量与 V1 默认值一致、`useVoiceAnswer` 状态迁移（含失败回退、`EMPTY`）、`useQuestionSpeech` 防重复播放、计时与上限。
- **验收标准**：全绿；不引入除 `vitest` 外的重依赖（如需 DOM 用 `jsdom`/`happy-dom`）。
- **验证**：`npx vitest run`（或 `npm.cmd run test:unit`）
- **追溯**：`design.md` 4.6"前端单测"、4.5-2

#### T-06 前端类型检查与构建
- **角色**：前端
- **前置**：M1-16 ～ M1-22
- **文件**：无（仅执行）
- **覆盖**：全部新增 `.ts/.vue`；`types.ts` 新类型；`InterviewView` 改造后无 `any`。
- **验收标准**：零错误、无新增 `any`、构建产物可生成。
- **验证**：`npx vue-tsc --noEmit`；`npm.cmd run build`
- **追溯**：`design.md` 4.6"静态验证"、4.5

#### T-07 接口集成测试（V1–V6 主路径 + 错误码矩阵）
- **角色**：测试
- **前置**：M1-11、M1-13、M2-02
- **文件**：新增 `scripts/voice-interview-api-test.sh`（或 `.http` 集合）与结果记录
- **覆盖**：
  1. V1 能力探测（200/401/404）。
  2. V2：成功 200、同 sha `reused=true`、`PENDING` 409、旧题 409 `STALE_INPUT`、413、415、400、429、503。
  3. V3：`PENDING/SUCCEEDED/EMPTY` 返回；`DISCARDED/EXPIRED` → `data=null`。
  4. V4：成功 `true`；已消费 409。
  5. V5：200 `audio/mpeg` + 头；非 ASSISTANT 404；超长 400。
  6. V6：`VOICE` 提交 202；未传字段行为不变；转写过期 409。
- **验收标准**：错误码与第 0.4 节逐条一致；双账号越权全部 404。
- **验证**：脚本执行输出归档到 `语音模拟面试-验收证据.md`
- **追溯**：`design.md` 2.2.2 全部异常映射表、F37

#### T-08 文字面试回归
- **角色**：测试
- **前置**：M1-22
- **文件**：`docs/功能资料/09-模拟面试/语音模拟面试-验收证据.md`（记录）
- **覆盖**：
  1. 未启用语音时：入口不显示，页面无语音控件。
  2. 全链路：创建→首题→文字提交→追问→结束→报告。
  3. 历史记录：旧会话 `mode` 缺失时按文字展示；消息无来源标记。
  4. 报告与成长证据：与改造前一致（评分维度/证据规则）。
  5. `V15` 后旧数据可读（与 T-02 交叉）。
- **验收标准**：无功能回归；报告字段无变化；历史展示无变化。
- **验证**：手工全链路 + 与基线分支结果比对。
- **追溯**：`design.md` 4.4、6.4 A11/A12；`spec.md` 4.5-1、8.4

#### T-09 异常矩阵测试（九类必测）
- **角色**：测试
- **前置**：T-07
- **文件**：`语音模拟面试-验收证据.md`
- **覆盖**（必须逐项留证）：
  1. 权限拒绝（F02）：拒绝后不重复弹窗，可切文字。
  2. 静音/空识别（F11）：提示"没有识别到语音"，无会话数据。
  3. 超长（F05/F09）：180s 自动停止；8MB 触发 413。
  4. 断网（F29）：转写中断网，恢复后可重传，音频仍在页面。
  5. 语音服务失败（F12/F13）：停 Agent → 503 + 三出口，会话状态不变。
  6. 重复提交（F22）：双击只产生一条回答、一个任务。
  7. 旧题晚到（F17）：提示"未生效"，不写会话。
  8. 切换文字（A10）：文本保留，可完成面试并出报告。
  9. 刷新恢复（F18/A9）：候选文本回填并标注"需确认"。
- **验收标准**：9 类全部通过；每类有截图或日志；反例清单 7 项无命中。
- **验证**：手工 + 脚本（断网用 DevTools 离线，Agent 用停止进程模拟）。
- **追溯**：`design.md` 三章、6.4；`spec.md` 8.2

#### T-10 端到端测试（Playwright，可选但建议）
- **角色**：测试 + 前端
- **前置**：M1-22
- **文件**：新增 `project/frontend/e2e/voice-interview.spec.ts`、`playwright.config.ts`
- **实现要点**：
  1. 用 `--use-fake-device-for-media-stream` + `--use-fake-ui-for-media-stream` 提供虚拟麦克风，避免真实录音。
  2. 脚本：加载会话 → 播放题目（可选） → 录音 3s → 停止 → 转写（Fake） → 编辑文本 → 提交 → 断言出现追问/下一题。
  3. 追加"权限拒绝"与"切换文字"两条。
- **验收标准**：本地 Chromium 通过；不依赖真实供应商；失败时保留 trace/截图。
- **验证**：`npx playwright test e2e/voice-interview.spec.ts`
- **追溯**：`design.md` 4.6"端到端"、6.2 T-4

#### T-11 M1 阶段验收演示
- **角色**：测试 + 产品 + 前端 + Java
- **前置**：T-01 ～ T-08（M1 相关项）
- **文件**：`语音模拟面试-验收证据.md`
- **覆盖**：A1 首题闭环、A2 权限拒绝、A3 静音、A7 重复提交、A9 刷新恢复、A10 文字切换、A11 文字回归、A13 日志合规、A15 隐私告知。
- **验收标准**：9 项全部通过（M1 必过项，`design.md` 6.4）；演示在 Chrome 或 Edge 本地完成。
- **验证**：现场演示 + 证据归档。
- **追溯**：`design.md` 6.1 M1 出口、6.4"必过项"

#### T-12 M2/M3 阶段验收
- **角色**：测试 + 产品 + 合规
- **前置**：T-09、T-10、M2 全部
- **文件**：`语音模拟面试-验收证据.md`
- **覆盖**：A4 超时/超限、A5 断网、A6 服务失败、A8 旧题晚到、A12 报告一致性、A14 越权 + U13 子项（若 O7 纳入）。
- **验收标准**：全部通过；A12 需"语音/文字各一次同类回答"对比评分维度与证据规则一致。
- **验证**：现场演示 + 双账号测试 + 日志抽查。
- **追溯**：`design.md` 6.4 剩余项、F37/F38/F39

---

## 7. 依赖关系、关键路径与并行度

### 7.1 关键路径（决定最早可演示时间）

```
M0-01 决策 → M0-02 契约 → M0-03 缺口处置 → M0-05 V15 评审
   → M1-01 V15 → M1-09 VoiceMapper → M1-10 转写主流程 → M1-11 五个接口
   → M1-16 前端接口层 → M1-19/20 播放与状态机 → M1-21 组件 → M1-22 页面接入
   → T-06/T-07 → T-11 M1 验收
   → M2-01 真实 Provider → M2-05 能力联动 → M2-08 实测 → T-12 → M3-01 → M3-03 → M3-04
```

**关键路径上的强阻塞点**：M0-01（决策）→ M0-02（契约）→ M1-10（转写主流程，逻辑最重）→ M1-22（页面接入）。

### 7.2 可并行任务（按角色）

| 时间窗 | Java 线 | Python 线 | 前端线 | 测试线 |
|--------|---------|-----------|--------|--------|
| M0 | M0-02、M0-03、M0-05、M0-04 | M0-06 | M0-07（原型对照）、M1-17（可提前） | M0-07（用例矩阵） |
| M1 前段 | M1-15（独立）、M1-08、M1-09 | M1-03 → M1-04 → M1-05 | M1-18（不依赖接口） | T-03、T-04 |
| M1 中段 | M1-10 → M1-11、M1-12、M1-13、M1-14 | M1-06、M1-07 | M1-16 → M1-19 → M1-20 | T-01、T-02 |
| M1 后段 | — | — | M1-21 → M1-22 | T-05、T-06、T-07、T-08、T-11 |
| M2 | M2-02、M2-03、M2-04、M2-05、M2-06 | M2-01 | M2-07（文案） | M2-08 |
| M3 | M3-02 | — | — | M3-01、T-09、T-10、T-12 → M3-03、M3-04 |

### 7.3 并行约束（不可违背）

1. **M1-10 依赖 M1-06**：Java 转写必须能调用 Agent `A1`；若 Agent 未就绪，Java 可用 Stub 先跑单测，但 M1 验收前必须联调。
2. **M1-22 依赖 M1-14**：前端展示 `answerSource` 需要后端回显。
3. **T-07 依赖 M2-02**：429 用例需要限流实现；M1 阶段可先验证其余错误码。
4. **M1-13 与 M1-14 必须串行**：先能写 `answer_source`，再谈回显断言。
5. **M2-01 与 M1 不重叠**：真实 Provider 不得在 M1 合入主分支默认配置（`speech_provider` 默认 `auto`，无 key 即 fake）。

---

## 8. 设计与代码冲突 / 缺口记录（本轮核对新增，均不改 `design.md` 结论）

> 处理原则：只记录、给建议，由 M0 决策或按建议实现；任何偏离必须回写设计文档。

| 编号 | 冲突/缺口 | 出处对照 | 影响 | 建议处置 |
|------|----------|---------|------|---------|
| **C1** | 设计要求"同意写 `interview_voice_consent`"，但 2.2.1 接口表无同意接口（V1–V6 中无） | `design.md` 2.9、F-7、2.2.1 | 隐私告知无法闭环；V2 无法校验同意 | 新增 `V7 POST /api/v1/interviews/{id}/voice-consent`（推荐），或本期豁免并配置开关（M3 前补）。见 M0-03 / M2-03 |
| **C2** | F12 写"504 `DEPENDENCY_UNAVAILABLE`"，但 `ApiErrorCode` 无 504 枚举（仅 50301） | `design.md` F12；`ApiErrorCode.java:26` | Java 无法返回 504，前端映射需二选一 | 统一 503 + `details.reason=TIMEOUT`（不新增错误码，符合 4.5-3）；若坚持 504 需新增枚举 |
| **C3** | 录音时长下限三处不一致：V2 表单 `10_000ms`、DB CHECK `1000ms`、F04 `<1000ms` | `design.md` 2.2.2-(2)、2.3.2、F04 | 前端/后端/DB 判定不一致，10s 以下录音行为不可预期 | 业务下限取配置（默认 2000ms），DB CHECK 保持 1000ms 硬边界；三处由同一常量表驱动（第 0.3 节） |
| **C4** | D5/F35 要求"分句后首段先播"，但 V5 契约是"一次性返回完整 `audio/mpeg`" | `design.md` 2.4 D5、F35、2.2.2-(5) | 长题首字节目标（≤2s）无法达成；"首段先播"需分段/流式 | 本期采用"分句串行 + 完整返回"，长题耗时在 M2-08 实测；分段接口列为二期候选（不做流式） |
| **C5** | 前端无"取二进制但不下载"的函数；`downloadBinary` 语义是落盘下载；2.1.2 文件清单未列 `request.ts` | `request.ts:124-178`、`180-215`；`design.md` 2.1.2 | V5 音频无法直接播放 | 新增 `requestBlob()`（复用 401 刷新与 JSON 错误解析）；列入 M1-16 |
| **C6** | `interviewApi.answer` 现有签名不含来源字段；`AnswerAttempt`（`InterviewView.vue:16`）需同步；2.1.2 未列 `api/interviews.ts` | `api/interviews.ts`、`InterviewView.vue:16,327`；`design.md` 2.1.2 | 前端 VOICE 提交缺参数，类型报错 | M1-16 扩展 `answer` 可选字段；M1-22 扩展 `AnswerAttempt` |
| **C7** | `insertMessage` 是 10 参数注解方法，被 ASSISTANT（`InterviewResultHandler:142`）与 USER（`InterviewService:169`）两处调用 | `InterviewMapper.java:73-82` | 直接加列会导致 ASSISTANT 插入违反 `ck_interview_message_source_role` | 增加 `answerSource` 参数，ASSISTANT 传 `null`、USER 传 `TEXT/VOICE`；M1-13 一并改两处 |
| **C8** | P-5 写"`security.py` 支持 `ai:speech`"，实际 `verify_internal_token` 已是参数化（第 46 行），固定值在 `api.py:202-211` | `security.py:46`、`api.py:202-211` | 按 P-5 字面实现会做无效改动 | 不改 `security.py`；`config.py` 加 `speech_scope`，`api.py` 增独立依赖并注册 router（M1-06） |
| **C9** | `ApiView.map` 是"全列驼峰映射"，若转写 VO 复用它会把 `audio_sha256`、`provider` 等内部字段暴露给前端 | `ApiView.java:22-39` | 违反最小化与日志/数据安全要求 | 转写 VO 必须手工白名单装配（M1-10 要点 6）；不得传入 `ApiView.map` |
| **C10** | `sessionVo` 是手工白名单装配，`mode` 不会自动出现 | `InterviewService.java:228-244` | 前端取不到 `mode` | M1-14 手工 `put("mode", ...)`；消息侧由 `view.map` 自动带出 `answerSource` |

---

## 9. 首版明确"不做"清单（越界即视为缺陷）

| 不做项 | 依据 | 进入条件（首版指标） |
|--------|------|---------------------|
| 实时流式语音（WebSocket 音频流） | `spec.md` 1.4-4；`design.md` 5.1、5.3 | 转写 P95 > 8s，或单题均时长 > 45s，或上传体积 P95 > 4MB |
| 服务端 VAD | 同上 | 空识别占比 > 10%，或"没反应"重录占比 > 15% |
| 实时字幕 | 同上 | 转写等待期放弃率 > 5%，或 P95 > 5s |
| 分句**并行**/流式 TTS | `design.md` 2.4 D5、5.1 | 题目平均字数 > 120，或首字节 P95 > 2s |
| 打断播报（barge-in） | `spec.md` 1.4-4 | "停止播报"点击占播放会话 > 15% |
| 回声消除（AEC）/`echoCancellation`/`noiseSuppression` | `design.md` 5.1（首版仅约束与提示） | 转写中出现播报原文 > 3% |
| 断线恢复（WS 重连/游标） | `spec.md` 1.4-4 | 断线导致会话丢失 > 0.5%/日 |
| 音频持久化与历史语音回放 | `spec.md` 1.4-3、5.7.1-2 | 首版不开放（需产品重新定义合规） |
| 第二套会话模型 / 第二套提交接口 | `design.md` D2、5.2 | 永不在本特性内引入 |
| 修改评分口径、报告维度与"每主问题最多追问 2 次"的局部规则 | `spec.md` 1.4-1/2、6.4 | 永不 |
| （2026-11-21 修订）总提问数量预算与额度耗尽后的结束规则 | `spec.md` 5.9、`design.md` 八章 | **不属于**上一行"不做"范围；已由 Q-01～Q-06 立项 |

| 新增消息队列 / 对象存储 / WebSocket 基础设施 | `design.md` 2.1.1"不引入的组件" | 二期评估后另立需求 |

> **2026-11-21 修订说明**：原"修改评分、报告、追问规则｜永不"一行，与 `spec.md` 5.9 新增的"含追问的总提问上限"存在字面冲突。现已拆分为"评分/报告/局部追问次数上限不改"与"总提问预算属于新增范围"两条；Q 阶段不改变评分算法、报告维度与 2 次追问上限。

> 说明：首版**允许**"分句串行合成 + 顺序播放"（`design.md` 5.1 标为"部分"），这与"分句并行/流式 TTS 不做"不冲突。

---

## 10. 可追溯矩阵

### 10.1 `spec.md` 能力 → 任务

| spec 条目 | 任务 |
|-----------|------|
| 5.1 模式选择（含历史会话按文字） | M1-22、M1-14、T-08 |
| 5.2 题目播报（手势、暂停/重播、录音即停、降级） | M1-12、M1-19、M1-21、F30–F34 |
| 5.3 录音采集（告知、停播、计时、上限、释放、格式） | M1-17、M1-18、M2-03、M2-07、F01–F07 |
| 5.4 转写与确认（候选态、幂等、有效期、旧题） | M1-09、M1-10、M1-20、M1-11（V4）、F11/F15–F18 |
| 5.5 提交与推进（复用规则、防重、口径一致） | M1-13、M1-14、T-01、T-09 |
| 5.6 结束与报告（复用、来源分布） | M1-14、T-08、T-12（A12） |
| 5.7 隐私与最小化（告知、不存音频、TTL、清理） | M0-03、M1-02、M2-03、M2-04、M2-07 |
| 5.8 降级（可读提示、三出口、不卡死） | M1-22、M2-05、F12/F13/F40 |
| 6.1/6.2 会话模式与回答来源 | M1-01、M1-13、M1-14 |
| 6.3 转写记录（临时） | M1-01、M1-09、M1-02、M2-04 |
| 6.4 报告仅基于正式回答 | M1-13（`agentMessages` 不含来源）、T-12（A12） |
| 7.1–7.8 待确认决策 | M0-01、M2-07、第 11 章 |
| 8.1–8.5 验收基线 | T-11、T-12、M3-04 |
| **5.9 总提问数量预算（2026-11-21）** | **Q-01～Q-06、T-08（历史回归）** |
| **8.6–8.9 总题数验收基线（2026-11-21）** | **Q-03、Q-06** |

### 10.2 `design.md` 6.4 验收项 → 任务

| 验收项 | 任务 | 阶段 |
|--------|------|------|
| A1 首题闭环 | M1-22、T-11 | M1 |
| A2 权限拒绝 | M1-18、M1-22、T-11 | M1 |
| A3 静音/空识别 | M1-04、M1-10、M1-20、T-11 | M1 |
| A4 超时/超限 | M1-15、M1-18、T-12 | M2 |
| A5 断网 | M1-20、T-09、T-12 | M2 |
| A6 服务失败 | M2-05、T-09、T-12 | M2 |
| A7 重复提交 | M1-13、T-09、T-11 | M1 |
| A8 旧题晚到 | M1-10、T-09、T-12 | M2 |
| A9 刷新恢复 | M1-20、T-11 | M1 |
| A10 文字切换 | M1-20、M1-22、T-11 | M1 |
| A11 文字回归 | T-08、T-11 | M1 |
| A12 报告一致性 | M1-13、T-12 | M2 |
| A13 日志合规 | M1-05、M1-08、M2-06、T-03、T-11 | M1 |
| A14 越权 | M1-11、T-07、T-12 | M2 |
| A15 隐私告知 | M2-03、M2-07、T-11 | M1/M2 |
| A16 总提问数不超上限（主问题+追问） | Q-02、Q-06 | Q |
| A17 最后一题可答且答后先评价再报告 | Q-03、Q-06 | Q |
| A18 刷新/重试/并发/旧结果不增加题数 | Q-02、Q-06、T-02 | Q |

---

## 11. 阻塞项与待确认决策

> 下表为 M0 必须收口项。**未收口不阻塞 M1 代码开工**（用"临时值"），但**阻塞 M1 验收与 M2 上线**。

| 编号 | 决策项 | 建议/临时值 | 影响任务 | 未决时处置 | 责任人 |
|------|--------|------------|---------|-----------|--------|
| **O1** | 语音供应商/账号/地域/音色（spec 7.1） | 待定 | M2-01、M2-08 | 用 Fake 跑通 M1 | 产品 + 技术负责人 |
| **O2** | 录音时长上限、文件大小、每日次数与时长配额（spec 7.3） | 3min / 8MB / 30 次/10min、200 次/日 | M0-03、M1-17、M2-02、M2-07 | 用建议值冻结 | 产品 |
| **O3** | 未确认转写 TTL（spec 7.4） | 30 分钟 | M1-01、M2-04 | 用 30min | 产品 + 合规 |
| **O4** | 是否允许逐题语音/文字混用（spec 7.5） | 允许 | M1-20、M1-22 | 按允许实现 | 产品 |
| **O5** | 生产 HTTPS 形态（spec 7.6） | 待定 | M3-02、真机录音 | 本地 `localhost` 视为安全上下文 | 运维 |
| **O6** | 第三方音频留存政策是否可接受（spec 7.7） | 待定 | M2-07、M2-03 | 文案标注"识别后删除"，待合规确认 | 法务/合规 |
| **O7** | U13 是否纳入本期验收 | 待定 | T-12、M3-04 | 先按纳入准备证据 | 项目负责人 |
| **O8** | 是否同时持久化会话模式与单条来源（spec 7.2） | 均保存（设计 D 结论） | M1-01、M1-13、M1-14 | 按均保存实现 | 产品 |
| **O9** | 隐私同意接口归属（本轮新增，C1） | 新增 V7（会话级） | M0-03、M2-03、M1-22 | M1 先只做前端告知 + 本地同意，服务端校验推迟 | 技术负责人 + 合规 |
| **O10** | 超时错误语义（本轮新增，C2） | 503 + `details.reason=TIMEOUT` | M0-02、M1-08、M1-11 | 按 503 实现 | 技术负责人 |
| **O11** | 录音时长下限（本轮新增，C3） | 2000ms（DB 硬边界 1000ms） | M1-10、M1-17、M1-18 | 按 2000ms 实现 | 产品 |
| **O12** | 历史未完成会话是否切换到"含追问的总提问"规则（`spec.md` 7.9） | 不切换（保持 `MAIN_ONLY` 直至会话结束） | Q-01、Q-02、Q-06 | 按不切换实现；如产品要求切换需单独立项 | 产品 + 技术负责人 |
| **O13** | 报告"有效回答数"是否新增含追问口径（`spec.md` 7.10） | 新增 `answeredAskCount`，原 `answeredQuestionCount` 语义不变 | Q-03、Q-06 | 按新增字段实现，历史报告不改 | 产品 |

---

## 12. 前 5 个待执行任务（按开工顺序）

1. **M0-01** 决策项收口与临时值冻结（产品/技术/合规）——唯一无前置，决定其余任务取值。
2. **M0-02** 接口契约与错误码表冻结（三方签署）——与 M0-06 并行。
3. **M0-06** Python `speech` 模块骨架与 Fake 契约（Python）——不依赖任何决策，可立即开工。
4. **M0-03** 缺口处置：隐私同意接口与超时/下限语义（C1/C2/C3）。
5. **M0-04** 落地方式修正登记（C4–C10），避免开发按错误理解动手。

> 说明：M0-05（`V15` 评审）需等 M0-01 的 O3 与 O2 结论；M1-17 与 M1-18（前端工具与录音封装）不依赖接口，可与 M0 并行启动。
>
> **2026-11-21 补充**：若按"总题数预算"阶段优先推进，开工顺序为 Q-01 → Q-02 → Q-04（契约并行）→ Q-03 → Q-05 → Q-06。Q-01 的迁移设计与 Q-02 的计数查询可先于 M2/M3 未完成项落地，但 Q-06 验收需 M1 的语音闭环可运行。

---

## 13. Q：总题数预算与后端结束规则（2026-11-21 新增，6 个待实现任务）

> 需求基线：`spec.md` 5.9、8.6–8.9；设计基线：`design.md` 第八章。
> **全部标记为待实现**：本轮只更新文档，未改任何业务代码、未创建/执行迁移、未调用模型、未运行测试。
> 阶段内编号 Q-01～Q-06 与既有 M0/M1/M2/M3/T 编号并列，互不覆盖；既有任务的编号、状态与追溯保持不变。

### 13.0 任务总览与状态（2026-11-21 编码任务规划）

| 编号 | 标题 | 状态 | 前置（硬依赖） | 涉及文件（主） | 可并行 | 主要阻塞点 |
|------|------|------|---------------|---------------|--------|-----------|
| **Q-01** | 历史兼容与数据库设计（`V16` 落地） | 实施中（见 13.7） | 无 | `V16__add_interview_question_count_rule.sql`、`InterviewMapper.insertSession`、新增 `QuestionCountRule` | 可与 Q-02 设计并行；落地须先于 Q-02.1 之外的所有读列任务 | 隔离库可用性；`O12` 未定不阻塞（按不切换历史会话） |
| **Q-02** | Java 总题数统计与会话锁内限制 | 实施中（见 13.7） | Q-01.1、Q-01.2、Q-01.3 | `InterviewMapper`（`:18-28/:38-48/:104` 后）、`InterviewService`（`:62-98/:114-217/:270-287/:311-325`）、`InterviewResultHandler`（`:86-146`） | 与 Q-04 可并行（先冻结 payload 字段名） | `pending_task_id` 校验会打断既有测试与手工插会话用例 |
| **Q-03** | 最终回答评估及结束规则 | 实施中（见 13.7） | Q-02.6（额度判定）、Q-02.2（payload） | `InterviewResultHandler`（`:97-135/:176-241`）、`InterviewService.reportTask`（`:237-255`） | 与 Q-04 可并行 | 结束分支顺序错误会丢评价或提前结束 |
| **Q-04** | Agent Schema、轮次处理与提示词调整 | 实施中（见 13.7） | Q-04.1 依赖 payload 字段冻结（Q-02.2 前完成字段表评审） | `app/schemas.py:827-838`、`app/handlers.py:1137-1253`、`app/skills/prompts/interview_turn.md`、`app/operations.py:128` | 与 Q-02、Q-03 可并行 | `TOTAL_ASKED` 与 `MAIN_ONLY` 分支必须并存 |
| **Q-05** | 前端计数展示 | 实施中（见 13.7） | Q-02.8（接口字段已返回） | `api/types.ts:802-818/851-861`、`types/workbench.ts:237-246`、`api/mock/workbench.mock.ts:153-158`、`InterviewView.vue:96-104/305-306/634-637/650/728/803` | 与 Q-03、Q-04 可并行 | 不得在 `canAnswer` 中加入额度条件 |
| **Q-06** | 单元、并发、幂等及完整报告验收 | 实施中（见 13.7） | Q-01～Q-05（用例可随各任务先写） | 新增 `InterviewQuestionBudgetTest`、`InterviewQuestionBudgetConcurrencyTest`、`scripts/interview_budget_acceptance.py`；更新既有 Java/Python 测试 | 用例编写可并行，验收执行必须串行收口 | 未实际执行不得标记完成；并发用例需隔离库 |

> 状态口径：**待实现** = 设计已定、尚无对应代码/脚本/用例；**实施中** = 已有实现，部分验收尚待完成。只有"子任务验收标准全部满足 + 13.3 回归项通过 + 实际执行命令留痕"后，才允许把该主任务改为完成；主任务尚未全部验收；已完成子任务按复选框标注，最新证据见 13.7。

#### Q-01 历史兼容与数据库设计（`V16` 设计落地）
- **状态（2026-11-21 修复后）**：实现已落地，隔离迁移通过，历史页面与完整发布回退仍待验收（13.7）。
- **角色**：数据库 + Java
- **前置**：无（设计已完成）；与 Q-02 可并行设计，落地需 Q-02 读取字段
- **文件**：新增 `project/backend/src/main/resources/db/migration/V16__add_interview_question_count_rule.sql`；修改 `InterviewMapper.insertSession`；更新 `docs/功能资料/09-模拟面试/语音模拟面试-回滚与清理.md`
- **实现要点**：
  1. `interview_session` 增加 `question_count_rule VARCHAR(16) NOT NULL DEFAULT 'MAIN_ONLY'` + `ck_interview_session_rule CHECK IN ('MAIN_ONLY','TOTAL_ASKED')`；`AFTER question_limit`。
  2. 历史行保持 `MAIN_ONLY`（不 UPDATE、不回填）；`question_limit` 的 `CHECK 3..10` 不变。
  3. 新建会话由 `insertSession` 显式写 `TOTAL_ASKED`，不依赖默认值。
  4. 回滚顺序：`DROP CHECK` → `DROP COLUMN`；不修改已执行的 `V15`。
  5. 若产品选择"切换历史未完成会话"（O12 未采建议值），单独设计 UPDATE 与影响记录，不并入本任务。
- **验收标准**：隔离库执行后 `SHOW CREATE TABLE` 与设计一致；历史会话的 `questionLimit` 行为、展示与结束规则不变；新建会话 `question_count_rule='TOTAL_ASKED'`；迁移可回滚；`V15` 文件未被修改。
- **验证**：隔离库 Flyway 执行 + 回滚演练 + `mysql -e "SELECT question_count_rule,COUNT(*) FROM interview_session GROUP BY 1"`；历史会话回归（T-08）。
- **追溯**：`design.md` 8.3；`spec.md` 6.1-3、7.9；O12

**子任务清单（完成状态见复选框与 13.7，行号为 2026-11-21 实测，编码前复核）**

- [ ] **Q-01.1 编写 `V16` 迁移脚本**（依赖：无；可并行：是）
  - 文件：`project/backend/src/main/resources/db/migration/V16__add_interview_question_count_rule.sql`（新增；`V15` 已存在且禁止修改）
  - 要点：单条 `ALTER TABLE interview_session`：`ADD COLUMN question_count_rule VARCHAR(16) NOT NULL DEFAULT 'MAIN_ONLY' AFTER question_limit` 与 `ADD CONSTRAINT ck_interview_session_rule CHECK (...)`；不 `UPDATE` 历史行；不触碰 `question_limit` 的 3..10 CHECK。
  - 验收：隔离库执行成功；`SHOW CREATE TABLE interview_session` 含该列、`DEFAULT 'MAIN_ONLY'` 与 CHECK；`V15` 文件内容未变；历史行分组统计只有 `MAIN_ONLY`。
  - 验证：隔离库 `aijob_interview_budget_test` 执行 `V1→V16`（Flyway 或等价脚本）。
- [ ] **Q-01.2 `insertSession` 显式写入 `TOTAL_ASKED`**（依赖：Q-01.1）
  - 文件：`InterviewMapper.java:18-28`（列清单与参数）、`InterviewService.java:96-97`（调用点）
  - 要点：列清单与 VALUES 增加 `question_count_rule`，新增 `@Param("questionCountRule") String questionCountRule`；`create` 传常量 `TOTAL_ASKED`，不依赖 DEFAULT；常量与 Q-01.3 枚举同源。
  - 验收：新建会话行 `question_count_rule='TOTAL_ASKED'`；历史行不受影响；全仓库只存在一处 `"TOTAL_ASKED"` 字面量定义。
  - 验证：`InterviewServiceTest` 捕获 `insertSession` 第 10 个参数 + 隔离库 `SELECT`。
- [ ] **Q-01.3 规则解释与剩余额度工具**（依赖：Q-01.1）
  - 文件：新增 `interview/QuestionCountRule.java`（或并入 `InterviewLimits`），被 `InterviewService`、`InterviewResultHandler` 引用
  - 要点：枚举 `MAIN_ONLY`/`TOTAL_ASKED`；`parse(Object raw)` 对 `null`、空串、`"null"`（`String.valueOf(null)` 的产物）、未知值一律返回 `MAIN_ONLY`，禁止抛异常；`remaining(rule, asked, limit)=max(0, limit-asked)`；`MAIN_ONLY` 不参与总额度判定。
  - 验收：缺失/未知值 → `MAIN_ONLY`；`remaining` 在 `asked>limit` 时为 0 不为负；历史会话不会被解释成 `TOTAL_ASKED`。
  - 验证：单元测试（并入 Q-06.1 的 `InterviewQuestionBudgetTest`）。
- [ ] **Q-01.4 回滚演练与运维记录**（依赖：Q-01.1）
  - 文件：`docs/功能资料/09-模拟面试/语音模拟面试-回滚与清理.md`
  - 要点：记录 `DROP CHECK ck_interview_session_rule` → `DROP COLUMN question_count_rule` 的固定顺序、Flyway 无自动回滚、回滚后旧代码 `SELECT *` 仍可用（`sessionVo` 为白名单装配，多列消失不影响）。
  - 验收：隔离库回滚后列与 CHECK 均消失，会话详情/列表仍可读写；文档步骤可照做且含影响范围说明。
  - 验证：隔离库手工执行 + 文档核对（不写业务代码）。

#### Q-02 Java 总题数统计与会话锁内限制
- **状态（2026-11-21 修复后）**：实施中，最新验证与缺口见 13.7。
- **角色**：Java
- **前置**：Q-01（字段）、Q-04（payload 契约需同步冻结）
- **文件**：`interview/InterviewMapper.java`、`interview/InterviewService.java`、`interview/InterviewResultHandler.java`；新增常量（如 `QuestionCountRule` 枚举或 `InterviewLimits`）
- **实现要点**：
  1. 新增 `askedQuestions(interviewId)`：`COUNT(*) FROM interview_message WHERE interview_id=? AND role='ASSISTANT'`（走 `idx_interview_id_role_seq`）。
  2. `turnPayload` 增加 `askedCount`、`remainingQuestions`、`questionCountRule`；首题写 0 与 `limit`；回答重试分支覆盖这三个字段（不改 `inputVersions`）。
  3. `turn()` 在 `lockSession` 内：读取 `rule`、计算 `remaining`；**增加 `pending_task_id == task.id` 校验**。
  4. `remaining <= 0`（仅 `TOTAL_ASKED`）：在 `evaluateAnswer` 之后跳过 question 校验与插入，直接进入 Q-03 的结束分支。
  5. `remaining > 0` 时沿用现有编号校验：追问 `followUpNo=current+1<=2`、主问题 `questionNo=current+1`；保存与计数同事务。
  6. `MAIN_ONLY` 分支保留现有逻辑，不解释为总提问预算。
- **验收标准**：`askedCount` 与 DB 行数一致；额度为 0 时不再插入 ASSISTANT；模型越界不导致评价丢失；并发/重复回调最多成功一道；历史会话不受影响。
- **验证**：`InterviewResultHandlerTest` 新增额度用例（mock 映射查询结果）；隔离库并发测试（两线程同会话）；`mvn -pl project/backend -Dtest=Interview*Test test`。
- **追溯**：`design.md` 8.2、8.5.1；`spec.md` 5.9.1-1～10；实验 A/C/F/I/K

**子任务清单（完成状态见复选框与 13.7）**

- [ ] **Q-02.1 新增 `askedQuestions` 计数查询**（依赖：Q-01.1；可并行：否，需先有列用于集成验证）
  - 文件：`InterviewMapper.java:103-104`（`latestQuestion` 之后新增方法）
  - 要点：`@Select("SELECT COUNT(*) FROM interview_message WHERE interview_id=#{interviewId} AND role='ASSISTANT'")`；不新增表/列/索引；走 `idx_interview_id_role_seq`。
  - 验收：返回值等于该会话 `role='ASSISTANT'` 行数；`EXPLAIN` 显示使用 `idx_interview_id_role_seq`（或等价覆盖索引）。
  - 验证：隔离库 `EXPLAIN` + 单测 mock。
- [ ] **Q-02.2 `turnPayload` 增加三字段的三个写入点**（依赖：Q-02.1、字段名冻结；可并行：字段冻结后与 Q-04 并行）
  - 文件：`InterviewService.java:311-325`（方法体）、`:92`（首题）、`:199`（新回答）、`:178-186`（重试）
  - 要点：方法签名增加 `rule`、`asked`，输出 `questionCountRule`、`askedCount`、`remainingQuestions`（紧跟 `questionLimit`）；首题写 `0` 与 `limit`；重试分支对复用快照 **覆盖** 三字段但不得改 `inputVersions`（`TaskResultService.java:75-78` canonical 校验）。
  - 验收：三个写入点产生的快照 JSON 均含三字段且类型/范围符合 `design.md` 8.4.5(1)；重试后 `askedCount/remainingQuestions` 为最新值而 `inputVersions` 字节不变。
  - 验证：`InterviewServiceTest.cancelledTurnRetryReusesOriginalUserMessageAndContent`（`:48-92`）捕获 payload 断言。
- [ ] **Q-02.3 `answer` 锁内读取规则与已提问数**（依赖：Q-02.2）
  - 文件：`InterviewService.java:133`（`lockSession` 之后）、`:198-199`（组装 payload 前）
  - 要点：锁内读 `question_count_rule` 与 `askedQuestions(interviewId)`；**不校验额度、不拒绝提交**（最后一题必须可答，`spec.md` 5.9.1-8）；重试分支同样读取，用于覆盖快照。
  - 验收：`asked==limit` 时 `/answers` 仍返回 202；payload 的 `askedCount` 等于 DB 行数；`MAIN_ONLY` 会话的 `remainingQuestions` 为 `limit-asked` 仅作参考、不参与 Java 校验。
  - 验证：`InterviewServiceTest` 新增用例 + 接口测试（Q-06.5）。
- [ ] **Q-02.4 增加 `pending_task_id == task.id` 校验**（依赖：无；可与 Q-02.1 并行）
  - 文件：`InterviewResultHandler.java:87-90`（状态校验之后）
  - 要点：从 session 取 `pending_task_id`，与 `task.getId()` 不等（含 session 行为 `null`）时抛 `BusinessException.conflict("面试状态已变化","INVALID_STATE")`；不新增错误码。
  - 验收：旧任务/晚到结果被拒且不写题、不评价、不改版本；合法重试任务（`prepareRetry` 已把 `pending_task_id` 更新为新任务）可通过。
  - 验证：单测（实验 K）+ 隔离库集成；同时完成 Q-02.9 的测试 stub 同步，否则既有用例失败。
- [ ] **Q-02.5 `askedQuestions` 作为锁后第一条普通 SELECT**（依赖：Q-02.1、Q-02.4）
  - 文件：`InterviewResultHandler.java:91-99`（`payload` 解析与 `evaluateAnswer` 之间）
  - 要点：COUNT 必须在 `lockSession` 与内存态校验之后、`evaluateAnswer`（`:98`）之前执行；后续额度判定复用同一个 `asked` 变量，禁止二次查询；在代码注释中写明"RR 快照稳定性"约束。
  - 验收：评审可见调用顺序为 `lockSession → 状态/待处理任务校验 → askedQuestions → evaluateAnswer → 额度判定 → insertMessage`；单测用 `InOrder` 断言 `askedQuestions` 先于 `message/evaluationByAnswer`。
  - 验证：代码评审清单（13.3 R-04）+ 单测 `InOrder`。
- [ ] **Q-02.6 `TOTAL_ASKED` 额度耗尽判定与越界题目忽略**（依赖：Q-02.5）
  - 文件：`InterviewResultHandler.java:108-135`
  - 要点：`rule=parse(session.question_count_rule)`；`exhausted = rule==TOTAL_ASKED && asked>=limit`；`automaticFinish = action==FINISH || exhausted`；`exhausted` 时**跳过** question 结构与编号校验（`:104-107`），仅保留 `action` 合法性；`MAIN_ONLY` 保留 `answeredNo>=limit && action!=FOLLOW_UP` 现状分支。
  - 验收：实验 E/F：模型返回 `ASK/FOLLOW_UP` + 题目时只保存评价、不插入 ASSISTANT、进入 `ENDING`；畸形 `question` 不导致回调回滚；`MAIN_ONLY` 结果与改造前逐字一致。
  - 验证：单测 `budgetE_*`/`budgetF_*`（Q-06.1）。
- [ ] **Q-02.7 `TOTAL_ASKED` 下题目编号校验调整**（依赖：Q-02.6）
  - 文件：`InterviewResultHandler.java:118-128`
  - 要点：`TOTAL_ASKED` 下主问题只校验 `questionNo == currentQuestion+1 && followUpNo==0`，**去掉** `questionNo > limit`；追问仍校验 `followUpNo == currentFollowUp+1 && <=2` 且 `questionNo==currentQuestion`；`MAIN_ONLY` 保留 `questionNo > limit` 拒绝。
  - 验收：`limit=5`、当前 `questionNo=4` 时 `questionNo=5` 合法；`questionNo=6` 不会到达（被额度分支截断）；`MAIN_ONLY` 下 `questionNo=limit+1` 仍被拒绝。
  - 验证：单测（覆盖 `TOTAL_ASKED` 合法/非法编号与 `MAIN_ONLY` 回归）。
- [ ] **Q-02.8 `sessionVo` 与 `page` 输出计数三字段**（依赖：Q-02.1；可并行：与 Q-02.6 并行）
  - 文件：`InterviewService.java:270-287`（新增三个 `put`）、`InterviewMapper.java:38-48`（`asked_count` 子查询，与 `answered_count` 同风格）
  - 要点：输出 `questionCountRule`、`askedQuestionCount`、`remainingQuestionCount`（`Math.max(0, limit-asked)`）；详情单查 `askedQuestions`，列表用子查询；`sessionVo` 是白名单装配，新列不会自动出现；缺失/未知规则按 `MAIN_ONLY` 输出。
  - 验收：详情与列表三字段存在且数值等于 DB 统计；`MAIN_ONLY` 会话不因新字段改变其它字段；未携带新字段的旧客户端不受影响。
  - 验证：`InterviewServiceTest` + 接口测试（Q-06.5）。
- [ ] **Q-02.9 同步既有 Java 测试 stub（不改原断言）**（依赖：Q-02.4、Q-02.6）
  - 文件：`InterviewResultHandlerTest.java:17-45`、`InterviewServiceTest.java:33-92`、`task/SevenCaseIntegrationTest.java:125-165`
  - 要点：`lockSession` stub 补 `pending_task_id`（值等于被测 `task.getId()`）与 `question_count_rule="MAIN_ONLY"`；`SevenCaseIntegrationTest` 的手工 `INSERT interview_session`（`:131-135`）需补 `pending_task_id`（或插入后 `UPDATE` 为当前任务）；不得删除或改弱既有断言。
  - 验收：改造后既有 3 个测试类无需修改业务断言即可编译通过；（未来执行时）全部通过。
  - 验证：`mvn -pl project/backend -Dtest="Interview*Test,SevenCaseIntegrationTest" test`（本轮未执行）。

#### Q-03 最终回答评估及结束规则
- **状态（2026-11-21 修复后）**：实施中，最新验证与缺口见 13.7。
- **角色**：Java
- **前置**：Q-02（额度判定）
- **文件**：`interview/InterviewResultHandler.java`、`interview/InterviewService.java`（`reportTask`/`sessionVo`）
- **实现要点**：
  1. 最后回答路径顺序固定为：保存评价 → 创建报告任务（`completionType=NORMAL`）→ 会话 `ENDING`；不得先结束、后评价。
  2. 报告 payload 增加 `questionCountRule`、`askedQuestionCount`；报告结果增加 `answeredAskCount`（有效评价去重 `question_message_id`），保留 `answeredQuestionCount`。
  3. 报告去重复用 `uk_interview_id` / `uk_task_id`，不新增幂等表；用户提前结束仍走 `EARLY_END`。
  4. 首题即 `FINISH` 的现状行为保持不变（不属于本阶段范围）。
- **验收标准**：第 5 题回答后恰好 1 条评价 + 1 条报告，无第 6 题；报告字段正确（2 主问题 + 3 追问 → `answeredQuestionCount=2`、`answeredAskCount=5`）；历史报告 `report_json` 不变。
- **验证**：`InterviewResultHandlerTest`、`InterviewEvidenceTest`；隔离库集成；报告接口断言。
- **追溯**：`design.md` 8.3.4、8.9；`spec.md` 5.6.1-4、5.9.1-9；实验 E/M

**子任务清单（完成状态见复选框与 13.7）**

- [ ] **Q-03.1 固化"先评价、后结束"的调用顺序**（依赖：Q-02.6）
  - 文件：`InterviewResultHandler.java:97-99`（评价）与 `:129-135`（结束）
  - 要点：`evaluateAnswer` 保持在结束分支之前；结束分支只在该次评价成功后才 `reportTask`；评价抛异常则整体回滚、不产生报告任务。
  - 验收：单测 `InOrder` 断言 `insertEvaluation` 先于 `interviews.reportTask`；实验 E 中最后一题回答后恰 1 条评价、1 条报告任务、0 条新 ASSISTANT。
  - 验证：单测 `budgetE_*` + 隔离库集成。
- [ ] **Q-03.2 报告 payload 增加 `questionCountRule` / `askedQuestionCount`**（依赖：Q-02.2）
  - 文件：`InterviewService.reportTask`（`:237-255`）
  - 要点：`payload` 新增两字段；`askedQuestionCount` 取 `askedQuestions(interviewId)`（`/end` 与自动结束两条入口都成立）；不改 `completionType` 计算与 `versions`。
  - 验收：`INTERVIEW_REPORT` 任务快照含两字段且数值正确；`/end` 提前结束时 `askedQuestionCount` 为当时实际值。
  - 验证：`InterviewServiceTest` 捕获 payload + 接口测试。
- [ ] **Q-03.3 报告结果新增 `answeredAskCount`（保留原字段）**（依赖：Q-03.2）
  - 文件：`InterviewResultHandler.report`（`:186-197` 去重循环、`:221-231` 组装的 `report` Map）
  - 要点：循环内同时收集 `answered`（按 `question_no`）与 `asked`（按 `question_message_id`）；`report.put("answeredAskCount", ...)`、`report.put("askedQuestionCount", ...)`、`report.put("questionCountRule", ...)`；`answeredQuestionCount`（`:230`）语义不变；`MAIN_ONLY` 会话 `answeredAskCount` 可为 `null`。
  - 验收：`limit=5`、2 主 + 3 追问 → `answeredQuestionCount=2`、`answeredAskCount=5`、`completionType=NORMAL`；历史 `report_json` 不被重写、不补齐。
  - 验证：单测（构造 5 条评价的 `evaluations` 桩）+ 隔离库报告查询（`report_json->>'$.answeredAskCount'`）。
- [ ] **Q-03.4 复用既有幂等与去重，不新增幂等表**（依赖：Q-03.1）
  - 文件：`InterviewResultHandler.java:153-155`（`evaluationByAnswer` 预检）、`:178-181`（报告前置校验）、`InterviewMapper.insertReport`（`:125-131`）
  - 要点：沿用 `uk_answer_message_id`、`uk_task_id`、`uk_interview_id`；报告任务重试仍走 `prepareRetry`；不新增表/键/计数列。
  - 验收：实验 M：最后一题评价已存在时重复回调不新增评价；报告任务失败重试后仅 1 条报告、0 条新题。
  - 验证：单测 `budgetM_*` + 集成。
- [ ] **Q-03.5 用户提前结束路径不变**（依赖：无；可并行）
  - 文件：`InterviewService.end`（`:219-235`）
  - 要点：`/end` 只校验版本与 `WAITING_ANSWER`，不读额度、不校验 `asked`；`completionType=EARLY_END`；报告字段由 Q-03.2/Q-03.3 自然带出。
  - 验收：实验 L：`/end` 后 `asked` 不变、无新题、`completionType=EARLY_END`；最后一题尚未回答时也可提前结束。
  - 验证：接口测试（Q-06.5）。

#### Q-04 Agent Schema、轮次处理与提示词调整
- **状态（2026-11-21 修复后）**：实施中，最新验证与缺口见 13.7。
- **角色**：Python
- **前置**：Q-02（字段契约）；可与 Q-02 并行开发，联调在 Q-06
- **文件**：`project/agent/app/schemas.py`、`app/handlers.py`、`app/skills/prompts/interview_turn.md`、`tests/test_handlers.py`、`tests/factories.py`
- **实现要点**：
  1. `InterviewTurnPayload` 新增 `asked_count`（默认 0）、`remaining_questions`（默认 None）、`question_count_rule`（默认 `MAIN_ONLY`），保证旧快照可解析。
  2. `prepare` 另算 `assistant_total`；`asked_effective = max(asked_count, assistant_total)`；`remaining_effective` 与入参取 `min`。
  3. `TOTAL_ASKED` 分支按 `design.md` 8.4.3 顺序判定；`MAIN_ONLY` 保留现有分支与测试预期。
  4. `remaining=0` 时必须返回 `FINISH` + `answer_evaluation`，不带 question。
  5. 提示词版本升级为 `interview-turn-v6`，说明总上限口径、已提问数与"额度为 0 只评价"。
- **验收标准**：`remaining=0` 不输出题目；`remaining=1` + `needsFollowUp` → `FOLLOW_UP`（额度占用）；`MAIN_ONLY` 用例结果不变；旧 payload（缺字段）可按现有行为运行。
- **验证**：`python -m pytest project/agent/tests -q -k interview`（含 `test_handlers.py` 新增用例）；`scripts/business_acceptance.py` 联调时校验新字段。
- **追溯**：`design.md` 8.4；`spec.md` 5.9.1-7/13；实验 B/C/F

**子任务清单（完成状态见复选框与 13.7）**

- [ ] **Q-04.1 `InterviewTurnPayload` 增加三字段（向后兼容）**（依赖：payload 字段名冻结；可并行：是）
  - 文件：`app/schemas.py:827-838`
  - 要点：`asked_count: int = Field(default=0, ge=0, le=62)`、`remaining_questions: int | None = Field(default=None, ge=0, le=10)`、`question_count_rule: Literal["MAIN_ONLY","TOTAL_ASKED"]="MAIN_ONLY"`；不加强制一致性校验。
  - 验收：旧 envelope（`tests/factories.py:306-367`）不含新字段仍能 `model_validate`；范围越界（如 `remaining=11`）被拒。
  - 验证：`pytest project/agent/tests -q -k interview`（本轮未执行）。
- [ ] **Q-04.2 `prepare` 计算有效额度**（依赖：Q-04.1）
  - 文件：`app/handlers.py:1137-1156`
  - 要点：新增 `assistant_total`（`messages` 中 `role=="ASSISTANT"` 数量）、`asked_effective=max(payload.asked_count, assistant_total)`、`remaining_effective=max(0, question_limit-asked_effective)`，若 `remaining_questions` 非空则 `min`；保留 `main_questions`（只统计 `follow_up_no==0`）并返回 `rule`。
  - 验收：`prepare()` 返回值含 `asked_effective`、`remaining_effective`、`rule`；`main_questions` 与 `asked_effective` 不互相替代（2 主 3 追问时分别为 2 与 5）。
  - 验证：pytest 单测（直接调用 `prepare`，用 `conftest` 的 `ExecutionContext`）。
- [ ] **Q-04.3 实现 `TOTAL_ASKED` 决策分支并保留 `MAIN_ONLY`**（依赖：Q-04.2）
  - 文件：`app/handlers.py:1220-1253`（`question_no < payload.question_limit` 分支所在处）
  - 要点：严格按 `design.md` 8.4.3 顺序：`remaining<=0 → FINISH(question=None, LIMIT_REACHED)` → `needs_follow_up and follow_up_no<max → FOLLOW_UP(f+1)` → `remaining>=1 → ASK(q+1, f=0)` → 兜底 FINISH；`MAIN_ONLY` 分支逐行保留；`TOTAL_ASKED` 下不再用 `question_no < question_limit` 判主问题。
  - 验收：实验 B（2 主 + 3 追问）、实验 C（`remaining=1` + `needs_follow_up` → `FOLLOW_UP`；当前主问题已 2 次追问 → `ASK` 或 `FINISH`）；既有 `test_turn_finishes_when_question_limit_reached`（`test_handlers.py:966-975`）与 `test_turn_asks_next_question_when_followup_budget_exhausted`（`:978-991`）预期不变。
  - 验证：pytest 新增用例 + 既有用例回归。
- [ ] **Q-04.4 额度为 0 时丢弃模型题目**（依赖：Q-04.3）
  - 文件：`app/handlers.py:1248-1253`（FINISH 返回处）
  - 要点：`remaining_effective<=0` 时即使 `followUpQuestion`/`nextQuestion` 非空也一律丢弃；`answer_evaluation` 必须保留；`stop_reason` 复用 `LIMIT_REACHED`，不新增枚举。
  - 验收：`remaining=0` + 模型给足追问与下一题 → `action=FINISH`、`question is None`、评价分数与模型一致。
  - 验证：pytest `test_total_asked_zero_budget_drops_questions`（建议名）。
- [ ] **Q-04.5 提示词升级 `interview-turn-v6`**（依赖：Q-04.3）
  - 文件：`app/skills/prompts/interview_turn.md:5`（version）、`:16-17`（规则段）、`app/operations.py:128`（`PROMPT_VERSIONS[INTERVIEW_TURN]`）
  - 要点：两处版本号同时改 `v6`；第 16 行拆为"总上限（含追问）"与"每主问题最多 `maxFollowUps`"两条，并新增 `remainingQuestions=0` 时只输出评价、`needsFollowUp=false`、题目为 null；第 17 行补"服务端还会复核总提问额度，越界题目不会保存"；其余评价格式与技能约束不动。
  - 验收：两处版本字符串一致为 `interview-turn-v6`；提示词文本包含"remainingQuestions"与"总上限"表述；不删除原有评分维度与 skillScores 约束。
  - 验证：`pytest project/agent/tests/test_operations.py -q` + 文本检索（本轮未执行）。
- [ ] **Q-04.6 测试工厂与 Agent 用例**（依赖：Q-04.3、Q-04.5）
  - 文件：`tests/factories.py:306-367`（两个 envelope）、`tests/test_handlers.py:943-1019`（面试区段）
  - 要点：`interview_turn_followup_envelope` 增加可选 `question_count_rule/asked_count/remaining_questions` 参数（默认不写以保持旧形态）；`_turn_prepared` 辅助补齐新键；新增 `MAIN_ONLY` 回归用例与 `TOTAL_ASKED` 用例；断言 `prompt_version` 存在但不硬编码具体值（版本号断言放 Q-06.4）。
  - 验收：新增用例覆盖 B/C/F 且旧用例零改动通过；工厂默认输出与当前 JSON 完全一致（diff 为空）。
  - 验证：pytest（本轮未执行）。

#### Q-05 前端计数展示
- **状态（2026-11-21 修复后）**：实施中，最新验证与缺口见 13.7。
- **角色**：前端
- **前置**：Q-02（接口字段）
- **文件**：`src/api/types.ts`、`src/types/workbench.ts`、`src/api/mock/workbench.mock.ts`、`src/views/interview/InterviewView.vue`
- **实现要点**：
  1. `InterviewVO` 增加可选 `questionCountRule`、`askedQuestionCount`、`remainingQuestionCount`；mock 同步。
  2. 创建表单文案改为"总提问上限（含追问，3–10）"，补充"追问同样计入总数"；3..10 校验不变。
  3. 会话头：`TOTAL_ASKED` 显示"已提问 3 / 5（含追问）"；`MAIN_ONLY` 保持"N 个主问题"。
  4. `asked == limit` 时展示"这是最后一题，提交后生成报告"；`canAnswer` 条件不变（不得因子弹/提示禁用提交）。
  5. 历史列表按规则版本分支文案；报告页保留"有效回答主问题数"，`answeredAskCount` 存在时追加"有效回答（含追问）"。
- **验收标准**：新会话显示 3/5；历史会话文案不变；缺字段时不报错、不显示计数；最后一题仍可提交。
- **验证**：`npx vue-tsc --noEmit`；手工检查新/旧会话与刷新；Playwright（可选）。
- **追溯**：`design.md` 8.6.2；`spec.md` 5.9.1-2、4.5-5；实验 D/N

**子任务清单（完成状态见复选框与 13.7）**

- [x] **Q-05.1 前端类型与 mock 增加三字段**（依赖：Q-02.8；可并行：是）
  - 完成说明（2026-11-21 任务执行）：`InterviewVO` / `InterviewReportVO` / `workbench.ts` / `mock` 已加字段；`npx vue-tsc --noEmit` 通过（无输出、退出码 0）。
  - 文件：`src/api/types.ts:802-818`（`InterviewVO`）、`:851-861`（`InterviewReportVO`）、`src/types/workbench.ts:237-246`、`src/api/mock/workbench.mock.ts:153-158`
  - 要点：`questionCountRule?: 'MAIN_ONLY'|'TOTAL_ASKED'|null`、`askedQuestionCount?: number|null`、`remainingQuestionCount?: number|null`；报告加 `answeredAskCount?: number|null`、`askedQuestionCount?: number|null`、`questionCountRule?: string|null`；mock 同步新会话样例。
  - 验收：`npx vue-tsc --noEmit` 零错误；可选字段未提供时既有页面编译通过。
  - 验证：在 project/frontend 执行 `npm run type-check`，本轮通过。
- [ ] **Q-05.2 创建表单文案改为"总提问上限（含追问）"**（依赖：Q-05.1）
  - 文件：`InterviewView.vue:634-637`（表单项与提示）、`:305-306`（校验）
  - 要点：标签改"总提问上限（含追问，3–10）"；提示改"每个主问题最多追问 2 次，追问同样计入总数"；校验仍为 3..10 且数值不变。
  - 验收：页面显示新文案；输入 2 或 11 仍被拦截；输入 3..10 通过。
  - 验证：手工走查 + 前端单测（若已引入）。
- [ ] **Q-05.3 会话头计数与最后一题提示**（依赖：Q-05.1）
  - 文件：`InterviewView.vue:650`（会话头 meta）、消息元信息区（`:661` 附近）
  - 要点：`questionCountRule==='TOTAL_ASKED'` 时显示 `已提问 {{askedQuestionCount}} / {{questionLimit}}（含追问）`；`MAIN_ONLY` 或字段缺失保持 `{{questionLimit}} 个主问题`；`TOTAL_ASKED && askedQuestionCount>=questionLimit && status==='WAITING_ANSWER'` 时显示"这是最后一题，提交后生成报告"；消息层级仍显示"第 N 题 · 追问 M"。
  - 验收：新会话第 3 题显示"已提问 3 / 5（含追问）"；历史会话文案逐字不变；最后一题提示只在额度用尽且待答时出现。
  - 验证：手工走查新/旧会话 + 刷新。
- [ ] **Q-05.4 历史列表与报告口径分支**（依赖：Q-05.1）
  - 文件：`InterviewView.vue:803`（历史列表）、`:728`（报告事实区）
  - 要点：列表 `TOTAL_ASKED` 显示"已提问 x / y（含追问）"，`MAIN_ONLY` 保持"计划 N 个主问题"；报告保留"有效回答主问题数"，仅当 `answeredAskCount != null` 追加"有效回答（含追问）N"。
  - 验收：两分支文案正确；`answeredAskCount` 缺失时不显示追加项且不报错。
  - 验证：手工走查历史列表与报告页。
- [ ] **Q-05.5 禁止改动 `canAnswer`（守护项）**（依赖：无）
  - 文件：`InterviewView.vue:96-104`
  - 要点：不加入任何额度/剩余题数条件；提交只依赖 `status`、`version`、`latestQuestion` 与内容长度。
  - 验收：`asked==limit` 的最后一题，提交按钮与快捷键仍可用；代码审查中 `canAnswer` 无新增条款。
  - 验证：手工走查 + 代码审查（13.3 R-05）。

#### Q-06 单元、并发、幂等及完整报告验收
- **状态（2026-11-21 修复后）**：实施中，最新验证与缺口见 13.7；设计阶段未执行的记录不代表当前状态。
- **角色**：测试 + Java + Python + 前端
- **前置**：Q-01～Q-05
- **文件**：新增 `project/backend/src/test/java/com/aijob/backend/interview/InterviewQuestionBudgetTest.java`（单元）与 `InterviewQuestionBudgetConcurrencyTest.java`（隔离库）；更新 `InterviewResultHandlerTest`、`InterviewServiceTest`、`SevenCaseIntegrationTest`、`project/agent/tests/test_handlers.py`、`test_operations.py`、`scripts/business_acceptance.py`；必要时补 `T-02`/`T-07`/`T-08` 用例
- **实现要点**：
  1. 将 `design.md` 8.8 的 A–N 逐条映射为测试方法，方法名标注实验编号（如 `budgetA_fiveMainQuestionsStopAtFive`）。
  2. 并发用例：同一会话两个 `INTERVIEW_TURN` 回调争抢最后额度，断言仅 1 条 ASSISTANT、1 次 succeed、另一次状态冲突；使用隔离库与真实事务。
  3. 幂等用例：同 `Idempotency-Key` 重复提交、同 digest 重复回调、不同 digest 冲突、旧 `pending_task_id` 晚到、失败任务重试成功后只新增 1 题、最后评价/报告任务重试。
  4. 完整报告用例：`limit=5`、2 主 + 3 追问 → 校验 `answeredQuestionCount=2`、`answeredAskCount=5`、`completionType=NORMAL`、评价 5 条、报告 1 条。
  5. 历史回归：`MAIN_ONLY`、`question_limit=3`、已有 5 条提问，按旧规则结束且展示不变。
- **验收标准**：A–N 全部有对应用例；实际执行（未来阶段）后全绿；未通过项不得标记完成；测试不依赖真实模型（Fake LLM/Fake Provider）。
- **验证**：`mvn -pl project/backend test`（含隔离库 profile）、`python -m pytest project/agent/tests -q`、`python project/agent/scripts/business_acceptance.py`（按现有脚本入口）、前端 `vue-tsc`。
- **追溯**：`design.md` 8.8、8.9；`spec.md` 8.6–8.9；A16–A18

**子任务清单（完成状态见复选框与 13.7）**

- [x] **Q-06.1 Java 预算单元用例（A/B/C/E/F/J/K）**（依赖：Q-02.6、Q-03.1；可并行：随 Q-02 编写）
  - 完成说明（2026-11-21 任务执行）：新增 `InterviewQuestionBudgetTest`（9 个用例，覆盖 A/B/C/E/F/J/K/N 与报告口径）；`mvn -o -Dtest="InterviewQuestionBudgetTest" test` 通过。
  - 文件：新增 `project/backend/src/test/java/com/aijob/backend/interview/InterviewQuestionBudgetTest.java`
  - 要点：沿用 `InterviewResultHandlerTest.java:17-45` 的 Mockito 风格，通过 `handler.success(task, json.tree(...))` 触发私有 `turn`（不得改可见性）；方法名带实验编号：`budgetA_fiveMainQuestionsStopAtFive`、`budgetB_twoMainThreeFollowUps`、`budgetC_lastSlotAllowsFollowUp`、`budgetE_lastAnswerEvaluatedThenEnd`、`budgetF_modelOverrunIgnored`、`budgetJ_saveFailureKeepsAsked`、`budgetK_staleTaskRejected`。
  - 验收：每条断言与 `design.md` 8.8.1 表一致（如 A：5 条 ASSISTANT、第 6 次回调不插入、`reportTask` 恰 1 次；F：题目被忽略、评价保存；J：`insertMessage` 抛异常后 `asked` 不变）。
  - 验证：已通过：在 project/backend 执行 `mvn -o -Dtest=InterviewQuestionBudgetTest test`；组合回归见 13.7。
- [x] **Q-06.2 并发争抢最后额度（I）**（依赖：Q-02.6、Q-01.1）
  - 文件：新增 `InterviewQuestionBudgetIntegrationTest.java`（真实独立事务，隔离库 `career_business_test`）；`InterviewQuestionBudgetConcurrencyTest` 保留为 Mock 分支测试
  - 要点：两个独立事务/线程对同一会话最后 1 个额度回调；断言 "1 次 succeed + 另 1 次 `INVALID_STATE`" 而不只是最终条数。
  - 验收：`COUNT(ASSISTANT)` 恰好 +1；失败方不重试建题；会话状态一致。
  - 验证：在 project/backend 执行 `mvn -o -Dintegration-tests=true -Dtest=InterviewQuestionBudgetIntegrationTest test`，真实 MySQL 双事务已通过；环境前置见 13.7。
- [ ] **Q-06.3 幂等与重试（H/M）**（依赖：Q-02.3、Q-03.4）
  - 文件：`InterviewServiceTest.java`（追加用例）、`InterviewResultHandlerTest.java`（追加 `budgetM_reportRetryDedupe`）、接口脚本（Q-06.5）
  - 要点：同 `Idempotency-Key` 重复提交 → 1 条 USER；失败任务重试成功 → `asked` +1；同 digest 重复回调不增加；不同 digest 冲突；最后一题评价/报告任务重试唯一。
  - 验收：`budgetH_idempotentRetry`、`budgetM_reportRetryDedupe` 断言通过；无新增幂等表。
  - 验证：`mvn -pl project/backend -Dtest="Interview*Test" test`（本轮未执行）。
- [x] **Q-06.4 Python 用例与提示词版本（B/C/N）**（依赖：Q-04.3、Q-04.5）
  - 文件：`project/agent/tests/test_handlers.py`（面试区段 `:943-1019` 之后追加）、`tests/test_operations.py`（`:229-300` 面试区段）、`tests/factories.py`
  - 要点：`test_total_asked_two_main_three_follow_ups`、`test_total_asked_last_slot_follow_up`、`test_total_asked_zero_budget_drops_questions`、`test_main_only_unchanged`；`test_operations.py` 断言 `PROMPT_VERSIONS["INTERVIEW_TURN"]=="interview-turn-v6"`（不依赖模型输出）。
  - 验收：新增用例通过；（未来执行时）`MAIN_ONLY` 既有 4 条用例零改动通过。
  - 验证：使用本机 venv 执行 test_handlers.py 与 test_operations.py，111 项已通过；命令及环境见 13.7。
- [ ] **Q-06.5 接口验收脚本（D/G/L + 8.9.1 十步）**（依赖：Q-06.1～Q-06.4）
  - 文件：新增 `project/agent/scripts/interview_budget_acceptance.py`（沿用 `business_acceptance.py:1-45` 的 `call/data` 风格与正常登录 Bearer/CSRF、`Idempotency-Key` 头；独立测试默认端口 8081，禁止依赖开发用户头绕过鉴权）
  - 要点：覆盖 `budgetD_lastQuestionAnswerable`（`/answers` 202、`COUNT(ASSISTANT)` 仍 5）、`budgetG_nonCountEvents`（重播/刷新/重录/重传/重转写后 `asked` 与版本不变）、`budgetL_earlyEnd`（`EARLY_END`、`asked` 不变）；再按 `design.md` 8.9.1 逐步执行 10 步并输出结果 JSON（不覆盖历史结果文件）。
  - 验收：脚本可重复运行；每步有断言与结果落盘；失败时输出失败步骤编号（对应 8.9.1 的 5 条失败判定）。
  - 验证：`python project/agent/scripts/interview_budget_acceptance.py`（本轮未执行；Agent 用 Fake）。
- [ ] **Q-06.6 历史会话回归（N）**（依赖：Q-01.2、Q-02.6）
  - 文件：`InterviewServiceTest.java`（`budgetN_mainOnlyUnchanged`）、T-08 手工回归清单
  - 要点：`MAIN_ONLY`、`question_limit=3`、已有 5 条提问（含追问）继续回答，按旧规则推进，不因 `asked>limit` 立即结束；展示与报告字段不变。
  - 验收：`budgetN_mainOnlyUnchanged` 通过；前端历史文案与升级前逐字一致。
  - 验证：`mvn -pl project/backend -Dtest="Interview*Test" test` + T-08 手工（本轮未执行）。
- [ ] **Q-06.7 汇总验收结论并更新状态**（依赖：Q-06.1～Q-06.6）
  - 文件：本章（追加"验收结果"记录）、`docs/功能资料/09-模拟面试/语音模拟面试_process.md`
  - 要点：A–N 逐条勾选并附命令与结果；未执行或失败的用例保持"待实现/未通过"，禁止只写"已通过"而无命令输出。
  - 验收：A–N 全部有测试方法名与执行记录；13.3 回归项全部有结论；主任务状态按 13.0 口径更新。
  - 验证：文档核对 + 命令留痕（编码阶段执行）。

### 13.1 实现落点与验收对应（2026-11-21 设计阶段补充）

> 本节不改变 Q-01～Q-06 的编号、状态（**历史设计阶段均为待实现，当前以复选框及 13.7 为准**）与既有验收标准，只把 `design.md` 8.2.4–8.10.1 的实现级细节回填为可执行落点。行号以当前工作区为准，编码前需复核。各子任务的完整五要素（依赖 / 文件与类方法 / 实现要点 / 验收标准 / 验证方式）见各任务下的"可领取子任务"列表，本表仅作落点索引。

| 任务 | 关键落点（文件:行号） | 测试落点 | 必须断言 |
|------|---------------------|---------|---------|
| **Q-01** | 新增 `V16__add_interview_question_count_rule.sql`（设计稿）；`InterviewMapper.insertSession`（:18-28）增列与参数；`InterviewService.create`（:96-97）显式写 `TOTAL_ASKED` | 隔离库 Flyway（`aijob_interview_budget_test`）+ `SHOW CREATE TABLE` + 历史行分组统计 | 历史行全为 `MAIN_ONLY`；新会话 `TOTAL_ASKED`；`question_limit` 的 3..10 CHECK 不变；回滚顺序 `DROP CHECK → DROP COLUMN` |
| **Q-02** | `InterviewMapper.askedQuestions`（:104 后新增）；`InterviewService.turnPayload`（:311-325）、:92/:178-186/:199、`sessionVo`（:270-287）、`page`（:38-48）；`InterviewResultHandler.turn`（:86-146） | 新增 `InterviewQuestionBudgetTest`、`InterviewQuestionBudgetConcurrencyTest`；更新 `InterviewResultHandlerTest`（既有用例需补 `pending_task_id`、`question_count_rule` stub，否则会因新增校验失败）、`InterviewServiceTest` | `asked` 在 `lockSession` 后、`evaluateAnswer` 前读取；额度 0 不插入 ASSISTANT；并发只成功一道；`MAIN_ONLY` 行为不变 |
| **Q-03** | `InterviewResultHandler.turn`（:129-135）先评价后结束；`report`（:186-197、:230）加 `answeredAskCount`；`InterviewService.reportTask`（:237-255）加报告字段 | `InterviewResultHandlerTest`、`InterviewEvidenceTest`；隔离库集成 | 最后一题回答后评价恰 1 条、报告 1 条、无第 6 题；`answeredQuestionCount=2`、`answeredAskCount=5`、`completionType=NORMAL` |
| **Q-04** | `app/schemas.py:827-836` 三字段；`app/handlers.py:1137-1156` 有效额度与 :1220-1253 `TOTAL_ASKED` 分支；`app/skills/prompts/interview_turn.md:5,:16` 升级 `v6`；`app/operations.py:128` 同步 | `tests/test_handlers.py`、`tests/test_operations.py`、`tests/factories.py` | `remaining=0` 不输出题目；`remaining=1` + `needsFollowUp` → `FOLLOW_UP`；`MAIN_ONLY` 结果不变；旧 payload 缺字段可运行 |
| **Q-05** | `src/api/types.ts`（`InterviewVO` :802-818、报告类型）；`src/types/workbench.ts`；`src/api/mock/workbench.mock.ts`；`InterviewView.vue`（:305-306、:634-637、:650、:728、:803） | `npx vue-tsc --noEmit`；新/旧会话手工走查；可选 Playwright | 新会话显示"已提问 3 / 5（含追问）"；历史文案不变；缺字段不显示计数；最后一题仍可提交（`canAnswer` :96-104 不改） |
| **Q-06** | 按 `design.md` 8.8.1 表创建/更新用例；新增 `scripts/interview_budget_acceptance.py`（正常登录鉴权） | `mvn -pl project/backend -Dtest="Interview*Test" test`；`python -m pytest project/agent/tests -q -k interview`；接口脚本 | A–N 每条均有方法名对应；并发/幂等/回滚/历史回归全部覆盖；未实际执行前不得标记完成 |

### 13.2 推荐执行顺序与并行度（2026-11-21 编码任务规划）

| 顺序 | 子任务 | 进入条件 | 主要产出 | 并行结论 |
|------|--------|---------|---------|---------|
| 1 | **Q-01.1**、**Q-01.3** | 无 | `V16` 脚本、规则解释工具 | 可同日并行；Q-01.3 不依赖脚本执行 |
| 2 | **Q-01.2**、**Q-01.4** | Q-01.1 脚本评审通过 | `insertSession` 写规则、回滚文档 | 可与第 1 步的 Q-01.3 并行 |
| 3 | **Q-02.1**、**Q-02.4** | Q-01.1/Q-01.2 完成 | 权威计数查询、`pending_task_id` 校验 | 二者可并行 |
| 4 | **Q-02.2**、**Q-02.5**、**Q-02.8** | Q-02.1；payload 字段名冻结 | payload 三字段、锁后立即读、展示字段 | Q-02.8 与 Q-02.5 可并行；Q-02.2 必须先于 Q-04.1 |
| 5 | **Q-04.1 → Q-04.2 → Q-04.3 → Q-04.4/Q-04.5/Q-04.6** | 字段名冻结（Q-02.2 之前完成评审） | Agent 契约、决策分支、v6 提示词 | 与第 4/6 步（Java 线）**并行**，不同语言线 |
| 6 | **Q-02.3**、**Q-02.6**、**Q-02.7**、**Q-02.9** | Q-02.2、Q-02.5 | 锁内限制、编号校验、既有测试 stub | 串行；Q-02.9 必须与 Q-02.4/Q-02.6 同批完成 |
| 7 | **Q-03.1 → Q-03.2 → Q-03.3 → Q-03.4**（Q-03.5 可提前） | Q-02.6 | 先评价后结束、报告字段 | 与第 5 步（Python 线）并行 |
| 8 | **Q-05.1 → Q-05.2～Q-05.5** | Q-02.8 | 前端类型与文案 | 与第 7 步并行 |
| 9 | **Q-06.1～Q-06.7** | 第 6/7/8 步完成；用例可随 4/5 步先写 | A–N 用例、并发、幂等、接口脚本 | 编写可并行，执行与结论必须串行收口 |

**并行与阻塞（不可违背）**

1. 三条可并行的工作线：Java（Q-02 → Q-03）、Python（Q-04）、前端（Q-05）。三者共享同一份 payload/响应字段表，字段名冻结是唯一同步点。
2. **强串行点**：Q-02.2（字段写入）→ Q-04.1；Q-02.6（额度判定）→ Q-03.1；Q-01.1 → Q-02.1 的集成验证；Q-02.8 → Q-05.1。
3. **禁止顺序**：Q-03 不得先于 Q-02.6（否则退化为"按模型 action 结束"，重现超额）；Q-05 不得先于 Q-02.8（无字段可验收）；Q-06.7 不得在任何用例未执行时提前勾选。
4. Q-04 虽可与 Q-02 并行编码，但**联调与验收只能在 Q-06**；并行期间不得各自改字段名。

### 13.3 回归与风险任务（编码阶段必查）

| 编号 | 类型 | 内容 | 关联子任务 | 处置与判定 | 验证方式 |
|------|------|------|-----------|-----------|---------|
| **R-01** | 回归 | `InterviewResultHandlerTest.java:17-45` 只 stub `status/question_limit/current_question_no/current_follow_up_no/version`，新增 `pending_task_id` 校验后会抛 `INVALID_STATE` | Q-02.9 | 补 `pending_task_id=task` 与 `question_count_rule="MAIN_ONLY"`；不得删改原断言 | `mvn -Dtest=Interview*Test` |
| **R-02** | 回归（本轮新发现） | `SevenCaseIntegrationTest.java:131-135` 手工 `INSERT interview_session` 未写 `pending_task_id`，随后直接 `interviews.success(turn,...)`（`:147`），新增校验后**必失败** | Q-02.9 | 插入时补 `pending_task_id`（或插入后 `UPDATE` 为当前 turn 任务）；不得删除或跳过该用例 | 同上 |
| **R-03** | 已加固 | `failure()` / `prepareRetry()` / `report()` 已加入会话行锁、当前任务与状态保护 | Q-02.4、13.8 | 用户授权报告重试验收后实施；真实 MySQL 10 项矩阵及浏览器报告故障恢复通过，合法重试保留 | InterviewReportRetryIntegrationTest；验收证据 |
| **R-04** | 评审 | `askedQuestions` 必须是 `turn` 事务中 `lockSession` 后第一条普通 SELECT，否则 RR 旧快照可能少计 1 条 | Q-02.5 | 代码审查必查项；用 `InOrder` 单测固化顺序 | 评审 + 单测 |
| **R-05** | 守护 | `canAnswer`（`InterviewView.vue:96-104`）不得加入额度条件 | Q-05.5 | 审查 diff；`asked==limit` 手工提交一次 | 手工 + 审查 |
| **R-06** | 风险（本轮新发现） | `String.valueOf(session.get("question_count_rule"))` 对缺失值得到字符串 `"null"` | Q-01.3 | `parse` 必须显式处理 `null`/空串/`"null"`/未知值 → `MAIN_ONLY` | 单测 |
| **R-07** | 风险 | `TaskService.create` 对 `INTERVIEW_REPORT` 抵扣同会话 RUNNING 任务（`:69-74`），语义变化可能误触"每人 2 个活动任务" | Q-06.1/Q-06.3 | 用 `SevenCaseIntegrationTest` 固定该行为 | `mvn -Dtest=SevenCaseIntegrationTest` |
| **R-08** | 风险 | 提示词 `v5→v6` 会进入任务输入摘要与运行记录 | Q-04.5/Q-06.4 | 断言版本生效，但不得把模型输出当契约 | `pytest -k interview` |
| **R-09** | 文档 | 行号漂移：`design.md` 8.4.5 引 `test_handlers.py:789`，实测面试区段在 `:943-1019`；`turn` 实测 `:86-146` | Q-06.4 | 编码前复核；以方法名/代码内容定位，不纯靠行号 | 检索核对 |
| **R-10** | 回归 | 历史 `MAIN_ONLY` 会话（`limit=3`、已有 5 条提问）不得因 `asked>limit` 立即结束 | Q-06.6 | `budgetN_mainOnlyUnchanged` + T-08 手工；文案逐字不变 | 单测 + 手工 |

### 13.4 与 `spec.md` / `design.md` 的一致性检查（2026-11-21）

| 检查项 | `spec.md` | `design.md` | 本文件落点 | 结论 |
|--------|-----------|-------------|-----------|------|
| 计数权威值 = ASSISTANT 行数 | 6.1-4 | 8.2.2 | Q-02.1 | 一致 |
| 会话级规则版本 `MAIN_ONLY`/`TOTAL_ASKED` | 6.1-3 | 8.3.2、8.3.5 | Q-01.1、Q-01.2、Q-01.3 | 一致 |
| 上限沿用 3..10、边界 CHECK 不动 | 5.9.1-2、6.1-5 | 8.3.5(1) | Q-01.1（不触碰）、Q-05.2（校验不变） | 一致 |
| 最后一题可答、不由前端拦截 | 5.9.1-8 | 8.7.1 | Q-02.3、Q-05.5 | 一致 |
| 先评价后结束 | 5.9.1-9 | 8.5.1、8.7.1 | Q-03.1 | 一致 |
| 超额由后端强制拒绝 | 5.9.1-7/10 | 8.5.1 | Q-02.6、Q-02.7 | 一致 |
| 重试/并发/重复回调不计题 | 5.9.1-5/10 | 8.5.2、8.8 | Q-06.2、Q-06.3 | 一致 |
| 历史会话行为不变 | 5.9.1-11、7.9 | 8.3 | Q-01、Q-06.6 | 一致（`O12` 未定，按"不切换"实现） |
| 报告保留主问题口径、新增含追问口径 | 5.6.1、7.10 | 8.3.4 | Q-03.3、Q-05.4 | 一致（`O13` 未定，按"新增"实现） |
| 响应字段与前端展示 | 5.9.2-4 | 8.6.1、8.6.3 | Q-02.8、Q-05.1～Q-05.4 | 一致 |
| Agent 只作辅助、后端兜底 | 5.9.1-7 | 8.4.4、8.4.5 | Q-04.3、Q-04.5 | 一致 |
| 不新增计数列/幂等表/业务接口 | 6.1-4 | 8.2.1、8.5.3、8.10 | Q-02.1、Q-03.4 | 一致 |

**差异与需注意（不改变已定设计结论）**

1. 行号漂移：`design.md` 8.5.4/8.8.1 与本文 13.1 的个别行号已与当前工作区不同（如 `turn` 实测 `:86-146`、Python 面试用例实测 `:943-1019`）。以方法名与代码内容定位，编码前复核（R-09）。
2. 顺序表述：`design.md` 8.10.1 写作 `Q-01→Q-02→Q-04→Q-03→Q-05→Q-06`，本文 13.2 写作 `Q-01→Q-02→{Q-03,Q-04}→Q-05→Q-06`。二者等价（Q-04 在字段冻结后可并行），无冲突。
3. 报告展示：`design.md` 8.6.2 建议追加"有效回答（含追问）"，本文 Q-05.4 明确"仅 `answeredAskCount != null` 时展示"，与 `spec.md` 4.5-3/4.5-5 的旧客户端兼容要求一致。

### 13.5 本轮边界与未执行事项（2026-11-21 编码任务规划阶段声明）

> 本节记录的是**编码任务规划阶段**的边界，已被 13.6「任务执行进度」取代；执行阶段的代码与验证结论以 13.6 为准。

1. 本轮仅修订本文档与过程记录；**未修改任何业务代码**（Java / Python / 前端）、**未创建或执行 `V16`**、**未修改已执行的 `V15`**、未执行任何数据库变更。
2. 未调用真实或 Fake 模型，未启动后端/Agent/前端，未运行任何单元、集成、接口、并发或压测用例。
3. `design.md` 8.8 的 A–N 实验与 8.9.1 的十步推演仍为**设计预期**，不能视为"已通过"；Q-06 全部子任务保持"待实现"。
4. Q-01～Q-06 六个主任务与 36 个可领取子任务**全部为待实现**，未把任何未实现内容标记为完成，也未改动既有任务编号与状态。
5. 行号以 2026-11-21 当前工作区实测为准；编码时若发生漂移，须回写本文 13.1/13.3 与 `design.md` 8.5.4，不得只在代码中就地处理。

**编码顺序（与 `design.md` 8.10.1 一致）**：见 13.2；`Q-01 → Q-02（字段冻结）→ {Q-03, Q-04} → Q-05 → Q-06`。
### 13.6 初次实施记录（历史快照，最新状态见 13.7）

> 以下保留首次实施时的验证记录，已被 13.7 更新；首次实施时主任务记为待实现。只有"子任务验收标准全部满足 + 13.3 回归项通过 + 实际执行命令留痕"才允许标完成；本节只记录代码已落地与本机实际验证结果，不代表验收通过。

| 任务 | 代码实现 | 本机实际验证 | 未完成/未执行 |
|------|---------|-------------|--------------|
| **Q-01** | `V16__add_interview_question_count_rule.sql`、`QuestionCountRule`、`insertSession` 显式写 `TOTAL_ASKED` | `mvn -o -q compile` 通过 | 未在隔离库执行 `V16`；回滚未演练；历史行分布未查 |
| **Q-02** | `askedQuestions` 查询、`turnPayload` 三字段、`answer` 锁内读取、`pending_task_id` 校验、额度耗尽分支、编号校验、`sessionVo`/`page` 三字段 | `InterviewQuestionBudgetTest`（9）、`InterviewQuestionBudgetConcurrencyTest`（2）、`InterviewResultHandlerTest`（1）、`InterviewServiceTest`（3）全部通过；`mvn -o -q compile` 通过 | 未执行 `EXPLAIN`；未验证真实行锁并发；未跑接口字段断言 |
| **Q-03** | 先评价后结束（顺序未改且被单测固化）、报告 payload 两字段、`answeredAskCount`（`question_message_id` 去重）、幂等复用既有唯一键 | 报告口径单测通过（2 主 3 追问 → `answeredQuestionCount=2`、`answeredAskCount=5`）；`InterviewEvidenceTest`（3）通过 | 未查隔离库 `report_json`；提前结束仅代码保留，未跑接口 |
| **Q-04** | `InterviewTurnPayload` 三字段（默认兼容）、`prepare` 有效额度、`TOTAL_ASKED`/`MAIN_ONLY` 双分支、额度 0 丢题、提示词 v6、工厂可选参数 | 未执行（本机无 pytest：`No module named pytest`；无 Python 3.10/3.11 运行时） | 全部 Python 用例未运行 |
| **Q-05** | 类型与 mock 三字段、创建表单文案、会话头"已提问 3 / 5（含追问）"、最后一题提示、历史列表分支、报告追加口径；`canAnswer` 未改 | `npx vue-tsc --noEmit` 通过 | 未做浏览器手工走查（本机 esbuild 不可用，Vite dev 无法启动） |
| **Q-06** | 新增 `InterviewQuestionBudgetTest`、`InterviewQuestionBudgetConcurrencyTest`、`scripts/interview_budget_acceptance.py`；更新既有测试 stub 与 `SevenCaseIntegrationTest` 前置 | 15 个 Java 单测通过；`SevenCaseIntegrationTest` 6 个用例全部 **skipped**（需外部数据库） | A–N 接口/并发/幂等全矩阵未执行；验收脚本未运行；数据库未迁移 |

**已实际执行的命令与结果：**

| 命令 | 结果 |
|------|------|
| `mvn -o -q compile`（`project/backend`） | 通过（无输出） |
| `mvn -o -Dtest="InterviewQuestionBudgetTest,InterviewQuestionBudgetConcurrencyTest,InterviewResultHandlerTest,InterviewServiceTest" test` | 通过：Tests run 15, Failures 0, Errors 0 |
| `mvn -o -Dtest="InterviewEvidenceTest" test` | 通过：Tests run 3, Failures 0, Errors 0 |
| `mvn -o -Dtest="SevenCaseIntegrationTest" test` | Tests run 6, Skipped 6（未连数据库，未验证 R-02 运行时行为） |
| `npx vue-tsc --noEmit`（`project/frontend`） | 通过（无错误） |
| `python -m pytest project/agent/tests/test_handlers.py -q -k "budget or total_asked or main_only"` | 未执行：`No module named pytest` |
| `py -3.11/-3.10 -c ...` | 未执行：无可用 Python 3.10/3.11 |

**遗留（阻塞验收）：**

1. `V16` 未在 MySQL 隔离库执行，`question_count_rule` 列与 CHECK 的运行时行为、回滚顺序均待验证。
2. Python 测试未运行；`handlers.py` 的 `TOTAL_ASKED` 分支只有静态审阅。
3. 并发行锁（实验 I）仅以"pending_task_id 已被覆盖"的近似单测覆盖，真实双事务竞争未验证。
4. 接口脚本 `scripts/interview_budget_acceptance.py` 未执行，D/G/L 仍为设计预期。
5. `SevenCaseIntegrationTest` 全部 skipped，R-02 修复只保证编译通过。

### 13.7 修复后实际验收状态（2026-11-21，优先于 13.6 历史记录）

主任务状态统一为“实施中”：主要实现已存在，但浏览器人工回归、报告失败重试全矩阵与目标部署环境仍未全部验收，不将其标为全部完成。Q-06.2 真实并发与 Q-06.4 Python 用例已完成并勾选。

| 范围 | 已验证 | 仍待验证 |
| --- | --- | --- |
| Q-01 迁移 | 独立 MySQL 8.0.41：V1→V15，插入历史记录，执行 V16；历史默认 MAIN_ONLY；UNKNOWN 被 CHECK 3819 拒绝，NULL 被拒绝；DROP CHECK→DROP COLUMN 后保留会话行；新 API 会话为 TOTAL_ASKED | 目标业务库的历史分布、发布升级及旧版本应用回退；未修改或迁移正式业务库 |
| Q-02 预算与并发 | 两线程独立事务争抢第 5 题：一方成功、一方 INVALID_STATE；5 条提问、1 条评价，重复回调不增加；EXPLAIN 使用按 interview_id 的索引访问 | 大数据量性能；旧失败任务晚到保护（R-03）仍需单独加固与验收 |
| Q-03 末题与报告 | API 末题允许 202；2 主问题+3 追问总共 5 条提问、5 条正式回答、5 条评价、1 份 NORMAL 报告；answeredQuestionCount=2、answeredAskCount=5；EARLY_END 与 NO_VALID_ANSWER 分开验证 | 报告任务失败、重试与多标签页的完整矩阵 |
| Q-04 Agent | 修复 5 条 async prepare 测试；test_handlers.py + test_operations.py 共 111 项通过；包含预算双分支与 v6；验收脚本回归另 3 项通过 | 真实面试模型输出质量；Python 3.10/3.11 版本矩阵（本轮使用现有 3.12.4） |
| Q-05 前端 | npm run type-check 通过；接口字段及刷新不增加计数由 API 验证 | 新旧会话的浏览器文案、录音、重播、重录与刷新恢复手工走查 |
| Q-06 接口验收 | full 36 断言、early 7 断言、no-answer 6 断言，共 49 项通过；正常登录 Bearer/CSRF；相同幂等键复用任务，换键重复提交 409，完成后额外提交 409；每场仅 1 份报告 | 实验 G 的浏览器媒体操作未执行；不能据 API 测试宣称 A–N 全矩阵已完成 |

**可复现验证方式：**

- Java：在 project/backend 运行 `mvn -o -Dintegration-tests=true -Dtest=InterviewQuestionBudgetIntegrationTest,SevenCaseIntegrationTest test`；用 `BUDGET_TEST_DB_URL` 指向专用 career_business_test 库（本轮端口 3308），凭据沿用 Spring 配置。另须事先创建并授权同实例 budget_migration_test 专用库。集成测试会清理这个明确命名的迁移实验库，禁止用于保存业务数据。
- Python：在 project/agent 使用本机 `.local-dev-env/venv-asr/Scripts/python.exe` 对应解释器执行 `-m pytest -p no:cacheprovider tests/test_handlers.py tests/test_operations.py tests/test_interview_budget_acceptance.py`。应清除进程继承的冲突旧语音密钥，测试使用 Fake，不调用真实供应商。
- 接口：设置 BUDGET_TEST_EMAIL、BUDGET_TEST_PASSWORD（专用测试账号），执行 `python scripts/interview_budget_acceptance.py --base-url http://127.0.0.1:8081 --interview-id <新会话ID> --scenario full --output-dir <结果目录>`。early/no-answer 分别使用其他新的 WAITING_ANSWER 会话；每场开始都只有第 1 题。
- full 使用短而有效的固定测试回答驱动 Fake 模型，确定性出现 `(1,0),(1,1),(1,2),(2,0),(2,1)`；不适用于验证真实模型的追问质量。
- 本轮隔离证据：`.local-dev-env/budget-validation/e2e-summary.json` 与各次独立 JSON；Java/Python 运行日志位于 `.local-dev-env/logs/budget-*`。失败记录保留，不覆盖成成功。

**环境限制与保留决策：**

- Docker Desktop 日志报 Virtual Machine Platform 未启用，本轮未重启系统或改变 Windows 功能；改用独立 MySQL 3308、临时 Windows Redis 5.0.14.1 6380、后端 8081、Fake Agent 8101 验证。未验证团队 Docker MySQL 8.0.42 / Redis 7.4 部署组合，临时环境不替换团队配置。
- 未回答就结束，或没有有效回答时，报告沿用 NO_VALID_ANSWER；有有效回答再提前结束才为 EARLY_END。实验 L 补充这一前置条件。
- O12 历史未完成会话不切换、O13 保留主问题有效回答数字段并新增含追问字段：本轮沿用既有实现，产品最终确认仍保留待决。
- 本轮不包括真实 TTS、浏览器 WebM/Opus 或真实语音面试整场验收。

**最终组合回归：** 在 project/backend 执行 `mvn -o -Dintegration-tests=true -Dtest=InterviewQuestionBudgetTest,InterviewQuestionBudgetConcurrencyTest,InterviewResultHandlerTest,InterviewServiceTest,InterviewEvidenceTest,InterviewQuestionBudgetIntegrationTest,SevenCaseIntegrationTest test`：30 项通过、0 失败、0 错误、0 跳过，日志 `.local-dev-env/logs/budget-java-final.log`。新增迁移测试还验证了 SHOW CREATE TABLE 的列类型、默认值、约束及回滚后列消失。Python 111 项业务测试 + 3 项验收脚本回归，共 114 项通过。


**Docker 环境进展（2026-11-21 22:01 +08:00）：** 用户授权后已管理员启用 VirtualMachinePlatform，从 Disabled 变为 EnablePending；WSL 组件与固件虚拟化均已开启。当前必须重启 Windows 才能生效，尚未验证 Docker 引擎与 Compose 服务恢复；不能把这一步标为部署组合验收通过。过程与结构化结果见语音模拟面试_process.md 和 .local-dev-env/docker-platform-repair/。


**Docker 重启后恢复结论（2026-11-21）：** HypervisorPresent=True，Docker Engine 29.9.3 正常；项目 Compose MySQL 8.0.42 与 Redis 7.4.12 均 healthy，项目账号 MySQL 查询和 Redis 认证/PING 均通过。Docker 启动故障已解决。业务库仍为 V15，本轮未迁移、未启动业务后端；团队容器恢复不等于预算、报告失败重试或浏览器录音已在该组合完成回归。后续仍在专用测试库推进；证据 .local-dev-env/docker-platform-repair/post-reboot-services.json。


### 13.8 报告失败重试与浏览器录音实际验收（2026-11-21，优先于 13.7 的对应遗留项）

- R-03 旧回调状态保护已补齐：failure/prepareRetry/report 统一核对会话锁、当前待处理任务与状态；新增 InterviewReportRetryIntegrationTest 10 项真实 MySQL 矩阵通过。
- Java 有效回归合计 41 项通过，首次 Evidence 夹具缺当前任务字段的失败及修复过程均保留；未修改原评分断言。
- Edge 与 Chrome 各 14 项 WebM/Opus→真实 Qwen ASR→刷新恢复→确认提交验证通过；Edge 权限拒绝、短录音及重录 6 项通过；页面五题→报告故障→刷新→重试→唯一报告 10 项通过。共 44 项最终浏览器断言。
- 浏览器验收实际发现并修复文字模式提交仍标 VOICE 导致 HTTP400 的问题，显式传递来源；切回语音且无转写 ID 时不允许误提交。
- 完整会话数据库断言：5 题、5 回答（1 VOICE+4 TEXT）、5 评价、1 报告；报告任务一失败一成功；无第 6 题。
- 数据库使用 Docker 3307，报告矩阵 career_business_test，浏览器 career_voice_browser_test，均专用库；正式 career_business 最新成功版本仍 V15。
- 详细方法、命令、证据与范围见 docs/功能资料/09-模拟面试/语音模拟面试-验收证据.md。Q-06.3/Q-06.5 仍不整项勾选：其余出题失败重试全矩阵、浏览器静音/超长等尚未全部执行。
- 物理麦克风、噪声/回声、Safari 与其他容器、真实长录音、真实 TTS、真实面试模型质量仍待验证；不能把虚拟麦克风成功当成完整真实语音面试完成。
---

## 14. M-LA：ASR 结果晚到保护（2026-11-22 新增，9 个主任务 / 41 个可领取子任务）

> 需求依据：用户本次请求（旧请求不得覆盖新状态、失效记录不得复活、重试旧尝试不得覆盖新尝试）+ `spec.md` 4.2-1、5.4.1-5/6、5.4.3-4/5、5.9.1-9（"旧版本结果晚到时，不覆盖当前状态、不增加题数"）。
> 设计依据：`design.md` 第九章 9.0–9.13（重点 9.2.3 允许操作矩阵、9.3.2 固定校验顺序、9.3.3 落盘白名单、9.4.3 条件更新语义、9.4.7 同题收敛、9.5 幂等分层、9.6 前端、9.7 测试矩阵、9.8 浏览器回归、9.9 差异清单）。
> 代码基线：2026-11-22 工作区。**已存在并已落地**：`V17__add_voice_transcription_attempt.sql`、`VoiceMapper` 条件更新、`VoiceTranscriptionService` 两段短事务与九项二次校验、`InterviewService.answer` 的 `markConsumed` 行数校验、前端请求令牌与组件 `key` 重建、`VoiceLateArrivalIntegrationTest` 9 项。
> 本章只做任务规划：**不修改任何业务代码、不创建/执行迁移、不调用供应商、不运行测试、不提交 Git**。
> 行号以当前工作区实测为准，编码前必须复核；行号漂移时以类名/方法名/语句内容定位。

### 14.0 状态口径与编号约定

| 口径 | 含义 | 本章用法 |
|------|------|---------|
| **既有代码状态：完整实现** | 现有代码已满足设计，只需回归验证或补注释，不重复计为"待实现" | 标"已有可复用" |
| **既有代码状态：部分实现（需修正）** | 现有代码存在且可改造，但不满足 9.x 的设计契约 | 标"需修正" |
| **既有代码状态：未实现（需新增）** | 当前工作区没有对应语句/用例/文档 | 标"需新增" |
| 任务状态 | 14.2 复选框保留规划态；2026-11-22 实际执行结果以 **14.9** 为准 | 未验证项不得勾选 |

> 2026-11-22 任务执行阶段已实施 14.2 的 B/C/D 阶段与部分 F/G 阶段，实际结果、命令与未执行项见 14.9。

编号约定：`M-LA-01`～`M-LA-09` 为新增主任务，与 `M0/M1/M2/M3/T/Q` 并列；子任务 `M-LA-01.1`～`M-LA-09.4` 顺序编号，不复用既有编号。**不删除、不改写既有任务**，只新增本章。

### 14.1 主任务总览

| 编号 | 标题 | 角色 | 既有代码状态 | 前置（硬依赖） | 涉及文件（主） | 可并行 |
|------|------|------|-------------|---------------|---------------|--------|
| **M-LA-01** | 状态规则与差距确认 | 文档 + Java + 测试 | 需新增（核对文档） | 无（design 第九章已评审） | 本章、`design.md` 9.9、5 个实现文件（只读） | 可与 M-LA-05 前期并行 |
| **M-LA-02** | 回写保护与 `attempt` 条件更新收敛 | Java | 部分实现 | M-LA-01.1 | `VoiceMapper.java`、`VoiceTranscriptionService.java` | 与 M-LA-05 并行 |
| **M-LA-03** | 同题活跃尝试收敛与读路径（P0） | Java | 部分实现 + 1 项未实现 | M-LA-01.1、M-LA-02.1 | `VoiceMapper.java`、`VoiceTranscriptionService.java` | 与 M-LA-05 并行 |
| **M-LA-04** | 确认提交消费幂等与错误原因收敛 | Java | 部分实现 | M-LA-03.1、M-LA-01.2 | `InterviewService.java`、新增 reason 常量、`api/types.ts`（契约） | 与 M-LA-06.1 并行 |
| **M-LA-05** | 前端令牌、提示分流与来源一致性 | 前端 | 部分实现 | M-LA-04.1（reason 集合冻结） | `useVoiceAnswer.ts`、`VoiceAnswerPanel.vue`、`InterviewView.vue`、`api/types.ts` | 与 M-LA-02/03 并行 |
| **M-LA-06** | 真实 MySQL 并发矩阵（含修复 D-13，P0） | 测试 + Java | 部分实现 + 4 项未实现 | M-LA-02、M-LA-03、M-LA-04 | `VoiceLateArrivalIntegrationTest.java`、新增 `VoiceLateArrivalConcurrencyIntegrationTest.java` | 用例可随 M-LA-02/03 先写 |
| **M-LA-07** | 幂等 / 迁移 / 越权 / 排序矩阵 | 测试 + Java | 1 项与既有共用，6 项未实现 | M-LA-02.1、M-LA-03.1、M-LA-03.2 | 新增 `VoiceAttemptMigrationIntegrationTest.java`、`VoiceRetrySemanticsIntegrationTest.java`（可选）、既有测试类追加 | 与 M-LA-06 并行编写 |
| **M-LA-08** | 浏览器回归（BR-01～BR-10） | 测试 + 前端 | 未实现（需执行） | M-LA-05、M-LA-06 主干通过 | `docs/功能资料/09-模拟面试/语音模拟面试-验收证据.md` | 依赖环境，串行 |
| **M-LA-09** | 文档、回滚检查单与过程记录 | 文档 | 部分实现 | 各阶段随做随记；收口依赖 M-LA-06/07/08 | 本章、`语音模拟面试-回滚与清理.md`、`语音模拟面试-验收证据.md`、`语音模拟面试_process.md` | 贯穿全程 |

**任务量**：9 个主任务 + 41 个可领取子任务 = 50 个编号；其中"需修正"22 项、"需新增"15 项、"已有可复用（仅回归/注释）"4 项（逐项见 14.2）。

### 14.1.1 主任务实施要素（角色 / 依赖 / 文件 / 要点 / 验收 / 验证 / 既有状态 / 追溯）

| 编号 | 角色 | 依赖 | 涉及文件 | 实施要点 | 验收标准 | 验证方式 | 既有代码状态 | 追溯 |
|------|------|------|---------|---------|---------|---------|-------------|------|
| **M-LA-01** | 文档 + Java + 测试 | 无（design 第九章已评审） | 本章、`design.md` 9.9/9.12、5 个实现文件（只读） | 逐条核对 D-01～D-18 真实形态；冻结收敛顺序；登记 5 项待确认决策与临时口径 | D 编号无遗漏，每条有子任务或"已满足/不处理"说明，每条含一个可断言检查点；5 项决策均有临时口径与切换点 | 只读核对 + 表格自查 + 文档评审 | 需新增（核对文档） | 9.1、9.9、9.12 |
| **M-LA-02** | Java | M-LA-01.1 | `VoiceMapper.java`、`VoiceTranscriptionService.java` | 失败回写补 `expires_at`；0 行回读细分 reason；以 SQL 为时间唯一裁决；固化 9 步校验顺序；`requestId`/日志加 `attempt` | 成功/空/失败三路径 `WHERE` 集合一致；过期记录不被任何路径改写；`GONE/OLD_ATTEMPT` 零落盘；同一记录两次尝试 `requestId` 不同 | `mvn -o -Dtest="VoiceMapperTest,VoiceTranscriptionServiceTest" test`；LA-17 真实库用例 | 部分实现（需修正） | 9.3.2、9.3.3、9.4.3、9.4.4、9.4.5、D-03/D-04/D-05/D-07/D-17/D-18 |
| **M-LA-03** | Java | M-LA-01.1、M-LA-02.1 | `VoiceMapper.java`、`VoiceTranscriptionService.java`、（条件）`V18` | 复用排除已消费/已过期；`latest`/`pending` 改 `updated_at, attempt` 排序；会话锁内收敛超窗陈旧 `PENDING`；60s 窗口单点定义 | 已消费记录不返回文本；重试复用记录被取为最新；同题至多 1 条 `PENDING`、`SUCCEEDED` 唯一；收敛不改 `attempt`/会话/消息 | LA-13、LA-14、LA-18 真实库 + `EXPLAIN`；`VoiceLateArrivalIntegrationTest` 回归 | 部分实现 + 1 项新增 | 9.4.3、9.4.6、9.4.7、D-01/D-02/D-06 |
| **M-LA-04** | Java | M-LA-03.1、M-LA-01.2 | `InterviewService.java`、新增 `VoiceReasons`（常量/枚举）、`api/types.ts`（契约） | 转写失效 reason 固定为 4 值；`markConsumed` 0 行同事务回滚；确认 tx2 与 `answer` 同一会话行锁、tx2 无正式写入 | 接口不再出现 `TRANSCRIPTION_PENDING/FAILED/SUCCEEDED`；LA-09b 只 1 条消息/1 任务/1 消费；LA-11 无"提交成功 + 旧题转写成功"并存 | `mvn -o -Dtest=InterviewAnswerVoiceTest test`；LA-09a 回归 + LA-09b/LA-11 | 部分实现（需修正） | 9.3.1、9.5.4、D-11 |
| **M-LA-05** | 前端 | M-LA-04.1 | `useVoiceAnswer.ts`、`VoiceAnswerPanel.vue`、`InterviewView.vue`、`api/types.ts` | 按 `details.reason` 分流提示；`SUPERSEDED` 静默；禁止放弃后自动重试；`VOICE` 带 ID / `TEXT` 不带；组件 `key` 重建 | BR-02/03/04 提示与原因一致且编辑区不被覆盖；BR-07 来源 `TEXT` 无 400；BR-08 题数与后端一致；`npm run type-check` 零错误 | 浏览器 M-LA-08 + `npm run type-check` + 代码走查 | 部分实现（需修正） | 9.6.1–9.6.5、D-04/D-09/D-12 |
| **M-LA-06** | 测试 + Java | M-LA-02、M-LA-03、M-LA-04 | `VoiceLateArrivalIntegrationTest.java`、新增 `VoiceLateArrivalConcurrencyIntegrationTest.java` | 库名 `_test` 断言；`started`/`release` 双屏障；LA-03/04 改真实 `answer`/`end`；新增 LA-09b/10/11/14 | AC-03/04/09b/10/11/13 全部由真实 MySQL + 真实事务证明；付费调用 0 次；四表断言齐全 | `mvn -o -Dintegration-tests=true -Djacoco.skip=true -Dsurefire.useManifestOnlyJar=false -Dtest=Voice*IntegrationTest test` | 部分实现 + 4 项新增 | 9.3.1、9.7.1、9.7.2、D-13 |
| **M-LA-07** | 测试 + Java | M-LA-02.1、M-LA-03.1、M-LA-03.2 | 新增 `VoiceAttemptMigrationIntegrationTest.java`、`VoiceRetrySemanticsIntegrationTest.java`（可选）、`VoiceLateArrivalIntegrationTest.java`（追加） | LA-12/13/15/16/17/18 全部落地；既有 9 项保留并标注"回拨时间模拟" | AC-06/12/13/14/15 有证据；用例数只增不减；迁移用例验证默认值与 CHECK | 同 M-LA-06 命令；迁移用独立 `_test` 库 + `SHOW CREATE TABLE` | 1 项与既有共用、6 项新增 | 9.7.2、9.7.4 |
| **M-LA-08** | 测试 + 前端 | M-LA-05、M-LA-06 主干通过 | `docs/功能资料/09-模拟面试/语音模拟面试-验收证据.md`（记录） | BR-01～BR-10 逐项执行；Chrome/Edge 各跑 BR-01～BR-08；日志抽样；失败保留证据 | AC-16～AC-19 有截图/日志/SQL 证据；AC-20 有调用次数记录；未覆盖范围如实标注 | 手工浏览器 + SQL 核对 + 日志 `grep` + Fake 调用计数 | 需执行 | 9.8 |
| **M-LA-09** | 文档 | 各阶段随做随记；收口依赖 M-LA-06/07/08 | 本章、`语音模拟面试-回滚与清理.md`、`语音模拟面试-验收证据.md`、`语音模拟面试_process.md` | 实现与文档一致性回写；D-16 上线/回滚检查单；AC-01～AC-20 证据归档；按 `AGENTS.md` 逐步记录 | 无未登记差异；回滚检查单可照做；AC 每条指向证据或明确"未执行及原因"；过程记录与变更逐条一致 | 文档 diff 评审 + 命令留痕 + `process.md` 核对 | 部分实现 | 9.4.6、9.11、9.12、`AGENTS.md` |

### 14.2 子任务清单（按执行阶段顺序）

#### 阶段 A：状态规则与差距确认（M-LA-01）

- [ ] **M-LA-01.1 差异清单逐项核对与"既有代码状态"冻结**（依赖：无；既有状态：需新增）
  - 文件：`design.md` 9.9（只读）、`VoiceMapper.java`、`VoiceTranscriptionService.java`、`InterviewService.java`、`useVoiceAnswer.ts`、`VoiceLateArrivalIntegrationTest.java`、本章 14.1
  - 要点：逐条核对 D-01～D-18 在**当前**工作区的真实形态（是"已修正""仍存在"还是"已不存在"），把结论写入 14.1 与 14.2 的"既有代码状态"列；与 9.9 描述不一致时只改本清单并记录原因，不改 `design.md`（设计文档由设计阶段维护）。
  - 验收：D-01～D-18 每条都能在本章找到对应子任务或明确的"不处理/已满足"说明；无编号遗漏；每条含至少一个可断言检查点（SQL、方法名或接口响应）。
  - 验证：只读核对 + 本章表格自查（`grep -c "D-0\|D-1"` 与 9.9 条目数对齐）。
  - 既有代码现状（本轮实测）：D-01、D-02、D-03、D-04、D-05、D-06、D-11、D-13、D-17 均**仍存在**；D-07 需复核（`:101` 已用记录 `interview_version`，可能只需注释）；D-08/D-10/D-14/D-15 已满足或不需代码改动。

- [ ] **M-LA-01.2 五项待确认决策登记与临时口径（不擅自定案）**（依赖：无；既有状态：需新增）
  - 文件：本章 14.6、`design.md` 9.12"待确认项"
  - 要点：按 14.6 表登记 5 项决策，每项写明"临时口径 / 受影响子任务 / 决策后可延后处理的切换点"；**禁止**在编码任务中把待决项当作已定案实现。
  - 验收：5 项均有临时口径与切换点；临时口径不产生新的对外契约（对外字段/错误码保持现状）；标注"需用户决策"与"可延后"。
  - 验证：文档评审。
  - 既有代码现状：无需代码；若用户已在其他渠道定案，必须在 14.6 记录结论与日期。

#### 阶段 B：回写保护与 `attempt` 条件更新（M-LA-02）

- [ ] **M-LA-02.1 `markFailedIfPending` 补"未过期"谓词（D-03）**（依赖：M-LA-01.1；既有状态：需修正）
  - 文件：`project/backend/src/main/java/com/aijob/backend/interview/voice/VoiceMapper.java:114-120`
  - 要点：`WHERE` 在现有 `id + attempt + status='PENDING' + consumed_at IS NULL` 基础上增加 `AND expires_at > UTC_TIMESTAMP(3)`；`SET` 与参数不变；不新增语句、不改表结构。
  - 验收：过期记录收到失败结果后**保持 `EXPIRED`**、`text IS NULL`、`error_key IS NULL`；成功/空识别/失败三条路径的 `WHERE` 条件集合一致（对照 9.4.5）。
  - 验证：`mvn -o -Dtest=VoiceMapperTest test`（若该类不存在，由 M-LA-07.5 真实库用例覆盖）；LA-17 未执行前不得标记完成。
  - 既有代码：条件更新骨架已存在，仅缺 `expires_at` 一个谓词。

- [ ] **M-LA-02.2 0 行回读判定与 `details.reason` 精确化（D-04 + 9.4.4）**（依赖：M-LA-02.1、M-LA-01.2；既有状态：需修正）
  - 文件：`VoiceTranscriptionService.java:126-202`（`completeSuccess`/`applyFailure`/`evaluate`/`settle`/`stale`）、`VoiceMapper.findById`
  - 要点：
    1. 条件更新返回 0 行时**回读记录**再判定原因：`EXPIRED` → `TRANSCRIPTION_EXPIRED`；`consumed_at != null` → `TRANSCRIPTION_CONSUMED`；`attempt` 更大 → `SUPERSEDED`（先作为内部常量，是否对外暴露待 14.6-1 决策）；其余 → `QUESTION_CHANGED`。
    2. `GONE` 与 `OLD_ATTEMPT` **不写任何记录**（现有 `settle` 已保证，补断言）；`>1` 行视为数据异常 → 记录告警 + 500，不静默。
    3. 对外默认保持 `STALE_INPUT` + `QUESTION_CHANGED`，避免在决策前改变前端契约。
  - 验收：四类原因与 9.4.4 一致；`GONE`/`OLD_ATTEMPT` 场景下转写表 `updated_at/status/text` 均未变化（SQL 前后对比）；`>1` 行有告警日志且不返回 200。
  - 验证：`mvn -o -Dtest="VoiceTranscriptionServiceTest,VoiceLateArrivalIntegrationTest" test`（真实库）；日志断言不含文本。
  - 既有代码：`Guard` 枚举与 `settle` 已区分五种结论，但 0 行统一映射 `QUESTION_CHANGED`，缺回读细分。

- [ ] **M-LA-02.3 统一过期时间源（D-17/D-18）**（依赖：M-LA-01.1；既有状态：需修正）
  - 文件：`VoiceTranscriptionService.java:208-217`（`expired()`）、`:182`（`evaluate` 第 9 步）
  - 要点：SQL 条件（`expires_at > UTC_TIMESTAMP(3)`）为**唯一裁决**；JVM 判断降级为"快速失败提示"，并在注释写明"与数据库时间可能偏差，最终以 0 行回读为准"；不新增数据库时间查询通道，不引入时钟同步组件。
  - 验收：构造"JVM 认为未过期、DB 认为已过期"的记录：结果落 `EXPIRED`（而非 `DISCARDED`/`QUESTION_CHANGED`）；反向构造不得把仍有效期记录判成过期。
  - 验证：真实库用例（并入 M-LA-07.5）+ 代码评审注释。
  - 既有代码：`expired()` 用 `LocalDateTime.now(ZoneOffset.UTC)`（JVM 时钟），与 SQL 判定可能不一致。

- [ ] **M-LA-02.4 二次校验顺序固化与等价性注释（D-07 + 9.3.2）**（依赖：M-LA-01.1；既有状态：需修正/补注释）
  - 文件：`VoiceTranscriptionService.java:169-184`（`evaluate`）、`:62-73`（tx1 校验）
  - 要点：确认并保持固定顺序=会话归属 → `WAITING_ANSWER` → 版本 → 当前题 → 记录存在 → `PENDING` → 未消费 → `attempt` → 未过期；第 3 步比较值确认来自记录 `interview_version`（`:101`），若成立仅补注释说明"与上传时 `expectedVersion` 等价"，若仍是方法参数则改为记录字段；不得调整顺序。
  - 验收：代码评审可见 9 步顺序与 9.3.2 表逐行一致；`InOrder` 或逐项单测能固定"先会话后记录、先状态后 attempt、过期最后"。
  - 验证：`mvn -o -Dtest=VoiceTranscriptionServiceTest test` + 评审清单。
  - 既有代码：9 项校验已实现，顺序基本一致；D-07 可能已满足，核实后仅补注释。

- [ ] **M-LA-02.5 `requestId` 与结构化日志加入 `attempt`（D-05）**（依赖：M-LA-02.1；既有状态：需修正；P2 可延后）
  - 文件：`VoiceTranscriptionService.java:359-361`（`auditRequestId`）、`:115-117`（成功日志）、`:104`（调用点）
  - 要点：派生键改为 `voice:{transcriptionId}:{attempt}`；日志字段增加 `attempt`；继续**不记录**转写全文、音频字节、密钥、JWT 原文、`Idempotency-Key`。
  - 验收：同一记录第 2 次尝试的 `requestId` 与第 1 次不同；日志含 `attempt` 且 `grep` 不到长文本/Base64。
  - 验证：单测断言派生值 + 日志抽样（M-LA-08.4 BR-09）。
  - 既有代码：`requestId` 只由记录 ID 派生，多次尝试不可区分。

#### 阶段 C：同题活跃尝试收敛与读路径（M-LA-03，全部 P0）

- [ ] **M-LA-03.1 同音频复用排除"已消费/已过期"（D-01）**（依赖：M-LA-01.1、M-LA-01.2；既有状态：需修正）
  - 文件：`VoiceMapper.java:47-53`（`findBySha` 或新增 `findReusableBySha`）、`VoiceTranscriptionService.java:74-81`
  - 要点：复用条件改为 `status='SUCCEEDED' AND consumed_at IS NULL AND expires_at > UTC_TIMESTAMP(3)`；已消费按临时口径返回 409 `STALE_INPUT` + `TRANSCRIPTION_CONSUMED`（14.6-2 定案后可改 200 语义）；已过期先走 `expireIfStale` 再按显式重试重置，**不得**直接把文本返回前端。
  - 验收：已消费记录的相同音频重传**不返回文本**、不新增记录、不重复调用供应商；`consumed_message_id` 不变；V3 查询对已消费记录不再作为可提交候选返回。
  - 验证：LA-13（M-LA-07.2）+ LA-18（M-LA-07.6）；`mvn -o -Dtest=VoiceLateArrivalIntegrationTest test` 回归 LA-01/LA-02。
  - 既有代码：`findBySha` 无 `consumed_at` 过滤，`:77` 命中 `SUCCEEDED` 即 `_cached` 返回。

- [ ] **M-LA-03.2 读路径排序改为 `updated_at DESC, attempt DESC`（D-02）**（依赖：M-LA-01.1；既有状态：需修正）
  - 文件：`VoiceMapper.java:71-78`（`findLatestByQuestion`）、`:80-87`（`findPendingByQuestion`）
  - 要点：两条查询的 `ORDER BY` 改为 `updated_at DESC, attempt DESC`；保留原 `status` 过滤与 `LIMIT 1`；不改索引 DDL（索引见 M-LA-03.4）。
  - 验收：重试复用的记录（`created_at` 不变、`attempt` 更大）被取为"最新"；V3 刷新恢复返回新尝试而非旧尝试；`findPendingByQuestion` 不再优先返回旧 `PENDING`。
  - 验证：LA-18（M-LA-07.6，构造 `created_at` 旧 + 另一条新记录）。
  - 既有代码：两条查询均为 `ORDER BY created_at DESC`。

- [ ] **M-LA-03.3 会话锁内收敛同题陈旧 `PENDING`（D-06）**（依赖：M-LA-03.1、M-LA-02.1；既有状态：需新增）
  - 文件：`VoiceMapper.java`（新增一条条件更新，语义名如 `discardStalePendingOfQuestion`）、`VoiceTranscriptionService.java:82-95`（tx1 内、`insertPending`/`resetPending` 之前）
  - 要点：
    1. 条件：同 `interview_id + question_message_id`、`status='PENDING'`、`id != 本次记录`、`updated_at < UTC_TIMESTAMP(3) - INTERVAL 60 SECOND`；`SET status='DISCARDED', text=NULL, updated_at=UTC_TIMESTAMP(3)`。
    2. 受影响行数 0..n 均可接受，**忽略结果、不重试**；不改 `attempt`；不触碰会话与消息。
    3. 新鲜窗口（≤60s）的 `PENDING` 仍由现有 `TASK_IN_PROGRESS` 拦截，两者共用同一阈值（M-LA-03.5）。
  - 验收：LA-14 中同题至多 1 条 `PENDING`；两条不同 `audio_sha256` 最终只有一条 `SUCCEEDED`；被收敛的旧请求晚到时 `GONE` 且记录不被二次改写；`askedQuestions`/`version` 不变。
  - 验证：LA-14（M-LA-06.6）真实库 + 单测（SQL 断言 `COUNT(status='PENDING')<=1`）。
  - 既有代码：`VoiceTranscriptionService:82-85` 只拦截新鲜 `PENDING`，超窗后允许并存两条。

- [ ] **M-LA-03.4 `V18` 复合索引（条件新增，需 `EXPLAIN` 证据）**（依赖：M-LA-03.2、M-LA-07.6；既有状态：未实现，默认不新增）
  - 文件：新增（仅在有证据时）`project/backend/src/main/resources/db/migration/V18__add_voice_question_activity_index.sql`
  - 要点：仅当 LA-18 的 `EXPLAIN` 显示 `using filesort` 或全表/范围扫描不可接受时，新增 `(interview_id, question_message_id, status, updated_at)` 复合索引；不修改 `V15`/`V17`；无证据时不新增并在本章记录"结论：不新增 + 证据文件路径"。
  - 验收：有 `EXPLAIN` 前后对比留档；新增时迁移可在专用库前向执行且不破坏既有查询；无证据时明确记录不新增理由。
  - 验证：专用测试库 `EXPLAIN` + Flyway 执行（仅新增分支）。
  - 既有代码：现有 `idx_voice_question (interview_id, question_message_id, created_at)` 支持等值 + `created_at` 排序，对 `updated_at` 排序可能失效。

- [ ] **M-LA-03.5 60 秒新鲜窗口单点收口（决策 14.6-3）**（依赖：M-LA-01.2；既有状态：需修正）
  - 文件：`VoiceTranscriptionService.java:30`（`PENDING_STALE_SECONDS = 60`）
  - 要点：保持常量 60，且被"新鲜度互斥"与"陈旧收敛"**共用同一常量**；不读配置（可配置化待决策）；补注释说明"供应商 12s 超时 << 60s 窗口"（R-04）。
  - 验收：全文件只有一个 60 秒阈值来源；两处行为一致；关闭配置化时不改变默认行为。
  - 验证：代码检索（`grep -n "60"`）+ 评审；LA-02/LA-14 回归。
  - 既有代码：常量已存在，但仅用于新鲜度判断，收敛语句需复用同一值。

#### 阶段 D：确认提交消费幂等（M-LA-04）

- [ ] **M-LA-04.1 转写失效 `reason` 收敛为固定枚举（D-11）**（依赖：M-LA-01.2、M-LA-03.1；既有状态：需修正）
  - 文件：`InterviewService.java:151-172`（重点 `:163-166` 动态拼接）、新增常量类（如 `interview/voice/VoiceReasons.java` 或枚举）、`project/frontend/src/api/types.ts`（reason 联合类型）
  - 要点：固定集合 `TRANSCRIPTION_NOT_FOUND` / `TRANSCRIPTION_CONSUMED` / `TRANSCRIPTION_DISCARDED` / `TRANSCRIPTION_EXPIRED`；禁止 `"TRANSCRIPTION_" + status` 拼接（会产出 `TRANSCRIPTION_PENDING`/`TRANSCRIPTION_FAILED`/`TRANSCRIPTION_SUCCEEDED` 等未定义值）；后端与前端引用同一份常量清单（前端以字面量联合类型 + 注释来源）。
  - 验收：任意状态输入只产生上述 4 个值之一（或 400 语义明确的缺参错误）；前端 switch/映射覆盖全部取值且无未知值回落；未知值不会出现在接口响应中。
  - 验证：`mvn -o -Dtest=InterviewAnswerVoiceTest test` + 接口用例（T-07 追加）；`npm run type-check`。
  - 既有代码：`:166` 动态拼接，PENDING/FAILED 会生成前端未定义 reason。

- [ ] **M-LA-04.2 `markConsumed` 0 行必须同事务回滚 + 重放分支保留**（依赖：M-LA-04.1；既有状态：完整实现，需补证据）
  - 文件：`InterviewService.java:218-222`（`markConsumed` 行数校验）、`:174-189`（既有回答重放）
  - 要点：保持"0 行 → 抛 `STALE_INPUT` → 整个提交事务回滚"，不得吞掉异常或补写；已消费但同消息同内容的重放允许；不新增幂等表、不新增计数列。
  - 验收：LA-09a 同幂等键重放仍只 1 条 USER/1 个任务；LA-09b 并发不同键只有一个成功，失败方消息与任务一并回滚（`ai_task` 计数不增加）。
  - 验证：LA-09a 既有用例回归 + LA-09b（M-LA-06.3）。
  - 既有代码：行数校验与回滚已实现，缺并发证据。

- [ ] **M-LA-04.3 提交与回写串行化确认（9.3.1 最关键不变量）**（依赖：M-LA-02.4；既有状态：完整实现，需评审固化）
  - 文件：`InterviewService.answer`（`lockSession` 调用点）、`VoiceTranscriptionService.java:171`（tx2 `lockSession`）
  - 要点：确认两处使用同一 `InterviewMapper.lockSession`（同一会话行锁）；确认 tx2 内不存在 `insertMessage`/`TaskService.create`/`updateTaskStatus`；把该约束写成代码注释与评审项。
  - 验收：评审可见"同锁"；代码检索 tx2 内无正式写入；LA-11 证明只出现两种合法结果，不存在"提交成功 + 旧题转写 `SUCCEEDED`"。
  - 验证：LA-11（M-LA-06.4）+ 代码检索（`grep -n "insertMessage\|create(" VoiceTranscriptionService.java` 为空）。
  - 既有代码：两处均已 `lockSession`，转写链路无正式写入（`VoiceMapper` 类注释约束）。

#### 阶段 E：前端令牌、提示与来源一致性（M-LA-05）

- [ ] **M-LA-05.1 按 `details.reason` 分流提示（9.6.2 矩阵）**（依赖：M-LA-04.1；既有状态：需修正）
  - 文件：`project/frontend/src/composables/voice/useVoiceAnswer.ts:93-142`、`src/views/interview/components/VoiceAnswerPanel.vue:114-120,214-219`、`src/api/types.ts`（reason 联合类型）
  - 要点：
    1. `QUESTION_CHANGED` → "题目已变化，本次识别未生效" + 不覆盖编辑区 + 父组件刷新会话；
    2. `TRANSCRIPTION_EXPIRED` → "识别结果已过期，请重新录音"，保留录音、允许重试识别/重录/改文字；
    3. `TRANSCRIPTION_CONSUMED` / `TRANSCRIPTION_DISCARDED` → 不覆盖编辑区，不静默重试；
    4. `TASK_IN_PROGRESS` → 静默转 `TRANSCRIBING` 并轮询 V3；
    5. `SUPERSEDED`（若 14.6-1 采纳）→ 静默等待新结果；
    6. `DEPENDENCY_UNAVAILABLE`（503/504）→ "语音识别失败，录音已保留，可重试识别或改用文字"；
    7. 415/413/400 → 对应格式/大小/时长提示，需要重录时不冒充可重试。
  - 验收：BR-02/BR-03/BR-04 提示与实际原因一致；旧题结果不修改编辑区与 `transcriptionId`；`npm run type-check` 零错误。
  - 验证：浏览器回归（M-LA-08）+ 代码走查；前端无单测框架时不得声称自动化覆盖。
  - 既有代码：`notice` 与 `stillCurrent()` 已实现，但提示统一为"题目已变化"，不按 reason 分流。

- [ ] **M-LA-05.2 版本快照复核（D-12，P2 可延后）**（依赖：M-LA-05.1；既有状态：未实现）
  - 文件：`useVoiceAnswer.ts`（`transcribe` 发起与返回处）
  - 要点：发起时记录 `interview.version`，返回时比较；仅作体验增强，**必须**仍以后端 409 为准，不得用前端版本判断替代错误处理。
  - 验收：同题版本变化时给出提示且不覆盖编辑区；去掉该判断后 409 处理仍正确（证明非正确性依赖）。
  - 验证：浏览器回归 BR-02 变体 + 评审。
  - 既有代码：`stillCurrent()` 只比较令牌与题目 ID。

- [ ] **M-LA-05.3 禁止 `discard()` 后自动重试（D-09 前后端契约）**（依赖：M-LA-05.1；既有状态：需确认）
  - 文件：`useVoiceAnswer.ts`（`reset`/`discard`/`retryLastAudio`）、`VoiceAnswerPanel.vue`（重试按钮）
  - 要点：`SUCCEEDED → PENDING` 只允许由用户点击"重试识别"触发；切题、重录、放弃后不得自动发起新转写；服务端 `resetPending` 允许重置的现状保持不变（服务端不区分是否自动，故前端必须守约）。
  - 验收：代码检索确认只有用户事件路径调用 `transcribe`；放弃后无网络请求；BR-05 刷新不重复上传。
  - 验证：代码评审 + 浏览器 BR-05。
  - 既有代码：`retryLastAudio` 已存在且由按钮触发；需确认无自动重试分支。

- [ ] **M-LA-05.4 `TEXT`/`VOICE` 来源与题数一致性（9.6.4/9.6.5）**（依赖：M-LA-05.1；既有状态：完整实现，需回归）
  - 文件：`InterviewView.vue:352-379`（提交参数展开）、`useVoiceAnswer.ts`（`canSubmit` 相关）、消息展示
  - 要点：`VOICE` 必须携带非空 `voiceTranscriptionId`；切文字后来源为 `TEXT` 且**不携带** id；失败重试沿用首次来源与内容；前端不维护本地题数，只展示后端 `askedQuestionCount`。
  - 验收：BR-01 消息 `answer_source='VOICE'` 且转写 `consumed_at` 非空；BR-07 `TEXT` 不带 id 且无 400；BR-08 提问数/回答数与后端一致。
  - 验证：浏览器 M-LA-08 + SQL 断言。
  - 既有代码：`InterviewView.vue:371/379` 已按来源条件展开，2026-11-21 曾修复文字模式误标 VOICE。

- [ ] **M-LA-05.5 组件 `key` 重建与编辑区防线复核（9.6.3）**（依赖：M-LA-05.1；既有状态：完整实现，需回归）
  - 文件：`InterviewView.vue:691-696`（`:key`）、`VoiceAnswerPanel.vue`（`reset` 调用）
  - 要点：`key = interviewId:questionId` 保证切题强制重建；卸载后的晚到回调直接丢弃；编辑区不被晚到结果覆盖。
  - 验收：BR-02 中 A 标签页编辑区文本不回退、不追加；切题后录音/转写状态清零。
  - 验证：浏览器 M-LA-08.2。
  - 既有代码：`key` 与令牌双判已实现。

#### 阶段 F：真实 MySQL 并发矩阵（M-LA-06，含修复 D-13）

- [ ] **M-LA-06.1 测试基础设施显式化（专用库 + 双屏障 + 独立事务）**（依赖：M-LA-01.1；既有状态：部分实现）
  - 文件：`project/backend/src/test/java/com/aijob/backend/interview/voice/VoiceLateArrivalIntegrationTest.java:49-85`（`setUp/tearDown`）、新增 `VoiceLateArrivalConcurrencyIntegrationTest.java`
  - 要点：
    1. 启动前断言库名显式含 `_test`（如 `career_business_test`），非测试库**直接失败**；不触碰正式业务库。
    2. 双屏障：`Call.started` 保证"供应商调用已进入"后才做状态变更；`Call.release` 保证"状态变更事务已提交"后才返回结果；只用一个屏障或用 `Thread.sleep` 代替均视为无效用例。
    3. 参与者全部通过 service 自带的 `TransactionTemplate` 开户，测试线程不手动开事务。
    4. 每个用例随机 UUID 隔离，断言限定本会话 ID；不依赖清库。
  - 验收：库名断言可被故意改错验证（改错即失败）；每个用例同时断言 `interview_message`（USER/ASSISTANT）、`ai_task`、`interview_session.version/status`、转写表四类数据；付费供应商调用次数=0。
  - 验证：`mvn -o -Dintegration-tests=true -Djacoco.skip=true -Dsurefire.useManifestOnlyJar=false -Dtest=Voice*IntegrationTest test`（`VOICE_TEST_DB_URL` 指向专用库）。
  - 既有代码：受控供应商、队列、`started/release` 已存在；缺"库名断言"与四表全面断言。

- [ ] **M-LA-06.2 改造 LA-03/LA-04 为真实 `answer`/`end` 路径（D-13，P0）**（依赖：M-LA-06.1、M-LA-04.3；既有状态：需修正）
  - 文件：`VoiceLateArrivalIntegrationTest.java:126-151`（LA-03）、`:153-168`（LA-04）
  - 要点：LA-03 改为真实 `interviewService.answer(...)` + 真实任务回调推进版本与当前题（不得 `INSERT interview_message` + 直接 `UPDATE version`）；LA-04 改为真实 `interviewService.end(...)`（不得直接 `UPDATE status='ENDING'`）；顺序固定为 `started → 状态变更提交 → release`。
  - 验收：AC-03：409 `QUESTION_CHANGED` + 记录 `DISCARDED` + `USER=1` + `asked=2` + `version=2`；AC-04：409 + `DISCARDED` + 会话保持结束态 + `USER=0` + 无新 `ai_task`。
  - 验证：同 M-LA-06.1 命令，单独跑这两个用例 3 次。
  - 既有代码：现为 SQL 直插/直改，不能证明与真实提交事务串行化。

- [ ] **M-LA-06.3 新增 LA-09b：不同幂等键并发提交同一转写**（依赖：M-LA-06.1、M-LA-04.2；既有状态：需新增）
  - 文件：新增 `VoiceLateArrivalConcurrencyIntegrationTest.java`
  - 要点：两线程各自调用 `interviewService.answer`，同 `questionMessageId`、不同 `Idempotency-Key`，用 `CyclicBarrier` 同时发起；断言 `USER=1`、`ai_task=1`、`consumed_message_id` 唯一且指向唯一消息、另一方 409、失败方无残留任务。
  - 验收：连续运行 3 次稳定通过；失败方整个事务回滚（消息与任务均不增加）。
  - 验证：`-Dtest=VoiceLateArrivalConcurrencyIntegrationTest test`。
  - 既有代码：只有同键串行重放（LA-09a）。

- [ ] **M-LA-06.4 新增 LA-11：晚到回写与提交回答真实争会话行锁（核心）**（依赖：M-LA-06.1、M-LA-04.3；既有状态：需新增）
  - 文件：`VoiceLateArrivalConcurrencyIntegrationTest.java`
  - 要点：供应商阻塞 → 线程 A 真实执行 `answer`（持会话行锁提交）→ 放行供应商；断言**只允许两种结果**：要么回写在提交前成功并被随后消费，要么回写被拒并 `DISCARDED`；禁止"回答已提交 + 旧题转写 `SUCCEEDED`"并存。
  - 验收：AC-11；断言用 4 张表联合核对；重复运行 5 次（上限 10 次，避免长等待）稳定。
  - 验证：同命令；保留 surefire 报告与日志。
  - 既有代码：无。

- [ ] **M-LA-06.5 新增 LA-10：空识别结果晚到（题变）**（依赖：M-LA-06.2；既有状态：需新增）
  - 文件：`VoiceLateArrivalIntegrationTest.java`（追加）
  - 要点：上传中 → 真实提交进入下一题 → 放行**空文本**结果；断言 409 `QUESTION_CHANGED`、记录 `DISCARDED`（**不是** `EMPTY`）、`text IS NULL`、`USER` 与 `asked` 符合 AC-03。
  - 验收：AC-10；`EMPTY` 不得覆盖 `DISCARDED`。
  - 验证：同命令。
  - 既有代码：无（现有仅成功/失败晚到）。

- [ ] **M-LA-06.6 新增 LA-14：同题双 `PENDING` 收敛**（依赖：M-LA-03.3；既有状态：需新增）
  - 文件：`VoiceLateArrivalIntegrationTest.java`（追加）
  - 要点：上传①（`sha A`）→ 回拨 `updated_at` 超窗 → 上传②（`sha B`）→ 再放行①；断言①被置 `DISCARDED`、①晚到 `GONE` 不改写、期间 `COUNT(PENDING)<=1`、最终 `SUCCEEDED` 唯一。
  - 验收：AC-13；不得出现两条 `SUCCEEDED`；`asked`/`version` 不变。
  - 验证：同命令；另断言 LA-14 后 V3 返回的是②的文本。
  - 既有代码：无。

#### 阶段 G：幂等 / 迁移 / 越权 / 排序矩阵（M-LA-07）

- [ ] **M-LA-07.1 LA-12：放弃后同一音频显式重试**（依赖：M-LA-03.1；既有状态：需新增）
  - 文件：`VoiceLateArrivalIntegrationTest.java`（追加）
  - 要点：上传① → `discard()` → 同 `sha` 再上传；断言记录 `attempt=2`、转回 `PENDING`、①晚到不改写、②成功后 `SUCCEEDED` 且记录数=1。
  - 验收：9.2.2 自环成立；`consumed_at` 仍为空；不新增记录。
  - 验证：同命令。
  - 既有代码：无（`resetPending` 支持递增，但无该用例）。

- [ ] **M-LA-07.2 LA-13：已消费记录的相同音频重传（D-01 验证）**（依赖：M-LA-03.1；既有状态：需新增）
  - 文件：`VoiceLateArrivalIntegrationTest.java`（追加）
  - 要点：VOICE 提交消费 → 同 `sha` 再上传；断言不复用已消费记录的文本；按临时口径 409 `STALE_INPUT` + `TRANSCRIPTION_CONSUMED`；记录数与 `consumed_message_id` 不变。
  - 验收：AC-12；用户不会看到"可提交"但必然失败的候选文本。
  - 验证：同命令。
  - 既有代码：无。

- [ ] **M-LA-07.3 LA-15：越权用户请求**（依赖：M-LA-06.1；既有状态：需新增）
  - 文件：`VoiceLateArrivalIntegrationTest.java`（追加）
  - 要点：用另一用户（存在但非会话所有者）调用 `transcribe`；断言 404、不创建记录、不读取他人记录、不调用供应商。
  - 验收：AC-14；不区分"不存在"与"非本人"（统一 404）。
  - 验证：同命令。
  - 既有代码：`:63-64` 已 `lockSession(interviewId,userId)` 返回 null → 404，缺用例。

- [ ] **M-LA-07.4 LA-16：`V17` 迁移与历史兼容**（依赖：无，可最早并行；既有状态：需新增）
  - 文件：新增 `project/backend/src/test/java/com/aijob/backend/interview/voice/VoiceAttemptMigrationIntegrationTest.java`
  - 要点：建库 → `V15`（含 `V16` 既有链）→ 插历史转写行 → 执行 `V17`；断言历史行 `attempt=1`、列类型 `INT UNSIGNED NOT NULL DEFAULT 1`、`CHECK (attempt>=1)` 拒绝 0、Flyway 重复执行被拒绝；不改 `V15`。
  - 验收：AC-15；迁移脚本唯一且只加列 + CHECK；ROLLBACK（DROP CHECK → DROP COLUMN）演练可选但需记录结论。
  - 验证：专用迁移实验库（如 `voice_migration_test`）+ 断言 `SHOW CREATE TABLE`。
  - 既有代码：`V17` 已存在（**不改**），无用例。

- [ ] **M-LA-07.5 LA-17：三条回写路径条件一致性（D-03 验证）**（依赖：M-LA-02.1、M-LA-02.3；既有状态：需新增）
  - 文件：`VoiceLateArrivalIntegrationTest.java`（追加）或 `VoiceRetrySemanticsIntegrationTest.java`
  - 要点：构造**已过期**记录，分别让供应商返回成功、空文本、异常；断言三者都**不得**把 `EXPIRED` 改写，`text` 保持 `NULL`，`error_key` 保持 `NULL`；再用 SQL `EXPLAIN`/语句文本核对三条 `WHERE` 集合一致。
  - 验收：AC-06 完整；无 `SUCCEEDED`/`EMPTY`/`FAILED` 复活；失败原因不被"过期"降级。
  - 验证：同命令。
  - 既有代码：成功/空识别带 `expires_at`，失败缺（M-LA-02.1 修复后由本用例闭环）。

- [ ] **M-LA-07.6 LA-18：V3 刷新查询排序正确（D-02 验证）**（依赖：M-LA-03.2；既有状态：需新增）
  - 文件：`VoiceLateArrivalIntegrationTest.java`（追加）
  - 要点：构造"重试复用记录（`created_at` 旧、`attempt` 大）"+"另一条新记录"，断言 V3/`latest` 返回 `updated_at`/`attempt` 最新的一条；同时采集 `EXPLAIN` 供 M-LA-03.4 决策。
  - 验收：AC-13 的读路径部分；不返回旧尝试/旧 `sha`；`EXPLAIN` 结果归档到 M-LA-03.4。
  - 验证：同命令 + `EXPLAIN` 输出留档。
  - 既有代码：无。

- [ ] **M-LA-07.7 既有 9 项保留回归与"回拨时间模拟"标注**（依赖：M-LA-06、M-LA-07.1～07.6；既有状态：部分实现）
  - 文件：`VoiceLateArrivalIntegrationTest.java:208-236`（LA-07 注释）
  - 要点：LA-01/LA-02/LA-05/LA-06/LA-07/LA-08/LA-09a 全部保留，不删除不跳过；LA-07 注明"用回拨 `updated_at` 模拟超窗，非真实等待 60s"；新增用例不替换旧用例。
  - 验收：该测试类全部用例通过且数量只增不减；报告含用例数/断言数；失败用例保留并记录两次结果（修复前后）。
  - 验证：`mvn -o -Dintegration-tests=true -Djacoco.skip=true -Dsurefire.useManifestOnlyJar=false -Dtest=Voice*IntegrationTest test`。
  - 既有代码：9 项已通过（2026-11-22 记录），本轮不重跑，编码阶段必须重跑。

#### 阶段 H：浏览器回归（M-LA-08）

- [ ] **M-LA-08.1 主干四项：BR-01 / BR-05 / BR-06 / BR-07**（依赖：M-LA-05.5；既有状态：需执行）
  - 环境：后端 + Agent（Fake ASR）+ 前端；数据库 `career_voice_browser_test`（专用浏览器测试库）。
  - 要点与断言：BR-01 录音→转写→编辑→确认提交（1 条 USER `answer_source='VOICE'`、转写 `consumed_at` 非空）；BR-05 刷新时 `PENDING` 显示"仍在识别中"且不重复上传；BR-06 刷新 `SUCCEEDED` 回填候选文本并标"需确认"；BR-07 切文字保留文本、来源 `TEXT`、不携带转写 ID、无 400。
  - 验收：四项截图/步骤记录 + SQL 核对；无新增转写记录（BR-05/06）。
  - 验证：Chrome / Edge 各执行一遍（见 M-LA-08.4 矩阵）。

- [ ] **M-LA-08.2 双标签页两项：BR-02 / BR-03**（依赖：M-LA-05.1、M-LA-06.2；既有状态：需执行）
  - 要点与断言：BR-02 A 标签页转写中，B 提交本题：A 提示"题目已变化，本次识别未生效"、A 编辑区**不被覆盖**、A 刷新见新题；`USER=1`、`asked` 只 +1、无多余 `ai_task`。BR-03 B 结束面试：A 提示未生效、刷新进入结束/报告、不出现新题、`USER` 不增加。
  - 验收：提示文案与原因一致；数据库 4 表断言；两标签页状态最终一致。
  - 验证：手工双窗口 + SQL；不使用 SQL 直改状态冒充。

- [ ] **M-LA-08.3 失败重试与题数：BR-04 / BR-08**（依赖：M-LA-05.1、M-LA-03.1；既有状态：需执行）
  - 要点与断言：BR-04 供应商 503/超时：录音保留、"重试识别"可点、成功后文本回填、Fake 调用计数符合预期、记录 `attempt=2` 且最终只有一条 `SUCCEEDED`；BR-08 全程结束后：提问数=预算内实际创建数、USER 数=有效回答数、报告 1 份。
  - 验收：无重复计费（Fake 计数 ≤2）；重试不增加题数；报告唯一。
  - 验证：停/恢复 Fake Agent 进程模拟故障 + SQL 核对。

- [ ] **M-LA-08.4 日志与边界：BR-09 / BR-10 + 跨浏览器矩阵**（依赖：M-LA-02.5、M-LA-08.1～08.3；既有状态：需执行）
  - 要点与断言：BR-09 抽样后端与 Agent 日志，`grep` 不含音频/Base64/转写全文/密钥/JWT；BR-10 权限拒绝与短录音：提示可执行、不产生转写记录。矩阵：Chrome、Edge 各执行 BR-01～BR-08；**不覆盖**物理麦克风、噪声、回声、Safari、真实长录音，且不得把虚拟麦克风成功写成"真人物理麦克风验收通过"。
  - 验收：AC-16～AC-20 中浏览器相关项有证据；真实 ASR 次数记录（≤10 次，账号由 14.6-4 确认）。
  - 验证：日志抽样 + 截图/录像 + 次数记录表。

#### 阶段 I：文档与过程记录（M-LA-09）

- [ ] **M-LA-09.1 `tasks.md` / `design.md` 一致性回写**（依赖：各阶段完成；既有状态：需执行）
  - 文件：本章、`design.md` 9.9/9.12（仅在实现被迫偏离时，由设计阶段确认后修改）
  - 要点：任何偏离设计（如 14.6 决策改变对外语义、`V18` 新增与否）必须同时回写设计文档与本章，不得只在代码中就地方便处理；行号漂移只更新本清单。
  - 验收：实现与文档无未登记差异；每条"需修正/需新增"子任务的实际落点与文件一致。
  - 验证：文档 diff 评审。

- [ ] **M-LA-09.2 回滚与清理文档补 D-16 上线/回滚检查单**（依赖：M-LA-03.1；既有状态：需新增）
  - 文件：`docs/功能资料/09-模拟面试/语音模拟面试-回滚与清理.md`
  - 要点：回滚前置**必须**：`app.voice.asr-enabled=false`（或关闭语音入口）→ 等待在途转写排空 → 再回滚应用；禁止"只回滚应用、继续开语音"（旧代码条件更新会覆盖新尝试）；数据库不删 `attempt` 列（默认 1 兼容旧 `SELECT *`）；若必须删列，先完成前置条件。
  - 验收：步骤可照做、含影响范围与失败处置；明确"正式库本轮不迁移"。
  - 验证：文档评审 + 专用库演练（可选，不改正式库）。

- [ ] **M-LA-09.3 验收证据归档（LA-01～LA-18 + BR-01～BR-10）**（依赖：M-LA-06、M-LA-07、M-LA-08；既有状态：需执行）
  - 文件：`docs/功能资料/09-模拟面试/语音模拟面试-验收证据.md`
  - 要点：逐条记录用例编号、命令、用例数/断言数、结果、证据文件路径；未执行/失败项保持"未通过"，不得只写"全部通过"而无输出；保留失败记录不覆盖成成功。
  - 验收：AC-01～AC-20 每条都能指向证据或明确"未执行及原因"；`AC-20` 附调用次数记录。
  - 验证：文档核对 + 命令留痕。

- [ ] **M-LA-09.4 开发过程记录追加到 `process.md`**（依赖：每个开发步骤；既有状态：需执行）
  - 文件：`docs/功能资料/09-模拟面试/语音模拟面试_process.md`
  - 要点：按 `AGENTS.md` 规定格式追加：任务目标 / 执行行为 / 文件变更表 / 执行命令与验证 / 影响范围 / 遗留问题；每个有实际影响的步骤立即追加，不集中到最后；不记录密钥、录音、转写全文。
  - 验收：记录与实际变更一致（文件表逐条可核对）；时间不伪造；失败与未执行项如实登记。
  - 验证：任务结束前逐条核对日志与 `git status`（只读，不提交）。

### 14.3 与既有任务的映射与依赖（不改变既有编号与状态）

| 既有任务 | 关系 | 本章承接 | 说明 |
|---------|------|---------|------|
| **M1-09** `VoiceMapper`、状态机与惰性过期 | 被增量扩展 | M-LA-02.1、M-LA-03.1～03.3 | `M1-09` 的"同题仅一个 `PENDING`"由 M-LA-03.3 收敛；`M1-09` 原验收不重写 |
| **M1-10** 转写上传主流程 | 被增量加固 | M-LA-02.2～02.5、M-LA-03.1、M-LA-03.5 | `M1-10` 六类场景保留；新增返回后二次校验与条件回写已由上阶段提前落地，本章只做收敛 |
| **M1-11** V1–V5 接口 | 被增量影响 | M-LA-03.1（V3 可提交性）、M-LA-05.1（错误提示） | V3 对已消费/过期记录的可提交判定以 M-LA-03.1 为准；接口签名不变 |
| **M1-13** `/answers` 来源与一次性消费 | 被增量加固 | M-LA-04.1～04.3 | `M1-13` 的 `markConsumed` 一次性消费已实现，本章补 reason 枚举与并发证据 |
| **M1-20** `useVoiceAnswer` 状态机 | 被增量扩展 | M-LA-05.1～05.5 | 令牌与 `stillCurrent` 已存在；新增按 reason 分流与自动重试禁令 |
| **M1-21** 语音作答五组件 | 被增量扩展 | M-LA-05.1、M-LA-05.4 | 提示文案矩阵、来源传递与题数展示 |
| **T-01** Java 单测（voice 包） | 被增量扩展 | M-LA-02.x 单测、M-LA-07.5 | 新增条件回写/原因判定用例并入 `VoiceTranscriptionServiceTest` |
| **T-02** 隔离库集成（迁移与并发） | 被增量承接 | M-LA-06.1、M-LA-07.4 | 专用库、迁移、并发基线沿用；新增 `Voice*IntegrationTest` 套件 |
| **T-09 第 7 项"旧题晚到"** | 被增量替代（证据升级） | M-LA-06.2、M-LA-08.2 | T-09 仍保留手工异常矩阵；旧题晚到的**并发级证据**改由 LA-03/LR-11/BR-02 提供 |
| **T-12 A8 旧题晚到** | 被增量细化 | M-LA-06、M-LA-08 | 验收标准不变，证据来源升级为真实事务矩阵 |
| **Q-01～Q-06 总题数预算** | 正交、不冲突 | M-LA-05.4、M-LA-08.3（BR-08） | 转写/重试不增加题数沿用 Q 阶段结论；不修改 `question_count_rule`、不做 `V16` 变更 |
| **13.8 报告失败重试与浏览器录音** | 前置证据 | M-LA-08.1 | 浏览器既有 44 项断言环境可复用；本轮只新增晚到相关场景 |

**不可违背的依赖**：`M-LA-01.1` → `M-LA-02.x`/`M-LA-03.x`；`M-LA-03.1` → `M-LA-04.1` → `M-LA-05.1`；`M-LA-02.x`+`M-LA-03.x` → `M-LA-06.x`；`M-LA-06.x`/`M-LA-07.x` → `M-LA-08.x` → `M-LA-09.3`。

### 14.4 关键路径与建议执行顺序

**关键路径（决定"可声称完成"的最早时间）**

```
M-LA-01.1 差异核对 → M-LA-01.2 决策登记
  → M-LA-02.1 失败谓词 → M-LA-02.2 0 行判定 → M-LA-02.3 时间源
  → M-LA-03.1 复用排除 → M-LA-03.2 排序 → M-LA-03.3 陈旧 PENDING 收敛
  → M-LA-04.1 reason 枚举 → M-LA-04.3 串行化确认
  → M-LA-06.1 基础设施 → M-LA-06.2 真实 answer/end → M-LA-06.4 真实争锁
  → M-LA-06.3/06.5/06.6 → M-LA-07.1～07.6
  → M-LA-05.1 前端分流（与后端并行收口）
  → M-LA-08.1～08.4 浏览器
  → M-LA-09.3 证据归档
```

**按阶段的可并行安排**

| 顺序 | 子任务 | 进入条件 | 并行结论 |
|------|--------|---------|---------|
| 1 | M-LA-01.1、M-LA-01.2、M-LA-07.4 | 无 | 三者可同日：差异核对、决策登记、迁移用例（迁移用例不依赖代码修复） |
| 2 | M-LA-02.1、M-LA-02.3、M-LA-03.2 | M-LA-01.1 | 三条互不依赖，可并行改 3 处 |
| 3 | M-LA-02.2、M-LA-02.4、M-LA-02.5 | M-LA-02.1、M-LA-01.2 | M-LA-02.2 需先有 M-LA-02.1 的完整谓词；02.4/02.5 可与 02.2 并行 |
| 4 | M-LA-03.1 → M-LA-03.3、M-LA-03.5 | M-LA-03.2 | **强串行**：03.1 先定复用语义，03.3 才能在 tx1 内收敛 |
| 5 | M-LA-04.1 → M-LA-04.2/04.3 | M-LA-03.1 | 04.1 是前端与测试的 reason 契约冻结点 |
| 6 | M-LA-05.1 → M-LA-05.2/05.3/05.4/05.5 | M-LA-04.1 | 与第 4/5 步（Java）并行，仅共享 reason 清单 |
| 7 | M-LA-06.1 → M-LA-06.2/06.3/06.4/06.5/06.6 | M-LA-02.x、M-LA-03.x、M-LA-04.2 | 06.2～06.6 可在基础设施就绪后并行编写，执行串行 |
| 8 | M-LA-07.1/07.2/07.3/07.5/07.6、M-LA-07.7 | 对应修复完成 | 可与第 7 步并行编写；07.7 作为整套回归收口 |
| 9 | M-LA-08.1/08.2/08.3/08.4 | M-LA-05.x、M-LA-06.2/06.4 通过 | 浏览器串行（同一环境互相干扰） |
| 10 | M-LA-03.4、M-LA-09.1/09.2/09.3/09.4 | 证据齐备 | 03.4 依 `EXPLAIN`；09.x 全程随做随记 |

**禁止顺序（违反即返工）**

1. M-LA-03.1 之前不得执行 M-LA-07.2（LA-13 必然失败）。
2. M-LA-03.3 之前不得执行 M-LA-06.6（LA-14 必然并存两条 `PENDING`）。
3. M-LA-04.1 之前不得开工 M-LA-05.1（前端无可映射的 reason 集合）。
4. M-LA-06.1 的库名断言之前不得运行任何 `Voice*IntegrationTest`（避免误连正式库）。
5. M-LA-09.3 不得在用例未实际执行时勾选；不得用 Mock 证据替代真实库/浏览器证据。

### 14.5 未覆盖风险与需人工执行项（单列，不计入子任务完成）

| 编号 | 项 | 性质 | 为什么不能由本轮自动化覆盖 | 处置建议 |
|------|---|---|---|---|
| **U-01** | 浏览器 BR-01～BR-10 | 人工执行 | 需运行后端 + Agent + 前端 + 专用浏览器库；Node/Playwright 版本、麦克风授权依赖本机环境 | 由 M-LA-08 执行并截图留档；未执行不得声称完成 |
| **U-02** | 真人物理麦克风、噪声、回声 | 人工执行 | 需真实设备与声学环境；虚拟麦克风成功不能外推 | 沿用既有结论边界；如需结论单独组织真人验收 |
| **U-03** | 真实 Qwen ASR 调用次数与账号 | 需用户提供/确认 | 涉及计费与账号归属；自动化测试必须为 0 次付费调用 | 只需在 BR 中做必要次数（建议 ≤10 次），记录次数与账号（14.6-4） |
| **U-04** | 全量回归与覆盖率 | 需人工执行 | 全仓库 `mvn test`、`pytest`、`npm run build` 耗时且依赖环境；JaCoCo 在本机曾因编码失败 | 编码收口时执行全量命令并记录；覆盖率未采集需如实写"未采集" |
| **U-05** | 多实例部署与数据库时钟偏差 | 架构确认 | 单机测试无法证明多实例时钟一致性（R-07） | 统一用 `UTC_TIMESTAMP(3)`，不用 JVM 时间做窗口判断；上线前确认部署时钟同步 |
| **U-06** | 供应商无幂等键（R-01） | 不可消除 | 网络"已受理但响应丢失"无法判定 | 明确不承诺跨网络 exactly-once；文案与文档同步（9.5.2） |
| **U-07** | 应用回滚（D-16） | 人工执行 | 需按序关闭开关、排空在途、再回滚 | M-LA-09.2 检查单；上线前演练 |
| **U-08** | `spec.md` 未收录 R-LA-1..5（R-08） | 跨阶段待办 | 需求文档由需求阶段维护，本特性不得擅自改 `spec.md` | 登记为跨 agent 待办（见 14.7），不阻塞本章编码 |
| **U-09** | 前端无单测框架（R-09） | 本轮不引入 | 引入 Vitest 属新增依赖与范围外决策（14.6-5） | 前端证据只认 `type-check` + 浏览器回归，不承诺自动化覆盖 |
| **U-10** | `V18` 索引必要性（R-11） | 依证据 | 无 `EXPLAIN` 证据时属过早优化 | M-LA-03.4 依 LA-18 的 `EXPLAIN` 决定 |
| **U-11** | 前端版本快照（D-12） | 可延后 | 正确性已由后端 409 保证 | M-LA-05.2 可延后，不影响 AC |

### 14.6 待用户决策项（标注"需用户决策 / 可延后"，不擅自定案）

| 编号 | 决策项（`design.md` 9.12） | 临时口径（编码按此执行，不产生新契约） | 影响子任务 | 决策后切换点 | 建议 |
|------|---|---|---|---|---|
| **LA-D1** | `SUPERSEDED`、`SESSION_CLOSED` 是否作为对外 `details.reason` | 仅内部常量区分；对外仍 `STALE_INPUT` + `QUESTION_CHANGED` | M-LA-02.2、M-LA-05.1 | 在 `.stale(...)` 映射处切换；前端映射表加两项即可 | **需用户决策**；倾向：先内部区分，稳定后再对外 |
| **LA-D2** | 已消费记录被同音频重传：409 还是"结果已使用"的 200 语义 | 409 `STALE_INPUT` + `TRANSCRIPTION_CONSUMED`（与现状一致） | M-LA-03.1、M-LA-07.2、M-LA-05.1 | `VoiceTranscriptionService:74-81` 复用分支返回点 | **需用户决策**；倾向 409（语义明确、不诱导提交） |
| **LA-D3** | 60 秒新鲜窗口是否改为可配置 | 保持常量 60，单点定义（M-LA-03.5） | M-LA-03.5、M-LA-06.6 | 常量改为读取配置项（默认 60） | **可延后**；当前 12s 超时下 60s 足够 |
| **LA-D4** | 浏览器真实 ASR 次数上限与验收账号 | 建议 ≤10 次；账号需用户提供 | M-LA-08.3、M-LA-08.4 | 执行前确认账号与次数；次数记入证据 | **需用户决策/提供**；阻塞 BR 真实链路 |
| **LA-D5** | 是否引入前端 Vitest | 不引入；前端证据用 `type-check` + 浏览器 | M-LA-05.x、M-LA-08 | 引入后补 `useVoiceAnswer.spec.ts` | **本轮不建议**（可延后） |

### 14.7 实施边界（写入任务约束，越界即视为缺陷）

1. **专用测试库**：真实库用例只用 `career_business_test`（迁移用例用单独命名的 `_test` 库）；启动前断言库名含 `_test`；**保留原业务数据**；正式库 `career_business` 本轮不迁移、不改动。
2. **日志红线**：不输出密钥、内部 JWT、`Idempotency-Key` 原文、音频字节/Base64、录音内容、转写全文。
3. **付费调用**：普通自动化测试调用付费供应商次数必须为 0；真实 ASR 仅用于浏览器必要次数，且需 LA-D4 账号与次数确认。
4. **迁移约束**：不修改已执行的 `V15`/`V16`/`V17`；只允许新增 `V18`（且需 `EXPLAIN` 证据）；不删列、不重建表。
5. **范围约束**：不改会话状态机、追问规则、总题数预算、评分与报告口径；不引入异步转写任务、消息队列、分布式锁、供应商幂等键；不重构 TTS/能力探测/隐私同意模块。
6. **改动方式**：保留已有修改，不做无关重构；每个子任务只改其声明的文件；行号漂移以方法名定位。
7. **记录要求**：按 `AGENTS.md` 追加 `语音模拟面试_process.md`；每完成一个有实际影响的步骤立即记录；**本次不提交、不推送 Git**。
8. **跨阶段待办（不在本章执行）**：`spec.md` 尚未收录 R-LA-1..5（识别尝试、条件回写、失效不复活、返回后二次校验、幂等分层），建议由需求阶段（`spec-requirement-agent`）正式增补；本章以用户请求与 `design.md` 第九章为依据。

### 14.8 本轮边界声明（2026-11-22 编码任务规划阶段）

1. 本轮仅修改 `tasks.md` 并按需追加 `process.md`；**未修改任何 Java/Python/前端业务代码**、**未创建或执行迁移**、**未调用供应商**、**未运行单元/集成/浏览器测试**、**未提交 Git**。
2. 本章所有子任务复选框保持 `[ ]`；"既有代码状态"依据是对当前工作区的只读核对，不代表验收通过。
3. `design.md` 第九章的 18 项测试矩阵与 10 项浏览器回归仍为**设计预期**；只有实际执行并留下命令输出与数据断言后，才可把对应子任务标记完成。
4. 既有任务的编号、状态、完成标记（含 13.7/13.8 的历史结论）一律保留；本章不重算、不覆盖。
5. 任务总数：新增 **9 个主任务 + 41 个可领取子任务**；累积主任务编号 **68**。
### 14.9 执行结果（2026-11-22，任务执行阶段）

> 本节只记录实际执行并通过的结果；未执行项保持"未通过/未验证"，不得用代码已实现替代验收。

#### 14.9.1 已完成并验证

| 子任务 | 实际落点 | 验证证据 |
|---|---|---|
| M-LA-01.1 / 01.2 | 差异与临时口径按 14.1/14.6 执行 | 设计文档与本节 |
| M-LA-02.1 | `VoiceMapper.markFailedIfPending` 增加 `expires_at > UTC_TIMESTAMP(3)` | LA-17 三路径用例 |
| M-LA-02.2 | `inspectAfterZeroRows` 回读区分 EXPIRED/CONSUMED/OLD_ATTEMPT/STALE；`GONE/OLD_ATTEMPT` 零落盘 | `VoiceLateArrivalIntegrationTest` |
| M-LA-02.3 | 删除 `expired()` JVM 判定，过期由 SQL 条件与回读裁决 | 代码评审 + LA-17 |
| M-LA-02.4 | 九项校验顺序保持不变并补等价性注释 | 代码评审 |
| M-LA-02.5 | `auditRequestId(id, attempt)`；日志增加 `attempt`，不含转写文本 | 代码评审 + 日志抽样 |
| M-LA-03.1 | 已消费结果不再复用，返回 `409 STALE_INPUT + TRANSCRIPTION_CONSUMED`；`latest` 排除已消费 | 已消费重传用例、V3 排序用例 |
| M-LA-03.2 | 两条读查询改为 `updated_at DESC, attempt DESC` | 「新尝试/旧尝试」读路径用例 |
| M-LA-03.3 | 新增 `discardStalePendingOfQuestion`，接管前收敛超窗同题 PENDING | 同题双 PENDING 收敛用例 |
| M-LA-03.5 | 60 秒常量被新鲜度与收敛共用 | 代码检索 |
| M-LA-04.1 | 新增 `VoiceReasons` 固定 4 值，不再拼接状态 | `InterviewService` 代码评审 |
| M-LA-04.2 | `markConsumed` 0 行同事务回滚保留 | 重复提交用例 + 并发不同键用例 |
| M-LA-04.3 | tx2 与会话提交共用 `lockSession`，tx2 无正式写入 | 代码检索 + 真实 answer 争锁用例 |
| M-LA-05.1 | 前端按 `details.reason` 分流提示；TASK_IN_PROGRESS 静默轮询；SUPERSEDED 静默 | `npm run type-check`（浏览器未验证） |
| M-LA-06.2 | LA-03 改真实 `interviewService.answer`；LA-04 改真实 `interviewService.end` | 17 项矩阵通过 |
| M-LA-06.5 / 06.6 | 新增空识别晚到、同题双 PENDING 收敛用例 | 17 项矩阵通过 |
| M-LA-07.1 / 07.2 / 07.3 | 放弃后重试、已消费重传、越权用户用例 | 17 项矩阵通过 |
| M-LA-07.4 | 新增 `VoiceAttemptMigrationIntegrationTest`（库名、列定义、默认值、CHECK、历史行） | 3 项迁移用例通过 |
| M-LA-07.5 / 07.6 | 过期三路径、V3 读路径排序用例 | 17 项矩阵通过 |
| M-LA-07.7 | 既有 9 项全部保留并通过 | 17 项矩阵通过 |
| M-LA-09.3 / 09.4 | 验收证据与过程记录已追加 | 文档 diff |

**实际命令与结果**

| 命令 | 结果 |
|---|---|
| `mvn -o -q -f project/backend/pom.xml -DskipTests test-compile` | 通过 |
| `mvn -o -f project/backend/pom.xml -Dintegration-tests=true -Dsurefire.useManifestOnlyJar=false -Djacoco.skip=true -Dtest="Voice*IntegrationTest" test` | **20 项通过**（晚到矩阵 17 + 迁移 3），0 失败/错误/跳过 |
| `mvn -o ... -Dtest="VoiceAudioValidationTest,VoiceConcurrencyGuardTest,SpeechAgentClientTest,QuestionSpeechServiceTest,InterviewServiceTest" test` | **18 项通过** |
| `npm run type-check`（`project/frontend`） | 通过 |

#### 14.9.2 未完成 / 未验证（不得视为通过）

| 子任务 | 状态 | 原因 |
|---|---|---|
| M-LA-03.4 `V18` 索引 | 未新增、未决策 | 未采集 LA-18 的 `EXPLAIN`；无证据不新增索引 |
| M-LA-06.1 四表逐用例断言 | 部分 | 库名断言已在迁移用例实现；矩阵未对每个用例同时断言四类表 |
| M-LA-06.3 / 06.4 多次重复 | 部分 | 并发用例已实现并单次通过；未按要求连续 3～5 次 |
| M-LA-05.2 / 05.3 / 05.5 | 代码走查完成，浏览器未验证 | BR-02/03/05 未执行 |
| M-LA-08.1～08.4 浏览器回归 | **未执行** | Node 18.19.0；Playwright 需 Node≥20；8080/8100/5173 未监听；验收账号需重新生成 |
| M-LA-09.2 回滚检查单 | 未补 | 本轮未改 `语音模拟面试-回滚与清理.md` |
| AC-03 的 `asked=2` | 未达成 | 用例使用真实 `answer`，但未注入 Agent 回调生成下一题；以 `GENERATING + version=2 + DISCARDED` 证明旧结果不能用于下一题 |

**仍存在的剩余风险**：供应商无幂等键，跨网络故障不能承诺 exactly-once；60 秒窗口为启发式；物理麦克风/噪声/回声未验收；正式库上线前需执行 V17 并遵循回滚顺序。
