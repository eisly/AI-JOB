package com.aijob.backend.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.aijob.backend.ability.*;
import com.aijob.backend.common.*;
import com.aijob.backend.interview.*;
import com.aijob.backend.job.*;
import com.aijob.backend.learning.*;
import com.aijob.backend.profile.ProfileService;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/career_business_test?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
    "app.agent.scheduler-enabled=false"
})
@EnabledIfSystemProperty(named = "integration-tests", matches = "true")
@Transactional
class SevenCaseIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonService json;
    @Autowired TaskService tasks;
    @Autowired TaskMapper taskMapper;
    @Autowired TaskRetryService retries;
    @Autowired JobResultHandler jobs;
    @Autowired JobService jobService;
    @Autowired JobMapper jobMapper;
    @Autowired InterviewResultHandler interviews;
    @Autowired InterviewMapper interviewMapper;
    @Autowired InterviewService interviewService;
    @Autowired AbilityMapper evidence;
    @Autowired ProfileService profiles;
    @Autowired LearningMapper learning;
    @Autowired LearningService learningService;
    String user;
    String job;
    String resume;

    String id() { return UUID.randomUUID().toString(); }

    @BeforeEach
    void seedIsolatedData() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertEquals("career_business_test", connection.getCatalog());
        }
        user = id(); job = id(); resume = id();
        jdbc.update("INSERT INTO app_user (id,email,password_hash,nickname,role,status) VALUES (?,?,?,'七项集成测试','USER','ACTIVE')",
                user, user + "@example.invalid", "{test}");
        String document = id();
        jdbc.update("INSERT INTO resume (id,user_id,title) VALUES (?,?,'隔离测试简历')", document, user);
        jdbc.update("""
            INSERT INTO resume_version (id,resume_id,user_id,version_no,file_name,storage_key,mime_type,size_bytes,sha256,status)
            VALUES (?,?,?,1,'test.docx','test','application/test',1,UNHEX(SHA2('test',256)),'CONFIRMED')
            """, resume, document, user);
        String source = id();
        jdbc.update("INSERT INTO job_source (id,name,type) VALUES (?,'隔离测试','USER_INPUT')", source);
        jdbc.update("INSERT INTO job (id,source_id,owner_user_id,visibility,status) VALUES (?,?,?,'PRIVATE','FAILED')", job, source, user);
        jdbc.update("""
            INSERT INTO job_version (id,job_id,version_no,title,company_name,candidate_type,jd_text,content_hash)
            VALUES (?,?,1,'Java','虚构测试','EXPERIENCED','Java',UNHEX(SHA2('Java',256)))
            """, id(), job);
        profiles.get(user);
        jdbc.update("INSERT IGNORE INTO skill_catalog (code,name) VALUES ('JAVA','Java'),('MYSQL','MySQL')");
    }

    AiTask task(String operation, String resourceType, String resourceId, Map<String, Object> versions, Map<String, Object> payload) {
        String taskId = id();
        jdbc.update("""
            INSERT INTO ai_task (id,user_id,operation,resource_type,resource_id,status,idempotency_key,
                input_versions_json,input_snapshot_json,agent_run_id,deadline_at)
            VALUES (?,?,?,?,?,'RUNNING',?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))
            """, taskId, user, operation, resourceType, resourceId, id(), json.stringify(versions), json.stringify(payload), id());
        return taskMapper.find(taskId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPSTREAM_TIMEOUT", "MODEL_UNAVAILABLE", "BUSINESS_CONFLICT"})
    void failuresRetryWithOriginalSnapshotAndCannotDuplicate(String code) {
        AiTask original = task("JD_ANALYZE", "JOB", job, Map.of("jobVersion", 1), Map.of("jdText", "Java", "jobVersion", 1));
        var results = new TaskResultService(taskMapper, tasks, json, mock(AgentEventSyncService.class), List.of(jobs));
        if (code.equals("MODEL_UNAVAILABLE")) {
            results.transportFailure(original.getId(), "模拟服务不可用");
        } else {
            results.submit(original.getId(), Map.of("runId", original.getAgentRunId(), "operation", "JD_ANALYZE",
                    "status", "FAILED", "inputVersions", Map.of("jobVersion", 1),
                    "error", Map.of("code", code, "message", "模拟" + code, "retryable", true)));
        }
        assertEquals(code, ((Map<?, ?>) tasks.detail(user, original.getId()).get("error")).get("code"));
        String route = "/tasks/" + original.getId() + "/retry";
        retries.retry(user, original.getId(), id(), route);
        String replacementId = jdbc.queryForObject("SELECT id FROM ai_task WHERE retry_of_task_id=?", String.class, original.getId());
        AiTask replacement = taskMapper.find(replacementId);
        assertEquals(original.getInputSnapshotJson(), replacement.getInputSnapshotJson());
        assertEquals(original.getInputVersionsJson(), replacement.getInputVersionsJson());
        assertEquals(original.getId(), tasks.detail(user, replacementId).get("retryOfTaskId"));
        assertThrows(BusinessException.class, () -> retries.retry(user, original.getId(), id(), route));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE retry_of_task_id=?", Integer.class, original.getId()));
    }

    @Test
    void cancellationProtectsTerminalStateAndLateCallback() {
        AiTask task = task("JD_ANALYZE", "JOB", job, Map.of("jobVersion", 1), Map.of());
        var results = new TaskResultService(taskMapper, tasks, json, mock(AgentEventSyncService.class), List.of(jobs));
        assertEquals("CANCEL_REQUESTED", tasks.cancel(user, task.getId()).get("status"));
        results.completeCancellation(task.getId());
        assertEquals("CANCELLED", tasks.cancel(user, task.getId()).get("status"));
        assertEquals(false, results.submit(task.getId(), Map.of()).get("accepted"));
        assertEquals("CANCELLED", taskMapper.find(task.getId()).getStatus());
        AiTask succeeded = task("JD_ANALYZE", "JOB", job, Map.of(), Map.of());
        taskMapper.succeed(succeeded.getId(), "{}", json.digest(Map.of()));
        assertEquals("SUCCEEDED", tasks.cancel(user, succeeded.getId()).get("status"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SKILLS", "NO_SKILLS", "INVALID"})
    void actualInterviewPersistenceNeverInventsSkillScores(String mode) {
        String session = id();
        String question = id();
        String answer = id();
        jdbc.update("""
            INSERT INTO interview_session (id,user_id,job_id,job_version,resume_version_id,profile_version,
                input_snapshot_json,status,question_limit,current_question_no)
            VALUES (?,?,?,1,?,1,'{}','GENERATING',3,1)
            """, session, user, job, resume);
        interviewMapper.insertMessage(question, session, 1, "ASSISTANT", "Java问题", 1, 0, null, "[\"JAVA\",\"MYSQL\"]", null);
        interviewMapper.insertMessage(answer, session, 2, "USER", "测试回答", 1, 0, question, "[]", null);
        AiTask turn = task("INTERVIEW_TURN", "INTERVIEW", session, Map.of(),
                Map.of("latestAnswer", Map.of("messageId", answer, "questionMessageId", question)));
        Map<String, Object> evaluation = Map.of("evidenceStatus", mode.equals("INVALID") ? "INVALID" : "VALID",
                "rubricVersion", AbilityService.RUBRIC, "modelVersion", "fixture-only", "promptVersion", "fixture-v1",
                "dimensions", List.of(Map.of("key", "correctness", "score", 80, "weight", 0.5),
                Map.of("key", "completeness", "score", 60, "weight", 0.3),
                Map.of("key", "expression", "score", 90, "weight", 0.2)));
        List<Map<String, Object>> scores = mode.equals("SKILLS") ? List.of(Map.of("skillCode", "JAVA",
                "score", 61, "confidence", 0.8, "sourceId", answer)) : List.of();
        interviews.success(turn, json.tree(Map.of("action", "ASK", "question", Map.of("content", "下一题", "questionNo", 2,
                "followUpNo", 0, "skillCodes", List.of("JAVA")), "answerEvaluation", Map.of("evaluation", evaluation, "skillScores", scores))));
        assertEquals(mode.equals("SKILLS") ? 1 : 0, evidence.evidenceCount(user, null, "INTERVIEW_ANSWER"));
        if (mode.equals("SKILLS")) {
            var row = evidence.evidencePage(user, "JAVA", "INTERVIEW_ANSWER", 10, 0).getFirst();
            assertEquals("61.0", row.get("score").toString());
            assertEquals(0, evidence.evidenceCount(user, "MYSQL", "INTERVIEW_ANSWER"));
            assertEquals(1, ((Number) evidence.snapshots(user).getFirst().get("sample_count")).intValue());
        }
        AiTask report = task("INTERVIEW_REPORT", "INTERVIEW_REPORT", session, Map.of(), Map.of("completionType", "NORMAL"));
        interviewMapper.updateTaskStatus(session, "ENDING", report.getId());
        interviews.success(report, json.tree(Map.of("evaluation", evaluation)));
        Map<String, Object> saved = interviewService.report(user, session);
        assertEquals(mode.equals("SKILLS") ? "AVAILABLE" : mode.equals("INVALID") ? "NO_VALID_ANSWER" : "NO_SKILL_SCORES", saved.get("skillEvidenceStatus"));
        var result = (Map<?, ?>) saved.get("evaluation");
        if (mode.equals("INVALID")) assertNull(result.get("overallScore"));
        else assertEquals(76.0, ((Number) result.get("overallScore")).doubleValue());
        assertEquals(mode.equals("SKILLS") ? 1 : 0, evidence.evidenceCount(user, null, "INTERVIEW_ANSWER"));
    }

    @Test
    void readingCompletionRetainsFirstTimestampAndCreatesNoEvidence() {
        String plan = id(); String item = id();
        AiTask task = task("LEARNING_PLAN", "LEARNING_PLAN", plan, Map.of(), Map.of());
        learning.insertPlan(plan, user, null, "RESUME", resume, "JAVA", null, 1, "{}", task.getId(), 1, 30, 1);
        learning.insertItem(item, plan, 1, "JAVA", "阅读验收", "READING", "BASIC", "理解", "基础", "阅读",
                "读完并记录自评", "PENDING", null, "[]", 10, "[]");
        learningService.completeReading(user, plan, item);
        Object first = learning.item(plan, item, user).get("completed_at");
        assertNotNull(first);
        learningService.completeReading(user, plan, item);
        learning.updateItemStatus(item, "COMPLETED");
        assertEquals(first, learning.item(plan, item, user).get("completed_at"));
        assertEquals(0, evidence.evidenceCount(user, null, null));
        assertThrows(BusinessException.class, () -> learningService.completeReading(id(), plan, item));
    }

    @Test
    void ownerCanTraceOriginalAndCorrectedJdButOtherUserCannotReadHistory() {
        jobService.correctPrivateJob(user, job, Map.of("expectedJobVersion", 1, "cityCode", "440100",
                "skillCodes", List.of("JAVA"), "sourceQuote", "Java", "reason", "人工补全测试"));
        var detail = jobService.job(user, job);
        assertNotNull(detail.get("revisions"));
        var revisions = (List<Map<String, Object>>) detail.get("revisions");
        assertEquals(2, revisions.size());
        assertEquals("人工补全测试", revisions.get(1).get("reason"));
        assertEquals("Java", revisions.get(1).get("sourceQuote"));
        assertThrows(BusinessException.class, () -> jobService.job(id(), job));
    }

    @Test
    void firstManualConfirmationCanKeepCorrectExtractedFields() {
        var requirements = new java.util.LinkedHashMap<String, Object>();
        requirements.put("skills", List.of("JAVA"));
        requirements.put("requiredSkillLevels", Map.of());
        requirements.put("quotes", Map.of());
        for (String field : List.of("educationMin", "graduationYearMin", "graduationYearMax", "experienceMonthsMin")) requirements.put(field, null);
        jobMapper.analyzed(job, 1, json.stringify(requirements), "{}", null, null, null);
        jobMapper.updateJobStatus(job, "ACTIVE");
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("expectedJobVersion", 1); body.put("cityCode", null);
        body.put("skillCodes", List.of("JAVA")); body.put("sourceQuote", "Java"); body.put("reason", "原解析正确，人工核对确认");
        assertEquals(2, ((Number) jobService.correctPrivateJob(user, job, body).get("jobVersion")).intValue());
        body.put("expectedJobVersion", 2);
        assertThrows(BusinessException.class, () -> jobService.correctPrivateJob(user, job, body));
    }
}
