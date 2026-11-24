package com.aijob.backend.account;

import com.aijob.backend.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhoneVerificationCodeServiceTest {

    private static final String PHONE = "13812345678";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SmsSender smsSender;

    private PhoneVerificationCodeService service;

    @BeforeEach
    void setUp() {
        service = new PhoneVerificationCodeService(redisTemplate, smsSender, 600, 5, 60);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void issuesSixDigitCodeWithTenMinuteTtl() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(60)))).thenReturn(true);

        String code = service.issue(PHONE, PhonePurpose.BIND);

        assertThat(code).matches("\\d{6}");
        verify(valueOperations).set(startsWith("career:account:sms:code:BIND:"), eq(code), eq(Duration.ofSeconds(600)));
        verify(smsSender).send(PHONE, code);
    }

    @Test
    void rejectsSecondSendWithinCooldown() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(60)))).thenReturn(false);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(30L);

        assertThatThrownBy(() -> service.issue(PHONE, PhonePurpose.BIND))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getRetryAfterSeconds()).isEqualTo(30L))
                .extracting("errorKey").isEqualTo("CODE_SEND_TOO_FREQUENT");
    }

    @Test
    void tooManyAttemptsInvalidatesCode() {
        when(valueOperations.increment(anyString())).thenReturn(6L);

        assertThat(service.verifyAndConsume(PHONE, PhonePurpose.BIND, "123456"))
                .isEqualTo(PhoneVerificationCodeService.VerifyResult.TOO_MANY_ATTEMPTS);
        verify(redisTemplate).delete(startsWith("career:account:sms:code:"));
    }

    @Test
    void expiredWhenCodeAbsent() {
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);
        when(valueOperations.get(anyString())).thenReturn(null);

        assertThat(service.verifyAndConsume(PHONE, PhonePurpose.BIND, "123456"))
                .isEqualTo(PhoneVerificationCodeService.VerifyResult.EXPIRED);
    }

    @Test
    void invalidWhenCodeMismatch() {
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);
        when(valueOperations.get(anyString())).thenReturn("654321");

        assertThat(service.verifyAndConsume(PHONE, PhonePurpose.BIND, "123456"))
                .isEqualTo(PhoneVerificationCodeService.VerifyResult.INVALID);
    }

    @Test
    void purposesAreIsolated() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(60)))).thenReturn(true);

        service.issue(PHONE, PhonePurpose.REBIND);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), anyString(), eq(Duration.ofSeconds(600)));
        assertThat(keyCaptor.getValue()).contains(":REBIND:").contains(PHONE);
    }

    @Test
    void channelFailureClearsIssuedState() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(60)))).thenReturn(true);
        doThrow(AccountExceptionFactory.phoneBindingUnavailable()).when(smsSender).send(anyString(), anyString());

        assertThatThrownBy(() -> service.issue(PHONE, PhonePurpose.BIND))
                .isInstanceOf(BusinessException.class)
                .extracting("errorKey").isEqualTo("PHONE_BINDING_UNAVAILABLE");
        verify(redisTemplate).delete(startsWith("career:account:sms:code:"));
        verify(redisTemplate).delete(startsWith("career:account:sms:cooldown:"));
    }
}