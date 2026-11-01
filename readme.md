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
docker compose up -d
docker compose ps
```

第一次运行会下载 MySQL 8.0.42、Redis 7.4 和 Mailpit 镜像，等待 `mysql`、`redis` 状态变为 `healthy`。Compose 会创建 `career_business` 数据库和持久化卷，但**不会自动创建业务表**；业务表由下一步启动后端时的 Flyway 创建。Mailpit 的 Web 收件箱在 `http://localhost:8025`。

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

默认后端端口为 `8080`。终端出现 `Started BackendApplication` 表示服务已启动；按 `Ctrl+C` 停止。登录、注册、刷新、退出、领取 CSRF、密码找回以及 `GET/PATCH /api/v1/users/me` 已实现（JWT accessToken + HttpOnly 刷新 Cookie 轮换 + CSRF 双提交 + Redis 限流与撤销）；Agent 通信尚未实现。

`docker compose up -d` 会同时启动 MySQL、Redis（6379）和 Mailpit（SMTP 1025 / Web 8025）。Python Agent、Qdrant 和前端不由该 Compose 启动，前端按下一节在本机运行。

## 5. 启动前端

```powershell
cd project\frontend
npm install
npm run dev
```

前端使用 Vue 3 + TypeScript + Vite，dev server 监听 `http://localhost:5173`，并将 `/api` 代理到 `http://localhost:8080`。请用 `localhost` 而不是 `127.0.0.1` 访问页面：后端刷新 Cookie 为 host-only，混用两个主机名会导致刷新令牌不被发送。

密码找回邮件可在 Mailpit 收件箱 `http://localhost:8025` 查看。若要在演示时跳过邮箱，可在 `.env` 中设置 `APP_MAIL_EXPOSE_CODE=true`，验证码会同时写入后端日志。

## 6. 演示数据与协作约定

表结构创建成功后，如需统一的测试样本，可在**空的开发库**手动导入 `project/backend/src/main/resources/db/demo/demo_data.sql`。例如已安装 MySQL 命令行客户端时，在项目根目录执行：

```powershell
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3307 -u aijob -p career_business -e "source project/backend/src/main/resources/db/demo/demo_data.sql"
```

输入自己 `.env` 中的 `MYSQL_PASSWORD`；如果主机、端口或用户名不同，替换命令中的对应值。脚本只用于开发库，不会自动运行，也不是可重复导入脚本。演示账号已禁用，岗位和公司均为虚构；登录演示请直接调用注册接口创建账号。

团队通过 Git 同步 Compose、依赖文件和 Flyway 迁移；各自的 Docker 数据不会自动同步。共享同一批数据时，应提前约定演示数据或使用专门的共享联调库。Flyway 的 `V1` 执行后不要直接修改；表结构调整应新增版本迁移，并由团队协调版本号。

## 7. 后端测试

在 `project/backend` 目录执行：

```powershell
mvn test
```

`AuthFlowIntegrationTest` 使用真实 MySQL、Redis 与 Mailpit，覆盖注册、重复邮箱、弱密码、登录失败、当前用户、改昵称、刷新轮换、重放吊销、错误 CSRF、退出、限流、验证码重置与账号枚举防护。首次运行前需创建独立测试库并授权：

```powershell
docker exec ai-job-mysql-1 mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS career_business_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT ALL PRIVILEGES ON career_business_test.* TO 'aijob'@'%'; FLUSH PRIVILEGES;"
```

只跑启动测试（不依赖数据库）：

```powershell
mvn -Dtest=BackendApplicationTest test
```

`DatabaseQueryTest` 直连 `career_business`，需要已导入演示数据。更多后端说明见 `project/backend/README.md`。
