package com.aijob.backend.common;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务异常：携带 HTTP 状态、业务码与 error.key，由 GlobalExceptionHandler 统一转换为 CommonError。
 * 错误码对齐数据说明 §1。
 */
public class BusinessException extends RuntimeException {

    private final int httpStatus;
    private final int code;
    private final String errorKey;
    private final Boolean retryable;
    private final Map<String, Object> details;

    private BusinessException(int httpStatus, int code, String msg, String errorKey, Boolean retryable,
                             Map<String, Object> details) {
        super(msg);
        this.httpStatus = httpStatus;
        this.code = code;
        this.errorKey = errorKey;
        this.retryable = retryable;
        this.details = details;
    }

    /** 400 VALIDATION_ERROR，details.fields=[{field,message}] */
    public static BusinessException validation(String field, String message) {
        Map<String, Object> details = new HashMap<>();
        details.put("fields", List.of(Map.of("field", field, "message", message)));
        return new BusinessException(400, 40001, "参数校验失败", "VALIDATION_ERROR", false, details);
    }

    /** 400 VALIDATION_ERROR（多字段） */
    public static BusinessException validation(List<Map<String, String>> fields) {
        Map<String, Object> details = new HashMap<>();
        details.put("fields", fields);
        return new BusinessException(400, 40001, "参数校验失败", "VALIDATION_ERROR", false, details);
    }

    /** 401 UNAUTHENTICATED */
    public static BusinessException unauthenticated() {
        return new BusinessException(401, 40101, "未认证或认证已失效", "UNAUTHENTICATED", null, null);
    }

    /** 404 RESOURCE_NOT_FOUND：不存在或不属于当前用户，不区分两种情况 */
    public static BusinessException notFound() {
        return new BusinessException(404, 40401, "资源不存在", "RESOURCE_NOT_FOUND", false, null);
    }

    /** 409 VERSION_CONFLICT，details.currentVersion=数据库当前版本 */
    public static BusinessException versionConflict(long currentVersion) {
        Map<String, Object> details = new HashMap<>();
        details.put("currentVersion", currentVersion);
        return new BusinessException(409, 40901, "数据版本已变化，请刷新后重试", "VERSION_CONFLICT", false, details);
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getErrorKey() {
        return errorKey;
    }

    public Boolean getRetryable() {
        return retryable;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}