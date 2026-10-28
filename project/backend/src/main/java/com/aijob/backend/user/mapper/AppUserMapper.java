package com.aijob.backend.user.mapper;

import com.aijob.backend.user.entity.AppUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * app_user 查询 Mapper（注解式，demo 用）。
 * 未查询 password_hash 等敏感列。
 */
@Mapper
public interface AppUserMapper {

    @Select("""
            SELECT id, email, nickname, role, status, created_at, updated_at
            FROM app_user
            WHERE deleted_at IS NULL
            ORDER BY created_at, id
            """)
    List<AppUser> selectAll();

    @Select("""
            SELECT id, email, nickname, role, status, created_at, updated_at
            FROM app_user
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    AppUser selectById(@Param("id") String id);
}
