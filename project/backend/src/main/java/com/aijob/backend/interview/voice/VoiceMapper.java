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

    /**
     * 显式重试：复用同一条记录，但识别尝试序号必须递增。
     * 已消费记录禁止重置；PENDING 也允许重置，仅用于超过新鲜窗口的僵死请求接管，
     * 新鲜 PENDING 在服务层已被 TASK_IN_PROGRESS 拦截。
     */
    @Update("""
            UPDATE interview_voice_transcription SET status='PENDING',text=NULL,error_key=NULL,provider=NULL,
              interview_version=#{version},attempt=attempt+1,updated_at=UTC_TIMESTAMP(3),
              expires_at=UTC_TIMESTAMP(3) + INTERVAL #{ttlSeconds} SECOND
            WHERE id=#{id} AND consumed_at IS NULL
              AND status IN ('PENDING','FAILED','EMPTY','DISCARDED','EXPIRED')
            """)
    int resetPending(@Param("id") String id, @Param("version") int version,
                     @Param("ttlSeconds") int ttlSeconds);

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

    /*
     * 重试会复用同一记录（created_at 不变，仅 attempt/updated_at 变化），
     * 因此“最新”必须按 updated_at + attempt 排序，不能按 created_at。
     */
    @Select("""
            SELECT * FROM interview_voice_transcription
            WHERE interview_id=#{interviewId} AND question_message_id=#{questionMessageId}
              AND status IN ('PENDING','SUCCEEDED','EMPTY') AND consumed_at IS NULL
            ORDER BY updated_at DESC, attempt DESC LIMIT 1
            """)
    Map<String,Object> findLatestByQuestion(@Param("interviewId") String interviewId,
                                            @Param("questionMessageId") String questionMessageId);

    @Select("""
            SELECT * FROM interview_voice_transcription
            WHERE interview_id=#{interviewId} AND question_message_id=#{questionMessageId}
              AND status='PENDING'
            ORDER BY updated_at DESC, attempt DESC LIMIT 1
            """)
    Map<String,Object> findPendingByQuestion(@Param("interviewId") String interviewId,
                                             @Param("questionMessageId") String questionMessageId);

    /**
     * 新请求接管前收敛同题已超新鲜窗口的陈旧 PENDING（不同音频并发上传的遗留请求），
     * 保证同一题目至多一条 PENDING。不改 attempt、不触碰会话与消息。
     */
    @Update("""
            UPDATE interview_voice_transcription
            SET status='DISCARDED',text=NULL,updated_at=UTC_TIMESTAMP(3)
            WHERE interview_id=#{interviewId} AND question_message_id=#{questionMessageId}
              AND id<>#{keepId} AND status='PENDING' AND consumed_at IS NULL
              AND updated_at < UTC_TIMESTAMP(3) - INTERVAL #{staleSeconds} SECOND
            """)
    int discardStalePendingOfQuestion(@Param("interviewId") String interviewId,
                                      @Param("questionMessageId") String questionMessageId,
                                      @Param("keepId") String keepId,
                                      @Param("staleSeconds") int staleSeconds);

    /*
     * 结果回写统一使用条件更新：记录 ID + 识别尝试序号 + 仍为 PENDING + 未消费（+ 未过期）。
     * 调用方必须检查受影响行数；0 行表示该尝试已失效，禁止再改写记录或影响会话。
     */
    @Update("""
            UPDATE interview_voice_transcription
            SET status='SUCCEEDED',text=#{text},provider=#{provider},error_key=NULL,
              expires_at=UTC_TIMESTAMP(3) + INTERVAL #{ttlSeconds} SECOND,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND attempt=#{attempt} AND status='PENDING' AND consumed_at IS NULL
              AND expires_at > UTC_TIMESTAMP(3)
            """)
    int markSucceededIfPending(@Param("id") String id, @Param("attempt") int attempt,
                               @Param("text") String text, @Param("provider") String provider,
                               @Param("ttlSeconds") int ttlSeconds);

    @Update("""
            UPDATE interview_voice_transcription
            SET status='EMPTY',text=NULL,provider=#{provider},error_key=NULL,
              expires_at=UTC_TIMESTAMP(3) + INTERVAL #{ttlSeconds} SECOND,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND attempt=#{attempt} AND status='PENDING' AND consumed_at IS NULL
              AND expires_at > UTC_TIMESTAMP(3)
            """)
    int markEmptyIfPending(@Param("id") String id, @Param("attempt") int attempt,
                           @Param("provider") String provider, @Param("ttlSeconds") int ttlSeconds);

    @Update("""
            UPDATE interview_voice_transcription
            SET status='FAILED',text=NULL,error_key=#{errorKey},updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND attempt=#{attempt} AND status='PENDING' AND consumed_at IS NULL
              AND expires_at > UTC_TIMESTAMP(3)
            """)
    int markFailedIfPending(@Param("id") String id, @Param("attempt") int attempt,
                            @Param("errorKey") String errorKey);

    /** 晚到结果到达时记录仍属于当前题，但会话/版本/题目已变化：落 DISCARDED。 */
    @Update("""
            UPDATE interview_voice_transcription
            SET status='DISCARDED',text=NULL,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND attempt=#{attempt} AND status='PENDING' AND consumed_at IS NULL
            """)
    int markDiscardedIfPending(@Param("id") String id, @Param("attempt") int attempt);

    /** 晚到结果到达时记录已过期：保持 EXPIRED，不允许复活。 */
    @Update("""
            UPDATE interview_voice_transcription
            SET status='EXPIRED',text=NULL,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND attempt=#{attempt} AND status='PENDING' AND consumed_at IS NULL
            """)
    int markExpiredIfPending(@Param("id") String id, @Param("attempt") int attempt);

    @Update("""
            UPDATE interview_voice_transcription
            SET status='DISCARDED',text=NULL,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND consumed_at IS NULL
              AND status IN ('PENDING','SUCCEEDED','EMPTY','FAILED')
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
              AND expires_at > UTC_TIMESTAMP(3)
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
