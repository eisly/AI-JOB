package com.aijob.backend.interview.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.interview.InterviewMapper;
import com.aijob.backend.interview.InterviewService;

/**
 * 真实 MySQL + 真实事务 + 受控供应商替身验证"结果晚到保护"。
 *
 * <p>供应商替身只模拟延迟与乱序，不调用付费供应商；并发和状态变化全部落在真实数据库事务中。
 * 使用专用测试库（默认 career_business_test），不触碰正式业务库。
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${VOICE_TEST_DB_URL:jdbc:mysql://127.0.0.1:3307/career_business_test?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true}",
    "app.agent.scheduler-enabled=false",
    "app.voice.consent-required=false"
})
@EnabledIfSystemProperty(named = "integration-tests", matches = "true")
class VoiceLateArrivalIntegrationTest {

    private static final byte[] AUDIO = new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 11, 22, 33, 44};
    private static final byte[] AUDIO_B = new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 55, 66, 77, 88};
    private static final int DURATION_MS = 9000;

    @Autowired JdbcTemplate jdbc;
    @Autowired InterviewMapper interviews;
    @Autowired VoiceMapper voice;
    @Autowired VoiceTranscriptionService service;
    @Autowired InterviewService interviewService;
    @MockitoBean SpeechAgentClient speech;
    @MockitoBean VoiceRateLimiter rateLimiter;

    private final BlockingQueue<Call> calls = new LinkedBlockingQueue<>();
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        calls.clear();
        pool = Executors.newFixedThreadPool(3);
        when(speech.transcribe(any(), any(), anyInt(), any(), any())).thenAnswer(invocation -> {
            Call call = new Call();
            calls.add(call);
            call.started.countDown();
            if (!call.release.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("受控供应商未被放行");
            }
            if (call.failure != null) throw call.failure;
            return call.response;
        });
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void normalResultSavesExactlyOnceAndDoesNotTouchSession() throws Exception {
        Fixture fixture = seed();
        Future<Map<String, Object>> future = submit(fixture, AUDIO);
        Call call = nextCall();
        call.response = text("正常识别结果");
        call.release.countDown();

        Map<String, Object> result = future.get(20, TimeUnit.SECONDS);
        assertEquals("SUCCEEDED", result.get("status"));
        assertEquals("正常识别结果", result.get("text"));
        assertEquals(1, ((Number) result.get("attempt")).intValue());
        assertEquals("SUCCEEDED", record(fixture).get("status"));
        assertEquals("正常识别结果", record(fixture).get("text"));
        assertEquals(1, askedQuestions(fixture));
        assertEquals(0, userAnswers(fixture));
        assertEquals(1, ((Number) session(fixture).get("version")).intValue());
    }

    @Test
    void concurrentSameAudioCallsProviderOnlyOnce() throws Exception {
        Fixture fixture = seed();
        Future<Map<String, Object>> first = submit(fixture, AUDIO);
        Call call = nextCall();

        BusinessException busy = assertThrows(BusinessException.class,
                () -> service.transcribe(fixture.user(), fixture.session(), file(AUDIO), fixture.question(), 1,
                        DURATION_MS, "audio/webm"));
        assertEquals("TASK_IN_PROGRESS", busy.getErrorKey());
        assertEquals(1, recordCount(fixture));

        call.response = text("并发只调用一次");
        call.release.countDown();
        assertEquals("SUCCEEDED", first.get(20, TimeUnit.SECONDS).get("status"));
        assertNull(calls.poll(500, TimeUnit.MILLISECONDS), "第二个请求不应调用供应商");
        assertEquals(1, recordCount(fixture));
        assertEquals(1, askedQuestions(fixture));
    }

    @Test
    void answerSubmittedInAnotherTabMakesLateResultStale() throws Exception {
        Fixture fixture = seed();
        Future<Map<String, Object>> future = submit(fixture, AUDIO);
        Call call = nextCall();

        // 另一标签页经真实提交链路完成本题：会话进入 GENERATING，版本推进，下一题由 Agent 回调生成。
        Map<String, Object> body = Map.of(
                "questionMessageId", fixture.question(),
                "content", "另一标签页的文字回答",
                "expectedVersion", 1,
                "answerSource", "TEXT");
        interviewService.answer(fixture.user(), fixture.session(),
                "/api/v1/interviews/" + fixture.session() + "/messages", UUID.randomUUID().toString(), body);
        assertEquals("GENERATING", session(fixture).get("status"));
        assertEquals(2, ((Number) session(fixture).get("version")).intValue());
        assertEquals(1, userAnswers(fixture));

        call.response = text("旧题结果");
        call.release.countDown();

        BusinessException stale = awaitBusiness(future);
        assertEquals("STALE_INPUT", stale.getErrorKey());
        assertEquals("QUESTION_CHANGED", stale.getDetails().get("reason"));
        assertEquals("DISCARDED", record(fixture).get("status"));
        assertNull(record(fixture).get("text"));
        assertEquals(1, userAnswers(fixture), "晚到结果不得新增正式回答");
        assertEquals(1, askedQuestions(fixture), "晚到结果不得增加题数");
    }

    @Test
    void endingInterviewKeepsRecordDiscardedAndSessionEnding() throws Exception {
        Fixture fixture = seed();
        Future<Map<String, Object>> future = submit(fixture, AUDIO);
        Call call = nextCall();

        // 另一标签页经真实结束链路结束面试：会话进入 ENDING 并创建报告任务。
        interviewService.end(fixture.user(), fixture.session(),
                "/api/v1/interviews/" + fixture.session() + "/end", UUID.randomUUID().toString(),
                Map.of("expectedVersion", 1));
        assertEquals("ENDING", session(fixture).get("status"));
        assertEquals(1, reportTaskCount(fixture));

        call.response = text("结束后晚到的结果");
        call.release.countDown();

        BusinessException stale = awaitBusiness(future);
        assertEquals("STALE_INPUT", stale.getErrorKey());
        assertEquals("DISCARDED", record(fixture).get("status"));
        assertEquals("ENDING", session(fixture).get("status"));
        assertEquals(0, userAnswers(fixture));
        assertEquals(1, askedQuestions(fixture));
    }

    @Test
    void discardedRecordCannotBeRevivedByLateResult() throws Exception {
        Fixture fixture = seed();
        Future<Map<String, Object>> future = submit(fixture, AUDIO);
        Call call = nextCall();

        String id = recordId(fixture);
        service.discard(fixture.user(), fixture.session(), id);
        assertEquals("DISCARDED", record(fixture).get("status"));

        call.response = text("放弃后晚到的结果");
        call.release.countDown();

        BusinessException stale = awaitBusiness(future);
        assertEquals("STALE_INPUT", stale.getErrorKey());
        assertEquals("DISCARDED", record(fixture).get("status"));
        assertNull(record(fixture).get("text"));
        assertEquals(1, recordCount(fixture));
    }

    @Test
    void expiredRecordStaysExpiredWhenLateResultArrives() throws Exception {
        Fixture fixture = seed();
        Future<Map<String, Object>> future = submit(fixture, AUDIO);
        Call call = nextCall();

        jdbc.update("UPDATE interview_voice_transcription SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=?",
                recordId(fixture));
        call.response = text("过期后晚到的结果");
        call.release.countDown();

        BusinessException stale = awaitBusiness(future);
        assertEquals("STALE_INPUT", stale.getErrorKey());
        assertEquals("TRANSCRIPTION_EXPIRED", stale.getDetails().get("reason"));
        assertEquals("EXPIRED", record(fixture).get("status"));
        assertNull(record(fixture).get("text"));
    }

    @Test
    void oldAttemptSuccessCannotOverwriteRetryAttempt() throws Exception {
        Fixture fixture = seed();
        Future<Map<String, Object>> oldAttempt = submit(fixture, AUDIO);
        Call oldCall = nextCall();

        // 第一次请求超过 60 秒新鲜窗口仍 PENDING，用户重试同一音频：复用记录并递增 attempt。
        stalePending(fixture);
        Future<Map<String, Object>> retry = submit(fixture, AUDIO);
        Call retryCall = nextCall();
        assertEquals(2, ((Number) record(fixture).get("attempt")).intValue());

        oldCall.response = text("旧尝试结果");
        oldCall.release.countDown();
        BusinessException stale = awaitBusiness(oldAttempt);
        assertEquals("STALE_INPUT", stale.getErrorKey());
        assertEquals("PENDING", record(fixture).get("status"));
        assertEquals(2, ((Number) record(fixture).get("attempt")).intValue());
        assertNull(record(fixture).get("text"));

        retryCall.response = text("新尝试结果");
        retryCall.release.countDown();
        Map<String, Object> saved = retry.get(20, TimeUnit.SECONDS);
        assertEquals("SUCCEEDED", saved.get("status"));
        assertEquals("新尝试结果", saved.get("text"));
        assertEquals(2, ((Number) saved.get("attempt")).intValue());
        assertEquals(1, askedQuestions(fixture));
        assertEquals(0, userAnswers(fixture));
    }

    @Test
    void oldAttemptFailureCannotOverwriteNewSuccess() throws Exception {
        Fixture fixture = seed();
        Future<Map<String, Object>> oldAttempt = submit(fixture, AUDIO);
        Call oldCall = nextCall();

        stalePending(fixture);
        Future<Map<String, Object>> retry = submit(fixture, AUDIO);
        Call retryCall = nextCall();

        retryCall.response = text("新成功结果");
        retryCall.release.countDown();
        assertEquals("SUCCEEDED", retry.get(20, TimeUnit.SECONDS).get("status"));

        oldCall.failure = BusinessException.dependencyUnavailable("模拟识别超时", Map.of("reason", "TIMEOUT"));
        oldCall.release.countDown();
        BusinessException stale = awaitBusiness(oldAttempt);
        assertEquals("STALE_INPUT", stale.getErrorKey());

        assertEquals("SUCCEEDED", record(fixture).get("status"));
        assertEquals("新成功结果", record(fixture).get("text"));
        assertNull(record(fixture).get("error_key"));
        assertEquals(2, ((Number) record(fixture).get("attempt")).intValue());
    }

    @Test
    void duplicateVoiceSubmitStoresSingleAnswer() throws Exception {
        Fixture fixture = seed();
        String transcriptionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO interview_voice_transcription
                  (id,user_id,interview_id,question_message_id,interview_version,status,text,mime_type,byte_size,
                   duration_ms,audio_sha256,provider,expires_at,attempt)
                VALUES (?,?,?,?,1,'SUCCEEDED','语音回答','audio/webm',8,9000,SHA2('submit',256),'stub',
                   DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1800 SECOND),1)
                """, transcriptionId, fixture.user(), fixture.session(), fixture.question());

        Map<String, Object> body = Map.of(
                "questionMessageId", fixture.question(),
                "content", "语音回答",
                "expectedVersion", 1,
                "answerSource", "VOICE",
                "voiceTranscriptionId", transcriptionId);
        String route = "/api/v1/interviews/" + fixture.session() + "/messages";
        String key = UUID.randomUUID().toString();

        interviewService.answer(fixture.user(), fixture.session(), route, key, body);
        assertEquals(1, userAnswers(fixture));
        assertEquals(1, taskCount(fixture));
        Map<String, Object> consumed = jdbc.queryForMap(
                "SELECT consumed_message_id FROM interview_voice_transcription WHERE id=?", transcriptionId);
        assertNotNull(consumed.get("consumed_message_id"));

        interviewService.answer(fixture.user(), fixture.session(), route, key, body);
        assertEquals(1, userAnswers(fixture));
        assertEquals(1, taskCount(fixture));
        assertEquals(1, askedQuestions(fixture));
        assertTrue(((String) session(fixture).get("status")).equals("GENERATING"));
    }

    @Test
    void lateEmptyResultCannotReviveDiscardedRecord() throws Exception {
        Fixture fixture = seed();
        Future<Map<String, Object>> future = submit(fixture, AUDIO);
        Call call = nextCall();

        Map<String, Object> body = Map.of(
                "questionMessageId", fixture.question(),
                "content", "另一标签页的文字回答",
                "expectedVersion", 1,
                "answerSource", "TEXT");
        interviewService.answer(fixture.user(), fixture.session(),
                "/api/v1/interviews/" + fixture.session() + "/messages", UUID.randomUUID().toString(), body);

        call.response = Map.of("text", "  ", "provider", "stub");
        call.release.countDown();

        BusinessException stale = awaitBusiness(future);
        assertEquals("STALE_INPUT", stale.getErrorKey());
        assertEquals("QUESTION_CHANGED", stale.getDetails().get("reason"));
        assertEquals("DISCARDED", record(fixture).get("status"), "空识别结果不得把已废弃记录改回 EMPTY");
        assertNull(record(fixture).get("text"));
        assertEquals(1, userAnswers(fixture));
    }

    @Test
    void retryAfterDiscardCreatesNewAttempt() throws Exception {
        Fixture fixture = seed();
        Future<Map<String, Object>> oldAttempt = submit(fixture, AUDIO);
        Call oldCall = nextCall();

        service.discard(fixture.user(), fixture.session(), recordId(fixture));
        assertEquals("DISCARDED", record(fixture).get("status"));

        Future<Map<String, Object>> retry = submit(fixture, AUDIO);
        Call retryCall = nextCall();
        assertEquals(2, ((Number) record(fixture).get("attempt")).intValue());
        assertEquals("PENDING", record(fixture).get("status"));

        oldCall.response = text("放弃前旧请求结果");
        oldCall.release.countDown();
        assertEquals("STALE_INPUT", awaitBusiness(oldAttempt).getErrorKey());
        assertEquals("PENDING", record(fixture).get("status"));
        assertEquals(2, ((Number) record(fixture).get("attempt")).intValue());
        assertNull(record(fixture).get("text"));

        retryCall.response = text("重试后的新结果");
        retryCall.release.countDown();
        Map<String, Object> saved = retry.get(20, TimeUnit.SECONDS);
        assertEquals("SUCCEEDED", saved.get("status"));
        assertEquals(2, ((Number) saved.get("attempt")).intValue());
        assertEquals(1, recordCount(fixture), "重试复用同一记录，不得新增记录");
        assertEquals(1, askedQuestions(fixture));
    }

    @Test
    void consumedAudioCannotBeReusedForTranscription() throws Exception {
        Fixture fixture = seed();
        insertConsumedSucceeded(fixture, AUDIO);
        assertEquals(1, recordCount(fixture));

        BusinessException stale = assertThrows(BusinessException.class,
                () -> service.transcribe(fixture.user(), fixture.session(), file(AUDIO), fixture.question(), 1,
                        DURATION_MS, "audio/webm"));
        assertEquals("STALE_INPUT", stale.getErrorKey());
        assertEquals("TRANSCRIPTION_CONSUMED", stale.getDetails().get("reason"));
        assertNull(calls.poll(500, TimeUnit.MILLISECONDS), "已消费记录不得再次调用供应商");
        assertEquals(1, recordCount(fixture));
        assertEquals(1, userAnswers(fixture), "重传不得新增正式回答");
    }

    @Test
    void stalePendingOfSameQuestionIsConvergedBeforeTakeover() throws Exception {
        Fixture fixture = seed();
        Future<Map<String, Object>> first = submit(fixture, AUDIO);
        Call firstCall = nextCall();
        String firstId = recordId(fixture);

        // 模拟旧请求超过 60 秒新鲜窗口仍在 PENDING。
        jdbc.update("UPDATE interview_voice_transcription SET updated_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 2 MINUTE) WHERE id=?",
                firstId);

        Future<Map<String, Object>> second = submit(fixture, AUDIO_B);
        Call secondCall = nextCall();
        assertEquals("DISCARDED", statusById(firstId));
        assertEquals(1, pendingCount(fixture), "同一题目至多保留一条 PENDING");

        firstCall.response = text("被收敛的旧结果");
        firstCall.release.countDown();
        assertEquals("STALE_INPUT", awaitBusiness(first).getErrorKey());
        assertEquals("DISCARDED", statusById(firstId), "旧请求晚到不得复活已收敛记录");

        secondCall.response = text("新音频结果");
        secondCall.release.countDown();
        assertEquals("SUCCEEDED", second.get(20, TimeUnit.SECONDS).get("status"));
        assertEquals(1, succeededCount(fixture), "同题最终只允许一条 SUCCEEDED");
        assertEquals(1, askedQuestions(fixture));
    }

    @Test
    void expiredRecordIgnoresSuccessEmptyAndFailure() throws Exception {
        for (String kind : List.of("success", "empty", "failure")) {
            Fixture fixture = seed();
            Future<Map<String, Object>> future = submit(fixture, AUDIO);
            Call call = nextCall();

            jdbc.update("UPDATE interview_voice_transcription SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=?",
                    recordId(fixture));
            if ("failure".equals(kind)) {
                call.failure = BusinessException.dependencyUnavailable("模拟识别失败", Map.of("reason", "TIMEOUT"));
            } else if ("empty".equals(kind)) {
                call.response = Map.of("text", "", "provider", "stub");
            } else {
                call.response = text("过期后的成功结果");
            }
            call.release.countDown();

            BusinessException stale = awaitBusiness(future);
            assertEquals("TRANSCRIPTION_EXPIRED", stale.getDetails().get("reason"), kind);
            assertEquals("EXPIRED", record(fixture).get("status"), kind);
            assertNull(record(fixture).get("text"), kind);
            assertNull(record(fixture).get("error_key"), kind);
            assertEquals(0, userAnswers(fixture), kind);
        }
    }

    @Test
    void latestTranscriptionUsesUpdatedAtThenAttempt() throws Exception {
        Fixture fixture = seed();
        String newAttempt = id();
        String oldAttempt = id();
        insertSucceeded(fixture, newAttempt, "新尝试文本", sha256(AUDIO), 2,
                "DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 10 MINUTE)", "UTC_TIMESTAMP(3)");
        insertSucceeded(fixture, oldAttempt, "旧尝试文本", sha256(AUDIO_B), 1,
                "DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 MINUTE)", "DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 5 MINUTE)");

        Map<String, Object> latest = service.latest(fixture.user(), fixture.session(), fixture.question());
        assertNotNull(latest);
        assertEquals("新尝试文本", latest.get("text"), "刷新恢复必须取 updated_at/attempt 最新的记录");
        assertEquals(2, ((Number) latest.get("attempt")).intValue());
    }

    @Test
    void anotherUserCannotTranscribe() throws Exception {
        Fixture fixture = seed();
        String other = id();
        jdbc.update("INSERT INTO app_user(id,email,password_hash,nickname) VALUES (?,?,?,'越权用户')",
                other, other + "@example.invalid", "{test}");

        BusinessException notFound = assertThrows(BusinessException.class,
                () -> service.transcribe(other, fixture.session(), file(AUDIO), fixture.question(), 1,
                        DURATION_MS, "audio/webm"));
        assertEquals("RESOURCE_NOT_FOUND", notFound.getErrorKey());
        assertEquals(0, recordCount(fixture));
        assertNull(calls.poll(500, TimeUnit.MILLISECONDS), "越权请求不得调用供应商");
    }

    @Test
    void concurrentDifferentIdempotencyKeysConsumeTranscriptionOnce() throws Exception {
        Fixture fixture = seed();
        String transcriptionId = insertUsableSucceeded(fixture, AUDIO);
        String route = "/api/v1/interviews/" + fixture.session() + "/messages";
        CyclicBarrier barrier = new CyclicBarrier(2);

        Future<String> left = pool.submit(() -> submitAnswer(fixture, route, UUID.randomUUID().toString(),
                transcriptionId, barrier));
        Future<String> right = pool.submit(() -> submitAnswer(fixture, route, UUID.randomUUID().toString(),
                transcriptionId, barrier));
        String leftResult = left.get(20, TimeUnit.SECONDS);
        String rightResult = right.get(20, TimeUnit.SECONDS);

        assertTrue("OK".equals(leftResult) || "OK".equals(rightResult), "至少一方提交成功: " + leftResult + "/" + rightResult);
        assertTrue(!"OK".equals(leftResult) || !"OK".equals(rightResult), "同一转写只能被一次提交消费");
        assertEquals(1, userAnswers(fixture));
        assertEquals(1, taskCount(fixture));
        String messageId = jdbc.queryForObject(
                "SELECT id FROM interview_message WHERE interview_id=? AND role='USER'", String.class, fixture.session());
        String consumed = jdbc.queryForObject(
                "SELECT consumed_message_id FROM interview_voice_transcription WHERE id=?", String.class, transcriptionId);
        assertEquals(messageId, consumed);
    }

    // ------------------------------------------------------------------

    private Future<Map<String, Object>> submit(Fixture fixture, byte[] audio) {
        return pool.submit(() -> service.transcribe(fixture.user(), fixture.session(), file(audio),
                fixture.question(), 1, DURATION_MS, "audio/webm"));
    }

    private static MockMultipartFile file(byte[] audio) {
        return new MockMultipartFile("audio", "answer.webm", "audio/webm", audio);
    }

    private Call nextCall() throws InterruptedException {
        Call call = calls.poll(15, TimeUnit.SECONDS);
        assertNotNull(call, "供应商没有被调用");
        return call;
    }

    private static BusinessException awaitBusiness(Future<?> future) throws Exception {
        ExecutionException error = assertThrows(ExecutionException.class, () -> future.get(20, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof BusinessException, "应为业务异常，实际: " + error.getCause());
        return (BusinessException) error.getCause();
    }

    private void stalePending(Fixture fixture) {
        jdbc.update("UPDATE interview_voice_transcription SET updated_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 2 MINUTE) WHERE id=?",
                recordId(fixture));
    }

    private String submitAnswer(Fixture fixture, String route, String key, String transcriptionId,
                                CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            interviewService.answer(fixture.user(), fixture.session(), route, key, Map.of(
                    "questionMessageId", fixture.question(),
                    "content", "并发语音回答",
                    "expectedVersion", 1,
                    "answerSource", "VOICE",
                    "voiceTranscriptionId", transcriptionId));
            return "OK";
        } catch (BusinessException ex) {
            return ex.getErrorKey();
        } catch (Exception ex) {
            return "ERR:" + ex.getClass().getSimpleName();
        }
    }

    private void insertConsumedSucceeded(Fixture fixture, byte[] audio) {
        String messageId = id();
        interviews.insertMessage(messageId, fixture.session(), 2, "USER", "VOICE", "已消费的语音回答", 1, 0,
                fixture.question(), "[]", null);
        jdbc.update("""
                INSERT INTO interview_voice_transcription
                  (id,user_id,interview_id,question_message_id,interview_version,status,text,mime_type,byte_size,
                   duration_ms,audio_sha256,provider,expires_at,consumed_at,consumed_message_id,attempt)
                VALUES (?,?,?,?,1,'SUCCEEDED','已消费的识别文本','audio/webm',?,9000,?,'stub',
                   DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1800 SECOND),UTC_TIMESTAMP(3),?,1)
                """, id(), fixture.user(), fixture.session(), fixture.question(), audio.length, sha256(audio), messageId);
    }

    private String insertUsableSucceeded(Fixture fixture, byte[] audio) {
        String id = id();
        jdbc.update("""
                INSERT INTO interview_voice_transcription
                  (id,user_id,interview_id,question_message_id,interview_version,status,text,mime_type,byte_size,
                   duration_ms,audio_sha256,provider,expires_at,attempt)
                VALUES (?,?,?,?,1,'SUCCEEDED','并发语音回答','audio/webm',?,9000,?,'stub',
                   DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1800 SECOND),1)
                """, id, fixture.user(), fixture.session(), fixture.question(), audio.length, sha256(audio));
        return id;
    }

    private void insertSucceeded(Fixture fixture, String id, String text, String sha, int attempt,
                                 String createdAtSql, String updatedAtSql) {
        jdbc.update("""
                INSERT INTO interview_voice_transcription
                  (id,created_at,updated_at,user_id,interview_id,question_message_id,interview_version,status,text,
                   mime_type,byte_size,duration_ms,audio_sha256,provider,expires_at,attempt)
                VALUES (?,%s,%s,?,?,?,1,'SUCCEEDED',?,'audio/webm',8,9000,?,'stub',
                   DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1800 SECOND),?)
                """.formatted(createdAtSql, updatedAtSql),
                id, fixture.user(), fixture.session(), fixture.question(), text, sha, attempt);
    }

    private String statusById(String id) {
        return jdbc.queryForObject("SELECT status FROM interview_voice_transcription WHERE id=?", String.class, id);
    }

    private int pendingCount(Fixture fixture) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM interview_voice_transcription
                WHERE interview_id=? AND question_message_id=? AND status='PENDING'
                """, Integer.class, fixture.session(), fixture.question());
    }

    private int succeededCount(Fixture fixture) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM interview_voice_transcription
                WHERE interview_id=? AND question_message_id=? AND status='SUCCEEDED'
                """, Integer.class, fixture.session(), fixture.question());
    }

    private static String sha256(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String recordId(Fixture fixture) {
        return jdbc.queryForObject("""
                SELECT id FROM interview_voice_transcription
                WHERE interview_id=? AND question_message_id=? ORDER BY created_at DESC LIMIT 1
                """, String.class, fixture.session(), fixture.question());
    }

    private Map<String, Object> record(Fixture fixture) {
        return jdbc.queryForMap("SELECT * FROM interview_voice_transcription WHERE id=?", recordId(fixture));
    }

    private int recordCount(Fixture fixture) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM interview_voice_transcription WHERE interview_id=?",
                Integer.class, fixture.session());
    }

    private int askedQuestions(Fixture fixture) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM interview_message WHERE interview_id=? AND role='ASSISTANT'",
                Integer.class, fixture.session());
    }

    private int userAnswers(Fixture fixture) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM interview_message WHERE interview_id=? AND role='USER'",
                Integer.class, fixture.session());
    }

    private int taskCount(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_task WHERE resource_id=? AND operation='INTERVIEW_TURN'",
                Integer.class, fixture.session());
    }

    private int reportTaskCount(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_task WHERE resource_id=? AND operation='INTERVIEW_REPORT'",
                Integer.class, fixture.session());
    }

    private Map<String, Object> session(Fixture fixture) {
        return jdbc.queryForMap("SELECT * FROM interview_session WHERE id=?", fixture.session());
    }

    private static Map<String, Object> text(String value) {
        return Map.of("text", value, "provider", "stub");
    }

    private record Fixture(String user, String session, String question) {
    }

    private static final class Call {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile Map<String, Object> response;
        volatile RuntimeException failure;
    }

    private Fixture seed() {
        String user = id();
        String document = id();
        String resume = id();
        String source = id();
        String job = id();
        String session = id();
        jdbc.update("INSERT INTO app_user(id,email,password_hash,nickname) VALUES (?,?,?,'语音晚到测试')",
                user, user + "@example.invalid", "{test}");
        jdbc.update("INSERT INTO resume(id,user_id,title) VALUES (?,?,'语音隔离测试')", document, user);
        jdbc.update("""
                INSERT INTO resume_version(id,resume_id,user_id,version_no,file_name,storage_key,mime_type,size_bytes,sha256,status)
                VALUES (?,?,?,1,'synthetic.txt','voice-only','text/plain',1,UNHEX(SHA2('synthetic',256)),'CONFIRMED')
                """, resume, document, user);
        jdbc.update("INSERT INTO job_source(id,name,type) VALUES (?,'语音隔离','USER_INPUT')", source);
        jdbc.update("INSERT INTO job(id,source_id,owner_user_id,visibility,status) VALUES (?,?,?,'PRIVATE','ACTIVE')",
                job, source, user);
        jdbc.update("""
                INSERT INTO job_version(id,job_id,version_no,title,company_name,candidate_type,jd_text,content_hash)
                VALUES (?,?,1,'Java','虚构测试','EXPERIENCED','Java',UNHEX(SHA2('Java',256)))
                """, id(), job);
        jdbc.update("""
                INSERT INTO interview_session
                  (id,user_id,job_id,job_version,resume_version_id,profile_version,input_snapshot_json,status,
                   question_limit,current_question_no,current_follow_up_no,last_seq,version)
                VALUES (?,?,?,1,?,1,
                   JSON_OBJECT('profile',JSON_OBJECT('profileVersion',1),
                               'job',JSON_OBJECT('id',?,'jobVersion',1,'jdText','Java')),
                   'WAITING_ANSWER',5,1,0,1,1)
                """, session, user, job, resume, job);
        String question = id();
        interviews.insertMessage(question, session, 1, "ASSISTANT", null, "请介绍你的项目经验", 1, 0, null, "[]",
                null);
        return new Fixture(user, session, question);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}