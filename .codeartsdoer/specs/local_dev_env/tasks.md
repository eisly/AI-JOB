# 本地开发环境配置 — 编码任务清单

> 依据 `.codeartsdoer/specs/local_dev_env/spec.md` 与 `design.md` 生成。
> 项目根目录：`H:\AI_JOB\AI-JOB`；主流程 PowerShell，`git-bash`/`cmd` 提供等效入口。
> 所有任务按"先只读核验、再环境构建、再数据与测试、最后回收与报告"排序；同一任务组内按编号顺序执行。

## 权限点速查（设计 2.4.1）

| 编号 | 权限/操作 | 未获批准时的降级 |
| --- | --- | --- |
| P1 | 项目目录读写（写 `.env`、日志、报告、锁文件） | 中止，退出码 30 |
| P2 | 进程级 `-ExecutionPolicy Bypass`（不改系统策略） | 改用 `cmd -File` 或手工流程 |
| P3 | 安装 Conda / JDK 21 / Maven / Docker Desktop（管理员或手工安装） | 中止并保留现状 |
| P4 | 修改系统级 `JAVA_HOME` / `PATH`（管理员） | 仅给会话级临时设置命令 |
| P5 | 启动 Docker Desktop | 提示手工启动并等待 |
| P6 | 拉取 MySQL 8.0.42 镜像（网络出站 + 磁盘） | 中止，退出码 40 |
| P7 | 结束占用 3307 / 8080 的进程（可能需管理员） | 建议改用其他端口 |
| P8 | 绑定非回环地址（防火墙可能拦截） | 强制 `127.0.0.1` |
| P9 | 创建 / 删除 Conda 环境 | 复用现有环境或中止 |
| P10 | 创建 / 停止容器与卷（Docker 权限） | 中止 |
| P11 | 删除数据卷（`purge`，数据不可恢复） | 拒绝执行，退出码 90 |
| P12 | 执行数据库 DDL / DML（建表、演示数据） | 中止，退出码 44 / 62 |
| P13 | 修改仓库代码（`application.yml`、测试类、文档、脚本） | 走代码评审与 Git 回滚，不适用运行时确认 |

## 任务依赖总览

| 任务组 | 依赖 | 可并行说明 |
| --- | --- | --- |
| 1 编排骨架与权限申请单 | 无 | 起点 |
| 2 前置条件核验 | 1 | 只读，不产生副作用 |
| 3 Conda 环境与 Python 依赖 | 1、2.7 | 与任务组 4 可并行 |
| 4 `.env` 初始化与校验 | 1、2.1、2.7 | 与任务组 3 可并行 |
| 5 Docker MySQL 启动与健康检查 | 4 | 串行 |
| 6 后端 `.env` 加载修复与启动建表 | 5；6.1 可与 1 并行 | 6.1 属代码修正，可提前实施 |
| 7 Flyway 建表验证 | 6 | 串行 |
| 8 演示数据导入 | 7 | 串行 |
| 9 测试隔离修复与 `mvn test` | 9.1 无依赖；9.3 依赖 1.2 | 默认测试可不依赖容器先行验证 |
| 10 环境回收与回滚 | 1.2、5、6 | 可与 7、8 并行开发 |
| 11 报告与文档交付 | 1、各阶段结果契约 | 贯穿，最后收口 |
| 12 端到端验证与验收 | 1–11 | 收尾 |

关键路径：`1 → 2 → 4 → 5 → 6 → 7 → 8 → 9 → 12`。

---

## 1. 编排骨架与权限申请单

### 1.1 建立编排目录结构与脚本说明骨架

- [ ] 创建 `scripts/dev-env/` 目录与 `scripts/dev-env/lib/` 目录，写入 `scripts/dev-env/README.md` 骨架，章节包含：用法、子命令表、退出码表、权限申请单（P1–P13）、回滚路径、故障排查。
  - 依赖：无
  - 涉及文件：`scripts/dev-env/README.md`、`scripts/dev-env/lib/`（新建目录）
  - 验收标准：目录存在；`README.md` 中 P1–P13 每行均有"操作 / 权限性质 / 未批准降级"三列；无任何真实密码出现。
  - 权限：P1、P13

### 1.2 实现基础能力模块 `DevEnv.Core.psm1`

- [ ] 在 `scripts/dev-env/lib/DevEnv.Core.psm1` 中实现：`DevEnvException`（按退出码构造）、`Write-DevLog`（quiet/normal/verbose 三级）、`Protect-Secret`（全量替换已知密码为 `<redacted>`）、`Invoke-External`（参数数组、显式工作目录、stdout/stderr 捕获、超时杀进程树、退出码透传）、`Write-Utf8NoBom`（BOM=false、LF）、`Resolve-EnvConfig`（从 `.env` / Compose / `application.yml` / `requirements.txt` 读取，不重复硬编码）。
  - 依赖：1.1
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Core.psm1`
  - 验收标准：`Protect-Secret` 对空值不替换、对多密码均替换；`Write-Utf8NoBom` 产物前 3 字节不含 `EF BB BF`；`Invoke-External` 在超时时返回 `TimedOut=true` 且不残留子进程；模块可被 `Import-Module` 无错误加载。
  - 权限：P1

### 1.3 实现 `setup.ps1` CLI 与退出码映射

- [ ] 实现 `scripts/dev-env/setup.ps1` 参数集（`-Command`、`-ProjectRoot`、`-NonInteractive`、`-SkipDemo`、`-IntegrationTest`、`-RecreatePython`、`-AllowNonLoopback`、`-ConfirmDataLoss`、`-BackendTimeoutSeconds`、`-DbTimeoutSeconds`、`-TestTimeoutSeconds`、`-ReportPath`、`-LogLevel`、`-DryRun`），用 `ValidateSet` 强约束子命令与日志级别，建立 `ExitCode` / `StageName` / `StageStatus` 枚举与"异常→退出码"映射表。
  - 依赖：1.2
  - 涉及文件：`scripts/dev-env/setup.ps1`
  - 验收标准：非法子命令或非法参数返回 2；`-DryRun` 下不创建文件、不启动容器、不安装依赖；未捕获异常统一映射为 99 并将脱敏堆栈写入日志；`-?` 输出包含全部子命令与权限说明。
  - 权限：P1、P2

### 1.4 实现 git-bash 薄封装与 cmd 调用方式

- [ ] 编写 `scripts/dev-env/setup.sh`：检测 `pwsh`，不存在则回退 `powershell`，以 `-NoProfile -ExecutionPolicy Bypass -File` 透传全部参数与退出码；在 `scripts/dev-env/README.md` 增加 cmd 的 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\dev-env\setup.ps1 -Command all` 示例。
  - 依赖：1.3
  - 涉及文件：`scripts/dev-env/setup.sh`、`scripts/dev-env/README.md`
  - 验收标准：在 git-bash 下 `./scripts/dev-env/setup.sh check` 可执行且 `echo $?` 与 PowerShell 退出码一致；参数含空格路径时不被拆断；脚本业务逻辑行数 ≤ 20（不复制业务判定）。
  - 权限：P1、P2

### 1.5 忽略运行期产物

- [ ] 在根 `.gitignore` 追加 `.local-dev-env/`，并确认 `.env`、`.env.*`、`!.env.example` 规则仍生效；确认 `.local-dev-env/` 下日志、报告、变更日志、锁文件均不被 Git 跟踪。
  - 依赖：1.1
  - 涉及文件：`.gitignore`
  - 验收标准：`git check-ignore -v .local-dev-env/report.json` 命中；`git check-ignore -v .env` 命中；`git check-ignore -v .env.example` 不命中。
  - 权限：P1、P13

### 1.6 实现单实例锁

- [ ] 实现 `.local-dev-env/orchestrator.lock` 的获取/释放：写入 `{ pid, startedAt, command }`，获取前检测 PID 存活，进程不存在或超过 2 小时的陈旧锁自动回收；`check` / `status` / `report` 不取锁。
  - 依赖：1.2
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Core.psm1`、`scripts/dev-env/setup.ps1`
  - 验收标准：两个终端同时执行 `db` 时，第二个返回 99 并提示"已有编排在运行"；正常结束后锁文件被删除；人为写入不存在 PID 的锁后可被自动回收。
  - 权限：P1

---

## 2. 前置条件核验（check）

### 2.1 必需文件与工作目录核验

- [ ] 实现工作目录核验：当前根目录下必须存在 `.env.example`、`docker-compose.yml`、`requirements.txt`、`project/backend/pom.xml`，任一缺失返回 11 并提示正确根目录示例，不创建任何文件。
  - 依赖：1.3
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Preflight.psm1`
  - 验收标准：在错误目录执行返回 11，且执行前后目标目录文件列表无变化；在项目根执行返回通过并列出四个文件。
  - 权限：P1

### 2.2 Conda 可用性核验

- [ ] 实现 Conda 核验：`Get-Command conda` 与 `conda --version`（15s 超时），失败返回 20，输出安装指引或将 Conda 加入 PATH 的说明。
  - 依赖：2.1
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Preflight.psm1`
  - 验收标准：Conda 不在 PATH 时返回 20 且不进入 Python 阶段；可用时在报告中记录 `conda` 原始版本串。
  - 权限：P3（仅提示，不自动安装）

### 2.3 JDK 21 核验

- [ ] 实现 JDK 核验：解析 `java -version`（输出在 stderr）与 `$env:JAVA_HOME/bin/java` 存在性，主版本必须严格等于 `project/backend/pom.xml` 中 `java.version`（21）；17 或 22 及以上均判不兼容并返回 12，提示切换 `JAVA_HOME`。
  - 依赖：2.1
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Preflight.psm1`
  - 验收标准：JDK 17 环境返回 12 并输出"需要 21"；JDK 21 通过；期望版本从 `pom.xml` 读取而非硬编码。
  - 权限：P3、P4

### 2.4 Maven 与 JDK 一致性核验

- [ ] 实现 Maven 核验：解析 `mvn -version` 的 Maven 版本（主版本 3、次版本 ≥ 9）与实际 `Java version`，并与 2.3 解析结果比对；不一致返回 13，提示 `JAVA_HOME` 与 `PATH` 顺序问题。
  - 依赖：2.3
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Preflight.psm1`
  - 验收标准：`mvn -version` 显示 Java 17 而 `java -version` 为 21 时返回 13；两者一致时通过并在报告中同时记录 Maven 与 Java 版本。
  - 权限：P3、P4

### 2.5 Docker 客户端与守护进程核验

- [ ] 实现 Docker 核验：`docker --version`、`docker compose version`、`docker info --format '{{.ServerVersion}}'`（30s 超时，不重试）；客户端缺失返回 14，守护进程不可达返回 14 并提示"请先启动 Docker Desktop"。
  - 依赖：2.1
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Preflight.psm1`
  - 验收标准：Docker Desktop 未启动时在 30s 内返回 14，不无限等待；守护进程可用时记录 Server 版本；交互模式下询问是否尝试启动 Desktop，非交互模式直接提示手工启动。
  - 权限：P5

### 2.6 端口预检

- [ ] 实现端口预检：读取 `.env` 的 `MYSQL_PORT`（缺省 3307）与 `SERVER_PORT`（缺省 8080），用 `Get-NetTCPConnection`（不存在时回退 `netstat -ano`）检测占用并输出 PID/进程名；占用时返回 42，区分"本项目已有容器"与"其他进程"。
  - 依赖：2.1
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Preflight.psm1`
  - 验收标准：3307 被占用且非本项目容器时返回 42 并建议改端口；8080 被占用时提示用 `SERVER_PORT` 覆盖；`check` 模式只读，不结束任何进程。
  - 权限：P7

### 2.7 版本解析强类型化与自检

- [ ] 实现 `ToolVersion { Name, Raw, Major, Minor, Patch, RequiredRange, Compatible }` 并让 2.2–2.4 全部返回该类型；提供纯 PowerShell 断言自检（不引入 Pester），用固定样例覆盖 `java -version` 的 stderr 输出、`mvn -version` 多行输出、`conda --version` 单行输出。
  - 依赖：2.2、2.4
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Preflight.psm1`、`scripts/dev-env/setup.ps1`
  - 验收标准：后续阶段不再重复解析版本字符串；自检在 `-Command check -DryRun` 下可触发且全部断言通过。
  - 权限：P1

### 2.8 `check` 汇总与只读保证

- [ ] 将 2.1–2.6 结果聚合为 `PreflightResult`（Tools、MissingFiles、PortStates、DockerState），输出表格并保证 `check` 全程无副作用；`-NonInteractive` 且核验失败时以对应退出码结束，不进入后续阶段。
  - 依赖：2.5、2.6、2.7
  - 涉及文件：`scripts/dev-env/setup.ps1`、`scripts/dev-env/lib/DevEnv.Preflight.psm1`
  - 验收标准：`check` 执行前后 `.local-dev-env/` 仅可新增日志，不新增容器、不新增 `.env`、不安装包；失败时输出"缺失项 + 整改建议"两个列表。
  - 权限：P1

---

## 3. Conda 环境与 Python 依赖

### 3.1 环境探测与版本校验

- [ ] 用 `conda env list --json` 探测 `ai-job`，存在时以 `conda run -n ai-job python -c` 读取 `sys.version_info`，主次版本非 3.11 时返回 21 并提示"需人工确认迁移或重建"，不自动删除。
  - 依赖：1.2、2.7
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Python.psm1`
  - 验收标准：环境不存在时返回"未创建"状态而不报错；环境为 3.10 时返回 21 且环境未被删除；3.11 时记录实际补丁版本。
  - 权限：P9

### 3.2 创建 Conda 环境

- [ ] 环境不存在时执行 `conda create -n ai-job python=3.11 -y`，成功后记入变更日志 `CondaEnvCreated`；已存在时跳过并输出"复用存量环境"。
  - 依赖：3.1
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Python.psm1`、`scripts/dev-env/lib/DevEnv.Rollback.psm1`
  - 验收标准：重复执行两次不报错且不覆盖环境；`CreatedByThisRun=true` 仅在本次新建时写入；仅在 `-RecreatePython` 时才允许删除重建。
  - 权限：P9

### 3.3 设置用户包隔离并验证生效

- [ ] 执行 `conda env config vars set -n ai-job PYTHONNOUSERSITE=1`（幂等），以 `conda run -n ai-job python -c "import site; print(site.ENABLE_USER_SITE)"` 验证输出 `False`；未生效则输出"退出环境后重新激活"指引并返回 21。
  - 依赖：3.1
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Python.psm1`
  - 验收标准：判定依据是 `site.ENABLE_USER_SITE is False`，不是"命令执行成功"；重复设置同值不报错。
  - 权限：P9

### 3.4 安装 Python 依赖

- [ ] 以 `conda run -n ai-job --no-capture-output python -m pip install -r requirements.txt` 安装，15 分钟超时；失败返回 22，输出失败包名与可能原因，保留已安装部分，不执行破坏性清理；命令行与日志不得出现索引凭据。
  - 依赖：3.3
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Python.psm1`
  - 验收标准：安装成功时 17 个直接依赖均存在；失败时退出码 22 且报告含失败包名；重跑时利用 pip 缓存，不重复下载已完成包。
  - 权限：P9、P1

### 3.5 依赖一致性检查与版本比对

- [ ] 执行 `pip check` 要求输出 `No broken requirements found.`（否则返回 23 并输出冲突链路）；再用 `pip list --format=json` 解析 `requirements.txt` 的直接依赖并逐项比对，包名做规范化（小写、`-` 与 `_` 等价），差异项写入 `DependencySpec.Matched=false`。
  - 依赖：3.4
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Python.psm1`
  - 验收标准：17 项直接依赖全部 `Matched=true` 才判定就绪；任一版本不符时列出包名、期望版本、实际版本三列；不得用安装退出码代替 `pip check`。
  - 权限：P9

### 3.6 Python 阶段结论与范围声明

- [ ] 输出 `PythonResult`（EnvName、PythonVersion、NoUserSite、Dependencies、Passed），并在结论中固定声明"环境就绪仅代表 Python 依赖可用，不代表 AI 服务已实现或可启动"。
  - 依赖：3.5
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Python.psm1`、`scripts/dev-env/lib/DevEnv.Report.psm1`
  - 验收标准：报告中不含"AI 服务可用"表述；就绪判据以数组形式逐条输出（环境存在、版本 3.11、隔离生效、依赖一致、无冲突）。
  - 权限：P1

---

## 4. `.env` 初始化与校验

### 4.1 生成 `.env`（不覆盖已有文件）

- [ ] 实现 `Initialize-EnvFile`：`.env` 缺失时由 `.env.example` 复制并进入"待配置"状态（返回 30）；已存在时一律保留，仅返回"已存在"；写入使用 UTF-8 无 BOM 与 LF 换行。
  - 依赖：1.2、2.1
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Core.psm1`、`scripts/dev-env/setup.ps1`
  - 验收标准：已存在 `.env` 时文件字节内容前后完全一致；新生成文件无 BOM；重复执行不产生第二份模板内容。
  - 权限：P1

### 4.2 交互式密码录入

- [ ] 交互模式下用 `Read-Host -AsSecureString` 读取 `MYSQL_PASSWORD` 与 `MYSQL_ROOT_PASSWORD`，转明文后仅写入 `.env`，不回显、不写入日志、不进入命令历史与报告；非交互模式缺少密码时返回 30 并给出待配置键名。
  - 依赖：4.1
  - 涉及文件：`scripts/dev-env/setup.ps1`、`scripts/dev-env/lib/DevEnv.Core.psm1`
  - 验收标准：日志与报告中检索不到密码原文；`.env` 内容不被整体打印；`-NonInteractive` 下不阻塞等待输入。
  - 权限：P1

### 4.3 `.env` 业务规则校验（E2–E7、E10）

- [ ] 实现规则校验：E2 五个键齐全且非空；E3 两密码不等于模板占位值（含 `replace_with`）；E4 两密码必须不同；E5 `MYSQL_USER` 不得为 `root`；E6 `MYSQL_PORT` 为 1–65535 且未被占用；E7 `MYSQL_BIND_ADDRESS` 为回环地址（非回环需 `-AllowNonLoopback` 且二次确认）；E10 密码字符集限定 `A-Za-z0-9-_@.` 且长度 ≥ 12。
  - 依赖：4.1、2.6
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Core.psm1`、`scripts/dev-env/lib/DevEnv.Preflight.psm1`
  - 验收标准：每条规则独立返回 `RuleId`、`Passed`、`Message`（消息不含值）；占位密码或两密码相同时返回 31 并拒绝启动容器；端口被占用返回 42；非回环未确认返回 31。
  - 权限：P1、P8

### 4.4 编码与解析校验（E8）

- [ ] 校验 `.env` 为 UTF-8 无 BOM、无重复键、可被解析为键值；出现 BOM 或重复键返回 32，并说明"BOM 会导致首个键名污染为 `\ufeffMYSQL_BIND_ADDRESS`"。
  - 依赖：4.3
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Core.psm1`
  - 验收标准：人为注入 BOM 后返回 32 且不启动容器；重复 `MYSQL_PORT` 键被识别；正常文件通过。
  - 权限：P1

### 4.5 Git 忽略校验（E9）

- [ ] 执行 `git check-ignore -q .env`，未命中时返回 30 并判定为严重违规，拒绝继续启动容器；同时在报告中标注"不存在已跟踪的 `.env`"。
  - 依赖：4.1
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Preflight.psm1`
  - 验收标准：临时移除 `.gitignore` 中的 `.env` 规则后校验失败；恢复后通过；校验过程不修改 Git 索引。
  - 权限：P1

### 4.6 凭据脱敏接入

- [ ] 在上下文初始化时收集 `.env` 中所有密码值注入 `Protect-Secret`，覆盖外部命令输出、异常消息、报告写入三条路径；额外屏蔽 `MYSQL_PWD=`、`--password=`、`-p` 后紧跟的值。
  - 依赖：4.3
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Core.psm1`
  - 验收标准：故意在外部命令输出中打印密码时，控制台与日志显示 `<redacted>`；报告 JSON 中不存在密码字段，只保留 `SecretRef` 布尔态。
  - 权限：P1

---

## 5. Docker MySQL 启动与健康检查

### 5.1 Compose 解析与卷状态探测

- [ ] 用 `docker compose config --services` 获取服务名（仅服务名，禁止把 `docker compose config` 展开结果写入日志），从 `docker-compose.yml` 读取镜像版本、库名、卷名、健康检查参数与端口映射；检测命名卷 `mysql_data` 是否已存在。
  - 依赖：4.6
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Docker.psm1`
  - 验收标准：日志中不含展开后的环境变量；服务名识别为 `mysql`；卷存在状态写入 `DbState.VolumeExists`；镜像版本与库名来自 Compose 而非硬编码。
  - 权限：P10

### 5.2 端口占用处置

- [ ] 启动前再次确认宿主端口（`.env` 的 `MYSQL_PORT`）可用；占用时区分"本项目已有容器占用"（可复用）与"其他进程占用"（返回 42），后者列出 PID/进程名，需 `-Approve KillProcess` 才允许结束，否则只建议改端口。
  - 依赖：5.1、2.6
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Docker.psm1`
  - 验收标准：未获批准时不会结束任何非本项目进程；改端口提示中明确"仅在首次启动前修改"。
  - 权限：P7

### 5.3 启动 MySQL 容器

- [ ] 执行 `docker compose up -d mysql` 并记录启动时刻与本阶段 `runId`；镜像拉取失败返回 40（保留已下载层），容器创建/启动失败返回 41；成功时记录变更日志 `ContainerStarted`，首次创建卷时记录 `VolumeCreated`。
  - 依赖：5.2
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Docker.psm1`、`scripts/dev-env/lib/DevEnv.Rollback.psm1`
  - 验收标准：命令输出不含密码；失败时不删除卷；重复执行 `up -d` 不重建已运行容器。
  - 权限：P6、P10

### 5.4 健康状态轮询

- [ ] 每 5s 读取 `docker compose ps --format json`，要求 `State=running` 且 `Health=healthy`；超时阈值首次（卷不存在或无镜像）900s、二次 90s，可用 `-DbTimeoutSeconds` 覆盖；超时返回 43。
  - 依赖：5.3
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Docker.psm1`
  - 验收标准：`running` 但未 `healthy` 时判定为未就绪；首次与二次超时阈值分别生效；超时后不进入后端阶段；轮询周期 5s 可观测于 verbose 日志。
  - 权限：P10

### 5.5 连通性双判据

- [ ] 健康后以应用账号执行 `SELECT 1`（经 `docker compose exec -T -e MYSQL_PWD` 注入密码，禁止把密码放在命令行参数），并校验可连接库为 `career_business`；连接失败返回 43，认证失败返回 44。
  - 依赖：5.4
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Docker.psm1`
  - 验收标准：健康状态成立但应用账号不可用时被判为失败；`SELECT 1` 成功后才允许进入后端阶段；密码不出现在进程参数与日志中。
  - 权限：P10、P12

### 5.6 失败分类与 L2 自动停止

- [ ] 健康或连通失败时采集 `docker compose logs --tail=200 mysql`，按"镜像拉取失败 / 初始化慢 / 资源不足 / 配置错误 / 端口冲突"分类输出，并自动执行 L2（`docker compose stop mysql`）保留数据卷，不重试、不删卷。
  - 依赖：5.5
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Docker.psm1`、`scripts/dev-env/lib/DevEnv.Rollback.psm1`
  - 验收标准：失败后容器为 `exited` 且卷仍存在；输出包含日志尾部路径与至少一条整改建议；不出现 `down -v`。
  - 权限：P10

### 5.7 卷语义与凭据沿用提示

- [ ] 卷已存在时，在报告中标注"账号密码沿用库内已初始化值，修改 `.env` 不会改密"，并提示"如需改密请在数据库内执行并同步 `.env`"；该情形不得判定为失败。
  - 依赖：5.5
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Docker.psm1`、`scripts/dev-env/lib/DevEnv.Report.psm1`
  - 验收标准：已有卷场景 `db` 阶段可成功；报告含"凭据沿用库内值"标记；修改 `.env` 密码后连接失败时提示按"库内密码 → `.env`"核对，而非直接报配置错误。
  - 权限：P1、P10

### 5.8 `DbState` 落库与阶段结果

- [ ] 生成 `DbState { ContainerState, Health, PortMapping, VolumeExists, CredentialsInitialized }` 并写入 `StageResult`；`db` 子命令单独执行时可独立成功。
  - 依赖：5.7
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Docker.psm1`、`scripts/dev-env/lib/DevEnv.Report.psm1`
  - 验收标准：`Status`/`Health`/端口映射与 `docker compose ps` 一致；`db` 阶段判据数组包含"healthy""SELECT 1 成功""端口映射一致""卷存在"。
  - 权限：P1

---

## 6. 后端 `.env` 加载缺陷修复与启动建表

### 6.1 修复 `application.yml` 的 `spring.config.import`

- [ ] 将 `project/backend/src/main/resources/application.yml:5` 的导入串由 `"optional:file:../../.env[.properties],optional:fmvn spring-boot:runile:./.env[.properties]"` 修正为两个合法候选：`optional:file:../../.env[.properties],optional:file:./.env[.properties]`，保持 `optional:` 语义不变。
  - 依赖：无（可与其他任务并行，属代码修正）
  - 涉及文件：`project/backend/src/main/resources/application.yml`
  - 验收标准：`grep "mvn spring-boot:run"` 在 `application.yml` 中无残留；两个候选路径均形如 `optional:file:<path>[.properties]`；`mvn -q help:evaluate` 或启动后可通过配置读取校验（无语法报错）。
  - 权限：P13

### 6.2 验证双工作目录读取 `.env`

- [ ] 分别以 `project/backend` 与项目根为工作目录启动后端各一次，确认两次都读取到根目录 `.env` 的库名、账号与端口（连接成功即可证明），将两次结果写入报告。
  - 依赖：6.1、5.8
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Backend.psm1`、`.codeartsdoer/specs/local_dev_env/design.md`（验证记录引用）
  - 验收标准：两个工作目录下均出现 `Started BackendApplication`；若仅一个成功，判定 6.1 修复不完整并返回 52。
  - 权限：P1

### 6.3 后台启动后端与日志重定向

- [ ] 实现 `Start-Backend`：在 `project/backend` 以 `mvn spring-boot:run` 后台启动，stdout/stderr 重定向至 `.local-dev-env/logs/backend-<runId>.log`，记录 PID、启动时刻与端口，写入变更日志 `BackendProcessStarted`。
  - 依赖：6.2
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Backend.psm1`、`scripts/dev-env/lib/DevEnv.Rollback.psm1`
  - 验收标准：日志文件非空且含 Maven 输出；PID 可被 `stop` 使用；不再向已有的 `project/backend/run.log` 写入。
  - 权限：P1

### 6.4 就绪判定与超时

- [ ] 实现 `Wait-BackendReady`：每 1s 轮询日志出现 `Started BackendApplication` 且端口可建立 TCP 连接；默认上限 60s，可用 `-BackendTimeoutSeconds` 覆盖（首次依赖下载建议 300s）；超时返回 50。
  - 依赖：6.3
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Backend.psm1`
  - 验收标准：仅日志出现但端口未监听时判定未就绪；就绪耗时写入 `BackendState.StartupDurationMs`；超时后自动 L1 结束本次进程。
  - 权限：P1

### 6.5 启动失败分类

- [ ] 将启动失败按现象分类：`Access denied` → 52（认证）、`Communications link failure`/`Connection refused` → 52（连接）、`FlywayException`/checksum mismatch → 51（迁移）、`Port ... already in use` → 52（端口）、`Unsupported class file major version` → 12（JDK），并输出对应排查顺序。
  - 依赖：6.4
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Backend.psm1`
  - 验收标准：五类现象各有一条匹配规则并有最小样例断言；认证类提示"先核对库内密码再核对 `.env`"；输出中不含密码。
  - 权限：P1

### 6.6 停止后端进程并释放端口

- [ ] 实现 `Stop-Backend`：按 PID 先正常结束、超时后强制结束进程树，验证端口释放；`stop` 与失败回滚均复用该函数。
  - 依赖：6.3
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Backend.psm1`
  - 验收标准：停止后再次启动不报端口占用；PID 不存在时幂等返回成功；不结束非本次启动的进程（以变更日志 `CreatedByThisRun` 判定）。
  - 权限：P1、P7（仅在强制结束残留进程时）

### 6.7 存量实例复用

- [ ] 通过变更日志 PID + 端口双重识别判断后端是否已在运行；已运行则复用并只做健康检查（`GET /api/v1/users` 或 TCP 连通），不重复启动。
  - 依赖：6.4、6.6
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Backend.psm1`
  - 验收标准：连续执行两次 `backend` 不产生两个 Java 进程；复用路径在报告中标注"复用已有实例"。
  - 权限：P1

### 6.8 启动前置校验

- [ ] 启动前校验：`.env` 存在且校验通过（否则 30/31）、容器 `healthy` 且 `SELECT 1` 成功（否则 43/44）、目标端口空闲（否则 42）；任一不满足即中止，不产生编译产物以外的副作用。
  - 依赖：5.8、6.2
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Backend.psm1`
  - 验收标准：容器 `starting` 时启动后端被拒绝并提示时序错误；8080 被占用时指出占用 PID 并建议 `SERVER_PORT`。
  - 权限：P7

---

## 7. Flyway 建表验证

### 7.1 迁移记录校验

- [ ] 实现 `Test-MigrationState` 的迁移记录部分：查询 `flyway_schema_history`，要求存在 `version=1` 且 `success=1` 的记录；缺失或校验和异常返回 51 并输出失败版本与脚本名。
  - 依赖：6.4
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Backend.psm1`
  - 验收标准：无记录时返回 51 并提示"是否已启动过后端完成迁移"；记录存在时写入 `MigrationState.AppliedVersions`。
  - 权限：P12

### 7.2 业务表数量校验

- [ ] 查询 `information_schema.tables`，统计 `table_schema=DATABASE()`、`table_type='BASE TABLE'` 且排除 `flyway_schema_history` 的表数，必须为 31；不足时返回 51 并输出缺失表名。
  - 依赖：7.1
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Backend.psm1`
  - 验收标准：表数为 31 时通过；人为删除一张业务表后返回 51 且缺失表名可见（不含敏感信息）；期望表数从设计基线读取（常量 31，注释标注来源）。
  - 权限：P12

### 7.3 不执行破坏性迁移操作

- [ ] 明确禁止 `flyway clean` 与修改已执行的 V1 脚本；迁移失败时保留数据库现场与日志，输出"修正脚本后重试或经确认重建空库"两条路径。
  - 依赖：7.2
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Backend.psm1`、`scripts/dev-env/README.md`
  - 验收标准：代码中不存在 `flyway clean` 调用；修改 V1 后启动报 checksum 校验失败（人工验证记录到报告）。
  - 权限：P12

### 7.4 存量非空库基线确认

- [ ] 检测"库内存在业务表但缺少 `flyway_schema_history`"的情形：判定迁移策略未确认，拒绝自动建表并返回 51，提示先确认基线策略，禁止强制清理。
  - 依赖：7.1
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Backend.psm1`
  - 验收标准：该场景下不创建 `flyway_schema_history`、不删除任何表；提示文案包含"确认迁移基线策略"。
  - 权限：P12

---

## 8. 演示数据导入

### 8.1 前置表存在校验

- [ ] 实现 `Test-DemoPreconditions`：确认 `app_user`、`job`、`job_version`、`skill_catalog`、`question` 五张表存在；缺失返回 60 并提示"先启动后端完成迁移"。
  - 依赖：7.2
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Data.psm1`
  - 验收标准：仅启动容器未跑迁移时返回 60；五表齐全时进入空库判定。
  - 权限：P12

### 8.2 空库判定与幂等跳过

- [ ] 对五张关键表执行 `COUNT(*)`，全部为 0 才允许导入；任一非 0 时标记 `DemoState.Skipped=true`，以退出码 0 结束并提示"已导入或非空库，脚本不可重复导入"，不报错。
  - 依赖：8.1
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Data.psm1`
  - 验收标准：二次执行 `demo` 返回 0 且数据库中行数不变；非空库不会被清空；报告中区分"导入成功"与"跳过"。
  - 权限：P12

### 8.3 经容器内客户端导入

- [ ] 以 `Get-Content -Raw` 读取 `project/backend/src/main/resources/db/demo/demo_data.sql`，通过管道交给 `docker compose exec -T mysql mysql --default-character-set=utf8mb4 -u<user> career_business`，密码经 `MYSQL_PWD` 注入；失败返回 62，不使用 `source` 命令。
  - 依赖：8.2
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Data.psm1`
  - 验收标准：路径含空格或中文时可成功导入；命令行与日志无密码；失败时事务未提交部分不落库。
  - 权限：P12、P6（如容器内无客户端需拉取镜像层）

### 8.4 导入结果抽样校验

- [ ] 校验 `SELECT nickname FROM app_user WHERE email='candidate@example.invalid'` 等于"演示求职者"、`skill_catalog` 含 `java/mysql/redis/spring`、`job` 至少 1 行；中文异常或缺失返回 63。
  - 依赖：8.3
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Data.psm1`
  - 验收标准：中文昵称正确显示（非乱码）；三项校验结果写入 `DemoState.CharsetVerified` 与关键行计数。
  - 权限：P12

### 8.5 `-SkipDemo` 与安全边界

- [ ] 实现 `-SkipDemo` 跳过整个演示数据阶段（标记 `Skipped`）；在报告与输出中固定声明"演示数据仅限本地开发库、账号已禁用、数据为虚构、不可重复导入"。
  - 依赖：8.4
  - 涉及文件：`scripts/dev-env/setup.ps1`、`scripts/dev-env/lib/DevEnv.Data.psm1`
  - 验收标准：`-SkipDemo` 下不产生任何 DML；声明文本在所有路径（成功/跳过/失败）均出现。
  - 权限：P12

### 8.6 `DemoState` 落库

- [ ] 输出 `DemoState { Imported, Skipped, KeyRowCounts, CharsetVerified }` 并合并到阶段结果与报告。
  - 依赖：8.5
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Data.psm1`、`scripts/dev-env/lib/DevEnv.Report.psm1`
  - 验收标准：`Imported` 与 `Skipped` 互斥；`KeyRowCounts` 至少包含五张关键表；报告可被 `report` 子命令重建。
  - 权限：P1

---

## 9. 测试隔离修复与 `mvn test`

### 9.1 修复 `DatabaseQueryTest` 与 spec 测试隔离要求的冲突

- [ ] 为 `DatabaseQueryTest` 增加类级条件执行：仅在系统属性 `mysqlIt=true` 时执行，默认整类跳过（使用 JUnit 5 内置 `@EnabledIfSystemProperty`，不新增依赖）；保留原有 6 个查询用例，不删除。
  - 依赖：无（可与其他任务并行，属代码修正）
  - 涉及文件：`project/backend/src/test/java/com/aijob/backend/DatabaseQueryTest.java`
  - 验收标准：不启动 MySQL 时 `mvn test` 不执行该类且构建成功；`mvn test -DmysqlIt=true` 时该类执行；类中不含新增第三方依赖。
  - 权限：P13

### 9.2 确认默认测试集不依赖本机 MySQL

- [ ] 验证默认 `mvn test` 仅执行 `BackendApplicationTest`（H2 内存库、`spring.flyway.enabled=false`），在未启动 Docker 的机器上也能通过；不修改 `BackendApplicationTest` 的 H2 与关闭迁移配置。
  - 依赖：9.1
  - 涉及文件：`project/backend/src/test/java/com/aijob/backend/BackendApplicationTest.java`（只读）、`project/backend/src/test/java/com/aijob/backend/DatabaseQueryTest.java`
  - 验收标准：`docker compose ps` 无运行容器时执行 `mvn test` 得到 `BUILD SUCCESS`，`DatabaseQueryTest` 显示 skipped；日志中无 MySQL 连接尝试。
  - 权限：P13

### 9.3 测试执行器与失败分类

- [ ] 实现 `Invoke-BackendTests`：在 `project/backend` 执行 `mvn test`，默认超时 300s（可用 `-TestTimeoutSeconds` 覆盖），解析 `target/surefire-reports/*.xml` 统计 `Total/Passed/Failed/Errors/Skipped/DurationMs`；失败按编译 71、断言 72、上下文启动 73、其他 70 分类。
  - 依赖：1.2、9.2
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Backend.psm1`
  - 验收标准：成功时输出 `BUILD SUCCESS` 与 `failures=0, errors=0`；人为制造断言失败返回 72 并指出失败用例与位置；首次下载依赖时可用参数放宽超时。
  - 权限：P1

### 9.4 集成测试通道（真实库验证）

- [ ] `-IntegrationTest` 时以 `-DmysqlIt=true` 执行，前置要求容器 `healthy` 且迁移校验通过（任务 7），用于验证 31 张表可查询；该通道失败不影响默认测试结论，但需在报告中单独标注。
  - 依赖：9.3、7.2
  - 涉及文件：`scripts/dev-env/setup.ps1`、`scripts/dev-env/lib/DevEnv.Backend.psm1`
  - 验收标准：容器未健康时明确拒绝执行集成通道并说明前置条件；执行成功时报告记录 31 表可查询。
  - 权限：P12

### 9.5 测试范围声明与 `TestState` 落库

- [ ] 报告固定输出"已验证：H2 上下文启动 / 未验证：真实 MySQL 行为、Redis、服务间联调"，并写入 `TestState`。
  - 依赖：9.3
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Report.psm1`
  - 验收标准：成功与失败路径均包含该声明；`TestState` 计数字段与 Surefire 汇总一致。
  - 权限：P1

---

## 10. 环境回收与回滚

### 10.1 变更日志（Change Journal）

- [ ] 实现 `journal-<runId>.json`：条目含 `RunId`、`Kind`、`Target`、`CreatedByThisRun`、`OccurredAt`、`IsReversible`，只追加、先写临时文件再原子替换；损坏时输出"不可回滚"告警而非猜测。
  - 依赖：1.2
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Rollback.psm1`
  - 验收标准：日志中不含密码与连接串；仅 `CreatedByThisRun=true` 的对象允许 L3/L4 删除；并发写入不产生半截 JSON。
  - 权限：P1

### 10.2 `stop`：只停进程与容器，保留数据

- [ ] 实现 `stop`：按变更日志执行 L1（停后端、释放端口）+ L2（`docker compose stop mysql`），验证端口释放、容器为 `exited`、卷仍存在；默认路径不出现 `down -v`。
  - 依赖：1.6、5.8、6.6、10.1
  - 涉及文件：`scripts/dev-env/setup.ps1`、`scripts/dev-env/lib/DevEnv.Rollback.psm1`
  - 验收标准：停止后 `docker volume ls` 仍含 `mysql_data`；再次 `up -d` 后数据可查；`stop` 重复执行幂等返回 0。
  - 权限：P10、P1

### 10.3 `rollback`：分级回滚

- [ ] 实现 `rollback`：L0–L2 自动执行；L3（删除本次创建的 Conda 环境）需 `-Approve RemoveCondaEnv`，L4（删除本次创建的 `.env`）需 `-Approve RemoveEnvFile`；未批准项跳过并说明替代路径。
  - 依赖：10.2
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Rollback.psm1`、`scripts/dev-env/setup.ps1`
  - 验收标准：默认 `rollback` 不删除环境与 `.env`；仅在对应批准且 `CreatedByThisRun=true` 时删除；每次动作写入 `RollbackAction` 结果。
  - 权限：P9、P10、P1

### 10.4 `purge`：双重确认删除卷

- [ ] 实现 `purge`：需同时提供命令与 `-ConfirmDataLoss <项目名>`（必须与仓库项目名 `AI-JOB` 匹配），缺一返回 90；执行前明确提示"数据不可恢复、仅能依赖外部备份"。
  - 依赖：10.3
  - 涉及文件：`scripts/dev-env/setup.ps1`、`scripts/dev-env/lib/DevEnv.Rollback.psm1`
  - 验收标准：确认串不匹配时拒绝执行；确认串不从环境变量或默认值推断；执行后卷被删除且报告显著标注数据已丢失。
  - 权限：P11

### 10.5 不可回滚项说明

- [ ] 在脚本 README 与报告"未覆盖/不可回滚"章节列出：MySQL DDL 隐式提交、已初始化卷的账号密码、已删除的数据卷、已下载依赖与镜像、Git 代码变更（由 Git 回滚）。
  - 依赖：10.4
  - 涉及文件：`scripts/dev-env/README.md`、`scripts/dev-env/lib/DevEnv.Report.psm1`
  - 验收标准：五类各有一条说明与补救方式；报告中该章节在任何路径下均存在。
  - 权限：P1

### 10.6 回滚自身失败处理

- [ ] 回滚步骤失败时停止后续动作、保留变更日志、输出手工恢复步骤，返回 80；不猜测资源状态、不继续执行更高级别删除。
  - 依赖：10.3
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Rollback.psm1`
  - 验收标准：人为使 `stop` 失败（如容器已不存在）时不抛出未分类异常；返回 80 且输出手册化步骤。
  - 权限：P1

---

## 11. 报告与文档交付

### 11.1 `EnvReport` 与 `report.json`

- [ ] 实现 `EnvReport`：`SchemaVersion`、`RunId`、`GeneratedAt`、`OperatingSystem`、`Tools[]`、`Stages[]`、`DbState`、`BackendState`、`MigrationState`、`DemoState`、`TestState`、`OverallExitCode`、`UncoveredScope[]`；写入 `.local-dev-env/report.json`，stdout 同步输出摘要。
  - 依赖：1.2、5.8、6.4、7.2、8.6、9.5
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Report.psm1`
  - 验收标准：任一执行路径（成功/跳过/失败/阻塞）都生成报告；报告不含密码；摘要包含工具版本、容器状态、后端端口、迁移表数、演示数据状态、测试结论与未覆盖范围。
  - 权限：P1

### 11.2 `status` / `report` 只读快照

- [ ] 实现 `status` 与 `report`：只读采集工具版本、容器状态与健康、卷是否存在、后端端口与进程、报告文件路径；不取锁、不产生副作用，可就地重建摘要。
  - 依赖：11.1
  - 涉及文件：`scripts/dev-env/lib/DevEnv.Report.psm1`、`scripts/dev-env/setup.ps1`
  - 验收标准：执行前后容器与进程状态不变；无报告文件时也能输出当前快照；`report` 可覆盖重建 `report.json`。
  - 权限：P1

### 11.3 `readme.md` 增量章节

- [ ] 在根 `readme.md` 增加："一键脚本用法（PowerShell/`setup.sh`/cmd 三种入口）""权限申请清单（P1–P13）""回滚路径（`stop`/`rollback`/`purge`）""故障排查（按退出码）"；保留原有手工流程作为等效路径。
  - 依赖：1.4、10.5
  - 涉及文件：`readme.md`
  - 验收标准：三种入口各有可复制命令；权限清单与脚本 README 一致；回滚章节明确"默认不删数据卷"。
  - 权限：P13

### 11.4 `project/backend/README.md` 增量

- [ ] 补充测试隔离说明：默认 `mvn test` 只跑 H2 用例；真实库验证使用 `mvn test -DmysqlIt=true` 且需容器 `healthy` 与迁移完成。
  - 依赖：9.1、9.4
  - 涉及文件：`project/backend/README.md`
  - 验收标准：两条命令与 `DatabaseQueryTest` 条件一致；说明未覆盖范围（Redis、联调）。
  - 权限：P13

### 11.5 权限申请单与脱敏自检说明

- [ ] 在 `scripts/dev-env/README.md` 固化"操作—影响—可回退性—未批准降级"四列表格，并说明 `-Approve KillProcess`、`-AllowNonLoopback`、`-RecreatePython`、`-Approve RemoveCondaEnv`、`-Approve RemoveEnvFile`、`-ConfirmDataLoss` 六个确认型参数的用途与默认拒绝语义。
  - 依赖：10.4、11.1
  - 涉及文件：`scripts/dev-env/README.md`
  - 验收标准：六个参数均有默认拒绝示例；说明"最小权限：不整体请求管理员"；脱敏规则（密码、`MYSQL_PWD=`、`--password=`）有明确描述。
  - 权限：P1

---

## 12. 端到端验证与验收

### 12.1 全流程执行

- [ ] 在干净环境执行 `setup.ps1 -Command all`，期望顺序 `check→python→env→db→backend→demo→test→report` 全部通过；记录首次镜像拉取到 `healthy` 的耗时（≤15 分钟）、二次启动到 `healthy`（≤90s）、后端就绪（≤60s）、`mvn test`（依赖已缓存 ≤5 分钟）。
  - 依赖：1–11 全部
  - 涉及文件：`scripts/dev-env/setup.ps1`、`.local-dev-env/report.json`
  - 验收标准：退出码 0；四项耗时均在 spec 4.1 阈值内或在报告中给出超时原因；报告含 31 表与演示数据结论。
  - 权限：P6、P10、P12、P1

### 12.2 幂等与重入验证

- [ ] 连续执行两次 `all`，以及单独重复执行 `python`、`env`、`db`、`backend`、`demo`，确认不重建环境、不覆盖 `.env`、不重建容器、不产生第二个后端进程、演示数据不重复导入。
  - 依赖：12.1
  - 涉及文件：`scripts/dev-env/lib/DevEnv.*`、`.local-dev-env/journal-*.json`
  - 验收标准：第二次运行无破坏性动作；`journal` 中 `CreatedByThisRun` 全为 `false`；`.env` 与卷未变更。
  - 权限：P1、P10

### 12.3 安全验收

- [ ] 校验：日志/报告/控制台无密码明文；`.env` 未被 Git 跟踪；非回环绑定需 `-AllowNonLoopback` 且二次确认；`purge` 缺确认返回 90；不使用 root 账号运行后端；`docker compose config` 展开结果不入日志。
  - 依赖：12.1
  - 涉及文件：`.local-dev-env/report.json`、`scripts/dev-env/lib/DevEnv.Core.psm1`、`application.yml`
  - 验收标准：对日志与报告做关键词扫描（两个密码值）零命中；`git status` 无 `.env`；五个安全项逐条记录通过。
  - 权限：P1、P8、P11

### 12.4 失败注入验证

- [ ] 按清单注入故障并核对退出码与提示：Conda 不在 PATH→20；JDK 17→12；`mvn -version` Java 不一致→13；Docker Desktop 未启动→14；占位密码→31；两密码相同→31；`.env` 带 BOM→32；3307 被占用→42；健康超时→43；认证失败→44；8080 占用→52；迁移校验失败→51；演示数据重复→0（跳过）。
  - 依赖：12.1
  - 涉及文件：`scripts/dev-env/lib/DevEnv.*`、报告文件
  - 验收标准：13 个场景退出码与设计一致，且失败后自动执行 L1/L2、不删卷；每场景有整改建议文本。
  - 权限：P7、P10、P12、P4

### 12.5 脚本自检与 `check -DryRun` 冒烟

- [ ] 以 `setup.ps1 -Command check -DryRun` 作为冒烟入口，执行纯 PowerShell 断言：脱敏替换、版本解析、`.env` 规则判定、退出码映射、`Write-Utf8NoBom` 无 BOM。
  - 依赖：12.1
  - 涉及文件：`scripts/dev-env/setup.ps1`、`scripts/dev-env/lib/DevEnv.Core.psm1`
  - 验收标准：自检全部通过且耗时 < 30s；不依赖 Pester；失败时明确指出断言名与期望值。
  - 权限：P1

### 12.6 验收映射核对与结论输出

- [ ] 对照 design 2.6"验证与验收映射"逐行核对 10 项判据（前置核验、Conda、`.env`、数据库、后端与迁移、演示数据、后端测试、回收、报告、安全），输出验收结论表与未覆盖范围。
  - 依赖：12.1–12.5
  - 涉及文件：`.local-dev-env/report.json`、`scripts/dev-env/README.md`
  - 验收标准：10 项逐条标注"通过/不通过/未执行"及证据位置；未覆盖范围至少包含真实 MySQL 行为以外的 Redis、Python Agent、Qdrant、前端、生产部署、CI/CD。
  - 权限：P1

---

## 交付完成定义（DoD）

1. 任务组 1–11 全部勾选完成，任务组 12 的 6 项验收全部记录结论。
2. 代码修正落地且经评审：`application.yml:5` 导入串修正、`DatabaseQueryTest` 条件执行、`.gitignore` 新增 `.local-dev-env/`。
3. `setup.ps1 -Command all` 在干净 Windows 环境一次通过，`report.json` 含全部阶段结论与未覆盖范围。
4. 无密码明文外泄；`purge` 未被执行或执行前完成双重确认；数据卷在默认路径下始终保留。
---

## 执行记录（2026-11-02）

### 已落地

| 类别 | 文件 |
| --- | --- |
| 编排入口 | `scripts/dev-env/setup.ps1`、`scripts/dev-env/setup.sh`、`scripts/dev-env/README.md` |
| 能力模块 | `scripts/dev-env/lib/DevEnv.Core.psm1`、`DevEnv.Preflight.psm1`、`DevEnv.Python.psm1`、`DevEnv.Docker.psm1`、`DevEnv.Backend.psm1`、`DevEnv.Data.psm1`、`DevEnv.Rollback.psm1`、`DevEnv.Report.psm1` |
| 自检工具 | `scripts/dev-env/tools/Test-Syntax.ps1`、`Set-Utf8Bom.ps1` |
| 代码修正 | `application.yml:5`、`DatabaseQueryTest.java`、`.gitignore` |
| 文档增量 | `readme.md` 第 7 节、`project/backend/README.md` 测试隔离 |

### 本地验证结果

| 验证项 | 结果 |
| --- | --- |
| PowerShell 语法解析 | 12 个脚本全部 OK |
| `check -DryRun` 核心断言 | 6/6 通过 |
| `check -DryRun` 版本解析断言 | 5/5 通过 |
| `check -DryRun` 退出码 | 12（JDK 17，符合预期） |
| `env -NonInteractive` 首次执行 | 创建 `.env`，退出码 30 |
| `env -NonInteractive` 二次执行 | `.env` 哈希不变，退出码 30 |
| `status` / `stop` | 退出码 0，不启动资源 |
| `.gitignore` 校验 | `.env`、`.local-dev-env/report.json` 命中；`.env.example` 不命中 |
| `application.yml` 污染残留 | 无 `mvn spring-boot:run` 残留 |
| 报告脱敏 | `report.json` 中密码/占位值命中数为 0 |

### 阻塞项（需要授权后继续）

| 阻塞项 | 现状 | 需要的权限 |
| --- | --- | --- |
| JDK 21 | 本机为 17.0.13 | P3/P4 |
| Docker | 未安装（`docker` 不在 PATH） | P3/P5/P6/P10 |
| `.env` 密码 | 仍为模板占位值 | P1（用户提供或交互录入） |
| `ai-job` Conda 环境 | 尚未创建/安装依赖 | P9 |
| 全流程 12.1–12.6 | 依赖上述环境，尚未执行 | P6/P10/P12 |

### 未验证范围

真实 MySQL 建表与查询、演示数据导入、后端启动、`mvn test`、完整故障注入与端到端验收，均需在 JDK 21 与 Docker 就绪后执行。