package com.aijob.backend.common;

public enum ApiErrorCode {

    VALIDATION_ERROR(400, 40001, "VALIDATION_ERROR", "请求参数不合法", false),
    WEAK_PASSWORD(400, 40002, "WEAK_PASSWORD", "密码长度需为 10-72 位", false),
    INVALID_VERIFICATION_CODE(400, 40003, "INVALID_VERIFICATION_CODE", "验证码错误或已过期", false),
    UNAUTHENTICATED(401, 40101, "UNAUTHENTICATED", "未登录或登录已过期", false),
    FORBIDDEN(403, 40301, "FORBIDDEN", "无权访问该资源", false),
    RESOURCE_NOT_FOUND(404, 40401, "RESOURCE_NOT_FOUND", "资源不存在", false),
    VERSION_CONFLICT(409, 40901, "VERSION_CONFLICT", "数据版本已变化，请刷新后重试", false),
    TASK_IN_PROGRESS(409, 40902, "TASK_IN_PROGRESS", "已有任务正在处理", false),
    IDEMPOTENCY_CONFLICT(409, 40903, "IDEMPOTENCY_CONFLICT", "幂等键冲突", false),
    INVALID_STATE(409, 40904, "INVALID_STATE", "当前状态不允许该操作", false),
    STALE_INPUT(409, 40905, "STALE_INPUT", "输入已变化，请刷新后重试", false),
    REPORT_NOT_READY(409, 40906, "REPORT_NOT_READY", "报告尚未生成", true),
    EMAIL_ALREADY_EXISTS(409, 40907, "EMAIL_ALREADY_EXISTS", "该邮箱已注册", false),
    EVENT_CURSOR_EXPIRED(410, 41001, "EVENT_CURSOR_EXPIRED", "事件游标已过期", false),
    RESULT_PURGED(410, 41002, "RESULT_PURGED", "结果已清理", false),
    PAYLOAD_TOO_LARGE(413, 41301, "PAYLOAD_TOO_LARGE", "提交内容超出大小限制", false),
    UNSUPPORTED_FILE_TYPE(415, 41501, "UNSUPPORTED_FILE_TYPE", "不支持的文件类型", false),
    AI_SCHEMA_INVALID(422, 42201, "AI_SCHEMA_INVALID", "AI 结果未通过校验", false),
    RATE_LIMITED(429, 42901, "RATE_LIMITED", "请求过于频繁，请稍后再试", true),
    CODE_SEND_TOO_FREQUENT(429, 42902, "CODE_SEND_TOO_FREQUENT", "验证码发送过于频繁，请稍后再试", true),
    INTERNAL_ERROR(500, 50001, "INTERNAL_ERROR", "服务器内部错误", false),
    DEPENDENCY_UNAVAILABLE(503, 50301, "DEPENDENCY_UNAVAILABLE", "依赖服务暂不可用，请稍后重试", true),

    CURRENT_PASSWORD_INCORRECT(400, 40004, "CURRENT_PASSWORD_INCORRECT", "当前密码不正确", false),
    NEW_PASSWORD_SAME(400, 40005, "NEW_PASSWORD_SAME", "新密码不能与当前密码相同", false),
    PASSWORD_CONFIRM_MISMATCH(400, 40006, "PASSWORD_CONFIRM_MISMATCH", "两次输入的密码不一致", false),
    INVALID_PHONE(400, 40007, "INVALID_PHONE", "请输入正确的 11 位手机号", false),
    VERIFICATION_CODE_EXPIRED(400, 40008, "VERIFICATION_CODE_EXPIRED", "验证码已过期，请重新获取", false),
    VERIFICATION_CODE_TOO_MANY(400, 40009, "VERIFICATION_CODE_TOO_MANY", "验证码错误次数过多，请重新获取", false),
    CSRF_INVALID(403, 40302, "CSRF_INVALID", "请求校验失败，请刷新页面后重试", false),
    PHONE_ALREADY_BOUND(409, 40908, "PHONE_ALREADY_BOUND", "该手机号已绑定其他账号", false),
    PHONE_CHANGED(409, 40909, "PHONE_CHANGED", "手机号已变更，请刷新后重试", false),
    AVATAR_TOO_LARGE(413, 41302, "AVATAR_TOO_LARGE", "图片大小不能超过 2 MB", false),
    UNSUPPORTED_AVATAR_TYPE(415, 41502, "UNSUPPORTED_AVATAR_TYPE", "仅支持 JPG、PNG、WebP 格式", false),
    IMAGE_UNREADABLE(415, 41503, "IMAGE_UNREADABLE", "图片无法读取，请更换图片后重试", false),
    PASSWORD_ATTEMPT_LIMIT(429, 42903, "PASSWORD_ATTEMPT_LIMIT", "尝试次数过多，请 15 分钟后再试", true),
    PHONE_BINDING_UNAVAILABLE(503, 50302, "PHONE_BINDING_UNAVAILABLE", "暂未开通手机号绑定", true);

    private final int httpStatus;
    private final int code;
    private final String key;
    private final String defaultMessage;
    private final boolean retryable;

    ApiErrorCode(int httpStatus, int code, String key, String defaultMessage, boolean retryable) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.key = key;
        this.defaultMessage = defaultMessage;
        this.retryable = retryable;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getKey() {
        return key;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public boolean isRetryable() {
        return retryable;
    }
}