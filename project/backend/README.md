# AI-JOB Java 业务后端

基于 JDK 21、Spring Boot 4、MyBatis 的 Maven 工程，已引入 Spring MVC、WebClient、Security/JWT、MySQL、Redis 和 Flyway。依赖配置不代表登录认证、业务接口或 Agent 通信已经实现。

## 运行

准备 MySQL 8，创建 `career_business` 数据库，并启动 Redis。数据库可以通过 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 覆盖配置；Redis 可通过 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD` 配置，服务端口通过 `SERVER_PORT` 配置。`.env` 仅保存在本地，不要提交密码。

项目根目录提供 `docker-compose.yml` 和 `.env.example`；首次使用时复制模板为 `.env` 并设置自己的 MySQL 密码，执行 `docker compose up -d --wait mysql redis` 可启动持久化 MySQL 和 Redis。首次初始化后的 MySQL 账号、密码由数据库保存，修改 `.env` 不会自动修改数据库账号密码。`MYSQL_BIND_ADDRESS` 默认为 `127.0.0.1`，MySQL 主机端口默认 3307。勿向公网直接暴露数据库。

Compose Redis 使用固定版本 `7.4.12-alpine`，默认仅绑定 `127.0.0.1:6379`，本地开发不启用密码；数据通过 AOF 和 `redis_data` 命名卷持久化。`REDIS_PORT` 可同时调整 Compose 映射端口和本机后端连接端口。执行 `docker compose exec -T redis redis-cli ping` 应返回 `PONG`。`REDIS_PASSWORD` 是后端客户端配置，单独设置它不会给 Compose Redis 开启认证。共享或生产环境需要另行配置认证。

从项目根目录或 `project/backend` 启动时，Spring Boot 会自动读取项目根目录的 `.env`，用于连接本机 Docker MySQL。无需手动复制密码到 `application.yml`。在 `project/backend` 目录执行：

```powershell
mvn spring-boot:run
```

每位成员在本机启动独立 MySQL 和 Redis，使用相同配置，但数据不会自动同步。团队应协调 Flyway 版本，避免产生冲突的表结构。关闭容器可执行 `docker compose stop mysql redis`，再次启动使用 `docker compose up -d --wait mysql redis`；不要使用 `docker compose down -v`，它会删除持久化卷。

首次启动需要数据库存在；Flyway 自动执行 `src/main/resources/db/migration/V1__init_business_schema.sql`，创建 Java 业务库的 P0 表。脚本不创建 Python Agent 独占的运行库。应用数据库账号需要具备迁移所需的建表和修改表权限；已有非空数据库不要直接当作新库执行，应先确认迁移策略。

演示数据在 `src/main/resources/db/demo/demo_data.sql`，仅供空的开发库手动导入，不随应用启动自动执行：

```powershell
mysql --default-character-set=utf8mb4 -u 你的数据库用户 -p career_business -e "source src/main/resources/db/demo/demo_data.sql"
```

演示账号已禁用，岗位和公司均为虚构；脚本不是可重复导入的迁移，不要在生产环境运行。

使用 `mvn test` 运行测试。`BackendApplicationTest` 使用内存 H2 并关闭 Flyway；`DatabaseQueryTest` 连接配置的真实 MySQL，要求先完成 Flyway 建表并准备演示数据。投递进度集成测试连接独立的 `career_business_test`，需提前创建数据库并授予当前数据库用户该库权限；测试会执行 Flyway V1、V2，自行准备和清理测试用户、岗位及投递记录。现有测试不验证 Redis。

投递进度已实现 `/api/v1/applications` 下的新增、分页筛选、详情、部分修改、物理删除和状态统计，包含用户归属隔离、字段校验、版本冲突检测及 PATCH 字段省略/null 的区分。新增表由 `V2__add_application_record.sql` 创建，未修改已执行的 V1。正式认证尚待对接：当前 `SecurityConfig` 为演示放行配置，投递进度接口另行通过 `CurrentUserResolver` 要求已认证的 JWT 用户身份；本地请求头模拟仅在 `local` profile 和显式开关同时启用时生效，默认关闭，不能替代正式登录。

集成测试支持 `integration-tests=auto/true/false`：默认 auto 在测试库不可达时跳过；true 强制执行、连接失败即失败；false 显式跳过。正式验收在本目录执行：

```powershell
mvn clean test -Dintegration-tests=true
```

Windows 也可使用本目录的 `mvn-run.bat`，在 PowerShell 中执行 `./mvn-run.bat clean test -Dintegration-tests=true`。脚本优先使用已有 `JAVA_HOME` 和 PATH 中的 Maven，可通过 `AIJOB_JAVA_HOME`、`AIJOB_MVN`、`AIJOB_SETTINGS`、`AIJOB_M2_REPO` 显式指定配置；团队验收使用 JDK 21，并确认没有跳过测试。
