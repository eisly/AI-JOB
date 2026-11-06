package com.aijob.backend.common;

import com.aijob.backend.auth.security.UserPrincipal;
import com.aijob.backend.user.mapper.AppUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CurrentUserResolverTest {
    private final CurrentUserResolver resolver = new CurrentUserResolver(false, new MockEnvironment(), mock(AppUserMapper.class));

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void resolvesVerifiedLoginPrincipal() {
        String id = "10000000-0000-4000-8000-00000000000a";
        UserPrincipal user = new UserPrincipal(id, "a@example.test", "A", "USER", 0, "jti");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        assertEquals(id, resolver.resolveCurrentUserId());
    }

    @Test void rejectsMissingAuthentication() {
        assertThrows(BusinessException.class, resolver::resolveCurrentUserId);
    }
}
