package com.aijob.backend.profile;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.CurrentUserResolver;
import com.aijob.backend.common.Responses;

@RestController
@RequestMapping("/api/v1")
public class ProfileController {

    private final CurrentUserResolver users;
    private final ProfileService profiles;

    public ProfileController(CurrentUserResolver users, ProfileService profiles) {
        this.users = users;
        this.profiles = profiles;
    }

    @GetMapping("/profile")
    public CommonResult<Map<String, Object>> profile() {
        return Responses.ok(profiles.get(users.resolveCurrentUserId()));
    }

    @PutMapping("/profile/preferences")
    public CommonResult<Map<String, Object>> preferences(@RequestBody Map<String, Object> body) {
        return Responses.ok(profiles.preferences(users.resolveCurrentUserId(), body));
    }

    @GetMapping("/skills")
    public CommonResult<List<Map<String, Object>>> skills() {
        users.resolveCurrentUserId();
        return Responses.ok(profiles.skillCatalog());
    }
}