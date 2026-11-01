package com.aijob.backend.common;

public class BusinessException extends RuntimeException {

    private final ApiErrorCode errorCode;
    private final Long retryAfterSeconds;

    public BusinessException(ApiErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), null);
    }

    public BusinessException(ApiErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ApiErrorCode errorCode, String message, Long retryAfterSeconds) {
        super(message);
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ApiErrorCode getErrorCode() {
        return errorCode;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}