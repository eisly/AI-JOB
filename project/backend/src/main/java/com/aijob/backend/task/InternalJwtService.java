package com.aijob.backend.task;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.aijob.backend.common.BusinessException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

@Service
public class InternalJwtService {

    private final String agentSecret;
    private final String callbackSecret;

    public InternalJwtService(@Value("${app.agent.jwt-secret}") String agentSecret,
                              @Value("${app.agent.callback-jwt-secret}") String callbackSecret) {
        this.agentSecret = agentSecret;
        this.callbackSecret = callbackSecret;
    }

    public String runToken(String taskId) {
        return token(agentSecret, "career-business", "career-ai-agent", "ai:run", taskId, 300);
    }

    /** 语音同步接口专用：scope=ai:speech，TTL 60 秒，只允许访问本次 requestId。 */
    public String speechToken(String requestId) {
        return token(agentSecret, "career-business", "career-ai-agent", "ai:speech", requestId, 60);
    }

    public void verifyCallback(String authorization, String taskId) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw BusinessException.unauthenticated();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(authorization.substring(7));
            if (!jwt.verify(new MACVerifier(callbackSecret.getBytes(StandardCharsets.UTF_8)))) {
                throw BusinessException.unauthenticated();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Instant now = Instant.now();
            if (!"career-ai-agent".equals(claims.getIssuer())
                    || !claims.getAudience().contains("career-business")
                    || claims.getExpirationTime() == null || claims.getExpirationTime().toInstant().isBefore(now)
                    || claims.getIssueTime() == null
                    || !scopes(claims.getStringClaim("scope")).contains("ai:callback")) {
                throw BusinessException.unauthenticated();
            }
            List<String> taskIds = claims.getStringListClaim("taskIds");
            String single = claims.getStringClaim("taskId");
            if (!taskId.equals(single) && (taskIds == null || !taskIds.contains(taskId))) {
                throw BusinessException.forbidden();
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BusinessException.unauthenticated();
        }
    }

    private String token(String secret, String issuer, String audience, String scope, String taskId, int ttl) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer).audience(audience).subject(issuer)
                    .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(ttl)))
                    .claim("scope", scope).claim("taskIds", List.of(taskId)).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Set<String> scopes(String scope) {
        return scope == null ? Set.of() : Set.of(scope.split(" "));
    }
}