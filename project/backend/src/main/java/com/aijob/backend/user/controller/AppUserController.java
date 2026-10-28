package com.aijob.backend.user.controller;

import com.aijob.backend.common.CommonResult;
import com.aijob.backend.user.entity.AppUser;
import com.aijob.backend.user.service.AppUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户查询 demo 接口。
 * GET /api/v1/users        列出全部用户
 * GET /api/v1/users/{id}   按 ID 查询用户
 */
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

    @GetMapping("/{id}")
    public CommonResult<AppUser> get(@PathVariable("id") String id) {
        AppUser user = appUserService.getUserById(id);
        return user == null ? CommonResult.notFound("用户不存在") : CommonResult.success(user);
    }
}
