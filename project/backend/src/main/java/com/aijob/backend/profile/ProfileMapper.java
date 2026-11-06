package com.aijob.backend.profile;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProfileMapper {

    @Insert("""
            INSERT IGNORE INTO user_profile (user_id,profile_version,cities_json,confirmed_facts_json)
            VALUES (#{userId},1,JSON_ARRAY(),JSON_OBJECT('education',NULL,'graduationYear',NULL,
              'experienceMonths',NULL,'major',NULL,'skills',JSON_ARRAY(),'warnings',JSON_ARRAY()))
            """)
    int ensure(@Param("userId") String userId);

    @Select("SELECT * FROM user_profile WHERE user_id=#{userId}")
    Map<String,Object> find(@Param("userId") String userId);

    @Select("SELECT * FROM user_profile WHERE user_id=#{userId} FOR UPDATE")
    Map<String,Object> lock(@Param("userId") String userId);

    @Update("""
            UPDATE user_profile SET target_role=#{targetRole},candidate_type=#{candidateType},cities_json=#{citiesJson},
              min_monthly_salary_cents=#{minSalary},max_monthly_salary_cents=#{maxSalary},profile_version=profile_version+1,
              updated_at=UTC_TIMESTAMP(3) WHERE user_id=#{userId} AND profile_version=#{expectedVersion}
            """)
    int updatePreferences(@Param("userId") String userId, @Param("targetRole") String targetRole,
                          @Param("candidateType") String candidateType, @Param("citiesJson") String citiesJson,
                          @Param("minSalary") Integer minSalary, @Param("maxSalary") Integer maxSalary,
                          @Param("expectedVersion") long expectedVersion);

    @Update("""
            UPDATE user_profile SET confirmed_facts_json=#{factsJson},active_resume_version_id=#{versionId},
              profile_version=profile_version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE user_id=#{userId} AND profile_version=#{expectedVersion}
            """)
    int confirmFacts(@Param("userId") String userId, @Param("factsJson") String factsJson,
                     @Param("versionId") String versionId, @Param("expectedVersion") long expectedVersion);

    @Update("""
            UPDATE user_profile SET confirmed_facts_json=JSON_OBJECT('education',NULL,'graduationYear',NULL,
              'experienceMonths',NULL,'major',NULL,'skills',JSON_ARRAY(),'warnings',JSON_ARRAY()),
              active_resume_version_id=NULL,profile_version=profile_version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE user_id=#{userId} AND active_resume_version_id=#{versionId}
            """)
    int clearActive(@Param("userId") String userId, @Param("versionId") String versionId);

    @Update("UPDATE user_profile SET profile_version=profile_version+1,updated_at=UTC_TIMESTAMP(3) WHERE user_id=#{userId}")
    int touch(@Param("userId") String userId);

    @Select("SELECT code,name,parent_code,enabled FROM skill_catalog WHERE enabled=1 ORDER BY code")
    List<Map<String,Object>> skills();

    @Select("SELECT COUNT(*) FROM skill_catalog WHERE enabled=1 AND code=#{code}")
    int skillEnabled(@Param("code") String code);
}