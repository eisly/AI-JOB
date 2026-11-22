package com.aijob.backend.interview;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import com.aijob.backend.ability.AbilityService;
import com.aijob.backend.common.*;
import com.aijob.backend.profile.ProfileService;
import com.aijob.backend.task.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties={
 "spring.datasource.url=${BUDGET_TEST_DB_URL:jdbc:mysql://127.0.0.1:3307/career_business_test?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true}",
 "app.agent.scheduler-enabled=false"})
@EnabledIfSystemProperty(named="integration-tests",matches="true")
class InterviewReportRetryIntegrationTest {
 @Autowired JdbcTemplate jdbc;
 @Autowired InterviewMapper mapper;
 @Autowired InterviewResultHandler handler;
 @Autowired InterviewService interviews;
 @Autowired TaskMapper taskMapper;
 @Autowired TaskService tasks;
 @Autowired TaskRetryService retries;
 @Autowired JsonService json;
 @Autowired ProfileService profiles;
 @Autowired PlatformTransactionManager manager;
 TransactionTemplate tx;
 TaskResultService results;
 String session,user;
 AiTask original;
 String id(){return UUID.randomUUID().toString();}
    private String seed(JdbcTemplate db, String rule) {
        String user = id(), resume = id(), document = id(), source = id(), job = id(), session = id();
        db.update("INSERT INTO app_user(id,email,password_hash,nickname) VALUES (?,?,?,'预算隔离测试')", user, user+"@example.invalid", "{test}");
        db.update("INSERT INTO resume(id,user_id,title) VALUES (?,?,'隔离测试')", document, user);
        db.update("""
            INSERT INTO resume_version(id,resume_id,user_id,version_no,file_name,storage_key,mime_type,size_bytes,sha256,status)
            VALUES (?,?,?,1,'synthetic.txt','budget-only','text/plain',1,UNHEX(SHA2('synthetic',256)),'CONFIRMED')
            """, resume, document, user);
        db.update("INSERT INTO job_source(id,name,type) VALUES (?,'隔离预算','USER_INPUT')", source);
        db.update("INSERT INTO job(id,source_id,owner_user_id,visibility,status) VALUES (?,?,?,'PRIVATE','ACTIVE')",job,source,user);
        db.update("""
            INSERT INTO job_version(id,job_id,version_no,title,company_name,candidate_type,jd_text,content_hash)
            VALUES (?,?,1,'Java','虚构测试','EXPERIENCED','Java',UNHEX(SHA2('Java',256)))
            """,id(),job);
        db.update("""
            INSERT INTO interview_session(id,user_id,job_id,job_version,resume_version_id,profile_version,
                input_snapshot_json,status,question_limit,current_question_no,current_follow_up_no,last_seq)
            VALUES (?,?,?,1,?,1,'{}','GENERATING',5,2,0,4)
            """,session,user,job,resume);
        if (rule != null) db.update("UPDATE interview_session SET question_count_rule=? WHERE id=?",rule,session);
        return session;
    }


 Map<String,Object> evaluation(){
  return Map.of("evidenceStatus","VALID","rubricVersion",AbilityService.RUBRIC,"modelVersion","fixture","promptVersion","fixture",
   "dimensions",List.of(Map.of("key","correctness","score",80,"weight",0.5),Map.of("key","completeness","score",80,"weight",0.3),Map.of("key","expression","score",80,"weight",0.2)));
 }
 AiTask task(String operation,String type,String status,Map<String,Object> payload){
  String tid=id();
  jdbc.update("""
   INSERT INTO ai_task(id,user_id,operation,resource_type,resource_id,status,idempotency_key,input_versions_json,input_snapshot_json,agent_run_id,deadline_at)
   VALUES (?,?,?,?,?,?,?,'{}',?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))
   """,tid,user,operation,type,session,status,id(),json.stringify(payload),id());
  return taskMapper.find(tid);
 }
 @BeforeEach void fixture() throws Exception {
  try(var c=jdbc.getDataSource().getConnection()){assertEquals("career_business_test",c.getCatalog());}
  tx=new TransactionTemplate(manager);
  results=new TaskResultService(taskMapper,tasks,json,mock(AgentEventSyncService.class),List.of(handler));
  session=seed(jdbc,"TOTAL_ASKED");user=jdbc.queryForObject("SELECT user_id FROM interview_session WHERE id=?",String.class,session);profiles.get(user);
  for(int n=1;n<=5;n++){
   String q=id(),a=id();int main=n<=3?1:2,follow=n<=3?n-1:n-4;
   AiTask turn=task("INTERVIEW_TURN","INTERVIEW","SUCCEEDED",Map.of());
   mapper.insertMessage(q,session,2*n-1,"ASSISTANT",null,"虚构测试问题",main,follow,null,"[]",null);
   mapper.insertMessage(a,session,2*n,"USER","TEXT","虚构测试回答",main,follow,q,"[]",null);
   mapper.insertEvaluation(id(),session,a,turn.getId(),json.stringify(evaluation()),AbilityService.RUBRIC,80);
  }
  original=task("INTERVIEW_REPORT","INTERVIEW_REPORT","RUNNING",Map.of("completionType","NORMAL","questionCountRule","TOTAL_ASKED","askedQuestionCount",5));
  mapper.updateTurn(session,"ENDING",original.getId(),2,1,10);
 }
 Map<String,Object> success(AiTask task){return Map.of("runId",task.getAgentRunId(),"operation","INTERVIEW_REPORT","status","SUCCEEDED","inputVersions",Map.of(),"result",Map.of("evaluation",evaluation(),"gaps",List.of()));}
 Map<String,Object> failure(AiTask task,String code){return Map.of("runId",task.getAgentRunId(),"operation","INTERVIEW_REPORT","status","FAILED","inputVersions",Map.of(),"error",Map.of("code",code,"message","测试故障","retryable",true));}
 void fail(String code){tx.execute(s->results.submit(original.getId(),failure(original,code)));}
 AiTask retry(String key){
  retries.retry(user,original.getId(),key,"/api/v1/tasks/"+original.getId()+"/retry");
  return taskMapper.find(jdbc.queryForObject("SELECT id FROM ai_task WHERE retry_of_task_id=?",String.class,original.getId()));
 }
 void complete(AiTask task){
  // Real dispatch normally binds the run before callback; do that explicitly in this controlled test.
  if(task.getAgentRunId()==null){taskMapper.bindRun(task.getId(),id());task=taskMapper.find(task.getId());}
  AiTask current=task;tx.execute(s->results.submit(current.getId(),success(current)));
 }
 void assertCounts(int reports){
  assertEquals(5,mapper.askedQuestions(session));
  assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM interview_message WHERE interview_id=? AND role='USER'",Integer.class,session));
  assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM interview_turn_evaluation WHERE interview_id=?",Integer.class,session));
  assertEquals(reports,jdbc.queryForObject("SELECT COUNT(*) FROM interview_report WHERE interview_id=?",Integer.class,session));
 }
 @ParameterizedTest @ValueSource(strings={"UPSTREAM_TIMEOUT","MODEL_UNAVAILABLE","RATE_LIMITED"})
 void reportFailureRetainsAllAnswersAndRetriesOriginalSnapshot(String code){
  fail(code);assertCounts(0);assertEquals("WAITING_ANSWER",mapper.session(session,user).get("status"));
  AiTask replacement=retry(id());
  assertEquals(original.getInputSnapshotJson(),replacement.getInputSnapshotJson());
  assertEquals(original.getInputVersionsJson(),replacement.getInputVersionsJson());
  assertEquals(replacement.getId(),mapper.session(session,user).get("pending_task_id"));
  complete(replacement);assertCounts(1);
  Map<String,Object> report=interviews.report(user,session);
  assertEquals(5,report.get("answeredAskCount"));assertEquals(2,report.get("answeredQuestionCount"));assertEquals("NORMAL",report.get("completionType"));
 }
 @Test void expiredReportCanRetryWithoutAnotherAnswer(){
  jdbc.update("UPDATE ai_task SET deadline_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=?",original.getId());
  tx.executeWithoutResult(s->results.expire(original.getId()));
  assertEquals("EXPIRED",taskMapper.find(original.getId()).getStatus());assertCounts(0);
  complete(retry(id()));assertCounts(1);
 }
 @Test void retrySameKeyReplaysAndDifferentKeyCannotCreateAnotherTask(){
  fail("UPSTREAM_TIMEOUT");String key=id();AiTask replacement=retry(key);
  long version=((Number)mapper.session(session,user).get("version")).longValue();
  assertEquals(replacement.getId(),retry(key).getId());
  assertThrows(BusinessException.class,()->retry(id()));
  assertEquals(version,((Number)mapper.session(session,user).get("version")).longValue());
  complete(replacement);assertCounts(1);assertThrows(BusinessException.class,()->retry(id()));
 }
 @Test void concurrentRetryCreatesOneReplacement() throws Exception {
  fail("MODEL_UNAVAILABLE");CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)){
   Callable<Boolean> action=()->{ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));try{retry(id());return true;}catch(BusinessException e){assertEquals("TASK_IN_PROGRESS",e.getErrorKey());return false;}};
   var a=pool.submit(action);var b=pool.submit(action);assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
  }finally{start.countDown();}
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE retry_of_task_id=?",Integer.class,original.getId()));assertCounts(0);
 }
 @Test void duplicateSuccessIsIdempotentAndDifferentResultConflicts(){
  complete(original);assertCounts(1);
  assertEquals(true,tx.execute(s->results.submit(original.getId(),success(original))).get("accepted"));
  Map<String,Object> changed=new LinkedHashMap<>(success(original));changed.put("result",Map.of("evaluation",evaluation(),"gaps",List.of(Map.of("title","不同结果"))));
  assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(BusinessException.class,()->tx.execute(s->results.submit(original.getId(),changed))).getErrorKey());assertCounts(1);
 }
 @Test void oldFailureSuccessAndRetryCannotOverwriteReplacementOrCompletedReport(){
  fail("UPSTREAM_TIMEOUT");AiTask replacement=retry(id());
  Map<String,Object> before=mapper.session(session,user);
  tx.executeWithoutResult(s->handler.failure(original,json.tree(Map.of())));
  assertEquals(before,mapper.session(session,user));
  assertThrows(BusinessException.class,()->tx.execute(s->handler.success(original,json.tree(Map.of("evaluation",evaluation())))));
  assertThrows(BusinessException.class,()->tx.executeWithoutResult(s->handler.prepareRetry(original,id())));
  assertEquals(true,tx.execute(s->results.submit(original.getId(),failure(original,"UPSTREAM_TIMEOUT"))).get("accepted"));
  assertThrows(BusinessException.class,()->tx.execute(s->results.submit(original.getId(),success(original))));
  assertEquals(before,mapper.session(session,user));complete(replacement);
  Map<String,Object> completed=mapper.session(session,user);
  tx.executeWithoutResult(s->handler.failure(original,json.tree(Map.of())));
  assertEquals(completed,mapper.session(session,user));assertCounts(1);
 }
 @Test void refreshWhileEndingAndAfterCompletionDoesNotCreateReportOrQuestion(){
  Map<String,Object> before=mapper.session(session,user);
  for(int i=0;i<3;i++){
   assertEquals("ENDING",interviews.detail(user,session).get("status"));
   assertEquals("REPORT_NOT_READY",assertThrows(BusinessException.class,()->interviews.report(user,session)).getErrorKey());
  }
  assertEquals(before,mapper.session(session,user));assertCounts(0);complete(original);
  interviews.report(user,session);interviews.report(user,session);assertCounts(1);
 }
 @Test void malformedReportRollsBackThenCanRecover(){
  Map<String,Object> malformed=new LinkedHashMap<>(success(original));malformed.put("result",Map.of("gaps",List.of()));
  assertThrows(BusinessException.class,()->tx.execute(s->results.submit(original.getId(),malformed)));
  assertEquals("RUNNING",taskMapper.find(original.getId()).getStatus());assertCounts(0);
  fail("AI_SCHEMA_INVALID");complete(retry(id()));assertCounts(1);
 }
}
