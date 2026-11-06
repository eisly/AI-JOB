package com.aijob.backend.job;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface JobSearchMapper {

    @Select("SELECT id FROM job_source WHERE code=#{code}")
    String sourceId(@Param("code") String code);

    @Insert("""
            INSERT INTO job_source (id,code,name,type,base_url,verification_status,verified_at)
            VALUES (#{id},#{code},#{name},#{type},#{baseUrl},#{verification},UTC_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE name=VALUES(name),type=VALUES(type),base_url=VALUES(base_url),
              verification_status=VALUES(verification_status),verified_at=VALUES(verified_at)
            """)
    int upsertSource(@Param("id") String id, @Param("code") String code, @Param("name") String name,
                     @Param("type") String type, @Param("baseUrl") String baseUrl,
                     @Param("verification") String verification);

    @Insert("""
            INSERT INTO job (id,source_id,owner_user_id,visibility,current_version,status,source_url,verified_at)
            VALUES (#{id},#{sourceId},#{userId},'PRIVATE',1,'ACTIVE',#{sourceUrl},UTC_TIMESTAMP(3))
            """)
    int insertJob(@Param("id") String id, @Param("sourceId") String sourceId, @Param("userId") String userId,
                  @Param("sourceUrl") String sourceUrl);

    @Insert("""
            INSERT INTO job_version (id,job_id,version_no,title,company_name,city_code,role_code,candidate_type,
              company_size,published_at,fetched_at,min_monthly_salary_cents,max_monthly_salary_cents,jd_text,
              content_hash,requirements_json,analysis_meta_json)
            VALUES (#{id},#{jobId},1,#{title},#{companyName},#{cityCode},#{roleCode},#{candidateType},
              #{companySize},#{publishedAt},#{fetchedAt},#{minSalary},#{maxSalary},#{jdText},
              UNHEX(#{contentHash}),#{requirementsJson},#{metaJson})
            """)
    int insertJobVersion(@Param("id") String id, @Param("jobId") String jobId, @Param("title") String title,
                         @Param("companyName") String companyName, @Param("cityCode") String cityCode,
                         @Param("roleCode") String roleCode, @Param("candidateType") String candidateType,
                         @Param("companySize") String companySize,
                         @Param("publishedAt") java.time.LocalDateTime publishedAt,
                         @Param("fetchedAt") java.time.LocalDateTime fetchedAt,
                         @Param("minSalary") Integer minSalary, @Param("maxSalary") Integer maxSalary,
                         @Param("jdText") String jdText, @Param("contentHash") String contentHash,
                         @Param("requirementsJson") String requirementsJson, @Param("metaJson") String metaJson);

    @Insert("""
            INSERT INTO job_search (id,user_id,task_id,batch_id,profile_version,resume_version_id,filters_json,status)
            VALUES (#{id},#{userId},#{taskId},#{batchId},#{profileVersion},#{resumeVersionId},#{filtersJson},'GENERATING')
            """)
    int insertSearch(@Param("id") String id, @Param("userId") String userId, @Param("taskId") String taskId,
                     @Param("batchId") String batchId, @Param("profileVersion") long profileVersion,
                     @Param("resumeVersionId") String resumeVersionId, @Param("filtersJson") String filtersJson);

    @Select("SELECT * FROM job_search WHERE id=#{id} AND user_id=#{userId}")
    Map<String, Object> search(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT * FROM job_search WHERE id=#{id} FOR UPDATE")
    Map<String, Object> lockSearch(@Param("id") String id);

    @Update("UPDATE job_search SET status=#{status},updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int updateStatus(@Param("id") String id, @Param("status") String status);

    @Update("UPDATE job_search SET task_id=#{taskId},status='GENERATING',updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int prepareRetry(@Param("id") String id, @Param("taskId") String taskId);

    @Update("""
            UPDATE job_search SET status=#{status},query_plan_json=#{queryPlan},sources_json=#{sources},
              summary_json=#{summary},updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}
            """)
    int complete(@Param("id") String id, @Param("status") String status, @Param("queryPlan") String queryPlan,
                 @Param("sources") String sources, @Param("summary") String summary);

    @Select("SELECT * FROM job_search WHERE user_id=#{userId} ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<Map<String, Object>> page(@Param("userId") String userId, @Param("size") int size, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM job_search WHERE user_id=#{userId}")
    long count(@Param("userId") String userId);

    @Select("""
            <script>
            SELECT i.*,jv.title AS job_title,jv.company_name,jv.city_code AS job_city_code,
              jv.candidate_type AS job_candidate_type,jv.company_size AS job_company_size,
              jv.published_at,jv.fetched_at,jv.min_monthly_salary_cents,jv.max_monthly_salary_cents
            FROM job_match_item i
            JOIN job j ON j.id=i.job_id
            JOIN job_version jv ON jv.job_id=i.job_id AND jv.version_no=i.job_version
            WHERE i.batch_id=#{searchId}
            <if test="keyword != null and keyword != ''">
              AND (jv.title LIKE CONCAT('%',#{keyword},'%') OR jv.company_name LIKE CONCAT('%',#{keyword},'%'))
            </if>
            <if test="cityCode != null and cityCode != ''">AND jv.city_code=#{cityCode}</if>
            <if test="candidateType != null and candidateType != ''">AND jv.candidate_type=#{candidateType}</if>
            <if test="sizes != null and !sizes.isEmpty()">
              AND (
                jv.company_size IN
                <foreach collection="sizes" item="size" open="(" separator="," close=")">#{size}</foreach>
                <if test="includeUnknown">OR jv.company_size IS NULL OR jv.company_size='SIZE_UNKNOWN'</if>
              )
            </if>
            ORDER BY i.rank_no,i.job_id LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<Map<String, Object>> searchItems(@Param("searchId") String searchId, @Param("keyword") String keyword,
                                          @Param("cityCode") String cityCode,
                                          @Param("candidateType") String candidateType,
                                          @Param("sizes") List<String> sizes,
                                          @Param("includeUnknown") boolean includeUnknown,
                                          @Param("size") int size, @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM job_match_item i
            JOIN job_version jv ON jv.job_id=i.job_id AND jv.version_no=i.job_version
            WHERE i.batch_id=#{searchId}
            <if test="keyword != null and keyword != ''">
              AND (jv.title LIKE CONCAT('%',#{keyword},'%') OR jv.company_name LIKE CONCAT('%',#{keyword},'%'))
            </if>
            <if test="cityCode != null and cityCode != ''">AND jv.city_code=#{cityCode}</if>
            <if test="candidateType != null and candidateType != ''">AND jv.candidate_type=#{candidateType}</if>
            <if test="sizes != null and !sizes.isEmpty()">
              AND (
                jv.company_size IN
                <foreach collection="sizes" item="size" open="(" separator="," close=")">#{size}</foreach>
                <if test="includeUnknown">OR jv.company_size IS NULL OR jv.company_size='SIZE_UNKNOWN'</if>
              )
            </if>
            </script>
            """)
    long searchItemCount(@Param("searchId") String searchId, @Param("keyword") String keyword,
                         @Param("cityCode") String cityCode, @Param("candidateType") String candidateType,
                         @Param("sizes") List<String> sizes, @Param("includeUnknown") boolean includeUnknown);

    @Select("""
            SELECT i.id,i.batch_id,i.job_id,i.job_version,i.match_score,i.eligibility_status,
              i.reasons_json,i.source_snapshot_json,i.created_at,
              jv.title,jv.company_name,jv.city_code,jv.requirements_json
            FROM job_match_item i
            JOIN job_match_batch b ON b.id=i.batch_id
            JOIN job j ON j.id=i.job_id
            JOIN job_version jv ON jv.job_id=i.job_id AND jv.version_no=i.job_version
            LEFT JOIN job_source s ON s.id=j.source_id
            WHERE b.user_id=#{userId} AND b.status='READY' AND b.profile_version=#{profileVersion}
              AND i.eligibility_status<>'FAIL' AND j.status='ACTIVE' AND j.current_version=i.job_version
              AND b.created_at>=UTC_TIMESTAMP(3)-INTERVAL 90 DAY
              AND (j.visibility='PRIVATE' OR (s.verification_status='VERIFIED'
                AND j.verified_at>=UTC_TIMESTAMP(3)-INTERVAL 30 DAY))
            ORDER BY b.created_at DESC,i.rank_no,i.job_id LIMIT 100
            """)
    List<Map<String, Object>> matchItemOptions(@Param("userId") String userId,
                                               @Param("profileVersion") long profileVersion);
}