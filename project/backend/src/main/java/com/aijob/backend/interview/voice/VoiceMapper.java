package com.aijob.backend.interview.voice;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 语音转写与隐私同意的存储访问。
 * 约束：本 Mapper 的任何语句都不得写 interview_message 或递增 interview_session.version。
 */
@Mapper
public interface VoiceMapper {

    @Insert("""
            INSERT INTO interview_voice_transcription
              (id,user_id,interview_id,question_message_id,interview_version,status,mime_type,byte_size,duration_ms,
               audio_sha256,expires_at)
            VALUES (#{id},#{userId},#{interviewId},#{questionMessageId},#{interviewVersion},'PENDING',#{mimeType},
               #{byteSize},#{durationMs},#{audioSha256},UTC_TIMESTAMP(3) + INTERVAL #{ttlSeconds} SECOND)
            """)
    int insertPending(@Param("id") String id, @Param("userId") String userId,
                      @Param("interviewId") String interviewId, @Param("questionMessageId") String questionMessageId,
                      @Param("interviewVersion") int interviewVersion, @Param("mimeType") String mimeType,
                      @Param("byteSize") int byteSize, @Param("durationMs") int durationMs,
                      @Param("audioSha256") String audioSha256, @Param("ttlSeconds") int ttlSeconds);

    @Select("""
            SELECT * FROM interview_voice_transcription
            WHERE interview_id=#{interviewId} AND question_message_id=#{questionMessageId} AND audio_sha256=#{audioSha256}
            """)
    Map<String,Object> findBySha(@Param("interviewId") String interviewId,
                                 @Param("questionMessageId") String questionMessageId,
                                 @Param("audioSha256") String audioSha256);

    @Select("""
            SELECT * FROM interview_voice_transcription
            WHERE id=#{id} AND user_id=#{userId} AND interview_id=#{interviewId}
            """)
    Map<String,Object> findById(@Param("id") String id, @Param("userId") String userId,
                                @Param("interviewId") String interviewId);

    @Select("""
            SELECT * FROM interview_voice_transcription
            WHERE id=#{id} AND user_id=#{userId} AND interview_id=#{interviewId}
              AND question_message_id=#{questionMessageId}
            """)
    Map<String,Object> findUsable(@Param("id") String id, @Param("userId") String userId,
                                  @Param("interviewId") String interviewId,
                                  @Param("questionMessageId") String questionMessageId);

    @Select("""
            SELECT * FROM interview_voice_transcription
            WHERE interview_id=#{interviewId} AND question_message_id=#{questionMessageId}
              AND status IN ('PENDING','SUCCEEDED','EMPTY')
            ORDER BY created_at DESC LIMIT 1
            """)
    Map<String,Object> findLatestByQuestion(@Param("interviewId") String interviewId,
                                            @Param("questionMessageId") String questionMessageId);

    @Update("""
            UPDATE interview_voice_transcription
            SET status='SUCCEEDED',text=#{text},provider=#{provider},error_key=NULL,
              expires_at=UTC_TIMESTAMP(3) + INTERVAL #{ttlSeconds} SECOND,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id}
            """)
    int markSucceeded(@Param("id") String id, @Param("text") String text, @Param("provider") String provider,
                      @Param("ttlSeconds") int ttlSeconds);

    @Update("""
            UPDATE interview_voice_transcription
            SET status='EMPTY',text=NULL,provider=#{provider},error_key=NULL,
              expires_at=UTC_TIMESTAMP(3) + INTERVAL #{ttlSeconds} SECOND,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id}
            """)
    int markEmpty(@Param("id") String id, @Param("provider") String provider, @Param("ttlSeconds") int ttlSeconds);

    @Update("""
            UPDATE interview_voice_transcription
            SET status='FAILED',text=NULL,error_key=#{errorKey},updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id}
            """)
    int markFailed(@Param("id") String id, @Param("errorKey") String errorKey);

    @Update("""
            UPDATE interview_voice_transcription
            SET status='DISCARDED',text=NULL,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND consumed_at IS NULL
            """)
    int markDiscarded(@Param("id") String id);

    @Update("""
            UPDATE interview_voice_transcription
            SET status='EXPIRED',text=NULL,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND status IN ('PENDING','SUCCEEDED','EMPTY') AND expires_at < UTC_TIMESTAMP(3)
            """)
    int expireIfStale(@Param("id") String id);

    @Update("""
            UPDATE interview_voice_transcription
            SET consumed_at=UTC_TIMESTAMP(3),consumed_message_id=#{messageId},updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND consumed_at IS NULL AND status='SUCCEEDED'
            """)
    int markConsumed(@Param("id") String id, @Param("messageId") String messageId);

    @Update("""
            UPDATE interview_voice_transcription SET status='EXPIRED',text=NULL,updated_at=UTC_TIMESTAMP(3)
            WHERE expires_at < UTC_TIMESTAMP(3) AND status IN ('PENDING','SUCCEEDED','EMPTY')
            """)
    int expireStale();

    @Select("""
            SELECT id FROM interview_voice_transcription
            WHERE expires_at < UTC_TIMESTAMP(3) - INTERVAL 24 HOUR AND consumed_at IS NULL
            LIMIT #{limit}
            """)
    List<String> staleUnconsumedIds(@Param("limit") int limit);

    @Select("""
            SELECT id FROM interview_voice_transcription
            WHERE consumed_at IS NOT NULL AND text IS NOT NULL
              AND consumed_at < UTC_TIMESTAMP(3) - INTERVAL 7 DAY
            LIMIT #{limit}
            """)
    List<String> consumedTextIds(@Param("limit") int limit);

    @Update("UPDATE interview_voice_transcription SET text=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int clearText(@Param("id") String id);

    @Select("""
            SELECT id FROM interview_voice_transcription
            WHERE consumed_at IS NOT NULL AND consumed_at < UTC_TIMESTAMP(3) - INTERVAL 30 DAY
            LIMIT #{limit}
            """)
    List<String> expiredConsumedIds(@Param("limit") int limit);

    @org.apache.ibatis.annotations.Delete("DELETE FROM interview_voice_transcription WHERE id=#{id}")
    int deleteById(@Param("id") String id);

    @Insert("""
            INSERT INTO interview_voice_consent (id,user_id,policy_version,agreed_at)
            VALUES (#{id},#{userId},#{policyVersion},UTC_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE agreed_at=UTC_TIMESTAMP(3),revoked_at=NULL,updated_at=UTC_TIMESTAMP(3)
            """)
    int upsertConsent(@Param("id") String id, @Param("userId") String userId,
                      @Param("policyVersion") String policyVersion);

    @Select("""
            SELECT * FROM interview_voice_consent
            WHERE user_id=#{userId} AND policy_version=#{policyVersion}
            """)
    Map<String,Object> findConsent(@Param("userId") String userId, @Param("policyVersion") String policyVersion);

    @Update("""
            UPDATE interview_voice_consent SET revoked_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3)
            WHERE user_id=#{userId} AND policy_version=#{policyVersion} AND revoked_at IS NULL
            """)
    int revokeConsent(@Param("userId") String userId, @Param("policyVersion") String policyVersion);
}