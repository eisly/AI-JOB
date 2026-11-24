package com.aijob.backend.common;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 审计日志写入（复用既有 audit_log 表，不新建表）。
 */
@Mapper
public interface AuditLogMapper {

    @Insert("""
            INSERT INTO audit_log (id, actor_id, action, resource_type, resource_id, trace_id, details_json)
            VALUES (#{id}, #{actorId}, #{action}, #{resourceType}, #{resourceId}, #{traceId}, #{detailsJson})
            """)
    int insert(@Param("id") String id,
               @Param("actorId") String actorId,
               @Param("action") String action,
               @Param("resourceType") String resourceType,
               @Param("resourceId") String resourceId,
               @Param("traceId") String traceId,
               @Param("detailsJson") String detailsJson);
}