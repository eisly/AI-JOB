package com.aijob.backend.account;

import com.aijob.backend.user.entity.AppUser;
import com.aijob.backend.user.mapper.AppUserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 账号资料读取与昵称修改。
 */
@Service
public class AccountProfileService {

    private static final List<String> AVATAR_ACCEPT_TYPES =
            List.of("image/jpeg", "image/png", "image/webp");

    private final AppUserMapper appUserMapper;
    private final SmsSender smsSender;
    private final AvatarStorageService avatarStorageService;
    private final AccountAuditService accountAuditService;
    private final long avatarMaxBytes;

    public AccountProfileService(AppUserMapper appUserMapper,
                                 SmsSender smsSender,
                                 AvatarStorageService avatarStorageService,
                                 AccountAuditService accountAuditService,
                                 @Value("${app.account.avatar.max-bytes:2097152}") long avatarMaxBytes) {
        this.appUserMapper = appUserMapper;
        this.smsSender = smsSender;
        this.avatarStorageService = avatarStorageService;
        this.accountAuditService = accountAuditService;
        this.avatarMaxBytes = avatarMaxBytes;
    }

    public AccountProfileVO getProfile(String userId) {
        AppUser user = appUserMapper.selectById(userId);
        if (user == null) {
            throw AccountExceptionFactory.userNotFound();
        }
        return toProfileVO(user);
    }

    public AccountCapabilitiesVO getCapabilities() {
        return new AccountCapabilitiesVO(
                smsSender.available(),
                avatarStorageService.isWritable(),
                avatarMaxBytes,
                AVATAR_ACCEPT_TYPES);
    }

    @Transactional
    public AccountProfileVO updateNickname(String userId, String rawNickname) {
        String nickname = NicknameRules.normalize(rawNickname);
        AppUser current = appUserMapper.selectById(userId);
        if (current == null) {
            throw AccountExceptionFactory.userNotFound();
        }
        if (nickname.equals(current.getNickname())) {
            return toProfileVO(current);
        }
        int updated = appUserMapper.updateNicknameAndTouch(userId, nickname);
        if (updated == 0) {
            throw AccountExceptionFactory.userNotFound();
        }
        accountAuditService.record(userId, AccountAction.NICKNAME_UPDATE,
                AccountAction.Result.SUCCESS, null, null);
        return getProfile(userId);
    }

    private static AccountProfileVO toProfileVO(AppUser user) {
        String masked = PhoneMask.mask(user.getPhone());
        boolean bound = user.getPhone() != null && !user.getPhone().isBlank();
        int avatarVersion = user.getAvatarVersion() == null ? 0 : user.getAvatarVersion();
        String avatarUrl = user.getAvatarStorageKey() == null
                ? null
                : "/api/v1/account/avatars/me?v=" + avatarVersion;
        return new AccountProfileVO(user.getId(), user.getEmail(), user.getNickname(),
                masked, bound, avatarUrl, avatarVersion, user.getCreatedAt(), user.getProfileUpdatedAt());
    }
}