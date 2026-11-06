# AGENTS.md — AI-JOB

AI 一站式求职平台（比赛项目）。设计上是 Vue 3 + Spring Boot 4（业务）+ FastAPI/LangGraph（Agent）双后端；**当前仓库只有 Java 后端骨架**，`project/agent/hello.py`、`project/frontend/hello.html` 是占位文件，`docs/` 全部是设计资料而非已实现功能。

## 现状与真实性
- 唯一真实接口：`GET /api/v1/users`、`GET /api/v1/users/{id}`（demo，用户列表/详情）。
- `SecurityConfig` 当前 `permitAll` + 关闭 CSRF，是 demo 放行规则；**认证/JWT 尚未实现**，pom 里的 oauth2-resource-server 依赖不代表已有登录。
- `mvn test` 中 `BackendApplicationTest` 只是上下文加载；根 `readme.md` 说“测试用 H2”仅对这一个类成立。

## 技术栈与结构
- JDK 21 + Spring Boot 4.0.4 + MyBatis（注解式 Mapper）+ MySQL 8 + Flyway；无 `mvnw`，用本机 Maven。
- 包结构按业务域：`com.aijob.backend.<domain>` 下 `controller / service / service.impl / mapper / entity`，公共返回体在 `common.CommonResult`。新增功能沿用此分层，勿引入 Controller 直连 Mapper 的写法。
- 响应统一 `CommonResult{code,msg,data,traceId}`（200 成功 / 202 受理），接口前缀 `/api/v1`；契约以 `docs/功能资料/业务后端接口文档.md` 为准，错误码/枚举/字段约定见 `docs/功能资料/数据说明.md`。
- 字段约定：DB `snake_case` ↔ Java `camelCase`，依赖 `application.yml` 的 `map-underscore-to-camel-case: true`；时间 UTC ISO 8601，薪资为月薪整数分，ID 为 36 字符 UUID。不要查询/映射 `password_hash` 等敏感列。

## 常用命令
```bash
cp .env.example .env            # 先设置 MYSQL_PASSWORD / MYSQL_ROOT_PASSWORD
docker compose up -d mysql      # 仅 MySQL(3307, 库 career_business)，Redis/Qdrant/Python 需另备
cd project/backend && mvn spring-boot:run   # 8080，自动读仓库根 .env
cd project/backend && mvn test
cd project/backend && mvn -Dtest=BackendApplicationTest test   # 单测，不依赖数据库
```
- **JDK 版本陷阱**：pom `java.version=21`，若 `mvn -version` 显示 17/18（本机默认 `JAVA_HOME` 指向 18），编译会报 `release version 21 not supported`，需先切到 JDK 21。

## 数据库与迁移
- `docker compose` 只建库不建表；首次启动后端时 Flyway 执行 `V1__init_business_schema.sql` 建 31 张表。
- **V1 已提交运行，禁止修改**；表结构变更新增 `V2__*.sql`（团队需协调版本号）。
- `db/demo/demo_data.sql` 仅供空的开发库**手动导入一次**，非可重复导入脚本；演示账号已禁用。导入后 `DatabaseQueryTest` 才会通过。
- 不要 `docker compose down -v`（删除持久化卷）。修改 `.env` 不会改已初始化数据库的账号密码。

## 测试陷阱
- `mvn test` 会跑 `DatabaseQueryTest`：它**不走 H2**，直连 `application.yml` 的真实 MySQL 并断言演示数据（`skill_catalog` 含 java/mysql/redis/spring、`question` 有 ACTIVE 记录），没起 MySQL 或没导演示数据就会失败。只验证编译/上下文时用 `-Dtest=BackendApplicationTest`。
- `DatabaseQueryTest` 还遍历 `information_schema` 对所有业务表 `COUNT(*)`，因此需要库账号具备查询权限。

## 已知缺陷（勿被误当作配置正确）
- `application.yml` 的 `spring.config.import` 第二个条目拼写损坏（`optional:fmvn spring-boot:runile:./.env`），实际生效的只有 `../../.env`；从仓库根目录启动时 `./.env` 不会加载。
- `docs/功能资料/README.md` 中指向 `../接口设计/...`、`docs/UI原型` 的链接在本仓库不存在（`docs.zip` 也没有）；权威内容只在 `docs/功能资料/`，`docs.zip` 只是 `docs/` 的归档副本。

## 架构边界（写代码前先遵守）
- 前端只调 Java `/api/v1`；Java 用 `WebClient` 调 Python `/internal/**` 并回调接收 AI 结果。
- 确定性规则（学历/年限/资格校验）放 Java；语义理解、生成放 Python。Agent 自有运行库不属于 Flyway 管理范围。
- 本仓库没有 CI、lint、formatter 配置；不要臆造 `npm`/`python` 校验命令。