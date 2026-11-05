package com.aijob.backend.profile.controller;

import com.aijob.backend.common.CommonResult;
import com.aijob.backend.profile.dto.ProfilePreferencesRequest;
import com.aijob.backend.profile.service.ProfileService;
import com.aijob.backend.profile.support.UserIdResolver;
import com.aijob.backend.profile.vo.ProfileVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 求职画像接口。
 * GET /api/v1/profile                 获取画像
 * PUT /api/v1/profile/preferences     保存求职偏好
 *
 * 临时身份来源：X-User-Id 请求头；认证落地后改为 Bearer accessToken。
 */
@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public CommonResult<ProfileVO> getProfile(
            @RequestHeader(value = "X-User-Id", required = false) String rawUserId) {
        return CommonResult.success(profileService.getProfile(UserIdResolver.resolve(rawUserId)));
    }

    @PutMapping("/preferences")
    public CommonResult<ProfileVO> savePreferences(
            @RequestHeader(value = "X-User-Id", required = false) String rawUserId,
            @Valid @RequestBody ProfilePreferencesRequest request) {
        return CommonResult.success(
                profileService.savePreferences(UserIdResolver.resolve(rawUserId), request));
    }
}