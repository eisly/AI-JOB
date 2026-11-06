# AI-JOB 本地开发环境

本文以 **Windows PowerShell** 为例

## 1. 前置条件

- 已安装 Conda，并能在终端执行 `conda --version`。
- 已安装并启动 Docker Desktop，能执行 `docker compose version`。
- 已安装 JDK 21 和 Maven，能分别执行 `java -version` 和 `mvn -version`。Maven 使用的 JDK 可通过 `mvn -version` 确认。
- 下载项目代码，打开 PowerShell 并进入**自己的项目根目录**，例如：

```powershell
cd D:\py\AI-JOB
```

下面的路径和命令，除特别说明外，均相对于项目根目录。Conda 环境用于 Python AI 服务；Java 后端使用 JDK 和 Maven。

## 2. 配置 Conda 环境

项目根目录的 `requirements.txt` 是 Python AI 服务的依赖清单。首次配置：

```powershell
conda create -n ai-job python=3.11 -y
conda env config vars set -n ai-job PYTHONNOUSERSITE=1
conda activate ai-job
python -m pip install -r requirements.txt
python -m pip check
```

`PYTHONNOUSERSITE=1` 用来阻止 Python 加载用户目录中其他项目安装的包，避免依赖版本冲突。`pip check` 显示 `No broken requirements found.` 表示当前环境已安装包之间没有声明的依赖冲突。

如果 `ai-job` 环境已经存在，跳过 `conda create`。若是在该环境已激活时才设置 `PYTHONNOUSERSITE`，执行 `conda deactivate` 后重新 `conda activate ai-job`，再安装和检查。

目前依赖文件中的**直接依赖**固定了版本，间接依赖没有完全锁定；完成安装也不代表 AI 服务已实现或启动。

## 3. 使用 Docker 配置 MySQL

### 3.1 使用项目配置

确保 Docker Desktop 已启动。首次使用时，在项目根目录将 `.env.example` 复制为 `.env`；如果 `.env` 已存在，保留已有配置：

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

编辑 `.env`，设置自己的 `MYSQL_PASSWORD` 和 `MYSQL_ROOT_PASSWORD`，不要沿用模板中的占位值。

默认绑定本机地址：

```dotenv
MYSQL_BIND_ADDRESS=127.0.0.1
```

`127.0.0.1` 表示 MySQL 只监听本机；如果 3307 已被占用，可以在**首次启动前**把 `MYSQL_PORT` 改为其他空闲端口。`.env` 包含密码，已加入 Git 忽略列表，不要提交或将其用于生产环境。

MySQL 用户和密码只在**全新数据卷的首次初始化**时创建；后续单独修改 `.env` 不会自动更改已存在的数据库账号密码，应在数据库中修改并同步更新配置。

### 3.2 启动并检查

```powershell
docker compose up -d mysql
docker compose ps
```

第一次运行会下载 MySQL 8.0.42 镜像，等待状态变为 `healthy`。Compose 会创建 `career_business` 数据库和持久化卷，但**不会自动创建业务表**；业务表由下一步启动后端时的 Flyway 创建。

使用 IDEA、DataGrip 或 Navicat 连接时选择普通 **MySQL** 驱动，填写：

| 配置项 | 本机默认值 |
| --- | --- |
| 主机 | `127.0.0.1` |
| 端口 | `3307`，或 `.env` 中的 `MYSQL_PORT` |
| 数据库 | `career_business` |
| 用户名 | `.env` 中的 `MYSQL_USER` |
| 密码 | `.env` 中的 `MYSQL_PASSWORD` |

以上主机和端口须与本机 `.env` 配置一致。例如已有环境若绑定了局域网 IP，应使用该 IP 而不是 `127.0.0.1`。

### 3.3 常用命令

在项目根目录执行：

```powershell
docker compose stop mysql   # 停止数据库
docker compose up -d mysql  # 再次启动
docker compose logs mysql   # 查看启动日志
```

数据保存在 Docker 命名卷中，普通停止或重建容器不会自动删除数据。不要使用 `docker compose down -v`：该命令会删除项目持久化卷及其中的数据。命名卷不等于备份，重要数据仍需另行备份。

## 4. 启动 Java 基础后端

确认 MySQL 容器已健康启动，然后从项目根目录进入后端目录：

```powershell
cd project\backend
mvn spring-boot:run
```

从 `project/backend` 或项目根目录作为工作目录启动时，Spring Boot 会读取项目根目录的 `.env`，自动连接对应 MySQL。首次启动时，Flyway 会执行 `src/main/resources/db/migration/V1__init_business_schema.sql`，创建 Java 业务库的 31 张表及 Flyway 迁移记录表。

默认后端端口为 `8080`。终端出现 `Started BackendApplication` 表示基础服务已启动；按 `Ctrl+C` 停止。当前项目只有工程骨架和依赖配置，业务接口、正式 JWT 登录认证及 Agent 通信尚未实现。运行时提供的是 Spring Security 默认保护，不等同于项目设计中的登录功能。

当前 Compose **只启动 MySQL**；后端的 Redis 配置默认为 `localhost:6379`，需要开发相关功能时另行准备 Redis。Python Agent、Qdrant 和前端也不会由该 Compose 自动启动。

### 4.1 启动前端与求职工作台

工作台是前端只读聚合页，数据来自画像、岗位匹配、学习计划、模拟面试、成长中心、任务中心与简历等既有接口，不新增后端聚合接口。

```powershell
cd project\frontend
npm ci
npm run dev
```

后端九个只读接口尚未实现、登录鉴权也未落地时，可在开发期启用 Mock：

1. 复制 `project/frontend/.env.example` 为 `project/frontend/.env.development.local`。
2. 设置 `VITE_WORKBENCH_MOCK=true`。
3. 通过 `VITE_WORKBENCH_MOCK_SCENARIO` 选择场景：`empty`（新用户空数据）、`normal`（完整数据）、`partial-error`（任务与岗位服务失败）。

Mock 仅在开发环境且显式开启时加载，生产构建不会启用。工作台常用命令：

```powershell
npm run type-check       # 类型检查
npm run verify:models    # 聚合规则验证（下一项任务/岗位/进度/动态/时间）
```

切换到真实接口前需具备：`/api/v1/users/me`、`/api/v1/profile`、`/api/v1/job-matches`、`/api/v1/job-matches/{id}`、`/api/v1/learning/plans`、`/api/v1/interviews`、`/api/v1/growth`、`/api/v1/tasks`、`/api/v1/resumes` 九个只读接口，以及登录态与 `CommonResult.traceId` 填充。投递进度在 P0 未开放，工作台只展示“投递进度功能未开放”，不调用投递接口。

## 5. 演示数据与协作约定

表结构创建成功后，如需统一的测试样本，可在**空的开发库**手动导入 `project/backend/src/main/resources/db/demo/demo_data.sql`。例如已安装 MySQL 命令行客户端时，在项目根目录执行：

```powershell
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3307 -u aijob -p career_business -e "source project/backend/src/main/resources/db/demo/demo_data.sql"
```

输入自己 `.env` 中的 `MYSQL_PASSWORD`；如果主机、端口或用户名不同，替换命令中的对应值。脚本只用于开发库，不会自动运行，也不是可重复导入脚本。演示账号已禁用，岗位和公司均为虚构。

团队通过 Git 同步 Compose、依赖文件和 Flyway 迁移；各自的 Docker 数据不会自动同步。共享同一批数据时，应提前约定演示数据或使用专门的共享联调库。Flyway 的 `V1` 执行后不要直接修改；表结构调整应新增版本迁移，并由团队协调版本号。

## 6. 后端测试

在 `project/backend` 目录执行：

```powershell
mvn test
```

当前启动测试使用 H2 内存数据库并关闭 Flyway，不会验证真实 MySQL、Redis 或服务间联调。更多后端说明见 `project/backend/README.md`。
## 7. 一键脚本（可选）

手工命令仍可单独使用；脚本按本文步骤编排，并输出 `.local-dev-env/report.json`。详细说明、退出码与权限申请单见 `scripts/dev-env/README.md`。

```powershell
# PowerShell：先只读核验，再全流程
.\scripts\dev-env\setup.ps1 -Command check -DryRun
.\scripts\dev-env\setup.ps1 -Command all

# 只做局部阶段
.\scripts\dev-env\setup.ps1 -Command check
.\scripts\dev-env\setup.ps1 -Command env
.\scripts\dev-env\setup.ps1 -Command db
.\scripts\dev-env\setup.ps1 -Command backend
.\scripts\dev-env\setup.ps1 -Command test
```

```bash
# git-bash：等效调用，退出码原样透传
./scripts/dev-env/setup.sh all
echo $?
```

```bat
:: cmd：等效调用
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\dev-env\setup.ps1 -Command all
```

**权限申请清单（P1–P13）**：P1 项目目录读写、P2 进程级执行策略豁免、P3 安装软件、P4 修改系统 `JAVA_HOME`/`PATH`、P5 启动 Docker Desktop、P6 拉取镜像、P7 结束占用端口的进程、P8 绑定非回环地址、P9 创建/删除 Conda 环境、P10 创建/停止容器与卷、P11 删除数据卷、P12 执行数据库 DDL/DML、P13 修改仓库代码。脚本默认只申请当前阶段必需的权限，不整体请求管理员权限。

**回滚路径**：

```powershell
.\scripts\dev-env\setup.ps1 -Command stop       # 停后端与 MySQL，保留数据卷
.\scripts\dev-env\setup.ps1 -Command rollback   # 分级回滚；默认不删环境、.env 和数据卷
.\scripts\dev-env\setup.ps1 -Command purge -ConfirmDataLoss AI-JOB   # 删除数据卷，数据不可恢复
```

默认流程不会执行 `docker compose down -v`。`.env`、`.local-dev-env/` 均已加入 Git 忽略列表，不要把密码或运行期产物提交到仓库。

**故障排查**：按退出码定位——11 工作目录错误、12 JDK 不是 21、13 Maven 使用的 JDK 不一致、14 Docker 不可用、20 Conda 不可用、31 `.env` 规则不通过、32 `.env` 编码/重复键、42 端口占用、43 数据库健康超时、44 数据库认证失败、51 Flyway 迁移失败、70–73 测试失败。完整表见 `scripts/dev-env/README.md`。
