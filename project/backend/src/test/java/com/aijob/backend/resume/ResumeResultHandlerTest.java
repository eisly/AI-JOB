package com.aijob.backend.resume;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.Map;
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
        assertEquals(1,handler.success(task,json.tree(Map.of("textHash",json.sha256("Java\nMySQL"),"facts",Map.of()))));
        assertThrows(BusinessException.class,()->handler.success(task,
                json.tree(Map.of("textHash",json.sha256("different"),"facts",Map.of()))));
    }
}
