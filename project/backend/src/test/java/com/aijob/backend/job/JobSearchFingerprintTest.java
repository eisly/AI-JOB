package com.aijob.backend.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class JobSearchFingerprintTest {

    @Test
    void equivalentCompanyAndTitleVariantsShareFingerprint() {
        String first = JobSearchFingerprint.of("腾讯科技有限公司", "Java 后端（急聘）", "深圳");
        String second = JobSearchFingerprint.of("腾讯科技", "Java后端", "深圳市");
        assertEquals(first, second);
        assertEquals(32, first.length());
    }

    @Test
    void differentCityKeepsSeparateFingerprint() {
        String shenzhen = JobSearchFingerprint.of("腾讯科技", "Java 后端", "440300");
        String beijing = JobSearchFingerprint.of("腾讯科技", "Java 后端", "110100");
        assertNotEquals(shenzhen, beijing);
    }

    @Test
    void differentRoleKeepsSeparateFingerprint() {
        assertNotEquals(
                JobSearchFingerprint.of("腾讯科技", "Java 后端", "440300"),
                JobSearchFingerprint.of("腾讯科技", "前端开发", "440300"));
    }
}