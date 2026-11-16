package com.aijob.backend.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.Data;
import com.aijob.backend.common.JsonService;

@Service
public class ProfileService {

    private static final Set<String> ROLES = Set.of("JAVA_BACKEND", "FRONTEND", "ALGORITHM", "TEST", "DATA");
    private static final Set<String> CANDIDATES = Set.of("INTERNSHIP", "CAMPUS", "EXPERIENCED");

    private final ProfileMapper mapper;
    private final JsonService json;
    private final ApiView view;

    public ProfileService(ProfileMapper mapper, JsonService json, ApiView view) {
        this.mapper = mapper;
        this.json = json;
        this.view = view;
    }

    public Map<String, Object> get(String userId) {
        mapper.ensure(userId);
        return vo(mapper.find(userId));
    }

    public Map<String, Object> locked(String userId) {
        mapper.ensure(userId);
        return mapper.lock(userId);
    }

    @Transactional
    public Map<String, Object> preferences(String userId, Map<String, Object> body) {
        Map<String, Object> current = locked(userId);
        Integer expectedValue = Data.integer(body, "expectedProfileVersion", null, 1, Integer.MAX_VALUE);
        if (expectedValue == null) {
            throw BusinessException.validation("expectedProfileVersion", "expectedProfileVersion 不能为空");
        }
        long expected = expectedValue;
        long actual = ((Number) current.get("profile_version")).longValue();
        if (actual != expected) {
            throw BusinessException.versionConflict(actual);
        }
        String role = Data.text(body, "targetRole", true, 32);
        String candidate = Data.text(body, "candidateType", true, 32);
        if (!CANDIDATES.contains(candidate)) {
            throw BusinessException.validation("body", "岗位方向或求职类型非法");
        }
        List<Object> rawCities = Data.list(body, "cities", true, 5);
        List<String> cities = new ArrayList<>();
        for (Object item : rawCities) {
            if (!(item instanceof String city) || !city.matches("[0-9A-Za-z_-]{1,12}")) {
                throw BusinessException.validation("cities", "城市编码非法");
            }
            if (!cities.contains(city)) {
                cities.add(city);
            }
        }
        Integer min = Data.integer(body, "minMonthlySalaryCents", null, 0, 100000000);
        Integer max = Data.integer(body, "maxMonthlySalaryCents", null, 0, 100000000);
        if (min != null && max != null && min > max) {
            throw BusinessException.validation("maxMonthlySalaryCents", "薪资上限不能小于下限");
        }
        if (mapper.updatePreferences(userId, role, candidate, json.stringify(cities), min, max, expected) == 0) {
            throw BusinessException.versionConflict(actual);
        }
        return vo(mapper.find(userId));
    }

    public Set<String> roles() {
        return ROLES;
    }

    public List<Map<String, Object>> skillCatalog() {
        return skillCatalog(null);
    }

    public List<Map<String, Object>> skillCatalog(String userId) {
        return mapper.skills(userId).stream().map(view::map).toList();
    }

    public List<Map<String, Object>> skillCatalog(String userId, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.length() > 50) throw BusinessException.validation("q", "q 长度不能超过 50");
        return skillCatalog(userId).stream().filter(item -> q.isEmpty()
                || String.valueOf(item.get("name")).toLowerCase(java.util.Locale.ROOT).contains(q)
                || String.valueOf(item.get("code")).toLowerCase(java.util.Locale.ROOT).contains(q)).toList();
    }

    public List<Map<String, Object>> agentSkillCatalog() {
        return agentSkillCatalog(null);
    }

    public List<Map<String, Object>> agentSkillCatalog(String userId) {
        return mapper.skills(userId).stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", row.get("code"));
            item.put("name", row.get("name"));
            return item;
        }).toList();
    }

    public void requireSkill(String code) {
        requireSkill(null, code);
    }

    public void requireSkill(String userId, String code) {
        if (mapper.skillEnabled(userId, code) == 0) {
            throw BusinessException.validation("skillCode", "技能目录中不存在: " + code);
        }
    }

    @Transactional
    public Map<String, Object> createCustomSkill(String userId, Map<String, Object> body) {
        String name = Data.text(body, "name", true, 100);
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw BusinessException.validation("name", "技能名称不能包含控制字符");
        }
        if (name.indexOf('<') >= 0 || name.indexOf('>') >= 0
                || name.codePoints().noneMatch(Character::isLetterOrDigit)) {
            throw BusinessException.validation("name", "请填写技术名称，不能包含标记代码或仅由符号组成");
        }
        Map<String, Object> existing = mapper.skillByName(userId, name);
        if (existing != null) return view.map(existing);
        String code = "CUSTOM_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        mapper.insertCustomSkill(userId, code, name);
        return view.map(mapper.skillByName(userId, name));
    }

    public void confirmFacts(String userId, String versionId, Map<String, Object> facts, long expectedVersion) {
        if (mapper.confirmFacts(userId, json.stringify(facts), versionId, expectedVersion) == 0) {
            throw BusinessException.versionConflict(expectedVersion);
        }
    }

    public void clearActive(String userId, String versionId) {
        mapper.clearActive(userId, versionId);
    }

    public Map<String, Object> vo(Map<String, Object> row) {
        Map<String, Object> result = view.map(row, "created_at");
        result.put("facts", result.remove("confirmedFacts"));
        return result;
    }

    public Map<String, Object> snapshot(String userId) {
        return get(userId);
    }
}
