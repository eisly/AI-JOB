package com.aijob.backend.auth.security;

import com.aijob.backend.config.AppProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final AppProperties properties;

    public JwtService(JwtEncoder jwtEncoder, AppProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public String issue(String userId, String role, int tokenVersion) {
        Instant now = Instant.now();
        long ttl = properties.jwt().accessTtlSeconds();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .audience(java.util.List.of(properties.jwt().audience()))
                .subject(userId)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(ttl))
                .id(UUID.randomUUID().toString())
                .claim("role", role)
                .claim("ver", tokenVersion)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long accessTtlSeconds() {
        return properties.jwt().accessTtlSeconds();
    }
}