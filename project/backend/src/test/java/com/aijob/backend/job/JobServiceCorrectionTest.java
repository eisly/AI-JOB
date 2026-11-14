package com.aijob.backend.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.profile.ProfileService;

import tools.jackson.databind.json.JsonMapper;

class JobServiceCorrectionTest {
    @Test
    void savesNewVersionWithConfirmedFieldsAndOriginalQuote() {
        JobMapper mapper = mock(JobMapper.class);
        ProfileService profiles = mock(ProfileService.class);
        JsonService json = new JsonService(JsonMapper.builder().build());
        JobService service = spy(new JobService(mapper, null, profiles, null, json, new ApiView(json), null));
        Map<String, Object> row = new HashMap<>();
        row.put("visibility", "PRIVATE");
        row.put("owner_user_id", "user");
        row.put("status", "ACTIVE");
        row.put("job_version", 1);
        row.put("job_version_id", "old-version");
        row.put("jd_text", "工作地点：广州。熟悉 FastAPI 和 Vue。");
        row.put("city_code", null);
        row.put("requirements_json", "{\"skills\":[],\"requiredSkillLevels\":{}}");
        when(mapper.lockJob("job", "user")).thenReturn(row);
        when(mapper.insertCorrectedVersion(anyString(), eq("old-version"), eq("440100"), anyString(), anyString())).thenReturn(1);
        when(mapper.advancePrivateVersion("job", "user", 1)).thenReturn(1);
        when(mapper.job("job", "user")).thenReturn(row);
        doReturn(Map.of("jobVersion", 2)).when(service).jobVo(row, "user");
        Map<String, Object> body = Map.of("expectedJobVersion", 1, "cityCode", "440100",
                "skillCodes", List.of("FASTAPI", "VUE"), "sourceQuote", "工作地点：广州。熟悉 FastAPI 和 Vue。",
                "reason", "补全地点和技能");
        assertEquals(2, service.correctPrivateJob("user", "job", body).get("jobVersion"));
        verify(profiles).requireSkill("user", "FASTAPI");
        verify(profiles).requireSkill("user", "VUE");
        verify(mapper).advancePrivateVersion("job", "user", 1);
    }

    @Test
    void rejectsQuoteNotPresentInJd() {
        JobMapper mapper = mock(JobMapper.class);
        JsonService json = new JsonService(JsonMapper.builder().build());
        JobService service = new JobService(mapper, null, mock(ProfileService.class), null, json, new ApiView(json), null);
        Map<String, Object> row = new HashMap<>();
        row.put("visibility", "PRIVATE");
        row.put("owner_user_id", "user");
        row.put("status", "ACTIVE");
        row.put("job_version", 1);
        row.put("requirements_json", "{\"skills\":[]}");
        row.put("jd_text", "工作地点：广州");
        when(mapper.lockJob("job", "user")).thenReturn(row);
        assertThrows(BusinessException.class, () -> service.correctPrivateJob("user", "job", Map.of(
                "expectedJobVersion", 1, "cityCode", "440100", "skillCodes", List.of(),
                "sourceQuote", "工作地点：深圳", "reason", "地点错误")));
        verify(mapper, never()).insertCorrectedVersion(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void recoversFailedAnalysisWithManualFields() {
        JobMapper mapper = mock(JobMapper.class);
        ProfileService profiles = mock(ProfileService.class);
        JsonService json = new JsonService(JsonMapper.builder().build());
        JobService service = spy(new JobService(mapper, null, profiles, null, json, new ApiView(json), null));
        Map<String, Object> row = new HashMap<>();
        row.put("visibility", "PRIVATE");
        row.put("owner_user_id", "user");
        row.put("status", "FAILED");
        row.put("job_version", 1);
        row.put("job_version_id", "old-version");
        row.put("jd_text", "工作地点：广州。熟悉 FastAPI。");
        when(mapper.lockJob("job", "user")).thenReturn(row);
        when(mapper.insertCorrectedVersion(anyString(), eq("old-version"), eq("440100"), anyString(), anyString())).thenReturn(1);
        when(mapper.advancePrivateVersion("job", "user", 1)).thenReturn(1);
        when(mapper.job("job", "user")).thenReturn(row);
        doReturn(Map.of("jobVersion", 2)).when(service).jobVo(row, "user");
        service.correctPrivateJob("user", "job", Map.of("expectedJobVersion", 1,
                "cityCode", "440100", "skillCodes", List.of("FASTAPI"),
                "sourceQuote", "工作地点：广州", "reason", "自动分析失败，人工补全"));
        ArgumentCaptor<String> requirements = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertCorrectedVersion(anyString(), eq("old-version"), eq("440100"),
                requirements.capture(), anyString());
        Map<String, Object> saved = json.map(requirements.getValue());
        assertEquals(List.of("FASTAPI"), saved.get("skills"));
        assertEquals(Map.of(), saved.get("requiredSkillLevels"));
        verify(mapper).advancePrivateVersion("job", "user", 1);
    }
}
