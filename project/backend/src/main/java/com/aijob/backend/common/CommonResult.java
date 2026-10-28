package com.aijob.backend.common;

/**
 * 统一返回体，对齐接口文档中的 CommonResult 约定（code/msg/data/traceId）。
 * 这是一个最小 demo 实现，仅用于跑通分层链路。
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
}
