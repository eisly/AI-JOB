package com.aijob.backend.job;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JobSearchLinksTest {

    @Test
    void acceptsOnlyHttpAndHttpsWithHost() {
        assertTrue(JobSearchLinks.isHttpUrl("https://careers.tencent.com/jobdesc.html?postId=1"));
        assertTrue(JobSearchLinks.isHttpUrl("http://example.com/jobs/1"));
    }

    @Test
    void rejectsDeadOrUnsafeLinkFormats() {
        assertFalse(JobSearchLinks.isHttpUrl(null));
        assertFalse(JobSearchLinks.isHttpUrl(""));
        assertFalse(JobSearchLinks.isHttpUrl("javascript:alert(1)"));
        assertFalse(JobSearchLinks.isHttpUrl("file:///C:/secret.txt"));
        assertFalse(JobSearchLinks.isHttpUrl("ftp://example.com/job"));
        assertFalse(JobSearchLinks.isHttpUrl("http://"));
        assertFalse(JobSearchLinks.isHttpUrl("not-a-url"));
    }
}