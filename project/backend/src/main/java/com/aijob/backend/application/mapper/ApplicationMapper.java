package com.aijob.backend.application.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.aijob.backend.application.entity.Application;

/**
 * application_record Mapper（注解式，风格对齐 AppUserMapper）。
 * id 由应用生成 UUIDv4 小写；created_at/updated_at 使用数据库默认值。
 * 所有业务查询均以 user_id 过滤实现数据隔离。
 */
@Mapper
public interface ApplicationMapper {

    @Insert("""
            INSERT INTO application_record
              (id, user_id, job_id, company_name, job_title, city_code, education,
               salary_text, description, status, planned_date, applied_date,
               interview_date, closed_date, note, version)
            VALUES
              (#{id}, #{userId}, #{jobId}, #{companyName}, #{jobTitle}, #{cityCode}, #{education},
               #{salaryText}, #{description}, #{status}, #{plannedDate}, #{appliedDate},
               #{interviewDate}, #{closedDate}, #{note}, 1)
            """)
    int insert(Application application);

    @Select("""
            SELECT ar.id, ar.user_id, ar.job_id, ar.company_name, ar.job_title, ar.city_code, ar.education,
                   ar.salary_text, ar.description, ar.status, ar.planned_date, ar.applied_date,
                   ar.interview_date, ar.closed_date, ar.note, ar.version, ar.created_at, ar.updated_at,
                   s.type AS source_type, s.name AS source_name
            FROM application_record ar
            LEFT JOIN job j ON j.id = ar.job_id
            LEFT JOIN job_source s ON s.id = j.source_id
            WHERE ar.id = #{id} AND ar.user_id = #{userId}
            """)
    Application selectByIdAndUserId(@Param("id") String id, @Param("userId") String userId);

    /**
     * 锁定读取（MySQL InnoDB）：用于修改/删除接口的乐观锁事务，
     * 统一事务边界内锁定→校验归属与版本→条件写→检查影响行数。
     */
    @Select("""
            SELECT id, user_id, job_id, company_name, job_title, city_code, education,
                   salary_text, description, status, planned_date, applied_date,
                   interview_date, closed_date, note, version, created_at, updated_at
            FROM application_record
            WHERE id = #{id} AND user_id = #{userId}
            FOR UPDATE
            """)
    Application selectByIdAndUserIdForUpdate(@Param("id") String id, @Param("userId") String userId);

    /** 统计同一用户对同一岗位的既有投递记录数（含全部状态），用于重复添加提示。 */
    @Select("""
            SELECT COUNT(*) FROM application_record
            WHERE user_id = #{userId} AND job_id = #{jobId}
            """)
    int countByUserIdAndJobId(@Param("userId") String userId, @Param("jobId") String jobId);

    /**
     * 锁定用户行（MySQL InnoDB FOR UPDATE）：用于新增投递的事务串行化。
     * 同一用户的并发创建在"锁用户行 → 重复检查 → 插入"事务内串行执行，
     * 后到的请求能读到先到请求已插入的记录并返回 409，防止并发静默制造重复（P01）。
     */
    @Select("SELECT id FROM app_user WHERE id = #{userId} FOR UPDATE")
    String lockUser(@Param("userId") String userId);

    @Select("""
            <script>
            SELECT ar.id, ar.user_id, ar.job_id, ar.company_name, ar.job_title, ar.city_code, ar.education,
                   ar.salary_text, ar.description, ar.status, ar.planned_date, ar.applied_date,
                   ar.interview_date, ar.closed_date, ar.note, ar.version, ar.created_at, ar.updated_at,
                   s.type AS source_type, s.name AS source_name
            FROM application_record ar
            LEFT JOIN job j ON j.id = ar.job_id
            LEFT JOIN job_source s ON s.id = j.source_id
            WHERE ar.user_id = #{userId}
            <if test="status != null">AND ar.status = #{status}</if>
            ORDER BY ar.created_at DESC, ar.id DESC
            LIMIT #{offset}, #{size}
            </script>
            """)
    List<Application> selectPage(@Param("userId") String userId,
                                 @Param("status") String status,
                                 @Param("offset") long offset,
                                 @Param("size") int size);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM application_record
            WHERE user_id = #{userId}
            <if test="status != null">AND status = #{status}</if>
            </script>
            """)
    long countByUserIdAndStatus(@Param("userId") String userId, @Param("status") String status);

    /**
     * 乐观锁条件更新：目标值由 Service 按三态语义解析后全量传入
     * （UNDEFINED=写回原值、NULL=写 null、VALUE=新值）。
     * 带版本条件的更新并检查影响行数，0 行由调用方按 409 处理。
     */
    @Update("""
            UPDATE application_record
            SET company_name = #{target.companyName},
                job_title = #{target.jobTitle},
                city_code = #{target.cityCode},
                education = #{target.education},
                salary_text = #{target.salaryText},
                description = #{target.description},
                status = #{target.status},
                planned_date = #{target.plannedDate},
                applied_date = #{target.appliedDate},
                interview_date = #{target.interviewDate},
                closed_date = #{target.closedDate},
                note = #{target.note},
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{target.id}
              AND user_id = #{target.userId}
              AND version = #{expectedVersion}
            """)
    int updateByIdUserIdVersion(@Param("target") Application target, @Param("expectedVersion") int expectedVersion);

    /**
     * 乐观锁条件删除（物理删除）：带 id + user_id + version 条件并检查影响行数。
     */
    @Delete("""
            DELETE FROM application_record
            WHERE id = #{id} AND user_id = #{userId} AND version = #{expectedVersion}
            """)
    int deleteByIdUserIdVersion(@Param("id") String id,
                                @Param("userId") String userId,
                                @Param("expectedVersion") int expectedVersion);

    @Select("""
            SELECT status, COUNT(*) AS cnt
            FROM application_record
            WHERE user_id = #{userId}
            GROUP BY status
            """)
    List<Map<String, Object>> countGroupByStatus(@Param("userId") String userId);

    /**
     * 岗位可见性校验（只读，过渡方案）：
     * 判定规则依据数据说明 §5.10 —— PUBLIC 要求 owner 为 NULL 且来源 VERIFIED；
     * PRIVATE 要求 owner 为当前用户。PUBLIC 额外要求 status=ACTIVE 属于本模块的保守假设，
     * 待与岗位模块负责人对齐结论后调整（协调项，见 tasks.md 任务 1.2）。
     * 岗位模块提供统一校验服务后应切换，本查询仅为最小依赖实现。
     */
    @Select("""
            SELECT COUNT(*)
            FROM job j
            JOIN job_source s ON s.id = j.source_id
            WHERE j.id = #{jobId}
              AND (
                (j.visibility = 'PUBLIC' AND j.owner_user_id IS NULL
                  AND s.verification_status = 'VERIFIED' AND j.status = 'ACTIVE')
                OR
                (j.visibility = 'PRIVATE' AND j.owner_user_id = #{userId})
              )
            """)
    int countVisibleJob(@Param("jobId") String jobId, @Param("userId") String userId);
}