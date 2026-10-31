package com.aijob.backend.application.dto;

import java.time.LocalDate;

import com.aijob.backend.application.ApplicationStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 修改投递记录请求体（部分更新，三态语义）。
 * <ul>
 *   <li>没传的字段保持原值；可空字段传 null 表示清空；companyName/jobTitle/status 传 null 返回 400；</li>
 *   <li>jobId 不可修改：请求体出现 jobId（任何值，含 null）返回 400；</li>
 *   <li>全部可修改字段均为未传（空修改体）返回 400；</li>
 *   <li>expectedVersion 必填正整数，与当前版本不一致返回 409。</li>
 * </ul>
 */
public class ApplicationUpdateDTO {

    @NotNull(message = "expectedVersion 不能为空")
    @Positive(message = "expectedVersion 必须为正整数")
    private Integer expectedVersion;

    /** 不可修改字段：出现即 400，用三态建模以识别"显式传 null" */
    private PatchField<String> jobId = PatchField.undefined();

    /** 必填字段：仅接受 VALUE 态 */
    private PatchField<String> companyName = PatchField.undefined();

    /** 必填字段：仅接受 VALUE 态 */
    private PatchField<String> jobTitle = PatchField.undefined();

    private PatchField<String> cityCode = PatchField.undefined();

    private PatchField<String> education = PatchField.undefined();

    private PatchField<String> salaryText = PatchField.undefined();

    private PatchField<String> description = PatchField.undefined();

    /** 必填字段：仅接受 VALUE 态 */
    private PatchField<ApplicationStatus> status = PatchField.undefined();

    private PatchField<LocalDate> plannedDate = PatchField.undefined();

    private PatchField<LocalDate> appliedDate = PatchField.undefined();

    private PatchField<LocalDate> interviewDate = PatchField.undefined();

    private PatchField<LocalDate> closedDate = PatchField.undefined();

    private PatchField<String> note = PatchField.undefined();

    public Integer getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(Integer expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

    public PatchField<String> getJobId() {
        return jobId;
    }

    public void setJobId(PatchField<String> jobId) {
        this.jobId = jobId;
    }

    public PatchField<String> getCompanyName() {
        return companyName;
    }

    public void setCompanyName(PatchField<String> companyName) {
        this.companyName = companyName;
    }

    public PatchField<String> getJobTitle() {
        return jobTitle;
    }

    public void setJobTitle(PatchField<String> jobTitle) {
        this.jobTitle = jobTitle;
    }

    public PatchField<String> getCityCode() {
        return cityCode;
    }

    public void setCityCode(PatchField<String> cityCode) {
        this.cityCode = cityCode;
    }

    public PatchField<String> getEducation() {
        return education;
    }

    public void setEducation(PatchField<String> education) {
        this.education = education;
    }

    public PatchField<String> getSalaryText() {
        return salaryText;
    }

    public void setSalaryText(PatchField<String> salaryText) {
        this.salaryText = salaryText;
    }

    public PatchField<String> getDescription() {
        return description;
    }

    public void setDescription(PatchField<String> description) {
        this.description = description;
    }

    public PatchField<ApplicationStatus> getStatus() {
        return status;
    }

    public void setStatus(PatchField<ApplicationStatus> status) {
        this.status = status;
    }

    public PatchField<LocalDate> getPlannedDate() {
        return plannedDate;
    }

    public void setPlannedDate(PatchField<LocalDate> plannedDate) {
        this.plannedDate = plannedDate;
    }

    public PatchField<LocalDate> getAppliedDate() {
        return appliedDate;
    }

    public void setAppliedDate(PatchField<LocalDate> appliedDate) {
        this.appliedDate = appliedDate;
    }

    public PatchField<LocalDate> getInterviewDate() {
        return interviewDate;
    }

    public void setInterviewDate(PatchField<LocalDate> interviewDate) {
        this.interviewDate = interviewDate;
    }

    public PatchField<LocalDate> getClosedDate() {
        return closedDate;
    }

    public void setClosedDate(PatchField<LocalDate> closedDate) {
        this.closedDate = closedDate;
    }

    public PatchField<String> getNote() {
        return note;
    }

    public void setNote(PatchField<String> note) {
        this.note = note;
    }
}