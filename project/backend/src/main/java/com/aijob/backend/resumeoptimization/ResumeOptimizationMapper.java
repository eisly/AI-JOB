package com.aijob.backend.resumeoptimization;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ResumeOptimizationMapper {

    @Insert("INSERT INTO resume_diagnosis (id,user_id,resume_version_id,task_id) VALUES (#{id},#{userId},#{resumeVersionId},#{taskId})")
    int insertDiagnosis(@Param("id") String id, @Param("userId") String userId,
                        @Param("resumeVersionId") String resumeVersionId, @Param("taskId") String taskId);

    @Select("SELECT * FROM resume_diagnosis WHERE id=#{id} AND user_id=#{userId}")
    Map<String, Object> diagnosis(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT * FROM resume_diagnosis WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    Map<String, Object> lockDiagnosis(@Param("id") String id, @Param("userId") String userId);

    @Select("""
            <script>
            SELECT * FROM resume_diagnosis WHERE user_id=#{userId}
            <if test="resumeVersionId != null">AND resume_version_id=#{resumeVersionId}</if>
            ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<Map<String, Object>> diagnosisPage(@Param("userId") String userId,
                                             @Param("resumeVersionId") String resumeVersionId,
                                             @Param("size") int size, @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM resume_diagnosis WHERE user_id=#{userId}
            <if test="resumeVersionId != null">AND resume_version_id=#{resumeVersionId}</if>
            </script>
            """)
    long diagnosisCount(@Param("userId") String userId, @Param("resumeVersionId") String resumeVersionId);

    @Update("UPDATE resume_diagnosis SET items_json=#{itemsJson},status='READY',updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND task_id=#{taskId} AND status='GENERATING'")
    int completeDiagnosis(@Param("id") String id, @Param("taskId") String taskId, @Param("itemsJson") String itemsJson);

    @Update("UPDATE resume_diagnosis SET status='FAILED',updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND task_id=#{taskId} AND status='GENERATING'")
    int failDiagnosis(@Param("id") String id, @Param("taskId") String taskId);

    @Update("UPDATE resume_diagnosis SET status='GENERATING',task_id=#{taskId},items_json=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='FAILED'")
    int retryDiagnosis(@Param("id") String id, @Param("taskId") String taskId);

    @Insert("""
            INSERT INTO resume_optimization (id,user_id,resume_version_id,job_id,diagnosis_id,task_id)
            VALUES (#{id},#{userId},#{resumeVersionId},#{jobId},#{diagnosisId},#{taskId})
            """)
    int insertOptimization(@Param("id") String id, @Param("userId") String userId,
                           @Param("resumeVersionId") String resumeVersionId, @Param("jobId") String jobId,
                           @Param("diagnosisId") String diagnosisId, @Param("taskId") String taskId);

    @Select("SELECT * FROM resume_optimization WHERE id=#{id} AND user_id=#{userId}")
    Map<String, Object> optimization(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT * FROM resume_optimization WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    Map<String, Object> lockOptimization(@Param("id") String id, @Param("userId") String userId);

    @Select("""
            <script>
            SELECT * FROM resume_optimization WHERE user_id=#{userId}
            <if test="resumeVersionId != null">AND resume_version_id=#{resumeVersionId}</if>
            <if test="jobId != null">AND job_id=#{jobId}</if>
            ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<Map<String, Object>> optimizationPage(@Param("userId") String userId,
                                                @Param("resumeVersionId") String resumeVersionId,
                                                @Param("jobId") String jobId, @Param("size") int size,
                                                @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM resume_optimization WHERE user_id=#{userId}
            <if test="resumeVersionId != null">AND resume_version_id=#{resumeVersionId}</if>
            <if test="jobId != null">AND job_id=#{jobId}</if>
            </script>
            """)
    long optimizationCount(@Param("userId") String userId, @Param("resumeVersionId") String resumeVersionId,
                           @Param("jobId") String jobId);

    @Update("UPDATE resume_optimization SET title=#{title},sections_json=#{sectionsJson},status='READY',updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND task_id=#{taskId} AND status='GENERATING'")
    int completeOptimization(@Param("id") String id, @Param("taskId") String taskId,
                             @Param("title") String title, @Param("sectionsJson") String sectionsJson);

    @Update("UPDATE resume_optimization SET accepted_sections_json=#{sectionsJson},accepted_at=UTC_TIMESTAMP(3),status='ACCEPTED',updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='READY'")
    int accept(@Param("id") String id, @Param("sectionsJson") String sectionsJson);

    @Update("UPDATE resume_optimization SET status='FAILED',updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND task_id=#{taskId} AND status='GENERATING'")
    int failOptimization(@Param("id") String id, @Param("taskId") String taskId);

    @Update("UPDATE resume_optimization SET status='GENERATING',task_id=#{taskId},title=NULL,sections_json=NULL,accepted_sections_json=NULL,accepted_at=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='FAILED'")
    int retryOptimization(@Param("id") String id, @Param("taskId") String taskId);
}