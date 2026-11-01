package com.aijob.backend.mail;

import com.aijob.backend.common.ApiErrorCode;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final AppProperties properties;

    public MailService(JavaMailSender mailSender, AppProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public void sendResetCode(String to, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mail().from());
        message.setTo(to);
        message.setSubject("AI-JOB 密码重置验证码");
        message.setText("你的密码重置验证码是 " + code + "，有效期 10 分钟。如非本人操作请忽略本邮件。");
        try {
            mailSender.send(message);
        } catch (MailException ex) {
            log.error("failed to send reset code mail", ex);
            throw new BusinessException(ApiErrorCode.DEPENDENCY_UNAVAILABLE, "邮件服务暂不可用");
        }
    }
}