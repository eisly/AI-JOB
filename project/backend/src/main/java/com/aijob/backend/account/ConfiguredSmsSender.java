package com.aijob.backend.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 配置驱动的短信通道：当前项目未接入真实短信供应商。
 * app.sms.enabled=false 时能力探测为不可用、发送返回 503，绝不误报成功。
 */
@Component
public class ConfiguredSmsSender implements SmsSender {

    private final boolean enabled;

    public ConfiguredSmsSender(@Value("${app.sms.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean available() {
        return enabled;
    }

    @Override
    public void send(String phone, String code) {
        // 当前无真实供应商实现：即使开关被误开也不假装发送成功。
        throw AccountExceptionFactory.phoneBindingUnavailable();
    }
}