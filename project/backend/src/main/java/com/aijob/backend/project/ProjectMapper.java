package com.aijob.backend.project;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProjectMapper {

    @Insert("""
            INSERT INTO project_recommendation (id,user_id,match_item_id,task_id,profile_version)
            VALUES (#{id},#{userId},#{matchItemId},#{taskId},#{profileVersion})
            """)
    int insertRecommendation(@Param("id") String id, @Param("userId") String userId,
                             @Param("matchItemId") String matchItemId, @Param("taskId") String taskId,
                             @Param("profileVersion") long profileVersion);

    @Select("SELECT * FROM project_recommendation WHERE id=#{id} AND user_id=#{userId}")
    Map<String, Object> recommendation(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT * FROM project_recommendation WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    Map<String, Object> lockRecommendation(@Param("id") String id, @Param("userId") String userId);

    @Update("UPDATE project_recommendation SET status=#{status},task_id=#{taskId},updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND (task_id=#{taskId} OR status='FAILED')")
    int updateRecommendation(@Param("id") String id, @Param("status") String status, @Param("taskId") String taskId);

    @Update("""
            UPDATE project_recommendation SET status=#{status},task_id=#{taskId},items_json=#{itemsJson},
              updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND task_id=#{taskId} AND status='GENERATING'
            """)
    int completeRecommendation(@Param("id") String id, @Param("status") String status,
                               @Param("taskId") String taskId, @Param("itemsJson") String itemsJson);

    @Insert("""
            INSERT INTO project_recommendation_item
              (id,recommendation_id,user_id,title,summary,skill_codes_json,difficulty,estimated_weeks,reason,deliverables_json)
            VALUES (#{id},#{recommendationId},#{userId},#{title},#{summary},#{skillCodesJson},#{difficulty},
              #{estimatedWeeks},#{reason},#{deliverablesJson})
            """)
    int insertRecommendationItem(@Param("id") String id, @Param("recommendationId") String recommendationId,
                                 @Param("userId") String userId, @Param("title") String title,
                                 @Param("summary") String summary, @Param("skillCodesJson") String skillCodesJson,
                                 @Param("difficulty") String difficulty, @Param("estimatedWeeks") int estimatedWeeks,
                                 @Param("reason") String reason, @Param("deliverablesJson") String deliverablesJson);

    @Select("SELECT * FROM project_recommendation_item WHERE recommendation_id=#{recommendationId} ORDER BY created_at,id")
    List<Map<String, Object>> recommendationItems(@Param("recommendationId") String recommendationId);

    @Select("""
            SELECT i.*,r.profile_version,r.status AS recommendation_status
            FROM project_recommendation_item i JOIN project_recommendation r ON r.id=i.recommendation_id
            WHERE i.id=#{id} AND i.user_id=#{userId} AND r.user_id=#{userId}
            """)
    Map<String, Object> recommendationItem(@Param("id") String id, @Param("userId") String userId);

    @Insert("""
            INSERT INTO project (id,user_id,recommendation_item_id,task_id,weekly_hours,skill_codes_json)
            VALUES (#{id},#{userId},#{recommendationItemId},#{taskId},#{weeklyHours},#{skillCodesJson})
            """)
    int insertProject(@Param("id") String id, @Param("userId") String userId,
                      @Param("recommendationItemId") String recommendationItemId, @Param("taskId") String taskId,
                      @Param("weeklyHours") int weeklyHours, @Param("skillCodesJson") String skillCodesJson);

    @Select("SELECT * FROM project WHERE id=#{id} AND user_id=#{userId}")
    Map<String, Object> project(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT * FROM project WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    Map<String, Object> lockProject(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT * FROM project WHERE user_id=#{userId} ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<Map<String, Object>> projectPage(@Param("userId") String userId, @Param("size") int size,
                                          @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM project WHERE user_id=#{userId}")
    long projectCount(@Param("userId") String userId);

    @Update("UPDATE project SET status=#{status},task_id=#{taskId},updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND (task_id=#{taskId} OR status='FAILED')")
    int updateProjectTask(@Param("id") String id, @Param("status") String status, @Param("taskId") String taskId);

    @Update("""
            UPDATE project SET title=#{title},summary=#{summary},status=#{status},task_id=#{taskId},
              updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND task_id=#{taskId} AND status='GENERATING'
            """)
    int completeProject(@Param("id") String id, @Param("title") String title, @Param("summary") String summary,
                        @Param("status") String status, @Param("taskId") String taskId);

    @Insert("""
            INSERT INTO project_milestone (id,project_id,seq,title,description)
            VALUES (#{id},#{projectId},#{seq},#{title},#{description})
            """)
    int insertMilestone(@Param("id") String id, @Param("projectId") String projectId, @Param("seq") int seq,
                        @Param("title") String title, @Param("description") String description);

    @Select("SELECT * FROM project_milestone WHERE project_id=#{projectId} ORDER BY seq")
    List<Map<String, Object>> milestones(@Param("projectId") String projectId);

    @Insert("""
            INSERT INTO project_task (id,project_id,milestone_id,title,description,estimated_hours)
            VALUES (#{id},#{projectId},#{milestoneId},#{title},#{description},#{estimatedHours})
            """)
    int insertTask(@Param("id") String id, @Param("projectId") String projectId,
                   @Param("milestoneId") String milestoneId, @Param("title") String title,
                   @Param("description") String description, @Param("estimatedHours") BigDecimal estimatedHours);

    @Select("""
            SELECT t.* FROM project_task t JOIN project_milestone m ON m.id=t.milestone_id
            WHERE t.project_id=#{projectId} ORDER BY m.seq,t.created_at,t.id
            """)
    List<Map<String, Object>> tasks(@Param("projectId") String projectId);

    @Select("SELECT * FROM project_task WHERE id=#{taskId} AND project_id=#{projectId}")
    Map<String, Object> task(@Param("projectId") String projectId, @Param("taskId") String taskId);

    @Select("""
            SELECT t.* FROM project_task t JOIN project p ON p.id=t.project_id
            WHERE t.id=#{taskId} AND t.project_id=#{projectId} AND p.user_id=#{userId} FOR UPDATE
            """)
    Map<String, Object> lockTask(@Param("projectId") String projectId, @Param("taskId") String taskId,
                                 @Param("userId") String userId);

    @Update("UPDATE project_task SET status=#{status},updated_at=UTC_TIMESTAMP(3) WHERE id=#{taskId} AND project_id=#{projectId}")
    int updateTaskStatus(@Param("projectId") String projectId, @Param("taskId") String taskId,
                         @Param("status") String status);

    @Select("""
            SELECT COUNT(*) AS total,COALESCE(SUM(status='COMPLETED'),0) AS completed
            FROM project_task WHERE project_id=#{projectId}
            """)
    Map<String, Object> taskCounts(@Param("projectId") String projectId);

    @Update("""
            UPDATE project SET progress=#{progress},status=#{status},version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND user_id=#{userId} AND version=#{expectedVersion}
            """)
    int updateProgress(@Param("id") String id, @Param("userId") String userId,
                       @Param("expectedVersion") int expectedVersion, @Param("progress") int progress,
                       @Param("status") String status);

    @Insert("""
            INSERT INTO project_deliverable (id,project_id,task_id,title,url,notes)
            VALUES (#{id},#{projectId},#{taskId},#{title},#{url},#{notes})
            """)
    int insertDeliverable(@Param("id") String id, @Param("projectId") String projectId,
                          @Param("taskId") String taskId, @Param("title") String title,
                          @Param("url") String url, @Param("notes") String notes);

    @Select("SELECT * FROM project_deliverable WHERE project_id=#{projectId} ORDER BY created_at,id")
    List<Map<String, Object>> deliverables(@Param("projectId") String projectId);
}