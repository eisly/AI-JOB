package com.aijob.backend.profile.service.impl;

import com.aijob.backend.common.BizException;
import com.aijob.backend.profile.dto.ProfilePreferencesRequest;
import com.aijob.backend.profile.entity.UserProfile;
import com.aijob.backend.profile.mapper.SkillCatalogMapper;
import com.aijob.backend.profile.mapper.UserProfileMapper;
import com.aijob.backend.profile.service.ProfileService;
import com.aijob.backend.profile.vo.ProfileVO;
import com.aijob.backend.profile.vo.ResumeFacts;
import com.aijob.backend.profile.vo.SkillVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 求职画像服务实现：读取 confirmed_facts_json/cities_json，写偏好时使用乐观锁。
 */
@Service
public class ProfileServiceImpl implements ProfileService {

    private final UserProfileMapper userProfileMapper;
    private final SkillCatalogMapper skillCatalogMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProfileServiceImpl(UserProfileMapper userProfileMapper,
                              SkillCatalogMapper skillCatalogMapper) {
        this.userProfileMapper = userProfileMapper;
        this.skillCatalogMapper = skillCatalogMapper;
    }

    @Override
    public ProfileVO getProfile(String userId) {
        UserProfile profile = userProfileMapper.selectByUserId(userId);
        if (profile == null) {
            throw resourceNotFound("画像不存在");
        }
        return toVO(profile);
    }

    @Override
    @Transactional
    public ProfileVO savePreferences(String userId, ProfilePreferencesRequest request) {
        UserProfile current = userProfileMapper.selectByUserId(userId);
        if (current == null) {
            throw resourceNotFound("画像不存在");
        }

        String citiesJson = writeCities(request.getCities());
        String targetRole = request.getTargetRole().trim();
        int updated = userProfileMapper.updatePreferences(
                userId,
                request.getExpectedProfileVersion(),
                targetRole,
                request.getCandidateType(),
                citiesJson,
                request.getMinMonthlySalaryCents(),
                request.getMaxMonthlySalaryCents()
        );

        if (updated == 0) {
            UserProfile latest = userProfileMapper.selectByUserId(userId);
            if (latest == null) {
                throw resourceNotFound("画像不存在");
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("currentVersion", latest.getProfileVersion());
            throw new BizException(HttpStatus.CONFLICT, 40901, "VERSION_CONFLICT",
                    "数据版本已变化，请刷新后重试", false, details);
        }

        return toVO(userProfileMapper.selectByUserId(userId));
    }

    @Override
    public List<SkillVO> listSkills(String q) {
        String normalized = q == null ? null : q.trim();
        if (normalized != null && normalized.length() > 50) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("field", "q");
            field.put("message", "q 长度不能超过 50");
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("fields", List.of(field));
            throw new BizException(HttpStatus.BAD_REQUEST, 40001, "VALIDATION_ERROR",
                    "请求参数校验失败", false, details);
        }
        if (normalized != null && normalized.isEmpty()) {
            normalized = null;
        }
        return skillCatalogMapper.selectEnabled(normalized);
    }

    private ProfileVO toVO(UserProfile profile) {
        ProfileVO vo = new ProfileVO();
        vo.setUserId(profile.getUserId());
        vo.setProfileVersion(profile.getProfileVersion());
        vo.setTargetRole(profile.getTargetRole());
        vo.setCandidateType(profile.getCandidateType());
        vo.setCities(readCities(profile.getCitiesJson()));
        vo.setMinMonthlySalaryCents(profile.getMinMonthlySalaryCents());
        vo.setMaxMonthlySalaryCents(profile.getMaxMonthlySalaryCents());
        vo.setFacts(readFacts(profile.getConfirmedFactsJson()));
        vo.setActiveResumeVersionId(profile.getActiveResumeVersionId());
        vo.setUpdatedAt(profile.getUpdatedAt());
        return vo;
    }

    private List<String> readCities(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> cities = objectMapper.readValue(json, new TypeReference<>() {
            });
            return cities == null ? new ArrayList<>() : cities;
        } catch (JsonProcessingException ex) {
            throw dataCorrupted();
        }
    }

    private ResumeFacts readFacts(String json) {
        if (json == null || json.isBlank()) {
            return new ResumeFacts();
        }
        try {
            ResumeFacts facts = objectMapper.readValue(json, ResumeFacts.class);
            return facts == null ? new ResumeFacts() : facts;
        } catch (JsonProcessingException ex) {
            throw dataCorrupted();
        }
    }

    private String writeCities(List<String> cities) {
        try {
            return objectMapper.writeValueAsString(cities);
        } catch (JsonProcessingException ex) {
            throw dataCorrupted();
        }
    }

    private BizException resourceNotFound(String message) {
        return new BizException(HttpStatus.NOT_FOUND, 40401, "RESOURCE_NOT_FOUND", message);
    }

    private BizException dataCorrupted() {
        return new BizException(HttpStatus.INTERNAL_SERVER_ERROR, 50001, "INTERNAL_ERROR",
                "画像数据格式异常");
    }
}