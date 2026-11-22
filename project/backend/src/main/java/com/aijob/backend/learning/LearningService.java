package com.aijob.backend.learning;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.Data;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.common.PageResult;
import com.aijob.backend.job.JobMapper;
import com.aijob.backend.job.JobService;
import com.aijob.backend.profile.JobRoleCatalog;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.resume.ResumeMapper;
import com.aijob.backend.task.AiTask;
import com.aijob.backend.task.TaskMapper;
import com.aijob.backend.task.TaskService;

@Service
public class LearningService {

    private static final Set<String> PLAN_SOURCES = Set.of("MATCH", "RESUME");
    private static final Set<String> RESUME_READY = Set.of("PARSED", "CONFIRMED");
    private static final int MAX_RESUME_TEXT = 8000;
    private static final Map<String, List<String>> DIRECTION_SKILL_KEYWORDS = Map.ofEntries(
            Map.entry("JAVA_BACKEND", List.of("java", "spring", "mysql", "redis", "jvm", "mybatis", "kafka",
                    "system", "algorithm", "linux", "network", "sql")),
            Map.entry("PYTHON_BACKEND", List.of("python", "django", "flask", "fastapi", "mysql", "redis",
                    "postgres", "kafka", "docker", "linux", "sql")),
            Map.entry("GO_BACKEND", List.of("go", "golang", "grpc", "mysql", "redis", "kafka", "docker",
                    "kubernetes", "linux", "sql")),
            Map.entry("CPP_DEVELOPER", List.of("c++", "cpp", "c", "linux", "algorithm", "network", "stl")),
            Map.entry("NODEJS_BACKEND", List.of("node", "nodejs", "javascript", "typescript", "mysql", "redis",
                    "docker", "sql")),
            Map.entry("FULL_STACK", List.of("javascript", "typescript", "vue", "react", "java", "python", "node",
                    "mysql", "redis", "sql")),
            Map.entry("MICROSERVICE_DEVELOPER", List.of("java", "spring", "microservice", "docker", "kubernetes",
                    "kafka", "redis", "sql")),
            Map.entry("AI_APPLICATION_DEVELOPER", List.of("python", "llm", "ai", "prompt", "rag", "embedding",
                    "vector", "langchain", "sql", "docker")),
            Map.entry("AI_AGENT_DEVELOPER", List.of("python", "agent", "llm", "langchain", "function",
                    "prompt", "rag", "docker", "sql")),
            Map.entry("LLM_APPLICATION_DEVELOPER", List.of("python", "llm", "transformer", "prompt", "rag",
                    "embedding", "vector", "pytorch", "sql")),
            Map.entry("RAG_APPLICATION_DEVELOPER", List.of("python", "rag", "embedding", "vector", "llm",
                    "retrieval", "prompt", "sql")),
            Map.entry("LLM_ENGINEER", List.of("python", "llm", "transformer", "pytorch", "deep", "machine",
                    "algorithm", "math")),
            Map.entry("AI_PLATFORM_DEVELOPER", List.of("python", "java", "docker", "kubernetes", "llm",
                    "platform", "redis", "kafka")),
            Map.entry("PROMPT_ENGINEER", List.of("prompt", "llm", "python", "ai", "rag", "evaluation")),
            Map.entry("AI_ALGORITHM_ENGINEER", List.of("python", "algorithm", "machine", "deep", "pytorch",
                    "tensorflow", "math", "statistics")),
            Map.entry("FRONTEND", List.of("javascript", "typescript", "vue", "react", "css", "html", "vite",
                    "node", "browser", "network", "algorithm")),
            Map.entry("REACT_DEVELOPER", List.of("react", "javascript", "typescript", "css", "html", "node",
                    "frontend")),
            Map.entry("VUE_DEVELOPER", List.of("vue", "javascript", "typescript", "css", "html", "node",
                    "frontend")),
            Map.entry("ANDROID_DEVELOPER", List.of("android", "java", "kotlin", "gradle", "ui", "network",
                    "sql")),
            Map.entry("IOS_DEVELOPER", List.of("ios", "swift", "objective", "xcode", "ui", "network")),
            Map.entry("FLUTTER_DEVELOPER", List.of("flutter", "dart", "ui", "android", "ios", "network")),
            Map.entry("ALGORITHM", List.of("python", "c++", "algorithm", "math", "machine", "deep", "pytorch",
                    "tensorflow", "sql", "statistics")),
            Map.entry("MACHINE_LEARNING_ENGINEER", List.of("python", "machine", "deep", "pytorch", "tensorflow",
                    "algorithm", "math", "statistics")),
            Map.entry("DEEP_LEARNING_ENGINEER", List.of("python", "deep", "pytorch", "tensorflow", "neural",
                    "algorithm", "math")),
            Map.entry("NLP_ALGORITHM_ENGINEER", List.of("python", "nlp", "transformer", "machine", "deep",
                    "algorithm", "pytorch")),
            Map.entry("COMPUTER_VISION_ENGINEER", List.of("python", "cv", "vision", "opencv", "deep",
                    "pytorch", "algorithm")),
            Map.entry("RECOMMENDATION_ALGORITHM", List.of("python", "recommendation", "machine", "deep",
                    "algorithm", "sql", "spark")),
            Map.entry("DATA", List.of("sql", "python", "spark", "hive", "flink", "data", "machine", "statistics",
                    "hadoop", "kafka")),
            Map.entry("DATA_ANALYST", List.of("sql", "python", "excel", "statistics", "data", "visualization")),
            Map.entry("DATA_ENGINEER", List.of("sql", "python", "spark", "hive", "flink", "kafka", "hadoop",
                    "data")),
            Map.entry("TEST", List.of("java", "python", "test", "junit", "pytest", "selenium", "docker", "ci",
                    "linux", "network")),
            Map.entry("TEST_DEVELOPMENT_ENGINEER", List.of("java", "python", "test", "junit", "pytest",
                    "selenium", "docker", "ci", "linux")),
            Map.entry("AUTOMATION_TEST_ENGINEER", List.of("python", "test", "selenium", "pytest", "docker",
                    "ci", "linux")),
            Map.entry("DEVOPS_ENGINEER", List.of("docker", "kubernetes", "ci", "linux", "network", "cloud",
                    "git", "shell")),
            Map.entry("SRE_ENGINEER", List.of("linux", "network", "docker", "kubernetes", "cloud", "shell",
                    "monitoring")),
            Map.entry("CLOUD_ENGINEER", List.of("cloud", "docker", "kubernetes", "linux", "network", "shell")),
            Map.entry("SECURITY_ENGINEER", List.of("security", "network", "linux", "cryptography", "web",
                    "docker")),
            Map.entry("EMBEDDED_ENGINEER", List.of("c", "c++", "linux", "embedded", "rtos", "hardware")),
            Map.entry("DATABASE_DEVELOPER", List.of("sql", "mysql", "postgres", "redis", "database", "linux")),
            Map.entry("GAME_DEVELOPER", List.of("c++", "c#", "unity", "unreal", "algorithm", "math")),
            Map.entry("BLOCKCHAIN_DEVELOPER", List.of("blockchain", "solidity", "web3", "c++", "go", "network")),
            Map.entry("TECHNICAL_PRODUCT_MANAGER", List.of("product", "sql", "data", "analytics", "agile")),
            Map.entry("SOLUTION_ENGINEER", List.of("solution", "java", "python", "docker", "cloud", "sql")));

    private final LearningMapper mapper;
    private final JobMapper jobs;
    private final JobService jobService;
    private final ProfileService profiles;
    private final ResumeMapper resumes;
    private final TaskService tasks;
    private final TaskMapper taskMapper;
    private final JsonService json;
    private final ApiView view;

    public LearningService(LearningMapper mapper, JobMapper jobs, JobService jobService, ProfileService profiles,
                           ResumeMapper resumes, TaskService tasks, TaskMapper taskMapper, JsonService json,
                           ApiView view) {
        this.mapper = mapper;
        this.jobs = jobs;
        this.jobService = jobService;
        this.profiles = profiles;
        this.resumes = resumes;
        this.tasks = tasks;
        this.taskMapper = taskMapper;
        this.json = json;
        this.view = view;
    }

    public ResponseEntity<CommonResult<Object>> createPlan(String userId, String route, String key,
                                                           Map<String, Object> body) {
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        String matchItemId = Data.uuid(body, "matchItemId", true);
        int dailyMinutes = requiredInt(body, "dailyMinutes", 10, 120);
        int days = requiredInt(body, "days", 1, 14);
        Map<String, Object> match = jobs.matchItem(matchItemId, userId);
        if (match == null || !"READY".equals(String.valueOf(match.get("batch_status")))
                || "FAIL".equals(String.valueOf(match.get("eligibility_status")))) {
            throw BusinessException.notFound();
        }
        long profileVersion = ((Number) profiles.get(userId).get("profileVersion")).longValue();
        if (profileVersion != ((Number) match.get("profile_version")).longValue()
                || jobs.staleItems(String.valueOf(match.get("batch_id"))) > 0) {
            throw BusinessException.stale("匹配输入已过期，请重新匹配");
        }
        Map<String, Object> matchItem = matchItemInput(jobService.matchItemVo(match));
        List<String> allowedSkills = allowedSkills(userId, matchItem);
        Map<String, Object> payload = Map.of("planSource", "MATCH", "matchItem", matchItem,
                "profileVersion", profileVersion, "dailyMinutes", dailyMinutes, "days", days,
                "allowedSkillCodes", allowedSkills, "knowledgeRefs", List.of());
        String planId = UUID.randomUUID().toString();
        PlanLineage lineage = lineage(userId, "MATCH");
        Map<String, Object> basis = basis("MATCH", dailyMinutes, days, null, null, matchItemId,
                String.valueOf(match.get("title")), List.of());
        String basisJson = json.stringify(basis);
        return tasks.create(userId, "POST", route, key, body, "LEARNING_PLAN", "LEARNING_PLAN", planId,
                Map.of("profileVersion", profileVersion), payload,
                taskId -> mapper.insertPlan(planId, userId, matchItemId, "MATCH", null, null,
                        lineage.supersedesPlanId(), lineage.generationNo(), basisJson, taskId, profileVersion,
                        dailyMinutes, days));
    }

    public ResponseEntity<CommonResult<Object>> createPlanFromResume(String userId, String route, String key,
                                                                      Map<String, Object> body) {
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        int dailyMinutes = requiredInt(body, "dailyMinutes", 10, 120);
        int days = requiredInt(body, "days", 1, 14);
        Map<String, Object> profile = profiles.get(userId);
        long profileVersion = ((Number) profile.get("profileVersion")).longValue();
        String requestedDirection = Data.text(body, "targetDirection", false, 32);
        String targetDirection = isBlank(requestedDirection)
                ? textOrNull(profile.get("targetRole")) : requestedDirection;
        if (!JobRoleCatalog.supports(targetDirection)) {
            throw BusinessException.validation("targetDirection", "目标求职方向不在支持范围内");
        }
        ResumeContext resume = resolveResume(userId, Data.uuid(body, "resumeVersionId", false), profile);
        if (!RESUME_READY.contains(resume.status())) {
            throw resumeRequired("简历尚未解析完成，请等待解析成功后再生成训练计划");
        }

        String requestedMatchItemId = Data.uuid(body, "matchItemId", false);
        Map<String, Object> matchItem = null;
        boolean matchReferenced = false;
        String matchNote = null;
        if (!isBlank(requestedMatchItemId)) {
            Map<String, Object> match = jobs.matchItem(requestedMatchItemId, userId);
            if (match == null || !"READY".equals(String.valueOf(match.get("batch_status")))
                    || "FAIL".equals(String.valueOf(match.get("eligibility_status")))) {
                matchNote = "指定的岗位匹配结果不可用，已忽略";
            } else if (profileVersion != ((Number) match.get("profile_version")).longValue()) {
                matchNote = "岗位匹配使用的画像版本已变化，已忽略该结果";
            } else if (jobs.staleItems(String.valueOf(match.get("batch_id"))) > 0) {
                matchNote = "岗位匹配结果已过期，已忽略该结果";
            } else {
                matchItem = matchItemInput(jobService.matchItemVo(match));
                matchReferenced = true;
            }
        }

        List<String> allowedSkills = resumeAllowedSkills(userId, resume.facts(), targetDirection, matchItem);
        if (allowedSkills.isEmpty()) {
            throw BusinessException.validation("targetDirection", "技能目录中没有可用于目标方向的技能");
        }
        Map<String, Object> history = trainingHistory(userId);
        List<String> limitations = new ArrayList<>();
        if (((Number) history.get("evaluatedAnswers")).intValue() == 0) {
            limitations.add("暂无历史训练评价，能力判断仅基于简历事实");
        }
        if (!matchReferenced) {
            limitations.add("未参考岗位匹配结果，训练内容仅基于简历与目标方向");
        }
        if (matchNote != null) {
            limitations.add(matchNote);
        }
        Object evidenceSkills = resume.facts().get("skills");
        if (!(evidenceSkills instanceof List<?> skillsList) || skillsList.isEmpty()) {
            limitations.add("简历未提供可映射的技能事实，技能证据标记为待评估");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planSource", "RESUME");
        payload.put("profileVersion", profileVersion);
        payload.put("dailyMinutes", dailyMinutes);
        payload.put("days", days);
        payload.put("targetDirection", targetDirection);
        payload.put("allowedSkillCodes", allowedSkills);
        payload.put("knowledgeRefs", List.of());
        payload.put("matchItem", matchItem);
        payload.put("resume", resumeInput(resume, targetDirection));
        payload.put("trainingHistory", history);
        payload.put("limitations", limitations);

        String planId = UUID.randomUUID().toString();
        PlanLineage lineage = lineage(userId, "RESUME");
        Map<String, Object> basis = basis("RESUME", dailyMinutes, days, resume.versionId(), targetDirection,
                matchItem == null ? null : String.valueOf(matchItem.get("id")), resume.title(), limitations);
        basis.put("resumeVersionNo", resume.versionNo());
        basis.put("matchReferenced", matchReferenced);
        basis.put("github", Map.of("status", "PENDING"));
        String basisJson = json.stringify(basis);
        return tasks.create(userId, "POST", route, key, body, "LEARNING_PLAN", "LEARNING_PLAN", planId,
                Map.of("profileVersion", profileVersion), payload,
                taskId -> mapper.insertPlan(planId, userId, null, "RESUME", resume.versionId(), targetDirection,
                        lineage.supersedesPlanId(), lineage.generationNo(), basisJson, taskId, profileVersion,
                        dailyMinutes, days));
    }

    public Map<String, Object> plan(String userId, String planId) {
        Map<String, Object> plan = mapper.plan(planId, userId);
        if (plan == null) {
            throw BusinessException.notFound();
        }
        return planVo(plan);
    }

    public PageResult<Map<String, Object>> plans(String userId, Integer current, Integer size) {
        int page = Data.page(current);
        int pageSize = Data.size(size);
        List<Map<String, Object>> records = mapper.planPage(userId, pageSize, Data.offset(current, size))
                .stream().map(this::planVo).toList();
        return new PageResult<>(mapper.planCount(userId), records, page, pageSize);
    }

    @Transactional
    public ResponseEntity<CommonResult<Object>> createSession(String userId, String route, String key,
                                                              Map<String, Object> body) {
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        String planId = Data.uuid(body, "planId", true);
        String itemId = Data.uuid(body, "planItemId", true);
        Map<String, Object> item = mapper.lockItem(planId, itemId, userId);
        if (item == null || !"PRACTICE".equals(String.valueOf(item.get("kind")))) {
            throw BusinessException.notFound();
        }
        ensureNoActive(userId, itemId);
        String sessionId = UUID.randomUUID().toString();
        long profileVersion = ((Number) profiles.get(userId).get("profileVersion")).longValue();
        List<Map<String, Object>> candidates = candidates(String.valueOf(item.get("skill_code")), null);
        Map<String, Object> payload = questionPayload(sessionId, item, candidates,
                mapper.usedQuestionIds(userId), mapper.usedQuestionStems(userId), "SELECT_OR_GENERATE");
        Map<String, Object> versions = Map.of("profileVersion", profileVersion);
        return tasks.create(userId, "POST", route, key, body, "LEARNING_QUESTION", "LEARNING_SESSION", sessionId,
                versions, payload, taskId -> {
                    mapper.insertSession(sessionId, userId, planId, itemId, null, null);
                    mapper.updateItemStatus(itemId, "IN_PROGRESS");
                });
    }

    public Map<String, Object> session(String userId, String sessionId) {
        Map<String, Object> session = mapper.session(sessionId, userId);
        if (session == null) {
            throw BusinessException.notFound();
        }
        return sessionVo(session);
    }

    @Transactional
    public ResponseEntity<CommonResult<Object>> answer(String userId, String sessionId, String route, String key,
                                                       Map<String, Object> body) {
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        String content = Data.text(body, "content", true, 10000);
        int expected = requiredInt(body, "expectedVersion", 1, Integer.MAX_VALUE);
        Map<String, Object> session = mapper.lockSession(sessionId, userId);
        if (session == null) {
            throw BusinessException.notFound();
        }
        int actual = ((Number) session.get("version")).intValue();
        if (expected != actual) {
            throw BusinessException.versionConflict(actual);
        }
        Map<String, Object> existing = mapper.sessionAnswer(sessionId);
        if (existing != null) {
            String status = String.valueOf(existing.get("status"));
            if ("EVALUATING".equals(status)) {
                throw BusinessException.taskInProgress(String.valueOf(existing.get("task_id")));
            }
            if (!"FAILED".equals(status) || !content.equals(String.valueOf(existing.get("content")))
                    || !"ACTIVE".equals(String.valueOf(session.get("status")))) {
                throw BusinessException.conflict("回答已提交或失败重试内容不一致", "INVALID_STATE");
            }
            String answerId = String.valueOf(existing.get("id"));
            AiTask original = taskMapper.latestResourceTask("LEARNING_ANSWER", answerId);
            if (original == null) {
                throw BusinessException.conflict("原评价任务不存在", "INVALID_STATE");
            }
            return tasks.create(userId, "POST", route, key, body, "LEARNING_EVALUATE", "LEARNING_ANSWER", answerId,
                    json.map(original.getInputVersionsJson()), json.map(original.getInputSnapshotJson()), original.getId(),
                    taskId -> {
                        mapper.updateAnswerTask(answerId, "EVALUATING", taskId);
                        mapper.answerSubmitted(sessionId, answerId);
                    });
        }
        if (!"ACTIVE".equals(String.valueOf(session.get("status")))) {
            throw BusinessException.conflict("当前训练会话不能提交回答", "INVALID_STATE");
        }
        Map<String, Object> question = mapper.question(String.valueOf(session.get("question_id")));
        if (question == null) {
            throw BusinessException.conflict("训练题目尚未就绪", "INVALID_STATE");
        }
        String answerId = UUID.randomUUID().toString();
        long profileVersion = ((Number) profiles.get(userId).get("profileVersion")).longValue();
        int questionVersion = ((Number) session.get("question_version")).intValue();
        Map<String, Object> payload = Map.of("answerId", answerId, "question", internalQuestion(question), "answer", content);
        Map<String, Object> versions = Map.of("profileVersion", profileVersion, "questionVersion", questionVersion);
        return tasks.create(userId, "POST", route, key, body, "LEARNING_EVALUATE", "LEARNING_ANSWER", answerId,
                versions, payload, taskId -> {
                    mapper.insertAnswer(answerId, sessionId, userId, content, json.sha256(content), taskId);
                    mapper.answerSubmitted(sessionId, answerId);
                });
    }

    public Map<String, Object> answerDetail(String userId, String answerId) {
        Map<String, Object> answer = mapper.answer(answerId, userId);
        if (answer == null) {
            throw BusinessException.notFound();
        }
        return answerVo(answer);
    }

    public PageResult<Map<String, Object>> mistakes(String userId, Integer current, Integer size, Boolean resolved) {
        int page = Data.page(current);
        int pageSize = Data.size(size);
        Boolean filter = resolved == null ? Boolean.FALSE : resolved;
        List<Map<String, Object>> records = mapper.mistakes(userId, filter, pageSize, Data.offset(current, size))
                .stream().map(this::mistakeVo).toList();
        return new PageResult<>(mapper.mistakeCount(userId, filter), records, page, pageSize);
    }

    public Map<String, Object> mistake(String userId, String mistakeId) {
        Map<String, Object> mistake = mapper.mistake(mistakeId, userId);
        if (mistake == null) {
            throw BusinessException.notFound();
        }
        return mistakeVo(mistake);
    }

    @Transactional
    public ResponseEntity<CommonResult<Object>> retryMistake(String userId, String mistakeId, String route, String key,
                                                             Map<String, Object> body) {
        ResponseEntity<CommonResult<Object>> replay = tasks.replay(userId, "POST", route, key, body);
        if (replay != null) {
            return replay;
        }
        Map<String, Object> mistake = mapper.mistake(mistakeId, userId);
        if (mistake == null) {
            throw BusinessException.notFound();
        }
        Map<String, Object> answer = mapper.answer(String.valueOf(mistake.get("last_answer_id")), userId);
        Map<String, Object> session = mapper.session(String.valueOf(answer.get("session_id")), userId);
        ensureNoActive(userId, String.valueOf(session.get("plan_item_id")));
        Map<String, Object> question = mapper.question(String.valueOf(mistake.get("question_id")));
        if (question == null) {
            throw BusinessException.conflict("原题不可用", "INVALID_STATE");
        }
        String sessionId = UUID.randomUUID().toString();
        long profileVersion = ((Number) profiles.get(userId).get("profileVersion")).longValue();
        int questionVersion = ((Number) question.get("question_version")).intValue();
        List<Map<String, Object>> candidates = List.of(candidate(question));
        Map<String, Object> payload = questionPayload(sessionId,
                Map.of("skill_code", ((List<String>) json.convert(json.parse(String.valueOf(question.get("skill_codes_json"))), List.class)).get(0)),
                candidates, List.of(), List.of(), "REUSE_ONLY");
        Map<String, Object> versions = Map.of("profileVersion", profileVersion, "questionVersion", questionVersion);
        return tasks.create(userId, "POST", route, key, body, "LEARNING_QUESTION", "LEARNING_SESSION", sessionId,
                versions, payload, taskId -> mapper.insertSession(sessionId, userId,
                        String.valueOf(session.get("plan_id")), String.valueOf(session.get("plan_item_id")),
                        String.valueOf(question.get("id")), questionVersion));
    }

    @Transactional
    public boolean completeReading(String userId, String planId, String itemId) {
        Map<String, Object> item = mapper.item(planId, itemId, userId);
        if (item == null) {
            throw BusinessException.notFound();
        }
        if (!"READING".equals(String.valueOf(item.get("kind")))) {
            throw BusinessException.conflict("仅 READING 计划项允许手动完成", "INVALID_STATE");
        }
        if ("COMPLETED".equals(String.valueOf(item.get("status")))) return true;
        mapper.updateItemStatus(itemId, "COMPLETED");
        mapper.completePlan(planId);
        return true;
    }

    public void completePractice(String planId, String itemId) {
        mapper.updateItemStatus(itemId, "COMPLETED");
        mapper.completePlan(planId);
    }

    public Map<String, Object> planVo(Map<String, Object> plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", plan.get("id"));
        result.put("source", plan.get("source"));
        result.put("matchItemId", plan.get("match_item_id"));
        result.put("resumeVersionId", plan.get("resume_version_id"));
        result.put("targetDirection", plan.get("target_direction"));
        result.put("supersedesPlanId", plan.get("supersedes_plan_id"));
        result.put("generationNo", plan.get("generation_no"));
        result.put("profileVersion", plan.get("profile_version"));
        result.put("dailyMinutes", plan.get("daily_minutes"));
        result.put("days", plan.get("days"));
        result.put("status", plan.get("status"));
        result.put("title", plan.get("title"));
        result.put("generatedAt", view.value(plan.get("created_at")));
        Object basis = plan.get("basis_json");
        result.put("basis", basis == null ? null : json.convert(json.parse(String.valueOf(basis)), Object.class));
        if (plan.get("match_item_id") != null) {
            result.put("matchSummary", matchSummary(String.valueOf(plan.get("match_item_id")),
                    String.valueOf(plan.get("user_id"))));
        }
        result.put("items", mapper.items(String.valueOf(plan.get("id"))).stream().map(view::map).toList());
        return result;
    }

    private Map<String, Object> matchSummary(String matchItemId, String userId) {
        Map<String, Object> match = jobs.matchItem(matchItemId, userId);
        if (match == null) {
            return null;
        }
        Map<String, Object> vo = jobService.matchItemVo(match);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", matchItemId);
        summary.put("title", vo.get("title"));
        summary.put("companyName", vo.get("companyName"));
        summary.put("matchScore", vo.get("matchScore"));
        return summary;
    }

    public Map<String, Object> sessionVo(Map<String, Object> session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", session.get("id"));
        result.put("planId", session.get("plan_id"));
        result.put("planItemId", session.get("plan_item_id"));
        result.put("status", session.get("status"));
        Object questionId = session.get("question_id");
        result.put("question", questionId == null || "PREPARING".equals(String.valueOf(session.get("status"))) ? null
                : questionVo(mapper.question(String.valueOf(questionId))));
        result.put("lastAnswerId", session.get("last_answer_id"));
        result.put("version", session.get("version"));
        return result;
    }

    public Map<String, Object> questionVo(Map<String, Object> question) {
        if (question == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", question.get("id"));
        result.put("skillCodes", json.convert(json.parse(String.valueOf(question.get("skill_codes_json"))), Object.class));
        result.put("difficulty", question.get("difficulty"));
        result.put("type", question.get("type"));
        result.put("stem", question.get("stem"));
        // 客观题复习需要展示选项与空位数（不含标准答案，避免错题本泄题）
        result.put("options", question.get("options_json") == null ? List.of()
                : json.convert(json.parse(String.valueOf(question.get("options_json"))), Object.class));
        result.put("blankCount", question.get("blank_count") == null ? 0
                : ((Number) question.get("blank_count")).intValue());
        result.put("score", question.get("score") == null ? 10 : ((Number) question.get("score")).intValue());
        result.put("sourceType", question.get("source_type"));
        result.put("generationEnv", question.get("generation_env"));
        result.put("sourceTitle", null);
        result.put("questionVersion", question.get("question_version"));
        return result;
    }

    public Map<String, Object> internalQuestion(Map<String, Object> question) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", question.get("id"));
        result.put("questionVersion", question.get("question_version"));
        result.put("stem", question.get("stem"));
        result.put("skillCodes", json.convert(json.parse(String.valueOf(question.get("skill_codes_json"))), Object.class));
        result.put("referenceAnswer", question.get("reference_answer"));
        result.put("rubric", json.convert(json.parse(String.valueOf(question.get("rubric_json"))), Object.class));
        return result;
    }

    public Map<String, Object> candidate(Map<String, Object> question) {
        Map<String, Object> result = internalQuestion(question);
        result.put("version", result.remove("questionVersion"));
        result.put("difficulty", question.get("difficulty"));
        result.put("type", question.get("type"));
        return result;
    }

    public Map<String, Object> answerVo(Map<String, Object> answer) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", answer.get("id"));
        result.put("sessionId", answer.get("session_id"));
        result.put("content", answer.get("content"));
        result.put("status", answer.get("status"));
        result.put("taskId", answer.get("task_id"));
        result.put("evaluation", answer.get("evaluation_json") == null ? null
                : json.convert(json.parse(String.valueOf(answer.get("evaluation_json"))), Object.class));
        return result;
    }

    private Map<String, Object> mistakeVo(Map<String, Object> mistake) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", mistake.get("id"));
        result.put("question", questionVo(mapper.question(String.valueOf(mistake.get("question_id")))));
        result.put("lastAnswerId", mistake.get("last_answer_id"));
        result.put("wrongCount", mistake.get("wrong_count"));
        result.put("resolved", mistake.get("resolved"));
        result.put("cause", mistake.get("cause"));
        return result;
    }

    private Map<String, Object> questionPayload(String sessionId, Map<String, Object> item, List<Map<String, Object>> candidates,
                                                 List<String> excluded, List<String> excludedStems, String mode) {
        Map<String, Object> planItem = new LinkedHashMap<>(Map.of("skillCode", item.get("skill_code"), "difficulty", "MEDIUM"));
        Map.of("title", "title", "objective", "objective", "reason", "reason", "completion_criteria", "completionCriteria")
                .forEach((column, field) -> {
                    if (item.get(column) != null) planItem.put(field, item.get(column));
                });
        return Map.of("sessionId", sessionId, "planItem", planItem,
                "candidateQuestions", candidates, "excludedQuestionIds", excluded,
                "excludedQuestionStems", excludedStems,
                "knowledgeRefs", List.of(), "mode", mode);
    }

    private List<Map<String, Object>> candidates(String skillCode, String onlyQuestionId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> question : mapper.questionCandidates()) {
            List<String> skills = json.convert(json.parse(String.valueOf(question.get("skill_codes_json"))), List.class);
            if (skills.contains(skillCode) && (onlyQuestionId == null || onlyQuestionId.equals(String.valueOf(question.get("id"))))) {
                result.add(candidate(question));
            }
        }
        return result;
    }

    private void ensureNoActive(String userId, String itemId) {
        Map<String, Object> active = mapper.activeSession(userId, itemId);
        if (active != null) {
            throw BusinessException.conflict("当前计划项已有活动训练会话", "INVALID_STATE",
                    Map.of("sessionId", String.valueOf(active.get("id"))));
        }
    }

    private ResumeContext resolveResume(String userId, String requestedVersionId, Map<String, Object> profile) {
        Map<String, Object> row = null;
        if (!isBlank(requestedVersionId)) {
            row = resumes.versionWithResume(requestedVersionId, userId);
            if (row == null) {
                throw BusinessException.notFound();
            }
        } else {
            Object activeVersion = profile.get("activeResumeVersionId");
            if (activeVersion != null) {
                row = resumes.versionWithResume(String.valueOf(activeVersion), userId);
            }
            if (row == null) {
                for (Map<String, Object> candidate : resumes.latestVersions(userId, 20)) {
                    if (RESUME_READY.contains(String.valueOf(candidate.get("status")))) {
                        row = candidate;
                        break;
                    }
                }
            }
        }
        if (row == null) {
            throw resumeRequired("请先在简历中心上传并解析简历，再生成训练计划");
        }
        String status = String.valueOf(row.get("status"));
        String factsJson = "CONFIRMED".equals(status) ? textOrNull(row.get("confirmed_facts_json"))
                : textOrNull(row.get("parsed_facts_json"));
        Map<String, Object> facts = factsJson == null ? Map.of() : json.map(factsJson);
        return new ResumeContext(String.valueOf(row.get("version_id")),
                ((Number) row.get("version_no")).intValue(), textOrNull(row.get("title")), status,
                facts, maxChars(textOrNull(row.get("extracted_text")), MAX_RESUME_TEXT));
    }

    private Map<String, Object> resumeInput(ResumeContext resume, String targetDirection) {
        List<Map<String, Object>> skills = new ArrayList<>();
        Object rawSkills = resume.facts().get("skills");
        if (rawSkills instanceof List<?> items) {
            for (Object raw : items) {
                if (!(raw instanceof Map<?, ?> map)) continue;
                Map<String, Object> skill = new LinkedHashMap<>();
                skill.put("skillCode", map.get("skillCode"));
                skill.put("claim", map.get("claim"));
                skill.put("quote", maxChars(String.valueOf(map.get("quote")), 500));
                skill.put("location", map.get("location"));
                skills.add(skill);
            }
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("versionId", resume.versionId());
        input.put("versionNo", resume.versionNo());
        input.put("title", resume.title() == null ? "简历" : resume.title());
        input.put("targetDirection", targetDirection);
        input.put("education", resume.facts().get("education"));
        input.put("experienceMonths", resume.facts().get("experienceMonths"));
        input.put("major", resume.facts().get("major"));
        input.put("skills", skills);
        input.put("projectsText", resume.text());
        return input;
    }

    private List<String> resumeAllowedSkills(String userId, Map<String, Object> facts, String targetDirection,
                                             Map<String, Object> matchItem) {
        Map<String, String> catalog = new LinkedHashMap<>();
        for (Map<String, Object> item : profiles.agentSkillCatalog(userId)) {
            catalog.put(String.valueOf(item.get("code")), String.valueOf(item.get("name")));
        }
        LinkedHashSet<String> chosen = new LinkedHashSet<>();
        Object rawSkills = facts.get("skills");
        if (rawSkills instanceof List<?> items) {
            for (Object raw : items) {
                if (raw instanceof Map<?, ?> map) {
                    String code = String.valueOf(map.get("skillCode"));
                    if (catalog.containsKey(code)) chosen.add(code);
                }
            }
        }
        for (String keyword : DIRECTION_SKILL_KEYWORDS.getOrDefault(targetDirection, List.of())) {
            for (Map.Entry<String, String> entry : catalog.entrySet()) {
                if (chosen.size() >= 16) break;
                String haystack = (entry.getKey() + " " + entry.getValue()).toLowerCase(Locale.ROOT);
                if (haystack.contains(keyword)) chosen.add(entry.getKey());
            }
        }
        if (matchItem != null) {
            for (String code : allowedSkills(userId, matchItem)) {
                if (chosen.size() >= 20) break;
                if (catalog.containsKey(code)) chosen.add(code);
            }
        }
        if (chosen.isEmpty()) {
            catalog.keySet().stream().limit(12).forEach(chosen::add);
        }
        return new ArrayList<>(chosen).subList(0, Math.min(20, chosen.size()));
    }

    private Map<String, Object> trainingHistory(String userId) {
        Set<String> weak = new LinkedHashSet<>();
        for (String skillJson : mapper.openMistakeSkillJson(userId)) {
            if (skillJson == null || weak.size() >= 10) continue;
            for (Object code : json.convert(json.parse(skillJson), List.class)) {
                if (weak.size() >= 10) break;
                weak.add(String.valueOf(code));
            }
        }
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("completedItems", mapper.completedItemCount(userId));
        history.put("evaluatedAnswers", mapper.evaluatedAnswerCount(userId));
        history.put("averageScore", mapper.averageEvaluationScore(userId));
        history.put("openMistakes", mapper.openMistakeCount(userId));
        history.put("weakSkillCodes", new ArrayList<>(weak));
        return history;
    }

    private Map<String, Object> basis(String source, int dailyMinutes, int days, String resumeVersionId,
                                      String targetDirection, String matchItemId, String matchTitle,
                                      List<String> limitations) {
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("source", source);
        basis.put("dailyMinutes", dailyMinutes);
        basis.put("days", days);
        basis.put("resumeVersionId", resumeVersionId);
        basis.put("targetDirection", targetDirection);
        basis.put("matchItemId", matchItemId);
        basis.put("matchTitle", matchTitle);
        basis.put("generatedAt", Instant.now().toString());
        basis.put("limitations", limitations);
        return basis;
    }

    private PlanLineage lineage(String userId, String source) {
        Map<String, Object> latest = mapper.latestPlan(userId, source);
        if (latest == null) {
            return new PlanLineage(null, 1);
        }
        int generation = latest.get("generation_no") == null ? 1
                : ((Number) latest.get("generation_no")).intValue() + 1;
        return new PlanLineage(String.valueOf(latest.get("id")), generation);
    }

    private BusinessException resumeRequired(String message) {
        return BusinessException.conflict(message, "RESUME_REQUIRED", Map.of("action", "GO_RESUME"));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String textOrNull(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private String maxChars(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record PlanLineage(String supersedesPlanId, int generationNo) {
    }

    private record ResumeContext(String versionId, int versionNo, String title, String status,
                                 Map<String, Object> facts, String text) {
    }

    private Map<String, Object> matchItemInput(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("id", "jobId", "jobVersion", "title", "eligibility", "matchScore", "scoreVersion",
                "components", "gaps", "reasons", "source", "sourceUrl", "verifiedAt")) {
            result.put(key, source.get(key));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<String> allowedSkills(String userId, Map<String, Object> matchItem) {
        List<String> skills = new ArrayList<>();
        for (Map<String, Object> gap : (List<Map<String, Object>>) matchItem.getOrDefault("gaps", List.of())) {
            String code = String.valueOf(gap.get("skillCode"));
            if (!skills.contains(code)) skills.add(code);
        }
        if (skills.isEmpty()) {
            profiles.agentSkillCatalog(userId).stream().limit(20).forEach(item -> skills.add(String.valueOf(item.get("code"))));
        }
        return skills;
    }

    private int requiredInt(Map<String, Object> body, String field, int min, int max) {
        Integer value = Data.integer(body, field, null, min, max);
        if (value == null) throw BusinessException.validation(field, field + " 不能为空");
        return value;
    }
}
