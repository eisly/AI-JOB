package com.aijob.backend.interview;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.task.AiTask;
import com.aijob.backend.ability.AbilityService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL transactions, deliberately separate from mock-based callback tests. */
@SpringBootTest(properties = {
    "spring.datasource.url=${BUDGET_TEST_DB_URL:jdbc:mysql://127.0.0.1:3307/career_business_test?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true}",
    "app.agent.scheduler-enabled=false"
})
@EnabledIfSystemProperty(named = "integration-tests", matches = "true")
class InterviewQuestionBudgetIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired InterviewMapper mapper;
    @Autowired InterviewResultHandler handler;
    @Autowired JsonService json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.core.env.Environment environment;

    private String id() { return UUID.randomUUID().toString(); }

    private String seed(JdbcTemplate db, String rule) {
        String user = id(), resume = id(), document = id(), source = id(), job = id(), session = id();
        db.update("INSERT INTO app_user(id,email,password_hash,nickname) VALUES (?,?,?,'预算隔离测试')", user, user+"@example.invalid", "{test}");
        db.update("INSERT INTO resume(id,user_id,title) VALUES (?,?,'隔离测试')", document, user);
        db.update("""
            INSERT INTO resume_version(id,resume_id,user_id,version_no,file_name,storage_key,mime_type,size_bytes,sha256,status)
            VALUES (?,?,?,1,'synthetic.txt','budget-only','text/plain',1,UNHEX(SHA2('synthetic',256)),'CONFIRMED')
            """, resume, document, user);
        db.update("INSERT INTO job_source(id,name,type) VALUES (?,'隔离预算','USER_INPUT')", source);
        db.update("INSERT INTO job(id,source_id,owner_user_id,visibility,status) VALUES (?,?,?,'PRIVATE','ACTIVE')",job,source,user);
        db.update("""
            INSERT INTO job_version(id,job_id,version_no,title,company_name,candidate_type,jd_text,content_hash)
            VALUES (?,?,1,'Java','虚构测试','EXPERIENCED','Java',UNHEX(SHA2('Java',256)))
            """,id(),job);
        db.update("""
            INSERT INTO interview_session(id,user_id,job_id,job_version,resume_version_id,profile_version,
                input_snapshot_json,status,question_limit,current_question_no,current_follow_up_no,last_seq)
            VALUES (?,?,?,1,?,1,'{}','GENERATING',5,2,0,4)
            """,session,user,job,resume);
        if (rule != null) db.update("UPDATE interview_session SET question_count_rule=? WHERE id=?",rule,session);
        return session;
    }

    @Test
    void v16PreservesHistoricalRowsRejectsUnknownRuleAndRollbackOrderWorks() throws Exception {
        var source = jdbc.getDataSource();
        String url;
        try (var c = source.getConnection()) {
            assertEquals("career_business_test", c.getCatalog());
            url = c.getMetaData().getURL().replace("/career_business_test", "/budget_migration_test");
        }
        var isolated = new DriverManagerDataSource(url, environment.getRequiredProperty("spring.datasource.username"),
                environment.getRequiredProperty("spring.datasource.password"));
        try (var c = isolated.getConnection()) { assertEquals("budget_migration_test",c.getCatalog()); }
        var migration = new JdbcTemplate(isolated);
        var all = Flyway.configure().dataSource(isolated).locations("classpath:db/migration").cleanDisabled(false).load();
        // Only this explicitly named, disposable schema is reset. Never the configured business schema.
        all.clean();
        try {
            Flyway.configure().dataSource(isolated).locations("classpath:db/migration").target("15").load().migrate();
            String historical = seed(migration, null);
            all.migrate();
            String ddl = String.valueOf(migration.queryForMap("SHOW CREATE TABLE interview_session").get("Create Table"));
            assertTrue(ddl.contains("`question_count_rule` varchar(16) NOT NULL DEFAULT 'MAIN_ONLY'"));
            assertTrue(ddl.contains("ck_interview_session_rule"));
            assertEquals("MAIN_ONLY",migration.queryForObject("SELECT question_count_rule FROM interview_session WHERE id=?",String.class,historical));
            var rejected = assertThrows(org.springframework.dao.DataAccessException.class, () ->
                migration.update("UPDATE interview_session SET question_count_rule='UNKNOWN' WHERE id=?",historical));
            assertEquals(3819, ((java.sql.SQLException) rejected.getMostSpecificCause()).getErrorCode());
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                migration.update("UPDATE interview_session SET question_count_rule=NULL WHERE id=?",historical));
            migration.update("UPDATE interview_session SET question_count_rule='TOTAL_ASKED' WHERE id=?",historical);
            migration.execute("ALTER TABLE interview_session DROP CHECK ck_interview_session_rule");
            migration.execute("ALTER TABLE interview_session DROP COLUMN question_count_rule");
            assertFalse(String.valueOf(migration.queryForMap("SHOW CREATE TABLE interview_session").get("Create Table")).contains("question_count_rule"));
            assertEquals(1,migration.queryForObject("SELECT COUNT(*) FROM interview_session WHERE id=?",Integer.class,historical));
        } finally { all.clean(); }
    }

    @Test
    void twoTransactionsCompeteForLastSlotAndDuplicateCannotCreateSixthQuestion() throws Exception {
        try (var c = jdbc.getDataSource().getConnection()) { assertEquals("career_business_test",c.getCatalog()); }
        String session = seed(jdbc,"TOTAL_ASKED");
        String user = jdbc.queryForObject("SELECT user_id FROM interview_session WHERE id=?",String.class,session);
        String taskId=id(), question=id(), answer=id();
        jdbc.update("""
            INSERT INTO ai_task(id,user_id,operation,resource_type,resource_id,status,idempotency_key,
                input_versions_json,input_snapshot_json,deadline_at)
            VALUES (?,?,'INTERVIEW_TURN','INTERVIEW',?,'RUNNING',?,'{}','{}',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))
            """,taskId,user,session,id());
        for (int n=1;n<=4;n++) mapper.insertMessage(n==4?question:id(),session,n,"ASSISTANT",null,"已有问题",n<=3?1:2,n<=3?n-1:0,null,"[]",null);
        mapper.insertMessage(answer,session,5,"USER","TEXT","用于并发校验的回答",2,0,question,"[]",null);
        mapper.updateTaskStatus(session,"GENERATING",taskId);
        AiTask task = new AiTask(); task.setId(taskId); task.setResourceId(session); task.setUserId(user); task.setOperation("INTERVIEW_TURN");
        task.setInputSnapshotJson(json.stringify(Map.of("latestAnswer",Map.of("messageId",answer,"questionMessageId",question))));
        var result=json.tree(Map.of("action","FOLLOW_UP","question",Map.of("content","最后一个追问","questionNo",2,"followUpNo",1,"skillCodes",List.of()),
            "answerEvaluation",Map.of("evaluation",Map.of("evidenceStatus","INSUFFICIENT","rubricVersion",AbilityService.RUBRIC,"modelVersion","fixture","promptVersion","fixture"))));
        var tx = new TransactionTemplate(transactions);
        CountDownLatch ready=new CountDownLatch(2), start=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(2)) {
            var action=(java.util.concurrent.Callable<Boolean>) () -> tx.execute(status -> {
                ready.countDown();
                try { assertTrue(start.await(10,TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                try { handler.success(task,result); return true; }
                catch (BusinessException e) {
                    assertEquals("INVALID_STATE",e.getErrorKey());
                    status.setRollbackOnly(); return false;
                }
            });
            var first=executor.submit(action); var second=executor.submit(action);
            assertTrue(ready.await(10,TimeUnit.SECONDS)); start.countDown();
            assertNotEquals(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
        } finally { start.countDown(); }
        assertEquals(5,mapper.askedQuestions(session));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM interview_turn_evaluation WHERE interview_id=?",Integer.class,session));
        assertEquals("WAITING_ANSWER",mapper.session(session,user).get("status"));
        assertThrows(BusinessException.class, () -> tx.execute(status -> handler.success(task,result)));
        assertEquals(5,mapper.askedQuestions(session));
    }
}
