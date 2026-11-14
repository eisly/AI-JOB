package com.aijob.backend.application;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aijob.backend.application.dto.ApplicationUpdateDTO;
import com.aijob.backend.application.dto.PatchField;
import com.aijob.backend.application.service.ApplicationService;
import com.aijob.backend.common.BusinessException;

import tools.jackson.databind.ObjectMapper;

/**
 * 投递进度集成测试：连接独立的 MySQL 测试库 career_business_test（与日常开发库
 * career_business 物理隔离），由 Flyway 执行全量迁移（V1+V2）验证建表与数据库行为。
 * <p>测试库准备（一次性，本地 Docker MySQL 3307 实例上）：
 * <pre>
 * CREATE DATABASE career_business_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
 * GRANT ALL ON career_business_test.* TO 'aijob'@'%';  -- 用户同 .env 的 MYSQL_USER
 * </pre>
 * <p><b>执行模式</b>（-Dintegration-tests=...，经 pom 中 surefire systemPropertyVariables 桥接）：
 * <ul>
 *   <li>auto（默认）：测试库可达才执行，不可达时跳过——适合本地日常快速回归；</li>
 *   <li>true：<b>验收/CI 使用</b>——强制执行，数据库不可达时直接失败（暴露连接/密码/测试库配置问题），不静默跳过；</li>
 *   <li>false：显式禁用。</li>
 * </ul>
 * <p>认证采用 Spring Security 测试支持（jwt() 模拟认证上下文，不伪造请求头）；
 * 无认证 + 伪造 X-User-Id 请求头必须得到 401。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/career_business_test"
                + "?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
})
@AutoConfigureMockMvc
@EnabledIf(value = "integrationTestsEnabled",
        disabledReason = "集成测试未启用（-Dintegration-tests=false 或 auto 模式下 MySQL 测试库不可达，见类注释）")
class ApplicationIntegrationTest {

    private static final String USER_A = "10000000-0000-4000-8000-00000000000a";
    private static final String USER_B = "10000000-0000-4000-8000-00000000000b";
    private static final String JOB_SOURCE = "3a000000-0000-4000-8000-000000000001";
    private static final String JOB_PUBLIC_ACTIVE = "3b000000-0000-4000-8000-000000000001";
    private static final String JOB_PUBLIC_CLOSED = "3b000000-0000-4000-8000-000000000002";
    private static final String JOB_PRIVATE_B = "3b000000-0000-4000-8000-000000000003";
    private static final String NOT_EXIST_JOB = "3f000000-0000-4000-8000-00000000000f";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 集成测试执行模式判定（与 pom 的 integration-tests 属性联动，见类注释）：
     * true=强制执行（数据库不可达时直接失败，验收/CI）；false=显式禁用；auto=探测可达才执行。
     */
    static boolean integrationTestsEnabled() {
        String mode = System.getProperty("integration-tests", "auto");
        if ("true".equalsIgnoreCase(mode)) {
            return true;
        }
        if ("false".equalsIgnoreCase(mode)) {
            return false;
        }
        return mysqlTestDatabaseAvailable();
    }

    static boolean mysqlTestDatabaseAvailable() {
        String user = envValue("MYSQL_USER", "aijob");
        String password = envValue("MYSQL_PASSWORD", "");
        DriverManager.setLoginTimeout(3);
        try (Connection ignored = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3307/career_business_test?connectTimeout=3000&socketTimeout=5000",
                user, password)) {
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    static String envValue(String key, String defaultValue) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        Path envFile = Path.of("..", "..", ".env");
        if (Files.exists(envFile)) {
            try {
                for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith(key + "=")) {
                        String value = trimmed.substring(key.length() + 1);
                        return value.isEmpty() ? defaultValue : value;
                    }
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return defaultValue;
    }

    @BeforeAll
    static void prepareData(@Autowired JdbcTemplate jdbcTemplate) throws Exception {
        // 防误连最后防线：集成测试必须连接独立测试库 career_business_test（url 由 @SpringBootTest
        // properties 硬性指定，无任何回退逻辑）。此处断言连接池实际拿到的 catalog，
        // 一旦误连其他库（尤其开发库 career_business）立即失败，不静默写入。
        try (var connection = jdbcTemplate.getDataSource().getConnection()) {
            assertEquals("career_business_test", connection.getCatalog(),
                    "集成测试必须连接 career_business_test，检测到误连其他数据库，禁止继续执行");
        }

        // 测试用户（ACTIVE，不使用 demo 已禁用账号）
        jdbcTemplate.update("""
                INSERT INTO app_user (id, email, password_hash, nickname, role, status) VALUES
                (?, ?, '{test}', '测试用户A', 'USER', 'ACTIVE'),
                (?, ?, '{test}', '测试用户B', 'USER', 'ACTIVE')
                """, USER_A, "test-a@example.invalid", USER_B, "test-b@example.invalid");

        byte[] hash = MessageDigest.getInstance("SHA-256").digest("integration-test".getBytes(StandardCharsets.UTF_8));

        jdbcTemplate.update("""
                INSERT INTO job_source (id, name, type, verification_status) VALUES (?, ?, 'OFFICIAL', 'VERIFIED')
                """, JOB_SOURCE, "测试认证来源");

        // PUBLIC ACTIVE：任何用户可见（保守规则：要求 status=ACTIVE，待与岗位模块对齐）
        jdbcTemplate.update("""
                INSERT INTO job (id, source_id, owner_user_id, visibility, current_version, status)
                VALUES (?, ?, NULL, 'PUBLIC', 1, 'ACTIVE')
                """, JOB_PUBLIC_ACTIVE, JOB_SOURCE);
        // PUBLIC CLOSED：按保守规则不可关联投递
        jdbcTemplate.update("""
                INSERT INTO job (id, source_id, owner_user_id, visibility, current_version, status)
                VALUES (?, ?, NULL, 'PUBLIC', 1, 'CLOSED')
                """, JOB_PUBLIC_CLOSED, JOB_SOURCE);
        // PRIVATE owner=B：仅 B 可见
        jdbcTemplate.update("""
                INSERT INTO job (id, source_id, owner_user_id, visibility, current_version, status)
                VALUES (?, ?, ?, 'PRIVATE', 1, 'DRAFT')
                """, JOB_PRIVATE_B, JOB_SOURCE, USER_B);

        for (String jobId : List.of(JOB_PUBLIC_ACTIVE, JOB_PUBLIC_CLOSED, JOB_PRIVATE_B)) {
            jdbcTemplate.update("""
                    INSERT INTO job_version (id, job_id, version_no, title, company_name, candidate_type, jd_text, content_hash)
                    VALUES (?, ?, 1, '测试岗位', '测试公司', 'CAMPUS', '测试 JD', ?)
                    """, UUID.randomUUID().toString(), jobId, hash);
        }
    }

    @AfterAll
    static void cleanupData(@Autowired JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("DELETE FROM application_record WHERE user_id IN (?, ?)", USER_A, USER_B);
        jdbcTemplate.update("DELETE FROM job_version WHERE job_id IN (?, ?, ?)",
                JOB_PUBLIC_ACTIVE, JOB_PUBLIC_CLOSED, JOB_PRIVATE_B);
        jdbcTemplate.update("DELETE FROM job WHERE id IN (?, ?, ?)",
                JOB_PUBLIC_ACTIVE, JOB_PUBLIC_CLOSED, JOB_PRIVATE_B);
        jdbcTemplate.update("DELETE FROM job_source WHERE id = ?", JOB_SOURCE);
        jdbcTemplate.update("DELETE FROM app_user WHERE id IN (?, ?)", USER_A, USER_B);
    }

    @BeforeEach
    void cleanApplicationRecords() {
        // 每个用例前清理本模块写入的数据，保证测试间隔离
        jdbcTemplate.update("DELETE FROM application_record WHERE user_id IN (?, ?)", USER_A, USER_B);
    }

    private static RequestPostProcessor asUser(String userId) {
        return SecurityMockMvcRequestPostProcessors.jwt().jwt(builder -> builder.subject(userId));
    }

    private String createBody(Map<String, Object> fields) throws Exception {
        return objectMapper.writeValueAsString(fields);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createRecordForUserA() throws Exception {
        String body = mockMvc.perform(post("/api/v1/applications").with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of(
                                "jobId", JOB_PUBLIC_ACTIVE,
                                "companyName", "云启智能（虚构）",
                                "jobTitle", "Java 后端开发工程师",
                                "cityCode", "330100",
                                "plannedDate", "2026-10-25",
                                "status", "APPLIED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        // 返回 data 部分，调用方直接 get("id") 即为投递记录 ID
        Map<String, Object> response = objectMapper.readValue(body, Map.class);
        return (Map<String, Object>) response.get("data");
    }

    // ---------- 认证：不默认启用请求头降级 ----------

    @Test
    void unauthenticatedRequestWithForgedHeaderIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/applications")
                        .header("X-User-Id", USER_B)
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of("companyName", "x", "jobTitle", "y"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101))
                .andExpect(jsonPath("$.error.key").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        mockMvc.perform(get("/api/v1/applications").header("X-User-Id", USER_A))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 创建 ----------

    @Test
    void createWithVisibleJobSucceedsAndSnapshotStored() throws Exception {
        mockMvc.perform(post("/api/v1/applications").with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of(
                                "jobId", JOB_PUBLIC_ACTIVE,
                                "companyName", "  云启智能（虚构）  ",
                                "jobTitle", "Java 后端开发工程师",
                                "status", "APPLIED",
                                "plannedDate", "2026-10-25",
                                "appliedDate", "2026-10-26",
                                "note", "等待反馈"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(JOB_PUBLIC_ACTIVE))
                .andExpect(jsonPath("$.data.companyName").value("云启智能（虚构）"))
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.plannedDate").value("2026-10-25"))
                .andExpect(jsonPath("$.data.appliedDate").value("2026-10-26"))
                .andExpect(jsonPath("$.data.interviewDate").doesNotExist())
                .andExpect(jsonPath("$.data.closedDate").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void createDefaultsStatusToPlannedWhenOmitted() throws Exception {
        mockMvc.perform(post("/api/v1/applications").with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of("companyName", "公司甲", "jobTitle", "岗位乙"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PLANNED"));
    }

    @Test
    void createWithoutJobIdSucceedsForManualEntry() throws Exception {
        mockMvc.perform(post("/api/v1/applications").with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of(
                                "companyName", "手动录入公司",
                                "jobTitle", "手动录入岗位",
                                "salaryText", "10–16K"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").doesNotExist())
                .andExpect(jsonPath("$.data.salaryText").value("10–16K"));
    }

    @Test
    void createReturns404ForMissingJobOrOthersPrivateJob() throws Exception {
        // 不存在的岗位
        mockMvc.perform(post("/api/v1/applications").with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of(
                                "jobId", NOT_EXIST_JOB,
                                "companyName", "公司甲", "jobTitle", "岗位乙"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401))
                .andExpect(jsonPath("$.error.key").value("RESOURCE_NOT_FOUND"));

        // 他人 PRIVATE 岗位
        mockMvc.perform(post("/api/v1/applications").with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of(
                                "jobId", JOB_PRIVATE_B,
                                "companyName", "公司甲", "jobTitle", "岗位乙"))))
                .andExpect(status().isNotFound());

        // PUBLIC 但 CLOSED 的岗位：按保守可见性规则同样 404（待与岗位模块对齐结论）
        mockMvc.perform(post("/api/v1/applications").with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of(
                                "jobId", JOB_PUBLIC_CLOSED,
                                "companyName", "公司甲", "jobTitle", "岗位乙"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void createOwnPrivateJobSucceedsForOwner() throws Exception {
        mockMvc.perform(post("/api/v1/applications").with(asUser(USER_B))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of(
                                "jobId", JOB_PRIVATE_B,
                                "companyName", "我的目标公司", "jobTitle", "私有岗位投递"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(JOB_PRIVATE_B));
    }

    @Test
    void createDuplicateJobRequiresExplicitConfirmation() throws Exception {
        createRecordForUserA();
        // 未确认的重复添加返回 409，并携带已有记录信息
        mockMvc.perform(post("/api/v1/applications").with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of(
                                "jobId", JOB_PUBLIC_ACTIVE,
                                "companyName", "云启智能（虚构）",
                                "jobTitle", "Java 后端开发工程师",
                                "status", "APPLIED"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.key").value("DUPLICATE_APPLICATION"))
                .andExpect(jsonPath("$.error.details.existingCount").value(1));
        // 确认后允许再次添加，形成独立记录
        mockMvc.perform(post("/api/v1/applications").with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of(
                                "jobId", JOB_PUBLIC_ACTIVE,
                                "companyName", "云启智能（虚构）",
                                "jobTitle", "Java 后端开发工程师",
                                "status", "APPLIED",
                                "confirmDuplicate", true))))
                .andExpect(status().isOk());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM application_record WHERE user_id = ?", Integer.class, USER_A);
        assertEquals(2, count);
    }

    @Test
    void createRejectsPlannedStatusWithAppliedDate() throws Exception {
        mockMvc.perform(post("/api/v1/applications").with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of(
                                "companyName", "公司甲",
                                "jobTitle", "岗位乙",
                                "status", "PLANNED",
                                "appliedDate", "2026-10-26"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.key").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("appliedDate"));
    }

    @Test
    void createRejectsIllegalJsonDateAndEnum() throws Exception {
        // 非法 JSON
        mockMvc.perform(post("/api/v1/applications").with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content("{\"companyName\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.error.key").value("VALIDATION_ERROR"));

        // 错误日期格式
        mockMvc.perform(post("/api/v1/applications").with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of(
                                "companyName", "公司甲", "jobTitle", "岗位乙",
                                "appliedDate", "2026-13-45"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.key").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.fields").isArray());

        // 非法状态枚举
        mockMvc.perform(post("/api/v1/applications").with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of(
                                "companyName", "公司甲", "jobTitle", "岗位乙",
                                "status", "XX"))))
                .andExpect(status().isBadRequest());

        // 必填字段为空白
        mockMvc.perform(post("/api/v1/applications").with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of("companyName", "  ", "jobTitle", "岗位乙"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fields").isArray());

        // 超长字段
        mockMvc.perform(post("/api/v1/applications").with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of(
                                "companyName", "超".repeat(101), "jobTitle", "岗位乙"))))
                .andExpect(status().isBadRequest());
    }

    // ---------- 分页查询与筛选 ----------

    @Test
    void listReturnsOnlyOwnRecordsWithFilterAndPaging() throws Exception {
        createRecordForUserA();
        // 用户 B 创建一条
        mockMvc.perform(post("/api/v1/applications").with(asUser(USER_B))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of("companyName", "B 公司", "jobTitle", "B 岗位"))));

        mockMvc.perform(get("/api/v1/applications").with(asUser(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.records[0].companyName").value("云启智能（虚构）"));

        mockMvc.perform(get("/api/v1/applications").with(asUser(USER_A)).param("status", "APPLIED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(get("/api/v1/applications").with(asUser(USER_A)).param("status", "PLANNED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records.length()").value(0));
    }

    @Test
    void listReturnsEmptyResultForUserWithoutRecords() throws Exception {
        mockMvc.perform(get("/api/v1/applications").with(asUser(USER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records.length()").value(0));
    }

    @Test
    void listRejectsIllegalPagingAndStatus() throws Exception {
        mockMvc.perform(get("/api/v1/applications").with(asUser(USER_A)).param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/applications").with(asUser(USER_A)).param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/applications").with(asUser(USER_A)).param("current", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/applications").with(asUser(USER_A)).param("status", "XX"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fields").isArray());
        mockMvc.perform(get("/api/v1/applications").with(asUser(USER_A)).param("current", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.key").value("VALIDATION_ERROR"));
        // 空串与空白 status 是"传了非法值"，必须 400 而非当作"不传"返回全部
        mockMvc.perform(get("/api/v1/applications").with(asUser(USER_A)).param("status", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.key").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/applications").with(asUser(USER_A)).param("status", " "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listRejectsOversizedPageOffset() throws Exception {
        // (current-1)*size 溢出 int：400 而非 500
        mockMvc.perform(get("/api/v1/applications").with(asUser(USER_A))
                        .param("current", "2147483647").param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.key").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.fields").isArray());
        // 边界内的大页码：200 + 空结果（深分页无数据）
        mockMvc.perform(get("/api/v1/applications").with(asUser(USER_A)).param("current", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records.length()").value(0));
    }

    @Test
    void clientErrorsKeepCorrectHttpStatus() throws Exception {
        // 405：对仅 GET/PATCH/DELETE 的详情路径发 POST
        mockMvc.perform(post("/api/v1/applications/{id}", UUID.randomUUID()).with(asUser(USER_A)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.key").value("METHOD_NOT_ALLOWED"));
        // 415：不支持的 Content-Type（XML 请求体）
        mockMvc.perform(post("/api/v1/applications").with(asUser(USER_A))
                        .contentType(org.springframework.http.MediaType.APPLICATION_XML)
                        .content("<a/>"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.key").value("UNSUPPORTED_MEDIA_TYPE"));
        // 406：Accept 无法满足（仅断言状态，body 协商受限）
        mockMvc.perform(get("/api/v1/applications").with(asUser(USER_A))
                        .accept(org.springframework.http.MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable());
        // 404：未映射路径（静态资源 fallback 不得变 500）
        mockMvc.perform(get("/api/v1/applications/{id}/extra", UUID.randomUUID()).with(asUser(USER_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.key").value("RESOURCE_NOT_FOUND"));
    }

    // ---------- 详情 ----------

    @Test
    void detailReturnsRecordForOwnerBut404ForOthersOrMissing() throws Exception {
        Map<String, Object> created = createRecordForUserA();
        String id = (String) created.get("id");

        mockMvc.perform(get("/api/v1/applications/{id}", id).with(asUser(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.companyName").value("云启智能（虚构）"));

        // 他人访问：404 且响应结构与不存在一致
        mockMvc.perform(get("/api/v1/applications/{id}", id).with(asUser(USER_B)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.key").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/applications/{id}", UUID.randomUUID()).with(asUser(USER_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.key").value("RESOURCE_NOT_FOUND"));

        // 非法 UUID
        mockMvc.perform(get("/api/v1/applications/not-a-uuid").with(asUser(USER_A)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.key").value("VALIDATION_ERROR"));
    }

    // ---------- 修改（三态 + 乐观锁） ----------

    @Test
    void patchAppliesThreeStateSemantics() throws Exception {
        Map<String, Object> created = createRecordForUserA();
        String id = (String) created.get("id");

        // 接口文档示例：expectedVersion=1、status=INTERVIEWING、interviewDate、note=null
        Map<String, Object> patchBody = new java.util.HashMap<>();
        patchBody.put("expectedVersion", 1);
        patchBody.put("status", "INTERVIEWING");
        patchBody.put("interviewDate", "2026-10-28");
        patchBody.put("note", null);

        mockMvc.perform(patch("/api/v1/applications/{id}", id).with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(patchBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INTERVIEWING"))
                .andExpect(jsonPath("$.data.interviewDate").value("2026-10-28"))
                .andExpect(jsonPath("$.data.note").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(2))
                // 未传字段保持原值
                .andExpect(jsonPath("$.data.companyName").value("云启智能（虚构）"))
                .andExpect(jsonPath("$.data.cityCode").value("330100"))
                .andExpect(jsonPath("$.data.plannedDate").value("2026-10-25"));

        // CLOSED → APPLIED 回退成功且不自动改日期
        mockMvc.perform(patch("/api/v1/applications/{id}", id).with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of(
                                "expectedVersion", 2,
                                "status", "CLOSED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.interviewDate").value("2026-10-28"));

        mockMvc.perform(patch("/api/v1/applications/{id}", id).with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of(
                                "expectedVersion", 3,
                                "status", "APPLIED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.interviewDate").value("2026-10-28"));
    }

    @Test
    void patchRejectsPlannedStatusWithRetainedAppliedDate() throws Exception {
        // 创建已投递且带实际投递日期的记录
        String body = mockMvc.perform(post("/api/v1/applications").with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of(
                                "companyName", "回退校验公司",
                                "jobTitle", "回退校验岗位",
                                "status", "APPLIED",
                                "appliedDate", "2026-10-26"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> created = (Map<String, Object>) objectMapper.readValue(body, Map.class).get("data");
        String id = (String) created.get("id");

        // 回退到待投递但保留实际投递日期：拒绝
        mockMvc.perform(patch("/api/v1/applications/{id}", id).with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of("expectedVersion", 1, "status", "PLANNED"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("appliedDate"));

        // 同时清空实际投递日期：允许回退
        Map<String, Object> clearBody = new java.util.HashMap<>();
        clearBody.put("expectedVersion", 1);
        clearBody.put("status", "PLANNED");
        clearBody.put("appliedDate", null);
        mockMvc.perform(patch("/api/v1/applications/{id}", id).with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(clearBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PLANNED"))
                .andExpect(jsonPath("$.data.appliedDate").doesNotExist());
    }

    @Test
    void patchRejectsRequiredNullEmptyBodyAndJobId() throws Exception {
        Map<String, Object> created = createRecordForUserA();
        String id = (String) created.get("id");

        // 必填字段传 null
        mockMvc.perform(patch("/api/v1/applications/{id}", id).with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"companyName\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fields").isArray());

        // 空修改体
        mockMvc.perform(patch("/api/v1/applications/{id}", id).with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());

        // jobId 出现（不可修改）
        Map<String, Object> jobIdPatchBody = new java.util.HashMap<>();
        jobIdPatchBody.put("expectedVersion", 1);
        jobIdPatchBody.put("jobId", null);
        mockMvc.perform(patch("/api/v1/applications/{id}", id).with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(jobIdPatchBody)))
                .andExpect(status().isBadRequest());

        // 错误日期格式
        mockMvc.perform(patch("/api/v1/applications/{id}", id).with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of("expectedVersion", 1, "interviewDate", "2026-13-45"))))
                .andExpect(status().isBadRequest());

        // 缺 expectedVersion
        mockMvc.perform(patch("/api/v1/applications/{id}", id).with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":\"APPLIED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchReturns409WithCurrentVersionAnd404ForOthers() throws Exception {
        Map<String, Object> created = createRecordForUserA();
        String id = (String) created.get("id");

        // 先更新到 version=2
        mockMvc.perform(patch("/api/v1/applications/{id}", id).with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of("expectedVersion", 1, "status", "APPLIED"))))
                .andExpect(status().isOk());

        // 过期版本：409 + details.currentVersion
        mockMvc.perform(patch("/api/v1/applications/{id}", id).with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of("expectedVersion", 1, "status", "CLOSED"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901))
                .andExpect(jsonPath("$.error.key").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.error.details.currentVersion").value(2));

        // 他人修改：404
        mockMvc.perform(patch("/api/v1/applications/{id}", id).with(asUser(USER_B))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of("expectedVersion", 2, "status", "CLOSED"))))
                .andExpect(status().isNotFound());
    }

    // ---------- 删除 ----------

    @Test
    void deleteSucceedsThenRepeatDeleteReturns404() throws Exception {
        Map<String, Object> created = createRecordForUserA();
        String id = (String) created.get("id");

        mockMvc.perform(delete("/api/v1/applications/{id}", id).with(asUser(USER_A))
                        .param("expectedVersion", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        // 删除后详情 404；重复删除 404（而非 409）
        mockMvc.perform(get("/api/v1/applications/{id}", id).with(asUser(USER_A)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/applications/{id}", id).with(asUser(USER_A))
                        .param("expectedVersion", "1"))
                .andExpect(status().isNotFound());

        // 不带 expectedVersion：400（而非 500）
        mockMvc.perform(delete("/api/v1/applications/{id}", id).with(asUser(USER_A)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.key").value("VALIDATION_ERROR"));

        // 非正整数
        mockMvc.perform(delete("/api/v1/applications/{id}", id).with(asUser(USER_A))
                        .param("expectedVersion", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteReturns409OnVersionMismatchAndDoesNotTouchJobData() throws Exception {
        Map<String, Object> created = createRecordForUserA();
        String id = (String) created.get("id");

        mockMvc.perform(delete("/api/v1/applications/{id}", id).with(asUser(USER_A))
                        .param("expectedVersion", "9"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.currentVersion").value(1));

        Integer jobCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job WHERE id = ?",
                Integer.class, JOB_PUBLIC_ACTIVE);
        assertEquals(1, jobCount);
    }

    // ---------- 统计 ----------

    @Test
    void summaryCountsByStatusWithZeroFill() throws Exception {
        createRecordForUserA();
        mockMvc.perform(post("/api/v1/applications").with(asUser(USER_A))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of("companyName", "公司丙", "jobTitle", "岗位丁"))));

        mockMvc.perform(get("/api/v1/applications/summary").with(asUser(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.counts.PLANNED").value(1))
                .andExpect(jsonPath("$.data.counts.APPLIED").value(1))
                .andExpect(jsonPath("$.data.counts.INTERVIEWING").value(0))
                .andExpect(jsonPath("$.data.counts.CLOSED").value(0));

        // 用户 B 无记录：total=0 且四状态均为 0
        mockMvc.perform(get("/api/v1/applications/summary").with(asUser(USER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.counts.PLANNED").value(0))
                .andExpect(jsonPath("$.data.counts.APPLIED").value(0))
                .andExpect(jsonPath("$.data.counts.INTERVIEWING").value(0))
                .andExpect(jsonPath("$.data.counts.CLOSED").value(0));
    }

    // ---------- 真实并发冲突（顺序提交过期版本不能替代） ----------
    // 两个线程各自通过 Spring 事务代理开启独立事务、从 Hikari 连接池获取独立数据库连接，
    // 模拟真实跨连接的并发竞争；下方断言最终版本仅递增一次、行仅被删除一次。

    @Test
    void concurrentUpdateWithSameVersionExactlyOneSucceeds() throws Exception {
        for (int round = 0; round < 5; round++) {
            Map<String, Object> created = createRecordForUserA();
            String id = (String) created.get("id");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(2);
            List<String> outcomes = new CopyOnWriteArrayList<>();

            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        ApplicationUpdateDTO dto = new ApplicationUpdateDTO();
                        dto.setExpectedVersion(1);
                        dto.setStatus(PatchField.of(ApplicationStatus.APPLIED));
                        applicationService.update(USER_A, id, dto);
                        outcomes.add("OK");
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        outcomes.add("INTERRUPTED");
                    } catch (BusinessException ex) {
                        outcomes.add(ex.getErrorKey());
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(15, TimeUnit.SECONDS), "并发修改应按时完成");
            pool.shutdown();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }

            assertEquals(2, outcomes.size(), "第 " + round + " 轮结果: " + outcomes);
            assertEquals(1, outcomes.stream().filter("OK"::equals).count(),
                    "恰一个修改成功，第 " + round + " 轮结果: " + outcomes);
            assertEquals(1, outcomes.stream().filter("VERSION_CONFLICT"::equals).count(),
                    "另一个必须 409，第 " + round + " 轮结果: " + outcomes);

            Integer version = jdbcTemplate.queryForObject(
                    "SELECT version FROM application_record WHERE id = ?", Integer.class, id);
            assertEquals(2, version, "版本只递增一次");
        }
    }

    @Test
    void concurrentDeleteWithSameVersionExactlyOneSucceeds() throws Exception {
        for (int round = 0; round < 5; round++) {
            Map<String, Object> created = createRecordForUserA();
            String id = (String) created.get("id");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(2);
            List<String> outcomes = new CopyOnWriteArrayList<>();

            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        boolean deleted = applicationService.delete(USER_A, id, 1);
                        outcomes.add(deleted ? "OK" : "FALSE");
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        outcomes.add("INTERRUPTED");
                    } catch (BusinessException ex) {
                        outcomes.add(ex.getErrorKey());
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(15, TimeUnit.SECONDS), "并发删除应按时完成");
            pool.shutdown();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }

            assertEquals(2, outcomes.size(), "第 " + round + " 轮结果: " + outcomes);
            assertEquals(1, outcomes.stream().filter("OK"::equals).count(),
                    "恰一个删除成功，第 " + round + " 轮结果: " + outcomes);
            assertTrue(outcomes.stream().filter(o -> "VERSION_CONFLICT".equals(o) || "RESOURCE_NOT_FOUND".equals(o)).count() == 1,
                    "另一个应为 409 或 404 之一，第 " + round + " 轮结果: " + outcomes);

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM application_record WHERE id = ?", Integer.class, id);
            assertEquals(0, count, "该行确定被删除且仅删除一次");
        }
    }

    // ---------- 数据隔离回归 ----------

    @Test
    void userBCannotModifyOrDeleteUserARecord() throws Exception {
        Map<String, Object> created = createRecordForUserA();
        String id = (String) created.get("id");

        mockMvc.perform(patch("/api/v1/applications/{id}", id).with(asUser(USER_B))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(Map.of("expectedVersion", 1, "status", "CLOSED"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/applications/{id}", id).with(asUser(USER_B))
                        .param("expectedVersion", "1"))
                .andExpect(status().isNotFound());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM application_record WHERE id = ?", Integer.class, id);
        assertEquals(1, count, "用户 B 的改/删均不得影响用户 A 的记录");
    }

    @Test
    void flywayMigrationsAppliedInOrder() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT version, success FROM flyway_schema_history WHERE version IN ('1','2') ORDER BY version");
        assertEquals(2, rows.size());
        for (Map<String, Object> row : rows) {
            // MySQL Connector/J 将 tinyint(1) 映射为 Boolean；两种类型都按成功判定
            Object success = row.get("success");
            boolean applied = (success instanceof Boolean b && b)
                    || (success instanceof Number n && n.intValue() == 1);
            assertTrue(applied, "迁移应成功执行: " + row);
        }
    }
}