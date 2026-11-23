# 本地服务启动过程记录

### [2026-11-15 15:35] 本地启动前端、后端、Agent 三个服务

**任务目标：**
在本地（不使用 Docker）启动 AI-JOB 的前端（Vite）、Java 后端（Spring Boot）和 Python Agent（FastAPI）三个服务，并保证 MySQL、Redis 依赖可用。

**执行行为：**

1. 检查环境：Conda 环境 `ai-job`（Python 3.11.18）、Maven 3.9.9 + JDK 21.0.9、Node v22.19.0 均可用；系统环境变量已存在 `DEEPSEEK_API_KEY`。
2. 发现 `.env` 仅包含 MySQL 与 `APP_COOKIE_SECURE` 配置，缺少 Redis 变量，Docker Compose 与后端 Redis 连接均无法成立，遂向 `.env` 追加 `REDIS_BIND_ADDRESS=127.0.0.1`、`REDIS_PORT=6379`、`REDIS_PASSWORD=<本地随机生成>`。
3. 用户明确要求不使用 Docker，改为本地运行 MySQL / Redis：
   - MySQL：已有 Windows 服务 `MySQL` 运行中，版本 8.0.42，监听 `127.0.0.1:3306`，`career_business` 库存在。
   - Redis：本机 `D:\redis\redis-server.exe` 存在但未运行（6379 未被占用），使用 `D:\redis\redis.windows.conf` 并追加 `--requirepass`（与 `.env` 中密码一致）后台启动，`redis-cli ping` 返回 `PONG`。
4. 首次启动后端时 Flyway 报错：`Unknown column 'installed_at' in 'field list'`，定位为 `V13__mark_legacy_generated_questions.sql` 引用了 `flyway_schema_history` 中不存在的列（Flyway 实际列名为 `installed_on`）。
5. 修正 V13 脚本列名，并在本地库删除 `success = 0` 的 V13 失败记录，使 Flyway 重新执行该迁移。
6. 重新启动后端，Flyway 成功应用到 v13，`BackendApplication` 在 8080 端口启动。
7. 后台启动前端与 Agent，日志统一重定向到 `%TEMP%\aijob-logs\`。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `.env` | 追加 `REDIS_BIND_ADDRESS`、`REDIS_PORT`、`REDIS_PASSWORD` 三项本地开发配置 | Compose 与后端均需 Redis 密码变量，原文件缺失导致配置解析失败 |
| 修改 | `project/backend/src/main/resources/db/migration/V13__mark_legacy_generated_questions.sql` | 子查询中 `MAX(installed_at)` 改为 `MAX(installed_on)` | 该列名与 Flyway 创建的历史表结构不符，迁移必然失败 |
| 新增 | `docs/功能资料/服务启动_process.md` | 本启动过程记录 | 遵守 `AGENTS.md` 过程记录规范 |

**执行命令与验证：**

- 命令：`docker compose up -d --wait mysql redis mailpit`
- 结果：失败 / 已放弃
- 说明：Docker Desktop 未运行且用户要求改本地运行，容器已执行 `docker compose stop` 停止，不参与本次启动。

- 命令：`D:\redis\redis-server.exe D:\redis\redis.windows.conf --requirepass <pwd>`
- 结果：通过
- 说明：`redis-cli -a <pwd> --no-auth-warning ping` 返回 `PONG`，监听 `127.0.0.1:6379`。

- 命令：`mysql -h 127.0.0.1 -P 3306 -u root -e "SELECT VERSION(); SHOW DATABASES LIKE 'career_business';"`
- 结果：通过
- 说明：返回 8.0.42，业务库存在。

- 命令：`mvn spring-boot:run`（工作目录 `project/backend`）
- 结果：第二次启动通过
- 说明：首次失败于 V13 迁移列名错误；修正后日志显示 `Successfully applied 1 migration ... now at version v13`、`Tomcat started on port 8080`、`Started BackendApplication in 3.215 seconds`；访问 `/api/v1/jobs` 返回 401（未登录，符合预期）。

- 命令：`npm run dev`（工作目录 `project/frontend`）
- 结果：通过
- 说明：Vite v6.4.5 就绪，监听 `[::1]:5173`，`http://localhost:5173/` 返回 200。

- 命令：`D:\Anaconda\envs\ai-job\python.exe project\agent\run.py`（工作目录项目根目录，`PYTHONNOUSERSITE=1`）
- 结果：通过
- 说明：日志显示 `agent started provider=deepseek model=deepseek-v4-pro storage=sqlite callback=True auth=enabled`；`/healthz` 返回 200，`/docs` 返回 200。

**影响范围：**

- `.env` 为本地私有配置（已在 Git 忽略列表），不影响其他开发者；但本地 Redis 必须带同一密码启动，否则后端连接 6379 会认证失败。
- V13 迁移脚本列名修正属于缺陷修复：该脚本在 Flyway 自建历史表的标准环境下原本无法成功执行，修正后对尚未应用 V13 的环境生效；若某环境已手工应用过 V13，需注意 checksum 差异。
- 服务端口：后端 8080、Agent 8100、前端 5173、MySQL 3306、Redis 6379。

**遗留问题：**

- 前端 Vite 默认只绑定 `::1`，`http://127.0.0.1:5173` 无法访问，需使用 `http://localhost:5173`（如需 IPv4 可加 `--host 127.0.0.1`）。
- 本次未执行 `mvn test` 与前端 `npm run build`，仅做启动级验证。
- 本地 Redis 为手动进程启动，机器重启后不会自启，如需常驻可注册为 Windows 服务。
- 未进行登录后的端到端 AI 任务联调验证。

### [2026-11-16 10:00] 启动整改分支以复测验收

**任务目标：**
启动当前整改分支的本地服务并准备复测验收用例。

**执行行为：**

1. 检查当前分支、现有启动记录、端口和本地依赖；未读取或记录凭据内容。
2. 请求启动 MySQL Windows 服务、本地 Redis、Agent、前端及 Java 后端。MySQL 服务控制不可用，Redis 进程启动报系统 DLL 重定位错误；其余进程请求已发出，等待检查实际运行结果。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | docs/功能资料/服务启动_process.md | 追加本次启动记录 | 按规范记录启动操作 |

**执行命令与验证：**

- 命令：Start-Service -Name MySQL；使用 Start-Process 分别请求启动 Redis、Agent、Vite 和 mvn spring-boot:run。
- 结果：MySQL 启动失败；Redis 启动报系统 DLL 重定位错误；其余服务状态待核查。
- 说明：此前服务查询提示无法打开服务控制管理器；3306 和 6379 端口连接失败。

**影响范围：**
仅涉及本地服务启动尝试，尚未修改业务代码或明确变更数据库。

**遗留问题：**
需确认进程、健康检查与数据库状态，再进行验收。

---

### [2026-11-16 10:01] 在本机环境请求启动服务

**任务目标：**
排除受限执行环境对服务控制和本地端口的干扰，启动项目供复测。

**执行行为：**

1. 在本机环境确认 MySQL 服务运行且 3306 监听；先前服务控制与连接失败属于受限环境的观测结果。
2. 按项目既有本地配置请求后台启动 Redis、Python Agent 和 Vite；后端及各服务最终状态继续核查。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | docs/功能资料/服务启动_process.md | 追加本机启动记录 | 保留启动过程与环境差异 |

**执行命令与验证：**

- 命令：Get-Service MySQL、Get-NetTCPConnection、Start-Process。
- 结果：MySQL 运行；Redis、Agent、前端已请求启动，健康检查待执行。

**影响范围：**
本地后台服务和日志；未修改业务代码。

**遗留问题：**
确认后端、端口与健康状态；实际验收尚未完成。

---

### [2026-11-16 10:02] 核查本机服务及后端迁移失败

**任务目标：**
确认项目启动状态并定位后端启动失败原因。

**执行行为：**

1. 前端与 Agent 健康检查均返回 HTTP 200，MySQL 与 Redis 已监听。
2. 后端启动时 Flyway 校验发现现有数据库 V13 与当前分支脚本校验和不一致，进程退出；未执行 Flyway repair 或修改迁移历史。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | docs/功能资料/服务启动_process.md | 追加启动核验与失败记录 | 保留真实结果 |

**执行命令与验证：**

- 命令：访问前端 /、Agent /healthz、后端 /api/v1/jobs，检查 Maven 启动日志。
- 结果：前两项 HTTP 200；后端未监听，日志显示 V13 migration checksum mismatch。

**影响范围：**
前端、Agent、Redis 在本机运行；后端尚不可用。Flyway 校验失败未自动修复数据库。

**遗留问题：**
核对数据库迁移版本及分支差异，选择保留现有数据的启动方式；业务验收尚未执行。

---

### [2026-11-16 10:03] 核对迁移状态并以临时配置重试后端

**任务目标：**
保留现有数据库和迁移历史，恢复本地后端供测试。

**执行行为：**

1. 用项目既有数据库账号只读查询迁移历史，确认 V1–V13 均标记成功；对比 stash 中先前本地 V13 与当前分支版本，当前脚本对 V12 历史增加 success=1 条件。
2. 不修改数据库历史、迁移脚本或项目配置；仅在本次后端进程设置 SPRING_FLYWAY_ENABLED=false，重新请求启动。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | docs/功能资料/服务启动_process.md | 追加迁移排查和临时启动过程 | 记录兼容性限制 |

**执行命令与验证：**

- 命令：只读查询 career_business.flyway_schema_history；设置进程环境变量后执行 mvn spring-boot:run。
- 结果：V1–V13 均为成功；后端请求启动，健康状态待核查。

**影响范围：**
本次后端不会检查或执行数据库迁移；此方式不证明默认启动及迁移验证通过。

**遗留问题：**
仍需正式解决 V13 校验和差异并复测默认启动；核查后端可用性。

---

### [2026-11-16 10:04] 临时配置启动后端成功

**任务目标：**
确认本地各服务可访问，为验收复测提供运行环境。

**执行行为：**

1. 检查禁用本进程 Flyway 后的 Java 后端日志及受保护接口。
2. 后端启动并建立 MySQL 连接；未登录访问岗位接口返回 401。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | docs/功能资料/服务启动_process.md | 追加后端启动结果 | 完整记录验证 |

**执行命令与验证：**

- 命令：Invoke-WebRequest http://127.0.0.1:8080/api/v1/jobs。
- 结果：HTTP 401，符合未登录访问受保护接口的预期；日志显示端口 8080 启动和数据库连接成功。

**影响范围：**
本地前端、后端、Agent、Redis、MySQL 可运行；当前后端仅本进程禁用 Flyway。

**遗留问题：**
默认启动 V13 校验失败仍未修复；启动级检查不代表业务验收通过。

---

### [2026-11-17 14:16] 启动本机三端服务

**任务目标：** 启动当前 feature/bug 工作区的前端、Java 后端和 Python Agent。

**执行行为：**

1. 检查既有启动记录及本机监听端口，MySQL 3306 已监听。
2. 使用现有本地配置通过 Start-Process 后台启动 Redis、Python Agent、Vite 和 Maven Spring Boot，日志写入系统临时目录 aijob-logs；未修改凭据。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加本次启动记录 | 记录实际操作 |

**执行命令与验证：**

- 命令：`Start-Process` 分别启动 redis-server、`python project/agent/run.py`、Vite 和 `mvn spring-boot:run`。
- 结果：已发出启动请求，健康检查待完成。

**影响范围：** 本地后台进程；后端默认 Flyway 启动时可能执行尚未应用的迁移。

**遗留问题：** 待核实服务状态及启动日志。

---

### [2026-11-17 14:16] 验证三端启动结果

**任务目标：** 确认本地服务和数据库迁移状态。

**执行行为：**

1. 检查监听端口和 HTTP 响应；后端首次检查时尚在启动，随后再次检查已可访问。
2. 查看后端日志：MySQL 连接成功，Flyway 校验 14 个迁移，数据库已处于 V14，无需执行迁移。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加启动验证结果 | 如实记录服务状态 |

**执行命令与验证：**

- 命令：`Invoke-WebRequest http://localhost:5173/`；结果：HTTP 200。
- 命令：`Invoke-WebRequest http://127.0.0.1:8100/healthz`；结果：HTTP 200。
- 命令：`Invoke-WebRequest http://127.0.0.1:8080/api/v1/jobs`；结果：HTTP 401，未登录访问受保护接口符合预期。
- 命令：查看后端启动日志及监听端口；结果：后端 8080 启动，MySQL 3306、Redis 6379 监听，Flyway 校验通过且未执行新迁移。

**影响范围：** 本机前端、后端、Agent 和 Redis 后台运行，使用现有 MySQL 数据库。

**遗留问题：** 未执行登录业务、Redis 认证或真实模型端到端验证；端口监听和启动级检查不代表完整业务验证。

---

### [2026-11-18 09:51:55] 请求启动本机三端及 Redis

**任务目标：** 在当前工作区启动前端、Java 后端和 Python Agent，复用本机现有数据库。

**执行行为：**

1. 在本机确认 MySQL 3306 已监听，检查三端和 Redis 的端口以避免重复启动。
2. 通过隐藏的后台进程启动 Redis、Agent、Vite 和 Maven 后端。Redis 从项目现有配置读取认证设置，运行日志写入系统临时目录。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | docs/功能资料/服务启动_process.md | 追加启动过程 | 记录本次启动 |

**执行命令与验证：**

- 命令：Get-NetTCPConnection -State Listen；结果：本机 MySQL 3306 已监听。
- 命令：Start-Process 启动 Redis、python project\agent\run.py、Vite 和 mvn spring-boot:run；结果：已发出启动请求，等待健康检查。

**影响范围：** 本机开发服务及其日志目录。

**遗留问题：** 待验证 Redis、三端 HTTP 响应和 Flyway 状态。

---

### [2026-11-18 09:53:09] 验证三端并定位 Redis 启动失败

**任务目标：** 检查服务响应和依赖状态。

**执行行为：**

1. 验证前端首页、Vite API 代理、Java 后端和 Agent 健康端点。后端日志显示数据库连接成功，Flyway 校验通过且数据库已是 V14。
2. 检查 Redis 端口及启动日志，确认 Windows Redis 使用标准输入加载配置时退出。Agent 启动后更新了 SQLite 运行时共享内存文件。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加三端与 Redis 的核验结果 | 记录验证 |
| 修改 | `project/agent/data/agent_runs.db-shm` | Agent 进程运行时更新 SQLite 共享内存文件 | SQLite 运行行为 |

**执行命令与验证：**

- 命令：`curl.exe` 检查前端首页与 Agent `/healthz`；结果：均为 HTTP 200。
- 命令：`curl.exe` 检查后端及 Vite 代理转发的 `/api/v1/jobs`；结果：均为预期的 HTTP 401。
- 命令：检查服务监听及启动日志；结果：Redis 6379 未监听，日志出现 `HandleServiceCommands: other exception caught`。后端通过 14 个 Flyway 迁移校验，本次无需执行迁移。

**影响范围：** 三端可访问。依赖 Redis 的业务仍待确认。

**遗留问题：** 使用本机 Redis 支持的配置文件方式重试。

---

### [2026-11-18 09:54:04] 重试 Redis 并验证认证

**任务目标：** 恢复三端依赖的本机 Redis。

**执行行为：**

1. 复用本机 Redis 配置，在系统临时目录生成一次性启动配置，保持绑定在 127.0.0.1，按项目现有认证配置启动进程。启动后清理一次性配置。
2. 从本机验证 Redis 认证和 PING。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加 Redis 重试和验证结果 | 记录恢复过程 |

**执行命令与验证：**

- 命令：`Start-Process` 启动 `redis-server.exe`；结果：后台进程启动。
- 命令：通过 `redis-cli.exe` 发送认证请求和 `PING`；结果：分别返回 `OK` 和 `PONG`。

**影响范围：** 本机 Redis 已恢复。三端继续在后台运行。

**遗留问题：** 本次验证覆盖服务启动、端口和基础响应，未执行登录或真实模型调用。

---

### [2026-11-19 14:26:26] 启动合入语音面试后的本地服务

**任务目标：** 重启当前 `feature/bug` 工作区的前端、Java 后端和 Python Agent。

**执行行为：**

1. 核对常用端口，三端和 Redis 均未监听，MySQL 3306 正在运行。对业务库只读查询 Flyway 历史，确认已应用项目实战 V15。
2. 使用现有 Redis 认证配置在本机回环地址启动 Redis，认证 PING 成功后清理一次性启动配置。
3. 使用 `Start-Process` 启动 Python Agent 与 Vite。用 Maven 离线从当前工作区打包 Java 后端，保留既有配置和数据库。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加启动前检查、依赖恢复和打包记录 | 记录本次重启 |
| 新增 | `project/backend/target/local-restarts/20261119-142514/` | 本地服务重启日志与临时启动配置目录 | 保留运行证据，目录由 Git 忽略 |
| 修改 | `project/backend/target/backend-0.0.1-SNAPSHOT.jar` | 重新打包 Java 后端 | 使用当前代码与迁移文件 |

**执行命令与验证：**

- 命令：`netstat -ano -p tcp`，MySQL `SELECT version, script, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5`。结果：业务库处于 V15，三端和 Redis 的目标端口空闲。
- 命令：`redis-server.exe <临时配置>` 与认证 `redis-cli.exe PING`。结果：Redis 6379 返回 PONG。
- 命令：`Start-Process` 执行 `python project/agent/run.py` 与 `node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173 --strictPort`。结果：均已创建进程，HTTP 核验待完成。
- 命令：`mvn -o -q -DskipTests package`。结果：打包成功。

**影响范围：** 本机开发环境及 Redis 依赖；Java 使用当前工作区打包，启动时按现有配置正常执行 Flyway。

**遗留问题：** 校验打包内容，启动后端并核对三端 HTTP 与数据库迁移结果。

---

### [2026-11-19 14:27:34] 核对前端和 Agent，记录后端审批拦截

**任务目标：** 验证已启动的服务，启动当前 Java 构建产物。

**执行行为：**

1. 检查 JAR 中的 17 份迁移与工作区文件逐字节相同，并核对包含语音面试 Controller。
2. 核验前端首页和 Agent 健康接口均可访问。
3. 请求以默认 Flyway 配置后台启动 Java。自动审批因缺少对本地业务库 V16/V17 迁移的明确授权而拦截命令。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加已完成检查与拦截原因 | 记录重启进展 |

**执行命令与验证：**

- 命令：使用 Python `zipfile` 核对 JAR 内迁移及语音面试类。结果：通过。
- 命令：`curl.exe` 请求前端首页及 Agent `/healthz`。结果：均返回 HTTP 200。
- 命令：`Start-Process` 启动 `java -jar target/backend-0.0.1-SNAPSHOT.jar`。结果：自动审批拒绝，命令未执行。

**影响范围：** 前端、Agent 和 Redis 已启动，后端未启动，本轮未执行 Flyway 迁移。

**遗留问题：** 等待本地业务库 V16/V17 迁移授权，之后再启动并验证后端。

---

### [2026-11-19 14:30:07] 定位登录 HTTP 500 并准备 V15 兼容后端

**任务目标：** 恢复登录代理，保留现有业务库 V15。

**执行行为：**

1. 查看 Vite 错误日志，确认 `/api/v1/auth/login` 被代理到未监听的 8080，底层返回 `ECONNREFUSED`。
2. 查询现有 JAR 与 Git 历史。上一版 JAR 已被当前构建覆盖，准备在构建目录用合并前 `cc71c08` 的 Java 源码和已应用的 V15 脚本单独构建兼容版本。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 记录登录故障证据和兼容恢复方案 | 跟踪服务恢复 |

**执行命令与验证：**

- 命令：`netstat -ano -p tcp`、读取 Vite 错误日志。结果：5173、8100、6379、3306 正常监听，8080 无监听，登录代理报 `ECONNREFUSED`。

**影响范围：** 当前登录不可用。兼容后端仅用于暂时恢复现有 V15 环境，原分支与工作区代码继续保留。

**遗留问题：** 构建兼容 JAR，核对迁移内容后启动并验证登录 API。

---

### [2026-11-19 14:31:49] 启动 V15 兼容 Java 后端

**任务目标：** 在本地业务库维持 V15 的情况下恢复登录服务。

**执行行为：**

1. 将合并前 `cc71c08` 的 Java 源码导出到独立构建目录，加入工作区中与实际数据库对应的项目实战 V15 迁移脚本，离线打包。
2. 通过 Flyway 的 `validate()` 和 `info()` 只读核对业务库。现有的 15 个迁移全部通过校验，待执行迁移为 0。
3. 核对兼容 JAR 只包含 V1–V15。以 `--spring.flyway.target=15 --app.agent.scheduler-enabled=false` 启动兼容后端，原工作区代码和新版本 JAR 均保留。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/target/local-restarts/v15-compat-20261119-143007/` | 保存兼容源码快照、验证脚本、JAR 和本次进程日志 | 独立构建 V15 兼容后端 |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加兼容构建和启动记录 | 保留恢复依据 |

**执行命令与验证：**

- 命令：`git archive --format=tar -o <独立归档> cc71c08 project/backend`、`mvn -o -q -DskipTests package`。结果：构建成功。
- 命令：Java 单文件程序调用 Flyway `validate()` 与 `info()`，Python `zipfile` 读取兼容 JAR。结果：V15 历史有效，待执行迁移为 0，JAR 只含版本 1–15。
- 命令：`Start-Process` 运行兼容 JAR 并指定 V15 上限。结果：创建 Java 进程，PID 20948，待核验 HTTP。

**影响范围：** 临时兼容后端用于恢复登录。后台任务调度暂时关闭，避免旧版本服务接管现有任务。此版本没有合入的语音面试功能和依赖 V16 的项目实战增量。

**遗留问题：** 核对 8080、登录代理、Flyway 启动日志及数据库版本。

---

### [2026-11-19 14:32:08] 验证登录代理恢复和数据库版本

**任务目标：** 确认登录请求可以经过 Vite 到达 Java，现有数据库仍处于 V15。

**执行行为：**

1. 从 Java 启动日志确认 Tomcat 监听 8080，Flyway 成功校验 15 个迁移，业务库当前是 V15，且本次没有待执行迁移。
2. 向 Java 和 Vite 代理分别发送无用户数据的空 JSON 登录请求。两条路径均返回应用校验错误，验证代理已连接真实后端。核对受保护接口及 Agent 就绪端点。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加恢复验证结果 | 记录接口和迁移状态 |

**执行命令与验证：**

- 命令：`Invoke-WebRequest -Method Post -ContentType application/json -Body '{}'` 请求 8080 和 5173 的 `/api/v1/auth/login`。结果：均返回 HTTP 400、应用错误码 40001，原代理 HTTP 500 已消失。
- 命令：`curl.exe` 请求 Java `/api/v1/jobs` 和 Agent `/readyz`。结果：分别返回预期的 HTTP 401 和 HTTP 200。
- 验证：Java 日志为 `Schema career_business is up to date. No migration necessary.`，业务库保持 V15。

**影响范围：** V15 兼容后端正在运行，可接收本地登录请求。后台任务调度暂时关闭。

**遗留问题：** 未使用真实账号进行认证。切回合并后的 Java 版本仍需明确授权执行 V16 和 V17，之后需复验语音面试和项目实战。

---

### [2026-11-19 15:06:34] 定位模拟面试首题一直排队

**任务目标：** 查明面试对话生成卡点并恢复任务派发。

**执行行为：**

1. 通过端口和进程参数确认 Java 仍为 V15 兼容版本，并带有 `--app.agent.scheduler-enabled=false`。源码确认关闭后 Outbox 派发和过期回收均直接返回。
2. 只读检查业务库。最新创建的 7 题面试处于准备中，首题任务为 `QUEUED`，无 Agent 运行记录，Outbox 为 `PENDING` 且尝试 0 次，已超过 5 分钟期限。其余类型无在队任务。
3. Agent `/readyz` 返回就绪。当前业务库 Flyway 已应用最新版本仍为 V15。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加面试排队的进程参数与数据库状态检查 | 记录根因 |

**执行命令与验证：**

- 命令：`netstat -ano -p tcp`、`psutil.Process(...).cmdline()`、`Invoke-RestMethod /readyz`。结果：Java 监听，调度被关闭；Agent 已就绪。
- 命令：`mysql.exe` 只读查询 `ai_task`、`outbox_event`、`interview_session` 和 `flyway_schema_history`。结果：首题任务超过时限，派发从未启动。

**影响范围：** 兼容后端的所有异步任务受调度开关影响。

**遗留问题：** 恢复调度并跟踪上述任务的过期处理及重试结果。

---

### [2026-11-19 15:08:34] 重启 V15 兼容后端并恢复任务调度

**任务目标：** 恢复任务派发和过期回收，继续使用业务库 V15。

**执行行为：**

1. 核对 8080 监听者、Java 命令行、兼容 JAR 路径和 JAR 中的迁移版本。首次进程校验因本机 psutil 版本没有 `net_connections()` 方法而提前退出，替换为 `netstat` 校验端口归属后通过。
2. 仅重启 8080 上的已确认 Java 进程。仍使用含 V1–V15 的同一个兼容 JAR，保留 `--spring.flyway.target=15`，将 `--app.agent.scheduler-enabled` 改为 `true`。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/target/local-restarts/v15-compat-20261119-143007/scheduler-restart-20261119-150731/` | 本次后台进程日志 | 核验调度和回写 |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加 Java 重启和运行参数 | 记录调度恢复 |

**执行命令与验证：**

- 命令：`psutil`、`netstat -ano -p tcp`、`zipfile`。结果：核实旧 PID、运行参数与 JAR 迁移范围 V1–V15。
- 命令：`Stop-Process`、`Start-Process java -jar <兼容 JAR> --spring.flyway.target=15 --app.agent.scheduler-enabled=true`。结果：成功创建新进程 PID 31644。

**影响范围：** 原有业务库和应用版本下恢复 Java 异步任务调度。前端、Agent 和 Redis 继续运行。

**遗留问题：** 核实旧任务的终态及原面试会话的重试结果。

---

### [2026-11-19 15:10:48] 验证调度恢复和已过期面试的重试入口

**任务目标：** 验证调度已接管原排队任务，并确认旧任务可以通过产品流程重试。

**执行行为：**

1. Java 日志确认 8080 已启动。调度开启后，旧任务的派发请求因已超过 Agent 可受理时间被拒绝，随后被回收为 `EXPIRED`。业务库查询确认对应 Outbox 已结束，原会话处于 `FAILED`，首题未生成。
2. 核对当前任务中心对 `EXPIRED` 展示重试，以及兼容版 Java 的 `TaskRetryService` 接受原面试任务重试。浏览器控制初始化报告找不到内核资源路径，本轮未通过真实会话触发新的模型调用。
3. 在兼容版独立源码目录运行任务派发、任务重试、任务回写及面试服务测试。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加运行结果、测试与重试状态 | 记录修复边界 |

**执行命令与验证：**

- 命令：`mysql.exe` 只读查询原任务、重试记录和 `flyway_schema_history`，复核 Java 启动日志。结果：原任务已过期，尚无重试记录，业务库仍为 V15。
- 命令：`Invoke-WebRequest /api/v1/jobs` 经前端代理。结果：预期的未登录 HTTP 401。
- 命令：`mvn -o -q -Dtest=OutboxSchedulerTest,OutboxServiceTest,TaskRetryServiceTest,TaskResultServiceTest,InterviewServiceTest,InterviewResultHandlerTest test`。结果：10 项通过，0 失败。

**影响范围：** Java 已恢复异步派发、取消、回收及结果同步，运行版本仍为 V15 兼容后端。

**遗留问题：** 已过期的首题需从任务中心按原参数重试。未验证新一轮真实模型出题。

---

### [2026-11-19 15:47:31] 核对并备份语音迁移目标库

**任务目标：** 经用户授权，在本机 `career_business` 应用 V16、V17 并启用合并版语音服务。

**执行行为：**

1. 核对 MySQL 连接目标为本机 3306 的 `career_business`，确认当前成功版本 V15，排队及运行中的任务查询无结果。
2. 检查合并版 JAR 包含语音 Controller，17 份迁移脚本和当前工作区逐字节一致。
3. 使用 `mysqldump` 在 Git 忽略的本地运行目录创建迁移前备份，校验文件存在。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/target/local-restarts/voice-v17-20261119-154711/career_business-before-v17.sql` | 本地业务库迁移前备份 | 保留迁移恢复依据 |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加授权后的预检与备份记录 | 跟踪本次切换 |

**执行命令与验证：**

- 命令：`mysql.exe` 只读查询 Flyway 与任务状态、Python `zipfile`、`mysqldump.exe --single-transaction --quick --no-tablespaces`。结果：通过，目标库为 V15，备份已产生。

**影响范围：** 本地备份和运行记录。

**遗留问题：** 启动合并版并核对两项迁移及接口。

---

### [2026-11-19 15:48:32] 应用 V16、V17 并启动合并版 Java

**任务目标：** 让现有 5173 前端连接支持语音面试的 Java 后端。

**执行行为：**

1. 确认 8080 的进程仍为 V15 兼容实例，只停止该进程。
2. 启动合并版 JAR，限定 Flyway 目标为 17，开启任务调度、语音识别及播报。
3. Java 日志确认两份迁移均成功应用，本机业务库升级至 V17，后端在 8080 监听。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/target/local-restarts/voice-v17-20261119-154711/backend.out.log`、`backend.err.log` | 合并版 Java 进程日志 | 核对 Flyway 和启动 |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加迁移与切换记录 | 记录实际变更 |

**执行命令与验证：**

- 命令：`Stop-Process`、`Start-Process java -jar <合并版 JAR> --spring.flyway.target=17 --app.agent.scheduler-enabled=true --app.voice.asr-enabled=true --app.voice.tts-enabled=true`。结果：PID 2404 启动。
- 验证：Flyway 日志确认项目实战 V16、语音 V17 成功。前端面试路由 HTTP 200，代理后端的受保护接口 HTTP 401（未登录预期）且 Agent 就绪 HTTP 200。

**影响范围：** 已授权的本机业务库结构与 Java 运行版本，前端、Agent 和 Redis 继续运行。

**遗留问题：** 复核语音表和 Agent 的 Mock 语音接口。

---

### [2026-11-19 15:49:27] 验证 V17 数据库和本机 Mock 语音服务

**任务目标：** 确认升级完成，前端代理和本机语音服务可用。

**执行行为：**

1. 只读查询 Flyway 历史和数据库元数据，确认 V16、V17 成功，语音隐私同意表与转写表存在。
2. 使用单次本机内部认证和合成 WAV 验证 Agent ASR，使用合成题目验证 TTS。两个接口均返回 HTTP 200，提供方为 `fake`。
3. 复核运行中的 Java 8080、前端 5173 和 Agent 8100。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加实际迁移及语音验证结论 | 留存结果 |

**执行命令与验证：**

- 命令：`mysql.exe` 只读核对 `flyway_schema_history` 与 `information_schema.tables`。结果：通过，两项迁移及语音两张表均已核对。
- 命令：Python `httpx` 请求本机 Agent `/internal/ai/v1/speech/transcribe` 和 `/internal/ai/v1/speech/synthesize`。结果：Mock 转写、播报均通过。

**影响范围：** 5173 继续使用当前工作区前端，8080 运行合并版 Java，已开启语音接口和任务调度。

**遗留问题：** 未使用真实登录会话和麦克风走整条前端录音链路，当前语音提供方为 Mock。

---

### [2026-11-20 21:02:13] 重启本机前端、Java 后端和 Agent

**任务目标：**
按用户要求重启当前工作区三项开发服务。

**执行行为：**

1. 确认 5173 和 8080 分别由当前项目的 Vite 和 Java 持有，8100 未监听且未发现本项目 Agent 进程。核对原后端使用 Maven 启动，业务库先前为 V17，源码与编译目录的 17 个迁移文件名称和哈希一致。
2. 使用项目既有 `ai-job` Conda 环境启动 Python Agent。从当前位置重启原 Vite 与 Java 监听进程，继续使用原端口和项目配置。
3. 后台日志写入 Git 忽略目录。未停止 MySQL 或启动 Redis。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/target/local-restarts/three-services-20261120-210155/` | 前端、后端和 Agent 的标准输出及错误日志 | 核对重启结果 |
| 修改 | `docs/功能资料/服务启动_process.md` | 在当前工作区既有记录末尾追加 | 规范列出的另一工作区目录不存在 |

**执行命令与验证：**

- 命令：`Get-NetTCPConnection`、`Get-CimInstance`、`jcmd VM.system_properties`、`Get-FileHash` 和 `Stop-Process`。
- 命令：通过 `Start-Process` 执行 `python project/agent/run.py`、`node node_modules/vite/bin/vite.js --port 5173 --strictPort`、`mvn -o -B -DskipTests spring-boot:run`。
- 结果：三个启动进程均已创建，等待逐项健康验证。

**影响范围：**
当前本机前端和 Java 后端短时重启，原未运行的 Agent 开始启动。

**遗留问题：**
复核三端 HTTP 响应及本次 Flyway 状态。

---

### [2026-11-20 21:03:47] 验证前后端并定位 Agent 启动失败

**任务目标：**
核验三项服务的 HTTP 状态，识别未就绪的服务。

**执行行为：**

1. 验证前端 5173 首页返回 200，Java 8080 受保护接口和经前端代理访问均返回预期的未登录 401。Flyway 校验 17 个版本，业务库仍为 V17，无待执行迁移。
2. Agent 进程因 FastAPI 文件上传路由需要 `python-multipart` 而退出。根目录 `requirements.txt` 已锁定 `python-multipart==0.0.20`，本机 `ai-job` 环境缺少该包。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加分项检查结果 | 记录已恢复项和 Agent 的阻塞原因 |

**执行命令与验证：**

- 命令：HTTP GET 检查 5173、8080、8100，读取 `agent.err.log` 与后端启动日志，核对根目录依赖清单。
- 结果：前后端通过；首次 Agent 健康检查失败，8100 未监听。

**影响范围：**
前端和 Java 已恢复，Agent 尚待补齐现有声明的依赖。

**遗留问题：**
安装指定版本并重新启动 Agent。

---

### [2026-11-20 21:04:17] 补齐 Agent 环境的已声明依赖

**任务目标：**
恢复 FastAPI 的文件上传路由初始化和 Agent 启动。

**执行行为：**
在 `ai-job` Conda 环境安装项目已指定的 `python-multipart==0.0.20`，仅安装缺失的包。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `D:/Anaconda/envs/ai-job/Lib/site-packages/` 下的 `python-multipart` 包文件 | 安装既有清单锁定版本 | 补齐当前环境的运行依赖 |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加依赖处理记录 | 记录环境变更 |

**执行命令与验证：**

- 命令：`python -m pip --isolated --disable-pip-version-check install --no-deps --timeout 8 --retries 0 --index-url https://pypi.org/simple python-multipart==0.0.20`、`python -m pip show python-multipart`。
- 结果：安装成功，版本为 0.0.20，位置为 `ai-job` 环境。

**影响范围：**
仅向本机 AI-JOB 的 Python 环境补装既有依赖，项目依赖清单无需修改。

**遗留问题：**
重启 Agent 并核对健康接口。

---

### [2026-11-20 21:05:15] 复查三项服务均已就绪

**任务目标：**
确认 Agent 补齐依赖后能启动，并复查前后端和代理。

**执行行为：**

1. 使用同一 Conda 环境重新启动 Agent，保留原 SQLite 路径；启动时更新了 SQLite 共享内存文件。
2. 再次检查前端首页、Java 受保护接口、前端 API 代理、Agent 健康和就绪端点，并核对监听端口归属。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/target/local-restarts/three-services-20261120-210155/agent-retry.out.log`、`agent-retry.err.log` | Agent 重试日志 | 验证补齐依赖后的启动 |
| 修改 | `project/agent/data/agent_runs.db-shm` | Agent 打开原 SQLite 库时更新共享内存文件 | 正常运行时行为 |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加三端最终验证 | 记录服务运行结果 |

**执行命令与验证：**

- 命令：`Start-Process` 启动项目 `agent/run.py`，HTTP GET 检查 `/`、`/api/v1/jobs`、`/healthz`、`/readyz`，以及 `Get-NetTCPConnection`。
- 结果：前端首页 200；后端和前端代理均返回预期的匿名 401；Agent 健康和就绪均为 200，就绪状态为 `ready`。三个端口均已监听。
- 说明：Java 启动校验 17 个 Flyway 迁移，业务库仍为 V17，未执行新迁移；Redis 6379 未监听。

**影响范围：**
本机前端 5173、Java 后端 8080 和 Agent 8100 均已恢复运行。

**遗留问题：**
此次核验为启动级检查，未发起真实模型请求。找回密码和令牌撤销仍依赖 Redis。

---

### [2026-11-20 23:15:02] 启动简历评分版三端服务

**任务目标：**
启动当前工作区的前端、Java 后端及 Python Agent。

**执行行为：**
确认 MySQL 3306 正在监听，5173、8080、8100 空闲。按既有配置启动三端，Java 使用正常 Flyway 流程，Agent 复用 ai-job 环境及已有数据目录。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/target/local-restarts/three-services-20261120-231451/` | 三端后台启动日志 | 核验启动结果 |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加启动记录 | 跟踪运行变更 |

**执行命令与验证：**

- 命令：`Get-NetTCPConnection`、`Start-Process` 启动 Vite、`mvn -o -B -DskipTests spring-boot:run` 与 `python project/agent/run.py`。
- 结果：三端启动器已创建，等待核对端口、健康接口和 Flyway。

**影响范围：**
本机 AI-JOB 开发环境。

**遗留问题：**
待检查三端服务是否就绪。

---

### [2026-11-20 23:15:32] 核验简历评分版三端就绪

**任务目标：**
确认现用简历页面、API 代理和 Agent 已连通。

**执行行为：**
核对 5173、8080、8100 的监听进程。Java 启动时自动执行 V18，为 `resume_diagnosis` 新增可空 `assessment_json` 列。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加三端启动结果和 V18 执行情况 | 留存验证记录 |

**执行命令与验证：**

- 命令：本机 HTTP GET、`Get-NetTCPConnection`、读取 Java 启动日志。
- 结果：简历优化页面 200，诊断 API 直连和代理均返回预期的匿名 401，Agent `/readyz` 返回 200 且状态为 ready。Flyway 将本机业务库从 V17 升至 V18。

**影响范围：**
前端 5173、后端 8080、Agent 8100，及本机简历诊断评分列。

**遗留问题：**
本次完成启动级检查，未提交真实简历诊断或下载报告。

---

### [2026-11-22 13:14:36] 启动三端服务

**任务目标：** 重启当前工作区的前端、Java 后端和 Python Agent。

**执行行为：** 检查端口后，使用已有 Vite、离线 Maven 和 ai-job Python 环境启动三端。检查时未看到 5173、8080、8100 和 3306 的监听，CIM 进程查询不可用。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | project/backend/target/local-restarts/three-services-20261122-131435/ | 三端启动日志 | 排查启动状态 |
| 修改 | docs/功能资料/服务启动_process.md | 追加启动记录 | 留存操作 |

**执行命令与验证：** 
etstat -ano；通过 Start-Process 启动 Vite、mvn -o -B -DskipTests spring-boot:run 和 Agent un.py。启动请求已发出，待核验。

**影响范围：** 本机开发服务及其运行日志。

**遗留问题：** 待检查数据库及三端健康状态。

---

### [2026-11-22 13:15:39] 在本机环境重启三端

**任务目标：** 重启实际运行中的三端服务。

**执行行为：** 沙箱内网络和进程查询受限，上条记录中未看到监听不代表实际端口空闲；本机检查发现三端及 MySQL、Redis 均在监听。停止原三端监听进程，按原命令启动 Vite、后端已有 jar（Flyway target 19）和 Agent，未停止数据库。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | project/backend/target/local-restarts/three-services-20261122-131539/ | 三端启动日志 | 核验服务 |
| 修改 | docs/功能资料/服务启动_process.md | 追加本机重启记录 | 纠正沙箱检查的限制并记录操作 |

**执行命令与验证：** Get-NetTCPConnection、Get-CimInstance、Stop-Process、Start-Process；启动命令已执行，待健康检查。

**影响范围：** 三端短时中断，复用已有 jar、数据库及 Agent 数据。

**遗留问题：** 待核验三端响应和 Flyway 状态；jar 未重新构建。

---

### [2026-11-22 13:16:35] 验证三端重启结果

**任务目标：** 核验服务监听、HTTP 响应和数据库迁移状态。

**执行行为：** 检查三端端口、前端页面和代理、后端接口及 Agent 健康和就绪接口，读取后端启动日志。前端绑定 IPv6 回环地址 ::1，使用 localhost 访问。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | docs/功能资料/服务启动_process.md | 追加验证结果 | 留存启动状态 |

**执行命令与验证：** Get-NetTCPConnection、Invoke-WebRequest 和读取启动日志：localhost:5173 首页 200；后端 8080 及前端代理的受保护接口均返回预期匿名 401；Agent 8100 的 healthz 和 readyz 均为 200，readyz 为 ready。Flyway 校验 19 个迁移，数据库当前 V19，无新迁移。

**影响范围：** 本机三端服务；使用原后端 jar 和数据库。

**遗留问题：** 未执行真实登录或模型调用；未重新构建后端 jar，不能据此确认其包含最新源码变更。

---

### [2026-11-22 13:24:39] 排查同一简历再次解析失败

**任务目标：** 确认上传后解析失败的原因，并核对是否可按原版本重试。

**执行行为：** 核对在运 Agent 日志，确认两次简历解析已准备文档文本，调用 DeepSeek 时均发生 `APIConnectionError`，没有上游 HTTP 状态。通过现有模型配置分别探测直连及本机 HTTP 代理，再用虚构简历文本走实际 DeepSeek 解析与结果校验。通过简历中心现有会话核对页面仍提供按原版本重试入口。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加模型连接故障排查记录 | 留存报错与实测结果 |

**执行命令与验证：** `rg` 检查 Agent 日志；使用 `ai-job` Python 环境运行 HTTP 探测和 `CareerOrchestrator` 的 `RESUME_PARSE` 冒烟。无凭证的两种连通性探测均返回 HTTP 401，真实模型冒烟通过，虚构简历产出结构化事实。浏览器只读核验页面上的失败版本及重试入口。

**影响范围：** 验证涉及现有模型网络配置和虚构文本。未修改应用源码或运行配置。

**遗留问题：** 原任务保留失败状态，个人简历待通过原版本重试验证。

---

### [2026-11-22 14:53:38] 启动本地三端服务

**任务目标：** 再次重启前端、后端和 Agent。

**执行行为：** 本机检查发现三端端口均未监听，MySQL 和 Redis 仍监听；沿用现有后端 jar 和 Flyway target 19，分别启动 Vite、Java 后端与 Python Agent，未停止数据库。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | project/backend/target/local-restarts/three-services-20261122-restart/ | 三端启动日志 | 排查服务启动状态 |
| 修改 | docs/功能资料/服务启动_process.md | 追加启动记录 | 记录操作 |

**执行命令与验证：** 使用 Get-NetTCPConnection、Get-CimInstance 和 Start-Process 启动三端；健康检查待执行。

**影响范围：** 本机开发服务；复用已有 jar 和数据库。

**遗留问题：** 待核验三端健康状态；后端 jar 未重新构建。

---

### [2026-11-22 14:54:09] 验证三端服务

**任务目标：** 检查三端启动后的监听和响应。

**执行行为：** 检查端口、前端页面及代理、后端接口、Agent 健康与就绪接口，并查看后端启动日志。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加验证结果 | 记录实际状态 |

**执行命令与验证：** `Get-NetTCPConnection` 和 `Invoke-WebRequest`：前端首页返回 200；后端接口及前端代理返回预期的匿名 401；Agent `/healthz` 和 `/readyz` 返回 200，状态为 ready。后端日志显示 Flyway 校验 19 个迁移，数据库为 V19，无新迁移。

**影响范围：** 本机三端及现有数据库；未改变业务源码。

**遗留问题：** 未验证登录或模型调用；后端复用旧 jar，未重新构建，不保证包含最新源码变更。

---

### [2026-11-22 15:31:06] 再次重启本地三端

**任务目标：** 重启前端、Java 后端和 Python Agent。

**执行行为：** 本机检查发现后端和 Agent 正在监听，前端未监听；停止已识别的后端和 Agent 进程，按既有配置启动三端，沿用后端 jar 及 Flyway target 19。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/target/local-restarts/three-services-20261122-1500/` | 三端运行日志 | 检查启动状态 |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加重启记录 | 留存操作 |

**执行命令与验证：** 使用 `Get-NetTCPConnection`、`Get-CimInstance`、`Stop-Process`、`Start-Process`；启动命令已执行，待健康检查。

**影响范围：** 本机三端短时中断；复用现有数据库和后端 jar。

**遗留问题：** 待检查健康状态；后端 jar 未重新构建。

---

### [2026-11-22 15:31:32] 核验再次重启结果

**任务目标：** 确认三端服务正常响应。

**执行行为：** 检查监听端口、首页、后端接口与前端代理、Agent 健康和就绪接口，以及后端启动日志。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加验证结果 | 留存实际状态 |

**执行命令与验证：** `Get-NetTCPConnection` 和 `Invoke-WebRequest`：前端首页 200；后端接口及代理均返回预期匿名 401；Agent `/healthz` 与 `/readyz` 均返回 200，状态为 ready。后端日志显示数据库已为 V21，校验 21 个迁移，本次无新迁移。

**影响范围：** 本机三端和现有数据库。

**遗留问题：** 未进行登录或模型调用；使用已有 jar，未重新构建。

---

### [2026-11-22 19:29:59] 准备重启合并后的三端服务

**任务目标：** 用 feature/bug 的合并提交 13be7e1 重启前端、Java 后端和 Agent。

**执行行为：** 核对工作区提交与三个服务的实际进程。当前数据库使用语音分支 V15–V17 历史，与目标分支同号迁移不一致。找到已停止的原 feature/bug 数据卷，拟使用其副本运行 V22，保留两套原数据。使用相同提交的独立工作树构建 Java jar。

**文件变更：** 修改 docs/功能资料/服务启动_process.md。独立构建工作树生成 project/backend/target/backend-0.0.1-SNAPSHOT.jar。

**执行命令与验证：** git log、docker ps、docker inspect、Flyway 历史查询及 mvn -o -q -DskipTests package。构建通过，在线语音分支数据库最高为 V17。

**影响范围：** 准备本机运行产物，现有三端保持运行。

**遗留问题：** 核对原 feature/bug 数据副本版本并升级，随后切换三端，验证健康与实际语音识别。

---

### [2026-11-22 19:38:17] 处理数据库连接差异并准备独立 V22 库

**任务目标：** 保留正在使用的本地账号和面试记录，完成合并分支启动。

**执行行为：** 复制已停止的 Docker 卷后发现其仅到 V4，停止临时容器。旧 feature/bug 的 Windows MySQL 在 3306，当前项目凭据无法认证。保留原库，改用已运行的语音数据库作为数据来源，在同实例建立独立 career_business_feature_bug，启动单独的 Java 进程执行迁移。

**文件变更：** 修改本记录。新增本地启动目录 feature-bug-v22-20261122-193713 的 jar、迁移日志和一次性数据复制脚本。临时 Docker 副本已停止。

**执行命令与验证：** Docker 只读核对语音库 V17 和空闲任务队列，Redis PING 返回 PONG。Windows MySQL 只读查询返回 1045。独立 V22 迁移已启动，待查询结果与数据试复制。

**影响范围：** 原有 Windows MySQL 和语音业务库均未改写。新库使用原 MySQL 实例的独立 schema。

**遗留问题：** 完成复制校验后切换三端。

---

### [2026-11-22 19:41:46] 完成数据复制并启动 feature/bug 三端

**任务目标：** 以合并提交的源码和 Java 构建产物启动本地服务。

**执行行为：** 独立 schema 经 Flyway 从空库应用 V1–V22。先在事务内试复制并回滚，核对来源记录主键和目标库外键。首次切换因 Java 启动器 PID 与实际监听进程不同而提前停止，未结束旧服务。核对实际子进程后停止旧三端及迁移进程，复制源业务记录和 Agent SQLite，再启动新三端。

**文件变更：** 修改本记录。运行目录 feature-bug-v22-20261122-193713 新增 restart-merged-services.ps1、Agent SQLite 与三端运行日志。当前 .env 未修改。

**执行命令与验证：** Flyway 应用 22 个迁移。数据复制脚本处理 137 条来源记录，新写入 123 条，14 个技能字典主键使用 V22 已有值，并核对 101 个外键。SQLite quick_check 通过。三端进程启动成功，待检查接口和转写。

**影响范围：** 业务后端连接 career_business_feature_bug，复用本机 3307 MySQL 与 6380 Redis。两套原有业务数据保留。

**遗留问题：** 待核对运行进程健康与合成录音接口响应。

---

### [2026-11-22 19:43:12] 核验三端和真实转写

**任务目标：** 核验合并分支上线后的入口、数据库和语音服务。

**执行行为：** 通过 HTTP 检查前端首页、面试路由、Java API、前端代理和 Agent 健康接口。登录相关请求未携带 refresh cookie，带可信 Referer 后按预期得到 401。以合成 WebM 调用运行中 Agent 的真实 Qwen ASR，通过 MySQL 只读核对迁移和数据数量。

**文件变更：** 修改本记录，三端日志位于 project/backend/target/local-restarts/feature-bug-v22-20261122-193713/。

**执行命令与验证：** Invoke-WebRequest 显示首页与面试入口 200、Java API 及代理的匿名访问 401、Agent healthz 和 readyz 200。MySQL 核对 V22、1 个账号、1 个面试会话及 14 条转写记录。合成录音转写返回 HTTP 200、provider=qwen，结果非空，用时约 0.81 秒。5173、8080、8100 由新进程监听。

**影响范围：** feature/bug 合并版本已在本机运行，业务连接独立 V22 库，保留语音库来源记录。未改动 Windows MySQL 原库。

**遗留问题：** 未使用实体麦克风或登录用户账户验收。

---

### [2026-11-22 21:16:49] 重启 feature/bug 版 Java 后端并验证

**任务目标：**
使用运行目录中的最新 jar 重新启动 8080 后端，仅做启动与存活验证，不编译、不测试、不修改源码或配置，不动 Agent(8100) 与前端(5173)。

**执行行为：**
1. 确认 8080 无监听、jar/.env/JDK21 均存在，备份旧 `backend.out.log`/`backend.err.log` 为 `*.prev-20261122`。
2. 在运行目录新增启动脚本，读取 `.env` 的 `MYSQL_USER`/`MYSQL_PASSWORD`，清除 `AI_BAILIAN_API_KEY`、`DASHSCOPE_API_KEY`，设置 `DB_URL`、`REDIS_PORT=6380`、`SERVER_PORT=8080`、`AGENT_SCHEDULER_ENABLED=true`，以工作目录 `project/backend` 启动 `D:\JDK21\bin\java.exe -jar`，输出重定向到运行目录日志。
3. 因 CMD 被策略禁止，改为通过 `Invoke-CimMethod Win32_Process Create` 启动独立 PowerShell 宿主进程，由脚本内 `Start-Process` 拉起 Java 并 `WaitForExit` 保活。
4. 每 5 秒轮询 8080 监听、`curl.exe` 根路径状态码与启动日志，最多 30 秒。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 新增 | `project/backend/target/local-restarts/feature-bug-v22-20261122-193713/_start-backend-20261122.ps1` | 运行期启动脚本 | 在独立会话中注入环境变量并保活 Java |
| 新增 | `project/backend/target/local-restarts/feature-bug-v22-20261122-193713/backend.out.log`、`backend.err.log`、`backend.launcher.log`、`backend.java.pid` | 本次启动日志与 PID 记录 | 排障与验证依据 |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加本次启动记录 | 跟踪服务运行 |

**执行命令与验证：**

- 命令：`Invoke-CimMethod Win32_Process Create`、`Get-NetTCPConnection`、`curl.exe -s -o NUL -w '%{http_code}'`、`Select-String`、`netstat -ano`。
- 结果：通过。Java PID 76900，启动 21:16:49，`Started BackendApplication in 7.752 seconds`，Tomcat 监听 8080，根路径 HTTP 401（预期匿名未登录）。
- 说明：Flyway 当前版本 V22，成功校验 22 个迁移，`Schema ... is up to date. No migration necessary.`；`backend.err.log` 为 0 字节。8100 仍在监听（PID 54772），未操作。

**影响范围：**
仅本机 Java 后端进程重启；MySQL 3307/Redis 6380 复用，未触发迁移。未修改任何源码、测试或配置，工作区中既有改动均为此前任务遗留。

**遗留问题：**
本次仅验证启动与存活，未做业务接口功能测试。

---

### [2026-11-23 14:41:06] 确认三端在线并打开定向简历验收页

**任务目标：** 提供可供用户自行测试的本地项目页面。

**执行行为：** 核对并保留现有独立 V23 三端。通过独立 Chrome 会话打开 5188 前端，用虚构验收账号访问截图对应的定向稿。

**文件变更：** 追加本日志。Chrome 测试会话与启动辅助脚本位于系统临时目录。

**执行命令与验证：** netstat 显示 5188、18087、18187 正在监听。前端首页 HTTP 200，经代理访问受保护 API 匿名返回预期 401，Agent readyz 返回 200。可见 Chrome 窗口标题为智能简历优化，目标稿正文成功加载。

**影响范围：** 复用隔离测试库及服务，业务源码和常用浏览器资料未变更。

**遗留问题：** 页面已交给用户自行测试。

---

### [2026-11-23 16:25:47] 核对 V23 隔离环境及重启范围

**任务目标：** 从当前工作区重新启动可访问定向简历功能的三端。

**执行行为：** 查看当前分支、运行日志和宿主机端口。确认上次验收入口 5188 无监听，Java 18087 和 Agent 18187 仍在运行，隔离 MySQL 13387 与 Redis 16387 均在线。读取上次使用的启动参数及数据路径，计划复用原数据库与 Agent SQLite，仅重启三端。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加本次环境排查记录 | 跟踪重启范围 |

**执行命令与验证：** `git status`、`Get-NetTCPConnection`、`Get-CimInstance`、`Invoke-WebRequest`。Agent 重启前 readiness 返回 HTTP 200，后端对无来源头的 CSRF 请求返回 403，确认进程有响应。规范列出的 D:\vscode 工作区不存在，沿用本仓库的原日志。

**影响范围：** 本机原 V23 验收环境及当前源码构建。

**遗留问题：** 待启动并核验三端与用户入口。

---

### [2026-11-23 16:27:14] 从当前工作区恢复三端进程

**任务目标：** 重启 Java 和 Agent，并恢复原 5188 前端入口。

**执行行为：** 校验端口归属后停止原 Java 与 Agent 进程，复用预先存在的隔离 MySQL/Redis、文件目录和 Agent SQLite。在系统临时目录写入 Vite 启动脚本，把同源 `/api` 请求代理到 18087；使用当前 Vue 与 Python 源码和 Maven `spring-boot:run` 启动三端。沿用上次隔离环境的 DeepSeek 和语音禁用配置。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加启动记录 | 按阶段记录实际执行 |
| 新增 | 系统临时目录 `aijob-feature-demo-8d70490/v23-acceptance/restart-20261123-162626/` | Vite 启动脚本及三端日志 | 持久运行和排查 |

**执行命令与验证：** `Get-NetTCPConnection`、`Get-CimInstance`、`Stop-Process`、`Start-Process`、`mvn -o -B -DskipTests spring-boot:run`。已启动新进程，HTTP 验证待记录。

**影响范围：** 本机 V23 验收三端短时中断，后端继续使用原隔离库与数据。

**遗留问题：** 核实服务就绪、代理和数据库版本。

---

### [2026-11-23 16:28:31] 验证 V23 健康、登录及历史定向稿

**任务目标：** 确认重启后的入口可用，当前前端组件能编译，隔离历史数据可继续访问。

**执行行为：** 检查三端最新监听 PID 和 HTTP。通过 5188 代理使用既有合成验收账号登录，读取优化历史与一条已确认完整稿，检查其原始底稿存在。请求 Markdown 预览组件确认由 Vite 编译并引用当前解析器。核对 Java Flyway 日志。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加服务与业务读取验证结果 | 留存重启验收 |

**执行命令与验证：** `Get-NetTCPConnection`、`Invoke-WebRequest`、`Select-String`。前端首页和定向优化路由 200，匿名受保护接口 401，Agent 健康与就绪均 200。合成账号登录、历史列表、完整定向稿详情、Markdown 组件均返回 200。Flyway 校验 23 个迁移，数据库已为 V23。前端在 Java 就绪前记录一次代理连接拒绝，就绪后登录和代理请求通过。

**影响范围：** 复用本机隔离 V23 环境，未修改业务源码和配置文件。

**遗留问题：** 未重新调用模型或下载导出文件。该验收环境沿用语音禁用配置。

---

### [2026-11-23 17:25:15] 启动个人 JD 证据卡新版后端与 Agent

**任务目标：** 让现有 5188 入口能生成新版匹配结果。

**执行行为：** 核验旧进程归属，重启 Java 18087 与 Agent 18187。Java 使用本工作区及 Maven `spring-boot:run`，Agent 沿用 DeepSeek 验收配置。复用既有 5188 前端、V23 隔离库与 Redis。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| --- | --- | --- | --- |
| 修改 | `docs/功能资料/服务启动_process.md` | 追加本地服务切换记录 | 留存实际启动操作 |
| 新增 | 系统临时目录 `aijob-feature-demo-8d70490/v23-acceptance/evidence-restart-20261123-172445/` | Java 和 Agent 运行日志 | 核验进程与业务响应 |

**执行命令与验证：** `Get-NetTCPConnection`、`Stop-Process`、`Start-Process`、`mvn -o -B -DskipTests spring-boot:run`。启动进程已返回，待检查 HTTP 与实际匹配。

**影响范围：** 仅本机该隔离实例的后端和 Agent 进程。

**遗留问题：** 复测使用合成账号，不提交个人账号的简历。

---
