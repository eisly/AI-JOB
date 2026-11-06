package com.aijob.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接 application.yml 中配置的 career_business MySQL 库，
 * 对已建数据表做只读查询验证（demo_data.sql 已导入的前提下数据可断言）。
 */
@EnabledIfSystemProperty(named = "mysqlIt", matches = "true")
@SpringBootTest(properties = "spring.flyway.enabled=false")
class DatabaseQueryTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appUserTableQuery() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class);
        assertNotNull(count);

        List<Map<String, Object>> users = jdbcTemplate.queryForList(
                "SELECT id, email, nickname, role, status FROM app_user ORDER BY created_at, id");
        for (Map<String, Object> user : users) {
            System.out.println("app_user -> " + user);
        }

        // demo 数据应包含 1 个求职者 + 1 个管理员
        if (count >= 2) {
            assertTrue(users.stream().anyMatch(u -> "candidate@example.invalid".equals(u.get("email"))),
                    "应存在演示求职者账号");
        }
    }

    @Test
    void skillCatalogQuery() {
        List<Map<String, Object>> skills = jdbcTemplate.queryForList(
                "SELECT code, name, parent_code, enabled FROM skill_catalog ORDER BY code");
        skills.forEach(s -> System.out.println("skill_catalog -> " + s));
        assertFalse(skills.isEmpty(), "skill_catalog 不应为空（demo 数据应有 java/mysql/redis/spring）");

        List<String> codes = jdbcTemplate.queryForList(
                "SELECT code FROM skill_catalog WHERE enabled = 1", String.class);
        assertTrue(codes.containsAll(List.of("java", "mysql", "redis", "spring")));
    }

    @Test
    void jobAndJobVersionJoinQuery() {
        List<Map<String, Object>> jobs = jdbcTemplate.queryForList("""
                SELECT j.id, j.status, j.visibility,
                       v.title, v.company_name, v.city_code, v.candidate_type,
                       v.min_monthly_salary_cents, v.max_monthly_salary_cents
                FROM job j
                JOIN job_version v ON v.job_id = j.id AND v.version_no = j.current_version
                ORDER BY j.created_at, j.id
                """);
        jobs.forEach(j -> System.out.println("job + job_version -> " + j));
        assertFalse(jobs.isEmpty(), "job/job_version 联表查询应至少返回演示岗位");
    }

    @Test
    void jobFavoriteQuery() {
        List<Map<String, Object>> favorites = jdbcTemplate.queryForList("""
                SELECT f.id, f.user_id, jv.title
                FROM job_favorite f
                JOIN job j ON j.id = f.job_id
                JOIN job_version jv ON jv.job_id = j.id AND jv.version_no = j.current_version
                """);
        favorites.forEach(f -> System.out.println("job_favorite -> " + f));
    }

    @Test
    void questionQueryWithJsonColumn() {
        Map<String, Object> question = jdbcTemplate.queryForMap("""
                SELECT id, stem, difficulty, skill_codes_json, status
                FROM question
                WHERE status = 'ACTIVE'
                ORDER BY created_at, id
                LIMIT 1
                """);
        System.out.println("question -> " + question);
        assertNotNull(question.get("stem"));
    }

    @Test
    void everyBusinessTableIsQueryable() {
        // 动态从 information_schema 拿到全部基础表，逐一 COUNT(*) 验证建表与查询权限
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """, String.class);
        assertFalse(tables.isEmpty(), "career_business 库中应存在业务表");
        for (String table : tables) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Integer.class);
            System.out.printf("%-28s rows=%d%n", table, count == null ? -1 : count);
            assertNotNull(count, table + " COUNT 查询失败");
        }
        System.out.println("可查询表数量: " + tables.size());
    }
}
