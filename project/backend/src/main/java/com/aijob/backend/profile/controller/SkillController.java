package com.aijob.backend.profile.controller;

import com.aijob.backend.common.CommonResult;
import com.aijob.backend.profile.service.ProfileService;
import com.aijob.backend.profile.support.UserIdResolver;
import com.aijob.backend.profile.vo.SkillVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 技能目录接口。
 * GET /api/v1/skills?q=  只返回启用技能。
 *
 * 临时身份来源：X-User-Id 请求头；认证落地后改为 Bearer accessToken。
 */
@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {

    private final ProfileService profileService;

    public SkillController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public CommonResult<List<SkillVO>> listSkills(
            @RequestHeader(value = "X-User-Id", required = false) String rawUserId,
            @RequestParam(value = "q", required = false) String q) {
        UserIdResolver.resolve(rawUserId);
        return CommonResult.success(profileService.listSkills(q));
    }
}