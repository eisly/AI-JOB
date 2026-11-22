package com.aijob.backend.learning;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LearningMapper {

    @Insert("""
            INSERT INTO learning_plan (id,user_id,match_item_id,source,resume_version_id,target_direction,
              supersedes_plan_id,generation_no,basis_json,task_id,profile_version,daily_minutes,days)
            VALUES (#{id},#{userId},#{matchItemId,jdbcType=VARCHAR},#{source},
              #{resumeVersionId,jdbcType=VARCHAR},#{targetDirection,jdbcType=VARCHAR},
              #{supersedesPlanId,jdbcType=VARCHAR},#{generationNo},#{basisJson,jdbcType=VARCHAR},
              #{taskId},#{profileVersion},#{dailyMinutes},#{days})
            """)
    int insertPlan(@Param("id") String id, @Param("userId") String userId,
                   @Param("matchItemId") String matchItemId, @Param("source") String source,
                   @Param("resumeVersionId") String resumeVersionId, @Param("targetDirection") String targetDirection,
                   @Param("supersedesPlanId") String supersedesPlanId, @Param("generationNo") int generationNo,
                   @Param("basisJson") String basisJson, @Param("taskId") String taskId,
                   @Param("profileVersion") long profileVersion, @Param("dailyMinutes") int dailyMinutes,
                   @Param("days") int days);

    @Select("""
            SELECT * FROM learning_plan WHERE user_id=#{userId} AND source=#{source}
            ORDER BY created_at DESC,id DESC LIMIT 1
            """)
    Map<String,Object> latestPlan(@Param("userId") String userId, @Param("source") String source);

    @Select("SELECT COUNT(*) FROM learning_plan WHERE user_id=#{userId} AND source=#{source}")
    int planGeneration(@Param("userId") String userId, @Param("source") String source);

    @Update("UPDATE learning_plan SET basis_json=#{basisJson,jdbcType=VARCHAR},updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int updatePlanBasis(@Param("id") String id, @Param("basisJson") String basisJson);

    @Select("SELECT * FROM learning_plan WHERE id=#{id} AND user_id=#{userId}")
    Map<String,Object> plan(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT * FROM learning_plan WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    Map<String,Object> lockPlan(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT * FROM learning_plan WHERE user_id=#{userId} ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<Map<String,Object>> planPage(@Param("userId") String userId, @Param("size") int size, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM learning_plan WHERE user_id=#{userId}")
    long planCount(@Param("userId") String userId);

    @Update("UPDATE learning_plan SET status=#{status},task_id=#{taskId},title=#{title},updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int updatePlan(@Param("id") String id, @Param("status") String status,
                   @Param("taskId") String taskId, @Param("title") String title);

    @Insert("""
            INSERT INTO learning_plan_item (id,plan_id,seq,skill_code,title,kind,category,objective,reason,`method`,
              completion_criteria,evidence_status,evidence_quote,resource_links_json,estimated_minutes,resource_refs_json)
            VALUES (#{id},#{planId},#{seq},#{skillCode},#{title},#{kind},#{category},#{objective},#{reason},#{method},
              #{completionCriteria},#{evidenceStatus},#{evidenceQuote,jdbcType=VARCHAR},
              #{resourceLinksJson,jdbcType=VARCHAR},#{estimatedMinutes},#{resourceRefsJson})
            """)
    int insertItem(@Param("id") String id, @Param("planId") String planId, @Param("seq") int seq,
                   @Param("skillCode") String skillCode, @Param("title") String title, @Param("kind") String kind,
                   @Param("category") String category, @Param("objective") String objective,
                   @Param("reason") String reason, @Param("method") String method,
                   @Param("completionCriteria") String completionCriteria, @Param("evidenceStatus") String evidenceStatus,
                   @Param("evidenceQuote") String evidenceQuote, @Param("resourceLinksJson") String resourceLinksJson,
                   @Param("estimatedMinutes") int estimatedMinutes, @Param("resourceRefsJson") String resourceRefsJson);

    @Select("SELECT * FROM learning_plan_item WHERE plan_id=#{planId} ORDER BY seq")
    List<Map<String,Object>> items(@Param("planId") String planId);

    @Select("""
            SELECT i.*,p.user_id,p.status AS plan_status,p.profile_version,p.match_item_id FROM learning_plan_item i
            JOIN learning_plan p ON p.id=i.plan_id WHERE i.id=#{itemId} AND p.id=#{planId} AND p.user_id=#{userId}
            """)
    Map<String,Object> item(@Param("planId") String planId, @Param("itemId") String itemId, @Param("userId") String userId);

    @Select("""
            SELECT i.*,p.user_id,p.status AS plan_status,p.profile_version,p.match_item_id FROM learning_plan_item i
            JOIN learning_plan p ON p.id=i.plan_id WHERE i.id=#{itemId} AND p.id=#{planId} AND p.user_id=#{userId} FOR UPDATE
            """)
    Map<String,Object> lockItem(@Param("planId") String planId, @Param("itemId") String itemId, @Param("userId") String userId);

    @Select("SELECT i.*,p.user_id,p.status AS plan_status,p.profile_version FROM learning_plan_item i JOIN learning_plan p ON p.id=i.plan_id WHERE i.id=#{itemId} AND p.user_id=#{userId}")
    Map<String,Object> itemById(@Param("itemId") String itemId, @Param("userId") String userId);

    @Update("""
            UPDATE learning_plan_item SET status=#{status},completed_at=CASE WHEN #{status}='COMPLETED' THEN COALESCE(completed_at,UTC_TIMESTAMP(3)) ELSE NULL END,
              updated_at=UTC_TIMESTAMP(3) WHERE id=#{itemId}
            """)
    int updateItemStatus(@Param("itemId") String itemId, @Param("status") String status);

    @Update("""
            UPDATE learning_plan SET status='COMPLETED',updated_at=UTC_TIMESTAMP(3) WHERE id=#{planId}
              AND NOT EXISTS (SELECT 1 FROM learning_plan_item i WHERE i.plan_id=#{planId} AND i.status<>'COMPLETED')
            """)
    int completePlan(@Param("planId") String planId);

    @Insert("""
            INSERT INTO learning_session (id,user_id,plan_id,plan_item_id,question_id,question_version,status)
            VALUES (#{id},#{userId},#{planId},#{planItemId},#{questionId},#{questionVersion},'PREPARING')
            """)
    int insertSession(@Param("id") String id, @Param("userId") String userId, @Param("planId") String planId,
                      @Param("planItemId") String planItemId, @Param("questionId") String questionId,
                      @Param("questionVersion") Integer questionVersion);

    @Select("SELECT * FROM learning_session WHERE id=#{id} AND user_id=#{userId}")
    Map<String,Object> session(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT * FROM learning_session WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    Map<String,Object> lockSession(@Param("id") String id, @Param("userId") String userId);

    @Select("""
            SELECT * FROM learning_session WHERE user_id=#{userId} AND plan_item_id=#{planItemId}
              AND status IN ('PREPARING','ACTIVE','EVALUATING') ORDER BY created_at DESC LIMIT 1
            """)
    Map<String,Object> activeSession(@Param("userId") String userId, @Param("planItemId") String planItemId);

    @Select("SELECT question_id FROM learning_session WHERE user_id=#{userId} AND question_id IS NOT NULL")
    List<String> usedQuestionIds(@Param("userId") String userId);

    /** 已用题的题干列表：选题与生成新题时按归一化题干排除等价重复 */
    @Select("""
            SELECT q.stem FROM learning_session s JOIN question q ON q.id=s.question_id
            WHERE s.user_id=#{userId} AND s.question_id IS NOT NULL
            """)
    List<String> usedQuestionStems(@Param("userId") String userId);

    @Update("UPDATE learning_session SET status=#{status},version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int updateSessionStatus(@Param("id") String id, @Param("status") String status);

    @Update("""
            UPDATE learning_session SET status='ACTIVE',question_id=#{questionId},question_version=#{questionVersion},
              version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='PREPARING'
            """)
    int questionReady(@Param("id") String id, @Param("questionId") String questionId,
                      @Param("questionVersion") int questionVersion);

    @Update("UPDATE learning_session SET last_answer_id=#{answerId},status='EVALUATING',version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int answerSubmitted(@Param("id") String id, @Param("answerId") String answerId);

    @Select("SELECT * FROM question WHERE id=#{id} AND status='ACTIVE'")
    Map<String,Object> question(@Param("id") String id);

    /** 候选题仅取真实环境的 ACTIVE 题：排除 FAKE（fake 模型生成）与 LEGACY（V12 前历史生成题，来源不可追溯），
     * 真实用户不得收到未批准的模拟题或来源不明的历史生成题（T01）。 */
    @Select("""
            SELECT * FROM question WHERE status='ACTIVE' AND generation_env NOT IN ('FAKE','LEGACY')
            ORDER BY created_at ASC,id ASC LIMIT 50
            """)
    List<Map<String,Object>> questionCandidates();

    @Insert("""
            INSERT INTO question (id,root_question_id,question_version,stem,type,skill_codes_json,difficulty,source_type,
              reference_answer,rubric_json,citations_json,generated_task_id,created_by,generation_env)
            VALUES (#{id},#{id},1,#{stem},#{type},#{skillCodesJson},#{difficulty},'GENERATED',#{referenceAnswer},
              #{rubricJson},#{citationsJson},#{taskId},#{userId},#{generationEnv})
            """)
    int insertGeneratedQuestion(@Param("id") String id, @Param("stem") String stem, @Param("type") String type,
                                @Param("skillCodesJson") String skillCodesJson, @Param("difficulty") String difficulty,
                                @Param("referenceAnswer") String referenceAnswer, @Param("rubricJson") String rubricJson,
                                @Param("citationsJson") String citationsJson, @Param("taskId") String taskId,
                                @Param("userId") String userId, @Param("generationEnv") String generationEnv);

    @Insert("""
            INSERT INTO learning_answer (id,session_id,user_id,content,content_hash,task_id)
            VALUES (#{id},#{sessionId},#{userId},#{content},UNHEX(#{contentHash}),#{taskId})
            """)
    int insertAnswer(@Param("id") String id, @Param("sessionId") String sessionId, @Param("userId") String userId,
                     @Param("content") String content, @Param("contentHash") String contentHash,
                     @Param("taskId") String taskId);

    @Select("SELECT * FROM learning_answer WHERE id=#{id} AND user_id=#{userId}")
    Map<String,Object> answer(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT * FROM learning_answer WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    Map<String,Object> lockAnswer(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT * FROM learning_answer WHERE session_id=#{sessionId}")
    Map<String,Object> sessionAnswer(@Param("sessionId") String sessionId);

    @Update("UPDATE learning_answer SET status=#{status},task_id=#{taskId},updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int updateAnswerTask(@Param("id") String id, @Param("status") String status, @Param("taskId") String taskId);

    @Update("""
            UPDATE learning_answer SET status='EVALUATED',evaluation_json=#{evaluationJson},evaluated_at=UTC_TIMESTAMP(3),
              updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='EVALUATING'
            """)
    int evaluated(@Param("id") String id, @Param("evaluationJson") String evaluationJson);

    @Insert("""
            INSERT INTO user_mistake (id,user_id,question_id,last_answer_id,wrong_count,resolved,cause)
            VALUES (#{id},#{userId},#{questionId},#{answerId},1,0,#{cause})
            ON DUPLICATE KEY UPDATE last_answer_id=VALUES(last_answer_id),wrong_count=wrong_count+1,
              resolved=0,cause=VALUES(cause),updated_at=UTC_TIMESTAMP(3)
            """)
    int wrong(@Param("id") String id, @Param("userId") String userId,
              @Param("questionId") String questionId, @Param("answerId") String answerId,
              @Param("cause") String cause);

    @Update("UPDATE user_mistake SET resolved=1,last_answer_id=#{answerId},updated_at=UTC_TIMESTAMP(3) WHERE user_id=#{userId} AND question_id=#{questionId}")
    int resolve(@Param("userId") String userId, @Param("questionId") String questionId, @Param("answerId") String answerId);

    @Select("SELECT * FROM user_mistake WHERE id=#{id} AND user_id=#{userId}")
    Map<String,Object> mistake(@Param("id") String id, @Param("userId") String userId);

    @Select("""
            <script>
            SELECT * FROM user_mistake WHERE user_id=#{userId}
            <if test="resolved != null">AND resolved=#{resolved}</if>
            ORDER BY updated_at DESC,id DESC LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<Map<String,Object>> mistakes(@Param("userId") String userId, @Param("resolved") Boolean resolved,
                                      @Param("size") int size, @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM user_mistake WHERE user_id=#{userId}
            <if test="resolved != null">AND resolved=#{resolved}</if>
            </script>
            """)
    long mistakeCount(@Param("userId") String userId, @Param("resolved") Boolean resolved);

    @Select("""
            SELECT COUNT(*) FROM learning_plan_item i JOIN learning_plan p ON p.id=i.plan_id
            WHERE p.user_id=#{userId} AND i.status='COMPLETED'
            """)
    int completedItemCount(@Param("userId") String userId);

    @Select("SELECT COUNT(*) FROM learning_answer WHERE user_id=#{userId} AND status='EVALUATED'")
    int evaluatedAnswerCount(@Param("userId") String userId);

    @Select("""
            SELECT AVG(CAST(JSON_UNQUOTE(JSON_EXTRACT(evaluation_json,'$.overallScore')) AS DECIMAL(6,1)))
            FROM learning_answer WHERE user_id=#{userId} AND status='EVALUATED'
              AND JSON_EXTRACT(evaluation_json,'$.overallScore') IS NOT NULL
            """)
    Double averageEvaluationScore(@Param("userId") String userId);

    // ===== 整组训练（GROUP 会话 / 批量出题与交卷）=====

    @Insert("""
            INSERT INTO learning_session (id,user_id,plan_id,plan_item_id,session_kind,config_json,total_questions,status)
            VALUES (#{id},#{userId},#{planId},#{planItemId},'GROUP',#{configJson},#{totalQuestions},'PREPARING')
            """)
    int insertGroupSession(@Param("id") String id, @Param("userId") String userId, @Param("planId") String planId,
                           @Param("planItemId") String planItemId, @Param("configJson") String configJson,
                           @Param("totalQuestions") int totalQuestions);

    @Insert("""
            INSERT INTO learning_session_question (id,session_id,question_id,question_version,seq,score)
            VALUES (#{id},#{sessionId},#{questionId},#{questionVersion},#{seq},#{score})
            """)
    int insertSessionQuestion(@Param("id") String id, @Param("sessionId") String sessionId,
                              @Param("questionId") String questionId, @Param("questionVersion") int questionVersion,
                              @Param("seq") int seq, @Param("score") int score);

    @Select("""
            SELECT sq.seq AS seq, sq.score AS session_score,
                   q.id AS id, q.stem AS stem, q.type AS type, q.options_json AS options_json,
                   q.answer_key_json AS answer_key_json, q.blank_count AS blank_count,
                   q.skill_codes_json AS skill_codes_json, q.difficulty AS difficulty,
                   q.reference_answer AS reference_answer, q.rubric_json AS rubric_json,
                   q.question_version AS question_version, q.source_type AS source_type,
                   q.citations_json AS citations_json, q.generation_env AS generation_env
            FROM learning_session_question sq
            JOIN question q ON q.id=sq.question_id
            WHERE sq.session_id=#{sessionId} ORDER BY sq.seq
            """)
    List<Map<String,Object>> sessionQuestions(@Param("sessionId") String sessionId);

    /** 全部题目落库后把 GROUP 会话置为 ACTIVE；题目数量不匹配时影响行数为 0。 */
    @Update("""
            UPDATE learning_session SET status='ACTIVE',version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND status='PREPARING'
              AND (SELECT COUNT(*) FROM learning_session_question WHERE session_id=#{id})=#{totalQuestions}
            """)
    int groupQuestionsReady(@Param("id") String id, @Param("totalQuestions") int totalQuestions);

    @Insert("""
            INSERT INTO question (id,root_question_id,question_version,stem,options_json,answer_key_json,blank_count,
              score,type,skill_codes_json,difficulty,source_type,reference_answer,rubric_json,citations_json,
              generated_task_id,created_by,generation_env)
            VALUES (#{id},#{id},1,#{stem},#{optionsJson,jdbcType=VARCHAR},#{answerKeyJson,jdbcType=VARCHAR},
              #{blankCount},#{score},#{type},#{skillCodesJson},#{difficulty},'GENERATED',#{referenceAnswer},
              #{rubricJson},#{citationsJson},#{taskId},#{userId},#{generationEnv})
            """)
    int insertGeneratedPracticeQuestion(@Param("id") String id, @Param("stem") String stem,
                                        @Param("optionsJson") String optionsJson,
                                        @Param("answerKeyJson") String answerKeyJson,
                                        @Param("blankCount") int blankCount, @Param("score") int score,
                                        @Param("type") String type, @Param("skillCodesJson") String skillCodesJson,
                                        @Param("difficulty") String difficulty,
                                        @Param("referenceAnswer") String referenceAnswer,
                                        @Param("rubricJson") String rubricJson,
                                        @Param("citationsJson") String citationsJson,
                                        @Param("taskId") String taskId, @Param("userId") String userId,
                                        @Param("generationEnv") String generationEnv);

    @Insert("""
            INSERT INTO learning_answer (id,session_id,question_id,question_version,seq,user_id,content,answer_json,
              content_hash,task_id,status)
            VALUES (#{id},#{sessionId},#{questionId},#{questionVersion},#{seq},#{userId},#{content},
              #{answerJson,jdbcType=VARCHAR},UNHEX(#{contentHash}),#{taskId},'EVALUATING')
            """)
    int insertPracticeAnswer(@Param("id") String id, @Param("sessionId") String sessionId,
                             @Param("questionId") String questionId, @Param("questionVersion") int questionVersion,
                             @Param("seq") int seq, @Param("userId") String userId, @Param("content") String content,
                             @Param("answerJson") String answerJson, @Param("contentHash") String contentHash,
                             @Param("taskId") String taskId);

    @Select("SELECT * FROM learning_answer WHERE session_id=#{sessionId} ORDER BY seq")
    List<Map<String,Object>> sessionAnswers(@Param("sessionId") String sessionId);

    @Update("""
            UPDATE learning_answer SET objective_correct=#{correct},score=#{score},status='EVALUATED',
              evaluated_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='EVALUATING'
            """)
    int objectiveEvaluated(@Param("id") String id, @Param("correct") boolean correct,
                           @Param("score") java.math.BigDecimal score);

    @Update("""
            UPDATE learning_answer SET status='EVALUATED',evaluation_json=#{evaluationJson},score=#{score},
              evaluated_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='EVALUATING'
            """)
    int practiceAnswerEvaluated(@Param("id") String id, @Param("evaluationJson") String evaluationJson,
                                @Param("score") java.math.BigDecimal score);

    @Update("""
            UPDATE learning_session SET status='EVALUATING',evaluation_task_id=#{taskId},duration_seconds=#{duration},
              submitted_at=UTC_TIMESTAMP(3),last_answer_id=#{lastAnswerId},version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND status='ACTIVE'
            """)
    int practiceSubmitted(@Param("id") String id, @Param("taskId") String taskId, @Param("duration") int duration,
                          @Param("lastAnswerId") String lastAnswerId);

    @Update("""
            UPDATE learning_session SET evaluation_task_id=#{taskId},version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND status='EVALUATING' AND session_kind='GROUP'
            """)
    int practiceEvaluationRetried(@Param("id") String id, @Param("taskId") String taskId);

    @Update("""
            UPDATE learning_session SET status='COMPLETED',result_json=#{resultJson},version=version+1,
              updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='EVALUATING'
            """)
    int practiceCompleted(@Param("id") String id, @Param("resultJson") String resultJson);

    @Select("""
            SELECT sq.question_id FROM learning_session_question sq
            JOIN learning_session s ON s.id=sq.session_id WHERE s.user_id=#{userId}
            """)
    List<String> usedSessionQuestionIds(@Param("userId") String userId);

    @Select("""
            SELECT q.stem FROM learning_session_question sq
            JOIN learning_session s ON s.id=sq.session_id
            JOIN question q ON q.id=sq.question_id WHERE s.user_id=#{userId}
            """)
    List<String> usedSessionQuestionStems(@Param("userId") String userId);

    @Select("SELECT COUNT(*) FROM user_mistake WHERE user_id=#{userId} AND resolved=0")
    int openMistakeCount(@Param("userId") String userId);

    @Select("""
            SELECT q.skill_codes_json FROM user_mistake m JOIN question q ON q.id=m.question_id
            WHERE m.user_id=#{userId} AND m.resolved=0 ORDER BY m.updated_at DESC,m.id DESC LIMIT 10
            """)
    List<String> openMistakeSkillJson(@Param("userId") String userId);
}
