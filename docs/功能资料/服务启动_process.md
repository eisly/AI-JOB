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
