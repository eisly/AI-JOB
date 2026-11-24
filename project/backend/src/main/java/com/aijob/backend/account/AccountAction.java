package com.aijob.backend.account;

/**
 * 账号管理审计动作，与 audit_log.action 取值一一对应。
 */
public enum AccountAction {

    NICKNAME_UPDATE,
    PASSWORD_CHANGE,
    PHONE_BIND,
    PHONE_REBIND,
    PHONE_UNBIND,
    AVATAR_UPDATE,
    AVATAR_RESET;

    public enum Result {
        SUCCESS,
        FAILED
    }
}