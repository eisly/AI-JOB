package com.aijob.backend.interview;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InterviewMapper {

    @Select("SELECT COUNT(*) FROM interview_session WHERE user_id=#{userId} AND status IN ('PREPARING','WAITING_ANSWER','GENERATING','ENDING')")
    int activeCount(@Param("userId") String userId);

    @Insert("""
            INSERT INTO interview_session (id,user_id,job_id,job_version,resume_version_id,profile_version,input_snapshot_json,
              status,question_limit,question_count_rule,pending_task_id,plan_item_id)
            VALUES (#{id},#{userId},#{jobId},#{jobVersion},#{resumeVersionId},#{profileVersion},#{snapshotJson},
              'PREPARING',#{questionLimit},#{questionCountRule},#{taskId},#{planItemId})
            """)
    int insertSession(@Param("id") String id, @Param("userId") String userId, @Param("jobId") String jobId,
                      @Param("jobVersion") int jobVersion, @Param("resumeVersionId") String resumeVersionId,
                      @Param("profileVersion") long profileVersion, @Param("snapshotJson") String snapshotJson,
                      @Param("questionLimit") int questionLimit, @Param("questionCountRule") String questionCountRule,
                      @Param("taskId") String taskId, @Param("planItemId") String planItemId);

    @Select("SELECT * FROM interview_session WHERE id=#{id} AND user_id=#{userId}")
    Map<String,Object> session(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT * FROM interview_session WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    Map<String,Object> lockSession(@Param("id") String id, @Param("userId") String userId);

    /** U09/U11：列表查询带出岗位名（按会话固定的 job_version）、报告完成类型与实际有效回答数，
     * 历史列表据此优先展示岗位标题，并区分正常完成/提前结束/无有效回答 */
    @Select("""
            SELECT i.*, jv.title AS job_title, ir.completion_type,
              (SELECT COUNT(*) FROM interview_turn_evaluation e
                WHERE e.interview_id=i.id AND e.overall_score IS NOT NULL) AS answered_count,
              (SELECT COUNT(*) FROM interview_message m
                WHERE m.interview_id=i.id AND m.role='ASSISTANT') AS asked_count
            FROM interview_session i
            LEFT JOIN job_version jv ON jv.job_id=i.job_id AND jv.version_no=i.job_version
            LEFT JOIN interview_report ir ON ir.interview_id=i.id
            WHERE i.user_id=#{userId}
            ORDER BY i.created_at DESC,i.id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<Map<String,Object>> page(@Param("userId") String userId, @Param("size") int size, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM interview_session WHERE user_id=#{userId}")
    long count(@Param("userId") String userId);

    @Update("""
            UPDATE interview_session SET status=#{status},pending_task_id=#{taskId},version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id}
            """)
    int updateTaskStatus(@Param("id") String id, @Param("status") String status, @Param("taskId") String taskId);

    @Update("""
            UPDATE interview_session SET status=#{status},pending_task_id=#{taskId},current_question_no=#{questionNo},
              current_follow_up_no=#{followUpNo},last_seq=#{lastSeq},version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id}
            """)
    int updateTurn(@Param("id") String id, @Param("status") String status, @Param("taskId") String taskId,
                   @Param("questionNo") int questionNo, @Param("followUpNo") int followUpNo, @Param("lastSeq") int lastSeq);

    @Update("""
            UPDATE interview_session SET status='COMPLETED',pending_task_id=#{taskId},ended_at=UTC_TIMESTAMP(3),
              version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='ENDING'
            """)
    int complete(@Param("id") String id, @Param("taskId") String taskId);

    @Insert("""
            INSERT INTO interview_message (id,interview_id,seq,role,answer_source,content,question_no,follow_up_no,
              question_message_id,skill_codes_json,task_id)
            VALUES (#{id},#{interviewId},#{seq},#{role},#{answerSource},#{content},#{questionNo},#{followUpNo},
              #{questionMessageId},#{skillCodesJson},#{taskId})
            """)
    int insertMessage(@Param("id") String id, @Param("interviewId") String interviewId, @Param("seq") int seq,
                      @Param("role") String role, @Param("answerSource") String answerSource,
                      @Param("content") String content, @Param("questionNo") int questionNo,
                      @Param("followUpNo") int followUpNo, @Param("questionMessageId") String questionMessageId,
                      @Param("skillCodesJson") String skillCodesJson, @Param("taskId") String taskId);

    @Update("""
            UPDATE interview_session SET mode='VOICE',updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND mode='TEXT'
            """)
    int markVoiceMode(@Param("id") String id);

    @Select("SELECT * FROM interview_message WHERE interview_id=#{interviewId} ORDER BY seq")
    List<Map<String,Object>> messages(@Param("interviewId") String interviewId);

    @Select("SELECT COALESCE(MAX(seq),0)+1 FROM interview_message WHERE interview_id=#{interviewId}")
    int nextSeq(@Param("interviewId") String interviewId);

    @Select("""
            SELECT m.* FROM interview_message m JOIN interview_session i ON i.id=m.interview_id
            WHERE m.id=#{id} AND m.interview_id=#{interviewId} AND i.user_id=#{userId}
            """)
    Map<String,Object> message(@Param("id") String id, @Param("interviewId") String interviewId, @Param("userId") String userId);

    @Select("SELECT * FROM interview_message WHERE interview_id=#{interviewId} AND role='ASSISTANT' ORDER BY seq DESC LIMIT 1")
    Map<String,Object> latestQuestion(@Param("interviewId") String interviewId);

    /** 总提问数量的权威值：主问题与追问都计为一条 ASSISTANT 消息，走 idx_interview_id_role_seq。 */
    @Select("SELECT COUNT(*) FROM interview_message WHERE interview_id=#{interviewId} AND role='ASSISTANT'")
    int askedQuestions(@Param("interviewId") String interviewId);

    @Select("SELECT * FROM interview_message WHERE question_message_id=#{questionMessageId} AND interview_id=#{interviewId}")
    Map<String,Object> answerByQuestion(@Param("questionMessageId") String questionMessageId,
                                        @Param("interviewId") String interviewId);

    @Insert("""
            INSERT INTO interview_turn_evaluation (id,interview_id,answer_message_id,task_id,evaluation_json,rubric_version,overall_score)
            VALUES (#{id},#{interviewId},#{answerMessageId},#{taskId},#{evaluationJson},#{rubricVersion},#{overallScore})
            """)
    int insertEvaluation(@Param("id") String id, @Param("interviewId") String interviewId,
                         @Param("answerMessageId") String answerMessageId, @Param("taskId") String taskId,
                         @Param("evaluationJson") String evaluationJson, @Param("rubricVersion") String rubricVersion,
                         @Param("overallScore") Object overallScore);

    @Select("SELECT * FROM interview_turn_evaluation WHERE interview_id=#{interviewId} ORDER BY created_at,id")
    List<Map<String,Object>> evaluations(@Param("interviewId") String interviewId);

    @Select("SELECT * FROM interview_turn_evaluation WHERE answer_message_id=#{answerMessageId}")
    Map<String,Object> evaluationByAnswer(@Param("answerMessageId") String answerMessageId);

    @Insert("""
            INSERT INTO interview_report (id,interview_id,task_id,report_version,report_json,overall_score,completion_type)
            VALUES (#{id},#{interviewId},#{taskId},1,#{reportJson},#{overallScore},#{completionType})
            """)
    int insertReport(@Param("id") String id, @Param("interviewId") String interviewId, @Param("taskId") String taskId,
                     @Param("reportJson") String reportJson, @Param("overallScore") Object overallScore,
                     @Param("completionType") String completionType);

    @Select("SELECT r.*,i.user_id,i.status AS interview_status,i.pending_task_id FROM interview_report r JOIN interview_session i ON i.id=r.interview_id WHERE r.interview_id=#{interviewId} AND i.user_id=#{userId}")
    Map<String,Object> report(@Param("interviewId") String interviewId, @Param("userId") String userId);
}