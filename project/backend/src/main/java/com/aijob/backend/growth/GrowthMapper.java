package com.aijob.backend.growth;

import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GrowthMapper {

    @Insert("""
            INSERT INTO growth_advice (id,user_id,profile_version,task_id,status,cited_evidence_ids_json)
            VALUES (#{id},#{userId},#{profileVersion},#{taskId},'GENERATING',JSON_ARRAY())
            """)
    int insertAdvice(@Param("id") String id, @Param("userId") String userId,
                     @Param("profileVersion") long profileVersion, @Param("taskId") String taskId);

    @Select("""
            SELECT * FROM growth_advice WHERE user_id=#{userId} AND profile_version=#{profileVersion}
              AND status='READY' ORDER BY created_at DESC,id DESC LIMIT 1
            """)
    Map<String,Object> latestReady(@Param("userId") String userId, @Param("profileVersion") long profileVersion);

    /** 不限版本取最新 READY 建议：画像版本变化后旧建议仍可查看，由服务层标注过期（G03） */
    @Select("""
            SELECT * FROM growth_advice WHERE user_id=#{userId} AND status='READY'
              ORDER BY created_at DESC,id DESC LIMIT 1
            """)
    Map<String,Object> latestReadyAnyVersion(@Param("userId") String userId);

    @Select("SELECT * FROM growth_advice WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    Map<String,Object> lockAdvice(@Param("id") String id, @Param("userId") String userId);

    @Update("""
            UPDATE growth_advice SET status='READY',suggestions_json=#{suggestionsJson},
              cited_evidence_ids_json=#{citedEvidenceIdsJson},updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND user_id=#{userId} AND task_id=#{taskId} AND status='GENERATING'
            """)
    int ready(@Param("id") String id, @Param("userId") String userId, @Param("taskId") String taskId,
              @Param("suggestionsJson") String suggestionsJson, @Param("citedEvidenceIdsJson") String citedEvidenceIdsJson);

    @Update("""
            UPDATE growth_advice SET status='FAILED',updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND user_id=#{userId} AND task_id=#{taskId} AND status='GENERATING'
            """)
    int fail(@Param("id") String id, @Param("userId") String userId, @Param("taskId") String taskId);

    @Update("""
            UPDATE growth_advice SET status='GENERATING',task_id=#{newTaskId},suggestions_json=NULL,
              cited_evidence_ids_json=JSON_ARRAY(),updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND user_id=#{userId} AND task_id=#{originalTaskId} AND status='FAILED'
            """)
    int prepareRetry(@Param("id") String id, @Param("userId") String userId,
                     @Param("originalTaskId") String originalTaskId, @Param("newTaskId") String newTaskId);
}