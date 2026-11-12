package com.aijob.backend.job;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.resume.ResumeMapper;
import com.aijob.backend.task.TaskService;

import tools.jackson.databind.ObjectMapper;

class JobServiceMatchItemTest {

    @Test
    void matchItemExposesCompanyNameFromSnapshot() {
        JsonService json = new JsonService(new ObjectMapper());
        JobService service = new JobService(mock(JobMapper.class), mock(ResumeMapper.class),
                mock(ProfileService.class), mock(TaskService.class), json, new ApiView(json),
                mock(AbilityService.class));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("source_snapshot_json", "{\"title\":\"Java 后端开发\",\"companyName\":\"虚构海盐研发中心\","
                + "\"cityCode\":\"330100\",\"candidateType\":\"CAMPUS\"}");

        Map<String, Object> result = service.matchItemVo(item);

        assertEquals("虚构海盐研发中心", result.get("companyName"));
        assertEquals("330100", result.get("cityCode"));
        assertEquals("CAMPUS", result.get("candidateType"));
    }
}