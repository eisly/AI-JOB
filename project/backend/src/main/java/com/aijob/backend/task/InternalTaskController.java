package com.aijob.backend.task;

import java.util.Map;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.Data;
import com.aijob.backend.common.Responses;

@RestController
@RequestMapping("/internal/business/v1/ai-tasks")
public class InternalTaskController {

    private final InternalJwtService jwtService;
    private final TaskResultService results;

    public InternalTaskController(InternalJwtService jwtService, TaskResultService results) {
        this.jwtService = jwtService;
        this.results = results;
    }

    @PostMapping("/{taskId}/result")
    public CommonResult<Map<String, Object>> result(@PathVariable String taskId,
                                                     @RequestHeader(value = "Authorization", required = false) String authorization,
                                                     @RequestBody Map<String, Object> request) {
        String id = Data.uuid(taskId, "taskId");
        jwtService.verifyCallback(authorization, id);
        return Responses.ok(results.submit(id, request));
    }
}