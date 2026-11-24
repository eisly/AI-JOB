# AI-JOB Java 业务后端

基于 JDK 21、Spring Boot 4、MyBatis 的业务后端，包含登录认证、业务接口与 Agent 任务通信。

## 运行

准备 MySQL 8、Redis 和测试邮件服务；连接配置见根目录 `.env.example`。数据库可通过 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 覆盖，Redis 使用 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`。

项目根目录提供 `docker-compose.yml` 和 `.env.example`；首次使用时复制模板为 `.env` 并设置自己的 MySQL 密码，执行 `docker compose up -d --wait mysql redis` 可启动持久化 MySQL 和 Redis。首次初始化后的 MySQL 账号、密码由数据库保存，修改 `.env` 不会自动修改数据库账号密码。`MYSQL_BIND_ADDRESS` 默认为 `127.0.0.1`，MySQL 主机端口默认 3307。勿向公网直接暴露数据库。

Compose Redis 使用密码认证，需在 `.env` 中设置 `REDIS_PASSWORD`；服务默认仅绑定本机，数据保存在 `redis_data` 卷。

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

使用 `mvn test` 运行测试。`DatabaseQueryTest` 依赖真实演示数据；投递和登录集成测试使用独立测试库，登录测试还需要隔离 Redis 和 SMTP 服务。

投递进度支持新增、分页、详情、修改、删除和统计。JWT 登录认证与业务用户解析已对接；内部 Agent 回调使用独立的令牌校验。Flyway V6 将历史零版本画像更新为版本 1。

集成测试支持 `integration-tests=auto/true/false`：默认 auto 在测试库不可达时跳过；true 强制执行、连接失败即失败；false 显式跳过。正式验收在本目录执行：

```powershell
mvn clean test -Dintegration-tests=true
```

Windows 也可使用本目录的 `mvn-run.bat`，在 PowerShell 中执行 `./mvn-run.bat clean test -Dintegration-tests=true`。脚本优先使用已有 `JAVA_HOME` 和 PATH 中的 Maven，可通过 `AIJOB_JAVA_HOME`、`AIJOB_MVN`、`AIJOB_SETTINGS`、`AIJOB_M2_REPO` 显式指定配置；团队验收使用 JDK 21，并确认没有跳过测试。

## 账号管理

账号资料、昵称、密码、手机号与头像统一位于 `/api/v1/account/**`，身份只取自 JWT 主体，写接口必须携带账号级 `X-CSRF-Token`（由 `GET /api/v1/account/csrf` 签发，与 `/api/v1/auth/csrf` 隔离）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/account/profile` | 账号资料（手机号脱敏、头像地址含版本） |
| GET | `/api/v1/account/capabilities` | 短信通道与头像上传能力探测 |
| GET | `/api/v1/account/csrf` | 签发账号写接口 CSRF 令牌 |
| PATCH | `/api/v1/account/nickname` | 修改昵称（1-30 字符，无变更不写库） |
| POST | `/api/v1/account/password/change` | 修改密码（校验当前密码，成功后全会话失效） |
| POST | `/api/v1/account/phone/code` | 发送手机号验证码（BIND/REBIND） |
| POST | `/api/v1/account/phone/bind` | 绑定手机号 |
| POST | `/api/v1/account/phone/rebind` | 换绑手机号（需当前密码） |
| POST | `/api/v1/account/phone/unbind` | 解绑手机号（需当前密码） |
| POST | `/api/v1/account/avatar` | 上传头像（JPEG/PNG/WebP，≤2 MB） |
| DELETE | `/api/v1/account/avatar` | 恢复默认头像 |
| GET | `/api/v1/account/avatars/me?v={version}` | 受控读取本人头像（ETag / 304） |

新增错误码：`CURRENT_PASSWORD_INCORRECT(40004)`、`NEW_PASSWORD_SAME(40005)`、`PASSWORD_CONFIRM_MISMATCH(40006)`、`INVALID_PHONE(40007)`、`VERIFICATION_CODE_EXPIRED(40008)`、`VERIFICATION_CODE_TOO_MANY(40009)`、`CSRF_INVALID(40302)`、`PHONE_ALREADY_BOUND(40908)`、`PHONE_CHANGED(40909)`、`AVATAR_TOO_LARGE(41302)`、`UNSUPPORTED_AVATAR_TYPE(41502)`、`IMAGE_UNREADABLE(41503)`、`PASSWORD_ATTEMPT_LIMIT(42903)`、`PHONE_BINDING_UNAVAILABLE(50302)`。

配置项（均带默认值，可用环境变量覆盖）：

```yaml
app:
  account:
    sms-code: { ttl-seconds: 600, max-attempts: 5, cooldown-seconds: 60 }
    password: { max-failures: 5, lock-seconds: 900 }
    avatar: { max-bytes: 2097152 }
  sms:
    enabled: false
    expose-code-in-response: false
```

迁移在 `V24__add_account_management.sql`。单测命令：

```powershell
mvn test "-Dtest=PhoneMaskTest,SourceDigestTest,AccountNicknameRulesTest,AccountPasswordRulesTest,AccountErrorCodeTest,AvatarTypeDetectorTest,AvatarStorageServiceTest,PasswordAttemptGuardTest,AccountCsrfServiceTest,PhoneVerificationCodeServiceTest,AccountProfileServiceTest" "-Djacoco.skip=true"
```

集成测试使用 `career_business_test`（默认 3307）与 Redis；两者任一不可达时相关用例无法验证。
