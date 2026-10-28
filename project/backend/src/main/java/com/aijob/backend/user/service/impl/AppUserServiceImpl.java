package com.aijob.backend.user.service.impl;

import com.aijob.backend.user.entity.AppUser;
import com.aijob.backend.user.mapper.AppUserMapper;
import com.aijob.backend.user.service.AppUserService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户查询服务实现，直连 MyBatis Mapper。
 */
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
}
