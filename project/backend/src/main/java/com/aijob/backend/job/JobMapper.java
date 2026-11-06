package com.aijob.backend.job;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface JobMapper {

    @Insert("INSERT INTO job_source (id,name,type,verification_status) VALUES (#{id},'个人 JD','USER_INPUT','UNVERIFIED')")
    int insertPrivateSource(@Param("id") String id);

    @Insert("""
            INSERT INTO job (id,source_id,owner_user_id,visibility,current_version,status,source_url)
            VALUES (#{id},#{sourceId},#{ownerUserId},'PRIVATE',1,'ANALYZING',#{sourceUrl})
            """)
    int insertPrivateJob(@Param("id") String id, @Param("sourceId") String sourceId,
                         @Param("ownerUserId") String ownerUserId, @Param("sourceUrl") String sourceUrl);

    @Insert("""
            INSERT INTO job_version (id,job_id,version_no,title,company_name,city_code,role_code,candidate_type,
              jd_text,content_hash,requirements_json)
            VALUES (#{id},#{jobId},1,#{title},#{companyName},NULL,#{roleCode},'EXPERIENCED',#{jdText},
              UNHEX(#{contentHash}),NULL)
            """)
    int insertJobVersion(@Param("id") String id, @Param("jobId") String jobId, @Param("title") String title,
                         @Param("companyName") String companyName, @Param("roleCode") String roleCode,
                         @Param("jdText") String jdText, @Param("contentHash") String contentHash);

    String COLUMNS = """
            j.id,j.current_version AS job_version,j.owner_user_id,j.visibility,j.status,j.source_url,j.verified_at,
            j.created_at,jv.id AS job_version_id,jv.title,jv.company_name,jv.city_code,jv.role_code,jv.candidate_type,
            jv.min_monthly_salary_cents,jv.max_monthly_salary_cents,jv.jd_text,jv.requirements_json,
            s.id AS source_id,s.name AS source_name,s.type AS source_type,s.base_url AS source_base_url,
            s.verification_status,s.verified_at AS source_verified_at
            """;

    @Select("SELECT " + COLUMNS + " FROM job j JOIN job_version jv ON jv.job_id=j.id AND jv.version_no=j.current_version JOIN job_source s ON s.id=j.source_id WHERE j.id=#{jobId} AND (j.visibility='PUBLIC' OR j.owner_user_id=#{userId})")
    Map<String,Object> job(@Param("jobId") String jobId, @Param("userId") String userId);

    @Select("SELECT " + COLUMNS + " FROM job j JOIN job_version jv ON jv.job_id=j.id AND jv.version_no=j.current_version JOIN job_source s ON s.id=j.source_id WHERE j.id=#{jobId} AND (j.visibility='PUBLIC' OR j.owner_user_id=#{userId}) FOR UPDATE")
    Map<String,Object> lockJob(@Param("jobId") String jobId, @Param("userId") String userId);

    @Select("""
            <script>
            SELECT
            """ + COLUMNS + """
            FROM job j JOIN job_version jv ON jv.job_id=j.id AND jv.version_no=j.current_version JOIN job_source s ON s.id=j.source_id
            WHERE j.visibility='PUBLIC' AND j.status='ACTIVE' AND s.verification_status='VERIFIED'
              AND j.verified_at>=UTC_TIMESTAMP(3)-INTERVAL 30 DAY
            <if test="q != null and q != ''">AND (jv.title LIKE CONCAT('%',#{q},'%') OR jv.company_name LIKE CONCAT('%',#{q},'%'))</if>
            <if test="cityCode != null">AND jv.city_code=#{cityCode}</if>
            <if test="candidateType != null">AND jv.candidate_type=#{candidateType}</if>
            ORDER BY j.created_at DESC,j.id DESC LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<Map<String,Object>> publicPage(@Param("userId") String userId, @Param("q") String q,
                                        @Param("cityCode") String cityCode, @Param("candidateType") String candidateType,
                                        @Param("size") int size, @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM job j JOIN job_version jv ON jv.job_id=j.id AND jv.version_no=j.current_version
            JOIN job_source s ON s.id=j.source_id
            WHERE j.visibility='PUBLIC' AND j.status='ACTIVE' AND s.verification_status='VERIFIED'
              AND j.verified_at>=UTC_TIMESTAMP(3)-INTERVAL 30 DAY
            <if test="q != null and q != ''">AND (jv.title LIKE CONCAT('%',#{q},'%') OR jv.company_name LIKE CONCAT('%',#{q},'%'))</if>
            <if test="cityCode != null">AND jv.city_code=#{cityCode}</if>
            <if test="candidateType != null">AND jv.candidate_type=#{candidateType}</if>
            </script>
            """)
    long publicCount(@Param("q") String q, @Param("cityCode") String cityCode,
                     @Param("candidateType") String candidateType);

    @Select("SELECT " + COLUMNS + " FROM job j JOIN job_version jv ON jv.job_id=j.id AND jv.version_no=j.current_version JOIN job_source s ON s.id=j.source_id WHERE j.owner_user_id=#{userId} ORDER BY j.created_at DESC,j.id DESC LIMIT #{size} OFFSET #{offset}")
    List<Map<String,Object>> privatePage(@Param("userId") String userId, @Param("size") int size, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM job WHERE owner_user_id=#{userId}")
    long privateCount(@Param("userId") String userId);

    @Select("SELECT " + COLUMNS + " FROM job j JOIN job_version jv ON jv.job_id=j.id AND jv.version_no=j.current_version JOIN job_source s ON s.id=j.source_id WHERE j.visibility='PUBLIC' AND j.status='ACTIVE' AND s.verification_status='VERIFIED' AND j.verified_at>=UTC_TIMESTAMP(3)-INTERVAL 30 DAY AND jv.requirements_json IS NOT NULL ORDER BY j.created_at DESC,j.id DESC LIMIT 50")
    List<Map<String,Object>> defaultCandidates();

    @Update("UPDATE job SET status=#{status},updated_at=UTC_TIMESTAMP(3) WHERE id=#{jobId}")
    int updateJobStatus(@Param("jobId") String jobId, @Param("status") String status);

    @Update("""
            UPDATE job_version SET requirements_json=#{requirementsJson},analysis_meta_json=#{metaJson},
              min_monthly_salary_cents=#{minSalary},max_monthly_salary_cents=#{maxSalary},role_code=#{roleCode}
            WHERE job_id=#{jobId} AND version_no=#{version}
            """)
    int analyzed(@Param("jobId") String jobId, @Param("version") int version,
                 @Param("requirementsJson") String requirementsJson, @Param("metaJson") String metaJson,
                 @Param("minSalary") Integer minSalary, @Param("maxSalary") Integer maxSalary,
                 @Param("roleCode") String roleCode);

    @Insert("INSERT IGNORE INTO job_favorite (id,user_id,job_id) VALUES (#{id},#{userId},#{jobId})")
    int favorite(@Param("id") String id, @Param("userId") String userId, @Param("jobId") String jobId);

    @Delete("DELETE FROM job_favorite WHERE user_id=#{userId} AND job_id=#{jobId}")
    int unfavorite(@Param("userId") String userId, @Param("jobId") String jobId);

    @Select("SELECT COUNT(*) FROM job_favorite WHERE user_id=#{userId} AND job_id=#{jobId}")
    int isFavorite(@Param("userId") String userId, @Param("jobId") String jobId);

    @Select("SELECT " + COLUMNS + " FROM job_favorite f JOIN job j ON j.id=f.job_id JOIN job_version jv ON jv.job_id=j.id AND jv.version_no=j.current_version JOIN job_source s ON s.id=j.source_id WHERE f.user_id=#{userId} ORDER BY f.created_at DESC,f.id DESC LIMIT #{size} OFFSET #{offset}")
    List<Map<String,Object>> favoritePage(@Param("userId") String userId, @Param("size") int size, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM job_favorite WHERE user_id=#{userId}")
    long favoriteCount(@Param("userId") String userId);

    @Insert("""
            INSERT INTO job_match_batch (id,user_id,task_id,profile_version,resume_version_id,input_snapshot_json)
            VALUES (#{id},#{userId},#{taskId},#{profileVersion},#{resumeVersionId},#{snapshotJson})
            """)
    int insertBatch(@Param("id") String id, @Param("userId") String userId, @Param("taskId") String taskId,
                    @Param("profileVersion") long profileVersion, @Param("resumeVersionId") String resumeVersionId,
                    @Param("snapshotJson") String snapshotJson);

    @Select("SELECT * FROM job_match_batch WHERE id=#{id} AND user_id=#{userId}")
    Map<String,Object> batch(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT * FROM job_match_batch WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    Map<String,Object> lockBatch(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT * FROM job_match_batch WHERE user_id=#{userId} ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<Map<String,Object>> batchPage(@Param("userId") String userId, @Param("size") int size, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM job_match_batch WHERE user_id=#{userId}")
    long batchCount(@Param("userId") String userId);

    @Update("UPDATE job_match_batch SET status=#{status},task_id=#{taskId},updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int updateBatch(@Param("id") String id, @Param("status") String status, @Param("taskId") String taskId);

    @Insert("""
            INSERT INTO job_match_item (id,batch_id,job_id,job_version,rank_no,eligibility_status,eligibility_json,
              match_score,components_json,gaps_json,reasons_json,source_snapshot_json)
            VALUES (#{id},#{batchId},#{jobId},#{jobVersion},#{rankNo},#{eligibilityStatus},#{eligibilityJson},
              #{matchScore},#{componentsJson},#{gapsJson},#{reasonsJson},#{sourceSnapshotJson})
            """)
    int insertItem(@Param("id") String id, @Param("batchId") String batchId, @Param("jobId") String jobId,
                   @Param("jobVersion") int jobVersion, @Param("rankNo") int rankNo,
                   @Param("eligibilityStatus") String eligibilityStatus, @Param("eligibilityJson") String eligibilityJson,
                   @Param("matchScore") Object matchScore, @Param("componentsJson") String componentsJson,
                   @Param("gapsJson") String gapsJson, @Param("reasonsJson") String reasonsJson,
                   @Param("sourceSnapshotJson") String sourceSnapshotJson);

    @Select("SELECT * FROM job_match_item WHERE batch_id=#{batchId} ORDER BY rank_no,job_id")
    List<Map<String,Object>> items(@Param("batchId") String batchId);

    @Select("""
            SELECT i.*,b.user_id,b.profile_version,b.status AS batch_status,b.resume_version_id,b.input_snapshot_json,
              b.created_at AS batch_created_at
            FROM job_match_item i JOIN job_match_batch b ON b.id=i.batch_id
            WHERE i.id=#{itemId} AND b.user_id=#{userId}
            """)
    Map<String,Object> matchItem(@Param("itemId") String itemId, @Param("userId") String userId);

    @Select("""
            SELECT COUNT(*) FROM job_match_item i JOIN job j ON j.id=i.job_id JOIN job_source s ON s.id=j.source_id
            WHERE i.batch_id=#{batchId} AND (i.job_version<>j.current_version OR j.status='CLOSED'
              OR (j.visibility='PUBLIC' AND (s.verification_status<>'VERIFIED'
                OR j.verified_at IS NULL OR j.verified_at<UTC_TIMESTAMP(3)-INTERVAL 30 DAY)))
            """)
    int staleItems(@Param("batchId") String batchId);
}
