package com.aijob.backend.common;

import java.util.Map;

/**
 * 统一错误返回体，对齐数据说明 §1 的错误结构：
 * {code, msg, data:null, traceId, error:{key, retryable, details}}。
 * HTTP 状态码保持真实，错误不伪装 200。
 */
public class CommonError {

    private Integer code;
    private String msg;
    private Object data;
    private String traceId;
    private ErrorBody error;

    public CommonError() {
    }

    public CommonError(Integer code, String msg, ErrorBody error) {
        this.code = code;
        this.msg = msg;
        this.data = null;
        this.error = error;
    }

    public static CommonError of(Integer code, String msg, String key, Boolean retryable, Map<String, Object> details) {
        ErrorBody body = new ErrorBody();
        body.setKey(key);
        body.setRetryable(retryable);
        body.setDetails(details);
        return new CommonError(code, msg, body);
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public ErrorBody getError() {
        return error;
    }

    public void setError(ErrorBody error) {
        this.error = error;
    }

    public static class ErrorBody {

        private String key;
        private Boolean retryable;
        private Map<String, Object> details;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public Boolean getRetryable() {
            return retryable;
        }

        public void setRetryable(Boolean retryable) {
            this.retryable = retryable;
        }

        public Map<String, Object> getDetails() {
            return details;
        }

        public void setDetails(Map<String, Object> details) {
            this.details = details;
        }
    }
}