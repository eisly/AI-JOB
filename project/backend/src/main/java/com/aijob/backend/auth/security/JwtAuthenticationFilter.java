package com.aijob.backend.auth.security;

import com.aijob.backend.auth.service.TokenRevocationService;
import com.aijob.backend.user.entity.AppUserAuth;
import com.aijob.backend.user.mapper.AppUserMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final AppUserMapper appUserMapper;
    private final TokenRevocationService tokenRevocationService;

    public JwtAuthenticationFilter(JwtDecoder jwtDecoder,
                                   AppUserMapper appUserMapper,
                                   TokenRevocationService tokenRevocationService) {
        this.jwtDecoder = jwtDecoder;
        this.appUserMapper = appUserMapper;
        this.tokenRevocationService = tokenRevocationService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(header.substring(BEARER_PREFIX.length()).trim(), request);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            Jwt jwt = jwtDecoder.decode(token);
            String userId = jwt.getSubject();
            if (userId == null || userId.isBlank()) {
                return;
            }
            AppUserAuth user = appUserMapper.selectAuthById(userId);
            if (user == null || !"ACTIVE".equals(user.getStatus())) {
                return;
            }
            Object versionClaim = jwt.getClaim("ver");
            if (!(versionClaim instanceof Number version) || version.intValue() != user.getTokenVersion()) {
                return;
            }
            if (tokenRevocationService.isRevoked(jwt.getId())) {
                return;
            }
            UserPrincipal principal = new UserPrincipal(user.getId(), user.getEmail(), user.getNickname(),
                    user.getRole(), user.getTokenVersion(), jwt.getId());
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException ex) {
            SecurityContextHolder.clearContext();
        }
    }
}