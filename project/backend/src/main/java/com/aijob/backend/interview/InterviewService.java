package com.aijob.backend.interview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.Data;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.common.PageResult;
import com.aijob.backend.job.JobMapper;
import com.aijob.backend.learning.LearningMapper;
import com.aijob.backend.interview.voice.VoiceMapper;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.resume.ResumeMapper;
import com.aijob.backend.task.AiTask;
import com.aijob.backend.task.TaskMapper;
import com.aijob.backend.task.TaskService;

@Service
public class InterviewService {

    private final InterviewMapper mapper;
    private final JobMapper jobs;
    private final ResumeMapper resumes;
    private final LearningMapper learning;
    private final ProfileService profiles;
    private final AbilityService ability;
    private final TaskService tasks;
    private final TaskMapper taskMapper;
    private final JsonService json;
    private final ApiView view;
    private final VoiceMapper voice;

    public InterviewService(InterviewMapper mapper, JobMapper jobs, ResumeMapper resumes, LearningMapper learning,
                            ProfileService profiles, AbilityService ability, TaskService tasks, TaskMapper taskMapper,
                            JsonService json, ApiView view, VoiceMapper voice) {
        this.mapper = mapper;
        this.jobs = jobs;
        this.resumes = resumes;
        this.learning = learning;
        this.profiles = profiles;
        this.ability = ability;
        this.tasks = tasks;
        this.taskMapper = taskMapper;
        this.json = json;
        this.view = view;
        this.voice = voice;
    }

    @Transactional
    public ResponseEntity<CommonResult<Object>> create(String userId, String route, String key, Map<String, Object> body) {
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        profiles.locked(userId);
        if (mapper.activeCount(userId) > 0) {
            throw BusinessException.conflict("每位用户最多一个活动面试", "INVALID_STATE");
        }
        String jobId = Data.uuid(body, "jobId", true);
        String resumeId = Data.uuid(body, "resumeVersionId", true);
        int limit = Data.integer(body, "questionLimit", 5, 3, 10);
        String planItemId = Data.uuid(body, "planItemId", false);
        Map<String, Object> job = jobs.job(jobId, userId);
        Map<String, Object> resume = resumes.version(resumeId, userId);
        if (job == null || resume == null || !"ACTIVE".equals(String.valueOf(job.get("status")))
                || !"CONFIRMED".equals(String.valueOf(resume.get("status")))) {
            throw BusinessException.conflict("岗位或未确认简历不可用于面试", "INVALID_STATE");
        }
        if (planItemId != null) {
            Map<String, Object> item = learning.itemById(planItemId, userId);
            if (item == null || !"INTERVIEW".equals(String.valueOf(item.get("kind")))) {
                throw BusinessException.notFound();
            }
        }
        Map<String, Object> profile = profiles.get(userId);
        long profileVersion = ((Number) profile.get("profileVersion")).longValue();
        int jobVersion = ((Number) job.get("job_version")).intValue();
        String interviewId = UUID.randomUUID().toString();
        Map<String, Object> jobInput = Map.of("id", jobId, "jobVersion", jobVersion, "jdText", job.get("jd_text"));
        Map<String, Object> payload = turnPayload(interviewId, 1, limit, profile, jobInput, List.of(), null);
        Map<String, Object> versions = Map.of("profileVersion", profileVersion, "resumeVersionId", resumeId,
                "jobVersion", jobVersion, "interviewVersion", 1);
        return tasks.create(userId, "POST", route, key, body, "INTERVIEW_TURN", "INTERVIEW", interviewId,
                versions, payload, taskId -> mapper.insertSession(interviewId, userId, jobId, jobVersion, resumeId,
                        profileVersion, json.stringify(Map.of("profile", profile, "job", jobInput)), limit, taskId, planItemId));
    }

    public Map<String, Object> detail(String userId, String interviewId) {
        Map<String, Object> session = mapper.session(interviewId, userId);
        if (session == null) throw BusinessException.notFound();
        return sessionVo(session, true);
    }

    public PageResult<Map<String, Object>> list(String userId, Integer current, Integer size) {
        int page = Data.page(current);
        int pageSize = Data.size(size);
        List<Map<String, Object>> records = mapper.page(userId, pageSize, Data.offset(current, size)).stream()
                .map(row -> sessionVo(row, false)).toList();
        return new PageResult<>(mapper.count(userId), records, page, pageSize);
    }

    @Transactional
    public ResponseEntity<CommonResult<Object>> answer(String userId, String interviewId, String route, String key,
                                                       Map<String, Object> body) {
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        String questionMessageId = Data.uuid(body, "questionMessageId", true);
        String content = Data.text(body, "content", true, 10000);
        int expected = requiredInt(body, "expectedVersion");
        String answerSource = Data.text(body, "answerSource", false, 8);
        answerSource = answerSource == null ? "TEXT" : answerSource;
        if (!Set.of("TEXT", "VOICE").contains(answerSource)) {
            throw BusinessException.validation("answerSource", "answerSource 只能为 TEXT 或 VOICE");
        }
        String transcriptionId = Data.uuid(body, "voiceTranscriptionId", false);
        if ("VOICE".equals(answerSource) && transcriptionId == null) {
            throw BusinessException.validation("voiceTranscriptionId", "语音回答必须携带 voiceTranscriptionId");
        }
        Map<String, Object> session = mapper.lockSession(interviewId, userId);
        if (session == null) throw BusinessException.notFound();
        int actual = ((Number) session.get("version")).intValue();
        if (expected != actual) throw BusinessException.versionConflict(actual);
        if (!"WAITING_ANSWER".equals(String.valueOf(session.get("status")))) {
            throw BusinessException.conflict("当前面试状态不能回答", "INVALID_STATE");
        }
        Map<String, Object> latest = mapper.latestQuestion(interviewId);
        if (latest == null || !questionMessageId.equals(String.valueOf(latest.get("id")))) {
            throw BusinessException.conflict("只能回答当前问题", "INVALID_STATE");
        }
        Map<String, Object> existing = mapper.answerByQuestion(questionMessageId, interviewId);
        if (transcriptionId != null) {
            Map<String, Object> transcription = voice.findById(transcriptionId, userId, interviewId);
            if (transcription == null
                    || !questionMessageId.equals(String.valueOf(transcription.get("question_message_id")))) {
                throw BusinessException.stale("转写记录不存在或不属于当前问题",
                        Map.of("reason", "TRANSCRIPTION_NOT_FOUND"));
            }
            Object consumedMessage = transcription.get("consumed_message_id");
            if (consumedMessage != null && (existing == null
                    || !String.valueOf(existing.get("id")).equals(String.valueOf(consumedMessage)))) {
                throw BusinessException.stale("转写已用于其他回答", Map.of("reason", "TRANSCRIPTION_CONSUMED"));
            }
            if ("VOICE".equals(answerSource)) {
                if (!"SUCCEEDED".equals(String.valueOf(transcription.get("status")))) {
                    throw BusinessException.stale("转写不可用",
                            Map.of("reason", "TRANSCRIPTION_" + transcription.get("status")));
                }
                if (expired(transcription)) {
                    voice.expireIfStale(transcriptionId);
                    throw BusinessException.stale("转写已过期，请重新录音", Map.of("reason", "TRANSCRIPTION_EXPIRED"));
                }
            }
        }
        if (existing != null) {
            AiTask original = taskMapper.latestResourceTask("INTERVIEW", interviewId);
            String status = original == null ? "" : original.getStatus();
            if (Set.of("QUEUED", "RUNNING", "CANCEL_REQUESTED").contains(status)) {
                throw BusinessException.taskInProgress(original.getId());
            }
            if (!content.equals(String.valueOf(existing.get("content"))) || original == null
                    || !Set.of("FAILED", "EXPIRED", "CANCELLED").contains(status)) {
                throw BusinessException.conflict("当前问题已回答或重试内容不一致", "INVALID_STATE");
            }
            Map<String, Object> payload = json.map(original.getInputSnapshotJson());
            payload.put("expectedVersion", actual);
            payload.put("messages", agentMessages(interviewId));
            Map<String, Object> versions = json.map(original.getInputVersionsJson());
            versions.put("interviewVersion", actual);
            return tasks.create(userId, "POST", route, key, body, "INTERVIEW_TURN", "INTERVIEW", interviewId,
                    versions, payload, original.getId(),
                    taskId -> mapper.updateTaskStatus(interviewId, "GENERATING", taskId));
        }
        String answerId = UUID.randomUUID().toString();
        int seq = mapper.nextSeq(interviewId);
        final String messageSource = answerSource;
        Map<String, Object> message = message(answerId, seq, "USER", content,
                ((Number) latest.get("question_no")).intValue(), ((Number) latest.get("follow_up_no")).intValue());
        List<Map<String, Object>> messages = new ArrayList<>(agentMessages(interviewId));
        messages.add(message);
        Map<String, Object> latestAnswer = Map.of("messageId", answerId, "questionMessageId", questionMessageId, "content", content);
        Map<String, Object> frozen = json.map(String.valueOf(session.get("input_snapshot_json")));
        Map<String, Object> profile = json.convert(json.tree(frozen.get("profile")), Map.class);
        Map<String, Object> jobInput = json.convert(json.tree(frozen.get("job")), Map.class);
        int limit = ((Number) session.get("question_limit")).intValue();
        Map<String, Object> payload = turnPayload(interviewId, expected, limit, profile, jobInput, messages, latestAnswer);
        Map<String, Object> versions = Map.of("profileVersion", session.get("profile_version"),
                "resumeVersionId", session.get("resume_version_id"), "jobVersion", session.get("job_version"),
                "interviewVersion", expected);
        return tasks.create(userId, "POST", route, key, body, "INTERVIEW_TURN", "INTERVIEW", interviewId,
                versions, payload, taskId -> {
                    mapper.insertMessage(answerId, interviewId, seq, "USER", messageSource, content,
                            ((Number) latest.get("question_no")).intValue(), ((Number) latest.get("follow_up_no")).intValue(),
                            questionMessageId, "[]", taskId);
                    if (transcriptionId != null) {
                        voice.markConsumed(transcriptionId, answerId);
                    }
                    if ("VOICE".equals(messageSource)) {
                        mapper.markVoiceMode(interviewId);
                    }
                    mapper.updateTaskStatus(interviewId, "GENERATING", taskId);

                });
    }

    @Transactional
    public ResponseEntity<CommonResult<Object>> end(String userId, String interviewId, String route, String key,
                                                    Map<String, Object> body) {
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        int expected = requiredInt(body, "expectedVersion");
        Map<String, Object> session = mapper.lockSession(interviewId, userId);
        if (session == null) throw BusinessException.notFound();
        int actual = ((Number) session.get("version")).intValue();
        if (expected != actual) throw BusinessException.versionConflict(actual);
        if (!"WAITING_ANSWER".equals(String.valueOf(session.get("status")))) {
            throw BusinessException.conflict("仅等待回答状态可以结束面试", "INVALID_STATE");
        }
        return reportTask(userId, interviewId, route, key, body, session, "EARLY_END", null);
    }

    public ResponseEntity<CommonResult<Object>> reportTask(String userId, String interviewId, String route, String key,
                                                           Object request, Map<String, Object> session,
                                                           String completionType, String retryOfTaskId) {
        List<Map<String, Object>> evaluations = mapper.evaluations(interviewId).stream().map(row -> {
            Map<String, Object> evaluation = json.map(String.valueOf(row.get("evaluation_json")));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("messageId", row.get("answer_message_id"));
            item.put("overallScore", row.get("overall_score"));
            item.put("dimensions", evaluation.get("dimensions"));
            item.put("evidenceStatus", evaluation.get("evidenceStatus"));
            return item;
        }).toList();
        Map<String, Object> payload = Map.of("interviewId", interviewId, "completionType", completionType,
                "messages", agentMessages(interviewId), "turnEvaluations", evaluations,
                "skillSnapshot", skillSnapshots(userId));
        Map<String, Object> versions = Map.of("interviewVersion", session.get("version"));
        return tasks.create(userId, "POST", route, key, request, "INTERVIEW_REPORT", "INTERVIEW_REPORT", interviewId,
                versions, payload, retryOfTaskId, taskId -> mapper.updateTaskStatus(interviewId, "ENDING", taskId));
    }

    public Map<String, Object> report(String userId, String interviewId) {
        Map<String, Object> session = mapper.session(interviewId, userId);
        if (session == null) throw BusinessException.notFound();
        Map<String, Object> report = mapper.report(interviewId, userId);
        if (report == null || !"COMPLETED".equals(String.valueOf(session.get("status")))) {
            throw BusinessException.reportNotReady(String.valueOf(session.get("pending_task_id")));
        }
        Map<String, Object> result = json.map(String.valueOf(report.get("report_json")));
        result.put("interviewId", interviewId);
        result.put("reportVersion", report.get("report_version"));
        return result;
    }

    public Map<String, Object> sessionVo(Map<String, Object> session, boolean withMessages) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", session.get("id"));
        result.put("jobId", session.get("job_id"));
        result.put("resumeVersionId", session.get("resume_version_id"));
        result.put("status", session.get("status"));
        result.put("mode", session.get("mode"));
        result.put("questionLimit", session.get("question_limit"));
        result.put("version", session.get("version"));
        result.put("pendingTaskId", session.get("pending_task_id"));
        // U09/U11：岗位名、报告完成类型与实际有效回答数（列表查询带出，历史列表据此展示）
        result.put("jobTitle", session.get("job_title"));
        result.put("completionType", session.get("completion_type"));
        result.put("answeredCount", session.get("answered_count"));
        result.put("messages", withMessages ? mapper.messages(String.valueOf(session.get("id"))).stream().map(view::map).toList() : List.of());
        result.put("createdAt", view.value(session.get("created_at")));
        return result;
    }

    public List<Map<String, Object>> agentMessages(String interviewId) {
        return mapper.messages(interviewId).stream().map(row -> {
            Map<String, Object> message = message(String.valueOf(row.get("id")), ((Number) row.get("seq")).intValue(),
                    String.valueOf(row.get("role")), String.valueOf(row.get("content")),
                    ((Number) row.get("question_no")).intValue(), ((Number) row.get("follow_up_no")).intValue());
            message.put("createdAt", view.value(row.get("created_at")));
            message.put("skillCodes", row.get("skill_codes_json") == null ? List.of()
                    : json.convert(json.parse(String.valueOf(row.get("skill_codes_json"))), List.class));
            return message;
        }).toList();
    }

    public List<Map<String, Object>> skillSnapshots(String userId) {
        return ability.snapshots(userId).stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("skillCode", row.get("skill_code"));
            item.put("score", row.get("score"));
            item.put("sampleCount", row.get("sample_count"));
            return item;
        }).toList();
    }

    private Map<String, Object> turnPayload(String interviewId, int expectedVersion, int limit, Map<String, Object> profile,
                                            Map<String, Object> job, List<Map<String, Object>> messages,
                                            Map<String, Object> latestAnswer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("interviewId", interviewId);
        payload.put("expectedVersion", expectedVersion);
        payload.put("questionLimit", limit);
        payload.put("maxFollowUps", 2);
        payload.put("profile", profile);
        payload.put("job", job);
        payload.put("messages", messages);
        payload.put("latestAnswer", latestAnswer);
        payload.put("questionRubricVersion", AbilityService.RUBRIC);
        return payload;
    }

    private Map<String, Object> message(String id, int seq, String role, String content, int questionNo, int followUpNo) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id);
        message.put("seq", seq);
        message.put("role", role);
        message.put("content", content);
        message.put("questionNo", questionNo);
        message.put("followUpNo", followUpNo);
        return message;
    }

    private int requiredInt(Map<String, Object> body, String field) {
        Integer value = Data.integer(body, field, null, 1, Integer.MAX_VALUE);
        if (value == null) throw BusinessException.validation(field, field + " 不能为空");
        return value;
    }

    private static boolean expired(Map<String, Object> row) {
        Object value = row.get("expires_at");
        if (value instanceof java.time.LocalDateTime time) {
            return time.isBefore(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        }
        if (value instanceof java.sql.Timestamp time) {
            return time.toInstant().isBefore(java.time.Instant.now());
        }
        return false;
    }
}
