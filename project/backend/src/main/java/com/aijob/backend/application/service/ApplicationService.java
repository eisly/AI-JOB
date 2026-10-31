package com.aijob.backend.application.service;

import com.aijob.backend.application.ApplicationSummaryVO;
import com.aijob.backend.application.ApplicationVO;
import com.aijob.backend.application.dto.ApplicationCreateDTO;
import com.aijob.backend.application.dto.ApplicationUpdateDTO;
import com.aijob.backend.common.PageResult;

/**
 * 投递进度服务：所有方法以调用方传入的当前认证用户 ID 隔离数据。
 */
public interface ApplicationService {

    /** 新增投递记录；提供 jobId 时校验岗位可见性（无权或不存在返回 404）。 */
    ApplicationVO create(String userId, ApplicationCreateDTO dto);

    /** 分页查询当前用户投递记录，可按状态筛选；current 从 1 开始，size 1..100。 */
    PageResult<ApplicationVO> list(String userId, String status, Integer current, Integer size);

    /** 查询详情；不存在或属于其他用户统一返回 404。 */
    ApplicationVO detail(String userId, String id);

    /** 部分更新（三态语义）+ 乐观锁；版本不一致返回 409。 */
    ApplicationVO update(String userId, String id, ApplicationUpdateDTO dto);

    /** 物理删除 + 乐观锁；版本不一致返回 409。 */
    boolean delete(String userId, String id, int expectedVersion);

    /** 按状态分组统计（四状态补零）。 */
    ApplicationSummaryVO summary(String userId);
}