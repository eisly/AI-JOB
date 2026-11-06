package com.aijob.backend.common;

import java.util.Arrays;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.aijob.backend.user.entity.AppUser;
import com.aijob.backend.user.mapper.AppUserMapper;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 当前用户解析：仅从 SecurityContext 读取已验证的认证身份（JWT subject 契约）。
 * 显式排除匿名身份（AnonymousAuthenticationToken 的 isAuthenticated 也为 true，不能只看该标志）；
 * 未认证、匿名或无法解析时抛 401 UNAUTHENTICATED。
 *
 * <p>本地联调降级方案（X-User-Id 请求头）必须同时满足两个条件才会启用：
 * ① 显式配置开关 app.auth.dev-user-header-enabled=true；② 激活 profile 包含 local。
 * 缺一不可——其他环境（默认/生产）即使误设开关为 true 也不会启用，任何请求头伪造均得到 401。
 * 开启时也仅接受 app_user 中 status=ACTIVE 的真实用户（demo 演示账号已 DISABLED，不可用）。
 * 认证模块落地后应移除该降级路径，对接方式以与认证模块负责人的对齐结论为准。
 */
@Component
public class CurrentUserResolver {

    public static final String DEV_USER_HEADER = "X-User-Id";
    public static final String DEV_PROFILE_NAME = "local";

    private static final Logger log = LoggerFactory.getLogger(CurrentUserResolver.class);

    private final boolean devUserHeaderEnabled;
    private final AppUserMapper appUserMapper;

    public CurrentUserResolver(@Value("${app.auth.dev-user-header-enabled:false}") boolean devUserHeaderEnabled,
                               Environment environment,
                               AppUserMapper appUserMapper) {
        boolean localProfileActive = Arrays.asList(environment.getActiveProfiles()).contains(DEV_PROFILE_NAME);
        // 双条件：显式开关 + local profile。缺任一条件即不启用（安全默认）。
        this.devUserHeaderEnabled = devUserHeaderEnabled && localProfileActive;
        this.appUserMapper = appUserMapper;
        if (devUserHeaderEnabled && !localProfileActive) {
            log.warn("app.auth.dev-user-header-enabled=true 但未激活 local profile，降级认证不生效（安全默认）");
        }
        if (this.devUserHeaderEnabled) {
            log.warn("!!! 本地开发降级认证已开启（local profile + 显式开关双条件满足）："
                    + "X-User-Id 请求头将被用于身份模拟，仅限本地联调，严禁在生产或共享环境开启 !!!");
        }
    }

    /**
     * 解析当前认证用户 ID；匿名、未认证或无法解析时抛 401。
     */
    public String resolveCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof com.aijob.backend.auth.security.UserPrincipal user && isUuid(user.getUserId())) {
                return user.getUserId();
            }
            if (principal instanceof Jwt jwt) {
                String subject = jwt.getSubject();
                if (isUuid(subject)) {
                    return subject;
                }
            }
        }
        if (devUserHeaderEnabled) {
            return resolveFromDevHeader();
        }
        throw BusinessException.unauthenticated();
    }

    private String resolveFromDevHeader() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String headerUserId = request.getHeader(DEV_USER_HEADER);
            if (isUuid(headerUserId)) {
                AppUser user = appUserMapper.selectById(headerUserId);
                if (user != null && "ACTIVE".equals(user.getStatus())) {
                    log.warn("DEV 请求头身份生效 userId={}（仅本地联调）", headerUserId);
                    return headerUserId;
                }
                log.warn("DEV 请求头用户不存在或非 ACTIVE，拒绝 userId={}", headerUserId);
            }
        }
        throw BusinessException.unauthenticated();
    }

    private boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}