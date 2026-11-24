package com.aijob.backend.account;

import com.aijob.backend.user.entity.AppUser;
import com.aijob.backend.user.mapper.AppUserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 头像上传、恢复默认与受控读取：数据库切换是唯一生效点，文件先落盘后切换。
 */
@Service
public class AvatarService {

    private final AppUserMapper appUserMapper;
    private final AvatarStorageService avatarStorageService;
    private final AccountProfileService accountProfileService;
    private final AccountAuditService accountAuditService;
    private final long maxBytes;

    public AvatarService(AppUserMapper appUserMapper,
                         AvatarStorageService avatarStorageService,
                         AccountProfileService accountProfileService,
                         AccountAuditService accountAuditService,
                         @Value("${app.account.avatar.max-bytes:2097152}") long maxBytes) {
        this.appUserMapper = appUserMapper;
        this.avatarStorageService = avatarStorageService;
        this.accountProfileService = accountProfileService;
        this.accountAuditService = accountAuditService;
        this.maxBytes = maxBytes;
    }

    public AccountProfileVO upload(String userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw com.aijob.backend.common.BusinessException.validation("file", "请选择要上传的图片");
        }
        if (file.getSize() > maxBytes) {
            accountAuditService.record(userId, AccountAction.AVATAR_UPDATE,
                    AccountAction.Result.FAILED, "AVATAR_TOO_LARGE", null);
            throw AccountExceptionFactory.avatarTooLarge();
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw AccountExceptionFactory.imageUnreadable();
        }
        String mimeType = AvatarTypeDetector.detect(bytes);
        if (mimeType == null) {
            accountAuditService.record(userId, AccountAction.AVATAR_UPDATE,
                    AccountAction.Result.FAILED, "UNSUPPORTED_AVATAR_TYPE", null);
            throw AccountExceptionFactory.unsupportedAvatarType();
        }
        if (!AvatarTypeDetector.decodable(bytes, mimeType)) {
            accountAuditService.record(userId, AccountAction.AVATAR_UPDATE,
                    AccountAction.Result.FAILED, "IMAGE_UNREADABLE", null);
            throw AccountExceptionFactory.imageUnreadable();
        }

        AppUser user = appUserMapper.selectById(userId);
        if (user == null) {
            throw AccountExceptionFactory.userNotFound();
        }
        String previousKey = user.getAvatarStorageKey();
        int newVersion = (user.getAvatarVersion() == null ? 0 : user.getAvatarVersion()) + 1;
        String newKey = avatarStorageService.store(userId, newVersion, bytes);
        int updated;
        try {
            updated = appUserMapper.updateAvatar(userId, newKey, mimeType, bytes.length);
        } catch (RuntimeException ex) {
            avatarStorageService.delete(userId, newKey);
            throw ex;
        }
        if (updated == 0) {
            avatarStorageService.delete(userId, newKey);
            throw AccountExceptionFactory.userNotFound();
        }
        avatarStorageService.delete(userId, previousKey);
        accountAuditService.record(userId, AccountAction.AVATAR_UPDATE, AccountAction.Result.SUCCESS, null, null);
        return accountProfileService.getProfile(userId);
    }

    public AccountProfileVO reset(String userId) {
        AppUser user = appUserMapper.selectById(userId);
        if (user == null) {
            throw AccountExceptionFactory.userNotFound();
        }
        if (user.getAvatarStorageKey() == null) {
            return accountProfileService.getProfile(userId);
        }
        String previousKey = user.getAvatarStorageKey();
        int updated = appUserMapper.clearAvatar(userId);
        if (updated == 0) {
            throw AccountExceptionFactory.userNotFound();
        }
        avatarStorageService.delete(userId, previousKey);
        accountAuditService.record(userId, AccountAction.AVATAR_RESET, AccountAction.Result.SUCCESS, null, null);
        return accountProfileService.getProfile(userId);
    }

    public AvatarContent read(String userId) {
        AppUser user = appUserMapper.selectById(userId);
        if (user == null) {
            throw AccountExceptionFactory.userNotFound();
        }
        if (user.getAvatarStorageKey() == null) {
            throw AccountExceptionFactory.avatarNotFound();
        }
        byte[] bytes = avatarStorageService.read(userId, user.getAvatarStorageKey());
        return new AvatarContent(bytes, user.getAvatarMimeType(), user.getAvatarVersion() == null ? 0 : user.getAvatarVersion());
    }

    public record AvatarContent(byte[] bytes, String mimeType, int version) {
    }
}