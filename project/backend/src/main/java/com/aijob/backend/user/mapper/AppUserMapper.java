package com.aijob.backend.user.mapper;

import com.aijob.backend.user.entity.AppUser;
import com.aijob.backend.user.entity.AppUserAuth;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
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
            SELECT id, email, nickname, phone, phone_bound_at, avatar_storage_key, avatar_mime_type,
                   avatar_size_bytes, avatar_version, profile_updated_at, role, status, created_at, updated_at
            FROM app_user
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    AppUser selectById(@Param("id") String id);

    @Select("""
            SELECT id, email, password_hash, nickname, role, status, token_version, password_failed_attempts,
                   password_locked_until, created_at, deleted_at
            FROM app_user
            WHERE email = #{email} AND deleted_at IS NULL
            """)
    AppUserAuth selectAuthByEmail(@Param("email") String email);

    @Select("""
            SELECT id, email, password_hash, nickname, role, status, token_version, password_failed_attempts,
                   password_locked_until, created_at, deleted_at
            FROM app_user
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    AppUserAuth selectAuthById(@Param("id") String id);

    @Select("""
            SELECT id
            FROM app_user
            WHERE phone = #{phone} AND deleted_at IS NULL
            """)
    String selectIdByPhone(@Param("phone") String phone);

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
            SET nickname = #{nickname},
                profile_updated_at = CURRENT_TIMESTAMP(3),
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int updateNicknameAndTouch(@Param("id") String id, @Param("nickname") String nickname);

    @Update("""
            UPDATE app_user
            SET phone = #{phone},
                phone_bound_at = #{phoneBoundAt},
                profile_updated_at = CURRENT_TIMESTAMP(3),
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND phone IS NULL AND deleted_at IS NULL
            """)
    int bindPhone(@Param("id") String id, @Param("phone") String phone, @Param("phoneBoundAt") Instant phoneBoundAt);

    @Update("""
            UPDATE app_user
            SET phone = #{phone},
                phone_bound_at = #{phoneBoundAt},
                profile_updated_at = CURRENT_TIMESTAMP(3),
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND phone = #{currentPhone} AND deleted_at IS NULL
            """)
    int rebindPhone(@Param("id") String id, @Param("phone") String phone,
                    @Param("phoneBoundAt") Instant phoneBoundAt, @Param("currentPhone") String currentPhone);

    @Update("""
            UPDATE app_user
            SET phone = NULL,
                phone_bound_at = NULL,
                profile_updated_at = CURRENT_TIMESTAMP(3),
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND phone = #{currentPhone} AND deleted_at IS NULL
            """)
    int unbindPhone(@Param("id") String id, @Param("currentPhone") String currentPhone);

    @Update("""
            UPDATE app_user
            SET avatar_storage_key = #{storageKey},
                avatar_mime_type = #{mimeType},
                avatar_size_bytes = #{sizeBytes},
                avatar_version = avatar_version + 1,
                profile_updated_at = CURRENT_TIMESTAMP(3),
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int updateAvatar(@Param("id") String id, @Param("storageKey") String storageKey,
                     @Param("mimeType") String mimeType, @Param("sizeBytes") long sizeBytes);

    @Update("""
            UPDATE app_user
            SET avatar_storage_key = NULL,
                avatar_mime_type = NULL,
                avatar_size_bytes = NULL,
                avatar_version = avatar_version + 1,
                profile_updated_at = CURRENT_TIMESTAMP(3),
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int clearAvatar(@Param("id") String id);

    @Update("""
            UPDATE app_user
            SET password_failed_attempts = password_failed_attempts + 1,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int incrementPasswordFailure(@Param("id") String id);

    @Select("""
            SELECT password_failed_attempts
            FROM app_user
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    Integer selectPasswordFailureCount(@Param("id") String id);

    @Update("""
            UPDATE app_user
            SET password_locked_until = #{lockedUntil},
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int lockPassword(@Param("id") String id, @Param("lockedUntil") Instant lockedUntil);

    @Update("""
            UPDATE app_user
            SET password_failed_attempts = 0,
                password_locked_until = NULL,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int clearPasswordFailure(@Param("id") String id);

    @Update("""
            UPDATE app_user
            SET password_hash = #{passwordHash},
                token_version = token_version + 1,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND password_hash = #{expectedHash} AND deleted_at IS NULL
            """)
    int updatePasswordHash(@Param("id") String id, @Param("passwordHash") String passwordHash,
                           @Param("expectedHash") String expectedHash);

    @Update("""
            UPDATE app_user
            SET password_hash = #{passwordHash},
                token_version = token_version + 1,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int updatePasswordAndBumpVersion(@Param("id") String id, @Param("passwordHash") String passwordHash);
}