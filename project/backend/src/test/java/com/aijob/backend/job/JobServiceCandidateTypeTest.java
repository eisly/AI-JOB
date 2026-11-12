package com.aijob.backend.job;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JobServiceCandidateTypeTest {

    @Test
    void campusRecruitingIsNotTreatedAsExperienced() {
        assertEquals("CAMPUS", JobService.candidateType("Java 后端开发 2027 届校园招聘"));
        assertEquals("CAMPUS", JobService.candidateType("后端开发工程师 招聘对象：2027 届应届生"));
    }

    @Test
    void internshipWinsWhenTextMentionsInternship() {
        assertEquals("INTERNSHIP", JobService.candidateType("AI 应用开发实习生 2027 届"));
        assertEquals("INTERNSHIP", JobService.candidateType("Backend Intern, 2027 graduate"));
    }

    @Test
    void experiencedRemainsDefaultWithoutCampusOrInternshipEvidence() {
        assertEquals("EXPERIENCED", JobService.candidateType("Java 后端开发工程师 3-5 年经验"));
    }
}