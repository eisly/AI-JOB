package com.aijob.backend.profile.vo;

import java.time.Instant;

/**
 * 求职画像 VO，字段与接口文档 ProfileVO 对齐。
 */
public class ProfileVO {

    private String userId;
    private Long profileVersion;
    private String targetRole;
    private String candidateType;
    private java.util.List<String> cities;
    private Integer minMonthlySalaryCents;
    private Integer maxMonthlySalaryCents;
    private ResumeFacts facts;
    private String activeResumeVersionId;
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

    public java.util.List<String> getCities() {
        return cities;
    }

    public void setCities(java.util.List<String> cities) {
        this.cities = cities;
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

    public ResumeFacts getFacts() {
        return facts;
    }

    public void setFacts(ResumeFacts facts) {
        this.facts = facts;
    }

    public String getActiveResumeVersionId() {
        return activeResumeVersionId;
    }

    public void setActiveResumeVersionId(String activeResumeVersionId) {
        this.activeResumeVersionId = activeResumeVersionId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}