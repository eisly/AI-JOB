package com.aijob.backend.user.controller;

import com.aijob.backend.auth.security.UserPrincipal;
import com.aijob.backend.auth.vo.UserVO;
import com.aijob.backend.common.CommonResult;
import com.aijob.backend.user.dto.UpdateNicknameRequest;
import com.aijob.backend.user.entity.AppUser;
import com.aijob.backend.user.service.AppUserService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
public class AppUserController {

    private final AppUserService appUserService;

    public AppUserController(AppUserService appUserService) {
        this.appUserService = appUserService;
    }

    @GetMapping
    public CommonResult<List<AppUser>> list() {
        return CommonResult.success(appUserService.listUsers());
    }

    @GetMapping("/me")
    public CommonResult<UserVO> me(@AuthenticationPrincipal UserPrincipal principal) {
        return CommonResult.success(appUserService.getCurrentUser(principal.getUserId()));
    }

    @PatchMapping("/me")
    public CommonResult<UserVO> updateMe(@AuthenticationPrincipal UserPrincipal principal,
                                         @Valid @RequestBody UpdateNicknameRequest request) {
        return CommonResult.success(appUserService.updateNickname(principal.getUserId(), request.nickname()));
    }

    @GetMapping("/{id}")
    public CommonResult<AppUser> get(@PathVariable("id") String id) {
        AppUser user = appUserService.getUserById(id);
        return user == null ? CommonResult.notFound("用户不存在") : CommonResult.success(user);
    }
}