package com.aijob.backend.profile.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 已确认事实，字段与接口文档 ResumeFacts 对齐；未知事实为 null。
 */
public class ResumeFacts {

    private String education;
    private Integer graduationYear;
    private Integer experienceMonths;
    private String major;
    private List<SkillFact> skills = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    public String getEducation() {
        return education;
    }

    public void setEducation(String education) {
        this.education = education;
    }

    public Integer getGraduationYear() {
        return graduationYear;
    }

    public void setGraduationYear(Integer graduationYear) {
        this.graduationYear = graduationYear;
    }

    public Integer getExperienceMonths() {
        return experienceMonths;
    }

    public void setExperienceMonths(Integer experienceMonths) {
        this.experienceMonths = experienceMonths;
    }

    public String getMajor() {
        return major;
    }

    public void setMajor(String major) {
        this.major = major;
    }

    public List<SkillFact> getSkills() {
        return skills;
    }

    public void setSkills(List<SkillFact> skills) {
        this.skills = skills == null ? new ArrayList<>() : skills;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings == null ? new ArrayList<>() : warnings;
    }
}