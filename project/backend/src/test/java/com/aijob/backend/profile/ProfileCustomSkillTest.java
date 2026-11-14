package com.aijob.backend.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aijob.backend.common.ApiView;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;

import tools.jackson.databind.json.JsonMapper;

class ProfileCustomSkillTest {
    @Test
    void createsPrivatePendingSkillAndChecksOwner() {
        ProfileMapper mapper = mock(ProfileMapper.class);
        JsonService json = new JsonService(JsonMapper.builder().build());
        ProfileService service = new ProfileService(mapper, json, new ApiView(json));
        when(mapper.skillByName("alice", "FastAPI"))
                .thenReturn(null, Map.of("code", "CUSTOM_1", "name", "FastAPI", "normalization_status", "PENDING", "enabled", 1));
        Map<String, Object> result = service.createCustomSkill("alice", Map.of("name", " FastAPI "));
        assertEquals("PENDING", result.get("normalizationStatus"));
        verify(mapper).insertCustomSkill(eq("alice"), startsWith("CUSTOM_"), eq("FastAPI"));
        when(mapper.skillEnabled("alice", "CUSTOM_1")).thenReturn(1);
        service.requireSkill("alice", "CUSTOM_1");
        assertThrows(BusinessException.class, () -> service.requireSkill("bob", "CUSTOM_1"));
    }
}
