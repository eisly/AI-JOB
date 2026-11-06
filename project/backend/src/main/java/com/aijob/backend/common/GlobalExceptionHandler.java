package com.aijob.backend.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

/**
 * 全局异常处理器：业务异常与参数类框架异常统一映射为 CommonError，
 * 保证非法 JSON、错误日期、缺参数等返回 400 而非兜底 500。
 * 客户端类框架错误保留真实 HTTP 状态（405/406/415/404），不得归入 500。
 * 未知异常仅返回 traceId，不回传堆栈（数据说明 §1：500 INTERNAL_ERROR）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<CommonError> handleBusiness(BusinessException ex) {
        CommonError body = CommonError.of(ex.getCode(), ex.getMessage(), ex.getErrorKey(), ex.getRetryable(),
                ex.getDetails());
        body.setTraceId(traceId());
        return ResponseEntity.status(ex.getHttpStatus()).body(body);
    }

    /** @Valid 请求体校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CommonError> handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> fields = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            Map<String, String> item = new HashMap<>();
            item.put("field", error.getField());
            item.put("message", error.getDefaultMessage());
            fields.add(item);
        }
        CommonError body = validationError(fields);
        return ResponseEntity.badRequest().body(body);
    }

    /** 路径/query 参数类型不匹配（如非整数） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<CommonError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String field = ex.getName();
        CommonError body = validationError(List.of(Map.of(
                "field", field,
                "message", "参数类型不匹配: " + field)));
        return ResponseEntity.badRequest().body(body);
    }

    /** 缺少必填 query 参数（如 DELETE 缺 expectedVersion） */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<CommonError> handleMissingParam(MissingServletRequestParameterException ex) {
        CommonError body = validationError(List.of(Map.of(
                "field", ex.getParameterName(),
                "message", "缺少必填参数: " + ex.getParameterName())));
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<CommonError> handleMissingHeader(MissingRequestHeaderException ex) {
        CommonError body = validationError(List.of(Map.of(
                "field", ex.getHeaderName(),
                "message", "缺少必填请求头: " + ex.getHeaderName())));
        return ResponseEntity.badRequest().body(body);
    }

    /** 非法 JSON 请求体、错误日期格式、非法枚举值等反序列化失败（Jackson 3，tools.jackson） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonError> handleNotReadable(HttpMessageNotReadableException ex) {
        String field = "body";
        String message = "请求体格式错误或字段值非法";
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof tools.jackson.databind.exc.InvalidFormatException ife) {
                field = ife.getPath().isEmpty() ? field : ife.getPath().get(ife.getPath().size() - 1).getPropertyName();
                message = "字段值或格式非法: " + field;
                break;
            }
            cause = cause.getCause();
        }
        CommonError body = validationError(List.of(Map.of("field", field, "message", message)));
        return ResponseEntity.badRequest().body(body);
    }

    /** 方法参数级约束（@Validated）失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<CommonError> handleConstraintViolation(ConstraintViolationException ex) {
        List<Map<String, String>> fields = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            Map<String, String> item = new HashMap<>();
            item.put("field", violation.getPropertyPath().toString());
            item.put("message", violation.getMessage());
            fields.add(item);
        }
        CommonError body = validationError(fields);
        return ResponseEntity.badRequest().body(body);
    }

    /** 405：请求方法不支持（如对仅 GET/PATCH/DELETE 的路径发 POST）——保留真实状态而非 500 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<CommonError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        CommonError body = CommonError.of(ex.getStatusCode().value(), "请求方法不支持: " + ex.getMethod(),
                "METHOD_NOT_ALLOWED", null, null);
        body.setTraceId(traceId());
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    /** 415：Content-Type 不支持（如 XML 请求体）——保留真实状态而非 500 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<CommonError> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        CommonError body = CommonError.of(ex.getStatusCode().value(), "Content-Type 不支持",
                "UNSUPPORTED_MEDIA_TYPE", null, null);
        body.setTraceId(traceId());
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    /** 406：Accept 无法满足——保留真实状态；body 协商受限，仅返回状态与错误结构 */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<CommonError> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        CommonError body = CommonError.of(406, "Accept 不支持", "NOT_ACCEPTABLE", null, null);
        body.setTraceId(traceId());
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body(body);
    }

    /** 404：未映射路径（静态资源 fallback）——按资源不存在处理，不得兜底成 500 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<CommonError> handleNoResourceFound(NoResourceFoundException ex) {
        CommonError body = CommonError.of(40401, "资源不存在", "RESOURCE_NOT_FOUND", false, null);
        body.setTraceId(traceId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /** 兜底：仅提供 traceId，不回传堆栈 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonError> handleUnknown(Exception ex) {
        String traceId = traceId();
        log.error("INTERNAL_ERROR traceId={} msg={}", traceId, ex.getMessage(), ex);
        CommonError body = CommonError.of(50001, "服务器内部错误", "INTERNAL_ERROR", null, null);
        body.setTraceId(traceId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private CommonError validationError(List<Map<String, String>> fields) {
        Map<String, Object> details = new HashMap<>();
        details.put("fields", fields);
        CommonError body = CommonError.of(40001, "参数校验失败", "VALIDATION_ERROR", false, details);
        body.setTraceId(traceId());
        return body;
    }

    private String traceId() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_KEY);
        return traceId != null ? traceId : UUID.randomUUID().toString();
    }
}