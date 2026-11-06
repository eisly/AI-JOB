package com.aijob.backend.ability;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AbilityMapper {

    @Insert("""
            INSERT IGNORE INTO ability_evidence (id,user_id,skill_code,source_type,source_id,source_version,
              score,confidence,quote,rubric_version,model_version,prompt_version,task_id,status)
            VALUES (#{id},#{userId},#{skillCode},#{sourceType},#{sourceId},#{sourceVersion},#{score},#{confidence},
              #{quote},#{rubricVersion},#{modelVersion},#{promptVersion},#{taskId},'VALID')
            """)
    int insertEvidence(@Param("id") String id, @Param("userId") String userId, @Param("skillCode") String skillCode,
                       @Param("sourceType") String sourceType, @Param("sourceId") String sourceId,
                       @Param("sourceVersion") int sourceVersion, @Param("score") Object score,
                       @Param("confidence") Object confidence, @Param("quote") String quote,
                       @Param("rubricVersion") String rubricVersion, @Param("modelVersion") String modelVersion,
                       @Param("promptVersion") String promptVersion, @Param("taskId") String taskId);

    @Update("""
            UPDATE ability_evidence SET status='REVOKED',revoked_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3)
            WHERE user_id=#{userId} AND source_type='RESUME_CLAIM' AND status='VALID'
            """)
    int revokeResumeClaims(@Param("userId") String userId);

    @Update("""
            UPDATE ability_evidence SET status='REVOKED',revoked_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3)
            WHERE user_id=#{userId} AND source_type='RESUME_CLAIM' AND source_id=#{sourceId} AND status='VALID'
            """)
    int revokeResumeVersion(@Param("userId") String userId, @Param("sourceId") String sourceId);

    @Select("""
            SELECT score FROM ability_evidence WHERE user_id=#{userId} AND skill_code=#{skillCode}
              AND status='VALID' AND score IS NOT NULL AND rubric_version='technical-answer-v1'
              AND source_type IN ('LEARNING_ANSWER','INTERVIEW_ANSWER')
            ORDER BY created_at DESC,id DESC LIMIT 20
            """)
    List<Object> last20(@Param("userId") String userId, @Param("skillCode") String skillCode);

    @Insert("""
            INSERT INTO user_skill_snapshot (user_id,skill_code,formula_version,score,sample_count,evidence_status,
              last_evidence_at,created_at,updated_at)
            VALUES (#{userId},#{skillCode},'ability-v1-trial',#{score},#{sampleCount},#{evidenceStatus},UTC_TIMESTAMP(3),
              UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE score=VALUES(score),sample_count=VALUES(sample_count),
              evidence_status=VALUES(evidence_status),last_evidence_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3)
            """)
    int saveSnapshot(@Param("userId") String userId, @Param("skillCode") String skillCode,
                     @Param("score") Object score, @Param("sampleCount") int sampleCount,
                     @Param("evidenceStatus") String evidenceStatus);

    @Select("SELECT * FROM user_skill_snapshot WHERE user_id=#{userId} AND formula_version='ability-v1-trial' ORDER BY skill_code")
    List<Map<String,Object>> snapshots(@Param("userId") String userId);

    @Select("""
            <script>
            SELECT id,skill_code,source_type,source_id,score,confidence,status,rubric_version,created_at
            FROM ability_evidence WHERE user_id=#{userId}
            <if test="skillCode != null">AND skill_code=#{skillCode}</if>
            <if test="sourceType != null">AND source_type=#{sourceType}</if>
            ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<Map<String,Object>> evidencePage(@Param("userId") String userId, @Param("skillCode") String skillCode,
                                          @Param("sourceType") String sourceType, @Param("size") int size,
                                          @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM ability_evidence WHERE user_id=#{userId}
            <if test="skillCode != null">AND skill_code=#{skillCode}</if>
            <if test="sourceType != null">AND source_type=#{sourceType}</if>
            </script>
            """)
    long evidenceCount(@Param("userId") String userId, @Param("skillCode") String skillCode,
                       @Param("sourceType") String sourceType);

    @Select("""
            SELECT e.id FROM ability_evidence e JOIN interview_turn_evaluation t ON t.answer_message_id=e.source_id
            WHERE t.interview_id=#{interviewId} AND e.source_type='INTERVIEW_ANSWER' AND e.status='VALID' ORDER BY e.created_at,e.id
            """)
    List<String> interviewEvidenceIds(@Param("interviewId") String interviewId);

    @Select("""
            SELECT id,skill_code,source_type,source_id,score,confidence,status,rubric_version,created_at
            FROM ability_evidence WHERE user_id=#{userId} ORDER BY created_at DESC,id DESC LIMIT #{limit}
            """)
    List<Map<String,Object>> recent(@Param("userId") String userId, @Param("limit") int limit);

    @Select("""
            SELECT id,skill_code,source_type,source_id,score,confidence,status,rubric_version,created_at
            FROM ability_evidence WHERE user_id=#{userId}
            <if test="skillCode != null">AND skill_code=#{skillCode}</if>
            ORDER BY created_at ASC,id ASC
            """)
    List<Map<String,Object>> timeline(@Param("userId") String userId, @Param("skillCode") String skillCode);
}