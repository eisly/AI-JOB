package com.aijob.backend.ability;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.aijob.backend.common.JsonService;

@Service
public class AbilityService {

    public static final String RUBRIC = "technical-answer-v1";

    private final AbilityMapper mapper;
    private final JsonService json;
    private final com.aijob.backend.profile.ProfileMapper profiles;

    public AbilityService(AbilityMapper mapper, JsonService json, com.aijob.backend.profile.ProfileMapper profiles) {
        this.mapper = mapper;
        this.json = json;
        this.profiles = profiles;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void replaceResumeClaims(String userId, String versionId, Map<String, Object> facts) {
        mapper.revokeResumeClaims(userId);
        List<Map<String, Object>> skills = (List<Map<String, Object>>) facts.getOrDefault("skills", List.of());
        for (Map<String, Object> skill : skills) {
            String code = String.valueOf(skill.get("skillCode"));
            mapper.insertEvidence(UUID.randomUUID().toString(), userId, code, "RESUME_CLAIM", versionId, 1,
                    null, 0, String.valueOf(skill.get("quote")), "resume-claim-v1", null, null, null);
            recompute(userId, code);
        }
    }

    @Transactional
    public void revokeResumeVersion(String userId, String versionId) {
        mapper.revokeResumeVersion(userId, versionId);
    }

    @Transactional
    public void addIndependent(String userId, String sourceType, String sourceId, String taskId,
                               List<Map<String, Object>> scores, String modelVersion, String promptVersion) {
        profiles.ensure(userId);
        profiles.lock(userId);
        for (Map<String, Object> score : scores) {
            String skillCode = String.valueOf(score.get("skillCode"));
            mapper.insertEvidence(UUID.randomUUID().toString(), userId, skillCode, sourceType, sourceId, 1,
                    score.get("score"), score.getOrDefault("confidence", 0), null, RUBRIC,
                    modelVersion, promptVersion, taskId);
            recompute(userId, skillCode);
        }
        if (!scores.isEmpty()) {
            profiles.touch(userId);
        }
    }

    public void recompute(String userId, String skillCode) {
        List<Object> values = mapper.last20(userId, skillCode);
        BigDecimal score = null;
        if (!values.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Object value : values) {
                sum = sum.add(new BigDecimal(String.valueOf(value)));
            }
            score = sum.divide(BigDecimal.valueOf(values.size()), 1, RoundingMode.HALF_UP);
        }
        String status = values.isEmpty() ? "MISSING" : values.size() < 3 ? "LIMITED" : "SUFFICIENT";
        mapper.saveSnapshot(userId, skillCode, score, values.size(), status);
    }

    public List<Map<String, Object>> snapshots(String userId) {
        return mapper.snapshots(userId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MatchSnapshot matchSnapshot(String userId, Set<String> evidenceSkills) {
        List<Map<String, Object>> snapshots = mapper.snapshots(userId);
        Map<String, List<String>> evidenceIds = new LinkedHashMap<>();
        for (Map<String, Object> snapshot : snapshots) {
            String skillCode = String.valueOf(snapshot.get("skill_code"));
            if (!evidenceSkills.contains(skillCode) || snapshot.get("score") == null) continue;
            List<String> ids = mapper.last20EvidenceIds(userId, skillCode);
            if (snapshot.get("sample_count") instanceof Number count && count.intValue() == ids.size()) {
                evidenceIds.put(skillCode, ids);
            }
        }
        return new MatchSnapshot(snapshots, evidenceIds);
    }

    public record MatchSnapshot(List<Map<String, Object>> skills, Map<String, List<String>> evidenceIds) {}

    public List<Map<String, Object>> evidenceByIds(String userId, List<String> ids) {
        return ids.isEmpty() ? List.of() : mapper.evidenceByIds(userId, ids);
    }

    public List<Map<String, Object>> learningAnswerDetails(List<String> ids) {
        return ids.isEmpty() ? List.of() : mapper.learningAnswerDetails(ids);
    }

    public List<Map<String, Object>> interviewMessageDetails(List<String> ids) {
        return ids.isEmpty() ? List.of() : mapper.interviewMessageDetails(ids);
    }
}
