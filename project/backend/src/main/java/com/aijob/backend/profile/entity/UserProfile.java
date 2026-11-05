package com.aijob.backend.profile.entity;

import java.time.Instant;

/**
 * user_profile 表实体；JSON 列保持原始字符串，由服务层转换。
 */
public class UserProfile {

    private String userId;
    private Long profileVersion;
    private String targetRole;
    private String candidateType;
    private String citiesJson;
    private Integer minMonthlySalaryCents;
    private Integer maxMonthlySalaryCents;
    private String confirmedFactsJson;
    private String activeResumeVersionId;
    private Instant createdAt;
    private Instant updatedAt;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Long getProfileVersion() {
        return profileVersion;
    }

    public void setProfileVersion(Long profileVersion) {
        this.profileVersion = profileVersion;
    }

    public String getTargetRole() {
        return targetRole;
    }

    public void setTargetRole(String targetRole) {
        this.targetRole = targetRole;
    }

    public String getCandidateType() {
        return candidateType;
    }

    public void setCandidateType(String candidateType) {
        this.candidateType = candidateType;
    }

    public String getCitiesJson() {
        return citiesJson;
    }

    public void setCitiesJson(String citiesJson) {
        this.citiesJson = citiesJson;
    }

    public Integer getMinMonthlySalaryCents() {
        return minMonthlySalaryCents;
    }

    public void setMinMonthlySalaryCents(Integer minMonthlySalaryCents) {
        this.minMonthlySalaryCents = minMonthlySalaryCents;
    }

    public Integer getMaxMonthlySalaryCents() {
        return maxMonthlySalaryCents;
    }

    public void setMaxMonthlySalaryCents(Integer maxMonthlySalaryCents) {
        this.maxMonthlySalaryCents = maxMonthlySalaryCents;
    }

    public String getConfirmedFactsJson() {
        return confirmedFactsJson;
    }

    public void setConfirmedFactsJson(String confirmedFactsJson) {
        this.confirmedFactsJson = confirmedFactsJson;
    }

    public String getActiveResumeVersionId() {
        return activeResumeVersionId;
    }

    public void setActiveResumeVersionId(String activeResumeVersionId) {
        this.activeResumeVersionId = activeResumeVersionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}