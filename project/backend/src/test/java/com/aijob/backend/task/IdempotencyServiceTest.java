package com.aijob.backend.task;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.JsonService;

import tools.jackson.databind.json.JsonMapper;

class IdempotencyServiceTest {

    private final TaskMapper mapper = mock(TaskMapper.class);
    private final JsonService json = new JsonService(JsonMapper.builder().build());
    private final IdempotencyService service = new IdempotencyService(mapper, json);

    @Test
    void sameKeyAndBodyReplaysOriginalResponse() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> request = Map.of("resumeVersionId", "version");
        AtomicInteger actions = new AtomicInteger();
        when(mapper.insertIdempotency(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        ResponseEntity<CommonResult<Object>> first = service.executeTask("user", "POST", "/route", key, request,
                () -> new IdempotencyService.ActionResult("task", Map.of("id", "task", "attempt", actions.incrementAndGet())));
        Map<String, Object> existing = new HashMap<>();
        existing.put("request_digest", java.util.HexFormat.of().parseHex(json.digest(request)));
        existing.put("http_status", 202);
        existing.put("response_json", json.stringify(first.getBody()));
        when(mapper.insertIdempotency(any(), any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(mapper.lockIdempotency("user", "POST", "/route", key)).thenReturn(existing);

        ResponseEntity<CommonResult<Object>> replay = service.executeTask("user", "POST", "/route", key, request,
                () -> new IdempotencyService.ActionResult("other", Map.of("id", "other", "attempt", actions.incrementAndGet())));

        assertEquals(202, replay.getStatusCode().value());
        assertEquals(first.getBody().getData(), replay.getBody().getData());
        assertEquals(1, actions.get());
    }

    @Test
    void sameKeyWithDifferentBodyReturns40903() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> original = Map.of("value", 1);
        Map<String, Object> existing = new HashMap<>();
        existing.put("request_digest", java.util.HexFormat.of().parseHex(json.digest(original)));
        when(mapper.insertIdempotency(any(), any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(mapper.lockIdempotency("user", "POST", "/route", key)).thenReturn(existing);

        BusinessException error = assertThrows(BusinessException.class, () -> service.executeTask("user", "POST",
                "/route", key, Map.of("value", 2), () -> new IdempotencyService.ActionResult("task", Map.of())));

        assertEquals(40903, error.getCode());
        assertEquals("IDEMPOTENCY_CONFLICT", error.getErrorKey());
    }
}