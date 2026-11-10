# AI-JOB 本地开发环境编排

本目录提供按 `readme.md` 复现本地开发环境的一键脚本。手工命令仍可单独使用，脚本只负责编排、判定与报告，不修改 `readme.md` 描述的业务范围。

## 1. 范围与边界

- 覆盖：前置核验、Conda/Python 依赖、`.env` 初始化与校验、Docker MySQL、后端启动与 Flyway 建表、演示数据、`mvn test`、报告与回收。
- 不启动：Redis、Qdrant、Python Agent、前端。
- 不实现：业务接口、JWT 登录认证、Agent 通信。
- 默认永不执行 `docker compose down -v`；删除数据卷只能通过 `purge` 且需要双重确认。

## 2. 三种入口

```powershell
# PowerShell
.\scripts\dev-env\setup.ps1 -Command all -LogLevel normal
.\scripts\dev-env\setup.ps1 -Command check -DryRun
```

```bash
# git-bash
./scripts/dev-env/setup.sh all
./scripts/dev-env/setup.sh check
echo $?
```

```bat
:: cmd
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\dev-env\setup.ps1 -Command all
```

运行期产物写入 `.local-dev-env/`（已加入 `.gitignore`）：日志、`report.json`、变更日志、锁文件。

## 3. 子命令

| 子命令 | 作用 | 是否产生副作用 |
| --- | --- | --- |
| `check` | 核验工具版本、文件、Docker 与端口 | 只读（仅可新增日志） |
| `python` | 创建/复用 `ai-job` 环境并安装依赖 | 是（Conda） |
| `env` | 生成或校验 `.env`，交互录入密码 | 是（写 `.env`） |
| `db` | 启动 MySQL 并等待 `healthy`，`SELECT 1` | 是（容器/卷） |
| `backend` | 启动后端并校验 Flyway 31 张表 | 是（进程/数据库） |
| `demo` | 向空库导入演示数据 | 是（DML） |
| `test` | 执行 `mvn test`（默认 H2；`-IntegrationTest` 走真实库） | 是（构建产物） |
| `all` | 依次执行 `check→env→python→db→backend→demo→test` | 是 |
| `status` | 输出当前环境快照 | 只读 |
| `report` | 重建 `report.json` | 写报告 |
| `stop` | 停止后端与 MySQL，保留数据卷 | 停止资源 |
| `rollback` | 分级回滚本次运行创建的资源 | 按级别 |
| `purge` | 删除容器与数据卷（双重确认） | **数据不可恢复** |

常用参数：`-ProjectRoot`、`-NonInteractive`、`-SkipDemo`、`-IntegrationTest`、`-RecreatePython`、`-AllowNonLoopback`、`-BackendTimeoutSeconds`、`-DbTimeoutSeconds`、`-TestTimeoutSeconds`、`-ReportPath`、`-LogLevel`、`-DryRun`。

## 4. 退出码

| 码 | 名称 | 含义 |
| --- | --- | --- |
| 0 | Success | 成功（含跳过） |
| 2 | InvalidArgument | 参数非法 |
| 10 | PrerequisiteMissing | 必需工具缺失 |
| 11 | WrongDirectory | 工作目录或必需文件缺失 |
| 12 | JdkIncompatible | JDK 缺失或主版本非 21 |
| 13 | MavenIncompatible | Maven 缺失或所用 JDK 不一致 |
| 14 | DockerUnavailable | Docker 客户端/守护进程不可用 |
| 20 | CondaUnavailable | Conda 不可用 |
| 21 | PythonVersionMismatch | 环境存在但非 3.11 |
| 22 | DependencyInstallFailed | 依赖安装失败 |
| 23 | DependencyConflict | `pip check` 冲突 |
| 30 | EnvFileMissing | `.env` 缺失或未完成配置 |
| 31 | EnvFileInvalid | 规则 E2–E7、E10 不通过 |
| 32 | EnvFileEncodingInvalid | BOM/重复键/解析异常 |
| 40 | ImagePullFailed | 镜像拉取失败 |
| 41 | ContainerStartFailed | 容器创建/启动失败 |
| 42 | PortInUse | 端口被占用 |
| 43 | DbHealthTimeout | 健康检查超时 |
| 44 | DbAuthFailed | 数据库认证失败 |
| 50 | BackendStartTimeout | 后端未在时限内就绪 |
| 51 | MigrationFailed | 迁移失败或表数不符 |
| 52 | BackendDbUnreachable | 连接/认证/端口类启动失败 |
| 60 | DemoTableMissing | 业务表未创建 |
| 61 | DemoDbNotEmpty | 非空库（默认不导入） |
| 62 | DemoImportFailed | 演示数据导入失败 |
| 63 | DemoCharsetMismatch | 中文抽样校验失败 |
| 70 | TestFailed | 测试失败 |
| 71 | TestCompileError | 编译失败 |
| 72 | TestAssertionFailed | 断言失败 |
| 73 | TestContextFailed | Spring 上下文失败 |
| 80 | RollbackFailed | 回滚本身失败 |
| 90 | ConfirmationRequired | 缺少破坏性操作确认 |
| 99 | InternalError | 未分类内部错误 |

## 5. 权限申请单

| 编号 | 操作 | 权限性质与影响 | 可回退性 | 未批准时的降级 |
| --- | --- | --- | --- | --- |
| P1 | 项目目录读写（`.env`、日志、报告、锁） | 写入运行期产物 | 可删除产物 | 中止（退出码 30） |
| P2 | 进程级 `-ExecutionPolicy Bypass` | 仅当前进程，不改系统策略 | 无需回退 | 改用 `cmd -File` 或手工流程 |
| P3 | 安装 Conda/JDK 21/Maven/Docker Desktop | 修改系统软件 | 需手工卸载 | 中止并保留现状 |
| P4 | 修改系统 `JAVA_HOME`/`PATH` | 影响所有终端 | 可改回 | 仅提供会话级临时命令 |
| P5 | 启动 Docker Desktop | 启动本机服务 | 可关闭 | 提示手工启动 |
| P6 | 拉取 `mysql:8.0.42` 镜像 | 网络出站与磁盘 | 可删除镜像 | 中止（退出码 40） |
| P7 | 结束占用 3307/8080 的进程 | 可能影响其他程序，需管理员 | 不可恢复进程 | 建议改端口 |
| P8 | 绑定非回环地址 | 可能被防火墙拦截 | 改回 `127.0.0.1` | 强制回环 |
| P9 | 创建/删除 Conda 环境 | 占用磁盘 | 需重新安装 | 复用现有环境或中止 |
| P10 | 创建/停止容器与卷 | 占用磁盘与端口 | 可停止/删除 | 中止 |
| P11 | 删除数据卷（`purge`） | **数据不可恢复** | 仅外部备份 | 拒绝执行（退出码 90） |
| P12 | 执行数据库 DDL/DML | 修改开发库 | 仅外部备份 | 中止（退出码 44/62） |
| P13 | 修改仓库代码/文档 | 影响 Git 工作区 | `git checkout` | 走代码评审 |

最小权限原则：脚本不会整体请求管理员权限；只在对应场景单独申请。

## 6. 六个确认型参数（默认拒绝）

| 参数 | 用途 | 默认行为 |
| --- | --- | --- |
| `-Approve KillProcess` | 允许结束占用端口的非本项目进程 | 拒绝，只建议改端口 |
| `-AllowNonLoopback` | 允许 `MYSQL_BIND_ADDRESS` 非回环 | 拒绝，要求回环地址 |
| `-RecreatePython` | 删除并重建 `ai-job` 环境 | 拒绝，复用现有环境 |
| `-Approve RemoveCondaEnv` | rollback 时删除本次创建的 Conda 环境 | 拒绝，保留环境 |
| `-Approve RemoveEnvFile` | rollback 时删除本次创建的 `.env` | 拒绝，保留 `.env` |
| `-ConfirmDataLoss AI-JOB` | purge 删除数据卷 | 拒绝，退出码 90 |

示例：

```powershell
.\scripts\dev-env\setup.ps1 -Command purge -ConfirmDataLoss WRONG   # 退出码 90
.\scripts\dev-env\setup.ps1 -Command purge -ConfirmDataLoss AI-JOB   # 数据不可恢复
```

## 7. 回滚路径

| 命令 | 级别 | 行为 |
| --- | --- | --- |
| `stop` | L1 + L2 | 停止后端、停止 MySQL，保留数据卷 |
| `rollback` | L0–L4 | 自动 L1/L2；L3/L4 仅在批准且本次创建时执行 |
| `purge` | L5 | 删除容器与数据卷，需 `-ConfirmDataLoss AI-JOB` |

不可回滚项：MySQL DDL 隐式提交、已初始化卷的账号密码、已删除的数据卷、已下载依赖与镜像、Git 代码变更（由 Git 回滚）。

## 8. 故障排查

| 现象 | 退出码 | 处理 |
| --- | --- | --- |
| 目录错误 | 11 | 在项目根执行（需 `.env.example`、`docker-compose.yml`、`requirements.txt`、`pom.xml`） |
| JDK 不是 21 | 12 | 安装 JDK 21，设置 `JAVA_HOME`，确认 `java -version` |
| Maven 使用的 Java 不一致 | 13 | 调整 `JAVA_HOME` 与 `PATH`，确认 `mvn -version` |
| Docker 不可达 | 14 | 启动 Docker Desktop，执行 `docker info` |
| Conda 不在 PATH | 20 | 安装 Conda 或将 `condabin` 加入 PATH |
| 密码为占位值/两密码相同 | 31 | 修改 `.env`，密码至少 12 位且仅含 `A-Za-z0-9-_@.` |
| `.env` 带 BOM/重复键 | 32 | 保存为 UTF-8 无 BOM，删除重复键 |
| 端口占用 | 42 | 首次启动前修改 `MYSQL_PORT`，或释放 8080 |
| 健康检查超时 | 43 | `docker compose logs --tail=200 mysql`，失败后脚本自动 L2 停止容器 |
| 认证失败 | 44 | 先核对库内密码，再核对 `.env`；已有卷改 `.env` 不会改密 |
| 后端未就绪 | 50/52 | 查看 `.local-dev-env/logs/backend-*.log` |
| 迁移失败 | 51 | 不要修改已执行的 V1，新增迁移版本 |
| 测试失败 | 70–73 | 查看 `target/surefire-reports` |

## 9. 脱敏规则

- `.env` 中的两个密码在上下文初始化时加入脱敏集合。
- 外部命令输出、异常消息、报告写入前统一替换为 `<redacted>`。
- 额外屏蔽 `MYSQL_PWD=`、`--password=`、`-p <value>`。
- `.env` 内容不会被整体打印；报告只保留"已配置"布尔态。
- `docker compose config` 的展开结果不写入日志。