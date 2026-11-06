package com.aijob.backend.common;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;

public final class Responses {

    private Responses() {
    }

    public static <T> CommonResult<T> ok(T data) {
        return trace(CommonResult.success(data));
    }

    public static <T> CommonResult<T> accepted(T data) {
        return trace(new CommonResult<>(202, "任务已受理", data));
    }

    public static <T> ResponseEntity<CommonResult<T>> acceptedEntity(T data) {
        return ResponseEntity.status(202).body(accepted(data));
    }

    public static <T> CommonResult<T> trace(CommonResult<T> result) {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_KEY);
        return result.withTraceId(traceId == null ? UUID.randomUUID().toString() : traceId);
    }
}