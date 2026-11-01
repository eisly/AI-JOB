package com.aijob.backend.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorBody {

    private String key;
    private boolean retryable;
    private Map<String, Object> details;

    public ErrorBody() {
    }

    public ErrorBody(String key, boolean retryable, Map<String, Object> details) {
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