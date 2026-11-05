package com.aijob.backend.profile.mapper;

import com.aijob.backend.profile.entity.UserProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * user_profile 读写 Mapper。
 * 更新使用 profile_version 作为乐观锁条件。
 */
@Mapper
public interface UserProfileMapper {

    @Select("""
            SELECT user_id, profile_version, target_role, candidate_type, cities_json,
                   min_monthly_salary_cents, max_monthly_salary_cents, confirmed_facts_json,
                   active_resume_version_id, created_at, updated_at
            FROM user_profile
            WHERE user_id = #{userId}
            """)
    UserProfile selectByUserId(@Param("userId") String userId);

    @Update("""
            UPDATE user_profile
            SET profile_version = profile_version + 1,
                target_role = #{targetRole},
                candidate_type = #{candidateType},
                cities_json = CAST(#{citiesJson} AS JSON),
                min_monthly_salary_cents = #{minMonthlySalaryCents},
                max_monthly_salary_cents = #{maxMonthlySalaryCents},
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE user_id = #{userId}
              AND profile_version = #{expectedVersion}
            """)
    int updatePreferences(@Param("userId") String userId,
                          @Param("expectedVersion") Long expectedVersion,
                          @Param("targetRole") String targetRole,
                          @Param("candidateType") String candidateType,
                          @Param("citiesJson") String citiesJson,
                          @Param("minMonthlySalaryCents") Integer minMonthlySalaryCents,
                          @Param("maxMonthlySalaryCents") Integer maxMonthlySalaryCents);
}