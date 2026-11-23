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

    @Update("UPDATE resume_diagnosis SET items_json=#{itemsJson},assessment_json=#{assessmentJson},source_text=#{sourceText},text_layout_version=#{textLayoutVersion},read_order_verified=#{readOrderVerified},status='READY',updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND task_id=#{taskId} AND status='GENERATING'")
    int completeDiagnosis(@Param("id") String id, @Param("taskId") String taskId, @Param("itemsJson") String itemsJson,
                          @Param("assessmentJson") String assessmentJson, @Param("sourceText") String sourceText,
                          @Param("textLayoutVersion") String textLayoutVersion,
                          @Param("readOrderVerified") boolean readOrderVerified);

    @Update("UPDATE resume_diagnosis SET status='FAILED',updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND task_id=#{taskId} AND status='GENERATING'")
    int failDiagnosis(@Param("id") String id, @Param("taskId") String taskId);

    @Update("UPDATE resume_diagnosis SET status='GENERATING',task_id=#{taskId},items_json=NULL,assessment_json=NULL,source_text=NULL,text_layout_version=NULL,read_order_verified=0,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='FAILED'")
    int retryDiagnosis(@Param("id") String id, @Param("taskId") String taskId);

    @Insert("""
            INSERT INTO resume_optimization (id,user_id,resume_version_id,job_id,diagnosis_id,mode,job_version,
              job_company_name,job_title,source_text,task_id)
            VALUES (#{id},#{userId},#{resumeVersionId},#{jobId},#{diagnosisId},#{mode},#{jobVersion},
              #{companyName},#{jobTitle},#{sourceText},#{taskId})
            """)
    int insertOptimization(@Param("id") String id, @Param("userId") String userId,
                           @Param("resumeVersionId") String resumeVersionId, @Param("jobId") String jobId,
                           @Param("diagnosisId") String diagnosisId, @Param("mode") String mode,
                           @Param("jobVersion") Integer jobVersion, @Param("companyName") String companyName,
                           @Param("jobTitle") String jobTitle, @Param("sourceText") String sourceText,
                           @Param("taskId") String taskId);

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

    @Update("""
            UPDATE resume_optimization SET title=#{title},sections_json=#{sectionsJson},
              missing_requirements_json=#{missingJson},notes_json=#{notesJson},
              full_document_text=#{fullText},full_document_json=#{fullJson},
              status='READY',updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND task_id=#{taskId} AND status='GENERATING'
            """)
    int completeOptimization(@Param("id") String id, @Param("taskId") String taskId,
                             @Param("title") String title, @Param("sectionsJson") String sectionsJson,
                             @Param("missingJson") String missingJson, @Param("notesJson") String notesJson,
                             @Param("fullText") String fullText, @Param("fullJson") String fullJson);

    @Update("""
            UPDATE resume_optimization SET accepted_sections_json=#{sectionsJson},
              full_document_text=#{fullText},full_document_json=#{fullJson},
              accepted_at=UTC_TIMESTAMP(3),status='ACCEPTED',updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND status='READY'
            """)
    int accept(@Param("id") String id, @Param("sectionsJson") String sectionsJson,
               @Param("fullText") String fullText, @Param("fullJson") String fullJson);

    @Update("UPDATE resume_optimization SET status='FAILED',updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND task_id=#{taskId} AND status='GENERATING'")
    int failOptimization(@Param("id") String id, @Param("taskId") String taskId);

    @Update("""
            UPDATE resume_optimization SET status='GENERATING',task_id=#{taskId},title=NULL,sections_json=NULL,
              missing_requirements_json=NULL,notes_json=NULL,accepted_sections_json=NULL,full_document_text=NULL,
              full_document_json=NULL,accepted_at=NULL,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND status='FAILED'
            """)
    int retryOptimization(@Param("id") String id, @Param("taskId") String taskId);
}