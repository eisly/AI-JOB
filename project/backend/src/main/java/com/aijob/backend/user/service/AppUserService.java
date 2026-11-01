package com.aijob.backend.user.service;

import com.aijob.backend.auth.vo.UserVO;
import com.aijob.backend.user.entity.AppUser;

import java.util.List;

public interface AppUserService {

    List<AppUser> listUsers();

    AppUser getUserById(String id);

    UserVO getCurrentUser(String userId);

    UserVO updateNickname(String userId, String nickname);
}