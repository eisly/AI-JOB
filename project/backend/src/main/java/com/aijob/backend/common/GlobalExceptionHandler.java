package com.aijob.backend.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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

/**
 * 统一异常处理，保证错误响应使用真实 HTTP 状态并携带 error.key。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<CommonResult<Void>> handleBiz(BizException ex) {
        ErrorInfo error = new ErrorInfo(ex.getKey(), ex.isRetryable(), ex.getDetails());
        CommonResult<Void> body = CommonResult.failure(ex.getCode(), ex.getMessage(), error);
        return ResponseEntity.status(ex.getStatus()).body(body);
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
        return validationError(fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<CommonResult<Void>> handleConstraint(ConstraintViolationException ex) {
        List<Map<String, Object>> fields = new ArrayList<>();
        ex.getConstraintViolations().forEach(violation -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", violation.getPropertyPath().toString());
            item.put("message", violation.getMessage());
            fields.add(item);
        });
        return validationError(fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResult<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        return validationError(List.of(Map.of("field", "body", "message", "请求体格式错误")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonResult<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        CommonResult<Void> body = CommonResult.failure(50001, "服务器内部错误",
                new ErrorInfo("INTERNAL_ERROR", false, null));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ResponseEntity<CommonResult<Void>> validationError(List<Map<String, Object>> fields) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fields", fields);
        CommonResult<Void> body = CommonResult.failure(40001, "请求参数校验失败",
                new ErrorInfo("VALIDATION_ERROR", false, details));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}