package com.aijob.backend.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;


public class CommonResult<T> {

    private Integer code;
    private String msg;
    private T data;
    private String traceId;
    private ErrorBody error;

    public CommonResult() {
    }

    public CommonResult(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.traceId = TraceIdFilter.currentTraceId();
    }

    public static <T> CommonResult<T> success(T data) {
        return new CommonResult<>(200, "操作成功", data);
    }

    public static <T> CommonResult<T> accepted(T data) {
        return new CommonResult<>(202, "已受理", data);
    }

    public static <T> CommonResult<T> notFound(String msg) {
        return failure(ApiErrorCode.RESOURCE_NOT_FOUND, msg, null);
    }

    public static <T> CommonResult<T> failure(ApiErrorCode errorCode) {
        return failure(errorCode, errorCode.getDefaultMessage(), null);
    }

    public static <T> CommonResult<T> failure(ApiErrorCode errorCode, String msg, Map<String, Object> details) {
        CommonResult<T> result = new CommonResult<>();
        result.code = errorCode.getCode();
        result.msg = msg == null || msg.isBlank() ? errorCode.getDefaultMessage() : msg;
        result.traceId = TraceIdFilter.currentTraceId();
        result.error = new ErrorBody(errorCode.getKey(), errorCode.isRetryable(), details);
        return result;
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

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ErrorBody getError() {
        return error;
    }

    public void setError(ErrorBody error) {
        this.error = error;
    }
    public CommonResult<T> withTraceId(String traceId) {
        this.traceId = traceId != null ? traceId : java.util.UUID.randomUUID().toString();
        return this;
    }

}