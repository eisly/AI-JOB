package com.aijob.backend.resume;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.Map;
import java.util.List;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.JsonService;
import com.aijob.backend.task.AiTask;
import tools.jackson.databind.json.JsonMapper;

class ResumeResultHandlerTest {
    @Test
    void windowsPdfLineEndingsMatchAgentHashButDifferentTextIsRejected() {
        ResumeMapper mapper=mock(ResumeMapper.class);
        JsonService json=new JsonService(JsonMapper.builder().build());
        ResumeResultHandler handler=new ResumeResultHandler(mapper,json);
        AiTask task=new AiTask(); task.setResourceId("version");task.setUserId("user");
        when(mapper.lockVersion("version","user")).thenReturn(Map.of("status","PARSING",
                "extracted_text","Java\r\nMySQL\r\n", "version_no",1));
        when(mapper.parsed(any(),any(),any())).thenReturn(1);
        Map<String,Object> facts=Map.of("skills",List.of(Map.of("skillCode","JAVA","claim","会 Java",
                "quote","Java","location","page:1;line:1","confirmed",false)));
        assertEquals(1,handler.success(task,json.tree(Map.of("textHash",json.sha256("Java\nMySQL"),"facts",facts))));
        assertThrows(BusinessException.class,()->handler.success(task,
                json.tree(Map.of("textHash",json.sha256("different"),"facts",facts))));
    }

    @Test
    void emptyFactsAreRejectedAsParseFailure() {
        ResumeMapper mapper=mock(ResumeMapper.class);
        JsonService json=new JsonService(JsonMapper.builder().build());
        ResumeResultHandler handler=new ResumeResultHandler(mapper,json);
        AiTask task=new AiTask(); task.setResourceId("version");task.setUserId("user");
        when(mapper.lockVersion("version","user")).thenReturn(Map.of("status","PARSING",
                "extracted_text","[page:1]\n[page:2]", "version_no",1));
        BusinessException ex=assertThrows(BusinessException.class,()->handler.success(task,
                json.tree(Map.of("textHash",json.sha256("[page:1]\n[page:2]"),"facts",Map.of()))));
        assertTrue(ex.getMessage().contains("没有任何有效事实"));
        verify(mapper,never()).parsed(any(),any(),any());
    }

    @Test
    void parsedExperienceFactsRemainUnconfirmed() {
        ResumeMapper mapper = mock(ResumeMapper.class);
        JsonService json = new JsonService(JsonMapper.builder().build());
        ResumeResultHandler handler = new ResumeResultHandler(mapper, json);
        AiTask task = new AiTask();
        task.setResourceId("version");
        task.setUserId("user");
        when(mapper.lockVersion("version", "user")).thenReturn(Map.of("status", "PARSING",
                "extracted_text", "实习经历\n获一等奖学金", "version_no", 1));
        when(mapper.parsed(any(), any(), any())).thenReturn(1);
        Map<String, Object> item = Map.of("claim", "经历", "quote", "实习经历", "location", "paragraph:1", "confirmed", true);
        handler.success(task, json.tree(Map.of("textHash", json.sha256("实习经历\n获一等奖学金"),
                "facts", Map.of("internships", List.of(item), "awards", List.of(item)))));
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(mapper).parsed(eq("version"), stored.capture(), anyString());
        Map<String, Object> facts = json.map(stored.getValue());
        for (String field : List.of("internships", "awards")) {
            assertEquals(false, ((Map<?, ?>) ((List<?>) facts.get(field)).getFirst()).get("confirmed"));
        }
    }
}
