# AI-JOB Java 业务后端

基于 JDK 21、Spring Boot 4、MyBatis 的 Maven 工程，已引入 Spring MVC、WebClient、Security/JWT、MySQL、Redis 和 Flyway。依赖配置不代表登录认证、业务接口或 Agent 通信已经实现。

## 运行

准备 MySQL 8，创建 `career_business` 数据库，并启动 Redis。数据库可以通过 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 覆盖配置；Redis 可通过 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD` 配置，服务端口通过 `SERVER_PORT` 配置。`.env` 仅保存在本地，不要提交密码。

项目根目录提供 `docker-compose.yml` 和 `.env.example`；首次使用时复制模板为 `.env` 并设置自己的密码，执行 `docker compose up -d mysql` 可启动持久化 MySQL。首次初始化后的账号、密码由数据库保存，修改 `.env` 不会自动修改数据库账号密码。`MYSQL_BIND_ADDRESS` 默认为 `127.0.0.1`，主机端口默认 3307。勿向公网直接暴露数据库。

从项目根目录或 `project/backend` 启动时，Spring Boot 会自动读取项目根目录的 `.env`，用于连接本机 Docker MySQL。无需手动复制密码到 `application.yml`。在 `project/backend` 目录执行：

```powershell
mvn spring-boot:run
```

每位成员在本机启动独立 MySQL，使用相同配置，但数据不会自动同步。团队应协调 Flyway 版本，避免产生冲突的表结构。关闭容器可执行 `docker compose stop mysql`，再次启动使用 `docker compose up -d mysql`；不要使用 `docker compose down -v`，它会删除持久化卷。

首次启动需要数据库存在；Flyway 自动执行 `src/main/resources/db/migration/V1__init_business_schema.sql`，创建 Java 业务库的 P0 表。脚本不创建 Python Agent 独占的运行库。应用数据库账号需要具备迁移所需的建表和修改表权限；已有非空数据库不要直接当作新库执行，应先确认迁移策略。

演示数据在 `src/main/resources/db/demo/demo_data.sql`，仅供空的开发库手动导入，不随应用启动自动执行：

```powershell
mysql --default-character-set=utf8mb4 -u 你的数据库用户 -p career_business -e "source src/main/resources/db/demo/demo_data.sql"
```

演示账号已禁用，岗位和公司均为虚构；脚本不是可重复导入的迁移，不要在生产环境运行。

使用 `mvn test` 运行启动测试；测试使用内存 H2 并关闭 Flyway，不会验证真实 MySQL 或 Redis。当前仅保留 Spring Security 默认保护，JWT 签发与校验、CSRF、角色和业务权限仍需按接口设计实现。
