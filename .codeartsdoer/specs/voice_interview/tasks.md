# 语音模拟面试 - 编码任务清单

> 文档定位：本文件是「语音模拟面试」首版（逐题整段录音）的编码执行清单，供后续编码阶段按编号领取任务。
> 需求基线：`.codeartsdoer/specs/voice_interview/spec.md`
> 设计基线：`.codeartsdoer/specs/voice_interview/design.md`（接口编号 V1–V6 / A1–A2、表名 `interview_voice_transcription`、迁移 `V15`、错误码、限额、状态机、里程碑 M0–M3 均以该文档为准）
> 代码基线：`AI-JOB` 工作区（迁移最大版本 `V14`；`interview` 包仅 4 个类；`agent/app` 无 `speech/`；前端无任何 `MediaRecorder` 使用）
> 本轮范围：**只产出任务清单**，不写业务代码、不改数据库脚本、不执行迁移、不提交代码。
> 任务总数：**53**（M0 7 + M1 22 + M2 8 + M3 4 + 测试 12）

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

### 0.2 任务完成定义（DoD，全部任务通用）

1. 代码/脚本已提交前先在本地跑通"验证"列中的命令，或完成列出的手工检查并留下记录。
2. 新增或修改的接口、字段、错误码与 `design.md` 一致；若被迫偏离，必须同步在 `design.md` 与第 8 章"冲突记录"中登记。
3. 不引入 `design.md` 2.4（D1–D14）之外的额外中间件（消息队列、WebSocket、对象存储）。
4. 日志中不得出现音频字节、转写全文、语音密钥、`Idempotency-Key` 原文（spec 4.3-5）。
5. 任务涉及的既有文字面试行为零变化（未传新字段时行为与当前完全一致）。

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
| 修改评分、报告、追问规则 | `spec.md` 1.4-1/2、6.4 | 永不 |
| 新增消息队列 / 对象存储 / WebSocket 基础设施 | `design.md` 2.1.1"不引入的组件" | 二期评估后另立需求 |

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

---

## 12. 前 5 个待执行任务（按开工顺序）

1. **M0-01** 决策项收口与临时值冻结（产品/技术/合规）——唯一无前置，决定其余任务取值。
2. **M0-02** 接口契约与错误码表冻结（三方签署）——与 M0-06 并行。
3. **M0-06** Python `speech` 模块骨架与 Fake 契约（Python）——不依赖任何决策，可立即开工。
4. **M0-03** 缺口处置：隐私同意接口与超时/下限语义（C1/C2/C3）。
5. **M0-04** 落地方式修正登记（C4–C10），避免开发按错误理解动手。

> 说明：M0-05（`V15` 评审）需等 M0-01 的 O3 与 O2 结论；M1-17 与 M1-18（前端工具与录音封装）不依赖接口，可与 M0 并行启动。