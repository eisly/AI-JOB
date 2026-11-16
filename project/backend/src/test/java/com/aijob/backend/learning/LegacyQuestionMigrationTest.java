package com.aijob.backend.learning;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyQuestionMigrationTest {
    @Test
    void isolatesOnlyPreMigrationGeneratedQuestionsUsingFlywayTimestamp() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:legacy;MODE=MySQL");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE flyway_schema_history (version VARCHAR(20), installed_on TIMESTAMP, success BOOLEAN)");
            statement.execute("INSERT INTO flyway_schema_history VALUES ('12', '2026-11-14 10:00:00', TRUE)");
            statement.execute("CREATE TABLE question (id INT, source_type VARCHAR(20), generation_env VARCHAR(20), created_at TIMESTAMP)");
            statement.execute("INSERT INTO question VALUES (1, 'GENERATED', 'PROD', '2026-11-13 10:00:00'),"
                    + "(2, 'GENERATED', 'PROD', '2026-11-15 10:00:00'),"
                    + "(3, 'CURATED', 'PROD', '2026-11-13 10:00:00'),"
                    + "(4, 'GENERATED', 'FAKE', '2026-11-13 10:00:00')");
            statement.execute(Files.readString(Path.of("src/main/resources/db/migration/V13__mark_legacy_generated_questions.sql")));
            try (var rows = statement.executeQuery("SELECT generation_env FROM question ORDER BY id")) {
                for (String expected : new String[]{"LEGACY", "PROD", "PROD", "FAKE"}) {
                    rows.next();
                    assertEquals(expected, rows.getString(1));
                }
            }
        }
    }
}
