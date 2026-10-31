package com.aijob.backend.common;

import java.util.UUID;

/**
 * 统一返回体，对齐接口文档中的 CommonResult 约定（code/msg/data/traceId）。 traceId 取自
 * TraceIdFilter 写入的 MDC；无请求上下文时兜底生成。
 */
public class CommonResult<T> {

    private Integer code;
    private String msg;
    private T data;
    private String traceId;

    public CommonResult() {
    }

    public CommonResult(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> CommonResult<T> success(T data) {
        return new CommonResult<>(200, "操作成功", data);
    }

    public static <T> CommonResult<T> notFound(String msg) {
        return new CommonResult<>(404, msg, null);
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

    public CommonResult<T> withTraceId(String traceId) {
        this.traceId = traceId != null ? traceId : UUID.randomUUID().toString();
        return this;
    }
}
