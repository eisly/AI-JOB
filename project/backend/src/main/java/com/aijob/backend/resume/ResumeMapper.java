package com.aijob.backend.resume;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ResumeMapper {

    @Insert("INSERT INTO resume (id,user_id,title) VALUES (#{id},#{userId},#{title})")
    int insertResume(@Param("id") String id, @Param("userId") String userId, @Param("title") String title);

    @Select("SELECT * FROM resume WHERE id=#{id} AND user_id=#{userId} AND deleted_at IS NULL")
    Map<String,Object> resume(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT * FROM resume WHERE id=#{id} AND user_id=#{userId} AND deleted_at IS NULL FOR UPDATE")
    Map<String,Object> lockResume(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT COALESCE(MAX(version_no),0) AS max_version FROM resume_version WHERE resume_id=#{resumeId} FOR UPDATE")
    int maxVersion(@Param("resumeId") String resumeId);

    @Insert("""
            INSERT INTO resume_version (id,resume_id,user_id,version_no,file_name,storage_key,mime_type,size_bytes,
              sha256,status,extracted_text,extracted_text_sha256)
            VALUES (#{id},#{resumeId},#{userId},#{versionNo},#{fileName},#{storageKey},#{mimeType},#{sizeBytes},
              UNHEX(#{sha256}),'UPLOADED',#{extractedText},#{extractedTextSha256})
            """)
    int insertVersion(@Param("id") String id, @Param("resumeId") String resumeId, @Param("userId") String userId,
                      @Param("versionNo") int versionNo, @Param("fileName") String fileName,
                      @Param("storageKey") String storageKey, @Param("mimeType") String mimeType,
                      @Param("sizeBytes") long sizeBytes, @Param("sha256") String sha256,
                      @Param("extractedText") String extractedText, @Param("extractedTextSha256") String extractedTextSha256);

    @Update("UPDATE resume SET latest_version_id=#{versionId},updated_at=UTC_TIMESTAMP(3) WHERE id=#{resumeId}")
    int updateLatest(@Param("resumeId") String resumeId, @Param("versionId") String versionId);

    @Select("SELECT * FROM resume_version WHERE id=#{id} AND user_id=#{userId} AND deleted_at IS NULL")
    Map<String,Object> version(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT * FROM resume_version WHERE id=#{id} AND user_id=#{userId} AND deleted_at IS NULL FOR UPDATE")
    Map<String,Object> lockVersion(@Param("id") String id, @Param("userId") String userId);

    @Select("""
            SELECT r.id,r.title,r.created_at,v.id AS version_id,v.resume_id,v.version_no,v.file_name,v.mime_type,
              v.size_bytes,v.status,v.parsed_facts_json,v.confirmed_facts_json,v.created_at AS version_created_at,
              v.storage_key,v.extracted_text,v.extracted_text_sha256
            FROM resume r JOIN resume_version v ON v.id=r.latest_version_id
            WHERE r.user_id=#{userId} AND r.deleted_at IS NULL ORDER BY r.created_at DESC,r.id DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<Map<String,Object>> page(@Param("userId") String userId, @Param("size") int size, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM resume WHERE user_id=#{userId} AND deleted_at IS NULL")
    long count(@Param("userId") String userId);

    @Select("""
            SELECT id,resume_id,version_no,file_name,mime_type,size_bytes,status,parsed_facts_json,confirmed_facts_json,
              storage_key,extracted_text,extracted_text_sha256,created_at FROM resume_version
            WHERE resume_id=#{resumeId} AND user_id=#{userId} AND deleted_at IS NULL ORDER BY version_no DESC
            """)
    List<Map<String,Object>> versions(@Param("resumeId") String resumeId, @Param("userId") String userId);

    @Update("UPDATE resume_version SET status=#{status},updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int updateStatus(@Param("id") String id, @Param("status") String status);

    @Update("""
            UPDATE resume_version SET parsed_facts_json=#{factsJson},parse_meta_json=#{metaJson},status='PARSED',
              updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='PARSING'
            """)
    int parsed(@Param("id") String id, @Param("factsJson") String factsJson, @Param("metaJson") String metaJson);

    @Update("""
            UPDATE resume_version SET confirmed_facts_json=#{factsJson},status='CONFIRMED',confirmed_at=UTC_TIMESTAMP(3),
              updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='PARSED'
            """)
    int confirm(@Param("id") String id, @Param("factsJson") String factsJson);

    @Select("""
            SELECT
              (SELECT COUNT(*) FROM interview_session i JOIN resume_version rv ON rv.id=i.resume_version_id
                WHERE rv.resume_id=#{resumeId} AND i.status IN ('PREPARING','WAITING_ANSWER','GENERATING','ENDING')) +
              (SELECT COUNT(*) FROM ai_task t JOIN resume_version rv ON rv.id=t.resource_id
                WHERE rv.resume_id=#{resumeId} AND t.resource_type='RESUME_VERSION'
                  AND t.status IN ('QUEUED','RUNNING','CANCEL_REQUESTED')) AS active_refs
            """)
    int activeReferences(@Param("resumeId") String resumeId);

    @Update("UPDATE resume SET deleted_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) WHERE id=#{resumeId} AND user_id=#{userId}")
    int deleteResume(@Param("resumeId") String resumeId, @Param("userId") String userId);

    @Update("UPDATE resume_version SET status='DELETED',deleted_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) WHERE resume_id=#{resumeId}")
    int deleteVersions(@Param("resumeId") String resumeId);
}