package com.aijob.backend.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 错误附加信息，对齐公共联调约定中的 error 结构。
 */
public class ErrorInfo {

    private String key;
    private boolean retryable;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> details;

    public ErrorInfo() {
    }

    public ErrorInfo(String key, boolean retryable, Map<String, Object> details) {
        this.key = key;
        this.retryable = retryable;
        this.details = details;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public void setRetryable(boolean retryable) {
        this.retryable = retryable;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }
}