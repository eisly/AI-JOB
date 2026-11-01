package com.aijob.backend.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<CommonResult<Void>> handleBusiness(BusinessException ex) {
        ApiErrorCode errorCode = ex.getErrorCode();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(errorCode.getHttpStatus());
        if (ex.getRetryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        }
        return builder.body(CommonResult.failure(errorCode, ex.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CommonResult<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", fieldError.getField());
            item.put("message", fieldError.getDefaultMessage());
            fields.add(item);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fields", fields);
        return ResponseEntity.status(ApiErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(CommonResult.failure(ApiErrorCode.VALIDATION_ERROR, null, details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(ApiErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(CommonResult.failure(ApiErrorCode.VALIDATION_ERROR, "请求体格式不正确", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonResult<Void>> handleUnexpected(Exception ex) {
        log.error("unhandled exception, traceId={}", TraceIdFilter.currentTraceId(), ex);
        return ResponseEntity.status(ApiErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(CommonResult.failure(ApiErrorCode.INTERNAL_ERROR, null, null));
    }
}