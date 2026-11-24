package com.aijob.backend.account;

import com.aijob.backend.user.entity.AppUser;
import com.aijob.backend.user.mapper.AppUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountProfileServiceTest {

    @Mock
    private AppUserMapper appUserMapper;

    @Mock
    private SmsSender smsSender;

    @Mock
    private AvatarStorageService avatarStorageService;

    @Mock
    private AccountAuditService accountAuditService;

    private AccountProfileService service;

    @BeforeEach
    void setUp() {
        service = new AccountProfileService(appUserMapper, smsSender, avatarStorageService,
                accountAuditService, 2097152);
    }

    @Test
    void assemblesUnboundAccountWithoutAvatar() {
        when(appUserMapper.selectById("u1")).thenReturn(user("u1", "旧昵称", null, null, 0));

        AccountProfileVO profile = service.getProfile("u1");

        assertThat(profile.phoneMasked()).isNull();
        assertThat(profile.phoneBound()).isFalse();
        assertThat(profile.avatarUrl()).isNull();
        assertThat(profile.avatarVersion()).isZero();
        assertThat(profile.profileUpdatedAt()).isNull();
    }

    @Test
    void masksPhoneAndBuildsVersionedAvatarUrl() {
        when(appUserMapper.selectById("u1"))
                .thenReturn(user("u1", "昵称", "13812345678", "u1/avatar/3", 3));

        AccountProfileVO profile = service.getProfile("u1");

        assertThat(profile.phoneMasked()).isEqualTo("138****5678");
        assertThat(profile.phoneBound()).isTrue();
        assertThat(profile.avatarUrl()).isEqualTo("/api/v1/account/avatars/me?v=3");
    }

    @Test
    void unchangedNicknameDoesNotWrite() {
        AppUser current = user("u1", "张三", null, null, 0);
        when(appUserMapper.selectById("u1")).thenReturn(current);

        AccountProfileVO profile = service.updateNickname("u1", "  张三  ");

        assertThat(profile.nickname()).isEqualTo("张三");
        verify(appUserMapper, never()).updateNicknameAndTouch(any(), any());
    }

    @Test
    void changedNicknameUpdatesAndAudits() {
        AppUser before = user("u1", "旧昵称", null, null, 0);
        AppUser after = user("u1", "新昵称", null, null, 0);
        when(appUserMapper.selectById("u1")).thenReturn(before, after);
        when(appUserMapper.updateNicknameAndTouch("u1", "新昵称")).thenReturn(1);

        AccountProfileVO profile = service.updateNickname("u1", "新昵称");

        assertThat(profile.nickname()).isEqualTo("新昵称");
        verify(appUserMapper).updateNicknameAndTouch("u1", "新昵称");
        verify(accountAuditService).record(eq("u1"), eq(AccountAction.NICKNAME_UPDATE),
                eq(AccountAction.Result.SUCCESS), eq(null), eq(null));
    }

    @Test
    void capabilitiesReflectChannelAndStorage() {
        when(smsSender.available()).thenReturn(false);
        when(avatarStorageService.isWritable()).thenReturn(true);

        AccountCapabilitiesVO capabilities = service.getCapabilities();

        assertThat(capabilities.smsBindingAvailable()).isFalse();
        assertThat(capabilities.avatarUploadAvailable()).isTrue();
        assertThat(capabilities.avatarMaxBytes()).isEqualTo(2097152);
        assertThat(capabilities.avatarAcceptTypes()).containsExactly("image/jpeg", "image/png", "image/webp");
    }

    private static AppUser user(String id, String nickname, String phone, String avatarKey, int avatarVersion) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setEmail(id + "@example.invalid");
        user.setNickname(nickname);
        user.setPhone(phone);
        user.setAvatarStorageKey(avatarKey);
        user.setAvatarVersion(avatarVersion);
        user.setRole("USER");
        user.setStatus("ACTIVE");
        user.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return user;
    }
}