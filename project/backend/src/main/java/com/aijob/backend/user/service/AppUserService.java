package com.aijob.backend.user.service;

import com.aijob.backend.user.entity.AppUser;

import java.util.List;

/**
 * 用户查询服务接口。
 */
public interface AppUserService {

    List<AppUser> listUsers();

    AppUser getUserById(String id);
}
