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

目前依赖文件中的直接依赖固定了版本，间接依赖没有完全锁定；Python Agent 位于 `project/agent`，需要独立启动。

## 3. 使用 Docker 配置 MySQL

### 3.1 使用项目配置

确保 Docker Desktop 已启动。首次使用时，在项目根目录将 `.env.example` 复制为 `.env`；如果 `.env` 已存在，保留已有配置：

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

编辑 `.env`，设置自己的 `MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`REDIS_PASSWORD` 和足够长的 `APP_JWT_SECRET`，不要沿用模板中的占位值。

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

| 配置项 | 本机默认值                                |
| ------ | ----------------------------------------- |
| 主机   | `127.0.0.1`                             |
| 端口   | `3307`，或 `.env` 中的 `MYSQL_PORT` |
| 数据库 | `career_business`                       |
| 用户名 | `.env` 中的 `MYSQL_USER`              |
| 密码   | `.env` 中的 `MYSQL_PASSWORD`          |

以上主机和端口须与本机 `.env` 配置一致。例如已有环境若绑定了局域网 IP，应使用该 IP 而不是 `127.0.0.1`。

### 3.3 常用命令

在项目根目录执行：

```powershell
docker compose stop mysql   # 停止数据库
docker compose up -d mysql  # 再次启动
docker compose logs mysql   # 查看启动日志
```

数据保存在 Docker 命名卷中，普通停止或重建容器不会自动删除数据。不要使用 `docker compose down -v`：该命令会删除项目持久化卷及其中的数据。命名卷不等于备份，重要数据仍需另行备份。

### 3.4 启动 Redis

项目 Compose 提供带密码的 Redis，默认仅绑定 `127.0.0.1:6379`。在 `.env` 中设置 `REDIS_PASSWORD`；若端口被占用，可修改 `REDIS_PORT`。

在项目根目录执行：

```powershell
docker compose up -d --wait mysql redis
docker compose ps
docker compose exec -T redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ping'
```

两个容器均应显示 `healthy`，Redis 检查应返回 `PONG`。Redis 启用 AOF 持久化，数据保存在 `redis_data` 命名卷中。停止和再次启动可分别执行：

```powershell
docker compose stop redis
docker compose up -d --wait redis
```

Java 后端在宿主机运行时使用上述本机地址和 `.env` 中的 `REDIS_PASSWORD`；若后端位于 Compose 网络，主机应改为 `redis`。

## 4. 启动 Java 基础后端

确认 MySQL 和 Redis 容器已健康启动，然后从项目根目录进入后端目录：

```powershell
cd project\backend
mvn spring-boot:run
```

从 `project/backend` 或项目根目录作为工作目录启动时，Spring Boot 会读取项目根目录的 `.env`，自动连接对应 MySQL。首次启动时，Flyway 会执行 `src/main/resources/db/migration/V1__init_business_schema.sql`，创建 Java 业务库的 31 张表及 Flyway 迁移记录表。

默认后端端口为 `8080`。已接入登录注册、JWT 认证、画像、投递进度和 Agent 任务等业务；启动时 Flyway 会按顺序执行未应用的迁移。V6 会将已有零版本画像更新为版本 1，已缓存旧版本的客户端需要刷新。

Compose 提供 MySQL、Redis 和 Mailpit；`docker compose up -d` 启动这三个服务。Mailpit 的本地收件箱默认在 `http://localhost:8025`，Python Agent、Qdrant 和前端需单独启动。

前端：在 `project/frontend` 目录执行 `npm ci` 和 `npm run dev`。Agent：激活 `ai-job` 环境后在项目根目录执行 `python project/agent/run.py`。

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

`BackendApplicationTest` 使用 H2；`DatabaseQueryTest` 需要真实 MySQL 和演示数据。投递与登录集成测试需要独立 `career_business_test` 数据库，登录测试还需要隔离 Redis 与 SMTP 测试服务；投递测试可用 `-Dintegration-tests=true` 强制执行。测试会写入测试库和 Redis，请勿指向共享或生产服务。

### 官网优先搜索与匿名渲染

默认来源增加 `company_official`：企业官网/企业委托 ATS 优先，第三方仅补缺或兜底。官网归属需要企业入口链接或可信配置，不能仅凭网站名称确认。可设置 `AGENT_JOB_SEARCH_COMPANY_SITES` 为 JSON 对象（公司名称到完整招聘域名的映射），例如 `{"示例有限公司":"careers.example.com"}`。

安装根目录 requirements.txt 后，使用同一 Python 环境执行 `python -m playwright install chromium`。渲染不读取个人浏览器或登录态，单次搜索最多 4 页、并发 2、每页 10 秒；总预算默认 150 秒，取数预留最后 30 秒分析收尾。浏览器不可用时继续返回静态结果及未确认状态。

字段 `companySizeText` 和 `fieldSources` 存入既有岗位 JSON 快照，无数据库迁移；旧搜索记录不会自动补写。字段缺失需要重新搜索。公开站点可能只返回空壳、登录页或验证码，不能保证所有字段可得。真实核对结果见 `project/agent/data/verify_official_priority_20261107.json`。
