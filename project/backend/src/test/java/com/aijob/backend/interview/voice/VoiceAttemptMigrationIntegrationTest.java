package com.aijob.backend.interview.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V22 识别尝试迁移验证：列定义、默认值、CHECK 约束与历史行兼容。
 * 只连接专用测试库，不触碰正式业务库。
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${VOICE_TEST_DB_URL:jdbc:mysql://127.0.0.1:3307/career_business_test?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true}",
    "app.agent.scheduler-enabled=false",
    "app.voice.consent-required=false"
})
@EnabledIfSystemProperty(named = "integration-tests", matches = "true")
class VoiceAttemptMigrationIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void databaseMustBeDedicatedTestSchema() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            String catalog = connection.getCatalog();
            assertNotNull(catalog);
            assertTrue(catalog.endsWith("_test"), "集成测试必须连接专用测试库，实际: " + catalog);
        }
    }

    @Test
    void attemptColumnIsUnsignedNotNullWithDefaultOne() {
        Map<String, Object> column = jdbc.queryForMap("""
                SELECT COLUMN_TYPE, COLUMN_DEFAULT, IS_NULLABLE
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='interview_voice_transcription'
                  AND COLUMN_NAME='attempt'
                """);
        assertEquals("int unsigned", String.valueOf(column.get("COLUMN_TYPE")).toLowerCase());
        assertEquals("NO", column.get("IS_NULLABLE"));
        assertEquals(1, Integer.parseInt(String.valueOf(column.get("COLUMN_DEFAULT"))));
    }

    @Test
    void attemptCheckConstraintIsPresentAndHistoricalRowsDefaultToOne() {
        String clause = jdbc.queryForObject("""
                SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='ck_voice_attempt'
                """, String.class);
        assertNotNull(clause);
        assertTrue(clause.contains("attempt"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM interview_voice_transcription WHERE attempt IS NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM interview_voice_transcription WHERE attempt < 1", Integer.class));
    }
}