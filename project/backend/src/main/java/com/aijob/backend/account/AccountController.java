package com.aijob.backend.account;

import com.aijob.backend.account.dto.BindPhoneRequest;
import com.aijob.backend.account.dto.ChangePasswordRequest;
import com.aijob.backend.account.dto.ChangePasswordResponse;
import com.aijob.backend.account.dto.CsrfVO;
import com.aijob.backend.account.dto.RebindPhoneRequest;
import com.aijob.backend.account.dto.SendPhoneCodeRequest;
import com.aijob.backend.account.dto.SendPhoneCodeResponse;
import com.aijob.backend.account.dto.UnbindPhoneRequest;
import com.aijob.backend.auth.security.UserPrincipal;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.CommonResult;
import com.aijob.backend.user.dto.UpdateNicknameRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.TimeUnit;

/**
 * 账号管理接口：全部身份取自 JWT 主体，不接收任何用户标识入参；写操作必须携带账号 CSRF 令牌。
 */
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private static final String CSRF_HEADER = "X-CSRF-Token";

    private final AccountProfileService accountProfileService;
    private final AccountCredentialService accountCredentialService;
    private final PhoneBindingService phoneBindingService;
    private final AvatarService avatarService;
    private final AccountCsrfService accountCsrfService;

    public AccountController(AccountProfileService accountProfileService,
                             AccountCredentialService accountCredentialService,
                             PhoneBindingService phoneBindingService,
                             AvatarService avatarService,
                             AccountCsrfService accountCsrfService) {
        this.accountProfileService = accountProfileService;
        this.accountCredentialService = accountCredentialService;
        this.phoneBindingService = phoneBindingService;
        this.avatarService = avatarService;
        this.accountCsrfService = accountCsrfService;
    }

    @GetMapping("/profile")
    public CommonResult<AccountProfileVO> profile(@AuthenticationPrincipal UserPrincipal principal) {
        return CommonResult.success(accountProfileService.getProfile(userId(principal)));
    }

    @GetMapping("/capabilities")
    public CommonResult<AccountCapabilitiesVO> capabilities() {
        return CommonResult.success(accountProfileService.getCapabilities());
    }

    @GetMapping("/csrf")
    public ResponseEntity<CommonResult<CsrfVO>> csrf(@AuthenticationPrincipal UserPrincipal principal) {
        UserPrincipal current = requirePrincipal(principal);
        String token = accountCsrfService.issue(current.getUserId(), current.getJti());
        return ResponseEntity.ok()
                .header(CSRF_HEADER, token)
                .body(CommonResult.success(new CsrfVO(token)));
    }

    @PatchMapping("/nickname")
    public CommonResult<AccountProfileVO> updateNickname(@AuthenticationPrincipal UserPrincipal principal,
                                                         @RequestHeader(value = CSRF_HEADER, required = false) String csrfToken,
                                                         @Valid @RequestBody UpdateNicknameRequest request) {
        UserPrincipal current = requirePrincipal(principal);
        accountCsrfService.require(current.getUserId(), current.getJti(), csrfToken);
        return CommonResult.success(accountProfileService.updateNickname(current.getUserId(), request.nickname()));
    }

    @PostMapping("/password/change")
    public CommonResult<ChangePasswordResponse> changePassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestHeader(value = CSRF_HEADER, required = false) String csrfToken,
            @Valid @RequestBody ChangePasswordRequest request) {
        UserPrincipal current = requirePrincipal(principal);
        accountCsrfService.require(current.getUserId(), current.getJti(), csrfToken);
        return CommonResult.success(accountCredentialService.changePassword(current.getUserId(),
                request.currentPassword(), request.newPassword(), request.confirmPassword()));
    }

    @PostMapping("/phone/code")
    public CommonResult<SendPhoneCodeResponse> sendPhoneCode(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestHeader(value = CSRF_HEADER, required = false) String csrfToken,
            @Valid @RequestBody SendPhoneCodeRequest request) {
        UserPrincipal current = requirePrincipal(principal);
        accountCsrfService.require(current.getUserId(), current.getJti(), csrfToken);
        return CommonResult.success(phoneBindingService.sendCode(current.getUserId(),
                request.phone(), request.purpose()));
    }

    @PostMapping("/phone/bind")
    public CommonResult<AccountProfileVO> bindPhone(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestHeader(value = CSRF_HEADER, required = false) String csrfToken,
            @Valid @RequestBody BindPhoneRequest request) {
        UserPrincipal current = requirePrincipal(principal);
        accountCsrfService.require(current.getUserId(), current.getJti(), csrfToken);
        return CommonResult.success(phoneBindingService.bind(current.getUserId(),
                request.phone(), request.code()));
    }

    @PostMapping("/phone/rebind")
    public CommonResult<AccountProfileVO> rebindPhone(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestHeader(value = CSRF_HEADER, required = false) String csrfToken,
            @Valid @RequestBody RebindPhoneRequest request) {
        UserPrincipal current = requirePrincipal(principal);
        accountCsrfService.require(current.getUserId(), current.getJti(), csrfToken);
        return CommonResult.success(phoneBindingService.rebind(current.getUserId(),
                request.currentPassword(), request.phone(), request.code()));
    }

    @PostMapping("/phone/unbind")
    public CommonResult<AccountProfileVO> unbindPhone(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestHeader(value = CSRF_HEADER, required = false) String csrfToken,
            @Valid @RequestBody UnbindPhoneRequest request) {
        UserPrincipal current = requirePrincipal(principal);
        accountCsrfService.require(current.getUserId(), current.getJti(), csrfToken);
        return CommonResult.success(phoneBindingService.unbind(current.getUserId(), request.currentPassword()));
    }

    @PostMapping("/avatar")
    public CommonResult<AccountProfileVO> uploadAvatar(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestHeader(value = CSRF_HEADER, required = false) String csrfToken,
            @RequestParam("file") MultipartFile file) {
        UserPrincipal current = requirePrincipal(principal);
        accountCsrfService.require(current.getUserId(), current.getJti(), csrfToken);
        return CommonResult.success(avatarService.upload(current.getUserId(), file));
    }

    @DeleteMapping("/avatar")
    public CommonResult<AccountProfileVO> resetAvatar(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestHeader(value = CSRF_HEADER, required = false) String csrfToken) {
        UserPrincipal current = requirePrincipal(principal);
        accountCsrfService.require(current.getUserId(), current.getJti(), csrfToken);
        return CommonResult.success(avatarService.reset(current.getUserId()));
    }

    @GetMapping("/avatars/me")
    public ResponseEntity<byte[]> readAvatar(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "v", required = false) Integer version,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        AvatarService.AvatarContent content = avatarService.read(userId(principal));
        String etag = "\"v" + content.version() + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(304).eTag(etag).build();
        }
        MediaType mediaType = MediaType.parseMediaType(
                content.mimeType() == null ? MediaType.IMAGE_PNG_VALUE : content.mimeType());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate())
                .eTag(etag)
                .body(content.bytes());
    }

    private static UserPrincipal requirePrincipal(UserPrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthenticated();
        }
        return principal;
    }

    private static String userId(UserPrincipal principal) {
        return requirePrincipal(principal).getUserId();
    }
}