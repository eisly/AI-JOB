package com.aijob.backend.user.mapper;

import com.aijob.backend.user.entity.AppUser;
import com.aijob.backend.user.entity.AppUserAuth;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

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

    @Select("""
            SELECT id, email, password_hash, nickname, role, status, token_version, created_at, deleted_at
            FROM app_user
            WHERE email = #{email} AND deleted_at IS NULL
            """)
    AppUserAuth selectAuthByEmail(@Param("email") String email);

    @Select("""
            SELECT id, email, password_hash, nickname, role, status, token_version, created_at, deleted_at
            FROM app_user
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    AppUserAuth selectAuthById(@Param("id") String id);

    @Insert("""
            INSERT INTO app_user (id, email, password_hash, nickname, role, status, token_version)
            VALUES (#{id}, #{email}, #{passwordHash}, #{nickname}, #{role}, #{status}, #{tokenVersion})
            """)
    int insert(AppUserAuth user);

    @Update("""
            UPDATE app_user
            SET nickname = #{nickname}, updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int updateNickname(@Param("id") String id, @Param("nickname") String nickname);

    @Update("""
            UPDATE app_user
            SET password_hash = #{passwordHash},
                token_version = token_version + 1,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int updatePasswordAndBumpVersion(@Param("id") String id, @Param("passwordHash") String passwordHash);
}