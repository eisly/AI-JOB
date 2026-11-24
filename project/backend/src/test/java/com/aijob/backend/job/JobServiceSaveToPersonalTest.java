package com.aijob.backend.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.profile.ProfileService;

import tools.jackson.databind.json.JsonMapper;

class JobServiceSaveToPersonalTest {

    private JobService service(JobMapper mapper) {
        JsonService json = new JsonService(JsonMapper.builder().build());
        return new JobService(mapper, null, mock(ProfileService.class), null, json, new ApiView(json), null);
    }

    private Map<String, Object> searchJob() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", "search-job");
        row.put("job_version", 3);
        row.put("status", "ACTIVE");
        row.put("source_type", "PUBLIC_SEARCH");
        row.put("source_name", "AI 全网搜索");
        row.put("source_url", "https://example.com/job/1");
        row.put("requirements_json", "{\"skills\":[\"JAVA\"]}");
        return row;
    }

    @Test
    void copiesSearchJobIntoPersonalJd() {
        JobMapper mapper = mock(JobMapper.class);
        when(mapper.job("search-job", "user")).thenReturn(searchJob());
        when(mapper.savedPersonalJobId("user", "search-job")).thenReturn(null);
        when(mapper.copyJobVersion(anyString(), anyString(), eq("search-job"), eq(3))).thenReturn(1);

        Map<String, Object> result = service(mapper).saveToPersonal("user", "search-job");

        assertTrue((Boolean) result.get("created"));
        verify(mapper).insertSavedSource(anyString(), eq("AI 全网搜索"));
        verify(mapper).insertSavedJob(anyString(), anyString(), eq("user"), eq("https://example.com/job/1"));
        verify(mapper).copyJobVersion(anyString(), anyString(), eq("search-job"), eq(3));
    }

    @Test
    void reusesExistingPersonalCopy() {
        JobMapper mapper = mock(JobMapper.class);
        when(mapper.job("search-job", "user")).thenReturn(searchJob());
        when(mapper.savedPersonalJobId("user", "search-job")).thenReturn("personal-job");

        Map<String, Object> result = service(mapper).saveToPersonal("user", "search-job");

        assertFalse((Boolean) result.get("created"));
        assertEquals("personal-job", result.get("jobId"));
        verify(mapper, never()).insertSavedJob(anyString(), anyString(), anyString(), anyString());
        verify(mapper, never()).copyJobVersion(anyString(), anyString(), anyString(), eq(3));
    }

    @Test
    void returnsPersonalJobDirectly() {
        JobMapper mapper = mock(JobMapper.class);
        Map<String, Object> row = searchJob();
        row.put("source_type", "USER_INPUT");
        when(mapper.job("user-job", "user")).thenReturn(row);

        Map<String, Object> result = service(mapper).saveToPersonal("user", "user-job");

        assertFalse((Boolean) result.get("created"));
        assertEquals("user-job", result.get("jobId"));
        verify(mapper, never()).insertSavedSource(anyString(), anyString());
    }

    @Test
    void rejectsUnanalyzedJob() {
        JobMapper mapper = mock(JobMapper.class);
        Map<String, Object> row = searchJob();
        row.put("requirements_json", null);
        when(mapper.job("search-job", "user")).thenReturn(row);

        assertThrows(BusinessException.class, () -> service(mapper).saveToPersonal("user", "search-job"));
        verify(mapper, never()).insertSavedJob(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void hidesInvisibleJob() {
        JobMapper mapper = mock(JobMapper.class);
        when(mapper.job("other-job", "user")).thenReturn(null);

        assertThrows(BusinessException.class, () -> service(mapper).saveToPersonal("user", "other-job"));
    }
}