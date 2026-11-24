package com.aijob.backend.account;

/**
 * 短信通道抽象：后续接入真实供应商时新增实现即可，调用方无需改动。
 */
public interface SmsSender {

    boolean available();

    void send(String phone, String code);
}