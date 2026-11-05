package com.aijob.backend.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * 统一返回体，对齐接口文档中的 CommonResult 约定（code/msg/data/traceId/error）。
 */
public class CommonResult<T> {

    private Integer code;
    private String msg;
    private T data;
    private String traceId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ErrorInfo error;

    public CommonResult() {
        this.traceId = UUID.randomUUID().toString();
    }

    public CommonResult(Integer code, String msg, T data) {
        this();
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public CommonResult(Integer code, String msg, T data, ErrorInfo error) {
        this(code, msg, data);
        this.error = error;
    }

    public static <T> CommonResult<T> success(T data) {
        return new CommonResult<>(200, "操作成功", data);
    }

    public static <T> CommonResult<T> failure(Integer code, String msg, ErrorInfo error) {
        return new CommonResult<>(code, msg, null, error);
    }

    public static <T> CommonResult<T> notFound(String msg) {
        return new CommonResult<>(40401, msg, null,
                new ErrorInfo("RESOURCE_NOT_FOUND", false, null));
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

    public ErrorInfo getError() {
        return error;
    }

    public void setError(ErrorInfo error) {
        this.error = error;
    }
}