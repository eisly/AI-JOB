package com.aijob.backend.user.service.impl;

import com.aijob.backend.account.NicknameRules;
import com.aijob.backend.auth.vo.UserVO;
import com.aijob.backend.common.ApiErrorCode;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.user.entity.AppUser;
import com.aijob.backend.user.mapper.AppUserMapper;
import com.aijob.backend.user.service.AppUserService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AppUserServiceImpl implements AppUserService {

    private final AppUserMapper appUserMapper;

    public AppUserServiceImpl(AppUserMapper appUserMapper) {
        this.appUserMapper = appUserMapper;
    }

    @Override
    public List<AppUser> listUsers() {
        return appUserMapper.selectAll();
    }

    @Override
    public AppUser getUserById(String id) {
        return appUserMapper.selectById(id);
    }

    @Override
    public UserVO getCurrentUser(String userId) {
        AppUser user = appUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ApiErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        return toUserVO(user);
    }

    @Override
    public UserVO updateNickname(String userId, String nickname) {
        String normalized = NicknameRules.normalize(nickname);
        int updated = appUserMapper.updateNicknameAndTouch(userId, normalized);
        if (updated == 0) {
            throw new BusinessException(ApiErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        return getCurrentUser(userId);
    }

    private static UserVO toUserVO(AppUser user) {
        return new UserVO(user.getId(), user.getEmail(), user.getNickname(), user.getRole(), user.getCreatedAt());
    }
}