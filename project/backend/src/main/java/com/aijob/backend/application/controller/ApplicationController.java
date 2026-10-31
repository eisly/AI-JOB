package com.aijob.backend.application.controller;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aijob.backend.application.ApplicationSummaryVO;
import com.aijob.backend.application.ApplicationVO;
import com.aijob.backend.application.dto.ApplicationCreateDTO;
import com.aijob.backend.application.dto.ApplicationUpdateDTO;
import com.aijob.backend.application.service.ApplicationService;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.CurrentUserResolver;
import com.aijob.backend.common.PageResult;
import com.aijob.backend.common.TraceIdFilter;

import jakarta.validation.Valid;

/**
 * 投递进度接口（接口文档 11-投递进度）：
 * POST   /api/v1/applications            新增投递记录
 * GET    /api/v1/applications            分页查询与状态筛选
 * GET    /api/v1/applications/summary    按状态统计数量（精确路径优先于 /{id}）
 * GET    /api/v1/applications/{id}       查询详情
 * PATCH  /api/v1/applications/{id}       修改记录及状态（三态 + 乐观锁）
 * DELETE /api/v1/applications/{id}       删除记录（乐观锁，expectedVersion 必填）
 */
@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {

    private final ApplicationService applicationService;
    private final CurrentUserResolver currentUserResolver;

    public ApplicationController(ApplicationService applicationService, CurrentUserResolver currentUserResolver) {
        this.applicationService = applicationService;
        this.currentUserResolver = currentUserResolver;
    }

    @PostMapping
    public CommonResult<ApplicationVO> create(@Valid @RequestBody ApplicationCreateDTO dto) {
        String userId = currentUserResolver.resolveCurrentUserId();
        return ok(applicationService.create(userId, dto));
    }

    @GetMapping
    public CommonResult<PageResult<ApplicationVO>> list(@RequestParam(value = "status", required = false) String status,
                                                         @RequestParam(value = "current", required = false) Integer current,
                                                         @RequestParam(value = "size", required = false) Integer size) {
        String userId = currentUserResolver.resolveCurrentUserId();
        return ok(applicationService.list(userId, status, current, size));
    }

    @GetMapping("/summary")
    public CommonResult<ApplicationSummaryVO> summary() {
        String userId = currentUserResolver.resolveCurrentUserId();
        return ok(applicationService.summary(userId));
    }

    @GetMapping("/{id}")
    public CommonResult<ApplicationVO> detail(@PathVariable("id") String id) {
        String userId = currentUserResolver.resolveCurrentUserId();
        return ok(applicationService.detail(userId, requireUuid(id)));
    }

    @PatchMapping("/{id}")
    public CommonResult<ApplicationVO> update(@PathVariable("id") String id,
                                              @Valid @RequestBody ApplicationUpdateDTO dto) {
        String userId = currentUserResolver.resolveCurrentUserId();
        return ok(applicationService.update(userId, requireUuid(id), dto));
    }

    @DeleteMapping("/{id}")
    public CommonResult<Boolean> delete(@PathVariable("id") String id,
                                         @RequestParam("expectedVersion") Integer expectedVersion) {
        if (expectedVersion == null || expectedVersion <= 0) {
            throw BusinessException.validation("expectedVersion", "expectedVersion 必须为正整数");
        }
        String userId = currentUserResolver.resolveCurrentUserId();
        return ok(applicationService.delete(userId, requireUuid(id), expectedVersion));
    }

    private String requireUuid(String id) {
        try {
            return UUID.fromString(id).toString().toLowerCase();
        } catch (IllegalArgumentException ex) {
            throw BusinessException.validation("id", "路径参数必须是合法 UUID");
        }
    }

    private <T> CommonResult<T> ok(T data) {
        CommonResult<T> result = CommonResult.success(data);
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_KEY);
        result.withTraceId(traceId != null ? traceId : UUID.randomUUID().toString());
        return result;
    }
}