package com.aijob.backend.auth.mapper;

import com.aijob.backend.auth.entity.AuthSession;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
public interface AuthSessionMapper {

    @Insert("""
            INSERT INTO auth_session (id, user_id, family_id, token_hash, csrf_hash, parent_id, status, expires_at)
            VALUES (#{id}, #{userId}, #{familyId}, #{tokenHash}, #{csrfHash}, #{parentId}, #{status}, #{expiresAt})
            """)
    int insert(AuthSession session);

    @Select("""
            SELECT id, created_at, updated_at, user_id, family_id, token_hash, csrf_hash, parent_id,
                   status, expires_at, rotated_at, revoked_at
            FROM auth_session
            WHERE token_hash = #{tokenHash}
            """)
    AuthSession selectByTokenHash(@Param("tokenHash") byte[] tokenHash);

    @Select("""
            SELECT id, created_at, updated_at, user_id, family_id, token_hash, csrf_hash, parent_id,
                   status, expires_at, rotated_at, revoked_at
            FROM auth_session
            WHERE token_hash = #{tokenHash}
            FOR UPDATE
            """)
    AuthSession selectByTokenHashForUpdate(@Param("tokenHash") byte[] tokenHash);

    @Update("""
            UPDATE auth_session
            SET status = 'ROTATED', rotated_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND status = 'ACTIVE'
            """)
    int markRotated(@Param("id") String id, @Param("now") Instant now);

    @Update("""
            UPDATE auth_session
            SET status = 'REVOKED', revoked_at = #{now}, updated_at = #{now}
            WHERE family_id = #{familyId} AND status IN ('ACTIVE', 'ROTATED')
            """)
    int revokeFamily(@Param("familyId") String familyId, @Param("now") Instant now);

    @Update("""
            UPDATE auth_session
            SET status = 'REVOKED', revoked_at = #{now}, updated_at = #{now}
            WHERE user_id = #{userId} AND status IN ('ACTIVE', 'ROTATED')
            """)
    int revokeAllByUser(@Param("userId") String userId, @Param("now") Instant now);

    @Update("""
            UPDATE auth_session
            SET csrf_hash = #{csrfHash}, updated_at = #{now}
            WHERE id = #{id} AND status = 'ACTIVE'
            """)
    int updateCsrfHash(@Param("id") String id, @Param("csrfHash") byte[] csrfHash, @Param("now") Instant now);

    @Select("""
            SELECT COUNT(*) FROM auth_session
            WHERE user_id = #{userId} AND status = 'ACTIVE' AND expires_at > #{now}
            """)
    int countActive(@Param("userId") String userId, @Param("now") Instant now);
}