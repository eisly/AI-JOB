package com.aijob.backend.profile.service;

import com.aijob.backend.profile.dto.ProfilePreferencesRequest;
import com.aijob.backend.profile.vo.ProfileVO;
import com.aijob.backend.profile.vo.SkillVO;

import java.util.List;

/**
 * 求职画像与技能目录服务。
 */
public interface ProfileService {

    ProfileVO getProfile(String userId);

    ProfileVO savePreferences(String userId, ProfilePreferencesRequest request);

    List<SkillVO> listSkills(String q);
}