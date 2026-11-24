package com.aijob.backend.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/career_business_test"
                + "?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
})
@EnabledIf(value = "integrationTestsEnabled",
        disabledReason = "集成测试未启用（-Dintegration-tests=false 或 auto 模式下 MySQL 测试库不可达）")
class JobSaveToPersonalIntegrationTest {

    private static final String USER = "20000000-0000-4000-8000-000000000001";
    private static final String SOURCE_SOURCE = "2a000000-0000-4000-8000-000000000001";
    private static final String NEW_SOURCE = "2a000000-0000-4000-8000-000000000002";
    private static final String SOURCE_JOB = "2b000000-0000-4000-8000-000000000001";
    private static final String SOURCE_VERSION = "2c000000-0000-4000-8000-000000000001";
    private static final String NEW_JOB = "2b000000-0000-4000-8000-000000000002";
    private static final String NEW_VERSION = "2c000000-0000-4000-8000-000000000002";

    private static JdbcTemplate jdbc;

    @Autowired
    private JobMapper mapper;

    static boolean integrationTestsEnabled() {
        String mode = System.getProperty("integration-tests", "auto");
        if ("true".equalsIgnoreCase(mode)) return true;
        if ("false".equalsIgnoreCase(mode)) return false;
        return mysqlTestDatabaseAvailable();
    }

    static boolean mysqlTestDatabaseAvailable() {
        DriverManager.setLoginTimeout(3);
        try (Connection ignored = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3307/career_business_test?connectTimeout=3000&socketTimeout=5000",
                user(), password())) {
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String envValue(String key, String defaultValue) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) return env.trim();
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
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static String user() {
        return envValue("MYSQL_USER", "aijob");
    }

    private static String password() {
        return envValue("MYSQL_PASSWORD", "");
    }

    @BeforeAll
    static void prepare(@Autowired JdbcTemplate jdbcTemplate) throws Exception {
        jdbc = jdbcTemplate;
        try (var connection = jdbcTemplate.getDataSource().getConnection()) {
            assertEquals("career_business_test", connection.getCatalog(), "禁止在业务库执行集成测试");
        }
        cleanup();
        jdbc.update("""
                INSERT INTO app_user (id, email, password_hash, nickname, role, status)
                VALUES (?, ?, '{test}', '保存个人JD测试', 'USER', 'ACTIVE')
                """, USER, "save-personal@example.invalid");
        jdbc.update("""
                INSERT INTO job_source (id, name, type, verification_status)
                VALUES (?, 'AI 全网搜索', 'PUBLIC_SEARCH', 'UNVERIFIED')
                """, SOURCE_SOURCE);
        jdbc.update("""
                INSERT INTO job (id, source_id, owner_user_id, visibility, current_version, status, source_url)
                VALUES (?, ?, ?, 'PRIVATE', 1, 'ACTIVE', 'https://example.com/job/1')
                """, SOURCE_JOB, SOURCE_SOURCE, USER);
        jdbc.update("""
                INSERT INTO job_version (id, job_id, version_no, title, company_name, candidate_type,
                  jd_text, content_hash, requirements_json, analysis_meta_json)
                VALUES (?, ?, 1, '后端开发实习生', '示例公司', 'INTERNSHIP', ?, UNHEX(SHA2('jd', 256)), ?, ?)
                """, SOURCE_VERSION, SOURCE_JOB, "岗位职责：负责后端开发。任职要求：熟悉 Java。",
                "{\"skills\":[\"JAVA\"],\"requiredSkillLevels\":{}}",
                "{\"warnings\":[],\"aliases\":[]}");
    }

    @AfterAll
    static void cleanup() {
        jdbc.update("DELETE FROM job_version WHERE job_id IN (?, ?)", SOURCE_JOB, NEW_JOB);
        jdbc.update("DELETE FROM job WHERE id IN (?, ?)", SOURCE_JOB, NEW_JOB);
        jdbc.update("DELETE FROM job_source WHERE id IN (?, ?)", SOURCE_SOURCE, NEW_SOURCE);
        jdbc.update("DELETE FROM app_user WHERE id = ?", USER);
    }

    @Test
    void copiesSearchJobAndKeepsPersonalListIsolated() {
        assertNull(mapper.savedPersonalJobId(USER, SOURCE_JOB));

        assertEquals(1, mapper.insertSavedSource(NEW_SOURCE, "AI 全网搜索"));
        assertEquals(1, mapper.insertSavedJob(NEW_JOB, NEW_SOURCE, USER, "https://example.com/job/1"));
        assertEquals(1, mapper.copyJobVersion(NEW_VERSION, NEW_JOB, SOURCE_JOB, 1));

        assertNotNull(mapper.job(NEW_JOB, USER));
        assertEquals(SOURCE_JOB, jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(analysis_meta_json, '$.savedFromJobId')) FROM job_version WHERE job_id = ?",
                String.class, NEW_JOB));
        assertEquals(NEW_JOB, mapper.savedPersonalJobId(USER, SOURCE_JOB));

        List<?> personal = mapper.privatePage(USER, 10, 0);
        assertEquals(1, personal.size());
        assertEquals(1, mapper.privateCount(USER));
    }
}