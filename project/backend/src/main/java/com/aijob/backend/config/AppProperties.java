package com.aijob.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        Session session,
        Security security,
        Mail mail,
        ResetCode resetCode) {

    public record Jwt(String secret, String issuer, String audience, long accessTtlSeconds) {
    }

    public record Session(long refreshTtlSeconds, String cookieName, String cookiePath, boolean secure, String sameSite) {
    }

    public record Security(String frontendOrigin, int loginRateLimit, long loginRateWindowSeconds) {
    }

    public record Mail(String from, boolean exposeCodeInResponse) {
    }

    public record ResetCode(long ttlSeconds, int maxAttempts, long cooldownSeconds) {
    }
}