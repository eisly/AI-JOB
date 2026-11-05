package com.aijob.backend.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * 业务异常，携带真实 HTTP 状态、业务码与 error 结构。
 */
public class BizException extends RuntimeException {

    private final HttpStatus status;
    private final Integer code;
    private final String key;
    private final boolean retryable;
    private final Map<String, Object> details;

    public BizException(HttpStatus status, Integer code, String key, String message) {
        this(status, code, key, message, false, null);
    }

    public BizException(HttpStatus status, Integer code, String key, String message,
                        boolean retryable, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.key = key;
        this.retryable = retryable;
        this.details = details;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Integer getCode() {
        return code;
    }

    public String getKey() {
        return key;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}