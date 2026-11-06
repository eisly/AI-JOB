package com.aijob.backend.user.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserProfileMapper {

    @Insert("""
            INSERT INTO user_profile (user_id, profile_version, cities_json, confirmed_facts_json)
            VALUES (#{userId}, 1, '[]', '{}')
            """)
    int insertEmpty(@Param("userId") String userId);
}