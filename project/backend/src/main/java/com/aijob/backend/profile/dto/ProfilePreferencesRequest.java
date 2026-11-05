package com.aijob.backend.profile.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 保存求职偏好请求，字段与接口文档 /api/v1/profile/preferences 对齐。
 */
public class ProfilePreferencesRequest {

    @NotNull(message = "expectedProfileVersion 不能为空")
    @Min(value = 0, message = "expectedProfileVersion 不能为负数")
    private Long expectedProfileVersion;

    @NotBlank(message = "targetRole 不能为空")
    @Size(max = 32, message = "targetRole 长度不能超过 32 个字符")
    private String targetRole;

    @NotBlank(message = "candidateType 不能为空")
    @Pattern(regexp = "INTERNSHIP|CAMPUS|EXPERIENCED", message = "candidateType 取值不合法")
    private String candidateType;

    @NotNull(message = "cities 不能为空")
    @Size(min = 1, max = 5, message = "cities 必须包含 1 到 5 个城市编码")
    private List<@NotBlank(message = "城市编码不能为空") @Size(max = 12, message = "城市编码长度不能超过 12") String> cities;

    @Min(value = 0, message = "minMonthlySalaryCents 不能为负数")
    private Integer minMonthlySalaryCents;

    @Min(value = 0, message = "maxMonthlySalaryCents 不能为负数")
    private Integer maxMonthlySalaryCents;

    @AssertTrue(message = "maxMonthlySalaryCents 不得小于 minMonthlySalaryCents")
    @JsonIgnore
    public boolean isSalaryRangeValid() {
        return minMonthlySalaryCents == null
                || maxMonthlySalaryCents == null
                || minMonthlySalaryCents <= maxMonthlySalaryCents;
    }

    public Long getExpectedProfileVersion() {
        return expectedProfileVersion;
    }

    public void setExpectedProfileVersion(Long expectedProfileVersion) {
        this.expectedProfileVersion = expectedProfileVersion;
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

    public List<String> getCities() {
        return cities;
    }

    public void setCities(List<String> cities) {
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
}