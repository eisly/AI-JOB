package com.aijob.backend.account;

import com.aijob.backend.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountCsrfServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AccountCsrfService service;

    @BeforeEach
    void setUp() {
        service = new AccountCsrfService(redisTemplate, 900);
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void issuesTokenWithConfiguredTtl() {
        String token = service.issue("u1", "jti-1");

        assertThat(token).isNotBlank();
        verify(valueOperations).set(startsWith("career:account:csrf:u1:"), eq(token), eq(Duration.ofSeconds(900)));
    }

    @Test
    void rejectsMissingToken() {
        assertThatThrownBy(() -> service.require("u1", "jti-1", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorKey").isEqualTo("CSRF_INVALID");
    }

    @Test
    void rejectsMismatchedToken() {
        when(valueOperations.get(anyString())).thenReturn("expected-token");

        assertThatThrownBy(() -> service.require("u1", "jti-1", "other-token"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorKey").isEqualTo("CSRF_INVALID");
    }

    @Test
    void acceptsMatchingToken() {
        when(valueOperations.get(anyString())).thenReturn("same-token");

        service.require("u1", "jti-1", "same-token");
    }

    @Test
    void redisFailureMapsToDependencyUnavailable() {
        when(valueOperations.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> service.require("u1", "jti-1", "token"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorKey").isEqualTo("DEPENDENCY_UNAVAILABLE");
    }
}