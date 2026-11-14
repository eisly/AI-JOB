package com.aijob.backend.application.dto;

import java.time.LocalDate;

import com.aijob.backend.application.ApplicationStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 新增投递记录请求体。
 * 名称类字段 trim 后长度约束（1..100 等）由 Service 层按接口文档语义校验，
 * 以保证"去除首尾空白后"的准确判定；此处仅做非空白基础校验。
 */
public class ApplicationCreateDTO {

    /** 关联岗位 ID；手动录入可省略或为 null。非 null 时必须是合法 UUID，且当前用户须有权查看该岗位。 */
    @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
            message = "jobId 必须是合法 UUID")
    private String jobId;

    /** 公司名称，trim 后 1..100 字符 */
    @NotBlank(message = "companyName 不能为空白")
    private String companyName;

    /** 岗位名称，trim 后 1..100 字符 */
    @NotBlank(message = "jobTitle 不能为空白")
    private String jobTitle;

    /** 城市编码，≤12 字符 */
    private String cityCode;

    /** 学历展示文本，≤50 字符 */
    private String education;

    /** 薪资展示文本，≤100 字符 */
    private String salaryText;

    /** 岗位简述，≤1000 字符 */
    private String description;

    /** 投递状态；省略时为 PLANNED。非法值在反序列化阶段返回 400。 */
    private ApplicationStatus status;

    private LocalDate plannedDate;

    private LocalDate appliedDate;

    private LocalDate interviewDate;

    private LocalDate closedDate;

    /** 备注，≤2000 字符 */
    private String note;

    /**
     * 同一岗位重复添加确认标记。
     * 同一用户对同一岗位已存在投递记录时，首次提交返回 409（DUPLICATE_APPLICATION）；
     * 用户明确确认再次添加后传 true 创建独立的新记录。
     */
    private Boolean confirmDuplicate;

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public void setJobTitle(String jobTitle) {
        this.jobTitle = jobTitle;
    }

    public String getCityCode() {
        return cityCode;
    }

    public void setCityCode(String cityCode) {
        this.cityCode = cityCode;
    }

    public String getEducation() {
        return education;
    }

    public void setEducation(String education) {
        this.education = education;
    }

    public String getSalaryText() {
        return salaryText;
    }

    public void setSalaryText(String salaryText) {
        this.salaryText = salaryText;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public void setStatus(ApplicationStatus status) {
        this.status = status;
    }

    public LocalDate getPlannedDate() {
        return plannedDate;
    }

    public void setPlannedDate(LocalDate plannedDate) {
        this.plannedDate = plannedDate;
    }

    public LocalDate getAppliedDate() {
        return appliedDate;
    }

    public void setAppliedDate(LocalDate appliedDate) {
        this.appliedDate = appliedDate;
    }

    public LocalDate getInterviewDate() {
        return interviewDate;
    }

    public void setInterviewDate(LocalDate interviewDate) {
        this.interviewDate = interviewDate;
    }

    public LocalDate getClosedDate() {
        return closedDate;
    }

    public void setClosedDate(LocalDate closedDate) {
        this.closedDate = closedDate;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Boolean getConfirmDuplicate() {
        return confirmDuplicate;
    }

    public void setConfirmDuplicate(Boolean confirmDuplicate) {
        this.confirmDuplicate = confirmDuplicate;
    }
}