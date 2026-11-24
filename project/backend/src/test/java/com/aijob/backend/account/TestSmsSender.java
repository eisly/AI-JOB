package com.aijob.backend.account;

import com.aijob.backend.common.BusinessException;

/**
 * 集成测试用短信通道：不访问外部网络，记录最近一次验证码。
 */
public class TestSmsSender implements SmsSender {

    private String lastPhone;
    private String lastCode;

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public void send(String phone, String code) {
        if (code == null || code.length() != 6) {
            throw new BusinessException(com.aijob.backend.common.ApiErrorCode.PHONE_BINDING_UNAVAILABLE);
        }
        this.lastPhone = phone;
        this.lastCode = code;
    }

    public String lastPhone() {
        return lastPhone;
    }

    public String lastCode() {
        return lastCode;
    }

    public void clear() {
        lastPhone = null;
        lastCode = null;
    }
}